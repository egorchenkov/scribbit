package com.egorchenkov.transcriber

import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Запись с микрофона в фоне (с выключенным экраном) — foreground-сервис типа microphone. */
class RecorderService : Service() {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (recorder != null) return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date())
        val f = File(File(filesDir, "recordings").apply { mkdirs() }, "Запись $stamp.m4a")
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, App.CH_REC)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Идёт запись")
            .setContentText("Нажмите «Стоп», чтобы завершить")
            .setUsesChronometer(true)
            .setOngoing(true)
            .setContentIntent(App.openAppIntent(this))
            .addAction(0, "Стоп", stopIntent)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, n,
            if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            _state.value = RecState(SystemClock.elapsedRealtime(), f.name)
        } catch (e: Exception) {
            _state.value = null
            _error.value = "Не удалось начать запись: ${e.message}"
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stop() {
        val r = recorder
        val f = file
        recorder = null
        file = null
        if (r != null && f != null) {
            val ok = runCatching { r.stop() }.isSuccess
            r.release()
            if (ok && f.length() > 0) {
                // Копия в Music/Transcriber — источник для повторной транскрибации
                Jobs.add(f.name, f, source = copyToMusic(f)?.toString())
            } else {
                f.delete()
            }
        }
        _state.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Копия в общую папку Music/Transcriber, чтобы запись была видна в файловом менеджере. */
    private fun copyToMusic(f: File): android.net.Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Transcriber")
            }
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            uri
        }.getOrNull()
    }

    override fun onDestroy() {
        if (recorder != null) stop()
        super.onDestroy()
    }

    data class RecState(val startedAt: Long, val name: String)

    companion object {
        private const val NOTIF_ID = 2
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"

        private val _state = MutableStateFlow<RecState?>(null)
        val state: StateFlow<RecState?> = _state
        private val _error = MutableStateFlow<String?>(null)
        val error = _error

        fun start(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, RecorderService::class.java).setAction(ACTION_START)
        )

        fun stop(ctx: Context) = ctx.startService(
            Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP)
        )
    }
}
