package com.omni.rescue.ui

import android.content.Intent
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        prefs = AppPreferences(this)

        sensitivitySeekBar = findViewById(R.id.sensitivity_seekbar)
        sensitivityText = findViewById(R.id.sensitivity_text)
        toggleService = findViewById(R.id.toggle_service)

        sensitivitySeekBar.progress = (prefs.sensitivity * 100).toInt()
        sensitivityText.text = "Sensitivity: ${prefs.sensitivity}"

        sensitivitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sensitivity = progress / 100f
                prefs.sensitivity = sensitivity
                sensitivityText.text = "Sensitivity: $sensitivity"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        toggleService.isChecked = prefs.isServiceRunning
        toggleService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startService(Intent(this, RescueListenerService::class.java))
            } else {
                stopService(Intent(this, RescueListenerService::class.java))
            }
            prefs.isServiceRunning = isChecked
        }
    }
}
