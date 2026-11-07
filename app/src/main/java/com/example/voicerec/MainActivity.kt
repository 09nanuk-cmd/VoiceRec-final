package com.example.voicerec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private var wantStart = false
    private var internalLaunch = false
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
            internalLaunch = true
            pending = i
            permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun toggle() {
        if (RecorderState.isRecording.value) startService(svc(RecorderService.ACTION_STOP))
        else requestRun(svc(RecorderService.ACTION_START))
    }

    private fun setArmed(on: Boolean) {
        if (on) requestRun(svc(RecorderService.ACTION_ARM))
        else startService(svc(RecorderService.ACTION_DISARM))
    }

    private fun work(action: String, path: String? = null) {
        val i = Intent(this, TranscribeService::class.java).setAction(action)
        if (path != null) i.putExtra(TranscribeService.EXTRA_PATH, path)
        ContextCompat.startForegroundService(this, i)
    }

    private fun shareExport(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        internalLaunch = true
        startActivity(Intent.createChooser(send, "Condividi file protetto"))
    }

    /** Dopo lo sblocco: se arriviamo dal tile, avvia la registrazione (il codice è già stato inserito). */
    private fun afterUnlock() {
        if (wantStart) {
            wantStart = false
            if (!RecorderState.isRecording.value) requestRun(svc(RecorderService.ACTION_START)) else fromTile = false
        }
    }

    private fun handle(i: Intent?) {
        if (i != null && i.action == ACTION_START) {
            i.action = null
            fromTile = true
            wantStart = true
            if (LockState.unlocked.value) afterUnlock()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // niente schermate nelle app recenti né screenshot
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        TranscribeState.modelReady.value = ModelStore.isReady(this)
        Thread {
            Storage.migrateLegacy(this)
            if (!RecorderState.isRecording.value) Storage.recoverLeftovers(this)
            Secure.cleanup(this)
        }.start()
        handle(intent)
        val actions = Actions(
            toggleRecord = { toggle() },
            setArmed = { setArmed(it) },
            downloadModel = { work(TranscribeService.ACTION_DOWNLOAD) },
            transcribe = { work(TranscribeService.ACTION_TRANSCRIBE, it.absolutePath) },
            cancelWork = {
                startService(Intent(this, TranscribeService::class.java).setAction(TranscribeService.ACTION_CANCEL))
            },
            lockNow = { LockState.unlocked.value = false },
            pickImport = { internalLaunch = true; picker.launch(arrayOf("*/*")) },
            shareExport = { shareExport(it) },
        )
        setContent {
            Root(actions, importUri, { importUri = null }, { afterUnlock() })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        internalLaunch = false
    }

    override fun onStop() {
        super.onStop()
        // appena si lascia l'app (o si spegne lo schermo) serve di nuovo il codice
        if (!internalLaunch && !isChangingConfigurations) LockState.unlocked.value = false
    }
}
