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
)

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
                ?.takeIf { it.file.exists() || it.status == Status.DONE }
        }.sortedBy { it.id }
        loaded.maxOfOrNull { it.id }?.let { max -> if (max > seq.get()) seq.set(max) }
        _items.value = loaded
    }

    fun add(name: String, file: File) {
        val job = Job(seq.incrementAndGet(), name, file)
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
        changed?.let { save(it) }
    }

    fun remove(id: Long) {
        _items.value.firstOrNull { it.id == id }?.let { deleteIfTemp(it.file) }
        _items.update { list -> list.filterNot { it.id == id } }
        jsonFile(id)?.delete()
    }

    fun clear() {
        _items.value.filter { it.status != Status.RUNNING }.forEach { deleteIfTemp(it.file); jsonFile(it.id)?.delete() }
        _items.update { list -> list.filter { it.status == Status.RUNNING } }
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
        )
    }

    fun nextQueued(): Job? = _items.value.firstOrNull { it.status == Status.QUEUED }

    val isRunning get() = _items.value.any { it.status == Status.RUNNING }

    // Входящие копии удаляем, собственные записи (recordings/) храним
    private fun deleteIfTemp(f: File) {
        if (f.parentFile?.name == "inbox") f.delete()
    }
}
