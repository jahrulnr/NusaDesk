# ADR-0075: Grapheme-aware terminal cell widths

## Status

Accepted for the terminal. User-reported half-rendered emoji reproduced in
OpenCode on the physical API 29 device.

## Problem

The bundled xterm 6.0.0 defaults to Unicode 6 widths. A live `😄` occupied one
cell (about 7.81 CSS pixels); the fallback color glyph needs two. The DOM
renderer applied negative letter spacing and the following cell's background
covered part of the glyph. This is a grid-width problem, not missing emoji data.

## Decision

Vendor the unmodified official `@xterm/addon-unicode-graphemes` 0.4.0 browser
bundle and MIT notice. Its npm package identifies commit
`f447274f430fd22513f6adbf9862d19524471c04`, the same source release as the
packaged xterm 6.0.0. Verify npm's SHA-512 integrity before extraction, pin the
JS SHA-256 in notices/regression tests, and load it locally before terminal open
and incoming output. No CDN, font download or renderer replacement is added.

The addon activates `15-graphemes`: it covers emoji presentation selectors,
ZWJ sequences and modifiers as well as ordinary wide codepoints. Unicode11
alone was evaluated but still assigned `⚠️` one column, leaving a reported
case unresolved. Upstream labels the grapheme addon experimental; adoption is
bounded by tests with the actual packaged core and physical rendered output.
Font artwork and coverage remain device-dependent; a correct two-cell width
does not supply a glyph missing from the installed font.

Do not widen every DOM span with CSS or rewrite the guest's output. Both would
break terminal cursor coordinates, adjacent text and full-screen TUI layout.

## Verification

The regression executes the production page's addon activation with the real
packaged xterm core. It checks `😄`, `✅`, `⚠️`, `👩‍💻`, `👍🏽`, `🇮🇩`, CJK,
combining accents, ordinary text/box drawing, variation selectors split across
writes, and wrapping a two-cell emoji at the last column.

For the already-open device session, the same vendored addon was loaded into
the retained terminal and a resize/refit requested a guest repaint. Existing
cells do not retroactively acquire new widths, so a redraw is necessary for
live verification. Subsequent output has correct widths. The OpenCode PIDs
remain unchanged; the new APK is tracked separately from this temporary live
application. Fresh terminal pages activate the provider before any output.
The rebuilt APK was subsequently installed with approval; a new terminal and
the resumed OpenCode conversation verified the provider without live injection.

## Sources

- [Pinned upstream README](https://github.com/xtermjs/xterm.js/blob/6.0.0/addons/addon-unicode-graphemes/README.md)
- [Exact npm package metadata](https://registry.npmjs.org/@xterm/addon-unicode-graphemes/0.4.0)
- Asset hashes and licensing: `app/src/main/assets/terminal/THIRD_PARTY_NOTICES.txt`.
