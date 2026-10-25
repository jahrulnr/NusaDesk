# ADR-0077: Pinned homescreen shortcuts for launcher entries

## Status

Accepted for the launcher surface: every pinnable tile offers "Add to Home
screen" from its long-press menu, and the resulting shortcut carries the
NusaDesk mark as a launcher-style badge.

## Context

The shell's launcher grid is the user's app list, but every open still starts
inside NusaDesk. Android launchers have offered per-app pinned shortcuts
since API 26 — the same mechanism Chrome uses to put a web app on the home
screen — so users expect "add to home screen" on any tile that opens an app.

Long-press previously jumped straight into the edit form, so there was no
menu at all, and no way to surface the pin affordance without hiding edit
behind another gesture.

## Decision

- **One actions menu per entry.** `LauncherEntry.hasActions()` names which
  tiles respond to long-press: registered web apps and terminal commands,
  installed Linux GUI apps, and the curated terminal. The product's own
  System and Logs surfaces and the Add-app action are never pinnable — they
  are the shell, not apps through it.
- **The icon follows the tile's own source order** — the user's image, then
  the endpoint's favicon, then the kind's bundled vector, then a monogram on
  the tile plate — the same contract `LauncherIconPolicy` names — with the
  NusaDesk mark badged in the corner on a white plate, mirroring how Chrome
  badges pinned web apps. Decoding is bounded exactly like the tile's reads:
  user images go through `IconBitmapDecoder.decodePicked` and file paths are
  sampled down to launcher size.
- **Pinned shortcuts target a non-exported trampoline.** The launch intent
  lands on `PinnedShortcutActivity` — which the system's shortcut path may
  start even though it is not exported — and the trampoline stamps a
  persistent random proof (`PinnedShortcutProof`) before forwarding to the
  shell. `MainActivity` is exported, so a composed `ACTION_VIEW` from another
  app cannot trigger app launches: it fails the proof check and is ignored.
  Malformed bundles degrade to the shell, never a crash.
- **Deletion disables the homescreen shortcut.** Every grid render reconciles
  live entry ids against `ShortcutManager.getPinnedShortcuts()`: a gone
  entry's pin is `disableShortcuts`'d — the launcher removes or dims its
  icon and reports "This app was removed." — while a returning one (a
  reinstalled Linux app, deterministic id) is re-enabled. There is no unpin
  API; disabling is the strongest deletion signal the platform exposes. The
  set the app disabled is tracked locally because `isEnabled()` semantics
  differ across OEMs. Device reality (Samsung OneUI, API 29): the disabled
  pin stays visible but dimmed and inert; other launchers are unverified.
- **Icons and labels follow their sources.** A pinned shortcut is a snapshot
  at pin time, but the platform's `updateShortcuts` can re-push a pinned
  shortcut's icon and label. Runtime-created pinned shortcuts can also update
  intents; NusaDesk keeps launch identity stable and resolves it on open.
  Every render compares
  a cheap fingerprint of the icon sources — image token and file revision,
  favicon bitmap generation id, bundled vector, label — against the fingerprint stored at
  pin/last-push time, and re-pushes only on an actual change: a new custom
  image, a re-fetched favicon, or a rename reaches the homescreen without
  re-pinning. Steady-state renders avoid rebuilding icons; a throttled update
  is retried by a later render. Web/command pins resolve the live registry id,
  and Linux pins resolve their desktop id through a fresh guest catalog, so a
  moved desktop file or renamed app does not reopen stale metadata.
  Re-registering a web app creates a new id whose old pin stays disabled.
- **An unloaded source is not a deletion.** Initial renders skip reconciliation
  until the registered app stores have loaded. Linux pins are left unchanged
  until the guest catalog has actually reported; a stopped runtime or failed
  scan must not read as "all Linux apps deleted". An authoritative empty scan
  does disable missing pins. Revival updates label/icon in the same pass.
- **Cold launches wait for readiness.** Web/command shortcuts wait for the
  running runtime and tab service before opening, then re-read their registry.
  Linux shortcuts wait for the current catalog rather than launching their
  saved file path. If the file disappeared while a task remains RUNNING or
  MINIMIZED, the pin resumes that owned task like its retained Home tile;
  absent/failed tasks are not revived. Requests survive Activity recreation, dispatch once, and
  user navigation cancels a waiting request.
- **Repeat taps deliver fresh intents.** Pin launch intents include `NEW_TASK`
  and `CLEAR_TOP`. On the API 29 device, a shortcut-started task retained the
  finished trampoline as its base intent even though MainActivity was the
  only remaining Activity. A later `NEW_TASK` tap merely foregrounded that
  task: neither trampoline `onCreate` nor shell `onNewIntent` ran. `CLEAR_TOP`
  forces the shortcut path to execute again. Reconciliation migrates old
  pins' launch flags, including unscanned Linux pins, without re-pinning.
- **`singleTask` launch mode.** The shell is one Activity; a pinned shortcut
  must re-enter the running instance via `onNewIntent`, not stack a second
  shell. Dispatch consumes the extras it used; a task-restore redelivery is
  intentionally re-dispatched since the user still asked for that app.
- **Accessibility states the gesture.** Tiles that offer actions say
  "long press for actions" in their content description; the menu is never
  color- or icon-only.

## Consequences

- `infrastructure/homescreen/` owns every `ShortcutManager`/proof/trampoline
  surface; domain and application layers stay free of Android shortcut APIs.
- Stale shortcuts degrade gracefully: web and terminal entries look up the
  live registry, Linux entries resolve against the guest catalog, and
  every failure path lands on the shell.
- The launcher's confirm UI and shortcut lifetime belong to the device
  launcher; OEMs that refuse pinning get a toast, not a silent no-op, and
  OEMs that keep disabled icons visible still make them inert.
- `singleTask` also prevents duplicate shells from any other re-launch path,
  matching the single-session product contract.

## Audit corrections (2026-10-25)

The first implementation violated its unloaded-source contract: construction
rendered empty app lists and missing Linux ids were disabled even when the
catalog had not reported. Focused regressions reproduced that failure, stale
labels on revival, and unbounded panorama decoding. The correction also
tracks file size/mtime for images replaced in place. Cold command dispatch,
recreation, cancellation, and live Linux metadata have executable tests.

Android permits updating mutable pinned shortcuts through `updateShortcuts`;
intents are not universally immutable. Disabled pins' visual treatment belongs
to the launcher; no removal on Pixel/stock is claimed without device evidence.
See [Android shortcut management](https://developer.android.com/develop/ui/views/launch/shortcuts/managing-shortcuts)
and [ShortcutManager](https://developer.android.com/reference/android/content/pm/ShortcutManager).

The warm-tap defect was reproduced after a cold shortcut start, rather than
inferred from unit tests. A live JDWP method-entry trace observed only shell
`onStart` and no trampoline/dispatch entry; the task dump showed its base
intent still targeted the finished trampoline. Two regressions failed before
adding launch flags and migration. Full task reuse still requires device
verification; Robolectric only verifies the dispatched intent contract.
See [Android intent flags](https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_CLEAR_TOP).
