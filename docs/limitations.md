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
guest process cannot create a real hard link anywhere in its rootfs. That is
still the case with the shipped bridge, device-verified with the app's own
PRoot: a bare `ln` in app data answers `Permission denied` under `Enforcing`,
and the "hard link" a guest program observes (equal inode, `st_nlink`
counting, write-through) is the `--link2symlink` emulation of ADR-0020
faking those fields -- the on-disk entries are symbolic links. `dpkg` creates
a backup hard link before every unpack, so without a workaround no
`apt install`/`apt upgrade` can complete; NusaDesk therefore runs PRoot with
`--link2symlink` (ADR-0020).

Consequences to keep in mind:

- A guest program that needs a *true* hard link — comparing inode numbers, or
  requiring `st_nlink > 1` — will not behave correctly. Package installation
  and upgrades are supported; code that depends on real hard link semantics is
  not.
- Linking a symbolic link, or one of the emulation's own entries, no longer
  destroys anything and the program sees the operation succeed (the fix
  recorded in the build spike, 2026-09-26); before it, such a `link()` moved
  the source away and answered `EPERM`.
- Removing a directory that holds emulated links may need a second `rm -rf`
  pass: the link-count update renames a backing file while the first pass
  walks the directory, and that first pass then reports `Directory not
  empty`. `rm` no longer answers `EPERM` for such files.
- The emulation's `.l2s.` entries are visible in directory listings (the
  extension does not filter them). Tools that enumerate a tree see them as
  extra entries.

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
bounded (one line-delimited frame, 64 KiB maximum, fifteen-second socket timeout,
four concurrent clients), and every request must carry the token. Loopback is
only a reachability restriction, not authentication, because another app can
share the device loopback namespace.

The protocol is intentionally small and dependency-free. Since ADR-0049 the
handler keeps its built-in methods and dispatches the rest to registered
`CapabilityModule`s; a module declares its methods, which of them accept a
bounded `params` object (at most 16 keys, key <= 32 chars, one string value
<= 8192 chars), and answers one typed response per call. `CapabilityParams`
typed getters plus `rejectUnknown` make a misspelled key fail closed as
`invalid-argument`, and module errors follow one taxonomy
(`<capability>-permission-required`/`-permission-denied`/`-unavailable`/
`-busy`/`-timeout`, `foreground-required`, `invalid-argument`,
`action-unsupported`):

- `bridge.info` reports the transport and the allowlisted capability set;
  `bridge.permissions` reports every permission's state plus the Settings
  action that opens each missing grant; `permission.request` runs the
  platform runtime dialog or the matching Settings screen through the
  foreground host under a bounded 120 s wait.
- The guest carries the full Termux:API client parity layer (ADR-0049): all
  57 upstream `termux-*` commands are generated into `/usr/local/bin`, thin
  scripts over a shared `termux_compat` runtime that translate Termux flags
  into bridge calls and print the Termux JSON shape with upstream exit
  codes. The Termux:API app cannot be used by this product (it is
  signature/UID-locked to Termux), so this is the only Termux-compatible
  surface; commands the hardware or platform cannot serve answer a typed
  absence, never a fake success (documented in the guest's
  `docs/termux-compat.md`, evidence in `docs/evidence/termux-parity-matrix.md`).
- `battery.status` reads Android's permission-free `BatteryManager` state.
- `location.get` performs a foreground-only one-shot `LocationManager`
  request with a 30 s bounded window: it honours a forced provider and a
  last-known-only request, picks the deliverable provider whose last-known
  fix is freshest, and falls back to a stale-marked last-known fix before
  reporting `location-timeout`. It never opens a permission Activity and
  returns explicit `location-permission-required`,
  `location-permission-denied`, `location-unavailable`, or
  `location-timeout` states; background location and continuous GPS are not
  claimed. A successful fix is bounded to finite coordinates, accuracy,
  provider, and timestamp.
