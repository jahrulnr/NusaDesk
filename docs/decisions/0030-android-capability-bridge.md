# ADR-0030: Best-effort Android capability bridge over authenticated loopback TCP

## Status
Accepted — control bridge implemented and device-verified on Samsung arm64
at Android 10/API 29 and Android 12/API 31. The former camera/microphone
artifact proposal in this ADR is superseded by ADR-0031's live-only RTSP
contract; the rest of this ADR remains the control-bridge decision. The
envelope is extended by ADR-0032, which adds one bounded `params` object for the
declaring calendar writes and the `calendar.list` / `calendar.insert` /
`calendar.update` / `calendar.delete` methods; the allowlisted control methods
named below stay otherwise unchanged.

## Date
2026-09-16

## Context

The Ubuntu guest runs through PRoot as the Android app's UID. It cannot call
Android framework services directly: it has no Binder/framework boundary and
SELinux blocks direct access to Android hardware devices. Some Android APIs are
nevertheless useful to Linux automation, while raw camera/audio/GPU/NPU and
system-service semantics are not honest Linux passthroughs.

A device spike proved that a guest process can reach an Android-process listener
on `127.0.0.1`, and that a listener bound to loopback is not reachable through
the device LAN address. The same spike also showed why loopback is not
authentication: another app on the device can share the loopback namespace.
The current runtime already has one user-visible foreground-service lifecycle
and one PRoot session; adding a second permanent bridge service would split
ownership and complicate teardown.

## Decision

Implement a capability-specific bridge inside the existing
`GuestSshdWorkload` session lifecycle:

1. Start an ephemeral IPv4 TCP listener explicitly bound to `127.0.0.1`.
2. Generate a fresh 256-bit random token per guest session.
3. Pass the address, port, protocol version, and token to the guest through
   the PRoot environment; never put the token in argv or logs.
4. Accept one bounded line-delimited request per connection. The protocol is a
   small flat JSON contract with a 16 KiB frame limit, fifteen-second socket
   timeout, four-client concurrency cap, and protocol version `1`.
5. Authenticate every request with a constant-time token comparison and
   dispatch only fixed method names. There is no shell, reflection, URI,
   Binder, or arbitrary Android API dispatch.
6. Ship `bridge.info`, `battery.status`, `sensor.accelerometer`,
   `sensor.gyroscope`, foreground-only `location.get`, the live-only
   `media.start` / `media.status` / `media.stop` controls, read-only
   contacts/call-log/SMS/telephony reads, and foreground-only location stream
   methods. Permission-aware methods return typed permission/error states,
   never open a consent UI from the socket worker, and never add side-effecting
   SMS/phone operations.
7. Maintain a best-effort, app-private projection of the battery snapshot at
   guest `/sys/class/power_supply/battery`. The projection is strictly bound
   for the active PRoot launch, refreshed every two seconds, and removed from
   active use when the session closes. It is not real kernel sysfs and is
   read-mostly rather than a writable control interface. A strict-bound,
   mode-0600 `/run/nusadesk/android-bridge.env` file carries the current
   loopback endpoint and token so guest scripts can consume the RPC without
   guessing a port.
8. The bridge does not bind any camera or microphone artifact directory. Live
   media is a separate explicit control path: the Android host captures and
   encodes camera + microphone and serves a loopback-only RTSP stream; media
   bytes and capture paths never cross the JSON control response. Messaging
   rows remain bounded and redacted.
9. Close the listener, worker pools, every capability source, stream, and
   projection during every supervised teardown path. A failed start closes
   the bridge before returning failure.

The Android manifest's future capability permissions remain declarations until
a capability has its own user-consent flow and implementation. Battery and
accelerometer/gyroscope need none; location/camera/microphone/messaging
methods return explicit permission state and do not request grants from the
bridge socket worker.

## Alternatives considered

### Unix-domain socket as the first transport

Rejected for this slice. It is attractive for lower overhead and no network
port, but the public Android `LocalServerSocket(String)` API uses the abstract
namespace, while the guest bind requires a filesystem socket. A filesystem UDS
would need a separately verified native/NIO implementation on the minimum API
and a complete app-process/PRoot/SELinux device pass. Loopback TCP is already
proven and can be kept safe with the per-session token.

