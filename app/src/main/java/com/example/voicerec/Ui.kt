package com.example.voicerec

import android.content.ClipData
import android.content.ClipboardManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

class Actions(
    val toggleRecord: () -> Unit,
    val setArmed: (Boolean) -> Unit,
    val setDenoise: (Boolean) -> Unit,
    val downloadModel: () -> Unit,
    val transcribe: (File) -> Unit,
    val cancelWork: () -> Unit,
    val pickImport: () -> Unit,
    val shareFile: (File, String) -> Unit,
)

/** Riproduzione: il file viene decifrato in una cartella temporanea privata e cancellato a fine ascolto. */
class Player(private val ctx: android.content.Context) {
    var playing by mutableStateOf<String?>(null)
    private var mp: MediaPlayer? = null
    private var tmp: File? = null
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null

    fun toggle(enc: File) {
        val same = playing == enc.absolutePath
        stop()
        if (same) return
        try {
            val t = Secure.decryptToTemp(ctx, enc, "m4a")
            tmp = t
            mp = MediaPlayer().apply {
                setDataSource(t.absolutePath)
                prepare()
                setOnCompletionListener { stop() }
                start()
            }
            try {
                enhancer = android.media.audiofx.LoudnessEnhancer(mp!!.audioSessionId).apply {
                    setTargetGain(600)
                    enabled = true
                }
            } catch (e: Exception) {
                enhancer = null
            }
            playing = enc.absolutePath
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        try { enhancer?.release() } catch (e: Exception) { }
        enhancer = null
        mp?.release()
        mp = null
        tmp?.let { Secure.wipe(it) }
        tmp = null
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

fun displayName(base: String): String {
    if (!base.matches(Regex("Rec_\\d{8}_\\d{6}"))) return base
    return try {
        val d = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(base.removePrefix("Rec_"))!!
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(d)
    } catch (e: Exception) {
        base
    }
}

private val secureDialog = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

@Composable
fun VoiceRecTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF80CBC4), secondary = Color(0xFFB0BEC5), error = Color(0xFFFF8A80))
        else -> lightColorScheme(primary = Color(0xFF00695C), secondary = Color(0xFF455A64), error = Color(0xFFD32F2F))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------- codice di accesso ----------------

@Composable
fun PinPad(
    title: String,
    subtitle: String,
    error: String?,
    resetKey: Int,
    onCancel: (() -> Unit)? = null,
    onComplete: (String) -> Unit,
) {
    var pin by remember(resetKey) { mutableStateOf("") }
    LaunchedEffect(pin) { if (pin.length == 6) onComplete(pin) }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(6) { i ->
                Box(
                    Modifier.size(16.dp).clip(CircleShape).background(
                        if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(error ?: " ", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "←").chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { k ->
                    val m = Modifier.size(72.dp).clip(CircleShape)
                    if (k.isEmpty()) {
                        Spacer(m)
                    } else {
                        Box(
                            m.background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable {
                                if (k == "←") pin = pin.dropLast(1) else if (pin.length < 6) pin += k
                            },
                            contentAlignment = Alignment.Center
                        ) { Text(k, fontSize = 26.sp) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        onCancel?.let { TextButton(onClick = it) { Text("Annulla") } }
    }
}

@Composable
fun PinSetup(onCancel: (() -> Unit)? = null, onDone: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var reset by remember { mutableIntStateOf(0) }
    PinPad(
        title = if (first == null) "Crea il tuo codice" else "Conferma il codice",
        subtitle = "6 cifre. Serve per ascoltare le registrazioni e per condividerle.",
        error = err, resetKey = reset, onCancel = onCancel
    ) { p ->
        if (first == null) {
            first = p; err = null; reset++
        } else if (first == p) {
            onDone(p)
        } else {
            err = "I codici non coincidono. Riprova."; first = null; reset++
        }
    }
}

@Composable
fun PinCheck(why: String, onCancel: () -> Unit, onOk: () -> Unit) {
    val ctx = LocalContext.current
    var err by remember { mutableStateOf<String?>(null) }
    var reset by remember { mutableIntStateOf(0) }
    PinPad("Inserisci il codice", why, err, reset, onCancel) { p ->
        when (val r = Pin.verify(ctx, p)) {
            is Pin.Result.Ok -> onOk()
            is Pin.Result.Wrong -> { err = "Codice errato (${r.fails})"; reset++ }
            is Pin.Result.Locked -> { err = "Troppi tentativi: riprova tra ${r.seconds} s"; reset++ }
        }
    }
}

@Composable
fun Root(actions: Actions, importUri: Uri?, clearImport: () -> Unit) {
    val ctx = LocalContext.current
    var changing by remember { mutableStateOf(false) }
    VoiceRecTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (changing) {
                PinSetup(onCancel = { changing = false }) { p -> Pin.set(ctx, p); changing = false }
            } else {
                Home(actions, importUri, clearImport) { changing = true }
            }
        }
    }
}

// ---------------- schermata principale ----------------

@Composable
fun HeroCard(content: @Composable ColumnScope.() -> Unit) {
    val c1 = MaterialTheme.colorScheme.primaryContainer
    val c2 = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Brush.verticalGradient(listOf(c1, c2)))) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** Barre animate che mostrano il livello della voce mentre registri. */
@Composable
fun LevelBars(levels: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = levels.size
        if (n == 0) return@Canvas
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = size.height * levels[i].coerceIn(0.06f, 1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (w + gap), (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f)
            )
        }
    }
}

@Composable
fun Chip(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "pulse")
    val scale by inf.animateFloat(
        1f, 1.2f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )
    val err = MaterialTheme.colorScheme.error
    Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        if (recording) {
            Box(Modifier.size(172.dp).scale(scale).clip(CircleShape).background(err.copy(alpha = 0.10f)))
            Box(Modifier.size(146.dp).scale(scale).clip(CircleShape).background(err.copy(alpha = 0.20f)))
        }
        Box(
            Modifier.size(124.dp).clip(CircleShape)
                .background(if (recording) err else MaterialTheme.colorScheme.primary)
                .semantics { contentDescription = if (recording) "Ferma e salva" else "Avvia registrazione" }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (recording) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color.White))
            } else {
                Icon(painterResource(R.drawable.ic_mic), null, tint = Color.White, modifier = Modifier.size(56.dp))
            }
        }
    }
}

