package com.omni.rescue.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    private lateinit var scoreText: TextView

    private val scoreReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val score = intent.getFloatExtra(RescueListenerService.EXTRA_SCORE, -1f)
            val threshold = prefs.sensitivity
            val pct = (score * 100).toInt()
            scoreText.text = "Live score: $pct%   (threshold: ${(threshold * 100).toInt()}%)"
            scoreText.setTextColor(
                if (score >= threshold)
                    ContextCompat.getColor(this@DashboardActivity, android.R.color.holo_red_dark)
                else
                    ContextCompat.getColor(this@DashboardActivity, android.R.color.darker_gray)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        prefs = AppPreferences(applicationContext)

        sensitivitySeekBar = findViewById(R.id.sensitivity_seekbar)
        sensitivityText = findViewById(R.id.sensitivity_text)
        toggleService = findViewById(R.id.toggle_service)
        btnStopAlarm = findViewById(R.id.btn_stop_alarm)
        statusText = findViewById(R.id.status_text)
        scoreText = findViewById(R.id.score_text)

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
        ContextCompat.registerReceiver(
            this,
            scoreReceiver,
            IntentFilter(RescueListenerService.ACTION_SCORE_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(scoreReceiver)
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