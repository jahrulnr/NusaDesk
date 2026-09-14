# Known limitations and non-goals

This file is the operational truth for what the current base project does **not** promise.

## Current scaffold limitations

- The repository contains a Java Android status/install shell, a verified Ubuntu Base ARM64 installer, a packaged PRoot launcher, a foreground-service runtime host, an xterm.js WebView terminal, and the guest-native SSH workload path.
- No Linux runtime is bundled; the first Ubuntu Base ARM64 profile can be downloaded and activated on demand.
- **Guest SSH needs the curated add-on:** stock Ubuntu Base ships no `sshd`, no `dropbear`, and no `/etc/ssh`, so the runtime offers a curated OpenSSH add-on payload (ADR-0010) and the workload fails with an explicit "guest SSH payload is not installed" reason until that add-on is activated. Nothing is faked while it is missing.
- **The guest shares the app's real supplementary groups.** PRoot cannot drop
  or change them (that needs `CAP_SETGID`, which an `untrusted_app` does not
  have; PRoot `-0` only fakes the ids `getuid`/`getgid` report), so guest
  processes carry the Android AIDs the app process holds (`3003 inet`,
  `9997 everybody`, the per-app cache/shared gids). The guest setup step names
  them in `/etc/group` as `aid_*` on every session start (ADR-0011), so the
  login no longer prints `groups: cannot find name for group ID …` and guest
  tools resolve the IDs. That is a naming fix, not isolation: the guest still
  shares the app UID/GID and group set, and PRoot remains a compatibility
  layer, not a sandbox.
- On a landscape phone the session card leaves the terminal a short (but usable) height; the terminal keeps focus and the PTY tracks the size, and a follow-up slice should compact the card.
- **Guest SSH uses one fixed loopback port: `127.0.0.1:22022`** (ADR-0013). It
  is a documented product constant, not a discovered value, so the host-key
  pin, the readiness frame, the notification, and the terminal config all name
  the same endpoint. There is no candidate selection and no retry: if the port
  is already held (another app, or a leftover listener from a crashed host
  process), the start ends in a typed `FAILED` naming the conflict, the tracer
  is torn down, and the host never attaches to a listener it did not start. A
  conflicting listener therefore makes the runtime unavailable until it is
  gone; that is deliberate, because the alternative is reporting an endpoint the
  app does not own.
- **The app is not an external SSH client.** Production client configuration
  comes from `LocalSshSessionFactory`, which cannot accept a host or a port and
  refuses any host key the runtime host did not pin. The generic
  `SshClientBridge`/`SshSessionConfig` remain for internal and test use only.
  Every remote-SSH surface (dialog, host profile, add-host form, layouts, and
  strings) was removed in the launcher slice (ADR-0014); no user-reachable path
  can dial a user-entered host.
- No QEMU, PTY layer, or desktop guest server is included; the desktop screen remains honestly unavailable until a real guest-owned server exists.
- No remote signed catalog service exists; the first catalog entry is compile-time pinned.
- No LAN exposure is supported.
- No Google Play compatibility or approval claim is made.

## Product limitations to preserve

### Generic Linux compatibility

A Linux ELF is not automatically an Android executable. Direct Termux/Bionic execution and Linux/glibc execution are different contracts. Even inside a glibc guest, applications can fail when they require unsupported kernel features, namespaces, cgroups, systemd, FUSE, kernel modules, GUI stacks, or architecture-specific native addons.

The supported product unit should be a **curated, tested app profile**, not “any Linux binary”.

### PRoot boundary

PRoot is a compatibility layer, not a hostile-code sandbox. It shares the Android kernel and app UID. It must not be advertised as Docker/VM isolation or used as the only defense against malicious target payloads.

PRoot is the adopted execution bridge (ADR-0007/0008): the packaged `libproot.so` + `libproot-loader.so` pair ran a curated guest `/bin/sh` on an Android 10 arm64 device under `untrusted_app`. A modern/16 KB-page device pass remains open.

### Guest hard links are emulated, not real

