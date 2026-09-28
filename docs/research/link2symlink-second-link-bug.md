# link2symlink: adding a member to an existing group can consume the group

Research note: 27 September 2026. Records a reproduced defect in PRoot's
`--link2symlink` extension, the fix now embedded in
`scripts/build-proot-arm64.sh` as "Patch C 2", and what was verified. This
note makes **no device-verification claim**: validation was host-side only
(x86_64 build of the patched source). The arm64 build and the Samsung S10e
re-verification are deliberately left to the reader of this note.

## Affected source

- Upstream: `termux/proot`, tag `v5.1.107.92`, pinned revision
  `7266fb3e8516535682f5a9c8f3a7e70f6506eddb` (what the build script clones).
- File: `src/extension/link2symlink/link2symlink.c`.
- Baseline: the file **with Patch C already applied** (the heredoc named
  `L2S_PATCH_C` in the build script). Patch C 2 is a follow-up on top of it.
- Newer upstream `v5.1.107.95` was checked; it still carries the same
  `move_and_symlink_path` group-add sequence, so no upstream fix exists to
  borrow.

## Background: what one emulated hardlink looks like on the host

`link(2)` inside the guest is emulated because the Android app UID cannot
create a real hardlink at all (measured on the S10e: `run-as ... ln a b`
fails with `Permission denied` even with PRoot bypassed). The extension
represents one group of hardlinked names as:

```
a                  -> .l2s.a0001          (symlink, visible guest name)
b                  -> .l2s.a0001          (symlink, another member)
.l2s.a0001         -> .l2s.a0001.0002     (the "intermediate" symlink)
.l2s.a0001.0002    regular file           (the "final"/backing file)
```

The link count of the group is encoded in the backing file's four-digit
suffix: `.0002` means two members. Every add/remove **renames** the backing
file to bump or decrement that counter, then repoints the intermediate.

## The defect

Patch C taught the `link()` handler (`move_and_symlink_path`) to recognize
a source that already belongs to a group — a visible member, the
intermediate, or the backing file — and route the new name into that group
(`first_link = 0`). But the add itself was still the original upstream
sequence:

```c
l2s_rename(final, new_final);        /* backing .0002 -> .0003        */
notify_extensions(LINK2SYMLINK_RENAME);
strcpy(final, new_final);
l2s_unlink(intermediate);            /* intermediate link REMOVED     */
l2s_symlink(final, intermediate);    /* ...then recreated             */
```

Every name of the group resolves through `intermediate`. Between the
`l2s_rename` and the `l2s_symlink` recreation the intermediate either still
spells the old (now renamed-away) backing name or does not exist at all —
a failure anywhere in that window leaves **all** members dangling, and
when the source *is* the intermediate, the `l2s_unlink` deletes the source
mid-operation. The same unlink/recreate window existed in
`decrement_link_count`, and a `notify_extensions` failure returned without
undoing the backing rename.

Fault injection against the unpatched (Patch C only) code — one injected
`EIO` at the `l2s_unlink(intermediate)` step — produces exactly the state
reported from the device:

```
.l2s.file0001 -> .l2s.file0001.0002   (intermediate still spells old name)
.l2s.file0001.0003                   (backing renamed; .0002 gone)
cat .l2s.file0001 -> No such file or directory   (whole group dangles)
```

## Why bulk installers hit this every time

A single-file reproduction succeeds because no source is already an l2s
member. Installers such as `hermes-agent`, `uv`, and `npm` hardlink
hundreds of files into caches and routinely re-link paths that are already
group members (directly or via `linkat`/`os.link` with
`AT_SYMLINK_FOLLOW`). Each such link walks the fragile sequence above, so
one transient failure — an `EIO`/`EBUSY`/`EPERM` under Android FUSE or
sdcardfs semantics, a notification failure from another extension, an
interrupted process — converts a whole group into dangling debris.

