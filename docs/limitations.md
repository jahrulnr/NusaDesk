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
- **The terminal session is process/session scoped, not immortal** (ADR-0033).
  The SSH session is owned by the foreground service rather than the terminal
  view, so Activity destruction no longer closes it and a dropped shell can be
  re-attached from the notification. It still ends when the app process dies
  (OEM kill, force stop, memory pressure), when the runtime stops or fails,
  when the guest `sshd` stops answering (the 30 s client heartbeat gives up
  after roughly 5.5 unanswered minutes), or when the user stops Linux. There is
  no infinite-lifetime guarantee and no silent re-attach: a dropped/failed
  shell requires the explicit Reconnect action. Scrollback is not restored
  across surface recreation — the shell continues, the xterm page starts fresh.
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

### udocker `compose` is a bounded adapter, not Docker (ADR-0025)

The guest ships a product-owned `udocker compose` subset on pinned udocker
1.3.17 source. Its limits are contract, not polish:

- **Subset only.** Per-service keys are `image`, `command`, `entrypoint`,
  `environment`, `volumes`, `working_dir`, `depends_on` (short
  list), and `restart` (`no`/`always`/`unless-stopped`); `ports` still
  parses into the model but is refused at `up` (see below). `build`,
  `networks`, `healthcheck`, `deploy`, `secrets`, `configs`, long-form
  `depends_on`, `on-failure`, named/anonymous volumes, container-only
  ports, YAML anchors/aliases/tags/merge keys, and `${...}` interpolation
  are all rejected — never silently ignored.
- **No Docker networking — and no port publishing in this MVP.** P1/P2
  containers share the guest network namespace: no bridge, NAT, or
  service-name DNS, and a "published" port is only a guest-side listener.
  The S10e traffic test (2026-09-16) proved udocker/PRoot strips the
  declared `host_ip` — a `127.0.0.1:18924:8080/tcp` publish produced no
  listener on `:18924` while the container stayed on `*:8080`, reachable
  via the device LAN IP. Since no loopback-only proxy/isolation exists,
  `up` rejects every `ports:` declaration (and argv build fails closed if
  a persisted plan still carries one) until that changes; use an
  app-level web app/port surface instead.
- **Workspace-only binds, read-write.** The compose file and every volume
  source must resolve inside the `~/nusadesk` workspace; `ro` is rejected
  because `udocker --volume` is always read-write.
- **Restart is owned by one product-owned supervisor**, not by systemd
  units: `always`/`unless-stopped` restart with exponential backoff (1 s
  doubling to 60 s, reset after 30 s healthy); `stop`/`down` persists a
  manual-stop flag so a stopped project stays stopped across session
  restarts for every policy; `restart: no` is final until `up`/`start`.
- **Detached only, no recreation on re-`up`.** `up` requires `-d` (no
  foreground mode); a changed image needs `down` first, because
  `--pull=reuse` reuses the named container.
- **Device-verified MVP, with explicit limits.** The wired overlay passed
  end-to-end on the S10e (SM-G970F, Android 12/API 31, arm64, 4 KB pages,
  2026-09-16): supervised `up -d`, nested-PRoot execution through the
  product `/usr/local/bin/udocker`, workspace-bind writes, six `always`
  restarts, a persisted `stop`, a clean `down`, an uncached
  `busybox:latest` image pull (16.36 s, arm64 manifest verified), and
  `unless-stopped` across a host force-stop/relaunch. The port traffic
  test ran on the same pass and proved the `host_ip` limitation above,
  which is why `ports` is now refused. The wider device matrix (other
  API levels, 16 KB pages, OEMs) remains open, and no full Compose
  compatibility is claimed.
- **Same-version overlay installs are digest-verified.** Session start
  re-hashes every vendored overlay file against its catalog SHA-256 pin
  (`GuestServiceBridge.detect()`); a stale or partially upgraded payload
  reads as not-installed and the pipeline reinstalls it, so an APK update
  that changes a packaged asset does not need a version bump to reach the
  device. (The initial verification pass predates this guard and had to
  delete one stale compose file by hand — that manual step is historical,
  not current behavior.) A container udocker cannot remove during `down`
  is left behind for manual `udocker rm`; project state is removed
  regardless once no live child is named.

