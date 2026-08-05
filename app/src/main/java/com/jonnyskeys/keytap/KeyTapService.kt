package com.jonnyskeys.keytap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Point
import android.hardware.input.InputManager
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
 *
 * Nothing here releases the touch on a timer. The hold ends when the physical
 * key comes up, and only then. Key auto-repeat is NOT used as a heartbeat:
 * repeats are synthesized downstream of the accessibility input filter, so a
 * consumed key often produces none at all, and treating their absence as
 * "key released" cut every hold short after a second or two.
 *
 * The stuck-finger cases the timer used to cover are handled by evidence
 * instead of by the clock: the keyboard disconnecting, the mapping being
 * switched off, the service being interrupted, or a fresh key-down arriving
 * while a hold is still active (which proves the previous key-UP was lost).
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

        // A hold that survives its full MAX_HOLD_MS is renewed so the finger
        // stays down. Backstop against a permanently stuck touch if a key-UP
        // is lost and no other evidence ever arrives: after this many
        // full-length renewals (~5 minutes of unbroken hold, far longer than
        // any real one) the finger lifts. Any key event resets the count.
        private const val MAX_HOLD_RENEWALS = 5

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

    /** True from key-down until key-up. */
    private var keyHeld = false

    /** Input device the current hold came from, so an unplug can end it. */
    private var heldDeviceId = -1

    /** Full-length hold renewals since the last key event (see MAX_HOLD_RENEWALS). */
    private var holdRenewals = 0

    /** When the hold stroke was last dispatched (re-plant rate limiting). */
    private var lastPlantAt = 0L

    private var tapX = 0f
    private var tapY = 0f

    /** A keyboard going away mid-hold is proof the key can never come up. */
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}

        override fun onInputDeviceChanged(deviceId: Int) {}

        override fun onInputDeviceRemoved(deviceId: Int) {
            if (keyHeld && deviceId == heldDeviceId) {
                Log.w(TAG, "Keyboard disconnected while the key was held; releasing")
                requestRelease()
            }
        }
    }

    /** Switching the mapping off mid-hold must not leave the finger down. */
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.KEY_ENABLED && !Prefs.enabled(this)) requestRelease()
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
        getSystemService(InputManager::class.java)
            ?.registerInputDeviceListener(inputDeviceListener, handler)
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener)
        Log.i(TAG, "Service connected")
    }

    override fun onDestroy() {
        running = false
        getSystemService(InputManager::class.java)
            ?.unregisterInputDeviceListener(inputDeviceListener)
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacks(refreshTerminator)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used; this service only cares about key events.
    }

    override fun onInterrupt() {
        // The system is telling the service to stop acting; don't leave a
        // finger pinned to the screen.
        requestRelease()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!Prefs.enabled(this)) return false
        if (event.keyCode != Prefs.keyCode(this)) return false

        // Any event for the mapped key proves the keyboard is still talking to
        // us, so the stuck-touch backstop starts over.
        holdRenewals = 0

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    if (keyHeld) {
                        // A fresh press while we still believe the key is down
                        // means the key-UP was lost. Re-sync so this press
                        // registers as a new tap instead of being swallowed.
                        Log.w(TAG, "Key-down while already held; re-syncing")
                        requestRelease()
                    }
                    keyHeld = true
                    heldDeviceId = event.deviceId
                    // Dispatching the hold also silently supersedes any
                    // parked terminator.
                    handler.removeCallbacks(refreshTerminator)
                    dispatchHold()
                }
                // Auto-repeats need no handling: the touch is already down and
                // stays down until the key comes up.
            }
            KeyEvent.ACTION_UP -> requestRelease()
        }
        // Consume the event so the key doesn't also reach the foreground app.
        return true
    }

    private fun requestRelease() {
        if (!keyHeld) return
        keyHeld = false
        heldDeviceId = -1
        holdRenewals = 0
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
                if (!keyHeld) return
                if (holdRenewals >= MAX_HOLD_RENEWALS) {
                    // Minutes of unbroken hold with no key event at all: the
                    // key-UP was almost certainly lost. Stop renewing.
                    Log.w(TAG, "Hold renewed $holdRenewals times with no key event; releasing")
                    keyHeld = false
                    heldDeviceId = -1
                    holdRenewals = 0
                    return
                }
                // Key is still physically down: put the finger back.
                holdRenewals++
                dispatchHold()
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
