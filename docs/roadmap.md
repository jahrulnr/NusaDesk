# Roadmap and implementation plan

The project follows risk-first vertical slices. The highest-risk question is native execution on Android 10+; it must be answered before building a polished app catalog.

The accepted product direction is a **Linux desktop on Android** (ADR-0012): the launcher is the home surface, the terminal is one Linux app, and one long-lived session is owned by the Android host. Linux is **background infrastructure** (ADR-0013) — it starts from an app launch and stops only from the platform notification — and the desktop's web apps are the ones the **user registers** (ADR-0014). SSH (ADR-0007) is the transport between the host and the guest, not the product surface.

Implemented and device-verified on Android 10/API 29 arm64: the packaged standalone PRoot
execution bridge, the curated OpenSSH add-on (ADR-0010), the fixed local SSH endpoint and
autostart boundary (ADR-0013), and a live guest shell in the terminal app surface.
Implemented and device-verified on the Samsung S10e (SM-G970F, Android 12/API 31, arm64, 4
KB pages): the bounded `udocker compose` adapter end to end (ADR-0025, Phase 3B).
Implemented and UX-verified on an x86_64 emulator (API 35): the launcher — search, readiness
pill, setup steps, the dashed `Add app` tile, the curated surfaces, and user web apps — the
add/edit/remove web-app form, the web-app surface's probing/unreachable/loaded states, the
terminal's waiting and failure prompts, the system screen, landscape, tablet, light/dark
themes, and font scaling. The emulator's ARM translation is **not** runtime evidence. Also
device-verified on that S10e (2026-09-16/17): the Android capability bridge — battery and
its sysfs projection, one-shot sensors, foreground location and its bounded stream,
read-only contacts/call-log/SMS/telephony, live media in three track modes, and the bounded
calendar read/write slice (ADR-0030, ADR-0031, ADR-0032). Remaining: the wider device matrix
(other API levels, 16 KB pages, OEMs) and any future curated runtime profile. The
published-port traffic test ran and proved `host_ip` is stripped, so Compose `ports` are
rejected in the MVP.

## Phase 0 — foundation (current)

- [x] Java Android scaffold, `minSdk 29`.
- [x] Clean Architecture package boundaries.
- [x] Domain vocabulary for runtime state and published loopback endpoint.
- [x] Repository instructions and local agent skills.
- [x] Responsive native status shell with human-readable lifecycle previews and contract disclosure.
- [x] Research, limitations, ADRs, and test plan.

## Phase 0.5 — curated install proof (current)

- [x] Pin Ubuntu Base 24.04.5 ARM64 catalog metadata and SHA-256.
- [x] Download to private temporary storage, verify exact size/digest, safely extract, validate, and atomically activate.
- [x] Render `DOWNLOADING`, `VERIFYING`, `EXTRACTING`, `READY`, `FAILED`, retry, and insufficient-storage outcomes.
- [x] Verify a clean on-device install on Android 10 arm64.
- [ ] Preserve progress/cancellation/resume across Activity recreation.
- [x] Execute the installed rootfs through a tested Android bridge (packaged PRoot, ADR-0008).


**Question:** Can a target API 37 Java app run a downloaded ARM64 guest shell and an SSH server without root on real Android 10+ devices, using a packaged standalone PRoot bridge?

Scope:

- Choose a source-available, license-compatible execution bridge. Leading candidate: packaged standalone PRoot binary (GPL-2.0-or-later) in `jniLibs/<abi>/`; decision pending the spike below.
- Keep the loader/native bridge in the APK as required by Android execution policy; keep guest rootfs out-of-band.
- Start with `arm64-v8a`; add x86_64 only when emulator coverage is needed.
- Test Android 10, 13, 15, 16, and 17; test 4 KB and 16 KB page-size environments.
- Test cold start, corrupted payload, low disk space, process death, screen-off, Doze, and resume.

Exit criteria:

- `/bin/sh -c 'uname -a'` succeeds in the guest under PRoot without root.
- A loopback SSH server (Dropbear or OpenSSH, explicitly added to the curated rootfs — not from Ubuntu Base) binds `127.0.0.1` and is reachable from the host.
- The host can stop/restart the process without orphan children.
- Invalid or tampered payloads never execute.
- Findings are recorded with device model, Android version, ABI, page size, and logs.
- PRoot license/notice obligations and the GPL combined-work question are reviewed before any distribution.