A second, independent symptom also observed on device and reproduced on
the host: `rm -rf` of a directory containing a group reports "Directory
not empty" and leaves a `.l2s.*.NNNN` file behind. That is **inherent to
the count-in-filename design**, not introduced by Patch C and not fixed by
Patch C 2: while `rm`'s fts traversal holds a snapshot of the directory,
each decrement renames the backing file to a fresh name the snapshot does
not cover. The debris is a regular file at a real path — nothing dangles —
and a second `rm -rf` pass removes it.

## The fix (Patch C 2)

Smallest change that removes the dangling window and rolls back correctly:

- New helper `repoint_intermediate()`: creates a scratch symlink
  (`<intermediate>.<pid>.tmp`, named so `is_l2s_file()` cannot mistake it
  for a backing file) and `rename(2)`s it over the intermediate — atomic
  substitution, so resolvers never observe the intermediate missing.
- `move_and_symlink_path` group-add branch: `l2s_unlink`+`l2s_symlink`
  replaced by `repoint_intermediate`; a `notify_extensions` or repoint
  failure renames the backing file back under the name the intermediate
  still spells.
- Tail of `move_and_symlink_path`: on a failure creating the new visible
  member, `first_link` sources still use `decrement_link_count`; existing
  groups repoint the intermediate back to the old name and rename the
  backing file back.
- `decrement_link_count`: same atomic repoint plus backing-name restore
  on `notify_extensions` failure; behavior when the intermediate is
  already gone is preserved (the rename-over recreates it, as the old
  `l2s_symlink` did).

Embedded in `scripts/build-proot-arm64.sh` as a second guarded heredoc
`L2S_PATCH_C_2`, applied after Patch C; the guard greps for
`repoint_intermediate`, so re-running the script does not re-apply.

## Validation performed (host x86_64 build of patched source)

- `gcc -fsyntax-only` clean; full `make proot` link clean.
- Group creation and every add-source variant: original file, visible
  member, `.l2s.<name>` intermediate, `.l2s.<name>.NNNN` backing file;
  all members read the payload and share the group afterward.
- Fault injection with `LD_PRELOAD` shims:
  - `symlink(scratch)` fails → `ln` returns EIO, group untouched,
    backing rename undone.
  - `rename(scratch → intermediate)` fails → `ln` returns EIO, scratch
    removed, group untouched.
  - `symlink(new visible member)` fails → `ln` returns EIO, the count
    bump fully undone (intermediate repointed back, backing renamed
    back), group usable.
  - `unlink(intermediate)` failure — the case that corrupts the
    unpatched code — is no longer reachable: nothing unlinks the
    intermediate anymore.
- Teardown: unlinking the intermediate first then all members removes the
  backing file; `rm -rf` of a group can still leave a `.NNNN` file when
  traversal order interleaves with count renames (pre-existing design
  limitation, unchanged).

## Not validated / open doubts

- **No Android build**: `scripts/build-proot-arm64.sh` was not run (it
  downloads and cross-builds a toolchain). The patch was verified to apply
  cleanly to a pristine pinned-revision clone on top of Patch C and to
  produce the exact file that was compiled and tested on the host.
- **No device run**: the S10e reproduction must be re-run against the
  rebuilt `libproot.so`.
- **Expected output hashes**: the build script's fail-closed
  `EXPECTED_SHA256` values for `libproot.so`/`libproot-loader.so` will not
  match the newly patched binary. They must be regenerated from the actual
  arm64 build output before the script can pass its own verification.
- **rm -rf debris**: the count-in-filename rename can still leave a
  `.l2s.*.NNNN` file behind a single guest `rm -rf`; a second pass removes
  it. Eliminating that would require changing upstream's count encoding —
  out of scope for a minimal patch.
- The injected `EIO` failures prove the windows exist and the rollback is
  correct; the specific fault the device hit (FUSE/sdcardfs transient,
  concurrent unlink, signal interruption) was not identified and cannot
  be — the fix removes the entire class rather than one trigger.
