# Changelog

All notable changes to this project will be documented in this file.
The current line (`0.14.x`) and `[Unreleased]` stay here; older minor lines
live under [`docs/changelogs/`](docs/changelogs/).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.14.5] - 2026-10-26

### Added

- Launcher app actions offer Add to Home screen with badged Android shortcuts;
  shortcuts follow labels/icons, reconcile removed apps, and wait for runtime
  readiness on cold launch (ADR-0077).
- Experimental native X11 Linux app surfaces, desktop-file discovery, touch and
  keyboard controls, zoom/layout controls, and retained apps with Minimize/Close
  and launcher badges (opt-in native build; ADR-0068–0070, ADR-0073).
- Web + Terminal app registrations start a guest command before opening its web
  port, with terminal diagnostics and coordinated server restart (ADR-0072).

### Fixed

- Web-app readiness no longer treats a missing HTTP status (`-1` or `0`) as
  a reachable app when a TCP listener does not answer valid HTTP.

- Web-app reload and cache tests keep ownership of their unavailable endpoint
  instead of probing a freed port; failed CI runs retain JUnit and HTML test
  reports.

- Release metadata is prepared with `make bump-version`: VERSION, changelog,
  security documentation and minor-line archives stay in sync. CI checks
  metadata before starting the Android build.
- Linux desktop icons behind guest version symlinks resolve in the guest
  before host decoding, fixing the installed NusaShell native launcher icon.
- Native guest apps receive a writable, session-scoped `/dev/shm` bind;
  Electron no longer aborts because Android's `/dev` lacks that directory
  (ADR-0079).
- Native builds pass `DISPLAY=:1` into new terminal sessions and guest service
  managers, allowing applications such as the NusaShell installer to detect
  the available native display endpoint (ADR-0078).
- A late disconnect from an old native-display binding can no longer acknowledge
  the replacement process's death before guest temporary-storage cleanup.
- Homescreen pins deliver repeated warm launches and resume retained Linux
  tasks after their desktop file disappears; cold commands wait for Linux.
- Android 10 native display startup uses the supported local-socket connect
  API with bounded send/read timeouts; it no longer fails with an unsupported
  timeout overload before the X11 handshake.
- Terminal drags survive DOM row replacement: long gestures keep following
  the finger and can reverse direction without lifting (ADR-0076).
- One-finger scrolling reaches mouse-tracking and alternate-screen terminal
  apps such as OpenCode through xterm wheel events (ADR-0074), while normal
  shell output retains touch scrollback and momentum.
- Terminal emoji use grapheme-aware cell widths, preventing adjacent cells
  from covering half a glyph, including variation-selector emoji (ADR-0075).
- SVG favicons, including NusaLLM's icon, render on launcher tiles (ADR-0071).
- Native display teardown runs off the UI thread and always releases binding
  state; renderer Retry reconnects. GTK apps receive a private D-Bus session so
  normal Mousepad Quit is not a shutdown crash. Stopping the display also
  removes its dead X socket and only the private buses recorded for GUI launches;
  unrelated terminal/service buses and their sockets remain intact. Missing desktop entries
  no longer leave prototype/failed launcher ghosts.

## [0.14.4] - 2026-10-21

### Added

- **The About page can check for updates and shows the latest changelog.** A
  `Check for updates` row under the GitHub link runs a real check on demand —
  bypassing the cadence floor — and reports the outcome inline while the
  launcher banner, notification, and assisted install still receive it
  (ADR-0066). A Changelog card under `How it works` renders the newest
  released section of the bundled `CHANGELOG.md` with a `Read more` link to
  the repository file.

### Changed

- **The app version is derived from `VERSION`, and the changelog asset is
  generated.** `versionName`/`versionCode` are no longer hand-edited in
  `app/build.gradle` — the build reads the `VERSION` marker and maps
  `major.minor.patch` to a monotonic code (ADR-0067), and `CHANGELOG.md` is
  staged into generated assets at build time so the About card can never lag
  a checked-in copy.

## [0.14.3] - 2026-10-21

### Fixed

- **The soft keyboard no longer rides across surface switches.** Every
  transition -- to the launcher, a fixed surface, a web app, or a
  terminal-command app -- settles the IME before the surface changes, with a
  decor-view token fallback for keyboards raised by a WebView field that
  already lost view focus. Each surface now declares whether it owns text
  input (`SoftKeyboardAware`), so surfaces without it have stray focus swept
  while forms and web pages keep theirs (ADR-0065). Returning to the launcher
  with Back or the Home action no longer leaves the keyboard covering it, and
  a hidden web-app tab can no longer hold the keyboard open.

