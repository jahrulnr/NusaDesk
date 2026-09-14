# PRoot native bridge: arm64-v8a packaging/build spike

Research/build note: 13 September 2026. This is a factual record of the
native PRoot execution-bridge build spike scoped by ADR-004. It records
provenance, the build process, patches, reproducibility evidence, license
obligations, and unresolved blockers. It makes **no device-success claim**;
on-device execution is not yet proven (no `adb` evidence).

## Scope and boundary

This spike packages a standalone PRoot executable for Android `arm64-v8a` as
`app/src/main/jniLibs/arm64-v8a/libproot.so`. Per ADR-004, the bridge is the
smallest necessary Android-native execution loader; the Linux rootfs and
target app remain downloaded, verified, out-of-band data (ADR-006).

PRoot is a **compatibility layer, not a security sandbox** (AGENTS.md
"PRoot boundary"). It shares the Android kernel and app UID. Shipping this
binary does not make `STARTING`/`RUNNING` honest; a real device spike for the
readiness handshake, foreground-service supervision, and loopback runtime
(ADR-003, ADR-006) is still required before any process is started.

No Java, Gradle, manifest, or `res/` changes were made. Only
`app/src/main/jniLibs/arm64-v8a/`, `app/src/main/jni/`, `scripts/`, and this
note were touched.

## Why `libproot.so` (an executable named like a library)

Android's package installer extracts native libraries from the APK `lib/<abi>/`
area into the app's native-library directory, which is **read-only and
executable**. This is the only standard Android contract that lets an app
execute a bundled native program on Android 10+ (target SDK 29+), where direct
`execve()` of files in writable app data is restricted (ADR-004; Android 10
behavior changes). PRoot is therefore built as a PIE executable and renamed
`libproot.so` so the installer treats it as a native library and chmods it
executable. It is **not** a JNI library and is not loaded with
`System.loadLibrary`; the Android host will `execve` it with a fixed argv
(ADR-004, AGENTS.md "no arbitrary shell strings"). A short pointer lives at
`app/src/main/jni/README.md`.

### Packaged outputs: `libproot.so` and `libproot-loader.so`

The build produces **two** packaged outputs in
`app/src/main/jniLibs/arm64-v8a/`, both renamed `.so` so the package installer
extracts them into the read-only, executable `nativeLibraryDir`:

- **`libproot.so`** — the PRoot PIE executable (exec'd by the app with a fixed
  argv). This is the output the rest of this note originally described.
- **`libproot-loader.so`** — PRoot's freestanding ELF loader, shipped
  separately. PRoot needs this loader to start tracees; by default it extracts
  an embedded copy into `PROOT_TMP_DIR` and re-executes it, but under
  `u:r:untrusted_app` on `targetSdk` 29+ that extracted copy is labeled
  `app_data_file` and its exec is denied by SELinux (`execute_no_trans`).
  Packaging the loader as a native library puts it in `apk_data_file`, which
  the app domain may execute. PRoot picks it up through the **`PROOT_LOADER`
  environment variable**, which `ProotLauncher` sets to the resolved
  `nativeLibraryDir/libproot-loader.so` path (`ProotPaths.PROOT_LOADER_BINARY_NAME`,
  `ProotLauncher.ENV_PROOT_LOADER`).

> **Historical scope of this note.** This spike was written when only
  `libproot.so` existed and the loader was extracted at runtime; the
  `libproot-loader.so` output and the `PROOT_LOADER` path were added later to
  make the bridge executable under `untrusted_app`. The build script
  (`scripts/build-proot-arm64.sh`) now builds, pins (`EXPECTED_LOADER_SHA256`),
  and installs both outputs. The historical observations below (build process,
  reproducibility, readelf) refer to `libproot.so` unless stated; they are not
  rewritten.

## Source provenance

All third-party **source** is cloned/downloaded into `/tmp` (never the
workspace); only the verified stripped build output enters the repo.

