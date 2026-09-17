# Architecture baseline

## Status

This repository is a Java Android launcher plus a curated Ubuntu Base runtime
that is installed on demand and started as one long-lived, background session.

The product direction is a Linux desktop on Android (ADR-0012) with Linux as
background infrastructure (ADR-0013) and the user's own web apps as the desktop
(ADR-0014). The **launcher is the home surface**; Linux apps open as maximized
surfaces that attach to whatever the host published, and no surface — launcher,
terminal, system, or web app — renders a lifecycle control. "SSH-first"
(ADR-0007) is retained as the *transport* between the Android host and the guest,
and only between this app and the Linux it started; it is no longer the product's
identity.

Implemented and device-verified on Android 10/API 29 arm64: the curated
install proof, the packaged PRoot execution bridge, the guest-native OpenSSH
daemon on the fixed loopback endpoint, the SSH client bridge with host-key
pinning, and a live guest shell in the terminal app surface.

Implemented and UX-verified on an x86_64 emulator (API 35): the launcher, the
non-ready launcher status states, the add/edit/remove web-app form with its field validation and
document-picker icon, the tile's favicon fallback (ADR-0015), the web-app
surface's probing/unreachable/loaded states, the terminal's waiting and failure
prompts, the system screen, landscape, tablet, and light/dark themes. The
emulator is **not** runtime evidence.

The product shape is:

```mermaid
flowchart TD
    AndroidHost["Android host"] --> Payload["Verified curated runtime payload<br/>rootfs + guest SSH server"]
    Payload --> PRoot["Packaged PRoot execution bridge"]
    PRoot --> Supervisor["Android-owned lifecycle and process supervision<br/>foreground service"]
    Supervisor --> GuestSsh["Guest SSH server<br/>127.0.0.1:22022 + readiness frame"]
    GuestSsh --> Surfaces["App surfaces<br/>local terminal · Linux system · user web apps"]
```

## Baseline decisions

| Area | Decision | Why |
| --- | --- | --- |
| Language | Java | Explicit product constraint; keeps the initial Android surface boring and portable |
| Android support | `minSdk 29` | Android 10+ is the requested floor |
| Build | Gradle Wrapper + Android Gradle Plugin 9.4.0 | Pinned, reproducible build tools; no dynamic plugin versions |
| SDK | `compileSdk 37`, `targetSdk 37` | Allows testing current Android behavior and avoids a legacy target workaround |
| Architecture | Small Clean Architecture boundary | Keeps Android/process/security details out of domain logic |
| Runtime strategy | Curated downloaded payload, not arbitrary OCI/image execution in MVP | Reduces supply-chain, compatibility, and support surface |
| Product direction | Launcher home, Linux started from an Activity foreground event, terminal as one local-only app, user web apps as the desktop (ADR-0012, ADR-0013, ADR-0014) | The runtime has one owner and no surface offers a lifecycle control |
| Execution bridge | Packaged standalone PRoot binary, guest rootfs out-of-band (ADR-0004, ADR-0007) | Rootless glibc userland without a kernel module; **not a sandbox** |
| Initial UI | Native Java launcher: search, Add app, curated surfaces, user web apps (ADR-0014) | Full WebView/xterm.js terminal is an app surface, not the product shell |

