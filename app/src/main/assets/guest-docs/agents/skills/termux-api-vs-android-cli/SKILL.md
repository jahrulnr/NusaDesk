---
name: {{SKILL_NAME}}
description: Choose between the NusaDesk capability-bridge
  commands (`termux-*`/`nusadesk-*`) and the native
  `android-cli` tools - or the higher adb-shell/root tiers -
  whenever a guest task touches an Android capability, device
  state, or a device binary.
---

# Termux API vs android-cli

The guest has two sanctioned doors into Android, and they are
not interchangeable: the bridge is a typed, permission-aware
API while `android-cli` runs the device's own binaries under
the app's uid. Decide in this order.

## 1. A bridge command covers it: use it

If an installed `termux-*` or `nusadesk-*` command covers the
task, prefer it. `termux-*` and `nusadesk-usb`/guest `adb`
are separate opt-in toolkits: check `command -v` before
using them; only the user enables a missing one from System
> One-click install. Never enable it silently as a workaround.
Bridge calls return typed errors
(`<capability>-permission-required`, `-permission-denied`,
`-unavailable`, `-busy`, `-timeout`, `invalid-argument`),
documented JSON output, and bounded reads. Exit codes depend on
the client; use the contract below and the managed copy in
`/root/docs/environment.md#exit-codes`.

- `termux-*` - optional Termux:API client surface, command- and
  JSON-compatible with Termux: battery, location, sensors,
  SMS list/send, contacts, call log, telephony,
  notifications, clipboard, toast and dialogs, TTS and
  speech-to-text, media playback, camera info/photo,
  microphone record, NFC, infrared, fingerprint, torch,
  vibrate, volume, brightness, wallpaper, wifi info/scan,
  downloads, keystore, job scheduler, SAF storage, share,
  USB. The installed set and its exact field subsets are in
  `/root/docs/termux-compat.md`.
- `nusadesk-android` - live camera/mic media sessions, the
  bounded calendar (list/add/update/delete), media-player
  routing, `bridge info`.
- `nusadesk-bt` - Bluetooth adapter state, pairing, BLE, GATT,
  RFCOMM, the bounded keyboard/mouse HID role, headset voice
  recognition.
- `nusadesk-wifi`, `nusadesk-pkg`, `nusadesk-usage`,
  `nusadesk-overlay`, `nusadesk-loc` - wifi hotspot/
  suggestions/locks, package list/info/launch, usage stats,
  overlay plates, background location.
- `nusadesk-serve` + `nusadesk-net` - serve the workspace over
  HTTP on the LAN and find the address to hand out.
- `nusadesk-usb` + the wrapped `adb` (optional USB/ADB choice)
  - USB pass-through; the stock guest `adb` sees devices
  attached to the phone's USB port.

A missing grant is a typed result, not a crash. The command
that needs the grant names it; `android-cli permissions`
lists every declared grant's state plus the Settings action
that opens it. Grants are the user's own decision - a denied
one stays denied; do not retry-loop or work around it.

## 2. A device binary or device state: android-cli

When the need is the device's own tooling or state rather
than a capability API, use `android-cli`:

- `android-cli getprop [name]`, `toybox <applet>`, `toolbox
  `<applet>`, `sh` - the phone's own binaries run natively as
  the app uid;
- `android-cli exec <absolute-path> [args...]` - any binary
  under the bound trees, no verb allowlist;
- `android-cli status` / `doctor` - report the tier and probe
  what exists; `android-cli permissions` is the one
  bridge-backed verb (declared grant states).

Every call prints an `android-cli: tier=… source=native
device=…` line on stderr (quiet with `-q`) and propagates the
child's exit code. `/system/bin`
is never on the guest `PATH` - it would shadow the Ubuntu
userland - so reach it through `android-cli`, not by adding
it to `PATH` or symlinking binaries.

## 3. The platform denies it: a higher tier, not a workaround

Power comes from the uid, not the binary. `pm`, `am`,
`dumpsys`, `screencap`, `input`, `settings put` and similar
are denied to an app uid by the platform's own checks - the
child's own error passes through verbatim with an
`android-cli: hint:` line. The honest answers are:

- the **shell tier**: the device's own `adbd` over loopback,
  once the user enables `adb tcpip` on the device - their
  enablement decision, not wired by default;
- the **root tier**: `android-cli su` on a rooted device - one
  Magisk prompt per grant.

Never pretend a denied verb ran, never silently retry, and
never claim guest root is device root.

## Never

- Do not contact the real Termux:API app: it is
  signature-locked to the Termux uid and rejects any other
  caller. The `termux-*` names here are served by NusaDesk's
  bridge.
- Do not call the bridge socket directly with an invented
  method or params - the allowlist is fixed; use the
  installed CLIs.
- Do not read or print the session token in
  `/run/nusadesk/android-bridge.env`.
- Do not add `/system/bin` to `PATH`.

## Exit codes

- `termux-*` and `nusadesk-android`: `0` result, `1` typed bridge
  error, `2` usage, unreachable bridge, or malformed response.
- `nusadesk-bt`, `nusadesk-wifi`, `nusadesk-pkg`, `nusadesk-usage`,
  `nusadesk-overlay`, `nusadesk-loc`, and `nusadesk-net`: `0` result,
  `1` typed bridge error, `2` usage,
  `5` runtime unavailable, unreachable bridge, or malformed response.
- `nusadesk-serve`: `0` success, `1` local runtime error, `2` usage.
- `nusadesk-usb`: `0` success, `1` its own usage, bridge, protocol,
  or device error; `exec` returns the child's exit code once launched.
- The wrapped `adb` follows adb's own exit-code contract.
- `android-cli`: the child's own exit code; `2` usage, `5`
  tool absent (or device `su` missing on a non-rooted phone).


