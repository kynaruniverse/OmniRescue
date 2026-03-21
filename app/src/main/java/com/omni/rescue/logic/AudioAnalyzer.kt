package com.omni.rescue.logic

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.omni.rescue.data.local.AppPreferences
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * AudioAnalyzer — streaming keyword-spotting using the wake_word_model.tflite.
 *
 * Model facts (parsed from flatbuffer):
 *   Input:  shape=[1, 1, 40], type=INT8
 *           scale=0.10202206, zero_point=-128
 *   Output: shape=[1, 1],     type=UINT8
 *           scale=0.00390625, zero_point=0
 *
 * The model is a STREAMING model. It expects ONE FRAME of 40 log-mel filterbank
 * features at a time (not raw PCM). It maintains its own internal state across
 * calls via the 11 stream_XX/states tensors.
 *
 * Pipeline per 10 ms hop (160 new PCM samples at 16 kHz):
 *   1. Append 160 samples to a 400-sample ring buffer (25 ms frame).
 *   2. Apply Hann window.
 *   3. Compute FFT (512-point, zero-padded).
 *   4. Apply 40 triangular mel filter banks (80 Hz – 7600 Hz).
 *   5. Take log of each bank energy.
 *   6. Quantize to INT8: q = clamp(round(val / 0.10202206) + (-128), -128, 127)
 *   7. Run interpreter with input shape [1, 1, 40].
 *   8. Dequantize UINT8 output: score = 0.00390625 * uint8_val
 *   9. If score > prefs.sensitivity → trigger alarm (once per session).
 */
