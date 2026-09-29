package com.egorchenkov.transcriber

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.Vad

// Ядро без зависимостей от Android: его же гоняет проверка на сервере (tools/jvm-check).

const val SAMPLE_RATE = 16000

/** Кусок текста: время в секундах, speaker = -1 без разделения говорящих. */
data class Piece(val start: Float, val end: Float, val text: String, val speaker: Int = -1)

data class Transcript(
    val name: String,
    val durationSec: Float,
    val model: String,
    val pieces: List<Piece>,
    val diarized: Boolean,
)

class Cancelled : Exception("отменено")

/** Источник звука: вызывает колбэк с порциями 16 кГц моно и позицией (с); false — прервать. */
typealias AudioSource = ((FloatArray, Float) -> Boolean) -> Unit

class Pipeline(
    private val recognizer: OfflineRecognizer,
    private val vad: Vad,
    private val diarizer: OfflineSpeakerDiarization?,
    private val maxChunkSec: Float,
) {
    val diarized get() = diarizer != null

    fun run(
        source: AudioSource,
        duration: Float,
        onProgress: (Float, String) -> Unit,
        cancelled: () -> Boolean,
    ): List<Piece> =
        if (diarizer == null) plain(source, duration, onProgress, cancelled)
        else withSpeakers(source, duration, onProgress, cancelled)

    private fun plain(
        source: AudioSource,
        duration: Float,
        onProgress: (Float, String) -> Unit,
        cancelled: () -> Boolean,
    ): List<Piece> {
        val out = mutableListOf<Piece>()
        val batcher = Batcher(maxChunkSec) { start, samples ->
            recognize(samples)?.let { out += Piece(start, start + samples.size / SAMPLE_RATE.toFloat(), it) }
        }
        val feeder = VadFeeder(vad, 0, batcher)
        source { chunk, t ->
            feeder.feed(chunk)
            if (duration > 0) onProgress((t / duration).coerceIn(0f, 1f), "распознавание")
            !cancelled()
        }
        if (cancelled()) throw Cancelled()
        feeder.finish()
        return out
    }

    private fun withSpeakers(
        source: AudioSource,
        duration: Float,
        onProgress: (Float, String) -> Unit,
        cancelled: () -> Boolean,
    ): List<Piece> {
        // 1. Весь файл в память (диаризации нужен целиком)
        val audio = GrowableFloats(((duration + 5) * SAMPLE_RATE).toInt().coerceAtLeast(SAMPLE_RATE))
        source { chunk, t ->
            audio.add(chunk)
            if (duration > 0) onProgress(0.1f * (t / duration).coerceIn(0f, 1f), "чтение аудио")
            !cancelled()
        }
        if (cancelled()) throw Cancelled()
        // Без копирования: хвост массива — нули (тишина), диаризации это не мешает
        val samples = audio.raw()

        // 2. Кто и когда говорит
        onProgress(0.1f, "поиск говорящих")
        val segs = diarizer!!.processWithCallback(samples, { done, total, _ ->
            if (total > 0) onProgress(0.1f + 0.4f * done / total, "поиск говорящих")
            0
        }).sortedBy { it.start }
        if (cancelled()) throw Cancelled()

        // 3. Реплики: склеиваем соседние куски одного говорящего, убираем перекрытия
        val turns = mutableListOf<Turn>()
        for (s in segs) {
            val last = turns.lastOrNull()
            var start = s.start
            if (last != null && start < last.end) start = last.end
            if (s.end - start < 0.3f) continue
            if (last != null && last.speaker == s.speaker && start - last.end < 1.0f) {
                last.end = s.end
            } else {
                turns += Turn(start, s.end, s.speaker)
            }
        }
        // Нумерация говорящих в порядке появления
        val order = LinkedHashMap<Int, Int>()
        turns.forEach { order.getOrPut(it.speaker) { order.size } }

        // 4. Распознаём каждую реплику
        val out = mutableListOf<Piece>()
        val total = turns.sumOf { (it.end - it.start).toDouble() }.toFloat().coerceAtLeast(1f)
        var doneSec = 0f
        for (turn in turns) {
            if (cancelled()) throw Cancelled()
            val from = (turn.start * SAMPLE_RATE).toInt().coerceIn(0, samples.size)
            val to = (turn.end * SAMPLE_RATE).toInt().coerceIn(from, samples.size)
            val speaker = order.getValue(turn.speaker)
            val batcher = Batcher(maxChunkSec) { start, chunk ->
                recognize(chunk)?.let {
                    out += Piece(start, start + chunk.size / SAMPLE_RATE.toFloat(), it, speaker)
                }
            }
            val feeder = VadFeeder(vad, from, batcher)
            feeder.feed(samples.copyOfRange(from, to))
            feeder.finish()
            doneSec += turn.end - turn.start
            onProgress(0.5f + 0.5f * doneSec / total, "распознавание")
        }
        return out
    }

    private class Turn(val start: Float, var end: Float, val speaker: Int)

    private fun recognize(samples: FloatArray): String? {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            return text.takeIf { it.isNotEmpty() && !isHallucination(it) }
        } finally {
            stream.release()
        }
    }

    // Типичные «галлюцинации» Whisper на тишине и шуме
    private fun isHallucination(t: String): Boolean {
        val s = t.lowercase()
        return s.length < 80 && listOf(
            "субтитры", "dimatorzok", "продолжение следует", "спасибо за просмотр", "подписывайтесь на канал",
        ).any { s.contains(it) }
    }

    companion object {
        const val VAD_WINDOW = 512
    }
}

