# ADR-0067: The VERSION marker derives the build, and the changelog is a generated asset

## Status

Accepted, implemented in `app/build.gradle` (the `releaseVersion` /
`derivedVersionCode` derivation and the `generateChangelogAsset` task with
its consumer fan-out) and the reworked `VersionConsistencyTest`.

## Context

Two pieces of release metadata were maintained by hand. `app/build.gradle`
carried literal `versionCode`/`versionName` that had to be edited in the same
commit that bumped `VERSION` — the `VersionConsistencyTest` existed precisely
because they had drifted before. And the About page's changelog card
(ADR-0066) needed the repository `CHANGELOG.md` inside the APK, but a
manually copied asset would be a second changelog that could silently lag.

## Decision

1. **`VERSION` is the single source of truth.** `app/build.gradle` reads it
   at configuration time: `versionName` is the marker text, `versionCode` is
   `major*1_000_000 + minor*1_000 + patch` — three digits per field, so
   strictly increasing semantic versions produce strictly increasing codes.
   The build fails loudly on a non-`M.m.p` marker or any field ≥ 1000 rather
   than emitting a colliding code. The mapping's first derived code (0.14.3 →
   14003) jumps above the last hand-edited 25; sideload/GitHub-release
   updates only require greater-than.
2. **`CHANGELOG.md` is staged into generated assets.** `generateChangelogAsset`
   copies it to `build/generated/changelog-assets`, which joins `main`
   assets via `sourceSets`; the file is never copied under `src/` or
   committed twice. Because it is a build step, the local `assembleDebug`,
   the QA build, and the release pipeline all produce it identically — no
   separate script to remember. Every consumer of the generated directory
   declares the task (`*Assets` merges, lint tasks, `preBuild`) because
   AGP 9's strict input validation rejects implicit dependencies; the
   `srcDir` gets a concrete path, not a `Provider`.
3. **The consistency guard pins the contract, not the literal.** The reworked
   `VersionConsistencyTest` asserts the marker is a strict semver whose
   fields fit the code mapping and that the derived code stays above the last
   manual code — so a regressive `VERSION` fails before it can produce an
   uninstallable update. It also asserts `app/build.gradle` derives from the
   marker and that `ChangelogSection.parseLatest` finds this version as the
   newest changelog section — the same parse the About card performs.

Rejected alternatives:

- **A checked-in `assets/CHANGELOG.md` copy.** Two changelogs can drift in
  both directions; generation has exactly one source.
- **`androidComponents` `addGeneratedSourceDirectory` (the Layered assets
  API).** The cleaner official wiring, but it needs a task exposing a
  `DirectoryProperty`; `Copy.getDestinationDirectory()` only exists in
  Gradle 9.8 while the wrapper pins 9.6.0. The `srcDir` + fan-out pattern is
  the proven equivalent on this toolchain; revisiting it is a Gradle bump,
  not a correctness issue.
- **Deriving versionCode from git state or the CI run number.** Local debug
  builds, QA-signed builds, and the release build must agree on the code or
  update-testing between them breaks; the marker keeps it reproducible
  everywhere.
- **Keeping a hand-managed versionCode.** It buys nothing over the mapping
  and is the drift the test was written to catch.

## Consequences

- A release bump edits `VERSION` + `CHANGELOG.md` + `SECURITY.md`; the Gradle
  side updates itself. `VersionConsistencyTest` still guards the whole set.
- The debug APK's `versionCode` now tracks the marker too; installing a debug
  build over an installed release with a higher code is rejected by the
  platform, as before — only the numbers moved.
- The generated-asset fan-out must grow if new task classes consume that
  directory; the matching names cover the known consumers today.
