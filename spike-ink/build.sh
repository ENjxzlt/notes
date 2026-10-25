#!/usr/bin/env bash
# Baut die Debug-APK in Docker (Debug-Signatur reicht für den Test). Ergebnis: dist/ink-spike.apk
set -euo pipefail
cd "$(dirname "$0")"

docker build -q -t inkspike-build . > /dev/null

docker run --rm -u "$(id -u):$(id -g)" -v "$PWD:/src" -e GRADLE_USER_HOME=/src/.gradle-home inkspike-build \
  gradle --no-daemon -q assembleDebug

mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk dist/ink-spike.apk
echo "Fertig: spike-ink/dist/ink-spike.apk"
