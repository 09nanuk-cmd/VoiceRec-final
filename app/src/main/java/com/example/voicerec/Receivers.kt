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
            notifyTapToRecord(c) // Android non ha permesso l'avvio automatico
        }
    }
}

/** Se Android blocca l'avvio automatico del microfono, basta un tocco su questa notifica per registrare. */
fun notifyTapToRecord(c: Context) {
    val nm = c.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("alert", "Avvisi", NotificationManager.IMPORTANCE_HIGH))
    val i = Intent(c, MainActivity::class.java).setAction(MainActivity.ACTION_START).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val pi = PendingIntent.getActivity(c, 21, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    nm.notify(
        21,
        NotificationCompat.Builder(c, "alert")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Registrazione non partita")
            .setContentText("Tocca per avviarla subito")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
    )
}

/** Pianifica un controllo ogni 10 minuti anche con il telefono in riposo profondo. */
fun scheduleHealth(c: Context) {
    try {
        val am = c.getSystemService(android.app.AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            c, 30, Intent(c, HealthReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAndAllowWhileIdle(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 10 * 60 * 1000L, pi
        )
    } catch (e: Exception) {
    }
}

/** Avviso: il comando dall'orologio si è fermato e Android non lo lascia ripartire da solo. */
fun notifyReactivate(c: Context) {
    val nm = c.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("alert", "Avvisi", NotificationManager.IMPORTANCE_HIGH))
    val pi = PendingIntent.getActivity(
        c, 22, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    nm.notify(
        22,
        NotificationCompat.Builder(c, "alert")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Comando dall'orologio fermato")
            .setContentText("Tocca per riattivarlo")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
    )
}

/** Controllo periodico: se il servizio è ancora vivo lo fa ripartire dove serve, altrimenti lo riavvia o avvisa. */
class HealthReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!Audio.armed(c)) return
        scheduleHealth(c)
        val svc = RecorderService.instance
        if (svc != null) {
            svc.healthCheck("allarme")
            return
        }
        RecorderState.health.value = "servizio chiuso da Android: riavvio"
        Crumbs.add(c, "allarme: il servizio era chiuso, provo a riavviarlo")
        try {
            ContextCompat.startForegroundService(
                c, Intent(c, RecorderService::class.java).setAction(RecorderService.ACTION_ARM)
            )
        } catch (e: Exception) {
            notifyReactivate(c)
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
