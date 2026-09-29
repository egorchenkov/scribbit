package com.egorchenkov.transcriber

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Текст для чтения человеком и для отправки в LLM: простой, без разметки Markdown. */
object Formatter {
    fun time(sec: Float): String {
        val s = sec.toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }

    fun document(list: List<Transcript>, timestamps: Boolean): String {
        if (list.size == 1) return one(list[0], timestamps)
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date())
        return "Транскрипции (${list.size} файлов), $date\n\n" +
            list.joinToString("\n\n----------\n\n") { one(it, timestamps) }
    }

    fun one(t: Transcript, timestamps: Boolean): String {
        val sb = StringBuilder()
        sb.append("Транскрипция: ").append(t.name).append('\n')
        val meta = mutableListOf("длительность ${time(t.durationSec)}", t.model)
        if (t.diarized) meta += "говорящих: ${t.pieces.map { it.speaker }.distinct().size}"
        sb.append(meta.joinToString(" · ")).append("\n\n")
        if (t.pieces.isEmpty()) {
            sb.append("(речь не обнаружена)")
            return sb.toString()
        }
        paragraphs(t).forEach { p ->
            if (timestamps) sb.append('[').append(time(p.start)).append("] ")
            if (t.diarized) sb.append("Спикер ").append(p.speaker + 1).append(": ")
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