### Guest service bridge overrides apt-installed entrypoints

The guest may later gain a native `systemctl`, `service`, or Python
interpreter through `apt`. The rootfs-owned files are preserved, but every new
PRoot session adds strict product-owned file binds at `/usr/bin/systemctl`,
`/usr/bin/service`, `/usr/bin/python3`, and `/usr/bin/python3.12`, so the
vendored `systemctl3` bridge remains the effective user-facing implementation
without deleting package-owned files (ADR-0024). The strict-bind behavior still
needs a fresh device pass over an apt-installed replacement.

### Systemd user units need the product's user manager

Only the manager instance the session starts with a target brings a unit up
automatically. The session's system manager starts *system* units, so a unit a
user enables with `systemctl --user enable` (a web app enabled into
`default.target`, for example) is started by the product-owned
`lw-user-manager.service` unit, which runs the same replacement in `--user`
mode with `HOME=/root`, `XDG_RUNTIME_DIR=/run/user/0`, and
`SYSTEMD_DEFAULT_TARGET=default.target` (ADR-0024). Consequences to keep in
mind:

- A user unit enabled *during* a session runs only from the next session: the
  replacement snapshots the enabled-unit set at `systemctl init`.
- `systemctl --user` still needs a usable `HOME`; a process launched outside the
  guest login shell (for example a raw adb `run-as` invocation) answers
  `Unit … not found` for a unit that exists.
- A hand-started service (`systemctl --user start` in a terminal) still forks
  under that login shell and dies with it; only manager-started units live for
  the whole session.

### Guest `/tmp` is session-temporary

The active rootfs is persistent app-private storage, so its guest `/tmp` would
otherwise persist across sessions and could accumulate package, Node, PRoot, or
application artifacts. At session start, after stale process reclaim, and after
normal stop, NusaDesk clears only the children of the real rootfs `/tmp`
directory. It refuses a symlink or non-directory there and never follows
symlinks while deleting. A force-stop or reboot can prevent immediate cleanup;
the next explicit app launch retries before starting Linux. Product caches such
as `/root/.local/share/lw-udocker` are intentionally not cleared by this rule
(ADR-0027).

### Guest logs are bounded and curated, not a full journal

The session console lands in `/var/log/lw/boot.log` and per-unit output in
`systemctl3`'s journal files (`/var/log/journal/`, `--user` under
`/root/.config/log/journal/`). Bounds are enforced in place because services
hold their log files open with `O_APPEND`: `boot.log` self-trims at ~2 MiB to
the newest ~1 MiB, and a host-side sweeper tail-trims oversized journal files
on the same thresholds — so a log line mid-flush can briefly push a file past
the bound (ADR-0034). The Logs surface is a curated catalog, not a guest file
browser: it lists only regular, non-symlink files under the active rootfs, so
a service writing to a custom `file:` path elsewhere never appears. `boot.log`
captures the supervised tree's console, not a kernel ring buffer — "boot"
means the product session, like `journalctl -b` under a container. Only one
previous boot generation is retained; there is no deeper history.

### Guest awareness README

Each usable guest session ensures a concise, writable `/root/README.md`. The
Android app owns this file, includes the installed APK version, and atomically
regenerates it on session start when the app version changes or the content is
stale. Guest users can read and edit it, but edits to this product-owned file
may be replaced by a later app update. Other files under `/root` are not touched.

### Guest `/etc/os-release` contributor metadata

NusaDesk preserves Ubuntu's `NAME`, `ID`, `ID_LIKE`, and version fields, then
exposes namespaced metadata through the effective `/etc/os-release` path:
`NUSADESK_CONTRIBUTOR="NusaDesk"` and
`NUSADESK_SOURCE="https://github.com/jahrulnr/NusaDesk"`. The product source is
strictly bound at session launch and regenerated from the current package-owned
Ubuntu base file, so `apt` changes to `/usr/lib/os-release` are reflected while
the custom fields remain present. The original `/usr/lib/os-release` is not
rewritten, and tools reading that path directly will see the original Ubuntu
file.

### Android capability bridge and automation surface

