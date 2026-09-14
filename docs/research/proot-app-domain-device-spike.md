# App-domain PRoot guest execution: on-device spike

Research/spike note: 13 September 2026. This is a factual record of the
on-device spike that resolves ADR-0008 blocker 1 (native-library packaging)
and proves — or, had it failed, would have refuted — an **app-domain** PRoot
guest command against the curated Ubuntu Base rootfs on the attached Android
device. It records exact commands, exact output, the SELinux domain used, the
packaging fix, and the honest scope of what is and is not proven.

Unlike the prior shell-domain probe (ADR-0008, `u:r:shell:s0` in
`/data/local/tmp`), this spike runs PRoot as the **app UID** through
`adb shell run-as com.linuxwrapper.android`, in the `u:r:runas_app:s0` SELinux
domain, against the curated rootfs installed under the app's private files dir.

> **Historical note (pre-rename).** This spike was recorded on 13 September 2026
> under the application id `com.linuxwrapper.android`. The product has since been
> renamed to `gh.nusashell.nusadesk` (ADR-0018); the command/output blocks below
> are preserved verbatim with the old id because they are the factual record of
> what was run. To reproduce against the current build, substitute
> `gh.nusashell.nusadesk` for `com.linuxwrapper.android` in every `run-as` and
> `pm path` command (see "Reproduce").

## Scope and boundary

In scope: (1) the Gradle packaging change that makes `libproot.so` extract into
`ApplicationInfo.nativeLibraryDir` and remain executable on `minSdk 29`;
(2) on-device verification that the bridge is extracted/executable in the app
domain; (3) execution of the curated rootfs with the fixed argv
`/bin/sh -c 'uname -a; id; test -f /etc/os-release'`, equivalent to
`ProotLauncher.GUEST_PROBE_ARGV` extended with `id` and the `os-release` check.

Out of scope (unchanged from ADR-0008): the process supervisor, foreground
service, readiness handshake, loopback runtime, guest sshd, and any
`STARTING`/`RUNNING` state wiring. No `STARTING`/`RUNNING` state is honest on the
strength of this spike alone (see "What is not proven").

Files touched: `app/build.gradle` (packaging) and this note. No manifest, `res/`,
Java source, or other ADRs were edited. No new unit tests were added — the
change is a Gradle packaging configuration with no pure-Java logic to unit-test;
the device spike is the verification.

## Device

| Field | Value |
| --- | --- |
| Model | Samsung SM-G935F (Galaxy S7 edge), `crownlte`/`crownltexx` |
| Android release / API | 10 / 29 (`minSdk` floor) |
| ABI | `arm64-v8a` |
| Kernel | `3.18.140-CronosKernel-V8.0-G935X-20231105` (aarch64, 4 KB page) |
| Security patch | 2023-02-01 |
| SELinux | `Enforcing` |
| `adb` host | 1.0.41 (platform-tools 34.0.4) |

## Build configuration change (ADR-0008 blocker 1 resolved)

AGP's default for `minSdk 29` is `extractNativeLibs=false`: native libs stay
uncompressed inside the APK and are loaded via `dlopen`, so
`ApplicationInfo.nativeLibraryDir` is empty and the host cannot `execve` the
bridge. ADR-0008 blocker 1 documented this.

The fix in `app/build.gradle`:

```groovy
packaging {
    jniLibs {
        useLegacyPackaging true
    }
    resources { /* existing META-INF excludes unchanged */ }
}
```

`useLegacyPackaging true` makes the package installer extract native libs from
the APK into `nativeLibraryDir` at install time and `chmod` them executable —
exactly the ADR-004 contract. It is a packaging/extraction option, **not a
security control**: the libs still come from the signed APK and land in the
app-private read-only lib dir. It does not alter `networkSecurityConfig`,
cleartext, backup, or any permission, and the manifest's
`android:extractNativeLibs` was not touched (AGP injects the merged value).

Verification of the effect:

```
$ grep -o 'extractNativeLibs="[^"]*"' \
    app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
extractNativeLibs="true"

$ unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libproot
   293544  1981-01-01 01:01   lib/arm64-v8a/libproot.so
```

## Build verification (exact commands and results)

```
$ ./gradlew test
... BUILD SUCCESSFUL in 4s   (22 actionable tasks: 6 executed, 16 up-to-date)

$ ./gradlew lintDebug
... BUILD SUCCESSFUL in 3s   (27 actionable tasks: 8 executed, 19 up-to-date)
    (lint { abortOnError true; warningsAsErrors true } — no ExtractNativeLibs
     or other warning fired; the packaging change introduced no lint regression)

$ ./gradlew assembleDebug
... BUILD SUCCESSFUL in 1s   (35 actionable tasks: 1 executed, 34 up-to-date)
```

