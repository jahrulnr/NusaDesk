# ADR-0066: The About page's manual update check and bundled changelog

## Status

Accepted, implemented in `SystemAboutPageView`, `SystemScreenView`,
`MainActivity` (`manualUpdateCheck`, `runUpdateCheck`,
`onUpdateCheckFinished`, `loadBundledChangelog`), and
`domain/update/ChangelogSection`. Covered by `SystemAboutPageViewTest`
(Robolectric) and `ChangelogSectionTest`; the manual tap path additionally
shares `runUpdateCheck` with the cadence path, so its network behavior is the
same code ADR-0038/0046 already own.

## Context

Two gaps showed up in the About page. First, ADR-0046 tightened the
foreground cadence but the only way to ask for a check *now* was to
force-stop and reopen the app — the amendment itself noted "a manual
action remains a reasonable future addition". Second, the release notes for
the installed build lived only on GitHub; nothing in-app answered "what
changed since my version".

Constraints inherited from the update feature: every check failure maps to
`UNAVAILABLE` silently, the assisted install and the dismissed-tag state stay
with the coordinator, and `applyUpdateResult` clears the banner on any
non-available result — a failed check therefore also clears a visible banner,
which is the pre-existing contract, not a new one.

## Decision

1. **A "Check for updates" row sits below the GitHub row.** Its status line
   names the installed version — the page's other "Version" entry is the
   *runtime profile* version — and reports `Checking…` / latest / update
   available / check failed after each completed check.
2. **A manual ask bypasses `UpdateCheckPrefs.isDue`.** The cadence question
   stays a cadence question; `manualUpdateCheck` calls the extracted
   `runUpdateCheck` directly. `recordCheck` still runs, so a real attempt
   resets the floor honestly. `updateCheckInFlight` collapses a manual tap
   onto an in-flight scheduled check — and vice versa — and the shared
   `onUpdateCheckFinished` renders the outcome on the About row *and* runs
   the normal `applyUpdateResult` hand-off: the asset triple is always
   recorded, while the banner and notification stay quiet for a tag the user
   dismissed — the About row still reports it, since it answers "what did
   the check find", not "what is un-dismissed".
3. **A Changelog card sits below "How it works".** It renders the newest
   released section of the bundled `CHANGELOG.md` — the same
   `## [x.y.z] - date` section `VersionConsistencyTest` requires to match
   `VERSION` — flattened to plain text (`### Group` keeps its word, `- `
   becomes `• `, emphasis markers drop, capped at 2000 chars on a line
   boundary). A "Read more" row hands the repository `CHANGELOG.md` to the
   system browser, the same `ActivityNotFoundException`-guarded pattern the
   GitHub row uses.
4. **The asset read is fail-quiet.** The bundled file is generated at build
   time (ADR-0067); a missing or unreadable asset, or a document with no
   released section, renders an explicit "unavailable in this build" line
   rather than a crash or a blank card.

Rejected alternatives:

- **A `force` flag on `checkForUpdate`.** `isDue` answers cadence, not
  capability; a separate `runUpdateCheck` keeps both paths reading as one
  mechanism instead of a conditional bypass.
- **Fetching the changelog from GitHub at view time.** Duplicates the
  update checker's failure modes for text the APK already carries; the
  bundled copy is correct by definition for the installed build.
- **Rendering raw markdown.** `**` and `###` read as markup noise on a plain
  `TextView`; the flattening is deterministic and JVM-tested.
- **Hiding the card when the asset is missing.** The unavailable line keeps
  the page honest about what it cannot show.

## Consequences

- The About row reflects the last *completed* check, scheduled or manual;
  there is no separate "manual result" state to drift.
- A manual check queued behind an assisted-install download on the shared
  `updateExecutor` waits for it — bounded and rare, and the row reads
  `Checking…` honestly while it waits.
- The card caps the section at 2000 chars; "Read more" is the full text.
- Because the card renders the newest section — which the consistency guard
  pins to `VERSION` — the in-app changelog is self-maintaining: a release
  bump changes the bundled section automatically.
