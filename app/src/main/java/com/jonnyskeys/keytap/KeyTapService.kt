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
 * real finger:
 *
 * Key down -> touch down instantly.
 * Key held -> touch stays down (ship/wave/hold mechanics).
 * Key up   -> touch up instantly.
 *
 * Reliability: the touch-up is dispatched *directly* from the key-up event as a
 * continuation of the on-screen stroke — it does not depend on the hold-segment
 * callback chain, so even if that chain stalls under game load the finger still
 * lifts the moment the key is released. Two further backstops:
 * - a watchdog force-releases if the key-UP event itself is lost (it uses the
 *   keyboard's auto-repeat as a "still physically held" heartbeat);
 * - if the system cancels our gesture while the key is still held, the hold is
 *   re-established instead of being dropped.
 */
class KeyTapService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyTapService"

        // Length of each chained hold segment. Because release is dispatched
        // immediately from key-up (not at a segment boundary), segments can be
        // long, which means fewer chain links and fewer chances to break.
        private const val HOLD_SEGMENT_MS = 400L
        private const val RELEASE_SEGMENT_MS = 1L

        // Watchdog windows. The keyboard's auto-repeat acts as a "still held"
        // heartbeat. The initial window must outlast the OS's repeat delay
        // (~500ms) so real holds aren't cut before the first repeat arrives;
        // once repeats are flowing, the window tightens.
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

    /** Fires only if key-UP was never delivered: lift the finger anyway. */
    private val watchdog = Runnable {
        Log.w(TAG, "Key-up not received; force-releasing touch")
        release()
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

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    // First press: finger down immediately.
                    if (!keyHeld) {
                        keyHeld = true
                        armWatchdog(WATCHDOG_INITIAL_MS)
                        touchDown()
                    }
                } else {
                    // Auto-repeat: proof the key is still physically held.
                    armWatchdog(WATCHDOG_REPEAT_MS)
                }
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(watchdog)
                release()
            }
        }
        // Consume the event so the key doesn't also reach the foreground app.
        return true
    }

    private fun armWatchdog(delayMs: Long) {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, delayMs)
    }

    private fun touchDown() {
        computeTapPoint()
        val path = Path().apply { moveTo(tapX, tapY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, HOLD_SEGMENT_MS, true)
        activeStroke = stroke
        dispatch(stroke, holdCallback)
    }

    /**
     * Lift the finger right now. Dispatching the finishing continuation
     * directly (instead of waiting for the hold chain's next callback) is what
     * guarantees the touch can't stay stuck: it works even if the chain's
     * callbacks were lost, and it truncates any in-flight hold segment.
     */
    private fun release() {
        keyHeld = false
        val stroke = activeStroke ?: return
        activeStroke = null
        val path = Path().apply { moveTo(tapX, tapY) }
        val end = stroke.continueStroke(path, 0, RELEASE_SEGMENT_MS, false)
        dispatch(end, null)
    }

    private val holdCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            // A hold segment finished. If the key is still down, chain the
            // next segment so the finger stays on screen.
            val stroke = activeStroke ?: return
            if (keyHeld) {
                val path = Path().apply { moveTo(tapX, tapY) }
                val next = stroke.continueStroke(path, 0, HOLD_SEGMENT_MS, true)
                activeStroke = next
                dispatch(next, this)
            } else {
                // Release already ran and cleared activeStroke in the normal
                // case; reaching here means state drifted — finish cleanly.
                activeStroke = null
                val path = Path().apply { moveTo(tapX, tapY) }
                dispatch(stroke.continueStroke(path, 0, RELEASE_SEGMENT_MS, false), null)
            }
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            // The system cancelled our gesture (this also lifts the pointer).
            activeStroke = null
            if (keyHeld) {
                // The key is still physically down: re-establish the hold so
                // an external cancel doesn't end the player's input.
                touchDown()
            }
        }
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
