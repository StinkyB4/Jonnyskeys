package com.jonnyskeys.keytap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Intercepts hardware (USB or Bluetooth) keyboard events system-wide and converts the mapped
 * key into an injected touch at a configurable screen position, behaving like a
 * real finger: key down = touch down, key held = touch held, key up = touch up.
 *
 * EMUI-safe design. Diagnostics from a Huawei P30 Pro showed EMUI cancels any
 * gesture that uses stroke continuation (willContinue) within ~3ms of
 * dispatch, so a hold can never be built by chaining segments there. Plain
 * one-shot strokes, however, run reliably. Therefore:
 *
 * - A press dispatches ONE plain stroke with a long preset duration
 *   (MAX_HOLD_MS). The pointer goes down immediately and simply stays down.
 *   No continuations are used anywhere.
 * - Release works by superseding: dispatching any new gesture makes the
 *   injector end the in-flight hold. The release gesture ("terminator") has
 *   its stroke delayed far into the future, so it kills the hold instantly
 *   while never touching the screen itself.
 * - A parked terminator would eventually fire its stroke as a stray tap, so
 *   while parked it is refreshed (re-dispatched, which silently cancels the
 *   old one) long before its delay elapses. The next real key press also
 *   supersedes it silently.
 * - A watchdog force-releases if the key-UP event is lost, using auto-repeat
 *   as a "still physically held" heartbeat.
 */
class KeyTapService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyTapService"

        // Maximum length of a single hold; the system caps gestures at 60s.
        private const val MAX_HOLD_MS = 59_000L

        // The terminator's stroke starts this far in the future. It exists to
        // supersede the hold, and is always refreshed or replaced before this
        // delay elapses, so the stroke itself never plays.
        private const val TERMINATOR_START_MS = 59_000L
        private const val TERMINATOR_DURATION_MS = 1L
        private const val TERMINATOR_REFRESH_MS = 45_000L

        // Minimum spacing between automatic re-plants after an external
        // cancel, so a device that cancels everything can never cause a
        // machine-gun loop of touch-downs again.
        private const val REPLANT_MIN_INTERVAL_MS = 100L

        // Watchdog for a lost key-UP. Generous initial window so genuine long
        // holds are never cut short if auto-repeat is late or sparse.
        private const val WATCHDOG_INITIAL_MS = 1_500L
        private const val WATCHDOG_REPEAT_MS = 400L

        /** Set while the service is connected, so the UI can show live status. */
        @Volatile
        var running = false
            private set
    }

    private enum class State {
        /** Nothing in flight: no touch on screen, no parked terminator. */
        IDLE,

        /** The long hold stroke is in flight; the pointer is down. */
        HOLDING,

        /** Released: a terminator is parked, silently pending. */
        PARKED,
    }

    private val handler = Handler(Looper.getMainLooper())

    private var state = State.IDLE

    /** True from key-down until key-up (or watchdog release). */
    private var keyHeld = false

    /** When the hold stroke was last dispatched (re-plant rate limiting). */
    private var lastPlantAt = 0L

    private var tapX = 0f
    private var tapY = 0f

    /** Fires only if key-UP was never delivered: lift the finger anyway. */
    private val watchdog = Runnable {
        Log.w(TAG, "Key-up not received; forcing release (state=$state)")
        requestRelease()
    }

    /** Keeps the parked terminator fresh so its stroke never actually plays. */
    private val refreshTerminator = object : Runnable {
        override fun run() {
            if (state == State.PARKED) {
                dispatchTerminator()
                handler.postDelayed(this, TERMINATOR_REFRESH_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        Log.i(TAG, "Service connected")
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(refreshTerminator)
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
                        // Dispatching the hold also silently supersedes any
                        // parked terminator.
                        handler.removeCallbacks(refreshTerminator)
                        dispatchHold()
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

    private fun requestRelease() {
        if (!keyHeld) return
        keyHeld = false
        if (state == State.HOLDING) {
            // Park a terminator: this supersedes the hold stroke immediately
            // (the game sees the touch end now) without playing any touch of
            // its own.
            state = State.PARKED
            dispatchTerminator()
            handler.postDelayed(refreshTerminator, TERMINATOR_REFRESH_MS)
        }
    }

    private fun dispatchHold() {
        computeTapPoint()
        val path = Path().apply { moveTo(tapX, tapY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, MAX_HOLD_MS, false)
        state = State.HOLDING
        lastPlantAt = SystemClock.uptimeMillis()
        if (!dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                holdCallback,
                null
            )
        ) {
            Log.w(TAG, "dispatchGesture rejected the hold stroke")
            state = State.IDLE
            keyHeld = false
        }
    }

    private fun dispatchTerminator() {
        val path = Path().apply { moveTo(tapX, tapY) }
        val stroke = GestureDescription.StrokeDescription(
            path, TERMINATOR_START_MS, TERMINATOR_DURATION_MS, false
        )
        if (!dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                terminatorCallback,
                null
            )
        ) {
            // Very bad: the hold could run to its full duration. Retry once.
            Log.w(TAG, "dispatchGesture rejected the terminator; retrying")
            handler.post {
                if (state == State.PARKED) dispatchTerminator()
            }
        }
    }

    private val holdCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            // The hold ran its full MAX_HOLD_MS and lifted on its own.
            if (state == State.HOLDING) {
                state = State.IDLE
                if (keyHeld) {
                    // Key is still physically down: put the finger back.
                    dispatchHold()
                }
            }
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            if (state != State.HOLDING) {
                // Expected: we superseded this hold ourselves with a
                // terminator (release) or a new hold.
                return
            }
            // External cancel (screen touched, system interference) while the
            // key is still held: re-plant once, but never faster than the
            // rate limit — a device that cancels everything must not turn
            // this into a rapid-fire loop.
            Log.w(TAG, "Hold cancelled externally (keyHeld=$keyHeld)")
            if (keyHeld &&
                SystemClock.uptimeMillis() - lastPlantAt >= REPLANT_MIN_INTERVAL_MS
            ) {
                dispatchHold()
            } else {
                state = State.IDLE
            }
        }
    }

    private val terminatorCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            // The parked stroke actually played — the refresh missed. Rare;
            // log it (it appears in the game as a stray micro-tap).
            Log.w(TAG, "Parked terminator played as a stray tap")
            if (state == State.PARKED) {
                state = State.IDLE
                handler.removeCallbacks(refreshTerminator)
            }
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            // Expected whenever a new hold or a refresh supersedes the parked
            // terminator. Nothing to do.
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
