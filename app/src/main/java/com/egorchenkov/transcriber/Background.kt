package com.egorchenkov.transcriber

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings

/** Работа при выключенном экране: исключение из экономии батареи и настройки производителя. */
object Background {
    private val maker get() = Build.MANUFACTURER.lowercase()

    private fun isOem(vararg names: String) = names.any { maker.contains(it) }

    val isHuawei get() = isOem("huawei", "honor")

    /** Производитель, у которого есть собственный менеджер запуска, помимо стандартной оптимизации батареи. */
    val hasOemManager get() = isOem("huawei", "honor", "xiaomi", "redmi", "poco", "oppo", "realme", "vivo", "oneplus", "samsung")

    fun ignoringBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun powerSave(ctx: Context): Boolean = ctx.getSystemService(PowerManager::class.java).isPowerSaveMode

    /** «Ограничить работу в фоне» в сведениях о приложении (Android 9+). */
    fun backgroundRestricted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 28 && ctx.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted

    fun notificationsOn(ctx: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** Группа ожидания Android (10 — активное … 45 — ограниченное). */
    fun standbyBucket(ctx: Context): Int =
        if (Build.VERSION.SDK_INT >= 28) ctx.getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket else 0

    /** Строка состояния для журнала. */
    fun summary(ctx: Context): String =
        "игнор батареи=${ignoringBatteryOptimizations(ctx)}; энергосбережение=${powerSave(ctx)}; " +
            "фон ограничен=${backgroundRestricted(ctx)}; уведомления=${notificationsOn(ctx)}; группа=${standbyBucket(ctx)}"

    fun openPowerSaver(a: Activity) {
        tryStart(a, Intent(AndroidSettings.ACTION_BATTERY_SAVER_SETTINGS)) || openAppInfo(a)
    }

    fun openNotifications(a: Activity) {
        tryStart(a, Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, a.packageName)) ||
            openAppInfo(a)
    }

    /** Системный диалог «Не ограничивать»; если недоступен — общий список исключений. */
    fun requestIgnore(a: Activity) {
        val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))
        if (!tryStart(a, direct)) {
            tryStart(a, Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || openAppInfo(a)
        }
    }

    /** Экран запуска/автозапуска производителя; если не открылся — сведения о приложении. */
    fun openOemManager(a: Activity) {
        val list = when {
            isHuawei -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            )
            isOem("xiaomi", "redmi", "poco") ->
                listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity")
            isOem("oppo", "realme") -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            )
            isOem("vivo") ->
                listOf("com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            isOem("oneplus") ->
                listOf("com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            isOem("samsung") ->
                listOf("com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity")
            else -> emptyList()
        }
        for ((pkg, cls) in list) {
            if (tryStart(a, Intent().setComponent(ComponentName(pkg, cls)))) return
        }
        openAppInfo(a)
    }

    fun openAppInfo(a: Activity): Boolean = tryStart(
        a, Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")),
    )

    /** Что включить в настройках производителя. */
    fun instruction(): String = when {
        isHuawei -> "Батарея → Запуск приложений → Транскрибатор: выключите «Управлять автоматически» " +
            "и в появившемся окне включите все три переключателя, особенно «Работа в фоне». " +
            "Без этого EMUI замораживает приложение через несколько секунд после выключения экрана, " +
            "даже при снятом ограничении батареи."
        isOem("xiaomi", "redmi", "poco") -> "Включите «Автозапуск» и в «Экономия заряда» выберите «Нет ограничений»."
        isOem("samsung") -> "Батарея → Ограничения в фоне: уберите приложение из «Спящих» и «Глубоко спящих»."
        isOem("oppo", "realme", "vivo", "oneplus") -> "Разрешите автозапуск и работу в фоне; экономию батареи для приложения выключите."
        else -> "Выберите для приложения «Не ограничивать» в настройках батареи."
    }

    private fun tryStart(a: Activity, i: Intent): Boolean = runCatching { a.startActivity(i); true }.getOrDefault(false)
}

/** Журнал фоновой работы (только время и состояние, без содержимого записей) — для диагностики заморозок. */
object BgLog {
    private var file: java.io.File? = null
    private val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)

    fun init(ctx: Context) {
        val f = java.io.File(ctx.filesDir, "bg.log")
        if (f.length() > 100_000) f.writeText("")
        file = f
    }

    /** Причины последних завершений процесса (Android 11+): отличает убийство системой от нехватки памяти и падения. */
    fun logExits(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 3).forEach {
                log("прошлый выход: причина=${it.reason} статус=${it.status} важность=${it.importance} " +
                    "память=${it.pss / 1024}МБ время=${fmt.format(java.util.Date(it.timestamp))} ${it.description.orEmpty()}")
            }
            // Нативное падение: в трассировке (tombstone) ищем сообщение об аварии и имена функций — без данных записи
            am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()
                ?.takeIf { it.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE }
                ?.traceInputStream?.use { st ->
                    val runs = Regex("[ -~]{8,}").findAll(String(st.readBytes(), Charsets.ISO_8859_1)).map { it.value }
                    val keys = listOf("terminate", "bad_alloc", "what(", "Abort", "std::", "Check failed", "ailed", "libsherpa", "libonnx")
                    runs.filter { r -> keys.any { r.contains(it) } }.distinct().take(25)
                        .forEach { log("трассировка: " + it.take(200)) }
                }
        }
    }

    @Synchronized fun log(msg: String) {
        runCatching { file?.appendText(fmt.format(java.util.Date()) + " " + msg + "\n") }
    }

    fun text(): String = runCatching { file?.readText() }.getOrNull().orEmpty()
}
