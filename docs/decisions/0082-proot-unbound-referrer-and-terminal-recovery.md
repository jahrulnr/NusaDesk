# ADR-0082: Unbound symlink referrers and terminal recovery

Status: accepted, 2026-10-29.

## Evidence

The user reported that Linux was healthy, Devin CLI opened, and the SSH
session disconnected roughly five seconds later. The failed terminal showed
`guest OpenSSH sshd exited unexpectedly (exit code 1)`. A subsequent photo of
Previous boot (`/var/log/lw/boot.log.1`) contains the underlying native failure:

```text
./path/path.c:453: int detranslate_path(Tracee *, char *, const char *):
assertion "binding_referrer != NULL" failed
proot warning: signal 6 received from process 11141
```

This matches the assertion in the pinned termux/proot revision. Its reverse
path translator assumes every symlink referrer outside the rootfs matches a
host binding. `get_path_binding` can return NULL, so the assertion aborts the
shared tracer and ends its SSH session. The exact Devin syscall and path were
not captured; the failing native invariant is established by the photo and
reproduced using the actual pinned C function locally. Other unit-file errors
visible in the boot log are not changed by this fix.

## Decision

Keep the pinned PRoot revision and add a minimal patch: same-binding rewriting
requires both the referree and referrer bindings. With no referrer binding,
continue the existing rootfs-prefix or unchanged-path behavior. Do not disable
assertions globally, invent a binding, or introduce another guest/tracer.

Rebuild the bundled ARM64 executable using the existing reproducible build
pipeline. The baseline reproduced its old pinned SHA-256, and two patched
clean builds were bit-identical. The output pin is updated together with the
artifact; the loader is unchanged. Source, patch, tests and pins are recorded
in `third_party/proot/README.md` and `scripts/build-proot-arm64.sh`.

Two recovery gaps are fixed through existing host-owned boundaries:

- A FAILED terminal now offers `Restart Linux` directly.
- Terminal and command-app overflow menus offer `Restart Linux`, including
  when there is no current terminal port. With an existing in-scope tab they
  also offer `Reconnect`. Reconnect revalidates ownership and the current
  running runtime before touching a tab; a command reconnect reruns its command.
- An explicit restart from an ordinary terminal failure enters `ensureRunning`
  directly. Previously `stop` could not move FAILED into STOPPING, and the
  service released the restart request without starting anything. Live and
  surviving workloads retain their existing stop/escalation path. A failed
  retry stays failed; there is no automatic retry loop.
- Unexpected exits retain the monitor's recent stderr lines in the displayed
  reason, captured before teardown, as well as in the existing boot log.

## Verification boundary

The native function test aborts on unpatched source and passes 11 path cases
on patched source, with assertions enabled. It covers unmapped referrers,
mapped/unmapped targets, same and different bindings, symmetric bindings,
relative targets, rootfs stripping, strict non-symlink rejection, and procfs
mapping. The native build verifies the output pin, AArch64 PIE/linker64,
16 KB segment alignment and non-executable stack.

Robolectric tests reproduce the missing FAILED restart action and ignored
restart request before the fix, then verify recovery, no retry loop, menu
callbacks, tab ownership/stale menus, and propagation of the tracer error.
An offline native-graphics render is inspected for layout. The complete local
gate passes 2,549 JVM tests, 34 Python tests, lint, debug assembly and desktop
APK verification. The signed release APK also builds and contains the new
pinned PRoot binary.

The user subsequently connected an SM-G935F running Android 10/API 29,
ARM64 with 4 KB pages. The signed release was upgraded in place using
`adb install -r`; first-install time, guest data and launcher apps were
preserved. Actual screenshots and process snapshots establish:

- Devin CLI reached its interactive prompt and stayed connected for over
  100 seconds, beyond the reported five-second failure. The shared PRoot and
  SSH listener retained their PIDs, and no new native assertion appeared.
- Both menu recovery actions were visible. Reconnect opened a new Devin
  process while retaining the same Linux tracer and SSH listener.
- Menu Restart Linux replaced the tracer/listener. The old processes ended;
  one new listener owned the fixed `127.0.0.1:22022` endpoint. Command tabs
  close on whole-session restart and are reopened from All apps.
- Sending SIGTERM to that known guest SSH listener from the guest terminal
  produced FAILED, retained its `Received signal 15` diagnostic and displayed
  an actionable Restart Linux button. Tapping it started a fresh Linux
  session; Devin opened again. No Android app restart or Settings action was
  needed, and only one tracer/listener remained.

The original triggering syscall/path was not traced. These are startup,
reconnect and restart samples on one API 29 device, not long-session or
cross-device guarantees. Other OEMs, API levels and 16 KB-page execution
remain unverified for this fix.
