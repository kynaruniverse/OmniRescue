package com.omni.rescue.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("omni_rescue_prefs", Context.MODE_PRIVATE)

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) = prefs.edit { putBoolean("service_enabled", value) }

    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", 0.8f)
        set(value) = prefs.edit { putFloat("sensitivity", value) }

    var selectedModel: String
        get() = prefs.getString("selected_model", "hey_jarvis.tflite") ?: "hey_jarvis.tflite"
        set(value) = prefs.edit { putString("selected_model", value) }

    var alarmSoundUri: String?
        get() = prefs.getString("alarm_sound_uri", null)
        set(value) = prefs.edit { putString("alarm_sound_uri", value) }

    var flashEnabled: Boolean
        get() = prefs.getBoolean("flash_enabled", true)
        set(value) = prefs.edit { putBoolean("flash_enabled", value) }

    var vibrateEnabled: Boolean
        get() = prefs.getBoolean("vibrate_enabled", true)
        set(value) = prefs.edit { putBoolean("vibrate_enabled", value) }
}
