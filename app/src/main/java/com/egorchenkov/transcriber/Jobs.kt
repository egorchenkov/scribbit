package com.egorchenkov.transcriber

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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

    fun add(name: String, file: File) {
        _items.update { it + Job(seq.incrementAndGet(), name, file) }
    }

    fun update(id: Long, f: (Job) -> Job) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    fun remove(id: Long) {
        _items.value.firstOrNull { it.id == id }?.let { deleteIfTemp(it.file) }
        _items.update { list -> list.filterNot { it.id == id } }
    }

    fun clear() {
        _items.value.filter { it.status != Status.RUNNING }.forEach { deleteIfTemp(it.file) }
        _items.update { list -> list.filter { it.status == Status.RUNNING } }
    }

    /** Вернуть ошибочные в очередь (повтор). */
    fun retryFailed() {
        _items.update { list -> list.map { if (it.status == Status.ERROR) it.copy(status = Status.QUEUED, error = null) else it } }
    }

    fun nextQueued(): Job? = _items.value.firstOrNull { it.status == Status.QUEUED }

    val isRunning get() = _items.value.any { it.status == Status.RUNNING }

    // Входящие копии удаляем, собственные записи (recordings/) храним
    private fun deleteIfTemp(f: File) {
        if (f.parentFile?.name == "inbox") f.delete()
    }
}
