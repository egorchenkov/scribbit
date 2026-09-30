package com.egorchenkov.transcriber

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.Vad
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

/**
 * Точка продолжения: готовы первые [windows] окон (звук до отсчёта [nextSample]), их текст и
 * центроиды говорящих. Если систему убьёт процесс, распознавание продолжится отсюда, а не с нуля.
 */
class Checkpoint(
    val nextSample: Int,
    val windows: Int,
    val pieces: List<Piece>,
    val sums: List<FloatArray>,
    val counts: List<Int>,
)

/** Источник звука: вызывает колбэк с порциями 16 кГц моно и позицией (с); false — прервать. */
typealias AudioSource = ((FloatArray, Float) -> Boolean) -> Unit

class Pipeline(
    private val recognizer: OfflineRecognizer,
    private val vad: Vad,
    /**
     * Экземпляры диаризации (каждый однопоточный). Несколько окон обрабатываются параллельно:
     * модели мелкие и плохо масштабируются по потокам внутри одного окна (замер: 1 поток ≈ 4 потока),
     * зато хорошо — по окнам. Пусто — без разделения говорящих.
     */
    private val diarizers: List<OfflineSpeakerDiarization>,
    private val embedder: SpeakerEmbeddingExtractor?,
    private val maxChunkSec: Float,
    /** Число говорящих: 0 — определить автоматически. */
    private val numSpeakers: Int = 0,
    /**
     * Длина окна диаризации: в памяти держим окна, а не весь файл (4 ч = ~900 МБ).
     * 300 с: на AMI DER 20.8 % против 20.0 % у 600 с, зато паузы прогресса вдвое короче (docs/benchmark.md).
     */
    private val windowSec: Float = 300f,
    /** Порог косинусной близости «тот же человек» при сшивке окон: 0.4 уже сливает разных людей, 0.5–0.6 — ок. */
    private val sameSpeaker: Float = 0.6f,
    /** Говорящий, у которого в окне меньше этого (с), — осколок: присоединяется к ближайшему. */
    private val minSpeakerSec: Double = 6.0,
) {
    val diarized get() = diarizers.isNotEmpty()

    /** Время по стадиям, мс (для замеров в tools/jvm-check); диаризация — сумма по потокам. */
    val timings: MutableMap<String, Long> = java.util.Collections.synchronizedMap(LinkedHashMap())

    private inline fun <T> timed(stage: String, block: () -> T): T {
        val t = System.nanoTime()
        try {
            return block()
        } finally {
            timings.merge(stage, (System.nanoTime() - t) / 1_000_000) { a, b -> a + b }
        }
    }

    fun run(
        source: AudioSource,
        duration: Float,
        onProgress: (Float, String) -> Unit,
        cancelled: () -> Boolean,
        resume: Checkpoint? = null,
        onCheckpoint: (Checkpoint) -> Unit = {},
    ): List<Piece> =
        if (diarizers.isEmpty()) plain(source, duration, onProgress, cancelled)
        else withSpeakers(source, duration, onProgress, cancelled, resume, onCheckpoint)

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
     * Звук идёт окнами по windowSec: диаризация окон — параллельно в пуле (до diarizers.size окон вперёд),
     * сшивка говорящих и распознавание — по порядку в вызывающем потоке, пока пул считает следующие окна.
     * В памяти одновременно не больше diarizers.size + 1 окон, а не весь файл.
     * После каждого готового окна — точка продолжения; с [resume] звук до неё пропускается.
     */
    private fun withSpeakers(
        source: AudioSource,
        duration: Float,
        onProgress: (Float, String) -> Unit,
        cancelled: () -> Boolean,
        resume: Checkpoint?,
        onCheckpoint: (Checkpoint) -> Unit,
    ): List<Piece> {
        val out = resume?.pieces?.toMutableList() ?: mutableListOf()
        val book = SpeakerBook(sameSpeaker).also { b -> resume?.let { b.restore(it.sums, it.counts) } }
        val windowSamples = (windowSec * SAMPLE_RATE).toInt()
        val win = GrowableFloats(windowSamples + SAMPLE_RATE)
        val free = LinkedBlockingQueue(diarizers)
        val pool = Executors.newFixedThreadPool(diarizers.size)
        val pending = ArrayDeque<Pair<Window, Future<Array<OfflineSpeakerDiarizationSegment>>>>()
        val expected = if (duration > 0) Math.ceil(duration / windowSec.toDouble()).toInt() else 0
        var submitted = resume?.windows ?: 0
        var winStart = resume?.nextSample ?: 0
        val skip = winStart
        var seen = 0

        fun submit() {
            val w = Window(submitted++, winStart, win.toArray())
            win.clear()
            winStart += w.samples.size
            pending += w to pool.submit(Callable {
                val d = free.take()
                try {
                    timed("диаризация") {
                        d.processWithCallback(w.samples, { done, total, _ ->
                            if (total > 0) w.diarDone = done.toFloat() / total
                            if (cancelled()) 1 else 0 // ненулевой ответ прерывает диаризацию окна
                        })
                    }
                } finally {
                    free.put(d)
                }
            })
        }

        fun finishOldest() {
            val (w, future) = pending.removeFirst()
            val t0 = w.start / SAMPLE_RATE.toFloat()
            val len = w.samples.size / SAMPLE_RATE.toFloat()
            // Длинный файл идёт частями: подпись «часть 3 из 24», чтобы было видно, что работа идёт
            val parts = maxOf(expected, submitted)
            val part = if (parts > 1) "часть ${w.index + 1} из $parts · " else ""
            // Доля файла, которую занимает окно: 0..0.5 — поиск говорящих, 0.5..1 — распознавание
            fun progress(f: Float, stage: String) {
                if (duration > 0) onProgress(((t0 + len * f) / duration).coerceIn(0f, 1f), part + stage)
            }
            var segs: Array<OfflineSpeakerDiarizationSegment>? = null
            timed("ожидание диаризации") {
                while (segs == null) {
                    try {
                        segs = future.get(500, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        if (cancelled()) throw Cancelled()
                        progress(0.5f * w.diarDone, "поиск говорящих")
                    }
                }
            }
            if (cancelled()) throw Cancelled()

            val turns = toTurns(segs!!.sortedBy { it.start })
            val global = timed("сшивка") { stitch(w.samples, turns, book) }

            val total = turns.sumOf { (it.end - it.start).toDouble() }.toFloat().coerceAtLeast(1f)
            var doneSec = 0f
            var prevEnd = 0
            for (turn in turns) {
                if (cancelled()) throw Cancelled()
                // Запас по краям реплики: диаризация режет впритык и съедает края слов
                val from = maxOf((turn.start * SAMPLE_RATE).toInt() - PAD, prevEnd).coerceIn(0, w.samples.size)
                val to = ((turn.end * SAMPLE_RATE).toInt() + PAD).coerceIn(from, w.samples.size)
                prevEnd = to
                val speaker = global.getValue(turn.speaker)
                val batcher = Batcher(maxChunkSec) { start, chunk ->
                    recognize(chunk)?.let {
                        out += Piece(t0 + start, t0 + start + chunk.size / SAMPLE_RATE.toFloat(), it, speaker)
                    }
                }
                val feeder = VadFeeder(vad, from, batcher)
                feeder.feed(w.samples.copyOfRange(from, to))
                feeder.finish()
                doneSec += turn.end - turn.start
                progress(0.5f + 0.5f * doneSec / total, "распознавание")
            }
            val (sums, counts) = book.snapshot()
            onCheckpoint(Checkpoint(w.start + w.samples.size, w.index + 1, out.toList(), sums, counts))
        }

        try {
            source { raw, _ ->
                // Продолжение: звук уже распознанных окон пропускаем
                val chunk = when {
                    seen >= skip -> raw
                    seen + raw.size <= skip -> FloatArray(0)
                    else -> raw.copyOfRange(skip - seen, raw.size)
                }
                seen += raw.size
                win.add(chunk)
                if (win.size >= windowSamples) {
                    submit()
                    // Пока пул считает следующие окна, распознаём самое старое
                    while (pending.size > diarizers.size) finishOldest()
                }
                !cancelled()
            }
            if (cancelled()) throw Cancelled()
            if (win.size > 0 || submitted == 0) submit()
            while (pending.isNotEmpty()) finishOldest()
        } finally {
            pool.shutdown()
            // Нативный вызов не прервать: ждём, пока окна увидят отмену в колбэке
            pool.awaitTermination(5, TimeUnit.MINUTES)
        }

        // Сквозная нумерация: при заданном числе говорящих сливаем лишних, затем — по порядку появления
        val merged = book.mergeTo(numSpeakers)
        val order = LinkedHashMap<Int, Int>()
        return out.sortedBy { it.start }.map {
            val id = merged[it.speaker]
            it.copy(speaker = order.getOrPut(id) { order.size })
        }
    }

    private class Window(val index: Int, val start: Int, val samples: FloatArray) {
        @Volatile var diarDone = 0f
    }

    /**
     * Локальные номера говорящих окна → сквозные. Кластеры на несколько секунд — почти всегда
     * осколки настоящих участников (кашель, смех, перекрытие): их присоединяем к ближайшему
     * крупному, а не заводим «Спикер 17».
     */
    private fun stitch(samples: FloatArray, turns: List<Turn>, book: SpeakerBook): Map<Int, Int> {
        val bySpeaker = turns.groupBy { it.speaker }
            .map { (id, ts) -> Triple(id, ts.sumOf { (it.end - it.start).toDouble() }, embed(samples, ts)) }
            .sortedByDescending { it.second }
        val result = HashMap<Int, Int>()
        for ((id, dur, e) in bySpeaker) {
            val small = dur < minSpeakerSec && result.isNotEmpty()
            result[id] = if (small) book.nearest(e) ?: result.getValue(bySpeaker.first().first) else book.assign(e)
        }
        return result
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

    private fun recognize(samples: FloatArray): String? = timed("распознавание") { recognizeNow(samples) }

    private fun recognizeNow(samples: FloatArray): String? {
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

    fun snapshot(): Pair<List<FloatArray>, List<Int>> = sums.map { it.copyOf() } to counts.toList()

    fun restore(s: List<FloatArray>, c: List<Int>) {
        sums.clear(); counts.clear()
        s.forEach { sums += it.copyOf() }
        counts += c
    }

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

    /** Ближайший из уже известных (без порога и без обновления центроида). */
    fun nearest(e: FloatArray?): Int? {
        if (e == null) return null
        val v = normalize(e)
        return sums.indices.filter { counts[it] > 0 }.maxByOrNull { dot(v, normalize(sums[it])) }
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
