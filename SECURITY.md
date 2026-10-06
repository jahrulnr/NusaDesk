# Security Policy

## Project status

NusaDesk is an Android/Linux workspace. The current
version is `0.13.0`. Runtime evidence covers the core runtime (Ubuntu Base
install, PRoot bridge, OpenSSH endpoint, session supervision, terminal) on one
Android 10 / API 29 ARM64 device, the bounded `udocker compose` adapter on one
Android 12 / API 31 ARM64 device (Samsung S10e), and — on that same S10e — the
guest service bridge (ADR-0024): the `systemctl` wrapper's argument and stderr
discipline, the CA trust store the curated rootfs lacked, and the session bus
with its `org.freedesktop.systemd1` provider, together with two real installers
(`openclaw`, `hermes-agent`) run from their published `install.sh` and, for the
latter, its user-unit supervisor reaching `active`. The
Android capability bridge: the original slices (battery, sensors, foreground
location and its bounded stream, read-only contacts/call-log/SMS/telephony,
live media in three track modes, bounded calendar read/write, USB pass-through,
the guest adb driver, ADR-0041/0042) and the Termux:API parity surface
(ADR-0049), which exposes the device-capable share of the upstream `termux-*`
command contract — device state, text/notifications, speech and dialog,
capture, storage/SAF/share, comms (SMS send, telephony call, keystore, job
scheduler), the sensor catalogue, wifi reads, infrared, media playback, NFC,
USB, and fingerprint — each device-verified per
`docs/evidence/termux-parity-matrix.md`. The web-app HTTP sign-in path
(ADR-0058) — a native card, per-app credentials in the Keystore vault, and the
challenge policy that answers only the owned host — was device-verified on that
same S10e (WA-028..033 in `docs/test-plan.md`); its one unresolved case, a
challenge that cannot be pinned to a port, is recorded as a limitation rather
than a result. The 0.8.0 capability surface
(ADR-0050/0051/0052) was verified with the S10e and the S7 Edge as each other's
peer: the Bluetooth modules (BLE advertise/scan, GATT client against the other
phone's GATT server, RFCOMM echo, discovery, pairing), the wifi extras (a real
local-only hotspot, network suggestions, wifi lock), the guest file server
reached from another machine over the LAN, usage stats, package enumeration and
launch, the guest-driven overlay, and background location — recorded per row in
`docs/evidence/capability-closure-matrix.md`. The ADR-0053 Bluetooth slice
was also exercised on a local same-key QA build at `versionCode 14`, before the
0.9.0 bump: the S10e presented as a bounded HID keyboard/mouse to the S7 Edge,
and the S10e reported WH-CH520 A2DP/HFP status, voice start/stop, and an
app-local MediaPlayer route. Exact results and unverified mouse-pointer/
built-in-speaker observations are in
`docs/evidence/bluetooth-hid-audio-matrix.md`.

Security behavior on other Android versions, OEMs, and 16 KB page-size devices
is not yet covered by the same evidence.

## Reporting a vulnerability

Please **do not disclose suspected vulnerabilities in a public issue, pull
request, or chat message**.

Until a dedicated security contact is published, report issues through a
private maintainer channel associated with this repository. If GitHub Security
Advisories are enabled for the repository, use a private advisory instead of a
public issue.

A useful report includes:

- a short description of the security impact;
- the affected version or commit;
- device model, Android version, ABI, and page-size information when relevant;
- clear reproduction steps or a minimal proof of concept;
- logs, screenshots, or traces with credentials and personal data removed;
- whether the issue is reproducible on a clean installation.

Please allow maintainers reasonable time to investigate and coordinate a fix
before making vulnerability details public. Do not access, modify, or delete
other users' data while investigating a report.

## What is in scope

Security reports are welcome for the current application and repository,
especially issues involving:

- execution of an unverified, tampered, or unintended runtime payload;
- archive extraction escaping app-private storage or creating unsafe file types;
- bypassing the fixed loopback or exact-origin WebView boundaries;
- unintended access to another local web-app port or external host;
- SSH host-key, credential, web-app sign-in credential, or Keystore handling;
- process supervision, orphan guest processes, stale PID files, or false
  `RUNNING`/readiness states;
- secrets exposed in logs, URLs, process arguments, APK resources, or crash
  messages;
