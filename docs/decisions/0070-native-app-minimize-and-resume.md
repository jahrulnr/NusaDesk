# ADR-0070: Retained native GUI tasks with minimize and resume

## Status

Accepted for the experimental native-display build. Supersedes the one-GUI-app
and close-before-switch behavior in ADR-0068/0069. The user explicitly requested
minimize and multiple live apps. The product still owns exactly one Linux guest
session and one X11 display; the GPL/build-flag boundaries remain unchanged.

## Decision

Keep one Openbox workload for the display, with its own SSH channel and EWMH
window-manager readiness probe. Each selected desktop entry has an independent
supervised GIO launch channel. Selecting another entry retains the existing
process and document. Selecting a live entry again resumes it, rather than
starting a second instance of that entry.

`DesktopTasks` owns the pure state transitions: STARTING, RUNNING, MINIMIZED,
FAILED, app exit and session clear. Snapshots are immutable. There is one selected
foreground task; background apps retain their processes. A visible title-bar
Minimize action hides the selected task and returns Home. Existing installed-app
icons show task badges: • running, − minimized, … starting, ! retry. Tapping the
same icon resumes its retained task. There is no separate running-app section.
A live task remains available until exit if a catalog refresh removes its entry.
A failed task whose entry disappeared does not leave a ghost icon.
Normal application exit removes its task while leaving other apps and the display
alive. The original Mousepad Quit acceptance was defective: it actually crashed
without a session bus. ADR-0073 records the reproduced failure and private-bus fix.
The toolbar Close action sends `_NET_CLOSE_WINDOW`; unsaved confirmation stays
owned by the application, including Cancel. A failed task exposes retry. No close-before-switch
confirmation is needed because switching no longer terminates an application.

The host atomically writes a session-scoped `/tmp/nusadesk-foreground.json`
containing the selected desktop ID and a fresh activation token. The token also
allows explicitly resuming the same task. Writes are serialized with display
teardown and invalidated on stop, so queued foreground work cannot recreate
files after ADR-0027 tmp cleanup.

Guest helpers associate EWMH clients with their launched process groups using
`_NET_WM_PID`, then include transitive `WM_TRANSIENT_FOR` children. Other tasks'
windows do not establish readiness. At each existing process-monitor tick,
helpers apply iconification or map/raise requests only on a foreground-token
change or newly discovered owned windows. Restoring follows X11 stacking order
and requests focus for the topmost owned window using `_NET_ACTIVE_WINDOW`.
A background app can create new windows, which its helper also minimizes.
Parentless dialogs retain the fit correction from ADR-0069.

Before changing tasks, the Android input adapter releases held keys/buttons and
finishes its local IME context. App document text, selection and scroll remain
inside the running Linux process. Zoom/layout and input mode remain display-wide.

Whole-session teardown closes every app channel, then the WM channel and native
display, before tmp cleanup. This is retention in the current Android/guest
session, not persistence of running apps across Android process death or a guest
restart. Apps without usable PID/transient ownership metadata, self-daemonizing
apps and D-Bus-only activation still require additional profiles/integration.
There is no promise that available device RAM supports an arbitrary app count.

## Validation

Pure task tests cover switch retention, minimize during startup, retry, immutable
snapshots, exit and clear. Guest helper tests cover process-group/transient
ownership, rejection of another task's readiness, minimize/restore dispatch,
parser/Exec behavior and standalone-dialog fit. Robolectric tests exercise the
contextual minimize button, badge updates/removal, and stable launcher identities. Shell workload
tests verify WM probe/cleanup and desktop-ID argument boundaries.

Physical evidence — PIDs, dialogs and teardown results — was captured on the
initial multi-app device, the existing SM-G935F/API 29 custom ROM; additional
APIs/OEMs, physical keyboards and long background/reclamation remain separate
gates.