class AudioAnalyzer(
    context: Context,
    private val onTriggerDetected: () -> Unit
) {
    private val appContext = context.applicationContext
    private val prefs = AppPreferences(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private var interpreter: Interpreter? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private val alarmTriggered = AtomicBoolean(false)

    // Audio config
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        .coerceAtLeast(3200)
    private val recordBufSize = minBuf * 2

    // Framing: 25 ms frame, 10 ms hop
    private val frameSize = 400   // 25 ms at 16 kHz
    private val hopSize = 160     // 10 ms at 16 kHz
    private val fftSize = 512
    private val numMelBins = 40

    // Quantization constants (from model flatbuffer)
    private val inputScale = 0.10202205926179886f
    private val inputZeroPoint = -128
    private val outputScale = 0.00390625f
    private val outputZeroPoint = 0

    // Frame buffer: holds the last `frameSize` samples
    private val frameBuf = ShortArray(frameSize)
    private var frameBufFill = 0  // how many samples currently in frameBuf

    // Pre-computed mel filterbank matrix [numMelBins x (fftSize/2+1)]
    private val melFilterbank: Array<FloatArray> = buildMelFilterbank()

    // Pre-computed Hann window
    private val hannWindow: FloatArray = FloatArray(frameSize) { i ->
        (0.5f * (1.0 - cos(2.0 * PI * i / (frameSize - 1)))).toFloat()
    }

    // Reusable buffers
    private val fftReal = FloatArray(fftSize)
    private val fftImag = FloatArray(fftSize)
    private val melFeatures = FloatArray(numMelBins)
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 1 * numMelBins)
        .order(ByteOrder.nativeOrder())
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 1)
        .order(ByteOrder.nativeOrder())

    companion object {
        private const val TAG = "AudioAnalyzer"
        private const val MEL_LOW_HZ = 80.0
        private const val MEL_HIGH_HZ = 7600.0
    }

    init {
        loadModel()
    }

    // ── Model loading ─────────────────────────────────────────────────────────

    private fun loadModel() {
        try {
            val fd = appContext.assets.openFd("models/wake_word_model.tflite")
            val buf = FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            val options = Interpreter.Options().apply { setNumThreads(1) }
            interpreter = Interpreter(buf, options)
            Log.d(TAG, "Model loaded. " +
                "Input: ${interpreter?.getInputTensor(0)?.shape()?.contentToString()} " +
                "type=${interpreter?.getInputTensor(0)?.dataType()}, " +
                "Output: ${interpreter?.getOutputTensor(0)?.shape()?.contentToString()} " +
                "type=${interpreter?.getOutputTensor(0)?.dataType()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startListening() {
        if (!isRecording.compareAndSet(false, true)) return

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, audioEncoding, recordBufSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            isRecording.set(false)
            return
        }

        alarmTriggered.set(false)
        frameBufFill = 0
        audioRecord = record
        record.startRecording()

        recordingThread = Thread({ processAudio() }, "AudioAnalyzer-Thread")
            .also { it.isDaemon = true; it.start() }

        Log.i(TAG, "Listening started")
    }

    fun stopListening() {
        isRecording.set(false)
        recordingThread?.interrupt()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordingThread = null
        Log.i(TAG, "Listening stopped")
    }

    fun resetAlarmGate() {
        alarmTriggered.set(false)
    }

    fun release() {
        stopListening()
        interpreter?.close()
        interpreter = null
    }

    // ── Audio processing loop ─────────────────────────────────────────────────

    private fun processAudio() {
        val readBuf = ShortArray(hopSize * 4)
        while (isRecording.get() && !Thread.currentThread().isInterrupted) {
            val read = audioRecord?.read(readBuf, 0, readBuf.size) ?: break
            when {
                read > 0 -> ingestSamples(readBuf, read)
                read == AudioRecord.ERROR_BAD_VALUE ->
                    Log.w(TAG, "AudioRecord ERROR_BAD_VALUE")
                read == AudioRecord.ERROR_INVALID_OPERATION ->
                    Log.w(TAG, "AudioRecord ERROR_INVALID_OPERATION")
                read < 0 -> Log.w(TAG, "AudioRecord error: $read")
            }
        }
    }

    /**
     * Push new PCM samples into the frame buffer.
     * Every time we accumulate `hopSize` new samples AND have a full frame,
     * we run one inference step.
     */
    private fun ingestSamples(buf: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            // Fill the frame buffer up to frameSize
            val canWrite = min(frameSize - frameBufFill, count - i)
            for (j in 0 until canWrite) {
                frameBuf[frameBufFill + j] = buf[i + j]
            }
            frameBufFill += canWrite
            i += canWrite

            if (frameBufFill == frameSize) {
                // We have a full frame — run inference
                runInferenceSafe(frameBuf)
                // Slide window: keep last (frameSize - hopSize) samples
                System.arraycopy(frameBuf, hopSize, frameBuf, 0, frameSize - hopSize)
                frameBufFill = frameSize - hopSize
            }
        }
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    private fun runInferenceSafe(frame: ShortArray) {
        try {
            val score = runInference(frame)
            Log.v(TAG, "Score: %.4f  threshold: %.2f".format(score, prefs.sensitivity))
            if (score > prefs.sensitivity && alarmTriggered.compareAndSet(false, true)) {
                Log.i(TAG, "Wake word DETECTED! score=$score")
                mainHandler.post { onTriggerDetected() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
        }
    }

    private fun runInference(frame: ShortArray): Float {
        val interp = interpreter ?: return 0f

        // 1. Compute 40 log-mel features from this frame
        computeLogMelFeatures(frame, melFeatures)

        // 2. Quantize features to INT8 and write into inputBuffer
        inputBuffer.rewind()
        for (f in melFeatures) {
            val q = (f / inputScale).let { Math.round(it) } + inputZeroPoint
            inputBuffer.put(q.coerceIn(-128, 127).toByte())
        }
        inputBuffer.rewind()

        // 3. Prepare output buffer (UINT8)
        outputBuffer.rewind()

        // 4. Run model: input shape [1,1,40], output shape [1,1]
        interp.run(inputBuffer, outputBuffer)

        // 5. Dequantize UINT8 output to float probability
        outputBuffer.rewind()
        val rawByte = outputBuffer.get().toInt() and 0xFF  // unsigned
        return outputScale * (rawByte - outputZeroPoint)
    }

    // ── Log-mel feature computation ───────────────────────────────────────────

    /**
     * Compute 40 log-mel filterbank features from a 400-sample (25ms) frame.
     * Steps: Hann window → zero-pad to 512 → FFT → power spectrum →
     *        40 mel filters → log
     */
    private fun computeLogMelFeatures(frame: ShortArray, out: FloatArray) {
        // Hann window + convert to float
        for (i in 0 until frameSize) {
            fftReal[i] = (frame[i] / 32768.0f) * hannWindow[i]
        }
        // Zero-pad to fftSize
        for (i in frameSize until fftSize) {
            fftReal[i] = 0f
        }
        fftImag.fill(0f)

        // In-place FFT
        fft(fftReal, fftImag, fftSize)

        // Apply mel filterbank and accumulate power
        val numBins = fftSize / 2 + 1  // 257
        for (m in 0 until numMelBins) {
            var energy = 0f
            val filter = melFilterbank[m]
            for (k in 0 until numBins) {
                val re = fftReal[k]
                val im = fftImag[k]
                energy += filter[k] * (re * re + im * im)
            }
            out[m] = ln(max(energy, 1e-10f))
        }
    }

    // ── Mel filterbank construction ───────────────────────────────────────────

    private fun buildMelFilterbank(): Array<FloatArray> {
        val numBins = fftSize / 2 + 1  // 257
        val filters = Array(numMelBins) { FloatArray(numBins) }

        fun hzToMel(hz: Double) = 2595.0 * Math.log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val melLow = hzToMel(MEL_LOW_HZ)
        val melHigh = hzToMel(MEL_HIGH_HZ)

        // numMelBins+2 equally spaced mel points
        val melPoints = DoubleArray(numMelBins + 2) { i ->
            melLow + i * (melHigh - melLow) / (numMelBins + 1)
        }
        // Convert to FFT bin indices
        val binPoints = IntArray(numMelBins + 2) { i ->
            Math.floor((fftSize + 1) * melToHz(melPoints[i]) / sampleRate).toInt()
                .coerceIn(0, numBins - 1)
        }

        for (m in 0 until numMelBins) {
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]
            for (k in left until center) {
                if (center != left) filters[m][k] = (k - left).toFloat() / (center - left)
            }
            for (k in center until right) {
                if (right != center) filters[m][k] = (right - k).toFloat() / (right - center)
            }
        }
        return filters
    }

    // ── Cooley-Tukey in-place FFT ─────────────────────────────────────────────

    /**
     * In-place radix-2 DIT FFT. Size must be a power of 2.
     * After this call, re[k] and im[k] hold the real and imaginary parts
     * of the k-th frequency bin.
     */
    private fun fft(re: FloatArray, im: FloatArray, n: Int) {
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        // Butterfly stages
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angStep = -2.0 * PI / len
            var k = 0
            while (k < n) {
                for (p in 0 until halfLen) {
                    val ang = angStep * p
                    val wr = cos(ang).toFloat()
                    val wi = kotlin.math.sin(ang).toFloat()
                    val ur = re[k + p]
                    val ui = im[k + p]
                    val vr = re[k + p + halfLen] * wr - im[k + p + halfLen] * wi
                    val vi = re[k + p + halfLen] * wi + im[k + p + halfLen] * wr
                    re[k + p] = ur + vr
                    im[k + p] = ui + vi
                    re[k + p + halfLen] = ur - vr
                    im[k + p + halfLen] = ui - vi
                }
                k += len
            }
            len = len shl 1
        }
    }
}