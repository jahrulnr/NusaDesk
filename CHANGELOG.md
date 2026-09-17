# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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

### Verification

- `./gradlew test lintDebug assembleDebug --no-daemon --rerun-tasks` passes with
  1142 JVM tests, zero failures/errors, and no lint issues.
- Samsung S10e SM-G970F (Android 12/API 31) passed the guest CLI
  `media.start/status/stop` flow with test-only camera/microphone grants;
  `ffprobe` saw H.264 1280x720 plus AAC 44.1 kHz mono through the loopback
  RTSP URL, and `ffmpeg -t 3 -f null -` decoded successfully. Grants were
  revoked and the media service was gone after stop.

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
