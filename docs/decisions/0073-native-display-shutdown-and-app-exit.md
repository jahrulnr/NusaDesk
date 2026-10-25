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
Disconnect callbacks check binding ownership before acknowledging server death;
an old binding must not release the replacement server's shutdown latch.

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


## Android 10 readiness probe correction (2026-10-25)

A cold Linux homescreen launch exposed `UnsupportedOperationException` from
`LocalSocket.connect(address, timeout)` before X11 could start. The overload is
unimplemented in the Android 10 framework; its existence in the SDK was not
runtime evidence. The protocol exchange now lives in `X11SocketProbe`:
materialize the descriptor, set a 500 ms socket timeout before the supported
single-argument connect, then use a 1000 ms timeout for the X11 setup exchange.
Android's `LocalSocketImpl` sets both `SO_SNDTIMEO` and `SO_RCVTIMEO`; a full Unix
listener backlog therefore cannot block connect indefinitely. The existing
8-second retry window remains. Null exception messages display their class
name, with the complete exception still logged.

`scripts/qa/x11-probe/run.sh` compiles that actual production source into an
isolated self-targeted instrumentation APK. On API 29 it passes accepted and
refused setup, a silent peer read timeout, and a saturated listener backlog
(connect returned in 515 ms). It does not stop NusaDesk or claim PRoot/runtime
coverage; the real native app launch is a separate device check. An initial
candidate using `Os.fcntlInt` was rejected by this device test because that
public API requires API 30; it was not deployed into NusaDesk.

Source: [Android 10 LocalSocket](https://github.com/aosp-mirror/platform_frameworks_base/blob/android10-release/core/java/android/net/LocalSocket.java)
and [LocalSocketImpl timeout options](https://github.com/aosp-mirror/platform_frameworks_base/blob/android10-release/core/java/android/net/LocalSocketImpl.java).
