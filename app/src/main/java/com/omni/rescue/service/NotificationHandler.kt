package com.omni.rescue.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.omni.rescue.R
import com.omni.rescue.ui.DashboardActivity

object NotificationHandler {
    private const val CHANNEL_ID = "omni_rescue_channel"
    private const val NOTIFICATION_ID = 1001

    fun createNotification(context: Context): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Omni-Rescue",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the listening service alive"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, DashboardActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, RescueListenerService::class.java).apply {
            action = "STOP_ALARM"
        }
        val stopPendingIntent = PendingIntent.getService(
            context, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Omni-Rescue")
            .setContentText("Listening for your voice...")
            .setSmallIcon(R.drawable.ic_mic)   // add your icon
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop Alarm", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}