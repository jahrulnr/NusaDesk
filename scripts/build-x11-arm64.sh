#!/usr/bin/env bash
# Build the experimental GPL display from pinned source; never commit the output.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
revision=7e5f5c5c9226e2cdab392b2efba91bc4bd86463e
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_root" ]]; then
    sdk_root="$(sed -n 's/^sdk.dir=//p' "$repo_dir/local.properties")"
fi
ndk_dir="$sdk_root/ndk/29.0.14206865"
[[ -f "$ndk_dir/build/cmake/android.toolchain.cmake" ]] || { echo 'Install pinned Android NDK 29.0.14206865 first.' >&2; exit 1; }
command -v bison >/dev/null || { echo 'bison is required (with its package data).' >&2; exit 1; }
command -v git >/dev/null || { echo 'git is required to fetch the pinned X11 source.' >&2; exit 1; }
command -v cmake >/dev/null || { echo 'cmake is required to build the X11 display.' >&2; exit 1; }
command -v ninja >/dev/null || { echo 'ninja is required to build the X11 display.' >&2; exit 1; }
build_root="${X11_BUILD_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/nusadesk-x11/$revision}"
mkdir -p "$build_root"
exec 9>"$build_root/build.lock"
flock 9
source_dir="$build_root/source"
if [[ ! -d "$source_dir/.git" ]]; then
    git clone --no-checkout https://github.com/termux/termux-x11.git "$source_dir"
    git -C "$source_dir" checkout --detach "$revision"
fi
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$revision" ]] || { echo 'Unexpected X11 source revision.' >&2; exit 1; }
git -C "$source_dir" submodule update --init --recursive --jobs 4 --depth 1
bash "$repo_dir/scripts/verify-x11-submodules.sh" "$source_dir" "$repo_dir/third_party/termux-x11/submodules.lock"
patch_file="$repo_dir/third_party/termux-x11/nusadesk.patch"
if ! git -C "$source_dir" apply --reverse --check "$patch_file" 2>/dev/null; then
    git -C "$source_dir" apply --check "$patch_file"
    git -C "$source_dir" apply "$patch_file"
fi
cmake -S "$source_dir/lorie/src/main/cpp" -B "$build_root/build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk_dir/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DPython3_EXECUTABLE="${PYTHON:-/usr/bin/python3}"
cmake --build "$build_root/build" --target Xlorie -j "${BUILD_JOBS:-4}"
readelf_tool="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
"$readelf_tool" -h "$build_root/build/libXlorie.so" | grep -q 'AArch64'
# Every LOAD segment must be at least 16 KB aligned. Runtime 16 KB proof is separate.
"$readelf_tool" -lW "$build_root/build/libXlorie.so" | awk '/LOAD/ { if ($NF != "0x4000" && $NF != "0x10000") bad=1; found=1 } END {exit (!found || bad)}'
output_dir="$repo_dir/app/build/generated/native-display/arm64-v8a"
mkdir -p "$output_dir"
cp "$build_root/build/libXlorie.so" "$output_dir/libXlorie.so"
# This digest is informational only. Unlike build-proot-arm64.sh there is no
# EXPECTED_SHA256 output gate: the cmake/NDK build embeds host paths and
# toolchain metadata, so libXlorie.so is not bit-reproducible across
# environments and no honest hash can be pinned. Integrity instead rests on
# the pinned source revision, the verified submodule lock, the reviewed patch,
# and the ELF checks above.
sha256sum "$output_dir/libXlorie.so"

# Preserve native dependency notices in the experimental APK, alongside the source pins.
notice_dir="$repo_dir/app/build/generated/native-display-assets/licenses/native-display"
mkdir -p "$notice_dir"
cp "$source_dir/LICENSE" "$notice_dir/Termux-X11-GPL-3.0.txt"
cp "$repo_dir/third_party/termux-x11/README.md" "$notice_dir/README.md"
cp "$repo_dir/third_party/termux-x11/submodules.lock" "$notice_dir/submodules.lock"
while IFS= read -r -d '' notice; do
    relative="${notice#"$source_dir/lorie/src/main/cpp/"}"
    mkdir -p "$notice_dir/$(dirname "$relative")"
    cp "$notice" "$notice_dir/$relative"
done < <(find "$source_dir/lorie/src/main/cpp" -type f     \( -iname 'COPYING*' -o -iname 'LICENSE*' -o -iname 'COPYRIGHT*' \) -print0)