| Component | Source | Pinned version | Exact revision | License |
| --- | --- | --- | --- | --- |
| PRoot | `https://github.com/termux/proot.git` | tag `v5.1.107.92` | `7266fb3e8516535682f5a9c8f3a7e70f6506eddb` | GPL-2.0-or-later |
| Talloc | `https://www.samba.org/ftp/talloc/talloc-2.4.2.tar.gz` | 2.4.2 (sha256 `85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6`) | release tarball | LGPL-3.0-or-later |
| NDK | Android NDK r28c | `28.2.13676358` | clang 19 | Android SDK License |

PRoot selection: `proot-me/proot` is the upstream (GPL-2.0-or-later). The
`termux/proot` fork is a **compatible GPL-2.0-or-later fork** of upstream
carrying the Android ptrace/`/proc`/ashmem patches required to build and run
on Android/Bionic. No AGPL-licensed fork is used. The exact upstream revision
and fork relationship are recorded above and pinned in the build script.

Talloc is PRoot's only mandatory native dependency (memory allocator).
`libandroid-shmem` (the optional Termux SysV-IPC shim) is **not** built or
linked; this binary targets an arm64 guest and does not need SysV shm for the
bridge itself.

## Build environment

- Host: x86_64 Linux, OpenJDK 17, `git`, `curl`, `make`, `mawk` (no `gawk`).
- NDK r28c (`28.2.13676358`) installed via `sdkmanager` into
  `/tmp/linux-wrapper-android-sdk`. Toolchain clang 19,
  `aarch64-linux-android29-clang` (API 29 = `minSdk`, Android 10 floor).
- No `qemu-user` available (no passwordless `sudo`), so talloc's `waf`
  cross-configure (which needs `--cross-execute`/`--cross-answers`) could not
  be used; see "Talloc build" below.

## Patches applied (recorded origin)

Both patches are local build-compatibility fixes. They are re-applied
idempotently by `scripts/build-proot-arm64.sh`. Neither changes PRoot
behavior; both are required for an NDK/Bionic/mawk build.

### Patch A — `src/extension/ashmem_memfd/ashmem_memfd.c`: add `<string.h>`

The file uses `strcmp`/`memset` without including `<string.h>`. glibc pulls
`<string.h>` transitively, so this is latent upstream; Bionic does not, so the
NDK clang rejects it as an implicit-function-declaration error. Fix: insert
`#include <string.h>` after `#include <stdlib.h>`. Not present in upstream
`termux/proot` at this revision; local fix.

### Patch B — `src/loader/loader-info.awk`: mawk compatibility

The awk script uses `strtonum()` (gawk-only) and `\y` word boundaries (gawk
regex) to derive `offset_to_pokedata_workaround` from `readelf -s loader/loader`.
The build host has only `mawk`. Replaced with a manual hex parser and a
`$NF`-based symbol match. The generated constant is verified correct:
`offset_to_pokedata_workaround=1016` (`0x3f8`), matching the loader symbol
offset (`pokedata_workaround` at `0x20000003f8` minus `_start` at
`0x2000000000`). Local fix.

## Build process (reproduced by `scripts/build-proot-arm64.sh`)

1. **Talloc (static).** `talloc.c` includes Samba's `replace.h`, whose `waf`
   cross-configure needs qemu/gawk (unavailable). Bionic already provides
   every libc symbol `talloc.c` uses (`malloc/free/realloc/memcpy/strdup/...`),
   so a minimal `replace.h` shim (standard headers + `MIN`/`discard_const`/
   `__location__` macros) is supplied instead. Compiled with
   `-fPIC -O2 -DNO_CONFIG_H -DHAVE_GETAUXVAL -DHAVE_VA_COPY
   -DTALLOC_BUILD_VERSION_*=2/4/2` into a single `talloc.o`, archived as
   `libtalloc.a` (67 exported symbols).
