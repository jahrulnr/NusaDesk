# ADR-0086: Window-supervised desktop tasks and the StartupWMClass association

- Status: Accepted
- Date: 2026-10-29

## Context

A user reported that a Linux desktop app (OpenCode) showed the `Could not open
the display` failure card while the app kept running and answering input
behind it. The app's self-update closed its windows, relaunched itself, and
the supervised process exited with a signal (`-5`) while a respawned process —
possibly in a different process group — re-mapped its window.

`desktop_apps.py` tied task health to the supervised pid set: any non-zero
`waitpid` status raised `RuntimeError` unconditionally, which the host renders
as task `FAILED`. The respawned process survived `killpg` (its group is not the
supervised one), so its windows stayed mapped and interactive behind the card.
The same teardown gap broke the surface's close button on a `FAILED` task:
`closeSelected` only wrote `/tmp/nusadesk-foreground.json`, a file consumed
inside `window_mapped` — i.e. only while the launch loop is alive — so the tap
on a dead task's surface went nowhere.

## Decision

The guest launch supervisor is now **window-supervised** instead of
pid-supervised (the contract changed deliberately; the old test pinning
"non-zero exit after READY ⇒ FAILED" was re-contracted, not deleted):

- `entry()` also reads `StartupWMClass` into a `wm_class` field.
- `owned_windows` owns a window when its `_NET_WM_PID`'s process group is
  supervised **or** either `WM_CLASS` string matches the entry's
  `StartupWMClass`. `WM_CLASS` is a format-8 STRING property of two
  NUL-terminated strings; the property reader now decodes fmt-8 strings in
  addition to fmt-32 longs. `StartupWMClass` is the freedesktop mechanism built
  for exactly this window↔entry association (bamf and docks use it).
- The launch loop lives while a supervised pid **or** an owned window exists.
  A non-zero exit prints `__NUSADESK_GUI_EXIT__<code>` and is remembered as a
  *pending* failure. Only an owned-window probe in a subsequent iteration can
  cancel it: the current window snapshot precedes `waitpid`, so it may still
  describe the very process that just crashed. A surviving or re-mapped window
  confirms the handoff; an exit without that confirmation keeps its status
  until the grace expires. This also covers a respawn that unmapped its windows
  **before** the old pid exited.
- Once no pid and no owned window remain, the task keeps watching for
  `UNMAPPED_GRACE` iterations (~3 s at the 0.15 s probe interval). A
  re-appearing owned window resets the counter **and** cancels a pending
  failure; if the grace expires the pending exit code is raised
  (`FAILED`/`status N`), otherwise the task reports
  `__NUSADESK_GUI_CLOSED__`. The same ~3 s therefore covers both the
  update→respawn gap and the deferred failure.
- An accepted edge of the deferred failure: the probe treats a transient X
  error burst (e.g. `BadWindow` while own windows tear down) as `mapped=False`,
  so a pending failure that survives the grace because errors kept every
  probe "unmapped" still reports `FAILED` — a genuinely vanished app waits the
  identical grace, so the conflation is bounded and on the safe side.
- Teardown sends `_NET_CLOSE_WINDOW` to the currently owned windows —
  including windows adopted by `WM_CLASS` after a respawn into a new group —
  before the supervised groups are `killpg`'d, so a user-initiated close still
  reaches a respawned app through EWMH.
- Host side, `NativeDesktopSession.closeSelected` on a `FAILED` task detaches
  any retained app channel and closes it on the worker before dropping the task
  and publishing `APP_CLOSED` (the host returns to the launcher). A display or
  foreground-file error can fail a task with its launch loop still alive;
  removing the channel first prevents late callbacks from reaching a new task
  for the same app. A task without a channel is simply dropped, instead of
  writing a close request no reader exists for.

## Accepted limitations

- `StartupWMClass` is optional: a desktop entry without it keeps pid/pgid
  ownership only. A respawn into a new group cannot confirm the handoff: a
  clean initial exit ends as `CLOSED`, while a non-zero exit remains `FAILED`.
- A `WM_CLASS` value shared across unrelated desktop entries would adopt a
  foreign window; entries so far carry app-specific classes.
- A respawned process whose window cannot be adopted (no `_NET_WM_PID`, no
  `StartupWMClass`) may linger as an orphan window until the display restarts;
  sweeping arbitrary orphans is out of scope (`docs/limitations.md`).

## Verification boundary

`test_desktop_apps.py` executes the real launch loop: non-zero exit with an
owned window confirmed after reaping ends `__NUSADESK_GUI_CLOSED__`; a crash
after the last mapped probe retains its status. Non-zero exit with no
window raises `status N` only after the unmapped grace expires; a window that
re-maps inside the grace cancels the pending failure, `StartupWMClass`
adoption owns windows across groups including their transient dialogs, and the
loop outlives an emptied pid set through the mapped/grace probes. Robolectric
covers the `FAILED`-task close path, including a retained live channel after a
display error and a fresh task on reopening. The device evidence in
`docs/test-plan.md` records the tested respawn handoff and EWMH close; other
devices and longer respawn gaps remain outside that evidence.
