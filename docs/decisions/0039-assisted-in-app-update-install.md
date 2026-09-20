# ADR-0039: Assisted in-app update download and install

## Status

Accepted and implemented in `domain/update/ApkDigest`, `infrastructure/update/{ApkDownloader,HttpAssetSource,PackageInstallerBridge}`, the `presentation/update` popup, and the coordinator wiring (manifest, banner, status receiver). Unit and device evidence are recorded in `docs/test-plan.md`.

## Context

ADR-0038 shipped the release check and the browser hand-off, and deliberately
rejected silent self-update: `REQUEST_INSTALL_PACKAGES` was not declared and
the notification only opened the release page. The accepted product decision
of 2026-09-20 adds an **assisted** install path instead.

The platform constraints shape every choice:

- Outside a store an app can stage an install through the platform
  `PackageInstaller`, but the system always shows its own confirmation
  dialog; a fully silent install is not possible without device-owner.
- Staging from a non-store source requires `REQUEST_INSTALL_PACKAGES` and the
  per-app "install unknown apps" grant, which only the user can enable in
  Settings; every install still asks for confirmation.
- The release asset's `digest` field is the `sha256:<hex>` value GitHub
  computes on the uploaded file; the APK's signing chain — not the digest —
  is the platform-enforced security boundary (an APK not signed by this
  app's key can never update it).
- The asset download path (`github.com/.../releases/download/...`) is the
  release channel's own endpoint and does not draw on the 60/hour JSON API
  budget that shared-egress tunnels (Cloudflare WARP was observed on the
  test device) routinely exhaust.

## Decision

1. **Assisted, never silent.** The banner's primary action becomes
   `Install` and opens the popup; the flow ends at the platform's own
   confirmation dialog, where the user taps Install. Silent self-update
   stays on the unsupported list.
2. **One complete asset triple or the browser hand-off.** The checker reads
   the first asset; it is handed to the install flow only as a complete
   `(https url, sha256:<hex> digest, size)` triple. A missing, malformed, or
   non-HTTPS piece drops the whole asset — the popup then offers only the
   release page. `ApkDigest` normalizes and validates the digest (64 hex
   characters, case-insensitive prefix).
3. **Checksum computed while streaming, fail-closed.** `ApkDownloader`
   writes `<destination>.tmp`, hashing the bytes in the same pass, enforces
   a hard 64 MiB cap, and only then atomically renames into the fixed cache
   slot (`cacheDir/update/NusaDesk-update.apk`). A mismatch against the
   recorded digest refuses the install session; the leftover file fails the
   next cache probe and is re-downloaded.
4. **Cache-first retry.** A cancelled system installer keeps the verified
   file; the next Install tap re-hashes it and skips the download when it
   matches the recorded digest. The digest bookkeeping lives in
   `UpdateCheckPrefs` (recorded with every completed check), so the retry
   survives process restarts.
5. **PackageInstaller over FileProvider.** The APK streams into the
   platform's install session from app-private storage — no world-legible
   copy, no `androidx.core` FileProvider for one provider. The session
   commits with a status `PendingIntent` that carries an app-chosen random
   token; the dynamically registered, token-verified receiver (not exported
   on API 33+) launches the platform's confirmation intent on
   `STATUS_PENDING_USER_ACTION` and maps `ABORTED` back to the popup's kept
   cache.
6. **Explicit one-time Settings step.** Without the per-app unknown-sources
   grant the popup shows the enable step with a Settings hand-off; returning
   to the foreground re-runs the gate. The grant never installs anything by
   itself.
7. **F-Droid stays a separate channel.** An F-Droid-signed install cannot
   be updated by the GitHub-signed APK — the platform refuses the signature
   mismatch as a typed failure; F-Droid users update through the F-Droid
   client.

Rejected alternatives:

- **Silent/background install.** Impossible outside a store without
  device-owner; rejected outright.
- **FileProvider + `ACTION_VIEW` installer intent.** A new dependency and a
  content-provider surface for what `PackageInstaller` already does with the
  bytes staying app-private.
- **Auto-installing the moment the download completes without the popup.**
  The popup is the only surface that shows real progress, speed, and the
  estimate, and Cancel must be reachable during the stream.
- **Treating a missing digest as "install anyway".** Fail-open; rejected —
  the browser hand-off remains for that case.

## Consequences

- One tap (plus the system's own confirmation) updates the app in place;
  progress, speed, and a time estimate are visible, and a cancelled install
  never re-downloads a verified file.
- **Release-signing requirement (flagged 2026-09-20):** the assisted install
  only works when every release is signed with the same key. The current
  release workflow builds the debug APK on an ephemeral CI runner, so the
  signing certificate rotates per release — a pinned CI keystore is required
  before in-app updates can work across releases. Device evidence and the
  open platform-apply stall observed on the S10e live in
  `docs/test-plan.md` (UPD-101..UPD-108).
- `REQUEST_INSTALL_PACKAGES` is appended to the reviewed permission
  allow-list with its rationale; it is Play-restricted and accepted for the
  project's signed-GitHub-release/F-Droid channel.
- The digest gate defends against corruption and channel inconsistency; the
  signature chain remains the security boundary, and the installer UI shows
  the same package identity the user already trusts.
- The banner, popup, and notification all still funnel into the same
  release-page fallback, so a hostile network or an unreported asset never
  leaves the user without a path.
