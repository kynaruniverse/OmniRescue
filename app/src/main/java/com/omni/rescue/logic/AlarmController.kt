package com.omni.rescue.logic

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.getSystemService
import com.omni.rescue.data.AppPreferences
import kotlinx.coroutines.*

class AlarmController(private val context: Context) {
    private val prefs = AppPreferences(context)
    private var mediaPlayer: MediaPlayer? = null
    private var flashlightRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var isFlashing = false

    suspend fun triggerAlarm() {
        // Override silent mode and increase volume
        val audioManager = context.getSystemService<AudioManager>()
        audioManager?.let {
            it.ringerMode = AudioManager.RINGER_MODE_NORMAL
            it.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
            it.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_RAISE, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)
        }

        // Play sound
        startSound()

        // Vibrate
        if (prefs.vibrateEnabled) {
            startVibration()
        }

        // Flashlight
        if (prefs.flashEnabled) {
            startFlashlight()
        }

        // Stop everything after 30 seconds
        delay(30000)
        stopAlarm()
    }

    private fun startSound() {
        val soundUri = prefs.alarmSoundUri?.let { Uri.parse(it) } ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        try {
            mediaPlayer = MediaPlayer.create(context, soundUri).apply {
                isLooping = true
                setVolume(1.0f, 1.0f)
                start()
            }
        } catch (e: Exception) {
            // Fallback to default
            mediaPlayer = MediaPlayer.create(context, android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI).apply {
                isLooping = true
                start()
            }
        }
    }

    private fun startVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService<VibratorManager>()
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService<Vibrator>()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(5000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(5000)
        }
    }

    private fun startFlashlight() {
        if (isFlashing) return
        isFlashing = true
        val cameraManager = context.getSystemService<CameraManager>()
        val cameraId = cameraManager?.cameraIdList?.firstOrNull() ?: return
        flashlightRunnable = object : Runnable {
            override fun run() {
                if (!isFlashing) return
                try {
                    cameraManager.setTorchMode(cameraId, true)
                    handler.postDelayed({
                        cameraManager.setTorchMode(cameraId, false)
                        handler.postDelayed(this, 500)
                    }, 250)
                } catch (e: Exception) {
                    // Camera not available
                }
            }
        }
        handler.post(flashlightRunnable!!)
    }

    private fun stopAlarm() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        // Stop flashlight
        isFlashing = false
        flashlightRunnable?.let { handler.removeCallbacks(it) }
        val cameraManager = context.getSystemService<CameraManager>()
        cameraManager?.cameraIdList?.firstOrNull()?.let {
            try {
                cameraManager.setTorchMode(it, false)
            } catch (e: Exception) { }
        }
    }
}
