# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.7.0] - 2026-09-23

### Added

- Add the full Termux:API client parity surface (ADR-0049): all 57 upstream
  `termux-*` commands are generated into the guest's `/usr/local/bin` from a
  domain-owned catalog over a shared `termux_compat` runtime, each one a thin
  flag-to-bridge translation that prints the upstream JSON shape and keeps the
  upstream exit codes (0 result, 1 typed bridge error, 2 usage). New domains:
  device state (`termux-vibrate`, `termux-torch`, `termux-volume`,
  `termux-brightness`, `termux-audio-info`), text and notifications
  (`termux-clipboard-get/set`, `termux-toast`, `termux-notification`,
  `termux-notification-channel`, `termux-notification-list`,
  `termux-notification-remove`), speech and dialog (`termux-tts-engines`,
  `termux-tts-speak`, `termux-speech-to-text`, `termux-dialog`), capture
  (`termux-camera-info`, `termux-camera-photo`, `termux-microphone-record`),
  storage (the nine `termux-saf-*` commands, `termux-storage-get`,
  `termux-share`, `termux-media-scan`, `termux-wallpaper`, `termux-download`),
  comms (`termux-sms-send`, `termux-telephony-call`, `termux-keystore`,
  `termux-job-scheduler`), the full sensor catalogue (`termux-sensor -l`
  lists 42 sensors on the test device), wifi (`termux-wifi-connectioninfo`,
  `termux-wifi-scaninfo`, `termux-wifi-enable`), infrared
  (`termux-infrared-frequencies`, `termux-infrared-transmit`), media and NFC
  (`termux-media-player`, `termux-nfc`), `termux-usb`, `termux-fingerprint`,
  the `termux-api-start`/`termux-api-stop` no-ops, and the deprecated
  `termux-sms-inbox` alias. Commands the hardware or platform cannot serve
  answer a typed absence — `wifi-toggle-unsupported` on API 29+ and
  `infrared-unavailable` on a device with no IR emitter — never a fake
  success.
- Add the bridge v2 foundation (ADR-0049): the request handler dispatches to
  registered `CapabilityModule`s behind a single `CapabilityModules`
  registry; every module validates its `params` through `CapabilityParams`
  typed getters and `rejectUnknown`, so a misspelled key fails closed as
  `invalid-argument` instead of being silently ignored; the error taxonomy is
  stable and typed (`<capability>-permission-required`/`-permission-denied`/
  `-unavailable`/`-busy`/`-timeout`, `foreground-required`,
  `invalid-argument`, `action-unsupported`) with a human hint after `:`; and
  the request bounds grow to a 64 KiB frame, 16 parameter keys, and 8192-char
  string values.
- Add user-visible consent and special access: `bridge.permissions` reports
  every permission's state plus the Settings action that opens each missing
  grant, and `permission.request` runs either the platform runtime dialog or
  the matching Settings screen through the foreground host with a bounded
  wait. Device-verified on the Samsung S10e: the CAMERA runtime dialog
  granted on tap (`dumpsys package` shows the grant), and the WRITE_SETTINGS
  screen granted after the toggle, after which `termux-brightness` set the
  real system value.
- Add the foreground host (`CapabilityForegroundHost` +
  `CapabilityForegroundActivity`): one transparent Activity runs the
  operations the platform only allows a visible app — runtime consent,
  `termux-dialog`, fingerprint, SAF pickers, the share chooser, speech
  recognition, NFC reader mode, and the while-in-use camera/microphone
  capture service — one operation at a time under a bounded wait, with typed
  `foreground-required` and `<capability>-busy` refusals.
- Add the guest-file staging rule: a bridge method that reads or writes a
  guest file receives a path inside the active rootfs resolved by
  `GuestFilePathResolver` (absolute, no traversal, symlinks refused), and the
  guest script moves the result to the user's destination because only the
  guest knows how a bind mount maps.
- Declare the parity permission and component set: VIBRATE, SEND_SMS,
  CALL_PHONE, SET_WALLPAPER, TRANSMIT_IR, NFC, WRITE_SETTINGS, USE_BIOMETRIC,
  ACCESS_WIFI_STATE/CHANGE_WIFI_STATE/NEARBY_WIFI_DEVICES,
  MODIFY_AUDIO_SETTINGS, FOREGROUND_SERVICE_MEDIA_PLAYBACK,
  DOWNLOAD_WITHOUT_NOTIFICATION; the notification-listener service behind
  `termux-notification-list`, the capture and media-playback foreground
  services, and the job-scheduler service. `READ_MEDIA_*` stays undeclared:
  nothing queries MediaStore image/video/audio.

### Changed

- The Termux compatibility layer grows from the seven ADR-0036 commands to
  the full generated set; the previously installed commands keep their
  behaviour through the same shared runtime, and `docs/termux-compat.md`
  inside the guest is regenerated from the same command catalogue.
