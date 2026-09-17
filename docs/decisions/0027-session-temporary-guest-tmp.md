# ADR-0027: Session-temporary guest `/tmp` in the persistent rootfs

## Status

Accepted and implemented in `GuestEphemeralStateCleaner` and
`GuestSshdWorkload`. JVM tests pass. A final APK device pass on Samsung
SM-G970F (Android 12/API 31) and SM-G935F (Android 10/API 29), both arm64,
created a marker under guest `/tmp`, force-stopped/relaunched the app, and
observed the marker absent before the new session continued.

## Context

The curated rootfs is stored under persistent app-private `filesDir` and PRoot
runs directly against that tree. Therefore the guest's `/tmp` is also a
persistent directory, even though Linux applications expect it to be temporary.
Device inspection found session artifacts, including Node compile-cache files,
under:

```text
files/linux-wrapper/runtimes/ubuntu-base-arm64/active/tmp/
```

The outer PRoot's `PROOT_TMP_DIR` is a different concern: it points at Android
`cacheDir` for the packaged PRoot loader's own temporary state. The nested
udocker PRoot sets its `PROOT_TMP_DIR` to guest `/tmp`, so nested temporary
artifacts also inherit the persistent-rootfs problem.

## Decision

Keep the rootfs in persistent storage, but give the one fixed guest
`/tmp` directory session semantics:

1. Before a new guest session launches, after any stale daemon/service-manager
   reclaim and before guest setup, clear the children of the active rootfs's
   real `tmp` directory.
2. After a normal supervised stop, clear it again once the tracer has stopped.
3. Never clean it while a guest process is live.
4. If `tmp` is missing, create it. If it is a symlink or non-directory, fail the
   new session rather than following or replacing an unexpected path.
5. Delete files, directories, and symlinks without following symlinks; do not
   touch `/var/tmp`, `/root`, the workspace, compose caches, package state, or
   arbitrary rootfs paths.
6. If Android force-stops or kills the app, cleanup cannot execute at that
   instant. The next explicit app launch retries cleanup before starting Linux;
   there is no boot receiver or hidden resurrection path.

## Consequences

- Guest `/tmp` no longer grows across clean stop/start cycles or reboot/relaunch
  recovery.
- A force-stop can leave stale files until the next user-visible start, which is
  an Android lifecycle limitation rather than a reason to add a sticky service.
- Applications that intentionally use `/tmp` as persistent storage lose that
  data at the next session boundary, matching the intended Linux semantics.
- Product caches such as `/root/.local/share/lw-udocker` remain persistent by
  design; they are not `/tmp` and require their own quota/cleanup policy later.
- Cleanup failure blocks a new session but does not delete outside the fixed
  directory or silently continue with an unsafe path.

## Verification

- `GuestEphemeralStateCleanerTest` covers regular files, nested directories,
  symlinks, missing `tmp`, and unsafe rootfs `tmp` paths.
- Device verification must create a marker under guest `/tmp`, stop/relaunch,
  and prove the marker is absent while a marker outside `/tmp` remains.
