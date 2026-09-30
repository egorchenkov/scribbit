package com.egorchenkov.transcriber

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
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
    private val embedder: SpeakerEmbeddingExtractor?,
    private val maxChunkSec: Float,
    /** Число говорящих: 0 — определить автоматически. */
    private val numSpeakers: Int = 0,
    /** Длина окна диаризации: в памяти держим только его, а не весь файл (4 ч = ~900 МБ). */
    private val windowSec: Float = 600f,
    /** Порог косинусной близости «тот же человек» при сшивке окон. */
    private val sameSpeaker: Float = 0.5f,
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

    /**
     * Звук идёт окнами по windowSec: в каждом окне диаризация → реплики → распознавание.
     * Говорящих из разных окон сшиваем по голосовым эмбеддингам (SpeakerBook).
     */
    private fun withSpeakers(
        source: AudioSource,
        duration: Float,
        onProgress: (Float, String) -> Unit,
        cancelled: () -> Boolean,
    ): List<Piece> {
        val out = mutableListOf<Piece>()
        val book = SpeakerBook(sameSpeaker)
        val windowSamples = (windowSec * SAMPLE_RATE).toInt()
        val win = GrowableFloats(windowSamples + SAMPLE_RATE)
        var winStart = 0
        var windows = 0

        fun processWindow(last: Boolean) {
            val samples = win.toArray()
            win.clear()
            val t0 = winStart / SAMPLE_RATE.toFloat()
            val len = samples.size / SAMPLE_RATE.toFloat()
            // Доля файла, которую занимает окно: 0..0.5 — поиск говорящих, 0.5..1 — распознавание
            fun progress(f: Float, stage: String) {
                if (duration > 0) onProgress(((t0 + len * f) / duration).coerceIn(0f, 1f), stage)
            }
            // Весь файл уместился в одно окно — можно задать точное число говорящих
            val n = if (last && windows == 0) numSpeakers else 0
            diarizer!!.setConfig(diarizer.config.copy(
                clustering = diarizer.config.clustering.copy(numClusters = if (n > 0) n else -1),
            ))
            val segs = diarizer.processWithCallback(samples, { done, total, _ ->
                if (total > 0) progress(0.5f * done / total, "поиск говорящих")
                if (cancelled()) 1 else 0 // ненулевой ответ прерывает диаризацию окна
            }).sortedBy { it.start }
            if (cancelled()) throw Cancelled()

            val turns = toTurns(segs)
            // Локальные номера говорящих окна → сквозные
            val global = turns.groupBy { it.speaker }.mapValues { (_, ts) -> book.assign(embed(samples, ts)) }

            val total = turns.sumOf { (it.end - it.start).toDouble() }.toFloat().coerceAtLeast(1f)
            var doneSec = 0f
            var prevEnd = 0
            for (turn in turns) {
                if (cancelled()) throw Cancelled()
                // Запас по краям реплики: диаризация режет впритык и съедает края слов
                val from = maxOf((turn.start * SAMPLE_RATE).toInt() - PAD, prevEnd).coerceIn(0, samples.size)
                val to = ((turn.end * SAMPLE_RATE).toInt() + PAD).coerceIn(from, samples.size)
                prevEnd = to
                val speaker = global.getValue(turn.speaker)
                val batcher = Batcher(maxChunkSec) { start, chunk ->
                    recognize(chunk)?.let {
                        out += Piece(t0 + start, t0 + start + chunk.size / SAMPLE_RATE.toFloat(), it, speaker)
                    }
                }
                val feeder = VadFeeder(vad, from, batcher)
                feeder.feed(samples.copyOfRange(from, to))
                feeder.finish()
                doneSec += turn.end - turn.start
                progress(0.5f + 0.5f * doneSec / total, "распознавание")
            }
            winStart += samples.size
            windows++
        }

        source { chunk, _ ->
            win.add(chunk)
            if (win.size >= windowSamples) processWindow(false)
            !cancelled()
        }
        if (cancelled()) throw Cancelled()
        if (win.size > 0 || windows == 0) processWindow(true)

        // Сквозная нумерация: при заданном числе говорящих сливаем лишних, затем — по порядку появления
        val merged = book.mergeTo(if (windows > 1) numSpeakers else 0)
        val order = LinkedHashMap<Int, Int>()
        return out.sortedBy { it.start }.map {
            val id = merged[it.speaker]
            it.copy(speaker = order.getOrPut(id) { order.size })
        }
    }

    /** Склеиваем соседние куски одного говорящего, убираем перекрытия. */
    private fun toTurns(segs: List<OfflineSpeakerDiarizationSegment>): List<Turn> {
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
        return turns
    }

    /** Голосовой отпечаток говорящего: до 30 с его самых длинных реплик. */
    private fun embed(samples: FloatArray, turns: List<Turn>): FloatArray? {
        val e = embedder ?: return null
        val parts = mutableListOf<FloatArray>()
        var size = 0
        for (t in turns.sortedByDescending { it.end - it.start }) {
            if (size >= 30 * SAMPLE_RATE) break
            val from = (t.start * SAMPLE_RATE).toInt().coerceIn(0, samples.size)
            val to = (t.end * SAMPLE_RATE).toInt().coerceIn(from, samples.size)
            parts += samples.copyOfRange(from, to)
            size += to - from
        }
        if (size < SAMPLE_RATE / 2) return null
        val stream = e.createStream()
        try {
            parts.forEach { stream.acceptWaveform(it, SAMPLE_RATE) }
            stream.inputFinished()
            return if (e.isReady(stream)) e.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    private class Turn(val start: Float, var end: Float, val speaker: Int)

    private fun recognize(samples: FloatArray): String? {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val text = TextCleanup.clean(recognizer.getResult(stream).text)
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
        /** Запас по краям фрагмента речи, отсчёты (0.25 с). */
        const val PAD = SAMPLE_RATE / 4
    }
}

/** Сквозные говорящие: центроиды голосовых эмбеддингов по всем окнам. */
private class SpeakerBook(private val threshold: Float) {
    private val sums = mutableListOf<FloatArray>()
    private val counts = mutableListOf<Int>()

    /** Номер похожего говорящего или новый; без эмбеддинга — ближайший по времени новый. */
    fun assign(e: FloatArray?): Int {
        if (e == null) { sums += FloatArray(0); counts += 0; return sums.size - 1 }
        val v = normalize(e)
        var best = -1
        var bestSim = threshold
        for (i in sums.indices) {
            if (counts[i] == 0) continue
            val sim = dot(v, normalize(sums[i]))
            if (sim >= bestSim) { best = i; bestSim = sim }
        }
        if (best < 0) { sums += v.copyOf(); counts += 1; return sums.size - 1 }
        for (k in v.indices) sums[best][k] += v[k]
        counts[best]++
        return best
    }

    /** Сливает ближайших, пока говорящих больше n (n = 0 — не трогать). Возвращает переназначение. */
    fun mergeTo(n: Int): IntArray {
        val parent = IntArray(sums.size) { it }
        fun root(i: Int): Int = if (parent[i] == i) i else root(parent[i])
        val alive = sums.indices.filter { counts[it] > 0 }.toMutableList()
        while (n > 0 && alive.size > n) {
            var bi = -1; var bj = -1; var bs = -2f
            for (a in alive.indices) for (b in a + 1 until alive.size) {
                val s = dot(normalize(sums[alive[a]]), normalize(sums[alive[b]]))
                if (s > bs) { bs = s; bi = alive[a]; bj = alive[b] }
            }
            for (k in sums[bi].indices) sums[bi][k] += sums[bj][k]
            parent[bj] = bi
            alive.remove(bj)
        }
        return IntArray(sums.size) { root(it) }
    }

    private fun normalize(x: FloatArray): FloatArray {
        var n = 0.0
        for (a in x) n += a * a
        val k = if (n > 0) (1 / Math.sqrt(n)).toFloat() else 0f
        return FloatArray(x.size) { x[it] * k }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}

/** Чистка артефактов CTC-моделей на стыках фрагментов. */
object TextCleanup {
    private val rules = listOf(
        // «это.Некуда», «Спасибо.я» — нет пробела после знака
        Regex("(?<=\\p{Ll})([.?!…])(?=\\p{L})") to "$1 ",
        Regex(",(?=\\p{L})") to ", ",
        // Пустые реплики «— . —» и повторы знаков
        Regex("—[\\s.,]*(?=—)") to "",
        Regex("(?<!\\.)\\.\\.(?!\\.)") to ".",
        Regex(",[\\s,]*,") to ",",
        Regex(",\\s*\\.") to ".",
        Regex("\\s+([.,?!;:])") to "$1",
        Regex("^[\\s.,]+") to "",
        Regex("\\s{2,}") to " ",
    )

    fun clean(s: String): String = rules.fold(s) { acc, (re, to) -> re.replace(acc, to) }.trim()
}

/**
 * Подаёт звук в VAD окнами и передаёт найденную речь в Batcher.
 * Края фрагментов расширяем на PAD из кольцевого буфера: VAD режет впритык,
 * и CTC-модель теряет первый звук слова («Нкуда» вместо «некуда»).
 */
private class VadFeeder(private val vad: Vad, private val offset: Int, private val batcher: Batcher) {
    private var pending = FloatArray(0)
    private val ring = Ring(60 * SAMPLE_RATE)
    private var prevEnd = 0

    init {
        vad.reset()
    }

    fun feed(x: FloatArray) {
        val buf = if (pending.isEmpty()) x else pending + x
        var i = 0
        while (i + Pipeline.VAD_WINDOW <= buf.size) {
            val w = buf.copyOfRange(i, i + Pipeline.VAD_WINDOW)
            vad.acceptWaveform(w)
            ring.write(w)
            i += Pipeline.VAD_WINDOW
            drain()
        }
        pending = buf.copyOfRange(i, buf.size)
    }

    fun finish() {
        if (pending.isNotEmpty()) { vad.acceptWaveform(pending); ring.write(pending) }
        pending = FloatArray(0)
        vad.flush()
        drain()
        batcher.flush()
    }

    private fun drain() {
        while (!vad.empty()) {
            val s = vad.front()
            vad.pop()
            val end = s.start + s.samples.size
            val from = maxOf(s.start - Pipeline.PAD, prevEnd, ring.oldest)
            val to = minOf(end + Pipeline.PAD, ring.total)
            if (from >= to) continue
            prevEnd = to
            batcher.add(offset + from, ring.read(from, to))
        }
    }
}

/** Последние cap отсчётов потока с абсолютной адресацией. */
private class Ring(private val cap: Int) {
    private val buf = FloatArray(cap)
    var total = 0
        private set
    val oldest get() = maxOf(0, total - cap)

    fun write(x: FloatArray) {
        for (v in x) { buf[total % cap] = v; total++ }
    }

    fun read(from: Int, to: Int) = FloatArray(to - from) { buf[(from + it) % cap] }
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
    var size = 0
        private set

    fun add(x: FloatArray) {
        if (size + x.size > data.size) data = data.copyOf(maxOf(data.size * 3 / 2, size + x.size))
        x.copyInto(data, size)
        size += x.size
    }

    fun toArray(): FloatArray = data.copyOf(size)

    fun clear() { size = 0 }
}
