#!/usr/bin/env bash
# Runs against an already built and installed signed native QA release.
# Instrumentation restarts the target process and stops its guest sessions.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../../.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$repo/local.properties")}"
adb="${ADB:-$sdk/platform-tools/adb}"
build_tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
platform="$(find "$sdk/platforms" -name android.jar | sort -V | tail -n 1)"
out="$repo/app/build/lifecycle-qa"
apk="$repo/app/build/outputs/apk/release/app-release.apk"
app_classes="$repo/app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes"
mkdir -p "$out/classes" "$out/dex"
installed="$($adb shell pm path gh.nusashell.nusadesk | tr -d '\r' | sed -n 's/^package://p' | head -n 1)"
"$adb" exec-out cat "$installed" > "$out/installed.apk"
cmp "$apk" "$out/installed.apk"
sha256sum "$apk" > "$out/artifact.txt"
javac -source 8 -target 8 -cp "$platform:$app_classes" -d "$out/classes" "$repo/scripts/qa/lifecycle/LifecycleDriver.java"
mapfile -t classes < <(find "$out/classes" -name '*.class')
"$build_tools/d8" --lib "$platform" --classpath "$app_classes" --output "$out/dex" "${classes[@]}"
"$build_tools/aapt2" link -I "$platform" --manifest "$repo/scripts/qa/lifecycle/AndroidManifest.xml" -o "$out/unsigned.apk"
zip -q -j "$out/unsigned.apk" "$out/dex/classes.dex"
set -a
. "$repo/.secret/.env"
set +a
export QA_LIFECYCLE_STORE_PASS="$ANDROID_KEYSTORE_PASSWORD"
export QA_LIFECYCLE_KEY_PASS="$ANDROID_KEY_PASSWORD"
"$build_tools/apksigner" sign --ks "$repo/.secret/keystore.jks" --ks-key-alias "$ANDROID_KEY_ALIAS" \
    --ks-pass env:QA_LIFECYCLE_STORE_PASS --key-pass env:QA_LIFECYCLE_KEY_PASS \
    --out "$out/driver.apk" "$out/unsigned.apk"
"$adb" install -r "$out/driver.apk"
trap '"$adb" uninstall qa.nusadesk.lifecycle >/dev/null 2>&1 || true' EXIT
"$adb" shell input keyevent KEYCODE_WAKEUP
"$adb" shell wm dismiss-keyguard
"$adb" shell run-as gh.nusashell.nusadesk rm -f files/qa-lifecycle/result.json
"$adb" shell am instrument -w -e mode "${QA_MODE:-all}" qa.nusadesk.lifecycle/qa.nusadesk.lifecycle.LifecycleDriver | tee "$out/instrumentation.txt"
"$adb" exec-out run-as gh.nusashell.nusadesk cat files/qa-lifecycle/result.json > "$out/result.json"
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["passed"]' "$out/result.json"
