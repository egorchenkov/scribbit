#!/bin/sh
# Предохранитель публичного репозитория: записи и расшифровки реальных встреч
# не должны попасть в git ни при каких обстоятельствах (правило проекта, CLAUDE.md).
# Проверяет список файлов из stdin (пути относительно корня репозитория) по содержимому в индексе/коммите $1.
rev="$1"   # пусто — индекс (pre-commit), иначе коммит (pre-push)
deny="${TRANSCRIBER_DENYLIST:-$HOME/.config/android-transcriber/private-denylist.txt}"
fail=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  case "$f" in
    app/src/main/jniLibs/*.so|app/src/main/assets/silero_vad.onnx|gradle/wrapper/gradle-wrapper.jar) continue ;;
  esac
  lower=$(printf '%s' "$f" | tr 'A-Z' 'a-z')
  case "$lower" in
    *.wav|*.mp3|*.m4a|*.ogg|*.oga|*.opus|*.flac|*.aac|*.amr|*.3gp|*.mp4|*.m4v|*.mkv|*.webm|*.mov|*.wma|*.srt|*.vtt|*.rttm|*.docx|*.pdf)
      echo "✖ $f — аудио/видео/расшифровки в репозиторий нельзя"; fail=1; continue ;;
  esac
  if [ -z "$rev" ]; then obj=":$f"; else obj="$rev:$f"; fi
  size=$(git cat-file -s "$obj" 2>/dev/null || echo 0)
  if [ "$size" -gt 300000 ]; then echo "✖ $f — ${size} байт: крупные файлы только по явному решению"; fail=1; continue; fi
  if [ -f "$deny" ] && git cat-file -p "$obj" 2>/dev/null | grep -a -i -q -F -f "$deny"; then
    echo "✖ $f — содержит слова из приватного стоп-списка ($deny)"; fail=1
  fi
done
[ $fail -eq 0 ] || echo "Коммит/пуш остановлен: см. правило «Приватность» в CLAUDE.md"
exit $fail
