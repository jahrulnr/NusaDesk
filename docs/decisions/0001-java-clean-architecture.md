# ADR-001: Java Android host with small Clean Architecture boundaries

## Status

Accepted for the foundation phase.

## Context

The product needs Android lifecycle, storage, WebView, download, process, and eventually native-runtime integration. Those concerns have different failure modes and should not leak into deterministic state rules. The user explicitly requested Java and a base scaffold rather than a full implementation.

## Decision

Use a single Java Android application module with four small boundaries:

```text
presentation -> application -> domain
infrastructure -> application/domain
```

Use pure Java domain value objects and JUnit tests. Keep Android framework access in presentation/infrastructure. Add an interface only where an actual boundary or test seam exists.

## Alternatives considered

### Kotlin + Compose

Rejected for this phase because Java is an explicit requirement and the initial UI is only a status shell.

### MVVM/framework-heavy architecture

Deferred. A ViewModel or state library may be added when there is a real asynchronous runtime flow to model; adding it now would be speculative.

### All-in-Activity implementation

Rejected because it would couple future process/storage/security behavior to the UI and make device testing harder.

## Consequences

- Positive: small dependency surface, testable domain, explicit dependency direction.
- Positive: later runtime adapters can be added without rewriting state policy.
- Negative: interfaces and package boundaries require discipline even for a small app.
- Negative: this does not solve Android native execution; it only preserves a place to solve it safely.
