# ADR-0062: Guest liveness canary and the UNRESPONSIVE session state

## Status

Accepted, implemented in `domain/session`, `application/session`,
`infrastructure/ssh`, `infrastructure/service`, `infrastructure/backup`,
`infrastructure/integration`, `presentation`, and
`presentation/terminal`. Covered by
`SessionLivenessPolicyTest`, `SessionStateTest`,
`RuntimeHostControllerTest`, `RuntimeNotificationPolicyTest`,
`LauncherStatusTest`, `SessionUiStateTest`, `GuestSshdWorkloadStopTest`,
and `SessionResumeReconcilerTest`. Device verification is the SIGSTOP recipe in
`docs/limitations.md`: freeze the guest tracer, watch the session flip to
UNRESPONSIVE inside a minute, and recover through `Restart Linux`.

## Context

The PRoot bridge's `link2symlink` extension can lose a tracee to a vendor
kernel quirk (`docs/research/proot-fork-hang-vendor-kernels.md`). Once one
tracee is lost, **every later command that forks in that guest hangs**: the
child stays stopped while its parent waits for it. The session does not die
— `sshd` keeps its TCP connection and the terminal's own 30 s SSH heartbeat
keeps succeeding, because nothing in the heartbeat asks the guest to spawn a
process. The observable product behaviour before this slice:

- the terminal noticed only when its heartbeat gave up, after roughly 5.5
  unanswered minutes, and then offered nothing but Reconnect;
- the app's launcher and foreground notification kept claiming `RUNNING`,
  the one state the user was told means "your Linux apps are ready".

A wedged session is therefore indistinguishable from a healthy one at every
user surface. That violates the repository's standing rule — never display a
false `RUNNING` — not because the state machine lies, but because nothing
ever asks the guest a question a wedge can fail.

## Decision

1. **The host service probes the guest on a fixed cadence.** While the
   session is `RUNNING` or `UNRESPONSIVE`, `RuntimeHostService` runs one
   probe every 15 s on its main-thread `Handler` — the same cadence pattern
   ADR-0046 established for the update check — and immediately when a
   session reaches `RUNNING`. The probe itself never runs on the main
   thread: it is handed to a single background executor, and the result is
   posted back before it touches the (deliberately non-thread-safe)
   controller.

2. **The probe must make the guest fork.** `GuestLivenessProbe` opens one
   short SSH session through the app's existing pinned wiring —
   `LocalSshSessionFactory`, the vault credential, the pinned host key — and
   exec's `/bin/true`, a command whose spawn is exactly the path the wedge
   breaks. A heartbeat or a daemon-liveness check cannot see a
   forked-command wedge; an exec can. One probe is one SSH session running
   one bounded command: silent while healthy (no per-tick logging, no
   notification churn), and hard-bounded at 8 s by the caller's await, after
   which the session is torn down and counted as a miss. Each probe gets a
   fresh `SshClientBridge` with a single-attempt `SshReconnectPolicy`, so a
   wedged probe can never hold a channel the next tick needs and the
   cadence — not the bridge — owns retry timing.

3. **Three consecutive misses flip the state; the first success flips it
   back.** `SessionLivenessPolicy` (domain) owns the counting: a single
   miss proves nothing — a busy guest or a transient SSH hiccup can cost one
   — so `RUNNING` degrades to `UNRESPONSIVE` only after three in a row,
   about 45 s at the cadence. While `UNRESPONSIVE`, one successful probe
   restores `RUNNING` because nothing else was ever torn down. The
   controller applies the transitions; results against a session that is not
   live are ignored. A fresh session gets a fresh counter, so a wedge never
   leaks misses into its replacement.

4. **`UNRESPONSIVE` is a first-class session state, persisted like the
   others.** It is reachable only from `RUNNING`; from it a session may go
   to `RUNNING` (probe recovery), `STOPPING` (user Stop or Restart),
   `FAILED`, or `CANCELLED`. It is *not* a running state:
   `HostRuntimeStatus.isRuntimeRunning()` stays false for it, so the
   terminal tabs close, the launcher's readiness pill names "Linux not
   responding" in words, and the notification says "Linux stopped answering"
   — in every case the state is text, never colour alone. The endpoint is
   preserved in the snapshot because the guest is still up, just not
   answering. It still requires a live workload, so service teardown and
   `GuestBackupTransfer` treat it as supervised/live. On process death it
   reconciles to `FAILED`, never a resurrected false `RUNNING` and never a
   persisted `UNRESPONSIVE` — a wedge that died with the process is just
   failed.

