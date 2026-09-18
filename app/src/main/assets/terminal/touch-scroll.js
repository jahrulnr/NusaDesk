/*
 * Touch-to-scrollback adapter for the packaged xterm.js terminal (NusaDesk).
 *
 * xterm.js 6.0.0 has a mobile touch regression: a one-finger drag over the
 * terminal does not scroll the scrollback viewport on Android WebView. This
 * adapter follows the upstream 6.1 touch fix at the page boundary: it writes
 * the bundled xterm viewport's fractional pixel position directly during the
 * drag and applies bounded friction-based momentum after release.
 *
 * It is deliberately narrow:
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
 *   - Pixel positions and fling steps are clamped to xterm's scroll range.
 *
 * The pure decision logic lives in `policy(opts)` so it can be unit-tested in
 * Node without a DOM. `install(container, term)` wires the DOM listeners and
 * is only invoked explicitly by the trusted terminal page after `term.open`.
 *
 * Security: this is packaged, trusted, local asset code running on the owned
 * WebView origin. It adds no JavaScript interface, no remote code, and no
 * bridge surface; it only reads xterm's own DOM classes and pixel controller.
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
  var FLING_FRICTION_PX_PER_MS2 = 0.005;
  var MIN_FLING_VELOCITY_PX_PER_MS = 0.02;
  var MAX_FLING_VELOCITY_PX_PER_MS = 3;
  var MAX_FLING_DURATION_MS = 450;
  var boundTerminal = null;
  var boundCancelFling = null;
  var pendingPixels = 0;

  /**
   * Return xterm 6's pixel-position controller. The public Terminal API only
   * exposes line-based scrolling, while the bundled 6.0 viewport keeps the
   * actual fractional position in this controller. The bundle is pinned, so
   * this small adapter is the page-side equivalent of xterm's upstream touch
   * fix until the asset can move to a release that contains it.
   *
   * @returns {object|null} xterm's pixel scroll controller
   */
  function xtermScrollable() {
    var core = boundTerminal && boundTerminal._core;
    var viewport = core && core._viewport;
    var scrollable = viewport && viewport._scrollableElement;
    if (!scrollable || typeof scrollable.getScrollPosition !== "function"
        || typeof scrollable.setScrollPosition !== "function"
        || typeof scrollable.getScrollDimensions !== "function") {
      return null;
    }
    return scrollable;
  }

  /**
   * Scroll by a pixel delta. Direct positioning follows the finger exactly;
   * it deliberately does not use xterm's smooth animation because that
   * animation is designed for wheel steps and adds lag when retargeted for
   * every touchmove.
   *
   * @param {number} amount positive moves toward newer output
   * @returns {boolean} whether the viewport moved
   */
  function scrollBy(amount) {
    if (isMouseTrackingFromDom()) {
      return false;
    }
    if (!Number.isFinite(amount) || amount === 0) {
      return false;
    }

    var scrollable = xtermScrollable();
    if (scrollable) {
      try {
        var position = scrollable.getScrollPosition();
        var dimensions = scrollable.getScrollDimensions();
        var current = Number(position && position.scrollTop);
        var max = Number(dimensions && dimensions.scrollHeight)
            - Number(dimensions && dimensions.height);
        if (Number.isFinite(current) && Number.isFinite(max)) {
          max = Math.max(0, max);
          var next = Math.max(0, Math.min(max, current + amount));
          if (next === current) {
            return false;
          }
          scrollable.setScrollPosition({ scrollTop: next });
          var updated = scrollable.getScrollPosition();
          if (Number(updated && updated.scrollTop) === next) {
            return true;
          }
        }
      } catch (ignored) {
        // Fall through to the bounded line-based compatibility path below.
      }
    }

    // Defensive fallback for an unexpected xterm asset shape. It retains
    // sub-row deltas until a whole line is available, but the pinned bundle
    // always takes the direct pixel path above.
    var vp = document.querySelector(".xterm-viewport");
    if (!vp || !boundTerminal || typeof boundTerminal.scrollLines !== "function"
        || boundTerminal.rows <= 0) {
      return false;
    }
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

  function isMouseTrackingFromDom() {
    var root = document.querySelector(".xterm");
    return !!root && root.classList.contains("enable-mouse-events");
  }

  function scrollToBottom() {
    if (boundCancelFling) {
      boundCancelFling();
    }
    var scrollable = xtermScrollable();
    var movedToBottom = false;
    if (scrollable) {
      try {
        var dimensions = scrollable.getScrollDimensions();
        var max = Number(dimensions && dimensions.scrollHeight)
            - Number(dimensions && dimensions.height);
        if (Number.isFinite(max)) {
          scrollable.setScrollPosition({ scrollTop: Math.max(0, max) });
          movedToBottom = true;
        }
      } catch (ignored) {
        // Fall through to xterm's public line-based command.
      }
    }
    if (!movedToBottom && boundTerminal
        && typeof boundTerminal.scrollToBottom === "function") {
      boundTerminal.scrollToBottom();
    }
    pendingPixels = 0;
  }


/**
   * Wire touch listeners on the xterm container. Call after `term.open(container)`.
   *
   * @param {HTMLElement} container the element passed to term.open (the #terminal div)
   * @param {object} term the xterm Terminal instance whose viewport is scrolled
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
      lastTime: 0,
      velocityY: 0,
      flingFrame: 0,
      flingTime: 0,
      flingElapsed: 0,
      canceled: false,
      primaryTouchId: null
    };

    function viewport() {
      // The scrollable scrollback element xterm creates inside the container.
      return container.querySelector(".xterm-viewport");
    }

    /* Use xterm's current fractional pixel position while available. The
       buffer viewportY is rounded to a row and is therefore unsuitable while
       a touch is moving. */
    function scrollMetrics() {
      var scrollable = xtermScrollable();
      if (scrollable) {
        var position = scrollable.getScrollPosition();
        var dimensions = scrollable.getScrollDimensions();
        var scrollTop = Number(position && position.scrollTop);
        var maxScroll = Number(dimensions && dimensions.scrollHeight)
            - Number(dimensions && dimensions.height);
        if (Number.isFinite(scrollTop) && Number.isFinite(maxScroll)) {
          return {
            scrollTop: scrollTop,
            maxScroll: Math.max(0, maxScroll)
          };
        }
      }

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

    function cancelFling() {
      if (state.flingFrame !== 0) {
        var view = container.ownerDocument && container.ownerDocument.defaultView;
        if (view && typeof view.cancelAnimationFrame === "function") {
          view.cancelAnimationFrame(state.flingFrame);
        }
      }
      state.flingFrame = 0;
      state.flingTime = 0;
      state.flingElapsed = 0;
      state.velocityY = 0;
    }

    function scheduleFling() {
      if (Math.abs(state.velocityY) < MIN_FLING_VELOCITY_PX_PER_MS) {
        state.velocityY = 0;
        return;
      }
      state.velocityY = Math.max(-MAX_FLING_VELOCITY_PX_PER_MS,
          Math.min(MAX_FLING_VELOCITY_PX_PER_MS, state.velocityY));
      var view = container.ownerDocument && container.ownerDocument.defaultView;
      if (!view || typeof view.requestAnimationFrame !== "function") {
        state.velocityY = 0;
        return;
      }
      state.flingFrame = 0;
      state.flingTime = state.lastTime;
      state.flingElapsed = 0;
      state.flingFrame = view.requestAnimationFrame(runFling);
    }

    function runFling() {
      state.flingFrame = 0;
      if (isMouseTracking() || hasDomSelection()) {
        cancelFling();
        return;
      }
      var view = container.ownerDocument && container.ownerDocument.defaultView;
      if (!view || typeof view.requestAnimationFrame !== "function") {
        cancelFling();
        return;
      }
      var timestamp = Date.now();
      if (state.flingTime === 0) {
        state.flingTime = timestamp;
      }
      var elapsed = Math.min(32, Math.max(1, timestamp - state.flingTime));
      state.flingTime = timestamp;
      state.flingElapsed += elapsed;
      var velocity = state.velocityY;
      if (velocity === 0 || state.flingElapsed > MAX_FLING_DURATION_MS) {
        cancelFling();
        return;
      }
      if (!scrollBy(-velocity * elapsed)) {
        cancelFling();
        return;
      }
      var nextVelocity = velocity > 0
        ? Math.max(0, velocity - FLING_FRICTION_PX_PER_MS2 * elapsed)
        : Math.min(0, velocity + FLING_FRICTION_PX_PER_MS2 * elapsed);
      state.velocityY = nextVelocity;
      if (Math.abs(nextVelocity) >= MIN_FLING_VELOCITY_PX_PER_MS
          && state.flingElapsed < MAX_FLING_DURATION_MS) {
        state.flingFrame = view.requestAnimationFrame(runFling);
      } else {
        cancelFling();
      }
    }

    /* A touchmove already arrives on the UI/compositor path. Applying its
       pixel delta now keeps the terminal under the finger. Only the release
       momentum is scheduled with requestAnimationFrame. */
    function applyTouchDelta(amount) {
      if (!Number.isFinite(amount) || amount === 0) {
        return;
      }
      scrollBy(amount);
    }

    /* Listen on the page container in capture phase. The xterm viewport and
       rendered DOM rows are siblings, so attaching only to `.xterm-viewport`
       misses drags that begin on terminal text. Capture at #terminal sees
       both. */
    var target = container;

    function handleTouchStart(e) {
      // Never preventDefault here: taps must reach xterm for focus/cursor and
      // the platform's long-press selection must see an untouched gesture.
      cancelFling();
      if (isMouseTracking() || e.touches.length !== 1) {
        state.active = false;
        state.canceled = true;
        state.primaryTouchId = null;
        return;
      }
      // A new one-finger sequence after the previous sequence ended may
      // recover from a canceled multi-touch gesture.
      state.canceled = false;
      state.primaryTouchId = e.touches[0].identifier;
      state.active = false; // a scroll starts only after the threshold is crossed
      state.startY = state.lastY = e.touches[0].clientY;
      state.lastTime = Date.now();
      state.velocityY = 0;
    }

    function handleTouchMove(e) {
      var vp = viewport();
      if (!vp) {
        return;
      }
      var y = e.touches.length > 0 ? e.touches[0].clientY : state.lastY;
      if (state.canceled) {
        return;
      }
      var primaryTouch = e.touches.length > 0 ? e.touches[0] : null;
      if (state.primaryTouchId != null && primaryTouch
          && primaryTouch.identifier != null
          && primaryTouch.identifier !== state.primaryTouchId) {
        return;
      }
      var timestamp = Date.now();
      var elapsed = Math.max(1, timestamp - state.lastTime);
      var deltaY = y - state.lastY;
      var metrics = scrollMetrics();
      var decision = policy({
        isMouseTracking: isMouseTracking(),
        hasSelection: hasDomSelection(),
        touchCount: e.touches.length,
        active: state.active,
        absDeltaFromStart: Math.abs(y - state.startY),
        deltaY: deltaY,
        scrollTop: metrics.scrollTop,
        maxScroll: metrics.maxScroll,
        threshold: SCROLL_THRESHOLD_PX
      });
      if (decision.cancel) {
        state.active = false;
        state.canceled = true;
        state.primaryTouchId = null;
        state.velocityY = 0;
        state.lastY = y;
        state.lastTime = timestamp;
        return;
      }
      if (decision.scroll) {
        state.active = true;
        applyTouchDelta(decision.scrollTop - metrics.scrollTop);
        state.velocityY = deltaY / elapsed;
        if (decision.preventDefault) {
          e.preventDefault();
        }
      } else if (isMouseTracking() || hasDomSelection()) {
        state.active = false;
        state.velocityY = 0;
      }
      state.lastY = y;
      state.lastTime = timestamp;
    }

    function finish(e) {
      var hasRemainingTouches = e && e.touches && e.touches.length > 0;
      if (hasRemainingTouches) {
        state.active = false;
        state.canceled = true;
        state.primaryTouchId = null;
        state.velocityY = 0;
        cancelFling();
        return;
      }
      var timestamp = Date.now();
      state.lastTime = timestamp;
      var shouldFling = state.active && !state.canceled
          && !isMouseTracking() && !hasDomSelection();
      state.active = false;
      state.primaryTouchId = null;
      state.canceled = false;
      if (shouldFling) {
        scheduleFling();
      } else {
        state.velocityY = 0;
      }
    }
    function cancel() {
      state.active = false;
      state.canceled = true;
      state.primaryTouchId = null;
      cancelFling();
    }
    target.addEventListener("touchstart", handleTouchStart, { passive: true, capture: true });
    target.addEventListener("touchmove", handleTouchMove, { passive: false, capture: true });
    target.addEventListener("touchend", finish, { passive: true, capture: true });
    target.addEventListener("touchcancel", cancel, { passive: true, capture: true });
    boundCancelFling = cancelFling;
    return function dispose() {
      cancelFling();
      target.removeEventListener("touchstart", handleTouchStart, { capture: true });
      target.removeEventListener("touchmove", handleTouchMove, { capture: true });
      target.removeEventListener("touchend", finish, { capture: true });
      target.removeEventListener("touchcancel", cancel, { capture: true });
      if (boundCancelFling === cancelFling) {
        boundCancelFling = null;
      }
    };
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
