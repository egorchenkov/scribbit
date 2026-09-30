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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
    private var silent: AudioTrack? = null
    private var heartbeat: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "transcriber:work")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val keepAlive = Settings(this).keepAliveAudio
        ServiceCompat.startForeground(
            this, NOTIF_ID, progressNotification("Подготовка…", 0f),
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    (if (keepAlive) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
            } else 0,
        )
        if (!working) {
            working = true
            BgLog.log("старт: тихое аудио=$keepAlive, экран включён=${getSystemService(PowerManager::class.java).isInteractive}")
            if (keepAlive) startSilentAudio()
            startHeartbeat()
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
            BgLog.log("конец: готово файлов $done")
            heartbeat?.cancel()
            stopSilentAudio()
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

    /** Беззвучный поток (±1 единица, неслышно): процесс со звуком система не замораживает при выключенном экране. */
    private fun startSilentAudio() {
        runCatching {
            val rate = 8000
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setBufferSizeInBytes(maxOf(min, rate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            silent = track
            Thread({
                val buf = ShortArray(rate / 2) { if (it % 2 == 0) 1 else -1 }
                while (silent === track) if (track.write(buf, 0, buf.size) < 0) break
            }, "keepalive").apply { isDaemon = true }.start()
        }.onFailure { BgLog.log("тихое аудио не запустилось: ${it.message}") }
    }

    private fun stopSilentAudio() {
        val t = silent
        silent = null
        runCatching { t?.stop(); t?.release() }
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
