package com.omni.rescue.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("omni_rescue", Context.MODE_PRIVATE)

    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", 0.7f)   // threshold 0-1
        set(value) = prefs.edit { putFloat("sensitivity", value) }

    var isServiceRunning: Boolean
        get() = prefs.getBoolean("service_running", false)
        set(value) = prefs.edit { putBoolean("service_running", value) }

}