package com.jonnyskeys.keytap

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of pipeline events, shown in the app's diagnostics
 * panel so a stuck-input session can be inspected without adb. Also mirrored
 * to logcat (tag "Jonnyskeys").
 */
object DebugLog {
    private const val MAX_ENTRIES = 300
    private val entries = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(msg: String) {
        if (entries.size >= MAX_ENTRIES) entries.removeFirst()
        entries.addLast("${fmt.format(Date())}  $msg")
        Log.i("Jonnyskeys", msg)
    }

    @Synchronized
    fun dump(): String = entries.joinToString("\n")

    @Synchronized
    fun clear() = entries.clear()
}
