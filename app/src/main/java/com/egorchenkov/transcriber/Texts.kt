package com.egorchenkov.transcriber

import java.util.Locale

/**
 * Тексты ядра (Pipeline, Formatter) — без Android-ресурсов, чтобы тот же код шёл и на JVM (tools/jvm-check).
 * Приложение выставляет [current] по языку системы при старте и при смене конфигурации.
 * Строки экрана, уведомлений и моделей — в res/values*, здесь только то, что порождает ядро.
 */
class CoreTexts(
    val locale: Locale,
    /** Стадии прогресса */
    val recognizing: String,
    val findingSpeakers: String,
    private val partOf: String,
    private val resumingSkip: String,
    private val resumingFrom: String,
    private val skippedPart: String,
    /** Текст результата */
    private val speakerN: String,
    val transcriptHeader: String,
    private val transcriptsHeader: String,
    private val durationMeta: String,
    private val speakersMeta: String,
    val noSpeech: String,
    val cancelled: String,
) {
    fun part(n: Int, of: Int) = partOf.format(locale, n, of)
    fun resumingSkip(part: Int) = resumingSkip.format(locale, part)
    fun resumingFrom(part: Int) = resumingFrom.format(locale, part)
    fun skippedPart(part: Int) = skippedPart.format(locale, part)
    fun speaker(n: Int) = speakerN.format(locale, n)
    fun transcripts(n: Int, date: String) = transcriptsHeader.format(locale, n, date)
    fun duration(t: String) = durationMeta.format(locale, t)
    fun speakers(n: Int) = speakersMeta.format(locale, n)

    companion object {
        val EN = CoreTexts(
            Locale.ENGLISH,
            recognizing = "recognizing",
            findingSpeakers = "finding speakers",
            partOf = "part %d of %d · ",
            resumingSkip = "resuming from part %d · skipping finished",
            resumingFrom = "resuming from part %d",
            skippedPart = "[part %d skipped: recognition failure]",
            speakerN = "Speaker %d",
            transcriptHeader = "Transcript: ",
            transcriptsHeader = "Transcripts (%d files), %s",
            durationMeta = "duration %s",
            speakersMeta = "speakers: %d",
            noSpeech = "(no speech detected)",
            cancelled = "cancelled",
        )
        val RU = CoreTexts(
            Locale("ru"),
            recognizing = "распознавание",
            findingSpeakers = "поиск говорящих",
            partOf = "часть %d из %d · ",
            resumingSkip = "продолжение с части %d · пропуск готового",
            resumingFrom = "продолжение с части %d",
            skippedPart = "[часть %d пропущена: сбой распознавания]",
            speakerN = "Спикер %d",
            transcriptHeader = "Транскрипция: ",
            transcriptsHeader = "Транскрипции (%d файлов), %s",
            durationMeta = "длительность %s",
            speakersMeta = "говорящих: %d",
            noSpeech = "(речь не обнаружена)",
            cancelled = "отменено",
        )
        // Узбекский (латиница) — черновой перевод, ждёт вычитки носителем
        val UZ = CoreTexts(
            Locale("uz"),
            recognizing = "tanish",
            findingSpeakers = "soʻzlovchilar qidirilmoqda",
            partOf = "%d-qism / %d · ",
            resumingSkip = "%d-qismdan davom · tayyor qismi oʻtkazilmoqda",
            resumingFrom = "%d-qismdan davom",
            skippedPart = "[%d-qism oʻtkazib yuborildi: tanishda xato]",
            speakerN = "%d-soʻzlovchi",
            transcriptHeader = "Transkripsiya: ",
            transcriptsHeader = "Transkripsiyalar (%d fayl), %s",
            durationMeta = "davomiyligi %s",
            speakersMeta = "soʻzlovchilar: %d",
            noSpeech = "(nutq aniqlanmadi)",
            cancelled = "bekor qilindi",
        )

        @Volatile var current: CoreTexts = forLocale(Locale.getDefault())

        fun forLocale(l: Locale): CoreTexts = when (l.language) {
            "ru" -> RU
            "uz" -> UZ
            else -> EN
        }
    }
}
