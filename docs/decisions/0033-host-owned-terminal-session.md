# ADR-0033: Host-owned terminal session and notification re-attach

## Status

Accepted — implemented in `infrastructure/service`, `infrastructure/ssh`,
`application/terminal`, `domain/terminal`, and `presentation/terminal`, and
device-verified on Samsung SM-G970F Android 12/API 31 arm64 (2026-09-17):
Activity destruction left the shell attached, the `exit`→`DROPPED`→Reconnect
round-trip worked from the notification, Stop closed the session with the
runtime, and a 60 s background stay kept the 30 s heartbeat and the session
alive. Wider OEM/API coverage remains open; the stale-notification guard is
defensive code plus a unit-covered path because a dead process also removes
the notification that would deliver it.

## Context

Until this change the terminal SSH client session was owned by the terminal
view: `TerminalAppView` constructed an `SshClientBridge` and closed it from
`onDetachedFromWindow()`. That made the shell's lifetime the view's lifetime.
The observable defect: leave the app, have the Activity (and therefore the
view) destroyed by the system, reopen the app, and the SSH session is gone even
though the Linux runtime under the foreground service never stopped. Rotation
was already safe (`configChanges` avoids recreation, and retained surfaces keep
a WebView alive across launcher trips), but process-level Activity destruction,
"Don't keep activities", and renderer loss all silently killed the shell.

Meanwhile the runtime half of the same product contract — Linux stays up in the
background under a user-visible foreground service (ADR-0013) — already lived
in `RuntimeHostService`. The terminal session was the only runtime-attached
resource still owned by a view.

The keep-alive facts that frame the decision (verified against the bundled
MINA SSHD 2.19.0):

- the client heartbeat fires every 30 s (`LocalSshSessionFactory`), and an
  unanswered heartbeat series closes the client session after roughly 5.5 min;
- the generated `sshd_config` sets no `ClientAliveInterval`, so the guest never
  idles the session out;
- loopback is exempt from the Doze firewall, so backgrounding alone does not
  break the connection — only losing the process or the connection owner does.

So the session was never meant to be short-lived; the view simply closed it.

## Decision

1. **The terminal session is owned by the host service, not the view.**
   `TerminalSessionController` (in `infrastructure/service`) opens the SSH
   session once the runtime session is `RUNNING` and closes it the moment the
   runtime leaves `RUNNING` or the service is destroyed. The session identity
   follows the runtime session id: a new runtime session gets a fresh
   transport, never a recycled shell.

2. **The terminal surface is a consumer through a port and a bus.**
   `TerminalSessionPort` (`application/terminal`) is the presentation-facing
   boundary: `write`, `resize`, `reconnect`, and `setOutputListener`. Views
   render `TerminalSessionStatus` delivered by `TerminalSessionBus`, which —
   like `RuntimeStatusBus` — retains the last status so a surface created after
   a transition immediately renders the truth. `TerminalSessionRegistry` holds
   the service's current session in-process; it is `null` before the service
   exists, which is honest because no session exists then either.
   `TerminalAppView` no longer constructs, starts, or closes an SSH client and
   its `onDetachedFromWindow` only unsubscribes.

3. **Drops and failures are explicit, not silently re-attached.**
   A shell that ends while the runtime stays up becomes `DROPPED`; one that
   exhausts the bridge's bounded retry becomes `FAILED`. Neither re-attaches on
   its own — the SSH client is an app-managed local guest terminal, and a dead
   shell may be dead because the user logged out. Re-attach is the explicit
   `reconnect()`, exposed two ways: the in-surface banner (unchanged UX) and a
   notification action.

4. **The host notification carries the terminal line and a Reconnect action.**
   `TerminalNotificationPolicy` (pure, unit-tested) maps the terminal status to
   one extra notification line and to the visibility of the action. The action
   fires `ACTION_TERMINAL_RECONNECT` on `RuntimeHostService`; the service feeds
   it to the controller, which no-ops when no runtime session is running. A
   stale action delivered to a restarted service whose runtime is gone
   foreground-promotes then immediately releases and stops itself, so a dead
   notification cannot leave a useless foreground service behind.

5. **The transport is a seam, the SSH facts are unchanged.**
   `TerminalTransport` is the minimal interface the controller drives so the
   state machine is unit-testable; `SshClientBridgeTransport` wraps the
   existing `SshClientBridge`. The endpoint (`127.0.0.1:22022`), the Keystore
   credential, the pinned-host-key-only trust policy, the 30 s heartbeat, and
   the bounded `SshReconnectPolicy` (5 attempts, 0.5–8 s backoff) are all
   unchanged — ADR-0013 still owns them.

