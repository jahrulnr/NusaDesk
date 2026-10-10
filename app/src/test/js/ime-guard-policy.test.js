/*
 * Pure-JS unit tests for the hidden-textarea hygiene adapter in ime-guard.js.
 *
 * No DOM, no browser: this runs under plain Node with a fake container, a fake
 * terminal, and a virtual clock, and it exercises both the pure policy
 * (`shouldSweep`, `createSweeper`) and `install()`.
 *
 * Run:  node app/src/test/js/ime-guard-policy.test.js
 *
 * The last section models the upstream defect the adapter exists for (the
 * deferred `newValue.replace(oldValue, '')` diff in xterm's CompositionHelper,
 * xtermjs/xterm.js#6078) so the before/after is asserted, not described.
 */
"use strict";

const assert = require("assert");
const path = require("path");
const { IDLE_DELAY_MS, shouldSweep, createSweeper, install } = require(path.join(
  __dirname, "..", "..", "main", "assets", "terminal", "ime-guard.js"
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

// ---- virtual clock: install() must own exactly one pending timer ----------
const realSetTimeout = global.setTimeout;
const realClearTimeout = global.clearTimeout;
const realNow = Date.now;
let now = 0;
let nextId = 0;
const timers = new Map();
global.setTimeout = function (fn, delay) {
  const id = ++nextId;
  timers.set(id, { fn: fn, at: now + (typeof delay === "number" ? delay : 0) });
  return id;
};
global.clearTimeout = function (id) {
  timers.delete(id);
};
Date.now = function () {
  return now;
};
function advance(ms) {
  const target = now + ms;
  for (;;) {
    let due = null;
    for (const [id, timer] of timers) {
      if (timer.at <= target && (due === null || timer.at < timers.get(due).at)) {
        due = id;
      }
    }
    if (due === null) {
      break;
    }
    const timer = timers.get(due);
    timers.delete(due);
    now = timer.at;
    timer.fn();
  }
  now = target;
}

function fakeField(value) {
  return { value: value === undefined ? "" : value, isConnected: true };
}
function fakeHost(field, screenReaderMode) {
  const listeners = {};
  const removed = [];
  return {
    field: field,
    listeners: listeners,
    removed: removed,
    container: {
      querySelector: (selector) =>
        selector === ".xterm-helper-textarea" ? field : null,
      addEventListener: (type, handler) => {
        listeners[type] = handler;
      },
      removeEventListener: (type) => {
        delete listeners[type];
        removed.push(type);
      },
    },
    term: { options: { screenReaderMode: !!screenReaderMode } },
    fire: (type) => listeners[type]({ type: type }),
  };
}

try {
  // ---- pure gate -----------------------------------------------------------
  check("a populated field outside a composition may be swept",
    shouldSweep({ composing: false, screenReaderMode: false, valueLength: 11 }), true);
  check("an open composition owns the field",
    shouldSweep({ composing: true, screenReaderMode: false, valueLength: 11 }), false);
  check("screenReaderMode keeps the value for the screen reader",
    shouldSweep({ composing: false, screenReaderMode: true, valueLength: 11 }), false);
  check("an empty field is left alone",
    shouldSweep({ composing: false, screenReaderMode: false, valueLength: 0 }), false);
  check("a missing state is refused",
    shouldSweep(null), false);

  // ---- pure schedule -------------------------------------------------------
  const sweeper = createSweeper(100);
  check("a keystroke arms the sweep one idle window later",
    sweeper.note("keydown", 1000), { arm: true, delay: 100 });
  check("the settle window itself is never a sweep point",
    sweeper.due(1099), false);
  check("the sweep is due once the idle window passed",
    sweeper.due(1100), true);
  check("pending delay counts down for the adapter",
    sweeper.remaining(1030), 70);
  check("compositionstart cancels the pending sweep",
    sweeper.note("compositionstart", 2000), { arm: false, delay: 0 });
  check("no sweep can be due while composing",
    sweeper.due(999999), false);
  check("compositionend re-arms after the deferred finalize window",
    sweeper.note("compositionend", 3000), { arm: true, delay: 100 });
  check("the finalize window is respected too",
    sweeper.due(3099), false);
  check("an unknown event only delays a sweep, never clears early",
    sweeper.note("paste-ish", 4000), { arm: true, delay: 100 });

  // ---- install(): the field stops being stale fuel -------------------------
  now = 10000;
  const host = fakeHost(fakeField("apt upgrade"), false);
  const guard = install(host.container, host.term);
  check("install returns the small documented handle",
    Object.keys(guard).sort(), ["dispose", "sweepNow"]);
  host.fire("keydown");
  check("a keystroke does not clear the field inside the settle window",
    host.field.value, "apt upgrade");
  advance(IDLE_DELAY_MS - 1);
  check("the field survives the whole settle window",
    host.field.value, "apt upgrade");
  advance(1);
  check("the accumulated fuel is cleared once the input goes quiet",
    host.field.value, "");
  check("no terminal surface is touched by a sweep",
    Object.keys(host.term), ["options"]);

  // ---- composition: never clear what xterm is about to read ---------------
  now = 20000;
  const composing = fakeHost(fakeField("apt"), false);
  const composingGuard = install(composing.container, composing.term);
  composing.fire("compositionstart");
  composing.fire("compositionend");
  advance(IDLE_DELAY_MS - 1);
  check("a commit is left in place until the idle window passes",
    composing.field.value, "apt");
  composing.fire("compositionstart");
  advance(IDLE_DELAY_MS * 3);
  check("an open composition is never cleared, however long it stays open",
    composing.field.value, "apt");
  composing.fire("compositionend");
  advance(IDLE_DELAY_MS);
  check("the field is cleared only after the composition closed",
    composing.field.value, "");
  composingGuard.dispose();

  // ---- screenReaderMode: the value is read aloud, so it stays --------------
  now = 30000;
  const reader = fakeHost(fakeField("apt upgrade"), true);
  const readerGuard = install(reader.container, reader.term);
  reader.fire("keydown");
  advance(IDLE_DELAY_MS * 2);
  check("screenReaderMode keeps the accumulated value",
    reader.field.value, "apt upgrade");
  reader.term.options.screenReaderMode = false;
  reader.fire("keydown");
  advance(IDLE_DELAY_MS);
  check("turning screenReaderMode off re-enables the sweep",
    reader.field.value, "");
  readerGuard.dispose();

  // ---- dispose: no listener and no timer survives --------------------------
  now = 40000;
  const disposed = fakeHost(fakeField("apt"), false);
  const disposedGuard = install(disposed.container, disposed.term);
  disposed.fire("keydown");
  disposedGuard.dispose();
  advance(IDLE_DELAY_MS * 2);
  check("a disposed guard never sweeps", disposed.field.value, "apt");
  check("every listener is detached on dispose",
    Object.keys(disposed.listeners), []);

  // ---- the upstream defect, modelled: before and after ---------------------
  // xterm's Android path emits `newValue.replace(oldValue, '')` one tick after
  // a keyCode-229 keydown. An in-place IME edit defeats that replace(), and the
  // whole field becomes input (xtermjs/xterm.js#6078).
  function upstreamEmission(oldValue, newValue) {
    const diff = newValue.replace(oldValue, "");
    if (newValue.length > oldValue.length) {
      return diff;
    }
    if (newValue.length === oldValue.length && newValue !== oldValue) {
      return newValue;
    }
    return "";
  }
  const residue = "apt upgrade";
  const editedResidue = residue.replace(/e$/, "é"); // same-length in-place edit
  const unmitigated = upstreamEmission(residue, editedResidue);
  check("unmitigated, the stale field is emitted as if freshly typed",
    unmitigated, editedResidue);
  check("that emission carries the earlier text with it",
    unmitigated.includes("apt up"), true);

  now = 50000;
  const fixed = fakeHost(fakeField(residue), false);
  install(fixed.container, fixed.term);
  fixed.fire("keydown");
  advance(IDLE_DELAY_MS);
  fixed.field.value += "a"; // the user's next real keystroke lands
  check("mitigated, the field holds only the in-flight input",
    fixed.field.value, "a");
  check("mitigated, the same in-place edit emits only that character",
    upstreamEmission("a", "é"), "é");
  check("mitigated, no earlier text can be re-emitted",
    upstreamEmission("a", "é").includes("apt up"), false);
} finally {
  global.setTimeout = realSetTimeout;
  global.clearTimeout = realClearTimeout;
  Date.now = realNow;
}

// ---- the page actually installs the guard ---------------------------------
// The policy tests above would still pass if terminal.html stopped loading the
// adapter, which would silently restore the upstream re-emission. Pin the wiring
// (and its order: the module must be loaded before the inline script installs it).
const fs = require("fs");
const html = fs.readFileSync(path.join(
  __dirname, "..", "..", "main", "assets", "terminal", "terminal.html"), "utf8");
const scriptTag = html.indexOf('<script src="ime-guard.js"></script>');
const installCall = html.indexOf("LinuxWrapperImeGuard.install(container, term)");
check("terminal.html loads the guard module",
  scriptTag >= 0, true);
check("the guard is installed by the trusted page",
  installCall >= 0, true);
check("the module is loaded before the page installs it",
  scriptTag >= 0 && installCall > scriptTag, true);
check("the guard is disposed with the terminal page",
  /imeGuard\.dispose\(\)/.test(html), true);

if (failures > 0) {
  console.error("\n" + failures + " ime-guard policy test(s) failed");
  process.exit(1);
}
console.log("\nall ime-guard policy tests passed");
