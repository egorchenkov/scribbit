# Исследование: офлайн-ASR модели для Android (ru / en / uz)

Дата проверки: 29.09.2026. Размеры архивов и файлов сняты с GitHub API и потоковым
листингом самих архивов (`tar tj`), а не из документации. Цифры WER — из первоисточников
(ссылки в конце). Скорость — оценка, на устройстве не мерилась.

## 1. Движок: sherpa-onnx

### Версия и артефакты
- Последний релиз: **v1.13.8** (10.09.2026), https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8
- Вариант А (рекомендуется): AAR, в нём и `.so`, и скомпилированный Kotlin API (`classes.jar`, пакет `com.k2fsa.sherpa.onnx`, minSdk 21):
  - https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar — 50,1 МБ
  - внутри: `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/` → `libonnxruntime.so` (22,2 МБ на arm64), `libsherpa-onnx-jni.so` (4,8 МБ), `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`
  - вариант со статической onnxruntime: `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` — 38,7 МБ
- Вариант Б: голые jniLibs + копирование .kt-файлов в проект:
  - https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2 — 46,1 МБ
  - внутри: `jniLibs/arm64-v8a/` (`libonnxruntime.so`, `libsherpa-onnx-jni.so`, `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`) + те же каталоги для `armeabi-v7a`, `x86`, `x86_64`
  - для APK под личный телефон достаточно `arm64-v8a` (+`x86_64` для эмулятора)
  - не путать с `*-termux-*`, `*-rknn*`: это другие сборки
- Kotlin API: `sherpa-onnx/kotlin-api/*.kt`, тег v1.13.8:
  https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/sherpa-onnx/kotlin-api
  - для OfflineRecognizer: `OfflineRecognizer.kt` (все `Offline*ModelConfig`, `OfflineModelConfig`, `OfflineRecognizerConfig`, `OfflineRecognizer`), `OfflineStream.kt`, `FeatureConfig.kt`, `HomophoneReplacerConfig.kt`, `QnnConfig.kt` (на них ссылаются конфиги)
  - для VAD: `Vad.kt` (`SileroVadModelConfig`, `TenVadModelConfig`, `VadModelConfig`, `Vad`, `SpeechSegment`)
  - опционально: `WaveReader.kt` (чтение wav), `OfflinePunctuation.kt` (пунктуация для моделей без неё)
  - пакет обязан остаться `com.k2fsa.sherpa.onnx`: JNI-символы привязаны к нему
- Готовый пример «VAD + офлайн-распознавание» для сверки: каталог `android/SherpaOnnxVadAsr` в репозитории sherpa-onnx.

### Что важно знать про API (v1.13.8)
- `OfflineRecognizer(assetManager = null, config)` — модели из файлов (скачанных в `filesDir`), с `assetManager` — из assets.
- `OfflineRecognizer.setConfig(config)` есть — язык Whisper можно менять без перезагрузки модели.
- `OfflineModelConfig`: общие поля `tokens`, `numThreads` (по умолчанию 1 — ставить 4), `provider = "cpu"`, `modelType`.
- `SileroVadModelConfig`: по умолчанию `maxSpeechDuration = 5.0` с — для транскрибации файлов поднять до ~20 с (Whisper максимум ~29,5 с, GigaAM обучен на ≤25 с); `windowSize = 512`, `minSilenceDuration = 0.25`.

## 2. Кандидаты (все URL — тег `asr-models`)

Базовый адрес: `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/`

### 2.1 Русский — GigaAM (Сбер, лицензия MIT)

Все варианты — int8, 220M параметров, энкодер ≈225 МБ после распаковки.

- **GigaAM v3 CTC** — `sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16.tar.bz2`, 163,3 МБ
  - файлы: `model.int8.onnx` (224,7 МБ), `tokens.txt`
  - Kotlin: `nemo = OfflineNemoEncDecCtcModelConfig(model = …/model.int8.onnx)`, `modelType` не нужен
  - WER ru, среднее по 10 наборам: 9,1 %; Common Voice 19: 1,3 %; Golos Crowd: 2,8 %; звонки (OpenSTT): 18,6 %
  - текст без пунктуации, строчными
