# ADR-0007: SSH-first product direction with packaged PRoot bridge (adopted; device-evidenced on Android 10 arm64)

## Status

Accepted and implemented. The SSH-first product direction and the packaged
standalone PRoot bridge are the shipped runtime. Device-verified on one Android
10/API 29 arm64 device (Samsung SM-G935F, 4 KB pages): PRoot execution under
`untrusted_app`, the curated OpenSSH add-on on the fixed loopback endpoint
`127.0.0.1:22022` (ADR-0009, ADR-0010, ADR-0013), idempotent autostart under a
user-visible foreground service, and a live guest SSH shell in the xterm.js
WebView. See `docs/test-plan.md` for the exact device-evidence constraint.

**Evidence boundary (unchanged from the spike).** Verification covers exactly one
Android 10/API 29 arm64 device with 4 KB pages. No other API level, no 16 KB-page
device, and no other OEM has been tested. Modern Android (13/15/16/17)
foreground-service-type, background-start, dynamic-code-loading, and 16 KB-page
behavior remain open and must be verified on the device matrix before any broader
claim. The body of this ADR retains its original spike-gate language and
constraints as historical context; where a section said the bridge was "not
adopted" or "pending a spike", read it as the pre-evidence intent that the
Android 10 arm64 device run has since satisfied (the matrix above has not).

## Context

The earlier ADRs framed the product as a generic curated Linux web-app host
with an xterm.js SSH terminal as a possible first target (ADR-0006). The
accepted product plan now elevates SSH to the **default user experience**: the
app is primarily a launcher that, once a Linux runtime is installed, gives the
user terminal access to that runtime over SSH, rendered through an xterm.js
WebView. A web-desktop experience remains a later capability, not the first
slice.

This changes what "ready" must mean. An installed Ubuntu Base rootfs is not a
usable SSH endpoint on its own:

- Ubuntu Base 24.04.5 ARM64 is a minimal root tarball. It does **not** ship an
  SSH server. There is no Dropbear or OpenSSH inside it, no configured listener,
  and no host keys. The SSH-first UX cannot assume the base magically provides
  one.
- The guest must therefore have an SSH server (Dropbear or OpenSSH) explicitly
  installed, configured, host-keyed, and bound to loopback as part of the
  curated rootfs build — before any `RUNNING` claim is honest.

The execution-bridge question (ADR-0004) was the highest-risk unknown at the
time this ADR was written. PRoot was the leading candidate because it is
rootless, runs a glibc userland without a kernel module or daemon, and is the
model Termux's `proot-distro` uses. PRoot shares the Android kernel and app UID;
it is a compatibility layer, not a security sandbox (see `docs/research-findings.md`).

**Current evidence.** The bridge has since been built, packaged, and run on
device (ADR-0008, `docs/research/proot-arm64-build-spike.md` and
`proot-app-domain-device-spike.md`). The on-device evidence to date is the
Ubuntu Base download/verify/extract/activate proof plus PRoot execution under
`untrusted_app`, a guest OpenSSH listener on `127.0.0.1:22022`, a live xterm.js
WebView session, and a foreground-service supervisor — all on one Android
10/API 29 arm64 device (4 KB pages). No 16 KB-page device and no other API
level/OEM has been tested.

## Decision

### Product direction (accepted as planning baseline)

1. The default experience is **SSH-first**: an installer wizard when the
   Linux runtime is not yet installed/ready, then an xterm.js terminal that
   connects to the local guest Linux runtime over a loopback SSH path.
2. A web-desktop UI is a later capability, not the first slice, and must not
   be advertised as available.
3. The UI must show an installer wizard while the runtime is `NOT_INSTALLED`,
   `DOWNLOADING`, `VERIFYING`, `EXTRACTING`, or retryable `FAILED`. It must
   not show a fake terminal or imply a running session before readiness.

### Execution bridge (adopted; spike passed on Android 10 arm64)

1. The leading execution-bridge candidate is a **packaged standalone PRoot
   binary** placed in the APK native-library area (`jniLibs/<abi>/`), with the
   Linux ARM64 rootfs downloaded out-of-band as verified data (per ADR-0004).
2. PRoot is invoked as a **separate executable**, not linked into the app, to
   keep the clearest practical GPL boundary (see License & distribution).
3. This choice is **adopted**: the device spike proved, on real Android 10/API 29
   arm64 hardware, that:
   - `/bin/sh` runs inside the guest without root;
   - a loopback SSH listener binds and is reachable from the host;
   - the host can stop/restart the process without orphan children;
   - tampered payloads never execute (digest-pinned catalog, ADR-0002);
   - findings are recorded with device model, Android version, ABI, page
     size, and logs (ADR-0008, `docs/test-plan.md`).
4. The spike has **not** passed on a 16 KB-page device or any API level/OEM
   other than Android 10/API 29. Those remain the open gate before any broader
   "works on modern Android" claim; this ADR is revised if a later matrix run
   refutes the bridge on a supported target.

### Guest SSH server (required, not assumed)

1. The curated rootfs must **explicitly include and configure** an SSH server —
   Dropbear (smaller) or OpenSSH (fuller) — at build time. Ubuntu Base does not
   supply one.
2. The SSH server binds to `127.0.0.1` inside the guest (PRoot shares the host
   network namespace, so a guest loopback bind is reachable from the Android
   host process). Public/LAN binding is a separate, explicitly-reviewed
   capability.
3. Host keys are generated at first guest start in app-private storage, never
   shipped inside a public catalog artifact, and never logged.
4. Authentication uses app-managed credentials or keys stored in Android
   Keystore/EncryptedStorage; passwords and private keys never appear in URLs,
   process arguments, logs, or WebView JavaScript.