Install:

```
$ adb install -r app/build/outputs/apk/debug/app-debug.apk
Performing Streamed Install
Success
```

## App-domain bridge extraction (ADR-0008 blocker 1, on device)

```
$ adb shell run-as com.linuxwrapper.android ls -l \
    /data/app/com.linuxwrapper.android-wP_VhXdLy1n787fJLTIIxw==/lib/arm64/
total 288
-rwxr-xr-x 1 system system 293544 1981-01-01 01:01 libproot.so

$ adb shell run-as com.linuxwrapper.android file \
    /data/app/com.linuxwrapper.android-wP_VhXdLy1n787fJLTIIxw==/lib/arm64/libproot.so
.../libproot.so: ELF shared object, 64-bit LSB arm64, dynamic (/system/bin/linker64)
```

`libproot.so` is now present in `nativeLibraryDir` with `-rwxr-xr-x`
(executable), 293544 bytes (matching the reproducible build hash in
`proot-arm64-build-spike.md`), as an arm64 PIE. The blocker is resolved.

## SELinux domain of the test vehicle (honesty)

`adb shell run-as com.linuxwrapper.android` runs commands as the app UID in the
`u:r:runas_app:s0` SELinux domain — **not** the prior `u:r:shell:s0`
(`/data/local/tmp`) domain, and **not** the real app process's
`u:r:untrusted_app:s0` domain:

```
$ adb shell run-as com.linuxwrapper.android id
uid=10279(u0_a279) gid=10279(u0_a279) groups=... context=u:r:runas_app:s0:c23,c257,c512,c768

$ adb shell run-as com.linuxwrapper.android cat /proc/self/attr/current
u:r:runas_app:s0:c23,c257,c512,c768
```

This is the central honesty caveat: `runas_app` is a debug, app-UID domain. It
is necessary-but-not-sufficient evidence for the real app process, which runs
as `u:r:untrusted_app:s0` from zygote. `untrusted_app` may restrict
ptrace/`process_vm` differently than `runas_app`; PRoot relies on ptrace. See
"What is not proven".

## Curated rootfs provisioning

The curated Ubuntu Base 24.04.5 arm64 rootfs was already present at
`<filesDir>/linux-wrapper/runtimes/ubuntu-base-arm64/active` (timestamped
15:02, installed by the real `AndroidRuntimeInstaller` in a prior session —
the project memory index records the install proof as done on device). It was
not re-provisioned for this spike; it is the genuine curated payload.

To rule out a stale/corrupt rootfs, the curated tarball was independently
re-downloaded and its sha256 verified to match the catalog
(`a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2`), and the
on-device rootfs was validated against `ProotRootfsValidator`'s contract:

```
$ adb shell run-as com.linuxwrapper.android ls -l \
    files/linux-wrapper/runtimes/ubuntu-base-arm64/active/etc/os-release \
    files/linux-wrapper/runtimes/ubuntu-base-arm64/active/usr/bin/sh
.../etc/os-release -> ../usr/lib/os-release        (symlink)
.../usr/bin/sh -> dash                              (symlink)
.../usr/lib/os-release : -rw-r--r-- 400 bytes, "Ubuntu 24.04.5 LTS"
.../usr/bin/dash      : ELF arm64, dynamic (/lib/ld-linux-aarch64.so.1)
```

`Files.isRegularFile` (used by the validator) follows symlinks, so both
required files pass. The rootfs is complete.

## App-domain guest execution (the proof)

The probe replicates `ProotLauncher.buildSpec` + `ProotCommandFactory.buildArgv`
exactly:

```
libproot.so -r <rootfs> -b /proc:/proc -b /dev:/dev -w /root --kill-on-exit /bin/sh -c 'uname -a; id; test -f /etc/os-release'
```

with the environment cleared then set explicitly (`PATH`, `HOME=/root`,
`USER=root`, `LOGNAME=root`, `TERM=linux`, `LANG=C.UTF-8`,
`PROOT_TMP_DIR=<cacheDir>`), matching `ProotLauncher.launchSpec`
(`ProcessBuilder.environment().clear()` then `putAll`).

