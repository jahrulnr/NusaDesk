# ADR-0035: Compact operational surfaces and local Markdown documentation

- Status: accepted
- Date: 2026-09-18

## Context

The Logs and Linux System surfaces were visually spacious but repeated large
hero copy and rounded cards around information that users need to scan quickly.
The System screen's `How it works` action also showed the product contract in a
plain native dialog, which could not present the architecture diagrams already
written in Mermaid Markdown.

## Decision

1. Keep Logs as a compact, flat catalog. Each log keeps a touch-sized row, a
   readable title, and its guest path/qualifier, but does not receive a large
   card background.
2. Keep System as one compact status panel followed by workspace, permissions,
   runtime details, and one documentation action. Remove obsolete eyebrow and
   repeated explanatory copy from the primary surfaces.
3. Render `How it works` in a WebView from packaged assets under the owned
   `https://nusadesk.local/how-it-works/` origin. The page uses the vendored
   Marked parser for the local Markdown document and Mermaid for SVG diagrams.
4. Mermaid runs with `securityLevel: "strict"`; the WebView has no JavaScript
   interface, serves only flat allowlisted assets, blocks non-owned navigation,
   and does not load a CDN or remote script.
5. The documentation WebView is owned and disposed by the dialog owner with
   the Activity lifecycle.

## Consequences

- Logs and System expose more useful content per screen and remain readable at
  the project's touch-target and font-scale constraints.
- The APK grows by the vendored Marked and Mermaid browser bundles; their MIT
  notices and exact versions are recorded in
  `app/src/main/assets/how-it-works/THIRD_PARTY_NOTICES.txt`.
- Mermaid diagrams are static documentation only. They have no links,
  callbacks, or host bridge.
- The WebView remains a presentation adapter; it does not own runtime state or
  execute shell commands.
