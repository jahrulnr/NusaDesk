# ADR-0014: User-local web-app launcher, local-only terminal, background Linux

## Status

Accepted and implemented in `presentation/`, `res/`, and the presentation tests.
UX-verified on the x86_64 UI emulator (API 35). Runtime
behaviour (guest SSH, a live shell, background continuation) is unchanged and
remains exactly as strong as the last arm64 device run; see
`docs/test-plan.md`.

## Context

Three product decisions landed at once and invalidated most of the shipped
presentation layer:

1. **Linux is background infrastructure.** The launcher was the only surface
   with a start/stop control (`SessionStatusStrip`), the terminal had a passive
   "start the session from the desktop" prompt, and the app surface task bar
   carried a session chip plus an overflow menu whose entries were
   `Linux system…` and `Remote SSH host…`. A user's reference screenshot of the
   intended home screen shows none of that: search at the top, a dashed
   `Add app` tile first, then app tiles, with no session chrome anywhere.
2. **The app is not an SSH client.** `RemoteHostDialog`, `SshHostProfile`, and
   `AddSshHostView` let a user enter an arbitrary host, port, username, and
   credential id, and the terminal then built a generic `SshSessionConfig` from
   that input. ADR-0013 replaced that with a fixed loopback endpoint and a
   local-only factory (`LocalSshSessionFactory`) that exposes no host or port
   parameter at all — but the presentation layer still constructed the old path.
3. **The user's web apps are the desktop.** `DesktopApp` shipped two curated
   placeholders (`Desktop Workspace`, `Files`) that could not open anything, a
   `Desktop Workspace` screen whose only job was to explain why it was empty,
   and a recents row. Meanwhile the backend for real user web apps already
   existed and was unused by production: `WebAppRegistry`,
   `SharedPreferencesWebAppStore`, `WebAppReadinessObserver`, and
   `WebAppWebViewBoundary`.

The pieces that were already right and are kept: the launcher-is-home shape
(ADR-0012), the domain install/session vocabulary, the readiness bus, the
packaged xterm terminal bridge, the pinned-host-key client, and the entire
web-app backend.

## Decision

1. **Linux starts from an explicit Activity foreground event, and nowhere else.**
   `MainActivity.onStart()` calls the idempotent
   `RuntimeHostService.ensureRunning(Context)` once the curated system is
   `READY` and the guest terminal component is installed. There is no in-app
   start/stop control, no boot receiver, job, or alarm, and no retry loop: a
   failure is stated by the launcher and the next foreground event tries again.
   The foreground-service notification and its `Stop` action remain, because
   Android requires ongoing work to be user-visible and stoppable.

2. **The launcher states readiness; it never offers an action.**
   `LauncherStatus` folds install state, terminal-component state, and session
   state into one pill (`Setup needed`, `Starting Linux…`, `Linux ready`,
   `Stopping Linux…`, `Linux stopped`, `Linux needs attention`) with no action
   vocabulary at all. `SessionUiState` lost its `Action` enum for the same
   reason, and a reflection test fails if either type grows one back.

3. **The terminal can only reach the Linux this app started.**
   `TerminalAppView` builds its client config from
   `LocalSshSessionFactory.create(cols, rows)` and uses
   `LocalSshSessionFactory.pinnedHostKeyOnly()` instead of a first-contact trust
   prompt. `RemoteHostDialog`, `SshHostProfile`, `AddSshHostView`,
   `widget_add_ssh_host.xml`, and every remote-host string are deleted, so no
   production code path can name an external host or port.
   `SshSessionConfig`/`SshClientBridge` remain infrastructure/test-only.

4. **One flat launcher grid, no placeholders.**
   `LauncherModel` builds one list: the `Add app` action first, the curated
   Linux surfaces this build can actually open (`Terminal`, `Linux System`),
   then the user's registered web apps. `Desktop Workspace`, `Files`, the
   recents row, the category sections, and the session strip are deleted rather
   than kept as unavailable items; the system screen is reached through its own
   tile, which is where install detail, technical detail, and the product
   contract belong.

5. **Add / edit web app is a three-field form, and the endpoint is generated.**
   Name (required), an optional image chosen through `ACTION_OPEN_DOCUMENT` with
   a persisted read permission, and the guest port (required). Validation is not
   re-implemented in the UI: the form hands the raw values to `WebAppRegistry`
   and `WebAppFormError` maps the typed reason to the field that caused it, which
   is focused and announced. Port `22022` stays reserved for the terminal, and
   there is no URL, host, credential, or profile field, so a registered app can
   never become a general-purpose URL launcher. Add, update, and delete all
   persist through the registry.

