# ADR-0011: Name inherited Android group IDs in the guest group database

## Status

Accepted and implemented. Verified on the Android 10/API 29 arm64 device
(Samsung SM-G935F).

## Context

Every interactive guest login printed one line per Android group ID:

```text
groups: cannot find name for group ID 1004
groups: cannot find name for group ID 1007
... (one per inherited ID)
```

Root cause, established by device probes (not inferred):

1. PRoot shares the Android kernel and the app's credentials. An Android app
   runs under `untrusted_app` without `CAP_SETGID`, and PRoot `-0` only fakes
   the ids that `getuid`/`getgid`/`getgroups` *report*; the process's real
   supplementary group list stays the Android one. On the test device the app
   process reports `Groups: 3003 9997 20279 50279` (`inet`, `everybody`, the
   per-app cache gid and the per-app shared gid), and guest processes inherit
   that list unchanged — a `run-as` debug shell (the uid the early probes ran
   as) additionally carries `1004 input, 1007 log, 1011 adb, 1015 sdcard_rw,
   1028 sdcard_r, 3001 net_bt_admin, 3002 net_bt, 3006 net_bw_stats, 3009
   readproc, 3011 uhid`, and the guest `id` prints exactly the host list of
   whichever process the probe ran as. PRoot's fake root reports the app's
   primary gid as `0`, so only the supplementary IDs are unnamed inside the
   guest.
2. Those IDs have no entry in the guest `/etc/group`, so guest glibc's
   `getgrgid()` fails for them.
3. Ubuntu's `/etc/bash.bashrc` runs `$(groups)` for its "sudo hint" block on
   every interactive shell, and coreutils' `groups` prints
   `cannot find name for group ID N` for every ID it cannot name. That is the
   login noise. It is a naming failure in the guest, not a daemon, auth, or
   session failure — which is exactly why filtering it away would be wrong.

## Decision

The guest setup step — the same one-shot script that provisions the `sshd`
privsep account and pins `root`'s password (ADR-0009/0010) — also appends one
`aid_<name>:x:<gid>:` entry per inherited supplementary group ID.

- The host reads its own real list from `/proc/self/status` (`Groups:` line)
  and maps IDs to names pinned from AOSP
  `android_filesystem_config.h` (`GuestSupplementaryGroups`); an unknown ID
  falls back to `aid_<gid>` so no name is ever invented.
- The data travels in the `LW_GUEST_GROUP_ENTRIES` environment variable, never
  in argv or interpolated into the script text, and every generated name is
  `[a-z0-9_]`-only, so it cannot inject `/etc/group` syntax or shell content.
- Entries are appended **only** when the guest does not already name that GID.
  The guest's own account database wins, so an Android ID can never be aliased
  onto an existing guest group, and nothing is rewritten or removed.
- The guest keeps the real membership; the `aid_` prefix marks the entries as
  inherited Android AIDs, so `groups`, `id`, and `ls -l` show the truth by name
  instead of printing warnings.

Rejected alternatives:

- **Dropping the groups in the guest.** Not possible — it needs `CAP_SETGID`,
  which an `untrusted_app` does not have. It would also break guest networking
  (`3003 inet`) that the daemon and the guest session depend on.
- **Suppressing the output** (a `groups` shim, `BASH_ENV`, a patched
  `/etc/bash.bashrc`, or `/root/.hushlogin`). That hides the state instead of
  representing it, leaves `id`, `ls -l`, `newgrp`, and `su` broken for the same
  IDs, and `/root/.hushlogin` would additionally silence the guest's own login
  banner. The message must not be hidden: if a future change makes the guest
  lose a group it should still need, the guest's own tools must be able to show
  it.
- **Faking `getgroups()` in PRoot.** It would misreport the process's real
  credentials to every guest program.

## Consequences

- Interactive login is clean and guest tools resolve the inherited IDs.
- `/etc/group` inside the activated rootfs is runtime state, like the `sshd`
  account and the pinned `root` password: it is re-applied idempotently on
  every session start and is not part of any digest-verified payload. The
  curated payload digests and the extraction/activation pipeline are untouched.
