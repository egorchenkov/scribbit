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
}