- `termux-location` honours `-p provider` and `-r`, reads with a 30 s bounded
  window (was 5 s), picks the deliverable provider whose last-known fix is
  freshest instead of preferring GPS first, and falls back to a stale-marked
  last-known fix (with its age) before reporting `location-timeout`.
- The capability dialogs render in the app's own surface: the foreground host
  is pinned to `Theme.Translucent.NoTitleBar`, so every widget now builds a
  plain `android.app.Dialog` on `NusaDeskDialogTheme` with the launcher's dark,
  teal-accented shell instead of the pre-Material platform look, and the window
  height is `WRAP_CONTENT` so the action row keeps its 48 dp touch target when
  the IME resizes the dialog. The result contract (`code`, `text`,
  `values_json`) is unchanged.

### Fixed

- `termux-sms-send` no longer reports a false "unconfirmed" for a message that
  actually went out: when the platform never delivers the per-part result
  broadcast, the module confirms the dispatch against `content://sms/sent`
  before calling it unsent. That confirmation reads the sent box, so it runs
  only when `READ_SMS` is granted — the send path itself never demands read
  access, and without the grant a dispatched part stays honestly
  `unconfirmed`.
- `termux-dialog` no longer collapses its action row when the keyboard opens:
  the height was a fixed pre-show measurement, so an IME resize squeezed the
  buttons to a sliver; the window is `WRAP_CONTENT` with the content slot
  yielding instead — verified on the S7 Edge with the keyboard open (every
  widget reports 48 dp button bounds).
- `termux-media-player` plays through to the end: the FD-based data source
  made every track stop within ~35 ms on device, so the module opens the
  resolved guest staging path instead — position advanced 0 → 3.73 s on a
  6 s WAV.
- `termux-share` grants the chooser read access to the shared content URI and
  guesses the MIME type — the two defects the device run exposed; Chrome then
  displayed the shared text.
- `termux-job-scheduler` executes the scheduled guest script through the
  session's own SSH path when the job fires; `cmd jobscheduler run -f`
  produced `/tmp/job-ran.txt` on device.

### Still open in the ledger

Per `docs/evidence/termux-parity-matrix.md` — implemented, with the honest
state recorded there. The S7 Edge pass on 2026-09-23 closed `termux-sms-send`,
`termux-telephony-call`, and the non-empty `termux-sms-list` /
`termux-telephony-deviceinfo` paths; the S10e closed `termux-fingerprint` and
recorded a fresh `termux-location` fix:

- `termux-speech-to-text` (WIP): neither project device has a usable
  recognizer backend (the S10e answers `speech-unavailable:network error`, the
  S7 Edge has no `voice_recognition_service`); the command fails typed instead
  of pretending.
- `termux-nfc` (PARTIAL): a real tag was detected in reader mode on the S7 Edge
  and answered the upstream "Wrong Technology" shape — that tag is not NDEF, so
  an NDEF read/write still needs an NDEF tag.
- `termux-media-scan` (PARTIAL): runs, but only indexes paths the platform
  media provider can read — workspace-bound paths, not the app-private rootfs.

## [0.6.4] - 2026-09-22

### Added

- Add internal web-app tabs (ADR-0048): user-gesture `target="_blank"` and
  `window.open()` requests create bounded child tabs scoped to the registered
  app, while the root page remains available. The taskbar menu can select or
  close child tabs, Android Back follows the selected page history before
  closing a child or returning to the launcher, and background popups remain
  refused. Every tab keeps the app's exact loopback-origin boundary.

## [0.6.3] - 2026-09-21

### Fixed

- The update check now asks at every fresh launch and polls while the app is
  open (ADR-0046 amendment): a device with the official 0.6.1 installed from
  the GitHub page never showed the 0.6.2 banner — force-stop included —
  because every launch inside the 30 minute floor was silently skipped and
  nothing re-asked while the app stayed open (the S7 timeline measured the
  missed window at 46 seconds). A fresh process — reboot, force-stop, first
  launch — now checks once whatever the store says, a foreground tick every
  5 minutes re-asks against a tightened 15 minute floor, and the poll is
  dropped in `onStop` so a backgrounded app spends no wakeups

## [0.6.2] - 2026-09-21

### Fixed

- The launcher tile now uses the icon a web app declares in its own document
  (`<link rel="icon">` and friends, same-origin only, at most three candidates)
  before the conventional `/favicon.ico`, which many apps do not serve: the
  NusaShell tile declares a 512x512 PNG of 340 KiB and answers `404` for
  `/favicon.ico`, so the tile kept its monogram while a perfectly decodable icon
  sat one attribute away. The byte caps were raised to match that measurement —
  2 MiB for an image, 256 KiB for the document read — while the decoded bitmap
  stays bounded by the tile. Device-verified on the S7 Edge: the NusaShell tile
  shows its declared icon (ADR-0015 amendment)

## [0.6.1] - 2026-09-21

### Fixed