5. **Recovery is a user action: `Restart Linux`.** While `UNRESPONSIVE` the
   notification shows `Restart Linux` next to the existing `Stop`, and the
   terminal's attach panel — the same surface that offers Reconnect —
   offers it too. `ACTION_RESTART` sets a flag and calls the existing
   `controller.stop(...)`; when the published status reaches `STOPPED` the
   service re-enters through the same `ensureRunning` boundary an app
   launch uses. There is deliberately no second lifecycle: stop is the
   workload's PID-file and teardown path, start is the app-launch path.
   `ensureRunning` while `UNRESPONSIVE` is a no-op, so a foreground event
   can never resurrect a wedge without the user's Restart.

   The flag alone cannot protect the slot: a terminal publish also queues a
   terminal-tabs re-render on the same main queue, and that re-render lands
   before the restart's `STARTING` publish. The rule is therefore that only
   `publishStatus` may release the foreground slot, atomically with
   consuming the restart flag — a terminal publish with no restart pending
   stops the service, and nothing else may. Notification renders, including
   stale queued ones, can never schedule the destroy that would kill an
   in-flight start through the service-destroy supervision path, and the
   stray intent releases check the flag plus `requiresLiveWorkload` before
   stopping. `onDestroy` clears the flag and marks the service destroyed so
   a callback queued across the boundary can neither re-foreground nor
   resurrect a session.

6. **A frozen workload is thawed before termination, and `STOPPED` is only
   published once the workload and its port are gone.** Device verification
   showed the wedge's own trap shape in the stop path: a tracer stopped by
   `SIGSTOP` never sees `SIGTERM` (a stopped process cannot run its signal
   handlers), and on the vendor kernels that lose the ptrace fork event the
   stopped tracer was observed surviving even `SIGKILL` until it resumed.
   A stop that ignores that ends in a false `STOPPED` while the tracer —
   and the `sshd` tracee suspended behind it — still holds
   `127.0.0.1:22022`, so the restart's new `sshd` hits the typed bind
   conflict. The workload stop therefore thaws first: `SIGCONT` to every
   tracer pid discovered under `/proc` — the tracer is always a direct
   child of the app, `java.lang.Process` exposes no pid on Android, and the
   `/proc` entry's argv0 must match the packaged `libproot.so` path so a
   signal can never reach an unrelated process — and the guest pid-file
   signals send their own `SIGCONT` first because a directly stopped
   tracee cannot take `SIGTERM` either. Then come the
   graceful pid-file signals, `destroy()` with its bounded wait,
   `destroyForcibly()` with a second bounded wait, and two verifications:
   `isAlive()` on the tracer and the endpoint probe on
   `127.0.0.1:22022` — a tracee can outlive its tracer and still hold the
   port, so `STOPPED` requires both to be gone. A workload that still
   reports alive or still holds the port is a typed `FAILED` naming the
   workload and the recovery that works — force-stop — never a `STOPPED`.
   A `FAILED` stop also ends a pending restart instead of re-entering
   `ensureRunning`: starting over a live workload guarantees the port
   conflict and would overwrite the force-stop reason with a less
   actionable one.

   Discovery unions two `/proc` sources so the thaw does not depend on a
   single kernel option. The original source,
   `/proc/self/task/<tid>/children`, needs `CONFIG_PROC_CHILDREN`, and on
   the verified wedge device (S10e, Samsung kernel 4.14.113) that file is
   unusable -- the thaw found no tracer, a stopped tracer survived the
   forced window still holding the port, and the stop ended `FAILED` over
   a live workload. The second source scans every numeric
   `/proc/<pid>/stat` and keeps the entries whose PPid field is the app's
   own pid (`Process.myPid()`); `stat` exists on every procfs, so the thaw
   works whether or not `children` does. Either way a candidate's argv0
   must match the packaged `libproot.so` path before it is signalled, and
   a scan or signal failure still only costs the thaw -- the
   verify-after-forced-kill leg fails honestly as before.

## Consequences

- A wedge becomes a visible, named state inside roughly 45 s instead of a
  silent frozen terminal for 5.5 minutes, and the app stops claiming
  `RUNNING`.
- `STOPPED` is a verified state, not a best-effort one: the workload reports
  a stop only once the tracer is dead and the fixed endpoint is quiet — a
  tracee that outlives its tracer still holds the port — so a restart's new
  `sshd` binds `127.0.0.1:22022` instead of hitting the conflict a
  still-frozen predecessor left behind. The survivor case ends `FAILED`
  — never a `STOPPED` — and ADR-0063 makes the `Restart Linux` tap on that
  state escalate to a self-restart of the app's own process tree, so
  force-stop is now the reason's last-resort sentence rather than the
  instruction.
- A stop that cannot reach the verdict also fails honestly: the untracked
  reclaim path has no process handle, so it judges by the endpoint alone,
  and a probe counter never borrows misses across states — only
  `RUNNING`/`UNRESPONSIVE` results count toward the threshold.
- The canary **deliberately does not auto-restart** the session, **does not
  kill the tracer** (PRoot is the workload's own child; the daemon PID-file
  teardown remains the only stop path), and **does not run a guest-side
  watchdog** (nothing new is installed or executed in the guest beyond one
  `/bin/true` per tick). Automatic recovery is a product decision for a
  later slice, not a safety mechanism to bolt on silently.
- `RECONNECTING` is not probed: a dropped shell with a still-healthy guest
  is already honest, and a wedge underneath it is caught the moment the
  session returns to `RUNNING`.
- One extra short-lived SSH session every 15 s while Linux is live is the
  cost of honesty. It reuses the pinned endpoint, credential, and host key —
  no new network surface, port, or dependency.
- The terminal's Restart action sends `ACTION_RESTART` to the service; the
  surface stays a consumer and owns no lifecycle.
