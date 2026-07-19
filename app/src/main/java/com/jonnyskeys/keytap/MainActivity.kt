package com.jonnyskeys.keytap

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Button
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var mapKeyButton: Button
    private lateinit var xLabel: TextView
    private lateinit var yLabel: TextView

    /** While true, the next hardware key press becomes the mapped key. */
    private var capturingKey = false

    private lateinit var diagLog: TextView
    private lateinit var diagScroll: ScrollView
    private val uiHandler = Handler(Looper.getMainLooper())
    private val diagRefresher = object : Runnable {
        override fun run() {
            val text = DebugLog.dump()
            if (diagLog.text.toString() != text) {
                diagLog.text = text
                diagScroll.post { diagScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        mapKeyButton = findViewById(R.id.map_key_button)
        xLabel = findViewById(R.id.x_label)
        yLabel = findViewById(R.id.y_label)
        val xSeek = findViewById<SeekBar>(R.id.x_seek)
        val ySeek = findViewById<SeekBar>(R.id.y_seek)
        val enabledSwitch = findViewById<Switch>(R.id.enabled_switch)

        findViewById<Button>(R.id.open_settings_button).setOnClickListener {
            showDisclosureThenOpenSettings()
        }

        mapKeyButton.setOnClickListener {
            capturingKey = true
            mapKeyButton.text = getString(R.string.press_any_key)
        }

        xSeek.progress = Prefs.tapXPercent(this)
        ySeek.progress = Prefs.tapYPercent(this)

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                Prefs.setTapPercent(this@MainActivity, xSeek.progress, ySeek.progress)
                updateLabels()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        }
        xSeek.setOnSeekBarChangeListener(seekListener)
        ySeek.setOnSeekBarChangeListener(seekListener)

        enabledSwitch.isChecked = Prefs.enabled(this)
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setEnabled(this, checked)
        }

        diagLog = findViewById(R.id.diag_log)
        diagScroll = findViewById(R.id.diag_scroll)
        findViewById<Button>(R.id.diag_copy_button).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("jonnyskeys-log", DebugLog.dump()))
            Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.diag_clear_button).setOnClickListener {
            DebugLog.clear()
            diagLog.text = ""
        }

        updateLabels()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        uiHandler.post(diagRefresher)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(diagRefresher)
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (capturingKey && event.action == KeyEvent.ACTION_DOWN) {
            // Ignore keys that would make the app unusable if consumed here.
            if (event.keyCode != KeyEvent.KEYCODE_BACK &&
                event.keyCode != KeyEvent.KEYCODE_HOME
            ) {
                Prefs.setKeyCode(this, event.keyCode)
                capturingKey = false
                updateLabels()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun updateLabels() {
        mapKeyButton.text = getString(
            R.string.mapped_key,
            KeyEvent.keyCodeToString(Prefs.keyCode(this)).removePrefix("KEYCODE_")
        )
        xLabel.text = getString(R.string.tap_x, Prefs.tapXPercent(this))
        yLabel.text = getString(R.string.tap_y, Prefs.tapYPercent(this))
    }

    /**
     * Prominent disclosure required by Play policy for apps that use the
     * accessibility API: explain what the service does and get consent
     * before sending the user to system settings.
     */
    private fun showDisclosureThenOpenSettings() {
        AlertDialog.Builder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(R.string.disclosure_body)
            .setPositiveButton(R.string.disclosure_agree) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(R.string.disclosure_cancel, null)
            .show()
    }

    private fun updateStatus() {
        if (KeyTapService.running) {
            statusText.text = getString(R.string.status_running)
            statusText.setTextColor(getColor(R.color.gd_green))
        } else {
            statusText.text = getString(R.string.status_stopped)
            statusText.setTextColor(getColor(R.color.gd_orange))
        }
    }
}
