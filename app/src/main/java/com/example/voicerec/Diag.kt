package com.example.voicerec

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Diario degli eventi importanti: serve a capire perché il comando dell'orologio smette di funzionare. */
object Crumbs {
    private fun f(c: Context) = File(c.filesDir, "crumbs.txt")

    @Synchronized
    fun add(c: Context, text: String) {
        try {
            val line = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date()) + "  " + text
            val old = if (f(c).exists()) f(c).readLines() else emptyList()
            f(c).writeText((old + line).takeLast(30).joinToString("\n"))
        } catch (e: Exception) {
        }
    }

    fun read(c: Context): List<String> = try { f(c).readLines() } catch (e: Exception) { emptyList() }
}

/** Salva l'errore se l'app si chiude per un problema. */
object CrashLog {
    @Volatile private var installed = false

    fun install(c: Context) {
        if (installed) return
        installed = true
        val ctx = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(ctx.filesDir, "last_crash.txt").writeText(
                    SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date()) + "\n" + e.stackTraceToString().take(2500)
                )
                Crumbs.add(ctx, "ERRORE dell'app: " + e.javaClass.simpleName + " " + (e.message ?: ""))
            } catch (x: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
    }
}

object Diag {
    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso eccessivo di risorse (fermata da Android)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "fermata dall'utente o dal gestore batteria"
        ApplicationExitInfo.REASON_USER_STOPPED -> "arresto forzato"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "poca memoria"
        ApplicationExitInfo.REASON_CRASH -> "errore dell'app (crash)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "errore nativo (crash)"
        ApplicationExitInfo.REASON_ANR -> "app bloccata (ANR)"
        ApplicationExitInfo.REASON_SIGNALED -> "terminata da Android"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dipendenza chiusa"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permesso cambiato"
        ApplicationExitInfo.REASON_EXIT_SELF -> "chiusa dall'app stessa"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "errore all'avvio"
        ApplicationExitInfo.REASON_OTHER -> "altro motivo di Android"
        else -> if (Build.VERSION.SDK_INT >= 33 && r == ApplicationExitInfo.REASON_FREEZER) "congelata da Android (risparmio energetico)"
        else "codice $r"
    }

    fun report(c: Context): String {
        val sb = StringBuilder()
        fun line(s: String) { sb.append(s).append('\n') }
        val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
        val pm = c.getSystemService(PowerManager::class.java)
        val am = c.getSystemService(ActivityManager::class.java)

        line("== Stato dell'app ==")
        line("Comando orologio acceso (scelta salvata): " + if (Audio.armed(c)) "sì" else "no")
        line("Servizio in esecuzione adesso: " + if (RecorderService.instance != null) "sì" else "NO")
        line("Ultimo controllo: " + RecorderState.health.value)
        line("Ultimo comando: " + RecorderState.lastWatchEvent.value)

        line("")
        line("== Impostazioni di Android ==")
        line("Esente dal risparmio batteria: " + if (pm.isIgnoringBatteryOptimizations(c.packageName)) "sì" else "NO  <-- tocca 'Batteria'")
        if (Build.VERSION.SDK_INT >= 28) {
            line("Limitata in background: " + if (am.isBackgroundRestricted) "SÌ  <-- problema" else "no")
            val b = c.getSystemService(UsageStatsManager::class.java).appStandbyBucket
            line(
                "Categoria di risparmio: " + when (b) {
                    10 -> "attiva"
                    20 -> "in uso"
                    30 -> "frequente"
                    40 -> "rara"
                    45 -> "LIMITATA  <-- problema"
                    else -> "codice $b"
                }
            )
        }
        line("Notifiche consentite: " + if (NotificationManagerCompat.from(c).areNotificationsEnabled()) "sì" else "NO")

        line("")
        line("== Orologio (Bluetooth) ==")
        val granted = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(c, "android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            line("Permesso Bluetooth non concesso: tocca 'Diagnosi', consentilo e poi 'Aggiorna'.")
        } else {
            try {
                val bm = c.getSystemService(BluetoothManager::class.java)
                @Suppress("MissingPermission")
                val names = bm.getConnectedDevices(BluetoothProfile.GATT).map { it.name ?: it.address }
                line("Dispositivi collegati ora: " + if (names.isEmpty()) "NESSUNO (l'orologio non risulta collegato)" else names.joinToString(", "))
            } catch (e: Exception) {
                line("Bluetooth non leggibile: " + (e.message ?: e.javaClass.simpleName))
            }
        }

        line("")
        line("== Chiusure recenti dell'app (decise da Android) ==")
        if (Build.VERSION.SDK_INT >= 30) {
            val list = am.getHistoricalProcessExitReasons(c.packageName, 0, 6)
            if (list.isEmpty()) line("nessuna chiusura registrata")
            for (e in list) {
                line(fmt.format(Date(e.timestamp)) + "  " + reasonName(e.reason) + (e.description?.let { " — $it" } ?: ""))
            }
        } else {
            line("non disponibile su questa versione di Android")
        }

        line("")
        line("== Ultimi eventi dell'app ==")
        val crumbs = Crumbs.read(c)
        if (crumbs.isEmpty()) line("nessuno")
        crumbs.takeLast(20).forEach { line(it) }
        val crash = File(c.filesDir, "last_crash.txt")
        if (crash.exists()) {
            line("")
            line("== Ultimo errore dell'app ==")
            line(crash.readText().take(900))
        }
        return sb.toString()
    }
}
