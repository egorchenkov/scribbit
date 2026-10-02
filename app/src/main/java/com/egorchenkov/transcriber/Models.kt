package com.egorchenkov.transcriber

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

enum class Engine { NEMO_CTC, WHISPER, DIARIZATION }

data class ModelFile(val name: String, val url: String)

data class ModelSpec(
    val id: String,
    /** Короткое имя без перевода — пишется в результат (Transcript.model). */
    val shortName: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val sizeMb: Int,
    val engine: Engine,
    val files: List<ModelFile>,
    /** Дописать ONNX-метаданные после скачивания (модель без метаданных sherpa-onnx). */
    val onnxMetadata: Map<String, String> = emptyMap(),
    /** Максимальная длина куска для распознавания, секунды. */
    val maxChunkSec: Float = 20f,
) {
    fun title(ctx: Context): String = ctx.getString(titleRes)
    fun description(ctx: Context): String = ctx.getString(descriptionRes)
}

private const val HF = "https://huggingface.co"
private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

object Models {
    val asr = listOf(
        ModelSpec(
            id = "gigaam_v3_ru",
            shortName = "GigaAM v3",
            titleRes = R.string.model_gigaam_title,
            descriptionRes = R.string.model_gigaam_desc,
            sizeMb = 215,
            engine = Engine.NEMO_CTC,
            files = listOf(
                ModelFile("model.int8.onnx", "$HF/csukuangfj/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16/resolve/main/model.int8.onnx"),
                ModelFile("tokens.txt", "$HF/csukuangfj/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16/resolve/main/tokens.txt"),
            ),
        ),
        ModelSpec(
            id = "gigaam_multi",
            shortName = "GigaAM Multilingual",
            titleRes = R.string.model_gml_title,
            descriptionRes = R.string.model_gml_desc,
            sizeMb = 215,
            engine = Engine.NEMO_CTC,
            files = listOf(
                ModelFile("model.int8.onnx", "$HF/istupakov/gigaam-multilingual-ctc-onnx/resolve/main/multilingual_ctc.int8.onnx"),
                ModelFile("tokens.txt", "$HF/istupakov/gigaam-multilingual-ctc-onnx/resolve/main/multilingual_vocab.txt"),
            ),
            onnxMetadata = linkedMapOf(
                "vocab_size" to "71",
                "normalize_type" to "",
                "subsampling_factor" to "4",
                "model_type" to "EncDecCTCModel",
                "version" to "1",
                "is_giga_am" to "1",
            ),
        ),
        ModelSpec(
            id = "whisper_small",
            shortName = "Whisper Small",
            titleRes = R.string.model_whisper_title,
            descriptionRes = R.string.model_whisper_desc,
            sizeMb = 360,
            engine = Engine.WHISPER,
            files = listOf(
                ModelFile("encoder.int8.onnx", "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx"),
                ModelFile("decoder.int8.onnx", "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx"),
                ModelFile("tokens.txt", "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt"),
            ),
            maxChunkSec = 25f,
        ),
    )

    val diarization = ModelSpec(
        id = "diarization",
        shortName = "pyannote + TitaNet",
        titleRes = R.string.model_diar_title,
        descriptionRes = R.string.model_diar_desc,
        sizeMb = 40,
        engine = Engine.DIARIZATION,
        files = listOf(
            ModelFile("segmentation.int8.onnx", "$HF/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.int8.onnx"),
            ModelFile("embedding.onnx", "$GH/speaker-recongition-models/nemo_en_titanet_small.onnx"),
        ),
    )

    val all = asr + diarization

    fun byId(id: String?) = all.firstOrNull { it.id == id }
}

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val progress: Float) : ModelState
    data object Installed : ModelState
    data class Failed(val message: String) : ModelState
}

private val installLock = Any()

class ModelManager(private val ctx: Context) {
    private val dm = ctx.getSystemService(DownloadManager::class.java)
    private val prefs = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun dir(spec: ModelSpec): File = File(ctx.getExternalFilesDir("models"), spec.id)
    fun file(spec: ModelSpec, name: String) = File(dir(spec), name)
    private fun marker(spec: ModelSpec) = File(dir(spec), ".complete")

    fun isInstalled(spec: ModelSpec) = marker(spec).exists()

