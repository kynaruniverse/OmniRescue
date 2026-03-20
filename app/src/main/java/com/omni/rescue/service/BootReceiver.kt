package com.omni.rescue.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omni.rescue.data.AppPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = AppPreferences(context)
            if (prefs.isServiceEnabled) {
                val serviceIntent = Intent(context, RescueListenerService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
