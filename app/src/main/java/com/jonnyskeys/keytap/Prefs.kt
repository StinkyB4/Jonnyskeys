package com.jonnyskeys.keytap

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * Single place for the app's few settings, shared between the activity and the
 * accessibility service (same process, same SharedPreferences file).
 */
object Prefs {
    private const val FILE = "keytap"
    private const val KEY_KEYCODE = "keycode"
    private const val KEY_TAP_X_PCT = "tap_x_pct"
    private const val KEY_TAP_Y_PCT = "tap_y_pct"

    /** Public so the service can spot the master switch being turned off. */
    const val KEY_ENABLED = "enabled"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun keyCode(context: Context): Int =
        get(context).getInt(KEY_KEYCODE, KeyEvent.KEYCODE_SPACE)

    fun setKeyCode(context: Context, keyCode: Int) =
        get(context).edit().putInt(KEY_KEYCODE, keyCode).apply()

    /** Tap position as a percentage of the screen, so it survives rotation. */
    fun tapXPercent(context: Context): Int = get(context).getInt(KEY_TAP_X_PCT, 50)

    fun tapYPercent(context: Context): Int = get(context).getInt(KEY_TAP_Y_PCT, 70)

    fun setTapPercent(context: Context, xPct: Int, yPct: Int) =
        get(context).edit().putInt(KEY_TAP_X_PCT, xPct).putInt(KEY_TAP_Y_PCT, yPct).apply()

    fun enabled(context: Context): Boolean = get(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) =
        get(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
}
