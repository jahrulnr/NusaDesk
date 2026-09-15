# Runtime verification test plan

## Scope

This plan covers the shipped foundation contracts, the Android runtime boundary,
and the presentation slice. The accepted direction is launcher-first with Linux
as background infrastructure (ADR-0012, ADR-0013) and user-registered web apps as
the desktop (ADR-0014); SSH (ADR-0007) is the transport between this app and the
Linux it started.

Evidence status:

- **Runtime:** the PRoot bridge, the curated OpenSSH add-on, the fixed loopback
  endpoint, autostart, the foreground service, and a live guest shell in the
  terminal are implemented and device-verified on one Android 10/API 29 arm64
  device. Other API levels, 16 KB-page devices, and OEMs are open.
- **Presentation:** the launcher, readiness pill, add/edit/remove web-app form,
  web-app surface states, terminal prompts, system screen, landscape, tablet,
  light/dark themes, and font scaling are UX-verified on an x86_64 emulator
  (API 35). The emulator cannot run the arm64 PRoot bridge, so it is not runtime
  evidence.

## Risk priorities

| Area | Risk | Why |
| --- | --- | --- |
| Android native execution | High | Android 10+ writable-app execution restrictions and ABI/libc mismatch can invalidate the product |
| Download/integrity | High | A corrupt or tampered payload must never become active code |
| Process/lifecycle | High | Android can kill processes; false `RUNNING` state causes data loss and bad UX |
| WebView boundary | High | A local admin/agent UI can expose secrets or command execution to other origins |
| Storage | Medium | Rootfs/state are large and app-specific storage disappears on uninstall |
| Visual/UI | Medium | Status/recovery flows must remain usable on small screens and under font scaling |

## Foundation unit tests

- Runtime state legal download path.
- Runtime state requires `STOPPING` before `STOPPED`.
- Runtime endpoint accepts loopback hosts only.
- Runtime endpoint rejects port zero and invalid port ranges.
- Runtime endpoint produces the expected URL.

## Launcher, terminal, and web-app verification

The product shell is launcher-first (ADR-0012, ADR-0013, ADR-0014). Linux is
background infrastructure: it starts from an Activity foreground event and is
stopped only from the platform notification, so no surface renders a lifecycle
control. The user's own web apps are the desktop.

Run on a real device **and** on the UI emulator. Only the real device can verify
runtime behaviour; the emulator verifies rendering, navigation, form validation,
and persistence.

| ID | Case | Expected result |
| --- | --- | --- |
| UI-001 | First run, nothing installed | Launcher shows the setup step with real storage/ABI/network requirements; the grid lists the dashed `Add app` tile, the curated surfaces, and no user web apps; curated tiles are locked with a reason; **no** session control is offered |
| UI-002 | Install in progress | Wizard shows the step and real progress; the readiness pill reads the install state, never a session state |
| UI-003 | Install failed | Wizard shows the reason and `Try again`; no tile claims a usable desktop |
| UI-004 | System installed, terminal component missing | A dedicated "Add the Linux terminal component" card, and **no** status line that contradicts it |
| UI-005 | System and component installed, session starting | The pill reads `Starting Linux…` with **no action**; the terminal surface shows a passive wait |
| UI-006 | Session running | Pill reads `Linux ready`; tiles are enabled; the task bar shows only the way back, the title, and (for a web app) options |
| UI-007 | Session stopped (notification `Stop` tapped while the app is foregrounded) | Pill reads `Linux stopped` and states that Linux starts again on the next app launch; **no** start button; opening the app again starts it |
| UI-008 | Session failed | Pill shows the host reason and no action; nothing anywhere shows a false running state |
| UI-009 | Terminal with no session | Full-bleed dark surface, honest waiting prompt, one `Go to all apps` action, disabled accessory keys; **no** start/stop control |
| UI-010 | Terminal with a live session | Full-bleed shell, no session chrome, compact task bar |
| UI-011 | Terminal after a trip to the launcher and back | Same WebView, same scrollback, same session |
| UI-012 | Linux system screen | Install state, session value, terminal-component value, registered web-app count, loopback endpoint, profile and version; no lifecycle control |
| UI-013 | Back contract | App surface → launcher → exit; no destination can strand the user |
| UI-014 | Every tile and destination transition | No crash; a surface is registered exactly once |
| UI-015 | Phone landscape | Launcher reflows to wider columns with whole labels; the terminal fills the surface |
| UI-016 | Font scale 1.3× and 2.0× | The grid reflows to fewer columns; no clipped, truncated, or mid-word-broken labels |
| UI-017 | Light and dark OS themes | Launcher, form, system screen, and terminal chrome are legible in both; the terminal surface stays dark by design |
| UI-018 | TalkBack traversal | One node per tile, per strip, and per task-bar button; a web app tile announces its name and the gestures it supports |
| UI-019 | No external SSH surface exists | No host, port, profile, or credential input is reachable from any screen |
| UI-020 | Long-press on terminal text | Platform selection handles and the stock Copy action mode appear; a drag extends the selection instead of scrolling; row output resumes rendering when the selection closes |
| UI-021 | One-finger drag on the terminal | Scrolls the scrollback without stealing taps; while scrolled back a jump button offers a return to live output and hides again at the bottom |
| UI-022 | Holding an accessory arrow key | One key on tap, repeats while held, stops on release, cancel, or sliding off the key; TalkBack activation still emits a single key |
| UI-023 | Rotation with a live terminal | No Activity recreation; the same WebView, buffer, and SSH attachment refit to the new dimensions |

