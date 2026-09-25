# ADR-0054: Multi-tab terminal and launcher terminal-command apps

## Status

Accepted and implemented in `domain/terminal`, `application/terminal`,
`infrastructure/terminal`, `infrastructure/service`, `infrastructure/ssh`,
`presentation/terminal`, `presentation/desktop`, and `presentation/MainActivity`.
The original multi-tab and command-PTY flows were verified on Samsung
SM-G970F, Android 12/API 31 arm64 (2026-09-25; see `docs/test-plan.md`, rows
`TTB-*`). The compact two-level tab menu, clean-exit auto-close, empty-tab
reopen path, nested Open/Close actions, and Type Spinner were verified on the
same Samsung S10e, Android 12/API 31 arm64 (2026-09-26; refinement pass in
`docs/test-plan.md`). Same-tile deduplication, Activity recreation/rotation,
and selected-tab notification behavior remain open.

## Context

ADR-0033 made the in-app shell a host-owned session: the service opens one SSH
session to the fixed loopback endpoint while the runtime is `RUNNING`, the
terminal surface consumes it through a port and a status bus, and a shell that
drops stays dropped until an explicit reconnect. Two requests extend that
contract:

1. The terminal became a place people work, and one shell is not enough. The
   user wants several terminals at once and a way to move between them from the
   options (⋮) button already in the task bar.
2. The launcher's `Add` action only knew one kind of user app: a local web app
   on a guest port. The user wants to register a *command* app — for example
   `docker exec -it codex bash` — and land directly in that PTY by tapping the
   tile, instead of typing the command by hand every time.

The security question is what a stored command means. The app already hands the
user an interactive shell as the guest account: typing `docker exec -it …`
there is exactly as authorized as typing `ls`. A stored command app therefore
adds convenience, not capability — as long as three things stay true: the
command is executed by the guest over the same pinned loopback SSH session
(never by the Android host), the SSH endpoint, credential, and host-key pinning
are unchanged (ADR-0013), and the command is a bounded, user-visible value that
the app never interpolates secrets or app data into. This ADR records those
rules explicitly, because "arbitrary shell command" is otherwise the kind of
phrase a future contributor could read as an invitation to build a host-side
command API.

## Decision

1. **Terminal tabs are host-owned sessions.** `TerminalTabsController`
   (in `infrastructure/service`) owns up to five tabs. It reuses
   `TerminalSessionController` as the per-tab session owner, so the state
   machine, the fixed endpoint, the pinned host key, and the bounded
   reconnect policy of ADR-0033 are unchanged. The runtime still owns the
   lifetime boundary: when it leaves `RUNNING`, every tab closes. When a new
   runtime session reaches `RUNNING`, it opens one initial shell tab with the
   stable id `SHELL_TAB_ID = "shell"`; the user may close it like any other
   tab. If all tabs are closed, the runtime stays up with an empty tab set;
   the terminal surface states that and the options menu still offers `New`.
   Re-publishing the same running runtime does not silently recreate a closed
   tab; only a new runtime session gets a fresh initial shell.

2. **Every tab is closable; clean exit closes its tab.** The initial shell,
   additional shells, and command tabs all close through the same controller
   path. Closing the selected tab selects the previous tab in insertion order;
   closing the last tab leaves no selection. A clean SSH channel end is
   `TerminalSessionState.EXITED`, distinct from a transport loss (`DROPPED`)
   or failure (`FAILED`): `TerminalTabsController` removes an `EXITED` tab
   immediately. Thus typing `exit` closes that shell tab, and a completed
   command closes its command tab. `DROPPED`/`FAILED` tabs stay open for an
   explicit Reconnect; connection trouble is not mistaken for a user exit.

