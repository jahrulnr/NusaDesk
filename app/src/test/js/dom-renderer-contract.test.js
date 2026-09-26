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
// Scope the backdrop assertions to the actual body::before block: a rule
// elsewhere in the file must not be able to satisfy them.
const backdropRule = (html.match(/body::before\s*\{([^}]*)\}/s) || [null, ""])[1];
assert.ok(backdropRule, "the math-art backdrop rule must exist");
assert.match(backdropRule, /content:\s*""/,
  "the backdrop pseudo-element must generate a box (content)");
assert.match(backdropRule, /position:\s*fixed/,
  "the backdrop must be a fixed page layer");
assert.match(backdropRule, /pointer-events:\s*none/,
  "the math-art backdrop must be a non-interactive page layer");
assert.match(backdropRule, /z-index:\s*0/,
  "the math-art backdrop must sit behind the terminal");
assert.match(backdropRule, /background-image:\s*url\(["']math-art\.svg["']\)/,
  "the backdrop rule itself must paint the packaged math-art.svg tile");
assert.match(backdropRule, /background-repeat:\s*repeat/,
  "the backdrop tile must repeat across the page");
// #terminal needs a positioning context for its z-index to stack it above
// the fixed backdrop.
const terminalRule = (html.match(/#terminal\s*\{([^}]*)\}/s) || [null, ""])[1];
assert.ok(terminalRule, "the #terminal rule must exist");
assert.match(terminalRule, /position:\s*relative/,
  "the terminal must establish a stacking context for its z-index");
assert.match(terminalRule, /z-index:\s*1/,
  "the terminal must render above the math-art backdrop");
assert.match(html, /allowTransparency:\s*true/,
  "the terminal must allow the backdrop to show through its theme");
assert.match(html, /background:\s*["']rgba\(0,0,0,0\)["']/,
  "the xterm theme background must stay transparent for the backdrop");
assert.match(html, /\.xterm \.xterm-viewport\s*\{[^}]*background-color:\s*transparent\s*!important/s,
  "the viewport must not repaint the theme background opaque over the backdrop");
const mathArt = fs.readFileSync(path.join(assets, "math-art.svg"), "utf8");
assert.doesNotMatch(mathArt, /<text\b/i,
  "math art must be computed curves, never arithmetic glyph text");
assert.match(mathArt, /fill='none'/,
  "math art must be a stroke-only line drawing");
assert.match(mathArt, /stroke='#4fd1a5'/,
  "math art must use the fixed dim terminal green");
const pathElements = mathArt.match(/<path d='/g) || [];
assert.ok(pathElements.length >= 4,
  "math art must contain multiple computed loop paths");
const pathData = (mathArt.match(/d='[^']*'/g) || []).join("");
assert.ok(pathData.length > 500,
  "math art path data must be substantial computed geometry");
assert.match(pathData, /d='M[\d.\- ]+L[\d.\- ]+L/,
  "math art paths must carry multi-segment polyline data");
assert.match(mathArt, /contour lines/i,
  "math art provenance must name the computed level-set curves");
assert.match(mathArt, /build-terminal-math-art\.py/,
  "math art must point at its generator script");

console.log("ok - terminal renderer contract is DOM-only with no selection mirror");