@Composable
fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) { Column(Modifier.padding(18.dp), content = content) }
}

@Composable
fun RecordingCard(
    f: File,
    playing: Boolean,
    hasTxt: Boolean,
    received: Boolean,
    onPlay: () -> Unit,
    onViewTxt: () -> Unit,
    onTranscribe: () -> Unit,
    onExport: () -> Unit,
    onSharePlain: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember(f.absolutePath) { mutableStateOf(false) }
    val dur = remember(f.absolutePath) { Storage.durationMs(f) }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
                Icon(if (playing) Icons.Default.Close else Icons.Default.PlayArrow, if (playing) "Ferma ascolto" else "Ascolta")
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(displayName(Storage.baseName(f)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    fmt(dur) + "  ·  " + "%.1f MB".format(f.length() / 1048576.0),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasTxt || received) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (hasTxt) Chip("Trascritto")
                        if (received) Chip("Ricevuto")
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Altro") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (hasTxt) DropdownMenuItem(text = { Text("Vedi trascrizione") }, onClick = { menu = false; onViewTxt() })
                    DropdownMenuItem(
                        text = { Text(if (hasTxt) "Rifai trascrizione" else "Trascrivi") },
                        onClick = { menu = false; onTranscribe() }
                    )
                    if (!received) DropdownMenuItem(text = { Text("Condividi file audio") }, onClick = { menu = false; onSharePlain() })
                    if (!received) DropdownMenuItem(text = { Text("Condividi protetto (con app)") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("Rinomina") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Elimina") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(actions: Actions, importUri: Uri?, clearImport: () -> Unit, onChangePin: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recording by RecorderState.isRecording.collectAsStateWithLifecycle()
    val started by RecorderState.startedAt.collectAsStateWithLifecycle()
    val armed by RecorderState.armed.collectAsStateWithLifecycle()
    val lastEvent by RecorderState.lastWatchEvent.collectAsStateWithLifecycle()
    val denoise by RecorderState.denoise.collectAsStateWithLifecycle()
    val level by RecorderState.level.collectAsStateWithLifecycle()
    val modelReady by TranscribeState.modelReady.collectAsStateWithLifecycle()
    val downloading by TranscribeState.downloading.collectAsStateWithLifecycle()
    val dlProgress by TranscribeState.downloadProgress.collectAsStateWithLifecycle()
    val busyPath by TranscribeState.busyPath.collectAsStateWithLifecycle()
    val progress by TranscribeState.progress.collectAsStateWithLifecycle()
    val message by TranscribeState.message.collectAsStateWithLifecycle()
    val done by TranscribeState.doneCounter.collectAsStateWithLifecycle()

    var tick by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    var toRename by remember { mutableStateOf<File?>(null) }
    var toExport by remember { mutableStateOf<File?>(null) }
    var showTxt by remember { mutableStateOf<File?>(null) }
    var toSharePlain by remember { mutableStateOf<File?>(null) }
    var pinWhy by remember { mutableStateOf("") }
    var pinAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun askPin(why: String, action: () -> Unit) { pinWhy = why; pinAction = action }
    var topMenu by remember { mutableStateOf(false) }
    val files = remember(recording, done, tick) { Storage.list(ctx) }
    val player = remember { Player(ctx) }

    DisposableEffect(Unit) { onDispose { player.stop() } }
    LaunchedEffect(recording, started) {
        while (recording) {
            elapsed = SystemClock.elapsedRealtime() - started
            delay(500)
        }
        elapsed = 0L
    }

    val history = remember { mutableStateListOf<Float>().apply { repeat(40) { add(0f) } } }
    LaunchedEffect(recording) {
        if (recording) {
            while (true) {
                val v = Math.sqrt((level * 6f).coerceIn(0f, 1f).toDouble()).toFloat()
                history.removeAt(0)
                history.add(v)
                delay(70)
            }
        } else {
            for (i in history.indices) history[i] = 0f
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Registratore", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Archivio cifrato · solo su questo telefono",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { topMenu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                        DropdownMenuItem(text = { Text("Importa file protetto") }, onClick = { topMenu = false; actions.pickImport() })
                        DropdownMenuItem(text = { Text("Cambia codice") }, onClick = { topMenu = false; askPin("per cambiare il codice") { onChangePin() } })
                    }
                }
            }
        }
        item {
            HeroCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    RecordButton(recording) { actions.toggleRecord() }
                    LevelBars(
                        history,
                        if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
                        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Text(
                        fmt(elapsed), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Light
                    )
                    Text(
                        if (recording) "Registrazione in corso" else "Tocca per registrare",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Comando dall'orologio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Play/pausa dalla schermata Musica dell'orologio avvia e ferma la registrazione.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Ultimo comando ricevuto: $lastEvent",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = armed, onCheckedChange = actions.setArmed)
                }
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Solo voci, senza rumore di fondo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (denoise)
                                "Attivo: toglie rumori e sottofondo e tiene le voci (qualità voce, 16 kHz). Vale dalla prossima registrazione."
                            else
                                "Spento: qualità massima (48 kHz) ma con tutto il rumore di fondo. Vale dalla prossima registrazione.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = denoise, onCheckedChange = actions.setDenoise)
                }
            }
        }
        if (!modelReady || busyPath != null || message.isNotEmpty()) {
            item {
                SectionCard {
                    Text("Trascrizione in italiano", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (!modelReady) {
                        Text(
                            "Serve scaricare il modello una sola volta (circa 490 MB, meglio con il Wi-Fi). Poi funziona offline.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (downloading) {
                            LinearProgressIndicator(progress = { dlProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Download ${(dlProgress * 100).toInt()}%", Modifier.weight(1f))
                                TextButton(onClick = actions.cancelWork) { Text("Annulla") }
                            }
                        } else {
                            Button(onClick = actions.downloadModel, modifier = Modifier.padding(top = 10.dp)) { Text("Scarica il modello") }
                        }
                    } else if (busyPath != null) {
                        Text("In corso: ${(progress * 100).toInt()}%", Modifier.padding(top = 6.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                        TextButton(onClick = actions.cancelWork) { Text("Annulla") }
                    }
                    if (message.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { TranscribeState.message.value = "" }) { Text("OK") }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Registrazioni (${files.size})",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (files.isEmpty()) {
            item {
                Text(
                    "Nessuna registrazione. Tocca il pulsante qui sopra per iniziare.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(files, key = { it.absolutePath }) { f ->
                val hasTxt = remember(f.absolutePath, done, tick) { Storage.txtEnc(f).exists() }
                RecordingCard(
                    f = f,
                    playing = player.playing == f.absolutePath,
                    hasTxt = hasTxt,
                    received = Storage.isReceived(f),
                    onPlay = {
                        if (player.playing == f.absolutePath) player.stop()
                        else askPin("per ascoltare la registrazione") { player.toggle(f) }
                    },
                    onViewTxt = { showTxt = f },
                    onTranscribe = {
                        when {
                            !modelReady -> Toast.makeText(ctx, "Scarica prima il modello (in alto)", Toast.LENGTH_LONG).show()
                            busyPath != null -> Toast.makeText(ctx, "Una trascrizione è già in corso", Toast.LENGTH_LONG).show()
                            else -> actions.transcribe(f)
                        }
                    },
                    onExport = { askPin("per condividere la registrazione") { toExport = f } },
                    onSharePlain = { toSharePlain = f },
                    onRename = { toRename = f },
                    onDelete = { toDelete = f },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    // ---- finestre di dialogo ----
    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            properties = secureDialog,
            title = { Text("Eliminare la registrazione?") },
            text = { Text(displayName(Storage.baseName(f))) },
            confirmButton = {
                TextButton(onClick = {
                    player.stop()
                    Storage.delete(f)
                    tick++
                    toDelete = null
                }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } }
        )
    }

    toRename?.let { f ->
        var name by remember(f) { mutableStateOf(Storage.baseName(f)) }
        AlertDialog(
            onDismissRequest = { toRename = null },
            properties = secureDialog,
            title = { Text("Rinomina") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val clean = name.trim().replace(Regex("[^\\p{L}\\p{N} _.-]"), "_")
                    if (clean.isNotEmpty() && clean != Storage.baseName(f)) {
                        player.stop()
                        if (Storage.rename(f, clean)) tick++
                        else Toast.makeText(ctx, "Esiste già una registrazione con questo nome", Toast.LENGTH_LONG).show()
                    }
                    toRename = null
                }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { toRename = null }) { Text("Annulla") } }
        )
    }

    toExport?.let { f ->
        var p1 by remember(f) { mutableStateOf("") }
        var p2 by remember(f) { mutableStateOf("") }
        var busy by remember(f) { mutableStateOf(false) }
        var err by remember(f) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) toExport = null },
            properties = secureDialog,
            title = { Text("Condividi in modo protetto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Il file si potrà aprire solo con questa app e con la frase segreta che scegli. " +
                            "Chi lo riceve non potrà ricondividerlo dall'app.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        p1, { p1 = it }, singleLine = true, label = { Text("Frase segreta (min. 8 caratteri)") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                    OutlinedTextField(
                        p2, { p2 = it }, singleLine = true, label = { Text("Ripeti la frase") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    if (p1.length < 8) {
                        err = "Servono almeno 8 caratteri"
                    } else if (p1 != p2) {
                        err = "Le due frasi non coincidono"
                    } else {
                        busy = true
                        err = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { Exporter.export(ctx, f, p1.toCharArray()) } }
                            busy = false
                            r.onSuccess { toExport = null; actions.shareFile(it, "application/octet-stream") }
                                .onFailure { err = "Esportazione non riuscita" }
                        }
                    }
                }) { Text(if (busy) "Attendi..." else "Crea e condividi") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { toExport = null }) { Text("Annulla") } }
        )
    }

    if (importUri != null) {
        var p by remember(importUri) { mutableStateOf("") }
        var busy by remember(importUri) { mutableStateOf(false) }
        var err by remember(importUri) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) clearImport() },
            properties = secureDialog,
            title = { Text("Importa file protetto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Inserisci la frase segreta scelta da chi ha creato il file.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        p, { p = it }, singleLine = true, label = { Text("Frase segreta") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && p.isNotEmpty(), onClick = {
                    busy = true
                    err = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Exporter.importFile(ctx, importUri, p.toCharArray()) } }
                        busy = false
                        r.onSuccess {
                            tick++
                            clearImport()
                            Toast.makeText(ctx, "File importato (non ricondivisibile)", Toast.LENGTH_LONG).show()
                        }.onFailure { err = "Frase errata o file non valido" }
                    }
                }) { Text(if (busy) "Attendi..." else "Importa") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { clearImport() }) { Text("Annulla") } }
        )
    }

    toSharePlain?.let { f ->
        AlertDialog(
            onDismissRequest = { toSharePlain = null },
            properties = secureDialog,
            title = { Text("Condividere il file audio?") },
            text = {
                Text(
                    "Chi lo riceve potrà aprirlo con qualsiasi app, senza codici. " +
                        "Una volta inviato non posso impedire che lo inoltri a sua volta: " +
                        "condividilo solo con persone di cui ti fidi."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    toSharePlain = null
                    askPin("per condividere la registrazione") {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { Exporter.exportPlain(ctx, f) } }
                            r.onSuccess { actions.shareFile(it, "audio/mp4") }
                                .onFailure { Toast.makeText(ctx, "Condivisione non riuscita", Toast.LENGTH_LONG).show() }
                        }
                    }
                }) { Text("Continua") }
            },
            dismissButton = { TextButton(onClick = { toSharePlain = null }) { Text("Annulla") } }
        )
    }

    pinAction?.let { act ->
        Dialog(
            onDismissRequest = { pinAction = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)
        ) {
            Surface(Modifier.fillMaxSize()) {
                if (!Pin.isSet(ctx)) {
                    PinSetup(onCancel = { pinAction = null }) { p -> Pin.set(ctx, p); pinAction = null; act() }
                } else {
                    PinCheck(pinWhy, onCancel = { pinAction = null }) { pinAction = null; act() }
                }
            }
        }
    }

    showTxt?.let { f ->
        val received = Storage.isReceived(f)
        val content = remember(f.absolutePath, done) {
            try { Secure.readText(Storage.txtEnc(f)) } catch (e: Exception) { "Impossibile leggere la trascrizione." }
        }
        Dialog(
            onDismissRequest = { showTxt = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)
        ) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                    Text(displayName(Storage.baseName(f)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row {
                        if (!received) {
                            TextButton(onClick = {
                                val cm = ctx.getSystemService(ClipboardManager::class.java)
                                cm.setPrimaryClip(ClipData.newPlainText("trascrizione", content))
                                Toast.makeText(ctx, "Testo copiato", Toast.LENGTH_SHORT).show()
                            }) { Text("Copia") }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { showTxt = null }) { Text("Chiudi") }
                    }
                    HorizontalDivider()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val body = @Composable {
                            Text(content, Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp))
                        }
                        if (received) body() else SelectionContainer { body() }
                    }
                }
            }
        }
    }
}
