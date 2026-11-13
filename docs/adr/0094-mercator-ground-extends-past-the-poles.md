# Mercator ground extends past the poles

## Context

Mercator basemap tiles stop at `y = 0` and `y = 1`, so a frame that reaches beyond those limits exposes the backdrop. Attaching caps only to selected edge-row tiles is insufficient because a rotated or pitched past-edge footprint can touch columns that the in-world edge strip does not.

## Decision

Add the opt-in renderer configuration `extendGroundPastPoles`, defaulting to false. When enabled, each ADR 0065 band selects explicit north or south cap instances from its footprint clipped only in x; each cap keeps a valid edge-row canonical tile and uses a rectangular quad long enough to cover that band. Mercator shaders collapse texture `v` to the north or south edge before applying provisional colour windows, mirroring globe caps. Terrain caps similarly collapse their DEM window and pin Mercator `y` to the edge.

## Smudging

A stretched non-uniform edge row produces long streaks, and using that row's average as the distant colour can leave a visible colour step against the imagery immediately inside the map. A frame with any cap therefore provisions the LOD-zero basemap tile as one auxiliary, non-drawn resource. On first use, its outermost eight north and south rows are copied, in their original top-to-bottom order, into separate repeating `side x 8` RGBA8 textures. Their mip pyramids use trilinear minification: mip level 3 is `side / 8 x 1`, so it has averaged the band both vertically and horizontally, while every level above it remains one row tall and averages only horizontally. The shader samples at `v = 0.5` and clamps the chosen level to `max(log2(8), log2(max(1, blurWidth * stripSide)))`, never exposing a partially averaged vertical band.

A cap is only that band colour at the blur width for its distance; it contains no mix with the cap tile's sharp outer row. The five-tap tent and the blur growth of six source-tile widths per cap tile height remain, so distant columns converge without fixed-tap aliasing. Continuity moves inside the map: an ordinary north or south edge-row tile fades its already-resolved sharp colour to the applicable band colour over 160 logical pixels, using `1 - smoothstep(0, fadeDistance, signedDistanceInside)`. The distance is measured from `grid.y = 0` at the north edge and from `grid.y = 1` at the south edge, matching the ground grid's north-to-south `v`; the fade is shortened to half a tile when necessary so it cannot reach an adjacent row that does not run this path. The LOD-zero tile carries both edge bits, samples both bands, and takes the larger fade weight. At the edge itself the map is wholly band colour, exactly matching the cap.

ADR 0086 first mixes the primary and target tile colours to obtain the sharp map colour, independently obtains and mixes their world-band colours, and then applies the edge fade. Terrain variants use the same colour operations, with shading still applied to the final colour. Missing auxiliary roots or strips that cannot be built retain the previous collapsed-edge fallback and never fail the frame. Edge-row tiles only enter the fade path when extension selected caps and the primary world strips resolved; every other tile takes mode zero before any strip sample, preserving the ordinary picture when the option is off.

## Consequences

Existing renderers keep finite Mercator ground and unchanged selection when the option is off. When on, caps share edge-tile colour, blend, and DEM acquisitions; labels and resource requests therefore never use out-of-range rows. Cap instances count toward the ordinary instance budget, and overlapping per-band caps retain the existing coarse-to-fine draw order.

## Per-frame override (2026-11-13)

`FramePlan.extendGroundPastPoles: Boolean?`, appended after `basemap`, lets one frame decide for itself: `true` or `false` wins over `RendererConfiguration.extendGroundPastPoles` in either direction, and `null` (the default, and what every older serialized plan decodes to) follows the configuration. A consumer that paints its own backdrop past the map's edge on some frames and wants the pole bands on others needs this; Geoanimator turns the bands off exactly on frames that carry a backdrop. The value is resolved once, in `planMercatorSpatial`, which every Mercator tile selection passes through — prepare and read-ahead alike — and the draw only ever sees the cap instances selected there, so planning and drawing cannot disagree. Globe frames ignore it, as they ignore the configuration.
