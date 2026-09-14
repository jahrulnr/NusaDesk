# Native bridge packaging note

This directory intentionally contains **no JNI source**. The Android execution
bridge selected by ADR-004 is PRoot, shipped as a standalone PIE executable
packaged as `../jniLibs/arm64-v8a/libproot.so`.

## Why an executable is named `libproot.so`

Android's package installer extracts native libraries from the APK `lib/<abi>/`
area into the app's native-library directory, which is read-only and
executable. On Android 10+ (target SDK 29+) direct `execve()` of files in
writable app data is restricted, so the only standard contract for an app to
run a bundled native program is to place it in `jniLibs/<abi>/` and let the
installer chmod it executable (ADR-004).

`libproot.so` is therefore a **PIE executable renamed to look like a shared
library**, not a JNI library. It is not loaded with `System.loadLibrary`; the
Android host `execve`s it with a fixed argv (no arbitrary shell strings, per
AGENTS.md). PRoot is a compatibility layer, not a security sandbox.

## Reproducible build

The binary is built from pinned, source-reviewed upstream by
`scripts/build-proot-arm64.sh`, which records exact revisions, licenses, and
patches. See `docs/research/proot-arm64-build-spike.md` for provenance,
reproducibility evidence, license obligations, and unresolved blockers.