3. **One WebView per tab, and output streams to every tab.** The terminal
   surface keeps one `TerminalBridgeView` per tab and shows the selected one;
   each tab's session delivers output to its own bridge, whether or not the tab
   is visible. Switching tabs is therefore a visibility change: a hidden tab
   keeps filling its own xterm scrollback, so no host-side replay buffer exists
   and ADR-0033's "output is a live stream, not state" stays true. The cap of
   five exists because every tab is a real WebView running xterm, mirroring the
   bounded child-tab cap of ADR-0048. ADR-0033's "one output listener at a
   time" becomes "one output listener per tab". The ⋮ menu is two-level: its
   first level lists `New`, `Terminal 1`, `Terminal 2`, …; tapping a tab name
   opens `Open` / `Close` actions for that tab. `New` is omitted at the five-tab
   cap.

4. **A bounded pre-ready queue fills the one gap that creates.** A tab's first
   output can be produced while its page is still loading; the bridge now holds
   writes in a bounded queue (64 KiB, overflow dropped) and flushes them in
   order when the packaged page reports ready. This is deliberately not a
   scrollback store: it exists only between "session opened" and "page ready",
   and Activity recreation still starts with an empty terminal.

5. **A command app is its own concrete model.** `domain/terminal/
   TerminalCommandApp` mirrors `WebAppDefinition`'s discipline (validated
   name and icon token, immutable, launcher order, `content://` icon only) and
   adds one field: a validated `TerminalCommand`. The registry, the store, and
   the codec are separate concrete types, not a shared "user app" abstraction.
   Merging the two would force a stored-record migration and a rewrite of the
   device-verified web-app slice for no user-visible gain; the launcher and the
   Add form are the surfaces where the two kinds must behave alike, and those
   are shared.

6. **The command is validated in the domain and executed only in the guest.**
   `TerminalCommand.of` trims, rejects blank input, rejects more than 512
   characters, and rejects any control character (the value is single-line and
   contains no NUL). Everything else is allowed, because the guest shell owns
   shell semantics. The controller turns a command tab into an SSH *exec*
   channel with a PTY requested (the equivalent of `ssh -t`, MINA
   `createExecChannel(command, pty, env)`), so an interactive command such as
   `docker exec -it … bash` sees a terminal. MINA's `ChannelExec` constructor
   copies the PTY dimensions/type but initializes `usePty=false`; the client
   explicitly calls `setUsePty(true)` before opening the channel. The device
   probe caught this: without the flag, `top` exited immediately and the guest
   log showed a command session without a PTY. The SSH integration test now
   asserts the server received `TERM` in its exec request and was observed red
   when the flag was removed. The Android host never executes the string: no
   `Runtime.exec`, no `ProcessBuilder`, no shell on the device.
   The tab stays associated with its command app internally for open-or-select;
   its visible menu label is always the assigned `Terminal N` ordinal,
   independent of tab kind, never command output.

7. **One live tab per command app; the tile selects or opens.** Tapping a
   command app's tile asks the controller to open-or-select: an existing tab
   for that app that is not `DROPPED`/`FAILED`/`EXITED` is selected, a dead tab
   for the same app is closed first so it cannot block the cap, and otherwise
   a fresh tab opens and is selected. A clean command exit closes its tab; a
   connection loss leaves it available for explicit Reconnect, which re-runs
   the command on a new PTY.

8. **The Add form grows a kind, not a second form.** The form keeps one name
   and one optional image, then a compact single-choice dropdown (web app on a
   local port, or terminal command) selects which single field it asks for.
   The field order is Name, Image, Type, then Port or Command. The kind is fixed
   after creation; editing shows the field the app actually has. Web apps keep
   their existing rules and error surfaces untouched.

9. **The launcher lists web apps first, then command apps**, each list in its
   own launcher order. Two stores have two order sequences; a single merged
   order would need a shared sequence the product does not otherwise have. A
   terminal app tile shows the user's image when there is one, otherwise the
   bundled terminal icon, and long-press opens its edit form.

## Alternatives considered

