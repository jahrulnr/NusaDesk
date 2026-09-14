# ADR-0021: Launcher backdrop is a code-drawn Truchet tiling

## Status

Accepted and implemented: `LinePatternDrawable`, `DesktopHomeView`, the
`launcher_pattern_line` color in `values/`, `values-night/`, and
`values-notnight/`, and `launcher_backdrop.xml`. Verified on the Android 10 /
arm64 test device (dark) and by a resource-exact off-device render (light; the
device ROM locks night mode, see Consequences).

## Context

The launcher previously sat on a flat two-stop wash with a soft radial accent
glow near the bottom. Tuan asked for a thin line texture in the background —
"infinity line" — and rejected the first hand-invented lattice as ugly, asking
for actual research into math art rather than improvised geometry. The radial
glow was also rejected as an odd blue/violet blob in the middle of the screen.

Constraints from the repository: no bundled binaries without provenance, no
speculative dependencies, Java + XML only, and the launcher must stay legible in
both themes.

## Decision

1. **Motif: the Truchet arc tiling.** A Truchet tile is one square carrying two
   quarter-circle arcs of radius half the tile edge, each arc running from one
   edge midpoint to the next, so every edge midpoint carries exactly one arc
   endpoint. Continuity across tiles is therefore a property of the geometry,
   not something the renderer enforces (Weisstein, "Truchet Tiling", MathWorld;
   the arc variant is Pickover's modification of Truchet's 1704 diagonal tile).
   Each arc is drawn twice at a small radius offset, so the stroke reads as a
   woven ribbon — the look of the reference image.

   Alternatives that were rendered and compared at device scale before choosing:
   checkerboard Truchet (calmer but reads as rings/dumbbells), diagonal Truchet
   (clean but reads as graph paper), seigaiha wave rows (too dense, moiré at
   this size), and hitomezashi stitch grid (reads as generic graph paper).

2. **Drawn in code, not shipped as an image.** `LinePatternDrawable` renders the
   tiling onto the canvas: it repeats infinitely at any screen size, stays crisp
   at every density, and adds no asset, no bitmap memory, and no licensing
   question. Tiles are cheap (four `drawArc` calls per tile, ~50 tiles on a
   phone screen), which is safe as a scrolling view's background.

3. **Orientation from a coordinate hash, not a random generator.** Each tile's
   orientation is a deterministic function of its grid coordinates, so the field
   is stable while the user scrolls, has no fixed extent, and cannot shimmer
   between frames. `LinePatternDrawableTest` pins the two properties that
   matter: stability per coordinate, and enough variety that the field never
   degenerates into a plain grid.

4. **Color is themed, geometry is not.** The pattern takes a single themed
   color resource at roughly 6-7% of ink (`#11F2F7F6` in dark, `#100F191B` in
   light), so the texture reads as material in both themes while leaving text
   contrast untouched.

5. **The radial accent glow is removed.** It read as an unexplained colored blob
   behind the grid, and the line texture now supplies the depth it was faking.

6. **The "Add app" tile loses its dashed outline and its label becomes "Add".**
   The grid is uniformly borderless; the tile's own glyph and label carry the
   meaning. `tile_add_surface.xml` and the `tile_add_border` color are deleted
   rather than left as dead resources, and the form's own heading keeps the
   fuller "Add app" wording through `webapp_add_title`.

## Consequences

- The launcher has a textured, calm background with no image asset and no new
  dependency; the pattern is the same structure in both themes.
- A future designer can restyle the texture by changing one color resource, or
  replace the motif inside one class, without touching the launcher layout.
- Light-theme appearance is verified by a resource-exact off-device render, not
  on the test device: that device reports `mNightModeLocked=true` in
  `dumpsys uimode`, so `cmd uimode night no` cannot switch it. A light-mode
  device screenshot remains open.
- The Truchet tile is asymmetric by design; the pattern is decor, not a
  semantic layer, and carries no state.
