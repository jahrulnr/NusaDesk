# ADR-005: Native status shell with curated install action

## Status

Accepted for the foundation phase.

## Context

The repository now owns a pinned Ubuntu Base install proof, but it does not yet own an execution bridge, process supervisor, runtime session, or WebView endpoint. The UI must expose the real install flow without pretending that installation means a running process.

## Decision

Build one responsive, native Java `Activity` as a status/install shell using Android SDK Views and XML resources. The screen:

- renders the current truthful install state as `NOT_INSTALLED`, persisted install progress, `READY`, or retryable `FAILED`;
- maps all `RuntimeState` values to human-readable copy through a small, pure presentation descriptor;
- provides a real curated install action plus lifecycle preview and contract disclosure dialogs;
- labels installation as non-running and never starts a process, claims a port, or opens a WebView;
- composes the screen from reusable XML widgets/includes and focused Java widgets (`RuntimeStatusCard`, `RuntimeLifecycleDialog`, and `FoundationContractDialog`); the Activity only wires callbacks and system bars;

Runtime actions remain application-layer work. Once those boundaries exist, this shell can consume a real snapshot without changing the presentation vocabulary.

## Alternatives considered

### Fake start/stop/download controls

Rejected. They would imply backend behavior that the foundation does not implement and could make users believe a Linux process or endpoint is live.

### Compose or a UI framework dependency

Deferred. Java and the existing no-third-party-dependency baseline are explicit constraints; the Android SDK View toolkit is sufficient for this vertical slice.

### A separate navigation shell and diagnostics screen

Deferred. There is no runtime session or real diagnostics data yet. Progressive disclosure keeps the foundation small and testable.

## Consequences

- Positive: users get a polished, honest entry point with a real install proof instead of a blank placeholder.
- Positive: lifecycle copy is exhaustive and can be tested independently from Android rendering.
- Positive: no execution or WebView security boundary is invented for visual polish.
- Negative: installation is currently Activity-coordinated and needs a durable coordinator for rotation/cancellation/resume.
- Negative: runtime controls must be added with the corresponding execution/process use cases, not as UI-only behavior.