- `sensor.list`, `sensor.read`, and `sensor.stream.*` cover the platform
  `SensorManager` catalogue (42 sensors on the test device) with bounded
  one-shot and sequential samples in the upstream `{SENSOR: {values: [..]}}`
  shape and explicit unavailable/timeout errors.
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
  typed `media-mode-conflict`. The RTSP slice itself writes no capture file;
  the `camera.photo` and `microphone.record.*` methods are the bounded way to
  produce a JPEG or audio artifact, written only to the guest staging path the
  command asked for. The user-visible foreground service returns typed
  `media-*` errors for missing/denied grants, background starts, busy
  hardware, unavailable devices, and encoder failures.
- `contacts.list`, `calllog.list`, `sms.inbox`, `sms.send`, `telephony.info`,
  `telephony.cellinfo`, and `phone.call` are bounded comms methods with
  per-method permission sets; a missing grant is a typed
  `<capability>-permission-required`/`-permission-denied` result, never a
  prompt from the bridge worker and never a fabricated read.
- `location.stream.start`, `.poll`, and `.stop` provide a bounded
  foreground-only pull stream with a capped queue; background location and
  FGS startup are not wired.
- The request is a flat JSON object with `v`, `id`, `token`, `method`, and an
  optional bounded `params`; the response is a flat bounded JSON object.
  There is no arbitrary shell, reflection, URI, Binder, or class dispatch.

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

- Samsung S10e SM-G970F (Android 12/API 31, arm64, 4 KB
  pages): from the live guest, the pinned Python probe read the session env
  file, connected to the host loopback port, received `battery.status` with
  `ok=true`, `available=true`, `capacity_percent=84`, `status=not-charging`,
  `health=good`, and read matching `SYSFS_CAPACITY=84`/`SYSFS_STATUS=Not
  charging` from the projected sysfs tree. The normal notification Stop action
  removed the bridge config and projection files after the PRoot/guest process
  stopped; a fresh app-visible launch recreated them.
