package com.example.voicerec

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Process
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Registra in M4A (AAC-LC) mono.
 *
 * - denoise = true: 16 kHz a 64 kbps (qualità voce, circa 29 MB all'ora). Il volume delle voci lontane viene
 *   alzato, poi un modello di intelligenza artificiale (DPDFNet, in tempo reale e sul telefono) toglie rumori
 *   e sottofondo tenendo le voci; infine un secondo controllo di volume riporta tutto a un livello alto.
 * - denoise = false: 48 kHz a 128 kbps (qualità massima, circa 57 MB all'ora), con tutto il rumore di fondo.
 *
 * Due thread: uno legge il microfono senza mai fermarsi, l'altro elabora e codifica. Se l'elaborazione non
 * regge il tempo reale, la riduzione del rumore si disattiva da sola e dalla registrazione dopo si passa a un
 * modello più leggero.
 */
class AacRecorder(private val ctx: Context, private val outFile: File, private val denoise: Boolean) {

    companion object {
        const val BITRATE_HQ = 64_000
        const val BITRATE_VOICE = 48_000
        private const val MAX_QUEUE = 3000 // 60 secondi di audio in attesa
    }

    private var sr = 48000
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var capThread: Thread? = null
    private var procThread: Thread? = null
    private val queue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var running = false
    @Volatile private var captureDone = false
    @Volatile private var gotData = false
    private var track = -1
    private var muxerStarted = false
    private val info = MediaCodec.BufferInfo()

    fun start() {
        val am = ctx.getSystemService(AudioManager::class.java)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = if (unprocessed)
            listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        else listOf(MediaRecorder.AudioSource.MIC)
        val rates = if (denoise) intArrayOf(16000) else intArrayOf(48000, 44100)

        var rec: AudioRecord? = null
        search@ for (rate in rates) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val size = maxOf(min * 4, rate)
            for (src in sources) {
                val r = AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; sr = rate; break@search } else r.release()
            }
        }
        requireNotNull(rec) { "Impossibile aprire il microfono" }
        record = rec

        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (denoise) BITRATE_VOICE else BITRATE_HQ)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // se il telefono lo supporta, "zoom" del microfono verso le voci lontane
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    rec.setPreferredMicrophoneFieldDimension(1.0f)
                }
            } catch (e: Throwable) {
            }
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microfono occupato" }
        } catch (e: Exception) {
            cleanup()
            throw e
        }
        running = true
        capThread = Thread { captureLoop() }.also { it.start() }
        procThread = Thread { processLoop() }.also { it.start() }
    }

    /** Ferma e chiude il file. Ritorna true se contiene audio. */
    fun stop(): Boolean {
        running = false
        capThread?.join(3000)
        procThread?.join(90_000)
        return gotData
    }

    // ---------- thread 1: lettura del microfono ----------

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rec = record ?: return
        val block = sr / 50 // 20 ms
        try {
            while (running) {
                val buf = ShortArray(block)
                val n = rec.read(buf, 0, block)
                if (n < 0) break
                if (n == 0) continue
                if (queue.size < MAX_QUEUE) queue.offer(if (n == block) buf else buf.copyOf(n))
            }
        } catch (e: Exception) {
            // si salva comunque quello che è stato registrato finora
        } finally {
            try { rec.stop() } catch (e: Exception) { }
            captureDone = true
        }
    }

    // ---------- thread 2: volume, riduzione rumore, codifica ----------

    private fun createDenoiser(): OnlineSpeechDenoiser? {
        val mc = OfflineSpeechDenoiserModelConfig(numThreads = 1)
        when (Audio.level(ctx)) {
            0 -> mc.dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig(model = "dpdfnet4.onnx")
            1 -> mc.dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig(model = "dpdfnet2.onnx") // più leggero, qualità simile
            else -> return null
        }
        return OnlineSpeechDenoiser(assetManager = ctx.assets, config = OnlineSpeechDenoiserConfig(model = mc))
    }

    private fun toShorts(f: FloatArray) =
        ShortArray(f.size) { (f[it] * 32768f).coerceIn(-32768f, 32767f).toInt().toShort() }

    private fun processLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val c = codec
        if (c == null) { cleanup(); return }
        var den: OnlineSpeechDenoiser? = null
        if (denoise) den = try { createDenoiser() } catch (e: Throwable) { null }
        // livelli per sensibilità: guadagno a riposo e massimo (prima e dopo il filtro del rumore)
        val s = Audio.sens(ctx)
        val preRest = floatArrayOf(4f, 10f, 20f)[s.coerceIn(0, 2)]
        val preMax = floatArrayOf(30f, 60f, 100f)[s.coerceIn(0, 2)]
        val postRest = floatArrayOf(1f, 2f, 3f)[s.coerceIn(0, 2)]
        val postMax = floatArrayOf(4f, 10f, 20f)[s.coerceIn(0, 2)]
        val pre = Agc(sr, 4500f, preMax, 15f, true, preRest)
        val post = Agc(sr, 4500f, postMax, 10f, false, postRest)
        var outSamples = 0L
        var bypass = false
        // Memoria dei campioni "asciutti" (dopo il volume): al segnale filtrato si mescola il 15% dell'originale,
        // così le voci deboli non vengono mai tagliate del tutto e l'audio non "si interrompe".
        // Il filtro ritarda il suono di 640 campioni, che qui vengono compensati.
        val histSize = 8192
        val hist = ShortArray(histSize)
        var inCount = 0L
        var outCount = 0L
        val wet = 0.85f
        fun mix(w: FloatArray): ShortArray {
            val res = ShortArray(w.size)
            for (i in w.indices) {
                val d = outCount + i - 640
                val dry = if (d >= 0 && d < inCount && inCount - d <= histSize) hist[(d % histSize).toInt()].toFloat() else 0f
                res[i] = (w[i] * 32768f * wet + dry * (1f - wet)).coerceIn(-32768f, 32767f).toInt().toShort()
            }
            outCount += w.size
            return res
        }
        try {
            while (true) {
                val blk = queue.poll(100, TimeUnit.MILLISECONDS)
                if (blk == null) { if (captureDone) break else continue }
                pre.process(blk, blk.size)
                for (v in blk) { hist[(inCount % histSize).toInt()] = v; inCount++ }
                if (den != null && !bypass && queue.size > 1500) {
                    bypass = true // troppo lento: da qui in poi niente filtro; la prossima volta si usa un modello più leggero
                    Audio.slower(ctx)
                }
                var out = blk
                if (den != null && !bypass) {
                    val f = FloatArray(blk.size) { blk[it] / 32768f }
                    out = mix(den.run(f, sr).samples)
                }
                outSamples = emit(c, out, post, outSamples)
            }
            if (den != null && !bypass) outSamples = emit(c, mix(den.flush().samples), post, outSamples)
            feed(c, ShortArray(0), 0, outSamples, true)
            drain(c, true)
        } catch (e: Throwable) {
            // si chiude il file con quello che c'è
        } finally {
            try { den?.release() } catch (e: Throwable) { }
            cleanup()
        }
    }

    private fun emit(c: MediaCodec, s: ShortArray, post: Agc, start: Long): Long {
        if (s.isEmpty()) return start
        post.process(s, s.size)
        var sum = 0.0
        for (v in s) sum += (v * v).toDouble()
        RecorderState.level.value = sqrt(sum / s.size).toFloat() / 32768f
        feed(c, s, s.size, start, false)
        drain(c, false)
        return start + s.size
    }

    private fun feed(c: MediaCodec, pcm: ShortArray, n: Int, startSample: Long, eos: Boolean) {
        var off = 0
        do {
            val take = minOf(n - off, 8192)
            var idx = c.dequeueInputBuffer(10_000)
            var tries = 0
            while (idx < 0) {
                drain(c, false)
                idx = c.dequeueInputBuffer(10_000)
                if (++tries > 500) throw IllegalStateException("Codec bloccato")
            }
            val bb = c.getInputBuffer(idx)!!
            bb.clear()
            if (take > 0) bb.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, off, take)
            val last = eos && off + take >= n
            val pts = (startSample + off) * 1_000_000L / sr
            c.queueInputBuffer(idx, 0, take * 2, pts, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            off += take
        } while (off < n)
    }

    private fun drain(c: MediaCodec, wait: Boolean): Boolean {
        var tries = 0
        while (true) {
            val idx = c.dequeueOutputBuffer(info, if (wait) 10_000L else 0L)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!wait || ++tries > 500) return false else continue
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val m = muxer ?: return false
                track = m.addTrack(c.outputFormat)
                m.start()
                muxerStarted = true
                continue
            }
            if (idx < 0) continue
            val out = c.getOutputBuffer(idx)!!
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
            if (info.size > 0 && muxerStarted) {
                out.position(info.offset)
                out.limit(info.offset + info.size)
                muxer?.writeSampleData(track, out, info)
                gotData = true
            }
            c.releaseOutputBuffer(idx, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
        }
    }

    private fun cleanup() {
        try { record?.stop() } catch (e: Exception) { }
        try { record?.release() } catch (e: Exception) { }
        record = null
        try { codec?.stop() } catch (e: Exception) { }
        try { codec?.release() } catch (e: Exception) { }
        codec = null
        try { if (muxerStarted) muxer?.stop() } catch (e: Exception) { gotData = false }
        try { muxer?.release() } catch (e: Exception) { }
        muxer = null
        RecorderState.level.value = 0f
    }

    /**
     * Controllo automatico del volume con limitatore morbido, più (opzionale) filtro passa-alto a 80 Hz.
     * Stima di continuo il rumore di fondo: il guadagno sale solo quando c'è parlato sopra il rumore
     * e resta fermo nei silenzi.
     */
    private class Agc(
        sr: Int,
        private val target: Float,
        private val maxGain: Float,
        private val minGate: Float,
        private val hpf: Boolean,
        private val rest: Float,
    ) {
        private val blk = sr / 50
        private var gain = rest
        private var noise = 200f
        private val f = FloatArray(blk)

        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            val w0 = 2.0 * PI * 80.0 / sr
            val alpha = sin(w0) / (2.0 * 0.7071)
            val c = cos(w0)
            val a0 = 1.0 + alpha
            b0 = ((1.0 + c) / 2.0) / a0
            b1 = (-(1.0 + c)) / a0
            b2 = ((1.0 + c) / 2.0) / a0
            a1 = (-2.0 * c) / a0
            a2 = (1.0 - alpha) / a0
        }

        private fun soft(x: Float): Float {
            val t = 26000f
            val a = abs(x)
            if (a <= t) return x
            val y = t + (32767f - t) * tanh((a - t) / (32767f - t))
            return if (x < 0) -y else y
        }

        fun process(b: ShortArray, n: Int) {
            var off = 0
            while (off < n) {
                val m = minOf(blk, n - off)
                block(b, off, m)
                off += m
            }
        }

        private fun block(b: ShortArray, off: Int, n: Int) {
            var sum = 0.0
            var peak = 0f
            for (i in 0 until n) {
                val x = b[off + i].toDouble()
                val v: Float
                if (hpf) {
                    val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                    x2 = x1; x1 = x
                    y2 = y1; y1 = y
                    v = y.toFloat()
                } else {
                    v = x.toFloat()
                }
                f[i] = v
                sum += (v * v).toDouble()
                val a = abs(v)
                if (a > peak) peak = a
            }
            val rms = sqrt(sum / n).toFloat()
            noise = if (rms < noise) rms else noise + (rms - noise) * 0.001f
            val gate = maxOf(minGate, noise * 1.4f)

            val old = gain
            if (rms > gate) {
                // c'è una voce sopra il rumore: porta il volume al livello voluto (scende subito, sale in fretta)
                val desired = (target / rms).coerceIn(if (hpf) 1f else 0.5f, maxGain)
                gain += (desired - gain) * (if (desired < gain) 0.5f else 0.06f)
            } else {
                // silenzio: il volume torna al livello di ascolto, così anche una voce lontana e debole parte già amplificata
                gain += (rest - gain) * 0.012f
            }
            if (peak > 0f && peak * gain > 30000f) gain = 30000f / peak
            val startGain = if (gain < old) gain else old
            for (i in 0 until n) {
                val g = startGain + (gain - startGain) * (i + 1) / n
                b[off + i] = soft(f[i] * g).coerceIn(-32768f, 32767f).toInt().toShort()
            }
        }
    }
}
