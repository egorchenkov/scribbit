# Конкуренты: офлайн-транскрибация с диаризацией

Состояние на конец сентября 2026 г. Это дополнение к `docs/crossplatform.md`. Там уже есть
базовые описания Whisper Notes, MacWhisper, noScribe, Vibe, Buzz, Whisper-WebUI, Subtitle Edit,
SuperWhisper и VoiceInk. Здесь то, чего там нет: Android целиком, цены и лицензии, фоновая запись,
русский и узбекский, минусы, облачные ориентиры и сравнение «локально vs облако».
Модели и WER собственного приложения описаны в `docs/research-models.md`.

Требования, с которыми сравнивали:
- русский (главный), английский, узбекский;
- открыть файл или получить его через «Поделиться» (диктофон, Telegram);
- запись с микрофона в фоне;
- диаризация;
- результат текстом, который можно отправить в LLM или Telegram;
- бесплатно.

Общая оговорка про узбекский. Ни одно из найденных офлайн-приложений не даёт пригодный узбекский:
у Whisper на узбекском WER около 100 % (см. `research-models.md`, раздел 2.4). Дальше это
отдельно не повторяю: «узбекский — нет» подразумевается у всех, где не сказано иное.

## 1. Android

### Системные и встроенные в прошивку

- **Google Recorder (Pixel Recorder)**
  - Цена: бесплатно, но **только телефоны Pixel**.
  - Офлайн: живая расшифровка на устройстве, но только для 8 языков (Pixel 3–5a) или 15 языков (Pixel 6+). **Русского среди них нет.**
  - Русский: только через «Transcribe again», а это **облако Google** (около 40 языков).
  - Диаризация: есть («Speaker labels», Pixel 6+). По отзывам работает хорошо, но в офлайне только на поддерживаемых языках.
  - Фоновая запись: да, это системный диктофон.
  - Open source: нет.
  - Минусы: чужие файлы (Telegram) импортировать нельзя, работает только с собственными записями. Для русского офлайн непригоден.
- **Samsung Voice Recorder + Transcript assist (Galaxy AI)**
  - Цена: бесплатно на флагманах Galaxy с One UI 6.1+ (S24/S25/S26, Z Fold/Flip 5+). Условия Galaxy AI после 2025 г. стоит перепроверить.
  - Офлайн: да, если заранее скачать языковой пакет и включить «Process data only on device». Саммари при этом отключается, потому что оно облачное.
  - Русский: **есть** в списке языков транскрибации. Узбекского нет.
  - Диаризация: есть, разметка «Speaker 1/2…» с таймкодами. В Samsung Notes можно расшифровать и аудио, вложенное в заметку. Качество на русском в независимых тестах не нашёл.
  - Фоновая запись: да, системный диктофон.
  - Open source: нет.
  - Минусы: только Samsung, и не все модели. Сценарий «поделиться файлом из Telegram → текст» неудобен. Модель закрытая.
  - **Для владельца свежего Galaxy это самый сильный готовый офлайн-вариант на Android.**

### Открытые приложения на Whisper

- **FUTO Voice Input / FUTO Keyboard**
  - Цена: бесплатно. Добровольная оплата $6.99 (FUTO Pay) или $11.99 (Google Play) ничего не открывает.
  - Офлайн: полностью. Модели: Whisper tiny/base/small, дообученные FUTO (ACFT). В 2026 г. добавились Moonshine, Parakeet TDT, Nemotron.
  - Русский: есть (мультиязычная модель). Качество на уровне Whisper small, то есть заметно ниже GigaAM.
  - Диаризации нет. Фоновой записи нет.
  - Open source: **не по OSI**. «FUTO Source First License»: исходники открыты, коммерческое использование запрещено.
  - Минусы: **только диктовка.** Это клавиатура или сервис голосового ввода, файлы не транскрибирует. По умолчанию лимит 30 с, длинный ввод включается, но качество падает.
