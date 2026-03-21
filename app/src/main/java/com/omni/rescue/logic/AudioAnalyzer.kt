package com.omni.rescue.logic

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.tensorflow.lite.Interpreter
import com.omni.rescue.data.local.AppPreferences
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class AudioAnalyzer(private val context: Context, private val onTriggerDetected: () -> Unit) {

    private var audioRecord: AudioRecord? = null
    private var interpreter: Interpreter? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private val prefs = AppPreferences(context)

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    // Model expects 1s window, but we'll use 0.5s overlap (50%)
    private val windowSize = 16000
    private val hopSize = 8000   // 0.5s
    private val audioBuffer = ShortArray(windowSize)
    private var bufferIndex = 0

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelFile = context.assets.openFd("models/wake_word_model.tflite")
            val inputStream = FileInputStream(modelFile.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = modelFile.startOffset
            val declaredLength = modelFile.declaredLength
            val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            interpreter = Interpreter(buffer)
        } catch (e: Exception) {
            Log.e("AudioAnalyzer", "Failed to load model", e)
        }
    }

    fun startListening() {
        if (isRecording) return
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioAnalyzer", "AudioRecord failed to initialize")
            return
        }
        audioRecord?.startRecording()
        isRecording = true
        recordingThread = Thread { processAudio() }.also { it.start() }
    }

    fun stopListening() {
        isRecording = false
        recordingThread?.join(1000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun processAudio() {
        val tempBuffer = ShortArray(bufferSize / 2)
        while (isRecording) {
            val read = audioRecord?.read(tempBuffer, 0, tempBuffer.size) ?: 0
            if (read > 0) {
                for (i in 0 until read) {
                    audioBuffer[bufferIndex] = tempBuffer[i]
                    bufferIndex++
                    if (bufferIndex >= windowSize) {
                        val score = runInference(audioBuffer)
                        if (score > prefs.sensitivity) {
                            onTriggerDetected()
                        }
                        // Shift buffer to keep last hopSize samples (overlap)
                        System.arraycopy(audioBuffer, hopSize, audioBuffer, 0, windowSize - hopSize)
                        bufferIndex = windowSize - hopSize
                    }
                }
            }
        }
    }

    private fun runInference(audioData: ShortArray): Float {
        val inputBuffer = ByteBuffer.allocateDirect(windowSize * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        for (sample in audioData) {
            val floatVal = sample / 32768.0f
            inputBuffer.putFloat(floatVal)
        }
        inputBuffer.rewind()

        val outputArray = Array(1) { FloatArray(1) }
        interpreter?.run(inputBuffer, outputArray)
        return outputArray[0][0]
    }
}
