package com.example.voicerec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder

/** Stato condiviso tra servizio e interfaccia. */
object TranscribeState {
    val modelReady = MutableStateFlow(false)
    val downloading = MutableStateFlow(false)
    val downloadProgress = MutableStateFlow(0f)
    val busyPath = MutableStateFlow<String?>(null)
    val progress = MutableStateFlow(0f)
    val message = MutableStateFlow("")
    val doneCounter = MutableStateFlow(0)
}

/**
 * Modello: NVIDIA Parakeet TDT 0.6B v3 (int8), 25 lingue europee tra cui l'italiano,
 * con punteggiatura e maiuscole. Si scarica una sola volta da GitHub (circa 490 MB).
 */
object ModelStore {
    const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2"
    private const val MODEL_BYTES = 487_211_443L
    const val DIR = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    private val NEEDED = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

    fun root(c: Context) = File(c.filesDir, "models")
    fun dir(c: Context) = File(root(c), DIR)
    fun isReady(c: Context) = NEEDED.all { n -> File(dir(c), n).let { it.exists() && it.length() > 0 } }

    private class Counting(i: InputStream) : FilterInputStream(i) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }

    fun download(c: Context, onProgress: (Float) -> Unit, cancelled: () -> Boolean) {
        val root = root(c)
        root.mkdirs()
        check(root.usableSpace > 900L * 1024 * 1024) { "Spazio insufficiente: servono circa 900 MB liberi" }
        val tmp = File(root, "tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "VoiceRec")
        try {
            check(conn.responseCode == 200) { "Download non riuscito (codice ${conn.responseCode})" }
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else MODEL_BYTES
            val counting = Counting(conn.inputStream)
            val buf = ByteArray(1 shl 16)
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(counting, 1 shl 16))).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    val base = e.name.substringAfterLast('/')
                    if (!e.isDirectory && base in NEEDED) {
                        File(tmp, base).outputStream().buffered().use { out ->
                            while (true) {
                                val n = tar.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                onProgress((counting.count.toFloat() / total).coerceIn(0f, 1f))
                                check(!cancelled()) { "Annullato" }
                            }
                        }
                    }
                    e = tar.nextEntry
                }
            }
            check(NEEDED.all { File(tmp, it).length() > 0 }) { "Archivio del modello incompleto" }
            dir(c).deleteRecursively()
            check(tmp.renameTo(dir(c))) { "Impossibile installare il modello" }
        } finally {
            conn.disconnect()
            tmp.deleteRecursively()
        }
    }
}

/** Decodifica M4A in audio mono a 16 kHz (float), a blocchi, con ricampionamento lineare. */
object AudioDecoder {
    fun decode(f: File, onChunk: (FloatArray) -> Unit, cancelled: () -> Boolean) {
        val ex = MediaExtractor()
        ex.setDataSource(f.absolutePath)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val tf = ex.getTrackFormat(i)
            if (tf.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = tf; break }
        }
        require(track >= 0 && fmt != null) { "File audio non valido" }
        ex.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        val out = FloatArray(16000)
        var outN = 0
        var nextOut = 0.0
        var idxIn = 0L
        var prev = 0f
        try {
            while (!outDone) {
                if (cancelled()) return
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = sb.remaining() / ch
                    val ratio = sr / 16000.0
                    for (fr in 0 until frames) {
                        var sum = 0
                        for (k in 0 until ch) sum += sb.get().toInt()
                        val cur = (sum / ch) / 32768f
                        while (nextOut <= idxIn) {
                            val frac = (nextOut - (idxIn - 1)).toFloat()
                            out[outN++] = prev + (cur - prev) * frac
                            nextOut += ratio
                            if (outN == out.size) { onChunk(out.copyOf()); outN = 0 }
                        }
                        prev = cur
                        idxIn++
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            if (outN > 0) onChunk(out.copyOf(outN))
        } finally {
            try { codec.stop() } catch (e: Exception) { }
            codec.release()
            ex.release()
        }
    }
}

object Transcriber {

    private fun ts(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** f è la registrazione cifrata. Ritorna true se completata, false se annullata. Il testo viene cifrato. */
    fun run(c: Context, f: File, onProgress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
        val plain = Secure.decryptToTemp(c, f, "m4a")
        val part = File(Secure.tmpDir(c), Storage.baseName(f) + ".txt.part")
        var rec: OfflineRecognizer? = null
        var vad: Vad? = null
        var writer: java.io.BufferedWriter? = null
        try {
            val dir = ModelStore.dir(c)
            val cfg = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder.int8.onnx").absolutePath,
                        joiner = File(dir, "joiner.int8.onnx").absolutePath,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    modelType = "nemo_transducer",
                    numThreads = 4,
                ),
            )
            val r = OfflineRecognizer(config = cfg)
            rec = r
            val v = Vad(
                assetManager = c.assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "silero_vad.onnx",
                        threshold = 0.25f,          // soglia bassa: non scarta le voci lontane
                        minSilenceDuration = 0.4f,
                        minSpeechDuration = 0.2f,
                        windowSize = 512,
                        maxSpeechDuration = 25f,
                    ),
                    sampleRate = 16000,
                    numThreads = 1,
                ),
            )
            vad = v
            val w = part.bufferedWriter()
            writer = w
            val totalMs = Storage.durationMs(f).coerceAtLeast(1L)
            var any = false
            var decoded = 0L

