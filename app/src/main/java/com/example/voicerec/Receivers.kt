package com.example.voicerec

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Riceve i tasti multimediali (play/pausa dell'orologio) quando l'app non è in esecuzione:
 * Android la riavvia e il servizio può partire con il microfono perché l'avvio nasce da un tasto multimediale.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_MEDIA_BUTTON || !Audio.armed(c)) return
        @Suppress("DEPRECATION")
        val ke = i.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (ke.action != KeyEvent.ACTION_DOWN) return
        when (ke.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_HEADSETHOOK -> {}
            else -> return
        }
        try {
            ContextCompat.startForegroundService(
                c, Intent(c, RecorderService::class.java).setAction(RecorderService.ACTION_MEDIA_KEY)
            )
        } catch (e: Exception) {
            // Android non ha permesso l'avvio: niente arresto anomalo
        }
    }
}

/** Dopo un riavvio del telefono Android non permette di avviare il microfono da solo: avvisa con una notifica. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED || !Audio.armed(c)) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("boot", "Promemoria", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, 20, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            20,
            NotificationCompat.Builder(c, "boot")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Comando dall'orologio da riattivare")
                .setContentText("Tocca per riattivarlo dopo il riavvio del telefono")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
        )
    }
}
