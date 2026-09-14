/*
 * Touch-to-scrollback adapter for the packaged xterm.js terminal (NusaDesk).
 *
 * xterm.js 6.0.0 has a mobile touch regression: a one-finger drag over the
 * terminal does not scroll the scrollback viewport on Android WebView. This
 * adapter translates a single-finger vertical drag over the xterm viewport
 * into bounded viewport scrollTop changes. It is deliberately narrow:
 *
 *   - It only acts when xterm has NOT enabled mouse tracking. xterm toggles
 *     the `enable-mouse-events` class on the root `.xterm` element when a
 *     guest program (vim/tmux/less) has requested mouse tracking. In that
 *     mode touch must pass through to xterm, which translates it to mouse
 *     events for the program; the adapter never steals that gesture.
 *   - It only acts on a single touch. A second finger cancels the active
 *     scroll and defers to xterm, so multi-touch / pinch and mouse-tracking
 *     touch are preserved.
 *   - It applies a movement threshold before a drag becomes a scroll, so a
 *     tap (down + up with little movement) is never stolen: xterm still gets
 *     the full tap sequence for focus / cursor positioning.
 *   - It never preventDefault on touchstart, so focus, keyboard, and the
 *     accessory key row (a native Android view, not in the WebView) are
 *     unaffected. It only preventDefault on touchmove once a scroll is
 *     actually in progress, to stop page bounce/zoom without blocking taps.
 *   - scrollTop is clamped to [0, scrollHeight - clientHeight].
 *
 * The pure decision logic lives in `policy(opts)` so it can be unit-tested in
 * Node without a DOM. `install(container, term)` wires the DOM listeners and
 * is only invoked explicitly by the trusted terminal page after `term.open`.
 *
 * Security: this is packaged, trusted, local asset code running on the owned
 * WebView origin. It adds no JavaScript interface, no remote code, and no
 * bridge surface; it only reads xterm's own DOM classes and sets scrollTop.
 */
