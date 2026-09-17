# ADR-0022: Cross-API-level launch guard runs on Robolectric

## Status

Accepted and implemented: `MainActivityApiLevelLaunchTest` with
`@Config(sdk = {29, 30, 31, 33})`, the test-only
`org.robolectric:robolectric:4.15.1` dependency and
`unitTests.includeAndroidResources`, an `actions/cache` step for the downloaded
Android runtimes in both workflows, and the removal of the source-text
`SystemBarsApiLevelContractTest` that this guard replaced. Proven against the
pre-fix revision: API 30 and 31 failed with the same stack trace the devices
produced while API 29 passed; all four levels pass after the fix.

## Context

NusaDesk force-closed on launch on two devices — Android 11 (ASUS ROG 2) and
Android 12 (Galaxy S10e) — while the Android 10 device ran normally.

The device stack trace named the cause:

```
java.lang.NullPointerException: Attempt to invoke virtual method
'android.view.WindowInsetsController com.android.internal.policy.DecorView.getWindowInsetsController()'
on a null object reference
    at com.android.internal.policy.PhoneWindow.getInsetsController(PhoneWindow.java:4106)
    at gh.nusashell.nusadesk.presentation.MainActivity.applySystemBars(MainActivity.java:825)
    at gh.nusashell.nusadesk.presentation.MainActivity.onCreate(MainActivity.java:138)
```

`Window.getInsetsController()` dereferences the window's internal decor view
directly on API 30/31, and `onCreate` runs before any content view exists. The
API-29 branch of the same method never calls it, so Android 10 was unaffected.

The repository had no test that could execute framework code for a chosen API
level. The "contract" tests added alongside the terminal work read source text
(assert a string is present or absent), which cannot catch a lifecycle or
platform-behaviour regression: the crashing line was correctly guarded by
`Build.VERSION.SDK_INT >= Build.VERSION_CODES.R`, so every existing check —
including lint's `NewApi` — passed while two devices crashed on every launch.

## Decision

1. **Robolectric as a test-only dependency.** It executes the real framework
   classes of a chosen API level on the JVM, so one test covers several Android
   releases inside the ordinary `./gradlew test` task, with no device, emulator,
   or KVM. It is `testImplementation` only; nothing ships in the APK.

2. **One launch test, bound to the crash.** `MainActivityApiLevelLaunchTest`
   builds the real Activity through `onCreate` and asserts it completes. That is
   the smallest test that fails for the whole class of "framework call made too
   early in the lifecycle" defects, and it needs no assertions about internals.

3. **The covered set is the owned devices plus one newer level**:
   `sdk = {29, 30, 31, 33}` = Android 10 (device-verified floor), 11 and 12 (the
   reporting devices) and 13. Widening or narrowing the set is one line; each
   level only costs one runtime download.

4. **CI caches the Android runtimes.** Robolectric fetches an
   `android-all-instrumented` jar per level (112–156 MB each, ~525 MB for this
   set) into `~/.m2/repository/org/robolectric`, which no existing cache step
   covered. Both workflows cache that directory keyed by the test file's hash,
   so changing the SDK set or the test re-seeds it, and a cold runner does not
   re-download half a gigabyte on every push.

5. **Source-text contract tests survive only where Robolectric cannot express
   the contract** (layout dimension values, manifest declarations). The insets
   contract test was deleted: a launch test that actually fails on the defect is
   strictly stronger evidence than a grep that a bad call is absent.

## Consequences

- Every `./gradlew test` now pins launch behavior on Android 10–13. A
  regression of this class fails locally and in CI without any device attached.
- Cost: ~525 MB of Android runtimes, downloaded once and cached; the suite adds
  ~24 s cold and under a second when the runtimes are present.
- Robolectric runs no WebView, no IME, and no real renderer, so terminal
  selection, touch scrolling, keyboard insets, and the PRoot runtime stay
  device-verified only (`android-compatibility-testing`).
- A future API level in the set is one array entry and one download; nothing
  else in the build changes.

## Alternatives considered

- **Instrumented tests, e.g. Gradle Managed Devices or an
  `android-emulator-runner` CI matrix.** The only layer that exercises the real
  WebView, IME, and renderer, and the natural next step for terminal UX
  regressions. Deferred: it needs emulator images plus a KVM-capable runner and
  minutes per API level, far beyond the current gate's budget, and it cannot
  replace Robolectric for the cheap per-push check this ADR adds.
- **Source-text contract tests only.** Cheap and dependency-free, but they
  cannot execute the framework, so they cannot catch this class of crash — they
  demonstrably did not.
- **Firebase Test Lab or another device farm.** Real hardware across many
  models, but an external service with credentials and quota; not adopted for a
  guard that must run on every push.
