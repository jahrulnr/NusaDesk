# Research findings and constraints

Research snapshot: 13 September 2026. This document records the external findings that shaped the base scaffold. It is not a claim that the unimplemented runtime is already compatible with every Android device or app.

## Feasibility verdict

The product idea is technically feasible as a **curated Android host for a downloaded ARM64 Linux web app**, but it is not equivalent to running arbitrary Linux desktop software. The likely implementation is an Android host plus a small packaged execution bridge and a downloaded Linux userland/app payload.

## Android execution constraints

Android 10 behavior changes state that apps targeting API 29+ cannot directly invoke `execve()` on files in the writable app home. See [Android 10 behavior changes](https://developer.android.com/about/versions/10/behavior-changes-10).

Termux documents the relevant distinction: native Termux packages are built for Android/Bionic, not glibc; PRoot/QEMU/chroot are separate compatibility paths. See [Termux execution environment](https://github.com/termux/termux-packages/wiki/Termux-execution-environment).

Termux's `termux-exec` workaround redirects execution through the Android system linker. Its documentation also notes limitations for static binaries and programs that invoke raw syscalls rather than libc exec wrappers. See [termux-exec](https://github.com/termux-play-store/termux-exec).

**Design consequence:** a modern app should not simply extract a Linux executable into `filesDir` and call `ProcessBuilder` on it. The execution bridge must be tested on Android 10+ and should normally keep the loader/native bridge in the APK's native library area while treating the downloaded rootfs as data.

Android ABI names are not Linux distribution identities. `arm64-v8a` identifies Android AArch64; the guest still needs a matching Linux ARM64 userland and libc. See [Android ABIs](https://developer.android.com/ndk/guides/abis).

## Linux runtime options

### Termux native/Bionic

Termux is an Android terminal and Linux environment that runs native Android packages and provides Node.js/Python packages. It is a good model when the target app has Android/Bionic-compatible packages, but it is not a generic glibc Linux environment.

### PRoot

PRoot-Distro provides a rootless, chroot-like Linux userland and can use OCI images or rootfs archives. It does not require root, a kernel module, or a Docker daemon. See [PRoot-Distro](https://github.com/termux/proot-distro/blob/master/README.md).

PRoot shares the Android kernel and is not a security VM. Its documented limitations include syscall overhead, no real kernel root, no normal systemd/init, and no real namespaces/cgroups/seccomp isolation. It is therefore suitable for compatibility and packaging, not hostile-code containment.

### Full VM / AVF

A full QEMU VM can provide a more faithful Linux kernel environment, but it adds significant memory, startup, storage, and maintenance cost. AVF is designed for stronger isolation, but the documented Java APIs are `@SystemApi` and require restricted permission; they are not a normal third-party app API. See [AVF overview](https://source.android.com/docs/core/virtualization) and [AVF API](https://android.googlesource.com/platform/packages/modules/Virtualization/+/HEAD/libs/framework-virtualization/README.md).

## Storage and integrity

Use internal app-specific storage for runtime state and mutable data. Android documents that internal app-specific files are private to the app, encrypted on Android 10+, and removed on uninstall. See [Access app-specific files](https://developer.android.com/training/data-storage/app-specific).

Do not use shared/public storage for the executable rootfs. External storage is mutable by other apps and commonly has `noexec`/filesystem limitations. Treat all downloaded content as untrusted until its signature/hash and archive structure are checked. See [Dynamic Code Loading](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading).

Android 15+ introduces 16 KB page-size devices. Any bundled native loader, JNI bridge, or packaged `.so` must be built and packaged for 16 KB compatibility. See [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes).

## Lifecycle and networking

Android does not give an application full control over process lifetime. A foreground service makes user-visible work more important, but it is not an unconditional 24/7 guarantee. See [Processes and app lifecycle](https://developer.android.com/guide/components/activities/process-lifecycle).

Android 14+ requires foreground-service types and matching permissions. `specialUse` exists for valid long-running uses not covered by the standard types, but the subtype and use case are reviewed in Play Console. See [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) and [Play foreground-service declaration](https://support.google.com/googleplay/android-developer/answer/13392821).

Do not use `dataSync` as an indefinite server process. Android 15 applies a six-hour-per-24-hour limit to `dataSync`/`mediaProcessing` foreground-service usage. See [Foreground service changes](https://developer.android.com/develop/background-work/services/fgs/changes) and [timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout).

Doze defers background CPU/network work; it must be tested rather than hidden behind an automatic battery-optimization exemption. See [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby).

For the UI, Android documents loading an owned local development server from WebView at `http://localhost:<port>`. See [Access a local server from WebView](https://developer.android.com/develop/ui/views/layout/webapps/access-local-server).

Keep the runtime server on loopback by default. Android security guidance warns that localhost sockets are not automatically an authenticated IPC boundary and recommends Android IPC or application-level authentication for sensitive interfaces. See [Android security tips](https://developer.android.com/privacy-and-security/security-tips).

Android 17 introduces local-network permission behavior for LAN traffic for apps targeting API 37+. Pure loopback, LAN, cellular, VPN, and cross-profile loopback need separate device tests. See [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission), [local-network definition](https://developer.android.com/privacy-and-security/local-network-definition), and [Android 17 behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17).

## Google Play distribution risk

Google Play's Device and Network Abuse policy prohibits self-updates outside Play and downloading executable code such as DEX/JAR/`.so` from outside Play, with an exception for code running in certain VMs/interpreters. The wording does not automatically establish that a PRoot Linux rootfs qualifies. See [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646).

The official Android dynamic-code-loading guidance also recommends avoiding remote dynamic code loading where possible and using trusted sources plus integrity checks. See [Dynamic Code Loading](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading).

Play Asset Delivery is not a generic solution for this design: its documentation is for game asset packs and states that those packs do not contain executable code. See [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery).

**Initial distribution decision:** validate through sideload/F-Droid or another controlled channel before treating Google Play as a requirement. A Play submission needs a separate policy review and a clear explanation of the product purpose, runtime provenance, execution bridge, FGS use, and sandbox limitations.

## Generic target profile matrix

The project does not select a named target application in the foundation phase. A target becomes a supported profile only after its runtime, architecture/libc contract, web UI, persistence, lifecycle, and security behavior pass the device test plan.

| Profile dimension | Required decision |
| --- | --- |
| Application identity | Source repository, immutable version/tag, license, and maintainer |
| Execution model | Native Android/Bionic or Linux/glibc guest; no implicit fallback |
| Architecture | `arm64-v8a` host and matching `linux/arm64` guest artifact |
| Entrypoint | Fixed argv array, no arbitrary shell interpolation |
| Web surface | HTTP/WebSocket/SSE protocol, health endpoint, configurable loopback port |
| Native dependencies | Complete list of platform/libc/ABI-specific addons and prebuilt artifacts |
| Persistence | State directory, migration/rollback behavior, backup expectations |
| Background behavior | Foreground-only, recoverable, or explicitly unsupported |
| Optional capabilities | Workspace, LAN, browser, microphone, filesystem, and network permissions |
| Support label | `supported`, `untested`, or `unsupported` with evidence |

### First profile

The first profile should be a small, source-reviewed Linux web application that can run from a prebuilt ARM64 guest image without compiling native/frontend dependencies on the device. Prefer an app with a configurable host/port, a health endpoint, durable state, and no desktop GUI requirement.

### Additional profiles

A second profile is allowed only after the first profile passes the execution bridge, WebView, lifecycle, storage, integrity, and compatibility gates. Different runtime languages are useful for testing the host boundary, but they must not expand the product scope into an arbitrary package manager or unrestricted app store.

## Source notes

Sources were checked against official Android/AOSP documentation and primary runtime/project documentation on the snapshot date above. Versioned facts are expected to change and must be re-checked before implementation or release.
