# Third-party notices — `assets/base-extras/`

This directory ships **documentation only**. The `guest-base-extras` add-on
payload is downloaded, digest-pinned, and verified at install time; nothing
here is the payload itself. This file is copied into the activated overlay
at `usr/share/lw-base/THIRD_PARTY_NOTICES.md` so the provenance record
travels with the payload.

The add-on exists because the curated Ubuntu Base rootfs ships **no CA
certificates at all** — without them every TLS client in the guest fails
certificate verification before doing anything.

## ca-certificates 20240203 — GPL-2+ (package) / MPL-2.0 (Mozilla CA set)

- Artifact: `ca-certificates_20240203_all.deb` (`Architecture: all` — the
  package is data only; `payload arch` in the catalog records the guest ABI
  it is installed into, like every other `_all` artifact in the catalog).
- Source pool: `https://ports.ubuntu.com/ubuntu-ports/pool/main/c/ca-certificates/`
  — Ubuntu Noble GA pocket (release-pocket pool files are never removed on
  supersede), the same pool every catalog artifact is pinned against.
- SHA-256: `641de77d8f142cfd62a1a6f964ba67b20754d3337c480efb529d086075a06c9a`
- Copyright: 2003 Fumitoshi UKAI, 2009 Philipp Kern, 2011 Michael Shuler,
  and various Debian contributors. The shipped `mozilla/*.crt` files derive
  from NSS `certdata.txt`, Copyright Mozilla Contributors (original
  copyright 1994–2000 Netscape Communications Corporation).
- Licence texts: the Debian `copyright` file ships inside the payload at
  `usr/share/doc/ca-certificates/copyright` (GPL-2+ full text is
  `/usr/share/common-licenses/GPL-2` upstream; MPL-2.0 is reproduced inline).
- Usage: only `usr/share/ca-certificates/mozilla/*.crt` is consumed. Because
  this product never runs maintainer scripts, the host concatenates that
  pinned set in sorted order into the overlay's
  `etc/ssl/certs/ca-certificates.crt` at session start — byte-for-byte the
  output `update-ca-certificates` would produce for the default set.
- The deb also ships `usr/sbin/update-ca-certificates`; it rides along in
  the overlay but is deliberately **not** wired onto the guest PATH:
  without the postinst-generated `/etc/ca-certificates.conf` it rebuilds a
  bundle containing only `/usr/local/share/ca-certificates` entries, which
  would silently drop the Mozilla roots. Running it from the overlay path
  remains possible but writes a real bundle over the product symlink —
  `GuestBaseExtras` then preserves that file like any other guest content.

## OpenSSL 3.0.13-0ubuntu3 — Apache License 2.0

- Artifact: `openssl_3.0.13-0ubuntu3_arm64.deb` — Ubuntu Noble GA pocket.
- Source pool: `https://ports.ubuntu.com/ubuntu-ports/pool/main/o/openssl/`
- SHA-256: `9b7136b1af32fbdefc2eac61bae86f8304c603c7b9a0297b20a1e31c522b024b`
- Copyright: The OpenSSL Project Authors; licence text inside the payload at
  `usr/share/doc/libssl3/copyright` (the openssl package's own `copyright`
  is a symlink to it).
- Role: the add-on's AArch64 ELF entrypoint (the installer's uniform ABI
  check — the data-only `ca-certificates` package ships no executable), the
  declared dependency of the payload's `update-ca-certificates`, and the
  conventional TLS-inspection tool wired at `/usr/bin/openssl`. `usr/lib/ssl`
  is wired so OpenSSL's compiled-in `OPENSSLDIR` resolves `cert.pem` to the
  generated bundle. Its runtime dependencies (`libssl.so.3`,
  `libcrypto.so.3`, `libc.so.6`) already ship in the curated rootfs, so the
  binary runs as delivered.
