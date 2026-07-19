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
 * key into an injected touch at a configurable screen position.
 *
 * Tap mode (default): key down -> one complete tap (down + up in a single
 * self-contained gesture). There is no separate release step, so the touch is
 * structurally incapable of getting stuck. Auto-repeat fires repeated taps.
 *
 * Hold mode (optional): key down -> touch down, key held -> touch stays down
 * (ship/wave/hold mechanics), key up -> touch up. Guarded by a watchdog that
 * uses the keyboard's auto-repeat as a "still held" heartbeat and
 * force-releases the touch if the key-UP event is ever lost.
 */
class KeyTapService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyTapService"

        // Tap mode: the whole tap (down + up) lives in one self-contained
        // gesture of this duration, so there is no separate release step that
        // could ever be missed. The game registers the press on touch-down.
        private const val TAP_MS = 40L

        // Hold mode: a gesture stroke needs a finite duration, so a "hold" is
        // built from short chained segments. Shorter segments = lower
        // worst-case latency between key release and touch-up.
        private const val HOLD_SEGMENT_MS = 24L
        private const val RELEASE_SEGMENT_MS = 1L

        // Hold-mode watchdogs. The keyboard's auto-repeat acts as a "still
        // held" heartbeat. The initial window must outlast the OS's repeat
        // delay (~500ms) so real holds aren't cut before the first repeat
        // arrives; after repeats start, the window tightens.
        private const val WATCHDOG_INITIAL_MS = 700L
        private const val WATCHDOG_REPEAT_MS = 300L

        /** Set while the service is connected, so the UI can show live status. */
        @Volatile
        var running = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    /** The stroke currently on screen, needed to continue or finish the hold. */
    private var activeStroke: GestureDescription.StrokeDescription? = null

    /** True from key-down until key-up (or watchdog release). */
    private var keyHeld = false

    private var tapX = 0f
    private var tapY = 0f

    /** Fires when the auto-repeat heartbeat stops without a key-UP. */
    private val watchdog = Runnable {
        Log.w(TAG, "Key-up not received; force-releasing touch")
        beginRelease()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        Log.i(TAG, "Service connected")
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used; this service only cares about key events.
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!Prefs.enabled(this)) return false
        if (event.keyCode != Prefs.keyCode(this)) return false

        if (Prefs.holdMode(this)) {
            handleHoldMode(event)
        } else {
            handleTapMode(event)
        }
        // Consume the event so the key doesn't also reach the foreground app.
        return true
    }

    /**
     * Default mode: every key press fires one complete, self-contained tap
     * (touch down + up inside a single gesture). Nothing is left on screen
     * waiting for a follow-up event, so the touch can never get stuck.
     * Auto-repeat while the key is held fires repeated taps.
     */
    private fun handleTapMode(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN) {
            computeTapPoint()
            val path = Path().apply { moveTo(tapX, tapY) }
            val tap = GestureDescription.StrokeDescription(path, 0, TAP_MS, false)
            dispatchGesture(GestureDescription.Builder().addStroke(tap).build(), null, null)
        }
    }

    /** Optional mode: the touch stays held while the key is held. */
    private fun handleHoldMode(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    // First press: put the finger down immediately.
                    if (!keyHeld) {
                        keyHeld = true
                        armWatchdog(WATCHDOG_INITIAL_MS)
                        // If a release from a previous press is still in flight,
                        // resume that finger instead of starting a second,
                        // concurrent gesture.
                        if (activeStroke == null) {
                            touchDown()
                        }
                    }
                } else {
                    // Auto-repeat: proof the key is still physically held.
                    // Reset the watchdog so a genuine hold is never cut short.
                    armWatchdog(WATCHDOG_REPEAT_MS)
                }
            }
            KeyEvent.ACTION_UP -> {
                // Release instantly on the real key-up.
                handler.removeCallbacks(watchdog)
                beginRelease()
            }
        }
    }

    private fun armWatchdog(delayMs: Long) {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, delayMs)
    }

    /**
     * Ask the hold loop to lift the finger. Clearing [keyHeld] makes the next
     * gesture callback chain a lifting segment instead of another hold segment.
     */
    private fun beginRelease() {
        keyHeld = false
    }

    private fun touchDown() {
        computeTapPoint()
        val path = Path().apply { moveTo(tapX, tapY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, HOLD_SEGMENT_MS, true)
        activeStroke = stroke
        dispatch(stroke)
    }

    private val gestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            continueOrFinish()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            // Another app or the system cancelled our gesture; drop the hold
            // so the next key press starts cleanly.
            activeStroke = null
            keyHeld = false
        }
    }

    private fun continueOrFinish() {
        val stroke = activeStroke ?: return
        val path = Path().apply { moveTo(tapX, tapY) }
        when {
            keyHeld -> {
                // Key still held: chain another hold segment (finger stays down).
                val next = stroke.continueStroke(path, 0, HOLD_SEGMENT_MS, true)
                activeStroke = next
                dispatch(next)
            }
            else -> {
                // Key released (or watchdog fired): chain a final segment that
                // lifts the finger.
                val end = stroke.continueStroke(path, 0, RELEASE_SEGMENT_MS, false)
                activeStroke = null
                dispatch(end)
            }
        }
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        if (!dispatchGesture(gesture, gestureCallback, null)) {
            Log.w(TAG, "dispatchGesture returned false")
            activeStroke = null
            keyHeld = false
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