## Phase 1.5 — launcher, local-only terminal, and user web apps (current)

**Status:** implemented; UX-verified on an x86_64 emulator (API 35, 720×1280@320).
No runtime claim is made from that environment (ADR-0012, ADR-0014).

**Question:** Can the product present itself as a Linux desktop — launcher home,
apps as surfaces, Linux as background infrastructure — without faking any state?

Scope:

- [x] Launcher as the home destination; peer tabs, the FAB, and the `About`
      destination removed.
- [x] Linux started from an Activity foreground event
      (`RuntimeHostService.ensureRunning`), gated on the curated system and the
      guest terminal component; no in-app start/stop control (ADR-0013).
- [x] One passive readiness pill (`LauncherStatus`) folding install, component,
      and session truth; a reflection test fails if it grows an action.
- [x] One serialized setup pipeline (Prepare → Download Linux → Verify → Extract
      → Add terminal component → Ready) driven by a single user action, shown
      only while a component is missing; the separate second "add terminal
      component" card and button were removed (ADR-0017, superseding the
      two-step setup of ADR-0012).
- [x] Terminal as a maximized app surface, local-only through
      `LocalSshSessionFactory`, with an honest waiting/failure prompt and a
      mobile accessory key row.
- [x] Every remote-SSH surface removed: dialog, host profile, add-host form,
      layouts, strings, and the arbitrary `SshSessionConfig` construction.
- [x] User-local web apps: add/edit/remove form (name, optional image through the
      document picker with a persisted read permission, guest port with
      field-level validation), app-private persistence, `22022` reserved.
- [x] Web-app surface: asynchronous reachability observation before the WebView
      loads exactly `http://127.0.0.1:<port>/` through `WebAppWebViewBoundary`,
      with probing, unreachable, failed, and loaded states.
- [x] Compact task bar (way back, title, and options only when the surface has
      app actions) instead of session chrome and a duplicated system entry.
- [x] Fake built-ins removed: `Desktop Workspace`, `Files`, the recents row, and
      the category sections are gone; the system screen is reached through its
      own tile.
- [x] Responsive grid (phone portrait, phone landscape, tablet), large-font
      reflow, and one accessibility node per tile.
- [x] Back contract: app surface → launcher → exit.
- [ ] Curated runtime web-app profile (Phase 3): a packaged, source-reviewed app
      that ships inside the runtime rather than being registered by the user.

## Phase 1 — SSH execution + terminal validation

**Status:** implemented and device-verified on Android 10/API 29 arm64 (live
guest shell, readiness, FGS, resize, reconnect after recreation, stop/restart,
failure reporting). Remaining: the wider device matrix and the 16 KB-page
environment.

**Question:** Can the host start a PRoot guest, reach a guest SSH server on loopback, and render an xterm.js terminal in a locked-down WebView — with honest readiness and a user-visible foreground service?

Scope:

- Build a curated rootfs that adds and configures Dropbear or OpenSSH bound to `127.0.0.1`; generate host keys at first start in app-private storage.
- Implement the readiness handshake: concrete loopback host:port + session identity after bind; health check, not merely a PID.
- Keep sensitive endpoints authenticated; loopback is reachability, not authentication.
- Pin the xterm.js bundle locally (no remote CDN); load only the packaged
  terminal origin in the WebView.
- Start the runtime from a user-visible foreground service with a Stop action; pick the FGS type (`specialUse` candidate, not `dataSync`).
- Handle host-key verification, credential/key storage in Android Keystore/EncryptedStorage, reconnect, and Activity recreation.

Exit criteria:

- Clean install reaches `RUNNING` and shows the xterm.js terminal connected to the guest SSH server.
- Readiness is health-checked; a PID without a healthy listener never shows `RUNNING`.
- WebView loads only the owned origin; external links leave for the system browser; no broad JavaScript interface.
- Process crash reaches `FAILED` or `RECOVERING`, never false `RUNNING`.
- Foreground service is stoppable and does not claim 24/7 survival.

## Phase 2 — local server/WebView validation

This isolated dummy-server validation may be run **before** Phase 1 as a cheaper
way to de-risk the WebView boundary, or folded into Phase 1's real terminal
slice. It is retained as an option, not a mandatory separate phase.

**Question:** Can an Android WebView safely and reliably consume a host-owned local HTTP/WebSocket/SSE server?

Scope:

