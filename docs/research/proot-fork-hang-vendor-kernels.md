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
