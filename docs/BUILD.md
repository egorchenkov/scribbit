# Сборка на сервере (Oracle ARM64, Ubuntu 24.04)

Окружение (ставилось 29.09.2026):
- `openjdk-17-jdk-headless` (apt), Android SDK в `~/android-sdk` (platform 35, build-tools 35).
- aapt2 от Google есть только под x86_64, поэтому в `~/.gradle/gradle.properties`:
  `android.aapt2FromMavenOverride=/home/ubuntu/android-sdk/arm64-tools/build-tools/aapt2`
  (статическая aarch64-сборка из github.com/lzhiyong/android-sdk-tools 35.0.2).
- Кэш: `~/dev/.cache/` — gradle, sherpa-onnx (jniLibs, kotlin-api, linux-aarch64 JNI, тестовые модели).

Сборка:
```bash
export ANDROID_HOME=~/android-sdk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```
Подпись: `keystore/release.jks` + `keystore/signing.properties` (вне git, 0600).
**Ключ не терять** — без него обновление поверх установленной версии невозможно
(придётся удалять приложение). APK ~17 МБ: `.so` сжаты (`useLegacyPackaging = true`),
чтобы влезать в лимит Telegram 20 МБ.

Проверка ядра без телефона (тот же Kotlin-код Pipeline/Configs/Formatter/Resampler на JVM):
```bash
export SHERPA_JNI=~/dev/.cache/sherpa/linuxjni/sherpa-onnx-v1.13.8-linux-aarch64-jni/lib
./gradlew -p tools/jvm-check -q run --args="$HOME/dev/.cache/sherpa/m gigaam /path/audio.ogg diar"
# модели: gigaam | gml | whisper:ru ; четвёртый аргумент diar[:N[:окно_с[:порог]]] — разделение говорящих
# (окно — длина куска диаризации, по умолчанию 600 с; порог сшивки говорящих между окнами 0.5)
```

Грабли:
- `-Xlambdas=class` обязателен: JNI sherpa-onnx ищет у колбэка прогресса диаризации
  `invoke(IIJ)Ljava/lang/Integer;`, которого нет у invokedynamic-лямбд Kotlin 2 → NoSuchMethodError.
- Без минификации: JNI читает поля Kotlin-классов конфигов по именам.

## Стенд качества разделения говорящих (AMI)
Открытые записи совещаний AMI (CC BY 4.0, 4 участника, эталонная разметка) — **вне git**, в `~/dev/.cache/sherpa/ami/`:
```bash
cd ~/dev/.cache/sherpa/ami
for m in ES2004a IS1009a; do
  curl -fLo $m.wav https://groups.inf.ed.ac.uk/ami/AMICorpusMirror/amicorpus/$m/audio/$m.Mix-Headset.wav
  curl -fLo $m.rttm https://raw.githubusercontent.com/pyannote/AMI-diarization-setup/main/only_words/rttms/test/$m.rttm
done
# движок диаризации отдельно (тот же C++ sherpa-onnx): конфиг = модель,шаг,порог,потоки[,N]
~/dev/.cache/sherpa/venv/bin/python tools/bench/diar_bench.py ~/dev/.cache/sherpa/m . titanet,0.25,0.9,1
# весь конвейер приложения: RTTM_OUT — разметка для tools/bench/der.py; DIAR_PAR — окон параллельно; DIAR_THR — порог
RTTM_OUT=/tmp/hyp.rttm ./gradlew -p tools/jvm-check -q run --args="$M gigaam ES2004a.wav diar:0:300:0.5"
```
Реальные записи пользователей для тестов **не используются и в репозиторий не попадают** (см. CLAUDE.md, «Приватность»).
