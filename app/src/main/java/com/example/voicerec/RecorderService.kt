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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
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
    val denoise = MutableStateFlow(true)
    val level = MutableStateFlow(0f) // livello del microfono (0..1) per l'indicatore
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
        const val ACTION_MEDIA_KEY = "com.example.voicerec.MEDIA_KEY"
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
    private var silent: AudioTrack? = null
    private var silentThread: Thread? = null
    @Volatile private var silentRun = false
    private var playbackCb: AudioManager.AudioPlaybackCallback? = null
    private var lastPlayers = 0
    private var lastReassert = 0L
    private var focus: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private val keepTick = object : Runnable {
        override fun run() {
            if (session != null) {
                if (lastPlayers >= 2) reassert() // altre app stanno suonando: riprendi la priorità del tasto
                handler.postDelayed(this, 15000)
            }
        }
    }

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
            ACTION_MEDIA_KEY -> mediaKey()
            null -> { if (Audio.armed(this)) arm() else leave() } // riavvio automatico fatto dal sistema
            else -> startRecording()
        }
        return if (session != null) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSilent()
        unwatchOthers()
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

    // ---------- priorità del tasto dell'orologio e audio degli altri ----------

    /** Riproduce silenzio: Android considera l'app "attiva" e non la mette a dormire con lo schermo spento. */
    private fun startSilent() {
        if (silentRun) return
        try {
            val rate = 8000
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min, rate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            silent = t
            t.play()
            silentRun = true
            silentThread = Thread {
                val zeros = ShortArray(800)
                while (silentRun) {
                    try { t.write(zeros, 0, zeros.size) } catch (e: Exception) { break }
                }
            }.also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            silentRun = false
            silent = null
        }
    }

    private fun stopSilent() {
        silentRun = false
        try { silent?.stop() } catch (e: Exception) { }
        try { silentThread?.join(500) } catch (e: Exception) { }
        try { silent?.release() } catch (e: Exception) { }
        silent = null
        silentThread = null
    }

    /** Quando un'altra app inizia a suonare, il tasto multimediale andrebbe a lei: si riprende la priorità. */
    private fun watchOthers() {
        if (playbackCb != null) return
        val am = getSystemService(AudioManager::class.java)
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                val n = configs?.size ?: 0
                val now = SystemClock.elapsedRealtime()
                if (n > lastPlayers && n >= 2 && now - lastReassert > 2000) {
                    lastReassert = now
                    reassert()
                }
                lastPlayers = n
            }
        }
        am.registerAudioPlaybackCallback(cb, handler)
        playbackCb = cb
        handler.removeCallbacks(keepTick)
        handler.postDelayed(keepTick, 15000)
    }

    private fun unwatchOthers() {
        handler.removeCallbacks(keepTick)
        playbackCb?.let {
            try { getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(it) } catch (e: Exception) { }
        }
        playbackCb = null
        lastPlayers = 0
    }

    private fun reassert() {
        try { silent?.let { it.pause(); it.play() } } catch (e: Exception) { }
        session?.let { s ->
            try {
                s.setPlaybackState(
                    PlaybackState.Builder()
                        .setState(PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                        .build()
                )
            } catch (e: Exception) { }
        }
        updateSession()
    }

    /** Chiede il "focus audio": le altre app (Spotify, YouTube...) si fermano mentre registri. */
    private fun takeFocus() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(req)
            focus = req
        } catch (e: Exception) {
        }
    }

    private fun dropFocus() {
        try { focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } } catch (e: Exception) { }
        focus = null
    }

    private fun mediaButtonPi(): PendingIntent {
        val i = Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, MediaButtonReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(this, 0, i, flags)
    }

    /** Tasto arrivato dal ricevitore con l'app chiusa: riattiva il servizio e fa partire/fermare la registrazione. */
    private fun mediaKey() {
        createChannel()
        try {
            goForeground(RecorderState.isRecording.value)
        } catch (e: Exception) {
            notifyTapToRecord(this)
            stopSelf()
            return
        }
        if (session == null) arm()
        fromWatch("tasto")
    }

    private fun arm() {
        createChannel()
        if (session == null) {
            val s = MediaSession(this, "VoiceRecWatch")
            s.setCallback(callback)
            s.setMediaButtonReceiver(mediaButtonPi())
            s.isActive = true
            session = s
        }
        startSilent()
        watchOthers()
        RecorderState.armed.value = true
        Audio.setArmed(this, true)
        try {
            goForeground(RecorderState.isRecording.value)
        } catch (e: Exception) {
            dropSession() // la scelta dell'utente resta salvata: si riattiverà all'apertura dell'app
            leave()
            return
        }
        updateSession()
    }

    private fun dropSession() {
        stopSilent()
        unwatchOthers()
        session?.let { it.isActive = false; it.release() }
        session = null
        RecorderState.armed.value = false
    }

    private fun disarm() {
        Audio.setArmed(this, false)
        dropSession()
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
        if (RecorderState.isRecording.value) {
            try { goForeground(true) } catch (e: Exception) { }
            return
        }
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
                val a = AacRecorder(this, out, Audio.denoise(this))
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
                r.setAudioEncodingBitRate(64_000)
                r.setOutputFile(out.absolutePath)
                r.prepare()
                r.start()
            }

            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicerec:rec")
                .also { it.acquire(12 * 60 * 60 * 1000L) }
            RecorderState.startedAt.value = SystemClock.elapsedRealtime()
            RecorderState.isRecording.value = true
            takeFocus() // Spotify, YouTube e simili vengono messi in pausa
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
        dropFocus()
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
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(recording), type)
        } catch (e: Exception) {
            // riavvio dal sistema in background: senza microfono si resta comunque in ascolto dell'orologio
            if (session != null && !recording) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, buildNotification(false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                throw e
            }
        }
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
