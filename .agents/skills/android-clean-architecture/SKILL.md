---
name: android-clean-architecture
description: Use when adding Java Android code to this wrapper so domain, application, infrastructure, and presentation dependencies stay explicit; enforce YAGNI/KISS, testable ports, and no speculative runtime implementation.
---

# Android Clean Architecture

Use this skill for changes to the Java Android foundation.

## Default structure

```text
presentation  -> application  -> domain
infrastructure --------------> application/domain
```

- `domain/`: Android-free immutable values, enums, invariants, and deterministic policies.
- `application/`: use cases and ports that represent real boundaries.
- `infrastructure/`: Android framework, files, network, process, cryptography, WebView, and native-runtime adapters.
- `presentation/`: Activities and UI state rendering; no shell commands or security policy.

## Rules

1. Read `AGENTS.md`, the relevant ADR, and the closest source before editing.
2. State the user-visible requirement and the smallest vertical slice.
3. Apply YAGNI/KISS: use Android/JDK primitives first; do not add a framework, service locator, generic manager, or abstraction without a current boundary.
4. Keep Android imports out of `domain/` and `application/`.
5. Use immutable Java value objects for data crossing a layer boundary.
6. Validate at the application boundary; UI checks are not enforcement.
7. Add a focused unit test for pure policy/value behavior before declaring the slice complete.
8. Update an ADR when a dependency, public contract, layer rule, or security boundary changes.

## Runtime-specific guardrails

- The foundation is not the Linux runtime. Do not implement PRoot, QEMU, JNI, downloads, or target-app integration under this trigger unless the task explicitly scopes one.
- The Android host owns lifecycle; a Linux app must not install `systemd`/launchd services.
- A port is published only after the child reports a concrete loopback endpoint and health readiness.
- Store recoverable working state explicitly; never infer `RUNNING` from a PID alone.

## Verification

```bash
./gradlew test
./gradlew lintDebug
./gradlew assembleDebug
```

Inspect the changed files and generated APK/report, not just the exit code. Never weaken a test to make the build pass.
