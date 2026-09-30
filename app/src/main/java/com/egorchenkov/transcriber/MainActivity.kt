package com.egorchenkov.transcriber

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var models: ModelManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        models = ModelManager(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (savedInstanceState == null) handleIntent(intent)
        lifecycleScope.launch { Jobs.items.collect { applyKeepScreenOn() } }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var screen by remember { mutableStateOf("main") }
                var openId by remember { mutableStateOf(0L) }
                BackHandler(screen != "main") { screen = "main" }
                when (screen) {
                    "settings" -> SettingsScreen(onBack = { screen = "main" })
                    "detail" -> DetailScreen(openId, onBack = { screen = "main" })
                    "dim" -> DimScreen(onExit = { screen = "main" })
                    else -> MainScreen(onSettings = { screen = "settings" }, onOpen = { openId = it; screen = "detail" }, onDim = { screen = "dim" })
                }
            }
        }
    }

    /** Экран не гаснет, пока идёт распознавание (если включено в настройках): иначе некоторые прошивки замораживают процесс. */
    fun applyKeepScreenOn() {
        val on = settings.keepScreenOn && Jobs.isRunning
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** «Поделиться» из Telegram и других приложений: сразу в очередь и в работу. */
    private fun handleIntent(intent: Intent?) {
        val uris = mutableListOf<Uri>()
        when (intent?.action) {
            Intent.ACTION_SEND -> intentStream(intent)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> intentStreams(intent)?.let { uris += it }
            Intent.ACTION_VIEW -> intent.data?.let { uris += it }
        }
        if (uris.isEmpty()) return
        addUris(uris, autoStart = true)
    }

    @Suppress("DEPRECATION")
    private fun intentStream(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun intentStreams(i: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)

    /** Копируем файл к себе: разрешение на чужой content:// живёт недолго. */
    fun addUris(uris: List<Uri>, autoStart: Boolean, title: String? = null, repeat: Boolean = false) {
        val app = applicationContext
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val inbox = File(filesDir, "inbox").apply { mkdirs() }
            var added = 0
            for (uri in uris) {
                try {
                    val name = displayName(uri) ?: "аудио-${System.currentTimeMillis()}"
                    val dst = File(inbox, "${System.nanoTime()}_${name.replace('/', '_')}")
                    contentResolver.openInputStream(uri)!!.use { input -> dst.outputStream().use { input.copyTo(it) } }
                    // Для выбора через системный диалог доступ можно сохранить — пригодится для повтора
                    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    Jobs.add(name, dst, source = uri.toString(), title = title)
                    added++
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        val msg = if (repeat) "Исходный файл больше недоступен — повторить нельзя"
                        else "Не удалось открыть файл: ${e.message}"
                        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
            val spec = Models.byId(settings.modelId)
            if (autoStart && added > 0 && spec != null && models.isInstalled(spec)) {
                withContext(Dispatchers.Main) { TranscriptionService.start(app) }
            }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    // ---------------- Главный экран ----------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen(onSettings: () -> Unit, onOpen: (Long) -> Unit, onDim: () -> Unit) {
        val jobs by Jobs.items.collectAsStateWithLifecycle()
        val rec by RecorderService.state.collectAsStateWithLifecycle()
        val recError by RecorderService.error.collectAsStateWithLifecycle()
        val spec = Models.byId(settings.modelId) ?: Models.asr.first()
        var installed by remember { mutableStateOf(models.isInstalled(spec)) }
        LaunchedEffect(Unit) {
            while (true) {
                installed = withContext(Dispatchers.IO) { models.state(spec) == ModelState.Installed }
                delay(1500)
            }
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) addUris(uris, autoStart = false)
        }
        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) RecorderService.start(this) else toast("Нужно разрешение на микрофон")
        }
        LaunchedEffect(recError) { recError?.let { toast(it); RecorderService.error.value = null } }

        val bgAttention = bgNeedsAttention()
        val active = jobs.filter { it.status != Status.DONE }
        val history = jobs.filter { it.status == Status.DONE }.sortedByDescending { it.createdAt }
        var selected by remember { mutableStateOf(emptySet<Long>()) }
        val sel = selected.filter { id -> history.any { it.id == id } }.toSet()
        var confirm by remember { mutableStateOf<String?>(null) } // "sel" | "all"
        var renaming by remember { mutableStateOf<Job?>(null) }
        BackHandler(sel.isNotEmpty()) { selected = emptySet() }

        confirm?.let { kind ->
            val n = if (kind == "sel") sel.size else history.size
            ConfirmDelete(n, onOk = {
                if (kind == "sel") Jobs.removeAll(sel) else Jobs.clearDone()
                selected = emptySet(); confirm = null
            }, onCancel = { confirm = null })
        }
        renaming?.let { RenameDialog(it) { renaming = null } }

        Scaffold(topBar = {
            if (sel.isNotEmpty()) TopAppBar(
                title = { Text("Выбрано: ${sel.size}") },
                navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "Отмена") } },
                actions = {
                    IconButton(onClick = {
                        val list = history.filter { it.id in sel }.sortedBy { it.createdAt }.mapNotNull { it.result }
                        shareText(Formatter.document(list, settings.timestamps))
                    }) { Icon(Icons.Default.Share, "Отправить") }
                    IconButton(onClick = { confirm = "sel" }) { Icon(Icons.Default.Delete, "Удалить") }
                },
            ) else TopAppBar(
                title = { Text("Транскрибатор") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Настройки") } },
            )
        }) { pad ->
            LazyColumn(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    val diar = if (settings.diarize && models.isInstalled(Models.diarization)) {
                        " · говорящие: " + if (settings.speakers > 0) settings.speakers else "авто"
                    } else ""
                    Text(
                        "Модель: ${spec.title}$diar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!installed) item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Модель распознавания не скачана", fontWeight = FontWeight.Bold)
                            Text("Скачайте её один раз (нужен интернет), дальше всё работает офлайн.")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onSettings) { Text("Открыть настройки") }
                        }
                    }
                }
                if (bgAttention) item { BackgroundCard() }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        if (rec == null) {
                            Button(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                                        == PackageManager.PERMISSION_GRANTED
                                    ) RecorderService.start(this@MainActivity)
                                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                modifier = Modifier.weight(1f).height(56.dp),
                            ) { Text("● Запись", fontSize = 18.sp) }
                        } else {
                            var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                            LaunchedEffect(rec) { while (true) { now = SystemClock.elapsedRealtime(); delay(500) } }
                            Button(
                                onClick = { RecorderService.stop(this@MainActivity) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                                modifier = Modifier.weight(1f).height(56.dp),
                            ) { Text("■ Стоп  " + Formatter.time((now - rec!!.startedAt) / 1000f), fontSize = 18.sp) }
                        }
                        OutlinedButton(
                            onClick = { picker.launch(arrayOf("audio/*", "video/*", "application/ogg")) },
                            modifier = Modifier.weight(1f).height(56.dp),
                        ) { Text("Выбрать файлы") }
                    }
                }

                // Текущая очередь: ожидают, идут, ошибки
                if (active.isNotEmpty()) {
                    item { SectionTitle("Сейчас") }
                    items(active, key = { it.id }) { job -> JobRow(job) }
                    item {
                        val queued = active.count { it.status == Status.QUEUED }
                        val running = active.any { it.status == Status.RUNNING }
                        val failed = active.count { it.status == Status.ERROR }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (running) {
                                var stopping by remember { mutableStateOf(false) }
                                OutlinedButton(
                                    onClick = { stopping = true; Jobs.cancelRequested = true },
                                    enabled = !stopping,
                                ) { Text(if (stopping) "Останавливаю…" else "Остановить") }
                                OutlinedButton(onClick = onDim) { Text("Чёрный экран") }
                            } else {
                                Button(
                                    onClick = { TranscriptionService.start(this@MainActivity) },
                                    enabled = queued > 0 && installed,
                                ) { Text("Транскрибировать ($queued)") }
                            }
                            if (failed > 0 && !running) TextButton(onClick = { Jobs.retryFailed() }) { Text("Повторить") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { Jobs.clear() }, enabled = !running) { Text("Убрать из очереди") }
                        }
                    }
                }

                // История: готовые транскрипции, новые сверху
                if (history.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("История · ${history.size}", Modifier.weight(1f))
                            if (sel.isEmpty()) TextButton(onClick = { confirm = "all" }) { Text("Очистить") }
                        }
                    }
                    items(history, key = { it.id }) { job ->
                        HistoryCard(
                            job, selecting = sel.isNotEmpty(), isSelected = job.id in sel,
                            onClick = { if (sel.isNotEmpty()) selected = sel.toggle(job.id) else onOpen(job.id) },
                            onLongClick = { selected = sel.toggle(job.id) },
                            onRename = { renaming = job },
                            onDelete = { selected = setOf(job.id); confirm = "sel" },
                        )
                    }
                    item {
                        Text(
                            "Аудио не хранится. Долгое нажатие — выбрать несколько записей.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                } else if (active.isEmpty()) item {
                    Text(
                        "Запишите разговор или выберите аудиофайлы. Можно также «Поделиться» голосовым " +
                            "или файлом из Telegram → «Транскрибатор». Готовые тексты будут храниться здесь.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    /** Фон настроен, если снято ограничение батареи и (для прошивок со своим менеджером) пользователь подтвердил шаги. */
    private fun bgOk() = Background.ignoringBatteryOptimizations(this) &&
        (!Background.hasOemManager || settings.bgConfirmed)

    @Composable
    private fun bgNeedsAttention(): Boolean {
        var need by remember { mutableStateOf(!bgOk() || settings.stallCount > 0) }
        LaunchedEffect(Unit) { while (true) { need = !bgOk() || settings.stallCount > 0; delay(1500) } }
        return need
    }

    /** Настройка работы при выключенном экране: статус, кнопки, инструкция под производителя. */
    @Composable
    private fun BackgroundCard() {
        var ignoring by remember { mutableStateOf(Background.ignoringBatteryOptimizations(this)) }
        var confirmed by remember { mutableStateOf(settings.bgConfirmed) }
        var keepOn by remember { mutableStateOf(settings.keepScreenOn) }
        var quiet by remember { mutableStateOf(settings.keepAliveAudio) }
        var stalls by remember { mutableIntStateOf(settings.stallCount) }
        LaunchedEffect(Unit) {
            while (true) { ignoring = Background.ignoringBatteryOptimizations(this@MainActivity); delay(1000) }
        }
        val ok = ignoring && (!Background.hasOemManager || confirmed)
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (ok && stalls == 0) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.tertiaryContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (ok) "✓ Работа при выключенном экране настроена" else "⚠ Работа при выключенном экране",
                    fontWeight = FontWeight.Bold,
                )
                if (stalls > 0) Text(
                    "Система замораживала распознавание при выключенном экране: $stalls раз, " +
                        "всего ${settings.stallSec / 60} мин. Проверьте шаги ниже или включите «Не гасить экран».",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    (if (ignoring) "✓ Ограничение батареи снято" else "✗ Батарея ограничивает приложение"),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!ignoring) Button(onClick = { Background.requestIgnore(this@MainActivity) }) { Text("Не ограничивать батарею") }
                if (Background.hasOemManager) {
                    Text(Background.instruction(), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { Background.openOemManager(this@MainActivity) }) { Text("Открыть настройки") }
                        if (!confirmed) TextButton(onClick = {
                            settings.bgConfirmed = true; settings.stallCount = 0; settings.stallSec = 0
                            confirmed = true; stalls = 0
                        }) { Text("Готово, настроил") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Не гасить экран при распознавании")
                        Text(
                            "Надёжно на любом телефоне; работает, пока приложение открыто. Лучше на зарядке.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = keepOn, onCheckedChange = { keepOn = it; settings.keepScreenOn = it; applyKeepScreenOn() })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Тихий звук для удержания фона")
                        Text(
                            "Беззвучный поток на время распознавания: прошивки не замораживают приложение «со звуком».",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = quiet, onCheckedChange = { quiet = it; settings.keepAliveAudio = it })
                }
                TextButton(onClick = { shareLog() }) { Text("Отправить журнал фоновой работы") }
                if (stalls > 0) TextButton(onClick = { settings.stallCount = 0; settings.stallSec = 0; stalls = 0 }) {
                    Text("Сбросить счётчик")
                }
            }
        }
    }

    private fun shareLog() {
        val head = "Transcriber ${packageManager.getPackageInfo(packageName, 0).versionName}; " +
            "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); " +
            "игнор батареи=${Background.ignoringBatteryOptimizations(this)}; тихий звук=${settings.keepAliveAudio}; " +
            "заморозок=${settings.stallCount}\n\n"
        shareFile(head + BgLog.text(), "transcriber-bg-log")
    }

    /** Почти выключенный экран: чёрный фон и минимальная яркость; экран не гаснет, поэтому система не замораживает работу. */
    @Composable
    fun DimScreen(onExit: () -> Unit) {
        val jobs by Jobs.items.collectAsStateWithLifecycle()
        val running = jobs.firstOrNull { it.status == Status.RUNNING }
        DisposableEffect(Unit) {
            val lp = window.attributes
            lp.screenBrightness = 0.01f
            window.attributes = lp
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                val l = window.attributes
                l.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = l
                applyKeepScreenOn()
            }
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onExit),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (running != null) "${running.label}\n${running.stage} ${(running.progress * 100).toInt()}%\n\nнажмите, чтобы выйти"
                else "Готово\n\nнажмите, чтобы выйти",
                color = Color(0xFF444444), fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

    @Composable
    private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
        Text(text, modifier, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }

    @Composable
    private fun JobRow(job: Job) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.label, fontWeight = FontWeight.Medium, maxLines = 2)
                    when (job.status) {
                        Status.QUEUED -> Text("ожидает", style = MaterialTheme.typography.bodySmall)
                        Status.RUNNING -> {
                            Text("${job.stage} ${(job.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp))
                        }
                        Status.DONE -> {}
                        Status.ERROR -> Text("ошибка: ${job.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (job.status != Status.RUNNING) {
                    IconButton(onClick = { Jobs.remove(job.id) }) { Icon(Icons.Default.Close, "Убрать") }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun HistoryCard(
        job: Job,
        selecting: Boolean,
        isSelected: Boolean,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        onRename: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val t = job.result
        var menu by remember { mutableStateOf(false) }
        Card(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
                if (selecting) Checkbox(isSelected, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (job.title != null) Text(
                        job.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        Formatter.dateTime(job.createdAt) + " · " + Formatter.time(t?.durationSec ?: 0f) +
                            (if (t?.diarized == true) " · говорящих: ${t.pieces.map { it.speaker }.distinct().size}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (t != null) Text(
                        Formatter.preview(t), Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!selecting) Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Действия") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; onRename() })
                        if (t != null) {
                            DropdownMenuItem(text = { Text("Отправить") }, onClick = {
                                menu = false; shareText(Formatter.one(t, settings.timestamps))
                            })
                            DropdownMenuItem(text = { Text("Копировать") }, onClick = {
                                menu = false; copy(Formatter.one(t, settings.timestamps))
                            })
                        }
                        if (canRepeat(job)) DropdownMenuItem(text = { Text("Повторить") }, onClick = { menu = false; repeat(job) })
                        DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }

    @Composable
    private fun ConfirmDelete(n: Int, onOk: () -> Unit, onCancel: () -> Unit) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(if (n == 1) "Удалить запись?" else "Удалить записи: $n?") },
            text = { Text("Текст будет удалён без возможности восстановления. Аудио приложение не хранит.") },
            confirmButton = { TextButton(onClick = onOk) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
        )
    }

    @Composable
    private fun RenameDialog(job: Job, onClose: () -> Unit) {
        var text by remember { mutableStateOf(job.title ?: "") }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Название записи") },
            text = {
                OutlinedTextField(
                    text, { text = it }, singleLine = true,
                    placeholder = { Text(job.name) },
                    supportingText = { Text("Пусто — показывать имя файла") },
                )
            },
            confirmButton = { TextButton(onClick = { Jobs.rename(job.id, text); onClose() }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
        )
    }

    // ---------------- Просмотр записи ----------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DetailScreen(id: Long, onBack: () -> Unit) {
        val jobs by Jobs.items.collectAsStateWithLifecycle()
        val job = jobs.firstOrNull { it.id == id }
        val t = job?.result
        LaunchedEffect(job == null) { if (job == null) onBack() }
        if (job == null || t == null) return

        val text = remember(t, settings.timestamps) { Formatter.one(t, settings.timestamps) }
        val paragraphs = remember(text) { text.split("\n\n") }
        var renaming by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }
        val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) runCatching {
                contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
                toast("Сохранено")
            }.onFailure { toast("Ошибка сохранения: ${it.message}") }
        }
        if (renaming) RenameDialog(job) { renaming = false }
        if (confirmDelete) ConfirmDelete(1, onOk = { Jobs.remove(job.id) }, onCancel = { confirmDelete = false })

        Scaffold(topBar = {
            TopAppBar(
                title = { Text(job.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Переименовать") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Удалить") }
                },
            )
        }) { pad ->
            SelectionContainer {
                LazyColumn(
                    Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            Formatter.dateTime(job.createdAt) + " · " + Formatter.time(t.durationSec) + " · " + t.model +
                                (if (job.title != null) "\nФайл: ${job.name}" else ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { shareText(text) }) { Text("Отправить") }
                                OutlinedButton(onClick = { copy(text) }) { Text("Копировать") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { shareFile(text, job.label) }) { Text("Файлом .txt") }
                                OutlinedButton(onClick = { saver.launch(safeName(job.label) + ".txt") }) { Text("Сохранить") }
                                if (canRepeat(job)) OutlinedButton(onClick = { repeat(job); onBack() }) { Text("Повторить") }
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 12.dp))
                    }
                    items(paragraphs.size) { i -> Text(paragraphs[i], fontSize = 15.sp) }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    /** Повторно берём файл из источника (если он там ещё есть) — как новую запись в истории. */
    private fun canRepeat(job: Job) = job.file.exists() || job.source != null

    private fun repeat(job: Job) {
        if (job.file.exists()) {
            Jobs.add(job.name, job.file, job.source, job.title)
            TranscriptionService.start(this)
        } else job.source?.let { addUris(listOf(Uri.parse(it)), autoStart = true, title = job.title, repeat = true) }
    }

    // ---------------- Настройки ----------------

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsScreen(onBack: () -> Unit) {
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
        val scope = rememberCoroutineScope()
        var selected by remember { mutableStateOf(settings.modelId) }
        var language by remember { mutableStateOf(settings.language) }
        var diarize by remember { mutableStateOf(settings.diarize) }
        var speakers by remember { mutableIntStateOf(settings.speakers) }
        var timestamps by remember { mutableStateOf(settings.timestamps) }
        var states by remember { mutableStateOf(emptyMap<String, ModelState>()) }
        LaunchedEffect(tick) {
            states = withContext(Dispatchers.IO) { Models.all.associate { it.id to models.state(it) } }
        }

        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } },
            )
        }) { pad ->
            LazyColumn(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Section("Модель распознавания") }
                items(Models.asr, key = { it.id }) { spec ->
                    val st = states[spec.id] ?: ModelState.NotInstalled
                    ModelCard(
                        spec, st,
                        selectable = true,
                        selected = selected == spec.id,
                        onSelect = { selected = spec.id; settings.modelId = spec.id },
                        onAction = { scope.launch(Dispatchers.IO) { modelAction(spec, st); tick++ } },
                    )
                }
                item {
                    Section("Язык (только для Whisper)")
                    val langs = listOf("ru" to "Русский", "en" to "English", "" to "Авто")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        langs.forEach { (code, label) ->
                            FilterChip(
                                selected = language == code,
                                onClick = { language = code; settings.language = code },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        "GigaAM язык не выбирает: v3 — только русский, Multilingual (узбекский) определяет сам.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Section("Разделение говорящих")
                    val d = Models.diarization
                    val st = states[d.id] ?: ModelState.NotInstalled
                    ModelCard(d, st, selectable = false, selected = false, onSelect = {},
                        onAction = { scope.launch(Dispatchers.IO) { modelAction(d, st); tick++ } })
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Размечать «Спикер 1, 2…»", Modifier.weight(1f))
                        Switch(
                            checked = diarize && st == ModelState.Installed,
                            enabled = st == ModelState.Installed,
                            onCheckedChange = { diarize = it; settings.diarize = it },
                        )
                    }
                    if (diarize && st == ModelState.Installed) {
                        Text("Сколько участников (если знаете — точнее):", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0, 2, 3, 4, 5, 6).forEach { n ->
                                FilterChip(
                                    selected = speakers == n,
                                    onClick = { speakers = n; settings.speakers = n },
                                    label = { Text(if (n == 0) "авто" else "$n") },
                                )
                            }
                        }
                        Text(
                            "Диаризация добавляет примерно 3–5 минут на час записи.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Section("Работа в фоне")
                    BackgroundCard()
                }
                item {
                    Section("Текст")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Метки времени [мм:сс]", Modifier.weight(1f))
                        Switch(checked = timestamps, onCheckedChange = { timestamps = it; settings.timestamps = it })
                    }
                }
                item {
                    Text(
                        "Всё распознаётся на телефоне, аудио никуда не отправляется. " +
                            "Интернет нужен только для скачивания моделей.\n" +
                            "Версия ${packageManager.getPackageInfo(packageName, 0).versionName} · движок sherpa-onnx 1.13.8",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }
    }

    private fun modelAction(spec: ModelSpec, st: ModelState) {
        when (st) {
            is ModelState.Downloading -> models.cancel(spec)
            ModelState.Installed -> models.delete(spec)
            else -> models.download(spec)
        }
    }

    @Composable
    private fun Section(title: String) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    }

    @Composable
    private fun ModelCard(
        spec: ModelSpec,
        st: ModelState,
        selectable: Boolean,
        selected: Boolean,
        onSelect: () -> Unit,
        onAction: () -> Unit,
    ) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp)) {
                if (selectable) {
                    RadioButton(selected = selected, onClick = onSelect)
                    Spacer(Modifier.width(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(spec.title, fontWeight = FontWeight.Bold)
                    Text(spec.description, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    when (st) {
                        is ModelState.Downloading -> {
                            LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Загрузка ${(st.progress * 100).toInt()}% из ${spec.sizeMb} МБ", Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = onAction) { Text("Отмена") }
                            }
                        }
                        ModelState.Installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✓ Скачана · ${spec.sizeMb} МБ", Modifier.weight(1f), color = Color(0xFF2E7D32),
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onAction) { Text("Удалить") }
                        }
                        is ModelState.Failed -> Column {
                            Text("Ошибка: ${st.message}", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = onAction) { Text("Скачать снова (${spec.sizeMb} МБ)") }
                        }
                        ModelState.NotInstalled -> OutlinedButton(onClick = onAction) { Text("Скачать (${spec.sizeMb} МБ)") }
                    }
                }
            }
        }
    }

    // ---------------- Действия с результатом ----------------

    private fun safeName(label: String) = label.substringBeforeLast('.', label)
        .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifEmpty { "Транскрипция" }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Транскрипция", text))
        if (Build.VERSION.SDK_INT < 33) toast("Скопировано")
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Отправить транскрипцию"))
    }

    private fun shareFile(text: String, label: String) {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val f = File(dir, safeName(label) + ".txt").apply { writeText(text) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Отправить файл"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
