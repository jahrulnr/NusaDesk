#!/usr/bin/env bash
#
# build-proot-arm64.sh — Reproducibly build the PRoot execution bridge for
# Android arm64-v8a as a standalone PIE executable packaged as libproot.so.
#
# PRoot is the user-space compatibility layer selected by ADR-004 as the
# Android execution bridge/loader. It is shipped in jniLibs/<abi>/ so the
# Android package installer extracts it into the APK's native library area
# (read-only, executable) — the only modern Android contract that allows an
# app to execute a bundled native program. The downloaded Linux rootfs stays
# out-of-band data (ADR-006); this binary only provides the loader/ptrace
# bridge, it is NOT a security sandbox (see AGENTS.md "PRoot boundary").
#
# This script clones third-party SOURCE into a build dir under /tmp (never
# into the workspace) and writes only the verified, stripped build outputs
# into app/src/main/jniLibs/arm64-v8a/:
#   libproot.so         the PRoot PIE executable (exec'd by the app)
#   libproot-loader.so  PRoot's freestanding ELF loader, shipped separately so
#                       PRoot can find it via the PROOT_LOADER env var instead
#                       of extracting its embedded copy into app-private
#                       writable storage (app_data_file), whose exec is denied
#                       under u:r:untrusted_app on targetSdk 29+. Files in
#                       nativeLibraryDir are apk_data_file, which the app
#                       domain may execute.
#
# Pinned inputs (reproducibility):
#   PRoot   : termux/proot  v5.1.107.92  (git rev 7266fb3e8516535682f5a9c8f3a7e70f6506eddb)
#             GPL-2.0-or-later — a compatible fork of upstream proot-me/proot
#             (also GPL-2.0-or-later) carrying the Android ptrace/proc patches.
#   Talloc  : samba talloc 2.4.2 tarball (LGPL-3.0-or-later), statically linked.
#   NDK     : Android NDK r28c (28.2.13676358), clang 19, 16 KB page capable.
#
# License obligations produced by this build (see docs/research note):
#   - libproot.so is a derivative of GPL-2.0-or-later PRoot + LGPL-3.0-or-later
#     talloc. The combined work is distributable under GPL-2.0+ (PRoot's
#     "or later" clause permits upgrading to GPLv3, which is compatible with
#     LGPL-3.0). Source for both is pinned above; this script reproduces the
#     binary. Talloc is statically linked, so LGPL-3.0 sections 4-6 require
#     that users can re-link against a modified talloc — satisfied by
#     publishing the exact source revisions and this build script.
#
# Usage:
#   scripts/build-proot-arm64.sh            # build + verify + install
#   scripts/build-proot-arm64.sh --no-install  # build + verify only
#   scripts/build-proot-arm64.sh --repro-check # build twice, compare hashes
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration (pinned for reproducibility)
# ---------------------------------------------------------------------------
PROOT_REPO="https://github.com/termux/proot.git"
PROOT_TAG="v5.1.107.92"
PROOT_REV="7266fb3e8516535682f5a9c8f3a7e70f6506eddb"
PROOT_VERSION="5.1.107.92"

TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-2.4.2.tar.gz"
TALLOC_TGZ="talloc-2.4.2.tar.gz"
TALLOC_SHA256="85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6"
TALLOC_VERSION="2.4.2"

NDK_VERSION="28.2.13676358"   # Android NDK r28c
ANDROID_API="29"              # minSdk 29 (Android 10) — lowest supported target

# 16 KB page-size compatibility: all LOAD segments aligned to 0x4000.
PAGE_SIZE_FLAG="-Wl,-z,max-page-size=16384"
# ld.lld parallel string-merge is non-deterministic; single-threaded linking
# yields a bit-identical binary across rebuilds (verified).
THREADS_FLAG="-Wl,--threads=1"

