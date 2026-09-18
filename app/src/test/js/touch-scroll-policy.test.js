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

// 12. Touch scrolling uses xterm's pixel scroll position instead of the public
//     line-based API. A sub-row movement must remain visible and must not be
//     rounded away or sent through scrollLines().
const viewport = { clientHeight: 100, scrollHeight: 1100, scrollTop: 1000 };
const xtermRoot = { classList: { contains: () => false } };
const listeners = {};
const removedListeners = [];
const canceledFrames = [];
const animationFrames = [];
const pixelMoves = [];
const pixelState = { scrollTop: 1000 };
const scrollable = {
  getScrollPosition: () => ({ scrollTop: pixelState.scrollTop }),
  getScrollDimensions: () => ({ height: 100, scrollHeight: 2100 }),
  setScrollPosition: ({ scrollTop }) => {
    pixelState.scrollTop = scrollTop;
    pixelMoves.push(scrollTop);
  },
};
const fakeContainer = {
  ownerDocument: {
    getSelection: () => null,
    defaultView: {
      requestAnimationFrame: (callback) => {
        animationFrames.push(callback);
        return animationFrames.length;
      },
      cancelAnimationFrame: (frame) => canceledFrames.push(frame),
    },
  },
  querySelector: (selector) => selector === ".xterm-viewport" ? viewport : xtermRoot,
  addEventListener: (name, handler) => { listeners[name] = handler; },
  removeEventListener: (name) => removedListeners.push(name),
};
const lineMoves = [];
const fakeTerminal = {
  rows: 10,
  buffer: { active: { viewportY: 100, length: 1010 } },
  _core: { _viewport: { _scrollableElement: scrollable } },
  scrollLines: (lines) => lineMoves.push(lines),
};
global.document = {
  querySelector: (selector) => selector === ".xterm-viewport" ? viewport : xtermRoot,
};
const disposeTouchScroll = install(fakeContainer, fakeTerminal);
check("pixel delta moves the xterm viewport", scrollBy(-25), true);
check("pixel scroll position is updated directly", pixelState.scrollTop, 975);
check("pixel scrolling does not call line API", lineMoves, []);
check("sub-row movement is preserved", scrollBy(0.5), true);
check("fractional pixel position remains visible", pixelState.scrollTop, 975.5);

// 13. Touch moves follow the finger in the same frame, then release starts a
//     bounded native-like fling. There is no extra animation frame of drag
//     latency and the xterm position changes by pixels, not rows.
const preventedMoves = [];
listeners.touchstart({ touches: [{ clientY: 200 }], timeStamp: 1 });
listeners.touchmove({
  touches: [{ clientY: 170 }],
  timeStamp: 17,
  preventDefault: () => preventedMoves.push("first"),
});
listeners.touchmove({
  touches: [{ clientY: 140 }],
  timeStamp: 33,
  preventDefault: () => preventedMoves.push("second"),
});
check("touch moves update xterm immediately", pixelState.scrollTop, 1035.5);
listeners.touchmove({
  touches: [{ clientY: 200 }],
  timeStamp: 49,
  preventDefault: () => preventedMoves.push("reverse-down"),
});
check("active touch can reverse toward older output", pixelState.scrollTop, 975.5);
listeners.touchmove({
  touches: [{ clientY: 140 }],
  timeStamp: 65,
  preventDefault: () => preventedMoves.push("reverse-up"),
});
check("active touch can reverse back toward newer output", pixelState.scrollTop, 1035.5);
check("active touch moves prevent native page panning", preventedMoves,
  ["first", "second", "reverse-down", "reverse-up"]);
check("touch moves do not wait for animation frame", animationFrames.length, 0);
listeners.touchend({
  touches: [],
  changedTouches: [{ clientY: 140 }],
  timeStamp: 49,
});
check("release schedules fling", animationFrames.length, 1);
const firstFlingFrame = animationFrames.shift();
firstFlingFrame(65);
check("fling advances pixel position", pixelState.scrollTop > 1035.5, true);
check("fling continues while velocity remains", animationFrames.length, 1);

// 14. A second finger cancels the whole gesture until all fingers are up; a
//     later single-finger sequence can start cleanly again.
animationFrames.length = 0;
pixelState.scrollTop = 1000;
listeners.touchstart({ touches: [{ identifier: 1, clientY: 200 }] });
listeners.touchmove({
  touches: [{ identifier: 1, clientY: 170 }, { identifier: 2, clientY: 170 }],
  preventDefault: () => {},
});
listeners.touchend({
  touches: [{ identifier: 1, clientY: 170 }],
  changedTouches: [{ identifier: 2, clientY: 170 }],
});
listeners.touchmove({
  touches: [{ identifier: 1, clientY: 130 }],
  preventDefault: () => {},
});
check("multitouch cancellation stays latched", pixelState.scrollTop, 1000);
listeners.touchend({
  touches: [],
  changedTouches: [{ identifier: 1, clientY: 130 }],
});
listeners.touchstart({ touches: [{ identifier: 3, clientY: 200 }] });
listeners.touchmove({
  touches: [{ identifier: 3, clientY: 170 }],
  preventDefault: () => {},
});
check("new single-finger sequence can scroll", pixelState.scrollTop, 1030);
listeners.touchcancel({ touches: [], changedTouches: [{ identifier: 3 }] });

disposeTouchScroll();
check("dispose removes touch listeners", removedListeners, [
  "touchstart", "touchmove", "touchend", "touchcancel",
]);
check("dispose cancels pending fling", canceledFrames.length > 0, true);
delete global.document;

if (failures > 0) {
  console.error("\n" + failures + " touch-scroll policy test(s) failed");
  process.exit(1);
}
console.log("\nall touch-scroll policy tests passed");
