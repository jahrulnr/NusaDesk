# ADR-0063: One-tap self-restart escalation for a workload that survives the forced stop

## Status

Accepted, implemented in `infrastructure/service` (`SelfRestartEscalation`,
`AppSelfRestart`, `JobSchedulerSelfRestart`, `RuntimeRevivalJobService`,
`RuntimeHostService`, `RuntimeHostController`, `HostRuntimeStatus`,
`RuntimeNotificationPolicy`, `WorkloadListener`), `infrastructure/integration`
(`GuestSshdWorkload`), and `presentation/terminal`. Covered by
`SelfRestartEscalationTest`, `JobSchedulerSelfRestartTest`,
`RuntimeHostControllerTest`, `RuntimeNotificationPolicyTest`, and
`GuestSshdWorkloadStopTest`. Device verification is the survivor recipe in
`docs/limitations.md`: freeze the tracer, let the session reach `FAILED`,
and watch the one-tap restart kill the whole app process tree and come back
`RUNNING` with a fresh tracer pid.

## Context

ADR-0062 made the wedged-session stop honest: thaw the frozen tracer, try
the graceful signals, force the kill, and verify both the tracer and the
fixed endpoint are gone before publishing `STOPPED`. Device verification on
the S10e proved that happy path end to end (`UNRESPONSIVE` → `STOPPING` →
`STOPPED` → `STARTING` → `RUNNING`).

The leg that remained is the survivor: a workload that is still alive after
the forced kill, or a port still held, ends the session `FAILED` — with a
reason whose only recovery was "force-stop the app". That means a multi-step
trip through Settings → Apps → Force stop, then reopening the app, when the
user already made one tap (`Restart Linux`) and Android exposes mechanisms
that let the app do the same thing to itself.

## Decision

1. **The escalation order is thaw → graceful → forced → verify →
   self-restart, driven only by the user's Restart tap.** The first four
   legs are unchanged (ADR-0062). The fifth is new: `Restart Linux` on a
   survivor `FAILED` does not accept the failure — it takes the app's own
   process tree down and brings it back.

2. **The survivor report is typed.** `GuestSshdWorkload` reports the leg as
   `WorkloadListener.onStopSurvived(reason)` — an interface default that
   delegates to `onFailed` for any listener that does not escalate — and
   `RuntimeHostController` records the flag on `HostRuntimeStatus`
   (`survivedStop`). The controller accepts the report only while a stop is
   in flight (`STOPPING`), a generic `onFailed` is never the escalatable
   survivor, and a new `start` clears the flag, so the decision belongs to
   the failure that produced it. The flag itself is transient, but its reason
   is persisted: `reconcileStored` re-derives it from the stored snapshot so
   a survivor `FAILED` still offers its Restart action after a process
   death — the reason string is produced only by the workload's own stop
   verdict, never by guest input.

3. **The kill is the app's own process tree, scoped by uid — not
   `killProcessGroup`.** `android.os.Process.killProcessGroup(int, int)` is
   a hidden framework API — `@hide` in AOSP, absent from the public SDK stub
   through the inspected API 37 — so it cannot be called or reflectively
   relied on for our targets. `JobSchedulerSelfRestart.kill()` performs the
   equivalent and reaches further: it scans `/proc` for every pid whose
   `status` `Uid:` field is the app's own uid — which also covers a tracee
   that called `setsid` and escaped the process group — sends them all
   `SIGCONT` (a stopped process cannot take `SIGKILL` on the kernels where
   this leg exists), then `SIGKILL`s them all, then `Process.killProcess` on
   itself. The caller's own pid is excluded from the scan and killed last so
   nothing is left behind by dying early. Nothing but the app's own uid is
   ever signalled.

4. **The revival trigger is a scheduled `JobScheduler` job — it survives the
   process death that delivers it.** `JobSchedulerSelfRestart.arm()` first
   tries an expedited `JobInfo` on API 31+ — the API the type needs, and the
   API whose background-start rules it answers; below 31, or when the
   expedited quota is spent, it schedules a plain job with a one-second
   latency floor and a five-minute override deadline, so Doze or app-standby
   can defer but never hold the revival indefinitely. The job is held by `system_server`, so it is
   delivered in a fresh process even though the process that scheduled it is
   dead. `RuntimeRevivalJobService` re-enters the same
   `RuntimeHostService.ensureRunning` boundary an app launch uses — no
   second lifecycle — and requests no reschedule from `onStopJob`, because
   the service-start intent is already handed off when the job is revoked.

5. **The API-31+ background-start restriction is handled, not assumed
   away.** Executing an expedited job is a permitted foreground-service-start
   context on API 31+, but the platform can still refuse — so
   `ensureRunning` now returns the `ComponentName` it launched (or null on a
   refusal such as `ForegroundServiceStartNotAllowedException`), and the
   JobService rewrites the restart notice the dying process raised into a
   tap-to-resume notification whose content intent opens `MainActivity`.
   The app is then foregrounded by the user's tap and `ensureRunning` runs
   the normal path. A full-screen intent is deliberately not used — the
   platform does not grant it to a sideloaded app — and no extra system
   dialog or Settings screen is part of the flow.

6. **The ordering contract is arm → notice → kill — and an unarmed trigger
   keeps the app alive.** `SelfRestartEscalation.run` calls `arm()` first
   and only calls `kill()` when arming returned true; a platform that refuses
   to schedule leaves the process running and the honest persisted `FAILED`
   as the state, with the reason's last-resort sentence covering Settings.
   `RuntimeHostService.publishStatus` publishes `FAILED` (and so persists
   it) *before* escalating, so the fresh process never inherits a dangling
   `STOPPING`.

7. **The fresh process reconciles the persisted `FAILED` and the restart
   just works.** `FAILED` is terminal for the reconciler, so the record
   stays honest rather than resurrecting; `ensureRunning` then drives the
   ordinary `start`, whose existing reclaim path deals with any tracee that
   somehow outlived the sweep, and a port still held lands in the typed
   bind-conflict `FAILED` instead of a crash.

8. **Deliberately not done:** no self-restart without a user tap — a plain
   Stop that hits the survivor leg releases and shows `Restart Linux` on the
   `FAILED` surfaces rather than restarting the app unasked; no watchdog or
   keeper process — the escalation is a single shot, not a supervisor of
   supervisors; no `AlarmManager` path — inexact alarms can be deferred under
   Doze and exact alarms need the API-34+ grant, where a scheduled job is
   deliverable; and no `killProcessGroup` reflection — the uid sweep is
   deterministic, testable, and strictly stronger.

## Consequences

- One tap on `Restart Linux` is the whole recovery even when the workload
  survives the forced kill: `STOPPING` → `FAILED` → the tree dies → the
  job runs → `STARTING` → `RUNNING`, with no Settings trip.
- The state stays honest the whole way: the survivor is still `FAILED`,
  persisted before the kill, and only the tap that follows escalates.
- The kill is stronger than the API it replaces: scoping by uid sweeps a
  `setsid`'d tracee that a process-group kill would leave holding
  `127.0.0.1:22022`.
- An OEM that spends the expedited-job quota or refuses the
  foreground-service start does not strand the user: the armed job still
  runs, and its tap-to-resume notice lands the same `ensureRunning` call.
- The decision and the seam are JVM-testable (`SelfRestartEscalation`,
  `AppSelfRestart`); only the two Android edges — the `JobScheduler`
  schedule and the `/proc` sweep — are infrastructure.