6. **A web app surface loads only after its endpoint answers.**
   `WebAppSurfaceView` observes `http://127.0.0.1:<port>/` once through
   `WebAppReadinessObserver` on a background executor, and only a reachable
   result is handed to a WebView bound to `WebAppWebViewBoundary` — the same
   exact-origin policy as before: a different loopback port is blocked, external
   links leave for the system browser, and no JavaScript interface or file access
   exists. Probing, unreachable, failed, and loaded are explicit states, each
   with the one action that can change it (`Try again`).

7. **The task bar is compact and contextual.**
   A way back, the surface title, and an options button that exists only when
   the surface really has app actions (a user web app: `Edit app…`). No session
   chip, no `Home` text button, no overflow menu whose only entry duplicates the
   launcher.

Rejected alternatives:

- **Keep a start button as an escape hatch.** It would contradict the automatic
  path, need a second session owner, and re-introduce exactly the chrome the
  reference screen does not have.
- **Keep the remote-SSH dialog as a power-user feature.** The app is not an SSH
  client; an external host needs its own credential, trust, and product story.
- **Keep `Desktop Workspace` / `Files` as "coming soon" tiles.** A launcher that
  advertises two apps that cannot open is worse than a launcher with two real
  ones and the user's own apps.
- **Probe every web app when the launcher renders.** That is N bounded HTTP
  probes per render for information the tile cannot honestly show anyway
  (reachability is not health). The probe happens when the user opens the app.
- **Add a URL field "for flexibility".** The generated origin is the security
  boundary; accepting a URL would make the tile an arbitrary-navigation surface.
- **Keep the session-metadata store as the terminal's reconnect memory.** With a
  fixed endpoint and no user disconnect, the metadata only recorded the endpoint
  it already knows; the terminal now remembers the one session whose shell
  dropped so it does not silently re-attach.

## Consequences

- **Removed presentation surface.** `SessionControl`, `SessionStatusStrip`,
  `AppSurfaceSessionChip`, `DesktopAppSearch`, `DesktopAppSectionView`,
  `DesktopAppTileView`, `DesktopRecents`, `DesktopWorkspaceView`,
  `SectionHeaderText`, `RemoteHostDialog`, `SshHostProfile`, `AddSshHostView`,
  `widget_add_ssh_host.xml`, `widget_session_strip.xml`,
  `widget_workspace_screen.xml`, `widget_desktop_app_section.xml`,
  `widget_desktop_app_tile.xml`, `widget_recents_card.xml`,
  `status_row_surface.xml`, the `layout-land`/`layout-sw600dp` launcher
  variants (the grid now reflows from one layout), and their tests.
- **Added presentation surface.** `LauncherEntry`, `LauncherModel`,
  `LauncherStatus`, `LauncherHeaderText`, `LauncherTileView`,
  `LauncherGridView`, `presentation/webapp/WebAppFormError`,
  `presentation/webapp/WebAppFormView`, `presentation/webapp/WebAppSurfaceView`,
  `widget_launcher_tile.xml`, `widget_add_web_app.xml`,
  `widget_web_app_surface.xml`, `tile_add_surface.xml`, `launcher_backdrop.xml`,
  `ic_home.xml`, `ic_more.xml`, `ic_image_placeholder.xml`.
- **The launcher cannot start Linux.** After the notification's `Stop`, Linux
  stays stopped until the next app launch. This is deliberate and is stated in
  the stopped-state copy ("Linux starts again next time you open the app").
- **`SharedPreferencesSessionMetadataStore`, `SessionMetadata`, and
  `SessionMetadataCodec` are now unused by production.** They are out of this
  change's ownership and are left in place with their tests; removing them is a
  follow-up cleanup, not a silent deletion.
- **Emulator evidence boundary.** The x86_64 UI emulator cannot run the
  arm64-only PRoot bridge, so the guest shell, the fixed port, and background
  continuation remain arm64-device evidence (ADR-0013 and
  `docs/test-plan.md`). The emulator covers layout, navigation, form validation,
  persistence, the picker path, and the web-app unreachable/loaded states.
- **Reachability is not health.** Unchanged: a registered app answers HTTP or it
  does not, and the surface says so.