Android's SELinux policy for the app domain denies hard-link creation, so a
guest process cannot create a real hard link anywhere in its rootfs
(device-verified: `ln` inside the guest fails with `Permission denied` even in
`/tmp`). `dpkg` creates a backup hard link before every unpack, so without a
workaround no `apt install`/`apt upgrade` can complete. NusaDesk therefore runs
PRoot with `--link2symlink` (ADR-0020), which emulates hard links with symlinks
inside the guest.

Consequence to keep in mind: a guest program that needs a *true* hard link —
comparing inode numbers, or requiring `st_nlink > 1` — will not behave correctly.
Package installation and upgrades are supported; code that depends on real hard
link semantics is not.

### Guest SSH server is not supplied by Ubuntu Base

The SSH-first UX requires a guest SSH server explicitly installed and
configured in a curated payload. Ubuntu Base 24.04.5 ARM64 is a minimal rootfs
and does **not** contain an SSH server, host keys, or a configured loopback
listener. Any design that assumes one is present would ship a non-functional
SSH-first experience. Host keys are generated at first guest start in
app-private storage, never shipped in a public catalog artifact. The adopted
answer is the curated OpenSSH add-on (ADR-0010): pinned upstream `.deb`
artifacts, verified digests, safe extraction, atomic activation, bound into
the guest at `/opt/lw-ssh`.

### Foreground service is not a survival guarantee

A foreground service improves process importance and provides user visibility but cannot guarantee persistence across low memory, OEM battery policy, Doze, force-stop, reboot, or user revocation. The UI must expose `STOPPED`, `FAILED`, and `RECOVERING` states rather than pretending the daemon is always alive. `dataSync` must not be used as an indefinite server type (Android 15 caps it at six hours per 24h); the FGS type is pending and must match the real use case.

### Autostart is app-visible only (ADR-0013)

Linux starts when the user opens the app: an Activity foreground event calls
`RuntimeHostService.ensureRunning`, which starts a session only when none is
live, and the foreground service then keeps the guest running while the app is
backgrounded. What this deliberately does **not** do:

- No boot start, no `BOOT_COMPLETED` receiver, no boot permission, no job,
  alarm, or sticky-restart resurrection. Linux does not exist before the user
  launches the app, and a device reboot leaves it stopped.
- No in-app start/stop control. The ongoing notification and its `Stop` action
  remain because Android requires foreground work to be user-visible and
  stoppable. Tapping `Stop` while the app is already in the foreground leaves
  the runtime stopped until the next foreground event (leaving and reopening the
  app); there is no control that starts it in place.
- An uninstalled or failed runtime is retried once per foreground event, so the
  notification can re-appear with the same honest failure until the missing
  payload (the curated OpenSSH add-on) is installed.
- On Android 12+ a foreground-service start must come from a foreground app
  context; the boundary is only safe from an Activity foreground event, which is
  exactly how it is wired. Background-start and FGS-type restrictions on API
  levels newer than the tested device remain unverified — the app targets API 37
  but has only run on API 29 (see `docs/test-plan.md`).

### Background lifecycle is device- and OEM-specific (observed on Samsung Android 10)

The 2026-09-13 lifecycle run on the single test device (Samsung SM-G935F,
Android 10/API 29) recorded what that OEM/API actually does. None of it is a
general promise:

- Home, 60 s of screen-off `Dozing`, and Activity recreation left the tracer,
  the guest daemon, and the loopback endpoint untouched, with the service still
  `isForeground=true`. This is what a foreground service is expected to give on
  API 29; newer Android releases add background-start and FGS-type restrictions
  that are untested here (the app targets API 37 but has only run on API 29).
