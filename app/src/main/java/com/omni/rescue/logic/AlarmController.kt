package com.omni.rescue.logic

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.omni.rescue.R

class AlarmController(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var isFlashing = false
    private var flashThread: Thread? = null

    fun triggerAlarm() {
        // Override silent mode and raise volume
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
        // Set volume to max gradually (simulate ramp)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)

        // Play sound
        mediaPlayer = MediaPlayer.create(context, R.raw.alarm_sound)   // add a raw sound file
        mediaPlayer?.isLooping = true
        mediaPlayer?.start()

        // Vibration
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), intArrayOf(0, 255, 0), -1))
        } else {
            vibrator.vibrate(longArrayOf(0, 500, 500), -1)
        }

        // Flash LED (SOS pattern)
        startFlashSOS()
    }

    fun stopAlarm() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        stopFlash()
        // Optionally reset volume? We'll leave as is.
    }

    private fun startFlashSOS() {
        if (isFlashing) return
        isFlashing = true
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull()
        if (cameraId == null) {
            Log.e("AlarmController", "No camera found for flash")
            return
        }

        flashThread = Thread {
            val pattern = listOf(100, 100, 100, 100, 100, 300) // SOS: short, short, short, long, long, long, short, short, short
            var index = 0
            while (isFlashing) {
                val duration = if (index % 2 == 0) 200 else 200 // simple on/off pattern; refine later
                try {
                    cameraManager.setTorchMode(cameraId, index % 2 == 0)
                } catch (e: Exception) {
                    Log.e("AlarmController", "Flash error", e)
                }
                Thread.sleep(duration.toLong())
                index++
            }
            // Turn off when done
            try {
                cameraManager.setTorchMode(cameraId, false)
            } catch (e: Exception) { }
        }.also { it.start() }
    }

    private fun stopFlash() {
        isFlashing = false
        flashThread?.interrupt()
        flashThread = null
        // Ensure LED is off
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraManager.cameraIdList.firstOrNull()?.let { cameraId ->
            try {
                cameraManager.setTorchMode(cameraId, false)
            } catch (e: Exception) { }
        }
    }
}