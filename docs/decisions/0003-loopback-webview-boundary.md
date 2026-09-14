# ADR-003: Loopback-only WebView boundary for the foundation

## Status

Accepted for the foundation and planned MVP.

**Amended by ADR-0013 (port contract).** The loopback-only origin, the
readiness-gated load, the external-link handoff, and the "no broad JavaScript
interface" decisions below still stand. The port-selection portion — "a
concrete ephemeral port reported by the child after binding" and the rejection
of a fixed well-known port — is **superseded for the shipped single-guest SSH
runtime** by ADR-0013, which fixes the endpoint at `127.0.0.1:22022`. The
ephemeral-port model remains the general guidance for a future multi-tenant host
that does not own exactly one guest; it is not the contract the product ships.

## Context

The target app exposes a local web interface. Android WebView can consume a local server, but localhost reachability alone is not authentication; the host must add an explicit auth token/proxy boundary when the target app exposes sensitive operations.

## Decision

The host will eventually load only an exact, host-generated loopback origin after a readiness health check. The default is `127.0.0.1`. The original foundation text specified a concrete ephemeral port reported by the child after binding; **for the shipped runtime that portion is amended by ADR-0013**, which fixes the single guest SSH endpoint at `127.0.0.1:22022` (no candidate selection, no retry, one documented product constant). The Android host adds an application token/proxy boundary before exposing sensitive target UI.

External links leave the WebView. No broad JavaScript interface or arbitrary remote URL loading is part of the foundation.

LAN/public binding is a separate capability requiring explicit user action, authentication, transport/security review, and Android 17 local-network testing.

## Alternatives considered

### Bind `0.0.0.0` by default

Rejected. It exposes an unauthenticated local service to the network and makes a mobile app's threat model unnecessarily broad.

### Use a fixed well-known port

Rejected as the **general** host contract. Fixed ports create conflicts across
apps and stale processes, and a readiness protocol can publish the actual port.

**Amended by ADR-0013 for the shipped runtime.** The product owns exactly one
guest at a time, so the conflict-avoidance rationale does not apply to it. ADR-0013
fixes the single guest SSH endpoint at `127.0.0.1:22022` (below the Linux ephemeral
range, IANA-unassigned), with no candidate selection and no retry, and a typed
`FAILED` on conflict. The ephemeral-port model above remains the guidance for a
future multi-tenant host that does not own exactly one guest.

### Use an external browser by default

Deferred. External browsers are useful for debugging and remote access, but the product's primary UX is an owned WebView with an explicit origin allowlist.

## Consequences

- Positive: narrow browser surface and no port conflict requirement in the host
  (under the ephemeral model; ADR-0013's fixed port for the shipped single-guest
  runtime instead makes a conflict a typed `FAILED`, by design).
- Positive: target apps can keep their own HTTP/WebSocket/SSE UI.
- Negative: target apps must support configurable host/port or an adapter.
- Negative: a token/proxy layer is extra work for targets designed for trusted localhost use.
