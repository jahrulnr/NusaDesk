# Debian trixie guest trial on the S10e (2026-10-10)

Before committing to a payload switch, the Debian 13 trixie rootfs was staged as a second runtime
on the physical device and every guest-side finding that previously became a bug on the Ubuntu Base
payload was re-tested against it under the shipped PRoot bridge.

## Setup

- **Device**: Samsung SM-G970F (Android 12, kernel `4.14.113-25257816`). The product session tracer
  kept running; the trial ran a second tracer on a second runtime directory
  (`files/linux-wrapper/runtimes/debian-trixie-arm64/active`).
- **Payload**: the debuerreotype slim arm64 tarball (the blob behind `arm64v8/debian:trixie-slim`),
  `sha256 bbeda6b4abb749f743f4547ef5a28070a14de8caef211daa5afb78d5b5fa21ba`, 30,200,282 bytes,
  Debian 13.7, glibc 2.41, 78 packages, 3,266 files, ~100 MB extracted.
- **Hardlink caveat**: the tarball contains hard links (`usr/bin/perl5.40.1` -> `perl`), and Android
  SELinux denies hard-link creation in app storage, so `tar xzf` fails on device at the first hard
  link. The trial staged a re-packed tarball built with `tar --hard-dereference`
  (31,789,543 bytes, 3,266 entries, zero hard-link entries); any product extractor must do the same
  or copy duplicates, which is what the shipped Ubuntu extraction path already tolerates.
- **Bridge**: the packaged `libproot.so` from the running session (pin `v5.1.107.96`), invoked with
  the production bind set (`resolv.conf`, `/system/bin`, `/system/lib64`, `/apex`, the three add-on
  trees, the `lw-proc` uptime/stat files, the android-bridge env and `power_supply` binds,
  `--link2symlink`, `-0`, `-w /root`, `--kill-on-exit`). The product's `os-release` bind was left
  out on purpose so the base's own file stayed visible.

## Results

| # | Finding (Ubuntu Base) | Debian trixie result | Verdict |
| --- | --- | --- | --- |
| 1 | Concurrent `git clone` wedge (`BUG-proot-fork-event-lost-vendor-kernels`) | **reproduced**: 2 of 5 concurrent rounds wedged (remote clone `rc=124` after a 240 s timeout; `git` in `R` with a `[git]` zombie sibling, tracee run-time +3.06 s in 3 s while the tracer idled) | still an issue; Debian is not a workaround |
| 2 | `git init` + `fetch` (`transfer.unpackLimit`) workaround | works (2/2 rounds, fetch and checkout `rc=0`) | unchanged |
| 3 | link2symlink internals visible in guest enumeration (`.l2s.`) | **not reproduced**: a full-depth `find / -name '.l2s.*'` was empty after `ln` and after a `dpkg` install | fixed by the `v5.1.107.96` pin |
| 4 | Second hard link consumes the backing file | **not reproduced**: three links to one file stay readable and `nlink` is faked as 3 | fixed by the pin |
| 5 | Guest SSH payload is not supplied by the base | same (no `sshd`); the noble-built add-on's `sshd` runs on Debian once the production `LD_LIBRARY_PATH` is set (`OpenSSH_9.6p1 Ubuntu-3ubuntu13`, OpenSSL 3.5.7) | portable |
| 6 | Guest service-bridge python | runs on Debian with the production `LD_LIBRARY_PATH` (`Python 3.12.3`, ssl/json/sqlite3 import) | portable |
| 7 | Rootfs without a CA store (`CERTIFICATE_VERIFY_FAILED`) | **reproduced** (Debian slim has no `/etc/ssl` at all); `apt-get install ca-certificates` fixes it (150 certs) and HTTPS then answers 200; the `guest-base-extras` add-on already carries `ca-certificates.crt` but must also create `/etc/ssl/certs` on Debian | unchanged, product fix exists |
| 8 | `systemctl is-system-running` false positive | **different**: Debian slim has no `systemctl` and no systemd at all (only packaged units and `libsystemd0`), so the ADR-0024 bridge has nothing to wrap | new product work item |
| 9 | `ps` in the guest | **different**: absent in Debian slim; after `apt install procps` the procps 4.x `ps` rejects `-o PID,STAT,NAME` ("unknown user-defined format specifier") | new work item / doc note |
| 10 | bionic binaries and `/apex` binds | works (`/system/bin/getprop` -> `SM-G970F`), with the pre-existing linker-config warning | unchanged |
| 11 | `/proc/net/tcp` denied | yes (`Permission denied`) | unchanged |
| 12 | `/proc/uptime` substitution | works (`999999.00 / 1999998.00`) | unchanged |
| 13 | apt/dpkg on the base | works (`deb.debian.org` over http, 10.1 MB in 3 s); `git 2.47.3` installed | unchanged |
| 14 | Host `TMPDIR` leaking into the guest | **new gotcha**: with `TMPDIR` pointing at the app cache (a host-only path) `mktemp` fails inside the guest and every dpkg postinst that uses it dies (`ca-certificates` configure failed twice); unsetting it fixes the install | record as a guardrail |

Not covered by this trial (product-level and distro-agnostic): terminal and session lifecycle, the
strict-bind overlay quirk, guest `/tmp` lifetime, bounded logs, `udocker compose`, USB pass-through,
the guest adb driver, the backup round trip, workspace picking, and the update banner.

## Conclusion

- Debian trixie boots and runs under the shipped bridge and the production bind set, and the add-ons
  (`sshd`, python) are portable as long as the production `LD_LIBRARY_PATH` is provided.
- The wedge is **not** a payload problem: it reproduces on Debian too, so the fix belongs in the
  vendored PRoot patch set or in the product (wedge detection plus a Restart Linux action). This
  trial removes "switch the distro" as a candidate workaround.
- The current PRoot pin already fixes two link2symlink findings that were previously bugs.
- Remaining product work for a Debian payload: a systemd/`systemctl` story that does not rely on the
  base shipping the binary, `procps`/`ps` expectations, creating `/etc/ssl/certs` before dropping in
  the extras CA bundle, `TMPDIR` hygiene for guest commands, and stripping the container-oriented
  apt hooks (`docker-clean`, `docker-gzip-indexes`, `docker-no-languages`) from the tarball.
