# ADR-0041: USB pass-through by descriptor delivery (`usb.list` / `usb.open`)

## Status

Accepted; the Android bridge slice and the guest `nusadesk-usb` CLI landed
2026-09-20, and the slice is **device-verified on the Samsung S10e** acting as
USB host with an S7 Edge attached: `nusadesk-usb probe 04e8:6860` printed
`probe ok: idVendor=04e8 idProduct=6860 bcdUSB=0x0200`, with the fd's usbfs
node visible to a child process and the device's own descriptor strings read
through it (`manufacturer 'SAMSUNG'`, `product 'SAMSUNG_Android'`; run ids
USB-001… USB-005 in `docs/test-plan.md`).

## Context

Android's userspace USB model gives an ordinary app no device nodes: there is
no app-visible `/dev/bus/usb`, and nothing in the filesystem namespace to hand
to the Linux guest. The platform instead hands an app a `UsbDeviceConnection`
after the user approves a per-device consent dialog, and that connection's
usbfs descriptor is the only real endpoint — every transfer an app performs on
USB is an ioctl on that descriptor.

The guest runs under PRoot, a ptrace-based syscall shim over the app's own
process: it has no USB bus of its own, no usbfs, and no way to open one. On
2026-09-20 a Samsung S10e in USB-host mode enumerated an attached S7 Edge
(`04e8:6860`, ADB interface active) and the platform even offered its own USB
handler chooser (Gallery, Smart Switch, Host MTP) — the bus, the device, and
the app-level access path all existed, but the guest had no route to any of
it. Delivering the descriptor into the guest is the smallest mechanism that
turns that enumeration into something a guest script can use; a network
side-channel cannot, because ADB needs the USB endpoint itself, not a socket
to us.

## Decision

1. **Exactly two bridge methods, no transfer RPC.** `usb.list` enumerates
   attached devices for scripts (one pre-encoded JSON array plus a count).
   `usb.open` performs the consent flow, opens the device, and delivers its
   descriptor. There is deliberately no host-side control/bulk transfer
   method: once the descriptor is in the guest, interface claims and URB
   submission are the guest's business, and the product does not grow a
   second, partial USB stack.
2. **The platform's consent dialog is the only grant path.** `usb.open`
   waits bounded (60 s) for the user's answer; a denied answer and an ignored
   dialog are distinct typed results (`usb-permission-denied`,
   `usb-permission-timeout`), and nothing opens silently. There is no
   remembered grant beyond the platform's own per-device permission.
3. **The descriptor travels over SCM_RIGHTS.** The guest creates an abstract
   unix socket (`nu-usb-<12 hex>`), where the name is a bounded parameter
   (`[A-Za-z0-9._-]`, at most 64 chars) — abstract sockets carry no
   filesystem state and die with the process. The app connects, duplicates
   the usbfs fd (`ParcelFileDescriptor.fromFd`, so the *duplicate* travels and
   the app's own descriptor stays untouched), sends one byte with the
   descriptor attached, and closes its duplicate. The app keeps its
   connection for the bridge session and releases every opened connection in
   `close()`.
4. **Parameter discipline stays behind the existing allowlist.** `usb.open`
   is the only USB method that declares `params` (`vendorId`, `productId`,
   `socket`); both ids must be 16-bit, the socket name must match the bounded
   shape, and any other key or shape is `invalid-argument`. `usb.list`
   declares nothing, so a params object on it is `unsupported-parameter`.
5. **The guest side is our own CLI.** `nusadesk-usb` (Python 3, stdlib only)
   offers `list`, `probe <vid>:<pid>`, and
   `exec <vid>:<pid> -- <command>`; it shares `nusadesk-android`'s bridge
   transport. `probe` is the proof command: it takes the delivered descriptor
   and issues `USBDEVFS_CONTROL` GET_DESCRIPTOR (18 bytes) through `ctypes`,
   printing `idVendor`/`idProduct`. `exec` runs a command with
   `NUSADESK_USB_FD` set and the descriptor passed through, so a patched adb
   client (the termux-adb pattern) can consume it on the guest side.
6. **Pattern provenance.** The "guest receives a USB fd over a unix socket
   from the app" pattern follows termux-usb / termux-api. Nothing is copied:
   the termux client package is MIT (notice obligations apply if we port
   anything from it) and the termux app side is GPLv3, which this repository
   does not copy — the Android side here is our own implementation of the
   documented pattern.

Rejected alternatives:

- **Host-side transfer RPC** (the app performs control/bulk transfers on
  behalf of the guest). Recreates a partial USB stack with its own bounded
  surface, locks guest scripts to the host-mediated subset, and buys nothing
  the descriptor does not already provide.
- **Require the separate termux-usb/termux-api apps.** The product owns one
  app and one guest session; requiring two more apps (with their own signing
  keys and a GPLv3 app-side component) breaks that model outright.
- **Bind `/dev/bus/usb` into the guest.** There are no app-visible usbfs
  nodes on Android to bind; this is a platform boundary, not a missing bind.
- **A path-based unix socket under the workspace bind.** Needs on-disk state
  and a bind mount; abstract sockets have no footprint and their lifetime is
  already the bridge session's.

## Consequences

- Guest scripts can now talk to USB devices the user approves — including
  driving a second phone through a guest-side adb client assembled from
  `nusadesk-usb exec` plus a USB-fd-capable transport. The consent dialog and
  per-device platform permission remain the gate, and the app never opens a
  device silently.
- Descriptor lifetime: the guest's copy stays valid while any reference
  remains open (the guest's own, plus the app's connection until the bridge
  session closes); closing the session releases the app side. Revocation
  (unplug, user revoke, process death) invalidates the usbfs file itself.
- No host-mode or gadget control is added: the app cannot force a port into
  host mode, provide VBUS, or make the phone act as a USB device; those stay
  platform/OEM territory (see `docs/limitations.md`).
- Verification scope: on device (run ids USB-001…USB-008 in
  `docs/test-plan.md`) the delivered fd exposed a real attached phone's
  descriptors, carried the full adb handshake (token → public key → on-device
  consent dialog → `CNXN`), and ran a shell command on that phone from inside
  the guest — so both the ioctl path and the bulk-transfer path are proven
  through the ptrace shim. The device work also pinned two protocol rules
  worth keeping next to this ADR: the adb header and payload must be separate
  USB transfers (a concatenated write desynchronizes adbd until a replug),
  and a never-seen key is authorized by sending `AUTH(RSAPUBLICKEY=3)`, which
  is what raises the dialog. A productized guest adb client (the termux-adb
  style transport on top of `nusadesk-usb exec`) remains application work.