## Alternatives considered

- **Keep the bridge in the view and auto-reconnect on resume.** Cheaper, but
  it keeps the asymmetry that caused the bug: the runtime's lifetime lives in
  the service while its shell's lifetime lives in a view. It also cannot
  surface the drop anywhere the user already looks (the notification), and it
  re-attaches implicitly — the opposite of the explicit contract chosen here.
- **Move the bridge into a plain singleton without the service.** A singleton
  still dies with the process and is not user-visible; the foreground service
  is the only owner whose existence already matches the runtime's.
- **Retain terminal output on the bus for scrollback restore.** Output is a
  live stream, not state; a re-attached surface starts a fresh xterm page and
  the shell itself (plus `tmux`/`screen` inside the guest, if the user wants
  persistence) is the real continuity. A replay buffer would grow unboundedly.
- **Auto-reconnect with backoff at the controller level.** The bridge already
  has a bounded retry; adding a second, longer retry above it would hide a dead
  guest daemon behind minutes of retries and drain the radio. Explicit
  re-attach keeps cost and intent aligned.
- **A second notification for the terminal.** One foreground service, one
  notification: a second entry duplicates the status surface and still cannot
  outlive the service that posts it.

## Consequences

### Positive

- Activity destruction, recreation, "Don't keep activities", and renderer loss
  no longer close the guest shell while the Android process lives; the surface
  re-subscribes and continues the same session.
- The notification now reports "Terminal connected / disconnected" next to the
  runtime line and offers `Reconnect` exactly when the shell is gone but Linux
  is up — the user no longer has to open the app to learn the shell died.
- The session state machine is a pure-JVM unit-tested controller driven by a
  fake transport, so drop/failure/reconnect ordering is regression-locked
  without a device.
- State stays honest: `DROPPED`/`FAILED` are explicit states, never a quiet
  reconnect loop, and a stopped runtime always yields `NOT_STARTED`.

### Negative and limitations

- **Process death still ends everything.** The foreground service improves
  survival but is not a guarantee on every OEM; when the process dies, the
  runtime, the session, and the shell die with it, and the reconcile path
  reports `FAILED` honestly — no architecture can attach to a shell whose
  process is gone.
- **Scrollback is not restored across surface recreation.** The shell
  continues, but a freshly created terminal page starts empty; guest-side
  `tmux`/`screen` is the answer for durable scrollback.
- **One output listener at a time.** Only one attached surface streams output;
  that matches the product's single terminal surface.
- A reconnect action arriving while a *new* runtime session runs attaches a
  fresh shell to that session, which is correct but means the action is not
  bound to the session that was current when the notification was posted.
- The guest `sshd` must actually be up for reconnect to succeed; if the runtime
  is `RUNNING` but its daemon died, the attach fails into `FAILED` and the user
  sees the same explicit state — it is not retried forever.

## Verification

JVM (all green in the focused run, then the full gate):
`TerminalSessionControllerTest` (open on `RUNNING`, close on runtime end,
session-id replacement, drop → `DROPPED`, fail → `FAILED`, explicit reconnect,
stale-transport callback rejection, write/resize forwarding, output-listener
attach/detach, size reuse across sessions), `TerminalSessionStatusTest`, and
`TerminalNotificationPolicyTest` (the exact notification line and action
visibility per state, null-safe).

Device pass (Samsung SM-G970F `R39M209Q3TM`, Android 12/API 31, arm64,
2026-09-17 — details in `docs/test-plan.md`):

- TSS-001 PASS: `always_finish_activities` destroyed the Activity on HOME; the
  SSH session stayed up (zero teardown, zero new handshake on reopen) and the
  new surface answered typed input on the same shell.
- TSS-002/TSS-003 PASS: `exit` produced `DROPPED`, the banner, and the
  notification's Reconnect action; the action opened a fresh shell on the
  same runtime and the notification returned to `Terminal: connected`.
- TSS-004 PARTIAL: after process death a service cannot be started from
  background at all, so the stale-action path is unreachable on-device; the
  guard stays defensive and unit-covered.
- TSS-005 PASS: `ACTION_STOP` closed the terminal session with the runtime.
- TSS-006 PASS: 60 s in background kept the heartbeat on its exact 30 s
  schedule and the session connected; a non-deterministic guest-workload kill
  observed once is pre-existing OEM behavior, honestly recovered as a fresh
  session.
