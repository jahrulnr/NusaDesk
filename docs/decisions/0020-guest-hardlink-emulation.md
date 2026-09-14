# ADR-0020: Emulate guest hard links with PRoot `--link2symlink`

## Status

Accepted and implemented in `ProotLaunchSpec` (default `link2symlink = true`) and
`ProotCommandFactory` (`--link2symlink` emitted for every launch). Device-verified
on the Android 10 / API 29 arm64 test device.

## Context

`apt update` works in the guest, but `apt upgrade` / `apt install` failed:

```text
dpkg: error processing archive .../libc6_...deb (--unpack):
 unable to make backup link of './usr/lib/aarch64-linux-gnu/gconv/ANSI_X3.110.so'
 before installing new version: Permission denied
dpkg: error creating new backup file '/var/lib/dpkg/status-old': Permission denied
```

Diagnosis on the device (no assumptions): every hard-link creation inside the
PRoot guest fails with `Permission denied`, including in `/tmp` and in
`/var/lib/dpkg`:

```text
/bin/ln /var/lib/dpkg/status /var/lib/dpkg/status-old
  -> failed to create hard link: Permission denied
/bin/ln /tmp/hl-src /tmp/hl-dst
  -> failed to create hard link: Permission denied
```

Files are owned by the app uid and the directories are writable (a plain
`touch` in `/var/lib/dpkg` succeeds), so this is not a permission-bit or
read-only-mount problem. It is Android's SELinux policy for the app domain
(`untrusted_app`): hard-link creation is not permitted for app data. `dpkg`
creates a backup hard link before every unpack and again for
`/var/lib/dpkg/status`, so the failure is structural: no `dpkg` install or
upgrade can ever complete, regardless of network, DNS, or storage.

The packaged PRoot build (`termux` fork v5.1.107.92) already ships the
documented workaround. Its own help text:

```text
--link2symlink, -l
  Replace hard links with symlinks, pretending they are really hardlinks
  Emulates hard links with symbolic links when SELinux policies
  do not allow hard links.
```

This is the same flag Termux's `proot-distro` uses so that `dpkg` works inside a
rootfs on an unrooted Android device.

## Decision

1. **`--link2symlink` is on by default for every PRoot launch.**
   `ProotLaunchSpec.Builder.link2symlink` defaults to `true`, and
   `ProotCommandFactory` emits `--link2symlink` for all specs, so the setup
   script, the guest `sshd` daemon (and therefore every interactive session it
   spawns), and the `dpkg-deb` extraction path all run with link emulation.
   There is no supported device on which the app is allowed to create guest hard
   links, so an opt-in flag would only invite the same failure again.

2. **The argv order stays fixed and testable:** `-r <rootfs>`, `-0` (when fake
   root is requested), `--link2symlink`, binds, `-w`, `--kill-on-exit`, then the
   verbatim guest argv. `ProotCommandFactoryTest` pins both the position and the
   default.

3. **No security or lifecycle rule changes.** The flag alters how the guest's own
   `link()` calls are satisfied inside its rootfs; it does not widen host access,
   does not affect the loopback-only bind, the pinned host key, the curated
   payload digests, or the foreground-service lifecycle.

## Alternatives considered

- **Install `libandroid-*` shims or a patched libc.** Unnecessary: the failure is
  the host SELinux decision on `link`, which a guest library cannot change.
- **Mount the rootfs read-write on a different filesystem (e.g. outside app
  data).** Rejected: shared storage is mutable by other apps and commonly
  `noexec`; AGENTS.md requires app-private extraction.
- **Patch `dpkg` to skip backup links.** Rejected: it would diverge the curated
  Ubuntu payload, and every other package manager tool that links (including
  `apt`'s own partial-file handling) would still fail.
- **Run as real root.** Not available to a third-party app.

## Consequences

- **Positive:** `dpkg` backup links work, so `apt install`, `apt upgrade`, and
  `apt --reinstall` complete. Device evidence with the app's exact argv:
  `dpkg --configure -a` → exit 0, `apt-get install --reinstall base-files`
  unpacked and configured successfully, and `apt-get -s upgrade` produced a clean
  two-package plan (`libc6`, `libc-bin`).
- **Positive:** because emulated links are symlinks, a program that later assumes
  a true hard link (e.g. comparing inode numbers, or relying on
  `st_nlink > 1`) sees a symlink instead. That is the accepted cost of the
  documented workaround and is confined to the guest rootfs.
- **Negative:** guests that genuinely require real hard links cannot be
  supported on this platform; this must be stated in the compatibility matrix.
- The `debconf: unable to initialize frontend: Readline` message in the same
  output is unrelated and non-fatal: the `Readline` debconf frontend module is
  not part of the curated payload, so debconf falls back to a non-interactive
  frontend and the install still completes.
