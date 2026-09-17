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
- **Compose adapter only:** the bounded `udocker compose` guest CLI
  (ADR-0025) is additionally device-verified on one Android 12/API 31 arm64
  device (Samsung S10e) — see the *udocker compose verification* table below.
  This extends Compose evidence to API 31 only; it does not extend the core
  runtime's API-29 evidence boundary.
- **Presentation:** the launcher, non-ready status visibility, add/edit/remove web-app form,
  web-app surface states, terminal prompts, system screen, permission shortcut,
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
| UI-006 | Session running | No launcher status pill is shown in the normal ready state; tiles are enabled; the task bar shows only the way back, the title, and (for a web app) options |
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
| UI-024 | System tile and permission shortcut | Launcher tile is labelled `System`; opening it shows the App permissions card; one tap opens this app's Android App Info page, where platform permissions can be reviewed; no runtime prompt is shown inside NusaDesk |
| UI-025 | Launcher icon plate contrast | In light and dark themes, the neutral launcher icon plate separates the bright teal vector/monogram from the desktop background without reducing label or icon legibility |

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

## udocker compose verification (implemented; device-verified on Android 12)

The bounded `udocker compose` adapter (ADR-0025) runs inside the guest as a
product-owned CLI on pinned udocker 1.3.17/PyYAML 6.0.1 source. Host-side
coverage: the domain suite (64 tests), the parser suite (22), the asset
harness (`assets/compose/test/run_tests.py`, 35 tests against the real
pinned tarballs and a fake `lw-udocker` child), and the
payload/wiring/launcher suites.

| ID | Case | Expected result |
| --- | --- | --- |
| CMP-001 | Compose file with an unsupported key (`build`, `networks`, `healthcheck`, `deploy`, `secrets`, `configs`, anchors/aliases/tags, `${...}` interpolation, long-form `depends_on`, `on-failure`, named volumes, container-only ports, key-only `environment`) | Parse fails closed with a path-specific error; nothing is silently ignored |
| CMP-002 | `ports:` declared on any service | Refused at `up` admission (`runtime_problems()`), re-refused on every supervisor scan, and fail-closed again at argv build; no `--publish` token reaches udocker |
| CMP-003 | Bind source outside the workspace, or `ro`/`read_only` | Rejected: sources must resolve inside `~/nusadesk`; udocker volumes are always read-write |
| CMP-004 | `up` without `-d` | Refused: there is no foreground `up` |
| CMP-005 | `restart: always` service exits | Supervisor respawns with exponential backoff (1 s doubling to 60 s, reset after 30 s healthy); `ps` reports honest states |
| CMP-006 | `stop`/`down`, then a session restart | Persisted `manual_stop` keeps the project down for every policy until `start`/`up` clears it |
| CMP-007 | `down` while a pid file still names a live child | `down` refuses to remove containers/state (it would orphan the child); state stays `desired=false, manual_stop=true` |
| CMP-008 | Environment values | Written to a mode-0600 env file passed as `--env-file`; never on argv; redacted in supervisor logs; newline values rejected |
| CMP-009 | Re-`up` after a changed image | `--pull=reuse` reuses the named container; a `down` is required to recreate |
| CMP-010 | `down` cleanup | `udocker rm` per service is best-effort after all pid files are dead, then project state is removed; a container udocker cannot remove is left for manual `udocker rm` |

Device pass (2026-09-16, Samsung S10e SM-G970F `R39M209Q3TM`, Android
12/API 31, arm64, 4 KB pages; guest endpoint `127.0.0.1:22022`):

