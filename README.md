# Scribbit — offline speech-to-text for Android

[Русский](README.ru.md) · [Oʻzbekcha](README.uz.md) · [Website](https://egorchenkov.github.io/scribbit/)

Scribbit turns a meeting recording, a Telegram voice message or any audio file into text
**right on your phone**. Internet is needed once, to download a model; after that nothing
leaves the device. Free and open source (MIT).

## Features
- **Russian** (GigaAM v3 — with punctuation), **Uzbek / Kazakh** (GigaAM Multilingual),
  **English** and ~100 other languages (Whisper Small).
- **Speaker separation** (“Speaker 1: …”) for meetings and calls; set the number of participants
  or leave “auto”. Multi-hour recordings are processed in parts, memory does not grow with length.
- Background recording from the microphone (screen can be off), batch processing of several files,
  “Share” from Telegram, WhatsApp, voice recorders and any other app (audio and video).
- Result: copy, share as text (to a messenger or an LLM), save as .txt; timestamps optional.
- Survives being killed by the system: work resumes from the last finished 5-minute part.
- Interface in English, Russian and Uzbek (follows the system language).

## Install
1. Download `Scribbit-X.Y.Z.apk` from [Releases](../../releases) and open it on the phone
   (allow installation from this source). Requires Android 8+ on 64-bit ARM (arm64-v8a) —
   practically every phone of recent years.
2. Gear icon → download a speech model and, for meetings, “Speaker separation”.
3. “● Record”, “Choose files” or “Share” → “Transcribe”.

For automatic updates add this repository to [Obtainium](https://github.com/ImranR98/Obtainium).

## Models
Downloaded inside the app from HuggingFace; not bundled in the APK.

- GigaAM v3 punct CTC — Russian, Sber, MIT.
- GigaAM Multilingual CTC — Uzbek / Russian / Kazakh, MIT.
- Whisper Small (int8) — OpenAI, MIT.
- Speaker separation: pyannote segmentation-3.0 (MIT) + NVIDIA TitaNet-small (CC BY 4.0).
- Voice activity detector Silero VAD (MIT) — bundled in the APK.

Why these models — `docs/research-models.md`; comparison with other apps — `docs/competitors.md`;
speaker-separation quality and speed — `docs/benchmark.md`; background work on Huawei/Xiaomi/… — `docs/background.md`.

## Build
`./gradlew assembleRelease` (JDK 17, Android SDK 35). Details, running the core on a server without
a phone and the quality bench — `docs/BUILD.md`. Engine — [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
(Apache-2.0), prebuilt `jniLibs/arm64-v8a`.

## Privacy
The app sends neither audio nor text anywhere and contains no analytics. The network is used only
to download models when you press the button. The optional “background log” contains timestamps and
system state only, never the content of recordings.

## License
MIT — see `LICENSE`. Models are distributed under their own licenses (listed above).
