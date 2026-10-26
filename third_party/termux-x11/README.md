# Experimental native display dependency

Source: https://github.com/termux/termux-x11
Revision: `7e5f5c5c9226e2cdab392b2efba91bc4bd86463e` (2026-10-23).
License: GPL-3.0; the full upstream license is adjacent. The Xorg and other
native submodules retain their own file notices/licenses. The submodule
revisions are recorded in `submodules.lock` and the upstream Git tree.

This experimental component is included in regular builds (ADR-0080).
Developers can omit it with `-PnativeDesktop=false`; no native binary is
committed here. The regular APK contains GPL-linked code and is distributed
under GPL-3.0 terms, with native notices and a matching desktop source archive.
See `docs/distribution.md`. NusaDesk's own-source MIT notices remain intact.

Build: install NDK 29.0.14206865, CMake, Ninja, Python 3, Bison (including its
package data), m4, patch and Git. Set `ANDROID_SDK_ROOT` or `sdk.dir`, then run
`./gradlew -PnativeDesktop=true assembleDebug`. The Gradle task invokes
`scripts/build-x11-arm64.sh`. `X11_BUILD_ROOT` optionally relocates the build
cache; `PYTHON` and `BUILD_JOBS` select the host interpreter and parallelism.
The script checks the Git SHA, initializes exact submodule revisions, applies
`nusadesk.patch`, and checks ARM64 ELF and 16 KB LOAD alignment before staging
`libXlorie.so` under the generated build directory.

The patch adapts JNI class names to NusaDesk's Java infrastructure, replaces
CriticalNative-only callbacks with the ordinary JNI calling convention, removes
upstream Activity freeform reflection, removes the wildcard TCP knock listener
(the renderer FD is delivered through private Binder), and initializes the CPU
mask before use. Xorg runs in a private bound Android process so its fatal exit
cannot terminate the launcher. No upstream Java UI, manifest, Kotlin or Compose
is imported.

Vet rationale: an active, Android-native Xorg integration with a successful
upstream CI build for the pinned revision, an established PRoot shared-tmp
contract, and a software presentation fallback. Device behavior and limits are
recorded in the native display evidence ledger; source availability/CI is not
runtime proof. Inclusion in regular builds does not close experimental
compatibility gates.