- **GigaAM v3 RNNT** — `sherpa-onnx-nemo-transducer-giga-am-v3-russian-2025-12-16.tar.bz2`, 167,4 МБ
  - файлы: `encoder.int8.onnx` (224,6 МБ), `decoder.onnx` (3,3 МБ), `joiner.onnx` (1,4 МБ), `tokens.txt`
  - Kotlin: `transducer = OfflineTransducerModelConfig(encoder, decoder, joiner)`, `modelType = "nemo_transducer"`
  - WER ru, среднее: **8,3 %** (лучший), CV19: 0,9 %
- **GigaAM v3 e2e CTC (с пунктуацией и нормализацией)** — `sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16.tar.bz2`, 163,3 МБ
  - файлы: `model.int8.onnx` (224,9 МБ), `tokens.txt` (BPE с пунктуацией)
  - Kotlin: как у v3 CTC (`OfflineNemoEncDecCtcModelConfig`)
  - WER ru (после снятия пунктуации): 12,0 %; в сравнении «бок о бок» с Whisper выигрывает 70:30
- **GigaAM v3 e2e RNNT (с пунктуацией)** — `sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16.tar.bz2`, 170,2 МБ
  - файлы: `encoder.int8.onnx` (224,6 МБ), `decoder.onnx` (4,6 МБ), `joiner.onnx` (2,7 МБ), `tokens.txt`
  - Kotlin: `OfflineTransducerModelConfig`, `modelType = "nemo_transducer"`
  - WER ru: 11,2 %; F1 запятых 84,5; **лучший вариант для «готового текста»**
- Предыдущее поколение (не нужно, v3 не хуже на публичных тестах и заметно лучше на звонках и голосовых):
  - v2 CTC `sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19.tar.bz2` — 166,9 МБ, WER 11,1 %
  - v2 RNNT `sherpa-onnx-nemo-transducer-giga-am-v2-russian-2025-04-19.tar.bz2` — 172,4 МБ, WER 10,6 %
- Лёгкая альтернатива для ru: `sherpa-onnx-zipformer-ru-int8-2025-04-20.tar.bz2`, 60,2 МБ
  - файлы: `encoder.int8.onnx` (70,9 МБ), `decoder.onnx` (2,1 МБ), `joiner.int8.onnx`, `tokens.txt`, `bpe.model`
  - Kotlin: `OfflineTransducerModelConfig`, `modelType = "transducer"`
  - в разы быстрее GigaAM, но точность ниже (сравнимых WER нет, проверять на своих аудио)
- Для сравнения — Whisper на русском (FLEURS): tiny 31,1 %, base 20,5 %, small 11,4 %, medium 7,2 %, large-v2 5,6 %. Среднее по наборам GigaAM у Whisper large-v3 — 21,0 % против 8,3 % у GigaAM v3 RNNT. На русском GigaAM сильнее любого Whisper, который реально запустить на телефоне.

### 2.2 Whisper multilingual (OpenAI, MIT)

Архив содержит и fp32, и int8; на телефон кладём только `*.int8.onnx` + tokens.

- **tiny** — `sherpa-onnx-whisper-tiny.tar.bz2`, 116,2 МБ
  - нужные файлы: `tiny-encoder.int8.onnx` (12,9 МБ), `tiny-decoder.int8.onnx` (89,9 МБ), `tiny-tokens.txt` (≈103 МБ итого)
- **base** — `sherpa-onnx-whisper-base.tar.bz2`, 207,6 МБ
  - `base-encoder.int8.onnx` (29,1 МБ), `base-decoder.int8.onnx` (130,7 МБ), `base-tokens.txt` (≈161 МБ)
- **small** — `sherpa-onnx-whisper-small.tar.bz2`, 639,4 МБ
  - `small-encoder.int8.onnx` (112,4 МБ), `small-decoder.int8.onnx` (262,2 МБ), `small-tokens.txt` (≈375 МБ)
- **turbo (large-v3-turbo)** — `sherpa-onnx-whisper-turbo.tar.bz2`, 563,8 МБ
  - `turbo-encoder.int8.onnx` (674,7 МБ), `turbo-decoder.int8.onnx` (361,1 МБ), `turbo-tokens.txt` (≈1,04 ГБ); fp32-энкодер лежит отдельно (`turbo-encoder.onnx` + `turbo-encoder.weights` 2,6 ГБ)
  - на среднем телефоне непрактично: ≈1 ГБ ОЗУ под веса, медленно
