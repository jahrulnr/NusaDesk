# ADR-0059: File upload from a hosted page — two sources, one answer

## Status

Accepted. Extends the web-app surface of ADR-0014/ADR-0048 with the missing
half of a page's file input: the platform file-chooser callback is answered now
instead of being left to fail silently.

**Amended 2026-10-06 after an independent review** (which ran before the first
device pass was complete). The review found that the built-in picker's
dismissal — its Cancel button, Back, and a touch outside — never reached the
answer path, so that one cancel left the page's input waiting; the dismissal is
now wired as the cancel it is, and the fix is device-verified (WA-042, second
pass). The same pass tightened the multi-type MIME rule to the platform's
documented `*/*` + `EXTRA_MIME_TYPES` combination, bound the accept entry
length, gated a system result on the request that really launched a picker,
answered a picker/window that refuses to start as a cancel, and dropped a
pending chooser when the WebView that owns it is destroyed.

Implemented in `domain/webapp/WebAppUploadRequest` (the pure request/filter
rules), `infrastructure/webapp/WebAppUploadIntents` (the system picker's
intent), `infrastructure/files/LocalFileListing` (the real-path listing rules),
and `presentation/webapp/` (`WebAppFileChooser`, `LocalFilePickerDialog`), wired
into `WebAppSurfaceView` and `MainActivity`. JVM and Robolectric tests cover the
intent shape, the result read, the source choice, and the built-in picker's
contract; the device run behind `docs/test-plan.md` WA-035…WA-042 covers the
end-to-end upload on the S10e.

## Context

A registered web app is allowed to render any form it likes, and a form with a
file field did nothing at all:

1. `WebChromeClient.onShowFileChooser` defaults to `false`. `WebAppSurfaceView`'s
   `SurfaceWebChromeClient` implemented window handling and title reporting
   only, so Chromium failed the request and the page's `<input type="file">`
   never completed — no picker, no error, and the next click on the same input
   was dead too.
2. The same callback is now also the File System Access entry point.
   Chromium shipped FSA for Android and WebView in M132 and, in WebView, keeps
   it disabled until an app's `targetSdkVersion` reaches the 2026 platform
   release (`ENABLE_FILE_SYSTEM_ACCESS`, `@EnabledAfter(targetSdkVersion = 35)`;
   this app targets 37). Apps that implement the callback therefore receive
   `showSaveFilePicker()` and `showDirectoryPicker()` through it, with two
   shapes the legacy contract never had: `MODE_OPEN_FOLDER` (API 37 constant
   `MODE_OPEN_FOLDER = 2`) and a permission mode (`getPermissionMode()`, API 37)
   that says whether the page intends to write.
3. Two hard facts about the platform shaped the implementation and were read
   from the provider's own source rather than guessed:
   - **`parseResult` drops a multi-selection.** The documented helper
     `WebChromeClient.FileChooserParams.parseResult(resultCode, data)` delegates
     to the provider, and Chromium's `AwContentsClient.parseFileChooserResult`
     reads `Intent.getData()` only. A `multiple` input's answer arrives as
     `ClipData`, so the helper returns `null` for the one case a multi-file
     input exists for.
   - **A returned URI is read with the app's own access.** Chromium converts
     the answer at the JNI boundary
     (`AwWebContentsDelegate::FilesSelectedInChooser`): a `file://` URI is
     turned into a real path with `net::FileURLToFilePath`, and anything else is
     taken as a path string. The renderer then reads through the browser
     process — this app — so a real path the app may read is a legal answer, and
     no document provider of our own is needed to serve one.
4. The product's files are not all inside a document provider. The guest's `/`
   is the app-private rootfs, the workspace bind is real shared storage, and
   PRoot binds real paths. A SAF-only picker can never return any of them, and a
   document provider's `content://` URI cannot be turned into a bindable path.

## Decision

1. **The surface answers the callback.** `WebAppSurfaceView` delegates
   `onShowFileChooser` to `WebAppFileChooser`. A request the surface can serve
   returns `true` and is answered exactly once; a request it cannot serve (no
   Activity to host a dialog) returns `false` and leaves the callback to the
   platform. Every cancel path answers `null`, which the platform documents as
   the cancel answer, because an unanswered callback leaves the page's input
   waiting and blocks every later file input: a cancelled or dismissed source
   chooser, a cancelled system picker, a **dismissed built-in picker** (its
   Cancel button, Back, and a touch outside are a dismissal, not a listener
   call), a picker that cannot be launched, and a window that cannot be shown at
   all (an activity that is finishing). A result is delivered only to the
   request that really launched a system picker, so a superseded request can
   never receive a stale pick.
