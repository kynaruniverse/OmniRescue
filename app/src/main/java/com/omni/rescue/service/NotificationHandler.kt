package com.omni.rescue.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.omni.rescue.R
import com.omni.rescue.ui.DashboardActivity

object NotificationHandler {
    const val NOTIFICATION_ID = 1001
    private const val CHANNEL_ID = "omni_rescue_channel"

    fun ensureChannelCreated(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Omni-Rescue",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the rescue listening service alive"
        }
        manager.createNotificationChannel(channel)
    }

    fun createNotification(context: Context): Notification {
        ensureChannelCreated(context)

        val dashPendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, DashboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopAlarmPendingIntent = PendingIntent.getService(
            context, 1,
            Intent(context, RescueListenerService::class.java).apply {
                action = RescueListenerService.ACTION_STOP_ALARM
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Omni-Rescue Active")
            .setContentText("Listening for your voice...")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(dashPendingIntent)
            .addAction(R.drawable.ic_stop, "Stop Alarm", stopAlarmPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}