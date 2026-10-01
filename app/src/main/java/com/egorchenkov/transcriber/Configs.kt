package com.egorchenkov.transcriber

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.VadModelConfig

// Параметры VAD и диаризации (без Android — общие с tools/jvm-check)

fun vadConfig(model: String, maxSpeechSec: Float) = VadModelConfig(
    sileroVadModelConfig = SileroVadModelConfig(
        model = model,
        threshold = 0.5f,
        minSilenceDuration = 0.5f,
        minSpeechDuration = 0.25f,
        windowSize = Pipeline.VAD_WINDOW,
        maxSpeechDuration = maxSpeechSec,
    ),
    sampleRate = SAMPLE_RATE,
    numThreads = 1,
)

// Число говорящих задаёт Pipeline (setConfig): при окнах оно известно только для файла целиком
fun diarizationConfig(segmentation: String, embedding: String, threads: Int, threshold: Float = 0.9f) =
    OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
            pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                model = segmentation,
                // 0.25 вместо 0.1: в ~2.5 раза быстрее (замер: 30 мин аудио — 142 с против 382 с)
                windowShiftRatio = 0.25f,
            ),
            numThreads = threads,
        ),
        embedding = SpeakerEmbeddingExtractorConfig(model = embedding, numThreads = threads),
        clustering = FastClusteringConfig(
            numClusters = -1,
            // 0.9 вместо 0.75: на совещаниях AMI DER 27→20 % и 24→21 %, лишних «говорящих» вдвое меньше (docs/benchmark.md)
            threshold = threshold,
        ),
        minDurationOn = 0.3f,
        minDurationOff = 0.5f,
    )

/** Сколько окон диаризации считать параллельно: ядра минус два под распознавание и декодер, 1..3. */
fun diarParallel() = 1 // 0.4.4: проверка, не память ли (в 0.4.3 три экземпляра падали на ~1,2 ГБ)