2. **PRoot.** Built in-tree with PRoot's `src/GNUmakefile`. Because the
   makefile uses `+=` on `CPPFLAGS`/`CFLAGS`/`LDFLAGS`, they are passed via the
   **environment** (command-line overrides would discard the makefile's
   `-D_GNU_SOURCE` and `-ltalloc`). Flags:
   - `CPPFLAGS`: `-DVERSION="5.1.107.92" -DARG_MAX=131072` + talloc include dirs.
   - `CFLAGS`: `-fPIE -O2 -fstack-protector-strong`.
   - `LDFLAGS`: `-L<talloc> -pie -Wl,-z,max-page-size=16384 -Wl,--threads=1`.
   - `STRIP/OBJCOPY/OBJDUMP` = NDK `llvm-*` tools.
   The 64-bit loader and the 32-bit ARM loader (the NDK aarch64 clang supports
   `-m32` and emits a valid ELF32 ARM object) are both built and bundled into
   `proot` via `objcopy`. `libandroid-shmem` is intentionally not linked.
3. **Strip.** `llvm-strip --strip-all` produces the final 293,544-byte binary,
   renamed `libproot.so`.

## Reproducibility evidence

A default parallel `ld.lld` link is **not** bit-identical: the `.rela.dyn`
relocation addends vary across runs because ld.lld's `SHF_MERGE` string
deduplication ordering depends on parallel processing. All input `.o` files
and the loader binary are themselves deterministic; only the final link
varies.

Forcing single-threaded linking with `-Wl,--threads=1` makes the output
**bit-identical** across clean rebuilds. Verified by
`scripts/build-proot-arm64.sh --repro-check` (two full clean builds, `cmp`):

```
Stripped sha256: 6577444428cd0a0ddd4dd45a56af1bb49622986f52700e33a9bfdbb681e64046
Reproducibility check: rebuilding and comparing...
  PASS: bit-identical across clean rebuilds (6577444428cd0a0ddd4dd45a56af1bb49622986f52700e33a9bfdbb681e64046)
```

The pinned expected hash is recorded in the build script and checked on
every run.

## readelf verification (local; not a device test)

```
Class:    ELF64
Type:     DYN (Position-Independent Executable file)
Machine:  AArch64
.interp:  /system/bin/linker64          (Android dynamic linker)
NEEDED:   libdl.so, libc.so            (Bionic; no talloc dep — statically linked)
LOAD alignments: 0x4000 0x4000 0x4000 0x4000   (16 KB page-size compatible)
GNU_STACK: RW                                  (no executable stack)
file: ELF 64-bit LSB pie executable, ARM aarch64, dynamically linked, stripped
```

All four `LOAD` segments are aligned to `0x4000` (16384), satisfying the
Android 15+ 16 KB page-size requirement. `GNU_STACK` is `RW` (no `E`), so the
stack is non-executable. The binary dynamically links only Bionic
(`libc.so`/`libdl.so`); talloc is statically linked, so no extra `.so` is
needed at runtime.

This is a **local ELF/static verification only**. It does not prove the
binary runs on a device, that ptrace-based syscall interception works under
the target device's SELinux policy, or that a guest rootfs boots.

## License obligations

`libproot.so` is a derivative work of:

- **PRoot — GPL-2.0-or-later.** Distribution of the binary requires offering
  the corresponding source. The exact source revision
  (`7266fb3e8516535682f5a9c8f3a7e70f6506eddb`, tag `v5.1.107.92`) and the
  build script (`scripts/build-proot-arm64.sh`) reproduce it.
- **Talloc — LGPL-3.0-or-later, statically linked.** LGPL-3.0 sections 4-6
  require that a user can re-link against a modified talloc. This is satisfied
  by publishing the exact talloc source (pinned tarball + sha256 above) and
  the build script, which builds `libtalloc.a` from source and links it.

GPL-2.0-or-later permits upgrading to GPLv3; GPLv3 is compatible with
LGPL-3.0, so the combined work is distributable under GPL-2.0+ (or GPLv3).
The two local patches (A, B) are trivial compatibility fixes and carry no
additional licensing; they are part of the corresponding source the script
reconstructs.

