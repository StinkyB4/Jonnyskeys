package com.jonnyskeys.keytap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Intercepts hardware (USB) keyboard events system-wide and converts the mapped
 * key into an injected touch at a configurable screen position, behaving like a
 * real finger: key down = touch down, key held = touch held, key up = touch up.
 *
 * Hard-won reliability rules (the game ignores touch-CANCEL events, so a
 * cancelled injection leaves it believing the finger is still down):
 *
 * 1. Never dispatch a gesture while another is in flight — interrupting an
 *    injected gesture makes Android emit ACTION_CANCEL instead of ACTION_UP.
 *    The hold is chained from short segments, and both the next segment and
 *    the final lift are dispatched only from onCompleted, the one point where
 *    continuation is guaranteed to splice without a cancel.
 * 2. If our gesture is cancelled anyway (e.g. the screen was touched during
 *    play) while the key is still held, re-plant the touch: the new pointer
 *    reuses the same pointer id, which overwrites the game's stale "held"
 *    state and keeps the player's input alive.
 * 3. If the gesture was cancelled when the finger should be lifting, dispatch
 *    a rescue tap: a complete down+up with the same pointer id, which clears
 *    the game's phantom held finger.
 * 4. If the chain goes silent entirely (no callback within a deadline after a
 *    lift was requested), assume it is dead and fire the rescue tap.
 * 5. A watchdog force-releases if the key-UP event itself is lost, using the
 *    keyboard's auto-repeat as a "still physically held" heartbeat.
 */
