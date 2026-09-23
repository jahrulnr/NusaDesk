<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="NusaDesk logo" width="180">
</p>

<h1 align="center">NusaDesk</h1>

<p align="center">
  <strong>Your Linux workspace, at home on Android.</strong><br>
  A calm, launcher-first desktop for a terminal and the web apps you choose.
</p>

<p align="center">
  <a href="docs/architecture.md">Architecture</a> ·
  <a href="docs/roadmap.md">Roadmap</a> ·
  <a href="docs/limitations.md">Limitations</a> ·
  <a href="docs/test-plan.md">Test plan</a>
</p>

## Overview

NusaDesk brings a focused Linux workspace to Android without trying to imitate a
full desktop operating system.

Open the app and you arrive at a simple launcher. From there, you can open a
local Linux terminal or launch the web apps you register yourself. Linux runs
quietly in the background, while NusaDesk keeps the user-facing experience
clear: setup when needed, a useful workspace when ready, and honest status when
something needs attention.

NusaDesk is intentionally curated rather than universal. It is built around a
small, tested runtime foundation—not a promise to run every Linux application.

## Quick start

### Check the project locally

```bash
make check
```

This runs the unit tests, Android lint, and a debug build.

`make test` also runs the pure JavaScript terminal gesture-policy suite, so
Node.js is required for that target in addition to the Android SDK/JDK.

### Build the debug APK