(function () {
  "use strict";

  /**
   * Pure, side-effect-free scroll decision for one touchmove frame.
   *
   * @param {object} opts
   * @param {boolean} opts.isMouseTracking  true when xterm is in mouse-tracking mode
   * @param {number}  opts.touchCount        number of current touches
   * @param {boolean} opts.active            whether a scroll is already in progress
   * @param {number}  opts.absDeltaFromStart |currentY - startY|
   * @param {number}  opts.deltaY            currentY - lastY (this frame's movement)
   * @param {number}  opts.scrollTop         viewport.scrollTop at this frame
   * @param {number}  opts.maxScroll         scrollHeight - clientHeight (>= 0)
   * @param {number}  opts.threshold         px a drag must travel before scrolling starts
   * @returns {{scroll:boolean, scrollTop:number, preventDefault:boolean, cancel:boolean}}
   */
  function policy(opts) {
    if (opts.isMouseTracking) {
      return { scroll: false, scrollTop: opts.scrollTop, preventDefault: false, cancel: false };
    }
    if (opts.touchCount !== 1) {
      return { scroll: false, scrollTop: opts.scrollTop, preventDefault: false, cancel: true };
    }
    var max = opts.maxScroll < 0 ? 0 : opts.maxScroll;
    if (max === 0) {
      // Nothing to scroll: defer entirely to native/xterm behavior.
      return { scroll: false, scrollTop: opts.scrollTop, preventDefault: false, cancel: false };
    }
    if (!opts.active && opts.absDeltaFromStart < opts.threshold) {
      // Below the gesture threshold: treat as a potential tap, do not steal.
      return { scroll: false, scrollTop: opts.scrollTop, preventDefault: false, cancel: false };
    }
    var next = opts.scrollTop - opts.deltaY;
    if (next < 0) {
      next = 0;
    }
    if (next > max) {
      next = max;
    }
    return { scroll: true, scrollTop: next, preventDefault: true, cancel: false };
  }

  /** Pixel distance a drag must travel before it becomes a scroll. */
  var SCROLL_THRESHOLD_PX = 10;
  var nativeMode = false;
  var boundTerminal = null;
  var pendingPixels = 0;

  /** Enable native WebView gesture delivery, avoiding duplicate DOM scrolling. */
  function enableNativeMode() {
    nativeMode = true;
  }

  /**
   * Scroll by a native touch delta. Native Android sends the inverted finger
   * movement (finger-up is positive scroll) so the same clamp policy is used.
   * @param {number} amount positive moves toward older scrollback
   * @returns {boolean} whether the viewport moved
   */
  function scrollBy(amount) {
    if (isMouseTrackingFromDom()) {
      return false;
    }
    var vp = document.querySelector(".xterm-viewport");
    if (!vp || !Number.isFinite(amount) || amount === 0) {
      return false;
    }
    if (boundTerminal && typeof boundTerminal.scrollLines === "function"
        && boundTerminal.rows > 0) {
      var rowHeight = vp.clientHeight / boundTerminal.rows;
      if (!Number.isFinite(rowHeight) || rowHeight <= 0) {
        return false;
      }
      pendingPixels += amount;
      var lines = pendingPixels > 0
        ? Math.floor(pendingPixels / rowHeight)
        : Math.ceil(pendingPixels / rowHeight);
      if (lines === 0) {
        return false;
      }
      pendingPixels -= lines * rowHeight;
      boundTerminal.scrollLines(lines);
      return true;
    }
    // Defensive fallback for an incomplete xterm initialisation.
    var before = vp.scrollTop;
    var max = Math.max(0, vp.scrollHeight - vp.clientHeight);
    vp.scrollTop = Math.max(0, Math.min(max, before + amount));
    return vp.scrollTop !== before;
  }

  function isMouseTrackingFromDom() {
    var root = document.querySelector(".xterm");
    return !!root && root.classList.contains("enable-mouse-events");
  }

  function scrollToBottom() {
    var vp = document.querySelector(".xterm-viewport");
    if (!vp) return;
    vp.scrollTop = vp.scrollHeight;
    if (boundTerminal && typeof boundTerminal.scrollToBottom === "function") {
      boundTerminal.scrollToBottom();
    }
    pendingPixels = 0;
  }


/**
   * Wire touch listeners on the xterm container. Call after `term.open(container)`.
   *
   * @param {HTMLElement} container the element passed to term.open (the #terminal div)
   * @param {object} term the xterm Terminal instance used for its public scrollLines API
   */
  function install(container, term) {
    if (!container) {
      return;
    }
    boundTerminal = term || null;
    pendingPixels = 0;
    var state = { active: false, startY: 0, lastY: 0 };

    function viewport() {
      // The scrollable scrollback element xterm creates inside the container.
      return container.querySelector(".xterm-viewport");
    }

    function isMouseTracking() {
      var root = container.querySelector(".xterm");
      return !!root && root.classList.contains("enable-mouse-events");
    }

    /* Listen on the page container in capture phase. The xterm viewport and
       xterm screen are sibling elements, so attaching only to `.xterm-viewport`
       misses drags that begin on the canvas. Capture at #terminal sees both. */
    var target = container;

    target.addEventListener("touchstart", function (e) {
      if (nativeMode) {
        return;
      }
      // Never preventDefault here: taps must reach xterm for focus/cursor.
      if (isMouseTracking() || e.touches.length !== 1) {
        state.active = false;
        return;
      }
      state.active = false; // a scroll starts only after the threshold is crossed
      state.startY = state.lastY = e.touches[0].clientY;
    }, { passive: true, capture: true });

    target.addEventListener("touchmove", function (e) {
      if (nativeMode) {
        return;
      }
      var vp = viewport();
      if (!vp) {
        return;
      }
      var y = e.touches.length > 0 ? e.touches[0].clientY : state.lastY;
      var decision = policy({
        isMouseTracking: isMouseTracking(),
        touchCount: e.touches.length,
        active: state.active,
        absDeltaFromStart: Math.abs(y - state.startY),
        deltaY: y - state.lastY,
        scrollTop: vp.scrollTop,
        maxScroll: vp.scrollHeight - vp.clientHeight,
        threshold: SCROLL_THRESHOLD_PX
      });
      if (decision.cancel) {
        state.active = false;
        state.lastY = y;
        return;
      }
      if (decision.scroll) {
        state.active = true;
        scrollBy(decision.scrollTop - vp.scrollTop);
        if (decision.preventDefault) {
          e.preventDefault();
        }
      }
      state.lastY = y;
    }, { passive: false, capture: true });

    function reset() {
      state.active = false;
    }
    target.addEventListener("touchend", reset, { passive: true, capture: true });
    target.addEventListener("touchcancel", reset, { passive: true, capture: true });
  }

  var api = {
    policy: policy,
    install: install,
    enableNativeMode: enableNativeMode,
    scrollBy: scrollBy,
    scrollToBottom: scrollToBottom,
    SCROLL_THRESHOLD_PX: SCROLL_THRESHOLD_PX
  };
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
  if (typeof window !== "undefined") {
    window.LinuxWrapperTouchScroll = api;
  }
})();
