# ADR-0029: Namespaced NusaDesk metadata in guest `os-release`

## Status

Accepted and implemented in `GuestOsReleaseWriter` and `ProotLauncher`.

## Context

The Ubuntu Base rootfs currently exposes:

```text
/etc/os-release -> ../usr/lib/os-release
```

Ubuntu and package tooling rely on the standard identity fields (`ID=ubuntu`,
`ID_LIKE=debian`, `VERSION_ID`, and related values). Replacing the distro
identity with `NusaDesk` could break repository selection or scripts that expect
Ubuntu. Editing `/usr/lib/os-release` directly would also make the product
change package-owned rootfs data and could be overwritten by an `apt` update.

NusaDesk still needs a machine-readable contributor/source marker in the
user-visible `/etc/os-release` path.

## Decision

Keep the original Ubuntu fields unchanged and append two namespaced fields to a
product-owned generated source:

```ini
NUSADESK_CONTRIBUTOR="NusaDesk"
NUSADESK_SOURCE="https://github.com/jahrulnr/NusaDesk"
```

`GuestOsReleaseWriter` reads the current active rootfs `etc/os-release`
(resolving only a symlink that stays inside the active rootfs), removes stale
copies of those two custom assignments, appends the canonical values, and
atomically writes the result under:

```text
<filesDir>/linux-wrapper/state/os-release
```

`ProotLauncher.buildSpec()` strictly binds that file at the literal guest path
`/etc/os-release!`. The underlying `/usr/lib/os-release` is never edited. On a
future `apt` base-files update, the next PRoot spec regenerates the overlay from
the new Ubuntu source, so both the distro update and the NusaDesk fields remain
visible through `/etc/os-release`.

## Consequences

- `cat /etc/os-release` exposes Ubuntu-compatible identity plus NusaDesk
  contributor/source metadata.
- Direct readers of `/usr/lib/os-release` still see the original Ubuntu data;
  this is deliberate and preserves package-owned state.
- A guest write or package replacement of the effective `/etc/os-release` is
  corrected on the next PRoot launch; the bind is not a read-only security
  boundary because this packaged PRoot contract only proves strict path
  selection, not `:ro` binds.
- A malformed or escaping rootfs `os-release` path fails the PRoot build rather
  than generating metadata from an untrusted outside file.

## Verification

`GuestOsReleaseWriterTest` covers Ubuntu field preservation, contributor/source
canonicalization, same-content no-op, base-file refresh, safe internal
symlinks, and escaping-link rejection. `ProotLauncherTest` verifies the
strict bind. Physical devices show the strict `/etc/os-release!` bind in the
live PRoot argv; a fresh shell-content probe remains a follow-up if direct
terminal automation is needed.
