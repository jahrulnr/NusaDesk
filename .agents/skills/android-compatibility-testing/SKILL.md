---
name: android-compatibility-testing
description: Use when validating Android runtime, foreground-service, native-loader, storage, process, port, or WebView behavior across API levels, ABIs, page sizes, OEMs, and background states.
---

# Android compatibility testing

Use this skill for device-test planning and release gates. Emulator-only evidence is insufficient for native execution/lifecycle work.

## Evidence boundary

Record which configurations have device-verified runtime behavior and which are emulator-only or untested. Do not claim support for a configuration that has no recorded device run. An emulator's ARM translation is not runtime evidence for an ARM target. Mark each configuration `supported`, `untested`, or `unsupported` rather than implying blanket coverage.

## Minimum matrix

- Android 10/API 29 floor;
- Android 13/API 33 process/background behavior;
- Android 15/API 35 with 16 KB testing where available;
- Android 16/API 36 local-network opt-in behavior;
- Android 17/API 37 local-network/dynamic-code behavior;
- arm64-v8a physical device;
- Pixel plus at least one Samsung/Xiaomi/Oppo-class OEM when possible;
- x86_64 emulator only if that ABI is explicitly supported.

## Test dimensions

### Installation and payload

- clean install;
- no network;
- slow/interrupted/resumed transfer;
- wrong digest/signature;
- wrong ABI/libc;
- low disk;
- archive traversal/symlink/device-node rejection;
- update failure and rollback;
- uninstall/reinstall state behavior.

### Native/process behavior

- downloaded execution through the chosen bridge, never direct writable-app `execve`;
- 4 KB and 16 KB page-size devices;
- cold/warm start;
- PID without readiness;
- port conflict;
- malformed readiness;
- graceful stop and child-tree cleanup;
- Android process kill, force-stop, screen-off, Doze, reboot, and OEM battery restrictions.

### WebView

- exact origin allowlist;
- HTTP, WebSocket, and SSE;
- runtime restart/reconnect;
- external-link handling;
- rotation, font scale, 320dp and tablet layouts;
- no console errors, unsafe bridge, or secret leakage.

## Evidence

For every device run record: app build, runtime/app version, Android release/API, ABI, page size, OEM/model, available disk/RAM, network condition, state transitions, endpoint, logs, and screenshots. Mark `supported`, `untested`, or `unsupported`; do not hide a failed device behind a generic pass rate.

## Exit gate

No open high-severity auth, integrity, data-loss, process-orphan, or execution-policy defect. All P0/P1 cases pass on the declared support matrix. Visual and interaction checks are inspected on a real device/emulator for UI changes.
