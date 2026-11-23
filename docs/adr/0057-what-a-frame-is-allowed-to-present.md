# What a frame is allowed to present

Every release of RenG has answered one question the same way: a prepared frame's ground is drawn from
the tiles the frame asked for, at the LOD the frame chose, and `prepare()` does not return until every
one of them has been rasterised. `resolveGroundTiles` states it as an invariant — *"prepare() already
proved every ground instance names a rendered tile"* — and `renderBasemapTiles` awaits
`renderTiles` synchronously to make it true.

That is a good answer and it has a cost this stack has now measured from both ends. ADR 0046's own
commit recorded a single frame costing **863–2105 ms** inside that call on a Snapdragon 8 Gen 3.
ADR 0046 and ADR 0052 removed the *repeats* — a tile already on the GPU, a tile already rendered
earlier in the batch — and neither touches a cold frame, because a cold frame has nothing resident
and nothing memoised. **All of a first frame's latency is still there, and it is all in this call.**

## The change, stated as a permission rather than a behaviour

**When the consumer sets a per-frame rasterisation budget, a frame may draw a tile it has not
rasterised from a resident ancestor of that tile, and say so.**

Three things about that sentence are load-bearing.

**"When the consumer sets"** — `maximumTilesRasterisedPerFrame` defaults to no limit, and with no
limit nothing below changes at all. Every existing consumer keeps the guarantee they have today,
byte for byte, and this ADR costs them nothing. The trade it offers — a frame sooner, at a lower
resolution, for a while — is a product decision about a particular application's feel, and RenG is
not in a position to make it for anyone.

**"a resident ancestor"** — never a fetch, never a render, never a guess. If tile `(z, x, y)` has no
resident ancestor the frame rasterises it as it always has, budget or no budget: a frame that cannot
draw its ground is not an improvement on a frame that is slow. So the budget bounds the work a frame
*adds*, and can never make a frame draw nothing.

**"and say so"** — a frame that presented an ancestor is not the same artefact as one that did not,
and a consumer compositing a still, or capturing a video frame, needs to know which they have. It is
reported, not inferred.

## Why the ancestor is enough, and why nothing runs in the background

The obvious design is to return the frame immediately and rasterise the exact tiles on another
coroutine, replacing them as they land. It is also the design that makes this a project rather than a
change: it needs a notification path, a second writer into `GlObjectRegistry`, interaction with the
cancellation semantics ADR 0050 has just re-ruled, and an answer to what `close()` means while a
render is outstanding.

None of that is necessary, because **the consumer already has a loop**. A frame that skipped a tile
leaves it un-resident, so the next `prepare()` selects it again and rasterises it under that frame's
own budget. Refinement is driven by the animation the consumer is already running, one budget's worth
per frame, and arrives without a single background task: a pan into new territory shows coarse ground
immediately and sharpens over the next few frames, which is exactly what the budget is for.

A consumer who is *not* looping — one still, prepared once — sets no budget and waits, correctly.

## The mechanism already ships, for the other texture

`GROUND_ELEVATION_SOURCE` samples an **ancestor DEM** through `uv * scale + offset` today, and
`DemTileWindow` is already the rectangle that describes it: a power-of-two `childScale` with integer
offsets, exact in `Double`, with the world-wrap `floorMod` trap solved and the non-ancestor case
returning `null` rather than guessing. All of it is colour-agnostic.

So the colour path borrows the shape rather than inventing one. `CanonicalBasemapTile` is
`(lod, tileY, canonicalX)`, so the ancestor `k` levels up is `(lod - k, tileY shr k, canonicalX shr k)`
and the window is `(fraction + offset) / 2^k` with `offset` the low `k` bits — arithmetic that is
exact in binary floating point for every `k`, which is why no tolerance appears anywhere in it.

Sampling is `GL_LINEAR` with `GL_CLAMP_TO_EDGE` and no mipmaps, so magnifying an ancestor is plain
bilinear with no LOD selection to get wrong.

## What a consumer shader sees, and what it does not

The window reaches the ground shaders as one `vec4` uniform and one `mix`, replacing
`rengGroundUv = rengGroundGrid;`. A tile drawn exactly gets the identity window, so the instruction
executes on every tile and the shader has no branch and no second variant.

Consumer geometry shaders see nothing: ADR 0008 hands them `aPosition` and a matrix, and the ground's
own uv is not part of that contract.

## Two invariants go, and what replaces them

`resolveGroundTiles`' `require` — "prepare() already proved every ground instance names a rendered
tile" — stops being true, and it must not be softened into silence. It becomes: **every ground
instance names a rendered tile, a resident texture, or a resident ancestor of one**, and the case
that satisfies none of the three is still the typed `RESOURCE_UNAVAILABLE` failure ADR 0046 made it,
for the same reason — a texture evicted between preparation and draw is a frame this renderer cannot
honour, and crashing a consumer for a cache decision they never made remains wrong.

