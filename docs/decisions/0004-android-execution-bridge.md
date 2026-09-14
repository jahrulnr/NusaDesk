# ADR-004: Keep the guest payload out-of-band, package only the Android execution bridge

## Status

Accepted as a constraint; concrete bridge selection is deferred to Phase 1.

## Context

Android 10+ restricts direct execution of files stored in writable app data for modern target SDKs. The product requirement also says that the Linux rootfs and target application should be downloaded on demand rather than bundled into the base APK.

A literal “zero runtime component in the APK” design would either depend on a separately installed runtime or require direct execution of downloaded binaries, which is not a reliable modern Android contract.

## Decision

The future app may package the smallest necessary Android-native execution bridge/loader in `jniLibs/<abi>/`, while downloading the Linux ARM64 rootfs and target application as verified data. The bridge must be source-available or otherwise redistributable under an approved license, reproducibly built, 16 KB-page compatible, and tested on the supported Android matrix.

The bridge is a compatibility mechanism, not a security sandbox. Guest payloads remain curated and signed; arbitrary downloaded code is not treated as safe merely because it runs through the bridge.

## Alternatives considered

### Direct `ProcessBuilder` on a downloaded executable

Rejected. It conflicts with the Android writable-app execution restriction and would fail on modern target SDK/device combinations.

### Require users to install Termux separately

Useful as a research/reference path, but rejected as the product baseline because the goal is one self-contained Android app.

### Full QEMU/AVF VM

Deferred. A VM can improve environment fidelity or isolation, but it has higher memory/startup/maintenance cost; AVF's normal Java APIs are not available to ordinary third-party apps.

### Bundle the entire rootfs in the APK

Rejected. It violates the on-demand download goal and makes the base installation unnecessarily large.

## Consequences

- Positive: honors on-demand rootfs delivery while keeping Android execution technically viable.
- Positive: bridge and guest payload can be versioned/tested independently.
- Negative: native code creates ABI, page-size, licensing, and Play-review obligations.
- Negative: the exact bridge remains the highest-risk unresolved implementation decision until the device spike passes.