The first feasible slice is implemented (ADR-0030, ADR-0031): the existing
`GuestSshdWorkload` starts a session-scoped host bridge on an ephemeral
`127.0.0.1` TCP port and passes its address, protocol version, and a fresh
random token to the guest through the PRoot environment. Every connection is
bounded (one line-delimited frame, 16 KiB maximum, fifteen-second socket timeout,
four concurrent clients), and every request must carry the token. Loopback is
only a reachability restriction, not authentication, because another app can
share the device loopback namespace.

The protocol is intentionally small and dependency-free:

- `bridge.info` reports the transport and allowlisted capability set.
- `battery.status` reads Android's permission-free `BatteryManager` state.
- `location.get` performs a foreground-only one-shot `LocationManager` request.
  It never opens a permission Activity and returns explicit
  `location-permission-required`, `location-permission-denied`,
  `location-unavailable`, or `location-timeout` states. A successful fix is
  bounded to finite coordinates, accuracy, provider, and timestamp; background
  location and continuous GPS are not claimed.
- `sensor.accelerometer` and `sensor.gyroscope` perform one-shot,
  permission-free `SensorManager` reads with finite x/y/z values, bounded
  accuracy text, platform timestamp, and explicit unavailable/timeout errors.
- `media.start`, `media.camera.start`, `media.microphone.start`, `media.status`,
  and `media.stop` control one live camera/microphone session per mode. Camera2
  and AudioRecord feed H.264 and AAC-LC hardware encoders; a loopback-only
  RTSP-over-TCP listener returns a bounded `rtsp://127.0.0.1:<port>/` URL and
  advertises only the tracks the active mode carries. A camera-only session
  needs no microphone grant and claims no microphone foreground-service type,
  and a microphone-only session needs no camera grant: the mode decides the
  required grants, the foreground types, the SDP tracks, and the status fields
  (`mode`, plus `video_*` only for a video mode and `audio_codec` only for an
  audio mode). One session runs at a time — a start in another mode answers the
  typed `media-mode-conflict`. NusaDesk writes no JPEG, M4A, MP4, or other
  capture artifact; consumers save manually if desired. The user-visible
  foreground service returns typed `media-*` errors for missing/denied grants,
  background starts, busy hardware, unavailable devices, and encoder failures.
- `contacts.list`, `calllog.list`, `sms.inbox`, `telephony.info`, and
  `telephony.cellinfo` are bounded read-only methods. SMS send and phone call
  remain typed `action-unsupported`.
- `location.stream.start`, `.poll`, and `.stop` provide a bounded
  foreground-only pull stream with a capped queue; background location and
  FGS startup are not wired.
- The request is a flat JSON object with `v`, `id`, `token`, and `method`; the
  response is a flat bounded JSON object. There is no arbitrary shell,
  reflection, URI, Binder, or class dispatch.

The battery capability also projects a best-effort Linux-shaped tree at the
literal guest path `/sys/class/power_supply/battery`. `capacity`, `status`,
`health`, `present`, `online`, `temp`, voltage/current/charge/energy values,
and a small `uevent` file are refreshed from Android every two seconds. This
is a product-owned snapshot, not real kernel sysfs: the guest must treat it as
read-mostly and tolerate stale or unavailable values. A guest write can be
replaced by the next refresh, and the projection may be absent if its bind
parent is unsafe. The RPC method remains the authoritative capability result.
For guest automation that needs the RPC directly, the host also strict-binds a
mode-0600 session file at `/run/nusadesk/android-bridge.env`; it contains the
loopback address, ephemeral port, protocol version, and session token. The
file is intentionally readable by the authorized guest and is removed during
normal session teardown. The bridge and projection close with the guest
session; the live media foreground service additionally closes its RTSP
clients, encoders, camera, microphone, and listener on stop.

Physical passes (2026-09-16 to 2026-09-17):

- Samsung S10e SM-G970F `R39M209Q3TM` (Android 12/API 31, arm64, 4 KB
  pages): from the live guest, the pinned Python probe read the session env
  file, connected to the host loopback port, received `battery.status` with
  `ok=true`, `available=true`, `capacity_percent=84`, `status=not-charging`,
  `health=good`, and read matching `SYSFS_CAPACITY=84`/`SYSFS_STATUS=Not
  charging` from the projected sysfs tree. The normal notification Stop action
  removed the bridge config and projection files after the PRoot/guest process
  stopped; a fresh app-visible launch recreated them.