### User web-app cases

| ID | Case | Expected result |
| --- | --- | --- |
| WA-001 | Add app, empty form | Save is rejected with the name error attached to the name field, focused and announced |
| WA-002 | Add app, reserved port `22022` | The port field states that the port belongs to the terminal; nothing is persisted |
| WA-003 | Add app, port already used by another app | The port field names the port the user typed; nothing is persisted |
| WA-004 | Add app, valid name and port | The tile appears in launcher order after the curated surfaces; the count updates |
| WA-005 | Add app, optional image | The system document picker opens; the chosen image renders in the preview, in the tile, and after a restart (persisted read permission) |
| WA-006 | Remove image | The tile falls back to a monogram of the app's name |
| WA-007 | Edit an app | The form is prefilled; saving keeps identity and launcher position, and a changed name or icon reaches the tile |
| WA-008 | Remove an app | A confirmation states that nothing inside Linux is deleted; the tile and its stored record are gone and its port is free again |
| WA-009 | Search | A query filters the grid by the label the user sees; the count follows the visible grid; clearing restores it |
| WA-010 | Open an app whose server is not running | Explicit `not running` state naming the port, with one retry action; no browser error page, and no WebView is created |
| WA-011 | Open an app whose server is running | The WebView loads exactly `http://127.0.0.1:<port>/`; the state panel is gone |
| WA-012 | Navigate to a different loopback port | Blocked, reported as a blocked navigation; the other origin never loads |
| WA-013 | External HTTPS link inside the app | Opens in the system browser, not in the WebView |
| WA-014 | WebView renderer dies | The surface reports the failure with a retry; a retry starts a fresh renderer |
| WA-015 | No JavaScript interface | `addJavascriptInterface` is never called for a web app; file and universal file-from-URL access stay disabled |
| WA-016 | Rotation while an app is open | The surface re-probes and reloads the same generated origin |
| WA-017 | Web app with no user image, its server running | The tile shows the favicon served at exactly `http://127.0.0.1:<port>/favicon.ico`, for a PNG and for a real multi-size ICO |
| WA-018 | Web app with a user image | The user's image stays on the tile and the favicon is **never requested** — the user's choice is primary |
| WA-019 | Favicon missing, `404`, redirected, oversized, over the byte cap, or not an image | The tile keeps its monogram; no error, toast, or state change is shown |
| WA-020 | Endpoint down when the launcher renders, up when the app is opened | The favicon appears on the launcher after the app surface reports the endpoint reachable, with no app restart |
| WA-021 | Launcher re-renders (typing in search) | No additional favicon request is made: one request per app, never one per render |

### Regression checks tied to shipped defects