- Fix the banner's Install action (ADR-0039 wiring): the update check parsed the
  release asset — HTTPS URL, channel digest, size — but nothing ever persisted
  it, so every Install tap answered "The channel did not report the APK over
  HTTPS, so the in-app install cannot start" and only the browser hand-off
  worked. The coordinator now records the asset from the check result; a device
  run confirms the dialog proceeds to the download ("v0.6.0 • 14.5 MB") with the
  recorded digest matching the published asset, and a wiring guard pins the call

## [0.6.0] - 2026-09-21

### Added

- Add an in-app workspace folder browser (ADR-0047): the workspace action opens
  a path-based browser written in this repository — a path header, the folder
  list, and Cancel / Use this folder — over shared storage on every Android
  version. Android 11+ uses the all-files grant it already holds; Android 10
  uses the platform's legacy storage model after one permission prompt, measured
  on the S7 Edge: without it `/sdcard` reads as `list=null canRead=false`, with
  it the app lists 171 entries and can create files there. Every picked path is
  validated and write-probed before it is stored, and the app's own media folder
  (`Android/media/<pkg>/nusadesk`) stays the default workspace until something is
  picked. The storage guard is amended accordingly:
  `READ`/`WRITE_EXTERNAL_STORAGE` are declared bounded to API 29 together with
  `requestLegacyExternalStorage`, while all-files access remains the only broad
  grant on Android 11+. A third-party picker library was evaluated and rejected
  (its selection is a marked checkbox rather than the folder in view, and it
  brings its own permission gate); the rationale sits next to the dependency
  block in `app/build.gradle`