Implementation note: the manual probe cannot use `env -i ... "$PROOT" ...`
because the APK install path contains `=` (base64 padding,
`...wP_VhXdLy1n787fJLTIIxw==`), and toybox `env` misparses a command path
containing `=` as a `VAR=value` assignment (it then tries to `exec` the next
arg, e.g. `-r`/`--version`, and fails with `env: exec -r: No such file or
directory`). toybox `env` also treats `--` as the utility, not an end-of-options
marker. `ProotLauncher` uses `ProcessBuilder` and is unaffected. The probe
therefore clears the environment with `unset` and runs `"$PROOT"` directly as a
command (the shell does not misparse `=` in a command path). The probe script
was pushed to `/data/local/tmp` and executed via
`adb shell run-as com.linuxwrapper.android sh /data/local/tmp/proot-probe.sh`
to avoid multi-layer adb/run-as shell quoting (chained `&&` inside
`sh -c '...'` is parsed by the outer device shell, not run-as's `-c`).

Result (app domain, `u:r:runas_app:s0`, uid 10279):

```
=== libproot.so --version (app domain, cleared env) ===
 _____ _____              ___
|  __ \  __ \_____  _____|   |_
|   __/     /  _  \/  _  \    _|
|__|  |__|__\_____/\_____/\____| v5.1.107.92-dirty
built-in accelerators: process_vm = yes, seccomp_filter = yes
...
version_exit=0

=== guest run: /bin/sh -c 'uname -a; id; test -f /etc/os-release' ===
Linux localhost 3.18.140-CronosKernel-V8.0-G935X-20231105 #1 SMP PREEMPT ... aarch64 aarch64 aarch64 GNU/Linux
uid=10279 gid=10279 groups=10279,1004,1007,1011,1015,1028,3001,3002,3003,3006,3009,3011,50279
guest_exit=0
```

`guest_exit=0` is PRoot's exit code, which reflects the guest `sh` exit code —
the last guest command `test -f /etc/os-release` succeeded. `uname -a` prints
the **host** kernel (PRoot shares the Android kernel; expected). `id` prints
the **app** UID 10279 (PRoot is a compatibility layer, not a UID sandbox;
expected per AGENTS.md "PRoot boundary").

## Proof the guest is the curated rootfs (not the host)

A discriminating probe contrasts host (no PRoot) vs guest (under PRoot `-r
<curated rootfs>`):

```
=== HOST (app domain, no PRoot) ===
host /etc/os-release: ls: /etc/os-release: No such file or directory
host /bin/sh: -rwxr-xr-x root shell 303672 bytes   (this ROM ships a /bin/sh)

=== GUEST (under PRoot -r curated rootfs) ===
--- /etc/os-release ---
PRETTY_NAME="Ubuntu 24.04.5 LTS"
NAME="Ubuntu"
VERSION_ID="24.04"
ID=ubuntu
ID_LIKE=debian
...
--- /bin/sh ---
lrwxrwxrwx 1 10279 10279 4 ... /bin/sh -> dash
readlink -f /bin/sh -> /usr/bin/dash
--- which uname ---
/usr/bin/uname            (guest coreutils, not host toybox)
/bin/uname: -rwxr-xr-x 10279 10279 67944 bytes
--- /usr/bin/dash ---
-rwxr-xr-x 10279 10279 133608 bytes
--- root dir listing ---
bin boot dev etc home lib media mnt opt proc root run sbin srv sys tmp usr var
guest_exit=0
```

The host has **no** `/etc/os-release`; the guest's `/etc/os-release` is the full
Ubuntu 24.04.5 content. The guest `/bin/sh -> dash` is owned by the app UID
(10279, extracted from the curated tarball) and resolves to `/usr/bin/dash`
(133608 bytes), distinct from the host's `root:shell` `/bin/sh`. The guest
`uname` is `/usr/bin/uname` (coreutils), not the host's toybox. This is
airtight: the PRoot session is rooted in the curated Ubuntu rootfs, in the app
domain.

## What is proven

1. **ADR-0008 blocker 1 (packaging) is resolved.** With
   `packaging.jniLibs.useLegacyPackaging true`, `libproot.so` is extracted into
   `ApplicationInfo.nativeLibraryDir` and remains separately executable
   (`-rwxr-xr-x`) on `minSdk 29`, without disabling any security control or
   editing the manifest.
2. **The bridge runs in the app domain.** `libproot.so --version` succeeds as
   uid 10279 in `u:r:runas_app:s0` with `process_vm`/`seccomp_filter`
   accelerators enabled.
3. **PRoot ptrace interception works in the app-UID `runas_app` domain.** The
   guest `/bin/sh -c 'uname -a; id; test -f /etc/os-release'` ran under PRoot
   against the curated rootfs and exited 0.
4. **The curated rootfs is the guest.** Discriminating evidence (Ubuntu
   `/etc/os-release`, app-uid-owned `/bin/sh -> dash`, guest coreutils `uname`)
   confirms the session is rooted in the curated Ubuntu 24.04.5 rootfs, not the
   host.

## What is NOT proven (honest limitations)

1. **`runas_app` is not `untrusted_app`.** The real app process (from zygote)
   runs as `u:r:untrusted_app:s0`; this spike ran as `u:r:runas_app:s0`.
   `untrusted_app` may restrict ptrace/`process_vm_readv`/`/proc/<pid>/mem`
   differently. A PRoot success in `runas_app` does **not** guarantee success in
   the real app process. Proving the real app process requires running
   `ProotLauncher` from inside the app (via the supervisor or an
   instrumentation test in the app process), which is out of scope for this
   spike: there is no UI hook to launch PRoot, and source/manifest could not be
   edited. This is the single most important follow-up.
2. **PRoot is not a security sandbox.** The guest runs as the app UID (10279);
   `id` inside the guest returns the app UID. This is by design (AGENTS.md
   "PRoot boundary") and is not isolation.
3. **No supervisor/readiness/loopback.** This proves a one-shot guest command,
   not a supervised runtime, foreground service, readiness handshake, or
   loopback WebView session (ADR-0003/ADR-0006). No `STARTING`/`RUNNING` state
   is honest yet.
4. **Single device, single Android level.** Samsung SM-G935F, Android 10/API
   29, 4 KB page, one OEM. Android 13/15/16, 16 KB-page, and other OEM SELinux
   policies are untested (see `.agents/skills/android-compatibility-testing`).
5. **No guest sshd.** Per ADR-007 the curated rootfs still needs an explicit
   guest SSH server; this spike only ran `/bin/sh`.

## Reproduce

```bash
cd /media/jahrulnr/storage/workspace/LinuxWrapperAndroidBase
./gradlew test lintDebug assembleDebug        # all BUILD SUCCESSFUL
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Bridge extraction (app domain) — use the current applicationId
# gh.nusashell.nusadesk (the spike was recorded under the pre-rename id
# com.linuxwrapper.android; see the historical note at the top):
adb shell run-as gh.nusashell.nusadesk ls -l \
    "$(adb shell pm path gh.nusashell.nusadesk | sed 's/^package://;s/\/base.apk$//')/lib/arm64/"
# Guest run (push a probe script that clears env and runs "$PROOT" directly;
# see "App-domain guest execution" for why env -i cannot be used with this
# APK install path):
adb shell run-as gh.nusashell.nusadesk sh /data/local/tmp/proot-probe.sh
```

The curated rootfs must be present at
`<filesDir>/linux-wrapper/runtimes/ubuntu-base-arm64/active` (installed by the
runtime installer, triggered from the app's install orb).

## Follow-ups

> **Status update (current evidence).** Follow-ups 1, 2, 4, and 5 are resolved by
> later device evidence recorded in `docs/test-plan.md` and reflected in
> ADR-0007/ADR-0008/ADR-0009/ADR-0010/ADR-0013. The historical text below is
> preserved as the spike's original to-do list; the open limit is follow-up 3
> (the compatibility matrix), which this spike could not cover.

1. **`untrusted_app` spike — RESOLVED.** Run `ProotLauncher.launch(...)` from
   within the real app process (supervisor or instrumentation) and capture the
   SELinux domain (`u:r:untrusted_app:s0`) and ptrace behavior. This was the
   gate for any `STARTING`/`RUNNING` claim. **Done on the same Android 10/API 29
   arm64 device**: the supervisor starts PRoot from the app process and a
   curated guest `/bin/sh` runs under `untrusted_app` (`docs/test-plan.md`,
   SSH-001; ADR-0008 status updated).
2. **Wire `ProotLauncher` into `RuntimeSupervisor` — RESOLVED.** With the
   readiness handshake and loopback port. The runtime now uses the fixed
   `127.0.0.1:22022` endpoint (ADR-0013, amending ADR-0003's ephemeral-port
   text).
3. **Compatibility matrix — OPEN.** Repeat on Android 13/15/16, 16 KB-page, and
   a non-Samsung OEM. This spike covered one Android 10/API 29 arm64 device (4
   KB pages) only; `untrusted_app` ptrace/SELinux behavior on other API levels,
   page sizes, and OEMs is unproven.
4. **Guest sshd — RESOLVED.** Build the curated rootfs with a guest SSH server
   and loopback bind per ADR-007. The curated OpenSSH add-on is shipped and
   device-verified on the same device (ADR-0009, ADR-0010).
5. **ADR-0008 status update — RESOLVED.** Blocker 1 is resolved; blockers 2 and
   3 are addressed (rootfs present; app-domain run proven in `runas_app`), and
   the `untrusted_app` gate has since passed. ADR-0008's status has moved from
   "Proposed" to "Accepted and implemented" (device-evidenced on Android 10/API
   29 arm64; the matrix remains open).
