# Native desktop and Web + Terminal implementation handoff

Restored 2026-10-24. The previous handoff disappeared; unsupported old
completion claims have not been copied back.

## Implemented seams

- Local PRoot guest, native X server process, SurfaceView output, Openbox;
  optional `-PnativeDesktop=true`, default build excludes the GPL binary.
- Guest desktop-file discovery, touch/keyboard/orb/zoom controls, retained
  tasks with Minimize, graceful Close, and icon-corner badges.
- Each GUI child receives an owned private D-Bus session. Nonzero application
  exits remain failures; they must not be hidden to make Quit appear successful.
- Web + Terminal stores an optional startup command with the web registration.
  Service-owned command tabs survive Home/recreation; terminal output has a
  bounded startup backlog and its renderer is created on Show terminal.
- Restart waits for SSH teardown and old TCP listener release before one
  replacement. Edit can remove the startup command by switching to Web app.

## Current corrections and evidence

The 2026-10-24 audit found overbroad private-bus cleanup, an order-dependent
renderer test, and missing evidence documents. Earlier crash/Retry and renderer
reconnect captures have been restored with hashes and PID/log evidence.
All three audit findings are corrected: full default/native gates pass, and
physical display-only teardown preserves an unrelated private bus and socket.
The corrected native APK is installed on the QA device. Cold-bind timing and
the full combined restart matrix below remain open.

Mousepad was intentionally removed by the user for edge testing and later
reinstalled with permission. Do not assume a missing executable is accidental,
or restore user packages silently in future QA. Preserve registrations and user
documents; use isolated desktop entries/documents for fault injection.

## Next acceptance work

1. Record Stop while native binding is still pending, then verify process
   death, socket/tmp cleanup and successful new startup.
2. Run the complete combined restart/cancellation matrix in the remediation
   checklist; retain trigger times, old/new process IDs and port observations.
3. Repeat relevant native tests on modern Android and 16 KB hardware when available.

Use `make check` for the default gate and `make check GRADLE_FLAGS='--no-daemon
-PnativeDesktop=true'` for the enabled branch. Build native artifacts from the
pinned source with the documented NDK/bison prerequisites. Signed local builds
use `make release` with the existing ignored signing directory. Do not commit
APKs, signing material, device serials or toolchain paths.

## Latest terminal gesture work

See ADR-0076. The detached text
node was the sustained-gesture failure: retain its listeners, deduplicate, and
release on termination. Final threshold is 8px. Installed artifact:
`750fc44238e4ceb99a80c36610bc1498048499458e158b91ee30f702775bfada`.
Five preliminary trials per mode passed; final installed guest-shell and
OpenCode each passed one long reversing gesture, with IME hidden. Full gate
passed. Do not claim five final-artifact repetitions or pixel-smooth TUI motion.

Next: recheck quota (5-hour reset 2026-10-25 03:58 WIB), then replay five final
trials per mode using `scripts/qa/terminal-touch/`. Guard the active package and
verify the exact APK before injection. Collect traces via `trace.js`; inspect
rendered output and ask the user to judge feel. Quota wrap-up is not acceptance
completion. No commit, push, delegation or scheduled continuation was requested.
