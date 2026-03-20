package com.omni.rescue.logic

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class AudioAnalyzer(private val context: Context, private val onTriggerDetected: () -> Unit) {

    private var audioRecord: AudioRecord? = null
    private var interpreter: Interpreter? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    // The model expects a fixed window size (e.g., 1 second of audio = 16000 samples)
    private val windowSize = 16000   // 1 sec
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
        val tempBuffer = ShortArray(bufferSize / 2) // 16-bit => 2 bytes per sample
        while (isRecording) {
            val read = audioRecord?.read(tempBuffer, 0, tempBuffer.size) ?: 0
            if (read > 0) {
                for (i in 0 until read) {
                    audioBuffer[bufferIndex] = tempBuffer[i]
                    bufferIndex++
                    if (bufferIndex >= windowSize) {
                        // Buffer full → run inference
                        val score = runInference(audioBuffer)
                        if (score > AppPreferences(context).sensitivity) {
                            onTriggerDetected()
                        }
                        // Reset buffer for next window (with overlap optional)
                        bufferIndex = 0
                    }
                }
            }
        }
    }

    private fun runInference(audioData: ShortArray): Float {
        // Convert ShortArray to ByteBuffer in the format expected by the model.
        // microWakeWord models usually expect normalized float32 [-1,1] or int16.
        // We'll assume float32 for generality.
        val inputBuffer = ByteBuffer.allocateDirect(windowSize * 4) // 4 bytes per float
        inputBuffer.order(ByteOrder.nativeOrder())
        for (sample in audioData) {
            val floatVal = sample / 32768.0f   // normalize to [-1,1]
            inputBuffer.putFloat(floatVal)
        }
        inputBuffer.rewind()

        // Output: typically a single float (score) or array.
        val outputArray = Array(1) { FloatArray(1) }
        interpreter?.run(inputBuffer, outputArray)
        return outputArray[0][0]
    }
}