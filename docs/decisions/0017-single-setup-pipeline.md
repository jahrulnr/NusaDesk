# ADR-0017: Single setup pipeline for rootfs + guest SSH add-on

## Status

Accepted and implemented in `presentation/MainActivity`,
`presentation/desktop/DesktopHomeView`,
`presentation/widget/InstallerWizardView`,
`presentation/widget/InstallerLog`,
the new pure presentation types `presentation/widget/InstallPhaseSnapshot`,
`SetupAction`, and `SetupPhasePolicy`, `res/layout/widget_desktop_home.xml`,
`res/values/strings.xml`, and their tests. Presentation-only; no runtime,
infrastructure, application, domain, manifest, or Gradle changes.

## Context

The curated Ubuntu rootfs and the guest-SSH add-on were two separate setup
steps in the product UI. The launcher showed a setup card with the installer
wizard while the rootfs was missing, then — once the rootfs was active — a
second "Add the Linux terminal component" card with its own
"Next: add terminal component" button. A fresh install therefore required two
user actions, and the user had to come back to the launcher and press a second
button after the rootfs finished. The parent plan
(`conv_7499eb2feb2d2f68`) calls this out as the annoying two-stage setup to
eliminate: one user action, one serialized process
(Prepare → Download Linux → Verify → Extract → Add terminal component → Ready).

Both installers already existed and already published truthful
`RuntimeSnapshot` objects through the same `ProgressListener` contract:
`AndroidRuntimeInstaller` for the rootfs (persisted to the runtime state store)
and `AndroidGuestSshAddonInstaller` for the add-on (reported to the listener
only, presence derived from disk). The installers share `PayloadIo.INSTALL_LOCK`
and are individually atomic, resumable, and retryable. The gap was orchestration
and presentation: nothing chained them, and the UI offered two actions.

## Decision

1. **One serialized orchestration, one action.**
   `MainActivity.startInstall()` is the single entry point. On the single-thread
   install executor, it installs the rootfs when it is not active on disk, then
   — on success, in the same task — immediately installs the add-on. If the
   rootfs is already active but the add-on is missing, the same action runs only
   the add-on. The `installInProgress` lock makes the action idempotent: a
   second tap or an auto-continue while a pipeline is running is a no-op. A
   rootfs failure throws before the add-on runs, so a failed rootfs never leaves
   the add-on half-installed. No parallel installers, no duplicate tap.

2. **Auto-continue on Activity foreground.**
   `onStart()` calls `continuePendingSetup()`: if the rootfs is active and the
   add-on is still missing (not failed, not installing, not installed), the same
   `startInstall()` runs only the add-on. It is idempotent and never loops; a
   failed add-on is not auto-retried, and a fresh rootfs install still starts
   only from the one button. This never starts arbitrary work before an explicit
   app launch — it is gated on the explicit Activity foreground event.

3. **Retry only what is missing or failed.**
   The pipeline decides what to run from disk truth, not from UI state:
   `isRootfsActiveOnDisk()` reads the reconciled runtime state store, and
   `isAddonActiveOnDisk()` uses the same `GuestSshDaemon.detect` as the launcher.
   A retry after an add-on failure skips a valid active rootfs and runs only the
   add-on, so a valid active rootfs is never re-downloaded.

4. **One unified installer surface.**
   `DesktopHomeView` shows a single installer wizard while either component is
   missing and hides app icons, search, and the grid until both are active
   (`LauncherModel.shouldShowApps` already requires both). The separate
   "Next: add terminal component" card and its second button are removed. The
   wizard's one action is Start setup (rootfs not installed), Try again (rootfs
   or add-on failed), or a disabled Installing… (in progress or auto-continuing);
   it is hidden when both are active.

5. **Unified, component-aware terminal log.**
   `InstallerLog` maps both components' snapshots to one append-only, bounded,
   deduplicated terminal log. Rootfs phases use the phase word
   (`download`, `verify`, `extract`, `ready`, `error`); add-on phases use the
   `ssh` component tag (`ssh  Downloading OpenSSH 1/7`, `ssh  Verifying …`,
   `ssh error  …`) so the two never blur together. Deduplication is by
   (component, state), so the add-on's per-artifact download/verify/extract
   cycle produces one line per phase per artifact, while download percent
   updates stay in the determinate progress bar. History is bounded to 80 lines.

6. **Active-install gating, no fake ready.**
   The log is gated by an active flag. It activates on a new rootfs attempt
   (the first rootfs download after idle or failure) or on an add-on download
   that starts without a rootfs install this session (rootfs already active,
   add-on auto-continued). Before activation it carries only the prepare
   context, so an initial load with the rootfs already active never appends a
   spurious `ready` line for an install that did not happen. The base rootfs
   `READY` is never faked for launcher unlock: the wizard's title for a
   rootfs-READY-but-add-on-pending state is "Adding terminal component", and the
   launcher unlocks only when both components are active.

7. **Add-on snapshots are display-only.**
   Add-on snapshots flow into the same `InstallerWizardView` as
   `InstallPhaseSnapshot` objects for display only. They are never persisted as
   base runtime state — the add-on installer already reports to the listener
   only, and presence is derived from disk. This keeps the runtime state
   store's READY check honest for the rootfs.

8. **Pure combined policy, tested without a device.**
   `SetupPhasePolicy` (phase + action), `InstallerLog` (line mapping,
   deduplication, new-attempt detection, active gating), and
   `InstallPhaseSnapshot` / `SetupAction` are pure Java with no Android imports,
   so the combined phase policy is unit-tested in plain JUnit
   (`SetupPhasePolicyTest`, `InstallerLogTest`). The Android view layer
   (scrolling, live region, layout) is verified on a device, not in
   `./gradlew test`.

Rejected alternatives:

- **Keep two cards and chain them automatically.** A hidden second action still
  leaves a second card visible after the rootfs finishes, contradicting "one
  user action". Removing the second card is the honest contract.
- **Persist an add-on snapshot in the runtime state store.** The store's READY
  check is for the rootfs; an add-on key would misread as a base runtime state.
  Presence is already derived from disk.
- **Auto-retry a failed add-on on foreground.** That is a retry loop the user
  cannot stop. A failed add-on surfaces Try again; the user decides.
- **Clear the log when the add-on retries.** The rootfs history is still valid;
  clearing it would hide that the rootfs is already done. The add-on retry
  appends after the failure (append-only, honest).
- **Re-download the rootfs on retry.** A valid active rootfs is never
  re-downloaded; retry runs only the missing/failed add-on.

## Consequences

- Fresh setup is one action: the user presses Start setup once, and the rootfs
  then the add-on install serialized on the same executor with no second tap.
- A device with an active rootfs but a missing add-on auto-continues the add-on
  on the next app launch; the user sees one "Adding terminal component" surface,
  not a second button.
- Retry after an add-on failure runs only the add-on and preserves the active
  rootfs and the rootfs log history.
- The launcher unlocks only when both the rootfs and the add-on are active; the
  wizard never fakes a base rootfs READY for unlock.
- The pure combined policy and log are tested in plain JUnit; the view layer is
  verified on the physical Samsung SM-G935F (arm64, API 29).
- The old `setup_service_card` / `setup_service_status` / `setup_service_action`
  view ids and the `setup_session_service_*` strings were removed; the launcher
  layout no longer defines them. The touch map
  (`T-launcher-and-terminal-layout-ids`) is updated to match.