Before any public distribution, the GPL/LGPL notices and the offer of source
must accompany the binary (e.g., in the app's "about"/legal screen and the
release artifacts). This spike does not add that UI; it is a build/packaging
proof only.

## What this binary is and is not

- **Is:** a standalone, reproducibly-built, 16 KB-page-compatible AArch64 PIE
  PRoot executable, packaged as `libproot.so` for Android's native-library
  extraction contract. It is the execution bridge/loader selected by ADR-004.
- **Is not:** a JNI library, a security sandbox, a VM, a process supervisor,
  a foreground service, or a running runtime. It does not, by itself, make
  any `STARTING`/`RUNNING` state honest.
- **Is not proven on device.** No `adb`/device execution evidence exists.

> **Later evidence (not part of this spike).** The "no device proof" limitation
> above was true when this build spike was recorded (13 September 2026). It has
> since been superseded for one target: ADR-0008 and `docs/test-plan.md` record
> that `libproot.so` is extracted executable, PRoot ptrace interception works
> under the device SELinux policy, and a curated arm64 rootfs enters a PRoot
> session — on one Android 10/API 29 arm64 device (4 KB pages), under
> `u:r:untrusted_app`. The 16 KB-page and modern-API/OEM cases remain open. This
> historical note does not rewrite the spike's own observations above.

## Unresolved blockers / next steps

> **Status update (current evidence).** Blockers 1 and 3 are resolved for the
> Android 10/API 29 arm64 target by later device evidence (ADR-0008,
> `docs/test-plan.md`); blockers 2, 4, and 5 remain open. The historical text
> is preserved below.

1. **No device proof — RESOLVED for one device.** The binary was verified by
   `readelf` only at spike time. On-device execution has since been confirmed on
   one Android 10/API 29 arm64 device: `libproot.so` is extracted executable,
   PRoot ptrace interception works under the device SELinux/domain policy, and
   a curated arm64 rootfs enters a PRoot session under `u:r:untrusted_app`
   (ADR-0008, `docs/test-plan.md` SSH-001). Per AGENTS.md, a unit-test-only
   result remains insufficient for lifecycle/execution behavior; the device
   evidence is what closes this blocker for that one target.
2. **16 KB page device test.** Alignment is verified in the ELF; runtime
   behavior on a real 16 KB-page Android 15+ device is not yet tested.
3. **Execution contract — RESOLVED for the shipped runtime.** A fixed argv
   array, readiness handshake, loopback port, and supervisor are now built and
   device-verified on Android 10/API 29 arm64. The loopback port is the fixed
   `127.0.0.1:22022` endpoint (ADR-0013, amending ADR-0003's ephemeral-port
   text), not an ephemeral value.
4. **`libandroid-shmem`** is intentionally omitted. If a future target
   profile needs SysV IPC shm inside the guest, it must be added as a
   separate, reviewed dependency with its own license note.
5. **Distribution review.** Google Play's Device and Network Abuse policy and
   the GPL/LGPL notice/source-offer obligations need a separate review before
   any public release; sideload/F-Droid is the initial channel per
   `docs/research-findings.md`.

## How to reproduce

```bash
# Prerequisite: Android cmdline-tools installed at /tmp/linux-wrapper-android-sdk
# (the script installs the NDK itself via sdkmanager).
cd /media/jahrulnr/storage/workspace/LinuxWrapperAndroidBase
scripts/build-proot-arm64.sh            # build + verify + install into jniLibs
scripts/build-proot-arm64.sh --repro-check  # also prove bit-identical rebuilds
```

The script clones PRoot and downloads talloc into `/tmp` (not the workspace),
applies the two recorded patches, builds static talloc, builds PRoot with
`--threads=1`, strips, verifies with `readelf`, and copies the result to
`app/src/main/jniLibs/arm64-v8a/libproot.so`.
