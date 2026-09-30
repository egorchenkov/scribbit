# Транскрибатор — офлайн-расшифровка аудио на Android

Бесплатное приложение с открытым кодом: превращает запись совещания, голосовое из Telegram
или диктофонный файл в текст **прямо на телефоне**. Интернет нужен один раз — скачать модель;
дальше звук никуда не уходит.

*English summary below.*

## Возможности
- Русский (GigaAM v3 — с пунктуацией), узбекский/казахский (GigaAM Multilingual),
  английский и ~100 других языков (Whisper Small).
- **Разделение говорящих** («Спикер 1: …») — для совещаний и созвонов; число участников
  можно указать или оставить «авто». Длинные записи (часы) обрабатываются частями,
  память телефона не растёт с длиной файла.
- Запись с микрофона в фоне (экран можно выключить), выбор нескольких файлов,
  «Поделиться» из Telegram, WhatsApp, диктофона и любых других приложений (аудио и видео).
- Результат: скопировать, отправить текстом (в мессенджер или LLM), сохранить .txt;
  таймкоды — по желанию.

## Установка
1. Скачайте `Transcriber-X.Y.Z.apk` со страницы [Releases](../../releases) и откройте на телефоне
   (разрешите установку из этого источника). Нужен Android 8+ на 64-битном ARM (arm64-v8a) —
   это практически все телефоны последних лет.
2. Шестерёнка → скачайте модель распознавания и, для совещаний, «Разделение говорящих».
3. «● Запись», «Выбрать файлы» или «Поделиться» → «Транскрибировать».

Обновления удобно получать через [Obtainium](https://github.com/ImranR98/Obtainium):
добавьте ссылку на этот репозиторий — он сам будет ставить новые версии из Releases.

## Модели
Скачиваются в приложении с HuggingFace; в APK не входят.

- GigaAM v3 punct CTC — русский, Sber, MIT.
- GigaAM Multilingual CTC — узбекский/русский/казахский, MIT.
- Whisper Small (int8) — OpenAI, MIT.
- Разделение говорящих: pyannote segmentation-3.0 (MIT) + NVIDIA TitaNet-small (CC BY 4.0).
- Детектор речи Silero VAD (MIT) — встроен в APK.

Почему именно эти — `docs/research-models.md`; сравнение с другими приложениями —
`docs/competitors.md`; качество и скорость разделения говорящих — `docs/benchmark.md`.

## Сборка
`./gradlew assembleRelease` (JDK 17, Android SDK 35). Подробности, проверка ядра без
телефона и стенд качества — `docs/BUILD.md`. Движок — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
(Apache-2.0), готовые `jniLibs/arm64-v8a`.

## Приватность
Приложение не отправляет звук и текст в сеть и не содержит аналитики. Сеть используется только
для скачивания моделей по нажатию кнопки.

## Лицензия
MIT — см. `LICENSE`. Модели распространяются под своими лицензиями (список выше).

---

## English summary
**Transcriber** is a free, open-source Android app for fully offline speech-to-text:
Russian (GigaAM v3 with punctuation), Uzbek/Kazakh (GigaAM Multilingual), English and other
languages (Whisper Small), plus speaker diarization for meetings (pyannote-3.0 + TitaNet).
Share audio/video from Telegram or any app, pick several files, or record in the background.
Models are downloaded once inside the app; audio never leaves the phone.
Install the APK from [Releases](../../releases) (arm64, Android 8+) or track it with Obtainium.
Built on [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). License: MIT.