- **One WebView and a host-side scrollback replay buffer.** Less memory, but it
  needs a bounded byte buffer per tab and replayed output can start inside an
  escape sequence; ADR-0033 rejected exactly this. One WebView per tab keeps
  each tab's scrollback honest and makes tab switching a visibility change.
- **Generalize `WebAppDefinition` into one user-app type.** The cleanest model
  on paper, but it migrates stored web apps, rewrites a device-verified slice
  and its tests, and buys nothing the user can see. Rejected for this slice;
  the ADR keeps the door open if a third kind ever appears.
- **Open a normal shell and type the command into it.** Superficially simpler,
  but a shell needs to be ready, the typed line lands in the user's shell
  history and in the scrollback, output before the shell's prompt is messy, and
  the exit of the command is not distinguishable from the shell's own.
- **Execute the command on the Android host.** Rejected outright: it would make
  a launcher entry a host code-execution API, break the app's execution-policy
  boundary (AGENTS.md), and hand the command a capability the terminal itself
  never had.
- **Unbounded tabs, or a tab strip in the surface.** A strip steals terminal
  rows on a phone, and unbounded tabs mean unbounded WebViews. The ⋮ menu
  already exists and names each tab, which also keeps the choice accessible
  without colour.
- **A second notification or a per-tab notification.** One foreground service
  posts one notification; per-tab detail belongs to the surface.

## Consequences

### Positive

- Several terminals can be live at once, each with its own scrollback. The
  ⋮ menu stays compact (`New`, `Terminal 1`, `Terminal 2`, …); a second Open/
  Close chooser appears only after the user taps a tab name.
- Any tab can be closed. A clean SSH channel exit (including the guest `exit`
  command) closes that tab automatically; a dropped connection stays open for
  explicit reconnect. Closing the last tab leaves the runtime alive and the
  surface offers an empty-terminal state plus `New` in ⋮.
- A command app opens its PTY in one tap, with the same endpoint, credential,
  and host-key pinning the terminal already used; nothing new listens, and no
  new Android permission is involved.
- The state machine, the reconnect rule, and the runtime lifecycle are inherited
  from ADR-0033 instead of reimplemented; the per-tab tests extend the existing
  controller tests.
- The Add form stays one screen: one name, one image, one extra field; its
  order is Name, Image, Type, then Port or Command. Type uses a single-choice
  Spinner instead of always-visible radio buttons.
- The ⋮ menu stays compact at scale: `New` plus numbered tabs, with each tab's
  Open/Close choices one tap deeper. All tabs, including the first shell, can
  be closed; a clean `exit` removes the current tab automatically.
- Popup actions are captured when the menu opens, so a session snapshot that
  arrives while the menu is visible cannot retarget an existing menu item. An
  Add-form draft (kind and entered values) survives Activity recreation while
  the image picker is open.

### Negative and limitations

- At most five terminals; `New` is omitted from the top-level menu at the cap,
  and a refused open still answers with a typed reason.
- Tab scrollback is still not restored across Activity recreation or process
  death — the same limitation ADR-0033 documented; guest-side `tmux`/`screen`
  remains the answer for durable scrollback.
- The notification reports the selected tab's state (plus its reconnect
  action); it does not enumerate tabs. Closing one tab is a surface action and
  does not stop the guest runtime.
- A command app is a foreground command, not a service. A clean exit closes its
  tab automatically. If the transport drops, the numbered tab offers the
  generic `Reconnect terminal` action, which re-runs the command on a fresh PTY;
  nothing restarts it automatically.
- Command apps live in the app's own private preferences, so they are neither
  part of the guest backup (ADR-0044) nor visible to Linux.
- Deleting a command app while its tab is live leaves the PTY running until it
  is closed; the live tab remains labelled `Terminal N`, and the deleted app
  definition no longer appears in the launcher.
- A command that needs no PTY (a pure pipe, for example) still gets one, which
  can change a command's own behavior compared with a non-interactive run.