/** Подаёт звук в VAD окнами и передаёт найденную речь в Batcher. */
private class VadFeeder(private val vad: Vad, private val offset: Int, private val batcher: Batcher) {
    private var pending = FloatArray(0)

    init {
        vad.reset()
    }

    fun feed(x: FloatArray) {
        val buf = if (pending.isEmpty()) x else pending + x
        var i = 0
        while (i + Pipeline.VAD_WINDOW <= buf.size) {
            vad.acceptWaveform(buf.copyOfRange(i, i + Pipeline.VAD_WINDOW))
            i += Pipeline.VAD_WINDOW
            drain()
        }
        pending = buf.copyOfRange(i, buf.size)
    }

    fun finish() {
        if (pending.isNotEmpty()) vad.acceptWaveform(pending)
        pending = FloatArray(0)
        vad.flush()
        drain()
        batcher.flush()
    }

    private fun drain() {
        while (!vad.empty()) {
            val s = vad.front()
            vad.pop()
            batcher.add(offset + s.start, s.samples)
        }
    }
}

/**
 * Склеивает короткие фрагменты речи в куски до maxSec (с паузой 0.3 с между ними):
 * модели точнее с контекстом, чем на отдельных коротких фразах.
 */
private class Batcher(maxSec: Float, private val emit: (Float, FloatArray) -> Unit) {
    private val maxSamples = (maxSec * SAMPLE_RATE).toInt()
    private val gap = FloatArray((0.3f * SAMPLE_RATE).toInt())
    private val parts = mutableListOf<FloatArray>()
    private var size = 0
    private var start = -1
    private var lastEnd = 0

    fun add(sampleStart: Int, samples: FloatArray) {
        if (parts.isNotEmpty() &&
            (size + gap.size + samples.size > maxSamples || sampleStart - lastEnd > 3 * SAMPLE_RATE)
        ) flush()
        if (start < 0) start = sampleStart
        if (parts.isNotEmpty()) { parts += gap; size += gap.size }
        parts += samples
        size += samples.size
        lastEnd = sampleStart + samples.size
    }

    fun flush() {
        if (parts.isEmpty()) return
        val all = FloatArray(size)
        var p = 0
        for (a in parts) { a.copyInto(all, p); p += a.size }
        emit(start / SAMPLE_RATE.toFloat(), all)
        parts.clear()
        size = 0
        start = -1
    }
}

private class GrowableFloats(initial: Int) {
    private var data = FloatArray(initial)
    private var n = 0

    fun add(x: FloatArray) {
        if (n + x.size > data.size) data = data.copyOf(maxOf(data.size * 3 / 2, n + x.size))
        x.copyInto(data, n)
        n += x.size
    }

    fun raw(): FloatArray = data
}