- **Transcribro** (soupslurpr)
  - Цена: бесплатно (Accrescent, GitHub). Лицензия ISC.
  - Офлайн: whisper.cpp + Silero VAD.
  - Русский: **нет, только английский** (мультиязычность в планах, issue #18).
  - Диаризации и файлов нет, это клавиатура/сервис распознавания.
  - Вывод: не конкурент.
- **Whisper / WhisperIME** (woheller69, F-Droid `org.woheller69.whisper`)
  - Цена: бесплатно, MIT.
  - Офлайн: модели около 435 МБ скачиваются с Hugging Face один раз. Английская модель и мультиязычная (русский есть). Умеет перевод на английский.
  - Диаризации нет, файлов нет, запись до 30 с.
  - Минусы: только диктовка. Автор предупреждает, что из-за верификации разработчиков Google с 2026–2027 гг. приложение может перестать работать на сертифицированных устройствах. Это касается любых APK вне Play, в том числе нашего (см. раздел 7).
- **Scrib** (F-Droid `org.scrib.transcriber`, v0.18)
  - Цена: бесплатно, GPL-3.0+.
  - Офлайн: whisper.cpp, любые ggml-модели (по ссылке или из .bin).
  - Русский: как у выбранной модели Whisper.
  - Файлы: **да**, открывает аудиофайлы. Есть фоновый сервис для других приложений: **Forkgram** (форк Telegram) расшифровывает голосовые через Scrib прямо на телефоне. Реализует открытый контракт `org.opentranscribe.api`.
  - Диаризации нет.
  - **Ближайший открытый аналог по сценарию «файл/голосовое → текст».** Проигрывает по качеству русского (Whisper, а не GigaAM) и по отсутствию спикеров. Идея с контрактом `org.opentranscribe.api` стоит внимания: наше приложение могло бы его реализовать.
- **Прочие клоны на whisper.cpp** (WhisperVault, android-offline-transcribe, «Whisper Android Speech To Text» в Play и т. п.)
  - Выбор файла → текст, Whisper, без диаризации. Сопровождение нестабильное.
  - «Whisper Notes для Android» **не существует**: разработчик прямо пишет, что APK с таким названием поддельные.

### Приложения с диаризацией на устройстве

- **Демо-APK sherpa-onnx** (k2-fsa)
  - Бесплатно, Apache-2.0, офлайн.
  - Отдельный APK для диаризации: pyannote segmentation + эмбеддинги 3D-Speaker/NeMo. На входе wav-файл, на выходе **только сегменты «спикер N: от–до», без текста.**
  - Отдельные APK для распознавания (VAD + ASR) есть под разные модели.
  - Готового «файл → текст со спикерами» нет. Это витрина движка, а не продукт, но для нас это эталон, с которым удобно сравнивать своё.
- **Meeting Transcriber (sherpa)** (github.com/qutschwalze/meeting-transcriber-sherpa)
  - Бесплатно, MIT, подписанные APK в релизах.
  - Офлайн: sherpa-onnx, потоковый Zipformer. Диаризация ReVerb + ERes2Net на 2–4 спикеров, с базой голосов.
  - Импорт голосовых из WhatsApp/Telegram до 30 мин. Фоновая запись с wake lock.
  - Русский: **нет**, модели немецкая и английская.
  - По архитектуре это почти наш проект, только для немецкого. Проект маленький (3 звезды).
- **phone-transcript-recorder** (github.com/fivelidz)
  - whisper.cpp + sherpa-onnx, звонки и встречи, диаризация офлайн.
  - Самодельный проект, не продукт.
- **BlackBox** (iOS + Android)
  - Бесплатно (по сайту). Закрытый код.
  - Круглосуточная фоновая запись, транскрибация и метки спикеров на устройстве.
  - Какой движок и есть ли русский, не раскрыто. Нужно проверять на своём файле.
- **TranscriAI** (Android + iOS)
  - Whisper на устройстве + диаризация. Freemium.
  - Детали по русскому и ценам на Android подтвердить не удалось.
- **Viska** (iOS $6.99, Android $4.99 разово)
  - Whisper + локальная LLM (саммари, чат по тексту). Есть диаризация с переименованием спикеров. Импорт mp3/wav/m4a.
  - **Русского нет**: 11 языков, среди них en, es, fr, de, zh, hi и др.

### Облачные на Android (только как ориентир)

- **Speechnotes**
  - Бесплатно с рекламой. Отключение рекламы — разовый платёж.
  - Диктовка «офлайн» идёт через системный распознаватель Google с языковым пакетом. **Файлы — облако**, оплата поминутно.
  - Диаризации в приложении нет.
  - Не конкурент по офлайну.
- **Otter.ai**
  - Free 300 мин/мес (3 импорта файлов за всё время). Pro $8.33/мес (при оплате за год), Business $19.99.
  - Языки: en, es, fr, de, ja, zh. **Русского нет**, русское аудио превращается в мусор.
  - Не вариант.
- **Notta**
  - Free 120–200 мин/мес, до 3 мин на запись. Pro $8.17/мес (при оплате за год) или $13.99 помесячно, 1800 мин.
  - Русский есть, спикеры есть. Облако.
- **TurboScribe**
  - Free: 3 файла в день по 30 мин. Unlimited $10/мес (при оплате за год) или $20 помесячно, файлы до 10 ч.
  - Основан на Whisper. Русский и спикеры есть, спикеры даже в бесплатном тарифе.
  - **Лучший облачный ориентир по соотношению цены и удобства.**
- **Яндекс и Сбер (потребительские приложения)**
  - **Алиса AI**: расшифровывает короткие фрагменты, для часовых встреч не предназначена.
  - **ИИ-диктофон Яндекса** (устройство, анонс 10.2025, продажи в 1-м полугодии 2026): запись + расшифровка + тезисы через «Алиса Про». Как устроена обработка и сколько стоит, в источниках нет. Скорее всего, облако.
  - **SaluteSpeech App** (Windows/macOS, не Android): облако Сбера, 100 мин/мес бесплатно для физлиц, дальше от 600 ₽/мес. В API асинхронного распознавания есть разделение дикторов (`speaker_separation_options`).
  - Узбекского нет ни у Яндекса, ни у Сбера в потребительских продуктах.
  - Доступность и оплата из Узбекистана — отдельный вопрос.
- **Telegram Premium**
  - Встроенная расшифровка голосовых, русский поддерживается. Облако, без спикеров.
  - Для коротких голосовых это закрывает часть сценария «голосовое из Telegram → текст» вообще без отдельного приложения.

## 2. macOS

Дополняю `crossplatform.md` только новым.

- **MacWhisper** (Good Snooze)
  - Цена: Pro €59–64 разово (Gumroad). В App Store (под названием «Whisper Transcription») подписка $6.99/мес, $29.99/год или $89.99–99.99 навсегда.
  - Офлайн: полностью. Модели Whisper tiny…large-v3/turbo и Parakeet v2/v3 (с версии 13), до 300× реального времени на M-серии.
  - Диаризация: в Pro. В 2026 г. это pyannote 4 + Parakeet v3, по обзорам уже зрелая (на 2 спикерах около 91 % верной атрибуции).
  - Запись с микрофона и системного звука есть (Pro). Закрытый код.
  - Минусы: только Mac. Русский только через Whisper, потому что Parakeet v3 на русском слабее. GigaAM нет.
- **Whisper Notes для Mac**
  - Около $14 разово на сайте или $7.99 в App Store (один покупатель, разные лицензии).
  - Whisper large-v3-turbo на Neural Engine, диаризация есть, закрытый код.
- **Aiko**
  - Около $24 разово (цена с июля 2026, раньше было бесплатно или около $22).
  - Whisper large-v3 на Mac. Диаризации нет. Закрытый код.
- **Buzz**
  - Бесплатно, MIT. Только Apple Silicon: Intel поддерживается до версии 1.4.5.
  - Whisper (whisper.cpp, faster-whisper, HF). Есть диаризация, запись с микрофона в реальном времени, папка-наблюдатель.
  - Минусы: тяжёлый установщик, на длинных файлах бывают ошибки диаризации.
- **Vibe**
  - Бесплатно, MIT. Whisper + Parakeet TDT v3 + Nemotron.
  - Диаризация на pyannote-rs (ONNX). Запись микрофона и системного звука. Есть анализ через локальную Ollama.
  - Минусы: интерфейс простой, диаризация средняя на перекрывающейся речи.
- **noScribe**
  - Бесплатно, GPL-3.0. faster-whisper + pyannote, есть встроенный редактор.
  - Только файлы, записи нет. Медленный, зато самый аккуратный для интервью.
- **VoiceInk**
  - $29/49/69 разово (1/2/3 Mac) или бесплатно при сборке из исходников (GPL-3.0).
  - В первую очередь диктовка. Файлы транскрибирует, диаризации нет.
- **SuperWhisper**
  - Pro $8.49/мес или $84.99/год. Lifetime в 2026 г. подорожал с $249.99 до $849.
  - Офлайн только на локальных моделях. Speaker separation и файлы — в Pro. Одна лицензия на Mac, Windows и iPhone.
  - Минусы: дорого, сильная сторона — диктовка, а не встречи.
- **SaluteSpeech App**: облако Сбера, см. раздел 1.

## 3. Windows

- **noScribe** — бесплатный, GPL. faster-whisper + pyannote, хорошая диаризация. Нужен приличный CPU или GPU, работает медленно.
- **Vibe** — бесплатный, MIT, самая простая установка. Ускорение на GPU через Vulkan (Nvidia/AMD/Intel). Есть диаризация, запись микрофона и системного звука.
- **Buzz** — бесплатный, MIT, CUDA/Vulkan. Диаризация и живая запись есть, на Windows встречаются баги.
- **Subtitle Edit** — бесплатный, только Windows. Встроенный Whisper (в том числе Purfview faster-whisper-XXL). Диаризация через дополнительный параметр `--diarize pyannote_v3.0` в Advanced, то есть нужна ручная настройка. Инструмент для субтитров, а не для заметок.
- **Whisper-WebUI** (jhj0517) — бесплатный, веб-интерфейс на Python. Диаризация pyannote требует токен HF, желательно GPU.
- **WhisperX GUI** — бесплатные сторонние оболочки над WhisperX. WhisperX Transcriber: портативный, около 19 МБ, мастер ставит Python. whisperx-batch-gui: очередь файлов. Диаризация pyannote, нужен токен HF.
  - Качество по сути то же, что у noScribe (Whisper + pyannote + выравнивание слов), но установка «гиковская».
- **SuperWhisper** — есть под Windows, условия те же, что на macOS.
- **OfflineTranscribe** — €49.99/год, локальный Whisper, без диаризации. Не интересен.

## 4. iOS (кратко)

Основное уже в `crossplatform.md`: Whisper Notes ($7.99, диаризация на устройстве), Aiko (без
спикеров), MacWhisper/Whisper Transcription. Дополнения:
- **LoroNote** — бесплатно до 2 мин, потом $39.99 навсегда. Whisper + диаризация, только iPhone.
- **BlackBox, TranscriAI, Viska** — есть и на Android, см. раздел 1. У Viska нет русского.
- **Apple Voice Memos** (iOS 18+) — расшифровка на устройстве, около 10 языков, без спикеров. Русский проверить на своей iOS.

## 5. Локально vs облако: насколько локальное хуже на русском

Цифры из разных наборов данных напрямую не сравниваются. Главное, что видно по независимым замерам:
разрыв «локальное против облачного» на русском небольшой, **а локальная GigaAM часто лучше облаков**.

- **Официальная оценка GigaAM** (10 наборов, средний WER; облака не участвовали):
  - GigaAM v3 RNNT — 8,3 %;
  - GigaAM v3 CTC — 9,1 %;
  - T-One + LM — 16,3 %;
  - Whisper large-v3 — 21,0 %.
  - На звонках: GigaAM 17,4 %, Whisper 27,4 %. На «естественной речи»: 6,9 % против 13,4 %.
- **stt-ru-benchmark (berdachuk)**: одинаковые байты и одинаковая нормализация для всех движков.
  - Студийная начитка: Deepgram Nova-2 — 3,97 %, Whisper large-v3-turbo — 4,44 %, GigaAM v3 — 4,50 %, Nova-3 — 6,55 %, Parakeet — 5,56 %.
  - Аудиокниги: **GigaAM v3 — 6,57 %**, Nova-3 — 8,88 %, Whisper turbo — 9,89 %, Nova-2 — 10,22 %.
  - Спонтанная речь: **GigaAM v3 — 6,95 %**, Nova-3 — 7,12 %, Whisper turbo — 7,73 %, Nova-2 — 9,27 %.
  - Вывод: на «живой» речи GigaAM локально обходит облачный Deepgram, а Whisper turbo идёт с ним вровень.
- **stt-benchmarks (Smolevich)**: 10 голосовых заметок с iPhone на русском (шум, шёпот, быстрая речь, смесь RU/EN).
  - ElevenLabs Scribe v1 experimental — 11,5 %;
  - Groq Whisper turbo — 17,9 %;
  - **GigaAM v2 CTC (локально) — 18,2 %**;
  - Deepgram Nova-2 — 18,6 %;
  - mlx-whisper large-v3-turbo (локально) — 19,4 %;
  - Deepgram Nova-3 — 21,5–24 %.
  - Вывод: на трудных заметках лидирует облачный ElevenLabs с отрывом около 7 п. п. Остальные облака на уровне локальных моделей. GigaAM v3 в этом тесте не участвовала.
- **Заявления вендоров (FLEURS ru)**: ElevenLabs Scribe — 3,1 % (Common Voice 5,5 %), Whisper large-v3 — 5,7 % (по данным ElevenLabs), Gemini Flash 2 — 3,9 %. Для сравнения, у GigaAM v3 на Common Voice 19 — 0,9–1,3 %, но это домен, на котором её обучали, так что сравнение нечестное.
- **OpenAI**: для gpt-4o-transcribe и вышедшего 28.07.2026 gpt-transcribe **отдельных русских цифр нет**, опубликованы только сводные по языкам. В сводном рейтинге AA-WER у gpt-transcribe 3,3 %, у whisper-1 4,1 %, у ElevenLabs Scribe v2 2,2 % (первое место). Цены: gpt-transcribe около $0,27/ч, Scribe $0,40/ч, Whisper turbo на Groq около $0,04/ч.
- **Yandex SpeechKit и SaluteSpeech**
  - Независимых сравнений на общих наборах вместе с GigaAM/Whisper не нашёл.
  - Единственный найденный замер (Habr, 2026, трудный собственный набор, абсолютные значения высокие): Sber API — 0,448, Yandex SpeechKit — 0,550. Там же дешёвые отечественные API (Nexara 0,391, Palatine 0,414) оказались лучше обоих.
  - Цены: около 600–650 ₽ за 1000 мин. Диаризация у обоих есть в асинхронном API.
  - Важно: публичная GigaAM — это та же семья моделей, что стоит в основе распознавания Сбера. Разумно ожидать, что локальная GigaAM v3 близка к SaluteSpeech по качеству текста, но это вывод, а не замер.
- **MacWhisper с large-v3 против облака.**
  - MacWhisper — это Whisper large-v3/turbo локально. Качество текста на русском совпадает с Whisper large-v3 где угодно (облачный whisper-1/Groq Whisper — это та же модель): около 4–6 % на чистой речи, около 8–10 % на живой и заметно хуже на звонках и шуме (до 20–27 %).
  - Облачные ElevenLabs Scribe или gpt-transcribe на трудном аудио лучше примерно на треть. На чистой речи разница 1–2 п. п.
  - Диаризация в облаке (ElevenLabs, Deepgram, SpeechKit) обычно стабильнее локальной pyannote на 3+ спикерах и перекрытиях.
- **Итог раздела.**
  - Для русского качество **текста** у локальной GigaAM v3 на уровне облаков или лучше. Whisper large-v3/turbo примерно на уровне Deepgram, но хуже ElevenLabs на трудном аудио.
  - Реальный разрыв у локальных решений — в **диаризации** и в **пунктуации и форматировании**, а не в словах.
  - На телефоне доступны только небольшие Whisper (small/turbo-q), и у них разрыв с облаком больше. GigaAM (около 170 МБ) этот разрыв закрывает.

## 6. Выводы

### Главный конкурент на каждой платформе
- **Android.** Для владельца свежего Galaxy это Samsung Voice Recorder + Transcript assist (офлайн, русский, спикеры). Для остальных Android готового офлайн-решения «русский + спикеры + файлы из Telegram» **нет**. Ближайшее: Scrib (файлы и голосовые из Telegram, но без спикеров, Whisper) и облачный TurboScribe.
- **macOS.** MacWhisper Pro (платно). Бесплатно — Vibe или noScribe.
- **Windows.** noScribe по качеству диаризации, Vibe по простоте.
- **iOS.** Whisper Notes.

### Чем своё бесплатное приложение может быть релевантнее
- **Русский на GigaAM v3.** Ни одно готовое офлайн-приложение не использует GigaAM, все сидят на Whisper. На живой речи и звонках это минус 30–60 % ошибок относительно Whisper large-v3 (среднее по 10 наборам: 8,3 % против 21 %). На телефоне разница ещё больше: там Whisper маленький.
- **Узбекский через GigaAM Multilingual** (WER около 10–14 % против более 100 % у Whisper). Офлайн-узбекского нет ни в одном конкуренте, ни в облаках Яндекса и Сбера. **Это самое сильное уникальное преимущество.**
- **Диаризация офлайн на любом Android**, а не только на Galaxy или Pixel.
- **Сценарий «Поделиться» из Telegram/диктофона → текст → сразу в Telegram или LLM.** Готовые приложения заканчиваются на «скопировать или экспорт txt/srt». Сюда же: формат текста под LLM (реплики «Спикер 1: …», таймкоды по желанию) и отправка в личного бота. Можно реализовать контракт `org.opentranscribe.api` (как у Scrib), чтобы Forkgram и другие клиенты расшифровывали голосовые через наше приложение.
- **Бесплатно, без аккаунта и подписки**, фоновая запись и один конвейер на все платформы (Flutter + sherpa-onnx, см. `crossplatform.md`).

### Честно: где своё делать не нужно
- **Mac.** MacWhisper Pro или бесплатные Vibe/noScribe уже хорошо закрывают русский + спикеров. Своё имеет смысл только ради GigaAM или узбекского.
- **Windows.** noScribe/Vibe бесплатны и с диаризацией. Та же логика: своё только ради GigaAM или узбекского.
- **iPhone.** Whisper Notes за $8 дешевле и надёжнее своей iOS-сборки (см. `crossplatform.md`).
- **Короткие голосовые в Telegram** уже расшифровывает Telegram Premium (облако, без спикеров), а часть — личный бот на сервере.
- **Если важен максимум точности, а облако допустимо**, TurboScribe ($10/мес) или ElevenLabs Scribe ($0,40/ч) на трудном аудио будут лучше любого офлайн-решения на телефоне, особенно по спикерам.
- **Английский** на Android неплохо закрывают FUTO и Scrib для диктовки и файлов, но без спикеров.

## 7. Риски, общие для APK вне Play

- Google вводит обязательную верификацию разработчиков для установки APK на сертифицированные устройства (2026–2027). Автор WhisperIME прямо предупреждает, что приложение может перестать ставиться.
- Для личного APK это нужно отслеживать: может понадобиться регистрация аккаунта разработчика или режим установки для опытных пользователей.

## Источники
- Google Recorder, языки: https://support.google.com/pixelphone/answer/16267698?hl=en
- Samsung Transcript assist: https://www.samsung.com/us/support/answer/ANS10000942/ ; только на устройстве: https://www.androidauthority.com/samsung-galaxy-ai-on-device-only-3488445/ ; спикеры в Samsung Notes: https://soyacincau.com/2024/07/29/samsung-galaxy-z-fold-6-notes-galaxy-ai-audio-transcription-sketch-to-image-pdf-translation-handwriting-help-auto-format-summary/
- FUTO Voice Input: https://voiceinput.futo.tech/ ; https://docs.keyboard.futo.tech/settings/voiceinput ; https://github.com/futo-org/voice-input
- Transcribro: https://github.com/soupslurpr/Transcribro
- WhisperIME: https://github.com/woheller69/whisperIME
- Scrib: https://f-droid.org/en/packages/org.scrib.transcriber/
- Whisper Notes, Android нет: https://whispernotes.app/android
- sherpa-onnx, APK диаризации: https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/android.html
- Meeting Transcriber (sherpa): https://github.com/qutschwalze/meeting-transcriber-sherpa ; phone-transcript-recorder: https://github.com/fivelidz/phone-transcript-recorder
- BlackBox: https://blackboxrecorder.in/blog/offline-speaker-diarization
- Viska: https://viskalocal.com/ ; TranscriAI: https://play.google.com/store/apps/details?id=com.owlmyst.ai.transcriai
- Обзор офлайн-приложений: https://voicescriber.com/best-offline-transcription-apps
- Speechnotes: https://speechnotes.co/
- Otter: https://otter.ai/pricing ; Notta: https://www.notta.ai/en/pricing ; TurboScribe: https://turboscribe.ai/pricing
- Русский в облачных сервисах: https://vexascribe.com/compare/best-transcription-software-for-russian-audio
- SaluteSpeech App: https://habr.com/ru/news/774388/ ; диаризация в API: https://developers.sber.ru/docs/ru/salutespeech/recognition/recognition-async-http
- ИИ-диктофон Яндекса: https://www.iphones.ru/iNotes/yandeks-predstavil-ii-diktofon-vstroennaya-alisa-i-rasshifrovka-lyubyh-audiozapisej
- Telegram, расшифровка: https://core.telegram.org/api/transcribe
- MacWhisper: https://www.getvoibe.com/resources/macwhisper-pricing/ ; https://spokenly.app/comparison/macwhisper
- Aiko: https://sindresorhus.com/aiko ; VoiceInk: https://www.getvoibe.com/resources/voiceink-pricing/ ; SuperWhisper: https://spokenly.app/blog/superwhisper-pricing
- Vibe: https://github.com/thewh1teagle/vibe ; noScribe: https://github.com/kaixxx/noScribe ; Buzz: https://github.com/chidiwilliams/buzz
- Subtitle Edit + диаризация: https://github.com/SubtitleEdit/subtitleedit/discussions/9496 ; WhisperX GUI: https://github.com/bubinez/whisperx-batch-gui , https://github.com/m-bain/whisperX/discussions/1434
- GigaAM, оценка: https://github.com/salute-developers/GigaAM/blob/main/evaluation.md
- stt-ru-benchmark: https://github.com/berdachuk/stt-ru-benchmark
- stt-benchmarks (RU голосовые заметки): https://github.com/Smolevich/stt-benchmarks
- ElevenLabs, русский: https://elevenlabs.io/speech-to-text/russian
- gpt-transcribe и AA-WER: https://diktuy.ru/blog/modeli-raspoznavaniya-rechi-2026
- Отечественные API, WER и цены: https://habr.com/ru/articles/993786/
