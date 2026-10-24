# ADR-0071: Static SVG web-app favicons

## Status

Accepted. Extends the favicon formats in ADR-0015/0048 without changing app
registration, request origins, authentication, or icon precedence.

## Context

The registered NusaLLM app serves HTML on its local guest port with
`<link rel="icon" href="/favicon.svg" type="image/svg+xml">`. Its favicon is a
viewBox-only SVG containing a dark rounded rectangle and a green N. The launcher
parser deliberately skipped `.svg`, and Android BitmapFactory cannot decode
SVG. A reachable app therefore kept the fallback monogram.

## Decision

Include same-origin declared SVG links among the existing three favicon
candidates. Rasterize static SVG through pinned
`com.caverock:androidsvg-aar:1.4`, the upstream stable Maven Central artifact,
licensed Apache-2.0. The dependency uses Android Canvas, with no WebView. Its
established SVG implementation covers paths, transforms and fills without a
custom parser. Upstream documentation and source were inspected; version 1.4 is
old, so this is a deliberate stable-version pin, not a claim of recent releases.
Bundled notices are under `assets/icons/`.

`IconBitmapDecoder` first attempts the existing raster/ICO path, then parses
SVG when raster decoding fails. It renders within a 256-pixel longest side,
preserving the document aspect ratio (viewBox supported); unspecified aspect
ratio uses a square viewport. SVG coordinate units do not represent allocated
pixels. Bitmap allocation remains inside the existing 256×256×4 byte budget.
The same decoder remains available for a chosen image the platform cannot read.
No external-resource resolver is registered, and XML entity expansion is off
under the existing bounded-payload contract. Static self-contained SVG is the
scope; browser execution, animation and external SVG resources are not added.

The same-origin checks, response byte limits, HTTP behavior, Basic credentials,
user-picked icon precedence and reachability-triggered retry remain unchanged.

## Validation

A regression first failed against the original code for declared SVG admission
and SVG bitmap decoding. Tests render pixels from a viewBox-only N-shaped SVG,
preserve a non-square SVG's aspect ratio, reject malformed SVG, and retain PNG
downsampling. Existing favicon parser/HTTP/policy tests remain in the gate.
Device results were captured on the physical device.

Sources: [upstream documentation](https://bigbadaboom.github.io/androidsvg/),
[upstream source and license](https://github.com/BigBadaboom/androidsvg),
[Maven Central artifact](https://central.sonatype.com/artifact/com.caverock/androidsvg-aar/1.4).
