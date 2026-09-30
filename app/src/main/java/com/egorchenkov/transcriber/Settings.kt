package com.egorchenkov.transcriber

import android.content.Context

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var modelId: String
        get() = p.getString("model", Models.asr.first().id)!!
        set(v) = p.edit().putString("model", v).apply()

    /** Язык для Whisper: "" — авто, иначе ru/en/uz. */
    var language: String
        get() = p.getString("language", "ru")!!
        set(v) = p.edit().putString("language", v).apply()

    var timestamps: Boolean
        get() = p.getBoolean("timestamps", true)
        set(v) = p.edit().putBoolean("timestamps", v).apply()

    /** Разделять говорящих (действует, если модель диаризации скачана). */
    var diarize: Boolean
        get() = p.getBoolean("diarize", true)
        set(v) = p.edit().putBoolean("diarize", v).apply()

    /** Число говорящих: 0 — определить автоматически. */
    var speakers: Int
        get() = p.getInt("speakers", 0)
        set(v) = p.edit().putInt("speakers", v).apply()

    /** Пользователь отметил, что настроил фоновую работу в системных настройках производителя. */
    var bgConfirmed: Boolean
        get() = p.getBoolean("bgConfirmed", false)
        set(v) = p.edit().putBoolean("bgConfirmed", v).apply()

    /** Сколько раз и на сколько секунд система замораживала распознавание при выключенном экране. */
    var stallCount: Int
        get() = p.getInt("stallCount", 0)
        set(v) = p.edit().putInt("stallCount", v).apply()

    var stallSec: Long
        get() = p.getLong("stallSec", 0L)
        set(v) = p.edit().putLong("stallSec", v).apply()

    /** Очередь шла, когда процесс остановили (система убила): при открытии приложения продолжить. */
    var interrupted: Boolean
        get() = p.getBoolean("interrupted", false)
        set(v) = p.edit().putBoolean("interrupted", v).commit().let { }
}