```bash
make build
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Install on a connected Android device or emulator

```bash
make push
```

`make push` builds first and installs the debug APK on every connected `adb`
target in the `device` state. To install manually instead:

```bash
./gradlew installDebug
```

A local Android SDK with the project’s required platform and build tools is
needed. See the [technical documentation](docs/) for the full environment and
runtime requirements.

## Features

### A launcher that feels like home

- A clean, launcher-first home surface.
- Search across the apps available in the workspace.
- Curated system surfaces alongside your own web apps.
- A Logs surface that reads the guest like a real Linux box: the session
  console at `/var/log/lw/boot.log` (one previous boot kept) plus a live tail
  of each service's `systemctl` journal — all size-bounded and viewed in the
  same terminal surface (ADR-0034).
- Responsive layouts for phones, landscape, tablets, and larger font sizes.

### A local Linux terminal

- A real guest shell presented through a mobile-friendly terminal.
- Built-in accessory keys for touch devices.
- Terminal text is copied with the platform's own long-press selection and
  Copy action mode — the app ships no custom clipboard code.
- Terminal buffer and SSH attachment stay intact across navigation and screen
  rotation; the existing surface only refits to the new dimensions.
- A workspace folder you choose on the device appears at `~/nusadesk` inside
  Linux, so the same files are editable from Android and from the guest shell.
  The folder is picked in an in-app browser over shared storage on every Android
  version: Android 11+ uses the all-files grant it already holds, and Android 10
  uses the platform's legacy storage model after one permission prompt (ADR-0047).
  Until something is picked, Linux uses the app's own media folder,
  `Android/media/<pkg>/nusadesk`, which needs no permission at all.
- Guest services are managed with a familiar `systemctl`/`service` surface:
  enabled services start with the Linux session and stop when it stops — for
  system units and for `systemctl --user` units alike (the session runs a
  system manager and a product-owned user manager; ADR-0024. A curated
  replacement, not real systemd — socket activation, timers, and systemd
  sandboxing are out of scope).
- Multi-service projects can be declared with a bounded `udocker compose`
  subset — for example `udocker compose -f ~/nusadesk/demo/compose.yaml up -d`
  in the guest terminal (ADR-0025, device-verified on the Samsung S10e). It
  is not Docker: no network isolation or service DNS, `ports` are rejected
  (udocker/PRoot cannot enforce a loopback-only bind), and bind sources are
  limited to the workspace folder.
- Reconnect and failure states that explain what is happening instead of
  pretending everything is running.

### Reaching Android from the guest

- The Linux guest can call a small, authenticated capability bridge over a
  loopback-only JSON socket. The endpoint and a per-session token are pinned in
  `/run/nusadesk/android-bridge.env`; the CLI never prints the token, and no
  LAN or wildcard bind exists (ADR-0030). The bridge dispatches to registered
  capability modules, each validating its own bounded parameters and answering
  one typed error taxonomy (ADR-0049).
- `nusadesk-android` wraps that contract with fixed commands only: `bridge info`
  for discovery, live media control (`media start [--camera|--microphone]`,
  `media status`, `media stop`), and bounded calendar access (`calendar list`,
  `calendar add`, `calendar update`, `calendar delete`). There is no generic
  `call <method>` passthrough (ADR-0031, ADR-0032).
- All 57 upstream Termux:API client commands are installed in `/usr/local/bin`
  and answered by the same bridge — sensors, wifi, SMS and contacts, camera
  and microphone capture, SAF file access, keystore, notifications, TTS,
  dialog, media playback, USB, and more — so scripts written for the
  `termux-*` clients keep working. What the hardware or platform cannot do
  answers a typed absence (wifi toggling on Android 10+, infrared without an
  emitter) rather than a fake success, and the few commands waiting on a
  physical input are listed honestly in the limitations. The Termux:API *app*
  cannot serve this product — it only accepts callers sharing the Termux
  signing key and UID — and no Termux app is required (ADR-0036, ADR-0049).
- Bluetooth is a NusaDesk-native surface (ADR-0050) because upstream
  Termux:API has no bluetooth command: the guest gets `nusadesk-bt` with
  `status`, `devices`, `discover`, `pair`/`unpair` (unpair answers a typed
  absence), `enable`/`discoverable` (through the platform's own consent
  dialogs), `le-scan`, `advertise`, `gatt …` (client and a fixed server), and
  `rfcomm …` for a serial link. Every call is bounded and every grant is
  checked per call; the platform's hard limits (adapter MAC address,
  programmatic enable/disable, OBEX/PAN/HID host) are reported as typed
  absences rather than fake features.
- Wifi gained the pieces the platform actually allows (ADR-0051):
  `wifi.hotspot.start|stop|status` opens a local-only hotspot (no internet) for
  device-to-device work, `wifi.suggest.add|remove|list` publishes advisory
  network suggestions, and `wifi.lock.acquire|release` keeps the radio awake
  for a transfer. The generated `nusadesk-serve` serves the workspace over HTTP
  on the LAN (directory listing plus bounded upload, LAN warning, best-effort
  wifi lock and a notification carrying the URL), and `nusadesk-net` reports
  the LAN address and bridge status. Toggling wifi stays a typed absence
  because the platform forbids it.
- The last four declarations now back real capabilities (ADR-0052):
  `usage.query|events|standby` (bounded usage reads behind the usage-access
  special grant), `packages.list|info|launch` (enumeration through
  `QUERY_ALL_PACKAGES`, with a typed `packages-launch-blocked` when the
  platform refuses a background start), `overlay.show|update|status|hide` (one
  text plate behind `SYSTEM_ALERT_WINDOW`), and
  `location.background.start|poll|stop` (background fixes behind "Allow all the
  time", with a visible `FOREGROUND_SERVICE_LOCATION` notification and a Stop
  action, matching the shape upstream Termux:API is heading toward). Three
  dead declarations are removed with this change:
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and
  `DOWNLOAD_WITHOUT_NOTIFICATION`.
- USB pass-through is host-mediated but guest-owned: `nusadesk-usb list`
  enumerates attached devices on the host's USB port, and
  `nusadesk-usb probe <vid>:<pid>` /
  `nusadesk-usb exec <vid>:<pid> -- <command>` ask the platform's own consent
  dialog, then hand the device descriptor into the guest over SCM_RIGHTS, so
  every transfer stays the guest's. The executed command receives the
  descriptor as `NUSADESK_USB_FD` — enough for a guest-side adb client built
  on a USB-fd-capable transport (ADR-0041).
- The same pass-through becomes a driver for the stock guest `adb`
  (ADR-0042): `/usr/local/bin/adb` autostarts `nusadesk-usbd`, compiles and
  loads a small libusb shim once `gcc` and the libusb headers are present,
  and runs the real adb with `ADB_LIBUSB=1` — so `adb devices` lists the
  devices attached to the host's USB port and `adb shell` / `push` /
  `install` operate on them. Android denies the kobject-uevent netlink socket
  libusb's hotplug monitor needs, so the driver expects a libusb build with
  that failure downgraded under `/opt/nusadesk/lib` (recipe in ADR-0042);
  without a live session (or without the shim) the wrapper falls back to the
  plain adb.
- Back up the whole guest — or just what you changed — from **System →
  Settings → Backup & restore** (ADR-0044): a full dump captures the runtime,
  the guest add-on and the session state and can rebuild a fresh install; a
  home or custom dump captures `/root` + `/home` or the top-level paths you
  check (never the pseudo trees) and merges back into an existing session.
  The archive is a plain streaming `tar.gz` you pick through the system file
  dialog — it may contain secrets (host keys, your files), so keep it
  somewhere you trust.
- `android-cli` gives the session the phone's own tooling, natively
  (ADR-0045): the device's `/system/bin`, `/system/lib64` and the Bionic
  runtime tree `/apex` are bound into the guest, and `android-cli sh`,
  `android-cli getprop`, `android-cli toolbox <applet>`,
  `android-cli toybox <applet>`, `android-cli su` (the device's own
  superuser — absent on a stock device, the user's own root door on a rooted
  one), and `android-cli exec <path>` run them under a collision-free
  namespace — never on `PATH`, so Ubuntu's `sh`/`ls`/`ps` stay intact. Every
  call reports its tier (`tier=app source=native`); verbs the platform
  reserves for another uid (screencap, input, pm, am, dumpsys) fail with the
  platform's own message, and `android-cli doctor` shows what exists on this
  device.
- Live media is the camera and/or microphone streamed as H.264/AAC over RTSP on
  loopback while the guest session and the visible media notification are
  active. No capture file is ever written — save one yourself from the stream if
  you need it.
- Calendar access is bounded in both directions: a fixed seven-day read (at most
  50 rows, no description/attendee/organizer fields) and validated writes into a
  calendar the user can write. No attendee row is written and no invitation is
  sent.
- Consent is real, not assumed: a missing or revoked grant returns a typed
  result the script can report, and `permission.request` runs the platform
  runtime dialog or the matching Settings screen (write-settings,
  notification access, all-files) through a foreground host — the same
  bounded, one-at-a-time host that runs dialogs, SAF pickers, the share
  chooser, fingerprint, speech recognition, NFC reader mode, and
  while-in-use camera/microphone capture. `termux-sms-send` and
  `termux-telephony-call` are implemented and device-verified on the
  SIM-equipped S7 Edge; what still waits on hardware or a platform backend is
  listed honestly in the limitations (`termux-speech-to-text`, an NDEF tag for
  `termux-nfc`, and media-provider reach for `termux-media-scan`).

### Your web apps, your workspace

- Add, edit, and remove web apps by name, icon, and local guest port.
- Open each app from its own launcher tile.
- Reachability checks before loading the app surface.
- Clear unavailable, failed, and loaded states.
- User-initiated new windows stay inside bounded tabs for that app, with the
  root page always reachable from the taskbar and Android Back (ADR-0048).

### Quiet background runtime

- Linux starts when the app is opened and continues behind a visible Android
  notification.
- One clear Stop action in the system notification.
- Optional "Start Linux at boot": when you turn it on, the session comes back
  after a reboot or an app update, re-checked against the installed runtime
  and never started without your opt-in (ADR-0037).
- A battery-optimization card explains Android's background limits and can ask
  Android for the exemption directly, from one explicit tap.
- A launcher banner (plus, where Android already allows notifications, a
  notification) points to a newer signed release; the check runs only while
  the app is open, at most once a day, and one tap downloads the verified
  update in a popup with live progress — ending at Android's own install
  confirmation (ADR-0038, ADR-0039).
- Curated setup flow for the Linux foundation and terminal component.
- No remote-host SSH UI and no LAN-sharing mode in the current product.

## Documentation

The README is intentionally product-focused. Technical details live in the
documentation set:

- [Architecture](docs/architecture.md) — runtime boundaries, layers, and data flow.
- [Limitations](docs/limitations.md) — current constraints, unsupported scenarios,
  and evidence boundaries.
- [Roadmap](docs/roadmap.md) — planned product slices and compatibility gates.
- [Test plan](docs/test-plan.md) — automated, emulator, and physical-device
  verification.
- [Architecture decisions](docs/decisions/) — the reasoning behind major
  product and runtime choices.
- [Research notes](docs/research-findings.md) — source material and technical
  findings behind the foundation; spike records live under
  [docs/research](docs/research/).
- [Contributor and agent guidance](AGENTS.md) — repository rules and safety
  constraints.
- [Security policy](SECURITY.md) — vulnerability reporting, security scope, and
  threat-model boundaries.

## Honest boundaries

NusaDesk does not currently claim full Linux compatibility, guaranteed 24/7
runtime survival, support for every Android device, LAN exposure, arbitrary
package installation, or Google Play approval. Guest access to Android APIs is
limited to the reviewed capability adapters the ADRs describe — declaring a
permission does not make an API available to Linux, and unsupported operations
answer a typed error instead of a fake result. See the
[limitations](docs/limitations.md) and [test plan](docs/test-plan.md) before
treating the current build as a production release.

## License and distribution

NusaDesk's own code is MIT-licensed — see [LICENSE](LICENSE). Release APKs
published on the project's GitHub channel are signed with a dedicated
release key whose certificate fingerprint is pinned in CI (ADR-0040), so
successive releases update each other in place (manually or through the
in-app flow). Builds published before that decision were signed ad hoc and
need a one-time reinstall to join the update chain. The final distribution
model is still under review; third-party runtime components carry their own
license and notice obligations — see the [technical documentation](docs/)
before redistribution.
