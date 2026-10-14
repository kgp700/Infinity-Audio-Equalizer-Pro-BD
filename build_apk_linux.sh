#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v java >/dev/null; then echo "Java 17 required" >&2; exit 2; fi
if [ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then echo "Set ANDROID_HOME to installed Android SDK" >&2; exit 2; fi
if command -v gradle >/dev/null; then
  gradle --no-daemon :app:assembleDebug
else
  echo "Gradle 8.11.1 required. Install Gradle or use GitHub Actions workflow." >&2; exit 2
fi
apk=app/build/outputs/apk/debug/app-debug.apk
test -s "$apk"
echo "APK: $PWD/$apk"
