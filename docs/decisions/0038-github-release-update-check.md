# ADR-0038: Foreground-only GitHub release update check

## Status

Accepted and implemented in `domain/update/` and `infrastructure/update/` plus
the coordinator wiring (`MainActivity.onStart` hook, launcher banner,
notification channel). Unit evidence covers the comparator, checker parsing,
prefs throttle, and notifier gating; a live-network check on a device is
recorded in `docs/test-plan.md` when run.

**Amended by ADR-0046 (check cadence).** Decision 1's 24 hour minimum interval
is lowered to a 15 minute floor applied to every outcome; an installed version
that differs from the one the last attempt ran under is due immediately; and a
fresh process checks once whatever the store says, with a foreground poll
re-asking while the app stays open — the floor and the fresh-launch rule were
tightened on 2026-09-21 after two device reports. Every other decision below
stands unchanged.

## Context

NusaDesk ships only signed APKs through GitHub releases on
`jahrulnr/NusaDesk`. CI publishes each release under a `vX.Y.Z` tag that
equals the app's `versionName`, and the app reads its own `versionName` at
runtime through `PackageManager`. There is no Google Play presence, so no
store updater ever tells the user a newer build exists; an F-Droid channel
also exists, where the F-Droid client is already the authoritative updater.

The platform rules that shape the design:

- `POST_NOTIFICATIONS` is declared, but on Android 13+ it needs a runtime
  grant this feature must never request — the permission exists for
  capability notices the user opted into, not for an update prompt.
- The autostart contract (ADR-0013, amended by ADR-0037) forbids a
  background wake-up path; an update check must not become one either.
- There is deliberately no silent self-update: `REQUEST_INSTALL_PACKAGES`
  is not declared, and installing an APK always goes through the system
  installer UI.

## Decision

1. **Foreground-only check, at most once per 24 hours.** The only trigger is
   an Activity foreground event (`MainActivity.onStart`).
   `UpdateCheckPrefs` keeps a dedicated `update_check` SharedPreferences
   file; `isDue(now)` gates the network call behind a 24 h minimum interval
   stored in `last_check_at`.
2. **Launcher banner first, system notification only when already allowed.**
   The primary surface is an in-app banner on the launcher. `UpdateNotifier`
   posts on its own `updates` channel (IMPORTANCE_DEFAULT) only when
   `POST_NOTIFICATIONS` is already granted — always allowed below API 33 —
   and never requests the permission. Tapping the notification opens the
   release page in the system browser via `ACTION_VIEW` and auto-cancels;
   there is no in-app update flow.
3. **Silent, typed failures.** `GitHubReleaseChecker` performs one bounded
   synchronous GET on the fixed `/releases/latest` endpoint (which already
   filters drafts and prereleases) and maps every failure — offline, any
   non-200 (403 rate limit, 404 no releases, 429), an oversized or malformed
   body, an unparsable tag, a non-HTTPS `html_url` — onto
   `UpdateCheckResult.Kind.UNAVAILABLE`. It never throws, so the UI simply
   shows nothing.
4. **Version contract.** `ReleaseVersion` compares the CI tag convention
   (`vX.Y.Z` == `versionName`) as a numeric major/minor/patch triple with a
   leading `v` stripped and missing components counting as 0. A prerelease
   suffix never counts as newer than the same triple, and unparsable input
   never counts at all — so a synthetic local `versionName` can never
   produce a false update prompt.
5. **No silent self-update, and no F-Droid interference.** The checker only
   links the GitHub release page; it never downloads or installs anything,
   and self-update remains on the unsupported list. For the F-Droid channel
   the checker is still only a link to the GitHub page — the F-Droid client
   stays the authoritative updater for installs from that channel.
6. **Dismissal is remembered per tag.** `dismissed_tag` in the same prefs
   file lets the coordinator suppress the banner for a tag the user already
   dismissed; a newer tag differs from the stored one and re-surfaces
   naturally.

Rejected alternatives:

- **WorkManager periodic check.** A new dependency plus an amendment to the
  manifest guard test, and OEMs can still kill deferred work — so it buys a
  background path the autostart contract deliberately avoids for a check
  that is nearly free at launch. Revisit only when a real background-need is
  accepted.
- **Silent self-update.** Requires `REQUEST_INSTALL_PACKAGES` and a much
  larger policy surface — installer consent, signature trust, download
  integrity, recovery — for a convenience the release page already provides.
  Rejected outright; it stays on the unsupported list.

## Consequences

- The check costs one small HTTPS request per app launch per day, and
  every failure mode is invisible to the user by design.
- **Shared-egress reality (observed 2026-09-20 on the test device):** a
  device that routes traffic through a shared-exit tunnel — Cloudflare WARP
  was active on the S10e (`tun0`, egress 104.28.213.128) — shares the
  unauthenticated 60 req/hour API budget with every other user of that
  egress, so `api.github.com` answers 403 for multi-minute windows. The
  checker maps those to silent `UNAVAILABLE` and the user simply sees no
  banner until an egress allows a check through; no API key will ever ship
  in the app (repository rule: no keys in source). An alternative that
  avoids the API budget entirely — reading the tag from the
  `github.com/.../releases/latest` redirect — was evaluated and, by
  decision, not taken: the API endpoint stays the contract.
- Users who never grant `POST_NOTIFICATIONS` still see the launcher banner;
  the system notification is a bonus surface, not the contract.
- The check's view of "latest" is the GitHub release channel only. An
  F-Droid user sees the same banner (it links the GitHub page) even though
  their actual update arrives through F-Droid on that channel's schedule —
  accepted, since the banner never installs anything.
- `last_seen_tag`/`dismissed_tag` give the coordinator enough state to
  render and suppress the banner without re-querying the network.
- This slice ships the checker, comparator, prefs, and notifier with unit
  tests; the Activity wiring, banner, strings, and end-to-end verification
  are the coordinator's task.
