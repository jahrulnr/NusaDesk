# ADR-0047: Workspace folder picking — one in-app browser, shared storage on every API level

## Status

Accepted and implemented 2026-09-20: the workspace folder is chosen in an
in-app browser written in this repository
(`WorkspaceFolderBrowserDialog` + `WorkspaceFolderListing`), rooted at shared
storage on **every** API level. Android 11+ reaches it through the all-files
grant (ADR-0023); Android 10 reaches it through the platform's **legacy storage
model**, which the manifest opts into (`requestLegacyExternalStorage`) together
with the two runtime permissions bounded to API 29. Device-verified on the S10e
(API 31) and the S7 Edge (API 29); rows WS-001..WS-008 in `docs/test-plan.md`.

**The Android 10 measurement that settled this (S7 Edge, 2026-09-21).** The
first implementation assumed a shared folder could not be bound below API 30 and
used the app's own media folder instead. A raw-path probe from this app
(targetSdk 37) showed otherwise:

| Manifest state | `/sdcard` | `/sdcard/WhatsApp` | write |
| --- | --- | --- | --- |
| nothing | `list=null canRead=false` | `list=null canRead=false` | ✗ |
| `READ`/`WRITE_EXTERNAL_STORAGE` granted | `list=null canRead=false` | `list=null canRead=false` | ✗ |
| + `requestLegacyExternalStorage="true"` | `list=171 canRead=true` | `list=7 canRead=true` | ✓ |

So on an Android 10 *device* the legacy flag is still honoured for this
targetSdk — the "the system ignores the flag once you target Android 11" rule
bites on Android 11+ systems, not there — and the two runtime permissions are
the classic gate for raw paths. That is also how WhatsApp (targetSdk 36, both
grants held) keeps writing `/sdcard/WhatsApp` on the S7 while this app saw
nothing.

## Context

The workspace is the one folder the guest sees as `~/nusadesk` (ADR-0023). The
guest mount needs a **real host path**: PRoot binds a path, not a `content://`
tree. On Android 11+ this app holds all-files access for that bind; on Android
10 the same folder is reachable through the platform's legacy model (see the
table above). The app's own media folder — `Android/media/<pkg>/nusadesk` —
stays the default workspace, because it needs no permission at all and a file
manager or MTP can still reach it.

A third-party picker library was evaluated and rejected:

- `io.github.tutorialsandroid:filepicker:10.1.3` — maintained, Apache-2.0,
  pure Java, empty manifest, Maven Central — but its own `show()` gate accepts
  only `READ_EXTERNAL_STORAGE` below API 30 (which is now *not* a problem per
  se, but the library would own a permission decision this app makes itself),
  and its directory selection is a *marked* selection (the row's checkbox)
  rather than the folder in view, which is not the "use this folder" semantics
  this product wants. With `allow_manage_external_storage` it did open on
  Android 11+, and that path was verified on the S10e before the decision.
- `hedzr/android-file-chooser` (archived 2022; its own README says the project
  stopped to comply with Android 10+) and `spacecowboy/NoNonsense-FilePicker`
  (archived 2022) are unmaintained.

## Decision

1. **One in-app browser, written here.** `WorkspaceFolderBrowserDialog` is a
   platform dialog with a path header, a folder list, and
   Cancel / Use this folder. `WorkspaceFolderListing` owns the listing rules
   (directories only, name order, never above the root, an unlistable folder
   lists as empty) and is unit-tested on the JVM. No dependency, no third-party
   permission gate, and one implementation for every API level.
2. **Shared storage is the root on every API level.** `pickerRoot()` returns
   `Environment.getExternalStorageDirectory()`; the root is created when missing
   (`preparePickerRoot`), and the browser can never leave it. On Android 10 the
   app asks for `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` before it
   opens the browser; on Android 11+ without the all-files grant the card offers
   the settings trip instead.
3. **"Use this folder" means the folder in view**, and the result is still
   validated before it is stored: `pickedPathWorkspace` accepts only absolute
   paths without traversal segments that sit inside this app's own external
   directories or, with storage access in place (`hasAllFilesAccess()` on 11+,
   `hasLegacyStorageAccess()` on 10), inside shared storage; `isUsable` then
   requires a successful write probe. A previously stored SAF folder keeps
   working because usability is judged on the path now, not on the tree
   document id.
4. **The permissions stay bounded and explained.** All-files access remains the
   only broad grant on Android 11+; `READ`/`WRITE_EXTERNAL_STORAGE` are declared
   with `maxSdkVersion="29"` and the legacy flag is a no-op for this targetSdk
   on Android 11+ systems. No media permission is requested.
5. **The app's media folder is the default, not the limit.**
   `appFolderWorkspace()` still resolves `Android/media/<pkg>/nusadesk` for the
   card before the user picks anything — app-owned, bindable, and visible to a
   file manager and MTP.

## Consequences

- Android 11+: one dialog over shared storage; the picked path is bound as-is
  at the next session start.
- Android 10: the same dialog over the same tree, after one permission prompt;
  `/sdcard/Download`, `/sdcard/WhatsApp`, or any folder the app can read and
  write is a valid workspace. Measured: with the legacy model in place this app
  lists 171 entries in `/sdcard` and can create a file there.
- The storage guard changed with this ADR: `WorkspaceStorageManifestTest` now
  pins the *bounded* legacy declaration (both permissions with
  `maxSdkVersion="29"`, the flag present, media permissions still absent)
  instead of forbidding it outright, and `BridgePermissionsManifestTest`'s
  allowlist carries the two permissions with that rationale.
- The browser shows no icons, sizes, or dates: the choice is a folder, and the
  roots are already constrained, so those columns would be noise.
- An empty folder can be chosen (the write probe still runs); a folder that
  disappears between listing and confirmation is reported, not bound.
- The system document picker is no longer part of the workspace path: the
  in-app browser returns a host path directly, so no SAF grant is involved in
  binding.
