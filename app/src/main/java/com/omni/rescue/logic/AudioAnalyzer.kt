package com.omni.rescue.logic

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.tensorflow.lite.Interpreter
import com.omni.rescue.data.local.AppPreferences
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

class AudioAnalyzer(
    context: Context,
    private val onTriggerDetected: () -> Unit
) {

    private val appContext = context.applicationContext
    private var audioRecord: AudioRecord? = null
    private var interpreter: Interpreter? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = AppPreferences(appContext)

    // Prevents the alarm firing more than once per listening session
    private val alarmTriggered = AtomicBoolean(false)

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        .coerceAtLeast(3200) * 2

    private val windowSize = 16000
    private val hopSize = 8000
    private val audioBuffer = ShortArray(windowSize)
    private var bufferIndex = 0

    private var inputShape: IntArray? = null
    private var outputShape: IntArray? = null

    companion object {
        private const val TAG = "AudioAnalyzer"
    }

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelFd = appContext.assets.openFd("models/wake_word_model.tflite")
            val buffer = FileInputStream(modelFd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, modelFd.startOffset, modelFd.declaredLength)
            interpreter = Interpreter(buffer)
            inputShape = interpreter?.getInputTensor(0)?.shape()
            outputShape = interpreter?.getOutputTensor(0)?.shape()
            Log.d(TAG, "Model loaded. Input: ${inputShape?.contentToString()}, Output: ${outputShape?.contentToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
        }
    }

    fun startListening() {
        if (!isRecording.compareAndSet(false, true)) return

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            isRecording.set(false)
            return
        }

        alarmTriggered.set(false)
        audioRecord = record
        record.startRecording()

        recordingThread = Thread({ processAudio() }, "AudioAnalyzer-Thread")
            .also { it.isDaemon = true; it.start() }

        Log.d(TAG, "Listening started")
    }

    fun stopListening() {
        isRecording.set(false)
        recordingThread?.interrupt()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordingThread = null
        Log.d(TAG, "Listening stopped")
    }

    fun resetAlarmGate() {
        alarmTriggered.set(false)
    }

    fun release() {
        stopListening()
        interpreter?.close()
        interpreter = null
    }

    private fun processAudio() {
        val tempBuffer = ShortArray(bufferSize / 2)
        while (isRecording.get() && !Thread.currentThread().isInterrupted) {
            val read = audioRecord?.read(tempBuffer, 0, tempBuffer.size) ?: break
            when {
                read > 0 -> processChunk(tempBuffer, read)
                read == AudioRecord.ERROR_BAD_VALUE ->
                    Log.w(TAG, "AudioRecord: ERROR_BAD_VALUE")
                read == AudioRecord.ERROR_INVALID_OPERATION ->
                    Log.w(TAG, "AudioRecord: ERROR_INVALID_OPERATION")
                read < 0 ->
                    Log.w(TAG, "AudioRecord: unknown error code $read")
            }
        }
    }

    private fun processChunk(tempBuffer: ShortArray, read: Int) {
        for (i in 0 until read) {
            audioBuffer[bufferIndex++] = tempBuffer[i]
            if (bufferIndex >= windowSize) {
                runInferenceSafe()
                System.arraycopy(audioBuffer, hopSize, audioBuffer, 0, windowSize - hopSize)
                bufferIndex = windowSize - hopSize
            }
        }
    }

    private fun runInferenceSafe() {
        try {
            val score = runInference(audioBuffer)
            Log.v(TAG, "Score: $score (threshold: ${prefs.sensitivity})")
            if (score > prefs.sensitivity && alarmTriggered.compareAndSet(false, true)) {
                Log.i(TAG, "Wake word detected! Score=$score")
                mainHandler.post { onTriggerDetected() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
        }
    }

    private fun runInference(audioData: ShortArray): Float {
        val interp = interpreter ?: return 0f
        val shape = inputShape ?: return 0f
        val outShape = outputShape ?: return 0f

        val floatData = FloatArray(windowSize) { audioData[it] / 32768.0f }

        val input: Any = when (shape.size) {
            1 -> floatData
            2 -> arrayOf(floatData)
            else -> {
                Log.e(TAG, "Unsupported input shape dimensions: ${shape.size}")
                return 0f
            }
        }

        val output: Any = when (outShape.size) {
            1 -> FloatArray(outShape[0])
            2 -> Array(outShape[0]) { FloatArray(outShape[1]) }
            else -> Array(1) { FloatArray(1) }
        }

        interp.run(input, output)

        return when (output) {
            is FloatArray -> output[0]
            is Array<*> -> (output[0] as? FloatArray)?.get(0) ?: 0f
            else -> 0f
        }
    }
}