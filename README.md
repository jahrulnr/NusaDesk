<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="NusaDesk logo" width="180">
</p>

<h1 align="center">NusaDesk</h1>

<p align="center">
  <strong>Your Linux workspace, at home on Android.</strong><br>
  A calm, launcher-first desktop for a terminal and the web apps you choose.
</p>

<p align="center">
  <a href="docs/architecture.md">Architecture</a> ·
  <a href="docs/roadmap.md">Roadmap</a> ·
  <a href="docs/limitations.md">Limitations</a> ·
  <a href="docs/test-plan.md">Test plan</a>
</p>

> **Project status:** NusaDesk is an experimental product foundation. The core
> runtime and terminal path have been verified on one Android 10 / API 29 ARM64
> device. The launcher and web-app experience has been UX-verified on an API 35
> emulator. Broader device, Android-version, OEM, and 16 KB page-size coverage
> is still open.

## Overview

NusaDesk brings a focused Linux workspace to Android without trying to imitate a
full desktop operating system.

Open the app and you arrive at a simple launcher. From there, you can open a
local Linux terminal or launch the web apps you register yourself. Linux runs
quietly in the background, while NusaDesk keeps the user-facing experience
clear: setup when needed, a useful workspace when ready, and honest status when
something needs attention.

NusaDesk is intentionally curated rather than universal. It is built around a
small, tested runtime foundation—not a promise to run every Linux application.

## Quick start

### Check the project locally

```bash
make check
```

This runs the unit tests, Android lint, and a debug build.

### Build the debug APK

```bash
make build
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Install on a connected Android device or emulator

```bash
make push
```

`make push` builds first and installs the debug APK on every connected `adb`
target in the `device` state. To install manually instead:

```bash
./gradlew installDebug
```

A local Android SDK with the project’s required platform and build tools is
needed. See the [technical documentation](docs/) for the full environment and
runtime requirements.

## Features

### A launcher that feels like home

- A clean, launcher-first home surface.
- Search across the apps available in the workspace.
- Curated system surfaces alongside your own web apps.
- Responsive layouts for phones, landscape, tablets, and larger font sizes.

### A local Linux terminal

- A real guest shell presented through a mobile-friendly terminal.
- Built-in accessory keys for touch devices.
- Session continuity across navigation and Activity recreation.
- Reconnect and failure states that explain what is happening instead of
  pretending everything is running.

### Your web apps, your workspace

- Add, edit, and remove web apps by name, icon, and local guest port.
- Open each app from its own launcher tile.
- Reachability checks before loading the app surface.
- Clear unavailable, failed, and loaded states.

### Quiet background runtime

- Linux starts when the app is opened and continues behind a visible Android
  notification.
- One clear Stop action in the system notification.
- Curated setup flow for the Linux foundation and terminal component.
- No remote-host SSH UI and no LAN-sharing mode in the current product.

## Documentation

The README is intentionally product-focused. Technical details live in the
documentation set:

- [Architecture](docs/architecture.md) — runtime boundaries, layers, and data flow.
- [Limitations](docs/limitations.md) — current constraints, unsupported scenarios,
  and evidence boundaries.
- [Roadmap](docs/roadmap.md) — planned product slices and compatibility gates.
- [Test plan](docs/test-plan.md) — automated, emulator, and physical-device
  verification.
- [Architecture decisions](docs/decisions/) — the reasoning behind major
  product and runtime choices.
- [Research notes](docs/research-findings.md) — source material and technical
  findings behind the foundation.
- [Contributor and agent guidance](AGENTS.md) — repository rules and safety
  constraints.

## Honest boundaries

NusaDesk does not currently claim full Linux compatibility, guaranteed 24/7
runtime survival, support for every Android device, LAN exposure, arbitrary
package installation, or Google Play approval. See the [limitations](docs/limitations.md)
and [test plan](docs/test-plan.md) before treating the current build as a
production release.

## License and distribution

The application license and final distribution model are still under review.
Third-party runtime components carry their own license and notice obligations;
see the [technical documentation](docs/) before redistribution.
