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
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Registra in M4A (AAC-LC) mono a 48 kHz e 128 kbps (qualità alta, circa 57 MB all'ora).
 * - Microfono "non elaborato" (se disponibile): il telefono non scambia le voci lontane per rumore.
 * - Filtro anti-rimbombo a 80 Hz, poi controllo automatico del volume fino a circa +34 dB,
 *   con limitatore morbido: le voci lontane si sentono forti e quelle vicine non distorcono.
 */
class AacRecorder(private val ctx: Context, private val outFile: File) {

    companion object {
        const val BITRATE = 128_000
        private val RATES = intArrayOf(48000, 44100)
    }

    private var sr = 48000
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var thread: Thread? = null
    @Volatile private var running = false
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

        var rec: AudioRecord? = null
        search@ for (rate in RATES) {
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
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microfono occupato" }
        } catch (e: Exception) {
            cleanup()
            throw e
        }
        running = true
        thread = Thread { loop() }.also { it.start() }
    }

    /** Ferma e chiude il file. Ritorna true se contiene audio. */
    fun stop(): Boolean {
        running = false
        thread?.join(8000)
        return gotData
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rec = record ?: return
        val c = codec ?: return
        val block = sr / 50 // 20 ms
        val pcm = ShortArray(block)
        val agc = Agc(sr)
        var samples = 0L
        try {
            while (running) {
                val n = rec.read(pcm, 0, block)
                if (n < 0) break
                if (n == 0) continue
                agc.process(pcm, n)
                feed(c, pcm, n, samples, false)
                samples += n
                drain(c, false)
            }
            feed(c, pcm, 0, samples, true)
            drain(c, true)
        } catch (e: Exception) {
            // si salva comunque quello che è stato registrato finora
        } finally {
            cleanup()
        }
    }

    private fun feed(c: MediaCodec, pcm: ShortArray, n: Int, startSample: Long, eos: Boolean) {
        var idx = c.dequeueInputBuffer(10_000)
        var tries = 0
        while (idx < 0) {
            drain(c, false)
            idx = c.dequeueInputBuffer(10_000)
            if (++tries > 500) throw IllegalStateException("Codec bloccato")
        }
        val bb = c.getInputBuffer(idx)!!
        bb.clear()
        if (n > 0) bb.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, 0, n)
        val pts = startSample * 1_000_000L / sr
        c.queueInputBuffer(idx, 0, n * 2, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
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
    }

    /**
     * Filtro passa-alto a 80 Hz + controllo automatico del volume + limitatore morbido.
     * Il livello del rumore di fondo viene stimato di continuo: il guadagno sale solo quando c'è
     * davvero un parlato sopra il rumore, e resta fermo nei silenzi (niente fruscio gonfiato).
     */
    private class Agc(sr: Int) {
        private val target = 4500f       // livello medio voluto (circa -17 dBFS)
        private val maxGain = 50f        // fino a circa +34 dB
        private val minGate = 22f
        private var gain = 6f
        private var noise = 200f

        // biquad passa-alto (Butterworth, 80 Hz)
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0
        private val f = FloatArray(sr / 50 + 16)

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
            var sum = 0.0
            var peak = 0f
            for (i in 0 until n) {
                val x = b[i].toDouble()
                val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = x
                y2 = y1; y1 = y
                val v = y.toFloat()
                f[i] = v
                sum += (v * v).toDouble()
                val a = abs(v)
                if (a > peak) peak = a
            }
            val rms = sqrt(sum / n).toFloat()

            // stima del rumore di fondo: scende subito, risale lentamente
            noise = if (rms < noise) rms else noise + (rms - noise) * 0.001f
            val gate = maxOf(minGate, noise * 1.8f)

            val old = gain
            if (rms > gate) {
                val desired = (target / rms).coerceIn(1f, maxGain)
                gain += (desired - gain) * (if (desired < gain) 0.5f else 0.06f)
            }
            if (peak > 0f && peak * gain > 30000f) gain = 30000f / peak
            val startGain = if (gain < old) gain else old
            for (i in 0 until n) {
                val g = startGain + (gain - startGain) * (i + 1) / n
                b[i] = soft(f[i] * g).coerceIn(-32768f, 32767f).toInt().toShort()
            }
        }
    }
}