| Case | Observed result |
| --- | --- |
| Wired overlay | 16 vendored files on disk; compose paths, inner-PRoot and `/system` binds, pre-created `multi-user.target.wants` links for the compose supervisor and the user-service manager; `systemctl` running with `lw-compose-supervisor` active/enabled; `/usr/local/bin/udocker --version` and image list OK |
| `up -d` on a pre-existing alpine image | Supervisor spawned the child through `/usr/local/bin/udocker`; nested PRoot executed it; the marker appended through the read-write workspace bind |
| `restart: always` | Six restart cycles observed |
| `stop` | Marker froze; `manual_stop` persisted |
| `down` | Project state and container removed; nothing left behind |
| Uncached `busybox:latest` pull | Completed in 16.36 s through upstream udocker's download path; arm64 manifest verified; image removed afterwards |
| `unless-stopped` | Service returned after host force-stop/relaunch and stayed down after `stop` until explicit `start` |
| Port declaration traffic test | `ports: ["127.0.0.1:18924:8080/tcp"]` produced **no** `:18924` listener while the container kept listening on `*:8080`, reachable via the device LAN IP — udocker/PRoot strips `host_ip`, so the MVP rejects every `ports:` declaration |
| Same-version overlay refresh | The initial pass predates the digest-aware `detect()` guard and deleted one stale compose file by hand; the guard now re-hashes every vendored file per session and reinstalls a stale overlay automatically |

Not covered: other API levels, 16 KB-page devices, and OEMs; no full
Compose compatibility is claimed.

Second device pass for SYS-002/SYS-003 (2026-09-17, same S10e, from the user's
report that a registered web app stayed dead after a force-close and relaunch):

| Case | Observed result |
| --- | --- |
| Before the fix | With the session up, `systemctl --user is-active nusashell.service` read `inactive` and `127.0.0.1:10994` refused connections; the unit was enabled in `/root/.config/systemd/user/default.target.wants` |
| Fix mechanism | `HOME=/root SYSTEMD_DEFAULT_TARGET=default.target systemctl --user init` started the enabled user unit (`active (running)`) and `GET /` answered `HTTP/1.0 200 OK`; with the replacement's built-in default target nothing started |
| User flow after the fix | `am force-stop` → `am start`: the app re-installed the bridge payload (two new vendored files), the tree read `libproot → system manager → user manager → nusashell`, and port 10994 answered `HTTP 200` with no manual start |
| Graceful stop | SIGTERM to the session manager stopped `lw-user-manager.service`, which stopped the user unit, removed its status mark, and closed the port |
| Stale user mark | A planted `/run/user/0/run/nusashell.service.status` (`MainPID=999999`) was gone after the next wire-up and replaced by the live pid's mark |

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

## Guest awareness README cases

| ID | Case | Expected result |
| --- | --- | --- |
| README-001 | New/first guest session | App creates writable `/root/README.md` with concise feature awareness, management notice, and installed APK version |
| README-002 | Same app version, unchanged file | No rewrite is needed; content remains unchanged |
| README-003 | App version changed or README stale/edited | App atomically regenerates only `/root/README.md`; unrelated `/root` files remain untouched |
| README-004 | `/root` is a symlink or unsafe path | Session setup fails closed; writer never escapes the active rootfs |

## Guest os-release metadata

| ID | Case | Expected result |
| --- | --- | --- |
| OSREL-001 | New PRoot spec | Effective `/etc/os-release` keeps Ubuntu fields and contains namespaced contributor/source assignments |
| OSREL-002 | Base `/usr/lib/os-release` changes after apt/base-files update | Next PRoot spec regenerates overlay from updated base content while preserving NusaDesk assignments |
| OSREL-003 | Base os-release symlink escapes active rootfs | PRoot spec fails closed; no outside file is copied |

## Resolver doctor and session-temporary state

