# ADR-0052: Usage stats, package enumeration/launch, overlay, and background location

- Status: accepted
- Date: 2026-09-23

## Context

Four declarations from the permission recheck had no capability behind them:
`PACKAGE_USAGE_STATS`, `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW` (as an
overlay surface — it already served as the background-activity-start
exemption), and `ACCESS_BACKGROUND_LOCATION`. Upstream Termux:API has no
client command for the first three (verified against the tracked surface
audit in `docs/research/termux-api-upstream-surface.md`: client v0.60.0 and the
GPL app's receiver), so they are NusaDesk-native work. Background location is
the one
domain where upstream is moving: the app's master branch (unreleased,
commits `255bc405f5`, `ee29d4314c`, 2026-09-16) added a `LocationService` and
requires "Allow all the time" for every `termux-location` request on API 30+,
with `-r updates` emitting a JSON array at the root. Mirroring that contract
now keeps `termux-location` compatible when upstream ships it.

The recheck also left three declarations with no job at all:
`FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and
`DOWNLOAD_WITHOUT_NOTIFICATION`. No shipped service declares the first two
(the Linux session's foreground service keeps the process alive for every
wireless capability), AGENTS.md forbids `dataSync` as an indefinite server
process, and the third is only required for `VISIBILITY_HIDDEN`, which
`download.request` never uses.

## Decision

1. **`UsageModule`** — `usage.query` (daily aggregates, window ≤ 7 days,
   at most 50 rows, sorted by total time), `usage.events` (bounded event
   window, stable type names), and `usage.standby` (app standby bucket).
   `PACKAGE_USAGE_STATS` is special access: a missing grant answers the typed
   `usage-permission-required` with the Settings hint, and the bridge never
   prompts. Documented deviation: `getAppStandbyBucket(String)` is
   `@SystemApi`, so a foreign package's bucket is derived from the newest
   `STANDBY_BUCKET_CHANGED` event in a bounded lookback (or `unknown`) instead
   of being fabricated, while the app's own bucket and inactive flag are read
   directly.
2. **`PackagesModule`** — `packages.list` (bounded, filterable by
   package/label prefix, system apps included by default), `packages.info`,
   and `packages.launch` (the app's launch intent, optional explicit
   activity). `QUERY_ALL_PACKAGES` is what makes enumeration possible for a
   target-30+ app; launching another app needs no package visibility but is
   subject to background-activity-start rules, so a refused start answers the
   typed `packages-launch-blocked` instead of a silent failure.
3. **`OverlayModule`** — `overlay.show|update|status|hide` draws one
   `TYPE_APPLICATION_OVERLAY` text plate with `SYSTEM_ALERT_WINDOW`. The grant
   is special access (`Settings.canDrawOverlays`), so a missing one is a typed
   `overlay-permission-required`; every view operation runs on the main
   looper with a bounded wait, and `close()` detaches the view.
4. **`LocationBackgroundModule` + `LocationBackgroundService`** — the only
   user of the `FOREGROUND_SERVICE_LOCATION` type. `location.background.start`
   requires `ACCESS_BACKGROUND_LOCATION` ("Allow all the time" on API 30+; the
   runtime dialog can still grant it on API 29) and answers the typed
   `location-background-permission-required` otherwise. Defaults mirror
   upstream's contract (5 s / 1 m), `poll` returns the fixes as a JSON array
   drained per call (service buffer 256, drop-oldest), and `stop` is
   idempotent. The service is `START_NOT_STICKY`, posts a visible notification
   with a Stop action, promotes the location type on API 30+, and releases the
   updates on every terminal path.
5. **Prune the three dead declarations.** `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
   `FOREGROUND_SERVICE_DATA_SYNC`, and `DOWNLOAD_WITHOUT_NOTIFICATION` are
   removed from the manifest and the allow-list test, with the manifest
   comment recording why (no service declares the types; `dataSync` must never
   be an indefinite server; `VISIBILITY_HIDDEN` is unused).

## Consequences

- Every permission the manifest declares now backs a shipped code path; the
  allow-list test still pins the exact set (48 declarations after the prune).
- The guest can read usage aggregates and events, enumerate and launch apps,
  draw an overlay, and follow location in the background — each with a typed
  permission result, bounded windows, and no prompt from the bridge.
- `termux-location` keeps working with the shape upstream is heading toward,
  including the "Allow all the time" requirement, so the eventual upstream
  release does not break the guest contract.
- Unit coverage: `UsageModuleTest` (16), `PackagesModuleTest` (21),
  `OverlayModuleTest` (17), `LocationBackgroundModuleTest` (14, including a
  service dispatch + simulate + drain round trip). Device verification on the
  S10e is the acceptance gate and lands in `docs/evidence/`.

## Device evidence

Verified on the S10e (SM-G970F, Android 12/API 31) on 2026-09-23/24 through
the live guest: `usage.query` answered the typed permission error before the
grant and returned real rows after the usage-access app-op was allowed, while
`usage.standby` worked without a grant; `packages.list`/`packages.info`
returned real labels and versions and `packages.launch` brought
`com.android.settings` up; `overlay.show|status|hide` round-tripped (the drawn
pixels still need one unlocked-screen capture — the plate does not render over
the secure keyguard); and `location.background.start|poll|stop` produced a real
network fix with the visible `nusadesk-location-background` notification before
stopping. Full observed output in
`docs/evidence/capability-closure-matrix.md`.
