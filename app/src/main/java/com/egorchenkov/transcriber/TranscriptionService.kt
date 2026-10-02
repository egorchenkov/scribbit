package com.egorchenkov.transcriber

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Распознаёт очередь в фоне (работает и с выключенным экраном). */
class TranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var working = false
    private lateinit var wakeLock: PowerManager.WakeLock
    private var lastNotify = 0L
    private var lastProgressAt = 0L
    private var lastInteractive = true
    private var heartbeat: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "transcriber:work")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Android 14+: specialUse — без суточного лимита (dataSync на Android 15 обрезается через 6 ч/сутки)
            val type = when {
                Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else -> 0
            }
            ServiceCompat.startForeground(this, NOTIF_ID, progressNotification("Подготовка…", 0f), type)
        } catch (e: Exception) {
            // Android 12+: из фона без исключения из экономии батареи запуск запрещён — продолжим при открытии приложения
            BgLog.log("запуск в фоне запрещён системой: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!working) {
            working = true
            // intent == null — система сама перезапустила сервис после убийства процесса
            BgLog.log(
                (if (intent == null) "перезапуск системой" else "старт") +
                    ", экран включён=${getSystemService(PowerManager::class.java).isInteractive}; " +
                    Background.summary(this),
            )
            BgLog.logExits(this)
            startHeartbeat()
            Settings(this).interrupted = true
            Jobs.cancelRequested = false
            holdWakeLock()
            scope.launch { runQueue() }
        }
        // Если систему убьёт процесс, она перезапустит сервис, и очередь продолжится с точки продолжения
        return START_STICKY
    }

    /** Wake lock с таймаутом, продлеваемый прогрессом: зависшая работа не держит процессор часами. */
    private fun holdWakeLock() = wakeLock.acquire(WAKE_MS)

    private fun runQueue() {
        val settings = Settings(this)
        val spec = Models.byId(settings.modelId) ?: Models.asr.first()
        val mm = ModelManager(this)
        val diarize = settings.diarize && mm.isInstalled(Models.diarization)
        var done = 0
        var transcriber: Transcriber? = null
        try {
            while (!Jobs.cancelRequested) {
                val job = Jobs.nextQueued() ?: break
                Jobs.update(job.id) { it.copy(status = Status.RUNNING, progress = 0f, stage = "загрузка модели") }
                val key = "${spec.id}|${settings.language}|$diarize|${settings.speakers}"
                try {
                    if (!mm.isInstalled(spec)) error("модель «${spec.title}» не скачана — откройте настройки")
                    val t = transcriber ?: Transcriber(this, spec, settings.language, diarize, settings.speakers)
                        .also { transcriber = it }
                    lastProgressAt = 0L
                    var resume = Jobs.loadCheckpoint(job.id, key)
                    if (resume != null) {
                        if (resume.attempts >= MAX_ATTEMPTS) {
                            // Часть раз за разом роняет процесс (нативное падение): пропускаем её, а не крутим вечно
                            BgLog.log("часть ${resume.windows + 1} роняла процесс ${resume.attempts} раз — пропущена")
                            resume = resume.skipWindow(Pipeline.DEFAULT_WINDOW_SEC, "[часть ${resume.windows + 1} пропущена: сбой распознавания]")
                        } else {
                            // Попытка отмечается до начала работы: если процесс упадёт, она останется в точке
                            resume = resume.withAttempts(resume.attempts + 1)
                        }
                        Jobs.saveCheckpoint(job.id, key, resume)
                        BgLog.log("продолжение с части ${resume.windows + 1}, попытка ${resume.attempts}")
                        Jobs.update(job.id) { it.copy(stage = "продолжение с части ${resume.windows + 1}") }
                    }
                    val result = t.transcribe(job.name, job.file, { p, stage ->
                        noteProgress(settings)
                        Jobs.update(job.id) { it.copy(progress = p, stage = stage) }
                        notifyProgress(job.name, p, stage)
                    }, { Jobs.cancelRequested }, resume, { Jobs.saveCheckpoint(job.id, key, it) })
                    Jobs.update(job.id) { it.copy(status = Status.DONE, progress = 1f, result = result) }
                    done++
                } catch (e: Cancelled) {
                    // Остановка пользователем — не падение: попытки на точке обнуляем
                    Jobs.loadCheckpoint(job.id, key)?.takeIf { it.attempts > 0 }?.let { Jobs.saveCheckpoint(job.id, key, it.withAttempts(0)) }
                    Jobs.update(job.id) { it.copy(status = Status.QUEUED, progress = 0f, stage = "") }
                } catch (e: Throwable) {
                    Jobs.update(job.id) { it.copy(status = Status.ERROR, error = e.message ?: e.javaClass.simpleName) }
                }
            }
        } finally {
            BgLog.log("конец: готово файлов $done")
            settings.interrupted = false
            heartbeat?.cancel()
            transcriber?.close()
            working = false
            if (wakeLock.isHeld) wakeLock.release()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            if (done > 0) notifyDone(done)
            stopSelf()
        }
    }

    /**
     * Долгая пауза между отчётами о прогрессе при выключенном экране — признак заморозки процесса
     * системой (менеджер питания производителя). Считаем такие паузы, чтобы подсказать настройку.
     */
    private fun noteProgress(settings: Settings) {
        val now = System.currentTimeMillis()
        if (lastProgressAt != 0L && !lastInteractive && now - lastProgressAt > STALL_MS) {
            BgLog.log("ЗАМОРОЗКА: пауза ${(now - lastProgressAt) / 1000} с при выключенном экране")
            settings.stallCount += 1
            settings.stallSec += (now - lastProgressAt) / 1000
        }
        lastProgressAt = now
        lastInteractive = getSystemService(PowerManager::class.java).isInteractive
        holdWakeLock()
    }

    /** Раз в 15 с пишет в журнал состояние; разрыв между записями показывает, когда процесс стоял. */
    private fun startHeartbeat() {
        heartbeat = scope.launch {
            var last = System.currentTimeMillis()
            while (true) {
                delay(15_000)
                val now = System.currentTimeMillis()
                val gap = (now - last) / 1000
                last = now
                val p = Jobs.items.value.firstOrNull { it.status == Status.RUNNING }
                BgLog.log(
                    "пульс: +${gap} с" + (if (gap > 30) " (ПРОСТОЙ)" else "") +
                        ", экран=${getSystemService(PowerManager::class.java).isInteractive}, " +
                        "прогресс=${((p?.progress ?: 0f) * 100).toInt()}% ${p?.stage.orEmpty()}",
                )
            }
        }
    }

    private fun progressNotification(text: String, p: Float): Notification =
        NotificationCompat.Builder(this, App.CH_WORK)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Транскрибация")
            .setContentText(text)
            .setProgress(100, (p * 100).toInt(), p <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(App.openAppIntent(this))
            .build()

    private fun notifyProgress(name: String, p: Float, stage: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, progressNotification("$name — $stage ${(p * 100).toInt()}%", p))
    }

    private fun notifyDone(n: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            DONE_ID,
            NotificationCompat.Builder(this, App.CH_DONE)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Транскрипция готова")
                .setContentText("Файлов: $n — нажмите, чтобы открыть")
                .setAutoCancel(true)
                .setContentIntent(App.openAppIntent(this))
                .build(),
        )
    }

    /**
     * Android 15: система исчерпала лимит времени для типа сервиса. Останавливаемся мягко —
     * точка продолжения уже на диске, задача вернётся в очередь и продолжится при следующем запуске.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        BgLog.log("система ограничила время работы в фоне (тип $fgsType) — остановка, продолжим позже")
        Jobs.cancelRequested = true
    }

    override fun onDestroy() {
        Jobs.cancelRequested = true
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val DONE_ID = 3
        private const val STALL_MS = 90_000L
        /** Столько раз подряд с одной точки — и часть пропускается. */
        private const val MAX_ATTEMPTS = 3
        private const val WAKE_MS = 15 * 60 * 1000L

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TranscriptionService::class.java))
        }
    }
}
