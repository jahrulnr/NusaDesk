# ADR-0016: Ubuntu live-installer setup UX

## Status

Accepted and implemented in `presentation/widget/InstallerWizardView`,
`presentation/widget/InstallerLog`, their layout/drawables/dimens, and
`InstallerLogTest`. Presentation-only; no runtime, infrastructure, application,
domain, manifest, or Gradle changes.

## Context

The fresh-install setup surface lived inside a large vertical setup card with
step dots, a step label, a title, a body paragraph, a requirements box, a
progress bar, a single detail line, and a button. While the install ran, the
detail line replaced itself on every snapshot — the user saw one changing line
and no history of what had already happened. The card also left visible empty
space during active phases, and the step dots added vertical height without
communicating live progress.

The product goal (parent plan `conv_7499eb2feb2d2f68`) is a fresh setup that
feels like an Ubuntu live installer terminal: an append-only, monospace, dark
log panel where each phase (prepare, download, verify, extract, ready, failure)
adds a truthful line, download shows wget-like determinate progress, the
history is preserved while installing, and one thumb-reachable bottom action
drives the flow. The launcher is already good and is not redesigned here.

The installer (`AndroidRuntimeInstaller`) already publishes truthful
`RuntimeSnapshot` objects with state and detail strings. The download detail
carries a percent (`"Downloading <name> · <percent>%"`); verify, extract, and
ready carry constant detail strings; failure carries the exception message.
No backend change is needed — the presentation derives the terminal log from
these snapshots.

## Decision

1. **Append-only terminal log, not a single replacing detail line.**
   `InstallerLog` maps each snapshot to a phase-tagged line
   (`download  …`, `verify  …`, `extract  …`, `ready  …`, `error  …`).
   Consecutive snapshots in the same phase do not produce duplicate lines
   (deduplication by state). The terminal viewport is a `ScrollView` containing
   a monospace `TextView` with a fixed dark background; it auto-scrolls to the
   bottom so the latest line is always visible.

2. **Download progress is a determinate bar, not log spam.**
   The percent is parsed from the snapshot detail and shown in the progress bar
   with an accessible content description. The log line for download strips the
   `· N%` suffix so the line reads as the stable phase label. The live detail
   text below the bar still shows the full detail (including percent) for users
   who want the number. No speed or ETA is invented — only what the snapshot
   truthfully carries.

3. **Non-download phases show indeterminate activity.**
   Verify and extract show an indeterminate progress bar next to the new log
   line, so the user sees active work without a false percent.

4. **Compact, readable setup surface.**
   The card holds: a context title, the bounded terminal log viewport
   (180 dp, ~10–12 monospace lines), the progress bar, the current detail line,
   the requirements box (only before setup starts), and one full-width bottom
   button. The step dots and step label are removed. The button is 52 dp tall
   (≥ 48 dp touch target) and full-width, positioned at the bottom of the
   wizard for thumb reachability.

5. **Bounded history.**
   `InstallerLog` retains at most 80 lines; older lines are dropped from the
   top. The `ScrollView` has a fixed height so the viewport never grows
   unbounded regardless of line count.

6. **History lifecycle.**
   History is cleared and reinitialized with the prepare-context line only when
   a new install attempt starts — the first `DOWNLOADING` after `NOT_INSTALLED`
   (start) or after `FAILED` (retry), detected by `InstallerLog.isNewAttempt`.
   History is preserved across snapshot renders and across activity recreation
   as far as the view's in-memory state survives. The view does not persist to
   disk; that would outlive the install attempt and is not justified for a
   one-time setup surface.

7. **Accessibility.**
   The terminal log `TextView` is an `ACCESSIBILITY_LIVE_REGION_POLITE` so
   screen readers announce new lines as the install progresses. The progress
   bar carries a content description with the percent (download) or "Working…"
   (indeterminate). The action button has a content description matching its
   label. The monospace font scales with the system font setting. No state is
   represented by color alone — every phase has a text label.

8. **Existing callbacks and semantics unchanged.**
   `setOnActionListener`, `setStorageRequirement`, and `render(RuntimeSnapshot)`
   keep their signatures. `DesktopHomeView` calls them exactly as before. The
   `RuntimeSnapshot` / `RuntimeState` contract is unchanged; the view only
   reads `getState()` and `getDetail()`.

9. **Pure logic extracted for testing.**
   `InstallerLog` is pure Java (no Android imports) so the phase-to-line mapping,
   percent parsing, percent stripping, deduplication, bounded history, and
   new-attempt detection are unit-tested in `InstallerLogTest` without a device.

Rejected alternatives:

- **Keep step dots and add a log below.** The dots added vertical height and
  communicated only a discrete step index, not live progress. The terminal log
  already communicates the phase, so the dots were redundant.
- **Append every download percent as a new log line.** That would produce 20+
  download lines for a single phase. The percent belongs in the progress bar;
  the log line is the stable phase label.
- **Persist log history to disk across activity recreation.** The setup is a
  one-time flow; a persisted copy would outlive the attempt and add lifecycle
  complexity for no user benefit.
- **Invent speed/ETA for download.** The snapshot carries only a percent. Any
  speed or ETA would be fabricated. The detail text shows the truthful percent.
- **Add a second action button (e.g. cancel).** The installer does not expose
  cancel, and a non-functional cancel button would be a lie. One primary action
  (Start / Retry) is the honest contract.

## Consequences

- Fresh setup now reads as a live terminal: the user sees prepare, download,
  verify, extract, and ready as successive appended lines, with the download
  percent in a determinate bar and the history preserved.
- The setup card is more compact: no step dots, no step label, no separate body
  paragraph (the prepare context is the first terminal line), and a bounded log
  viewport instead of unbounded vertical growth.
- One full-width bottom button is the only action; it is Start setup before
  install, disabled "Installing…" during work, and Try again on failure. On
  ready, it is hidden (Linux starts automatically from an app launch).
- The pure `InstallerLog` is tested in plain JUnit; the Android view layer
  (scrolling, live region, layout) is verified on a device or emulator, not in
  `./gradlew test`.
- The old `wizard_step_dot` / `wizard_step_gap` dimens,
  `wizard_step_active` / `wizard_step_inactive` drawables, and the
  `wizard_step_count` / `wizard_step_welcome` / `wizard_step_download` /
  `wizard_step_verify` / `wizard_step_extract` / `wizard_step_ready` /
  `wizard_ready_body` strings were removed because they became unused when the
  step dots and body paragraph were replaced by the terminal log. Lint
  (`abortOnError true`, `warningsAsErrors true`) would fail on unused resources.
