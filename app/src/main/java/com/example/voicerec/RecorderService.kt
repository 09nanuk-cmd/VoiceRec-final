package com.example.voicerec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stato condiviso tra servizio, interfaccia e tile (stesso processo). */
object RecorderState {
    val isRecording = MutableStateFlow(false)
    val startedAt = MutableStateFlow(0L) // SystemClock.elapsedRealtime()
    val armed = MutableStateFlow(false) // modalità orologio attiva
    val lastWatchEvent = MutableStateFlow("nessuno")
}

/** Archivio locale cifrato: i file sono leggibili solo da questa app su questo telefono. */
object Storage {
    fun dir(c: Context): File = File(c.filesDir, "vault").apply { mkdirs() }
    fun recTmp(c: Context): File = File(c.noBackupFilesDir, "rec_tmp").apply { mkdirs() }

    fun list(c: Context): List<File> =
        dir(c).listFiles { f -> f.name.endsWith(".m4a.enc") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun baseName(f: File) = f.name.removeSuffix(".m4a.enc")
    fun txtEnc(f: File) = File(f.parentFile, baseName(f) + ".txt.enc")
    private fun metaOf(f: File) = File(f.parentFile, baseName(f) + ".meta")
    private fun rcvOf(f: File) = File(f.parentFile, baseName(f) + ".rcv")
    fun isReceived(f: File) = rcvOf(f).exists()

    fun durationMs(f: File): Long =
        try { metaOf(f).readText().trim().toLong() } catch (e: Exception) { 0L }

    private fun durationOfPlain(plain: File): Long {
        val m = MediaMetadataRetriever()
        return try {
            m.setDataSource(plain.absolutePath)
            m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            m.release()
        }
    }

    fun uniqueBase(c: Context, base: String): String {
        var b = base
        var i = 2
        while (File(dir(c), "$b.m4a.enc").exists()) { b = "$base-$i"; i++ }
        return b
    }

    /** Cifra un M4A in chiaro, salva i dati accessori e cancella l'originale. */
    fun store(c: Context, plain: File, base: String, received: Boolean) {
        val d = dir(c)
        val dur = durationOfPlain(plain)
        Secure.encryptTo(plain, File(d, "$base.m4a.enc"))
        File(d, "$base.meta").writeText(dur.toString())
        if (received) File(d, "$base.rcv").writeText("1")
        Secure.wipe(plain)
    }

    fun delete(f: File) {
        f.delete()
        txtEnc(f).delete()
        metaOf(f).delete()
        rcvOf(f).delete()
    }

    fun rename(f: File, newBase: String): Boolean {
        val d = f.parentFile ?: return false
        val old = baseName(f)
        if (File(d, "$newBase.m4a.enc").exists()) return false
        for (ext in listOf("m4a.enc", "txt.enc", "meta", "rcv")) {
            val s = File(d, "$old.$ext")
            if (s.exists()) s.renameTo(File(d, "$newBase.$ext"))
        }
        return true
    }

    /** Registrazioni rimaste in chiaro per un arresto improvviso: vengono cifrate. */
    fun recoverLeftovers(c: Context) {
        recTmp(c).listFiles { x -> x.extension == "m4a" && x.length() > 0 }?.forEach {
            try { store(c, it, uniqueBase(c, it.nameWithoutExtension), false) } catch (e: Exception) { }
        }
    }

    /** Sposta nell'archivio cifrato le registrazioni create dalle versioni precedenti. */
    fun migrateLegacy(c: Context) {
        val olds = listOfNotNull(c.getExternalFilesDir(null)?.let { File(it, "Recordings") }, File(c.filesDir, "Recordings"))
        for (o in olds) {
            o.listFiles { x -> x.extension == "m4a" }?.forEach {
                try { store(c, it, uniqueBase(c, it.nameWithoutExtension), false) } catch (e: Exception) { }
            }
            o.listFiles()?.forEach { if (it.extension == "txt") it.delete() }
        }
    }
}

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.example.voicerec.svc.START"
        const val ACTION_STOP = "com.example.voicerec.STOP"
        const val ACTION_ARM = "com.example.voicerec.ARM"
        const val ACTION_DISARM = "com.example.voicerec.DISARM"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 1
    }

    private var recorder: MediaRecorder? = null
    private var aac: AacRecorder? = null
    private var file: File? = null
    private var wake: PowerManager.WakeLock? = null
    private var session: MediaSession? = null
    private var recWall = 0L
    private var lastToggle = 0L

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = fromWatch("play")
        override fun onPause() = fromWatch("pausa")
        override fun onStop() = fromWatch("stop")
        override fun onSkipToNext() = fromWatch("avanti")
        override fun onSkipToPrevious() = fromWatch("indietro")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_ARM -> arm()
            ACTION_DISARM -> disarm()
            else -> startRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseRecorder()
        session?.let { it.isActive = false; it.release() }
        session = null
        RecorderState.armed.value = false
        super.onDestroy()
    }

    // ---------- comandi dall'orologio ----------

    private fun fromWatch(name: String) {
        RecorderState.lastWatchEvent.value =
            name + " " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val now = SystemClock.elapsedRealtime()
        if (now - lastToggle < 1000) return // evita doppi eventi (giù/su del tasto)
        lastToggle = now
        if (RecorderState.isRecording.value) stopRecording() else startRecording()
    }

    private fun arm() {
        if (session != null) return
        createChannel()
        val s = MediaSession(this, "VoiceRecWatch")
        s.setCallback(callback)
        s.isActive = true
        session = s
        RecorderState.armed.value = true
        try {
            goForeground(RecorderState.isRecording.value)
        } catch (e: Exception) {
            disarm()
            return
        }
        updateSession()
    }

    private fun disarm() {
        session?.let { it.isActive = false; it.release() }
        session = null
        RecorderState.armed.value = false
        if (RecorderState.isRecording.value) {
            try { goForeground(true) } catch (e: Exception) { /* continua comunque */ }
        } else {
            leave()
        }
    }

    private fun updateSession() {
        val s = session ?: return
        val rec = RecorderState.isRecording.value
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, if (rec) "Registrazione in corso" else "Registratore")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, if (rec) "Premi pausa per salvare" else "Premi play per registrare")
                .build()
        )
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (rec) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f
                )
                .build()
        )
    }

    // ---------- registrazione ----------

    private fun startRecording() {
        if (RecorderState.isRecording.value) return
        createChannel()
        recWall = System.currentTimeMillis()
        try {
            goForeground(true)
        } catch (e: Exception) {
            if (session == null) leave()
            return
        }

        val name = "Rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val out = File(Storage.recTmp(this), "$name.m4a")
        file = out
        try {
            try {
                val a = AacRecorder(this, out)
                a.start()
                aac = a
            } catch (e: Exception) {
                aac = null
                val r = newRecorder()
                recorder = r
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(128_000)
                r.setOutputFile(out.absolutePath)
                r.prepare()
                r.start()
            }

            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicerec:rec")
                .also { it.acquire(12 * 60 * 60 * 1000L) }
            RecorderState.startedAt.value = SystemClock.elapsedRealtime()
            RecorderState.isRecording.value = true
            updateSession()
        } catch (e: Exception) {
            stopRecording()
        }
    }

    private fun stopRecording() {
        releaseRecorder()
        if (session != null) {
            try { goForeground(false) } catch (e: Exception) { /* ignora */ }
            updateSession()
        } else {
            leave()
        }
    }

    private fun releaseRecorder() {
        aac?.let { a -> if (!a.stop()) file?.delete() }
        aac = null
        recorder?.let { r ->
            try {
                r.stop()
            } catch (e: RuntimeException) {
                file?.delete() // registrazione vuota o interrotta
            }
            r.release()
        }
        val f = file
        recorder = null
        file = null
        RecorderState.isRecording.value = false
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        // Il file in chiaro esiste solo qui, nella cartella privata temporanea: lo cifriamo subito.
        if (f != null && f.exists() && f.length() > 0) {
            try {
                Storage.store(this, f, f.nameWithoutExtension, false)
            } catch (e: Exception) {
                // resta nella cartella temporanea e viene cifrato al prossimo avvio
            }
        }
    }

    private fun leave() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- notifica ----------

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Registrazione", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun goForeground(recording: Boolean) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (session != null) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(recording), type)
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun buildNotification(recording: Boolean): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
        if (recording) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), flags
            )
            b.setContentTitle("Registrazione in corso")
                .setContentText("Tocca per aprire l'app")
                .setUsesChronometer(true)
                .setWhen(recWall)
                .setShowWhen(true)
                .addAction(0, "Ferma e salva", stop)
        } else {
            val off = PendingIntent.getService(
                this, 2, Intent(this, RecorderService::class.java).setAction(ACTION_DISARM), flags
            )
            b.setContentTitle("In ascolto dall'orologio")
                .setContentText("Play/pausa sull'orologio avvia la registrazione")
                .addAction(0, "Disattiva", off)
        }
        return b.build()
    }
}