2. **The intent mirrors the provider's own mapping.** For a read-only open,
   `ACTION_GET_CONTENT` + `CATEGORY_OPENABLE` (more providers answer it than
   `ACTION_OPEN_DOCUMENT`); for a writable open, `ACTION_OPEN_DOCUMENT` plus the
   write grant flag; for a save, `ACTION_CREATE_DOCUMENT` (with the page's
   filename hint as `EXTRA_TITLE`); for a folder, `ACTION_OPEN_DOCUMENT_TREE`
   with no filter; a multiple open adds `EXTRA_ALLOW_MULTIPLE`.
3. **The `accept` attribute becomes a MIME filter with a documented
   fallback.** Literal MIME types are kept, `.ext` entries resolve through the
   platform's own `MimeTypeMap`, and an entry that resolves to nothing is
   dropped. If nothing survives, the filter is `*/*` — no picker API can filter
   by an unknown extension, and filtering the wrong way would hide the file the
   user wants. Several surviving types keep the intent type at `*/*` and travel
   whole as `EXTRA_MIME_TYPES`: that is the combination the platform documents
   for disjoint types, and one that a picker intersecting type and extras cannot
   use to hide an accepted type (the provider's own app-facing helper sets the
   first type instead, which over-filters on such pickers). The page-controlled
   inputs are bounded: 16 accept entries of at most 128 characters each, trimmed
   and de-duplicated, and a filename hint longer than 128 characters is treated
   as absent.
4. **Two sources, chosen per request.** When a page asks for a file, the user
   picks where from, and the rows are a mark plus a plain name — never a scheme
   or a path (an end-user should not read `content://` or `/` to pick a file):
   - **Android files** — the system document picker, returning `content://`
     URIs owned by the provider the user chose.
   - **NusaDesk files** — the built-in browser, walking the real filesystem,
     returning `file://` URIs for the paths the app itself may read: the active
     rootfs behind the guest's `/`, the workspace's real storage, and everything
     else PRoot binds. An app may not list `/`, `/storage`, or `/data` at all
     (SELinux denies the app domain; verified on the S10e), so an unlistable
     directory states that plainly and offers the readable roots — Internal
     storage, Linux files (where the guest's `/` lives), and System files —
     the paths themselves appear only once the picker is open, in its path line.
   A folder request skips the choice: only the tree picker can express the
   document-tree URI that contract is built on.
5. **The camera is a source when a photo can satisfy the accept.** An
   image-accepting input — including one carrying `capture`, which is what that
   attribute means — also offers **Camera**. It shoots through
   `ACTION_IMAGE_CAPTURE` with `EXTRA_OUTPUT` set to a fresh file in the app
   cache, and the page is handed that file through `CameraCaptureProvider`: a
   non-exported provider with `grantUriPermissions="true"`, so the camera app
   reaches exactly the one URI the launch intent carries and nothing else, and
   it serves only files directly inside the cache's `camera/` directory. The
   `CAMERA` grant — declared by this app's manifest for the guest capability
   bridge, and required by the platform before it delivers a capture intent to
   an app that declares it — is asked for on first use; a refusal is answered
   like any other cancel. A capture that produced no bytes (the camera ran and
   wrote nothing) is a cancel too: the page is never handed an empty file. Old
   captures are swept when a later capture finds them older than a day.
   Recording is not offered; see the rejected alternatives.

6. **No grant is persisted.** The picker's grant lives as long as the activity,
   which is all a page's upload needs. Taking persistable permissions would
   accumulate permanent access to user files that nothing ever releases.
7. **A save into a real path exists before the page hears about it.** The
   built-in picker's new-file mode creates the file (or refuses, keeping the
   dialog open with the reason), the same thing `ACTION_CREATE_DOCUMENT` does
   for a document provider, because the page writes through the URI it is
   handed. A directory the app cannot list is stated as unreadable instead of
   being shown as an empty folder.
8. **The WebView boundary is unchanged.** No `setAllowFileAccess(true)`, no
   JavaScript interface, no new permission. The renderer reads only the URI the
   user picked; the page still never learns a path (its `File` object carries a
   name and bytes).
