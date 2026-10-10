/*
 * Hidden-textarea hygiene adapter for the packaged xterm.js terminal (NusaDesk).
 *
 * xterm keeps a hidden `.xterm-helper-textarea` as its IME/input scratch field.
 * It only empties that field on blur and on a real Enter / Ctrl+C keydown
 * (`CoreBrowserTerminal._keyDown`), so on Android WebView — where every
 * printable key is delivered as an IME `input` commit rather than a real
 * keydown — the field accumulates everything typed since the last Enter.
 * xterm's Android input path then re-reads that field:
 *
 *   - `CompositionHelper._handleAnyTextareaChanges()` diffs the field against a
 *     snapshot taken at the `keyCode 229` keydown and emits
 *     `newValue.replace(oldValue, '')`. The moment an IME edit is not a clean
 *     append (autocorrect, an in-place replacement, key rollover), `replace`
 *     matches nothing and the *entire accumulated field* is emitted as fresh
 *     input (xtermjs/xterm.js#6078, #6012, #6045);
 *   - `_finalizeComposition` slices `textarea.value` by absolute offsets, so a
 *     field that no longer matches the offsets it captured emits an old slice.
 *
 * The result reaches the shell as if it had been typed: earlier text reappears
 * in the prompt, sometimes ahead of the character just typed. The fix belongs
 * upstream (make the diff positional, or clear the field per keystroke); until
 * a release carries it, this adapter restores the invariant xterm's own code
 * assumes — the field holds only the in-flight input.
 *
 * Rules, in order of importance:
 *
 *   - Never touch the value while a composition is open. xterm reads the field
 *     after `compositionend` and its composition math uses the field length
 *     captured at `compositionstart`, so clearing there loses or mangles a
 *     commit. A `compositionstart` cancels a pending sweep outright.
 *   - Never sweep inside xterm's 0 ms settle windows (the `keydown` diff timer
 *     and the deferred composition finalize). A value that shrinks in that
 *     window is reported as `DEL`, i.e. a stray backspace in the shell
 *     (xtermjs/xterm.js#6045), so the sweep only runs after IDLE_DELAY_MS of
 *     quiet, which is longer than every settle timer xterm schedules.
 *   - Never sweep when `screenReaderMode` is on: a screen reader reads the
 *     accumulated value aloud, which is why the upstream per-keystroke clear
 *     (PR #4265) was rejected. This page never enables that mode, but the gate
 *     keeps the adapter honest if it ever does.
 *   - Only clear a non-empty value, and only the packaged page's own field.
 *
 * The decision logic lives in pure functions (`shouldSweep`, `createSweeper`)
 * so it is unit-tested in Node without a DOM; `install(container, term)` wires
 * the DOM listeners and timers and is only invoked by the trusted terminal page
 * after `term.open`.
 *
 * Security: packaged, trusted, local asset code on the owned WebView origin. It
 * adds no JavaScript interface, no remote code, and no bridge surface; it only
 * reads and clears xterm's own helper field.
 */
