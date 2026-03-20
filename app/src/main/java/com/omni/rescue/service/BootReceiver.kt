package com.omni.rescue.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Start the service
            val serviceIntent = Intent(context, RescueListenerService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}