- Samsung S7 Edge SM-G935F `ce0516054597102d05` (Android 10/API 29, arm64, 4
  KB pages): the same guest probe returned `ok=true`, `available=true`,
  `capacity_percent=100`, `status=full`, `health=good`, and matching
  `SYSFS_CAPACITY=100`/`SYSFS_STATUS=Full`.

- On both devices, guest calls to `sensor.accelerometer` and
  `sensor.gyroscope` returned `ok=true`, `available=true`, finite x/y/z
  values, `accuracy="high"`, and platform timestamps. This validates the
  one-shot adapter path on API 29 and API 31; it does not validate continuous
  streaming or every sensor type.

- On S10e, with the app's foreground location permissions ungranted, the guest
  received `location-permission-denied` without any prompt. A test-only
  `adb pm grant` of foreground fine/coarse location moved the same request into
  the bounded `location-timeout` state because no fresh provider fix arrived;
  the grants were revoked afterwards. This validates permission mediation and
  bounded failure handling, but not a successful live fix.

- On S10e on 2026-09-17, with test-only CAMERA and RECORD_AUDIO grants and
  the Activity visible, the actual guest CLI ran `media.start` and returned
  `running` with `rtsp://127.0.0.1:<port>/`, H.264/AAC, 1280x720, 30 fps, and
  `client_limit=2`. An `adb forward` exposed that device-loopback port only to
  the host test consumer: `ffprobe` enumerated H.264 1280x720 and AAC 44.1 kHz
  mono, and `ffmpeg -t 3 -f null -` exited successfully with no decode errors.
  The guest CLI then ran `media.stop` and `media.status` (`stopped`), the
  foreground service disappeared, and both test-only grants were revoked.
  No capture file was written.

- On the same S10e pass the three media modes were verified independently:
  `media start --camera` ran with RECORD_AUDIO **revoked** and served only the
  H.264 track (ffprobe `h264,video`; 181 decoded frames in 6 s), `media start
  --microphone` ran with CAMERA **revoked** and served only the AAC track
  (ffprobe `aac,audio,44100,1`; a 5 s recording with 216 audio frames and no
  decode errors), the combined `media start` still served both tracks, and a
  start in another mode while one was live answered `media-mode-conflict`.


The manifest still declares the permission set a user-invoked
automation surface may need: camera, microphone, location (foreground and
background), sensors, activity recognition, Bluetooth, telephony and messaging
reads, contacts, calendar, wake lock, battery-exemption, overlay, usage stats,
and all-packages. **Battery and accelerometer/gyroscope do not use dangerous
permissions**; no dangerous grant is requested at launch. Live media requires
explicit camera and microphone grants managed through Android App Info, and a
missing grant must produce a typed result rather than a prompt or fake value.

Some users want to drive Android from inside their Linux workspace — for
example a guest script that reads location, watches motion, reacts to a call,
or posts an overlay. We can expose those APIs only through separately scoped,
user-invoked adapters. The product does not imply that declaring a permission
makes the API available to Linux.

Two consequences to keep in view:

- The installer's permission list includes capabilities not yet implemented.
  Several are Google Play *restricted* or *special access* permissions,
  acceptable for the project's signed GitHub/F-Droid channel but requiring the
  matching declaration forms and approved use cases for a Play submission.
- Normal permissions (`WAKE_LOCK`, `HIGH_SAMPLING_RATE_SENSORS`,
  `QUERY_ALL_PACKAGES`, and the foreground-service types) are install-time and
  should not be treated as user opt-in. Before a stable release each must be
  used by a shipped capability or removed. `QUERY_ALL_PACKAGES` should narrow
  to a `<queries>` block once concrete automation use cases are known.
  `BridgePermissionsManifestTest` keeps the reviewed manifest set exact.

Current capability status:

