# NusaDesk guest - global agent instructions

> Seeded by the NusaDesk Android app. Unlike the generated files
> at `/root/README.md` and `/root/docs/`, this file and the
> whole `/root/.agents/` tree belong to the user: the app
> updates a seeded file only while it is byte-identical to
> what it installed and never recreates one that was deleted.
> Edit freely.

You are inside the NusaDesk guest: an Ubuntu Base ARM64 userland
running under PRoot on an Android device. That is a
compatibility layer, not a VM and not a container with its own
kernel. `root` here is guest root - every process still runs as
the Android app's uid inside the app's SELinux domain.

## Environment facts

- `/tmp` is wiped at every session start and `/run` holds
  per-session state (the bridge env, service markers). Nothing
  durable belongs in either.
- `/root/README.md` and `/root/docs/*.md` are app-generated and
  rewritten on session start - read them, never edit them.
- `systemctl` and `service` are the vendored systemctl3 bridge,
  not systemd. Only units enabled when `systemctl init` ran are
  restart-supervised, and `systemctl stop` on an enabled unit
  does not survive a session restart.
- Containers run through `udocker compose` only, and published
  `ports:` are rejected - services stay inside the guest.
- Guest storage (`/root`, `/home`, `/opt`, `/usr/local`, `/etc`,
  `/var/lib`) persists across sessions and is covered by the
  app's guest backup.
- The user's shared Android workspace, when they picked one, is
  bound at `/root/nusadesk` - real host storage visible to
  Android.
- The guest SSH endpoint is fixed at `127.0.0.1:22022`.

## Reaching Android

Two sanctioned doors - pick by what the task needs. The skill
`skills/termux-api-vs-android-cli/SKILL.md` has the full
decision.

- **`termux-*` commands (optional capability clients) and
  `nusadesk-*` commands (native NusaDesk capability clients).**
  Use installed bridge commands for Android features: notifications,
  SMS/contacts/call log, telephony, calendar, location,
  sensors, battery, clipboard, dialogs, speech, camera and
  microphone capture, Bluetooth, Wi-Fi extras, installed
  packages, usage stats, overlays, USB pass-through, and LAN
  file serving. Prefer these: grants and failures come back as
  typed errors, output is documented JSON, and the bridge only
  answers a fixed method allowlist.
- **`android-cli` (native tools).** Runs the device's own
  binaries as the app uid: `getprop`, `toolbox`/`toybox`
  applets, `sh`, `exec <path>`, and `su` on rooted devices,
  plus `status`, `doctor`, and `permissions`. Use it for device
  state or utilities the bridge does not cover. `/system/bin`
  is deliberately not on `PATH` - go through `android-cli`.
- The real Termux:API app can never serve this guest - it is
  locked to the Termux app uid and signature. If the user
  installed optional Termux commands under System > One-click
  install, these `termux-*` clients use NusaDesk's bridge.
  Likewise, `nusadesk-usb` and guest `adb` need the independent
  USB / ADB choice; neither command set is presumed present.
- A platform denial is an honest boundary, not a bug: an app
  uid cannot run `pm`, `am`, `dumpsys`, `screencap`, and
  friends. The higher tiers - the device's own `adbd` once the
  user enables `adb tcpip`, or `su` on a rooted device - are
  the user's own enablement decision, not something to work
  around.

## House rules for agents

- Never print, log, or copy the session token in
  `/run/nusadesk/android-bridge.env`; the generated CLIs read
  it themselves.
- Do not hand-roll bridge calls with invented methods or
  params - use the installed CLIs.
- Do not kill `sshd`, `lw-session-supervisor`, or `systemctl
  init` - they are the session you are running inside.
- Guest root is still an app uid: no mounts, no raw devices,
  no kernel changes. Test a capability before relying on it.
- To detach the seeded files from future updates permanently,
  delete `/var/lib/nusadesk/agent-seed.state`; to get a fresh
  seed instead, delete the seeded files together with that
  state file.


