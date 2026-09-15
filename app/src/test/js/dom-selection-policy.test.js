/* Pure policy tests for preserving xterm's real DOM rows while Android owns a
 * native text selection. No mirror/canvas layer is involved.
 */
"use strict";

const assert = require("assert");
const path = require("path");
const { shouldFreezeRowMutation, shouldDeferFit } = require(path.join(
  __dirname, "..", "..", "main", "assets", "terminal", "dom-selection.js"
));

assert.strictEqual(shouldFreezeRowMutation(false), false,
  "normal terminal renders must remain live");
assert.strictEqual(shouldFreezeRowMutation(true), true,
  "xterm must not replace selected DOM row contents");
assert.strictEqual(shouldDeferFit(false), false,
  "fit runs immediately without a native selection");
assert.strictEqual(shouldDeferFit(true), true,
  "IME/viewport fit waits until native selection closes");

console.log("ok - direct DOM selection freezes row mutation and fit only while selected");
