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
        isHuawei -> "Батарея → Запуск приложений → Транскрибатор: выключите «Автоматическое управление» " +
            "и включите три переключателя (автозапуск, косвенный запуск, работа в фоне). " +
            "Ещё: закрепите приложение замком в списке недавних."
        isOem("xiaomi", "redmi", "poco") -> "Включите «Автозапуск» и в «Экономия заряда» выберите «Нет ограничений»."
        isOem("samsung") -> "Батарея → Ограничения в фоне: уберите приложение из «Спящих» и «Глубоко спящих»."
        isOem("oppo", "realme", "vivo", "oneplus") -> "Разрешите автозапуск и работу в фоне; экономию батареи для приложения выключите."
        else -> "Выберите для приложения «Не ограничивать» в настройках батареи."
    }

    private fun tryStart(a: Activity, i: Intent): Boolean = runCatching { a.startActivity(i); true }.getOrDefault(false)
}
