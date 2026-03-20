package com.omni.rescue.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.omni.rescue.data.local.AppPreferences
import com.omni.rescue.logic.AlarmController
import com.omni.rescue.logic.AudioAnalyzer

class RescueListenerService : Service() {

    private lateinit var audioAnalyzer: AudioAnalyzer
    private lateinit var alarmController: AlarmController
    private lateinit var prefs: AppPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        alarmController = AlarmController(this)
        audioAnalyzer = AudioAnalyzer(this) {
            // Trigger alarm when wake word detected
            alarmController.triggerAlarm()
        }

        // Start foreground with notification
        startForeground(
            NotificationHandler.NOTIFICATION_ID,
            NotificationHandler.createNotification(this)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP_ALARM" -> {
                alarmController.stopAlarm()
            }
            else -> {
                // Normal start: begin listening
                audioAnalyzer.startListening()
                prefs.isServiceRunning = true
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        audioAnalyzer.stopListening()
        prefs.isServiceRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}