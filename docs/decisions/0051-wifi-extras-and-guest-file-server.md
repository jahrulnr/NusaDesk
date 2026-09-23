# ADR-0051: Wifi extras (hotspot, suggestions, lock) and the guest file-server toolkit

- Status: accepted
- Date: 2026-09-23

## Context

The permission recheck left `CHANGE_WIFI_STATE` declared but inert: the only
method that needed it, `wifi.set`, always answers the typed
`wifi-toggle-unsupported` because Android 10+ removed third-party wifi
toggling. The user's requirement for the wifi domain is explicit: the surface
should be rich — control, transfer, and server use cases — while staying inside
what the platform actually allows.

The platform matrix (`docs/research/platform-capability-matrix.md`) separates
the domain cleanly:

- hard limits: programmatic toggle (`setWifiEnabled` is always false for a
  target-29+ app), saved-network enumeration (`getConfiguredNetworks` returns
  an empty list), and internet tethering (`TetheringManager` is
  `@SystemApi` through API 35);
- possible: local-only hotspot (`startLocalOnlyHotspot` — a hotspot with no
  internet, exactly right for device-to-device), network suggestions
  (advisory, the platform decides auto-join), WifiLock, and the read surfaces
  already shipped (`wifi.connectioninfo`, `wifi.scaninfo`).

The classic use case behind "server/FTP" is the phone serving files to another
machine on the same network. Python's standard library has no FTP server, so
the guest-side answer is an HTTP file server with upload — the same transfer
job, no dependency.

## Decision

1. **`WifiExtrasModule`** adds three bounded groups under the existing
   `wifi.*` namespace:
   - `wifi.hotspot.start|stop|status` — a local-only hotspot (no internet)
     through `startLocalOnlyHotspot`, with a bounded wait on the platform
     callback and one reservation at a time. The observed SSID/passphrase are
     reported verbatim; in-use/incompatible-mode, unsupported, and permission
     states are typed errors, and the platform's `onStopped` clears the
     reported state.
   - `wifi.suggest.add|remove|list` — `WifiNetworkSuggestion` entries
     (ssid/passphrase/priority/hidden), capped at 20 rows on read. The platform
     decides whether a suggestion is ever used; nothing here forces a join.
   - `wifi.lock.acquire|release` — one `WIFI_MODE_FULL_HIGH_PERF` lock so a
     long transfer survives the screen going off, released by `close()` too.
2. **`wifi.set` stays a typed absence.** The declaration behind it is no
   longer dead: `CHANGE_WIFI_STATE` now backs the hotspot, suggestion, and
   lock paths, which is what the platform actually permits.
3. **Guest server toolkit, generated like every managed guest file.**
   `nusadesk-serve` starts a stdlib HTTP file server (directory listing plus
   bounded PUT/POST upload with traversal rejection), prints one
   `http://<lan-ip>:<port>/` URL per non-loopback address, and makes two
   best-effort bridge calls — `wifi.lock.acquire` and a `notification.post`
   carrying the URLs — so the transfer keeps the radio awake and the user can
   see and stop it from the shade. `nusadesk-serve stop|status` manages the
   daemon through a pid file. `nusadesk-net` exposes `lan-ip` (ioctl-based, no
   subprocess) and `bridge` (address/status from `bridge.info`, never the
   token).
4. **Everything is bounded and honest.** The hotspot start waits at most 15 s;
   uploads are capped (512 MiB) with `..`/separator/NUL rejection and a
   realpath-under-root check; the server prints a loud no-authentication LAN
   warning on start; every bridge call the script makes is optional and its
   failure is ignored, so the server never depends on the bridge being up.
5. **P2P and RTT are deliberately deferred.** Wi-Fi Direct and RTT ranging are
   possible but OEM-sensitive and heavier than the use case needs; they stay
   documented as separately scoped work rather than shipped half-verified.

## Consequences

- The wifi surface is no longer read-only: a guest script can create a
  device-to-device hotspot, suggest networks, hold the radio awake, and serve
  the workspace over HTTP to a laptop — with the platform's hard limits still
  reported as typed absences.
- The "server" use case has a real end-to-end path: `nusadesk-serve start`
  prints the URL, the notification carries it, and the lock keeps the transfer
  alive with the screen off.
- HTTP is the shipped protocol; FTP is not, because no stdlib FTP server
  exists. A guest that installs one (or `pyftpdlib`) can still bind it to the
  same LAN address — the capability boundary is the network, not the tool.
- Unit coverage: `WifiExtrasModuleTest` (26), `GuestServeCliWriterTest` (6,
  including a real fork + HTTP round trip). Device verification on the S10e is
  the acceptance gate and lands in `docs/evidence/` when it runs.

## Device evidence

Verified on the S10e (SM-G970F, Android 12/API 31) on 2026-09-23/24 through
the live guest: `wifi.hotspot.start|stop` (a real `AndroidShare_9086` hotspot
with its passphrase; a dozing device answered the typed refusal instead),
`wifi.suggest.add|list|remove` — where the first device run found a real
defect: `remove` submitted a freshly built suggestion and the platform matched
nothing, so the method now submits the object stored in
`getNetworkSuggestions()` and the add→list→remove round trip is clean —
`wifi.lock.acquire|release`, and the `nusadesk-serve` end-to-end path (a
`GET` from the host over the LAN returned 200, a `PUT` returned 201 with the
exact bytes landing in the guest, `status`/`stop` clean). Full observed output
in `docs/evidence/capability-closure-matrix.md`.
