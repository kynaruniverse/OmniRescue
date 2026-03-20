package com.omni.rescue.logic

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class AudioAnalyzer(context: Context, private val onDetection: (Float) -> Unit) {
    private val sampleRate = 16000
    private val audioBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var interpreter: Interpreter? = null
    private val modelInputSize = 16000 * 1 // 1 second of audio (adjust to model's expected size)
    private val modelOutputSize = 1 // binary classification (detection score)

    init {
        // Load TFLite model
        try {
            val modelBuffer = loadModelFile(context, "hey_jarvis.tflite")
            interpreter = Interpreter(modelBuffer)
            Log.d("AudioAnalyzer", "Model loaded successfully")
        } catch (e: Exception) {
            Log.e("AudioAnalyzer", "Failed to load model", e)
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun start() {
        if (interpreter == null) {
            Log.e("AudioAnalyzer", "No model loaded, cannot start")
            return
        }
        isListening = true
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            audioBufferSize
        )
        audioRecord?.startRecording()

        val audioBuffer = ShortArray(audioBufferSize)
        val ringBuffer = mutableListOf<Short>()
        val targetSamples = modelInputSize

        Thread {
            while (isListening) {
                val read = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (read > 0) {
                    for (i in 0 until read) {
                        ringBuffer.add(audioBuffer[i])
                    }
                    while (ringBuffer.size >= targetSamples) {
                        // Take the oldest targetSamples
                        val segment = ringBuffer.take(targetSamples).toShortArray()
                        // Convert to float [-1,1]
                        val floatArray = FloatArray(segment.size) { segment[it].toFloat() / Short.MAX_VALUE }
                        // Run inference
                        val inputTensor = TensorBuffer.createFixedSize(intArrayOf(1, targetSamples), org.tensorflow.lite.DataType.FLOAT32)
                        inputTensor.loadArray(floatArray)
                        val outputTensor = TensorBuffer.createFixedSize(intArrayOf(1, modelOutputSize), org.tensorflow.lite.DataType.FLOAT32)
                        interpreter?.run(inputTensor.buffer, outputTensor.buffer)
                        val score = outputTensor.floatArray[0]
                        // Callback with score
                        onDetection(score)
                        // Remove the processed segment from the ring buffer (overlap by 50% for smooth detection)
                        val shift = targetSamples / 2
                        repeat(shift) { if (ringBuffer.isNotEmpty()) ringBuffer.removeAt(0) }
                    }
                }
            }
        }.start()
    }

    fun stop() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun close() {
        interpreter?.close()
    }
}
