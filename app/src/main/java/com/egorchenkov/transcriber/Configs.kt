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

fun diarizationConfig(segmentation: String, embedding: String, numSpeakers: Int, threads: Int) =
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
            numClusters = if (numSpeakers > 0) numSpeakers else -1,
            threshold = 0.75f,
        ),
        minDurationOn = 0.3f,
        minDurationOff = 0.5f,
    )
