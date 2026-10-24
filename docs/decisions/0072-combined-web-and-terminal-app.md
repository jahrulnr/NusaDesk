# ADR-0072: Web + Terminal launcher apps

## Status

User-authorized implementation in progress. Code and focused JVM tests exist;
physical UI/runtime verification is still required before completion. The native-enabled candidate is installed on the physical API 29 QA device;
the acceptance coverage below records completed and remaining gates.

## Decision

Add a third new-app type, **Web + Terminal**, with both the guest port and a
single-line guest command. A web definition optionally stores a validated
`TerminalCommand`; legacy records without that field remain web-only. Web registrations can switch between Web app and Web + Terminal in the edit
form; selecting Web app removes the stored startup command. Terminal-only
registrations retain their existing type.
One launcher icon remains the identity for the web app and its server.

Start/reuse a service-owned terminal command through `TerminalTabsPort` before
binding the web surface. `WebAppCommandSession` scopes that tab as `web:<id>` so
it cannot collide with a terminal-only registration. Existing terminal supervision
owns SSH, PTY, session stop and Activity-independent process retention. Home and
opening the icon again keep a live server; no automatic restart loop is added.
Commands must keep the server in their foreground rather than daemonize it.

HTTP responses, not command-channel readiness, gate web loading. Combined apps
retry bounded HTTP probes during a 30-second startup window; attempts release
the shared worker between retries. The existing per-request timeouts still
apply. Legacy web apps keep their one-shot probe. Retry/reload asks for the
command again, reusing a live tab or replacing a dead one. Probe generations
cancel stale deliveries and delayed retries after release or a new probe.

The web menu offers **Show terminal** and **Restart server**; the terminal menu
can return to the web app. The existing terminal Close action stops its command.
The terminal renderer is created only when Show terminal is requested; a bounded
64 KiB startup backlog preserves output before it is shown. Exited web-owned tabs
do not consume live terminal capacity. Combined tabs that exit stay available for diagnostics until explicit retry,
close or deletion; normal terminal-only clean-exit behavior remains unchanged.
Changing/removing a stored startup command closes only the old owned command.
Metadata edits retain it. Deleting the app closes its command and surfaces.
Port changes affect the web endpoint; users must keep command arguments aligned.

This reuses the existing guest command boundary. It does not execute commands
on the Android host, install services, choose another port, or create another
guest. The favicon behavior from ADR-0071 remains available to the web endpoint.

Restart is coordinated independently of Activity lifetime: stop the owned channel,
await SSH channel teardown on a worker, wait for the old TCP listener to disappear
(10-second bound), then
start one replacement. Repeated requests share the pending restart; edit/delete
cancel it. An occupied port produces failure instead of starting a competing
command. This is a release fence, not a reservation of a future server port.

## Acceptance coverage

- Add/edit/draft UI on the physical API 29 device.
- Delayed server startup on port 4050 followed by automatic web load.
- Home/reopen and Activity recreation retain one command process.
- Terminal diagnostics, explicit restart, failed command and Retry.
- Command edit/delete stop only the owned server, and a guest stop closes it.
- Inspect restart for a possible old-process/port-release race.

See [the implementation handoff](../research/web-terminal-implementation-handoff.md)
for exact state, tests, artifacts and next steps.
