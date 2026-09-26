# ADR-0055: Opt-in guest-services extra at first install and on One-click install

## Status

Superseded by ADR-0057. The opt-in services bundle described below was
implemented and device-tested, then replaced by a required services bridge
and two independently optional toolkits. This record preserves the earlier
context and OPT-001–005 evidence.

Amends ADR-0017 (the pipeline gains an optional tail), ADR-0037 (the boot
gate drops the bridge check), and ADR-0043 item 4 (the page's first real
Install action is the services extra; the USB/ADB and Termux rows report a
dependency on it instead of claiming they are always active).

## Context

ADR-0017 made rootfs + guest-SSH one serialized setup action, and the later
service bridge (ADR-0024) was appended to the same pipeline:
`MainActivity.continuePendingSetup()` auto-continued into the bridge install
whenever it was missing, and `ensureRuntimeRunning()` held the session start
until the bridge was active or failed. First install therefore always
downloaded every curated bundle — the user report was exactly that:
"everything installs automatically" while System &gt; One-click install was
display-only (ADR-0043 had deferred real install actions to "the provisioning
slice").

The honest component boundary already exists:

- **Core** — the `ubuntu-base-arm64` rootfs and the `guest-ssh-openssh`
  add-on. The terminal product cannot work without them; the launcher unlock
  rule is unchanged.
- **Optional extra** — the `guest-service-bridge` overlay (Python 3.12
  closure, vendored `systemctl`/`service`, udocker compose, session
  supervisor, user-service manager). `GuestSshdWorkload` already runs a valid
  SSH-only session without it; `ProotLauncher.activatedAddonBinds` simply
  binds whichever overlays are active.

Every session-provisioned toolkit (`nusadesk-usb`, the adb driver,
`termux-*`, the `nusadesk-*` capability clients) is a
`#!/usr/bin/env python3` script, so the single extra gates all of them
functionally at once — no per-package installer is needed or wanted.

## Decision

1. **The extra is opt-in everywhere, never auto-installed.**
   `continuePendingSetup()` no longer continues into the bridge; a missing
   bridge is a settled state, not pending work. `startInstall(boolean
   includeServices)` runs the bridge only on an explicit user choice: the
   setup card's "Guest services &amp; tools" checkbox (unchecked by default,
   shown only while a start/retry decision is pending, labelled with the
   real download size and where to install it later) or the Install /
   Try again action on System &gt; One-click install.
2. **One serialized pipeline still owns all installs.** The System-page
   action calls the same `startInstall` path, so the `installInProgress`
   lock makes a tap during a core install a no-op; the row stays disabled
   while the pipeline is busy (`ServicesExtraState`).
3. **Session start waits only for a user-requested extra in flight.**
   `serviceBridgeSettled()` treats an absent extra as settled. When the user
   did ask for it, the session still holds until the overlay lands or fails,
   so the first session can run the service manager the user just chose —
   and a failure still starts the honest SSH-only session.
4. **Boot start follows the same truth.** `BootAutostartPolicy` drops the
   `serviceBridgePresent` gate and `SKIP_BRIDGE_NOT_SETTLED` is removed:
   an absent optional extra is a valid session, and add-on install outcomes
   are not persisted, so at boot "absent" cannot be told apart from
   "declined". Requiring it would silently disable boot start forever for
   everyone who opted out.
5. **Dependent rows tell the truth.** The services row carries the real
   Install/Installing…/Installed/Install failed states with the live detail
   line; the USB/ADB and Termux rows show "Active in every session" only
   while the extra is installed, otherwise "Needs Guest services &amp;
   tools". `ServicesExtraState` derives this from disk truth (installed) over
   the in-memory snapshot (installing/failed), because add-on outcomes are
   not persisted. The page also tells users to restart an already-running
   Linux session after installing: its overlay binds were selected at session
   start, so the current shell does not gain Python or these tools mid-session.
6. **The setup log names the extra distinctly.** Bridge snapshots get their
   own `SERVICES` component tag — `svc  Downloading Guest services (systemctl)
   3/14`, `svc error  …` — instead of borrowing the `ssh` tag, and the log's
   wrapped continuations indent under the detail column (`LeadingMarginSpan`)
   so a wrapped line can never be mistaken for a new log event.

## Provisioning overlap — deliberately unchanged

`GuestAwarenessReadmeWriter` and the `Guest*CliWriter`/`Guest*DaemonWriter`
family still write their toolkit scripts into the guest every session —
gating per-toolkit file writes means splitting that shared bundle, which
belongs to the guest-agent provisioning area and is coordinated separately.
Without the extra the scripts are inert text that fail on their `python3`
interpreter; the System page states the dependency rather than hiding it.
Per-toolkit toggles or un-provisioning are rejected for this slice.

## Existing installs and upgrades

State derives from disk: an overlay already installed stays installed and is
never re-downloaded or removed; a device that never had the bridge simply
shows "Not installed" with the Install action. No reset, no uninstall, no
migration. The opt-in flag is in-memory only — a process killed mid-pipeline
loses the checkbox choice, and the extra is then offered from the System
page instead of resuming silently.

## Rejected alternatives

- **Per-toolkit checkboxes (USB, Termux, compose…).** One curated extra
  covers them all today; a matrix duplicates plumbing the catalog does not
  have and AGENTS.md's YAGNI rule forbids it without a second real extra.
- **Persisting the opt-in to auto-resume a killed install.** A silent
  re-download after process death is the opposite of opt-in; the System
  page's durable action is the recovery path.
- **Keeping the boot-time bridge gate.** Would freeze boot start for every
  user who declined the extra — a persisted negative consent encoded as a
  permanent skip.
- **Hiding the toolkits' files when the extra is absent.** See the
  provisioning-overlap section; the dependency is surfaced in UI instead of
  destabilizing the shared writer bundle.

## Consequences

- First install downloads only the core (rootfs + OpenSSH); the ~7 MB extra
  is a choice, in setup or later.
- The One-click install page performs its first real install; toolkit rows
  are honest about their prerequisite.
- Boot start and session start no longer depend on the optional extra.
- Install-only surfaces are covered by `ServicesExtraStateTest`,
  `DesktopHomeViewExtrasTest`, and the extended `InstallerLogTest` /
  `SetupPhasePolicyTest` / boot tests. Both device paths (first-run checkbox
  checked/unchecked and later System install) were exercised on the S10e;
  see OPT-001–005 in `docs/test-plan.md`.
