# ADR-0049: Full Termux:API parity surface for the guest

- Status: accepted
- Date: 2026-09-22

## Context

ADR-0036 installed seven `termux-*` commands (battery, location, sensor,
contact-list, sms-list, telephony-deviceinfo, telephony-cellinfo) and
deliberately left the rest absent. The user's requirement for this change is
the opposite: the guest Linux environment must reach **every** Android
capability the device can legally expose, through the bridge and through
`termux-*` clients, with real device evidence for each command rather than a
documented absence.

The upstream Termux:API app cannot serve this product (ADR-0036,
`G-termux-compat-signature-lock`): it is UID/signature-locked to Termux. What is
portable is the client contract: the MIT-licensed client scripts
(`termux-api-package`, 57 commands) define the command names, flags, JSON output
shapes, and exit codes that community scripts depend on.

## Decision

1. **Parity target.** Every upstream client command that the hardware and the
   platform permit is implemented as a generated guest script that translates
   flags into one bounded bridge method. Commands that cannot work are answered
   as typed, documented absences (`infrared-*` without an emitter,
   `wifi-enable` because API 29+ forbids third-party toggling), never as a fake
   success.
2. **Bridge v2 module registry.** The request handler keeps its built-in
   methods and dispatches everything else to registered `CapabilityModule`s.
   A module declares its methods, which of them accept a bounded `params`
   object, and returns one typed response per call. `CapabilityModules` is the
   single registration point, so capability domains stay independent.
3. **Bounded parameters.** `params` is a flat object (16 keys, key <= 32 chars,
   one string <= 8192 chars, frame <= 64 KiB). Each module validates with
   `CapabilityParams` (typed getters + `rejectUnknown`); a violation is the
   typed `invalid-argument` error. Bulk content travels as a file path, never
   inline.
4. **Permission model.** One method = one permission set, checked per call
   through `AndroidPermissionChecker` (`granted` / `denied` / `required` /
   `unsupported`, with the documented API used for special access).
   `bridge.permissions` publishes the whole state plus the Settings action that
   opens each missing grant; `permission.request` runs the user-visible consent
   (runtime dialog or Settings screen) through the foreground host and returns
   the resulting granted/denied lists.
5. **Foreground host.** Platform APIs that need a visible app (consent dialogs,
   `dialog`, `fingerprint`, SAF pickers, share chooser, speech recognition, NFC
   reader mode) and APIs whose while-in-use state is required (camera,
   microphone) run through one transparent `CapabilityForegroundActivity` that
   the bridge launches and waits on with a bounded timeout. Refusals are typed
   `foreground-required` with the action that fixes it; a second concurrent
   operation is `foreground-busy`.
6. **Guest files cross the bridge by staging.** A method that reads or writes a
   guest file receives a path inside the active rootfs (guest `/tmp`), resolved
   and validated by `GuestFilePathResolver`; the script moves the result to the
   user's destination, because only the guest knows how a bind mount (the
   workspace folder) maps. `SYSTEM_ALERT_WINDOW` is what makes a background
   bridge call able to open the foreground host at all.
7. **Guest documentation is generated with the commands.** `docs/termux-compat.md`
   lists every installed command, its flags, the field subset it emits, and the
   deliberate absences; the command catalogue is the single source of truth for
   the script set, the docs, and the stale-script sweep.
8. **Device evidence is the acceptance gate.** A command counts as working only
   with recorded output from the physical device. Unit tests cover pure logic;
   they never substitute for the device run.

## Consequences

- Community scripts written against `termux-*` keep working in the NusaDesk
  guest without the Termux app; the bridge remains the only Android surface, and
  the allowlist stays the single source of truth for what the guest can reach.
- The licence split stays explicit: the client package (MIT) is the contract
  reference; the Termux:API app (GPL-3.0) is read for behaviour only.
- Some Termux behaviours are emulated from bounded readings and are documented
  as such: `termux-location` returns one fix (with a `stale` marker when it is a
  last-known fix), `termux-sensor` samples are sequential one-shot reads, and
  row-returning commands cap their rows and report truncation on stderr.
- Special access is a user action, not a code path: overlay, all-files,
  write-settings, usage-stats, notification access, and battery exemption are
  opened through the platform Settings screens and reported honestly until
  granted.
- The generated scripts are app-owned files: an app update refreshes them and
  overwrites manual edits, like every other managed guest file.

## Device evidence

All 57 commands are installed and were exercised from the live guest on the
Samsung S10e (SM-G970F, Android 12/API 31, arm64) on 2026-09-22 (plus the
earlier 2026-09-19/2026-09-20 passes noted per row). Recorded per command in
`tasks/termux-parity-matrix.md` — the ledger: a row reads `DONE` only after its
output was observed on a device — with the run details in `docs/test-plan.md`.

That pass verified 50 commands including the three deliberate typed absences
(`termux-infrared-frequencies`/`termux-infrared-transmit` on a device with no IR
emitter, `termux-wifi-enable` against the API 29+ platform restriction) and the
`termux-usb` fd path (verified 2026-09-20 with a device attached). It also
produced the fixes recorded in the changelog: the media-player FD data source
replaced by a staging-path read, the share chooser's missing read grant and
MIME guess, the `termux-location` timeout/provider fix, and job-scheduler
script execution through the session's own SSH path.

Still not device-verified, honestly open in the ledger: `termux-sms-send` and
`termux-telephony-call` success paths (they need the SIM device),
`termux-speech-to-text` (a real voice input), `termux-fingerprint` (an enrolled
finger), `termux-nfc` tag read/write (a physical tag), `termux-media-scan`
beyond paths the media provider can read, and a recorded fresh
`termux-location` fix on the fixed build.