| ID | Case | Expected result |
| --- | --- | --- |
| DOC-001 | Valid Android DNS and matching host source | Doctor reports `OK`; no rewrite |
| DOC-002 | Host resolver source missing, modified, oversized, or symlinked | Doctor atomically repairs only the source file when valid Android DNS exists; symlink target is never followed |
| DOC-003 | Android has no usable DNS | Doctor reports `NO_ACTIVE_DNS`; it does not write an empty file or public-DNS fallback |
| DOC-004 | Burst of resolver `rename`/`delete`/`create` events | Parent-directory observer debounces the burst; periodic tick remains the correctness fallback |
| DOC-005 | Guest/package operation changes `/etc/resolv.conf` | Fresh PRoot launch and device probe confirm whether the host source repair reaches the guest; no arbitrary rootfs repair is attempted |
| TMP-001 | Marker files/directories/symlinks under active rootfs `/tmp` before a new session | Cleanup removes only `/tmp` children and preserves the real `/tmp` directory and symlink targets outside it |
| TMP-002 | Rootfs `/tmp` missing, symlinked, or a regular file | Missing directory is created; symlink/non-directory fails closed before guest launch |
| TMP-003 | Normal stop/restart | `/tmp` is cleared after the tracer stops; workspace, `/var/tmp`, package state, and compose cache remain |
| TMP-004 | Force-stop/reboot | Immediate cleanup is not claimed; the next explicit app launch clears stale `/tmp` before Linux starts |
| SYS-001 | Rootfs has apt-installed real/native `systemctl` or Python paths | Strict PRoot file binds make product `systemctl3`/Python effective without deleting rootfs-owned files |
| SYS-002 | A `systemctl --user` unit is enabled into `default.target` (for example a web app) and the host is force-closed and relaunched | The product-owned `lw-user-manager.service` unit runs the replacement in `--user` mode with `HOME`/`XDG_RUNTIME_DIR`/`SYSTEMD_DEFAULT_TARGET=default.target`, so the enabled user unit is active again after the session restart and its port answers; stopping the session stops the user unit too |
| SYS-003 | A previous session left `.status` marks under `/run/user/<uid>` | Wire-up wipes user marks as well as system marks, so `systemctl --user is-active` never reports a service the new session did not start |

The resolver policy is covered by `GuestResolverDoctorTest`; temporary-state
cleanup is covered by `GuestEphemeralStateCleanerTest`; strict systemctl
binds are covered by `GuestServiceBridgeTest` and `ProotBindMountTest`.

## WebView cases

- Loads only the exact generated `http://127.0.0.1:<port>` origin.
- Blocks navigation to a different loopback port or non-allowlisted origin.
- Opens external HTTPS links with the system browser.
- Handles HTTP, WebSocket, and SSE disconnect/reconnect.
- Shows startup timeout, runtime stopped, process failure, and retry states.
- Does not expose filesystem, shell, or unrestricted Android JavaScript interfaces.
- Works at 320dp, 600dp+, landscape, rotation, and increased font scale.

## Android capability bridge and live media (ADR-0030, ADR-0031)

