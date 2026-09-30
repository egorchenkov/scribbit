package check

import com.egorchenkov.transcriber.Formatter
import com.egorchenkov.transcriber.Pipeline
import com.egorchenkov.transcriber.Resampler
import com.egorchenkov.transcriber.Transcript
import com.egorchenkov.transcriber.diarizationConfig
import com.egorchenkov.transcriber.vadConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Аргументы: <каталог моделей> <gigaam|gml|whisper[:lang]> <аудио> [diar[:N[:окно_с[:порог]]]]
 * Звук декодируется ffmpeg в 44.1 кГц — чтобы заодно проверить ресэмплер приложения.
 */
fun main(args: Array<String>) {
    val m = args[0]
    val (kind, lang) = args[1].split(":").let { it[0] to it.getOrElse(1) { "ru" } }
    val audio = args[2]
    val diar = args.getOrNull(3)?.startsWith("diar") == true
    val dargs = args.getOrNull(3)?.split(":").orEmpty()
    val speakers = dargs.getOrNull(1)?.toIntOrNull() ?: 0
    val window = dargs.getOrNull(2)?.toFloatOrNull() ?: 600f
    val same = dargs.getOrNull(3)?.toFloatOrNull() ?: 0.5f

    val model = when (kind) {
        "gigaam" -> OfflineModelConfig(nemo = OfflineNemoEncDecCtcModelConfig("$m/gigaam/model.int8.onnx"), tokens = "$m/gigaam/tokens.txt", numThreads = 4)
        "gml" -> OfflineModelConfig(nemo = OfflineNemoEncDecCtcModelConfig("$m/gml/appended.onnx"), tokens = "$m/gml/tokens.txt", numThreads = 4)
        else -> OfflineModelConfig(
            whisper = OfflineWhisperModelConfig("$m/wsmall/small-encoder.int8.onnx", "$m/wsmall/small-decoder.int8.onnx", language = lang),
            tokens = "$m/wsmall/small-tokens.txt", modelType = "whisper", numThreads = 4,
        )
    }
    val maxChunk = if (kind.startsWith("whisper")) 25f else 20f
    val t0 = System.currentTimeMillis()
    val recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = model))
    val vad = Vad(null, vadConfig("$m/silero_vad.onnx", maxChunk))
    val diarizer = if (diar) OfflineSpeakerDiarization(
        null,
        diarizationConfig("$m/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx", "$m/nemo_en_titanet_small.onnx", 4),
    ) else null
    val embedder = if (diar) SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig("$m/nemo_en_titanet_small.onnx", 4)) else null
    val pipeline = Pipeline(recognizer, vad, diarizer, embedder, maxChunk, speakers, window, same)

    val inRate = 44100
    val pcm = ProcessBuilder("ffmpeg", "-v", "quiet", "-i", audio, "-ac", "1", "-ar", "$inRate", "-f", "f32le", "-")
        .start().inputStream.readBytes()
    val fb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val all = FloatArray(fb.remaining()).also { fb.get(it) }
    val duration = all.size / inRate.toFloat()
    var lastPct = -1
    val pieces = pipeline.run({ cb ->
        val rs = Resampler(inRate, 16000)
        var i = 0
        while (i < all.size) {
            val n = minOf(4096, all.size - i)
            if (!cb(rs.process(all.copyOfRange(i, i + n)), (i + n) / inRate.toFloat())) break
            i += n
        }
    }, duration, { p, stage ->
        val pct = (p * 10).toInt()
        if (pct != lastPct) { lastPct = pct; System.err.println("  $stage ${pct * 10}%") }
    }, { System.getenv("CANCEL_AFTER")?.toLongOrNull()?.let { System.currentTimeMillis() - t0 > it * 1000 } ?: false })
    val sec = (System.currentTimeMillis() - t0) / 1000.0
    println(Formatter.document(listOf(Transcript(File(audio).name, duration, kind, pieces, pipeline.diarized)), true))
    System.err.println("время: %.1f с на %.0f с аудио".format(sec, duration))
}
