# ADR-0037: Opt-in boot start for the Linux session

## Status

Accepted and implemented in `infrastructure/boot/` plus the coordinator wiring
(`BootAutostartPreferences`, `BootStartReceiver`, the manifest, and the
amended manifest guard). Unit evidence is in place; real-device verification
of boot delivery, FGS promotion, `MY_PACKAGE_REPLACED`, and OEM behavior is
open work recorded in `docs/test-plan.md`.

## Context

ADR-0013 drew the autostart boundary at "no background start": Linux exists
only after a user-visible launch, with no boot receiver, boot permission,
job, alarm, or sticky-restart path, and `RuntimeAutostartManifestTest`
guards that at the manifest level. The accepted product decision of
2026-09-20 amends it in exactly one place: users may enable **Start Linux at
boot** on the Linux system screen so their session comes up after a reboot
(or after an app update) without opening the app first — but it is the
user's own opt-in, default OFF, never a silent always-on.

The platform facts that shape the design (verified against Android
documentation, not re-measured here):

- Starting a foreground service from a `BOOT_COMPLETED` receiver is an
  explicit exemption from the Android 12+ background-start restrictions
  (developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
  so `context.startForegroundService` is legal from this trigger.
- Android 15 blocks a fixed list of FGS *types* from being started at boot —
  `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`,
  `microphone` — and the host's declared `specialUse` type is not among
  them.
- `RuntimeHostService` promotes itself to foreground immediately in
  `onStartCommand` before any action handling, so the FGS contract is met
  regardless of which intent woke it.
- `MY_PACKAGE_REPLACED` matters independently of boot: installing an update
  kills the runtime process, and this broadcast (delivered only to the
  replaced package, needing no permission) is the platform's own way to
  learn the app is back.
- `RECEIVE_BOOT_COMPLETED` is a normal install-time permission — no runtime
  grant, no special access.
- `LOCKED_BOOT_COMPLETED` is useless here: before the first unlock,
  credential-encrypted storage is unavailable, and every persisted signal
  the start decision reads lives there.

The OEM reality also shapes it: boot delivery and foreground-service
survival are best-effort. On stock Android a force-stopped app receives no
`BOOT_COMPLETED`; in the 2026-09-20 device run the Samsung S10e (OneUI 4.x,
API 31) **delivered it anyway** to an app with `stopped=true` — on that OEM,
force-stop does not keep the session off across a reboot when boot start is
on (the reliable off is the toggle). MIUI gates the broadcast behind a
separate Autostart switch, and Samsung sleeping-apps/battery policy can
still kill the service after it starts. This slice therefore promises a
best-effort convenience, not an always-on daemon.

## Decision

1. **One opt-in setting, default OFF, single source.** "Start Linux at boot"
   lives on the Linux system screen (coordinator wiring) and persists
   through `BootAutostartPreferences` — its own `boot_autostart`
   SharedPreferences file, `enabled()` defaulting to `false`. Nothing else
   writes it, so a stored `true` is always the user's own choice.

2. **One unexported receiver, two actions, nothing else.**
   `infrastructure/boot/BootStartReceiver` is declared
   `android:exported="false"`, not `directBootAware`, with a filter of
   exactly `android.intent.action.BOOT_COMPLETED` and
   `android.intent.action.MY_PACKAGE_REPLACED`. Both are protected
   broadcasts only the system can send. `LOCKED_BOOT_COMPLETED` is never
   requested. The manifest guard test is amended to pin this exact shape —
   one receiver, two actions — while keeping the job/alarm/exported-service
   prohibitions.

3. **A pure policy owns the decision, on persisted truth only.**
   `domain/boot/BootAutostartPolicy.decide(optedIn, payloadReady,
   sshAddonPresent, serviceBridgePresent)` returns a typed
   `BootAutostartDecision` (`START`, `SKIP_OPT_OUT`,
   `SKIP_PAYLOAD_NOT_READY`, `SKIP_SSH_ADDON_MISSING`,
   `SKIP_BRIDGE_NOT_SETTLED`). The receiver logs the decision and the skip
   reason; a missing payload is a skip, never a repair — the boot path
   never installs, downloads, or schedules anything.

   The gate inputs mirror `MainActivity.ensureRuntimeRunning`, but only
   through disk-readable signals (presentation state is unreachable from a
   receiver):

   - *payloadReady* — `AndroidRuntimeStateStore.load(appId)` reconciled by
     `RuntimeSnapshotReconciler` equals `RuntimeState.READY`. The store
     already fails a `READY` record whose active rootfs files are missing
     (`activeRootfsExists`), and the reconciler turns an interrupted
     install into an honest `FAILED`.
   - *sshAddonPresent* — `GuestSshDaemon.detect(activeRootfs,
     activeAddon(guest-ssh-openssh)) != null`, the same detection the
     launcher's "terminal component installed" state uses.
   - *serviceBridgePresent* — `GuestServiceBridge.detect(
     activeAddon(guest-service-bridge)) != null`, which re-verifies every
     vendored file against its pinned digest.

   The bridge gate is deliberately **stricter** than the in-app one: the
   launcher also treats a *failed* bridge install as settled (SSH works
   without it), but add-on install outcomes are never persisted
   (`AndroidGuestAddonInstaller.publish` reports to the listener only), so
   at boot "absent" cannot be told apart from "failed". Starting anyway
   would open a session that can never run the service manager — and the
   idempotent boundary would then leave that live session alone. An absent
   bridge is therefore `SKIP_BRIDGE_NOT_SETTLED`; the next app-visible
   launch runs the normal pipeline.

4. **START reuses the existing idempotent boundary.** On `START` the
   receiver calls `RuntimeHostService.ensureRunning(applicationContext)`,
   which sends the existing `ACTION_ENSURE_RUNNING` intent via
   `startForegroundService`. The runtime identity still comes from
   `CuratedRuntimeCatalog`, a live session is still left alone, and the
   user-visible notification with its Stop action is unchanged — the
   boot-started session is exactly as visible and stoppable as a
   launch-started one.

5. **Nothing can crash or wedge at boot.** A gate that throws is a logged
   skip; a platform that refuses `startForegroundService` (an OEM
   restriction the exemption does not cover) is a logged skip. Gate probing
   is lazy: the opt-in is checked first, so the default-OFF install does
   zero disk work on every boot — no `READY` re-verification and none of
   the bridge overlay's digest hashing.

Rejected alternatives:

- **Default ON (opt-out).** A background start the user never asked for is
  precisely what ADR-0013 forbids, and an opt-out buried in a settings
  screen is consent in name only. The toggle is offered, not assumed.
- **`LOCKED_BOOT_COMPLETED` / direct boot.** Before the first unlock the
  runtime state store, the overlays, and the host-key material are all
  unreachable — the gate could only ever answer "skip". Listening earlier
  buys nothing and adds a second code path.
- **JobScheduler / exact alarms / WorkManager.** A scheduler would still
  run the same persisted gate and then make the same
  `startForegroundService` call — it only adds permissions, a second
  wake-up surface to guard, and OEM-deferred timing, for work the two
  broadcasts already cover.
- **Automatic battery-optimization exemption.** Boot delivery improves when
  the app is exempt, but a special-access grant stays the user's own
  Settings-side decision; the battery-optimization recommendation card on
  the system screen (wired alongside this slice, with the update check
  decided in ADR-0038) asks explicitly instead. Automatic exemption stays
  on the unsupported list.
- **Persist the bridge install outcome so "failed" can start.** A new
  write-path contract across the pipeline just to relax one skip — and a
  stale "failed" record could outlive a fixed payload. Absence ⇒ skip is
  the honest rule.

## Consequences

- A user who opts in gets the session back after a reboot and after an app
  update without opening the app; a user who never does sees zero change
  and zero boot work.
- Honest limits stand: stock Android does not deliver `BOOT_COMPLETED` to a
  force-stopped app, but the tested Samsung S10e (OneUI 4.x, API 31) did
  deliver it to a `stopped=true` app in the 2026-09-20 run, so force-stop
  does not reliably keep the session off across a reboot there — the toggle
  is the reliable off. MIUI's separate Autostart switch can suppress
  delivery, and OEM battery policy can kill the foreground service after it
  starts — the path is best-effort, and `docs/limitations.md` says so. The
  battery-optimization
  recommendation card is the mitigation, not a guarantee.
- The bridge asymmetry is documented behavior, not a bug: a failed bridge
  install lets an in-app session start but keeps boot start off until the
  bridge lands — consistent with "a boot session must be a full session".
- `RECEIVE_BOOT_COMPLETED` appears once in the manifest and in the
  reviewed permission allow-list; the receiver and its exact filter are
  pinned by `RuntimeAutostartManifestTest`, so any second wake-up path
  fails the build.
- JVM evidence: the policy truth table, the prefs default/round-trip, the
  receiver's trigger→intent wiring (including the real disk probe failing
  closed on a fresh device), and the amended manifest guard. Device
  verification — actual `BOOT_COMPLETED` delivery, FGS promotion timing on
  API 31+, `MY_PACKAGE_REPLACED` across a real update, and OEM boot
  behavior — is open work for `docs/test-plan.md`; a unit-test-only result
  is insufficient for lifecycle behavior.
