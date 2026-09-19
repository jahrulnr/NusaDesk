# ADR-0036: Termux command compatibility for the guest

- Status: accepted
- Date: 2026-09-19

## Context

NusaDesk's guest is a Linux environment, and the usual way people drive
Android from a Linux environment is the Termux:API command-line clients
(`termux-battery-status`, `termux-location`, `termux-sms-list`, ...). Those
clients talk to the Termux:API Android app, and that app cannot serve this
product by construction:

- `termux-api`'s manifest declares `android:sharedUserId="com.termux"` and the
  project requires it to be signed with the same key as the Termux app; only
  callers sharing that UID may invoke its methods.
- `TermuxApiReceiver` is `android:exported="false"`, and the app's
  `LocalServerSocket` (`com.termux.api://listen`) rejects any connection whose
  peer UID is not the app's own UID.
- The client hardcodes `com.termux.api/.TermuxApiReceiver`, so a clean copy of
  the client cannot be redirected at another app without patching it, and even
  a patched client would be rejected.

The client package itself is MIT-licensed and is only an interface contract:
command name, flags, and a JSON document on stdout. NusaDesk already owns an
authenticated loopback capability bridge (ADR-0030) that answers the same
questions.

## Decision

1. Install a bounded set of Termux command names into the guest's
   `/usr/local/bin`: `termux-battery-status`, `termux-location`,
   `termux-sensor`, `termux-contact-list`, `termux-sms-list`,
   `termux-telephony-deviceinfo`, `termux-telephony-cellinfo`.
2. Each command is a generated Python 3 script owned by the app (like every
   other generated guest file), translating the Termux flags into the fixed
   bridge method and printing the Termux JSON shape: Termux key names, Termux
   enum spelling (`CHARGING`, `PLUGGED_USB`), one document per sensor sample
   (`{SENSOR: {values: [x, y, z]}}`, like Termux's per-event stream), and,
   where Termux has one, the `received` timestamp in the Termux
   `yyyy-MM-dd HH:mm:ss` format for `termux-sms-list`. Exit codes follow the
   Termux contract: `0` result, `1` typed bridge error, `2` usage, malformed
   payload, or transport failure.
3. Only methods the bridge actually answers are installed, and each command
   documents the exact field subset it emits. Commands that need the Termux
   app, a side effect the bridge does not expose, or a capability outside the
   allowlist stay absent, and the generated `docs/termux-compat.md` lists them
   explicitly. Camera and microphone remain the live media session (ADR-0031),
   not a photo/record file command.
4. The bridge contract is unchanged: same envelope, same token, same fixed
   methods, no guest-supplied method name, and the compatibility scripts send
   no `params` at all (they call only param-free methods).
5. Bounded by construction: at most 50 SMS rows, 50 contacts (each with up to
   5 numbers, matching Termux's row shape), 10 cell rows, and 10 sensor
   samples per invocation; `termux-sms-list -l/-o` narrows within that cap. A
   truncated bridge reading is reported on stderr while the valid rows still
   print. Unsupported flags fail closed with usage instead of being ignored,
   and a script this writer no longer declares is swept from the guest bin
   directory so a shrinking command set cannot leave a stale executable.

## Consequences

- Scripts and tools written against the Termux clients keep working inside the
  NusaDesk guest without changes, and no Termux app is required or contacted.
- The compatibility layer is documentation-driven: the installed command set,
  the omitted commands, the row caps, and the exit codes are all stated in
  `docs/termux-compat.md`, generated with the rest of the guest docs.
- The scripts are extra generated files in `/usr/local/bin`; an app update
  refreshes them and overwrites manual edits, like the rest of the managed
  bundle.
- Because the Termux JSON shape is emulated from bounded readings, some fields
  are best-effort: `termux-location` always reports one foreground fix
  (`-r updates` warns and returns that fix) and omits fields the bridge did not
  return; `termux-sms-list` derives `received` from the bridge's epoch millis,
  so it is exact to the second but rendered in the guest's timezone; and
  `termux-sensor` samples are sequential one-shot reads rather than a
  continuous stream. Each command's emitted field subset is documented, and
  absent fields are omitted rather than fabricated.
- Adding a command later means adding a bridge method first, then the script
  mapping; the allowlist stays the single source of truth for what Android
  surface the guest can reach.

## Device evidence

Device run on the Samsung S10e (SM-G970F, Android 12/API 31), 2026-09-19, in
a real PRoot guest session under the production environment contract
(`PROOT_LOADER`, `PROOT_TMP_DIR`, fixed `PATH`, the service overlay bound at
`/opt/lw-services`, and the live bridge env file at
`/run/nusadesk/android-bridge.env`):

| command | observed |
| --- | --- |
| `termux-battery-status` | `{"health":"GOOD","percentage":84,"plugged":"PLUGGED_USB","status":"NOT_CHARGING","temperature":31.4,"current":5,"voltage":4091}` (rc 0) |
| `termux-contact-list` | bounded provider rows in the Termux `{name, number}` shape (rc 0) |
| `termux-telephony-deviceinfo` | `{"phone_type":"gsm","sim_state":"absent","network_type":"unknown"}` (rc 0) |
| `termux-sms-list -l 2` | `[]` (rc 0; the device's inbox is empty) |
| `termux-location -p wifi` | `termux-location: unsupported provider: wifi`, rc 2 (fail-closed usage) |
| `termux-sensor -s accelerometer -n 2` | one JSON document per sample, flat `values`, rc 0 |
| `termux-telephony-cellinfo` | bounded cell rows, `{"type":"gsm","dbm":-51,"level":4}` (rc 0) |
| `nusadesk-android bridge info` | unchanged; the full capability list (rc 0) |

Not yet verified on device: `termux-location` against a real fix (the run had
no granted location permission) and `termux-sms-list` with a non-empty inbox.
The generated commands were additionally reviewed inside the guest in two
adversarial passes; every defect those passes found (sensor output shape and
fabricated zeros, missing `truncated` warning, tracebacks on malformed
payloads, dropped unnamed-contact numbers, undisclosed thin field subsets, the
stale-script sweep TOCTOU, and version-guard input) is fixed in the same
change.