- Add `android-cli` to the guest (ADR-0045): the session binds the device's
  own tool trees — `/system/bin`, `/system/lib64` and the Bionic runtime tree
  `/apex`, without which no Bionic exec resolves its linker on Android 11+ —
  and the dispatcher exposes them under a collision-free namespace — `sh`
  (the device's mksh), `getprop` (toolbox), `toolbox <applet>`,
  `toybox <applet>`, `su` (the device's own superuser, typed as absent when
  the device has none), and `exec <path>` for anything else the platform lets
  an app uid run — with `tier=app source=native` reported on every call
  (quiet with `-q`), child exit codes propagated exactly, the platform's own
  denial text surfaced verbatim (screencap, input, pm, am, dumpsys belong to
  a higher tier, and the optional shell tier through the device's own adbd is
  documented rather than hidden), and a `doctor` that lists what actually
  exists. `/system/bin` never joins the guest PATH, so no Ubuntu tool is
  shadowed and every entry stays reachable by absolute path.

### Fixed

- Fix the stored workspace on Android 10 and for the in-app picker (ADR-0047):
  a `picked-path` choice read back as "no workspace" because the store's restore
  only understood SAF tree document ids, and the Android 10 card ignored a
  stored choice entirely — so a folder picked in the browser looked like it had
  never been saved
- Make the SAF backup round trip actually complete on a real guest (ADR-0044):
  a create-document round trip that recreates the Activity — which the platform
  does while the picker is in front — no longer loses the stashed export
  selection, so the export starts instead of answering "cancelled" and leaving
  an empty document behind; the System sub-page survives the same recreation
- The export walk emits a bind mount point's entry and never lists it: the
  guest's add-on overlays carry mode 000, which failed every Everything export
  with an `io-failure`, and the device's own tool trees (`/system`, `/apex`)
  stay on the device instead of travelling inside a guest backup
- The restore accepts an archive this app produced: the shared extractor's
  entry cap was sized for the curated rootfs payload (20,000) while a real
  guest backup carries 53,226 entries, and directory modes are applied after
  the payload instead of before it, so read-only directories (the guest's Go
  module cache holds 597 of them) no longer fail the restore
- Fix the update check's cadence (ADR-0046): the 24 hour floor that let a
  single attempt silence the check for a whole day is now a 30 minute floor
  applied to every outcome, and a changed installed version — a sideloaded or
  assisted install — is due immediately instead of inheriting the previous
  build's throttle. Observed on the S10e: v0.5.0 was published 6.5 hours
  after the device's last recorded attempt, so the banner could not appear
  until the next day however often the app was reopened, force-stop
  included.

## [0.5.0] - 2026-09-20

### Added

- Add guest backup and restore (ADR-0044): the System → Settings → Backup &
  restore page exports the Ubuntu guest as a plain streaming `tar.gz` —
  full, home, or a custom set of top-level guest paths picked from an
  allowlist — through SAF, and imports one back. Full restores bootstrap a
  clean install with an atomic swap and a rollback slot; home and custom
  restores merge into an existing runtime, one top-level path at a time
  (typed `runtime-required` when there is none). Device-bound credentials
  never travel (the Keystore-wrapped root credential is re-asserted at
  session start), the workspace folder and the pseudo trees are excluded,
  and every import validates the manifest, the curated runtime id and the
  archive paths before touching anything, with bounded failures
  (`manifest-missing`, `unsupported-format`, `runtime-mismatch`,
  `unsafe-archive`, `storage-full`, `io-failure`, `busy`)
- Add USB pass-through to the capability bridge (ADR-0041): `usb.list`
  enumerates attached devices and `usb.open` runs the platform's own
  per-device consent dialog before delivering the usbfs descriptor to the
  guest's abstract unix socket with SCM_RIGHTS; the guest `nusadesk-usb` CLI
  adds `list`, `probe` (a GET_DESCRIPTOR read through `USBDEVFS_CONTROL` as
  the end-to-end proof), and `exec` (runs a command with `NUSADESK_USB_FD`
  set and the descriptor passed through), so a guest-side adb client can
  target a device attached to the host's USB port while every transfer stays
  in the guest
- Add the guest adb driver (ADR-0042): `nusadesk-usbd` owns the session's
  opened device descriptors and serves them over an abstract socket, a
  generated `libusb-shim.c` (compiled once by the wrapper and loaded with
  `LD_PRELOAD`) presents those devices to the stock adb's libusb backend, and
  `/usr/local/bin/adb` wires it together with `ADB_LIBUSB=1` — so
  `adb devices`, `adb shell`, `adb push`, and `adb install` work against
  devices attached to the host's USB port, with a plain-adb fallback whenever
  the session or the shim is unavailable. Device-verified on the S10e with an
  S7 Edge attached: `adb devices` lists it (`1-1`, model `SM_G935F`) and
  `adb shell` runs on it. The driver expects a libusb build with its hotplug
  monitor failure downgraded (Android denies the kobject-uevent netlink
  socket); today that build is made in the guest, with shipping it as a
  verified artifact as follow-up work

### Changed

- Restructure the System screen into an Android-Settings-style hub
  (ADR-0043): it now opens with three rows — Settings (workspace, battery
  optimization, boot start, app settings), One-click install (a curated
  templates list), and About NusaDesk (system state, technical details, the
  GitHub page, and how it works) — each on its own page with a back row, and
  the system back action on a sub-page returns to the hub before it leaves
  the screen. The first template entries state honestly that the USB/adb
  driver and the Termux compatibility layer ship with every session;
  installable add-ons (starting with the driver's prebuilt artifacts) arrive
  with the provisioning slice

## [0.4.0] - 2026-09-20

### Added

- Add an opt-in "Start Linux at boot" toggle on the Linux system screen (default OFF): one unexported receiver listens to `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, re-checks the persisted install gates, and reuses the idempotent ensure-running boundary; jobs, alarms, and silent always-on stay forbidden, and the amended manifest guard pins the exact receiver shape (ADR-0037)
- Add a battery-optimization recommendation card on the Linux system screen: a permission-free state row plus one explicit tap into the system's direct exemption dialog, with the generic optimization screen as fallback; automatic exemption stays unsupported
- Add a foreground-only update check against the GitHub releases channel: at most once per 24 hours from an app foreground event, a launcher banner plus a system notification only when the notification grant already exists, per-tag dismissal, a browser hand-off to the release page, and silent typed failures (ADR-0038); no background scheduling and no silent self-update
- Add an assisted in-app update flow (ADR-0039): the banner's Install action opens a popup with determinate download progress, speed, and a time estimate; the APK streams into the app cache with its SHA-256 computed in the same pass, is verified against the channel-reported `sha256` digest, and is staged through the platform `PackageInstaller` — the system still asks for confirmation, and a cancelled install keeps the verified file so the next attempt skips the download. `REQUEST_INSTALL_PACKAGES` joins the reviewed permission allow-list

### Changed

- Publish the release asset from the signed release build with a dedicated keystore pinned in CI (ADR-0040): the workflow fails closed without the signing secrets or when the certificate fingerprint drifts, successive releases can update each other in place, and the shipped APK is no longer debuggable; builds signed before this change need a one-time reinstall
- Amend the AGENTS.md dependency rule: a well-maintained, vetted dependency is now the preferred default over hand-rolled code, with the existing supply-chain discipline (pinned versions, license check, recorded vetting rationale) unchanged

## [0.3.0] - 2026-09-19

### Added

- Add a bounded Termux command compatibility layer in the guest: `termux-battery-status`, `termux-location`, `termux-sensor`, `termux-contact-list`, `termux-sms-list`, `termux-telephony-deviceinfo`, and `termux-telephony-cellinfo` are generated into `/usr/local/bin` and answered by the Android capability bridge with Termux JSON output, exit codes, and documented row caps (ADR-0036)
- Add `docs/research/guest-local-llm-spike.md`, a device-recorded spike that ran llama.cpp CPU inference (small GGUF models, OpenAI-compatible `llama-server`) inside the guest on the S10e, with measured prompt/generation throughput, the required `libgomp` dependency, and the 4-thread configuration finding
- Document guest GPU/NPU reach in `docs/limitations.md`: the Mesa Turnip path is Adreno-only, Mali devices such as the S10e have no usable guest GPU driver, and NPU acceleration has no guest path

## [0.2.1] - 2026-09-18

### Added

- Add Logs surface documentation to README explaining boot.log persistence and live systemctl journal tails with size bounds (ADR-0034)
- Implement read-only terminal mode with disabled native text selection to preserve touch-scroll gestures for log scrollback
- Add terminal.reset() and terminal.fontSize() APIs for switching log sources and adjusting cell 

## [0.2.0] - 2026-09-17

### Added

- Live media now offers three track modes instead of one: `media.start` keeps
  the combined camera + microphone session, and `media.camera.start` /
  `media.microphone.start` add camera-only and microphone-only sessions. The
  mode decides everything downstream — the runtime grant the bridge checks
  (camera-only never asks for the microphone, microphone-only never for the
  camera), the foreground-service types the service claims, the sources the
  pipeline starts, the tracks the SDP advertises, and the status fields
  (`mode` plus `video_*` only for a video mode and `audio_codec` only for an
  audio mode). One session runs at a time; starting another mode while one is
  live answers the new typed `media-mode-conflict`. The guest CLI exposes the
  modes as `nusadesk-android media start [--camera|--microphone]`, and the
  runtime permission, foreground type, and notification for each mode were
  device-verified on the S10e (ADR-0031, ACB-021).

- The Android capability bridge is implemented in the existing guest session
  lifecycle as an authenticated, ephemeral `127.0.0.1` TCP JSON contract for
  battery, sensors, foreground location, read-only contacts/call-log/SMS/
  telephony, foreground location stream, and the unified live media controls
  `media.start`, `media.status`, and `media.stop` (ADR-0030, ADR-0031).
  Permission-aware methods return typed states and never prompt from the bridge.
  The live media path uses a camera/microphone foreground service and a
  loopback-only RTSP-over-TCP H.264/AAC stream; it never writes capture files.
- The manifest carries the reviewed permission set for the shipped live media
  and other user-invoked capability paths: camera, microphone, foreground and
  background location, high-rate sensors, activity recognition, Bluetooth,
  telephony/messaging/contacts/calendar reads, wake lock, battery-exemption,
  overlay, usage stats, all-packages, and matching foreground-service types.
  The live media path checks camera/microphone grants but never prompts from
  the bridge; `BridgePermissionsManifestTest` keeps the exact reviewed set.
- Guest awareness now generates `/root/docs/README.md`, focused bridge/media/
  battery-location/messaging docs, and the fixed-allowlist
  `/usr/local/bin/nusadesk-android` CLI. The media docs explicitly describe
  live-only operation, manual consumer-side saving, foreground eligibility,
  loopback RTSP, typed errors, and the retirement of `camera.snapshot`/
  `mic.record` artifacts.
- A bounded resolver doctor inside the existing foreground runtime lifecycle:
  persistent host-owned DNS source, parent-directory `FileObserver`, periodic
  verification, atomic allowlisted repair, and explicit no-active-DNS behavior.
- Session-boundary cleanup for the persistent rootfs `/tmp`, with symlink-safe
  deletion and fail-closed handling for an unsafe `tmp` path.

- The effective guest `/etc/os-release` now preserves Ubuntu identity fields
  while appending namespaced `NUSADESK_CONTRIBUTOR="NusaDesk"` and
  `NUSADESK_SOURCE="https://github.com/jahrulnr/NusaDesk"` through a strict,
  version-independent product bind regenerated from the current base file.
  conventional `systemctl`, `service`, and Python entrypoint paths, so
  apt-installed native files cannot silently shadow the product implementation;
  rootfs-owned files remain physically intact.

- Calendar access for the guest: `calendar.list` reads a fixed seven-day window
  from the platform's instance table (at most 50 rows, `truncated` flag, no
  description/attendee/organizer columns), and `calendar.insert`,
  `calendar.update`, and `calendar.delete` write into the user's own writable
  calendars with validated fields and typed errors. The request envelope gains
  an optional bounded `params` object that only declaring methods accept — a
  flat map of at most 8 safe short keys with bounded scalar values, rejected
  wholesale when malformed, and `unsupported-parameter` on any other method. The
  manifest declares `WRITE_CALENDAR`, the guest CLI gains
  `nusadesk-android calendar list|add|update|delete` commands that build the
  bounded parameters from typed flags (never a raw JSON argument), and the
  generated guest docs gain `calendar.md` plus the params rules in `bridge.md`
  (ADR-0032, ACB-023/ACB-024).

- Systemd **user** units now come up with the guest session. The session
  manager only starts system units, so a `systemctl --user` unit — a registered
  web app enabled into `default.target`, for example — never started on its own
  and only ran until the login shell that started it by hand exited. The
  bridge payload now ships a product-owned `lw-user-manager.service` plus its
  launcher, enabled at wire-up like the compose supervisor: it runs the same
  vendored replacement in `--user` mode with `HOME=/root`,
  `XDG_RUNTIME_DIR=/run/user/0`, and
  `SYSTEMD_DEFAULT_TARGET=default.target`, and a session teardown stops the
  enabled user units again (ADR-0024, SYS-002/SYS-003).

### Changed

- The launcher now labels the curated settings surface `System`, hides the
  normal `Linux ready` pill, and keeps a passive pill only for setup/transition,
  stopped, or failed states. The System screen now includes a one-click Android
  App Info shortcut for managing declared permissions. Launcher icon plates use
  a neutral Color Hunt-inspired charcoal/teal contrast palette:
  [#222831/#393E46/#29A19C/#A3F7BF](https://colorhunt.co/palette/222831393e4629a19ca3f7bf).
- The camera/microphone bridge is live-only: `media.start/status/stop` owns a
  user-visible camera|microphone foreground service, a loopback-only RTSP/TCP
  H.264/AAC stream, bounded client queues, and no capture files or artifact
  binds. The former snapshot/record paths are retired.

### Fixed

- A `systemctl --user` unit is no longer stranded after a session restart. Only
  the system manager ran, so an enabled user unit stayed dead until someone
  started it by hand in a terminal, and that hand-start died with the login
  shell that issued it. A registered web app therefore answered nothing after a
  force-close and relaunch. The product-owned user manager (see Added) now
  starts the enabled user units with the session and stops them with it
  (ADR-0024, SYS-002).
- Stale `.status` marks are wiped for **user** instances too. The replacement
  keeps user marks in `/run/user/<uid>/run/*.status`, which the previous
  session-start wipe did not touch, so a mark whose pid the kernel later
  recycled could make `systemctl --user is-active` report an active service
  that this session never started (SYS-003).
  provider requires it: the begin and end times travel in the `Instances` URI
  path instead of a selection argument. The selection form made the provider
  throw, which surfaced to the guest as `capability-unavailable` even though the
  grant was present — a JVM fake provider could not catch it because it ignores
  the URI shape (ACB-024).
- Calendar inserts always write an `eventTimezone` (UTC for an all-day event,
  the calendar's zone otherwise, falling back to the device zone). Relying on
  the optional calendar-zone read meant an insert could fail with the provider's
  `Event values must include an eventTimezone` and surface as `calendar-failed`.
- The request decoder rejects any fifth top-level field that is not `params`.
  Adding the optional `params` object had widened the accepted envelope, so an
  unknown field was silently dropped instead of failing closed; the existing
  protocol test caught it.
- A single-track RTSP session now honours the interleaved channel pair the
  client requests at SETUP (remembered per session) instead of insisting on
  the fixed video 0-1 / audio 2-3 mapping. A microphone-only stream, whose
  first advertised track is audio, was therefore rejected with
  `461 Unsupported Transport` by ffprobe/ffmpeg; the RTCP sender reports now
  use the same requested pair (ACB-022).
- Live video now carries correct RTP timestamps. The H.264 track converted a
  microsecond presentation time to the 90 kHz RTP clock by multiplying by 90
  instead of 90/1000, so consecutive frames landed ~33 s apart in stream time
  while audio advanced normally. A decoder therefore showed a single frozen
  picture (or nothing) for a stream that was actually being transmitted. The
  conversion is now `presentationTimeUs * 90 / 1000`, the existing RTSP test
  asserts the 90 000-tick-per-second contract, and the physical pass decodes a
  continuous 5 s clip (105 video frames, video duration matching audio).
- A video pump that stops is logged instead of failing silently (bounded
  diagnostic; the guest contract is unchanged).
- `calllog.list` no longer fails on devices whose CallLog provider rejects a
  SQL `LIMIT` token in the sort order (Samsung answers it with
  `IllegalArgumentException: Invalid token LIMIT`, which surfaced to the guest
  as `capability-unavailable`). The read now asks for a plain newest-first
  order and enforces the row cap in Java, so the bound no longer depends on the
  provider's SQL dialect; the same portability change was applied to the SMS
  inbox read. A swallowed provider failure in this adapter is also logged now
  instead of disappearing.

### Verification

- `./gradlew test lintDebug assembleDebug --no-daemon --rerun-tasks` passes with
  1215 JVM tests, zero failures/errors/skips, and no lint issues. A previously
  racy assertion in `GuestSshdStderrMonitorTest` waits for the drain instead of
  a line count, so the suite is deterministic.
- Samsung S10e SM-G970F (Android 12/API 31) passed the guest CLI
  `media.start/status/stop` flow with test-only camera/microphone grants;
  `ffprobe` saw H.264 1280x720 plus AAC 44.1 kHz mono through the loopback
  RTSP URL, and `ffmpeg -t 3 -f null -` decoded successfully. Grants were
  revoked and the media service was gone after stop.
- The same device passed the calendar cycle with the generated guest CLI:
  `calendar list` read an empty window before the test, `calendar add` returned
  `event_id 138`, the next read showed that event, `calendar update` changed it,
  `calendar delete` returned the window to empty, and an all-day insert came
  back as `all_day true` with `timezone "UTC"` and was cleaned up. A 25-hour
  request was rejected as `calendar-invalid-argument`, and with `WRITE_CALENDAR`
  revoked the write answered `calendar-permission-required` while the read kept
  working. Logcat carried only `calendar write op=… event=… calendar=…` lines
  with no event content.
- The same device reproduced and then cleared the reported user-unit failure:
  before the fix `systemctl --user is-active nusashell.service` read `inactive`
  and `127.0.0.1:10994` refused connections after a force-close and relaunch.
  After the fix the session came up with the tree `libproot → system manager →
  user manager → nusashell`, the marks read `MainPID=<user manager>` and
  `MainPID=<the web app>`, and port 10994 answered `HTTP 200` with no manual
  start. SIGTERM to the session manager stopped the user manager, the user unit,
  its mark, and the port; a planted stale user mark (`MainPID=999999`) was gone
  after the next wire-up.

## [0.1.0] - 2026-09-16

> Guest service management, a user-chosen workspace folder, and the bounded
> `udocker compose` adapter — device-verified end to end on a Samsung S10e
> (Android 12 / API 31, arm64).

### Added

- Guest service management: the `guest-service-bridge` add-on vendors
  `systemctl3.py` (docker-systemctl-replacement v1.7.1097, EUPL-1.2) and a
  digest-pinned Ubuntu Noble arm64 Python 3 closure, so `systemctl` and
  `service` work inside the guest. Enabled services autostart with the Linux
  session and stop when it stops; the manager and the session daemon share
  one PRoot tree so terminal `systemctl` commands can reach them (ADR-0024).
- A Linux workspace folder: choose a folder on the device (for example
  `Documents/nusadesk`) from the Linux system screen and it is bound into the
  guest at `~/nusadesk`, so files can be edited from Android and from Linux.
  The folder is the user's choice, the choice is remembered, and nothing is
  bound without the platform grant the feature needs (ADR-0023).
- A bounded `udocker compose` adapter in the guest (ADR-0025):
  - a strict, reject-first Compose subset — `up -d`, `down`, `stop`, `start`,
    `ps`, and `logs` — parsed by mirrored SnakeYAML Engine (host) and vendored
    PyYAML (guest) parsers; unsupported keys and unenforceable declarations
    fail closed instead of being silently ignored;
  - digest-pinned udocker 1.3.17 and PyYAML 6.0.1 source payloads, executed
    by the overlay's own interpreter; the upstream `udocker-englib` helper
    tarball is never shipped or downloaded;
  - the packaged NusaDesk PRoot as the inner container runtime behind fixed,
    product-owned binds (the guest sees `/usr/local/bin/proot`, its loader,
    and the Android linker/libc set it needs);
  - one product-owned global supervisor owning restart semantics: `no`,
    `always`, and `unless-stopped` with exponential backoff, a persisted
    manual-stop flag that survives session restarts, and honest `ps` states;
  - compose files and bind sources restricted to the `~/nusadesk` workspace
    folder; service environment values travel through a mode-0600 env file,
    never on process argv;
  - image pulls delegated to upstream udocker's own registry download path —
    uncached `busybox:latest` pull verified on device.
- A cross-API-level launch guard: `MainActivityApiLevelLaunchTest` builds the
  real Activity on Android 10, 11, 12, and 13 with Robolectric inside the
  ordinary JVM test task, so a lifecycle regression fails without a device
  (ADR-0022). Both workflows cache the Android runtimes it downloads.

### Changed

- Compose `ports:` declarations are rejected at admission. A device traffic
  test proved udocker/PRoot strips the declared `host_ip` — a
  `127.0.0.1:18924:8080/tcp` publish produced no `:18924` listener while the
  container stayed reachable on `*:8080` via the device LAN IP — so admitting
  an explicit-loopback declaration would have implied a loopback enforcement
  the runtime does not have. A real loopback-only proxy can revisit this.
- Same-version add-on overlays are now verified by content, not presence:
  `GuestServiceBridge.detect()` re-hashes every vendored file against its
  catalog SHA-256 pin, so an APK update that changes a packaged asset marks
  the stale overlay not-installed and the pipeline reinstalls it.

### Fixed

- NusaDesk no longer force-closes on launch on Android 11 and 12. The
  status-bar setup asked `Window.getInsetsController()` for a controller before
  the window had a decor view, which that API dereferences directly; the
  controller is now taken from the decor view (null-safe before attach) and
  applied once the decor is attached.

### Verification

- The full repository gate (`./gradlew test`, `lintDebug`, `assembleDebug`)
  passes.
- The Compose adapter was verified end to end on the Samsung S10e
  (SM-G970F, Android 12/API 31, arm64, 4 KB pages, 2026-09-16): supervised
  `up -d` on a pre-existing alpine image, nested-PRoot execution, workspace
  bind writes, six `restart: always` cycles, a persisted `stop`, a clean
  `down`, an uncached `busybox:latest` pull (16.36 s), and `unless-stopped`
  across a host force-stop/relaunch. The port traffic test on the same pass
  is why `ports` is refused.
- The wider device matrix (other API levels, 16 KB pages, OEMs) remains open;
  no full Compose compatibility is claimed.

## [0.0.1] - 2026-09-16

> Terminal usability pass: platform text selection, fullscreen chrome,
> hold-to-repeat accessory keys, and rotation continuity without Activity
> recreation.

### Added

- Terminal text is selected and copied with the platform's own long-press
  selection and Copy action mode on the real xterm DOM rows; the app ships no
  custom clipboard code. Row mutation and refit are deferred while a selection
  is held, then the latest content is applied when it closes.
- Hold-to-repeat on the arrow accessory keys: a tap emits one key, a held press
  repeats after a short delay at a fixed interval, and release, cancellation,
  or sliding the finger off the key stops it at once.
- A "scroll to live output" button that appears only while the viewport is
  scrolled back, driven by the page's own reported scroll state.

### Changed

- The shell is fullscreen: the status bar is hidden (transient on swipe) and
  the navigation bar keeps the surface colour. On API 29 the soft-keyboard
  overlap is compensated from the window's visible frame because a fullscreen
  window receives no IME inset.
- Rotation no longer recreates `MainActivity`
  (`configChanges="orientation|screenSize|keyboardHidden"`); the terminal's
  WebView, buffer, and SSH attachment refit in place to the new dimensions.
- Task-bar and accessory-key heights are more compact on phones (40dp; tablets
  stay at 48dp) so the terminal keeps usable rows while the soft keyboard is
  open.
- The terminal typeface prefers a compact monospace stack at 13px with 1.0
  line height.
- One-finger scrollback is handled entirely inside the packaged terminal page;
  every touch now reaches the WebView unmodified.

### Removed

- The native WebView touch interceptor and the host-to-page `scroll` message,
  superseded by the in-page touch adapter; page and host ship together.

## [0.0.0] - 2026-09-14

> Initial NusaDesk release. Runtime evidence currently covers one Android 10 /
> API 29 ARM64 device. See the [limitations](docs/limitations.md) and [test
> plan](docs/test-plan.md) for the complete evidence boundary.

### Added

- NusaDesk launcher-first desktop shell for Android.
- Curated Ubuntu Base ARM64 setup flow with download, integrity verification,
  safe extraction, validation, and atomic activation.
- Curated OpenSSH guest component installed as a private runtime overlay.
- Local Linux terminal with:
  - bundled xterm.js terminal surface;
  - touch-friendly accessory keys;
  - PTY resize handling;
  - session reconnect and Activity-recreation support;
  - explicit waiting, failure, and recovery states.
- Fixed local guest endpoint at `127.0.0.1:22022`, with readiness verification,
  host-key pinning, and an Android Keystore-backed credential.
- Activity-triggered Linux autostart managed by a user-visible foreground
  service with a notification Stop action.
- User-managed web apps:
  - add, edit, and remove app entries;
  - optional image selected through the Android document picker;
  - generated local endpoint from a validated guest port;
  - launcher tiles and search;
  - reachability, unavailable, failed, and loaded states;
  - favicon fallback with bounded fetching and decoding.
- Locked-down WebView boundaries for terminal and web-app surfaces:
  - exact owned origins;
  - different loopback ports blocked;
  - external links handed to the system browser;
  - no broad JavaScript interface;
  - file and universal file access disabled.
- Responsive launcher and surface layouts for phone portrait, landscape, tablet,
  light/dark themes, and larger font sizes.
- Clean Architecture boundaries, domain policies, runtime state handling, and
  focused unit-test coverage.
- Product documentation covering architecture, ADRs, research, limitations,
  roadmap, compatibility, and device verification.

### Security and reliability

- SHA-256-pinned runtime and guest component artifacts.
- HTTPS-only payload downloads with redirects disabled.
- Archive traversal, unsafe-link, file-type, entry-count, and extraction-size
  protections.
- Last-known-good activation and rollback-preserving update flow.
- PRoot and loader build outputs verified against pinned hashes before packaging.
- Rejected guest-daemon starts reclaim the guest daemon before tearing down the
  PRoot tracer, with process identity checks to avoid signaling unrelated PIDs.
- Runtime readiness requires a healthy listener rather than a PID alone.
- Runtime failures, process death, fixed-port conflicts, and interrupted setup
  are surfaced as explicit states instead of false readiness.

### Verification

- The unit test suite passes with no failures or errors.
- Android lint passes with warnings treated as errors.
- Debug APK builds successfully.
- Runtime and terminal path verified on one physical Android 10 / API 29 ARM64
  device with 4 KB pages.
- Launcher and web-app UX verified on an x86_64 API 35 emulator. The emulator
  is not runtime evidence for the ARM64 guest.

### Known limits

- Android versions beyond API 29, other OEMs, and 16 KB page-size devices are
  not yet covered by runtime evidence.
- NusaDesk does not claim full Linux compatibility, guaranteed 24/7 survival,
  LAN sharing, arbitrary package installation, or Google Play approval.
- The application license and final distribution model remain under review.