9. **The permission mode is read only where it exists.** `getPermissionMode()`
   is an API 37 method, so it is called behind a `SDK_INT >= 37` guard;
   everything older is read-only, which is what its file-chooser contract ever
   expressed.

Rejected alternatives:

- **Use `FileChooserParams.createIntent()` + `parseResult()`.** The documented
  pair is the shortest path, but `parseResult` cannot return a multi-selection
  (above), and `createIntent` cannot express the write flags, the filename hint,
  or the built-in source — and its decisions would be untestable. Its action
  mapping is mirrored instead, which keeps the shapes the platform expects.
- **Answer `<input capture>` with a camera of our own** (a `FileProvider`
  dependency plus an in-app capture UI). Rejected in favour of the platform
  camera plus a small in-repo provider: this repo already prefers one in-repo
  implementation over a dependency it does not otherwise need, the camera app is
  what users know, and this way a capture is one intent plus one cache file.
- **Offer recording (`ACTION_VIDEO_CAPTURE`) next to the still camera.**
  Rejected on evidence: with an app-owned `EXTRA_OUTPUT`, the device-tested
  Samsung camcorder (S10e, Android 12) wrote **zero bytes** into the target,
  saved nothing of its own, returned no URI, and the page saw only a cancel — a
  `Video` row would be a control that cannot work there. A `MediaStore` target
  (a recording that lands in the user's gallery) or an in-app recorder is the
  path to revisit if recording is ever really needed.
- **Register a `DocumentsProvider` and expose app-private paths as
  `content://`.** A provider is a full read/write contract (query, insert,
  delete, tree semantics) and would exist to serve one picker. `file://` is
  accepted by the provider's own conversion and needs none of it.
- **Copy every picked file into app storage and hand over a provider URI.**
  Silently duplicates user data and doubles storage for large files.
- **Persist the picked grants.** See decision 5.
- **One source only (system, or built-in).** The system picker cannot reach the
  files this product exists for; the built-in picker cannot search providers,
  reach cloud documents, or hand out a tree URI. Each is the honest tool for one
  half, so the user chooses per request.

## Consequences

- **A page's file input works on every surface**: open, open-multiple, and — on
  the 2026 platform release — save and folder requests. Before this change none
  of them did.
- **The camera works end to end.** Device-verified on the S10e: the first
  `Camera` tap raised the platform's `CAMERA` prompt, the camera app shot into
  the app's cache file through the provider, the page read a 2.5 MB JPEG from
  that URI, and `send-all` posted bytes whose SHA-256 equals the cache file's
  digest. A later capture in the same session behaved the same, on the build
  that shipped.
- **Recording is not offered,** and that limit is a device result rather than an
  assumption (see the rejected alternatives); `docs/test-plan.md` records it.
- **Evidence boundary.** The device run (S10e, Android 12/API 31; WA-035…WA-046
  in `docs/test-plan.md`) verifies the reachable shapes: single, multiple
  (clip-data), `accept` filtering including a resolved extension, cancel, the
  camera capture, `webkitdirectory` arriving as a multiple-files open, all three
  sources, and the uploaded bytes matching the host files digest for digest. `MODE_OPEN_FOLDER`, `MODE_SAVE`, and the permission
  mode cannot be exercised there — the platform only produces them on API 37
  with FSA enabled — so those paths rest on the documented contract and their
  JVM tests, and the rows are recorded as untested.
- **The built-in picker cannot start on a readable `/`.** The literal root is
  unlistable for an app, on every Android this product supports; the readable
  roots shortcut is the honest workaround, and it is a row in the picker rather
  than a silent redirect.
- **Downloads are still not handled.** A link that would download (for example
  `Content-Disposition: attachment`) reaches neither `onShowFileChooser` nor a
  `DownloadListener`; nothing happens today. That is a separate slice, recorded
  in `docs/limitations.md`.
- **The built-in picker shows hidden entries** and lets the user walk anywhere
  the app may read, including app-private directories. That is deliberate — the
  guest's own files live there — and it is the same reach the app already has.
- **`accept` filtering is best effort.** A document provider decides what it
  honours; an unknown extension becomes `*/*` rather than a wrong filter.
- **A cancelled answer is `null`, and a second request replaces the first.**
  Both are the provider's own semantics, and both are pinned by tests so a
  future refactor cannot strand a page's input.
