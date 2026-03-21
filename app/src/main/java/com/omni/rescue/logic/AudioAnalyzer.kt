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
            mainHandler.post { Toast.makeText(context, "Model loaded", Toast.LENGTH_SHORT).show() }
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
        while (isRecording) {
            val read = audioRecord?.read(tempBuffer, 0, tempBuffer.size) ?: 0
            if (read > 0) {
                for (i in 0 until read) {
                    audioBuffer[bufferIndex] = tempBuffer[i]
                    bufferIndex++
                    if (bufferIndex >= windowSize) {
                        try {
                            val score = runInference(audioBuffer)
                            // Show score if above 0.05 (so we see if any sound triggers)
                            if (score > 0.05f) {
                                mainHandler.post {
                                    Toast.makeText(context, "Score: $score", Toast.LENGTH_SHORT).show()
                                }
                            }
                            if (score > prefs.sensitivity) {
                                onTriggerDetected()
                            }
                        } catch (e: Exception) {
                            Log.e("AudioAnalyzer", "Inference error", e)
                        }
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