| ID | Defect | Pass condition |
| --- | --- | --- |
| X-01 | White terminal flash | No frame shows a white terminal area, including while the page loads |
| X-02 | Landscape PTY fit | `stty size` in landscape is not the compile-time `24 80` |
| X-03 | Contradictory badge | No screen shows an install badge beside a contradicting session or app statement |
| X-04 | Duplicate session control | No surface renders a start/stop control; `SessionUiState` and `LauncherStatus` expose no action (reflection test) |
| X-05 | State faking | No UI path renders a state the host is not in |
| X-06 | Double surface registration | Every launcher tile and destination opens without `IllegalStateException` |
| X-07 | Terminal contrast | Attach prompt and banner text are legible in the light theme on the dark terminal surface |
| X-08 | Edge-to-edge insets | On API 35+ the launcher header is not drawn under the status bar |
| X-09 | Web-app tile announced a raw format string | A user web app tile announces its own name and the gestures it supports, never `%1$s` |
| X-10 | Filtered grid still announced the full count | The header count follows the visible grid |
| X-11 | Soft keyboard covered the opened surface | Opening a surface from the launcher dismisses the launcher's keyboard and focus |


| ID | Case | Expected result |
| --- | --- | --- |
| EX-001 | First install on Android 10 arm64 | Guest shell starts without root |
| EX-002 | First install on Android 15 arm64 | Guest shell starts through the modern execution bridge |
| EX-003 | Android 17 target API 37 | No direct writable-app `execve` path is used |
| EX-004 | 16 KB page-size device | APK native libraries load and guest starts |
| EX-005 | Wrong ABI payload | Rejected before extraction/activation |
| EX-006 | Missing ELF interpreter/libc | Fails with actionable diagnostics; no false ready state |
| EX-007 | Corrupt archive/hash mismatch | Download is quarantined and last-known-good version remains active |
| EX-008 | Low disk space during extraction | Extraction stops safely and partial staging is removed |
| EX-009 | Process killed | State changes to failed/recoverable; no orphan supervisor loop |
| EX-010 | Screen off/Doze | Behavior is recorded; UI does not promise unsupported persistence |

## SSH-first runtime cases (implemented; device-verified on Android 10)

| ID | Case | Expected result |
| --- | --- | --- |
| SSH-001 | PRoot starts guest `/bin/sh` without root on Android 10 arm64 | `uname -a` succeeds; no root required |
| SSH-002 | Curated runtime provides a configured SSH server | The OpenSSH add-on is present and bound to `127.0.0.1:22022`; Ubuntu Base alone is rejected as insufficient |
| SSH-003 | Guest SSH server binds non-loopback | Host rejects unless an explicit LAN capability is enabled |
| SSH-004 | Host keys generated at first start | Keys live in app-private storage, never in a public catalog artifact, never logged |
| SSH-005 | Credentials/keys in Android Keystore | The password never appears in URLs, process args, logs, or WebView JS |
| SSH-006 | Changed host key | The pinned-host-key-only policy refuses it; there is no first-contact path for the local endpoint |
| SSH-007 | xterm.js bundle is local | No remote CDN fetch; bundle is pinned and integrity-checked |
| SSH-008 | Reconnect after controlled restart | WebView reconnects only after new identity/readiness, not on a stale PID |
| SSH-009 | Activity recreation during connect | Connect/cancellation/resume state survives a real recreation, not silently dropped; rotation alone no longer recreates the Activity — the retained terminal refits in place |

## Readiness and endpoint cases

| ID | Case | Expected result |
| --- | --- | --- |
| RDY-001 | PID exists but the listener is down | State stays `STARTING` or becomes `FAILED`; WebView does not load |
| RDY-002 | Guest reports concrete loopback host:port + session id | Host accepts only after a successful health check |
| RDY-003 | Malformed readiness message | Host rejects and records diagnostics; no false `RUNNING` |
| RDY-004 | Sensitive endpoint without credentials | Rejected; loopback is reachability, not authentication |
| RDY-005 | Credential reused after stop | The endpoint is gone and the session is not accepted on a new session id |
| RDY-006 | Stale listener with an old session id | Host does not attach; reconciles to the current process identity |

