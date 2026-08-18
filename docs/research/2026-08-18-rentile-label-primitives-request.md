# Handoff: label primitives for a 3D consumer

**For:** an agent or developer working in the Rentile repository (`/Users/rohittp/Data/Other/rentile`,
`com.rohittp.rentile:kmp`).
**From:** RenG's Cycle C/D grilling session, 2026-08-18. RenG is `com.rohittp.reng`, at
`/Users/rohittp/Data/Other/RenG`.
**Status:** a request with reasoning and evidence, not an approved design. Rentile's owner decides whether
to take it, and the API sketch below is a starting point to argue with, not a specification.

Read this whole document before proposing anything. The last two sections list what must be verified first
and what is explicitly out of scope.

## Why this exists

RenG is a Kotlin Multiplatform 3D renderer that draws worlds on top of Rentile basemap tiles. It consumes
`RenderedTile.pngBytes` and maps it onto the mercator ground under a camera whose pitch runs `[0, 90)`.

Rentile 0.1.5 draws no map text. That is not an oversight and this document does not treat it as a bug —
it is an explicit compatibility-profile decision, made in three places in `StyleCompiler.kt`:

| Situation | Code emitted | Result | Source |
|---|---|---|---|
| Symbol layer with text and no meaningful icon | `TEXT_ONLY_LAYER_EXCLUDED` | layer excluded | `StyleCompiler.kt:1376` |
| Optional text alongside an icon, no `icon-text-fit` | `TEXT_COMPONENT_REMOVED_ICON_RETAINED` | text dropped, icon kept | `StyleCompiler.kt:1400` |
| Text coupled to an icon | `TEXT_COUPLED_ICON_LAYER_EXCLUDED` | **whole layer excluded, icon too** | `StyleCompiler.kt:1408` |

Grepping `kmp/src/commonMain/kotlin/` for `glyph` returns nothing. There is no font stack, no SDF glyph
fetch, no text shaping, and no `GLYPH` resource class.

The third row is worth noticing even without RenG: a text-coupled icon layer loses its icon as well as its
text, so a style that pairs labels with markers renders neither.

## Why RenG cannot simply ask Rentile to draw the text

The obvious fix — Rentile grows glyph handling and rasterises text into the tile like any other layer — is
wrong for RenG specifically, and it is worth being precise about why, because it is a geometry argument
rather than a preference.

Rentile's output is a mercator ground texture. RenG maps that texture onto the ground plane and views it
with a pitched perspective camera. Anything baked into the texture is baked into the ground: at pitch 0 it
looks correct, and as pitch increases toward the horizon the text is compressed vertically, sheared by
bearing, and reduced to sub-pixel height in the far field. Map labels are the one map element that must
stay upright, legible and constant-size in screen space regardless of camera orientation, which is why
every 3D map renderer draws them as screen-space billboards after the ground rather than as part of it.

There is a second, independent reason. Rentile's existing collision placement operates per tile, in tile
space — see `CollisionBox` and the `allowOverlap` check at `DefaultBasemapRasterizer.kt:1662-1736`.
Correct label collision for a 3D view is a screen-space problem across the whole viewport: two labels in
different tiles can overlap on screen while never overlapping in tile space, and whether they collide
depends on the camera. No tile-local decision can be right for all cameras, so collision has to happen in
the consumer, per frame.

So the seam is not "who draws text" but "who decides what, and who decides where":

- **Rentile owns what the style says** — MVT decode, filter and expression evaluation, which features
  produce labels, their text after formatting, their font stack, size, anchor, offset, justification,
  placement mode, priority and the icon they pair with, plus the glyph coverage those strings need.
- **RenG owns where it lands** — projection into screen space under the current camera, collision and
  priority resolution across the viewport, depth and occlusion against the 3D scene, and drawing.

## What Rentile already has that this would build on

None of this is speculative; it is all present in the checkout.

- A full MVT decoder in `internal/mvt/`, over the `wire` protobuf runtime.
- Style compilation with expression evaluation, layer filtering and zoom handling in
  `internal/style/StyleCompiler.kt`, including the symbol-layer classification quoted above.
- Sprite atlas compilation and icon placement, so the pattern of "compile an atlas, hand out placements"
  already exists.
- Tile-space collision boxes and an overlap policy.
- A resource model — `ResourceClass`, `ResourceTransport`, `RawResourceStore`, `ResourceLimits`,
  `SingleFlight`, retry — that a glyph resource class would slot into rather than needing invention.
- An existing escape hatch, `labelLayerDescriptors(PreparedStyle)` and
  `acquireLabelTiles(PreparedStyle, List<TileId>, ResourceAccessMode)`, returning
  `LabelLayerDescriptor(id, sourceId, sourceLayer, sourceMinimumZoom, sourceMaximumZoom, layerJson)` and
  `ValidatedMvtTile(requestedTile, sourceTile, sourceId, bytes, contentDigest)`.

