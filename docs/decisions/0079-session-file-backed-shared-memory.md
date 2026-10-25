# ADR-0079: Session file-backed shared memory for native guest apps

## Status

Accepted for the optional native-enabled build after Electron runtime testing
found a fatal missing `/dev/shm`.

## Decision

Android's `/dev` is bound into the PRoot guest and does not provide `/dev/shm`
on the tested device. Creating a directory in the rootfs's hidden `/dev` cannot
fix the effective path. Native launch specs therefore add a nested strict bind
after the Android `/dev` bind: guest `/dev/shm` points to the app-private
`tmp/.nusadesk-shm` directory in the active rootfs.

Preparation reuses an existing real directory and preserves its live files.
The helper rejects a symlink or non-directory at either temporary path, following
the existing bind-confinement requirement. The conventional temporary-directory
mode is applied with the same portable fallback as guest `/tmp`.

This is regular file-backed POSIX shared memory, not a kernel tmpfs mount or
a new Linux namespace. It consumes app storage. It is not a guest GPU driver,
DMA-BUF bridge, System V shared-memory implementation, or Android permission.
Native display and guest process teardown still precede clearing `/tmp`, so its
contents end with the existing guest session (ADR-0027, ADR-0073). The default
build's bind set is unchanged.

## Verification

Focused tests cover preserving files during preparation, deleting them at the
session boundary, recreating an empty directory, rejecting an escaping symlink,
and placing the strict shared-memory bind after `/dev` in native launch specs.
Default and native configurations exercise their respective branches. Physical
mapping and Electron results are recorded in the NusaShell integration report;
the missing-path failure is retained independently of later candidates.
