# ADR-0009: Guest-native SSH daemon instead of an in-app SSH bridge

## Status

Accepted and implemented for the runtime workload path. The curated guest-SSH
add-on (ADR-0010) supplies the daemon, so the session now reaches a real guest
shell on-device; the lifecycle decisions below were hardened after device
verification (see "Verification" and "Teardown and death detection").

## Context

ADR-0007 made SSH the default experience and recorded that Ubuntu Base ships
no SSH server. The first vertical slice therefore ran the SSH server on the
**Android side**: an Apache MINA SSHD `SshBridgeServer` bound a loopback port
and, for each authenticated channel, spawned the curated guest shell
(`/bin/sh -i`) through PRoot with pipe-connected stdio.

Device verification of that design was fragile:

- MINA's NIO socket writes had to be marshalled off the Android main thread
  (`NetworkOnMainThreadException` under StrictMode tore down sessions on the
  first keystroke or window-change).
- The pipe-backed guest shell had no PTY: no `icrnl` line-discipline
  conversion, no job control, and resize semantics that did not map to a real
  guest terminal. A `script(1)`-allocated PTY inside PRoot was proven to work,
  but every additional piece of terminal semantics had to be re-implemented
  Android-side.
- The bridge split ownership awkwardly: Android implemented SSH server logic
  for a guest that is supposed to own its own services.

## Decision

The SSH server is **guest-owned**. The runtime workload
(`GuestSshdWorkload`) does exactly:

1. Detect a supported daemon — the activated guest-SSH add-on overlay
   (bound at `/opt/lw-ssh`) or a rootfs-resident `usr/sbin/sshd`; both are
   OpenSSH (ADR-0010 explains why Dropbear was rejected). No daemon means an
   explicit payload-not-installed failure, never a fake server.
2. Write the fixed `sshd_config` and generate/persist the guest host key
   host-side (the overlay deliberately ships no `ssh-keygen`).
3. Run a fixed guest setup script through PRoot that provisions the `sshd`
   privsep account and sets `root`'s password to the per-install Keystore
   token, delivered via a fixed environment variable (never argv).
4. Spawn the daemon under the locked PRoot spec in foreground mode.
5. Treat readiness as a real SSH protocol banner from the endpoint
   (`SshBannerProbe`), not a PID or a bare TCP accept.
6. Pin the guest host public key into the client trust store, then publish the
   readiness frame. The existing `SshClientBridge`, host-key trust store, and
   Keystore credential path are reused unchanged.

Android owns supervision only: launch, readiness, health, teardown. No
`systemd`, no guest-side service management.

The in-app MINA server core (`SshBridgeServer`, host-key store, trust policy,
health probe) remains in `infrastructure/sshserver` — independently tested
and usable for any future host-side SSH endpoint — but it is no longer wired
as the runtime workload. The glue written for it
(`LocalSshBridgeWorkload`, `ProotGuestShellLauncher`, `ShellStreamPump`) was
removed.

### Port selection: the daemon reports, the host never reserves

> **Superseded by ADR-0013 for the shipped runtime.** The candidate-proposal
> and bounded-retry model below was the original workload behavior. ADR-0013
> replaced it for the single-guest product: the endpoint is the fixed
> `127.0.0.1:22022`, there is no candidate selection and no retry, and a bind
> conflict produces a typed `FAILED` (`GuestSshdBindFailureException`). The
> historical text is preserved as the rationale for the daemon-reports/host-
> never-reserves invariant, which ADR-0013 keeps (the daemon still reports
> `Server listening on 127.0.0.1 port 22022.` before the host publishes).

Guest `sshd` cannot bind port 0: the pinned OpenSSH 9.6p1 payload rejects it
at every level (`sshd: Bad port number.` for `-p 0`, `Badly formatted port
number.` for `-o Port=0`, `bad port number` for `ListenAddress
127.0.0.1:0` — all verified on-device), and `/proc/net/tcp` is SELinux-denied
to the app UID, so a kernel-assigned port could not be discovered anyway.

The host therefore **proposes** a candidate port from the IANA dynamic range
and requires the daemon's own stderr report (`Server listening on 127.0.0.1
port N.`, emitted at the `LogLevel VERBOSE` the workload pins) before it
publishes anything. This is what ADR-0007 requires: the guest reports a
concrete loopback `host:port` after binding and the host does not
reserve-then-release a port. Consequences:

- The endpoint is attributed to the process the host launched; a port that
  answers from somewhere else can never be mistaken for our listener.
- A candidate that is already taken produces an explicit bind failure and a
  bounded retry with the next candidate, instead of a start that silently
  fails (or a published endpoint the daemon never bound).
- The SSH banner probe still runs after the bind report: the daemon's log
  proves the listener, the banner proves the protocol.

### Teardown and death detection

Killing the PRoot tracer does **not** kill its tracee: verified on-device,
both SIGTERM and SIGKILL to the tracer leave the guest daemon listening and
orphaned. The workload therefore signals the daemon itself:

- `sshd` writes its own pid into the daemon config directory
  (`PidFile`), and stop/start reclaims it with
  `android.system.Os.kill(SIGTERM)` after checking `/proc/<pid>/cmdline`
  still names this daemon (a stale pid file must never signal an unrelated
  process).
- Only then is the tracer torn down (destroy → bounded wait → destroyForcibly).
- A stop confirms the endpoint no longer answers; the confirmation is logged.
- A daemon that dies while the session is live is detected through its output
  pipe reaching EOF (`GuestSshdStderrMonitor`) and reported as a failure, so a
  dead daemon can never be displayed as `RUNNING`.

## Consequences

- Terminal semantics (PTY, echo, `icrnl`, window size, job control) come from
  the guest daemon and a real PTY, which is what sshd already implements — the
  app stops re-implementing a terminal server. The host forwards the terminal's
  real size over the channel (`window-change`), including for the local guest
  session.
- Authentication is guest-native password auth: `root` + the Keystore token.
  Host-key verification is preserved by pinning the guest's own persisted
  key, generated by our setup step.
- The endpoint remains loopback-only; the daemon itself binds `127.0.0.1`.

## Verification

Verified on the physical Android 10/API 29 arm64 device (Samsung SM-G935F):
live `root@localhost:~#` shell over the guest OpenSSH endpoint; Activity
recreation (rotation) resuming the session; Terminal → Desktop → Terminal
keeping the same session and scrollback; PTY resize tracking the layout in
both orientations; stop leaving no orphaned daemon and a dead port; restart
attaching to a new endpoint; an externally killed guest daemon reported as
`FAILED` instead of a false `RUNNING`; and recovery after that failure.
Evidence paths and the full flow are recorded in the handoff report; the
unit-test layer covers the log parser, port candidates, pid-file validation,
and the output monitor.

## Limitations

- Guest `/etc` persists inside the activated rootfs directory (PRoot `-r`
  binds it writable), so the generated host key and `root` password survive
  restarts; a rootfs reinstall regenerates them, which invalidates the pinned
  trust record under a new `host:port` scope.
- The guest session used to print `groups: cannot find name for group ID …` on
  login: the app UID's supplementary groups did not exist in the guest's
  `/etc/group`. The setup step now names the inherited Android AIDs in the
  guest group database, so the login is clean (ADR-0011). The guest still
  shares the app's real supplementary groups — that is a PRoot boundary
  property, not something this host can drop.
- If the *service* (not the process) is destroyed while a session is live, the
  next intent reconciles the persisted snapshot as `FAILED` and stops the
  workload, because the host cannot distinguish a service restart from a
  process restart. The state shown stays honest (the runtime really is torn
  down), but the failure reason is generic.
