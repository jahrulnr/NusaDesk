# ADR-0010: Curated OpenSSH add-on payload for the guest SSH server

## Status

Accepted and implemented. The add-on installs, activates, and serves the guest
SSH session on-device.

## Context

ADR-0009 made the guest own its SSH server, but the curated Ubuntu Base
24.04.5 ARM64 rootfs ships no `sshd`, no Dropbear, and no `/etc/ssh`. The
runtime therefore had no SSH endpoint: the workload failed honestly with
"guest SSH payload is not installed" instead of faking a session.

Two questions had to be answered:

1. **Which daemon?** Dropbear is much smaller, but the packaged PRoot build
   (termux fork v5.1.107.92) cannot translate the `execveat(fd, ...)` call
   Dropbear 2022.83 uses to re-exec itself per connection, so Dropbear fails
   under this bridge. OpenSSH 9.6p1 runs under PRoot.
2. **How is it distributed?** Ubuntu Base has no `apt`/`dpkg` configured and
   the host must not run a package manager against arbitrary repositories.
   The daemon has to arrive as pinned, digest-verified artifacts inside the
   existing curated catalog model (ADR-0002).

## Decision

The guest SSH server ships as a **curated add-on payload**: an ordered list of
pinned upstream `.deb` artifacts that install into a private overlay
directory, which the runtime binds into the guest at `/opt/lw-ssh`.

- Seven artifacts are pinned in `CuratedRuntimeCatalog.guestSshAddon()`:
  `openssh-server` 1:9.6p1-3ubuntu13 plus the shared-library closure it needs
  inside the minimal rootfs (`libgssapi-krb5-2`, `libkrb5-3`, `libk5crypto3`,
  `libkrb5support0`, `libkeyutils1`, `libwrap0`, all 1.20.1-6ubuntu2 /
  1.6.3-3build1 / 7.6.q-33).
- Every artifact is a permanent GA-pocket pool URL on `ports.ubuntu.com`
  (release-pocket pool files are never removed on supersede) with a pinned
  SHA-256, size, and ABI recorded by the signed
  `dists/noble/main/binary-arm64/Packages` index. Nothing here is user input.
- The overlay install path is the existing verified pipeline: download →
  digest verification → safe extraction (no absolute paths, `..`, escaping
  symlinks, device nodes) → atomic activation. A partial or failed update
  never replaces the last known-good overlay.
- The daemon entrypoint (`usr/sbin/sshd`) must exist in the activated overlay
  before the workload will use it. The overlay wins over a rootfs-resident
  `sshd` so the pinned daemon is the one that runs.
- The overlay deliberately ships **no** `ssh-keygen`, no `chpasswd`, and no
  PAM stack: the host generates the Ed25519 host key with the bundled MINA
  writer, and the setup script edits `/etc/shadow` directly with the rootfs's
  own `perl` (PAM helpers cannot run inside the guest).

## Consequences

- The SSH-first experience works end-to-end on-device: a real guest OpenSSH
  bound to loopback, reached by the app's SSH client and xterm.
- The add-on is optional at install time: the wizard/card offers "install the
  Guest SSH server (OpenSSH) add-on" when the runtime is installed but the
  add-on is not.
- Storage cost is bounded and recorded in the profile (compressed and
  extracted sizes), so the installer can refuse an install with insufficient
  space before downloading.
- OpenSSH is BSD-licensed and the overlay keeps upstream license files with
  the extracted package contents; the notices are also listed in the
  third-party notice file. No GPL obligation is added by this payload (PRoot's
  GPL obligations are separate, see ADR-0004/0008).
- A future daemon change (or a second profile) must re-verify the PRoot
  syscall coverage and re-pin digests; the catalog is the single place to do
  that.

## Verification

The add-on downloaded, verified, and activated on the Android 10/API 29
arm64 device; the guest daemon reported its bound loopback endpoint, the SSH
banner probe succeeded, and the terminal rendered a live
`root@localhost:~#` shell (ADR-0009 "Verification").