There is a **second** check saying the same thing one phase earlier, in `groundInstances` at
preparation time, and it was easy to miss because the two are worded differently and live four
hundred lines apart. It admits the same third way. Both are widened by exactly one clause, and
neither is relaxed: a tile with no rendered bytes, no resident texture and no resident ancestor is
still a defect at prepare and still a typed failure at draw.

## A tile that could not be downloaded

The permission above was granted for a tile the frame *chose* not to draw. The same permission, opted
into separately, now covers a tile the frame *could not* draw because its resource never arrived.

**What it answers.** Measured in Geoanimator's editor on an OPPO phone with no network: two raster
imagery tiles were missing from the consumer's Store and could not download. Every `prepare()` whose
view needed either one threw `RESOURCE_UNAVAILABLE` at `RESOURCE_LOOKUP` — 397 failures — and the
preview froze for 5.6 s of playback, while a coarser tile over each of them was resident on the GPU
throughout. The engine reports exactly which tiles it could not acquire (Rentile's `affectedTiles`), so
the frame knows which of its tiles it could draw from an ancestor instead.

**The permission.** With `RendererConfiguration.presentFailedTilesProvisionally` on, a preparation whose
basemap tiles fail to acquire may leave out every failed tile that has a resident ancestor, ask the
engine for the rest once more, and draw the left-out tiles from their ancestors exactly as a
budget-deferred tile is drawn: through the same colour window, reported by the same
`GROUND_PRESENTED_PROVISIONALLY`, and counted separately by
`RenGMetricName.TILES_PRESENTED_AFTER_FAILURE`.

**Why opt-in, and why for a preview only.** An export must never encode a frame with a hole it was not
told about, and a video encoder cannot read a diagnostic. Off, the default, is every earlier release's
behaviour. A preview is the opposite trade: a blurred patch for a few frames is better than a view that
does not move.

**Why `RendererConfiguration` and not `ResourceLimits`, where the budget lives.** The budget is a limit
— a number bounding work — and `ResourceLimits` is a `data class` of limits whose every field is a
number. This is a policy about what a frame may show, the same kind of renderer-lifetime choice as
`terrainShading`, and `RendererConfiguration` is the plain class built so that such an option costs one
constructor parameter and no `copy`/`componentN` growth on the ABI. It is a property of the renderer
rather than of a frame because the choice belongs to what the renderer is *for*: a consumer that both
previews and exports keeps it off on the exporting renderer.

**Ancestors only.** The rule is the one above, unchanged: never a fetch, never a render, never a guess.
A failed tile with no resident ancestor fails the frame, which means zooming *out* into ground never
shown before still fails, because there is nothing coarser on the GPU to borrow. The decision is all or
nothing: if any tile the failure names has no ancestor, the original failure is rethrown unchanged, so a
consumer's handling of it is exactly what it is today.

**What stays fail-closed.** Everything that is not a colour tile failing to *acquire*:

- a `raster-dem` tile, a hillshade layer's or terrain's: elevation is not colour, and terrain's DEM is
  acquired on a separate path this never sees;
- the style, a sprite, a TileJSON, a glyph range: shared by every tile, so there is no per-tile answer;
- a tile that arrived and does not decode, a Store fault, any rendering error;
- a failure that names no tile, a tile the frame did not ask for, or a resource whose digest names no
  route this invocation preregistered. The last is load-bearing: the firewall refusing a url RenG never
  derived also reaches the engine as an acquisition failure, and an ancestor must not hide RenG's own
  defect;
- the second request failing. The retry happens once, and whatever it throws is the frame's failure.

One limit is inherited from the engine. It acquires a source sample once however many requested tiles
share it, and the failure names only the tile whose sample it was. Two tiles overzoomed past a source's
`maxzoom` can share one sample, so only one of them is named; the other fails the second request and the
frame fails as it does today.

**Refinement is unchanged.** A tile drawn from its ancestor is still not resident, so the next
`prepare()` asks for it again. The first frame whose download succeeds draws the real tile, and nothing
runs in the background. The retry costs no network for the tiles that did arrive, because the
invocation's firewall registry latched their responses. The read-ahead worker (ADR 0075, ADR 0077) is
not affected; its own preparation fails as it always did.

**The firewall is unchanged in shape.** Tile substitution stays disabled at every engine call (ADR 0016).
The affected tiles are read at the one seam allowed to read a Rentile exception, mapped back onto the
`CanonicalBasemapTile`s the frame asked for, and handed to the renderer as an internal result beside the
exact `RenGException` today's path would throw. No engine type reaches the public API, and the public
failure gains no field.
