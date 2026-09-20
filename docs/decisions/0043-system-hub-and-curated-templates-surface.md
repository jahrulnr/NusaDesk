# ADR-0043: The System hub and the curated templates surface

## Status

Accepted; the three-level hub lands 2026-09-20 (System → Settings /
One-click install / About NusaDesk). The templates surface is scoped here;
its first *installable* entry (the USB driver's prebuilt artifacts) lands
with the packaging slice, and the packaging rules are restated below so the
two cannot drift apart.

## Context

The System screen had grown into one long scroll mixing runtime state,
session details, per-setting rows (workspace, battery optimization, boot
start, app settings) and the update surfaces. The product direction is the
Android-Settings shape instead: a **System hub** with three grouped rows,
each opening its own page:

- **Settings** — workspace, battery optimization, boot start, app
  permissions;
- **One-click install** — a store-like templates list (termux api, adb, …);
- **About NusaDesk** — system state, technical details, the GitHub page, and
  how it works.

The repository draws one hard line that shapes the middle row: AGENTS.md
lists **arbitrary image/package/app installation** as out of scope. A
store-like surface is therefore acceptable only as a **curated, allowlisted
catalog of declarative recipes** — the same discipline already used for the
runtime catalog and the guest writers — never as a passthrough to arbitrary
URLs, packages, or user-supplied commands.

## Decision

1. **System is a hub with three pages.** The rows live in the existing
   single-activity desktop shell; no new activities and no second navigation
   system. Sub-pages carry a back row, and the system back action on a
   sub-page returns to the hub before it leaves the screen.
2. **Each page owns one responsibility.** Settings keeps the existing
   control rows (workspace, battery optimization, boot start, app settings);
   About keeps the existing state/session details plus the GitHub link and
   the how-it-works contract dialog; One-click install owns the templates
   list. The MainActivity entry points that render into those rows stay
   unchanged, so state ownership does not move.
3. **Templates are declarative and allowlisted.** A template declares only
   bounded steps: verified-asset extraction (pinned SHA-256), writer-provided
   guest files (the `GuestXxxWriter` pattern), and pinned guest packages
   scoped by an explicit decision. It never carries a user-supplied URL or
   command, and it reports the existing typed states (`NOT_INSTALLED` …
   `READY` / `FAILED`), with installs serialized through the setup pipeline
   (ADR-0017) so they cannot race a session start.
4. **Entries are honest about what exists today.** The USB/adb driver
   (ADR-0042) and the Termux compatibility layer (ADR-0036) ship and activate
   with every session and say exactly that; they do not render a fake
   Install button. The first genuinely installable template is the driver's
   **prebuilt artifacts** — a patched libusb plus the compiled shim —
   produced by a reproducible build script, pinned by digest, and extracted
   by the provisioning step, following the `libproot.so` precedent
   (AGENTS.md documents that exception). That packaging removes the current
   gcc/libb-header requirement from the user's rootfs.
5. **The out-of-scope line stands.** Templates stay curated and declarative;
   there is still no arbitrary package installation surface.

Rejected alternatives:

- **A separate Activity per page.** Duplicates navigation and back handling
  outside the established desktop shell for no gain.
- **A generic plugin/registry framework before real templates exist.**
  AGENTS.md forbids abstractions "for later"; the catalog grows from concrete
  templates (driver first), and the step types are added when a template
  needs them.
- **Shipping gcc and libusb headers into every rootfs** (or compiling on
  first use forever). Building in the guest was an interim: it bloats the
  curated rootfs and makes the driver's correctness depend on a toolchain the
  product does not own. Prebuilt, digest-pinned assets are the product path.
- **An "apt install anything" store.** Reverses the project's security
  posture; the curated catalog is what makes the surface compatible with the
  existing rules.

## Consequences

- The System screen reads like a settings app: three rows, three pages, each
  with a single responsibility; the existing state renderers keep feeding the
  same rows from their new page.
- One-click install becomes the single place where guest add-ons are
  provisioned; the driver's packaging slice plugs into it as the first real
  template, and future add-ons (extra toolchains, shells, editors) need only
  new catalog entries.
- Platform gates are untouched: templates provision the *guest*; they never
  grant an Android permission, and USB consent or debugging authorization
  remain user decisions (ADR-0041, ADR-0042).