- Есть и `distil-large-v3`, `distil-large-v3.5` (≈530 МБ), но они только английские.
- Kotlin: `whisper = OfflineWhisperModelConfig(encoder, decoder, language, task = "transcribe", tailPaddings)`, `tokens = …/xxx-tokens.txt`, `modelType = "whisper"`
- Язык: поле `language` — `"ru"`, `"en"`, `"uz"`; пустая строка `""` — автоопределение. **По умолчанию в Kotlin стоит `"en"`** — обязательно задавать явно. Менять язык можно через `recognizer.setConfig(...)`.
- Лимит длины: реализация sherpa-onnx режет вход на ~29,5 с (3000 кадров минус 50) и молча отбрасывает остаток (пишет в лог). Поэтому длинные файлы — только через Silero VAD (сегменты ≤20–25 с). Без паддинга до 30 с: к сегменту добавляется `tailPaddings` (по умолчанию 1000 кадров = 10 с).
- WER (FLEURS, статья Whisper, таблица 13):
  - en: tiny 12,4 %, base 8,9 %, small 6,1 %, medium 4,4 %
  - ru: tiny 31,1 %, base 20,5 %, small 11,4 %
  - uz: tiny 105,2 %, base 114,0 %, small 107,7 %, medium 109,6 %, large-v2 90,2 %; large-v3 по замерам Сбера — 105,4 % (FLEURS) и 109,9 % (CV)
- **Честная оценка Whisper на узбекском: непригоден.** WER около 100 % — это, по сути, мусор или ответ не на том языке. Нужна дообученная модель (см. 2.4).

### 2.3 Английский

- Whisper base или small даёт 6–9 % на FLEURS. Лучшие по соотношению «качество/размер» модели:
- **Moonshine tiny en** — `sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2`, 29,9 МБ
  - файлы: `encoder_model.ort` (13,3 МБ), `decoder_model_merged.ort` (30,4 МБ), `tokens.txt`
  - Kotlin: `moonshine = OfflineMoonshineModelConfig(encoder = …/encoder_model.ort, mergedDecoder = …/decoder_model_merged.ort)`
  - WER LibriSpeech clean/other ≈4,5 / 11,7 % (статья Moonshine, исходная версия): лучше Whisper tiny при меньшем размере. Время работы пропорционально длине аудио, 30-секундного окна нет.
- **Moonshine base en** — `sherpa-onnx-moonshine-base-en-quantized-2026-02-27.tar.bz2`, 111,3 МБ
  - файлы: `encoder_model.ort` (31,3 МБ), `decoder_model_merged.ort` (109,4 МБ), `tokens.txt`; конфиг тот же
  - WER ≈3,2 / 8,2 %, примерно уровень Whisper small
