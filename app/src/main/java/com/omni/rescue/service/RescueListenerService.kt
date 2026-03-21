package com.omni.rescue.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.omni.rescue.data.local.AppPreferences
import com.omni.rescue.logic.AlarmController
import com.omni.rescue.logic.AudioAnalyzer

class RescueListenerService : Service() {

    private var audioAnalyzer: AudioAnalyzer? = null
    private var alarmController: AlarmController? = null
    private lateinit var prefs: AppPreferences

    companion object {
        const val ACTION_STOP_ALARM = "ACTION_STOP_ALARM"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        private const val TAG = "RescueListenerService"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(applicationContext)
        alarmController = AlarmController(applicationContext)
        audioAnalyzer = AudioAnalyzer(applicationContext) {
            // onTriggerDetected is already posted to main thread inside AudioAnalyzer
            alarmController?.triggerAlarm()
        }
        startForeground(
            NotificationHandler.NOTIFICATION_ID,
            NotificationHandler.createNotification(this)
        )
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALARM -> {
                alarmController?.stopAlarm()
                audioAnalyzer?.resetAlarmGate()
            }
            ACTION_STOP_SERVICE -> {
                prefs.isServiceRunning = false
                stopSelf()
            }
            else -> {
                audioAnalyzer?.startListening()
                prefs.isServiceRunning = true
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        audioAnalyzer?.release()
        audioAnalyzer = null
        alarmController?.stopAlarm()
        alarmController = null
        prefs.isServiceRunning = false
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}