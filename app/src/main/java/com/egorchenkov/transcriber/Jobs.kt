package com.egorchenkov.transcriber

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class Status { QUEUED, RUNNING, DONE, ERROR }

data class Job(
    val id: Long,
    val name: String,
    val file: File,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val stage: String = "",
    val result: Transcript? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Метка, заданная пользователем; в списке показывается вместо имени файла. */
    val title: String? = null,
    /** Откуда взят файл (content://…), для повторной транскрибации, пока он там доступен. */
    val source: String? = null,
) {
    val label get() = title?.takeIf { it.isNotBlank() } ?: name
}

/** Очередь файлов текущей сессии (общая для экрана и сервиса). */
object Jobs {
    private val seq = AtomicLong(System.currentTimeMillis())
    private val _items = MutableStateFlow<List<Job>>(emptyList())
    val items: StateFlow<List<Job>> = _items

    @Volatile var cancelRequested = false

    // Очередь и готовые тексты переживают убийство процесса: каждый файл-задача сохраняется в filesDir/jobs
    private var dir: File? = null

    /** Вызывается из App.onCreate: поднимает сохранённые задачи (прерванные возвращаются в очередь). */
    @Synchronized fun init(ctx: android.content.Context) {
        if (dir != null) return
        val d = File(ctx.filesDir, "jobs").apply { mkdirs() }
        dir = d
        val loaded = d.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
                ?.takeIf { it.file.exists() || it.status == Status.DONE || it.source != null }
        }.sortedBy { it.id }
        loaded.maxOfOrNull { it.id }?.let { max -> if (max > seq.get()) seq.set(max) }
        _items.value = loaded
    }

    fun add(name: String, file: File, source: String? = null, title: String? = null) {
        val job = Job(seq.incrementAndGet(), name, file, source = source, title = title)
        _items.update { it + job }
        save(job)
    }

    fun update(id: Long, f: (Job) -> Job) {
        var changed: Job? = null
        _items.update { list ->
            list.map {
                if (it.id != id) it else f(it).also { n ->
                    changed = if (n.status != it.status || n.result !== it.result) n else null
                }
            }
        }
        changed?.let {
            // Аудио не храним: после готового текста рабочая копия не нужна
            if (it.status == Status.DONE && keepsNoAudio(it)) it.file.delete()
            save(it)
        }
    }

    fun rename(id: Long, title: String) {
        val t = title.trim().ifEmpty { null }
        _items.update { list -> list.map { if (it.id == id) it.copy(title = t) else it } }
        _items.value.firstOrNull { it.id == id }?.let { save(it) }
    }

    fun removeAll(ids: Set<Long>) = ids.forEach { remove(it) }

    /** Готовые тексты (история), новые сверху. */
    fun clearDone() = removeAll(_items.value.filter { it.status == Status.DONE }.map { it.id }.toSet())

    private fun keepsNoAudio(j: Job) = j.file.parentFile?.name == "inbox" || j.source != null

    fun remove(id: Long) {
        _items.value.firstOrNull { it.id == id }?.let { deleteIfTemp(it.file) }
        _items.update { list -> list.filterNot { it.id == id } }
        jsonFile(id)?.delete()
    }

    fun clear() {
        // «Очистить» убирает только очередь и ошибки; история (DONE) остаётся
        val gone = _items.value.filter { it.status == Status.QUEUED || it.status == Status.ERROR }
        gone.forEach { deleteIfTemp(it.file); jsonFile(it.id)?.delete() }
        val ids = gone.map { it.id }.toSet()
        _items.update { list -> list.filterNot { it.id in ids } }
    }

    /** Вернуть ошибочные в очередь (повтор). */
    fun retryFailed() {
        _items.update { list -> list.map { if (it.status == Status.ERROR) it.copy(status = Status.QUEUED, error = null) else it } }
        _items.value.filter { it.status == Status.QUEUED }.forEach { save(it) }
    }

    private fun jsonFile(id: Long) = dir?.let { File(it, "$id.json") }

    private fun save(job: Job) {
        val target = jsonFile(job.id) ?: return
        runCatching {
            val tmp = File(target.path + ".tmp")
            tmp.writeText(toJson(job).toString())
            tmp.renameTo(target)
        }
    }

    private fun toJson(j: Job) = JSONObject().apply {
        put("id", j.id); put("name", j.name); put("file", j.file.path)
        put("createdAt", j.createdAt); put("title", j.title); put("source", j.source)
        // RUNNING при перезапуске процесса значит «прервано» — вернём в очередь
        put("status", j.status.name); put("error", j.error)
        j.result?.let { t ->
            put("result", JSONObject().apply {
                put("name", t.name); put("duration", t.durationSec.toDouble()); put("model", t.model)
                put("diarized", t.diarized)
                put("pieces", JSONArray().also { a ->
                    t.pieces.forEach { p ->
                        a.put(JSONArray().put(p.start.toDouble()).put(p.end.toDouble()).put(p.text).put(p.speaker))
                    }
                })
            })
        }
    }

    private fun fromJson(o: JSONObject): Job {
        val st = Status.valueOf(o.getString("status"))
        val result = o.optJSONObject("result")?.let { r ->
            val a = r.getJSONArray("pieces")
            Transcript(
                r.getString("name"), r.getDouble("duration").toFloat(), r.getString("model"),
                List(a.length()) { i ->
                    val p = a.getJSONArray(i)
                    Piece(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getString(2), p.getInt(3))
                },
                r.getBoolean("diarized"),
            )
        }
        return Job(
            o.getLong("id"), o.getString("name"), File(o.getString("file")),
            status = if (st == Status.RUNNING) Status.QUEUED else st,
            progress = if (st == Status.DONE) 1f else 0f,
            result = result,
            error = if (o.isNull("error")) null else o.getString("error"),
            createdAt = o.optLong("createdAt", o.getLong("id")),
            title = if (o.isNull("title")) null else o.optString("title").ifEmpty { null },
            source = if (o.isNull("source")) null else o.optString("source").ifEmpty { null },
        )
    }

    fun nextQueued(): Job? = _items.value.firstOrNull { it.status == Status.QUEUED }

    val isRunning get() = _items.value.any { it.status == Status.RUNNING }

    // Входящие копии удаляем, собственные записи (recordings/) храним
    private fun deleteIfTemp(f: File) {
        if (f.parentFile?.name == "inbox") f.delete()
    }
}