    fun download(spec: ModelSpec) {
        cancel(spec)
        dir(spec).mkdirs()
        val ids = spec.files.map { f ->
            val req = DownloadManager.Request(Uri.parse(f.url))
                .setTitle("${spec.title(ctx)}: ${f.name}")
                .setDestinationInExternalFilesDir(ctx, "models", "${spec.id}/${f.name}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            dm.enqueue(req)
        }
        prefs.edit().putString(spec.id, ids.joinToString(",")).apply()
    }

    fun cancel(spec: ModelSpec) {
        pendingIds(spec)?.let { dm.remove(*it) }
        prefs.edit().remove(spec.id).apply()
        dir(spec).deleteRecursively()
    }

    fun delete(spec: ModelSpec) = cancel(spec)

    private fun pendingIds(spec: ModelSpec): LongArray? =
        prefs.getString(spec.id, null)?.split(",")?.mapNotNull { it.toLongOrNull() }?.toLongArray()

    /** Опрос состояния; по завершении всех файлов — постобработка и маркер. */
    fun state(spec: ModelSpec): ModelState {
        if (isInstalled(spec)) return ModelState.Installed
        val ids = pendingIds(spec) ?: return ModelState.NotInstalled
        var done = 0L
        var total = 0L
        var allOk = true
        var failed: String? = null
        dm.query(DownloadManager.Query().setFilterById(*ids)).use { c ->
            if (c.count < ids.size) failed = ctx.getString(R.string.dl_interrupted)
            while (c.moveToNext()) {
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val so = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                done += so
                if (tot > 0) total += tot
                if (status == DownloadManager.STATUS_FAILED) {
                    failed = ctx.getString(R.string.dl_error_code, c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                }
                if (status != DownloadManager.STATUS_SUCCESSFUL) allOk = false
            }
        }
        failed?.let {
            cancel(spec)
            return ModelState.Failed(it)
        }
        if (allOk) {
            return try {
                finalizeInstall(spec)
                prefs.edit().remove(spec.id).apply()
                ModelState.Installed
            } catch (e: Exception) {
                cancel(spec)
                ModelState.Failed(e.message ?: ctx.getString(R.string.install_error))
            }
        }
        val expected = spec.sizeMb * 1024L * 1024L
        return ModelState.Downloading((done.toFloat() / maxOf(total, expected)).coerceIn(0f, 1f))
    }

    private fun finalizeInstall(spec: ModelSpec) = synchronized(installLock) {
        // Состояние опрашивают и главный экран, и настройки — метаданные дописать ровно один раз
        if (isInstalled(spec)) return
        spec.files.forEach { f ->
            val file = file(spec, f.name)
            require(file.exists() && file.length() > 0) { ctx.getString(R.string.file_missing, f.name) }
        }
        if (spec.onnxMetadata.isNotEmpty()) {
            OnnxMeta.append(file(spec, spec.files.first().name), spec.onnxMetadata)
        }
        marker(spec).writeText("ok")
    }

    fun asrConfig(spec: ModelSpec, language: String, threads: Int): OfflineModelConfig {
        val d = dir(spec).absolutePath
        return when (spec.engine) {
            Engine.NEMO_CTC -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = "$d/model.int8.onnx"),
                tokens = "$d/tokens.txt",
                numThreads = threads,
            )
            Engine.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$d/encoder.int8.onnx",
                    decoder = "$d/decoder.int8.onnx",
                    language = language,
                    tailPaddings = 1000,
                ),
                tokens = "$d/tokens.txt",
                modelType = "whisper",
                numThreads = threads,
            )
            Engine.DIARIZATION -> error("not an ASR model")
        }
    }
}

/**
 * Дописывает metadata_props (поле 14 ModelProto) в конец ONNX-файла. Protobuf склеивает
 * повторяющиеся поля, поэтому файл не нужно перепаковывать — достаточно добавить байты.
 */
object OnnxMeta {
    fun append(file: File, meta: Map<String, String>) {
        val out = java.io.ByteArrayOutputStream()
        meta.forEach { (k, v) ->
            val entry = field(0x0A, k.toByteArray()) + field(0x12, v.toByteArray())
            out.write(field(0x72, entry))
        }
        file.appendBytes(out.toByteArray())
    }

    private fun field(tag: Int, data: ByteArray): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(tag)
        var n = data.size
        while (true) {
            val x = n and 0x7f
            n = n ushr 7
            if (n != 0) b.write(x or 0x80) else { b.write(x); break }
        }
        b.write(data)
        return b.toByteArray()
    }
}
