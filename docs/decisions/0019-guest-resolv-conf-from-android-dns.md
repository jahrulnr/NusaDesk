# ADR-0019: Guest resolv.conf bridged from Android active-network DNS

## Status

Accepted and implemented in `domain/network`, `infrastructure/network`, and the
PRoot launcher. Device-verified for the `apt update` DNS regression on the
Samsung SM-G935F (Android 10, arm64, 4 KB pages).

## Context

The curated Ubuntu Base ARM64 rootfs ships no usable `/etc/resolv.conf`, and
PRoot does not virtualise DNS. Inside the guest, `apt update` therefore failed
with `Temporary failure resolving 'ports.ubuntu.com'`, and any guest network
code that resolves a domain failed the same way. The host, however, runs on an
Android device with a working active network and a DNS list available from
`ConnectivityManager.getLinkProperties(activeNetwork).getDnsServers()`.

The product rules (AGENTS.md) forbid a hardcoded public-DNS-only workaround
when Android DNS is available, forbid mutating the active rootfs, forbid
broad host-filesystem binds, and require the guest to keep working gracefully
when DNS is unavailable.

## Decision

1. **Read Android's active-network DNS, never hardcode it.**
   `AndroidActiveNetworkDns` reads
   `ConnectivityManager.getActiveNetwork()` + `getLinkProperties().getDnsServers()`
   and returns the IP literals (scope IDs stripped). There is no public-DNS
   fallback: the guest uses the network Android actually routes on.

2. **Validate and bound the resolver in the domain layer.**
   `domain/network/ResolvConf` is a pure, Android-free value object that accepts
   only literal IPv4/IPv6 nameserver lines, rejects hostnames, ports, brackets,
   whitespace, zone/scope IDs, and null bytes, deduplicates, and caps the count
   at `MAXNS` (3). It returns `null` when no candidate is a valid literal.

3. **Write one app-private file atomically and bind it, do not mutate the rootfs.**
   `GuestResolvConfWriter` (Android-free `java.nio`) writes the validated text
   to a temp file in the same directory and moves it over the target
   (`ATOMIC_MOVE` with a `REPLACE_EXISTING` fallback), bounded to 512 bytes. A
   failed write deletes the temp file and never touches the last known-good
   resolver. `GuestDnsResolver` owns the fixed file under the app cache dir and
   returns a `ProotBindMount` of that file over the guest `/etc/resolv.conf`.

4. **Apply the bind consistently from the single PRoot chokepoint.**
   `ProotLauncher.buildSpec` adds the resolver bind to every spec (setup,
   daemon, deb extraction, and the on-device probe), so all guest paths resolve
   consistently. The host file is app-private, so it passes the existing
   app-private bind policy; no broad host filesystem is exposed.

5. **Bind only when valid; otherwise keep the guest's own resolver.**
   When Android has no valid DNS, `resolverBind()` returns empty, no bind is
   added, and the guest keeps whatever its rootfs ships. The runtime still
   works; only DNS is unavailable. This is the graceful degradation the rules
   require.

6. **Refresh while the runtime runs.**
   `GuestDnsResolver.startRefresh()` registers a default-network callback
   (`registerDefaultNetworkCallback`, API 24+) that rewrites the same file on
   network/LinkProperties changes. Because the file is bind-mounted (a
   reference, not a copy), glibc re-reads the updated resolver in place. The
   long-lived `GuestSshdWorkload` starts the refresh when the daemon becomes
   supervised and stops it on every teardown/stop path. Transient PRoot
   callers (deb extractor, probe) do not refresh.

7. **Add `ACCESS_NETWORK_STATE`.**
   Reading the active network and its LinkProperties requires this normal,
   non-dangerous permission. It enables no network I/O of its own and has no
   broad storage/LAN/battery scope.

## Consequences

- The guest resolves domains through Android's current network, including
  captive-portal and VPN-provided DNS, without a hardcoded fallback.
- The active rootfs is never mutated for DNS; the resolver is a host file
  bound over the guest path, so a failed write cannot corrupt a working rootfs.
- The resolver file contains only validated literal nameserver lines — it
  cannot carry an injected directive or a hostname.
- When Android has no DNS, the guest keeps its own (possibly empty) resolver;
  `apt update` then fails with a real DNS error rather than a fake success.
- The lifecycle refresh keeps the bound file current across Wi-Fi/cellular
  switches while the daemon runs; a registration failure is logged and the
  runtime continues with the resolver written at start time.

## Open

- 16 KB page-size devices, API 33/35/36/37, and OEM-specific
  `ConnectivityManager` behaviour remain on the compatibility matrix, not
  device-verified for this slice.