- Samsung S7 Edge SM-G935F (Android 10/API 29, arm64, 4
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
reads and sends, phone calls, contacts, calendar, wake lock,
battery-exemption, overlay, usage stats, all-packages, vibration, wallpaper,
infrared transmit, NFC, biometric, wifi state, audio settings, media-read,
and the notification-listener service behind `termux-notification-list` —
each declared because a shipped capability needs it (the manifest comments
name the command per grant). **Battery and the sensor catalogue do not use
dangerous permissions**; no dangerous grant is requested at launch. Runtime
grants are asked on first use through `permission.request`, special access
(WRITE_SETTINGS, notification access, all-files, battery exemption) goes
through the platform Settings screens, and a missing or refused grant is a
typed result rather than a prompt loop or a fake value.

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
| Sensors | Implemented through the `sensor.list`/`sensor.read`/`sensor.stream.*` catalogue (42 sensors listed on the S10e); samples are sequential bounded one-shot reads, so continuous streaming and backpressure remain future work. |
| Location | Implemented foreground one-shot `location.get` (30 s window, provider and last-known params, stale-marked fallback) and bounded location stream with explicit permission/status errors; no background GPS contract. |
| Live camera + microphone | Implemented live-only with three modes (`media.start` both, `media.camera.start`, `media.microphone.start`) over Camera2 + H.264/AAC and loopback RTSP-over-TCP; all three modes consumer-verified on Samsung S10e API 31, while wider OEM/API coverage remains open and the RTSP slice writes no files. |
| Contacts/call log/SMS/telephony | Implemented bounded methods with per-method permissions, redaction, and row/byte caps; the reads are device-verified on the S10e (the call log returned an empty list because the device log is empty) and the non-empty inbox/deviceinfo paths on the S7 Edge, and `sms.send`/`phone.call` were device-verified on the SIM-equipped S7 Edge on 2026-09-23 (a real SMS landed in the platform sent box, a real call reached `mCallState=2`). |
| Calendar | Implemented bounded read (`calendar.list`: fixed seven-day window, at most 50 rows, `truncated` flag, no description/attendee/organizer columns) and bounded writes (`calendar.insert`/`calendar.update`/`calendar.delete`) with per-method grants, validated fields, and typed errors; verified on Samsung S10e API 31. No attendee/invitation support, no calendar creation or listing, no guest-chosen window, and no reminder fields; wider OEM/API coverage remains open because the provider's instance/timezone shape is OEM-sensitive. |
| Wifi extras + file server | Implemented NusaDesk-native (ADR-0051): `wifi.hotspot.*` (local-only hotspot, no internet), `wifi.suggest.*` (advisory network suggestions), `wifi.lock.*` (keep the radio awake for a transfer), plus the guest `nusadesk-serve` HTTP file server and `nusadesk-net` helper. Toggling wifi stays a typed absence (platform rule) and Wi-Fi Direct/RTT remain separately scoped. Unit-verified; the S10e device pass is the open acceptance gate. |
| Bluetooth | Implemented NusaDesk-native (ADR-0050/0053): adapter, discovery, pairing, BLE, GATT, RFCOMM, phone-as-HID-Device keyboard/mouse (`BluetoothHidDevice`), and public A2DP/HFP/LE Audio status plus HFP voice-recognition start/stop for an already-connected headset. Guest CLI: `nusadesk-bt`. S10e/S7 verified HID register/connect/type/key and teardown; S10e/WH-CH520 verified A2DP/HFP status and voice start/stop. Pointer visibility/click and broader device coverage remain unverified. No HID host, A2DP profile connect/disconnect, A2DP sink, PAN tethering, or raw arbitrary HID reports. |
| Guest media output route | `mediaplayer.outputs` / `route` / `route.clear` enumerate current output devices and set an ephemeral preferred output on NusaDesk's `MediaPlayer` only; this does not switch other apps or connect profiles. WH-CH520 routing was device-observed on S10e (`deviceId=421`, Android playback config active); physical audibility was not independently measured and built-in speaker playback did not remain observable on this ROM. |
| Usage stats | Implemented NusaDesk-native (ADR-0052): `usage.query` / `usage.events` / `usage.standby`, bounded (≤ 7 days, ≤ 50 rows) behind the usage-access special grant. A foreign package's standby bucket is derived from a bounded `STANDBY_BUCKET_CHANGED` lookback because the per-package getter is `@SystemApi`; the app's own bucket and inactive flag read directly. Unit-verified; device pass pending. |
| Packages | Implemented NusaDesk-native (ADR-0052): `packages.list` / `packages.info` (backed by `QUERY_ALL_PACKAGES`) and `packages.launch`, which answers a typed `packages-launch-blocked` when the platform refuses a background activity start. Unit-verified; device pass pending. |
| Overlay | Implemented NusaDesk-native (ADR-0052): one `overlay.show` / `update` / `status` / `hide` text plate behind the `SYSTEM_ALERT_WINDOW` special grant, all view work on the main looper with a bounded wait. Unit-verified; device pass pending. |
| Background location | Implemented NusaDesk-native following the upstream contract that is not released yet (ADR-0052): `location.background.start` / `poll` / `stop` behind "Allow all the time" and the `FOREGROUND_SERVICE_LOCATION` type, with a visible notification and a Stop action. Unit-verified; device pass pending. |
| Notification listener | Implemented for `notification.list` only: the listener service is declared and inert until the user enables notification access in Settings, and the module reports a typed permission result until then. Accessibility stays deliberately undeclared — it is a special user-enabled service with a much broader data boundary. |

`WRITE_EXTERNAL_STORAGE` is also left out: it grants nothing extra on API 30+
alongside `MANAGE_EXTERNAL_STORAGE`, so it would be installer noise.

The earlier device spike established the transport premise on the SM-G970F
(API 31, running as the app's own UID):

| Probe | Result |
| --- | --- |
| Guest → `adb`-reverse listener on `127.0.0.1:17890` | `HTTP_BODY=BRIDGE_SPIKE_OK` |
| Guest → app-UID listener on `127.0.0.1:17891` | `GUEST_OK=APP_UID_LISTENER_OK` |
| Device shell → `127.0.0.1:17892` | `SCOPE_OK` |
| Device shell → the device's LAN IP (port 17892) | `Connection refused` |

The production slice keeps that proven loopback/TCP choice. A Unix-domain
socket remains a future optimization/spike, not a prerequisite for the
working bridge; a UDS does not remove the need for the token. Raw Android
Binder, direct `/dev` hardware access, GPU/NPU paths, and kernel/SELinux
changes remain limitations that require a different form or root-level
support.

### Termux:API parity surface is bounded by the platform and the hardware (ADR-0049)

All 57 upstream `termux-*` client commands are installed in the guest and
answered through the bridge; the limits below are contract, not polish:

- **Wifi cannot be toggled.** Android 10/API 29+ reserves the wifi enable
  switch to the system: `termux-wifi-enable` answers
  `wifi-toggle-unsupported` (rc 1) and never claims a toggle happened.
- **Infrared needs the emitter.** The S10e evidence device has no IR
  hardware, so `termux-infrared-frequencies`/`termux-infrared-transmit`
  answer `infrared-unavailable:this device has no IR emitter` (rc 1) there;
  the implementation stays for a device that has one.
- **Foreground operations are bounded.** Consent dialogs, `termux-dialog`,
  SAF pickers, the share chooser, and speech recognition wait at most 120 s
  for a result (fingerprint carries its own bounded wait); an unanswered
  prompt times out typed instead of hanging the bridge, and a second
  concurrent foreground operation is `<capability>-busy`. A platform refusal
  to show the Activity answers `foreground-required` — the bridge never
  silently waits.
- **Jobs run only while the guest session is alive.** `termux-job-scheduler`
  has no background executor of its own: a trigger that fires while the
  session is down records a `session-down` outcome instead of pretending a
  background run happened.
- **`notification.list` needs notification access.** The listener service is
  inert until the user enables notification access in Settings; before the
  grant the command answers `notification-permission-required` with the
  Settings hint.
- **`media.scan` can only index what the media provider can read.** A path
  inside the app-private rootfs scans as zero files; workspace-bound paths
  (the user-picked `~/nusadesk` folder) index normally.
- **Capture and recording artifacts go only to the guest staging path.** A
  bridge method never receives a path the host cannot resolve inside the
  active rootfs, and the guest script moves the result to the user's
  destination (the bind-mounted workspace is a guest-side concept).

Still open in the ledger (per `docs/evidence/termux-parity-matrix.md`):

- `termux-speech-to-text` (WIP): implemented, but neither project device has a
  usable recognizer backend; the command fails typed instead of pretending.
- `termux-nfc` (PARTIAL): a real tag was detected in reader mode, but it is not
  NDEF, so an NDEF read/write still needs an NDEF tag.
- `termux-media-scan` (PARTIAL): only indexes paths the platform media
  provider can read, not the app-private rootfs.

Closed since the first pass: `termux-sms-send` and `termux-telephony-call`
(S7 Edge with SIM, 2026-09-23), `termux-fingerprint` (S10e enrolled finger),
and `termux-location` (a fresh fix with the 30 s window and the
freshest-provider pick).

### USB pass-through delivers a descriptor, not a device bus (ADR-0041)

The guest has no `/dev/bus/usb` and never gets one through this product:
Android does not expose usbfs nodes to apps, so `usb.open` can only deliver
an already-consented device descriptor. What that means in practice:

- Every open shows the platform's own per-device consent dialog, so a
  headless or background trigger still needs the user to answer it; a denied
  or ignored dialog is a typed error (`usb-permission-denied`,
  `usb-permission-timeout`), and no grant is remembered beyond the
  platform's own permission.
- The app cannot force a port into host mode, provide VBUS, or make the
  phone act as a USB device: host mode, OTG power, and gadget/UDC behavior
  stay platform/OEM territory.
- Interface claims, URB submission, and protocol logic belong to the guest
  once the descriptor arrives — the host adds no transfer API. A guest-side
  adb client therefore needs a USB-fd-capable transport (the termux-adb
  pattern); `nusadesk-usb exec` only hands it `NUSADESK_USB_FD`.
- Descriptor lifetime follows the file descriptors: the guest's copy stays
  usable while any reference is open, and the app-side connection is
  released when the bridge session closes. Unplug, revoke, or process death
  invalidates the device file itself.
- `usb.list`/`usb.open` cover enumeration and delivery only: no device-class
  support matrix, no automatic re-attach on hotplug, and no persistence
  beyond what the platform dialog already grants.
- The guest adb driver (ADR-0042) does not remove the platform gates: the
  host's per-attach USB consent dialog still has to be answered once per
  attach, the target still shows its debugging authorization unless "always
  allow" was chosen, and both dialogs need the devices reachable and awake.
  `adb devices` answers from cached/fabricated descriptors until adb actually
  opens a device (that is when the real descriptor, serial included, is
  read); the driver is session-scoped and its descriptors die with the guest
  session. The first use needs `gcc` in the guest to compile the shim;
  without it the wrapper degrades to the plain adb.
- The driver also needs a **libusb whose hotplug monitor failure is
  non-fatal** (Android denies the kobject-uevent netlink socket; stock libusb
  then refuses to initialize): today that build is made in the guest with
  `gcc` + the libusb headers and a one-hunk source patch, installed under
  `/opt/nusadesk/lib` (ADR-0042 records the recipe). Shipping it as a
  verified artifact is open work. Also observed on device: a USB session that
  ends abruptly (host process killed mid-handshake) can leave the ROM's adbd
  ignoring further CNXN packets until the cable is replugged — the reset is
  the documented recovery, not a product defect.

### Guest backup archives are plain, user-owned artifacts (ADR-0044)

- The `tar.gz` an export produces is **not encrypted and not signed**: it is
  the user's artifact, and it may contain the guest SSH host key, guest
  files, and everything under `/root`. Keep it somewhere trusted; the UI
  says so next to the Export action.
- The workspace folder is never included (copy it yourself), the pseudo
  trees (`/proc`, `/sys`, `/dev`, `/run`, `/tmp`) and session temp files are
  excluded, and an export is a best-effort snapshot of a guest that may be
  running.
- Home and custom restores merge into an existing session, one top-level
  path at a time — they replace whole subtrees, not individual files — and
  need an installed runtime (`runtime-required` otherwise). A full restore
  brings what the archive contains; it does not delete an add-on that is
  active on the device but absent from the archive. Device-bound credentials
  are regenerated: the Keystore-wrapped root credential is re-asserted at
  the next session start.
- A full restore needs the session stopped, and the only stop control is the
  platform notification's `Stop` action: the file picker's return fires an
  Activity foreground event that autostarts the session again, so a restore
  started while that start has already reached `RUNNING` answers
  `busy · Stop the Linux session before restoring.` The host stopping its own
  session for the restore is the recorded follow-up.
- Bind mount points are kept in the archive as entries only: the guest's
  add-on overlays (`/opt/lw-ssh`, `/opt/lw-services`) and the workspace folder
  carry mode `000` in the guest and cannot even be listed, and the device's
  own tool trees (`/system`, `/apex`) belong to the device — a backup never
  carries them, so a restore into another device cannot smuggle in a foreign
  Bionic copy. The shared extractor accepts up to 500,000 entries (a real
  Everything backup measured 53,226) with the extracted-byte cap as the
  primary bound, and applies directory modes after the payload so read-only
  directories restore correctly.

- The workspace folder is the one guest path a backup deliberately excludes, so
  the user copies it out themselves. Where it lives depends on the platform
  (ADR-0047): any folder the user picks on Android 11+ (all-files access) and on
  Android 10 (the legacy storage model, after one permission prompt), with
  `Android/media/<pkg>/nusadesk` as the default until something is picked —
  app-owned and bindable, and visible to a file managers and MTP. On Android 10
  the app holds `READ`/`WRITE_EXTERNAL_STORAGE` bounded to API 29 for exactly
  this: measured on the S7 Edge, without them `/sdcard` reads as
  `list=null canRead=false`, and with them (plus the legacy flag) the app lists
  and writes it normally.
- The in-app browser shows the folder currently open and applies "Use this
  folder" to it: a picked path is validated (absolute, no traversal, inside the
  app's own trees or shared storage with storage access in place) and
  write-probed before it replaces the stored workspace, and the guest binds it
  at the next session start.

- The native Android tooling tier (ADR-0045) is bounded by the app's own uid
  and by the trees the product binds: `/system/bin`, `/system/lib64` and the
  Bionic runtime tree `/apex` (on Android 11+ `/system/bin/linker64` is only
  a symlink into `/apex`, so that bind is what makes any Bionic exec resolve
  at all). `/linkerconfig` is unreadable, so the linker runs without its
  namespace map (the `failed to find generated linker configuration` warning
  on every Bionic exec is unavoidable, and libraries living only in an APEX —
  e.g. `libicu` — do not resolve by name; `android-cli` gives its children an
  Android `PATH`/`LD_LIBRARY_PATH` prefix as a partial bridge), `ls
  /system/bin` is denied while `exec` of its entries works, and the verbs the
  platform reserves for another uid (`screencap`, `input`, `pm`, `am`,
  `dumpsys`) remain the user's own higher-tier decision, not a product gate.
  `screencap` can report success and still write a 0-byte file as an app uid,
  so a capture counts as evidence only when the file has content.
  `android-cli su` runs the device's own `/system/bin/su`: absent on a stock
  device (typed exit 5 with a hint), and on a rooted one the user's own root
  door with one prompt per grant — where a genuine root child inside the
  traced session is an unusual PRoot state, and `adb root` over the
  documented shell tier stays the cleaner path.

### Guest GPU/NPU acceleration is device-class specific

There is no general guest GPU path. The community Mesa stack is SoC-specific:
Turnip covers Adreno GPUs only, and Mali devices (including the S10e used for
this project's device evidence) expose no usable driver to a glibc guest, so
guest rendering and inference stay on the CPU there. NPU acceleration has no
guest path: the platform NN API is deprecated and vendor runtimes are
host-side only. CPU inference with small models through the capability bridge
is feasible and was verified on the S10e — see
`docs/research/guest-local-llm-spike.md`.

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

### Autostart is app-visible, plus an opt-in boot start (ADR-0013, ADR-0037)

Linux starts when the user opens the app: an Activity foreground event calls
`RuntimeHostService.ensureRunning`, which starts a session only when none is
live, and the foreground service then keeps the guest running while the app is
backgrounded. Since ADR-0037 the user may additionally enable **Start Linux at
boot** on the Linux system screen (default OFF): the unexported
`BootStartReceiver` then answers `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`,
re-checks the persisted install state, and calls the same idempotent
ensure-running boundary. What this still deliberately does **not** do:

- No job, alarm, or sticky-restart resurrection, and no boot start without the
  opt-in — the default-OFF install never wakes at boot.
- `LOCKED_BOOT_COMPLETED` is never requested: credential-encrypted storage is
  unavailable before the first unlock, and every persisted signal the boot
  gate reads lives there.
- Boot delivery is best-effort and OEM-dependent. Stock Android does not
  deliver `BOOT_COMPLETED` to a stopped app, but the 2026-09-20 S10e run
  (OneUI 4.x, API 31) observed delivery to an app with `stopped=true` — on
  that OEM force-stop does not keep the session off across a reboot; the
  toggle is the reliable off. MIUI devices gate the broadcast behind a
  separate Autostart switch; Samsung sleeping-apps and battery policy can
  still kill the foreground service after it starts. The battery-optimization
  recommendation card on the same screen mitigates this but does not
  guarantee delivery or survival.
- A boot trigger that finds an incomplete install does nothing: the receiver
  never installs or downloads — it logs a typed skip, and the next
  app-visible launch runs the normal setup pipeline. A service bridge absent
  at boot (never attempted, or failed — add-on install outcomes are not
  persisted) also skips, because a session started without it could never
  run the service manager and the idempotent boundary would then leave that
  live session alone.
- No in-app start/stop control. The ongoing notification and its `Stop` action
  remain because Android requires foreground work to be user-visible and
  stoppable. Tapping `Stop` while the app is already in the foreground leaves
  the runtime stopped until the next foreground event (leaving and reopening
  the app); there is no control that starts it in place.
- An uninstalled or failed runtime is retried once per foreground event, so the
  notification can re-appear with the same honest failure until the missing
  payload (the curated OpenSSH add-on) is installed.
- On Android 12+ a foreground-service start must come from a foreground app
  context or an explicit platform exemption — `BOOT_COMPLETED` is one — and
  the declared `specialUse` type is not among the FGS types Android 15 blocks
  from that broadcast. API levels and OEM builds newer than the tested
  devices remain unverified: the app targets API 37 but has only run on
  API 29/31 (see `docs/test-plan.md`).

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

### Web-app favicon fallback (ADR-0015, amended 2026-09-21)

A launcher tile without a user-picked image reads the app's own document once for
its icon declarations (`<link rel="icon">` and friends) and asks only for the URLs
that resolve to `http://127.0.0.1:<guestPort>/`; the conventional `/favicon.ico`
remains the fallback. The limitations are deliberate:

- **Same-origin discovery only.** The document is untrusted input: a declaration
  that names another scheme, host, or port is refused, not followed, and at most
  three declared candidates are tried. There is no `<base>` handling, no CSS or
  script parsing, and no URL from any other source.
- **Whatever the platform decoder accepts.** The payload is decoded with `BitmapFactory`, so a vector or otherwise undecodable "favicon" (for example an SVG, including a declared one) falls back to the monogram.
- **Bounded reads, not a browser.** The document read stops at 256 KiB and an image at 2 MiB; the image must fit a 1024 px source cap and decodes downsampled to 256 px.
- **Not persisted.** The decoded image lives in memory for the life of the app process; there is no disk cache, so it is fetched once per app per process.
- **One retry, on a real event.** The launcher's first attempt usually happens before the app's server is running. A missing favicon is retried only when the app's own surface proves the endpoint answers, so an app that is never opened keeps its monogram.
- **Never fetched for an app with a user image.** The user's own pick is primary and is not replaced, and no request is made in that case.
- **Silent by design.** Unreachable, timed out, redirected, too large, or not an image are all the same outcome: the tile keeps its monogram, with no error, toast, or state change.

### Terminal tabs and terminal-command apps (ADR-0054)

The terminal is a set of host-owned sessions (ADR-0033 extended): an initial
shell tab plus tabs opened from the terminal's own options menu. Every tab,
including the initial shell, can be closed. A clean guest `exit` closes its
tab; if all tabs close, Linux keeps running and the surface shows an
empty-terminal prompt while ⋮ still offers `New`.

A launcher terminal-command app is a second kind of terminal surface, not a tab
of the built-in terminal (ADR-0054, isolation amendment): it opens in a window
of its own titled after the app, owns exactly its session, and its options menu
is a single `Close`. The built-in terminal lists its shell tabs only, so one app
can never appear inside another surface's menu.

The bounds are deliberate:

- **Five tabs at a time.** Every tab is a real `WebView` running xterm, so the
  cap protects memory on a phone; it counts every host session, shell or command
  app, and at the cap the terminal's `New` action is hidden and an open attempt
  answers a typed `TAB_LIMIT` reason. The terminal's ⋮ list is just `New` and
  its shell tabs, numbered 1, 2, …; tapping one opens its `Open` / `Close`
  choices.
- **No scrollback restore.** A tab keeps its own scrollback while it is alive,
  but Activity recreation, process death, and tab switching by close/reopen
  start from an empty screen — the same limitation ADR-0033 documented. Guest
  `tmux`/`screen` remains the answer for durable scrollback.
- **One live session per command app.** Opening a command app again returns to
  its own window, whose live session is selected instead of duplicated; a clean
  exit closes the session and the window shows its idle state, and `Close` in
  its menu ends it manually.
- **A command app is a foreground command, not a service.** A clean exit closes
  its session automatically. A transport drop is different: its window stays
  open with an explicit reconnect action, which re-runs the command on a fresh
  PTY; nothing restarts it automatically, and nothing runs it at boot or in the
  background on its own.
- **The notification reports the selected tab.** With several tabs open, the
  terminal line and the Reconnect action describe the tab the surface is on, not
  every tab at once.
- **One printable line.** The stored command is validated as a single line
  without control characters (512 characters max), so a multi-step command must
  be written the way a shell accepts it, for example
  `sh -c 'cd /root; ls; exec bash'`. The guest shell owns quoting, expansion,
  and exit behaviour.
- **A PTY is always requested.** A command that behaves differently without a
  terminal still gets one, because the feature exists to land the user in an
  interactive session.
- **App-private registration.** Terminal-command apps live in the app's own
  private preferences, like web apps: they are not part of the guest backup
  (ADR-0044), are not visible to Linux, and are removed with the app's data.
- **Deleting an app closes its session.** The app's window and its live session
  end with the definition: an isolated surface exposes exactly one app, so a
  session it could no longer reach would be unclosable. Removing the definition
  never deletes anything inside Linux.

### Native-code distribution

The future APK will likely need a small native execution bridge even though the Linux rootfs is downloaded. Any JNI/native `.so` must support the device ABI and 16 KB page-size devices. Native files must be reproducibly built, checksum/signature verified, licensed, and tested.

### License and distribution

PRoot is GPL-2.0-or-later. Packaging and distributing it requires GPLv2+ source/notice obligations, and the combined-work implications need legal review before distribution. Invoking PRoot as a separate executable is intended to keep the clearest practical GPL boundary, but **no GPL-cleanliness claim is made**. Dropbear (permissive/MIT-style) and OpenSSH (BSD) are includable in the curated rootfs, but their license/notice/attribution obligations apply. The app's own code is MIT-licensed (root `LICENSE`).

### Google Play

Downloading and executing a Linux runtime from a non-Play source may trigger Device and Network Abuse or dynamic-code-loading review. PRoot's status as a VM/interpreter exception is not assumed. Start with a controlled distribution channel (sideload/F-Droid) and request policy guidance before committing to Play. No Google Play compliance or approval is claimed.

## Target profile limitations

- The first profile has a pinned Ubuntu Base ARM64 rootfs install proof, a supervised PRoot launch path, and a working guest-native SSH endpoint through the curated OpenSSH add-on (ADR-0010). The desktop/web surface is still unavailable, so only the terminal path is exercised end-to-end.
- A second profile is allowed only after the first profile passes the device test matrix.
- Each profile must document its runtime version, architecture/libc requirements, native dependencies, browser requirements, service assumptions, persistence, and unsupported features.
- No target profile is supported merely because its CLI starts; its web UI, state persistence, process recovery, and security boundary must also pass validation.

## Unsupported future features unless explicitly approved

- Arbitrary user-provided rootfs/image/URL.
- Arbitrary shell command entered into a privileged/native execution API. The one
  bounded exception is a launcher terminal-command app (ADR-0054): a
  user-authored single-line command is stored in app-private preferences and
  handed to the app's own guest Linux over the pinned loopback SSH session —
  the same shell the user could type it into. It is never executed by the
  Android host, never reaches a `Runtime.exec`/`ProcessBuilder`/Binder execution
  path, and no app data is interpolated into it. Arbitrary commands into
  host-side execution APIs remain unsupported.
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