### Added

- **Web-app surfaces now carry two cache actions.** The options menu gains
  `Reload without cache` -- the app's WebView cache is dropped and the page
  the user is on reloads in place, keeping its URL and history -- and `Clear
  cache`, which drops the same HTTP cache and reports the outcome in a toast
  (ADR-0064). Cookies, DOM storage, and stored sign-ins are untouched.
- **`.ico` icons decode deterministically.** Tile icons no longer depend on
  whether the device's platform decoder ships an ICO codec: the container is
  unwrapped in-app (`IcoImage`), with PNG frames decoded directly, DIB frames
  rewritten as BMP, and the AND transparency mask reapplied. The same decode
  backs a user-picked image, so choosing an `.ico` file works too (ADR-0015
  amendment).
- **A runnable favicon/cache test lab.** `scripts/webapp-lab/serve.py` serves
  sixteen single-purpose edge-case web apps on consecutive loopback ports --
  declared links of every format, the `/favicon.ico` fallback, ICO containers
  (PNG and masked DIB frames), cross-port and mislabeled rejections, and a
  self-evidencing cache-staleness page -- each printable next to its tile for
  device verification.

## [0.14.2] - 2026-10-11

### Added

- **A wedged Linux session is now an explicit, recoverable state.** While a
  session is live, the foreground service probes the guest every 15 s over
  the app's pinned loopback SSH session: one `/bin/true` exec that must fork
  in the guest, hard-bounded at 8 s. Three consecutive misses flip the
  session to `UNRESPONSIVE` instead of leaving a frozen terminal claiming
  `RUNNING` for minutes; the first success flips it back. The launcher,
  the notification, and the terminal all name the state in words, and a new
  `Restart Linux` action -- on the notification and the terminal surface --
  stops the session and starts it again through the existing paths. There is
  no auto-restart, no automatic tracer kill, and no guest-side watchdog
  (ADR-0062).

### Fixed

- **`Restart Linux` now actually recovers a frozen session.** On the
  verified SIGSTOP recipe the restart used to declare `STOPPED` while the
  frozen tracer was still alive and its `sshd` still held
  `127.0.0.1:22022`, so the new session failed on the fixed-port conflict
  and only `adb shell am force-stop` cleared it. The stop path now sends
  `SIGCONT` to the tracer and the signalled guest processes before
  terminating -- a stopped process never sees `SIGTERM`, and on this device
  the frozen tracer also outlived the forced kill until it was resumed --
  escalates to a forced kill, and reports `STOPPED` only once the tracer
  is really gone and nothing still holds the port. The tracer pids come
  from a `/proc/<pid>/stat` scan for the app's own children, with the
  `CONFIG_PROC_CHILDREN`-gated `children` file kept only as a second
  source: the verified wedge device (S10e, Samsung 4.14.113) has no usable
  `children` file, so the thaw used to find no tracer and a stopped tracer
  survived the forced window. A workload that
  survives anyway ends `FAILED` -- never a false stop -- and the same tap
  escalates it further (next entry); the service now releases its
  foreground slot only at the terminal publish itself, so a queued render
  or a stale intent can no longer destroy it mid-restart (ADR-0062).

- **A session that survives even the forced kill now recovers on the same
  tap.** `Restart Linux` on that `FAILED` state escalates to a self-restart
  of the app's own process tree (ADR-0063): the app arms a scheduled
  `JobScheduler` revival, thaws and `SIGKILL`s every process its own uid
  owns -- the documented `Process.killProcessGroup` is a hidden API, so the
  equivalent `/proc` uid sweep is used, which also reaches a tracee that
  called `setsid` and escaped the process group -- then kills itself. The
  platform delivers the job in a fresh process, which re-enters
  `ensureRunning`; if the API-31+ background rules refuse the
  foreground-service start, the restart notice raised before the kill stays
  up as a tap-to-open fallback. If no revival could be armed the app stays
  alive and the persisted `FAILED` is the honest record; its reason names
  `Restart Linux` as the recovery and mentions a Settings force-stop only
  as the last resort, so the primary instruction is never "go to
  Settings".