### Readiness, loopback token, and WebView security (exact contract)

1. Readiness means the advertised health check succeeds, not merely that a PID
   exists. The guest reports a concrete loopback host:port plus a session
   identity after binding; the host does not reserve-then-release a port.
2. The loopback endpoint is **reachability-only**, not authentication. The host
   injects an app-generated, per-session **bearer token** for sensitive
   operations (terminal WebSocket, control endpoints). The token is not
   derivable from a public value and is cleared on stop.
3. The WebView loads **only** the exact host-generated `http://127.0.0.1:<port>`
   origin after readiness. External HTTPS links leave the WebView for the
   system browser. No broad `addJavascriptInterface`; any bridge is minimal,
   origin-checked, and documented.
4. The xterm.js bundle is pinned locally (no remote CDN). The terminal page
   and all JavaScript beside it are treated as part of the terminal trust
   boundary.

### Foreground service (FGS) considerations

1. The guest runtime is started from a user-visible foreground service with an
   ongoing notification and a user-visible Stop action. Android owns
   supervision; no Linux `systemd`/`service install`.
2. `dataSync` is **not** used as an indefinite server type (Android 15 caps
   `dataSync`/`mediaProcessing` at six hours per 24h). The leading candidate is
   `specialUse` with a documented runtime-host subtype, but the exact type is
   pending and must match the real use case.
3. FGS type declaration is enforced at runtime on API 34+; the manifest must
   declare it even though `minSdk` is 29. A foreground service is not a 24/7
   survival guarantee; the UI must expose `STOPPED`, `FAILED`, and `RECOVERING`
   rather than pretending the daemon is always alive.

### Android 10 / API 29 device-evidence constraint

1. The on-device evidence is the Ubuntu Base install proof plus PRoot execution
   under `untrusted_app`, the guest OpenSSH listener, the xterm.js WebView
   session, and the foreground-service supervisor — all on one Android 10/API 29
   arm64 device (4 KB pages). Execution, SSH, WebView, and FGS behavior are
   verified on that device.
2. No 16 KB-page device and no API level/OEM other than Android 10/API 29 has
   been tested. Until the matrix passes on at least one modern/16 KB-page
   device, no runtime component may be described as working on modern Android.

## License & distribution caveats

- **PRoot is GPL-2.0-or-later.** Packaging and distributing PRoot requires
  honoring GPLv2+ (source offer, license/notice inclusion). Invoking PRoot as a
  separate executable is intended to keep the clearest practical GPL boundary,
  but the combined-work implications need legal review before distribution.
  This ADR makes **no claim** that the combination is GPL-clean.
- **Dropbear** is permissive (MIT-style, with some OpenSSH-derived BSD
  components); **OpenSSH** is BSD-style ("contains no GPL code"). Either is
  includable in the guest rootfs, but license/notice/attribution obligations
  apply and must be satisfied in the curated build.
- **No Google Play compliance or approval is claimed.** Downloading and
  executing a Linux runtime from a non-Play source may trigger Play Device and
  Network Abuse / dynamic-code-loading review, and PRoot's status as a
  VM/interpreter exception is not assumed. Initial distribution is via
  sideload/F-Droid or another controlled channel; a Play submission needs a
  separate policy review.
- The app's own code is **MIT-licensed** (root `LICENSE`).

## Alternatives considered

### Generic Linux web-app host first, SSH later

Rejected as the default UX. The accepted plan makes terminal-via-SSH the
primary experience because it is the smallest credible runtime surface and
validates the execution bridge, readiness, loopback, and WebView boundary
without a full desktop stack. A web-desktop remains a later capability.

### Assume Ubuntu Base provides an SSH server

Rejected. Ubuntu Base is a minimal rootfs without an SSH server. Any design
that assumes one is present would ship a non-functional SSH-first UX. The SSH
server must be an explicit curated-rootfs build dependency.

### Direct `ProcessBuilder` on a downloaded executable

Rejected (per ADR-0004). Conflicts with Android 10+ writable-app execution
restrictions for modern target SDKs.

### Full QEMU/AVF VM instead of PRoot

Deferred. Higher memory/startup/maintenance cost; AVF's normal Java APIs are
not available to ordinary third-party apps. Re-evaluate only if the PRoot
spike fails or stronger isolation becomes a real requirement.

### Host-side Android-native SSH client (no guest SSH server)

Not the leading path. It would move SSH cryptography and host-key policy into
the Android native surface and still require an execution bridge to provide a
PTY. Keeping the SSH server in the guest and the WebView as a thin terminal
keeps the Android native attack surface smaller. A host-side client remains a
fallback only if it can preserve host-key verification, PTY resize, reconnect,
and secret handling without expanding native risk.

## Consequences

- Positive: the product has a clear, minimal first slice (install → PRoot
  guest → loopback SSH → xterm.js) that exercises every risky boundary.
- Positive: separating "installed rootfs" from "running SSH endpoint" forces
  an honest readiness contract.
- Negative: PRoot (GPLv2+) introduces a copyleft and legal-review obligation
  that a permissive bridge would not.
- Negative: the guest rootfs is no longer just Ubuntu Base; it must be a
  curated build that adds and configures an SSH server, host keys, and loopback
  binding — more build pipeline and more to verify.
- Open: verification is one Android 10/API 29 arm64 device only. Modern Android
  (13/15/16/17), 16 KB-page, and other OEMs are untested; the SSH-first UX, the
  PRoot bridge, the FGS, and the WebView session are real on that device but
  not yet proven across the matrix.