That escape hatch is the reason this document exists. It hands back **encoded MVT bytes and raw layer
JSON**, so a consumer using it must write its own protobuf reader and its own style-expression evaluator —
a second copy of two things Rentile already has and does well. RenG will not do that: it has one
production dependency today, its repository policy forbids a protobuf runtime, and duplicating basemap
parsing contradicts its stated boundary.

## What is missing

1. **Glyph acquisition.** Fetching SDF glyph ranges for the font stacks a style requires, through the
   existing transport and store, under a new resource class with its own limit. This is the largest new
   piece and the one with real unknowns.
2. **Text shaping and measurement.** Turning a formatted string plus a font stack into positioned glyph
   quads with a measured bounding box. Skia is already a dependency and can do this; whether to use Skia's
   shaping or SDF glyph metrics directly is a genuine design choice with different tradeoffs for
   consistency with the rest of the tile.
3. **A label primitive API.** Decoded, style-evaluated, un-placed label descriptions plus the glyph atlas
   needed to draw them.

## API sketch, to argue with

Deliberately shaped as "everything the style decided, nothing about where it goes on screen".

```kotlin
public data class LabelGlyphAtlas(
    public val pngBytes: ByteArray,            // one atlas image, consumer uploads it
    public val contentKey: String,
    public val entries: List<LabelGlyphEntry>, // codepoint/font -> atlas rect + metrics
)

public data class LabelPrimitive(
    public val layerId: String,
    public val sourceTile: TileId,
    public val anchor: LabelAnchor,          // geographic position, or a line for line placement
    public val glyphs: List<PlacedGlyph>,    // atlas-relative quads, laid out in label-local space
    public val boundingBox: LabelBox,        // for the consumer's screen-space collision
    public val icon: LabelIconRef?,          // ties back to the sprite atlas Rentile already builds
    public val placement: SymbolPlacement,   // POINT | LINE | LINE_CENTER
    public val allowOverlap: Boolean,
    public val ignorePlacement: Boolean,
    public val priority: Int,                // style order, so consumers resolve ties identically
)

public suspend fun acquireLabelPrimitives(
    style: PreparedStyle,
    tiles: List<TileId>,
    accessMode: ResourceAccessMode,
): LabelPrimitiveBatch   // primitives plus the atlas they reference
```

The important property is that nothing in there is in screen coordinates and nothing has been collided.
Rentile says "this label exists, here, with these glyphs, at this priority, and may not overlap"; the
consumer decides whether it survives this frame.

## Constraints that must hold

- **Purity.** Every byte still arrives through the injected `ResourceTransport` and `RawResourceStore`.
  Glyph ranges are resources like any other: a resource class, a limit, a store key, single-flighted.
- **No new consumer burden by default.** Existing consumers that never call the new API must see no
  behaviour change, no new required configuration, and no new mandatory network traffic.
- **Redaction.** Glyph URLs are style-derived and may carry credentials, so they follow the existing
  `withRedactedAuthenticationQuery` identity rule (`internal/ContentIdentity.kt`). Note the existing
  hazard: `RawResourceKey.toString()` prints its `stableId` verbatim while every other Rentile DTO
  redacts. Do not widen that.
- **Cancellation** stays an unwrapped `CancellationException`, as the transport call sites and
  `TileRequestRetry.kt` already do.
- **Determinism.** Two runs over the same style and tiles must produce the same primitives in the same
  order, or consumer-side collision results will flicker between frames.
- **Additive release.** This should be a minor version — `0.1.6` or later — that RenG can adopt by bumping
  a version, with no change to `prepare`, `prepareBatch`, `render`, or the existing tile output.

## Verify before designing

- Whether Skia's text shaping is reachable from `commonMain` on all published targets, or only where
  Skiko's native payload is present. This decides whether shaping lives in Rentile or is pushed to the
  consumer as glyph metrics.
- What glyph endpoint the target styles actually use, and its licensing. Some style providers' glyph
  endpoints have terms that differ from their tile endpoints.
- Whether `TEXT_COUPLED_ICON_LAYER_EXCLUDED` dropping the icon as well is intended. If it is not, that is
  a smaller, independent fix worth doing first and separately — it restores markers to styles that pair
  them with labels, with no glyph work at all.
- How large a realistic glyph atlas is for a full-viewport label set, since the consumer uploads it as a
  texture and RenG's `ResourceLimits` will need a ceiling for it.

## Out of scope

- Drawing text into the rendered tile. See the geometry argument above.
- Screen-space collision, occlusion, depth, and fade-in behaviour — those are the consumer's, by
  construction.
- Changing the existing `acquireLabelTiles` surface. Leave it for consumers that do want raw MVT.
- Anything about RenG's schedule. RenG has no cycle that draws text and is not blocked on this; its Cycle C
  specification states plainly that RenG draws no map text and why.

## Definition of done

A released Rentile version whose new API lets a consumer draw style-correct map labels without decoding
MVT, without evaluating style expressions, and without fetching glyphs itself — and a note in Rentile's own
`CONTEXT.md` recording that label placement is deliberately the consumer's, so nobody later "completes" the
feature by rasterising text into the tile.
