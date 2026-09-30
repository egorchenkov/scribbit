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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Распознаёт очередь в фоне (работает и с выключенным экраном). */
class TranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var working = false
    private lateinit var wakeLock: PowerManager.WakeLock
    private var lastNotify = 0L
    private var lastProgressAt = 0L
    private var lastInteractive = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "transcriber:work")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, progressNotification("Подготовка…", 0f),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (!working) {
            working = true
            Jobs.cancelRequested = false
            wakeLock.acquire(6 * 60 * 60 * 1000L)
            scope.launch { runQueue() }
        }
        return START_NOT_STICKY
    }

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
                try {
                    if (!mm.isInstalled(spec)) error("модель «${spec.title}» не скачана — откройте настройки")
                    val t = transcriber ?: Transcriber(this, spec, settings.language, diarize, settings.speakers)
                        .also { transcriber = it }
                    lastProgressAt = 0L
                    val result = t.transcribe(job.name, job.file, { p, stage ->
                        noteProgress(settings)
                        Jobs.update(job.id) { it.copy(progress = p, stage = stage) }
                        notifyProgress(job.name, p, stage)
                    }, { Jobs.cancelRequested })
                    Jobs.update(job.id) { it.copy(status = Status.DONE, progress = 1f, result = result) }
                    done++
                } catch (e: Cancelled) {
                    Jobs.update(job.id) { it.copy(status = Status.QUEUED, progress = 0f, stage = "") }
                } catch (e: Throwable) {
                    Jobs.update(job.id) { it.copy(status = Status.ERROR, error = e.message ?: e.javaClass.simpleName) }
                }
            }
        } finally {
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
            settings.stallCount += 1
            settings.stallSec += (now - lastProgressAt) / 1000
        }
        lastProgressAt = now
        lastInteractive = getSystemService(PowerManager::class.java).isInteractive
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

    override fun onDestroy() {
        Jobs.cancelRequested = true
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val DONE_ID = 3
        private const val STALL_MS = 90_000L

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TranscriptionService::class.java))
        }
    }
}
