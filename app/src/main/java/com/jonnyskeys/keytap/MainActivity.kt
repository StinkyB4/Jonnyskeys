package com.jonnyskeys.keytap

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var mapKeyButton: Button
    private lateinit var xLabel: TextView
    private lateinit var yLabel: TextView

    /** While true, the next hardware key press becomes the mapped key. */
    private var capturingKey = false

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
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

        updateLabels()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
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

    private fun updateStatus() {
        statusText.text = if (KeyTapService.running) {
            getString(R.string.status_running)
        } else {
            getString(R.string.status_stopped)
        }
    }
}
