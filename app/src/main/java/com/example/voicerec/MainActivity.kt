package com.example.voicerec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_START = "com.example.voicerec.START"
    }

    private var pending: Intent? = null
    private var fromTile = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pending?.let { if (hasMic()) launchService(it) }
            pending = null
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

    private fun toggle() {
        if (RecorderState.isRecording.value) startService(svc(RecorderService.ACTION_STOP))
        else requestRun(svc(RecorderService.ACTION_START))
    }

    private fun setArmed(on: Boolean) {
        if (on) requestRun(svc(RecorderService.ACTION_ARM))
        else startService(svc(RecorderService.ACTION_DISARM))
    }

    private fun handle(i: Intent?) {
        if (i != null && i.action == ACTION_START) {
            i.action = null
            fromTile = true
            if (!RecorderState.isRecording.value) requestRun(svc(RecorderService.ACTION_START))
            else fromTile = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { MaterialTheme { App(onToggle = { toggle() }, onArm = { setArmed(it) }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }
}

class Player {
    var playing by mutableStateOf<String?>(null)
    private var mp: MediaPlayer? = null

    fun toggle(f: File) {
        val wasThis = playing == f.absolutePath
        stop()
        if (wasThis) return
        try {
            mp = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                setOnCompletionListener { stop() }
                start()
            }
            playing = f.absolutePath
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        mp?.release()
        mp = null
        playing = null
    }
}

fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

@Composable
fun App(onToggle: () -> Unit, onArm: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val recording by RecorderState.isRecording.collectAsStateWithLifecycle()
    val started by RecorderState.startedAt.collectAsStateWithLifecycle()
    val armed by RecorderState.armed.collectAsStateWithLifecycle()
    val lastEvent by RecorderState.lastWatchEvent.collectAsStateWithLifecycle()
    var files by remember { mutableStateOf(Storage.list(ctx)) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    var toRename by remember { mutableStateOf<File?>(null) }
    val player = remember { Player() }

    DisposableEffect(Unit) { onDispose { player.stop() } }
    LaunchedEffect(recording) { files = Storage.list(ctx) }
    LaunchedEffect(recording, started) {
        while (recording) {
            elapsed = SystemClock.elapsedRealtime() - started
            delay(500)
        }
        elapsed = 0L
    }

    Scaffold { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Text("Registratore", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                fmt(elapsed),
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = if (recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors()
            ) { Text(if (recording) "Ferma e salva" else "Avvia registrazione") }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Modalità orologio", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Play/pausa dalla schermata Musica dell'orologio avvia e ferma la registrazione.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Ultimo comando ricevuto: $lastEvent", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = armed, onCheckedChange = onArm)
            }
            Spacer(Modifier.height(20.dp))

            if (files.isEmpty()) {
                Text("Nessuna registrazione. Premi \"Avvia registrazione\" per iniziare.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(files, key = { it.absolutePath }) { f ->
                        val dur = remember(f.absolutePath, f.length()) { Storage.durationMs(f) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.nameWithoutExtension, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${fmt(dur)}  ·  ${f.length() / 1024} KB  ·  " +
                                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                            .format(Date(f.lastModified())),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = { player.toggle(f) }) {
                                Icon(
                                    if (player.playing == f.absolutePath) Icons.Default.Close else Icons.Default.PlayArrow,
                                    contentDescription = if (player.playing == f.absolutePath) "Ferma ascolto" else "Ascolta"
                                )
                            }
                            IconButton(onClick = { toRename = f }) { Icon(Icons.Default.Edit, "Rinomina") }
                            IconButton(onClick = { toDelete = f }) { Icon(Icons.Default.Delete, "Elimina") }
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminare la registrazione?") },
            text = { Text(f.name) },
            confirmButton = {
                TextButton(onClick = {
                    player.stop()
                    f.delete()
                    files = Storage.list(ctx)
                    toDelete = null
                }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } }
        )
    }

    toRename?.let { f ->
        var name by remember(f) { mutableStateOf(f.nameWithoutExtension) }
        AlertDialog(
            onDismissRequest = { toRename = null },
            title = { Text("Rinomina") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val target = File(f.parentFile, "$clean.m4a")
                    if (clean.isNotEmpty() && !target.exists()) {
                        player.stop()
                        f.renameTo(target)
                        files = Storage.list(ctx)
                    }
                    toRename = null
                }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { toRename = null }) { Text("Annulla") } }
        )
    }
}
