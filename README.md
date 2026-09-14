# NusaDesk

A Linux desktop that lives on an Android device. The launcher is the home
surface; the terminal is one Linux app among others, and the desktop's web apps
are the ones the user registers. Linux itself is background infrastructure: it
starts when the app comes to the foreground and keeps running behind a
user-visible foreground service.

> **Status:** desktop shell + curated install + guest-native SSH runtime path +
> user-local web-app launcher. Ubuntu Base rootfs
> download/verification/extraction/activation is implemented and device-verified.
> The runtime workload starts the guest's own OpenSSH daemon under PRoot on the
> fixed loopback endpoint `127.0.0.1:22022`, verifies an SSH banner as readiness,
> and connects the Android SSH client with host-key pinning and a Keystore-backed
> credential (ADR-0009, ADR-0013). The daemon arrives as a curated,
> digest-pinned OpenSSH add-on payload (ADR-0010) that installs into a private
> overlay and is bound into the guest.
>
> The presentation layer is launcher-first (ADR-0012, ADR-0014): the launcher is
> the home destination, Linux starts from an Activity foreground event
> (`RuntimeHostService.ensureRunning`) and is stopped only from the platform's own
> notification action, the terminal is local-only through
> `LocalSshSessionFactory`, and a user can add, edit, and remove web apps by name,
> optional image, and guest port. UX for the launcher, the add/edit form, search,
> the unreachable/loaded web-app states, the terminal's waiting state, the system
> screen, landscape, tablet, and large-font states is verified on an x86_64
> emulator (API 35, 720×1280@320).
>
> **Evidence boundary.** The emulator runs PRoot through ARM translation, so it
> is **not** evidence for the runtime contract — guest SSH, live shells, and
> background/foreground lifecycle remain exactly as strong as the last arm64
> device run and no stronger. Other API levels, 16 KB-page devices, and OEMs are
> still open. No Google Play compliance claim is made.

## Baseline

- Java source code only.
- Android `minSdk 29` (Android 10+).
- `compileSdk 37`, `targetSdk 37`.
- Android Gradle Plugin 9.4.0.
- Gradle 9.6.0 via the Gradle Wrapper.
- JDK 17 for the build; Java 11 source/target compatibility.
- No Kotlin, Compose, or JNI source; the only native artifact is the packaged standalone PRoot execution bridge (`libproot.so` + `libproot-loader.so` in `jniLibs/arm64-v8a/`), reproducibly built and hash-pinned by `scripts/build-proot-arm64.sh` (ADR-0004, ADR-0008). Apache Commons Compress is used only for safe tar.gz inspection/extraction.

## Quick start

Using the Makefile:

```bash
make test       # Run the unit-test suite
make build      # Run lint and assemble the debug APK
make check      # Run tests, lint, and assemble
make push       # Build first, then install to every online adb device
```

`make push` uses `adb devices`, filters to targets in the `device` state, and
installs with `adb install -r` so each device's app data and runtime payload are
preserved. Override the tools or APK path when needed, for example:

```bash
make push ADB=/path/to/adb
make build GRADLEW=./gradlew
```

The equivalent Gradle commands are:

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

The build requires an Android SDK with platform 37 and Build Tools 36.0.0. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` if the SDK is not installed in the IDE-managed default location.

Install the debug APK on a connected device/emulator only after the scaffold builds:

```bash
./gradlew installDebug
```

## Architecture

```text
presentation  -> application  -> domain
      \             ^
       \            |
        infrastructure implements application ports
