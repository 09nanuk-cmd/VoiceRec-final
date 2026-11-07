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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Registra in M4A (AAC-LC) mono 16 kHz a 48 kbps (circa 21 MB all'ora).
 * Il microfono "non elaborato" (se disponibile) evita che il telefono tratti le voci lontane
 * come rumore; un controllo automatico del volume le porta poi a un livello ben udibile.
 */
class AacRecorder(private val ctx: Context, private val outFile: File) {

    companion object {
        const val SR = 16000
        const val BITRATE = 48_000
        private const val BLOCK = 320 // 20 ms
    }

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
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "Microfono non disponibile" }
        val size = maxOf(min * 2, SR)

        val sources = if (unprocessed)
            listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        else listOf(MediaRecorder.AudioSource.MIC)
        var rec: AudioRecord? = null
        for (src in sources) {
            val r = AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break } else r.release()
        }
        requireNotNull(rec) { "Impossibile aprire il microfono" }
        record = rec

        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
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
        val pcm = ShortArray(BLOCK)
        val agc = Agc()
        var samples = 0L
        try {
            while (running) {
                val n = rec.read(pcm, 0, BLOCK)
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
        val pts = startSample * 1_000_000L / SR
        c.queueInputBuffer(idx, 0, n * 2, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
    }

    /** Svuota il codec verso il muxer. Con wait=true aspetta la fine del flusso. */
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

    /** Controllo automatico del volume: alza le voci lontane fino a +30 dB senza distorcere. */
    private class Agc {
        private var gain = 4f
        private val target = 3000f      // circa -20 dBFS
        private val maxGain = 30f
        private val noiseFloor = 35f    // sotto questo livello non alza oltre (evita di gonfiare il fruscio)

        fun process(b: ShortArray, n: Int) {
            var sum = 0.0
            var peak = 0
            for (i in 0 until n) {
                val v = b[i].toInt()
                sum += (v * v).toDouble()
                val a = abs(v)
                if (a > peak) peak = a
            }
            val rms = sqrt(sum / n).toFloat()
            val desired = if (rms < noiseFloor) gain else (target / rms).coerceIn(1f, maxGain)
            val old = gain
            gain += (desired - gain) * (if (desired < gain) 0.5f else 0.02f)
            if (peak > 0 && peak * gain > 30000f) gain = 30000f / peak
            val startGain = if (gain < old) gain else old
            for (i in 0 until n) {
                val g = startGain + (gain - startGain) * (i + 1) / n
                val v = b[i] * g
                b[i] = v.coerceIn(-32768f, 32767f).toInt().toShort()
            }
        }
    }
}
