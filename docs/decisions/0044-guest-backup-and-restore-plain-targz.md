# ADR-0044: Guest backup and restore — plain tar.gz, three modes

## Status

Accepted; the implementation lands 2026-09-20 with the **System → Settings →
Backup & restore** page and the streaming `tar.gz` archive. The archive format
is versioned (`formatVersion: 1`).

## Context

Moving to a new phone, or a clean reinstall (same signing identity), currently
means redoing the whole setup: the curated Ubuntu Base download, the guest
add-on, guest packages, configs and the user's files inside the guest. The
Linux ecosystem's own convention for this is a `tar.gz` archive, and the
product's posture is personal use: the archive is an artifact the *user* owns
and stores. If it leaks, it leaked because of where the user put it — the
product does not pretend to protect a file the user controls, and adding an
encryption ceremony the user did not ask for is not this slice's job.

What the product still owns is **extraction safety**: an archive is untrusted
input, and the repository's rules (AGENTS.md) require rejecting absolute paths,
`..`, escaping symlinks and special files, extracting into app-private storage
only, and activating atomically. Those are correctness rules, not secrecy.

## Decision

1. **Format: plain `tar.gz`, manifest first.** The first tar entry is
   `manifest.json` (format version, mode, runtime app id + version, app
   version, creation time, selected roots, entry count, total bytes). The
   payload follows as `rootfs/…`, `addons/<id>/…`, `state/…`. No encryption
   and no signature; the trust boundary is "the user chose this file", and
   the import path validates the structure (manifest, runtime id against the
   curated catalog, path safety, sizes) before anything is touched.
2. **Three modes.** **Full** captures the active runtime rootfs, the active
   guest add-on(s), the session state and the guest SSH host key — it is the
   only mode that can bootstrap a fresh install, and its restore is an atomic
   swap (staging → validate → activate, with the previous tree kept for
   rollback). **Home** captures only `/root` + `/home`. **Custom** captures
   top-level guest paths the user checks from a fixed allowlist
   (`/root`, `/home`, `/opt`, `/usr/local`, `/etc`, `/var/lib`; never
   `/proc`, `/sys`, `/dev`, `/run`, `/tmp`). Home and custom restores
   **merge** into an existing runtime and fail typed (`runtime-required`)
   when there is none; their granularity is one selected top-level path at a
   time (old subtree aside, staged subtree in, old subtree dropped on
   success, restored on failure), which is simple, bounded and stated in the
   UI.
3. **Export streams to the destination.** The user picks the file through
   SAF (`ACTION_CREATE_DOCUMENT`); the tar.gz is written straight into that
   `OutputStream` (no second temp copy), with progress, and small last-run
   records kept for display. The session may keep running; the archive is a
   best-effort snapshot of a live guest, not a quiesced one.
4. **Import streams once and stages everything.** The user picks the file
   (`ACTION_OPEN_DOCUMENT`); the manifest is read first and validated against
   the curated catalog; the payload is extracted into a staging directory
   using the repository's existing safe extractor (`PayloadIo`) — the same
   implementation the curated runtime install uses, reached through a small
   public facade (`RuntimePayloadSupport`) rather than duplicated. Full
   restores then reuse the installer's atomic activation; home/custom
   restores merge per top-level path.
5. **Exclusions are fixed and documented.** The workspace bind target inside
   the guest (`root/nusadesk`), the pseudo trees (`proc`, `sys`, `dev`,
   `run`, `tmp`), the runtime's previous/staging slots, and session temp
   files never enter an archive. App-owned generated files (guest CLI
   scripts, the awareness README, `sshd_config`) may be carried by an
   archive, but the writers re-assert them at the next session start, so a
   stale copy cannot break the runtime contract.
6. **Device-bound secrets never travel.** The guest root credential lives in
   the Android Keystore (`linuxwrapper_session_credentials`) and is re-hashed
   into the guest's `/etc/shadow` at every session start
   (`GuestSshDaemon`), so a restore on a new device needs no credential
   migration step: the next session start re-asserts the new device's
   credential. The archive therefore never has to carry Keystore-wrapped
   material, and it must not.
7. **Restores are serialized with installs.** Export/restore run off the UI
   thread under the same install lock as curated installs, with a
   session-live probe and a free-space check, and a typed `busy` result
   instead of racing.

Rejected alternatives:

- **Encrypting or signing the archive in v1.** The user asked for the Linux
  convention (plain tar.gz) and owns the archive's fate; a passphrase wrapper
  can be added later without changing the format, and signing with the
  release key is impossible on-device (ADR-0040) and would not stop the
  user's own leak.
- **Per-file merging for home/custom.** File-level three-way merges are a
  maintenance tail for a personal-use feature; per-top-level-path replacement
  is predictable and easy to explain.
- **Driving `tar` inside the guest.** Fails exactly when the feature matters
  most: on a fresh install there is no guest yet, and the product would
  depend on guest tooling that does not exist until after setup.
- **Including the workspace folder.** It lives on shared storage, is the
  user's own folder, and can be copied without the product's help; including
  it would duplicate potentially large trees and widen what a restore can
  overwrite.
- **Backing up into a hidden app location instead of SAF.** Then the archive
  cannot leave the phone, which defeats the whole point.

## Consequences

- A full dump restores a device or install end to end; a home/custom dump
  restores the parts a user actually customizes on top of a working session.
- The archive may contain secrets (the guest SSH host key, guest files, the
  user's `/root`), which the UI states plainly next to the Export action: it
  is the user's file and belongs somewhere the user trusts.
- Restores are safe against partial failure: extraction happens in staging,
  full restores swap atomically with a rollback slot, and a failed merge
  restores the subtree it moved aside.
- The feature is host-side only: it works with the session stopped, and full
  restores work before any runtime exists.
- A FULL restore brings what the archive contains; it does not delete an
  add-on that is active on the device but absent from the archive.
