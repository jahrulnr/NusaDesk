# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

- Unit test suite passes with 615 tests and no failures or errors.
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
