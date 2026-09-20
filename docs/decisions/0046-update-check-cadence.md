# ADR-0046: Update-check cadence — a 15 minute floor, a check on every fresh launch, and a foreground poll

## Status

Accepted and implemented in `infrastructure/update/UpdateCheckPrefs` plus the
coordinator wiring (`MainActivity.checkForUpdate`). It amends decision 1 of
ADR-0038 (the 24 hour minimum interval); every other decision there stands.
Unit evidence covers the floor, the version-change rule and the upgrade path;
device verification is recorded in `docs/test-plan.md`.

**Amended 2026-09-21: the floor is 15 minutes, and a fresh launch always
asks.** Two device reports shaped this. On the ROG Phone 2 an official 0.6.1
installed from the GitHub page — never touched by adb — never showed the
0.6.2 banner, force-stop included; and the S7 Edge measurement that evening
pinned the mechanism exactly: the last attempt sat at `03:44:26`, so the floor
ended at `04:14:26`, the "relaunch and look" happened at `04:13` — 46 seconds
early — and the next launch at `04:17` found the release immediately. The
mechanism worked as decided; what the decision got wrong is that a launch is
precisely the moment a user expects a look, and the silent floor answered with
the stored banner and no explanation. The floor is now 15 minutes (still one
rule per outcome), a fresh process checks once whatever the store says, and a
foreground poll re-asks while the app stays open — still no job, no alarm, no
background path.

## Context

ADR-0038 made the update check foreground-only and gated it behind a 24 hour
minimum interval stored in `last_check_at`, recording every attempt — whatever
its outcome. Two flaws surfaced in practice:

- **The interval is coarse relative to the release cadence.** Observed on the
  S10e (2026-09-20): the last recorded attempt ran at 13:05 WIB and reported
  nothing newer; v0.4.0 was published at 14:14 and v0.5.0 at 19:39. Every app
  open after that — force-stop and relaunch included — was throttled, because
  the next attempt was not due until 2026-09-21 13:05. No banner could appear
  for the rest of the day, and the stored state gave no hint that the check
  had simply not run.
- **A check that ran under another installed version is a different
  question.** A release the user sideloads from the GitHub page (or installs
  through the assisted flow) changes what "newer" means, yet the new build
  inherited the old build's throttle.

The 403 shared-egress windows already documented in ADR-0038 make the first
flaw worse: an inconclusive attempt (offline, rate limit, malformed body)
consumed the same day-long quota as a definitive one.

## Decision

1. **A 15 minute floor, one rule for every outcome — and a fresh launch
   always asks.** `UpdateCheckPrefs.MIN_INTERVAL_MILLIS` is 15 minutes and
   `isDue(now, installedVersionName, freshProcess)` gates the network call.
   The trigger stays a foreground event only (`MainActivity.onStart` plus the
   foreground poll below) — no job, no alarm, no background path, exactly as
   ADR-0038 required. `freshProcess` is true for this process's first
   foreground event, so a reboot, a force-stop and a fresh launch ask once
   regardless of the stored timestamp: the moment a user actually expects the
   app to look, and the moment the previous floor silently swallowed. The
   poll is one main-thread tick every 5 minutes that merely re-asks the
   question, so the effective cadence is the floor plus at most one tick
   while the app stays in the foreground, and the poll is dropped in
   `onStop`, so a backgrounded app spends no wakeups. The cost is at most
   four requests per hour of foreground use: one bounded GET on the fixed
   endpoint, far inside GitHub's 60 req/hour unauthenticated budget.
2. **A changed installed version is due immediately.** `recordCheck` also
   stores `last_check_version` — the `versionName` the attempt ran under —
   and `isDue` returns true when it differs from the installed one. A
   sideloaded or assisted install therefore re-checks on the next foreground
   instead of inheriting an older build's throttle; a store written before
   this key existed counts as a change, so the first launch after upgrading
   to this build checks once regardless of the old timestamp. A `null`
   installed version cannot prove a change and falls back to the floor alone.
3. **Inconclusive attempts retry at the same floor.** `recordCheck` still
   records every attempt, but a failed one now costs 15 minutes rather than a
   day. The `last_seen_tag` semantics (null for up-to-date or unavailable
   results) and the silent-failure contract are unchanged.
4. **Nothing else changes.** The endpoint, the parser, the version contract,
   the banner and notification surfaces, per-tag dismissal, the browser
   hand-off and the assisted install flow are untouched.

Rejected alternatives:

- **Keep 24 hours and add a manual "Check now" action.** That leaves the
  default path blind for a day and puts the discovery burden on the user. A
  manual action remains a reasonable future addition, but it is not what
  fixes this defect. The 2026-09-21 amendment does give the user an
  equivalent path with no new UI: force-stopping and reopening the app is a
  fresh process, and a fresh process always asks.
- **Adaptive schedule (e.g. 15 minutes after a failure, 24 hours after a
  success).** An option matrix for a call this cheap; one floor is easier to
  reason about, to test, and to explain.
- **WorkManager or another background path.** Still rejected for the reasons
  ADR-0038 gives: a dependency and a manifest-guard amendment to buy a
  background wake-up the autostart contract deliberately avoids.

## Consequences

- Discovery latency after a release is at most one launch — a fresh process
  always asks — and at most one floor plus one tick while the app stays open;
  the post-install question — "is what I just installed already superseded?"
  — is answered immediately.
- A device behind a 403 shared egress retries every 15 minutes of foreground
  use, and at every fresh launch, instead of once a day, so the banner can
  appear as soon as an egress window opens.
- `last_check_version` is diagnostic state only; which release counts as
  newer is still decided by `ReleaseVersion` against the installed
  `versionName`.
- Both keys are written with `apply()`; losing them on process death only
  re-runs a harmless check.
