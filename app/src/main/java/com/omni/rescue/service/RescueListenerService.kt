package com.omni.rescue.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omni.rescue.R
import com.omni.rescue.data.AppPreferences
import com.omni.rescue.logic.AlarmController
import com.omni.rescue.logic.AudioAnalyzer
import kotlinx.coroutines.*

class RescueListenerService : Service() {
    private val NOTIFICATION_ID = 1002
    private val CHANNEL_ID = "rescue_listener_channel"
    private lateinit var appPreferences: AppPreferences
    private lateinit var alarmController: AlarmController
    private var audioAnalyzer: AudioAnalyzer? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isActive = false

    override fun onCreate() {
        super.onCreate()
        appPreferences = AppPreferences(this)
        alarmController = AlarmController(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        isActive = true
        startListening()
    }

    private fun startListening() {
        audioAnalyzer = AudioAnalyzer(this) { score ->
            Log.d("RescueListener", "Detection score: $score")
            if (score > appPreferences.sensitivity) {
                // Trigger alarm
                serviceScope.launch {
                    alarmController.triggerAlarm()
                }
            }
        }
        audioAnalyzer?.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rescue Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listening for your voice"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Omni Rescue")
            .setContentText("Listening for trigger phrase...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
        audioAnalyzer?.stop()
        audioAnalyzer?.close()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
