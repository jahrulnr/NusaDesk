# ADR-0008: Packaged PRoot launch integration (launcher contract + on-device spike findings)

## Status

Accepted and implemented. The launcher/factory contract is implemented and
unit-tested, and on-device PRoot viability spikes were run on an Android 10/API 29
arm64 device. The first spike proved the bridge binary runs and PRoot ptrace
interception works **in the shell domain**. A follow-up app-domain spike
(`docs/research/proot-app-domain-device-spike.md`) resolved blocker 1
(packaging) and proved a curated-rootfs guest command as the app UID in the
`u:r:runas_app:s0` domain. The `untrusted_app` honesty gate subsequently
passed: `ProotLauncher` is wired into the runtime supervisor and a curated
guest `/bin/sh` ran under `u:r:untrusted_app` on the same Android 10/API 29
arm64 device, so `STARTING`/`RUNNING` are honest on that device
(`docs/test-plan.md`, SSH-001).

**Evidence boundary.** Verification is one Android 10/API 29 arm64 device (4 KB
pages) only. Modern Android (13/15/16/17), 16 KB-page devices, and other OEMs
are untested; the `untrusted_app` ptrace/SELinux result on this one device and
kernel does not generalize to other API levels, page sizes, or OEM SELinux
policies.

## Context

ADR-004 packages a standalone PRoot PIE executable as `libproot.so` in the APK
native-library area, and ADR-007 makes PRoot the leading execution bridge for
the SSH-first product. The research spike
(`docs/research/proot-arm64-build-spike.md`) built and ELF-verified the binary
but made **no device-success claim**. This slice implements the concrete launch
integration — a command factory/launcher that turns the curated active rootfs
plus a fixed guest argv into a PRoot `ProcessBuilder` invocation — and runs the
first on-device probe.

The integration is isolated in the infrastructure package
`gh.nusashell.nusadesk.infrastructure.proot`. No manifest, Gradle,
presentation, or pre-existing source files were edited.

## Decision

### Package design (clean-architecture compliant)

- `ProotCommandFactory` (pure Java, no Android): builds the fixed PRoot argv
  array from a `ProotLaunchSpec`. This is the testable builder that requires no
  Android.
- `ProotLaunchSpec` + `Builder` (pure Java): immutable, fully-resolved launch
  specification (proot binary, rootfs, guest workdir, guest argv, bind mounts,
  explicit env, host working dir, `--kill-on-exit`).
- `ProotBindMount` (pure Java): validated host:guest bind mount.
- `ProotPaths` (pure Java): path resolution and string validation (absolute,
  no `..`, no null bytes, app-private confinement).
- `ProotRootfsValidator` (pure Java, `java.nio.file`): validates the active
  rootfs is a directory containing `etc/os-release` and `usr/bin/sh`.
- `ProotLauncher` (Android adapter): the only class that touches Android. It
  resolves `libproot.so` from `Context.applicationInfo.nativeLibraryDir`,
  resolves the curated active rootfs from `Context.getFilesDir()`, validates
  it, builds the spec, and starts the process with `ProcessBuilder` (explicit
  working dir and cleared-then-set explicit env). It returns the existing
  `runtimehost.ProcessHandle` port so a future supervisor integration needs no
  new abstraction.

### Security contract (AGENTS.md)

- Only the curated `ubuntu-base-arm64` app id is accepted; the active rootfs
  path is resolved from that exact id.
- The guest argv is fixed by the caller and appended verbatim; no shell
  interpolation, no URLs.
- Bind mounts are the fixed system set (`/proc`, `/dev`) plus optional
  app-private host paths (e.g. host-key storage). Non-system host paths must be
  under the app's files/cache/data dir or they are rejected. No arbitrary host
  paths.
- The environment is cleared and set explicitly (PATH, HOME, USER, TERM, LANG,
  `PROOT_TMP_DIR` = app cache dir) so no host secrets leak into the guest.
- The launcher supports a fixed guest entrypoint for a future guest sshd/web
  runtime but does **not** invent a guest sshd (Ubuntu Base has none; ADR-007).

### Fixed argv form

```
libproot.so -r <rootfs> -b <host>:<guest> ... -w <guestWorkdir> --kill-on-exit <guestArgv...>
```

**No `--` separator is emitted.** The packaged PRoot build (termux fork
v5.1.107.92) rejects the bare `--` option (`proot error: unknown option '--'`,
verified on device). PRoot's parser treats the first non-option argument as the
guest command and passes the rest through verbatim. The spec builder therefore
rejects guest entrypoints that begin with `-` so PRoot cannot misparse them as
options.

## On-device spike evidence

Device: Samsung SM-G935F (Galaxy S7 edge), Android 10/API 29, arm64-v8a, 4 KB
page size, SELinux `Enforcing`. APK built with `./gradlew assembleDebug` and
installed.

1. **Bridge binary launches.** `libproot.so --version` (run from
   `/data/local/tmp`, shell domain) prints `v5.1.107.92-dirty` with
   `built-in accelerators: process_vm = yes, seccomp_filter = yes`.
