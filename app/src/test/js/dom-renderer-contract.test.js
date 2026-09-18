/* Contract test: the mobile terminal uses xterm's DOM renderer directly.
 * There must be no canvas/WebGL renderer hook and no transparent selectable
 * text mirror layered over xterm. Native WebView selection targets .xterm-rows.
 */
"use strict";

const assert = require("assert");
const fs = require("fs");
const path = require("path");

const assets = path.join(__dirname, "..", "..", "main", "assets", "terminal");
const html = fs.readFileSync(path.join(assets, "terminal.html"), "utf8");
const css = fs.readFileSync(path.join(assets, "xterm.css"), "utf8");
const touchScroll = fs.readFileSync(path.join(assets, "touch-scroll.js"), "utf8");

assert.match(html, /\.xterm[^}]*user-select:\s*text/s,
  "xterm DOM rows must opt into browser text selection");
assert.doesNotMatch(html, /selectmirror|selection-mirror|createElement\(["']canvas["']\)/i,
  "terminal page must not create a mirror or canvas layer");
assert.doesNotMatch(html, /addon-(?:canvas|webgl)|WebglAddon|CanvasAddon/i,
  "terminal page must not load a canvas/WebGL renderer addon");
assert.doesNotMatch(css, /\.xterm \.xterm-screen canvas\s*\{/,
  "vendored terminal CSS must not retain a canvas renderer hook");
assert.match(html, /smoothScrollDuration:\s*[1-9]\d*/,
  "mouse-wheel scrolling must retain xterm's short interpolation");
assert.match(touchScroll, /_scrollableElement/,
  "touch scrolling must use xterm's fractional pixel viewport");
assert.match(touchScroll, /FLING_FRICTION_PX_PER_MS2/,
  "touch scrolling must preserve native-like release momentum");
assert.match(html, /setNativeTextSelectionEnabled/,
  "terminal page must support disabling native selection for read-only viewers");
assert.match(html, /native-selection-disabled/,
  "read-only viewers must have a CSS selection-disabled mode");
assert.match(html, /native-selection-disabled[^}]*touch-action:\s*none/s,
  "read-only logs must keep every touchmove in the scroll adapter");
assert.match(touchScroll, /hasDomSelection/,
  "interactive terminal must retain its native-selection guard");
assert.match(touchScroll, /requestAnimationFrame/,
  "touch release momentum must be frame-scheduled");

console.log("ok - terminal renderer contract is DOM-only with no selection mirror");
