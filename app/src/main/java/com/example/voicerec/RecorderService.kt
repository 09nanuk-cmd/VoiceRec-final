package com.example.voicerec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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

/** Archiviazione locale: cartella privata dell'app, nessun cloud. */
object Storage {
    fun dir(c: Context): File =
        File(c.getExternalFilesDir(null) ?: c.filesDir, "Recordings").apply { mkdirs() }

    fun list(c: Context): List<File> =
        dir(c).listFiles { f -> f.extension == "m4a" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun durationMs(f: File): Long {
        val m = MediaMetadataRetriever()
        return try {
            m.setDataSource(f.absolutePath)
            m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            m.release()
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
        val out = File(Storage.dir(this), "$name.m4a")
        val r = newRecorder()
        recorder = r
        file = out
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()

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
        recorder?.let { r ->
            try {
                r.stop()
            } catch (e: RuntimeException) {
                file?.delete() // registrazione vuota o interrotta
            }
            r.release()
        }
        recorder = null
        file = null
        RecorderState.isRecording.value = false
        wake?.let { if (it.isHeld) it.release() }
        wake = null
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
