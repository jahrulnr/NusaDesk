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
| WA-017 | Web app with no user image, its server running | The tile shows the icon the app's own document declares (same-origin only) and, when it declares none, the favicon served at exactly `http://127.0.0.1:<port>/favicon.ico` — for a PNG, for a real multi-size ICO, and for a declared PNG over the old cap |
| WA-018 | Web app with a user image | The user's image stays on the tile and the favicon is **never requested** — the user's choice is primary |
| WA-019 | Favicon missing, `404`, redirected, oversized, over the byte cap, or not an image — including a declared icon that is over the cap or names another origin | The tile keeps its monogram; no error, toast, or state change is shown |
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

Device pass (2026-09-16, Samsung S10e SM-G970F, Android
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
| AUTO-007 | Manifest guard | Exactly one receiver component: the opt-in boot trigger (`BootStartReceiver`, unexported, filter pinned to `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`), no job/alarm path, host service unexported and `specialUse`; asserted by `RuntimeAutostartManifestTest` (ADR-0037) |
| AUTO-008 | Process death while `RUNNING` | Reopening the app reconciles honestly (`FAILED`/`STOPPED`, never false `RUNNING`) and the foreground event starts a fresh session with no orphaned listener |
| AUTO-009 | Terminal component installed while the app is open | The launcher requests the runtime without waiting for the next foreground event; the request stays idempotent |
| AUTO-010 | Half-installed device (system missing or component missing) | No start is requested; the launcher states the missing setup step |

## Terminal session ownership cases (ADR-0033)

The terminal SSH session is owned by `RuntimeHostService`, not the terminal
view: detaching the surface unsubscribes it but never closes the shell, and a
dropped/failed shell re-attaches only through the explicit Reconnect action
(banner or notification). JVM-locked by `TerminalSessionControllerTest`,
`TerminalSessionStatusTest`, and `TerminalNotificationPolicyTest`; the
lifecycle claims below need the device pass.

| ID | Case | Expected result |
| --- | --- | --- |
| TSS-001 | Activity destroyed with a live terminal (e.g. "Don't keep activities", then reopen) | The guest shell is still attached; the new surface streams the same session; no disconnect |
| TSS-002 | Shell drops while Linux stays `RUNNING` (`exit` in the shell) | Terminal state `DROPPED`; banner offers Reconnect; notification shows the terminal line + Reconnect action |
| TSS-003 | Reconnect from the notification action | A fresh shell opens on the same runtime session; notification returns to "Terminal: connected" |
| TSS-004 | Reconnect tapped after process death (stale notification) | Service foreground-promotes, finds no runtime, releases foreground, and stops itself — no useless service left behind |
| TSS-005 | Runtime `Stop` from the notification | Terminal session closes with the runtime; terminal state `NOT_STARTED`; no orphan SSH client |
| TSS-006 | App backgrounded long then reopened, process alive | Session survives (30 s heartbeat, loopback exempt from Doze firewall); if the process was killed instead, reconcile reports honestly and a fresh session attaches |

Device pass (Samsung SM-G970F, Android 12/API 31, arm64,
2026-09-17, debug build):

| Case | Result |
| --- | --- |
| TSS-001 | PASS — `always_finish_activities=1` + HOME destroyed the Activity (EGL surface and input channel gone, same pid); zero SSH teardown in logcat; reopen produced **zero** new KEX/auth/channel-open; the live `root@localhost:~#` prompt answered typed input |
| TSS-002 | PASS — `exit` in the shell → `SSH_MSG_CHANNEL_EOF`/`CLOSE`; notification gained `[1] "Reconnect"` with text `Terminal disconnected — tap Reconnect`; in-app banner showed `The terminal disconnected. Linux is still running.` |
| TSS-003 | PASS — `ACTION_TERMINAL_RECONNECT` reached `onStartCommand`; a fresh `pty-req`/`CHANNEL_OPEN_CONFIRMATION` opened a new shell on the same runtime; notification returned to `Terminal: connected` with only `Stop` |
| TSS-004 | PARTIAL — after process death the service cannot be started from background at all (`app is in background uid null`), so the PendingIntent path is unreachable post-death on this device (the notification dies with the process); the intent delivery itself and the running-session reconnect were verified live, and the `lastStatus == null → stopForeground + stopSelf` guard remains defensive |
| TSS-005 | PASS — `ACTION_STOP` → `STOPPING` → `STOPPED`; SSH client session closed with the runtime; notification removed; process exited |
| TSS-006 | PASS — 60 s background: zero runtime state transitions, `keepalive@sshd.apache.org` sent exactly every 30 s, session stayed connected; reopen republished the same `RUNNING` with zero new handshake. In an earlier 45 s window the guest workload was killed non-deterministically while the service process survived — pre-existing OEM behavior, and relaunch honestly started a fresh session rather than faking continuity |

## Guest log cases (ADR-0034)

The session console is persisted to `/var/log/lw/boot.log` in the rootfs;
per-service `systemctl` journals stay under `/var/log/journal/` and
`/root/.config/log/journal/`; the Logs launcher surface lists them through
`GuestLogCatalog` and follows one file live through `GuestLogTail` in the
terminal WebView. JVM-locked by `SessionLogWriterTest`, `GuestLogTrimTest`,
`GuestLogCatalogTest`, `GuestLogTailTest`, and the monitor sink cases; the
device cases below are open.

| ID | Case | Expected result |
| --- | --- | --- |
| LOG-001 | Runtime running → open Logs | Catalog shows `boot.log` under "This boot" plus one item per service journal; no files outside the active rootfs are listed |
| LOG-002 | Pick `boot.log` | Viewer shows timestamped setup/supervisor/sshd lines; new output appears live without leaving the surface |
| LOG-003 | Restart the session, open "Previous boot" | `boot.log.1` holds the prior session's console; the fresh `boot.log` starts with the new session |
| LOG-004 | Pick a service journal, generate output (`systemctl status`/service activity) | New journal lines stream into the viewer within the poll interval |
| LOG-005 | Grow a journal file past the bound during a session | Sweeper trims it in place to the newest bounded tail; the guest service keeps writing to the same file; no growth past ~2× bound |
| LOG-006 | A symlink planted under a scanned log directory | The entry never appears in the catalog; nothing outside the rootfs is readable |
| LOG-007 | Switch between log items | Previous file's content is fully cleared (`RESET`); tail from the old file never bleeds into the new view |
| LOG-008 | Leave Logs / stop the runtime | Tail thread stops; no further writes reach the viewer; log files remain on disk for next session |

Device pass (Samsung SM-G970F, Android 12/API 31, arm64,
2026-09-17, debug build):

| Case | Result |
| --- | --- |
| LOG-001 | PASS — Logs tile sits after System in the launcher; catalog shows Boot (`This boot`, `Previous boot`) then System items (compose supervisor + `compose` tag, both service journals, user journal) |
| LOG-002 | PASS — `boot.log` renders in the xterm surface with timestamped sshd `-e`/`systemctl`/service lines; scrolled to the tail |
| LOG-003 | PASS — every journal `.log` rotated to `.log.1` at session start; `boot.log.1` retained the prior session's file |
| LOG-004 | PASS — a line appended to the open journal file appeared in the live viewer within ~1.5 s (poll interval 300 ms + render) |
| LOG-005 | PASS — journal grown to 2.5 MB was tail-trimmed in place by the sweeper to the newest bounded tail; **same inode** before and after, so the open `O_APPEND` guest writer keeps working |
| LOG-006 | PASS — a symlink planted in `/var/log/journal/` pointing at host `shared_prefs` never appeared in the catalog |
| LOG-007 | PASS — switching from `boot.log` to a service journal cleared the prior content fully (terminal `RESET`) |
| LOG-008 | PASS — `guest-log-tail` thread visible under `/proc/<pid>/task` while viewing; gone immediately after `← All logs` |

Note: `boot.log` initially appeared empty on the first installed session
because `SessionLogWriter` buffered without flushing until close; fixed to
flush per append before this pass. The empty rotated `boot.log.1` above is
the honest artifact of that pre-fix session.

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

- Samsung S10e SM-G970F (Android 12/API 31, arm64, 4 KB
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
(API 35, AVD `LinuxWrapperUiApi35`): the launcher in the
not-installed, installed, and stopped/failed states; the readiness pill; the
setup steps; the dashed `Add app` tile; the add form (empty, name-error,
reserved-port error); search and its count; saving a web app and seeing its tile;
the picked image rendering in the form and the tile; the edit form; the remove
confirmation and the resulting empty store; the web-app surface's unreachable
state and its options menu; the terminal's waiting and failure prompts with the
disabled accessory row; the system screen; phone landscape; a tablet-class
configuration (`wm size 1600x2560`, `wm density 320` → `sw800dp`); and both
themes. Screenshots are kept outside the repository, in a local temp
directory that is never committed.

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

### Favicon fallback run (2026-09-14, x86_64 UI emulator, API 35)

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

Screenshots are kept outside the repository, in a local temp directory that
is never committed (`13-evidence-montage.png` for WA-019,
`21-priority-montage.png` for the user-icon/favicon/monogram order,
`16-retry-step4-launcher-favicon.png` for WA-020). The fixture app was removed and the pushed image deleted afterwards, so
the emulator's web-app store is empty again.

#### Declared-icon pass (2026-09-21, S7 Edge, API 29, arm64, real guest)

The NusaShell tile on the S7 Edge ran the real case the emulator pass could not:
a web app whose document declares its icon and whose conventional path is absent.

| Case | Method | Observed result |
| --- | --- | --- |
| WA-017 (declared PNG) | `GET /` on port 10994 returns `200`, 114 KiB, with `<link rel="icon" href="./nusashell-mark.png">`; `GET /nusashell-mark.png` returns `200`, 340 281 bytes (512x512); `GET /favicon.ico` returns `404` | Tile shows the declared icon (screenshots kept outside the repository); before the change the same tile kept its monogram |
| WA-020 (retry) | The launcher's first attempt raced the guest's own start-up and answered with the monogram; the tile was then opened once and Back returned to the launcher | The icon appears without an app restart, on the retry the app surface triggers |

The declared PNG is 340 KiB — over the original 64 KiB cap — which is why the
byte budget moved with the same change. The fetch chain was also replayed on the
workstation against the same forwarded port: the parser resolves exactly
`http://127.0.0.1:10994/nusashell-mark.png` from the real document.

**Not covered by this pass.** The reachability retry is triggered by the app's own
surface, which needs an unlocked tile; the emulator's app-private `READY`
snapshot (see the method note above) provided that, so the path is covered on the
emulator but not on a device that really runs the runtime. The stale-result guard
(app edited or deleted while a fetch is in flight) and the platform decode of a
malformed image are covered by inspection and by the emulator cases above, not by
a dedicated run. The arm64 gap this pass left was closed on the S7 Edge on
2026-09-21 (declared-icon pass above).

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

## Boot start, battery recommendation, and update check (2026-09-20)

Implemented with unit evidence (policy truth table, opt-in prefs, receiver
trigger wiring, amended manifest guard, comparator/checker/prefs/notifier)
and **device-verified on the Samsung S10e (SM-G970F, OneUI, Android 12/API 31,
arm64, id-ID locale, 2026-09-20)**. Evidence: `/tmp/qa-boot/` (logcat
captures, uiautomator dumps, screenshots).

| ID | Case | Observed result |
| --- | --- | --- |
| BOOT-001 | Opt-in OFF (default) + reboot, unlock | `BootStartReceiver: BOOT_COMPLETED -> SKIP_OPT_OUT` after user unlock; zero disk gate probing (opt-in checked first); no runtime procs |
| BOOT-002 | Opt-in ON + reboot, unlock | `BOOT_COMPLETED -> START` ~1 min after unlock; tracer + `libproot.so` + `sshd_config -p 22022` spawned; `0100007F:5606` LISTEN; runtime notification posted |
| BOOT-003 | `adb install -r` while the session is live (opt-in ON) | `MY_PACKAGE_REPLACED -> START`; old tracers/daemon gone, new pids; endpoint republished. Fired on every package replace in the run (upgrade 0.2.1→0.3.0, QA reinstalls, downgrade-name build) — opt-in OFF answered `SKIP_OPT_OUT` instead |
| BOOT-004 | Service-bridge overlay deleted + reboot (opt-in ON) | `BOOT_COMPLETED -> SKIP_BRIDGE_NOT_SETTLED`; no runtime start, no install attempt; launching the app reinstalled the bridge through the setup pipeline and the session started |
| BOOT-005 | Force-stop (`stopped=true` confirmed via `dumpsys package`) + reboot | **OEM deviation**: OneUI delivered `BOOT_COMPLETED` anyway (`-> START`, session up) — the stock stopped-state suppression does not apply on this build. The toggle, not force-stop, is the reliable off here |
| BAT-001 | Battery card + exemption cycle | Card rendered "Not exempt" honestly (probe re-reads); action opened the system dialog (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, localized); granting flipped the card to "Exempt" and `dumpsys deviceidle whitelist` listed the app |
| BAT-002 | The build that hides the direct dialog | Not reachable on this device (the direct dialog works); the fallback chain is unit-pinned and start-and-fall-back identical to the workspace card |
| UPD-001 | Debug build with a lower `versionName` (0.2.9, versionCode 5) against the real channel | Fresh check returned UPDATE_AVAILABLE: launcher banner "Update available: v0.3.0" with View release / Dismiss rendered (uiautomator + screenshot); notification posted on the `updates` channel (below API 33 no grant is needed); Dismiss cleared the banner and persisted `dismissed_tag` |
| UPD-002 | Current build (`versionName` == latest tag) + second foreground within 24 h | `UP_TO_DATE`: no banner, no notification; `last_check_at` unchanged across relaunches — the throttle suppressed the second network call |
| UPD-003 | Airplane mode + fresh check | Offline `UNAVAILABLE` (typed, silent): `last_check_at` recorded, no banner, no crash |

Run findings:

1. **Found and fixed (coordinator wiring):** the battery card did not
   re-render after the exemption dialog closed, because a dialog-themed
   screen only pauses the Activity (no `onStart`). Fixed with
   `startActivityForResult`/`onActivityResult` re-probe in
   `MainActivity`/`BatteryOptimizationAccess`; the full revoke → request →
   allow cycle was then verified on device.
2. **`UpdateNotifier.cancelUpdateNotice()` added:** "Dismiss" now also
   cancels the standing notification, so both surfaces stay consistent for a
   dismissed tag.
3. **OEM delivery deviation (documented above and in `docs/limitations.md`):**
   OneUI delivered `BOOT_COMPLETED` to a `stopped=true` app.
4. **Egress reality for the check:** through the device's hotspot/mobile
   carrier path (CGNAT), `api.github.com` answered 403 for several minutes
   straight (six consecutive attempts) while another path answered 200; the
   checker mapped every one to silent `UNAVAILABLE` — the throttle recorded
   each attempt and no UI noise appeared. On shared-CGNAT networks the
   unauthenticated 60/hour budget is shared, so multi-minute 403 windows are
   normal; the design handles them.
5. Remaining: the same pass on a stock (non-OneUI) device and another OEM;
   the UPDATE_AVAILABLE notification captured on-device end-to-end once an
   egress allows a successful check (unit evidence covers the gate).

## Assisted in-app update (ADR-0039), device run 2026-09-20

Run on the Samsung S10e (SM-G970F, OneUI, Android 12/API 31): QA build with
`versionName 0.2.9` (versionCode 5) against an update stub served over the
loopback bridge (`adb reverse`), digest recorded from the served artifact.
Evidence: `/tmp/qa-boot/` (screenshots, uiautomator dumps, logcat).

| ID | Case | Observed result |
| --- | --- | --- |
| UPD-101 | Banner Install opens the popup | Popup rendered "v0.3.0 • 16.8 MB" and the unknown-sources step (grant not yet enabled), with Cancel and Release page actions |
| UPD-102 | Unknown-sources gate | "Allow installing" opened the platform settings surface; with the grant active the flow proceeded straight to the download |
| UPD-103 | Cleartext asset URL | A LAN `http://…` asset URL failed with the typed "Cleartext HTTP traffic … not permitted" — the network security config's loopback-only cleartext exception holds; the retry used `http://127.0.0.1` through `adb reverse` |
| UPD-104 | Streaming progress | Download ran with live progress; the popup's status line showed bytes/total, speed, and the time estimate (screenshots mid-download) |
| UPD-105 | Checksum gate + session hand-off | Post-download verification passed and the platform's own confirmation dialog appeared ("NusaDesk — Ingin mengupdate aplikasi ini?" with Batal/Update) |
| UPD-106 | Cancel keeps the cache | Batal produced `STATUS_FAILURE_ABORTED` ("User rejected installation"); the popup showed "Install cancelled. The downloaded file is kept for the next attempt." |
| UPD-107 | Cache-hit retry | Tapping Install again reached the system dialog in ~1.2 s (vs ~11 s for the throttled download) and the cache file's mtime was unchanged — no re-download |
| UPD-108 | Final platform apply | **OPEN (platform-side stall):** after Update, the pipeline logs `PACKAGE_INSTALL_STARTED` → integrity check passed → `Verification timed out` → `Continuing with installation`, then never reaches the `stagedDir`/`Update package` steps; the version stays unchanged. Reproduced across a reboot and with WARP off, app backgrounded, and package-verifier settings changed. A `adb install -r` (shell session) of the same APK completes the identical replace, so the stall is in the device's app-staged session pipeline (Samsung verifier path) after a correct hand-off, not in the app flow |

Run findings:

1. **Confirmation-extra key corrected on device.** The broadcast carries the
   confirmation intent under `android.intent.extra.INTENT` (not the
   `android.content.pm.extra.*` family the bridge originally read); with the
   corrected key the system dialog opened. The bridge and its test pin the
   verified key.
2. **The unknown-sources Settings trip can recreate the Activity** (OneUI
   Settings is a separate task); the popup closes with it, the banner
   remains, and the next Install tap resumes the flow — acceptable for this
   slice, tracked here as observed behavior.
3. **Play Protect verification times out on this device for app-staged
   sessions** even with WARP disabled and the verifier setting cleared; the
   platform's "continue anyway" path is where UPD-108 stalls.
4. **Release-signing requirement (repo-level):** the release workflow built
   the debug APK on an ephemeral CI runner, so the signing certificate
   rotated between releases; an update install (assisted or manual) requires
   every release to be signed with a stable key. Wired 2026-09-20 (ADR-0040):
   the workflow now builds the signed **release** APK and fails closed
   without the pinned keystore or when the certificate fingerprint drifts;
   the maintainer-side keystore secrets must be set before the next release,
   and pre-key builds need a one-time reinstall.

## Workspace folder picking (ADR-0047), device run 2026-09-20

Run on both devices: the S10e (SM-G970F, API 31, all-files grant in place) and
the S7 Edge (SM-G935F, API 29, release build). Evidence: `/tmp/qa-workspace/`
(uiautomator dumps, screenshots).

| ID | Case | Observed result |
| --- | --- | --- |
| WS-001 | Card on API 31 | Renders the stored folder, the detail line ("Appears at ~/nusadesk. Applies the next time Linux starts."), and `Change folder` |
| WS-002 | The action opens the in-app browser | Title "Choose a workspace folder", the path header `/storage/emulated/0`, the folder list, and `CANCEL` / `USE THIS FOLDER` |
| WS-003 | Picking a folder on API 31 | The browser starts at the stored folder; `USE THIS FOLDER` stores it (`treeDocumentId=picked-path`, `hostPath=/storage/emulated/0/Documents/nusadesk`) and the card renders the choice |
| WS-004 | The choice reaches the guest | After a session restart the tracer's argv carries `-b /storage/emulated/0/Documents/nusadesk:/root/nusadesk` |
| WS-005 | Card on API 29 (S7 Edge) | `App folder` names `/storage/emulated/0/Android/media/<pkg>/nusadesk` and offers `Choose folder` |
| WS-006 | Picking on API 29 | The browser opens over shared storage after one permission prompt; `USE THIS FOLDER` on the app media root stores it and the card switches to `nusadesk`; the choice survived an app reinstall and relaunch (the card still showed it) |
| WS-007 | Library-based picker (evaluated, rejected) | With `io.github.tutorialsandroid:filepicker:10.1.3` the dialog opened on API 31 once `DialogProperties.allow_manage_external_storage` accepted this app's all-files grant; without that flag the button silently requested `READ_EXTERNAL_STORAGE` and did nothing. Its directory selection is a marked checkbox rather than the folder in view, so the library was dropped for the in-repo browser |
| WS-008 | What Android 10 really allows (S7 Edge, measured) | Raw-path probe from this app (targetSdk 37): with no permissions `/sdcard` is `list=null canRead=false`; with `READ`/`WRITE_EXTERNAL_STORAGE` granted it is still `list=null`; adding `android:requestLegacyExternalStorage="true"` makes it `list=171 canRead=true canWrite=true`, `/sdcard/WhatsApp` `list=7 canRead=true`, and a file can be created in `/sdcard`. WhatsApp (targetSdk 36, both grants held) keeps writing `/sdcard/WhatsApp` on the same device — the legacy model is the platform's own door on Android 10 |

Notes:

- Two defects were found and fixed by this run: a stored `picked-path`
  workspace read back as "no workspace" (the store's `restore` only understood
  SAF tree document ids), and the Android 10 card ignored a stored choice
  entirely, so a picked folder looked unsaved.
- `run-as` cannot read a release build's preferences, so the S7's stored state
  was verified through the card itself, which reads the same store.

## Guest backup & restore round trip (ADR-0044), device run 2026-09-20

Run on the Samsung S10e (SM-G970F, OneUI, Android 12/API 31, arm64) against
the real GitHub-free local flow: the SAF picker (DocumentsUI) for both legs,
the guest live at 2.5 GB / 53 k entries. Evidence: `/tmp/qa-saf/`
(uiautomator dumps, screenshots, archive listings).

| ID | Case | Observed result |
| --- | --- | --- |
| BAK-001 | Export "Everything" through the picker to Download | 952,053,731 bytes / 53,226 entries; `manifest.json` is the first entry; `rootfs/`, `addons/<id>/`, `state/` trees present; no `proc`, `sys`, `dev`, `run`, `tmp`, `previous`, or staging entries; the mount points `rootfs/root/nusadesk/`, `rootfs/opt/lw-ssh/`, `rootfs/opt/lw-services/` are kept as single entries with no contents |
| BAK-002 | Reproduction of the lost-selection defect (before the fix) | The picker round trip recreated the Activity; the page answered **"Cancelled — nothing was changed."** after Save and left a 0-byte document in Download — the export never started |
| BAK-003 | Export after the fix, session live | The export started on the picker result, progress rendered on the page, and the record persisted: `Last backup: Everything · 20/09/26 22.20 · nusadesk-backup-full-20260920-2215.tar.gz` |
| BAK-004 | Restore of that archive, before the extractor fixes | Rejected twice by our own reader: `unsafe-archive · payload contains too many archive entries` (53,226 > the 20,000 cap), then `io-failure · …/staging/rootfs/root/go/pkg/mod/golang.org/x/sys@v0.47.0` (a 0500 directory whose mode was applied before its contents) |
| BAK-005 | Full restore after the fixes (session stopped) | `Last restore: succeeded` + **"Done — 53225 files · 2.46 GB"**; the `active` slot was swapped (mtime 22:42) with the old tree parked at `previous`, staging cleaned; the 597 read-only directories survived with mode 0500; the mount points exist again; the session came back from the restored tree (`127.0.0.1:22022` LISTEN) and content spot-checks pass (`etc/os-release` = Ubuntu 24.04.5, `usr/local/bin/android-cli`, `root/.bashrc`) |

Notes:

- **Stopping the session for a full restore** uses the platform surface the
  product documents: the notification's `Stop` action (there is no in-app
  stop control, ADR-0013). The picker round trip fires an Activity foreground
  event on return, which autostarts the session again; when that start had
  already reached `RUNNING`, the import gate answered `busy · Stop the Linux
  session before restoring.` — so the reliable order is: stop the session,
  then pick the file, and let the import win the race (observed both ways in
  this run). The host stopping the session for its own restore is the obvious
  follow-up; it needs an ADR-0013 amendment.
- The export of a live session is a best-effort snapshot (documented): the
  guest keeps writing while the archive streams.
- A release-signed APK cannot be installed over a debug-signed QA build, so
  the "older installed version" states in this plan use QA-only builds with a
  staged `versionName`; the repository's `VERSION`/`build.gradle` were
  reverted before any further build.

## Update-check cadence fix (ADR-0046), device run 2026-09-20

**Reported symptom (S10e, real usage):** the app was installed from the
GitHub release, a newer release was published hours later, and no banner ever
appeared — force-stop and relaunch included.

**Root cause (read from device state, not inferred):**
`shared_prefs/update_check.xml` held `last_check_at = 1789884308024`
(2026-09-20 13:05:08 WIB) and no other key. v0.4.0 was published 14:14 WIB and
v0.5.0 at 19:39 WIB, so every open after the release was throttled by
ADR-0038's 24 hour interval — the next attempt was not due until 2026-09-21
13:05. The check never ran; nothing was wrong with the banner.

| ID | Case | Observed result |
| --- | --- | --- |
| UPD-201 | Stale state, old code (reproduction) | With `last_check_at` at 13:05 and the release 6.5 h later, cold starts (force-stop + relaunch at 20:54 and 21:07) left `last_check_at` **unchanged** — no network attempt, no banner |
| UPD-202 | QA build staged to `versionName 0.4.0` (versionCode 6, fixed code) with the same stale prefs | Cold start re-checked immediately: `last_check_at` rewritten (21:09:57 WIB), `last_check_version=0.4.0`, `last_seen_tag=v0.5.0`, and the launcher banner rendered **"Update available: v0.5.0"** with Install / Dismiss (uiautomator dump + `banner-0.4.0-staged.png`) |
| UPD-203 | Upgrade to the real build (`0.5.0`, versionCode 7) while `last_check_version=0.4.0` | Immediate re-check on the first foreground (version-change rule): `last_check_version=0.5.0`, `last_check_at` rewritten, `last_seen_tag` cleared by the up-to-date result, banner absent — no false prompt after an install |
| UPD-204 | Second foreground inside the floor | `last_check_at` unchanged across a relaunch 1.5 min later — the floor still throttles, now for 30 minutes instead of a day |

Notes:

- A release-signed APK cannot be installed over a debug-signed QA build
  (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so the "older installed version"
  state was produced with a QA-only build whose `versionName` was staged to
  `0.4.0`; `VERSION`/`app/build.gradle` were reverted before any further
  build, and the build left on the device is the real `0.5.0` artifact.
- Evidence directory: `/tmp/qa-updater/`.
- UPD-002 above recorded the old contract ("the throttle suppressed the
  second call"). Under ADR-0046 that suppression lasts 30 minutes, and a
  changed installed version bypasses it entirely.
- The egress happened to allow the check during this run (the same device had
  multi-minute 403 windows earlier); a 403 window would now cost one floor of
  silence instead of a day.

## USB pass-through (ADR-0041), device run 2026-09-20

Run on the Samsung S10e (SM-G970F, OneUI, Android 12/API 31, arm64) acting as
the **USB host** with an S7 Edge attached (`04e8:6860`, ADB interface active),
plus a USB storage device (`05e3:0751`) and two hubs (`214b:7250`) on the bus.
The debug build with the USB slice was installed over the existing session
(`adb install -r`, data kept), the session cold-started (launch the app;
see the session-start note below), and the guest was driven over the planted
probe key (playbook `PB-guest-shell-planted-key`).

| ID | Case | Observed result |
| --- | --- | --- |
| USB-001 | `nusadesk-usb list` from the guest | `04e8:6860  /dev/bus/usb/001/008  SAMSUNG  SAMSUNG_Android`, `05e3:0751  /dev/bus/usb/001/009  USB Storage  USB Storage`, `count: 2`, rc 0 — enumeration without any prompt |
| USB-002 | `nusadesk-usb probe 04e8:6860` (first open) | System consent dialog: `ActivityTaskManager: START u0 {… cmp=com.android.systemui/.usb.UsbPermissionActivity (has extras)} from uid 1000` at 15:32:23.659, `Displayed … +87ms`; after Allow: `probe ok: idVendor=04e8 idProduct=6860 bcdUSB=0x0200`, rc 0 — the descriptor was read **inside the guest** through `USBDEVFS_CONTROL` after the tap (~58 s wait, under the 75 s guest timeout) |
| USB-003 | `nusadesk-usb exec 04e8:6860 -- sh -c 'ls -l /proc/self/fd/$NUSADESK_USB_FD'` | `fd=3`, `/proc/self/fd/3 -> /dev/bus/usb/001/008`, rc 0; **no second dialog** — the platform grant persisted while the device stayed attached |
| USB-004 | `nusadesk-usb probe 1234:5678` (absent device) | Typed `nusadesk-usb: usb-device-not-found`, rc 1, no dialog, no crash |
| USB-005 | Re-run after the device re-enumerated (node moved 001/008 → 001/011): `list` + `exec … -- python3` reading the descriptor **strings** through the fd | `list` tracks the new node (`04e8:6860 /dev/bus/usb/001/011`); the strings came from the device itself: `manufacturer 'SAMSUNG'`, `product 'SAMSUNG_Android'`, `fd path: /dev/bus/usb/001/011`; no second consent dialog — the platform grant survived re-enumeration |
| USB-006 | Minimal in-guest adb client over the delivered fd: claim interface 4 → `CNXN` | adbd answered `AUTH arg0=1 len=20` — the adb handshake is live over the OTG fd (after the header/payload framing fix below) |
| USB-007 | Same client sends `AUTH(RSAPUBLICKEY=3)` with an `adb keygen` keypair | S7 (screen unlocked) showed the "Allow USB debugging?" dialog; after Allow the device answered `CNXN` with `banner='device::ro.product.name=crownltexx;ro.product.model=SM-G935F;…features=cmd,stat_v2,shell_v2'` |
| USB-008 | `OPEN "shell:getprop ro.product.model"` on the authorized stream | `S7 says its model is: 'SM-G935F'`, rc 0 — a shell command executed **on the S7 from inside the Linux guest over USB**; the whole run repeated once with the same result |

Run findings:

1. **The fd crosses PRoot intact.** USB-003's symlink is a real usbfs node:
   `SCM_RIGHTS` delivery and the `ioctl` that reads the descriptor both pass
   through the ptrace shim, which was the open risk this slice had to answer.
2. **Consent is the only gate and it is per attached device.** The dialog
   appeared exactly once (USB-002); USB-003's second open (different process,
   same guest session) opened without a prompt, which matches the platform's
   grant lifetime rather than any product-side cache.
3. **Session start after an in-place install.** Launching the app cold
   (`am force-stop` → `am start`) autostarts the session and the writer
   installs the new `/usr/local/bin/nusadesk-usb` (observed 15:28, byte
   identical to the built script). An adb-injected `RUNTIME_ENSURE_RUNNING`
   while that session was live was followed by
   `RuntimeHostService: service destroyed while the runtime was live;
   stopping it` and the runtime stopped; the cold start brought it back. The
   destroy's origin (the injected start vs. the system) is not pinned down —
   recorded so a later run does not chase it.
4. **Remaining:** sustained bulk traffic and a complete guest-side adb client
   (transport with fd import, the termux-adb pattern) are not yet exercised;
   another OEM/API level and 16 KB-page devices stay open as usual.
5. **adb over OTG works, and the framing is the trap.** USB-006…USB-008 prove
   the full handshake and a shell over the delivered fd. Two protocol rules
   came out of the failed attempts and a documentation check
   (`cstyan/adbDocumentation`, AOSP `adb_auth_host.cpp`):
   - the 24-byte adb header and its payload must travel as **separate USB
     transfers**; one concatenated write desynchronizes adbd, which then goes
     silent and only recovers on a replug (retransmission does not help);
   - for a key the device has never seen, the client sends
     `AUTH(RSAPUBLICKEY=3)` — that is what raises the on-device dialog, and
     after Allow the device answers `CNXN` (no signature round needed). If a
     token follows the public key later, the signature is
     `RSA_sign(NID_sha1, token)`: PKCS#1 v1.5 over a SHA-1 DigestInfo
     (`3021300906052b0e03021a05000414`) wrapping the 20-byte token.
6. **Repeat run:** the second USB-008 run completed with the same result
   (the dialog may reappear when the key was not marked "always allow").

## Guest adb driver (ADR-0042), device run 2026-09-20

Run on the S10e (USB host) with the S7 Edge attached, debug build carrying the
driver bundle written at session start (`/usr/local/bin/nusadesk-usbd`,
`/usr/local/bin/adb`, `/opt/nusadesk/libusb-shim.c`), plus the patched libusb
at `/opt/nusadesk/lib` (`gcc` and `libusb-1.0-0-dev` installed in the guest
once; recipe in ADR-0042).

| ID | Case | Observed result |
| --- | --- | --- |
| USB-101 | `adb devices -l` with the driver active | `device 1-1 product:crownltexx model:SM_G935F device:crownlte` — the stock adb sees the attached phone; enumeration arrives through the shim's virtual hotplug |
| USB-102 | `adb -s 1-1 shell getprop ro.product.model`, three consecutive runs | `SM-G935F` each time; the transport stays `device` |
| USB-103 | Authorization | First contact: adb sent its public key and the transport sat `unauthorized`; after that key was authorized once (with "always allow"), later attaches authenticate silently through the signature path — no dialog |
| USB-104 | Enumeration with an empty descriptor cache | the device still lists and opens; the descriptor is fabricated for enumeration and refreshed from the device at open (real serial `ce0516054597102d05` read through the wrapped handle) |

Run findings:

1. **usbfs claims live on the opened file.** The shim's claim, the app's
   connection and the daemon's cached fd all referenced one file, so the next
   attacher's claim failed `EBUSY`. Fix: the daemon opens a fresh fd per
   `usb.open` (the app replaces its previous connection on every open) and
   closes its own copy once the descriptor has been handed over.
2. **A poll bug arrived every virtual device and left it in the same tick**
   (adb log: `device arrived` + `device left` 1 ms apart), leaving one
   offline transport per poll. Fix: mark a freshly created vdev as seen so
   the same poll's LEFT pass cannot sweep it.
3. **Stock libusb cannot initialize in this environment**: its hotplug
   monitor needs a `NETLINK_KOBJECT_UEVENT` socket that Android's SELinux
   denies (errno 13), which libusb 1.0.27 treats as fatal
   (`LIBUSB_ERROR_OTHER`). The shim supplies hotplug itself, so the run used
   a libusb build with that failure downgraded to a warning (recipe in
   ADR-0042).
4. **adbd wedges after an abrupt host disconnect** (a process exiting
   mid-handshake): further CNXN packets go unanswered until the cable is
   replugged. A clean authorization followed by a normal attach does not
   wedge it; the replug is the documented recovery.

## System hub (ADR-0043), device run 2026-09-20

Run on the S10e (SM-G970F, OneUI, Android 12/API 31) with the debug build,
driven entirely through uiautomator (no hardcoded coordinates); UI dumps kept
in `/tmp/qa-system/` (`00-launcher` … `05-hub-final`).

| ID | Case | Observed result |
| --- | --- | --- |
| SYS-001 | Launcher → System tile | the System screen opens on the hub with its rows: Settings, One-click install, Backup & restore, and About NusaDesk |
| SYS-002 | One-click install page | `USB / ADB driver` and `Termux command compatibility` render with the "Active in every session" state and the no-arbitrary-install explainer |
| SYS-003 | System back on a sub-page | returns to the hub from both the install page and the About page; only a second Back leaves the screen |
| SYS-004 | About page | system state, technical details (six values), the GitHub row, and the how-it-works row render |

Run findings: the four settings cards (workspace, battery, boot, app
permissions) moved verbatim into the Settings page and keep their ids, so
the existing MainActivity renderers still update them — state ownership did
not move with the layout. `SystemScreenView.navigateBack()` is the Back
contract MainActivity consults for the System destination.

## Native Android tooling in the guest (`android-cli`, ADR-0045), device run 2026-09-20

Run on the S10e (SM-G970F, API 31) with the debug build; the session binds
`/system/bin`, `/system/lib64` and `/apex`, and the writer installs
`/usr/local/bin/android-cli`.

| ID | Case | Observed result |
| --- | --- | --- |
| AND-001 | `android-cli doctor` | `sh`/`toolbox`/`toybox` present and exec-ok, `getprop` present, `device su: absent`, `tier: app source=native` |
| AND-002 | `android-cli getprop ro.product.model` / `ro.build.version.sdk` | `SM-G970F` / `31` — the device's own toolbox, natively, as the app uid |
| AND-003 | `android-cli toybox echo …` and `android-cli sh -c 'id'` | `native-toybox-ok`; `uid=0(root) …` (the PRoot fake-root mapping) — the device's mksh runs inside the session |
| AND-004 | `android-cli exec /system/bin/input keyevent 0` | the shell script now resolves its `cmd`/`app_process` because children get Android's `PATH`; the injection itself is the platform's call |
| AND-005 | `android-cli exec /system/bin/screencap -p …` | exits 0 but writes a 0-byte file (display state / app-uid capture limit) — recorded as open |

Run findings:

1. **Android 11+ needs the APEX bind.** `/system/bin/linker64` is only a
   symlink into `/apex/com.android.runtime`; without binding `/apex` every
   Bionic binary fails with "required file not found". The curated rootfs
   also carries physical Bionic copies from provisioning, which the
   `/system/bin` bind shadows — the apex bind is what makes the tooling exec.
2. **`/linkerconfig` is unreadable by the app domain** (SELinux), so the
   linker runs without its namespace map: the warning
   `failed to find generated linker configuration` appears on every Bionic
   exec (cosmetic), and libraries that live only in an APEX (e.g. `libicu`
   for `libharfbuzz_ng`) do not resolve by name. `android-cli` compensates
   for its children with an Android `PATH` + `LD_LIBRARY_PATH` prefix; the
   session's own paths are never touched.
3. **The app cannot list `/system/bin`** (`ls` is denied) while `stat` and
   `exec` of its entries work: the tooling is reachable, the listing is not.
4. **`su` is absent** on this (non-rooted) device and the CLI answers that as
   a typed result; the root tier stays the user's own door on a rooted
   device, one Magisk prompt per grant.

## License and distribution note

- PRoot is GPL-2.0-or-later. Packaging/distributing it requires GPLv2+
  source/notice obligations and a combined-work legal review before
  distribution. No GPL-cleanliness claim is made.
- Dropbear (permissive/MIT-style) and OpenSSH (BSD) are includable in the
  curated rootfs, but license/notice/attribution obligations apply.
- No Google Play compliance or approval is claimed. Initial distribution is
  sideload/F-Droid or another controlled channel; a Play submission needs a
  separate policy review.
