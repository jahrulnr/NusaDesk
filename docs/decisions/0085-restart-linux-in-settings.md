# ADR-0085: Restart Linux lives on the Settings page, not in surface menus

- Status: Accepted
- Date: 2026-10-29

## Context and decision

`Restart Linux` was offered in three places: the terminal and command-app
overflow menus (added by ADR-0082 as a recovery affordance), the attach panel
shown on a `FAILED`/`UNRESPONSIVE` session, and the foreground-service
notification. A whole-session restart is session policy, not a tab or app
action: offering it next to `New`/`Close` made it read as one more
terminal-scoped item and made it reachable in exactly the place where a stray
tap is most destructive — every open app, terminal and window dies.

The menu entries are removed. `Reconnect` stays: it acts on the visible tab's
own session, so it still belongs to the surface's menu. The recovery-specific
entry points are unchanged — the attach panel on a `FAILED`/`UNRESPONSIVE`
session keeps its `Restart Linux` button (terminal, command-app, and
webapp-terminal surfaces), and the host's notification keeps its restart
action; those are contextual recovery affordances, not a standing menu item.

The moved entry point is a **Linux session** card on System > Settings,
always visible and placed first because the session is the context every
other card on that page applies to. The card reports the live runtime state
with the existing `SessionUiState` badge vocabulary (Running / Stopped /
Needs attention / Not responding…) and carries one action, `Restart Linux`,
which calls the unchanged `RuntimeHostService.requestRestart` boundary.
`ACTION_RESTART` already routes every state: `FAILED` enters `ensureRunning`
directly, while live or surviving workloads go through the existing stop
path — so the button is never gated or hidden (ADR-0043: the page reports
state and forwards the tap; it owns no policy).

The About page's session row shares the renamed `system_session_label`
("Linux session") string; its row still reads correctly.

## Alternatives

- **Keep the menu action and add the card too** — rejected: two standing
  entry points for one destructive, session-wide action is a UX regression
  with no compensating need; contextual recovery (attach panel, notification)
  already covers the states where the action matters most.
- **Hide or disable the action while a restart is meaningless** — rejected:
  `ACTION_RESTART` is meaningful in every state, and the screen's contract is
  to report state, not gate it.
- **Move it to the launcher or a dialog** — rejected: Settings is where
  session-wide controls (workspace bind, battery, boot) already live; the
  launcher is for opening apps.

## Verification boundary

Robolectric: `MainActivityRecoveryMenuTest` pins that neither the terminal nor
the command-app overflow menu offers `Restart Linux` while `Reconnect` remains,
and `SystemSessionCardTest` pins that tapping the card reaches
`RuntimeHostService` with `ACTION_RESTART` and that the card renders the
published state. `TerminalRecoveryUiTest` still passes unchanged, confirming
the attach panel keeps its restart action. Layout, state refresh on a live
session, and the actual restart on a device need device verification; a
unit-test-only result is not lifecycle evidence.