- Android manifest, foreground-service, backup, or permission configuration
  that creates an unintended security boundary bypass.

## Important security boundaries

The following are deliberate properties of the current design:

- **PRoot is not a sandbox.** It shares the Android kernel, app UID, and real
  supplementary groups. A guest runtime must not be treated as hostile-code
  isolation.
- **Loopback is not authentication.** The guest SSH path uses credentials and
  pinned host keys, and the capability bridge requires its own per-session
  token; any sensitive service of its own must carry its own authentication.
- **The capability bridge authenticates every request.** The guest-facing
  Android capability bridge binds loopback only and
  requires the token generated for the active session, compared in constant
  time; the token is written into the session env file with owner-only
  permissions where the filesystem supports them and is never printed by the
  CLI. Dispatch is a fixed method allowlist — the built-in methods plus the
  registered `CapabilityModule`s of ADR-0049, no shell, reflection, URI, class,
  or Binder path. Permission-aware methods return typed states instead of
  assuming a grant, the user-visible consent and special-access flows run
  through the single bounded foreground host, and side-effecting methods
  (`sms.send`, `phone.call`, writes, posts) execute only inside that same
  per-call permission check — never silently and never without the platform
  grant.
- **Parameters are a bounded, declared surface.** Only methods that declare
  parameters may carry one flat `params` object (16 keys, 32-char keys,
  8192-char strings, scalar values only; anything else fails closed at decode
  or as `invalid-argument`), and each consumer validates every field before a
  platform call. Calendar writes stay the narrowest case: they target only a
  calendar the user can write, never write an attendee row or send an
  invitation, and are audited with one line carrying the operation and ids —
  never the event title, location, or other content (ADR-0032).
- **USB access is consent-gated and host-mediated.** `usb.list` / `usb.open`
  are the only USB bridge methods; there is no host-side transfer API. Opening
  a device always runs the platform's own per-device consent dialog (bounded,
  with denied and ignored answers as typed results), the guest receives only a
  duplicated usbfs descriptor over an abstract unix socket, and every claim and
  transfer stays the guest's — the app keeps no way to move device data on
  behalf of a script. Device classes are filtered to adb-shaped interfaces
  (`ff/42/01`) before anything is offered to the guest adb driver, so unrelated
  hardware is never opened (ADR-0041, ADR-0042).
- **The guest adb driver cannot bypass platform gates.** It presents devices
  to the stock guest `adb` through a preloaded shim; the host's consent dialog
  and the target's debugging authorization remain the user's decisions. The
  shim's sources live in the guest, and its libusb build only downgrades the
  hotplug monitor that Android's SELinux denies — no kernel or SELinux change
  is involved.
- **Payload integrity is digest-based.** Current catalog entries use reviewed,
  compile-time pinned SHA-256 digests. A signed remote catalog service is not
  implemented yet.
- **WebView content is origin-restricted.** User web apps are limited to their
  generated loopback origin. Different loopback ports are blocked, external
  links leave for the system browser, and no broad JavaScript interface is
  registered.
- **A page's file input is served by the platform picker, with the app's own
  access.** The file-chooser callback answers the page with the URI the user
  picked: a `content://` URI from a document provider, or a `file://` URI for a
  path the built-in picker walked to. The renderer reads that URI through this
  app, so the reach of an upload is the app's own reach — including app-private
  files the user deliberately navigates to, which is the point for a product
  whose guest lives there. No file access was enabled on the WebView, no
  JavaScript interface was added, the page never receives a path, and no
  persistable grant is taken: a picked file stays reachable only while the
  surface that picked it exists (ADR-0059).
- **A camera capture is served by one non-exported provider.** An image input
  may offer `Camera`; the photo is written by the camera app into this app's
  cache through `CameraCaptureProvider`, which is `exported="false"` and
  `grantUriPermissions="true"`, so the camera app reaches exactly the one URI the
  launch intent carries. The provider refuses any path outside the cache's
  `camera/` directory, offers no insert/update/delete contract, and the file is
  swept after a day. The `CAMERA` permission is the manifest's existing
  declaration (the guest capability bridge); the user is asked for it on first
  use, and a refusal is reported to the page as "nothing chosen" — never a
  capture without the grant.