- Use a dummy server with HTTP, WebSocket, SSE, `/healthz`, and a readiness line.
- Load only the exact generated loopback origin.
- Handle server startup timeout, crash, restart, back, rotation, and Activity recreation.
- Keep external links outside the embedded origin.

Exit criteria:

- WebView loads only after readiness, not merely after PID creation.
- Reconnect works after a controlled server restart.
- Invalid navigation and unexpected origins are blocked.
- No broad JavaScript interface exists.

## Phase 3 — first curated web-app vertical slice

**Question:** Can one curated Linux ARM64 web application run with durable local state through the wrapper contract?

The launcher slice (Phase 1.5) already ships **user-registered** web apps. This
phase is about shipping one *curated* app inside the runtime, with the same
lifecycle, port, and WebView contracts.

Scope:

- Package one intentionally selected, source-reviewed web-app profile.
- Build the payload outside the device; the device only downloads/verifies/extracts/runs it.
- Adapt host/port configuration and disable desktop-only/service-installer features.
- Add an Android-side auth token/proxy when the target app is designed for trusted localhost use.
- Test streaming, WebSocket/SSE persistence, `exec` where explicitly supported, and small file flows.

Exit criteria:

- Clean install reaches `RUNNING` and shows the UI.
- Restart preserves the app's documented state.
- Process crash reaches `FAILED` or `RECOVERING`, never false `RUNNING`.
- Previous payload remains available after a failed update.

## Phase 3B — bounded `udocker compose` adapter

**Status:** implemented and device-verified on the Samsung S10e (SM-G970F,
Android 12/API 31, arm64, 4 KB pages, 2026-09-16), including an uncached
registry image pull and `unless-stopped` across a session restart. The
published-port traffic test ran on that pass and proved udocker/PRoot
strips `host_ip` (no loopback-only enforcement), so `ports` is rejected
at admission. Remaining: the wider device matrix.

**Question:** Can a deliberately small Compose subset run honestly on udocker
inside the curated guest — with restart semantics that do not pretend to be
Docker?

Scope:

- [x] Strict-subset domain contract and mirrored YAML parsers (host-side
      SnakeYAML Engine, guest-side vendored PyYAML); unsupported keys and
      unenforceable declarations are rejected, never ignored.
- [x] Product-owned guest runtime: pinned udocker 1.3.17 + PyYAML 6.0.1
      source, the packaged NusaDesk PRoot as the inner runtime behind fixed
      binds, and one global supervisor owning restart/backoff/manual-stop
      (ADR-0025). The upstream helper tarball is never shipped or
      downloaded.
- [x] Host-side evidence: asset harness (35 tests), domain (64), parser
      (22), and the payload/wiring/launcher suites; lint.
- [x] Physical end-to-end pass on the S10e (SM-G970F, Android 12/API 31,
      arm64, 4 KB pages, 2026-09-16): wired overlay on disk (14-file
      manifest, compose paths, inner-PRoot/system binds, pre-init wants
      link), `systemctl` running with `lw-compose-supervisor`
      active/enabled, product `/usr/local/bin/udocker` version and image
      list OK, and a real project on a pre-existing alpine image —
      `up -d` → supervisor-spawned argv → nested PRoot → marker through
      the workspace bind → six `always` restarts → persisted `manual_stop`
      on `stop` → `down` removed the project and container. The pass also
      covered an uncached `busybox:latest` pull (16.36 s, arm64 manifest
      verified) and `unless-stopped` across a force-stop/relaunch. A
      same-version overlay install did not self-refresh during the pass
      (one stale compose file was deleted to force it); the digest-aware
      `detect()` guard added afterwards now triggers the reinstall
      automatically.
- [x] Published-port traffic device test: a `127.0.0.1`-declared publish
      produced no loopback listener while the container stayed on `*:8080`
      (LAN-reachable) — udocker/PRoot strips `host_ip`, so `ports` is
      rejected at admission until a real loopback-only proxy exists.
- [x] Image pull and `unless-stopped` across a session restart (same
      S10e pass).
- [ ] The wider device matrix (other API levels, 16 KB pages, OEMs).

## Guest → Android capability bridge (implemented)

**Status:** implemented and device-verified on the Samsung S10e (SM-G970F,
Android 12/API 31, arm64, 4 KB pages) for battery, one-shot sensors, foreground
location and its bounded stream, read-only contacts/call-log/SMS/telephony, live
media in three track modes, and the bounded calendar read/write slice
(2026-09-17). Remaining: the wider device matrix, continuous sensor streaming,
and any further capability track as separately scoped work.

