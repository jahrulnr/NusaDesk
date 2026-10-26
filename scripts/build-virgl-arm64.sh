#!/usr/bin/env bash
# Pinned Android GLES vtest renderer. Outputs stay under app/build.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' "$repo/local.properties")}}"
llvm="$sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin"
cache="${VIRGL_BUILD_ROOT:-${XDG_CACHE_HOME:-$HOME/.cache}/nusadesk-virgl/1.3.0}"
mkdir -p "$cache"
exec 9>"$cache/build.lock"
flock 9
fetch() {
    local name="$1" digest="$2" url="$3"
    if [[ ! -f "$cache/$name" ]]; then
        curl --fail --location --retry 3 "$url" -o "$cache/$name.part"
        mv "$cache/$name.part" "$cache/$name"
    fi
    printf '%s  %s\n' "$digest" "$cache/$name" | sha256sum --check --status
}
fetch virgl.tar.gz 56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce \
    https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-1.3.0/virglrenderer-virglrenderer-1.3.0.tar.gz
fetch epoxy.tar.gz a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15 \
    https://codeload.github.com/anholt/libepoxy/tar.gz/refs/tags/1.5.10
stamp="$(cat "$repo/third_party/virglrenderer/android.patch" "$0" | sha256sum | cut -d' ' -f1)"
work="$cache/$stamp"
if [[ ! -d "$work" ]]; then
    mkdir -p "$work"
    tar -xf "$cache/virgl.tar.gz" -C "$work"
    tar -xf "$cache/epoxy.tar.gz" -C "$work"
    patch -d "$work/virglrenderer-virglrenderer-1.3.0" -p1 < "$repo/third_party/virglrenderer/android.patch"
fi
cat > "$work/cross.ini" <<EOF
[binaries]
c = '$llvm/aarch64-linux-android29-clang'
cpp = '$llvm/aarch64-linux-android29-clang++'
ar = '$llvm/llvm-ar'
strip = '$llvm/llvm-strip'
pkg-config = '/usr/bin/pkg-config'
[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
[properties]
needs_exe_wrapper = true
pkg_config_libdir = ['$work/install/lib/pkgconfig']
[built-in options]
c_link_args = ['-Wl,-z,max-page-size=16384']
EOF
if [[ ! -f "$work/epoxy-build/build.ninja" ]]; then
    meson setup "$work/epoxy-build" "$work/libepoxy-1.5.10" --cross-file "$work/cross.ini" \
        --prefix="$work/install" --libdir=lib -Ddefault_library=static \
        -Degl=yes -Dglx=no -Dx11=false -Dtests=false
fi
ninja -C "$work/epoxy-build" install -j "${BUILD_JOBS:-4}"
if [[ ! -f "$work/virgl-build/build.ninja" ]]; then
    meson setup "$work/virgl-build" "$work/virglrenderer-virglrenderer-1.3.0" --cross-file "$work/cross.ini" \
        -Ddefault_library=static -Dplatforms=egl -Dvenus=false -Dvideo=false -Dtests=false
fi
ninja -C "$work/virgl-build" vtest/virgl_test_server -j "${BUILD_JOBS:-4}"
binary="$work/virgl-build/vtest/virgl_test_server"
"$llvm/llvm-readelf" -h "$binary" | grep AArch64 >/dev/null
"$llvm/llvm-readelf" -lW "$binary" | awk '/LOAD/ {if ($NF != "0x4000" && $NF != "0x10000") bad=1; found=1} END {exit (!found || bad)}'
out="$repo/app/build/generated/native-display/arm64-v8a"
notices="$repo/app/build/generated/native-display-assets/licenses/virglrenderer"
mkdir -p "$out" "$notices"
cp "$binary" "$out/libvirglserver.so"
cp "$work/virglrenderer-virglrenderer-1.3.0/COPYING" "$notices/virglrenderer-MIT.txt"
cp "$work/libepoxy-1.5.10/COPYING" "$notices/libepoxy-MIT.txt"
cp "$repo/third_party/virglrenderer/README.md" "$notices/README.md"
sha256sum "$out/libvirglserver.so"
