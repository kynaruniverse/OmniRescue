package com.omni.rescue.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.omni.rescue.R
import com.omni.rescue.data.AppPreferences
import com.omni.rescue.service.RescueListenerService

class DashboardActivity : AppCompatActivity() {
    private lateinit var prefs: AppPreferences
    private lateinit var btnToggleService: Button
    private lateinit var sensitivitySeekBar: SeekBar
    private lateinit var tvSensitivity: TextView
    private lateinit var switchFlash: Switch
    private lateinit var switchVibrate: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        prefs = AppPreferences(this)

        btnToggleService = findViewById(R.id.btnToggleService)
        sensitivitySeekBar = findViewById(R.id.sensitivitySeekBar)
        tvSensitivity = findViewById(R.id.tvSensitivity)
        switchFlash = findViewById(R.id.switchFlash)
        switchVibrate = findViewById(R.id.switchVibrate)

        updateUI()

        btnToggleService.setOnClickListener {
            prefs.isServiceEnabled = !prefs.isServiceEnabled
            updateUI()
            if (prefs.isServiceEnabled) {
                startService(Intent(this, RescueListenerService::class.java))
            } else {
                stopService(Intent(this, RescueListenerService::class.java))
            }
        }

        sensitivitySeekBar.progress = (prefs.sensitivity * 100).toInt()
        sensitivitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress / 100f
                tvSensitivity.text = "Sensitivity: ${value}"
                if (fromUser) {
                    prefs.sensitivity = value
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        switchFlash.isChecked = prefs.flashEnabled
        switchFlash.setOnCheckedChangeListener { _, isChecked -> prefs.flashEnabled = isChecked }

        switchVibrate.isChecked = prefs.vibrateEnabled
        switchVibrate.setOnCheckedChangeListener { _, isChecked -> prefs.vibrateEnabled = isChecked }
    }

    private fun updateUI() {
        btnToggleService.text = if (prefs.isServiceEnabled) "Stop Service" else "Start Service"
    }
}