| Capability | Status |
| --- | --- |
| Battery/status | Implemented best-effort through RPC plus `/sys/class/power_supply/battery` projection; no runtime grant. |
| Accelerometer/gyroscope | Implemented one-shot through `sensor.accelerometer`/`sensor.gyroscope`; no runtime grant; streaming/backpressure remains future work. |
| Other sensors | Limitation for now; Android `SensorManager` types and rate/backpressure need explicit contracts. |
| Location | Implemented foreground one-shot `location.get` and bounded location stream with explicit permission/status errors; no background GPS contract. |
| Live camera + microphone | Implemented live-only with three modes (`media.start` both, `media.camera.start`, `media.microphone.start`) over Camera2 + H.264/AAC and loopback RTSP-over-TCP; all three modes consumer-verified on Samsung S10e API 31, while wider OEM/API coverage remains open and no files are written. |
| Contacts/call log/SMS/telephony | Implemented bounded read-only methods with per-method permissions, redaction, row/byte caps; real provider data verification remains pending. |
| Calendar | Implemented bounded read (`calendar.list`: fixed seven-day window, at most 50 rows, `truncated` flag, no description/attendee/organizer columns) and bounded writes (`calendar.insert`/`calendar.update`/`calendar.delete`) with per-method grants, validated fields, and typed errors; verified on Samsung S10e API 31. No attendee/invitation support, no calendar creation or listing, no guest-chosen window, and no reminder fields; wider OEM/API coverage remains open because the provider's instance/timezone shape is OEM-sensitive. |
| Bluetooth, usage stats, overlay | Limitation for now; permission declarations alone do not implement or authorize these APIs. |
| Notification listener/accessibility | Deliberately not declared; each is a special user-enabled service with a much broader data boundary. |

`WRITE_EXTERNAL_STORAGE` is also left out: it grants nothing extra on API 30+
alongside `MANAGE_EXTERNAL_STORAGE`, so it would be installer noise.

The earlier device spike established the transport premise on the SM-G970F
(API 31, app UID `u0_a280`):

| Probe | Result |
| --- | --- |
| Guest → `adb`-reverse listener on `127.0.0.1:17890` | `HTTP_BODY=BRIDGE_SPIKE_OK` |
| Guest → app-UID listener on `127.0.0.1:17891` | `GUEST_OK=APP_UID_LISTENER_OK` |
| Device shell → `127.0.0.1:17892` | `SCOPE_OK` |
| Device shell → LAN IP `192.168.18.202:17892` | `Connection refused` |

The production slice keeps that proven loopback/TCP choice. A Unix-domain
socket remains a future optimization/spike, not a prerequisite for the
working bridge; a UDS does not remove the need for the token. Raw Android
Binder, direct `/dev` hardware access, GPU/NPU paths, and kernel/SELinux
changes remain limitations that require a different form or root-level
support.

### Resolver doctor is bounded and allowlisted

The resolver guard runs inside the existing foreground runtime lifecycle; it
is not a second guest `cron` daemon. It observes the host-owned resolver source
with a debounced `FileObserver` and a 30-second periodic check. It repairs only
the validated app-private resolver file, never arbitrary rootfs paths or
package state. No active Android DNS means `NO_ACTIVE_DNS`, not a hardcoded
public-DNS fallback. Startup/next-session repair remains necessary after
force-stop or process death (ADR-0026).

### Foreground service is not a survival guarantee

A foreground service improves process importance and provides user visibility but cannot guarantee persistence across low memory, OEM battery policy, Doze, force-stop, reboot, or user revocation. The UI must expose `STOPPED`, `FAILED`, and `RECOVERING` states rather than pretending the daemon is always alive. `dataSync` is not used as an indefinite server type (Android 15 caps it at six hours per 24h); the declared type is `specialUse` with a `linux_runtime_host` subtype, and the subtype rationale plus any distribution-channel policy review remain open.

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
- Docker-compatible container semantics inside PRoot beyond the bounded
  `udocker compose` adapter (ADR-0025): arbitrary Compose features, real
  network isolation, or container-in-container nesting.
- Host filesystem-wide mounts.
- Unauthenticated LAN/public binding.
- Automatic battery-optimization exemption.
- Silent self-update of payloads.
- First-launch compilation of native modules or web frontends.
- Claims of full desktop Linux compatibility.