- **Parakeet TDT-CTC 110M en** (NVIDIA, CC-BY-4.0) — `sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2`, 104,3 МБ
  - файлы: `model.int8.onnx` (131,7 МБ), `tokens.txt`
  - Kotlin: `nemo = OfflineNemoEncDecCtcModelConfig(model)`
  - вариант transducer: `sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2` (108,0 МБ; `encoder.int8.onnx` 131,1 МБ, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt`; `modelType = "nemo_transducer"`)
  - WER LibriSpeech clean/other 2,4 / 5,2 %. Лучшая английская модель в весе ~130 МБ и быстрая (CTC); выдаёт пунктуацию и регистр.
- Parakeet TDT 0.6B v3 (25 европейских языков, включая ru и en; uz нет) — `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2`, 487,2 МБ (`encoder.int8.onnx` 652 МБ). Тяжёлая, на ru хуже GigaAM — не рекомендую.
- Вывод: для en Parakeet 110M заметно лучше Whisper base при сопоставимом размере. Moonshine tiny годится как сверхлёгкий вариант.

### 2.4 Узбекский

- В релизах sherpa-onnx **нет ни одной специализированной узбекской модели** (ни zipformer, ни Whisper-fine-tune). Узбекский там понимают только многоязычные:
  - **Omnilingual ASR 300M CTC** (Meta, 1600 языков) — `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2`, 292,3 МБ
    - файлы: `model.int8.onnx` (365,8 МБ), `tokens.txt`
    - Kotlin: `omnilingual = OfflineOmnilingualAsrCtcModelConfig(model)`
    - качество uz: даже у старшей версии 1B (LLM) по замерам Сбера WER 15,4 % (FLEURS), 32,8 % (CV), 30,2 % (in-the-wild); 300M CTC заведомо хуже. Работает сразу, из коробки, но слабо.
  - Dolphin base (`sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02.tar.bz2`, 80,7 МБ) — ориентирован на восточные языки, на uz не рассматриваю.
- **Лучший кандидат — GigaAM Multilingual CTC 220M** (Сбер, 06.2026, MIT, языки ru/en/kk/ky/uz):
  - WER uz: CV 11,3 %, FLEURS 10,0 %, in-the-wild 13,8 % (Large 600M: 9,2 / 7,3 / 12,7 %); Whisper large-v3 на тех же наборах — 105–121 %
  - заодно хорош на ru (CV 7,1 %, FLEURS 4,4 %), en посредственно (FLEURS 12,2 %)
  - в релизах sherpa-onnx его **пока нет**. Есть ONNX для onnx-asr: https://huggingface.co/istupakov/gigaam-multilingual-ctc-onnx (`multilingual_ctc.int8.onnx` 224,8 МБ, `multilingual_vocab.txt` — 70 символов: латиница a–z и `'` для узбекской латиницы, кириллица + казахские/киргизские буквы, `<blk>`=70)
  - архитектура совпадает с GigaAM v3 CTC (64 mel, n_fft 320, hop 160, conv1d-субсэмплинг ×4, 16 слоёв × 768), а v3 CTC sherpa-onnx уже запускает. Путь: взять скрипт `export-onnx-ctc-v3.py` из https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16, подставить `multilingual_ctc`, записать метаданные (`vocab_size=71`, `subsampling_factor=4`, `model_type=EncDecCTCModel`, `is_giga_am=1`, `normalize_type=""`) и `tokens.txt`, квантизовать в int8. Дальше — `OfflineNemoEncDecCtcModelConfig`. **Требует проверки** на эталонном wav.
  - в ответе модели нет пунктуации и регистра
- **Whisper, дообученный на узбекский** — NavAI (Apache-2.0, 753 ч человеческой разметки):
  - https://huggingface.co/navai-uz/whisper-tiny-uzbek — macro-WER 18,1 % (FLEURS 26,3 %)
  - https://huggingface.co/navai-uz/whisper-base-uzbek — 15,1 % (FLEURS 22,4 %)
  - https://huggingface.co/navai-uz/whisper-small-uzbek — 11,6 % (FLEURS 17,0 %, CV 9,6 %)
  - формат HF transformers (safetensors). Для sherpa-onnx нужна конвертация: HF → чекпойнт openai-whisper → `scripts/whisper/export-onnx.py` из sherpa-onnx → int8. Потом тот же `OfflineWhisperModelConfig`, `language = "uz"`. Это работоспособный запасной вариант, но GigaAM Multilingual точнее и без 30-секундного окна.
- Vosk: официальной узбекской модели нет, на sherpa-onnx конвертированы только ru/bn-модели Vosk.
- Прочее на HF (например, `sarahai/uzbek-stt-3` — wav2vec2 1,26 ГБ, `zafarrr/uzbek-stt-fastconformer-v1.5` — .nemo 459 МБ) — тяжелее или без сопоставимых замеров. Не рекомендую.

### 2.5 Silero VAD
- https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx — 644 КБ (рекомендуемый, на него ссылается `Vad.kt`)
- int8: `…/asr-models/silero_vad.int8.onnx` — 213 КБ; старые `silero_vad_v5.onnx` (2,3 МБ), `silero_vad_v4.onnx`
- альтернатива: `…/asr-models/ten-vad.onnx` (332 КБ) → `TenVadModelConfig`
- Kotlin: `VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model, threshold = 0.5, minSilenceDuration, minSpeechDuration, windowSize = 512, maxSpeechDuration), sampleRate = 16000)`
- VAD кладём в assets APK: он маленький и нужен всем моделям.

## 3. Сравнение с whisper.cpp
- Последний релиз: v1.9.4 (11.09.2026). ggml-модели: https://huggingface.co/ggerganov/whisper.cpp
  - `ggml-tiny-q5_1.bin` 32 МБ, `ggml-base-q5_1.bin` 60 МБ, `ggml-small-q5_1.bin` 190 МБ, `ggml-large-v3-turbo-q5_0.bin` 574 МБ
- Плюсы whisper.cpp:
  - квантизованные Whisper-модели компактнее (q5), чем int8-ONNX у sherpa-onnx (small: 190 МБ против 375 МБ);
  - встроенная длинная транскрибация окнами по 30 с;
  - сильная оптимизация под ARM NEON.
- Минусы whisper.cpp:
  - только Whisper, а на ru он уступает GigaAM, на uz без дообучения непригоден;
  - нет готового Kotlin API, JNI придётся писать самим (есть лишь пример `examples/whisper.android`);
  - по умолчанию обрабатывает полное 30-секундное окно, короткие сегменты дороже.
- sherpa-onnx даёт один движок и один API для GigaAM, Whisper, Parakeet, Moonshine, Omnilingual и VAD, готовый AAR с Kotlin-классами.
- Вывод: движок — **sherpa-onnx**; whisper.cpp не нужен.

## 4. Рекомендация для экрана настроек

Скорость — ориентировочный RTF (время обработки / длительность аудио) на среднем телефоне
(Snapdragon 7-й серии / Dimensity 7000, 4 потока CPU). Это оценка, её нужно замерить на реальном телефоне.

1. **«Русский — точный, с пунктуацией» (по умолчанию)** — GigaAM v3 e2e RNNT, `sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16`
   - скачивание 170 МБ, на диске ≈232 МБ
   - готовый читаемый текст с пунктуацией и цифрами; голосовые из Telegram, звонки
   - RTF ≈0,1–0,2 (минута аудио ≈6–12 с)
   - при желании пункт «Русский — макс. точность, без пунктуации» на v3 RNNT (`…transducer-giga-am-v3-russian…`, WER 8,3 %)
2. **«Узбекский (+ русский, казахский)»** — GigaAM Multilingual CTC 220M, после собственной конвертации в формат sherpa-onnx (раздел 2.4)
   - ≈225 МБ, RTF ≈0,1–0,2
   - пока конвертация не проверена, запасной вариант: navai-uz/whisper-small-uzbek (≈375 МБ int8, RTF ≈0,5–1) или Omnilingual 300M (292 МБ, работает сразу, но заметно хуже)
3. **«Английский»** — Parakeet TDT-CTC 110M int8, `sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8`
   - 104 МБ архив, 132 МБ на диске; WER LibriSpeech 2,4 / 5,2 %, с пунктуацией и регистром
   - RTF ≈0,05–0,1
4. **«Любой язык — Whisper base (автоопределение)»** — `sherpa-onnx-whisper-base`, только int8-файлы (≈161 МБ)
   - для прочих языков и смешанных записей; `language = ""` или выбор ru/en
   - RTF ≈0,3–0,5; не для узбекского
   - Whisper small (≈375 МБ, RTF ≈0,8–1,5) — только как опция «медленно, но точнее»
5. **«Быстрый английский / слабый телефон»** (опционально) — Moonshine tiny en, 30 МБ, RTF ≈0,03–0,05

Общее:
- модели не зашивать в APK, а скачивать по выбору в настройках (распаковка tar.bz2 на устройстве — через commons-compress) или раздавать заранее подготовленные zip только с нужными int8-файлами;
- Silero VAD (`silero_vad.onnx`, 644 КБ) — в assets, `maxSpeechDuration` ≈20 с;
- `numThreads = 4`.

## Источники
- Релизы sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8, https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
- Kotlin API: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/sherpa-onnx/kotlin-api
- Реализация Whisper в sherpa-onnx (лимит 30 с): `sherpa-onnx/csrc/offline-recognizer-whisper-impl.h`, тег v1.13.8
- GigaAM WER: https://github.com/salute-developers/GigaAM/blob/main/evaluation.md
- GigaAM Multilingual: https://huggingface.co/ai-sage/GigaAM-Multilingual
- Whisper, FLEURS WER (таблица 13): https://cdn.openai.com/papers/whisper.pdf
- NavAI Uzbek Whisper: https://huggingface.co/navai-uz/whisper-small-uzbek
- Parakeet TDT-CTC 110M: https://huggingface.co/nvidia/parakeet-tdt_ctc-110m
- Moonshine: https://arxiv.org/abs/2410.15608
- whisper.cpp: https://github.com/ggml-org/whisper.cpp, https://huggingface.co/ggerganov/whisper.cpp
