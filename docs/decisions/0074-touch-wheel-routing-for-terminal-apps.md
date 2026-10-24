# ADR-0074: Touch wheel routing for terminal applications

## Status

Accepted. User-reported OpenCode scrolling failure reproduced on the physical
API 29 device. The fix is verified in the retained live WebView without
restarting the guest command, then verified again after installing the rebuilt
APK with the user's approval.

## Problem

The scrollback adapter deliberately yielded when xterm's root had the
`enable-mouse-events` class. OpenCode enabled that mode and owned its content
inside the terminal screen. Android one-finger drags did not become mouse-wheel
reports, and moving terminal scrollback would not scroll the TUI's own content.

## Decision

Keep the existing pixel scrolling and bounded release momentum for the normal
terminal buffer. If mouse tracking is active or the alternate buffer is active,
convert one-finger vertical movement into row-sized DOM wheel events dispatched
to xterm's screen. Xterm owns protocol encoding and the alternate-buffer
cursor-key fallback; the adapter does not construct SGR or legacy mouse bytes.

Accumulate sub-row movement; emit one line-mode wheel event per crossed row.
Keep the coordinates where the drag started so crossing a pane or prompt does
not redirect the remaining gesture. Send no terminal scrollback momentum for
this route. The 10 CSS-pixel activation threshold preserves taps/focus and
long-press selection — superseded by ADR-0076, which ships 8px. Selection, a
second finger, cancellation, or leaving the application mode cancels the
gesture. A fresh gesture can start after release.

The pinned xterm bundle, host input bridge and Android permissions are unchanged.
Physical mouse-wheel events continue through xterm's existing handler.

## Verification

The regression first failed because a 40-pixel drag produced zero wheel events
in a zero-scrollback TUI. It now verifies direction/reversal, row accumulation,
tap threshold, stable panel coordinates, selection, multitouch cancellation,
mode exit, no application fling, and alternate-buffer fallback. Existing normal
scrollback/momentum tests remain in the Makefile JavaScript gate.

The live OpenCode page was updated through its debuggable WebView connection:
only the old touch adapter was disposed and replaced with the exact corrected
asset. The terminal, SSH channel and OpenCode processes were retained. This
live application is temporary; subsequent newly created WebViews require the
rebuilt APK. APK installation stops active commands and is tracked separately
from the live-session verification.
