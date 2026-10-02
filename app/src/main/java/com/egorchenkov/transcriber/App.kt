package com.egorchenkov.transcriber

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        applyLocale()
        Jobs.init(this)
        BgLog.init(this)
        // Имена каналов пересоздаются при каждом старте — так они следуют за языком системы
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_WORK, getString(R.string.channel_work), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_REC, getString(R.string.channel_rec), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLocale()
    }

    /** Тексты ядра (стадии, заголовки результата) — по языку приложения; экран берёт свои из ресурсов сам. */
    private fun applyLocale() {
        CoreTexts.current = CoreTexts.forLocale(resources.configuration.locales[0])
    }

    companion object {
        const val CH_WORK = "work"
        const val CH_REC = "rec"
        const val CH_DONE = "done"

        fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
