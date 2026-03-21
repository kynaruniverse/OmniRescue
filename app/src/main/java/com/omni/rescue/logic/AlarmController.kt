package com.omni.rescue.logic

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.omni.rescue.R
import java.util.concurrent.atomic.AtomicBoolean

class AlarmController(context: Context) {

    private val appContext = context.applicationContext

    private var mediaPlayer: MediaPlayer? = null
    private val isFlashing = AtomicBoolean(false)
    private var flashThread: Thread? = null
    private var originalVolume: Int = -1

    @Synchronized
    fun triggerAlarm() {
        if (mediaPlayer != null) return

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)

        mediaPlayer = MediaPlayer.create(appContext, R.raw.alarm_sound)?.apply {
            isLooping = true
            start()
        }

        val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        // True SOS pattern: 3 short, 3 long, 3 short (on/off in ms), repeat
        val sosPattern = longArrayOf(
            0, 200, 200,
            200, 200, 200,
            200, 200, 500,
            200, 500, 200,
            200, 500, 500,
            200, 200, 200,
            200, 200, 1000
        )
        vibrator.vibrate(VibrationEffect.createWaveform(sosPattern, 0))

        startFlashSOS()
    }

    @Synchronized
    fun stopAlarm() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("AlarmController", "MediaPlayer stop error", e)
        }

        val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.cancel()

        stopFlash()

        if (originalVolume >= 0) {
            try {
                val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            } catch (e: Exception) {
                Log.e("AlarmController", "Volume restore error", e)
            }
            originalVolume = -1
        }
    }

    private fun startFlashSOS() {
        if (!isFlashing.compareAndSet(false, true)) return

        val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull() ?: run {
            Log.e("AlarmController", "No camera found for flash")
            isFlashing.set(false)
            return
        }

        // SOS on/off durations in ms: S=200ms, O=500ms, gap=200ms, end pause=1000ms
        val sosPattern = listOf(
            200, 200, 200, 200, 200, 200,
            500, 200, 500, 200, 500, 200,
            200, 200, 200, 200, 200, 1000
        )

        flashThread = Thread {
            try {
                while (isFlashing.get()) {
                    sosPattern.forEachIndexed { index, duration ->
                        if (!isFlashing.get()) return@Thread
                        try {
                            cameraManager.setTorchMode(cameraId, index % 2 == 0)
                        } catch (e: Exception) {
                            Log.e("AlarmController", "Torch error", e)
                        }
                        try {
                            Thread.sleep(duration.toLong())
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        }
                    }
                }
            } finally {
                try { cameraManager.setTorchMode(cameraId, false) } catch (e: Exception) { }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopFlash() {
        isFlashing.set(false)
        flashThread?.interrupt()
        flashThread?.join(500)
        flashThread = null
    }
}