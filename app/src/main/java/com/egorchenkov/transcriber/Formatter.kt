package com.egorchenkov.transcriber

import java.text.DateFormat
import java.util.Date

/** Текст для чтения человеком и для отправки в LLM: простой, без разметки Markdown. */
object Formatter {
    fun time(sec: Float): String {
        val s = sec.toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }

    /** Дата и время в формате текущего языка (ru: 02.10.2026, 22:10; en: Oct 2, 2026, 10:10 PM). */
    fun dateTime(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, CoreTexts.current.locale).format(Date(ms))

    /** Начало текста для карточки истории. */
    fun preview(t: Transcript): String =
        t.pieces.joinToString(" ") { it.text }.take(200).ifEmpty { CoreTexts.current.noSpeech }

    fun document(list: List<Transcript>, timestamps: Boolean): String {
        if (list.size == 1) return one(list[0], timestamps)
        return CoreTexts.current.transcripts(list.size, dateTime(System.currentTimeMillis())) + "\n\n" +
            list.joinToString("\n\n----------\n\n") { one(it, timestamps) }
    }

    fun one(t: Transcript, timestamps: Boolean): String {
        val sb = StringBuilder()
        val x = CoreTexts.current
        sb.append(x.transcriptHeader).append(t.name).append('\n')
        val meta = mutableListOf(x.duration(time(t.durationSec)), t.model)
        if (t.diarized) meta += x.speakers(t.pieces.map { it.speaker }.filter { it >= 0 }.distinct().size)
        sb.append(meta.joinToString(" · ")).append("\n\n")
        if (t.pieces.isEmpty()) {
            sb.append(x.noSpeech)
            return sb.toString()
        }
        paragraphs(t).forEach { p ->
            if (timestamps) sb.append('[').append(time(p.start)).append("] ")
            if (t.diarized && p.speaker >= 0) sb.append(x.speaker(p.speaker + 1)).append(": ")
            sb.append(p.text).append("\n\n")
        }
        return sb.toString().trimEnd()
    }

    /** Абзацы: смена говорящего, пауза > 2 с или длинный абзац. */
    private fun paragraphs(t: Transcript): List<Piece> {
        val out = mutableListOf<Piece>()
        for (p in t.pieces) {
            val last = out.lastOrNull()
            if (last != null && last.speaker == p.speaker && p.start - last.end < 2f && last.text.length < 700) {
                out[out.size - 1] = last.copy(end = p.end, text = join(last.text, p.text))
            } else {
                out += p
            }
        }
        return out
    }

    /** Модель не ставит точку в конце куска — добавляем, если следующий начинается с заглавной. */
    private fun join(a: String, b: String): String {
        val needDot = a.isNotEmpty() && a.last().isLetterOrDigit() && b.firstOrNull()?.isUpperCase() == true
        return a + (if (needDot) ". " else " ") + b
    }
}
