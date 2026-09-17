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
 *   - It never acts while the platform's own text selection is active on the
 *     terminal rows: a drag then extends that selection (long-press + drag is
 *     Android's stock select gesture) and must not also move the viewport.
 *   - It only acts on a single touch. A second finger cancels the active
 *     scroll and defers to xterm, so multi-touch / pinch and mouse-tracking
 *     touch are preserved.
 *   - It applies a movement threshold before a drag becomes a scroll, so a
 *     tap (down + up with little movement) is never stolen: xterm still gets
 *     the full tap sequence for focus / cursor positioning.
 *   - It never preventDefault on touchstart, so focus, keyboard, the stock
 *     long-press selection, and the accessory key row (a native Android view,
 *     not in the WebView) are unaffected. It only preventDefault on touchmove
 *     once a scroll is actually in progress, to stop page bounce/zoom without
 *     blocking taps.
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
   * @param {boolean} opts.hasSelection     true while a DOM selection exists on the terminal rows
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
    if (opts.isMouseTracking || opts.hasSelection) {
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
  var boundTerminal = null;
  var pendingPixels = 0;

  /**
   * Scroll by a pixel delta via the public scrollLines API: a finger-up drag
   * yields a positive amount and moves the viewport toward newer lines.
   * @param {number} amount positive moves toward newer output
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
    return false;
  }

  function isMouseTrackingFromDom() {
    var root = document.querySelector(".xterm");
    return !!root && root.classList.contains("enable-mouse-events");
  }

  function scrollToBottom() {
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
    var state = {
      active: false,
      startY: 0,
      lastY: 0,
      queuedPixels: 0,
      animationFrame: 0
    };

    function viewport() {
      // The scrollable scrollback element xterm creates inside the container.
      return container.querySelector(".xterm-viewport");
    }

    /* Scroll position and range in pixels, derived from xterm's buffer.
       .xterm-viewport itself cannot be used: xterm 6 wraps the screen in a
       virtual .xterm-scrollable-element and the viewport element has no
       overflowing content, so its scrollTop range is always zero. The real
       scroll state is buffer.active.viewportY over [0, length - rows]
       lines, which term.scrollLines() moves. */
    function scrollMetrics() {
      var rows = boundTerminal && boundTerminal.rows > 0 ? boundTerminal.rows : 0;
      var buf = boundTerminal && boundTerminal.buffer ? boundTerminal.buffer.active : null;
      var vp = viewport();
      var cellH = vp && rows > 0 ? vp.clientHeight / rows : 0;
      if (!buf || cellH <= 0) {
        return { scrollTop: 0, maxScroll: 0 };
      }
      return {
        scrollTop: buf.viewportY * cellH,
        maxScroll: Math.max(0, (buf.length - rows) * cellH)
      };
    }

    function isMouseTracking() {
      var root = container.querySelector(".xterm");
      return !!root && root.classList.contains("enable-mouse-events");
    }

    /* The platform's own selection wins the gesture: while Android's stock
       long-press selection holds terminal text, a drag extends the selection
       and must not scroll. Only a selection anchored on xterm's rendered DOM
       rows counts because its hidden helper textarea may hold a selection of
       its own. */
    function hasDomSelection() {
      var doc = container.ownerDocument;
      var sel = doc && doc.getSelection ? doc.getSelection() : null;
      if (!sel || sel.isCollapsed) {
        return false;
      }
      var holds = function (el) {
        return !!el && (el.contains(sel.anchorNode) || el.contains(sel.focusNode));
      };
      return holds(container.querySelector(".xterm-rows"));
    }

    /* xterm refreshes its rendered rows whenever scrollLines() runs. Android
       can dispatch several touchmove events inside one compositor frame, so
       issuing a refresh for each one makes a slow device visibly stutter.
       Fold their deltas together and make at most one xterm call per frame. */
    function flushQueuedScroll() {
      state.animationFrame = 0;
      var amount = state.queuedPixels;
      state.queuedPixels = 0;
      if (amount === 0 || isMouseTracking() || hasDomSelection()) {
        return;
      }
      scrollBy(amount);
    }

    function queueScroll(amount) {
      if (!Number.isFinite(amount) || amount === 0) {
        return;
      }
      state.queuedPixels += amount;
      if (state.animationFrame !== 0) {
        return;
      }
      var view = container.ownerDocument && container.ownerDocument.defaultView;
      if (view && typeof view.requestAnimationFrame === "function") {
        state.animationFrame = view.requestAnimationFrame(flushQueuedScroll);
      } else {
        flushQueuedScroll();
      }
    }

    function discardQueuedScroll() {
      state.queuedPixels = 0;
    }

    /* Listen on the page container in capture phase. The xterm viewport and
       rendered DOM rows are siblings, so attaching only to `.xterm-viewport`
       misses drags that begin on terminal text. Capture at #terminal sees
       both. */
    var target = container;

    target.addEventListener("touchstart", function (e) {
      // Never preventDefault here: taps must reach xterm for focus/cursor and
      // the platform's long-press selection must see an untouched gesture.
      if (isMouseTracking() || e.touches.length !== 1) {
        state.active = false;
        discardQueuedScroll();
        return;
      }
      state.active = false; // a scroll starts only after the threshold is crossed
      state.startY = state.lastY = e.touches[0].clientY;
    }, { passive: true, capture: true });

    target.addEventListener("touchmove", function (e) {
      var vp = viewport();
      if (!vp) {
        return;
      }
      var y = e.touches.length > 0 ? e.touches[0].clientY : state.lastY;
      var metrics = scrollMetrics();
      var decision = policy({
        isMouseTracking: isMouseTracking(),
        hasSelection: hasDomSelection(),
        touchCount: e.touches.length,
        active: state.active,
        absDeltaFromStart: Math.abs(y - state.startY),
        deltaY: y - state.lastY,
        scrollTop: metrics.scrollTop,
        maxScroll: metrics.maxScroll,
        threshold: SCROLL_THRESHOLD_PX
      });
      if (decision.cancel) {
        state.active = false;
        discardQueuedScroll();
        state.lastY = y;
        return;
      }
      if (decision.scroll) {
        state.active = true;
        queueScroll(decision.scrollTop - metrics.scrollTop);
        if (decision.preventDefault) {
          e.preventDefault();
        }
      } else if (isMouseTracking() || hasDomSelection()) {
        state.active = false;
        discardQueuedScroll();
      }
      state.lastY = y;
    }, { passive: false, capture: true });

    function finish() {
      state.active = false;
    }
    function cancel() {
      state.active = false;
      discardQueuedScroll();
    }
    target.addEventListener("touchend", finish, { passive: true, capture: true });
    target.addEventListener("touchcancel", cancel, { passive: true, capture: true });
  }

  var api = {
    policy: policy,
    install: install,
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
