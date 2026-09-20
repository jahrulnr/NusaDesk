# ADR-0040: Release signing key custody and the signed release artifact

## Status

Accepted; build wiring, workflow, and local-path validation landed 2026-09-20.
The keystore itself and the repository secrets are operational setup owned by
the maintainer (steps in this ADR).

## Context

The published GitHub release asset was built by CI from the **debug** APK
(`make check` → `app-debug.apk`), signed with the debug keystore that the
ephemeral GitHub runner generates on the fly. Two consequences surfaced
during the ADR-0039 device work (2026-09-20):

1. **The signer rotated between releases.** Every CI run produced a new
   random debug certificate, so an installed release could never be updated
   by the next one — the platform refuses an update whose signer differs.
   The assisted in-app update flow (ADR-0039) and any manual update both
   depend on a stable signer; the whole point of the feature is void without
   one.
2. **The shipped artifact was debuggable.** A debug build sets
   `android:debuggable=true`, which is a poor property for the user-facing
   download.

The repository has no release signing configuration, and no code depends on
`BuildConfig.DEBUG` (checked 2026-09-20), so moving the published artifact to
the release build changes no application behavior. The Android platform
enforces signer continuity at install time — the keystore *is* the app's
long-term identity, so its custody is the security decision this ADR records.

## Decision

1. **One dedicated release keystore; the artifact moves to the release
   build.** `assembleRelease` (minify disabled, unchanged) with a
   `signingConfigs.release` that activates only when `ANDROID_KEYSTORE_PATH`
   is present in the environment. Local builds without the variables keep
   the old behavior (debug signing for debug builds; an unsigned
   `app-release-unsigned.apk`), and that is deliberate: only CI publishes
   artifacts, and it fails closed.
2. **The keystore lives in repository secrets, never in the repository.**
   CI decodes `ANDROID_KEYSTORE_BASE64` into the ephemeral runner, and the
   password/alias/key-password come from `ANDROID_KEYSTORE_PASSWORD`,
   `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Committing a keystore —
   debug or release — is rejected outright: a public key material would let
   anyone sign "NusaDesk" builds, destroying the app identity.
3. **CI pins the certificate fingerprint and refuses to publish a
   mismatch.** The SHA-256 of the release certificate is stored as the
   repository variable `ANDROID_CERT_SHA256` (public information, not a
   secret). The workflow runs `apksigner verify --print-certs` and fails the
   release if the fingerprint differs or either the keystore secret or the
   fingerprint variable is missing — an unsigned, rotated, or
   accidentally-replaced key can never reach a release.
4. **Custody discipline is part of the decision.** The `.jks` and its
   passwords are backed up in a password manager plus one offline copy.
   Losing the key means installed users cannot update in place anymore; they
   must uninstall and reinstall once. Rotating the key later has the same
   cost, so it is treated as permanent.
5. **One-time migration.** Builds published before this decision were signed
   with ephemeral (and mutually incompatible) debug certificates. The first
   release signed with the pinned key requires a one-time reinstall for
   anyone on those builds; release notes say so.
6. **F-Droid stays a separate identity.** An F-Droid build carries F-Droid's
   key and updates through the F-Droid client, exactly as ADR-0038/0039
   already document; cross-channel updates remain impossible by design.

Rejected alternatives:

- **Commit a keystore to the repository (even the debug one).** The key
  material becomes public; anyone could sign a malicious update as this app.
- **Keep the debug keystore but pin it as a secret.** Debug signing marks
  the published APK debuggable and couples the release to a key whose alias
  and password are public conventions; a dedicated release key is the honest
  form of the same idea.
- **Re-sign the debug APK with `apksigner` inside CI.** Technically
  possible, but it fights AGP's signing pipeline for no benefit; the
  `signingConfig` path is the supported one.
- **Skip the fingerprint pin.** The pin is what turns "signed" into "signed
  with the key we decided on"; without it a misconfigured secret would still
  publish.
- **Keep publishing debug APKs.** Ships `debuggable=true` and forfeits any
  update path between releases.

## Consequences

- Successive releases update each other in place (manual or assisted,
  ADR-0039), and the published APK is non-debuggable.
- The release workflow now hard-requires the signing secrets; a missing or
  drifted key fails the release before any tag or asset is created.
- The maintainer setup: generate the keystore once
  (`keytool -genkeypair -v -keystore nusadesk-release.jks -alias nusadesk
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12`), store
  `base64 -w0` in `ANDROID_KEYSTORE_BASE64` plus the three credential
  secrets, and record the certificate's SHA-256 in `ANDROID_CERT_SHA256`.
- Users of pre-key releases reinstall once; the release notes for the first
  pinned-key release carry that instruction.
- Device QA that relied on `adb shell run-as` (playbook
  `PB-ui-qa-physical-device`) continues to use **debug** builds; release
  APKs are intentionally not debuggable.
