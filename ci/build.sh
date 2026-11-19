#!/usr/bin/env bash
# Сборка Полки в GitHub Actions. Логика живёт здесь, а не в workflow: так её можно менять
# обычным коммитом, без отдельных прав на .github/workflows.
set -uo pipefail

bot_name="github-actions[bot]"
bot_mail="41898282+github-actions[bot]@users.noreply.github.com"
import_state="нет"
build_state="не запускался"
release_state="нет"

# Короткий отчёт в ветку ci-status: его видно без доступа к логам Actions.
report() {
  local result="$1"
  {
    echo "# Сборка Полки"
    echo
    echo "- Итог: $result"
    echo "- Импорт: $import_state"
    echo "- Gradle: $build_state"
    echo "- Релиз: $release_state"
    echo "- Коммит: $(git rev-parse --short HEAD)"
    echo "- Запуск: ${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
    echo "- Время: $(date -u +%FT%TZ)"
    if [ -f build.log ]; then
      echo
      echo "## Ошибки"
      echo '```'
      grep -nE '^e: |^ERROR|error:|FAILED|Unresolved reference|Could not resolve' build.log | head -n 120 || true
      echo '```'
      echo
      echo "## What went wrong"
      echo '```'
      awk '/What went wrong/{f=1} f{print; if(++n>60) exit}' build.log || true
      echo '```'
      echo
      echo "## Хвост лога"
      echo '```'
      tail -n 60 build.log
      echo '```'
    fi
  } > /tmp/status.md
  local dir
  dir=$(mktemp -d)
  (
    cd "$dir" && git init -q -b ci-status && cp /tmp/status.md status.md && git add status.md &&
      git -c user.name="$bot_name" -c user.email="$bot_mail" commit -qm "CI: $result" &&
      git push -qf "${CI_STATUS_REMOTE:-https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git}" ci-status
  ) || echo "не удалось записать ci-status"
}

# 1. Импорт архива проекта по короткой ссылке из ci/import.json со сверкой SHA-256.
if [ -f ci/import.json ]; then
  url=$(jq -r .url ci/import.json)
  sum=$(jq -r .sha256 ci/import.json)
  msg=$(jq -r '.message // "Импорт проекта"' ci/import.json)
  if curl -fsSL --retry 3 --retry-delay 5 -o /tmp/import.zip "$url" &&
    echo "$sum  /tmp/import.zip" | sha256sum -c -; then
    unzip -oq /tmp/import.zip -d .
    # Workflow-файлы правит только владелец: у токена Actions на них нет прав.
    git checkout -q HEAD -- .github/workflows 2>/dev/null || true
    git ls-files --others --exclude-standard .github/workflows | xargs -r rm -f
    git rm -q ci/import.json
    git add -A
    git -c user.name="$bot_name" -c user.email="$bot_mail" commit -qm "$msg"
    if git push -q origin "HEAD:${GITHUB_REF_NAME}"; then import_state="готово"; else import_state="ошибка пуша"; fi
  else
    import_state="архив не скачался или не сошлась сумма"
    report "failure"
    exit 1
  fi
fi

# 1б. Мелкая правка: ci/patch.diff накладывается через git apply, коммитится и удаляется.
# Так однострочные починки не требуют перезаливки всего архива.
if [ -f ci/patch.diff ]; then
  if git apply --whitespace=nowarn ci/patch.diff; then
    git rm -q ci/patch.diff
    git add -A
    git -c user.name="$bot_name" -c user.email="$bot_mail" commit -qm "CI: правка из ci/patch.diff"
    if git push -q origin "HEAD:${GITHUB_REF_NAME}"; then import_state="правка наложена"; else import_state="ошибка пуша правки"; fi
  else
    import_state="правка не наложилась"
    report "failure"
    exit 1
  fi
fi

if [ ! -f app/build.gradle.kts ]; then
  report "нет проекта"
  exit 0
fi

# 2. Сборка подписанного APK.
if gradle :app:assembleRelease --stacktrace 2>&1 | tee build.log; then
  build_state="success"
else
  build_state="failure"
  report "failure"
  exit 1
fi

# 3. Релиз с APK: v<версия>, файл polka-<версия>.apk.
ver=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
apk=$(ls app/build/outputs/apk/release/*.apk | head -n 1)
cp "$apk" "polka-$ver.apk"
if gh release view "v$ver" >/dev/null 2>&1; then
  gh release upload "v$ver" "polka-$ver.apk" --clobber && release_state="обновлён v$ver" || release_state="ошибка"
else
  # Описание релиза: первый раздел «Что нового» из README, его же Полка покажет на странице обновления.
  notes=$(awk '
    /^## Что нового/ { if (seen) exit; seen = 1; next }
    seen && /^## / { exit }
    seen && /^- / { if (item != "") print item; item = $0; next }
    seen && /^  +[^ ]/ { line = $0; sub(/^ +/, "", line); item = item " " line; next }
    END { if (item != "") print item }
  ' README.md)
  [ -n "$notes" ] || notes="Сборка $(git rev-parse --short HEAD)."
  notes="$notes

Вручную: adb install -r polka-$ver.apk"
  gh release create "v$ver" "polka-$ver.apk" --target "$(git rev-parse HEAD)" \
    --title "Полка $ver" \
    --notes "$notes" &&
    release_state="создан v$ver" || release_state="ошибка"
fi
report "success"