(function () {
  "use strict";

  /**
   * Quiet period before a sweep. Must stay well above xterm's `setTimeout(0)`
   * settle windows (a keydown diff timer and the deferred composition
   * finalize), so a sweep can never land inside one of them.
   */
  var IDLE_DELAY_MS = 400;

  /**
   * Pure gate for one sweep. Called at sweep time, so it re-checks state that
   * may have changed between scheduling and the timer firing.
   *
   * @param {object} state
   * @param {boolean} state.composing         a composition owns the field right now
   * @param {boolean} state.screenReaderMode  xterm announces the field's value
   * @param {number}  state.valueLength       current field length
   * @returns {boolean} whether the field may be cleared
   */
  function shouldSweep(state) {
    if (!state || state.composing) {
      return false;
    }
    if (state.screenReaderMode) {
      return false;
    }
    return state.valueLength > 0;
  }

  /**
   * Pure scheduling state behind the sweep. Tracks whether a composition is
   * open and when the next sweep may run; the DOM adapter owns the timer.
   *
   * @param {number} [idleMs] quiet period before a sweep (default {@link IDLE_DELAY_MS})
   */
  function createSweeper(idleMs) {
    var idle = typeof idleMs === "number" && idleMs >= 0 ? idleMs : IDLE_DELAY_MS;
    var composing = false;
    var dueAt = null;

    return {
      /**
       * Note one input event. Returns the schedule the adapter must apply:
       * either arm a sweep (after `delay` ms) or cancel the pending one.
       * Unknown event types only delay a sweep; they can never cause an early
       * clear.
       *
       * @param {string} type DOM event type
       * @param {number} now  current time in ms
       * @returns {{arm: boolean, delay: number}}
       */
      note: function (type, now) {
        if (type === "compositionstart") {
          composing = true;
          dueAt = null;
          return { arm: false, delay: 0 };
        }
        if (type === "compositionend") {
          composing = false;
        }
        dueAt = now + idle;
        return { arm: true, delay: idle };
      },

      /** True while a composition owns the field. */
      isComposing: function () {
        return composing;
      },

      /**
       * True when a sweep is allowed at `now`: no open composition, a pending
       * schedule, and the quiet period has actually elapsed.
       */
      due: function (now) {
        return !composing && dueAt !== null && now >= dueAt;
      },

      /**
       * Milliseconds left before a sweep may run (0 when it is already due and
       * idle), for the adapter's timer.
       */
      remaining: function (now) {
        if (dueAt === null) {
          return 0;
        }
        return Math.max(0, dueAt - now);
      },

      /** Drop any pending schedule (dispose, or an explicit cancel). */
      cancel: function () {
        dueAt = null;
      }
    };
  }

  /**
   * Wire the sweep into a live terminal page.
   *
   * @param {Element} container the element passed to `term.open()`
   * @param {object}  term      the xterm Terminal instance
   * @param {object} [options]  `{ idleMs }` override for the quiet period
   * @returns {object|null} handle with `sweepNow` and `dispose`, or null
   */
  function install(container, term, options) {
    if (!container || typeof container.addEventListener !== "function" ||
        typeof setTimeout !== "function") {
      return null;
    }
    var idleMs = options && typeof options.idleMs === "number"
        ? options.idleMs
        : IDLE_DELAY_MS;
    var sweeper = createSweeper(idleMs);
    var timer = null;
    var helper = null;
    var disposed = false;

    function helperTextarea() {
      if (!helper || !helper.isConnected) {
        helper = container.querySelector
            ? container.querySelector(".xterm-helper-textarea")
            : null;
      }
      return helper;
    }

    function screenReaderMode() {
      var opts = term && term.options;
      return !!(opts && opts.screenReaderMode);
    }

    function now() {
      return Date.now();
    }

    function cancelTimer() {
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
    }

    function armTimer(delay) {
      cancelTimer();
      timer = setTimeout(sweep, delay);
    }

    function sweep() {
      timer = null;
      if (disposed) {
        return;
      }
      var field = helperTextarea();
      if (!field) {
        return;
      }
      if (!shouldSweep({
        composing: sweeper.isComposing(),
        screenReaderMode: screenReaderMode(),
        valueLength: field.value.length
      })) {
        return;
      }
      field.value = "";
    }

    function onEvent(event) {
      var plan = sweeper.note(event && event.type, now());
      if (plan.arm) {
        armTimer(plan.delay);
      } else {
        cancelTimer();
      }
    }

    var types = ["keydown", "keyup", "input", "compositionstart", "compositionend"];
    for (var i = 0; i < types.length; i++) {
      container.addEventListener(types[i], onEvent, true);
    }

    return {
      /** Run one sweep now, through the same gate the timer uses. */
      sweepNow: sweep,
      /** Detach every listener and drop the pending timer. */
      dispose: function () {
        disposed = true;
        cancelTimer();
        sweeper.cancel();
        for (var i = 0; i < types.length; i++) {
          container.removeEventListener(types[i], onEvent, true);
        }
      }
    };
  }

  var api = {
    IDLE_DELAY_MS: IDLE_DELAY_MS,
    shouldSweep: shouldSweep,
    createSweeper: createSweeper,
    install: install
  };
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
  if (typeof window !== "undefined") {
    window.LinuxWrapperImeGuard = api;
  }
})();
