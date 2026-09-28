Third-party notices — guest D-Bus face (`guest-systemd-dbus-face`)
==================================================================

These packages back the curated `guest-systemd-dbus-face` add-on: the
`org.freedesktop.systemd1` session-bus provider (`lw-systemd-dbus-provider`)
and the `busctl` client shim (`lw-busctl`) that consumers gate on. The
provider and the shim themselves are product-owned (same licence as
NusaDesk); the packages below are fetched as pinned Ubuntu Noble
`ports.ubuntu.com` pool `.deb` artifacts whose SHA-256 digests live in
`CuratedRuntimeCatalog.guestSystemdBusFace()`. The overlay also carries this
file at `usr/share/lw-dbus/THIRD_PARTY_NOTICES.md`.

1. D-Bus — dbus-daemon, dbus-bin, libdbus-1-3, dbus-session-bus-common
   Version:   1.14.10-4ubuntu4 (Ubuntu Noble GA pocket, arm64 + arch-all)
   License:   dual-licensed Academic Free License 2.1 or GPL-2.0-or-later;
              some bundled files BSD-3-clause/Expat — NusaDesk relies on the
              AFL-2.1 option, which permits redistribution of the unmodified
              binaries with this notice.
   Source:    https://dbus.freedesktop.org/releases/dbus/
   Pool dir:  pool/main/d/dbus/
   Copyright: (c) Red Hat Inc. and the D-Bus contributors
   Provides:  /usr/bin/dbus-daemon + dbus-run-session (dbus-daemon),
              /usr/bin/dbus-{send,monitor,uuidgen,…} (dbus-bin),
              libdbus-1.so.3 (libdbus-1-3), and the upstream session-bus
              configuration /usr/share/dbus-1/session.conf
              (dbus-session-bus-common).

2. dbus-python — python3-dbus
   Version:   1.3.2-5build3 (Ubuntu Noble, arm64)
   License:   AFL-2.1 or GPL-2.0-or-later, plus Expat for some files;
              relied on under AFL-2.1/Expat.
   Source:    https://dbus.freedesktop.org/releases/dbus-python/
   Pool dir:  pool/main/d/dbus-python/
   Copyright: (c) 2003-2006 Red Hat Inc. and contributors
   Provides:  the `dbus` client bindings used by lw-busctl
              (_dbus_bindings.cpython-312-aarch64-linux-gnu.so — the CPython
              3.12/aarch64 tag is pinned by the profile's required-files
              list). The _dbus_glib_bindings extension ships inside the same
              package; it is inert here — nothing loads it and libglib is
              deliberately not part of this payload.

3. AppArmor — libapparmor1
   Version:   4.0.0-beta3-0ubuntu3 (Ubuntu Noble, arm64)
   License:   LGPL-2.1-or-later (the userspace library); NusaDesk ships the
              unmodified shared library solely because dbus-daemon links it.
   Source:    https://launchpad.net/apparmor
   Pool dir:  pool/main/a/apparmor/
   Copyright: (c) 1998-2010 Novell/SuSE/Immunix and contributors

4. Expat — libexpat1
   Version:   2.6.1-2build1 (Ubuntu Noble, arm64)
   License:   MIT/Expat
   Source:    https://github.com/libexpat/libexpat
   Pool dir:  pool/main/e/expat/
   Copyright: (c) Expat maintainers
   Note:      also shipped by the guest-service-bridge overlay; this overlay
              carries its own copy so its dependency closure is
              self-contained.