```

- `domain/` contains Android-free state/value objects, including the web-app
  definition, its id, and the guest port policy that reserves `22022`.
- `application/` contains ports/contracts for runtime, state, port handling, and
  the web-app registry that validates and persists launcher entries.
- `infrastructure/` contains the app-private state/download/extraction adapter,
  the PRoot execution bridge, the runtime host service, the SSH client with
  host-key pinning, and the loopback WebView boundary.
- `presentation/` contains the shell. `desktop/` holds the launcher
  (`DesktopHomeView`), its model (`LauncherModel`/`LauncherEntry`), the readiness
  pill (`LauncherStatus`), the responsive grid (`LauncherGridView`), the app tiles,
  the app surface host with its compact task bar, and the system screen;
  `terminal/` holds the local-only terminal surface and its accessory key row;
  `webapp/` holds the add/edit form (`WebAppFormView`) and the web-app surface
  (`WebAppSurfaceView`). `MainActivity` routes between them, owns the install
  coordinator, the web-app registry, and the readiness probe executor, and wires
  callbacks; it does not run runtime work or make security decisions locally.

Linux has no lifecycle control anywhere in the UI. `MainActivity.onStart()` asks
the idempotent `RuntimeHostService.ensureRunning` to bring Linux up once the
curated system and its terminal component are installed, and the launcher states
readiness with one passive pill (`LauncherStatus`). The foreground-service
notification keeps the user-visible `Stop` action Android requires.

The terminal cannot dial anything but the Linux this app started:
`LocalSshSessionFactory` fixes the host, port, credential, and trust policy, and
takes only the initial PTY size.

A user web app is a launcher entry with a generated endpoint: the form accepts a
name, an optional image, and a guest port, and the tile opens exactly
`http://127.0.0.1:<port>/` — after a bounded reachability observation, through
`WebAppWebViewBoundary` (exact-origin policy, external links to the system
browser, no JavaScript interface).

See [`AGENTS.md`](AGENTS.md), [`docs/architecture.md`](docs/architecture.md),
[`docs/roadmap.md`](docs/roadmap.md), and [`docs/decisions/`](docs/decisions/)
(notably [ADR-0014](docs/decisions/0014-user-local-web-app-launcher.md) for the
launcher and web-app slice, [ADR-0013](docs/decisions/0013-fixed-local-ssh-background.md)
for the fixed local endpoint and autostart, and
[ADR-0009](docs/decisions/0009-guest-native-ssh-daemon.md) for the guest daemon).

## Repository skills

The local agent playbooks live under [`.agents/skills/`](.agents/skills/). They are intentionally narrow and must not be treated as permission to implement the complete runtime:

- `android-clean-architecture`
- `android-webview-hosting`
- `runtime-process-and-port-handling`
- `runtime-download-and-integrity`
- `android-compatibility-testing`

## Scope of this phase

Included:

- Launcher home: search, the dashed `Add app` action, the curated Linux surfaces,
  and the user's registered web apps in one dense grid (ADR-0014).
- Add/edit/remove web app: required name, optional image through the system
  document picker with a persisted read permission, required guest port with
  field-level validation, and app-private persistence.
- Web-app surface: asynchronous reachability observation before the WebView
  loads the exact generated loopback origin, with explicit probing, unreachable,
  failed, and loaded states.
- Terminal as a maximized app surface that is local-only, with an honest waiting
  or failure prompt and a mobile accessory key row.
- Linux as background infrastructure: autostart from an Activity foreground
  event, a passive launcher readiness pill, and the platform notification as the
  only stop path.
- Linux system screen: install state, session and terminal-component status,
  registered web-app count, loopback endpoint, runtime profile, and the product
  contract — with no lifecycle control.
- Curated Ubuntu Base ARM64 on-demand download, digest verification, safe extraction, and atomic activation proof.
- Clean Architecture package boundaries.
- Minimal working-state and port contracts.
- Documentation of research findings, constraints, risks, and planned spikes.
- Unit tests for pure domain and presentation logic.

Out of scope:

- A curated desktop web-app profile: the user's own registered web apps are the
  desktop, and the product ships no built-in desktop or file browser.
- Arbitrary image/package/app installation.
- An external SSH client: no remote host, port, profile, or credential UI exists.
- LAN/public binding.
- Target-specific app integration.
- A second Linux session: the product owns exactly one at a time.

## License and distribution caveats

- PRoot (the packaged execution bridge) is GPL-2.0-or-later. Packaging/distributing it requires GPLv2+ source/notice obligations and a combined-work legal review before distribution. No GPL-cleanliness claim is made.
- Dropbear (permissive/MIT-style) and OpenSSH (BSD) are includable in a curated rootfs, but license/notice/attribution obligations apply.
- No Google Play compliance or approval is claimed. Initial distribution is sideload/F-Droid or another controlled channel; a Play submission needs a separate policy review.

## License

Not selected yet.