UDS remains a future optimization, not a security boundary or a prerequisite
for additional capability adapters.

### Shared-folder request/response files

Rejected as the primary RPC channel. Shared storage is mutable by other
actors, has poor stream semantics, and is unsuitable for interactive or
frequent reads. Live media is intentionally consumed from RTSP; a consumer
that needs a file saves it itself rather than receiving a host-owned artifact.

### Unauthenticated HTTP or a wildcard/LAN listener

Rejected. `0.0.0.0`, mDNS, Wi-Fi exposure, and a tokenless local endpoint
would expose sensitive Android APIs to processes outside the intended guest.
Loopback plus a per-session token is the minimum contract.

### Generic Android API proxy

Rejected. A generic method/class/shell proxy would turn the guest into an
unbounded host-control surface and make permission, lifecycle, output-size,
and user-consent behavior impossible to review. Each future capability must
have an allowlisted method and a Linux-facing contract of its own.

## Consequences

### Positive

- Battery/status and one-shot accelerometer/gyroscope reads are useful to
  Linux automation without a dangerous runtime permission. The sensor result
  remains a snapshot contract, not a generic `/dev` or streaming interface.
- Live camera/microphone control is deliberately not a byte-returning RPC:
  ADR-0031 uses a user-visible foreground service and a loopback RTSP stream.
  The guest receives a URL and codec metadata, never a host path or capture
  artifact. Physical H.264/AAC consumer verification remains device-specific.
- Foreground location stream is bounded and pull-based with queue backpressure;
  it does not imply background execution or an FGS.
- Existing session supervision owns the bridge, so stop, failure, and restart
  do not leave a second server behind.
- Bounded frames, connections, and responses prevent a guest request from
  consuming unbounded host resources.

### Negative and limitations

- The projection is a snapshot and may be stale between refreshes. Android
  fields do not perfectly correspond to Linux sysfs semantics, and unavailable
  fields use explicit unknown values.
- A guest can read the session token from its own inherited environment. This
  is intentional: all processes in the authorized guest session share the
  capability. The token protects against unrelated device-local clients, not
  against the guest itself.
- The current transport is TCP rather than UDS and has per-request framing
  overhead. This is acceptable for control/status APIs, not for raw media.
- Location is a permission-aware foreground adapter, not a background service:
  permission state is returned to the guest, a user-visible consent flow remains
  outside this socket worker, and a granted request can still be unavailable
  or time out when the device has no usable provider/fix.
- Camera and microphone are now available only through the separately scoped
  live-media control path in ADR-0031. The bridge never prompts for either
  grant; a missing/denied grant or an ineligible background start is typed.
- The live media path was not part of the 2026-09-16 control-bridge probe;
  its positive S10e H.264/AAC RTSP consumer verification is recorded in
  ADR-0031 and the 2026-09-17 test-plan evidence. No artifact behavior is
  claimed.

## Verification

- `AndroidCapabilityProtocolTest` covers strict parsing, limits, escaping, and
  response validation.
- `AndroidCapabilityRequestHandlerTest` covers token authorization,
  allowlisted dispatch, and bounded capability failures.
- `GuestBatterySysfsTest` covers unit mapping, strict bind construction, and
  symlink-safe state handling.
- `SensorReadingTest` and `AndroidSensorManagerSourceTest` cover finite values,
  accuracy mapping, unavailable/timeout/error states, listener cleanup, and
  the bounded one-shot SensorManager adapter on Robolectric.
- `LocationSnapshotTest` and `AndroidLocationManagerSourceTest` cover bounded
  coordinates/optionals, grant-required/denied, provider selection,
  unavailable/timeout/error states, listener cleanup, and fail-closed teardown
  through Robolectric.
- The live `AndroidCapabilityBridge` path was physically verified on Samsung
  arm64 devices at API 29 and API 31: the guest read the session config,
  received Android-backed `battery.status` responses, read matching projected
  sysfs values, received finite accelerometer/gyroscope responses, and
  exercised `location.get` with explicit permission-denied and bounded timeout
  outcomes. The notification Stop action on the API 31 pass removed the bridge
  config/projection after teardown.
- The physical results are evidence for the tested Samsung devices and API
  levels, not a product-wide OEM/API guarantee; continuous sensor streaming
  remains unverified.
