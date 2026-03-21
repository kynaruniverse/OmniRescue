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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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
 *   1. Accumulate samples into a 480-sample frame buffer (30 ms).
 *   2. Pre-emphasis filter: y[n] = x[n] - 0.97 * x[n-1]
 *   3. Apply Hann window.
 *   4. Zero-pad to 512 and compute FFT.
 *   5. Apply 40 triangular mel filter banks (20 Hz – 7600 Hz).
 *   6. Log energy, shifted to [0, ~26] to match model quantization range.
 *   7. Quantize to INT8: q = clamp(round(val / 0.10202206) + (-128), -128, 127)
 *   8. Run interpreter with ByteBuffer input shape [1, 1, 40].
 *   9. Dequantize UINT8 output: score = 0.00390625 * uint8_val
 *  10. Broadcast score for live UI display.
 *  11. If score > prefs.sensitivity → trigger alarm (once per session).
 */
class AudioAnalyzer(
    context: Context,
    private val onTriggerDetected: () -> Unit,
    private val onScoreUpdate: ((Float) -> Unit)? = null
) {
    private val appContext = context.applicationContext
    private val prefs = AppPreferences(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private var interpreter: Interpreter? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private val alarmTriggered = AtomicBoolean(false)

    // ── Audio config ──────────────────────────────────────────────────────────

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        .coerceAtLeast(3200)
    private val recordBufSize = minBuf * 2

    // ── Framing ───────────────────────────────────────────────────────────────

    // 30 ms frame, 10 ms hop — kws_streaming standard for 16 kHz
    private val frameSize = 480   // 30 ms × 16000 = 480 samples
    private val hopSize   = 160   // 10 ms × 16000 = 160 samples
    private val fftSize   = 512
    private val numMelBins = 40

    // ── Quantization constants (read from model flatbuffer) ───────────────────

    private val inputScale      = 0.10202205926179886f
    private val inputZeroPoint  = -128
    private val outputScale     = 0.00390625f
    private val outputZeroPoint = 0

    // ── Buffers ───────────────────────────────────────────────────────────────

    // Sliding frame buffer — holds the most recent `frameSize` PCM samples
    private val frameBuf     = ShortArray(frameSize)
    private var frameBufFill = 0

    // Pre-computed mel filterbank matrix: [numMelBins][fftSize/2 + 1]
    private val melFilterbank: Array<FloatArray> = buildMelFilterbank()

    // Pre-computed Hann window coefficients
    private val hannWindow = FloatArray(frameSize) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (frameSize - 1)))).toFloat()
    }

    // Reusable working arrays (allocated once, never on hot path)
    private val fftReal     = FloatArray(fftSize)
    private val fftImag     = FloatArray(fftSize)
    private val melFeatures = FloatArray(numMelBins)

    // Direct ByteBuffers for TFLite (INT8 input, UINT8 output)
    private val inputBuffer  = ByteBuffer.allocateDirect(numMelBins).order(ByteOrder.nativeOrder())
    private val outputBuffer = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())

    companion object {
        private const val TAG         = "AudioAnalyzer"
        private const val MEL_LOW_HZ  = 20.0   // Hz — kws_streaming standard
        private const val MEL_HIGH_HZ = 7600.0  // Hz
    }

    init {
        loadModel()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Model loading
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadModel() {
        try {
            val fd  = appContext.assets.openFd("models/wake_word_model.tflite")
            val buf = FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            val options = Interpreter.Options().apply { setNumThreads(1) }
            interpreter = Interpreter(buf, options)
            Log.d(TAG, "Model loaded." +
                " Input: ${interpreter?.getInputTensor(0)?.shape()?.contentToString()}" +
                " type=${interpreter?.getInputTensor(0)?.dataType()}" +
                " | Output: ${interpreter?.getOutputTensor(0)?.shape()?.contentToString()}" +
                " type=${interpreter?.getOutputTensor(0)?.dataType()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

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
        audioRecord  = record
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
        audioRecord     = null
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

    // ─────────────────────────────────────────────────────────────────────────
    // Audio processing loop
    // ─────────────────────────────────────────────────────────────────────────

    private fun processAudio() {
        val readBuf = ShortArray(hopSize * 4)
        while (isRecording.get() && !Thread.currentThread().isInterrupted) {
            val read = audioRecord?.read(readBuf, 0, readBuf.size) ?: break
            when {
                read > 0                                  -> ingestSamples(readBuf, read)
                read == AudioRecord.ERROR_BAD_VALUE       -> Log.w(TAG, "AudioRecord ERROR_BAD_VALUE")
                read == AudioRecord.ERROR_INVALID_OPERATION -> Log.w(TAG, "AudioRecord ERROR_INVALID_OPERATION")
                read < 0                                  -> Log.w(TAG, "AudioRecord error: $read")
            }
        }
    }

    /**
     * Push [count] new samples from [buf] into the sliding frame buffer.
     * Every time the buffer is full (frameSize samples), run one inference
     * step then slide the window forward by hopSize samples.
     */
    private fun ingestSamples(buf: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            val canWrite = min(frameSize - frameBufFill, count - i)
            for (j in 0 until canWrite) {
                frameBuf[frameBufFill + j] = buf[i + j]
            }
            frameBufFill += canWrite
            i            += canWrite

            if (frameBufFill == frameSize) {
                runInferenceSafe(frameBuf)
                // Slide: discard oldest hopSize samples, keep the rest
                System.arraycopy(frameBuf, hopSize, frameBuf, 0, frameSize - hopSize)
                frameBufFill = frameSize - hopSize
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inference
    // ─────────────────────────────────────────────────────────────────────────

    private fun runInferenceSafe(frame: ShortArray) {
        try {
            val score = runInference(frame)
            Log.d(TAG, "Score: ${"%.4f".format(score)}  threshold: ${"%.2f".format(prefs.sensitivity)}")
            onScoreUpdate?.invoke(score)
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

        // Step 1 — compute 40 log-mel features for this frame
        computeLogMelFeatures(frame, melFeatures)

        // Step 2 — quantize float features → INT8 and load into ByteBuffer
        inputBuffer.rewind()
        for (f in melFeatures) {
            val q = Math.round(f / inputScale) + inputZeroPoint
            inputBuffer.put(q.coerceIn(-128, 127).toByte())
        }
        inputBuffer.rewind()

        // Step 3 — clear output buffer
        outputBuffer.rewind()

        // Step 4 — run model (input [1,1,40] INT8 → output [1,1] UINT8)
        interp.run(inputBuffer, outputBuffer)

        // Step 5 — dequantize UINT8 output → float probability [0.0, 1.0]
        outputBuffer.rewind()
        val rawByte = outputBuffer.get().toInt() and 0xFF   // treat as unsigned
        return outputScale * (rawByte - outputZeroPoint)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Log-mel feature computation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compute [numMelBins] log-mel filterbank features from one [frameSize]-sample frame.
     *
     * Steps:
     *   1. Pre-emphasis: y[n] = x[n] - 0.97 × x[n-1]
     *   2. Hann window
     *   3. Zero-pad to [fftSize] and compute in-place FFT
     *   4. Power spectrum × mel triangular filters
     *   5. ln(energy + 1e-6) then shift to [0, ~26] to match model quantization range
     *      (training used ln values centred ~−13; +26 maps that to ~[0, 26])
     */
    private fun computeLogMelFeatures(frame: ShortArray, out: FloatArray) {
        // 1. Pre-emphasis + Hann window → fftReal[]
        for (i in 0 until frameSize) {
            val emphasized = if (i == 0) {
                frame[i].toFloat()
            } else {
                frame[i].toFloat() - 0.97f * frame[i - 1].toFloat()
            }
            fftReal[i] = (emphasized / 32768.0f) * hannWindow[i]
        }

        // 2. Zero-pad remainder of fftReal[], clear fftImag[]
        for (i in frameSize until fftSize) {
            fftReal[i] = 0f
        }
        fftImag.fill(0f)

        // 3. In-place FFT
        fft(fftReal, fftImag, fftSize)

        // 4. Mel filterbank + log energy with shift
        val numBins = fftSize / 2 + 1  // 257 positive-frequency bins
        for (m in 0 until numMelBins) {
            var energy = 0f
            val filter = melFilterbank[m]
            for (k in 0 until numBins) {
                val re = fftReal[k]
                val im = fftImag[k]
                energy += filter[k] * (re * re + im * im)
            }
            // ln(energy + floor) then +26 offset to place values in [0, ~26]
            val logEnergy = ln(max(energy, 1e-6f))
            out[m] = max(0f, logEnergy + 26f)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mel filterbank construction
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildMelFilterbank(): Array<FloatArray> {
        val numBins = fftSize / 2 + 1  // 257
        val filters = Array(numMelBins) { FloatArray(numBins) }

        fun hzToMel(hz: Double) = 2595.0 * Math.log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val melLow  = hzToMel(MEL_LOW_HZ)
        val melHigh = hzToMel(MEL_HIGH_HZ)

        // (numMelBins + 2) equally-spaced mel-scale centre points
        val melPoints = DoubleArray(numMelBins + 2) { i ->
            melLow + i * (melHigh - melLow) / (numMelBins + 1)
        }

        // Map each mel-scale point to the nearest FFT bin index
        val binPoints = IntArray(numMelBins + 2) { i ->
            Math.floor((fftSize + 1) * melToHz(melPoints[i]) / sampleRate)
                .toInt().coerceIn(0, numBins - 1)
        }

        // Build triangular filters
        for (m in 0 until numMelBins) {
            val left   = binPoints[m]
            val center = binPoints[m + 1]
            val right  = binPoints[m + 2]
            // Rising slope
            for (k in left until center) {
                if (center != left) filters[m][k] = (k - left).toFloat() / (center - left)
            }
            // Falling slope
            for (k in center until right) {
                if (right != center) filters[m][k] = (right - k).toFloat() / (right - center)
            }
        }
        return filters
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cooley-Tukey in-place radix-2 DIT FFT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * In-place FFT. [n] must be a power of 2.
     * After the call, re[k]/im[k] hold real/imaginary parts of bin k.
     */
    private fun fft(re: FloatArray, im: FloatArray, n: Int) {
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
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
                    val wr  = cos(ang).toFloat()
                    val wi  = kotlin.math.sin(ang).toFloat()
                    val ur  = re[k + p]
                    val ui  = im[k + p]
                    val vr  = re[k + p + halfLen] * wr - im[k + p + halfLen] * wi
                    val vi  = re[k + p + halfLen] * wi + im[k + p + halfLen] * wr
                    re[k + p]           = ur + vr
                    im[k + p]           = ui + vi
                    re[k + p + halfLen] = ur - vr
                    im[k + p + halfLen] = ui - vi
                }
                k += len
            }
            len = len shl 1
        }
    }
}
