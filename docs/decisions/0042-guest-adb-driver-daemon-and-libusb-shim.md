# ADR-0042: Guest adb driver — a session daemon and a libusb shim

## Status

Accepted; the guest driver bundle (`nusadesk-usbd`, `libusb-shim.c`, the `adb`
wrapper) and the patched libusb build it needs landed 2026-09-20, and the
slice is **device-verified on the S10e** with an S7 Edge attached: the stock
guest `adb` listed the phone (`usb:1-1`, model `SM_G935F`) and
`adb -s 1-1 shell getprop ro.product.model` answered `SM_G935F` on repeated
runs (run ids USB-101… in `docs/test-plan.md`).

Deployment reality (2026-09-20): the guest needs `gcc`, the libusb headers,
and a **libusb build with its hotplug monitor downgraded to a warning** —
Android denies the `NETLINK_KOBJECT_UEVENT` socket the monitor needs
(errno 13), and stock Ubuntu libusb turns that failure into
`libusb_init` failing with `LIBUSB_ERROR_OTHER`. The shim supplies hotplug
itself, so the monitor is dead weight; the recipe that works is a
`./configure --enable-shared --disable-static` build of libusb 1.0.27 with
`linux_usbfs.c`'s monitor-failure path changed to set
`no_device_discovery = 1` and continue, installed as
`/opt/nusadesk/lib/libusb-1.0.so.0` (the wrapper prepends that directory to
`LD_LIBRARY_PATH`). Shipping that build as a verified artifact is follow-up
work; today it is compiled in the guest.

## Context

ADR-0041 gave the guest a real usbfs descriptor and proved, on device, that a
purpose-built client can complete the adb handshake and run a shell command on
an attached phone. What it did **not** give the guest is a usable `adb`: the
stock Ubuntu adb opens USB devices itself, and the guest has no `/dev/bus/usb`
at all (Android never exposes usbfs nodes to the app, so there is nothing to
bind and no node to fabricate).

Facts established on the actual guest before choosing a mechanism
(2026-09-20):

- the Ubuntu `adb` (platform-tools 34) links `libusb-1.0`, carries the
  `ADB_LIBUSB` switch, and never touches `libudev`;
- its libusb backend drives every transfer through the libusb API and
  enumerates purely through
  `libusb_hotplug_register_callback(..., LIBUSB_HOTPLUG_ENUMERATE, ...)` plus
  `libusb_handle_events(NULL)` — the binary does not even import
  `libusb_get_device_list`;
- the guest's libusb is 1.0.27, which provides `libusb_wrap_sys_device` — the
  supported way to turn a raw usbfs fd into a libusb handle.

## Decision

1. **Do not patch adb.** The driver is a shim: with `ADB_LIBUSB=1` and an
   `LD_PRELOAD`ed library, the same stock adb talks to virtual devices that
   the shim backs with fds delivered by the host bridge (ADR-0041). Real I/O
   is never reimplemented: after the shim receives a descriptor, it wraps it
   with `libusb_wrap_sys_device` and forwards every handle call (claim,
   clear-halt, transfers, string descriptors, close) to the real libusb.
2. **A guest daemon owns the session's descriptors.**
   `nusadesk-usbd` connects to the capability bridge, opens devices through
   `usb.open` (the host's consent dialog included), keeps one fd per device
   for the whole session, caches each descriptor after the first successful
   open, and serves the shim over the abstract unix socket
   `nusadesk-usbd` with a line protocol: `LIST` → `DEV …`/`END`,
   `OPEN <vid> <pid>` → `OK …` plus the fd via `SCM_RIGHTS` (or `ERR
   <code>`), `PING` → `PONG`. One thread per request, because an `OPEN` can
   block on the host dialog while the shim still polls `LIST`.
3. **The shim fabricates for enumeration and refreshes at open.** `adb
   devices` must not trigger the host consent dialog, so device-inventory
   calls are answered from the daemon's cache (or a canonical adb-shaped
   fabrication: device class 0, one interface `ff/42/01`, two bulk
   endpoints). The moment adb actually opens a device, the shim refreshes the
   virtual descriptor and config descriptor from the wrapped handle, so
   adb's interface/endpoint selection always uses the device's real numbers.
4. **Hotplug delivery lives in `libusb_handle_events`.** The shim registers
   the hotplug callback, polls the daemon at most twice a second, and
   delivers ARRIVED/LEFT callbacks from the event loop thread before
   forwarding to the real `libusb_handle_events` with a 250 ms timeout —
   which is exactly how the ENUMERATE behavior adb expects comes to exist
   without a device list API.
5. **The wrapper keeps shadowing safe.** `/usr/local/bin/adb` sets
   `ADB_LIBUSB=1` and the preload, compiles the shim on first use when `gcc`
   is present, autostarts the daemon (`--ensure`), and otherwise falls back
   to the plain `/usr/bin/adb` — so life without a live session is unchanged.
6. **Platform gates stay visible.** The host's per-attach USB consent dialog
   and the target's "Allow USB debugging?" dialog are the user's decisions;
   the driver never bypasses them. `adb devices` avoids the former until a
   device is actually used, and "always allow" on the target removes the
   latter for good.

Rejected alternatives:

- **Patch or rebuild adb.** A vendored binary fork with a maintenance tail;
  the preload shim achieves the same result on the stock package.
- **Shim libudev/sysfs instead.** adb's libusb backend does not use udev, and
  its non-libusb backend would need `/sys` and `/dev/bus/usb` nodes that the
  guest cannot have; the libusb surface is both real and small.
- **FUSE or bind-mount a fake `/dev/bus/usb`.** Device-node semantics
  (usbfs ioctls) cannot be conveyed through FUSE, and there is nothing to
  bind: the nodes do not exist in the app's mount namespace.
- **Ship the termux-adb patched adb (or patched libusb).** Works for Termux's
  bionic world; here it would mean maintaining a fork and a rebuild pipeline
  for a problem the preload shim solves with the system packages.
- **A lookalike `adb` CLI in front of the ADR-0041 client.** It would fake
  the interface instead of providing it: no `push`, no `install`, no
  protocol parity for third-party tools that call adb themselves.

## Consequences

- Inside the guest, `adb devices` lists devices attached to the host's USB
  port, and `adb shell` / `push` / `install` operate on them — with the stock
  adb, no patched binaries, no network hop. Verified end to end
  (2026-09-20): `usb:1-1 … model:SM_G935F` → `adb shell getprop
  ro.product.model` → `SM_G935F`.
- Device-descriptors are cached by the daemon but **descriptors are not**:
  every `usb.open` hands out a fresh fd, because a usbfs claim lives on the
  opened file and a cached fd lets a stale claim collide with the next
  client's claim (`EBUSY`, observed on device).
- The driver is session-scoped: the daemon's descriptors die with the guest
  session, and the host consent dialog cannot be pre-granted by the product.
- The first use compiles the shim (one `gcc -shared`); without `gcc` the
  wrapper degrades to the plain adb rather than failing.
- Enumeration before a device's first open relies on the daemon's cached (or
  fabricated) descriptor; the visible identity of a device (its serial) is
  read from the device when adb opens it.
- Repeated attaches are cheaper than the first: device-side authorization is
  cached by the target when the user chose "always allow".
