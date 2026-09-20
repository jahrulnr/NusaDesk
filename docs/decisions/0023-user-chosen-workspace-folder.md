# ADR-0023: The Linux workspace is a user-chosen folder bound at ~/nusadesk

## Status

Accepted and implemented: `WorkspaceFolder`, `WorkspaceStore` and
`SharedPreferencesWorkspaceStore`, `WorkspaceFolderAccess`, `WorkspaceDirectory`,
the workspace card in `SystemScreenView`, the `MANAGE_EXTERNAL_STORAGE`
declaration, and the workspace bind in `ProotLauncher`.

Verified by unit and contract tests — path translation including every refusal,
the write probe, the four screen states, and the manifest — and by the
cross-API-level launch guard running the real Activity on API 29, 30, 31, and 33
(ADR-0022). Both device runs are recorded at the end of this document.

**Amended by ADR-0047 (picking a folder).** On Android 11+ the workspace is
chosen in a built-in path browser instead of the system document picker (the
guest needs a host path, and the app already holds all-files access), and on
Android 10 the app-owned workspace moved from
`Android/data/<pkg>/files/nusadesk` to `Android/media/<pkg>/nusadesk` so the
user can still reach and back it up. The bind contract itself — a real host
path, validated, write-probed, bound at `/root/nusadesk`, guest content wins —
is unchanged.

## Context

Tuan asked for the guest to have a workspace at `~/nusadesk`, backed by a folder
that is also visible from Android — `Documents/nusadesk` was the suggestion, with
an explicit instruction to check whether Android 11+ still allows that and to come
back with a recommendation if it does not.

Until this decision the product was fully isolated: the rootfs, session state, and
host keys live in app-private storage, and every non-system bind mount had to be
an app-private host path, enforced in `ProotLauncher` by `ProotPaths.isAppPrivatePath`.

The platform rules (checked against the Android documentation, September 2026):

- PRoot binds a **real host path**. A Storage Access Framework grant yields a
  content URI, so `ACTION_OPEN_DOCUMENT_TREE` alone can never be the workspace —
  it can only choose the folder.
- From Android 11 (API 30), an app may open a shared-storage path directly only
  with the special **all-files access** grant (`MANAGE_EXTERNAL_STORAGE`), which
  the user enables in Settings; `Environment.isExternalStorageManager()` reports
  whether it is held. `ACTION_OPEN_DOCUMENT_TREE` additionally refuses the volume
  root and `Download` on API 30+.
- On Android 10 (API 29) that permission does not exist, and
  `requestLegacyExternalStorage` is ignored because this app targets API 37, so
  scoped storage applies and no shared folder can be bound at all.

Options considered were: all-files access with a fixed `Documents/nusadesk`
path, a fixed app-specific external directory, keeping storage internal with SAF
export/import, and all-files access with a **user-chosen** folder. Tuan chose the
last one, and then asked for Android 10 to have a workspace as well.

Android 10 (API 29) has no all-files access at all — the permission arrives in
API 30 — and `requestLegacyExternalStorage` is ignored at target API 37, so a
shared folder cannot be bound there by any route. The one location that yields
both a real folder and a bindable path on that release is the app's own external
files folder, `Android/data/<package>/files`: it needs no permission, and the
restriction that hides `Android/data` from file managers and from
`ACTION_OPEN_DOCUMENT*` only begins with Android 11.

## Decision

1. **The folder is the user's choice, made in the system picker.**
   `WorkspaceFolderAccess` opens `ACTION_OPEN_DOCUMENT_TREE`, pre-positioned at
   `primary:Documents/nusadesk` (created by the product when it may), and takes
   the persistable grant so the choice survives restarts.

2. **A content URI becomes a host path in exactly one place.** `WorkspaceFolder`
   translates the tree document id — `primary:`, a removable `XXXX-XXXX` volume,
   or `raw:` — into an absolute path, and refuses everything else: cloud
   providers, malformed ids, an absolute "relative" path, `..`, empty segments,
   and a stored pair whose parts no longer agree. A refusal means no workspace
   instead of a guessed mount, which is why the class carries the test that pins
   every one of those refusals.

3. **All-files access is asked for, never assumed.** The workspace card states
   why Android 11+ needs the grant and offers exactly one action; below API 30 it
   states the limitation instead of offering a picker that could not work
   (`WorkspaceUiState`, four states). Because the check is a probe on a visible
   screen, `hasAllFilesAccess()` degrades to "not granted" instead of crashing if
   the platform reports incomplete state.

4. **A folder must prove it is usable.** `WorkspaceDirectory` creates the
   directory when missing and writes a uniquely named probe file into it before
   the folder counts as usable, then removes the probe. A read-only, missing, or
   blocked folder therefore fails where the user can still act on it, not later
   inside the guest.

5. **The bind is fixed and narrow.** `ProotLauncher` adds one bind — the stored
   folder to `/root/nusadesk` (`ENV_HOME` is `/root`, so the user sees
   `~/nusadesk`) — and only when a choice is stored and `isUsable` passes.
   Caller-supplied `extraBinds` keep their app-private-only rule, so the workspace
   cannot be used to smuggle an arbitrary host path into the guest.

