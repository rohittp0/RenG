# Configure several basemap styles, and let a Frame Plan blend two

## This supersedes ADR 0004

ADR 0004 names one basemap style per renderer and keeps the style out of every **Frame Plan**. It
records the cost it accepted: a mid-sequence style change, "a day-to-night change mid-sequence", costs a
new renderer and a cold GPU cache. Travel Animator now authors exactly that — a project's map changes
style along its timeline, with a transition between two styles that is visible for a second or so, while
the camera keeps moving. Rebuilding a renderer at the seam cannot draw the transition at all (both styles
are on screen at once), and a preview that scrubs across a seam would rebuild on every crossing.

The style set stays in setup configuration, so ADR 0004's guard against accidental per-frame variation
holds: a plan cannot name a style the renderer was not set up with. What a plan gains is a choice among
the configured styles, and a blend of two.

## Public surface

- `RendererConfiguration.additionalBasemapStyles: List<ResourceLocator> = emptyList()`, appended last.
  The renderer's **Style Set** is `basemapStyle` (index 0) followed by these (indices 1..n). A
  configuration with no `basemapStyle` and a non-empty list is refused at construction: index 0 is the
  style a plan that says nothing draws, and it must exist for any other to.
- `FramePlan.basemap: BasemapSelection? = null`, appended last. `null` means index 0 alone, exactly the
  frame every existing consumer gets.
- `BasemapSelection(style: Int = 0, blend: BasemapBlend? = null)`. `style` is the **Primary Style**.
- `BasemapBlend(toStyle: Int, mask: BlendMask)`. Where the mask is 1 a pixel shows `toStyle`; where it is
  0 it shows the primary style; between, a premultiplied linear mix.
- `BlendMask`, sealed, every value finite and validated in `init`:
  - `Uniform(amount: Float)` — `amount` in `0..1` everywhere: a cross-fade.
  - `Radial(centreX: Float, centreY: Float, radius: Float, feather: Float)` — a circle that is 1 inside.
    Centre in **frame fractions** (0..1 across the frame's width and height from the top-left; may lie
    outside 0..1). `radius` and `feather` are fractions of the frame's diagonal, `radius >= 0`,
    `feather >= 0`.
  - `Linear(angleRadians: Float, position: Float, feather: Float)` — a straight edge sweeping across the
    frame in the direction `angleRadians` (0 = left to right, increasing counter-clockwise on screen).
    `position` 0 shows no pixel of `toStyle`, 1 shows every pixel, and the edge moves linearly between.
    `feather` is a fraction of the frame's diagonal.
- `DiagnosticCode.BASEMAP_STYLE_INDEX_OUT_OF_RANGE`, appended: frame planning refuses a selection whose
  `style` or `toStyle` is not in the Style Set. A blend whose `toStyle` equals `style` is refused in
  `init`.

