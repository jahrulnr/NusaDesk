# Native desktop APK distribution

Regular NusaDesk APKs include GPL-3.0 Termux:X11/Xlorie code. Distribution of
that combined APK follows GPL-3.0 terms; it must not be described as MIT-only.
The repository's own code retains its MIT license and copyright notice.
Dependency-specific notices and licenses remain applicable. The full GPL text
is in `third_party/termux-x11/LICENSE` and is packaged in the APK at
`assets/licenses/native-display/Termux-X11-GPL-3.0.txt`.

The integration review retains Xlorie's GPL-3.0 notice, the existing MIT host
source grant, and the Xorg/submodule notices. The native build copies dependency
notices into the APK. Source pins and the patch remain tracked. This records
the project's distribution choice and materials; it makes no legal-clearance
or Android-store approval claim.

Release Actions attach `NusaDesk-<tag>-source.tar.gz` beside the signed APK.
Debug Actions include the equivalent source archive in the APK artifact.
It contains `nusadesk/` (tracked project sources, build scripts and notices)
and `termux-x11/` (the exact native working tree with the NusaDesk patch already
applied, including tracked files from initialized recursive submodules).
Untracked signing material, native build outputs and Git metadata are excluded.
The repository's pinned Maven/Gradle dependencies and existing PRoot source/build
instructions remain in `app/build.gradle` and `scripts/build-proot-arm64.sh`.

To build the host, install JDK 17, Android platform 37.0, build-tools 36.0.0,
NDK 29.0.14206865, CMake, Ninja, Meson, pkg-config, curl, Python 3, Bison, m4, patch and Git. Configure
the SDK using `ANDROID_SDK_ROOT` or `sdk.dir`, then run `make check`. The normal
native build script fetches the pinned upstream tree, checks recursive source
revisions, applies the tracked patch, and builds the renderer. The archived
`termux-x11/` tree supplies the corresponding patched source for inspection or
building directly with the CMake invocation in `scripts/build-x11-arm64.sh`.
Release signing uses the distributor's key; development builds need no private
release key. See `third_party/termux-x11/README.md` for native build details.

GUI remains experimental. Including it in regular updates preserves installed
functionality; compatibility and guest prerequisites remain documented in
`limitations.md`.

The native build also compiles virglrenderer 1.3.0 with libepoxy 1.5.10 from
digest-pinned archives. Both are MIT licensed; notices ship under
`assets/licenses/virglrenderer/`. The source companion contains the tracked
build script and Android patch; their archive URLs and SHA-256 pins provide
the upstream MIT sources. See `third_party/virglrenderer/README.md`.