6. **The permission is declared with its reason.** The manifest records why it
   exists and that it is asked for, lint's `ScopedStorage` check is disabled in
   `build.gradle` with that decision written down, and
   `WorkspaceStorageManifestTest` pins both the declaration and the absence of the
   legacy broad-storage permissions.

7. **Android 10 gets a workspace too, from the app's own external folder.** Where
   the platform cannot bind a shared folder at all, `WorkspaceFolderAccess`
   produces `Android/data/<package>/files/nusadesk` instead — no permission, a
   real path PRoot can bind, and a folder a file manager can still reach on that
   release. The card names the folder it actually uses (`App folder` plus the full
   path) rather than either inviting a pick that could not work or claiming no
   workspace exists. Nothing is stored for this case: it is derived, not chosen.

## Consequences

- Every supported Android release gets a workspace: a folder the user picks on
  Android 11+, and the app's own external folder on Android 10, where no shared
  folder can be bound. The exact folder is named on the screen in both cases.
- On Android 11+ the product requests one broad permission. It is tied to a
  visible explanation, nothing is bound without it, and the guest keeps working
  without a workspace when it is missing. Publishing on Google Play would require
  the all-files access declaration form and an approved use case; today only the
  signed GitHub release APK is distributed.
- Changing the folder takes effect at the next Linux start, because the bind is
  part of the launch spec and the product deliberately has no session restart
  control (ADR-0013).
- A folder that is deleted or becomes read-only while Linux runs simply stops
  being bound on the next start; the guest then has no workspace directory rather
  than a broken one.

## Alternatives considered

- **Fixed `Documents/nusadesk` with all-files access.** Simplest UI, but it
  hard-codes a location the user cannot move (SD card, a project folder) and
  creates a directory without being asked. Rejected in favour of the picker, with
  the same folder as the picker's starting point — so the suggested path is still
  one confirmation away.
- **App-specific external directory** (`Android/data/<pkg>/files/nusadesk`): not
  adopted as the *whole* answer, because Android 11+ hides that directory from
  file managers, which fails the "editable from Android" half of the goal there.
  It is however exactly what Android 10 uses (decision 7), since that release
  still exposes the directory and no shared folder can be bound at all.
- **Internal workspace plus SAF export/import.** No broad permission, but files
  are copied one at a time; that is an export feature, not a workspace.
- **`requestLegacyExternalStorage`.** Ignored at target API 37, and it would not
  help on Android 11+ in any case.
- **Shipping our own documents provider.** Would let other apps browse the guest
  rootfs over SAF — a far larger surface than the requested folder, and contrary
  to the app-private rootfs boundary.

## Device verification

**Android 10 (Galaxy S7 Edge, API 29) — verified.** This is the release with no
all-files access, so it exercises the derived app-folder workspace end to end:

- the workspace card reads *App folder* with the detail "Android 10 cannot bind a
  shared folder, so Linux uses this app's own folder:
  /storage/emulated/0/Android/data/gh.nusashell.nusadesk/files/nusadesk. A file
  manager can still reach it on this Android version." and offers no action, which
  is the intended state for this platform;
- the supervised runtime's PRoot argv carries exactly one workspace bind,
  `-b /storage/emulated/0/Android/data/gh.nusashell.nusadesk/files/nusadesk:/root/nusadesk`
  (read from `/proc/<pid>/cmdline` of the live `libproot.so` process);
- Android → guest: a file written into that folder from the Android side is listed
  by `ls -la /root/nusadesk` inside the guest shell;
- guest → Android: `touch /root/nusadesk/from-linux.txt` inside the guest appears
  in the Android folder.

The two test files were removed afterwards, leaving the workspace folder empty.

**Android 12 (Galaxy S10e, API 31) — verified.** The whole pick flow ran on that
device against the build in this change:

- the card first read *Not chosen* with "A shared folder lives outside app
  storage, so Android 11+ requires all-files access. Nothing is bound until you
  allow it." and the single action *Allow all-files access*;
- that action opened the platform's all-files access screen. On this One UI build
  `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` resolves to the shared
  "Akses semua file" list instead of NusaDesk's own page, which is exactly the
  OEM variation the generic-screen fallback exists for; the list is still how the
  user grants it. The grant itself was then applied through the platform's
  documented test path (`appops set --uid … MANAGE_EXTERNAL_STORAGE allow`)
  rather than by walking the list;
- with the grant in place the card offered *Choose folder*;
- the picker opened **inside** `Documents/nusadesk`, and that folder had already
  been created by the product — the suggested-folder step, visible as a new
  directory in Documents;
- confirming stored the choice
  (`treeDocumentId=primary:Documents/nusadesk`,
  `hostPath=/storage/emulated/0/Documents/nusadesk`), and the card then read
  *Documents/nusadesk* with *Change folder*;
- after the runtime restarted, its PRoot argv carried exactly
  `-b /storage/emulated/0/Documents/nusadesk:/root/nusadesk`;
- Android → guest: a file written into `Documents/nusadesk` is listed by
  `ls -la /root/nusadesk` in the guest shell;
- guest → Android: `touch /root/nusadesk/from-linux.txt` in the guest appears in
  `Documents/nusadesk`.

The two test files were removed afterwards, leaving the chosen folder as the
user left it.
