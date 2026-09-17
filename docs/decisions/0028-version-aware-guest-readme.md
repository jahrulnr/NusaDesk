# ADR-0028: Version-aware guest awareness README

## Status

Accepted and implemented in `GuestAwarenessReadmeWriter` and the guest session
setup path.

## Context

The Android app provides a curated Linux guest with features that are otherwise
not obvious from a plain shell. A short `/root/README.md` gives users a local
awareness surface without requiring a separate Android screen. The file must
not become a stale copy of the feature set after an APK update.

The guest rootfs is persistent app-private data and the guest user can write
under `/root`, so the file should remain an ordinary writable file. At the same
time, this one path is product-owned and may be regenerated when the installed
APK version changes or when its content no longer matches the current template.

## Decision

On every usable guest session start:

1. Read the installed APK `versionName` from Android package metadata.
2. Ensure the fixed active-rootfs path `/root/README.md` is a real file under
   the active rootfs; reject a symlinked or unsafe `/root` directory.
3. Compare the existing bytes with the current concise template, which includes
   the APK version and an explicit notice that the file is app-managed and
   writable.
4. Leave a matching file untouched; otherwise write a temporary sibling and
   atomically replace the target.
5. Apply a normal owner-writable regular-file mode where the filesystem exposes
   POSIX permissions.

The writer never touches other files under `/root`, never executes a guest
command, and does not bind the README read-only. A user edit to this specific
product-owned file may be replaced by a later app version or session start.

## Consequences

- Existing installations receive updated awareness text on the next session
  after an APK update; no rootfs re-download is required.
- The README is available from the guest shell at exactly `/root/README.md`.
- A malformed `/root` symlink or write failure blocks session setup rather than
  writing outside the active rootfs or silently leaving a stale product file.
- The file is intentionally not an arbitrary user-document editor; durable user
  notes belong elsewhere in `/root` or the workspace.

## Verification

`GuestAwarenessReadmeWriterTest` covers first write, same-version no-op,
version/stale-content replacement, writable output, unsafe root symlink
rejection, and version-line validation. Device verification should inspect the
file after a session start and after an APK version change.