The AGP 9.4 baseline requires Gradle 9.6 and JDK 17; the project pins those values in the build files. See the official [AGP 9.4 compatibility notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes) and [Java versions in Android builds](https://developer.android.com/build/jdks).

## Layers

```mermaid
flowchart LR
    Presentation["presentation"] --> Application["application"]
    Application --> Domain["domain"]
    Infrastructure["infrastructure"] --> Application
    Infrastructure --> Domain
```

### `domain/`

Pure Java rules and value objects:

- runtime lifecycle vocabulary and legal transitions;
- validated loopback endpoint;
- session state vocabulary;
- the user web-app definition, its id, and the guest port policy that reserves
  `22022` for the terminal;
- deterministic policies that can be unit-tested without Android.

This layer must not import `android.*`, access files/network/processes, read environment variables, or know about Gradle.

### `application/`

Use-case boundaries and ports:

- runtime state persistence;
- curated runtime installation and progress/error reporting;
- runtime readiness/endpoint observation;
- the web-app registry and store contracts: validation, launcher order, and
  add/update/delete persistence.

Interfaces belong here only when they represent an actual boundary. Do not create a service locator or generic `Manager` abstraction.

### `infrastructure/`

Adapters for:

- Android services and notifications;
- app-private file storage;
- HTTPS downloader, digest verifier, safe rootfs extractor, and atomic activation adapter;
- native execution bridge/PRoot adapter;
- process supervision and stdout/stderr capture;
- Android WebView configuration;
- Android Keystore and device capability probes.

Implemented: app-private state storage, the curated download/verification/safe
extraction/atomic-activation adapter, the PRoot launcher, the guest OpenSSH
daemon workload on the fixed loopback port, the foreground host service with its
status bus, the Android SSH client bridge with host-key pinning and
Keystore-backed credentials, the local-only client factory, the bounded web-app
readiness observer, the loopback WebView boundary, the user-chosen workspace
bind (ADR-0023), and the guest service bridge wiring `systemctl` plus the
bounded `udocker compose` payload into the session (ADR-0024, ADR-0025).

### `presentation/`

The shell. It translates host state into UI and never runs runtime work or makes
security decisions.

```text
presentation/
  DesktopDestination.java     HOME · TERMINAL · SYSTEM · ADD_WEB_APP
  SessionUiState.java         domain session state -> label/badge/detail, no action
  RuntimeStateDescriptor.java install-state copy (pure, unit-tested)
  GuestSshUiState.java        terminal-component state (pure)
  desktop/
    DesktopHomeView.java      the launcher: search, non-ready status visibility,
                             setup steps, grid, and neutral icon plates
    LauncherModel.java        the grid's entries and its search rule (pure)
    LauncherEntry.java        Add app | curated surface | user web app (pure)
    LauncherStatus.java       one passive non-ready status statement (pure)
    LauncherHeaderText.java   the header's count wording (pure)
    LauncherGridView.java     responsive tile grid (columns follow real width)
    LauncherTileView.java     one tile: icon plate, label, honest status
    AppSurfaceHostView.java   compact task bar + retained surface container
  terminal/
    TerminalAppView.java      local-only terminal surface (LocalSshSessionFactory)
    TerminalKeyRowView.java   mobile accessory keys
  webapp/
    WebAppFormView.java       add/edit form: name, optional image, guest port
    WebAppFormError.java      registry reason -> form field (pure)
    WebAppSurfaceView.java    probe, then the exact generated origin in a WebView
  system/
    SystemScreenView.java     install/session/technical detail + Android App Info shortcut
  widget/
    TerminalBridgeView.java   owned-origin xterm host, WebMessagePort only
    InstallerWizardView.java  first-run setup steps
    StateBadgeView.java       one state pill
```

Rules this layer enforces:

- **No lifecycle control anywhere.** Linux starts from an Activity foreground
  event and is stopped from the platform notification. `LauncherStatus` and
  `SessionUiState` expose no action at all, and a reflection test fails if either
  type grows one back.
- **One truth per surface.** The launcher folds install, component, and session
  state into one sentence, but keeps the normal ready state visually quiet and
  shows the status pill only when setup or a non-ready session needs attention;
  the system screen reports the same states as labelled rows and exposes the
  platform-owned App Info page for permission management.
- **No fake state.** The launcher lists only surfaces the build can open; there
  is no placeholder desktop or file browser. A web app tile is a registered
  entry, and the surface says "not running" until the endpoint answers.
- **No arbitrary target.** The terminal's client config comes only from
  `LocalSshSessionFactory`, which takes the initial PTY size and nothing else.
- **Retained surfaces.** A destination stays attached and is switched by
  visibility, which is what keeps a live terminal's WebView and scrollback — and
  a web app's page — alive across a trip back to the launcher. Surfaces are
  created lazily on first open. Rotation does not recreate them either:
  `MainActivity` declares `configChanges="orientation|screenSize|keyboardHidden"`,
  so the same surface refits to the new dimensions instead of resuming from a
  destroyed Activity.

## Runtime boundary

The runtime host owns one explicit app session at a time:

```mermaid
flowchart TD
    Activity["RuntimeHostService.ensureRunning(context)<br/>idempotent foreground event"] --> Controller["controller.ensureRunning(appId, version, sessionId)"]
    Controller --> Daemon["PRoot + guest OpenSSH daemon<br/>fixed loopback endpoint"]
    Daemon --> Ready["awaitReady()<br/>health check, not merely PID"]
    Ready --> Publish["publish HostRuntimeStatus<br/>on status bus"]
    Publish --> Stop["stop()<br/>notification action or service teardown"]
```

The guest app must own its listener and report a concrete endpoint after binding.
The host must not implement a `findFreePort()` check that releases a port before
the child binds; that pattern has a race with another process. For the guest SSH
endpoint the product deliberately uses one documented fixed port instead of
ephemeral discovery (ADR-0013), and a bind conflict becomes a typed failure.

Readiness must include:

- app id and version;
- concrete loopback host and port;
- health status;
- protocol version or identity;
- process/session identity to avoid attaching to a stale listener.

## SSH runtime boundary (implemented)

The SSH transport slice (ADR-0007) composes four layers. All four are
implemented and device-verified on Android 10/API 29 arm64.

```mermaid
flowchart TD
    Bridge["Execution bridge<br/>packaged standalone PRoot in jniLibs/<abi>"] --> Guest["Guest SSH server<br/>curated OpenSSH add-on on 127.0.0.1:22022"]
    Guest --> Terminal["Terminal surface<br/>local xterm.js bundle in owned WebView origin"]
    Terminal --> Supervision["Supervision<br/>Android foreground service with user-visible Stop"]
```

### Guest SSH server is a required build dependency

Ubuntu Base 24.04.5 ARM64 is a minimal rootfs and does **not** contain an SSH
server. The terminal is non-functional unless the curated runtime provides one,
which it does as a digest-pinned OpenSSH add-on payload (ADR-0010):

- the server arrives as a curated artifact, not from the base rootfs;
- it binds `127.0.0.1` only;
- host keys are generated at first guest start in app-private storage, never in a
  public catalog artifact and never logged;
- authentication uses an app-managed credential in the Android Keystore; the
  password never appears in URLs, process arguments, logs, or WebView JavaScript.

PRoot shares the host network namespace, so a guest loopback bind is reachable
from the Android host process. Public/LAN binding is a separate, explicitly
reviewed capability, not the default.

### Readiness, loopback, and WebView security

- **Readiness** = the advertised health check succeeds, not merely that a PID
  exists. The guest reports a concrete `127.0.0.1:<port>` plus a session identity
  after binding.
- **Loopback is reachability, not authentication.** Sensitive operations must not
  rely on the port being local; the SSH credential and the pinned host key are
  the authentication for the terminal, and a user web app is the user's own
  service inside the guest.
- **WebView** loads only an origin produced by the host. The terminal loads its
  own packaged origin; a user web app loads exactly
  `http://127.0.0.1:<guest-port>/` through `WebAppWebViewBoundary`. External
  HTTPS links leave the WebView for the system browser. No
  `addJavascriptInterface` bridge exists for either surface; the terminal's I/O
  crosses a `WebMessagePort` only.

### Foreground service considerations

- The guest runtime runs under a user-visible foreground service with an ongoing
  notification and a user-visible Stop action. Android owns supervision; no Linux
  `systemd`/`service install`.
- `dataSync` is **not** used as an indefinite server type (Android 15 caps
  `dataSync`/`mediaProcessing` at six hours per 24h). The declared type is
  `specialUse` with a documented runtime-host subtype; the subtype rationale and
  any distribution-channel policy review still need to be completed.
- FGS type declaration is enforced at runtime on API 34+; the manifest declares
  it even though `minSdk` is 29. A foreground service is not a 24/7 survival
  guarantee; the UI exposes `STOPPED`, `FAILED`, and `RECOVERING` states.

## User web-app boundary (implemented)

A user web app is a launcher entry, not a runtime the host owns.

```mermaid
flowchart TD
    Form["Add app form<br/>name + optional image token + guest port"] --> Registry["WebAppRegistry<br/>validate, mint id, persist"]
    Registry --> Tile["Launcher tile<br/>one entry, no render-time probe"]
    Tile --> Favicon["Favicon fallback<br/>bounded background request when no user image"]
    Tile --> Open["Open app"]
    Open --> Observe["WebAppReadinessObserver.observe(port)<br/>background probe"]
    Observe --> Reachable["Reachable"]
    Reachable --> WebView["WebAppWebViewBoundary<br/>load exact loopback origin"]
    Observe --> Unreachable["Unreachable<br/>explicit state + retry"]
```

- The endpoint is generated from the validated port and is never user input, so
  a registered app cannot become an arbitrary-navigation surface. The favicon is
  derived from that same origin plus one fixed path, never from a stored field.
- Port `22022` is reserved for the terminal; two apps cannot share a port.
- The stored icon is an opaque `content://` token whose read permission the form
  persisted; it always wins over a favicon. A token that cannot be resolved falls
  back to the favicon and then to a monogram.
- A favicon response is untrusted: `200` only, no redirects, a 64 KiB byte cap, a
  1024 px source cap, a decode downsampled to 256 px, and silence on any failure.
- A different loopback port is a different origin and is blocked, not handed to
  the system browser, so one registered app cannot reach another app's server.
- Reachability is not health: the surface never claims the app is working.

## Storage shape

Program payloads and mutable state are kept separate:

```text
files/
  linux-wrapper/
    runtimes/<runtime-id>/active/       // activated rootfs
    addons/<addon-id>/active/           // activated terminal component overlay
    downloads/                          // staging for verified payloads
shared_prefs/
  runtime_state.xml                     // install state per app id
  web_apps.xml                          // user-registered launcher entries
  session_state.xml                     // last published session snapshot
```

Only a validated, complete version may become `active`. Keep the previous
known-good version until the new process has passed readiness. Never treat cache
storage as durable application state.

## Lifecycle boundary

The Android host, not Linux `systemd`, owns supervision. The runtime is started
from a user-visible foreground service with an ongoing notification and a
user-visible Stop action; the app starts it only from an Activity foreground
event.

The service must treat process lifetime as lossy:

- Android may kill the host or child under memory pressure;
- OEM battery policies may suspend work;
- screen-off and Doze must be tested;
- a PID is not equivalent to readiness;
- restart attempts must be bounded and observable.

## Android capability bridge boundary (implemented control and live-media slices)

The guest cannot invoke Android framework APIs directly, so the host exposes
only reviewed capability adapters (ADR-0030, ADR-0031, ADR-0032). The adapters
are owned by the same `GuestSshdWorkload` session:

```mermaid
flowchart LR
    Battery["Android BatteryManager"] --> Adapter["AndroidCapabilityBridge\nallowlisted JSONL RPC"]
    Sensors["Android sensors/location"] --> Adapter
    Calendar["Calendar provider\nInstances read + event writes"] --> Adapter
    MediaControl["media.start/status/stop"] --> MediaService["camera|microphone FGS"]
    MediaService --> Capture["Camera2 + AudioRecord\nMediaCodec H.264 + AAC"]
    Capture --> RTSP["RTSP over TCP\n127.0.0.1:ephemeral"]
    Adapter --> Loopback["127.0.0.1:ephemeral\nper-session token"]
    RTSP --> Guest["guest tools\nffplay/ffmpeg/OpenCV"]
    Loopback --> Guest
    Adapter --> Sysfs["product-owned snapshot\n/sys/class/power_supply/battery"]
    Sysfs --> Guest
```

- The TCP listener binds IPv4 loopback explicitly; it never binds LAN or
  wildcard addresses. Loopback is not auth, so every control request carries a
  fresh session token passed through the guest environment.
- The control protocol is versioned, line-delimited, flat JSON with bounded
  frames, socket timeouts, and a small concurrency cap. The allowlisted methods
  include `bridge.info`, battery/sensor/location reads, read-only
  contacts/call-log/SMS/telephony reads, the foreground-only location stream,
  `media.start`, `media.status`, `media.stop`, and the calendar slice
  (`calendar.list` read plus `calendar.insert`/`update`/`delete`). There is no
  arbitrary shell, reflection, URI, or Binder proxy. A strict-bound
  `/run/nusadesk/android-bridge.env` file gives authorized guest tools the
  current endpoint and token.
- The envelope is param-free except for the declaring calendar writes, which
  accept one bounded flat `params` object (ADR-0032). A method that does not
  declare parameters answers `unsupported-parameter` when one is sent, an
  unknown key inside a declaring method is `calendar-invalid-argument`, and any
  other top-level field fails closed at decode time.
- `battery.status` uses Android's permission-free battery snapshot. The
  best-effort sysfs projection gives existing Linux programs a familiar read
  path, but it is not kernel sysfs, can be stale/unavailable, and guest writes
  may be overwritten by the next refresh.
- `sensor.accelerometer` and `sensor.gyroscope` are bounded one-shot reads.
  They return finite x/y/z values, bounded accuracy text, and platform event
  timestamps. Continuous sensor streaming and rate/backpressure contracts are
  intentionally not promised yet.
- `location.get` is a bounded foreground-only one-shot adapter. It checks the
  current grant per request and returns explicit permission-required/denied,
  unavailable, timeout, or finite fix results. It never prompts, requests
  background location, or claims continuous GPS behavior.
- Live media is deliberately separate from the short RPC response: Android
  captures the back camera and microphone, encodes H.264/AAC with MediaCodec,
  and serves a unified RTSP-over-TCP stream on an explicit `127.0.0.1`
  ephemeral port. The server accepts at most two clients, uses bounded per-
  client queues, and disconnects a slow consumer rather than blocking capture.
  It stores no JPEG, M4A, MP4, or other capture artifact; a guest consumer
  saves manually when needed.
- `media.start` never opens a permission prompt. It succeeds only after the
  camera/microphone foreground service is eligible, the RTSP listener is bound,
  and both encoder metadata sets are ready. Background starts, missing/denied
  grants, busy cameras, unavailable hardware, and encoder failures are typed
  errors. `media.stop` and bridge/session teardown close the service, clients,
  encoders, camera, microphone, and listener.
- The bridge closes with the guest session and is not a second long-lived
  Android service. The media foreground service is short-lived and exists only
  while an explicit live stream is active; it has its own user-visible Stop
  notification action.
- Contacts/call-log/SMS/telephony methods are read-only, row/byte bounded, and
  redact sensitive fields; SMS send and phone call remain unsupported.
- The calendar slice reads the provider's instance table inside a fixed
  seven-day window (at most 50 rows) with a minimal projection, and its three
  write methods validate every field before a provider call, target only a
  writable calendar, and write no attendee row and no invitation. There is no
  calendar creation/listing, no guest-chosen window, and no reminder or
  conference-data support.
- The location stream is foreground-only, pull-based, and queue-bounded. It
  does not start an FGS, request background permission, or wake the app.
- Bluetooth, usage stats, overlay, notification-listener, accessibility, raw
  `/dev` hardware, Binder, GPU/NPU, kernel, and SELinux operations remain
  separately scoped limitations.

## Explicit non-goals

- no curated desktop web-app profile: the user's registered web apps are the
  desktop, and the product ships no built-in desktop or file browser;
- no external SSH client: no remote host, port, profile, or credential UI;
- no arbitrary URL/image/package execution by the host — the one bounded
  exception is the ADR-0025 `udocker compose` guest adapter, which delegates
  registry image pulls to udocker's own download path under its strict
  subset; arbitrary rootfs URLs and shell-command APIs remain out of scope;
- no local-network/LAN sharing;
- no target-specific app integration;
- no second concurrent Linux session;
- no Play Store compliance claim.