- Killing the host app process (package update, `am force-stop`, `am crash`)
  killed the whole process group — tracer and guest daemon included — so no
  orphan survived those paths on this device. The code still reclaims a daemon
  a stale pid file names, because a tracer-only death leaves its tracee alive
  and that path is not reproducible from `adb` on a production build (neither
  `shell` nor `run-as` may signal the app's children). The reclaim path itself
  was exercised on-device: it never signals a pid that is not this daemon, and
  repeated stop/start cycles left no stray process or held port.
- The host service is deliberately not exported, so no external actor can stop
  it. A system-initiated destroy is the only non-UI path, and it now stops the
  runtime instead of leaving an unsupervised guest daemon.
- Whether the endpoint can die while the session stays `RUNNING` (the guest
  daemon master killed while a session child keeps the output pipe open) is
  still unmeasured. A live guest shell is now device-verified on Android 10/API
  29 arm64 (see `docs/test-plan.md`), so the earlier blocker — the Terminal
  app tile crashing before a shell could be opened — no longer applies; the
  specific master-killed/session-child-open scenario has still not been
  exercised on a device.


### Storage

Runtime files belong in internal app-specific storage unless a later design proves an explicit external-storage use case. Shared storage may be mutable by other apps and may be mounted `noexec`.

Uninstalling the Android app removes app-specific runtime and state. A future backup/export feature must be explicit; it must not rely on app uninstall persistence.

### WebView and localhost

Loopback limits reachability but does not provide authentication. Target apps with sensitive APIs must use an application token or a host proxy. WebView must be restricted to the owned origin and must not expose a general-purpose Android bridge to downloaded web content.

### Web-app favicon fallback (ADR-0015)

A launcher tile without a user-picked image asks the app's own endpoint for exactly `http://127.0.0.1:<guestPort>/favicon.ico`. The limitations are deliberate:

- **One path, no discovery.** There is no HTML parsing for `<link rel="icon">`, because that would mean trusting a URL found inside an arbitrary document. A server that publishes its icon only under another path shows a monogram.
- **Whatever the platform decoder accepts.** The payload is decoded with `BitmapFactory`, so a vector or otherwise undecodable "favicon" (for example SVG served at that path) falls back to the monogram.
- **Not persisted.** The decoded image lives in memory for the life of the app process; there is no disk cache, so it is fetched once per app per process.
- **One retry, on a real event.** The launcher's first attempt usually happens before the app's server is running. A missing favicon is retried only when the app's own surface proves the endpoint answers, so an app that is never opened keeps its monogram.
- **Never fetched for an app with a user image.** The user's own pick is primary and is not replaced, and no request is made in that case.
- **Silent by design.** Unreachable, timed out, redirected, too large, or not an image are all the same outcome: the tile keeps its monogram, with no error, toast, or state change.

### Native-code distribution

The future APK will likely need a small native execution bridge even though the Linux rootfs is downloaded. Any JNI/native `.so` must support the device ABI and 16 KB page-size devices. Native files must be reproducibly built, checksum/signature verified, licensed, and tested.

### License and distribution

PRoot is GPL-2.0-or-later. Packaging and distributing it requires GPLv2+ source/notice obligations, and the combined-work implications need legal review before distribution. Invoking PRoot as a separate executable is intended to keep the clearest practical GPL boundary, but **no GPL-cleanliness claim is made**. Dropbear (permissive/MIT-style) and OpenSSH (BSD) are includable in the curated rootfs, but their license/notice/attribution obligations apply. The app's own license is not yet selected.

### Google Play

Downloading and executing a Linux runtime from a non-Play source may trigger Device and Network Abuse or dynamic-code-loading review. PRoot's status as a VM/interpreter exception is not assumed. Start with a controlled distribution channel (sideload/F-Droid) and request policy guidance before committing to Play. No Google Play compliance or approval is claimed.

## Target profile limitations

- The first profile has a pinned Ubuntu Base ARM64 rootfs install proof, a supervised PRoot launch path, and a working guest-native SSH endpoint through the curated OpenSSH add-on (ADR-0010). The desktop/web surface is still unavailable, so only the terminal path is exercised end-to-end.
- A second profile is allowed only after the first profile passes the device test matrix.
- Each profile must document its runtime version, architecture/libc requirements, native dependencies, browser requirements, service assumptions, persistence, and unsupported features.
- No target profile is supported merely because its CLI starts; its web UI, state persistence, process recovery, and security boundary must also pass validation.

## Unsupported future features unless explicitly approved

- Arbitrary user-provided rootfs/image/URL.
- Arbitrary shell command entered into a privileged/native execution API.
- Running systemd or a complete init system.
- Docker/container-in-container behavior inside PRoot.
- Host filesystem-wide mounts.
- Unauthenticated LAN/public binding.
- Automatic battery-optimization exemption.
- Silent self-update of payloads.
- First-launch compilation of native modules or web frontends.
- Claims of full desktop Linux compatibility.
