# ADR-006: Pinned Ubuntu Base install proof before execution

## Status

Accepted for the first proof slice.

## Context

The product needs evidence on a real Android device before choosing the execution bridge or promising a target app. The user explicitly asked to implement what is certain and research the uncertain parts in parallel. Downloading and executing arbitrary Linux images is out of scope; the first payload must be a product-owned, version-pinned catalog entry.

## Decision

Implement one curated Ubuntu Base ARM64 installation path before runtime execution:

- pin Ubuntu Base 24.04.5 ARM64 URL, guest ABI, exact compressed/uncompressed size budgets, and SHA-256 digest;
- download over HTTPS into app-private temporary storage;
- verify the complete payload before extraction;
- extract into a versioned staging directory with traversal, link, type, entry-count, size, and rootfs manifest checks;
- activate atomically under `files/linux-wrapper/runtimes/<app-id>/active`;
- keep an existing active version while activating a replacement and retain a previous slot for rollback;
- persist explicit progress/failure/ready states; never equate `READY` with a running process;
- do not execute any extracted binary until the execution-bridge spike is separately accepted.

The initial target-app direction is a server-side SSH bridge rendered through xterm.js, but it remains a research boundary. xterm.js is a terminal frontend; it is not an SSH client or Linux runtime.

## Alternatives considered

### Install Debian with device-side `debootstrap`/APT

Deferred for the first proof. Debian's official `debootstrap` path is useful for future reproducible build tooling, but an on-device package-manager flow adds mutable mirrors, network-dependent package resolution, and a larger first-launch surface. A pinned Debian rootfs may be added after the Ubuntu install/execution boundary is proven.

### Execute immediately after extraction

Rejected. Android 10+ W^X restrictions prohibit the naive writable-app `execve` path. The bridge/loader and process contract need their own device spike.

### Accept arbitrary user URL/image

Rejected. The product boundary is an allowlisted, versioned catalog; arbitrary payloads would turn the install action into an unsafe code-delivery API.

## Consequences

- Positive: the team has a concrete device-tested artifact path and a truthful installed state before solving the highest-risk execution problem.
- Positive: the rootfs is reusable by a later execution bridge and target profile without changing the download contract.
- Negative: `READY` currently means verified/activated rootfs, not a running guest.
- Negative: the first Activity-coordinated installer still needs a durable application/service coordinator for rotation, cancellation, resume, and background survival.
- Negative: xterm.js/SSH, PTY, host-key storage, reconnect/session persistence, and WebView integration remain open research/implementation work.
