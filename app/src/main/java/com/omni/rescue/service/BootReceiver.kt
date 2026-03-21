package com.omni.rescue.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.omni.rescue.data.local.AppPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = AppPreferences(context.applicationContext)
        if (!prefs.isServiceRunning) {
            Log.d("BootReceiver", "Service was not running before boot — skipping auto-start")
            return
        }

        Log.d("BootReceiver", "Boot completed, restarting RescueListenerService")
        context.startForegroundService(Intent(context, RescueListenerService::class.java))
    }
}