            fun handle(seg: SpeechSegment) {
                val s = r.createStream()
                s.acceptWaveform(seg.samples, 16000)
                r.decode(s)
                val text = r.getResult(s).text.trim()
                s.release()
                if (text.isNotEmpty()) {
                    w.write("[" + ts(seg.start / 16000) + "] " + text + "\n\n")
                    w.flush()
                    any = true
                }
            }

            fun pump() {
                while (!v.empty()) {
                    val seg = v.front()
                    v.pop()
                    handle(seg)
                }
            }

            AudioDecoder.decode(plain, { chunk ->
                v.acceptWaveform(chunk)
                decoded += chunk.size
                pump()
                onProgress((decoded * 1000f / 16000f / totalMs).coerceIn(0f, 1f))
            }, cancelled)
            if (cancelled()) return false
            v.flush()
            pump()
            if (!any) w.write("(Nessun parlato riconosciuto)\n")
            w.close()
            Secure.encryptTo(part, Storage.txtEnc(f))
            return true
        } finally {
            try { writer?.close() } catch (e: Exception) { }
            vad?.release()
            rec?.release()
            Secure.wipe(plain)
            Secure.wipe(part)
        }
    }
}

class TranscribeService : Service() {

    companion object {
        const val ACTION_DOWNLOAD = "com.example.voicerec.DOWNLOAD"
        const val ACTION_TRANSCRIBE = "com.example.voicerec.TRANSCRIBE"
        const val ACTION_CANCEL = "com.example.voicerec.CANCEL_WORK"
        const val EXTRA_PATH = "path"
        private const val CHANNEL = "work"
        private const val NOTIF_ID = 2
        private const val DONE_ID = 3
        @Volatile var cancelFlag = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastNotif = 0L
    private var title = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelFlag = true
                if (job?.isActive != true) stopSelf()
            }
            ACTION_DOWNLOAD -> begin("Download del modello") { download() }
            ACTION_TRANSCRIBE -> {
                val p = intent.getStringExtra(EXTRA_PATH)
                begin("Trascrizione in corso") { if (p != null) transcribe(File(p)) }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelFlag = true
        super.onDestroy()
    }

    private fun begin(t: String, block: () -> Unit) {
        title = t
        createChannel()
        ServiceCompat.startForeground(this, NOTIF_ID, notif(0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive == true) return
        cancelFlag = false
        TranscribeState.message.value = ""
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicerec:work")
            .also { it.acquire(6 * 60 * 60 * 1000L) }
        job = scope.launch {
            try {
                block()
            } catch (e: Throwable) {
                TranscribeState.message.value =
                    if (cancelFlag) "Operazione annullata" else "Errore: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                wake?.let { if (it.isHeld) it.release() }
                TranscribeState.downloading.value = false
                TranscribeState.busyPath.value = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                val m = TranscribeState.message.value
                if (m.isNotEmpty()) getSystemService(NotificationManager::class.java).notify(DONE_ID, doneNotif(m))
                stopSelf()
            }
        }
    }

    private fun download() {
        TranscribeState.downloading.value = true
        TranscribeState.downloadProgress.value = 0f
        ModelStore.download(this, { p ->
            TranscribeState.downloadProgress.value = p
            progressNotif(p)
        }, { cancelFlag })
        TranscribeState.modelReady.value = ModelStore.isReady(this)
        TranscribeState.message.value = "Modello installato: ora puoi trascrivere."
    }

    private fun transcribe(f: File) {
        if (!ModelStore.isReady(this)) {
            TranscribeState.message.value = "Scarica prima il modello."
            return
        }
        TranscribeState.busyPath.value = f.absolutePath
        TranscribeState.progress.value = 0f
        val ok = Transcriber.run(this, f, { p ->
            TranscribeState.progress.value = p
            progressNotif(p)
        }, { cancelFlag })
        if (ok) {
            TranscribeState.doneCounter.value = TranscribeState.doneCounter.value + 1
            TranscribeState.message.value = "Trascrizione pronta: " + Storage.baseName(f)
        } else {
            TranscribeState.message.value = "Trascrizione annullata."
        }
    }

    private fun progressNotif(p: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotif < 1500) return
        lastNotif = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif((p * 100).toInt(), false))
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Trascrizione", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun notif(pct: Int, indeterminate: Boolean): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), flags)
        val cancel = PendingIntent.getService(
            this, 11, Intent(this, TranscribeService::class.java).setAction(ACTION_CANCEL), flags
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "Avvio..." else "$pct%")
            .setProgress(100, pct, indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Annulla", cancel)
            .build()
    }

    private fun doneNotif(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 12, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Registratore")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }
}
