# ADR-0018: Product identity migration to NusaDesk

## Status

Accepted and implemented in `app/build.gradle` (`namespace`, `applicationId`),
the full `app/src/{main,test}/java/gh/nusashell/nusadesk/` source tree,
`app/src/main/AndroidManifest.xml` (relative class references resolve under the
new namespace), `res/values/strings.xml` (`app_name`, `system_how_title`), and
`README.md`. No runtime, security, port, or lifecycle behavior changed; only
the Android package identity and the user-visible product label.

## Context

The product was originally scaffolded under the Android package identity
`com.linuxwrapper.android` with the visible label "Linux Wrapper Base". The
product is now named **NusaDesk** and the Android application/package identity
is `gh.nusashell.nusadesk`. The repository folder (`LinuxWrapperAndroidBase`),
the Gradle root project name, and internal identifiers that do not encode the
dotted Android package identity (the `Widget.LinuxWrapper` style namespace, the
`LinuxWrapperApplication` class, the `window.LinuxWrapperTerminal` JS bridge
contract, the `LinuxWrapperAndroidBase/0.1` download User-Agent, and the
historical AVD/research names) are deliberately left unchanged: they are
internal or historical, not the user-facing Android identity.

## Decision

1. **Android package identity.** `applicationId` and the AGP `namespace` in
   `app/build.gradle` are both `gh.nusashell.nusadesk`. Every Java package
   declaration and import was moved from `com.linuxwrapper.android` to
   `gh.nusashell.nusadesk`, and the source/test directory trees were moved to
   `app/src/{main,test}/java/gh/nusashell/nusadesk/`. The manifest's relative
   class references (`.infrastructure.integration.LinuxWrapperApplication`,
   `.presentation.MainActivity`, `.infrastructure.service.RuntimeHostService`)
   resolve under the new namespace without text changes.

2. **Internal intent action strings.** The `RuntimeHostService` action
   constants (`ACTION_START`, `ACTION_STOP`, `ACTION_ENSURE_RUNNING`) were
   renamed from `com.linuxwrapper.android.action.*` to
   `gh.nusashell.nusadesk.action.*`. They are explicit-intent discriminators
   (the intent targets the service class directly), so the rename is cosmetic
   consistency, not a routing change.

3. **User-visible label.** `app_name` is "NusaDesk" and `system_how_title` is
   "How NusaDesk works". The README H1 is "NusaDesk". Generic "Linux" status
   vocabulary (the runtime, the session, the terminal) is unchanged: it
   describes the guest, not the product.

4. **Internal identifiers preserved.** The `Widget.LinuxWrapper.*` style
   namespace, the `LinuxWrapperApplication` class, the
   `window.LinuxWrapperTerminal` JS contract, and the download User-Agent
   `LinuxWrapperAndroidBase/0.1` are internal identifiers that do not encode
   the dotted Android package identity and are not user-visible; renaming them
   is churn and risk outside this slice's scope.

## Consequences

- **Old app-private data does not auto-migrate.** Changing `applicationId`
  creates a *new* Android application identity. Android scopes app-private
  storage (`/data/data/<applicationId>/`, including the extracted rootfs, the
  runtime state store, the SSH host-key/credential material, and the web-app
  registry) and the Android Keystore namespace by `applicationId`. The new
  `gh.nusashell.nusadesk` app cannot read the old
  `com.linuxwrapper.android` app's private storage or Keystore entries. A user
  upgrading from the old identity to NusaDesk must run setup again (download,
  verify, extract, activate the rootfs and the SSH add-on, and re-establish
  host-key trust and web-app registrations). The old app remains a separate
  install and its data is not deleted; it can be uninstalled manually after
  the user confirms NusaDesk is working. This is an inherent Android
  identity-change limitation, not a bug.

- **No runtime behavior change.** The loopback endpoint, the SSH bridge, the
  foreground-service lifecycle, the WebView boundary, the download/verify/
  extract/activate contract, and the security rules in `AGENTS.md` are
  unchanged. Only the package identity and the visible label moved.

- **Tests and lint stay green.** The package rename is mechanical and
  exhaustive across the source and test trees; the autostart manifest guard
  (`RuntimeAutostartManifestTest`) still asserts the same manifest invariants
  because the manifest's relative class references are namespace-relative.