# Reproducible stripped output hash (sha256) for this exact configuration.
EXPECTED_SHA256="6577444428cd0a0ddd4dd45a56af1bb49622986f52700e33a9bfdbb681e64046"
# Same for the freestanding loader shipped as libproot-loader.so.
EXPECTED_LOADER_SHA256="12d2b63e897fd91a334fce23edea5d2419cae4d5fd2a369f05d03ab75682add0"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNILIBS_DIR="${REPO_ROOT}/app/src/main/jniLibs/arm64-v8a"
OUTPUT_NAME="libproot.so"
LOADER_OUTPUT_NAME="libproot-loader.so"

BUILD_ROOT="${BUILD_ROOT:-/tmp/linux-wrapper-proot-build}"
SDK_ROOT="${SDK_ROOT:-/tmp/linux-wrapper-android-sdk}"

DO_INSTALL=1
REPRO_CHECK=0
for arg in "$@"; do
  case "$arg" in
    --no-install) DO_INSTALL=0 ;;
    --repro-check) REPRO_CHECK=1; DO_INSTALL=0 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
log() { printf '\033[1;34m[build-proot]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[build-proot] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

require() { command -v "$1" >/dev/null 2>&1 || die "missing required tool: $1"; }
require git curl sha256sum make

# ---------------------------------------------------------------------------
# 1. Locate / install the Android NDK
# ---------------------------------------------------------------------------
NDK_DIR="${SDK_ROOT}/ndk/${NDK_VERSION}"
if [[ ! -x "${NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${ANDROID_API}-clang" ]]; then
  log "NDK ${NDK_VERSION} not found at ${NDK_DIR}; installing via sdkmanager..."
  SDKMGR="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
  [[ -x "${SDKMGR}" ]] || die "sdkmanager not found at ${SDKMGR}; install Android cmdline-tools first."
  yes | "${SDKMGR}" --install "ndk;${NDK_VERSION}" || die "NDK install failed."
fi
TOOL="${NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="${TOOL}/bin:${PATH}"
CC="${TOOL}/bin/aarch64-linux-android${ANDROID_API}-clang"
[[ -x "${CC}" ]] || die "NDK aarch64 clang not found at ${CC}"
log "NDK: ${NDK_DIR} (API ${ANDROID_API})"

# ---------------------------------------------------------------------------
# 2. Fetch sources into /tmp build root (never the workspace)
# ---------------------------------------------------------------------------
mkdir -p "${BUILD_ROOT}"
PROOT_SRC="${BUILD_ROOT}/proot-src"
TALLOC_SRC="${BUILD_ROOT}/talloc-src"
TALLOC_BUILD="${BUILD_ROOT}/talloc-build"

if [[ ! -d "${PROOT_SRC}/.git" ]]; then
  log "Cloning PRoot ${PROOT_TAG} (rev ${PROOT_REV:0:12})..."
  git clone --depth 1 --branch "${PROOT_TAG}" "${PROOT_REPO}" "${PROOT_SRC}"
else
  log "PRoot source already present at ${PROOT_SRC}"
fi
cd "${PROOT_SRC}"
ACTUAL_REV="$(git rev-parse HEAD)"
[[ "${ACTUAL_REV}" == "${PROOT_REV}" ]] || die "PRoot rev mismatch: ${ACTUAL_REV} != ${PROOT_REV}"
log "PRoot rev verified: ${ACTUAL_REV} (${PROOT_TAG})"

if [[ ! -f "${TALLOC_SRC}/talloc.c" ]]; then
  log "Downloading talloc ${TALLOC_VERSION}..."
  ( cd "${BUILD_ROOT}" && curl -fsSL -o "${TALLOC_TGZ}" "${TALLOC_URL}" )
  ( cd "${BUILD_ROOT}" && echo "${TALLOC_SHA256}  ${TALLOC_TGZ}" | sha256sum -c - )
  ( cd "${BUILD_ROOT}" && tar xzf "${TALLOC_TGZ}" && mv "talloc-${TALLOC_VERSION}" "${TALLOC_SRC}" )
else
  log "Talloc source already present at ${TALLOC_SRC}"
fi

# ---------------------------------------------------------------------------
# 3. Apply recorded build patches (see docs/research note for full origin)
# ---------------------------------------------------------------------------
log "Applying build patches..."

# Patch A: ashmem_memfd.c uses strcmp/memset without <string.h>. glibc pulls it
# transitively; bionic does not, so it is a hard error under the NDK.
# Upstream termux/proot does not include this; it is a local compatibility fix.
ASHMEM="${PROOT_SRC}/src/extension/ashmem_memfd/ashmem_memfd.c"
if ! grep -q '<string.h>' "${ASHMEM}"; then
  sed -i '3a #include <string.h>' "${ASHMEM}"
  log "  Patch A: added <string.h> to ashmem_memfd.c"
fi

# Patch B: loader/loader-info.awk uses gawk-only strtonum() and \y word
# boundaries. Replace with a mawk-compatible hex parser and $NF symbol match.
# This only affects the generated offset constant, which is verified correct.
AWK_SCRIPT="${PROOT_SRC}/src/loader/loader-info.awk"
if grep -q 'strtonum' "${AWK_SCRIPT}"; then
  cat > "${AWK_SCRIPT}" <<'AWKEOF'
# Note: This file is included only for targets which have pokedata workaround
# Patched for mawk compatibility: strtonum() is gawk-only and \y word
# boundaries are not supported by mawk. Use a manual hex parser and match the
# symbol name in the last field ($NF).
function hex2num(s,   n, i, c, v) {
	sub(/^0x/, "", s)
	n = 0
	for (i = 1; i <= length(s); i++) {
		c = tolower(substr(s, i, 1))
		v = index("0123456789abcdef", c) - 1
		n = n * 16 + v
	}
	return n
}
$NF == "pokedata_workaround" { pokedata_workaround = hex2num("0x" $2) }
$NF == "_start"              { start = hex2num("0x" $2) }
END {
	print "#include <unistd.h>"
	print "const ssize_t offset_to_pokedata_workaround=" (pokedata_workaround-start) ";"
}
AWKEOF
  log "  Patch B: rewrote loader-info.awk for mawk compatibility"
fi

# ---------------------------------------------------------------------------
# 4. Build static libtalloc.a with a minimal replace.h shim
# ---------------------------------------------------------------------------
log "Building static libtalloc.a..."
mkdir -p "${TALLOC_BUILD}"
# talloc.c includes samba's replace.h (LGPL-3.0). Bionic already provides every
# libc symbol talloc uses, so a minimal shim suffices and avoids the waf
# cross-compile toolchain (which needs qemu/gawk unavailable here).
cat > "${TALLOC_BUILD}/replace.h" <<'REPEOF'
#ifndef _MINI_REPLACE_H
#define _MINI_REPLACE_H
#define NO_CONFIG_H 1
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <errno.h>
#include <string.h>
#include <stdint.h>
#include <inttypes.h>
#include <unistd.h>
#include <sys/types.h>
#include <stdbool.h>
#ifndef MIN
#define MIN(a,b) ((a) < (b) ? (a) : (b))
#endif
#ifndef MAX
#define MAX(a,b) ((a) > (b) ? (a) : (b))
#endif
#define _PUBLIC_ __attribute__((visibility("default")))
#define discard_const(ptr) ((void *)((uintptr_t)(ptr)))
#define discard_const_p(type, ptr) ((type *)discard_const(ptr))
#define __STRING(x) #x
#define __STRINGSTRING(x) __STRING(x)
#define __LINESTR__ __STRINGSTRING(__LINE__)
#define __location__ __FILE__ ":" __LINESTR__
#endif
REPEOF

( cd "${TALLOC_SRC}"
  "${CC}" -fPIC -O2 -fstack-protector-strong \
    -DNO_CONFIG_H=1 -DHAVE_GETAUXVAL=1 -D__STDC_WANT_LIB_EXT1__=1 \
    -DHAVE_VA_COPY=1 -DHAVE_CONSTRUCTOR_ATTRIBUTE=1 \
    -DTALLOC_BUILD_VERSION_MAJOR=2 -DTALLOC_BUILD_VERSION_MINOR=4 -DTALLOC_BUILD_VERSION_RELEASE=2 \
    -I"${TALLOC_BUILD}" -I"${TALLOC_SRC}" \
    -c talloc.c -o "${TALLOC_BUILD}/talloc.o"
)
llvm-ar rcs "${TALLOC_BUILD}/libtalloc.a" "${TALLOC_BUILD}/talloc.o"
llvm-ranlib "${TALLOC_BUILD}/libtalloc.a"
log "  libtalloc.a: $(llvm-nm "${TALLOC_BUILD}/libtalloc.a" | grep -c ' T ') exported symbols"

# ---------------------------------------------------------------------------
# 5. Build PRoot (PIE executable, bundled 32+64-bit loaders, static talloc)
# ---------------------------------------------------------------------------
log "Building PRoot ${PROOT_VERSION} for arm64-v8a..."
# PRoot's GNUmakefile uses `+=` on CPPFLAGS/CFLAGS/LDFLAGS, so they MUST come
# from the environment (command-line overrides would discard the makefile's
# essential additions like -D_GNU_SOURCE and -ltalloc).
export CC
export STRIP=llvm-strip
export OBJCOPY=llvm-objcopy
export OBJDUMP=llvm-objdump
export CPPFLAGS="-DVERSION=\"${PROOT_VERSION}\" -DARG_MAX=131072 -I${TALLOC_SRC} -I${TALLOC_BUILD}"
export CFLAGS="-fPIE -O2 -fstack-protector-strong"
export LDFLAGS="-L${TALLOC_BUILD} -pie ${PAGE_SIZE_FLAG} ${THREADS_FLAG}"

build_one() {
  make -C "${PROOT_SRC}/src" clean >/dev/null 2>&1 || true
  make -C "${PROOT_SRC}/src" proot >/dev/null 2>&1
  llvm-strip --strip-all "${PROOT_SRC}/src/proot" -o "$1"
}

OUT_BIN="${BUILD_ROOT}/${OUTPUT_NAME}"
build_one "${OUT_BIN}"
log "Built: ${OUT_BIN} ($(stat -c%s "${OUT_BIN}") bytes)"

# The loader is an order-only byproduct of `make proot` (src/loader/loader).
# It is a freestanding static EXEC, not a PIE, so the readelf verification in
# step 7 does not apply; it is verified by hash only.
OUT_LOADER="${BUILD_ROOT}/${LOADER_OUTPUT_NAME}"
llvm-strip --strip-all "${PROOT_SRC}/src/loader/loader" -o "${OUT_LOADER}"
log "Built loader: ${OUT_LOADER} ($(stat -c%s "${OUT_LOADER}") bytes)"
ACTUAL_LOADER_SHA256="$(sha256sum "${OUT_LOADER}" | awk '{print $1}')"
log "Loader stripped sha256: ${ACTUAL_LOADER_SHA256}"

# ---------------------------------------------------------------------------
# 6. Reproducibility verification
# ---------------------------------------------------------------------------
ACTUAL_SHA256="$(sha256sum "${OUT_BIN}" | awk '{print $1}')"
log "Stripped sha256: ${ACTUAL_SHA256}"

if [[ "${REPRO_CHECK}" -eq 1 ]]; then
  log "Reproducibility check: rebuilding and comparing..."
  SECOND="${BUILD_ROOT}/${OUTPUT_NAME}.second"
  build_one "${SECOND}"
  SECOND_SHA256="$(sha256sum "${SECOND}" | awk '{print $1}')"
  if cmp -s "${OUT_BIN}" "${SECOND}"; then
    log "  PASS: bit-identical across clean rebuilds (${SECOND_SHA256})"
  else
    die "  FAIL: builds are not bit-identical (${ACTUAL_SHA256} vs ${SECOND_SHA256})"
  fi
  rm -f "${SECOND}"
fi

# Fail closed: a mismatch between the actual stripped output hash and the
# pinned expected hash means the build is not reproducible against the
# recorded baseline. Refuse to install an unverified binary. This gate runs
# after the --repro-check comparison so that mode still reports its
# bit-identical result before the pinned-hash enforcement.
if [[ "${ACTUAL_LOADER_SHA256}" != "${EXPECTED_LOADER_SHA256}" ]]; then
  die "Loader hash mismatch: actual ${ACTUAL_LOADER_SHA256} != expected ${EXPECTED_LOADER_SHA256}"
fi
log "Loader hash matches expected pinned output."
if [[ "${ACTUAL_SHA256}" != "${EXPECTED_SHA256}" ]]; then
  die "PRoot hash mismatch: actual ${ACTUAL_SHA256} != expected ${EXPECTED_SHA256}"
fi
log "Hash matches expected pinned output."

# ---------------------------------------------------------------------------
# 7. readelf verification of the packaged executable
# ---------------------------------------------------------------------------
verify_elf() {
  local bin="$1"
  local err=0
  local class type machine interp
  class="$(readelf -h "${bin}" | awk '/Class:/{print $2}')"
  type="$(readelf -h "${bin}" | awk '/Type:/{print $0}')"
  machine="$(readelf -h "${bin}" | awk '/Machine:/{print $2}')"
  interp="$(readelf -p .interp "${bin}" 2>/dev/null | awk -F']' '/linker/{gsub(/^ +/,"",$2); print $2}')"
  # LOAD segment alignments (last column of each LOAD line).
  local maxalign
  maxalign="$(readelf -lW "${bin}" | awk '/^[[:space:]]*LOAD/{print $NF}' | sort -u | tr '\n' ' ')"
  # GNU_STACK flags are the 7th whitespace field on the GNU_STACK line.
  local stack
  stack="$(readelf -lW "${bin}" | awk '/^[[:space:]]*GNU_STACK/{print $7}')"

  [[ "${class}" == "ELF64" ]] || { log "  FAIL class=${class}"; err=1; }
  echo "${type}" | grep -q "Position-Independent Executable" || { log "  FAIL type=${type}"; err=1; }
  [[ "${machine}" == "AArch64" ]] || { log "  FAIL machine=${machine}"; err=1; }
  echo "${interp}" | grep -q "linker64" || { log "  FAIL interp=${interp}"; err=1; }
  # Every LOAD segment must be aligned to 0x4000 (16384) for 16 KB pages.
  local a
  for a in ${maxalign}; do [[ "${a}" == "0x4000" ]] || { log "  FAIL LOAD align=${a}"; err=1; }; done
  # GNU_STACK must be RW (not RWE); RWE means an executable stack.
  [[ "${stack}" == "RW" ]] || { log "  FAIL GNU_STACK=${stack} (execstack!)"; err=1; }
  return ${err}
}

log "readelf verification:"
if verify_elf "${OUT_BIN}"; then
  log "  PASS: ELF64 AArch64 PIE, Android linker64, 16 KB LOAD alignment, noexecstack"
else
  die "readelf verification failed."
fi

# ---------------------------------------------------------------------------
# 8. Install into jniLibs/arm64-v8a
# ---------------------------------------------------------------------------
if [[ "${DO_INSTALL}" -eq 1 ]]; then
  mkdir -p "${JNILIBS_DIR}"
  cp "${OUT_BIN}" "${JNILIBS_DIR}/${OUTPUT_NAME}"
  cp "${OUT_LOADER}" "${JNILIBS_DIR}/${LOADER_OUTPUT_NAME}"
  log "Installed: ${JNILIBS_DIR}/${OUTPUT_NAME}"
  log "  installed sha256: $(sha256sum "${JNILIBS_DIR}/${OUTPUT_NAME}" | awk '{print $1}')"
  log "Installed: ${JNILIBS_DIR}/${LOADER_OUTPUT_NAME}"
  log "  installed sha256: $(sha256sum "${JNILIBS_DIR}/${LOADER_OUTPUT_NAME}" | awk '{print $1}')"
fi

log "Done."