**Question:** Can the Linux guest reach the Android APIs a user actually asks
for — without a shell, reflection, or Binder bridge, without LAN exposure, and
without inventing data for a permission that was not granted?

Scope:

- [x] Authenticated, session-scoped loopback JSON control bridge with a fixed
      method allowlist, bounded frames, and typed permission/error states
      (ADR-0030).
- [x] Battery status plus a best-effort `/sys/class/power_supply/battery`
      projection, one-shot accelerometer/gyroscope reads, and a bounded
      foreground location stream.
- [x] Read-only, redacted, row/byte-bounded contacts, call-log, SMS, and
      telephony reads; `sms.send` and `phone.call` stay typed
      `action-unsupported`.
- [x] Live-only camera and/or microphone in three track modes over loopback RTSP
      (H.264/AAC) with no capture artifact (ADR-0031).
- [x] Bounded calendar read plus validated `insert`/`update`/`delete` writes
      behind one bounded `params` object, with no attendee and no invitation
      path (ADR-0032).
- [x] Generated guest docs and the `nusadesk-android` CLI, including the params
      rules and the calendar commands.
- [ ] The wider device matrix (other API levels, 16 KB pages, OEMs), continuous
      sensor streaming, and additional capability tracks (Bluetooth, usage
      stats, overlay) as separately scoped work.

## Host app UX — boot start, battery recommendation, and update check

Implemented 2026-09-20 with unit evidence; device verification of the
lifecycle rows is open work in `docs/test-plan.md` (BOOT-*, BAT-*, UPD-*).

- [x] Opt-in "Start Linux at boot" (default OFF): one unexported receiver for
      `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`, persisted install gates, and
      the idempotent ensure-running boundary (ADR-0037).
- [x] Battery-optimization recommendation card: permission-free state plus one
      explicit tap into the system exemption dialog with a generic fallback;
      automatic exemption stays unsupported.
- [x] Foreground-only update check against GitHub releases: once per 24 hours,
      launcher banner + notification only when already granted, per-tag
      dismissal, browser hand-off, silent failures (ADR-0038). The cadence was
      later lowered to a 30 minute floor with an immediate check after an
      installed version change (ADR-0046).
- [x] Device verification on the S10e (SM-G970F, OneUI, API 31, 2026-09-20):
      boot delivery + typed skips, `MY_PACKAGE_REPLACED` restores, the
      exemption-dialog cycle, and the update banner/throttle/offline rows
      (`docs/test-plan.md` BOOT-*/BAT-*/UPD-*).
- [ ] The same pass on a stock (non-OneUI) Android and one more OEM; the
      UPDATE_AVAILABLE notification captured on-device once an egress allows
      a successful check.

## Phase 4 — second curated runtime profile

**Question:** Can a second, materially different runtime profile run without device-side compilation or weakening the host security contract?

Scope:

- Add one additional curated profile only after Phase 3 passes.
- Pin its runtime and dependencies.
- Include prebuilt frontend assets and disable first-launch native/frontend builds.
- Record unsupported features explicitly instead of silently falling back to host behavior.
- Reuse the same lifecycle, port, state, download, and WebView contracts.

## Phase 5 — compatibility expansion

**Question:** Which additional web-app profiles are worth supporting, and what does each require?

Scope:

- Evaluate candidates one at a time against the compatibility matrix.
- Verify Linux ARM64 native modules, libc, browser, channel, and service requirements per candidate.
- Keep browser automation, voice, local models, desktop GUI, and LAN exposure as separate capability validation tracks.
- Do not label a candidate supported until its profile passes the device test plan.

## Phase 6 — multi-app catalog

Only after multiple profiles are reliable:

- signed app/runtime manifest catalog;
- per-app version and state directories;
- port allocator based on child readiness, not a released pre-check;
- capability declarations;
- disk quotas and cleanup;
- atomic activation and rollback;
- compatibility labels: `supported`, `untested`, `unsupported`;
- app-specific diagnostics and export.

Arbitrary images, arbitrary shell commands, and public/LAN binding remain opt-in capabilities with explicit security review.

## Verification baseline

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

Future device gates must include Android 10+, modern target API behavior, arm64-v8a, 16 KB page size, process/background transitions, corrupt downloads, WebView origin checks, and visual inspection on a real device/emulator.
