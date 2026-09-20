# ADR-0047: Workspace folder picking — built-in picker on 11+, app media folder on 10

## Status

Accepted and implemented 2026-09-20: the built-in picker (the vetted FilePicker
library) replaces the system document picker for choosing the workspace on
Android 11+, and the Android 10 workspace moves from
`Android/data/<pkg>/files/nusadesk` to `Android/media/<pkg>/nusadesk`.
Device-verified on the S10e (API 31): the dialog opens with this product's
title, browses `/storage/emulated/0`, and navigates into folders.

**Open decision (Android 10 picker).** The built-in picker cannot run on
Android 10 without widening this app's storage grants: the library gates its own
`show()` on storage access and, below API 30, only accepts
`READ_EXTERNAL_STORAGE` — the all-files grant that satisfies it on Android 11+
does not exist there. Declaring that permission is exactly what
`WorkspaceStorageManifestTest` forbids, because it is a broader grant than the
feature needs. Three ways forward, none taken yet:

1. hand-roll a small browser (path list over the app's media tree) for Android
   10 — one extra view, no new grant, keeps the guard intact;
2. hand-roll the browser for every API level and drop the library — one
   implementation, no dependency, no grant;
3. amend the storage guard by ADR and ask for `READ_EXTERNAL_STORAGE` with
   `maxSdkVersion="29"` — the smallest widening that makes the library work
   everywhere.

## Context

The workspace is the one folder the guest sees as `~/nusadesk` (ADR-0023). The
guest mount needs a **real host path**: PRoot binds a path, not a
`content://` tree. Android only lets this app open a shared-storage path when
the user granted all-files access, and that grant starts at API 30. On Android
10, with this app targeting API 37, scoped storage applies and no opt-out
exists (`requestLegacyExternalStorage` is ignored for targetSdk ≥ 30), so a
user-picked shared folder can never be bound there — the previous ADR-0023
implementation said so in the card text and used the app's own external files
folder instead.

Two problems with that fallback: it lives in `Android/data/<pkg>/files/`, which
file managers and MTP hide on Android 11+ and which is awkward to back up by
hand on Android 10 — and the guest backup deliberately excludes the workspace
bind, so the user has to copy that folder out themselves. Meanwhile the system
document picker was an odd fit even on Android 11+: it exists to hand out
`content://` grants, while this app needs a path it already has the right to
open.

## Decision

1. **A path-based built-in picker on Android 11+.** The workspace action opens
   `WorkspaceFolderPickerDialog`, which wraps the vetted FilePicker library
   (`io.github.tutorialsandroid:filepicker`, pinned 10.1.3; Apache-2.0, pure
   Java, empty manifest, Maven Central) rooted at
   `Environment.getExternalStorageDirectory()`. The app already holds
   all-files access there, so raw paths are exactly its capability — no
   `content://` indirection and no separate grant translation. The library's
   own gate is told to treat that grant as its access
   (`DialogProperties.allow_manage_external_storage`), and a missing grant
   answers with the settings trip instead of a silent no-op.
2. **The chosen path is validated before it is stored.** The picker returns a
   path; `WorkspaceFolderAccess.pickedPathWorkspace` accepts only absolute
   paths without traversal segments that sit inside shared storage on API 30+,
   and `isUsable` additionally requires the grant and a successful write probe.
   A path that fails any gate is reported, never bound.
3. **Android 10 uses the app's media folder.**
   `WorkspaceFolderAccess.appFolderWorkspace()` now resolves
   `Android/media/<pkg>/nusadesk`: it needs no permission, PRoot can bind it,
   and unlike `Android/data` it stays visible to file managers and MTP — so the
   folder the guest backup excludes is at least one the user can copy out.
   The card says so in the same words the state renders.
4. **Android 10 offers no picker yet**, because every option above either
   widens the storage grant or needs a second browser implementation; the
   state renders no action rather than one it cannot honour.
5. **The system document picker stays for the copy actions** (the SAF tree
   picker and its persisted grant), which remain the only way to reach a
   folder the app cannot bind — including any future import/export of the
   workspace on Android 10.

## Consequences

- On Android 11+ the workspace is chosen in-app: one dialog, the folder list,
  and the folder is bound as-is at the next session start. The stored
  `treeDocumentId` stays `picked-path`, and a previously stored SAF folder
  keeps working because `isUsable` now judges the path, not the id.
- The library's directory selection is a *marked* selection (the row's
  checkbox) rather than "the folder you are standing in"; the dialog's
  positive button returns the marked folder. Verified from the library's
  source; the interaction is recorded in the test plan as device-checked for
  the dialog itself and pending for the marking gesture.
- Android 10 gains reachability, not choice: the workspace folder is
  `Android/media/<pkg>/nusadesk`, and the card names it.
- A dependency joins the build: pinned exactly, with its vetting rationale
  next to it in `app/build.gradle`, and its transitive `kotlin-stdlib`
  excluded (the Android Gradle plugin puts its own on the classpath anyway).
- `WorkspaceFolderAccess.pickerRoot()` still returns the app's media tree below
  API 30 and is unit-tested, so whichever Android 10 option is chosen next
  starts from a tested root policy.
