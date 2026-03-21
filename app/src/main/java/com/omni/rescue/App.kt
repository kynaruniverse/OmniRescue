package com.omni.rescue

import android.app.Application
import com.omni.rescue.service.NotificationHandler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Create the notification channel once at app startup,
        // not every time the service creates a notification.
        NotificationHandler.ensureChannelCreated(this)
    }
}