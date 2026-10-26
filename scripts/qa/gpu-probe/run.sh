#!/usr/bin/env bash
# Exercise Android GPU APIs in an isolated ordinary app, not the adb shell domain.
set -euo pipefail
repo="$(cd "$(dirname "$0")/../../.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$repo/local.properties")}"
adb="${ADB:-$sdk/platform-tools/adb}"
tools="$sdk/build-tools/36.0.0"
platform="$sdk/platforms/android-37.0/android.jar"
ndk="${QA_NDK_ROOT:-$sdk/ndk/29.0.14206865}"
key="${QA_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
mkdir -p "$repo/app/build"
out="$(mktemp -d "$repo/app/build/gpu-probe-qa.XXXXXX")"
mkdir -p "$out/classes" "$out/dex" "$out/stage/lib/arm64-v8a"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang" \
    -Wall -Wextra -Werror -shared -fPIC -Wl,-z,max-page-size=16384 \
    "$repo/scripts/qa/gpu-probe/probe.c" -lvulkan -o "$out/stage/lib/arm64-v8a/libgpuprobe.so"
javac -source 11 -target 11 -cp "$platform" -d "$out/classes" "$repo/scripts/qa/gpu-probe/GpuProbe.java"
"$tools/d8" --lib "$platform" --output "$out/dex" "$out/classes/qa/nusadesk/gpuprobe/GpuProbe.class"
"$tools/aapt2" link -I "$platform" --manifest "$repo/scripts/qa/gpu-probe/AndroidManifest.xml" -o "$out/unsigned.apk"
(cd "$out/stage" && zip -q -0 "$out/unsigned.apk" lib/arm64-v8a/libgpuprobe.so)
zip -q -j "$out/unsigned.apk" "$out/dex/classes.dex"
"$tools/apksigner" sign --ks "$key" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --out "$out/probe.apk" "$out/unsigned.apk"
"$adb" install -r "$out/probe.apk"
trap '"$adb" uninstall qa.nusadesk.gpuprobe >/dev/null 2>&1 || true' EXIT
"$adb" shell am instrument -w qa.nusadesk.gpuprobe/qa.nusadesk.gpuprobe.GpuProbe | tee "$out/result.txt"
"${PYTHON:-python3}" - "$out" <<'PY'
import json
from pathlib import Path
import sys
out = Path(sys.argv[1])
text = (out / 'result.txt').read_text()
report = json.loads(text.split('INSTRUMENTATION_RESULT: report=', 1)[1]
                   .split('\nINSTRUMENTATION_CODE:', 1)[0])
(out / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
assert 'u:r:untrusted_app:' in report['selinux'], report['selinux']
assert report.get('egl', {}).get('trianglePixelVerified'), report
print('App-domain EGL draw verified; Vulkan outcome is recorded separately in', out / 'result.json')
PY