2. **PRoot ptrace interception works on this device.** Running a shell under
   PRoot succeeded (shell domain, host rootfs):
   ```
   $ PROOT_TMP_DIR=/data/local/tmp libproot.so -r / -b /proc -b /dev /system/bin/sh /data/local/tmp/guest.sh
   PROOT_GUEST_OK
   Linux localhost 3.18.140-CronosKernel-V8.0-G935X-20231105 ... aarch64
   uid=2000(shell) ... context=u:r:shell:s0
   ```
3. **Exact factory argv form works.** The form produced by
   `ProotCommandFactory` (with `-b host:guest`, `-w`, `--kill-on-exit`, no
   `--`) ran the guest shell and printed `FACTORY_FORM_OK` plus `uname -a`/`id`.
4. **`PROOT_TMP_DIR` is required.** Without it PRoot fails with
   `can't create temporary file` (Android has no `/tmp`). The launcher sets it
   to the app cache dir.
5. **`--` is rejected** by this build (see above); the factory omits it.

## Blockers (status as of the device-evidenced runtime)

1. **Native-library extraction is off (packaging blocker) — RESOLVED.** The
   build did not set `android:extractNativeLibs` or `useLegacyPackaging`, so
   AGP applied its default for `minSdk 29`: `extractNativeLibs=false`, leaving
   `libproot.so` inside the APK (dlopen-only) and `nativeLibraryDir` empty. The
   follow-up spike set `packaging.jniLibs.useLegacyPackaging true` in
   `app/build.gradle` (no manifest edit, no security control disabled); the
   installer now extracts `libproot.so` to `nativeLibraryDir` as
   `-rwxr-xr-x` and the `run-as` app test passes. See
   `docs/research/proot-app-domain-device-spike.md`.
2. **Curated rootfs not installed on the device — ADDRESSED.** The curated
   Ubuntu Base rootfs was later installed by the real `AndroidRuntimeInstaller`
   at `<filesDir>/linux-wrapper/runtimes/ubuntu-base-arm64/active` and verified
   complete (`etc/os-release`, `usr/bin/sh -> dash`). The follow-up spike ran
   the curated-guest `/bin/sh -c 'uname -a; id; test -f /etc/os-release'` and
   confirmed via a discriminating probe (Ubuntu `/etc/os-release`, app-uid-owned
   `/bin/sh -> dash`, guest coreutils `uname`) that the session is rooted in the
   curated rootfs, not the host.
3. **Shell domain ≠ app domain — RESOLVED.** The follow-up spike ran PRoot as
   the app UID in `u:r:runas_app:s0` (not `u:r:shell:s0`) and the guest command
   succeeded. The real-process honesty gate — running `ProotLauncher` from
   inside the app process as `u:r:untrusted_app:s0` — subsequently passed on the
   same Android 10/API 29 arm64 device: the supervisor starts PRoot from the app
   process and a curated guest `/bin/sh` runs under `untrusted_app`
   (`docs/test-plan.md`, SSH-001). `STARTING`/`RUNNING` are therefore honest on
   that device. The boundary that remains open is the matrix: `untrusted_app`
   ptrace/SELinux behavior on other API levels, 16 KB-page devices, and OEMs is
   unproven.
4. **No guest sshd.** Per ADR-007 the curated rootfs must explicitly add and
   configure an SSH server; the launcher only provides the fixed entrypoint
   hook for it. The guest OpenSSH add-on is now shipped and device-verified on
   the same Android 10/API 29 arm64 device (ADR-0009, ADR-0010).

## Consequences

- Positive: the launch contract is concrete, isolated, and unit-tested (47
  tests; full suite 225 tests, 0 failures). The factory's argv is verified
  against the real device's PRoot option syntax.
- Positive: the on-device spike de-risked the highest ADR-007 unknown — PRoot
  ptrace interception works on an Android 10 arm64 kernel — and found the
  `--`/`PROOT_TMP_DIR` requirements before any supervisor wiring.
- Positive: the `untrusted_app` honesty gate has passed on the Android 10/API 29
  arm64 device; `ProotLauncher` is wired into the supervisor and `STARTING`/
  `RUNNING` are honest on that device.
- Open: the matrix is one Android 10/API 29 arm64 device (4 KB pages) only.
  `untrusted_app` ptrace/SELinux behavior on modern Android, 16 KB-page devices,
  and other OEMs is unproven; the `runas_app` and `untrusted_app` successes on
  this one device do not generalize to the rest of the matrix.
- Follow-up: (a) compatibility matrix — repeat on Android 13/15/16/17, 16 KB-page,
  and a non-Samsung OEM; (b) keep the curated rootfs guest sshd and loopback bind
  in sync with ADR-0009/ADR-0010/ADR-0013; (c) maintain the `RuntimeSupervisor`
  readiness handshake against the fixed `127.0.0.1:22022` endpoint.