## Foreground-service cases

| ID | Case | Expected result |
| --- | --- | --- |
| FGS-001 | FGS type declared for API 34+ | Manifest declares `specialUse`; `dataSync` is not used as an indefinite server |
| FGS-002 | User-visible Stop action | Stopping the service tears down the guest without orphan children |
| FGS-003 | Android 15 `dataSync`/`mediaProcessing` timeout | The runtime does not rely on a time-limited FGS type for indefinite survival |
| FGS-004 | Kill under memory pressure / force-stop | UI shows `STOPPED`/`FAILED`/`RECOVERING`, never false `RUNNING` |
| FGS-005 | No `systemd`/`service install` from host | Android owns supervision; guest init is not used for lifecycle |

## Port/readiness cases

| ID | Case | Expected result |
| --- | --- | --- |
| PORT-001 | Guest daemon binds the fixed loopback endpoint | The daemon's own `Server listening on 127.0.0.1 port 22022.` report attributes the endpoint; the host publishes `127.0.0.1:22022` (ADR-0013) |
| PORT-002 | Fixed port already occupied | Start fails with the typed `GuestSshdBindFailureException` reason, the tracer is torn down, no orphan, and the host never attaches to the other listener |
| PORT-003 | PID exists but health fails | State stays `STARTING` or becomes `FAILED`; WebView does not load |
| PORT-004 | Server restarts | It reuses the same fixed port; the client reconnects only after new identity/readiness |
| PORT-005 | Child prints malformed readiness | Host rejects the message and records diagnostics |
| PORT-006 | Child binds non-loopback | Host rejects configuration unless an explicit future LAN capability is enabled |

