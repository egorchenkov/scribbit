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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var screen by remember { mutableStateOf("main") }
                BackHandler(screen != "main") { screen = "main" }
                if (screen == "main") MainScreen(onSettings = { screen = "settings" })
                else SettingsScreen(onBack = { screen = "main" })
            }
        }
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
    fun addUris(uris: List<Uri>, autoStart: Boolean) {
        val app = applicationContext
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val inbox = File(filesDir, "inbox").apply { mkdirs() }
            var added = 0
            for (uri in uris) {
                try {
                    val name = displayName(uri) ?: "аудио-${System.currentTimeMillis()}"
                    val dst = File(inbox, "${System.nanoTime()}_${name.replace('/', '_')}")
                    contentResolver.openInputStream(uri)!!.use { input -> dst.outputStream().use { input.copyTo(it) } }
                    Jobs.add(name, dst)
                    added++
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, "Не удалось открыть файл: ${e.message}", Toast.LENGTH_LONG).show()
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
    fun MainScreen(onSettings: () -> Unit) {
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
        val timestamps = settings.timestamps

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) addUris(uris, autoStart = false)
        }
        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) RecorderService.start(this) else toast("Нужно разрешение на микрофон")
        }
        LaunchedEffect(recError) { recError?.let { toast(it); RecorderService.error.value = null } }

        val results = jobs.mapNotNull { it.result }
        val document = remember(results, timestamps) {
            if (results.isEmpty()) "" else Formatter.document(results, timestamps)
        }
        val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) runCatching {
                contentResolver.openOutputStream(uri)!!.use { it.write(document.toByteArray()) }
                toast("Сохранено")
            }.onFailure { toast("Ошибка сохранения: ${it.message}") }
        }

        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Транскрибатор") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Настройки") }
                },
            )
        }) { pad ->
            LazyColumn(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                if (jobs.isNotEmpty()) {
                    items(jobs, key = { it.id }) { job -> JobRow(job) }
                    item {
                        val queued = jobs.count { it.status == Status.QUEUED }
                        val running = jobs.any { it.status == Status.RUNNING }
                        val failed = jobs.count { it.status == Status.ERROR }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (running) {
                                var stopping by remember { mutableStateOf(false) }
                                OutlinedButton(
                                    onClick = { stopping = true; Jobs.cancelRequested = true },
                                    enabled = !stopping,
                                ) { Text(if (stopping) "Останавливаю…" else "Остановить") }
                            } else {
                                Button(
                                    onClick = { TranscriptionService.start(this@MainActivity) },
                                    enabled = queued > 0 && installed,
                                ) { Text("Транскрибировать ($queued)") }
                            }
                            if (failed > 0 && !running) TextButton(onClick = { Jobs.retryFailed() }) { Text("Повторить") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { Jobs.clear() }, enabled = !running) { Text("Очистить") }
                        }
                    }
                } else item {
                    Text(
                        "Запишите разговор или выберите аудиофайлы. Можно также «Поделиться» голосовым " +
                            "или файлом из Telegram → «Транскрибатор».",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (document.isNotEmpty()) {
                    item {
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { shareText(document) }) { Text("Отправить") }
                            OutlinedButton(onClick = { copy(document) }) { Text("Копировать") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { shareFile(document) }) { Text("Файлом .txt") }
                            OutlinedButton(onClick = { saver.launch(fileName() + ".txt") }) { Text("Сохранить") }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            SelectionContainer {
                                Text(document, Modifier.padding(12.dp), fontSize = 15.sp)
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun JobRow(job: Job) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.name, fontWeight = FontWeight.Medium, maxLines = 2)
                    when (job.status) {
                        Status.QUEUED -> Text("ожидает", style = MaterialTheme.typography.bodySmall)
                        Status.RUNNING -> {
                            Text("${job.stage} ${(job.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp))
                        }
                        Status.DONE -> Text(
                            "готово · " + Formatter.time(job.result?.durationSec ?: 0f),
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32),
                        )
                        Status.ERROR -> Text("ошибка: ${job.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (job.status != Status.RUNNING) {
                    IconButton(onClick = { Jobs.remove(job.id) }) { Icon(Icons.Default.Close, "Убрать") }
                }
            }
        }
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

    private fun fileName() = "Транскрипция " +
        java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.US).format(java.util.Date())

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Транскрипция", text))
        if (Build.VERSION.SDK_INT < 33) toast("Скопировано")
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Отправить транскрипцию"))
    }

    private fun shareFile(text: String) {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val f = File(dir, fileName() + ".txt").apply { writeText(text) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Отправить файл"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
