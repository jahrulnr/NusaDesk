# ADR-002: Curated downloaded runtime instead of arbitrary Linux images in MVP

## Status

Accepted. The curated Ubuntu Base ARM64 payload and the curated OpenSSH add-on
are implemented with compile-time pinned SHA-256 digests, and the
download/verify/extract/activate flow is device-verified on one Android 10/API 29
arm64 device (ADR-0006) and install-verified on an x86_64 emulator (API 35,
`docs/test-plan.md`). No signed catalog service or signature verification is
implemented (see Decision).

## Context

The long-term idea may support multiple web apps, but arbitrary Linux images/packages create large compatibility and security surfaces: ABI/libc mismatch, unsupported kernel features, unbounded disk use, malicious payloads, native addon builds, and difficult support diagnostics.

## Decision

The first runtime contract uses a product-owned, versioned catalog with a
prebuilt ARM64 payload. **Integrity is enforced by compile-time pinned SHA-256
digests**, not by a signed manifest: each catalog entry (the Ubuntu Base rootfs
and the curated OpenSSH add-on artifacts) carries a SHA-256 digest pinned in
source (`CuratedRuntimeCatalog`), and the device downloads over HTTPS, verifies
the archive hash against the pinned digest, safely extracts, and atomically
activates it. The device does not compile Go/C/C++ code, run a frontend build,
or repair native npm modules during first launch.

**No signed catalog service or signature verification exists yet.** The pinned
digests are reviewed and updated in source at compile time; there is no remote
signed manifest, no catalog signing key, and no runtime signature check. This
is the implemented model recorded in `docs/limitations.md` ("No remote signed
catalog service exists; the first catalog entry is compile-time pinned"). A
future signed-catalog/signature-verification capability is a separate decision;
until then the trust root is the reviewed source pin plus HTTPS transport, not a
signature.

The catalog may grow to multiple curated app profiles only after one target
passes the device test matrix. Arbitrary URLs/images/shell commands are not the
default API.

## Alternatives considered

### Pull arbitrary OCI images

Rejected for MVP. PRoot-Distro can pull OCI images, but registry resolution, layers, manifests, architecture selection, and arbitrary image trust add complexity that one app does not need.

### Let the device run `apt`, `npm install`, or `npm rebuild`

Rejected for first launch. It makes the result network-dependent, non-reproducible, slow, and vulnerable to platform-specific native build failures.

### Bundle the entire Linux rootfs in the APK

Rejected because it violates the product's on-demand download goal and increases base install size. A small native execution bridge may still need to be packaged in the APK for Android execution policy.

## Consequences

- Positive: deterministic releases, smaller APK, reproducible target behavior, simpler rollback.
- Positive: compatibility labels and per-app capabilities can be tested explicitly.
- Negative: users cannot install arbitrary Linux software in the first version.
- Negative: every supported app/version requires a maintained build and test pipeline.