The mask's exact functions are part of the contract, because a consumer draws its own geometry under the
same transition and must reproduce `m` to the bit (Travel Animator's map spotlight does). With `p` the
pixel centre in frame pixels, `W × H` the frame, `D = sqrt(W² + H²)`, and `smoothstep` GLSL's:

    Uniform: m = amount
    Radial:  r = distance(p, (centreX·W, centreY·H)) / D
             m = 1 − smoothstep(radius − feather, radius, r)        (feather = 0: m = r <= radius ? 1 : 0)
    Linear:  d = (cos a, −sin a)                                     (screen y grows downward)
             e = 0.5·(W·|cos a| + H·|sin a|) / D                     (half the frame's extent along d)
             s = dot(p − (W/2, H/2), d) / D                          (−e at the trailing corner, +e at the leading)
             edge = −e − feather + position·(2e + feather)           (position 0: edge behind the trailing corner)
             m = 1 − smoothstep(edge, edge + feather, s)            (feather = 0: m = s < edge ? 1 : 0)

So `toStyle` enters from the trailing side and the edge travels in direction `a`: at angle 0 the new
style comes in from the left edge of the frame and has covered it entirely at position 1.

The Radial edge grows from the centre outward as the consumer grows `radius`; RenG animates nothing.
Every mask is a per-frame value the consumer sets.

## Drawing

A frame without a blend draws its ground exactly as today. A blended frame draws its ground **once**, over
the same tile selection — the selection does not depend on the style (the spatial planners only ask
whether a style is configured) — with a mixed ground program: each tile instance samples the Primary
Style's texture through its own colour window and the `toStyle`'s texture through its own, and writes
`mix(primary, to, m)` (premultiplied) with the ordinary ground pass's blend, depth and cull state, from the
Primary Style's elevation (its terrain, if it has one). A `toStyle` with terrain of its own is draped over
the Primary Style's for the length of the blend; when a later plan selects it as the Primary Style its own
terrain applies, a jump this ADR accepts. Labels, terrain, DEM and hillshade come from the Primary Style
alone.

**Why one pass, not a second additive pass.** The first design drew the `toStyle`'s tiles in a second
pass whose output was scaled by `m`, so that `ONE, ONE_MINUS_SRC_ALPHA` produced the mix. That is only
correct if every pixel is covered by exactly one tile, and ADR 0065's LOD bands do not promise that: at a
pitched camera, a band keeps every tile that touches it, so tiles of neighbouring bands overlap and the
ordinary pass relies on draw order (the finer tile last) to hide it. The additive pass added `m × to` once
per covering tile, so a pitched cross-fade drew band-shaped blocks where `to` was added once per covering
tile — measured on a phone at 27° pitch as 0.75 where 0.5 was asked, and in a two-band readback as 0.875. Mixing inside one draw per tile makes an overlap behave
exactly as it does without a blend: the last tile drawn wins. It also draws the ground once rather than
twice, and needs no depth agreement between two programs.

The mixed programs are new internal program roles (flat, shaded and globe). The existing ground programs
are pinned character for character (ADR 0068) and are not edited. The mixed programs are compiled on the
first blended frame, not at setup, so a renderer that never blends compiles what it did.

Bloom is untouched: the basemap has never been emissive.

## Acquisition, caching and read-ahead

Each style in the set is compiled once and kept, keyed by its style key, rather than one compiled-style
slot. The first foreground preparation compiles the whole Style Set, so the read-ahead never compiles a
style: it only warms tiles of styles that are already compiled, on the worker it already had, and a
foreground preparation never waits behind a read-ahead's style fetch. A blend target whose
compilation is unavailable is treated exactly as an unavailable Primary Style already is (the frame
prepares with that style's ground absent, as ADR 0004's renderer did); nothing about a single-style
frame's failure behaviour changes. A style that fails the first attempt is attempted again only when a
frame names it. A frame acquires tiles for the styles its selection names: one normally, two during a blend. The
planner's "at most one basemap style may be traversed" becomes "at most two, and only the styles the
selection names". Rendered tile keys already carry the style digest (ADR 0069), so two styles' tiles never
collide in the GPU registry. Ancestor fallback and the provisional-failed-tile rule (ADR 0057) apply per
style.

The read-ahead's rendered-tile cache adopts the whole Style Set instead of one style, so the primary and
the blend target do not evict each other's tiles every frame; its byte budget is shared. `readAhead(plans)` warms, for each plan, the
styles that plan names — so a consumer that reads ahead across a seam warms the next style before the
seam arrives, with no other hint.

## Cost

A blended frame rasterises and uploads up to twice the tiles and samples two textures per ground
fragment in its one ground draw. Outside a blend a
frame costs what it did. Two styles' tiles share one GPU budget, so during a blend that budget reaches half
as far; least-recently-used eviction returns it after.

## Identity

`FramePlan.basemap` is Frame Identity segment `BASEMAP_SELECTION(11)`, encoded only when non-null, so a
plan that leaves it alone keeps the identity it had (the `Geometry.blend` precedent). A selection of index
0 with no blend is encoded, and differs in identity from `null` while drawing the same pixels: consumers
should send `null`.
