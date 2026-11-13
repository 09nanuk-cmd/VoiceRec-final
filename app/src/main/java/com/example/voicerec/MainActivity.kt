package com.example.voicerec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_START = "com.example.voicerec.START"
    }

    private var pending: Intent? = null
    private var fromTile = false
    private var importUri by mutableStateOf<Uri?>(null)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pending?.let { if (hasMic()) launchService(it) }
            pending = null
        }

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importUri = uri
        }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun svc(action: String) = Intent(this, RecorderService::class.java).setAction(action)

    private fun launchService(i: Intent) {
        ContextCompat.startForegroundService(this, i)
        if (fromTile) {
            fromTile = false
            moveTaskToBack(true)
        }
    }

    private fun requestRun(i: Intent) {
        val missing = buildList {
            if (!hasMic()) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) {
            launchService(i)
        } else {
            pending = i
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun batteryExempt() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openHealthSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.huawei.health")))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Huawei Health non trovata", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private val btLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun askBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    /** La prima volta che si accende il comando, chiede ad Android di non limitare l'app in background. */
    private fun askBatteryOnce() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (hasMic() && !pm.isIgnoringBatteryOptimizations(packageName) && !Audio.batteryAsked(this)) {
            Audio.setBatteryAsked(this)
            batteryExempt()
        }
    }

    private fun toggle() {
        if (RecorderState.isRecording.value) startService(svc(RecorderService.ACTION_STOP))
        else requestRun(svc(RecorderService.ACTION_START))
    }

    private fun setArmed(on: Boolean) {
        if (on) {
            requestRun(svc(RecorderService.ACTION_ARM))
            askBatteryOnce()
        }
        else startService(svc(RecorderService.ACTION_DISARM))
    }

    private fun work(action: String, path: String? = null) {
        val i = Intent(this, TranscribeService::class.java).setAction(action)
        if (path != null) i.putExtra(TranscribeService.EXTRA_PATH, path)
        ContextCompat.startForegroundService(this, i)
    }

    private fun shareFile(f: File, mime: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Condividi"))
    }

    /** Dal tile: avvia subito la registrazione (il codice non serve per registrare). */
    private fun handle(i: Intent?) {
        if (i != null && i.action == ACTION_START) {
            i.action = null
            if (!RecorderState.isRecording.value) {
                fromTile = true
                requestRun(svc(RecorderService.ACTION_START))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        enableEdgeToEdge()
        // niente schermate nelle app recenti né screenshot
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        TranscribeState.modelReady.value = ModelStore.isReady(this)
        RecorderState.denoise.value = Audio.denoise(this)
        RecorderState.sens.value = Audio.sens(this)
        Thread {
            Storage.migrateLegacy(this)
            if (!RecorderState.isRecording.value) Storage.recoverLeftovers(this)
            Secure.cleanup(this)
        }.start()
        handle(intent)
        // comando dall'orologio sempre attivo: se era acceso, si riattiva all'apertura dell'app
        if (Audio.armed(this) && !RecorderState.armed.value && hasMic()) launchService(svc(RecorderService.ACTION_ARM))
        RecorderService.instance?.healthCheck("apertura app")
        val actions = Actions(
            toggleRecord = { toggle() },
            setArmed = { setArmed(it) },
            batteryExempt = { batteryExempt() },
            askBluetooth = { askBluetooth() },
            openHealthSettings = { openHealthSettings() },
            setDenoise = { Audio.setDenoise(this, it); RecorderState.denoise.value = it },
            setSens = { Audio.setSens(this, it); RecorderState.sens.value = it },
            downloadModel = { work(TranscribeService.ACTION_DOWNLOAD) },
            transcribe = { work(TranscribeService.ACTION_TRANSCRIBE, it.absolutePath) },
            cancelWork = {
                startService(Intent(this, TranscribeService::class.java).setAction(TranscribeService.ACTION_CANCEL))
            },
            pickImport = { picker.launch(arrayOf("*/*")) },
            shareFile = { f, mime -> shareFile(f, mime) },
        )
        setContent { Root(actions, importUri) { importUri = null } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }
}
