# ADR-0060: Keep xterm's hidden IME field empty between keystrokes

## Status

Accepted, implemented in `app/src/main/assets/terminal/ime-guard.js` and wired by
`terminal.html`. Covered by `app/src/test/js/ime-guard-policy.test.js`, which
runs inside `make test`. Verified live against the packaged page and the pinned
bundle in a real Chromium over CDP, and device-verified on the arm64 phone
(Samsung SM-G970F, Android 12/API 31) for the page-side rows; the real-IME rows
still need a human keyboard session.

## Context

Reported symptom (2026-10-09): typing a single character in the terminal
occasionally echoed earlier text into the prompt. Typing `a` produced
`aapt upgrade`, other times `aaapt`, as if a command line or history had been
replayed. Closing and reopening the terminal cleared it while Linux kept
running.

The cause is upstream, inside the pinned `@xterm/xterm` 6.0.0 bundle, and it is
two compounding defects tracked as xtermjs/xterm.js#6078, with #6012, #6045 and
#5887 as the surrounding family:

1. xterm's hidden `.xterm-helper-textarea` is emptied only on blur and on a real
   Enter / Ctrl+C keydown (`CoreBrowserTerminal._keyDown`). On Android WebView
   printable keys arrive as IME commits rather than real keydowns, so the field
   silently accumulates everything typed since the last Enter.
2. `CompositionHelper._handleAnyTextareaChanges()` (the `keyCode 229` path)
   diffs that field against a snapshot taken at the keydown and emits
   `newValue.replace(oldValue, '')`. The moment an IME edit is not a clean append
   (`autocorrect`, an in-place replacement, key rollover), `replace` matches
   nothing and the whole field is emitted as input; the same-length branch emits
   `newValue` outright.

Those bytes travel our ordinary stdin path, the guest shell echoes them at the
cursor, and the screen shows earlier text being typed again, sometimes ahead of
the character just typed.

The host is not the source. The Android side has exactly one stdin path
(`TerminalAppView.sendInput` to `TerminalSessionController.write`), it is FIFO on
a single-thread executor (`SshClientBridge`), frames are dropped, never delayed,
when no channel is open, there is no input queue, replay buffer, history restore,
or local echo, and `TerminalPendingWrites` is a one-shot host-to-page output
queue only. A sticky accessory CTRL, or a mis-tapped HOME / arrow key, produces a
lookalike (readline's cursor moves, so the next character lands in front of the
line that is already on screen) but re-sends no bytes; it is a different
signature and is out of scope here.

## Decision

1. **Our own page glue keeps the field empty.** `ime-guard.js` sits next to
   `touch-scroll.js` and `dom-selection.js`; the bundled `xterm.js` stays
   verbatim, as `THIRD_PARTY_NOTICES.txt` requires.
2. **The sweep only runs after `IDLE_DELAY_MS` (400 ms) of quiet.** That is
   longer than every `setTimeout(0)` settle window xterm schedules, so the value
   can never shrink inside the keydown diff window or the deferred composition
   finalize. A shrink in that window is reported as a stray `DEL` to the shell
   (xtermjs/xterm.js#6045).
3. **An open composition owns the field.** `compositionstart` cancels a pending
   sweep outright and no sweep runs while composing, because xterm reads the
   field after `compositionend` and its composition math is anchored to the
   length captured at `compositionstart`.
4. **`screenReaderMode` disables the sweep.** A screen reader announces the
   accumulated value, which is exactly why the upstream per-keystroke clear
   (PR #4265) was rejected. The terminal page never enables that mode today.
5. **The adapter adds no terminal-side surface.** It reads and clears xterm's own
   helper field, never calls into the session, and exposes only `sweepNow()` and
   `dispose()`.

## Alternatives considered

- **Patch the bundled `xterm.js`.** Forbidden by the directory's third-party
  notice, and it would silently diverge from the pinned upstream artifact.
- **Clear the field on every keystroke (upstream PR #4265).** Rejected upstream
  for the screen-reader regression; the idle debounce plus the explicit gate gets
  the same protection without touching the accessibility case.
- **Filter "suspicious" input on the host** (for example drop keystrokes that
  arrive as a long chunk). Indistinguishable from a legitimate paste, and it
  would corrupt real input.
- **Wait for an upstream release.** The defect fabricates shell input on a device
  we ship. The mitigation is small, testable, and independent of the bundle;
  re-pinning a release that carries the real fix (a positional diff) remains the
  actual fix.
- **Change the accessory key row as well.** Out of scope: that path moves
  readline's cursor and re-sends nothing.

## Consequences

### Positive

- Earlier text can no longer be re-emitted as input. Measured live: with the
  sweep starved the real page emits the whole field (`apt upgradé`) after one
  synthetic `keyCode 229` keydown plus an in-place IME edit; with the guard the
  field is empty first and the same sequence emits only the in-flight character.
- The mitigation lives in our glue, is pinned by a Node policy test inside
  `make test`, and can be deleted cleanly once the bundle carries the fix.

### Negative and limitations

- The field is also cleared for the IME, so an IME flow that expects its own
  scratch value to survive loses that context. No regression appeared in the
  synthetic cases; a real-IME device pass is still pending.
- A mis-diff of the *in-flight* character is beyond this adapter (upstream
  #6045): a key rollover can still duplicate or drop the character being typed.
  The sweep guarantees that no *earlier* text can be replayed, nothing more.
- A pause mid-line lets the sweep run, so the field is not a durable record of
  the current line. That is the point (nothing there is sent to the guest), but
  it means the page cannot use the field as state.

## Verification

JVM / Node, `make test`: Gradle unit tests plus every `app/src/test/js/*.test.js`.
`ime-guard-policy.test.js` pins the gate (composing, `screenReaderMode`, empty
field), the schedule (settle window respected, cancel on `compositionstart`,
re-arm after `compositionend`, unknown events only delay), the adapter behaviour
(no clear inside the settle window, a composition is never cleared, `dispose`
detaches every listener), and a model of the upstream diff showing the
re-emission before the guard and its absence after.

Live (2026-10-09, headless Google Chrome driven over CDP, packaged
`terminal.html` plus the pinned bundle served locally): the sequence in the
paragraph above, plus the composition gate (an open composition was never
cleared, and the value was cleared after `compositionend`).

Device pass (Samsung SM-G970F, Android 12/API 31, arm64, 2026-10-09;
QA-debuggable APK signed with the installed release key, so the installed 0.13.0
signature matched and the guest data was preserved): the terminal ran against the
live guest shell and the installed page loads `ime-guard.js`. Between keystrokes
the helper field was empty; an open composition kept its value across the idle
window and the field emptied after `compositionend`; with the sweep starved, one
`keyCode 229` keydown plus an in-place edit made the page emit the whole field
(`{"t":"input","d":"apt upgradé"}`), while with the guard live the identical
sequence emitted only the in-flight character (`{"t":"input","d":"é"}`). Typed
commands (`echo IMG-SMOKE-OK`, `echo IMG-FINAL-OK`) each echoed exactly once, so
the input path itself is unchanged. Device rows IMG-001..IMG-003, which need a
human typing on a real IME, remain open; `docs/test-plan.md` carries the
row-by-row record. An x86_64 UI-QA emulator can cover the page-side rows (no live
shell needed) but not a real Android IME delivery shape.
