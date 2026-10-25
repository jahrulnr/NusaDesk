# ADR-0076: Retain terminal touch targets across DOM repaint

## Status

Accepted following a physical reproduction of freezing/one-direction drags.
The replay tools live under `scripts/qa/terminal-touch/`.

## Cause

The native-copy integration keeps xterm DOM rows hit-testable. A finger can
therefore start on a text span which xterm replaces during the first scroll or
TUI redraw. Subsequent TouchEvents retain that original target. Once detached,
the target no longer bubbles through the terminal container; the adapter loses
move/end events although the finger is still moving. Short separate swipes did
not exercise this lifecycle.

Before the fix, the original target received 166 moves and touchend while the
container saw only ten moves and one wheel event. The stream loss followed a
DOM replacement, not a configured one-second timeout. CSS touch-action and
reserving the first cancelable move were also tested alone, but did not solve it.

## Decision

Retain move/end/cancel listeners on the original target for the gesture, as
well as the container capture listeners. Deduplicate the same event with a
WeakSet while its target is still attached. Keep all targets involved in a
multitouch sequence until the last finger ends; release listeners on finish,
cancel, a fresh sequence, and disposal. Never call preventDefault on an event
whose cancelable flag is false.

Preserve native long-press selection, the current xterm renderer and its
upstream assets. Do not make text pointer-events:none, replace the renderer,
or intercept Android touches to hide this integration defect. No new runtime
dependency or JavaScript bridge is added.

Normal-buffer drags retain fractional-pixel positioning and row-offset
presentation. TUI gestures retain xterm wheel/protocol encoding from ADR-0074;
the guest still decides its whole-row scrolling behavior. This decision does
not promise pixel animation of a guest TUI or a constant frame rate.

## Verification

The regression first failed on the old adapter. It now simulates the touched
row being replaced, verifies continued normal/TUI moves and reversal, fractional
pixels, duplicate-event suppression, multitouch termination, cancel, and
dispose cleanup. The preliminary 10px candidate passed five reversing
physical gestures per mode, and native text selection was observed on that
candidate. On 2026-10-25 the final installed 8px artifact separately passed
five actual guest-shell and five actual OpenCode gestures lasting 6.5–7.2
seconds. See [the evidence ledger](../evidence/terminal-gesture-lifetime.md)
for raw traces, hashes, scope and limitations. Native selection was not
independently reaccepted on this final artifact.

Replacing xterm remains an option if a renderer/emulation defect is demonstrated.
It is not selected for this fault: the lost events arise at our DOM/input seam,
and repeated tests establish a small correction at that seam. No alternative
terminal has been built or benchmarked in this task.

The final adapter uses an 8px activation threshold (rather than 10), supported
by a regression for the observed first cancelable movement at 9.5px. This
complements retained-target delivery; it is not a substitute for it.
