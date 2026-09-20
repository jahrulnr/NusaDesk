# ADR-0045: `android-cli` — the device's own tooling, natively, in the guest

## Status

Accepted; lands 2026-09-20: the session binds the device's tool trees into
the guest and the `android-cli` dispatcher exposes them under a collision-free
namespace. The device run is recorded in `docs/test-plan.md`.

## Context

The guest is already a Linux root shell inside PRoot, and the phone's own
tooling (`/system/bin/sh` (mksh), toolbox's `getprop`, `toybox` applets) is
worth having inside the session: scripts and agents can read device state and
use Android's utilities without a workstation.

Two facts shape the design:

1. **The `/system` files the guest already sees are not a prepared runtime.**
   `ProotLauncher` binds exactly the files the packaged bridge needs
   (`bin/linker64`, `lib64/libc.so`, `lib64/libdl.so`, `lib64/libm.so`),
   because `libproot.so` is an Android PIE that cannot exec inside a glibc
   guest without its host linker and Bionic libraries. Nothing about that was
   designed for running arbitrary Android binaries.
2. **Powers come from the uid, not from the binary.** Executing `toybox`,
   `getprop`, or `sh` from the guest runs them as the app uid in the app's
   SELinux domain — the same reach `Runtime.exec` already has. `getprop`,
   toybox utilities, and the native shell work; `screencap`, `input`, `pm`,
   `am`, `dumpsys`, and `settings put` stay denied because the platform keys
   those on the calling uid (Binder permission checks, `/dev/binder` access),
   not on which binary you ran. Those are **hard platform limits**, and the
   product does not pretend otherwise — nor does it forbid the user from
   reaching the higher tiers the platform offers (see below).

## Decision

1. **Bind the device's tool trees into the session.** The session adds
   directory binds `/system/bin` and `/system/lib64` at their identical guest
   paths (ADR-0045's deliberate exception to the file-by-file inner-runtime
   binds). The host partition is read-only, the binds are fixed host paths
   and fixed guest targets, and the guest-content-wins rule still applies.
   A bound tool tree also covers the linker/Bionic file binds inside it, so
   the inner-runtime binds are skipped when the trees are present.
2. **`android-cli` is a namespace and honesty layer, not a feature gate.**
   `/system/bin` is never added to the guest's `PATH` (it would shadow
   Ubuntu's `sh`, `ls`, `ps` and break the session); the dispatcher gives the
   tooling a separate namespace instead: `android-cli sh`, `android-cli
   getprop`, `android-cli toybox <applet>`, and `android-cli exec <path>
   [args…]` for anything else under the bound trees — no artificial verb
   whitelist. User arguments are passed as an argument list, child exit codes
   propagate, and the platform's own denial text is surfaced verbatim with a
   hint.
3. **Tier honesty.** Every call reports `tier=app source=native device=<host>`
   on stderr (quiet with `-q`), `status` prints the one-liner, and `doctor`
   lists what exists (`sh`, `toolbox`, `toybox` probes) and what the platform
   reserves for a higher tier. `android-cli sh` is the device's Android shell
   (mksh), not the session shell: it does not source guest profiles and does
   not replace `/bin/sh`.
4. **Higher tiers are documented, not hidden.** The shell tier (the device's
   own `adbd`, reached over loopback once the user enables `adb tcpip`) grants
   `screencap`, `input`, `pm`, `am`, `dumpsys` and the rest; the root tier on
   rooted devices grants more. Both are the user's own device decisions, and
   nothing in this design blocks them — they are simply not what v1 wires.
5. **The bridge keeps its role.** Anything bound to a consent dialog or
   better served by a typed API — MediaProjection capture and the RTSP stream
   (ADR-0031), battery/sensors/calendar — stays on the capability bridge
   (ADR-0030). Growing that surface stays a case-by-case decision; it is not
   a restriction on the native tier.

Rejected alternatives:

- **Driving everything through adb as the v1 mechanism.** It needs a one-time
  `adb tcpip` per boot and an authorization tap, and it is a *higher* tier —
  useful, but not a prerequisite for having the device's tooling natively.
  It remains the documented path to shell powers.
- **Putting `/system/bin` on `PATH`.** Collides with the Ubuntu userland the
  guest depends on.
- **A curated verb whitelist.** The user has a reason for every command; the
  platform's uid and permission checks are the boundary, and the dispatcher
  reports them instead of hiding them.

## Consequences

- The guest can run the device's own shell, `getprop`, and toybox applets
  with no setup step at all — no tcpip, no authorization, no extra service.
- Denied verbs fail with the platform's own message plus one hint line; the
  tier is visible in every invocation, so scripts never guess their reach.
- The bind set grows by the tool trees and the APEX runtime; the inner-runtime
  contract (the bridge's own linker/libraries) is unchanged.
- Device findings that belong to the decision (2026-09-20): the tool trees
  must include `/apex` because on Android 11+ `/system/bin/linker64` is only a
  symlink into the runtime APEX (without it every Bionic binary fails with
  "required file not found"); `/linkerconfig` cannot be bound because the app
  domain cannot read it, so the linker runs without its namespace map — the
  warning is unavoidable and APEX-only libraries do not resolve by name; and
  the dispatcher gives its **children** an Android `PATH`/`LD_LIBRARY_PATH`
  prefix while the session's own paths stay untouched.