Implemented in the workload and unit-tested at the pure-logic level:
`LocalSshEndpoint` (the single fixed loopback port + host-key scope),
`GuestSshdBindFailureException` (typed fixed-port conflict, deterministic
reason), `GuestSshdStartupLog` (only the daemon's own `Server listening on
127.0.0.1 port N.` / bind-failure lines count), `GuestSshdPidFile` (a stale pid
file never signals an unrelated process), `GuestSshdStderrMonitor` (bind report,
bounded diagnostics, EOF as the daemon-death signal), and
`LocalSshSessionFactory` (the local-only client config plus the
pinned-host-key-only trust policy, with an API-shape test that fails if a host
or port parameter is ever added).

## Autostart and background cases

| ID | Case | Expected result |
| --- | --- | --- |
| AUTO-001 | Activity foreground event with no runtime | The launcher calls `ensureRunning`; exactly one session starts; the notification appears only after readiness |
| AUTO-002 | Repeated foreground events (onCreate/onStart/onResume) | One session, one tracer, one guest daemon; later calls are no-ops that re-publish the current state |
| AUTO-003 | Foreground event while `RUNNING` | The live runtime is left alone; no second daemon, no port churn |
| AUTO-004 | Foreground event while `STOPPING` | No resurrection mid-stop; the next foreground event may start a fresh session |
| AUTO-005 | Notification `Stop` while the app is foregrounded | Guest daemon signalled and gone, endpoint refused, `STOPPED`; the pill says Linux starts again on the next app launch, and there is deliberately no in-app start control |
| AUTO-006 | App backgrounded while `RUNNING` | Home, screen-off/Doze, and Activity recreation leave the tracer, daemon, and endpoint untouched under the foreground service |
| AUTO-007 | Manifest guard | No boot receiver, no boot permission, no job/alarm path, host service unexported and `specialUse`; asserted by `RuntimeAutostartManifestTest` |
| AUTO-008 | Process death while `RUNNING` | Reopening the app reconciles honestly (`FAILED`/`STOPPED`, never false `RUNNING`) and the foreground event starts a fresh session with no orphaned listener |
| AUTO-009 | Terminal component installed while the app is open | The launcher requests the runtime without waiting for the next foreground event; the request stays idempotent |
| AUTO-010 | Half-installed device (system missing or component missing) | No start is requested; the launcher states the missing setup step |

## WebView cases

- Loads only the exact generated `http://127.0.0.1:<port>` origin.
- Blocks navigation to a different loopback port or non-allowlisted origin.
- Opens external HTTPS links with the system browser.
- Handles HTTP, WebSocket, and SSE disconnect/reconnect.
- Shows startup timeout, runtime stopped, process failure, and retry states.
- Does not expose filesystem, shell, or unrestricted Android JavaScript interfaces.
- Works at 320dp, 600dp+, landscape, rotation, and increased font scale.

## Device matrix

Minimum planned matrix:

- Android 10/API 29 baseline.
- Android 13/API 33 process/background behavior.
- Android 15/API 35 with 16 KB testing where available.
- Android 16/API 36 local-network opt-in testing.
- Android 17/API 37 local-network and dynamic-code behavior.
- At least one Pixel, Samsung, and Xiaomi/Oppo-class OEM where available.
- arm64-v8a physical device; x86_64 emulator only if added to scope.

## Exit criteria for a runtime release

- All EX/PORT/SSH/RDY/FGS/UI/WA P0 and P1 cases pass on the supported matrix.
- No open High-severity integrity, auth, data-loss, or process-orphan defect.
- Last-known-good rollback is demonstrated after a failed update.
- WebView visual and interaction inspection is completed on a real device/emulator.
- Logs contain actionable app/runtime version, ABI, Android version, state, and failure code without secrets.

## Current device-evidence constraint

**Runtime.** Verified on one physical Android 10/API 29 arm64 device (Samsung
SM-G935F, 4 KB pages): the Ubuntu Base install proof, PRoot execution under
`untrusted_app`, the curated OpenSSH add-on install/activation, the fixed
`127.0.0.1:22022` endpoint with idempotent autostart, a live guest SSH shell in
the xterm WebView, Activity recreation, PTY resize, stop/restart, guest-daemon
death reporting, and recovery after failure. No other API level, 16 KB-page
device, or OEM is covered.

**Launcher and web apps (this slice).** Verified on the x86_64 UI emulator
(API 35, AVD `LinuxWrapperUiApi35`, `emulator-5554`): the launcher in the
not-installed, installed, and stopped/failed states; the readiness pill; the
setup steps; the dashed `Add app` tile; the add form (empty, name-error,
reserved-port error); search and its count; saving a web app and seeing its tile;
the picked image rendering in the form and the tile; the edit form; the remove
confirmation and the resulting empty store; the web-app surface's unreachable
state and its options menu; the terminal's waiting and failure prompts with the
disabled accessory row; the system screen; phone landscape; a tablet-class
configuration (`wm size 1600x2560`, `wm density 320` → `sw800dp`); and both
themes. Screenshots are kept outside the repository under
`/tmp/linux-wrapper-android-sdk/qa-screenshots/launcher-webapp/`.

**Method note (emulator only).** The emulator cannot run the arm64 PRoot bridge,
so the installed and installed-but-component-missing launcher states were first
produced by placing the app-private marker files the runtime detects
(`runtimes/ubuntu-base-arm64/active/etc/os-release`, `.../usr/bin/sh`,
`addons/guest-ssh-openssh/active/usr/sbin/sshd`) and the `READY` install
snapshot in the app's own storage with `run-as`. Nothing was executed by that
instrumentation: it drives the presentation states only. The markers were removed
after that pass.

### Favicon fallback run (2026-09-14, emulator-5554, API 35)

One pass of the WA-017…WA-021 cases against `app-debug.apk` on the x86_64 UI
emulator, with a loopback fixture the app could actually reach:
`python3 /tmp/lw-favicon-fixture/serve.py 8000` on the host, published to the
device as `127.0.0.1:8000` with `adb reverse tcp:8000 tcp:8000`, serving a page
at `/` and a switchable body at `/favicon.ico`. The fixture's request log is the
evidence for what the app asked for and how often.

| Case | Method | Observed result |
| --- | --- | --- |
| WA-017 (PNG) | `/favicon.ico` = 64x64 PNG; add a web app on port 8000 with no image | Tile shows the favicon; log shows exactly one `GET /favicon.ico` from the app |
| WA-017 (ICO) | `/favicon.ico` = real 64x64 ICO; cold launch | Tile shows the favicon: `BitmapFactory` decodes ICO on API 35 |
| WA-018 | Picked a 256x256 PNG through the document picker and saved | Tile shows the user's image; the log shows **zero** `GET /favicon.ico` for that app, on the save and on a later cold launch |
| WA-019 | `/favicon.ico` = 4000x4000 PNG, then a 1 MB PNG, then an HTML body, then `404`, then a stopped fixture | Monogram in every case; no error, toast, or state change; one bounded request per case |
| WA-020 | Fixture down at launch (monogram), fixture started, app opened, Back to the launcher | Tile shows the favicon without a restart; log shows the probe `GET /`, the retry `GET /favicon.ico`, and the WebView's own `GET /` |
| WA-021 | Nine characters typed into the launcher search field (nine re-renders) | Request count unchanged at one |

Screenshots are kept outside the repository under `/tmp/lw-favicon-qa/`
(`13-evidence-montage.png` for WA-019, `21-priority-montage.png` for the
user-icon/favicon/monogram order, `16-retry-step4-launcher-favicon.png` for
WA-020). The fixture app was removed and the pushed image deleted afterwards, so
the emulator's web-app store is empty again.

**Not covered by this pass.** The reachability retry is triggered by the app's own
surface, which needs an unlocked tile; the emulator's app-private `READY`
snapshot (see the method note above) provided that, so the path is covered on the
emulator but not on a device that really runs the runtime. The stale-result guard
(app edited or deleted while a fetch is in flight) and the platform decode of a
malformed image are covered by inspection and by the emulator cases above, not by
a dedicated run. No favicon case was run on the arm64 device.

The curated **install** itself also ran for real on the same emulator during this
session: `Start setup` completed download → digest verification → safe extraction
→ atomic activation, leaving a 122 MB activated rootfs with an empty `downloads/`
staging directory, and the launcher advanced from the system step to the
"Add the Linux terminal component" step. That is install-pipeline evidence on
API 35, not execution evidence: the terminal component's install is still the
known x86_64 emulator gap recorded below, and the guest shell, fixed port, and
background continuation remain arm64-device evidence.

A unit-test-only result remains insufficient for lifecycle, execution, or
visual behaviour; those need the device matrix above.

Device-verified lifecycle cases (Android 10/API 29, arm64):

| Case | Observed result |
| --- | --- |
| Start session to a live guest shell | `root@localhost:~#` in xterm over the guest OpenSSH endpoint |
| Activity recreation via rotation (pre-`configChanges` build) | Session resumed; terminal regained input focus; PTY resized |
| Terminal → Desktop → Terminal | Same session, scrollback preserved, input still works |
| PTY resize | `stty size` 22x39 portrait vs 24x80 landscape, both directions |
| Terminal reopened after the launcher redesign | Same session, scrollback preserved, no session card on the surface |
| Stop session | Guest daemon signalled and gone; endpoint no longer answers; `STOPPED` |
| Restart after stop | New endpoint; terminal auto-attached; live shell |
| Guest daemon killed externally | `FAILED` with "guest OpenSSH sshd exited unexpectedly"; no orphan |
| Restart after failure | New session; live shell again |

### Background/foreground lifecycle run (2026-09-13, Samsung SM-G935F, Android 10)

Two runs on the same device, both against `app-debug.apk` (`versionCode 1`,
`minSdk 29`, `targetSdk 37`) with app-private storage already carrying the
activated Ubuntu Base rootfs and OpenSSH add-on: run 1 on the pre-fix build
(md5 `dd0e578d204c2f2de97628a8f2447d59`), run 2 re-running the matrix on the
lifecycle fixes (md5 `2426c0ae9322ca777394032fcb6596af`).

| Case | Method | Observed result |
| --- | --- | --- |
| Start session | UI start action | New PRoot tracer + guest `sshd` on a new loopback port; `RUNNING`; ongoing notification "Linux runtime running · Loopback http://127.0.0.1:<port>" |
| Home/background | `KEYCODE_HOME`, 20 s | Tracer, daemon, and endpoint unchanged; service still `isForeground=true`; state `RUNNING` |
| Screen off/on | `KEYCODE_POWER`, 60 s `Dozing`, then wake (device locked and unlocked) | Same processes and endpoint; state `RUNNING`; no service churn |
| Activity recreation | Rotation portrait → landscape → portrait | Same session id and endpoint; UI re-rendered the running state |
| Force-stop then reopen | `am force-stop`, relaunch | Process group died together (tracer, daemon, endpoint refused); reopened UI shows "Stopped" — no false `RUNNING` |
| Host process crash | `am crash` | Same group death, no orphan; reopened UI shows "Stopped" |
| Notification Stop action | Expanded the FGS notification, tapped `Stop` | `signalled guest sshd pid N to stop` → `guest sshd stopped; 127.0.0.1:N no longer answers` → `STOPPED`; service and notification removed; port refused |
| In-app stop | UI `Stop` on the session card (the pre-ADR-0014 control, since removed) | Same clean teardown as the notification action |
| Fast stop → start | Tap `Stop`, then start at the first frame the control re-enables | A new service instance reconciled the persisted `STOPPED` snapshot and started a fresh daemon on a new port; no duplicate daemon, no leaked port |
| Repeated cycles | 3 × stop → start | Every stop left 0 tracer/daemon processes and a refused port; every start produced exactly one tracer + one daemon on a fresh port answering with the SSH banner |
| Pid-file safety (unreadable foreign pid) | Pointed the pid file at a live process the app may not inspect (SELinux `runas_app`) | No signal was sent; the process survived and the next session started normally |
| Pid-file safety (readable mismatched pid) | Pointed the pid file at the app's own process, then started a session | `pid N is not this guest sshd; leaving it alone` (twice, reconcile + start reclaim); the process survived and the session started |
| External service stop | `am stopservice` | Rejected ("not exported"); no external actor can stop the host service |
| Guest daemon external kill | Documented from the earlier run | `FAILED` with "guest OpenSSH sshd exited unexpectedly"; no orphan |

Evidence: process/service/state dumps per step, a continuous `logcat`
capture, and screenshots (`b1-session-running.png`, `b4-landscape.png`,
`b15-final-stopped.png`) were captured for these runs; the raw files are kept
outside the repository.

Lifecycle cases **not** completed, and why:

| Case | Why it is open |
| --- | --- |
| Tracer-only SIGKILL orphan probe / reclaiming a *real* untracked daemon | Not reproducible from `adb` on a production build: neither `shell` nor `run-as` may signal the app's children, and this OEM kills the whole process group when the host process dies. The reclaim path itself was exercised on-device (see the pid-file safety rows), but with a foreign pid rather than a real orphan |
| Service destroyed while a live session runs | The host service is not exported, so no external `stopService` is possible; the teardown path is covered by unit tests and code review only |

### Fixed-port and autostart run (2026-09-14, Samsung SM-G935F, Android 10)

One run on the same arm64 device (`ce0516054597102d05`, API 29, 4 KB pages)
against `app-debug.apk` md5 `e99b8d8883895712d42fb8a49d653f11`, whose installed
copy was byte-identical (`adb exec-out cat $(pm path …) | md5sum`). App-private
storage already carried the activated Ubuntu Base rootfs and OpenSSH add-on.

The autostart boundary was fired as `ACTION_ENSURE_RUNNING` from the app's own
UID (`run-as … am start-service`), because the host service is deliberately not
exported. The Activity-side wiring of `RuntimeHostService.ensureRunning` landed
in the launcher slice (ADR-0014); the same service, controller, and workload path
is exercised either way.

| Case | Method | Observed result |
| --- | --- | --- |
| Fixed port bind | `ACTION_ENSURE_RUNNING` | `guest OpenSSH sshd ready on 127.0.0.1:22022` → `RUNNING endpoint=127.0.0.1:22022`; `ss -ltn` = `127.0.0.1:22022` only; `/proc/net/tcp` = `0100007F:5606` owned by the app UID; daemon argv `sshd_config -p 22022 [listener] 0 of 10-100 startups` |
| Idempotent autostart | 3 further `ENSURE_RUNNING` intents | Only `state=RUNNING endpoint=127.0.0.1:22022` republishes; the same tracer/daemon PIDs stayed alive; no second session, no port churn |
| User-visible notification | `dumpsys notification --noredact` + shade screenshots | title `Linux runtime running`, text `Loopback http://127.0.0.1:22022`, one action `Stop`, a `startActivity` content intent, channel `runtime_host`, `mFgServiceShown=true` |
| Background continuation | `KEYCODE_HOME`, then 60 s screen-off (device idle `ACTIVE`), then wake | Tracer, guest daemon, and endpoint unchanged at every step; service `isForeground=true` with the same notification |
| Stop via the notification action | Tapped `Stop` in the shade (not an adb intent) | `signalled guest sshd pid 16937 to stop` → `guest sshd stopped; 127.0.0.1:22022 no longer answers` → `STOPPED`; 0 tracer/daemon processes, port refused, service stopped and notification removed |
| Fixed-port conflict, plain holder | `nc -l -p 22022` as the `shell` user, then `ENSURE_RUNNING` | `STARTING` → `GuestSshdBindFailureException: guest sshd could not bind 127.0.0.1:22022: the fixed local SSH port is already in use` → `FAILED` with that reason; no orphan tracer; the app never attached |
| Fixed-port conflict, SSH banner | `echo SSH-2.0-OpenSSH_9.6p1 \| nc -l -p 22022`, then `ENSURE_RUNNING` | `FAILED` with `…: another listener already holds it, and the app never attaches to a listener it did not start`; no orphan tracer |
| Restart keeps the fixed port | `ENSURE_RUNNING` after a stop | Fresh tracer + daemon (new PIDs) listening on the same `127.0.0.1:22022` |
| Process death then reopen | `am force-stop` while `RUNNING`, reopen the app, `ENSURE_RUNNING` | The whole group died (no orphan, port refused); the new process started a fresh session on `127.0.0.1:22022` with no false `RUNNING` in between |

Screenshots (`notification-fixed-port.png`, `notification-expanded-stop.png`) and
the raw `logcat`/`ss`/`ps` dumps were captured for this run; the raw files are
kept outside the repository. Cases **not** covered by this run: an Activity-side
`ensureRunning` call (that wiring landed in ADR-0014 and still needs an arm64
re-run), OEM/API levels newer than 29, and 16 KB page-size devices.

### Lifecycle fixes (2026-09-13)

Three defects were found by reading the lifecycle code against the
process/lifecycle rules:

1. `GuestSshdWorkload.stop()` now reclaims a daemon the pid file names when no
   daemon is tracked in-process. The service calls `stop` to reconcile a
   session persisted before a host-process death; previously that path did
   nothing, so a tracee that outlived its tracer kept its loopback port until
   the next start. Device-verified: the path runs on every reconcile without
   breaking a start, and it never signals a pid that is not this daemon.
2. The unexpected daemon-output end-of-file path now runs the full teardown
   (signal the daemon through its pid file, drop the host-key pin, confirm the
   endpoint) before reporting `FAILED`, instead of only clearing in-process
   state.
3. `RuntimeHostService.onDestroy()` now stops the runtime when a session still
   requires a live workload. A service destroyed by the system (or by a future
   `stopService` caller) previously left the guest daemon running with no
   notification and no supervision. Terminal states remain a no-op. Unit
   tested; not device-triggerable while the service stays unexported.

## License and distribution note

- PRoot is GPL-2.0-or-later. Packaging/distributing it requires GPLv2+
  source/notice obligations and a combined-work legal review before
  distribution. No GPL-cleanliness claim is made.
- Dropbear (permissive/MIT-style) and OpenSSH (BSD) are includable in the
  curated rootfs, but license/notice/attribution obligations apply.
- No Google Play compliance or approval is claimed. Initial distribution is
  sideload/F-Droid or another controlled channel; a Play submission needs a
  separate policy review.
