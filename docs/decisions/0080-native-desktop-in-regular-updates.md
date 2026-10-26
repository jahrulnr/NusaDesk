# ADR-0080: Preserve the native desktop in regular updates

## Status

Accepted by the user on 2026-10-27. Supersedes the default-off and no-public-GUI
packaging boundary in ADR-0068/0069, not their runtime compatibility limits.

## Problem

A desktop-enabled local APK and the default GitHub release shared package ID
and signer. Installing the notified GitHub release succeeded but removed
`libXlorie.so`, disabled native desktop discovery and hid the installed GUI
apps. Linux files were retained. This was a release configuration regression,
not a guest uninstall.

## Decision

Include the native desktop in regular debug/release builds. Keep an explicit
developer-only `-PnativeDesktop=false` override. Both Actions workflows install
the pinned NDK and native build prerequisites. Regular Make builds and release
CI inspect the actual APK for the renderer and native license materials.

Release CI publishes a matching desktop source archive, including the patched
pinned X11 source and recursive submodules, alongside the signed APK. The
updater selects `NusaDesk-<tag>.apk` by name rather than selecting the first
asset, so archive ordering cannot send source bytes to the Android installer.
Distribution terms and build instructions are in `docs/distribution.md`.

GUI dependencies inside the guest still require explicit installation. This
decision adds no second Linux session, automatic GUI installer, performance
claim, GPU driver or expanded supported-device matrix.

## Validation

Focused release-parser tests place a source archive before the APK and reject
unrelated APK names. Python tests exercise actual ZIP inspection and Git source
exports. The APK gate rejects the affected installed 0.14.5 release because its
renderer is absent. 
