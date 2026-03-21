package com.omni.rescue.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import com.omni.rescue.R
import com.omni.rescue.data.local.AppPreferences
import com.omni.rescue.service.RescueListenerService

class DashboardActivity : AppCompatActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var sensitivitySeekBar: SeekBar
    private lateinit var sensitivityText: TextView
    private lateinit var toggleService: ToggleButton
    private lateinit var btnStopAlarm: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        prefs = AppPreferences(applicationContext)

        sensitivitySeekBar = findViewById(R.id.sensitivity_seekbar)
        sensitivityText = findViewById(R.id.sensitivity_text)
        toggleService = findViewById(R.id.toggle_service)
        btnStopAlarm = findViewById(R.id.btn_stop_alarm)
        statusText = findViewById(R.id.status_text)

        refreshUI()

        sensitivitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.sensitivity = progress / 100f
                sensitivityText.text = "Sensitivity: $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        toggleService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startForegroundService(Intent(this, RescueListenerService::class.java))
            } else {
                startService(Intent(this, RescueListenerService::class.java).apply {
                    action = RescueListenerService.ACTION_STOP_SERVICE
                })
            }
            prefs.isServiceRunning = isChecked
            updateStatusText(isChecked)
        }

        btnStopAlarm.setOnClickListener {
            startService(Intent(this, RescueListenerService::class.java).apply {
                action = RescueListenerService.ACTION_STOP_ALARM
            })
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    private fun refreshUI() {
        val progress = (prefs.sensitivity * 100).toInt()
        sensitivitySeekBar.progress = progress
        sensitivityText.text = "Sensitivity: $progress%"
        toggleService.isChecked = prefs.isServiceRunning
        updateStatusText(prefs.isServiceRunning)
    }

    private fun updateStatusText(running: Boolean) {
        statusText.text = if (running)
            "Service is active — listening for wake word"
        else
            "Service is stopped"
    }
}