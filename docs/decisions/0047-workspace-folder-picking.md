# ADR-0047: Workspace folder picking — one in-app browser, per-API roots

## Status

Accepted and implemented 2026-09-20: the workspace folder is chosen in an
in-app browser written in this repository
(`WorkspaceFolderBrowserDialog` + `WorkspaceFolderListing`), rooted at shared
storage on Android 11+ and at the app's own media folder on Android 10. The
Android 10 workspace also moves from `Android/data/<pkg>/files/nusadesk` to
`Android/media/<pkg>/nusadesk`. Device-verified on the S10e (API 31) and the
S7 Edge (API 29); rows WS-001..WS-007 in `docs/test-plan.md`, including the
session bind carrying the picked path.

## Context

The workspace is the one folder the guest sees as `~/nusadesk` (ADR-0023). The
guest mount needs a **real host path**: PRoot binds a path, not a
`content://` tree. Android only lets this app open a shared-storage path when
the user granted all-files access, and that grant starts at API 30. On Android
10, with this app targeting API 37, scoped storage applies and no opt-out
exists (`requestLegacyExternalStorage` is ignored for targetSdk ≥ 30), so a
user-picked shared folder can never be bound there — the card said so and the
app used its own external files folder instead.

That fallback had two problems: it lived in `Android/data/<pkg>/files/`, which
file managers and MTP hide on Android 11+ and which is awkward to back up by
hand on Android 10 — and the guest backup deliberately excludes the workspace
bind, so the user has to copy that folder out themselves. Meanwhile the system
document picker was an odd fit even on Android 11+: it exists to hand out
`content://` grants, while this app needs a path it already has the right to
open.

A third-party path-based picker was evaluated and rejected:

- `io.github.tutorialsandroid:filepicker:10.1.3` — maintained, Apache-2.0,
  pure Java, empty manifest, Maven Central — but its own `show()` gate accepts
  only `READ_EXTERNAL_STORAGE` below API 30 (a broader grant than this feature
  needs, which `WorkspaceStorageManifestTest` forbids), and its directory
  selection is a *marked* selection (the row's checkbox) rather than the folder
  in view, which is not the "use this folder" semantics this product wants.
  With `allow_manage_external_storage` it did open on Android 11+, and that
  path was verified on the S10e before the decision.
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
2. **Per-API roots.** `WorkspaceFolderAccess.pickerRoot()` returns shared
   storage (`Environment.getExternalStorageDirectory()`) on Android 11+ and the
   app's own media folder on Android 10; the root is created when missing
   (`preparePickerRoot`). The browser can never leave the root it is given.
3. **"Use this folder" means the folder in view**, and the result is still
   validated before it is stored: `pickedPathWorkspace` accepts only absolute
   paths without traversal segments that sit inside this app's own external
   directories or (on API 30+) inside shared storage, and `isUsable` requires
   the all-files grant where the path needs it plus a successful write probe.
   A previously stored SAF folder keeps working because usability is judged on
   the path now, not on the tree document id.
4. **The all-files grant stays the only broad permission.** On Android 11+
   without it, the card offers the settings trip instead of the browser
   (unchanged from ADR-0023). Nothing here asks for `READ_EXTERNAL_STORAGE`,
   `WRITE_EXTERNAL_STORAGE`, or any media permission.
5. **Android 10 uses the app's media folder.** `appFolderWorkspace()` resolves
   `Android/media/<pkg>/nusadesk`: app-owned, bindable, and — unlike
   `Android/data` — still visible to file managers and MTP, so the one folder a
   guest backup excludes is one the user can copy out. The card names it and
   offers the browser, which can only ever walk that tree.

## Consequences

- Android 11+: one dialog over shared storage; the picked path is bound as-is
  at the next session start.
- Android 10: the workspace is a folder inside `Android/media/<pkg>`; the user
  can pick the folder itself or any subfolder, and can reach the same folder
  from a file manager or over USB.
- The browser shows no icons, sizes, or dates: the choice is a folder, and the
  roots are already constrained, so those columns would be noise.
- An empty folder can be chosen (the write probe still runs); a folder that
  disappears between listing and confirmation is reported, not bound.
- The system document picker is no longer part of the workspace path: the
  in-app browser returns a host path directly, so no SAF grant is involved in
  binding. A SAF copy action — exporting the workspace to a folder the app
  cannot bind — was considered for Android 10 and deliberately not built: that
  workspace lives in the app's media tree, which a file manager and MTP already
  reach, so the picker alone closes the "hard to back up" gap.
