---
name: runtime-download-and-integrity
description: Use when adding runtime/rootfs/app payload downloads or updates; enforce signed catalogs, resumable HTTPS transfer, ABI checks, safe extraction, atomic activation, rollback, and no arbitrary executable loading.
---

# Runtime download and integrity

Use this skill for a future curated payload pipeline. It does not authorize arbitrary image/package installation.

## Contract

A payload is eligible for activation only when all of these are true:

- catalog entry is allowlisted and version-pinned;
- HTTPS transfer completes;
- signature or securely stored SHA-256 digest matches;
- target ABI and format match the device/guest contract;
- archive passes path/type/size validation;
- extraction completes in a private staging directory;
- post-extract manifest and health checks pass.

## Download flow

1. Resolve a signed catalog entry; never accept an arbitrary URL as the default API.
2. Check available disk space before downloading and again before extraction.
3. Download to a temporary file with bounded progress, cancellation, and resume support where the server contract allows it.
4. Verify the complete payload before extraction. A partial file is never executable.
5. Extract into `.<version>.staging`, not the active directory.
6. Reject absolute paths, `..`, path separators that escape the root, unsafe symlinks, device nodes, sockets, FIFOs, unexpected file types, and excessive file counts/size.
7. Validate an internal manifest and expected entrypoint/health contract.
8. Atomically activate the complete version. Keep the last known-good version until the new version is healthy.
9. On any failure, delete staging and leave the active version untouched.
10. Record version, digest, source, verification result, and failure code without logging secrets.

## Update rules

- Pin exact app/runtime versions for reproducible support.
- Do not run `npm rebuild`, C/C++ compilation, frontend builds, `apt upgrade`, or package-manager repair during first launch.
- Do not replace a running version in place; start a new version, verify it, then switch ownership.
- Rollback is a first-class path, not a manual filesystem trick.
- App-private internal storage is the default. Shared storage is not a trust boundary and may be `noexec`.

## Supply-chain checklist

Record source repository, commit/tag, build environment, license notices, digest, native ABI, libc contract, and transitive native modules. Re-check these when the target app version changes.

## Verification

Test success, cancellation, resume, timeout, hash mismatch, bad signature, wrong ABI, traversal archive, escaping symlink, low disk, interrupted extraction, failed health check, update rollback, and uninstall/reinstall behavior.