- **A guest command that forks can no longer wedge the whole session.** The
  emulated hard-link storage is now kept outside the guest's own directories: the
  session points the bridge's `PROOT_L2S_DIR` at an app-private directory and
  binds that same path into the guest, so the extension's `.l2s.` backing files
  never appear in a guest listing. The directory filter that used to hide them
  was removed with it: its compaction wedged the tracer on the S10e, which is
  what made a concurrent `git clone` hang, and the bisect is recorded in the
  `proot-fork-hang-vendor-kernels` research note (since removed; in git
  history). Five concurrent-clone rounds
  on the S10e pass, `rm -rf` over a tree with emulated links keeps links outside
  it readable, and the workaround in `docs/limitations.md` stays for sessions
  that already wedged. The bridge also carries two further fixes: it keeps the
  kernel's syscall result when the extension's bookkeeping fails, and publishes a
  link-count change without a window in which a name a reader follows is missing.

## [0.14.1] - 2026-10-10

### Fixed

- **The execution bridge is bumped to termux/proot v5.1.107.96.** The previous
  pin (v5.1.107.92) predates upstream's fix for vendor kernels that lose the
  ptrace fork-event PID (`8b994150`), and with it any guest command that forks
  could wedge permanently while the tracer spun. The new pin also carries the
  seccomp event fix (`d4d2a190`), two `link2symlink` fixes, and the `fake_id0`
  open fix; the freestanding loader is bit-identical, and the build script's
  pinned digests are updated with it. Device verification on the S10e shows the
  remote-clone path passing; the Samsung kernel still reproduces the wedge, so
  the hazard stays documented with a workaround and a recovery step in
  `docs/limitations.md` (the `proot-fork-hang-vendor-kernels` research note,
  since removed; in git history).

## [0.14.0] - 2026-10-10

### Added

- **A web app can be reloaded from its task bar.** A page can navigate inside
  the app to a view with no route back, and the only recovery was editing the
  app definition and saving it again. The task bar now carries a reload action
  (`Refresh this app`), visible only while a web app is the open surface, which
  re-probes the app and loads its registered address again on a fresh renderer
  (ADR-0061).

### Fixed

- **A dead link in a popup no longer closes the app.** Tapping a link to an
  address this app does not own (the reported case was `http://127.0.0.1` shown
  by a guest-served page) opened a popup whose blocked navigation was handled by
  tearing the child WebView down *inside its own callback*, which aborted the
  process (`Fatal signal 5 (SIGTRAP)` through
  `WebAppSurfaceView.destroyTabWebView`). Every child teardown triggered by a
  callback is now deferred to the main loop, a refused popup states why the tap
  did nothing, and the external-link handoff can no longer kill the surface
  (ADR-0061).

- **Earlier text can no longer be re-typed into the terminal by the IME path.**
  Typing a character occasionally echoed text from before into the prompt (`a`
  becoming `aapt upgrade`), as if a command history had been replayed. The cause
  is upstream in the pinned `@xterm/xterm` bundle: its hidden helper textarea is
  emptied only on blur and on a real Enter/Ctrl+C keydown, and the Android
  `keyCode 229` diff then re-emits the whole accumulated field as input when an
  IME edit is not a clean append (xtermjs/xterm.js#6078, with #6012, #6045,
  #5887). The host was never the source: one FIFO stdin path, no input queue, no
  replay buffer, no local echo. `ime-guard.js` now keeps xterm's field empty
  between keystrokes (ADR-0060), after 400 ms of quiet, never while composing and
  never inside xterm's settle windows, and it skips `screenReaderMode`.

## Older releases

- [0.13.x](docs/changelogs/0.13.md) — 0.13.0
- [0.12.x](docs/changelogs/0.12.md) — 0.12.0
- [0.11.x](docs/changelogs/0.11.md) — 0.11.1, 0.11.0
- [0.10.x](docs/changelogs/0.10.md) — 0.10.2, 0.10.1, 0.10.0
- [0.9.x](docs/changelogs/0.9.md) — 0.9.0
- [0.8.x](docs/changelogs/0.8.md) — 0.8.0
- [0.7.x](docs/changelogs/0.7.md) — 0.7.0
- [0.6.x](docs/changelogs/0.6.md) — 0.6.4, 0.6.3, 0.6.2, 0.6.1, 0.6.0
- [0.5.x](docs/changelogs/0.5.md) — 0.5.0
- [0.4.x](docs/changelogs/0.4.md) — 0.4.0
- [0.3.x](docs/changelogs/0.3.md) — 0.3.0
- [0.2.x](docs/changelogs/0.2.md) — 0.2.1, 0.2.0
- [0.1.x](docs/changelogs/0.1.md) — 0.1.0
- [0.0.x](docs/changelogs/0.0.md) — 0.0.1, 0.0.0