- **Web-app HTTP sign-in is answered per app, never through
  `WebViewDatabase`.** A registered app may protect its own endpoint with
  HTTP Basic auth; the challenge is answered only when it names the exact
  host the WebView owns — always the literal `127.0.0.1` for a registered
  app — and is refused for any other host. `WebViewDatabase` is never used,
  because it stores credentials in plaintext. A remembered pair lives in the
  same AES-256-GCM `AndroidKeyStore` vault as the SSH credentials under the
  per-app id `webapp.<webAppId>`, is tried at most once per surface open, and
  is forgotten when the app is removed; a pair is entered only in response to
  the app's own challenge, never collected in the Add/Edit form. The sign-in
  card is a native View: no JavaScript bridge was added for it, and the
  password goes from the field to the vault without entering the WebView
  renderer (ADR-0058).
- **A WebView auth challenge cannot be pinned to a port.** The platform
  reports a challenged host and realm but no port, and the public
  `HttpAuthHandler` exposes only `cancel()`, `proceed(String, String)`, and
  `useHttpAuthUsernamePassword()`. Every registered app shares the host
  `127.0.0.1`, so a page served by one app that references a subresource on
  another app's port would be answered with the first app's stored pair if
  that port challenges the fetch. The practical exposure is bounded — the
  referring page is content the user installed in their own guest and could
  already read the same password off the guest filesystem — but the limit is
  real and is recorded in ADR-0058 rather than claimed solved.
- **The runtime is app-visible.** Linux starts from an Activity foreground
  event and remains visible through an Android foreground-service notification.
  There is no boot or silent LAN autostart path.
- **Guest services stay inside the session.** The `systemctl` replacement is a
  curated, digest-pinned guest payload — not systemd, and never installed or
  invoked on the Android host. Two manager instances run as tracees of the
  session's own PRoot tracer: the system manager the host starts and signals on
  teardown, and the product-owned `lw-user-manager.service` unit that runs the
  same replacement in `--user` mode so `systemctl --user` units come up with
  the session too. The host signals them only through command-line-verified pid
  files, and guest services bind loopback paths under the same rules as every
  other guest process. PRoot mediates `kill(2)` by tracer tree, so a guest
  command cannot signal processes outside its own session tree (ADR-0024).
- **Shared storage is opt-in and narrow.** The Linux workspace binds exactly one
  user-chosen folder into the guest at `~/nusadesk`. On Android 11+ that needs
  all-files access, which the product asks for from a visible explanation and
  never assumes: without the grant, or when a probe write into the chosen folder
  fails, the guest simply starts without a workspace. Everything else — rootfs,
  session state, host keys — stays in app-private storage (ADR-0023). Publishing
  on Google Play would additionally require the all-files access declaration and
  an approved use case.
- **The `udocker compose` adapter is a strict subset, not Docker.** The guest
  CLI accepts only a bounded service model on digest-pinned udocker/PyYAML
  source; unsupported keys and unenforceable declarations are rejected rather
  than ignored (ADR-0025). There is no Docker isolation, bridge, NAT, or
  service DNS — containers share the guest network namespace, and every
  `ports:` declaration is refused at admission because udocker/PRoot provably
  cannot enforce a loopback-only bind. Volume sources must resolve inside the
  user-chosen workspace and are always read-write. Service environment values
  travel only through a mode-0600 env file, never on process argv. Image
  pulls are delegated to upstream udocker's own registry download path; the
  host still accepts no arbitrary rootfs URL or shell command API.

See the [architecture](docs/architecture.md), [limitations](docs/limitations.md),
and [architecture decisions](docs/decisions/) for the detailed security model
and its trade-offs.

## Out of scope or not a security guarantee

- Full Linux compatibility or arbitrary Linux application support.
- PRoot/guest isolation equivalent to a VM, container, or hostile-code sandbox.
- Guaranteed 24/7 process survival across Android versions and OEM policies.
- LAN/public runtime exposure.
- Google Play approval or compliance.
- Security claims for devices or Android versions outside the documented test
  evidence.

## Disclosure and remediation

Maintainers will validate the report, determine affected versions and device
profiles, and document the remediation in the relevant source, test, or ADR.
Fixes should include regression coverage where practical and should be
verified with the repository baseline checks:

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

No fixed response or remediation SLA is promised for this version.
