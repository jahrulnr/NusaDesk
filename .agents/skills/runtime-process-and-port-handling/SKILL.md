---
name: runtime-process-and-port-handling
description: Use when designing or implementing the Android-owned Linux web-app process lifecycle, readiness handshake, loopback port publication, restart, stop, child cleanup, and recoverable working state.
---

# Runtime process and port handling

Use this skill only for an explicitly scoped runtime/process slice. The current repository contains contracts but no process implementation.

## Ownership

- Android `Service`/host owns lifecycle and user-visible status.
- The guest app owns its listener and emits readiness.
- Linux service installers (`systemd`, `launchd`, scheduled tasks) are not used inside Android.

## State machine

Use explicit states and reject invalid transitions:

```text
NOT_INSTALLED -> DOWNLOADING -> VERIFYING -> EXTRACTING -> READY
READY -> STARTING -> RUNNING -> STOPPING -> STOPPED
STARTING/RUNNING -> FAILED
FAILED -> RECOVERING or STOPPED
```

Persist enough state to recover after a killed process: app/version, state, PID if known, endpoint, start identity, timestamps, failure code, and restart count. Never show `RUNNING` solely because a PID exists.

## Port contract

Do not implement a `findFreePort()` check that closes a socket before the child binds. It races with other processes.

Preferred contract:

1. configure the child to bind `127.0.0.1:0`;
2. child binds and obtains the concrete port;
3. child writes a versioned, machine-readable readiness frame through stdout/FD/IPC;
4. host validates app id, version, loopback host, port range, and identity;
5. host calls health check and only then exposes the WebView.

A fixed port may be an explicit compatibility fallback, but conflicts must become a typed error with no orphan process.

## Process rules

- Use an argv array, not shell interpolation, for the entrypoint.
- Use process groups or an equivalent tree-aware stop mechanism.
- Capture bounded logs and retain a diagnostic spill path separately.
- Stop gracefully first; force-kill only after a bounded timeout.
- Make start/stop/restart idempotent and serialized per app.
- Bound automatic restart attempts and surface the reason to the user.
- Reconcile after Android process death; clean stale metadata without killing an unrelated PID.
- Never pass secrets through command-line arguments.

## Android lifecycle

Start long-running work from a visible user action, promote it to a correctly declared foreground service, show an ongoing notification, and provide Stop. Test screen-off, Doze, force-stop, reboot, low memory, OEM battery policy, and Activity recreation. A foreground service is not a promise of universal always-on execution.

## Verification

Test state transitions, port conflict, PID-without-health, malformed readiness, process death, child-tree cleanup, repeated start/stop, and a server restart on a new port. Verify the actual process table and endpoint; do not trust a log line that merely says “started”.
