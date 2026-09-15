/*
 * Pure-JS unit tests for the touch-scroll policy in touch-scroll.js.
 *
 * No DOM, no browser: this runs under plain Node and exercises the
 * side-effect-free `policy(opts)` decision function that the terminal page
 * uses to translate a one-finger drag into bounded viewport scrollback.
 *
 * Run:  node app/src/test/js/touch-scroll-policy.test.js
 *
 * This mirrors the project's JUnit policy tests: it pins the deterministic
 * behaviour (threshold, clamp, multi-touch cancel, mouse-tracking gate) so a
 * later change to the adapter cannot silently regress the gesture contract.
 */
"use strict";

const assert = require("assert");
const path = require("path");
const { policy, install, scrollBy } = require(path.join(
  __dirname,
  "..",
  "..",
  "main",
  "assets",
  "terminal",
  "touch-scroll.js"
));

let failures = 0;
function check(name, actual, expected) {
  try {
    assert.deepStrictEqual(actual, expected);
    console.log("ok - " + name);
  } catch (e) {
    failures++;
    console.error("FAIL - " + name);
    console.error("  expected " + JSON.stringify(expected));
    console.error("  actual   " + JSON.stringify(actual));
  }
}

const T = 10; // threshold, must match SCROLL_THRESHOLD_PX in touch-scroll.js

// 1. Below the threshold and not yet active: a tap, never stolen.
check(
  "below threshold is a tap",
  policy({
    isMouseTracking: false,
    touchCount: 1,
    active: false,
    absDeltaFromStart: 4,
    deltaY: 4,
    scrollTop: 0,
    maxScroll: 1000,
    threshold: T,
  }),
  { scroll: false, scrollTop: 0, preventDefault: false, cancel: false }
);

// 2. Crossing the threshold with an upward drag scrolls down (scrollTop up).
check(
  "crossed threshold scrolls and clamps within range",
  policy({
    isMouseTracking: false,
    touchCount: 1,
    active: false,
    absDeltaFromStart: 20,
    deltaY: -20, // finger moved up -> reveal lower content
    scrollTop: 100,
    maxScroll: 1000,
    threshold: T,
  }),
  { scroll: true, scrollTop: 120, preventDefault: true, cancel: false }
);

// 3. Once active, a small frame delta still scrolls (momentum continues).
check(
  "active scrolls even below per-frame threshold",
  policy({
    isMouseTracking: false,
    touchCount: 1,
    active: true,
    absDeltaFromStart: 2,
    deltaY: -3,
    scrollTop: 120,
    maxScroll: 1000,
    threshold: T,
  }),
  { scroll: true, scrollTop: 123, preventDefault: true, cancel: false }
);

// 4. Clamp at the top (scrollTop never negative).
check(
  "clamps at top",
  policy({
    isMouseTracking: false,
    touchCount: 1,
    active: true,
    absDeltaFromStart: 50,
    deltaY: 60, // finger moved down past the top
    scrollTop: 5,
    maxScroll: 1000,
    threshold: T,
  }),
  { scroll: true, scrollTop: 0, preventDefault: true, cancel: false }
);

// 5. Clamp at the bottom (scrollTop never exceeds maxScroll).
check(
  "clamps at bottom",
  policy({
    isMouseTracking: false,
    touchCount: 1,
    active: true,
    absDeltaFromStart: 50,
    deltaY: -60,
    scrollTop: 990,
    maxScroll: 1000,
    threshold: T,
  }),
  { scroll: true, scrollTop: 1000, preventDefault: true, cancel: false }
);

// 6. Multi-touch cancels and never steals the gesture.
check(
  "two fingers cancel",
  policy({
    isMouseTracking: false,
    touchCount: 2,
    active: true,
    absDeltaFromStart: 50,
    deltaY: -30,
    scrollTop: 100,
    maxScroll: 1000,
    threshold: T,
  }),
  { scroll: false, scrollTop: 100, preventDefault: false, cancel: true }
);

// 7. Mouse-tracking mode (vim/tmux/less) defers entirely to xterm.
check(
  "mouse tracking defers to xterm",
  policy({
    isMouseTracking: true,
    touchCount: 1,
    active: true,
    absDeltaFromStart: 50,
    deltaY: -30,
    scrollTop: 100,
    maxScroll: 1000,
    threshold: T,
  }),
  { scroll: false, scrollTop: 100, preventDefault: false, cancel: false }
);

// 8. No scrollable content: defer to native, do not hijack.
check(
  "no scrollback defers to native",
  policy({
    isMouseTracking: false,
    touchCount: 1,
    active: true,
    absDeltaFromStart: 50,
    deltaY: -30,
    scrollTop: 0,
    maxScroll: 0,
    threshold: T,
  }),
  { scroll: false, scrollTop: 0, preventDefault: false, cancel: false }
);

// 9. Negative maxScroll is normalised to 0 (defensive).
check(
  "negative maxScroll normalised",
  policy({
    isMouseTracking: false,
    touchCount: 1,
    active: true,
    absDeltaFromStart: 50,
    deltaY: -30,
    scrollTop: 0,
    maxScroll: -5,
    threshold: T,
  }),
  { scroll: false, scrollTop: 0, preventDefault: false, cancel: false }
);

// 10. While the platform's own long-press selection holds terminal text, a
//     drag extends that selection — the adapter must not scroll or
//     preventDefault (which would fight the stock selection overlay).
check(
  "active platform selection defers the drag",
  policy({
    isMouseTracking: false,
    hasSelection: true,
    touchCount: 1,
    active: false,
    absDeltaFromStart: 50,
    deltaY: -30,
    scrollTop: 100,
    maxScroll: 1000,
    threshold: T,
  }),
  { scroll: false, scrollTop: 100, preventDefault: false, cancel: false }
);

// 11. A scroll in progress stops the moment a selection appears: the gesture
//     now belongs to the selection handles, not the viewport.
check(
  "scroll yields mid-gesture when a selection appears",
  policy({
    isMouseTracking: false,
    hasSelection: true,
    touchCount: 1,
    active: true,
    absDeltaFromStart: 50,
    deltaY: -30,
    scrollTop: 100,
    maxScroll: 1000,
    threshold: T,
  }),
  { scroll: false, scrollTop: 100, preventDefault: false, cancel: false }
);

// 12. scrollBy uses xterm's public scrollLines API and preserves fractional
//     row movement across frames.
const viewport = { clientHeight: 100, scrollHeight: 1100, scrollTop: 1000 };
const xtermRoot = { classList: { contains: () => false } };
const listeners = {};
const fakeContainer = {
  ownerDocument: { getSelection: () => null },
  querySelector: (selector) => selector === ".xterm-viewport" ? viewport : xtermRoot,
  addEventListener: (name, handler) => { listeners[name] = handler; },
};
const lineMoves = [];
const fakeTerminal = { rows: 10, scrollLines: (lines) => lineMoves.push(lines) };
global.document = {
  querySelector: (selector) => selector === ".xterm-viewport" ? viewport : xtermRoot,
};
install(fakeContainer, fakeTerminal);
check("pixel delta scrolls whole xterm rows", scrollBy(-25), true);
check("scroll uses xterm public API", lineMoves, [-2]);
check("fractional pixels accumulate to the next row", scrollBy(-5), true);
check("accumulated movement emits one more row", lineMoves, [-2, -1]);
delete global.document;

if (failures > 0) {
  console.error("\n" + failures + " touch-scroll policy test(s) failed");
  process.exit(1);
}
console.log("\nall touch-scroll policy tests passed");
