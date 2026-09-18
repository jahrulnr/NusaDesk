/* Contract test for the local How-it-works documentation surface.
 * The page must remain self-contained: Markdown and Mermaid are packaged,
 * Mermaid is strict, and the page must not depend on a CDN or a JS bridge.
 */
"use strict";

const assert = require("assert");
const fs = require("fs");
const path = require("path");

const assets = path.join(__dirname, "..", "..", "main", "assets", "how-it-works");
const html = fs.readFileSync(path.join(assets, "index.html"), "utf8");
const markdown = fs.readFileSync(path.join(assets, "how-it-works.md"), "utf8");
const css = fs.readFileSync(path.join(assets, "how-it-works.css"), "utf8");
const marked = fs.readFileSync(path.join(assets, "marked.umd.js"), "utf8");
const mermaid = fs.readFileSync(path.join(assets, "mermaid.min.js"), "utf8");
const notices = fs.readFileSync(path.join(assets, "THIRD_PARTY_NOTICES.txt"), "utf8");
const dialogSource = fs.readFileSync(path.join(
  __dirname, "..", "..", "main", "java", "gh", "nusashell", "nusadesk",
  "presentation", "widget", "FoundationContractDialog.java"), "utf8");

assert.match(html, /marked\.umd\.js/,
  "documentation page must use the packaged Markdown parser");
assert.match(html, /mermaid\.min\.js/,
  "documentation page must use the packaged Mermaid renderer");
assert.match(html, /window\.marked\.parse/,
  "Markdown must be converted to HTML in the page");
assert.match(html, /securityLevel:\s*[\"']strict[\"']/,
  "Mermaid must render with strict security");
assert.match(html, /window\.mermaid\.render/,
  "Mermaid source must be rendered into SVG");
assert.match(markdown, /flowchart\s+TD/,
  "architecture diagram must use a phone-friendly vertical layout");
assert.doesNotMatch(html, /https?:\/\//i,
  "documentation page must not load remote URLs");
assert.doesNotMatch(dialogSource, /setPositiveButton/,
  "documentation dialog must not add a bottom close-button panel");
assert.match(dialogSource, /setCanceledOnTouchOutside\(true\)/,
  "documentation dialog must use standard outside-tap dismissal");
assert.match(markdown, /```mermaid/,
  "documentation Markdown must contain at least one Mermaid diagram");
assert.match(css, /\.mermaid\s+svg/,
  "rendered Mermaid SVG must have responsive styling");
assert.ok(marked.length > 10000, "marked bundle must be present");
assert.ok(mermaid.length > 1000000, "Mermaid bundle must be present");
assert.match(notices, /mermaid.*11\.17\.2/i,
  "Mermaid version must be recorded");
assert.match(notices, /marked.*18\.0\.13/i,
  "Marked version must be recorded");

console.log("ok - local Markdown and strict Mermaid documentation contract");
