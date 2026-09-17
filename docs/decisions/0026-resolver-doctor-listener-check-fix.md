# ADR-0026: ResolverDoctor — guest resolver listener, checker, and bounded autofix

## Status

Accepted and implemented for the host-owned resolver source in
`GuestResolverDoctor` and `GuestDnsResolver`, with JVM coverage in
`GuestResolverDoctorTest`. The listener/periodic repair is device-pending for
an `apt upgrade` / `apt install resolvconf` reproduction; no device completion
claim is made until that pass is recorded.

## Context

ADR-0019 solved the initial state: Android's active-network DNS is written
to an app-private file and bind-mounted over the guest `/etc/resolv.conf`,
with a lifecycle refresh on network change.

What it does not protect against is the guest itself overwriting the
resolver. Inside the PRoot guest the bind is writable, so `apt upgrade` /
`apt install` can run maintainer scripts or DHCP logic that rewrite or
replace `/etc/resolv.conf`; this is the incident where running
`apt update && apt upgrade` inside the guest killed guest DNS. Known Linux
packages that legitimately claim the resolver (systemd-resolved,
openresolv/resolvconf, dhclient on lease renewal) replace the file or its
symlink, which the existing live bind does not recover from.

Because a foreground listener cannot be relied on to survive force-stop or
process death, correctness must not depend on the listener being alive.

## Decision

Start with one fixable resource, not a generic hook over the whole rootfs.
The vertical slice is the guest resolver (/etc/resolv.conf only).

### 1. Listener: `FileObserver` on the host source parent directory

Watch the app-private parent directory of the resolver source, not a guest
inode: the guest `/etc` path is virtual under PRoot and package operations can
re-create the bound source via rename/unlink+create. Subscribed events:
`CLOSE_WRITE`, `CREATE`, `DELETE`, `MOVED_FROM`, `MOVED_TO`, `ATTRIB`
(debounced — packages emit a burst of events for one logical change).

- The `FileObserver` reference is held for the life of the runtime; an
  unreferenced observer is finalized and stops silently.
- The listener is only **a trigger for early repair**. A periodic verification
  tick and a check at runtime start carry the system — every runtime moment
  does not rely on a background process being alive.
- The source lives under persistent `filesDir`, not Android `cacheDir`:
  Android may remove cache files while the app is installed, while the bound
  resolver is product state needed to reconstruct a session.

### 2. Checker

The doctor compares the host-owned resolver source against the expected Android
active-network DNS (the source is what every new PRoot launch binds),
distinguishing:

| Status | Meaning |
|---|---|
| `OK` | File exists and matches the Android resolver |
| `MODIFIED` | Present but content diverged (guest replaced it) |
| `MISSING` / `INVALID` | File absent or not valid resolv.conf |
| `NO_ACTIVE_DNS` | Android has no usable DNS now — report, never rewrite with a fallback |
| `NOT_PROVEN` | File looks right but the guest-side probe failed — a different failure from a stale file; the correct response is a bounded re-check, not a blind rewrite |

### 3. Fixer: bounded autofix only

`check` (read-only) and `fix` are separate actions. `fix` is an idempotent
atomic rewrite of the host-owned resolver file back to the validated Android
DNS (the ADR-0019 writer path: write temp file in the same app dir, atomic
move over the target, never touch the rootfs directly). The source bytes are
verified after repair; a real guest-side probe remains part of the device
verification plan because PRoot may virtualise a guest mutation without
emitting a host-file event.

Explicitly forbidden:

- watching or autofixing the whole rootfs (allowlist: `/etc/resolv.conf` only);
- `chattr +i` — not portably supported on app-private filesystems and it
  also blocks legitimate ADR-0019 refreshes;
- a public-DNS fallback (8.8.8.8) — if Android is offline the answer is
  `NO_ACTIVE_DNS`, not an arbitrary resolver the guest cannot reach;
- a new background process outside the existing foreground runtime
  service (Android background limits; the listener lives in the already
  owned foreground service and is user-visible through its notification);
- running arbitrary shell commands from the doctor; the fix path is the
  same validated writer code, not a shell pipeline.

## Verification plan

Phase 1: unit tests for the decision logic (status transition table,
debounce, idempotent repair, no-DNS case writes nothing).

Phase 2: device reproduction on a real phone (e.g. Samsung S10e): run
`apt update`, `apt upgrade`, `apt install resolvconf`,
`apt install systemd-resolved` inside the guest; before and after each
step record host resolver hash, guest resolver content, symlink/file type
under PRoot, observer events seen, and `getent hosts` result for a known
domain. Only after this can the design be declared working.

Phase 3 (backlog, not part of this ADR's acceptance): generalize to a
GuestDoctor registry (e.g. SshConfigDoctor, ServiceBridgeDoctor) once the
resolver slice is proven; each doctor keeps its own explicit allowlist and
check-only/fix modes.

## Consequences

- A guest-side resolver overwrite is repaired automatically while the
  runtime stays alive, bounded, and idempotent.
- Rootfs integrity is preserved: every rewrite goes through the
  host-owned app-private file and the PRoot bind, never direct rootfs
  mutation (ADR-0019 stays authoritative for content and transport).
- Correctness does not depend on listener liveness: periodic tick and the
  startup check are sufficient for eventual repair.
- A new failure class is testable: any guest code that claims the resolver
  can be reproduced and observed through the doctor statuses rather than
  debugging raw DNS failure.

## Open

- Device proof is still required that an `apt` mutation of the guest bind is
  observed or recovered by the host source guard and that a fresh PRoot launch
  sees the repaired bytes.
- Whether the verification tick needs to be per-process or can piggyback on
  the existing ADR-0019 network-callback refresh cycle remains open; v1 uses a
  dedicated bounded 30-second tick plus event debounce.