class KeyTapService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyTapService"

        // Hold segment length. The lift can only happen at a segment boundary
        // (rule 1), so this is also the worst-case release latency.
        private const val HOLD_SEGMENT_MS = 50L
        private const val RELEASE_SEGMENT_MS = 1L

        // If no gesture callback arrives within this window after a lift was
        // requested, the chain is presumed dead and a rescue tap is fired.
        private const val RESCUE_TIMEOUT_MS = 250L

        // Watchdog windows for a lost key-UP. The initial window outlasts the
        // OS auto-repeat delay (~500ms) so real holds aren't cut short before
        // the first repeat arrives; once repeats flow, the window tightens.
        private const val WATCHDOG_INITIAL_MS = 700L
        private const val WATCHDOG_REPEAT_MS = 300L

        /** Set while the service is connected, so the UI can show live status. */
        @Volatile
        var running = false
            private set
    }

    /**
     * Injection pipeline state. Everything is strictly serialized: a new
     * gesture is only ever dispatched when the previous one has finished
     * (completed or cancelled), because dispatching over an in-flight gesture
     * turns its ending into an ACTION_CANCEL that the game ignores.
     */
    private enum class State {
        /** No injected touch on screen. */
        IDLE,

        /** Finger is down; hold segments are being chained. */
        HOLDING,

        /** The finishing (touch-up) segment is in flight. */
        LIFTING,
    }

    private val handler = Handler(Looper.getMainLooper())

    private var state = State.IDLE

    /** The stroke currently on screen, needed to continue or finish the hold. */
    private var activeStroke: GestureDescription.StrokeDescription? = null

    /** True from key-down until key-up (or watchdog release). */
    private var keyHeld = false

    private var tapX = 0f
    private var tapY = 0f

    /** Fires only if key-UP was never delivered: lift the finger anyway. */
    private val watchdog = Runnable {
        Log.w(TAG, "Key-up not received; forcing release")
        requestRelease()
    }

    /** Fires only if the gesture chain went silent after a lift was requested. */
    private val rescue = Runnable {
        Log.w(TAG, "Gesture chain stalled; dispatching rescue tap")
        activeStroke = null
        state = State.IDLE
        dispatchRescueTap()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        Log.i(TAG, "Service connected")
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(rescue)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used; this service only cares about key events.
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!Prefs.enabled(this)) return false
        if (event.keyCode != Prefs.keyCode(this)) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    if (!keyHeld) {
                        keyHeld = true
                        armWatchdog(WATCHDOG_INITIAL_MS)
                        when (state) {
                            // Nothing on screen: plant the finger now.
                            State.IDLE -> touchDown()
                            // Previous press's stroke is still on screen and
                            // its lift hasn't been dispatched yet: just keep
                            // holding it — the chain sees keyHeld again.
                            State.HOLDING -> handler.removeCallbacks(rescue)
                            // The lift is in flight. Do NOT dispatch over it
                            // (that would cancel it and lose its touch-up);
                            // the lift's onCompleted sees keyHeld and plants
                            // the new press immediately after.
                            State.LIFTING -> {}
                        }
                    }
                } else {
                    // Auto-repeat: proof the key is still physically held.
                    armWatchdog(WATCHDOG_REPEAT_MS)
                }
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(watchdog)
                requestRelease()
            }
        }
        // Consume the event so the key doesn't also reach the foreground app.
        return true
    }

    private fun armWatchdog(delayMs: Long) {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, delayMs)
    }

    /**
     * Ask for the finger to lift at the next segment boundary (rule 1: never
     * interrupt an in-flight gesture). A deadline timer backs this up in case
     * the chain never calls back.
     */
    private fun requestRelease() {
        if (!keyHeld) return
        keyHeld = false
        if (state == State.HOLDING) {
            handler.removeCallbacks(rescue)
            handler.postDelayed(rescue, RESCUE_TIMEOUT_MS)
        }
    }

    private fun touchDown() {
        computeTapPoint()
        val path = Path().apply { moveTo(tapX, tapY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, HOLD_SEGMENT_MS, true)
        activeStroke = stroke
        state = State.HOLDING
        dispatch(stroke, holdCallback)
    }

    private val holdCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            when (state) {
                State.HOLDING -> {
                    val stroke = activeStroke ?: return
                    val path = Path().apply { moveTo(tapX, tapY) }
                    if (keyHeld) {
                        // Key still down: splice on the next hold segment.
                        val next = stroke.continueStroke(path, 0, HOLD_SEGMENT_MS, true)
                        activeStroke = next
                        dispatch(next, this)
                    } else {
                        // Key released: splice on the finishing segment,
                        // which ends with a genuine ACTION_UP. Keep the
                        // rescue deadline armed until the touch-up is
                        // confirmed by the lift's own callback.
                        activeStroke = null
                        state = State.LIFTING
                        val end = stroke.continueStroke(path, 0, RELEASE_SEGMENT_MS, false)
                        dispatch(end, this)
                        handler.removeCallbacks(rescue)
                        handler.postDelayed(rescue, RESCUE_TIMEOUT_MS)
                    }
                }
                State.LIFTING -> {
                    // The touch-up made it to the screen.
                    handler.removeCallbacks(rescue)
                    state = State.IDLE
                    // If the key was pressed again while the lift was in
                    // flight, the press was deferred to here: plant it now.
                    if (keyHeld) {
                        touchDown()
                    }
                }
                State.IDLE -> {}
            }
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            // Something cancelled our injection; the game may now believe the
            // finger is stuck down, because it ignores ACTION_CANCEL.
            handler.removeCallbacks(rescue)
            activeStroke = null
            state = State.IDLE
            if (keyHeld) {
                // Key still physically down: re-plant the touch. The fresh
                // pointer reuses the same id and supersedes the stale one.
                touchDown()
            } else {
                // We were holding or lifting and lost the pointer without a
                // proper touch-up: clear the phantom held finger with a
                // complete down+up tap.
                dispatchRescueTap()
            }
        }
    }

    /**
     * A complete, self-contained 1ms tap. Ends in a genuine ACTION_UP with the
     * same pointer id as everything else we inject, which overwrites and
     * clears any phantom "held finger" the game is tracking.
     */
    private fun dispatchRescueTap() {
        val path = Path().apply { moveTo(tapX, tapY) }
        val tap = GestureDescription.StrokeDescription(path, 0, RELEASE_SEGMENT_MS, false)
        // No callback: the rescue tap is fire-and-forget, so it can never
        // trigger further reactions or loops.
        dispatchGesture(GestureDescription.Builder().addStroke(tap).build(), null, null)
    }

    private fun dispatch(
        stroke: GestureDescription.StrokeDescription,
        callback: GestureResultCallback?
    ) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        if (!dispatchGesture(gesture, callback, null)) {
            Log.w(TAG, "dispatchGesture returned false")
            activeStroke = null
            keyHeld = false
            state = State.IDLE
            handler.removeCallbacks(rescue)
        }
    }

    private fun computeTapPoint() {
        val wm = getSystemService(WindowManager::class.java)
        val size = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(size)
        tapX = size.x * Prefs.tapXPercent(this) / 100f
        tapY = size.y * Prefs.tapYPercent(this) / 100f
    }
}
