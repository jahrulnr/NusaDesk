# Guest commands wedge on vendor kernels that lose the ptrace fork-event PID

Date: 2026-10-10. Status: diagnosis confirmed on device, upstream fix identified,
bump to it verified to build and run but **not sufficient** on the Samsung S10e
kernel; the fork/child handling still needs a product-side patch (see "What is
still open").

## Symptom

`git clone` in the NusaDesk terminal "hangs sometimes" (also reported by a
second device, an Android 11 ROG 2, where `git index-pack` stopped at
`ptrace_stop` with `TracerPid` pointing at `libproot.so`). Observed shapes on
the S10e (SM-G970F, Android 12, kernel `4.14.113-25257816`, Samsung):

- a remote clone (`git clone https://github.com/termux/proot.git`, full);
- a local clone (`git clone --local hw1 hw1-local`);
- two clones at once through the session's tracer (the reliable repro);
- and, once one tracee is wedged, **every** later guest command hangs too: a
  plain `rm -rf` was found stopped in `t` (tracing stop).

## Evidence

- Process states during a hang: the guest command is `State: t (tracing stop)`
  with `wchan=ptrace_stop` and `TracerPid` = the session's `libproot.so`; a
  sibling `git` shows `Z` (zombie); the tracer alternates `S`/`R` and burns
  **system** time (`stime` far above `utime`), or the tracee spins in userspace
  (schedstat run-time ~64-100 % of wall) while the tracer sleeps in `wait4`.
- Killing a tracee from outside fails while its tracer is alive
  (`kill: unknown pid`, i.e. the signal cannot be delivered); killing the
  **tracer first** ends the whole tree, and `am force-stop` clears everything
  (the product's `GuestSshdPidFile` doc already noted that killing the tracer
  alone leaves the daemon; here it is the tracees that only die once the tracer
  has gone).
- The same shape reproduces with `PROOT_NO_SECCOMP=1`, so the seccomp fast path
  is not the trigger.
- Upstream root cause (termux/proot commit `8b994150`, in tag v5.1.107.95+):
  > "Some kernels lose the message of the last ptrace event: ... `sys_ptrace:
  > reset ptrace_message on ptrace_check_attach` ... The PID of a new child then
  > always reads as 0, so `new_child()` never registered the child: it stayed
  > stopped in SIGSTOP_PENDING while its parent waited for it, and any guest
  > command that forks hung, with or without PROOT_NO_SECCOMP."

## What was tried

| Build | Shape | Result |
| --- | --- | --- |
| pinned `v5.1.107.92` (shipped before this note) | remote full clone, local clone, two concurrent clones | wedges (3/3 shapes observed) |
| `v5.1.107.96` (`a179d3e8`, the newest upstream, carries `8b994150` + the seccomp event fix + two `link2symlink` fixes) | remote shallow clone | passes |
| `v5.1.107.96` | two concurrent clones (round 1) | wedges |
| `v5.1.107.96` + `PROOT_NO_SECCOMP=1` | two concurrent clones | wedges |
| `v5.1.107.96` | single local clone | passes (the race did not trigger) |

So the bump is a necessary step (it carries the upstream fix for the vendor
kernels that fix targets) but it does not cover this Samsung kernel's variant:
the child is still lost, and once it is lost the tracer wedges and takes every
later guest command with it.

## Workaround that works (from the ROG 2 investigation)

Avoid the `index-pack` path, which is where the lost child bites:

```sh
mkdir repo && cd repo
git init
git remote add origin <url>
git -c transfer.unpackLimit=100000 fetch --depth=1 --no-tags origin <branch>
git checkout -B <branch> FETCH_HEAD
```

`fetch` with a raised `unpackLimit` uses `unpack-objects` instead of
`index-pack` and completed in ~10 s on the ROG 2 where `git clone` hung.

## Recovery when it has already wedged

The session is unusable until the tracer goes away, because a wedged tracer
never resumes its tracees:

1. `Stop` the Linux session from the notification, or force-stop the app
   (either kills the process tree), then reopen the app: Linux restarts from the
   next user-visible launch.
2. From a host/debug build, the same effect is achieved by killing the
   **tracer** (`libproot.so`) first; after that the tracees die too. Killing
   tracees while the tracer lives does nothing.

## What is still open

1. **Extend the vendored PRoot patch set for this kernel.** The repository
   already patches PRoot locally (five patches in
   `scripts/build-proot-arm64.sh`), and upstream `.96` reads
   `/proc/<pid>/status` to recover a lost child. The next step is to find which
   event is lost on the Samsung kernel (the verbose `-v 3` trace is the tool:
   the tracer's last lines before the wedge) and to cover it the same way, with
   a build that fails closed on the pinned digests as usual.
2. **Make a wedged session visible and recoverable.** Today the user sees a
   frozen command and no explanation. A bounded canary (a trivial guest command
   with a timeout, sampled by the host service) could turn "the terminal is
   stuck" into an explicit state with a `Restart Linux` action.

## How to reproduce (host + device)

1. Install a QA build (`make release GRADLE_FLAGS="--no-daemon -PqaDebuggable=true"`).
2. In the guest: `apt-get install -y git`, then run two clones at once, e.g.
   `git clone --depth 1 https://github.com/octocat/Hello-World.git a & git clone --local <any repo> b & wait`.
3. Watch from the host: `ps -A -o PID,STAT,NAME | grep -E "git|libproot"` (look
   for `t`/`Z`) and `/proc/<pid>/status` (`TracerPid`, `State`), `/proc/<pid>/wchan`
   (`ptrace_stop`), `/proc/<pid>/schedstat` (CPU burn).

## Localisation update (2026-10-10, Debian trixie trial on the S10e)

The wedge is inside the `--link2symlink` extension, not in the fork/child bookkeeping:

- Two concurrent remote clones, three rounds each, same bridge, same rootfs, 120 s per-round timeout:
  - **with `--link2symlink`**: `rc=0,124` / `rc=124,0` / `rc=0,124` -> 3 of 3 rounds wedged (3 of 6 clones stuck).
  - **without `--link2symlink`**: `rc=0,0` / `rc=0,0` / `rc=0,0` -> 0 of 3 rounds wedged (6 of 6 clones finished).
- git's own trace of a stuck clone stops right after its `index-pack` child exits with code 0 and
  before the parent starts `rev-list`; the PRoot `-v 6` log ends without any further syscall, so the
  tracee spins in userspace. The earlier Ubuntu capture froze immediately after an `fstatat64` whose
  path had been translated to `.l2s.tmp_pack_*.0001`, the same l2s surface.
- Two micro-shapes appear: the tracer spins while the tracee is stopped (`t`, `wchan=ptrace_stop`), or
  the tracee spins while the tracer is idle (schedstat run-time ~100% of wall). Both burn a core.
- `PROOT_NO_SECCOMP=1` still wedged and the upstream fork-event fix (`8b994150`) is not triggered on
  this kernel (no `fork event without the child's pid` log), so neither seccomp nor the lost-event
  path is the cause here.

Next step: reduce it to a minimal guest-side reproducer (create/rename/fstat a file repeatedly under
concurrency, no git needed), find the l2s code path that mishandles it, patch it in the vendored set,
rebuild through `scripts/build-proot-arm64.sh`, and verify with the concurrent-clone harness across
repeated rounds plus the guest smoke tests (dpkg hardlink, session start, service bridge).

## Patch D attempt and the frozen syscall (2026-10-10, later)

A sixth local patch (**Patch D**) now keeps the kernel's syscall result when the l2s
`stat`/`fstatat` bookkeeping fails: `handle_sysexit_end()` runs at `SYSCALL_EXIT_END` and its return
value is poked as `SYSARG_RESULT` (`src/syscall/exit.c:805`), so an internal `lstat`/`readlink`
failure used to turn a successful `fstatat(2)` into ENOENT for the guest. The build script, the
pinned digest (`9e2bf135…`) and `ProotBridgePinTest` all pass, and the device ran the patched bridge
(digest verified from the installed APK).

It is **not** the cure: the concurrent-clone harness wedged again on the first round (baseline with
the unpatched bridge was 3 of 3 rounds; the patched bridge wedged round 1 of 5). Under `-v 6` with
the patched bridge the same run produced:

- no `keeping the kernel result` line at all (the new path was never taken), and
- no `getdents64` in the tail (the chained-directory-filter loop is not involved either).

The frozen tracee's last syscall is an `fstatat64` on the **final pack name**
(`…/objects/pack/ffda95f5ba16b3959bd82.pack`, not an `.l2s.` name) whose result the kernel reported
as **ENOENT**, while the pack existed moments before and after. Its child is a zombie and the tracee
then spins in userspace without issuing another syscall. That points at the l2s rename machinery
(Patch C 2's scratch-symlink rename-over plus rename-back) leaving a window in which the canonical
name is momentarily absent to a concurrent reader, or at the POKEDATA stub path (the workaround
exists for exactly this era of arm64 kernels) resuming the tracee into a wild userspace loop.

Next candidates, in order: (1) make the l2s membership rename atomic for readers, e.g. swap the two
names with a single `renameat2(…, RENAME_EXCHANGE)` (available since Linux 3.15, so on 4.14) instead
of rename-away plus rename-back; (2) determine whether the POKEDATA stub workaround is active on this
kernel and whether forcing it off changes the outcome.

## The wedge is ours, not upstream's (diagnostic build, 2026-10-10)

A diagnostic bridge built from the same pinned revision with **only Patches A and B** (the two build
fixes; no C, C2, C3 or D) ran the same five-round concurrent-clone harness on the S10e and did **not**
wedge once (round results `0,0` / `0,128` / `0,0` / `0,0` / `0,0`; the single 128 is the plain
`link(2)`-on-a-symlink-source failure that Patch C exists to fix, a clean error rather than a hang).

So the wedge is introduced by the local l2s patch set, not by upstream termux/proot at this revision.
Together with the frozen trace (a real ENOENT on the plain pack name, whose resolution walks guest
name -> intermediate symlink -> count-encoded backing name) the suspect is Patch C 2's count change:
it renames the backing to a new count-encoded name *before* repointing the intermediate, so a
concurrent reader that already resolved the intermediate looks up a backing name that no longer
exists and gets ENOENT.

Plan for the next patch: give the backing file a **stable** name and keep the link count only in the
intermediate symlink name, which `repoint_intermediate()` already swaps atomically (scratch symlink
plus rename). A count change then becomes a single atomic repoint, the backing never moves, and the
`st_nlink` fabrication reads the count from the intermediate instead of the backing name; the
deletion path must read the same place.

## Bisect results (2026-10-11)

- **Stock (Patches A+B only)**: five of five concurrent-clone rounds clean -> the wedge is not
  upstream's.
- **Patch C only** (no C2, C3, D, E): five of five rounds clean -> Patch C is not the cause.
- **C + C2 + C3 + D**: wedged on round one.
- **Patch E** (window-free count changes layered on C2: publish the new count as a link to a stable
  backing, repoint the intermediate second, never rename the backing): still wedged on round one, so
  the count-rename window was not the trigger either.

The trigger therefore sits in Patch C 2 or Patch C 3. The next bisect step is a build with **C + C2
only** (no C3, D or E): if it wedges, C2 is the culprit and its hunks can be bisected one by one; if
it does not, C3's directory filter is.

Device state after the runs: restored to the released 0.14.1 bridge (sha256 `51801225…`) so the phone
runs the shipped pin; the development patch set (D and E) stays in the working tree, uncommitted.

## The compaction is the culprit, and what was tried on it (2026-10-11)

The trap is not the problem: a build that keeps the `getdents64(2)` trap but makes the filter a no-op
ran five concurrent-clone rounds clean on the S10e.  The compaction in `filter_l2s_dirents()` is.

Attempts so far, each built and run on the device:

- **Patch F** (fail open on any unreadable or unwalkable batch, bound the chained re-arms): still wedged.
- **Patch G** (never chain an all-internal batch, hand it out unfiltered): still wedged.
- **Patch I** (refuse guest mutations of internal names, so that removing Patch C 3 would not let
  `rm -rf` take a group's storage with it): the clones then failed with `rc=128` -- git's own
  directory cleanup removes the internal names it enumerates, so refusing that removal breaks it.
  Removing C 3 without such a guard is worse: the cleanup succeeds and deletes the group's storage.
  **The entries have to stay hidden**, which puts the fix back on the compaction.
- **Patch J** (stop copying the last examined record's `d_off`, a position from the unfiltered stream,
  into the last surviving record): one round gave `rc=128,128` (both clones failed with a git error),
  the next `rc=124,0` (a wedge), so it changes the failure modes without fixing either.

Next candidates, in order: (1) give every surviving record the `d_off` of the *next surviving* record
and the batch end to the last one, so the offsets form a consistent chain inside the batch the guest
actually receives; (2) rebuild the compacted batch in a second buffer, so in-place aliasing cannot be
involved; (3) only if neither works, look for a different way to hide the entries.

State after this round: the working tree and the device are back on the released 0.14.1 bridge
(sha256 `51801225…`) and the shipped patch set; only this note carries the new work.

## Resolution: hide the entries structurally, not in the extension (2026-10-11)

The wedge came from the filter, so the filter went away.  The extension already
supports keeping its storage elsewhere (`PROOT_L2S_DIR`), so the session now:

- points `PROOT_L2S_DIR` at an app-private directory
  (`<filesDir>/linux-wrapper/l2s`, `ProotPaths.l2sDirPath`), and
- binds that same path into the guest under the same name
  (`ProotLauncher`), so the link symlinks resolve.

The backing files then never live inside a guest directory and no listing can
show them: Patch C 3 was removed from the build set, and with it the only
patch that ever wedged.  The shipped set is A, B, C, C 2, D and E.

Device verification on the S10e (QA build, bridge sha256
`30236acb30550d59a96b038c9f1899b352831e1b495a9da85606c716b3800e6b`, session env
and argv inspected from `/proc`):

- five concurrent-clone rounds: `rc=0,0` each, no wedge;
- the guest's own listing of a directory with emulated links shows the links and
  **zero** `.l2s.` entries, while the app-private storage directory holds them;
- `rm -rf` over a tree that contains emulated links keeps a link outside the tree
  readable (`keeper reads: payload`), and git's own directory cleanup still works;
- the dpkg hardlink path, the SSH add-on and the bridge python are unaffected.

Legacy entries created before this change stay where they are and remain visible
until their group is removed; `docs/limitations.md` records that, along with the
storage directory never being swept automatically.
