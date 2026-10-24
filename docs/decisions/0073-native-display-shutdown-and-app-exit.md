# ADR-0073: Native display shutdown and supervised application exit

## Status

Accepted for the experimental native-enabled build. This corrects lifecycle and
exit defects found in review of ADR-0068–0072. It does not remove the GPL release
gate or establish Android/OEM coverage beyond recorded devices.

## Decision

Run complete display teardown on the display worker. Closing an SSH bridge is
nonblocking for UI callers; the worker explicitly awaits completed channel
cleanup before guest temporary storage is cleared. Native shutdown always
unbinds and clears binding/server ownership in `finally`, including timeout,
Binder failure and interruption. Cold binding has a 30-second completion bound;
server death and all channel closure have separate 10-second bounds and display
shutdown diagnostics, rather than misreporting a temporary-storage error.

Readiness requires both the WM and an attached renderer. Renderer disconnection
invalidates display readiness; Retry reconnects to the retained server instead
of publishing RUNNING without a renderer. Stale view/binding callbacks do not
invalidate a newer surface or resurrect a stopped generation.

Each GIO application launch runs inside `dbus-run-session`. The helper disables
D-Bus activation in its own GIO launch environment to retain the actual spawned
PID, while its `GAppLaunchContext` passes the real private session bus to the
child. Each launch supplies a unique abstract Unix address through the staged
`session-bus.sh` daemon wrapper; the host records that address. Display shutdown
matches the exact daemon address against this owner's records and waits for
those buses to die. Abstract addresses disappear with their process, so cleanup
never removes unrelated `tmp/dbus-*` names. The display socket and lock are
unlinked only after the server Binder is dead. This is required by device evidence: Mousepad Quit without a bus exits
with SIGSEGV (-11), including with keyfile settings; with the private bus it
exits 0. A nonzero exit remains an error. Missing GIO libraries produce a typed
helper failure; the scanner includes merged PTY output in its diagnostic.

The toolbar Close action requests EWMH `_NET_CLOSE_WINDOW` once per request.
Linux owns unsaved prompts and cancellation. Explicit helper CLOSED only follows
successful exit after mapped-window readiness; host navigation then removes the
task and returns Home. Transport/crash failures retain Retry. READY is not
concatenated into the user-facing failure reason; full output remains logged.

Catalog refreshes arriving during a scan coalesce into a fresh subsequent scan.
A removed catalog entry is retained only while its task is live; failed or exited
removed apps do not leave ghost tiles. No fixed Mousepad placeholder is inserted.

## Evidence

Focused regressions, physical Quit/Close/uninstall observations, fresh dialog
captures and limitations were collected during review remediation. Historical
identical screenshots are explicitly withdrawn as independent before/restore
evidence.
