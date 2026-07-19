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
 * Key down  -> touch down at the tap point (held via chained gesture strokes)
 * Key held  -> touch stays down (needed for ship/wave/hold mechanics in games)
 * Key up    -> touch up
 *
 * Robust release: the finger is lifted the instant the mapped key is released.
 * Because a fast-paced game can occasionally swallow the hardware key-UP event
 * (which would otherwise leave the finger stuck down forever), a watchdog uses
 * the keyboard's auto-repeat as a heartbeat: while the key is physically held
 * the OS keeps re-sending ACTION_DOWN, so if that heartbeat stops without a
 * key-UP, the touch is force-released.
 */
class KeyTapService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyTapService"

        // A gesture stroke needs a finite duration, so a "hold" is built from
        // short chained segments. Shorter segments = lower worst-case latency
        // between key release and touch-up. 24ms is under one frame at 30fps.
        private const val HOLD_SEGMENT_MS = 24L
        private const val RELEASE_SEGMENT_MS = 1L

        // If the auto-repeat heartbeat stops for longer than this without a
        // key-UP, assume the UP was dropped and release the touch. Comfortably
        // longer than a hardware key's ~50ms repeat interval, so genuine holds
        // are never cut short, but short enough that a stuck finger recovers
        // almost immediately.
        private const val WATCHDOG_MS = 250L

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

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    // First press: put the finger down immediately (lowest
                    // possible latency).
                    if (!keyHeld) {
                        keyHeld = true
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
                    armWatchdog()
                }
            }
            KeyEvent.ACTION_UP -> {
                // Release instantly on the real key-up.
                handler.removeCallbacks(watchdog)
                beginRelease()
            }
        }
        // Consume the event so the key doesn't also reach the foreground app.
        return true
    }

    private fun armWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
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
