#!/usr/bin/env bash
# Inject one real uninterrupted finger gesture into the currently visible app.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../../.." && pwd)"
mode="${1:-zigzag}"
case "$mode" in zigzag|long|hold) ;; *) echo 'Use zigzag, long, or hold' >&2; exit 1 ;; esac
sdk="${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$repo/local.properties")}"
adb="${ADB:-$sdk/platform-tools/adb}"
tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
platform="$(find "$sdk/platforms" -name android.jar | sort -V | tail -n 1)"
[[ -n "$tools" ]] || { echo "No Android build-tools directory found under ${sdk:-<unset>}/build-tools." >&2; exit 1; }
[[ -n "$platform" ]] || { echo "No android.jar found under ${sdk:-<unset>}/platforms." >&2; exit 1; }
key="${QA_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
[[ -f "$key" ]] || { echo 'Run make build first to create the standard Android debug keystore.' >&2; exit 1; }
out="$repo/app/build/terminal-touch-qa"
mkdir -p "$out/classes" "$out/dex"
javac -source 8 -target 8 -cp "$platform" -d "$out/classes" "$repo/scripts/qa/terminal-touch/GestureDriver.java"
"$tools/d8" --lib "$platform" --output "$out/dex" "$out/classes/qa/nusadesk/gestures/GestureDriver.class"
"$tools/aapt2" link -I "$platform" --manifest "$repo/scripts/qa/terminal-touch/AndroidManifest.xml" -o "$out/unsigned.apk"
zip -q -j "$out/unsigned.apk" "$out/dex/classes.dex"
"$tools/apksigner" sign --ks "$key" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$out/driver.apk" "$out/unsigned.apk"
"$adb" install -r "$out/driver.apk"
trap '"$adb" uninstall qa.nusadesk.gestures >/dev/null 2>&1 || true' EXIT
"$adb" shell am instrument -w -e mode "$mode" qa.nusadesk.gestures/.GestureDriver | tee "$out/result.txt"
grep -q '^INSTRUMENTATION_CODE: -1' "$out/result.txt"
