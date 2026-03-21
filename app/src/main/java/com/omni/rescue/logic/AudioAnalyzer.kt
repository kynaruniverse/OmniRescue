package com.omni.rescue.logic

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private val prefs = AppPreferences(context)

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    private val windowSize = 16000
    private val hopSize = 8000
    private val audioBuffer = ShortArray(windowSize)
    private var bufferIndex = 0

    private var inputShape: IntArray? = null
    private var outputShape: IntArray? = null

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

            // Get input tensor details
            val inputTensor = interpreter?.getInputTensor(0)
            inputShape = inputTensor?.shape()
            Log.d("AudioAnalyzer", "Input shape: ${inputShape?.joinToString(", ")}")
            // Get output tensor details
            val outputTensor = interpreter?.getOutputTensor(0)
            outputShape = outputTensor?.shape()
            Log.d("AudioAnalyzer", "Output shape: ${outputShape?.joinToString(", ")}")

            mainHandler.post {
                Toast.makeText(context, "Model ready. Check logcat for shape.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("AudioAnalyzer", "Failed to load model", e)
            mainHandler.post { Toast.makeText(context, "Model error: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    fun startListening() {
        if (isRecording) return
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioAnalyzer", "AudioRecord failed to initialize")
                mainHandler.post { Toast.makeText(context, "AudioRecord init failed", Toast.LENGTH_LONG).show() }
                return
            }
            audioRecord?.startRecording()
            isRecording = true
            recordingThread = Thread { processAudio() }.also { it.start() }
            mainHandler.post { Toast.makeText(context, "Listening started", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            Log.e("AudioAnalyzer", "startListening error", e)
            mainHandler.post { Toast.makeText(context, "Start error: ${e.message}", Toast.LENGTH_LONG).show() }
        }
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
        var inferenceCount = 0
        while (isRecording) {
            val read = audioRecord?.read(tempBuffer, 0, tempBuffer.size) ?: 0
            if (read > 0) {
                for (i in 0 until read) {
                    audioBuffer[bufferIndex] = tempBuffer[i]
                    bufferIndex++
                    if (bufferIndex >= windowSize) {
                        inferenceCount++
                        try {
                            val score = runInference(audioBuffer)
                            if (score > prefs.sensitivity) {
                                onTriggerDetected()
                            }
                            if (inferenceCount % 10 == 0) {
                                mainHandler.post {
                                    Toast.makeText(context, "Score: $score", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AudioAnalyzer", "Inference error", e)
                            mainHandler.post { Toast.makeText(context, "Inference error: ${e.message}", Toast.LENGTH_LONG).show() }
                        }
                        System.arraycopy(audioBuffer, hopSize, audioBuffer, 0, windowSize - hopSize)
                        bufferIndex = windowSize - hopSize
                    }
                }
            } else if (read == -1) {
                mainHandler.post { Toast.makeText(context, "AudioRecord read error", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun runInference(audioData: ShortArray): Float {
        // Convert to float array
        val floatData = FloatArray(windowSize)
        for (i in audioData.indices) {
            floatData[i] = audioData[i] / 32768.0f
        }

        // Use local copies to avoid smart cast issues
        val shape = inputShape ?: return 0f
        val outShape = outputShape

        // Calculate expected number of elements
        val expectedElements = shape.reduce { acc, i -> acc * i }
        if (expectedElements != windowSize && expectedElements != windowSize * shape[0]) {
            Log.e("AudioAnalyzer", "Model expects $expectedElements elements, but we have $windowSize")
            return 0f
        }

        // Create input based on shape
        val input = when (shape.size) {
            1 -> floatData
            2 -> arrayOf(floatData)
            else -> {
                Log.e("AudioAnalyzer", "Unsupported input shape dimensions: ${shape.size}")
                return 0f
            }
        }

        // Create output buffer based on output shape
        val output = when (outShape?.size) {
            1 -> FloatArray(outShape[0])
            2 -> Array(outShape[0]) { FloatArray(outShape[1]) }
            else -> Array(1) { FloatArray(1) }  // fallback
        }

        // Run inference
        interpreter?.run(input, output)

        // Extract result as a float
        return when (output) {
            is FloatArray -> output[0]
            is Array<*> -> (output[0] as FloatArray)[0]
            else -> 0f
        }
    }
}
