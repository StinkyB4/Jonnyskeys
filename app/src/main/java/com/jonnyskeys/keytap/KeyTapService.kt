package com.jonnyskeys.keytap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
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
 */
class KeyTapService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyTapService"

        // A gesture stroke needs a finite duration, so a "hold" is built from
        // short chained segments. Shorter segments = lower worst-case latency
        // between key release and touch-up.
        private const val HOLD_SEGMENT_MS = 50L
        private const val RELEASE_SEGMENT_MS = 1L

        /** Set while the service is connected, so the UI can show live status. */
        @Volatile
        var running = false
            private set
    }

    /** The stroke currently on screen, needed to continue or finish the hold. */
    private var activeStroke: GestureDescription.StrokeDescription? = null

    /** True from key-down until key-up. */
    private var keyHeld = false

    /** Set when key-up arrives while a hold segment is still in flight. */
    private var releasePending = false

    private var tapX = 0f
    private var tapY = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        Log.i(TAG, "Service connected")
    }

    override fun onDestroy() {
        running = false
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
                // OS auto-repeat re-sends ACTION_DOWN while held; only the
                // first press starts a touch.
                if (event.repeatCount == 0 && !keyHeld) {
                    keyHeld = true
                    releasePending = false
                    touchDown()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (keyHeld) {
                    keyHeld = false
                    // If no segment is in flight the callback won't fire again,
                    // so finish the stroke directly.
                    val stroke = activeStroke
                    if (stroke != null) {
                        releasePending = true
                    }
                }
            }
        }
        // Consume the event so the key doesn't also reach the foreground app.
        return true
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
            releasePending = false
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
            releasePending -> {
                // Key was released: chain a final segment that lifts the finger.
                releasePending = false
                val end = stroke.continueStroke(path, 0, RELEASE_SEGMENT_MS, false)
                activeStroke = null
                dispatch(end)
            }
            else -> activeStroke = null
        }
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        if (!dispatchGesture(gesture, gestureCallback, null)) {
            Log.w(TAG, "dispatchGesture returned false")
            activeStroke = null
            releasePending = false
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