| ID | Case | Expected result |
| --- | --- | --- |
| ACB-001 | Malformed, oversized, duplicate-key, or unknown-version request | Parser rejects the frame; the live server maps it to a bounded connection response without exposing platform detail |
| ACB-002 | Missing or wrong per-session token | Response is `unauthorized`; the capability source is not called |
| ACB-003 | Unsupported method | Response is `unsupported-method`; no shell/reflection/Android class dispatch occurs |
| ACB-004 | Battery source unavailable or a platform field is unknown | RPC returns explicit unavailable/unknown values; no fabricated public fallback is emitted |
| ACB-005 | Battery snapshot projection | Guest `/sys/class/power_supply/battery` values map to the documented Linux-shaped units and are atomically refreshed |
| ACB-006 | Projection state path is symlinked or unsafe | The optional sysfs bind is omitted; the RPC bridge remains independent and usable |
| ACB-007 | Guest session stops, fails, or is rejected before readiness | TCP listener, worker pools, token, and projection are closed/cleared; no bridge survives the session |
| ACB-008 | Guest calls `battery.status` from the active session | Physical device returns Android-backed capacity/status and the projection is readable from inside the guest |
| ACB-009 | Background location, FGS start, or automatic permission prompt | Remains intentionally unwired; no background wake-up or consent UI comes from the bridge worker |
| ACB-010 | Sensor source unavailable, timeout, registration failure, or non-finite platform values | RPC returns `sensor-unavailable`, `sensor-timeout`, or bounded unavailable error; no fabricated reading is encoded |
| ACB-011 | Guest calls `sensor.accelerometer`/`sensor.gyroscope` from the active session | Physical device returns finite x/y/z values, bounded accuracy text, and platform timestamp, or an explicit unavailable/timeout result |
| ACB-012 | Location grant missing, previously denied, provider disabled, or no fix | RPC returns `location-permission-required`, `location-permission-denied`, `location-unavailable`, or `location-timeout`; it never opens a permission UI |
| ACB-013 | Foreground location grant and usable provider | Guest receives a bounded finite location fix when the provider actually delivers one; background/continuous location is not claimed |
| ACB-014 | Camera/mic/messaging/telephony permission missing or side-effect method requested | Each read/action returns a typed permission/unavailable error; `sms.send` and `phone.call` return `action-unsupported` |
| ACB-015 | Unified live media control contract | `media.start`/`media.status`/`media.stop` return the documented explicit states and typed `media-*` errors; the media methods declare no request parameters — only the calendar writes do (ACB-023) — and no media bytes cross JSONL |
| ACB-016 | Location stream start/poll/stop | Foreground-only bounded queue/poll works; stop/close removes listener; no FGS/background claim |
| ACB-017 | Loopback RTSP server protocol | Server binds only IPv4 `127.0.0.1`, accepts RTSP-over-TCP interleaving only, advertises H.264/AAC SDP, replays a keyframe, caps clients, and drops slow consumers |
| ACB-018 | Positive live media consumer on a physical device | With test-only camera/mic grants and visible app, guest receives a running RTSP URL; `ffprobe`/`ffmpeg -f null -` sees H.264/AAC for a bounded interval; `media.stop` removes the stream and grants are revoked |
| ACB-019 | A provider rejects a SQL `LIMIT` token in the sort order (for example Samsung's CallLog) | The read uses a plain newest-first order and caps rows in Java, so the guest still receives a `READING` snapshot and never `capability-unavailable` (regression guard: no `LIMIT` token in the provider sort order) |
| ACB-020 | Video RTP timestamp unit | A 1 s presentation time maps to 90 000 ticks on the H.264 90 kHz clock, so consecutive frames sit ~3000 ticks (33 ms) apart; a ×1000 unit error that made the stream look frozen is a regression-guarded failure |
| ACB-021 | Camera-only and microphone-only modes | `media.camera.start` / `media.microphone.start` require only their own grant, claim only their own foreground-service type, advertise only their own SDP track (the other track's `SETUP` answers 404), report `mode` plus only their own status fields, and a start in a different mode while one is live answers `media-mode-conflict` |
| ACB-022 | Interleaved channel pair requested by the client | The server honours the client's `interleaved=<rtp>-<rtcp>` per session (a single-track session's first advertised track asks for 0-1) and replays/RTCPs on that pair; a malformed pair (RTCP ≠ RTP+1) is still refused with 461 |
| ACB-023 | Bounded request `params` | A flat object of at most 8 keys with safe short keys and bounded scalar values decodes; nested objects/arrays, oversized keys or strings, control characters, non-scalar values, and a `params` on a method that does not declare parameters are rejected (`unsupported-parameter`), and a fifth top-level field other than `params` fails closed at decode time |
| ACB-024 | Calendar read and bounded write | `calendar.list` reads the fixed seven-day instance window, returns at most 50 rows with a `truncated` flag and no description/attendee/organizer columns, and maps permission/unavailable states to typed errors; `calendar.insert`/`update`/`delete` validate every field before a provider call (title/location length and control characters, `begin < end`, ≤ 24 h duration, start within −24 h…+366 d, all-day on whole UTC days and carrying its range), target only a writable calendar, carry a timezone on every write, and answer typed `calendar-*` errors with an audit line that carries ids only |

JVM coverage implements ACB-001 through ACB-007, ACB-010, ACB-012, and ACB-014 through ACB-017, plus ACB-019 through ACB-024. ACB-007 cleanup is covered by
the physical stop/relaunch pass below; the bridge is deliberately a single-use
session object and is not unit-restarted. ACB-009 remains a scope guard; ACB-008,
ACB-011, and ACB-018 are covered on the physical devices documented below.

Physical ACB-008 and ACB-011 passes (2026-09-16), plus ACB-018 (2026-09-17):

- Samsung S10e SM-G970F `R39M209Q3TM` (Android 12/API 31, arm64, 4 KB
  pages): a Python probe executed inside the live guest read
  `/run/nusadesk/android-bridge.env`, called `battery.status` over the session
  loopback port, and received `ok=true`, `available=true`,
  `capacity_percent=84`, `status=not-charging`, and `health=good`. The same
  probe read `capacity=84` and `status=Not charging` from
  `/sys/class/power_supply/battery`. The notification Stop action stopped the
  session and the host-side bridge config/projection files disappeared; a
  fresh user-visible launch recreated them.
- Samsung S7 Edge SM-G935F `ce0516054597102d05` (Android 10/API 29, arm64,
  4 KB pages): the same guest probe received `ok=true`, `available=true`,
  `capacity_percent=100`, `status=full`, `health=good`, and matching
  `capacity=100`/`status=Full` from projected sysfs.

- On the same two devices, guest calls to `sensor.accelerometer` and
  `sensor.gyroscope` returned `ok=true`, `available=true`, finite x/y/z values,
  `accuracy="high"`, and platform timestamps. This is one-shot evidence only;
  streaming/backpressure is unverified.

- On S10e, `location.get` returned `location-permission-denied` with the app's
  foreground grants absent. After a test-only foreground grant, the same
  request returned the bounded `location-timeout` result because no fresh
  provider fix arrived; the grant was revoked afterwards. A successful live
  fix remains unverified.

- On S10e on 2026-09-17, with test-only CAMERA and RECORD_AUDIO grants and
  the Activity visible, the actual guest CLI ran `media.start` and returned
  `running` with `rtsp://127.0.0.1:<port>/`, H.264/AAC, 1280x720, 30 fps, and
  `client_limit=2`. An `adb forward` exposed that device-loopback port only to
  the host test consumer: `ffprobe` enumerated H.264 1280x720 and AAC 44.1 kHz
  mono, and `ffmpeg -t 3 -f null -` exited successfully with no decode errors.
  The guest CLI then ran `media.stop` and `media.status` (`stopped`), the
  foreground service disappeared, and both test-only grants were revoked.
  No capture file was written.

- On the same S10e pass, an adb-driven sweep exercised every guest-facing
  surface from inside the guest: the generated CLI (help/usage exits, fixed
  allowlist, no `call` passthrough, media start/status/stop, idempotent stop,
  `bridge info`) and all bridge methods — battery, accelerometer, gyroscope,
  location (typed timeout, no fresh fix indoors), contacts, call log, SMS
  inbox, telephony info, telephony cellinfo, and the location stream
  start/poll/poll/stop cycle — plus the negative cases (wrong token →
  `unauthorized`, unknown method → `unsupported-method`, `sms.send`/`phone.call`
  → `action-unsupported`, malformed frame → `malformed-request`, revoked
  camera/microphone grants → `media-permission-denied` with CLI exit code 1).
  The sweep found one real defect: `calllog.list` returned
  `capability-unavailable` on every call because the read put a SQL `LIMIT`
  token in the provider sort order, which Samsung's CallLog provider rejects
  (`IllegalArgumentException: Invalid token LIMIT`). The fix (plain sort order,
  Java-side row cap, bounded diagnostic log) is covered by ACB-019 and was
  re-verified on the device: `calllog.list` now returns a `READING` snapshot.

- A raw-socket RTSP probe on the same device (no ffmpeg involved) then exposed a
  second, more serious defect: the server transmitted video RTP continuously
  (~220 packets/s, valid FU-A, marker per frame) but with timestamps ~1000× too
  large — consecutive frames sat 2 999 700 ticks apart (33 s on the 90 kHz
  clock) instead of 3000 (33 ms), so every decoder showed one frozen picture
  while audio played. The H.264 conversion is now `presentationTimeUs * 90 /
  1000`; after the fix the same probe measures 2999–3000-tick deltas, a 6 s
  consumer decodes ~145 frames instead of 1, and a 5 s recording holds 105
  video frames with a video duration matching audio (ACB-020). Note for future
  passes: the scene was physically dark (the stock camera app showed a black
  viewfinder and its own "use Night mode" hint), so frame *content* stays dark;
  brightness is an environment property, not a stream defect.

- The three track modes were then verified end to end on the same device with
  the actual guest CLI. Camera-only: `media start --camera` succeeded while
  RECORD_AUDIO was revoked (so the microphone grant is genuinely not required
  and the microphone foreground type is not claimed), `ffprobe` listed only
  `h264,video`, `ffmpeg` decoded 181 video frames in 6 s, and the notification
  read "Camera streaming…". Microphone-only: `media start --microphone`
  succeeded while CAMERA was revoked, `ffprobe` listed only
  `aac,audio,44100,1`, a 5 s recording held 216 audio frames with no decode
  errors, and the notification read "Microphone streaming…". Combined
  `media start` still served both tracks (107 video frames in 5 s on the dark
  scene). A start in a different mode while a session was live answered
  `media-mode-conflict` in both directions (ACB-021). This pass also exposed
  ACB-022: the single-track stream first failed with `461 Unsupported
  Transport` because the client numbers its first advertised track 0-1 while
  the server insisted on the audio default 2-3; the server now honours the
  requested pair per session, and ffprobe/ffmpeg consume both single-track
  modes after the fix.

- Calendar read and write were verified on the same device with the generated
  guest CLI (ADR-0032, ACB-023/ACB-024). `calendar list` returned a valid empty
  read (`count 0`) before any test data existed. A write cycle then ran with
  `WRITE_CALENDAR` granted: `calendar add --title … --begin-ms … --end-ms …`
  answered `written true` with `event_id 138`, and the following
  `calendar list` returned that event with `calendar_name "My calendar"` and
  `timezone "Asia/Jakarta"` and without description/attendee fields;
  `calendar update 138 --title …` changed the title as observed in the next
  read; `calendar delete 138` returned the read to `count 0`. An all-day insert
  (`--all-day` on day-aligned bounds) returned `all_day true` with
  `timezone "UTC"` and was deleted afterwards, leaving the calendar empty. A
  25-hour request answered `calendar-invalid-argument` without a provider call.
  With `WRITE_CALENDAR` revoked, the write answered
  `calendar-permission-required` while `calendar list` still returned a
  reading, which shows the read and write grants are independent. Logcat held
  only `calendar write op=… event=… calendar=…` lines — the probe titles appear
  nowhere in the log. This pass exposed two defects that the JVM fakes could
  not: the `Instances` window has to travel in the URI path (a selection
  argument made the provider throw and the read degrade to
  `capability-unavailable`), and the provider rejects an insert whose values
  lack `eventTimezone` (`Event values must include an eventTimezone`). Both are
  fixed and guarded.

These are device evidence for API 29/API 31 on two Samsung arm64 devices, not a
product-wide OEM/API claim.

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

**Launcher/System follow-up (2026-09-17).** The rebuilt APK was installed and
visually inspected on two physical arm64 phones:

- Samsung S7 Edge SM-G935F, Android 10/API 29: launcher showed `System` with
  no normal `Linux ready` pill; the neutral icon plates separated the bright
  teal vectors from the dark desktop. The System screen showed the App
  permissions card and one tap opened Android Settings → NusaDesk App Info.
- ASUS ROG Phone 2 ASUS_I001DE, Android 11/API 30: the same launcher/status and
  contrast behavior was visible; the System screen's `Open app settings` button
  opened `com.android.settings/.applications.InstalledAppDetails` for NusaDesk.

No permission was granted and no runtime permission prompt was added during this
UI pass; the settings shortcut was verified as navigation only.

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
