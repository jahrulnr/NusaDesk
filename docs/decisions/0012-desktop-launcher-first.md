# ADR-0012: The desktop launcher is home; the terminal is one Linux app

> Requested as `0011-desktop-launcher-first.md`. Renumbered to 0012 because
> ADR-0011 (`0011-guest-group-representation.md`) already occupies that slot;
> `AGENTS.md` requires sequential ADRs.

## Status

Accepted and implemented in the presentation layer.

## Context

The shipped shell was a runtime control panel with a terminal attached:

- Four peer destinations — `Terminal · Desktop · Runtime · About` — with
  `Terminal` as the default, so the product's front door was a
  `LOCAL LINUX SESSION` card whose primary action was **Start session**.
- That session control existed twice, in `TerminalScreenView` and
  `RuntimeScreenView`, sharing the same strings, and the same button was
  overloaded between "install the guest SSH payload" and "start the session"
  depending on a three-way state conjunction.
- The header badge rendered the *install* state, which produced real
  contradictions on device: an **Installed** badge above a card reading
  "No desktop server in this runtime", and an **Installed** badge beside
  "Session stopped".
- There was no home, no app grid, and no way to reach a shell without first
  operating a runtime lifecycle control.

The product direction is a Linux desktop on Android: one long-lived session,
the launcher as home, and the terminal as one Linux app among others. That
requires the session to have exactly one owner and the app surfaces to have no
lifecycle controls at all.

## Decision

1. **The launcher is the home destination.** `DesktopDestination.HOME` is
   default and there is no peer tab bar. Linux apps (`TERMINAL`,
   `WORKSPACE`) and `SYSTEM` are reached from the launcher and from the app
   surface's task bar. The four-tab navigation, the floating action orb, and the
   `About` destination are removed.

2. **The launcher is the single session owner.** `SessionStatusStrip` is the
   only view in the product that renders a start/stop control. It renders
   exactly the state the host published, through `SessionUiState`, and exposes
   at most one action. App surfaces attach to whatever it reports.

3. **One truth per surface.** The launcher's status pill renders the *install*
   state while setup is incomplete and the *session* state once the desktop is
   usable — never both. An app surface shows session chrome only when the
   session is **not** running.

4. **The terminal is a maximized app surface.** `TerminalAppView` keeps the
   device-proven SSH client, host-key trust, metadata reconciliation, PTY
   resize, and stale-bridge guards, and drops the session card, the installer
   wizard, the always-visible remote-host card, and the guest-payload card.
   When the session is not running it shows a passive prompt that links back to
   the launcher instead of a second start control.

5. **Setup is two explicit steps, not one overloaded button.** While the Linux
   system is absent the launcher shows the installer wizard; once the system is
   installed but the guest session service is not, it shows a dedicated
   "Add the Linux session service" card. The desktop — session strip, app grid,
   recents — appears only when both are in place, so an install badge and a
   session statement can never contradict each other.

6. **Recents are presentation state.** `DesktopRecents` is an immutable,
   bounded, de-duplicated list persisted through `onSaveInstanceState`. It
   deliberately does not become a domain concept or a new storage port.

7. **`DesktopApp` is a curated presentation catalogue.** Apps that are not
   implemented (`FILES`) are listed with a `null` destination so the launcher
   can say "coming soon" honestly; the tile is disabled and never fakes a
   surface.

Rejected alternatives:

- **Tile-tap auto-start.** Tapping `Terminal` while the session is stopped could
  have started the session from the launcher and opened the terminal in a
  passive wait. It is better UX, but it hides a lifecycle action inside an app
  launch and makes the terminal's own state depend on a tap that looks like
  navigation. This slice keeps one visible owner instead; a later slice can add
  it deliberately with its own acceptance criteria.
- **A `domain/desktop` + `application/desktop` layer.** The desktop app set and
  the recents list are presentation vocabulary: they name surfaces this layer
  can render and remember which one the user opened. Promoting them would add
  layers with no policy in them.
- **Retaining all app surfaces.** Only the surfaces the user has opened are
  created, and they are retained afterwards so a live terminal keeps its WebView
  and scrollback. Creating them eagerly would build a WebView the user may never
  need.
- **Keeping `About` as a destination.** Its contract copy moved into the Linux
  system screen's `How it works` disclosure, which is where a user looks for it.

## Consequences

- `LauncherDestination`, `ScreenHeader`, `DesktopScreenView`,
  `RuntimeScreenView`, `RuntimeStatusCard`, `AboutScreenView`,
  `FloatingActionOrb`, `RuntimeLifecycleDialog`, and `TerminalScreenView` are
  deleted. `RuntimeStateDescriptor` no longer says the product is a preview, and
  `RuntimeLifecycleDialog` — which could render a state the runtime was not in —
  is gone.
- The notification copy, the guest `groups:` noise, and the notification channel
  are **not** touched by this ADR; they live in `infrastructure/` and belong to
  their own slices.
- `README.md`, `docs/architecture.md`, and `docs/roadmap.md` reframe "SSH-first"
  from product identity to internal transport. SSH remains the transport between
  the Android host and the guest; it is no longer the product surface.
- The `AndroidManifest.xml` is unchanged: the launcher is a new default
  destination inside the existing single Activity, not a second exported
  Activity.
- Device verification for this change is UX-only on an x86_64 emulator
  (API 35, 720×1280@320). That ABI runs PRoot through NDK translation, so it is
  **not** evidence for the runtime contract; the arm64 device matrix in
  `docs/test-plan.md` still owns that. Live-terminal, guest-SSH, and
  background-lifecycle claims remain exactly as strong as the last arm64 device
  run, no stronger.