- A guest `groupadd` now avoids the AID GIDs (they are taken); `groupadd -g
  <aid>` fails with "GID already exists". Accepted: those GIDs really are in
  use by the process tree.
- Entries are additive and never pruned: if a future app update loses a group,
  its `aid_*` name stays in the guest database. Harmless — the kernel simply
  stops reporting that ID.
- This is naming, not isolation. The guest still shares the app's UID/GID and
  group set, and PRoot remains a compatibility layer, not a sandbox
  (`docs/limitations.md`).

## Verification

- Unit tests: `GuestSupplementaryGroupsTest` (kernel `Groups:` line parsing,
  pinned AID names, `aid_<gid>` fallback, zero/duplicate/non-positive handling,
  inert entry shape) plus the `GuestSshDaemonTest` case asserting the setup
  script carries the IDs by env only and only ever appends to `/etc/group`.
- Guest-level probe on the device: the exact new block text from
  `GuestSshDaemon.setupArgv()` was executed inside the guest under the same
  PRoot argv/env the workload uses (`-0`, `/proc` + `/dev` binds, `-w /root`).
  The block exited 0, appended 12 entries, was idempotent on a second run, and
  the interactive login shell (`/bin/bash -i`, the shell `sshd` spawns) went
  from 12 `groups: cannot find name for group ID` lines to none, with `groups`
  and `id` resolving every ID (`aid_inet`, `aid_sdcard_rw`, …).
- Product path on the device (Android 10/API 29, arm64, Samsung SM-G935F,
  app uid `u0_a279`, APK built from this change): the app process really
  carries `Groups: 3003 9997 20279 50279`; after the app's own session start
  (`GuestSshdWorkload: guest OpenSSH sshd ready on 127.0.0.1:60920`) the guest
  `/etc/group` contained exactly those four entries —
  `aid_inet:x:3003:`, `aid_everybody:x:9997:`, `aid_20279:x:20279:`,
  `aid_50279:x:50279:` — and guest `getent group <id>` resolved all four.
- The app's terminal (real SSH login through the guest daemon) showed three
  consecutive logins with a clean banner:

  ```text
  Last login: Sun Sep 13 15:35:08 2026 from 127.0.0.1
  root@localhost:~# Last login: Sun Sep 13 15:35:42 2026 from 127.0.0.1
  root@localhost:~# Last login: Sun Sep 13 15:37:58 2026 from 127.0.0.1
  root@localhost:~#
  ```

  versus the same terminal on the previous build, whose scrollback showed
  `groups: cannot find name for group ID 3003 / 9997 / 20279 / 50279` on every
  login.
- A real interactive SSH login (PTY, public key) into the app's **own
  supervised daemon** on the live session endpoint produced no warning at all
  and resolved every ID:

  ```text
  Last login: Sun Sep 13 15:45:57 2026 from 127.0.0.1

  root@localhost:~# groups
  root aid_inet aid_everybody aid_20279 aid_50279
  root@localhost:~# id
  uid=0(root) gid=0(root) groups=0(root),3003(aid_inet),9997(aid_everybody),20279(aid_20279),50279(aid_50279)
  root@localhost:~# exit
  logout
  ```

- Control case proving nothing is suppressed: an SSH login into a *test*
  daemon launched from a `run-as` debug shell (whose group list is a different
  set of AIDs, none of them named in the guest) still prints
  `groups: cannot find name for group ID 1004 …` — the guest only stops warning
  for IDs that are genuinely named.
- Daemon lifecycle was unaffected: start → `guest OpenSSH sshd ready on
  127.0.0.1:<port>`; stop → `guest sshd stopped; 127.0.0.1:<port> no longer
  answers`, no orphaned `sshd`/`libproot.so` left behind.
- The guest `/etc/group` was restored to its pre-probe contents between the
  guest-level probe and the product-path run, so the final state is entirely
  product-produced; the test key, test config, and test daemon were removed
  afterwards.
