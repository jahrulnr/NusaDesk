#!/usr/bin/env bash
# Build an isolated instrumentation APK with the actual production probe source.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../../.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$repo/local.properties")}"
adb="${ADB:-$sdk/platform-tools/adb}"
tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
platform="$(find "$sdk/platforms" -name android.jar | sort -V | tail -n 1)"
key="${QA_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
out="$repo/app/build/x11-probe-qa"
mkdir -p "$out/classes" "$out/dex"
javac -source 8 -target 8 -cp "$platform" -d "$out/classes" \
    "$repo/app/src/main/java/gh/nusashell/nusadesk/infrastructure/display/X11SocketProbe.java" \
    "$repo/scripts/qa/x11-probe/X11SocketProbeDriver.java"
mapfile -t classes < <(find "$out/classes" -name '*.class')
"$tools/d8" --lib "$platform" --output "$out/dex" "${classes[@]}"
"$tools/aapt2" link -I "$platform" --manifest "$repo/scripts/qa/x11-probe/AndroidManifest.xml" -o "$out/unsigned.apk"
zip -q -j "$out/unsigned.apk" "$out/dex/classes.dex"
"$tools/apksigner" sign --ks "$key" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$out/driver.apk" "$out/unsigned.apk"
"$adb" install -r "$out/driver.apk"
trap '"$adb" uninstall qa.nusadesk.x11probe >/dev/null 2>&1 || true' EXIT
"$adb" shell am instrument -w qa.nusadesk.x11probe/gh.nusashell.nusadesk.infrastructure.display.X11SocketProbeDriver | tee "$out/result.txt"
grep -q '^INSTRUMENTATION_CODE: -1' "$out/result.txt"
