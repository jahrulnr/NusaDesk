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

// 15. Release momentum must reflect the finger's final motion only. A finger
//     held still before release must not fling: the last movement sample ages
//     out of FLING_SAMPLE_WINDOW_MS, so a hold-release stays put instead of
//     lurching. A fake clock makes the hold deterministic.
const realNow = Date.now;
let fakeNow = 0;
Date.now = function () { return fakeNow; };
try {
  const viewport2 = { clientHeight: 100, scrollHeight: 2100 };
  const xtermRoot2 = { classList: { contains: () => false } };
  const screen2 = { style: {} };
  const listeners2 = {};
  const removedListeners2 = [];
  const animationFrames2 = [];
  const pixelMoves2 = [];
  const pixelState2 = { scrollTop: 1000 };
  // cellHeight = scrollHeight / buffer.length = 2100 / 105 = 20px; the fake
  // mimics xterm's Viewport._handleScroll, which rounds scrollTop to a row.
  const buffer2 = { viewportY: 50, length: 105, baseY: 50 };
  const scrollable2 = {
    getScrollPosition: () => ({ scrollTop: pixelState2.scrollTop }),
    getScrollDimensions: () => ({ height: 100, scrollHeight: 2100 }),
    setScrollPosition: ({ scrollTop }) => {
      pixelState2.scrollTop = scrollTop;
      buffer2.viewportY = Math.round(scrollTop / 20);
      pixelMoves2.push(scrollTop);
    },
  };
  const scrollHooks2 = [];
  const container2 = {
    ownerDocument: {
      getSelection: () => null,
      defaultView: {
        requestAnimationFrame: (callback) => {
          animationFrames2.push(callback);
          return animationFrames2.length;
        },
        cancelAnimationFrame: () => {},
      },
    },
    querySelector: (selector) => selector === ".xterm-viewport" ? viewport2
      : selector === ".xterm-screen" ? screen2 : xtermRoot2,
    addEventListener: (name, handler) => { listeners2[name] = handler; },
    removeEventListener: (name) => removedListeners2.push(name),
  };
  const terminal2 = {
    rows: 5,
    buffer: { active: buffer2 },
    _core: { _viewport: { _scrollableElement: scrollable2 } },
    scrollLines: () => {},
    onScroll: (cb) => { scrollHooks2.push(cb); return { dispose: () => {} }; },
    onResize: () => ({ dispose: () => {} }),
  };
  const dispose2 = install(container2, terminal2);
  const noopPrevent = () => {};

  fakeNow = 0;
  pixelState2.scrollTop = 1000;
  buffer2.viewportY = 50;
  listeners2.touchstart({ touches: [{ identifier: 7, clientY: 400 }] });
  listeners2.touchmove({
    touches: [{ identifier: 7, clientY: 370 }],
    preventDefault: noopPrevent,
  });
  listeners2.touchmove({
    touches: [{ identifier: 7, clientY: 340 }],
    preventDefault: noopPrevent,
  });
  check("fast drag keeps scrolling", pixelState2.scrollTop, 1060);
  fakeNow = 150; // finger held still: no more move events arrive
  listeners2.touchend({ touches: [], changedTouches: [{ identifier: 7 }] });
  check("hold-release schedules no fling", animationFrames2.length, 0);
  check("hold-release leaves the position alone", pixelState2.scrollTop, 1060);

  // 16. A flick that is still moving at release keeps momentum, averaged over
  //     the trailing window instead of trusting one noisy final frame.
  fakeNow = 1000;
  animationFrames2.length = 0;
  listeners2.touchstart({ touches: [{ identifier: 8, clientY: 400 }] });
  listeners2.touchmove({
    touches: [{ identifier: 8, clientY: 384 }],
    preventDefault: noopPrevent,
  }); // d=-16
  fakeNow = 1016;
  listeners2.touchmove({
    touches: [{ identifier: 8, clientY: 368 }],
    preventDefault: noopPrevent,
  }); // d=-16
  fakeNow = 1032;
  listeners2.touchmove({
    touches: [{ identifier: 8, clientY: 366 }],
    preventDefault: noopPrevent,
  }); // d=-2, decelerating
  fakeNow = 1048;
  listeners2.touchend({ touches: [], changedTouches: [{ identifier: 8 }] });
  check("moving release schedules a fling", animationFrames2.length, 1);
  // windowed velocity = -34px/32ms ~= -1.06px/ms; one frame of 16ms moves the
  // viewport ~17px, far below the 48px a -3px/ms saturated fling would do.
  pixelMoves2.length = 0;
  const before16 = pixelState2.scrollTop;
  fakeNow += 16;
  animationFrames2.shift()();
  const step16 = pixelState2.scrollTop - before16;
  check("windowed fling speed is the averaged motion", step16 > 5 && step16 < 30, true);

  // 17. The fling eases to rest instead of cutting off mid-motion. With the
  //     old 0.005px/ms2 friction the 450ms cap ended a max-speed fling while it
  //     was still moving ~0.75px/ms (~12px/frame); now the last applied frame
  //     must be smaller than one frame at MIN_FLING_VELOCITY.
  fakeNow = 2000;
  animationFrames2.length = 0;
  pixelState2.scrollTop = 200;
  buffer2.viewportY = 10;
  listeners2.touchstart({ touches: [{ identifier: 9, clientY: 400 }] });
  listeners2.touchmove({
    touches: [{ identifier: 9, clientY: 340 }],
    preventDefault: noopPrevent,
  });
  fakeNow = 2016;
  listeners2.touchmove({
    touches: [{ identifier: 9, clientY: 280 }],
    preventDefault: noopPrevent,
  });
  fakeNow = 2032;
  listeners2.touchend({ touches: [], changedTouches: [{ identifier: 9 }] });
  check("hard flick schedules a fling", animationFrames2.length, 1);
  let lastAppliedStep = 0;
  let frames = 0;
  while (animationFrames2.length > 0 && frames < 40) {
    const frame = animationFrames2.shift();
    const before = pixelState2.scrollTop;
    fakeNow += 16;
    frame();
    const step = Math.abs(pixelState2.scrollTop - before);
    if (step > 0) {
      lastAppliedStep = step;
    }
    frames++;
  }
  check("fling eases to rest instead of stopping mid-motion",
    lastAppliedStep < 4, true);
  check("fling ended on velocity, not the duration cap", frames < 29, true);

  // 18. The DOM renderer repaints whole rows at viewportY, so the adapter
  //     mirrors the fractional remainder onto .xterm-screen. As scrollTop
  //     crosses a row boundary (cellHeight 20px here) the translate offset
  //     wraps sign instead of letting the text jump a whole row.
  fakeNow = 3000;
  animationFrames2.length = 0;
  pixelState2.scrollTop = 1000;
  buffer2.viewportY = 50;
  scrollHooks2.forEach((cb) => cb()); // external re-alignment fires onScroll
  listeners2.touchstart({ touches: [{ identifier: 10, clientY: 400 }] });
  listeners2.touchmove({
    touches: [{ identifier: 10, clientY: 395 }],
    preventDefault: noopPrevent,
  }); // below threshold: no scroll, no offset
  check("sub-threshold move applies no row offset", screen2.style.transform || "", "");
  listeners2.touchmove({
    touches: [{ identifier: 10, clientY: 390 }],
    preventDefault: noopPrevent,
  }); // scrollTop 1005, viewportY stays 50 -> offset -5
  check("fractional scroll offsets the rendered rows", screen2.style.transform,
    "translateY(-5px)");
  listeners2.touchmove({
    touches: [{ identifier: 10, clientY: 384 }],
    preventDefault: noopPrevent,
  }); // scrollTop 1011 -> viewportY rounds to 51 -> offset 1020-1011 = +9
  check("offset wraps across a row crossing", screen2.style.transform,
    "translateY(9px)");

  // External scrolls (wheel, buffer output) resync the same offset through
  // the term.onScroll hook; returning to a row boundary clears it.
  pixelState2.scrollTop = 1500;
  buffer2.viewportY = 75;
  scrollHooks2.forEach((cb) => cb());
  check("row-aligned external scroll clears the offset",
    screen2.style.transform || "", "");
  pixelState2.scrollTop = 1506;
  buffer2.viewportY = 75;
  scrollHooks2.forEach((cb) => cb());
  check("fractional external scroll re-applies the offset",
    screen2.style.transform, "translateY(-6px)");

  dispose2();
  check("second dispose removes touch listeners", removedListeners2, [
    "touchstart", "touchmove", "touchend", "touchcancel",
  ]);
  check("dispose clears the row offset", screen2.style.transform, "");
} finally {
  Date.now = realNow;
}
delete global.document;

if (failures > 0) {
  console.error("\n" + failures + " touch-scroll policy test(s) failed");
  process.exit(1);
}
console.log("\nall touch-scroll policy tests passed");
