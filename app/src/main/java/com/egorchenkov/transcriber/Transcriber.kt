package com.egorchenkov.transcriber

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.Vad
import java.io.File

/** Android-обвязка: модели из настроек + декодер аудио → Pipeline. */
class Transcriber(
    private val ctx: Context,
    private val spec: ModelSpec,
    language: String,
    diarize: Boolean,
    numSpeakers: Int,
) : AutoCloseable {
    private val mm = ModelManager(ctx)
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private val recognizer = OfflineRecognizer(
        null,
        OfflineRecognizerConfig(modelConfig = mm.asrConfig(spec, language, threads)),
    )

    private val vad = Vad(ctx.assets, vadConfig("silero_vad.onnx", spec.maxChunkSec))

    private val diarizer: OfflineSpeakerDiarization? = if (!diarize) null else OfflineSpeakerDiarization(
        null,
        diarizationConfig(
            mm.file(Models.diarization, "segmentation.int8.onnx").absolutePath,
            mm.file(Models.diarization, "embedding.onnx").absolutePath,
            numSpeakers, threads,
        ),
    )

    private val pipeline = Pipeline(recognizer, vad, diarizer, spec.maxChunkSec)

    fun transcribe(
        name: String,
        file: File,
        onProgress: (Float, String) -> Unit,
        cancelled: () -> Boolean,
    ): Transcript {
        val uri = Uri.fromFile(file)
        val duration = AudioDecoder.durationSec(ctx, uri)
        val pieces = pipeline.run({ cb -> AudioDecoder.decode(ctx, uri, cb) }, duration, onProgress, cancelled)
        return Transcript(name, duration, spec.title, pieces, pipeline.diarized)
    }

    override fun close() {
        recognizer.release()
        vad.release()
        diarizer?.release()
    }
}
