# Rasterise read-ahead tiles before the frame that needs them

ADR 0075 let a consumer say which frames it will prepare next, and RenG fetches those frames' raw tile bytes
into the consumer's Store while earlier frames are still being prepared. That removed the network from
`prepare`, and a device measurement of a cold export then showed that the network was not where the time went.
On a OnePlus 12, about ten of a 120-frame export's frames account for 74 to 82 percent of its wall time, a warm
run has none of them, and per-frame counters show those frames stalling in the engine's tile drawing:
`ENGINE_TILE_DRAW_NANOS` summing 1.2 to 2.9 seconds on a frame where the level of detail changes and twelve
tiles are new, and 0.2 to 2.1 seconds on later frames that each bring three to seven. The first of them had its
bytes warmed already, and the later ones stall identically in a run that makes no network request at all. A
frame waits because the tiles it is first to see have to be drawn, and drawing is CPU work that read-ahead has so
far left to the frame that needs it.

## The worker draws what it has just warmed

The read-ahead worker already holds a window of the tiles the next frames will want, in the order it expects to
prepare them, and it has just made their bytes local. After the warm returns it now also rasterises the window's
cold tiles and keeps the pixels, so that when a frame asks for one of them the drawing has been done.

This is the same work `prepare` does, in the same order. The worker runs the engine's `prepareBatch` over a slice
of the window and then `renderRaw` over that batch, both through the host helpers `prepare` uses, and closes the
batch as soon as the pixels are in hand. It does this inside the read-ahead operation of ADR 0075, between the
return of `warmRawResources` and the discarding of the window's registry, so every callback the engine makes
reaches the consumer through the window's own registry exactly as the warm's did. The bytes were stored a moment
earlier, so these are Store reads, and a tile whose warm failed simply fails to prepare and is left for the frame
that needs it, which fails on it exactly as it would have without read-ahead. A failure of the drawing is
absorbed like a failure of the warm: it is a hint, and the window's byte counts are reported whatever happens to
the pixels.

The worker skips a tile that is already on the GPU and one that is already in the cache, and it keeps ADR 0075's
cap of sixty-four tiles and its cursor order. It draws at most as many tiles as the cache can hold, because a
tile drawn only to be evicted before the frame that wants it is work thrown away, and the later tiles of a
window are the ones that would push out the earlier.

## The cache is a bounded queue of read-ahead output, not a memo

ADR 0052 argued against a renderer-lifetime memo of rendered tiles: an unbounded store of raw pixels sitting
beside the two budgets that govern them already, with the residency filter of ADR 0046 sufficient outside a
batch. That argument stands, and this cache is built to be the thing it did not rule out.

It holds only what the read-ahead worker drew, never what `prepare` drew, so it is not a memo of everything
prepared and a frame's own rasterisation is not added to it. It is bounded by an explicit byte budget, charged at
the size of each tile's pixels, and past the budget it evicts the entry used least recently. An entry is
**consumed** by the frame that takes it: the tile is removed from the cache as it moves into the frame, because
from that point the frame holds it, ADR 0046 holds the texture after the draw, and a copy left behind would be
the unbounded-pixel store ADR 0052 refused. What the cache retains is therefore a short list of tiles drawn for
frames that have not been prepared yet.

The budget is `RendererConfiguration.maximumReadAheadRasterisedTileBytes`, zero by default, and zero means
there is no cache, no worker rasterisation and no change to `prepare` in any respect. A consumer that never reads
ahead, or that reads ahead for the network alone, gets today's behaviour byte for byte. The option is public
because the cost is memory the consumer owns: a 512 pixel tile is a megabyte of pixels, so a budget that covers
one window is tens of megabytes held for as long as the window's frames are pending. It is validated to lie
between zero and one gibibyte, an upper bound chosen so that no value in range overflows the arithmetic around it
and so that a typo of several orders of magnitude fails fast rather than being believed.

## It sits beside the ADR 0044 and ADR 0057 budgets and replaces neither

A tile taken from the cache enters the frame exactly as a freshly drawn raw tile does, and that is the whole
design of the prepare side. It is charged to the frame's raw-tile reservation under
`maximumInFlightRawBasemapTileBytes`, with the same arithmetic ADR 0044 uses for tiles the batch memo of
ADR 0052 hands back. Where the reservation does not fit, the same fallback applies as for those tiles: the tile
is left out of the cache hits and drawn fresh, which draws it as a PNG where the reservation fails, so a frame
the budget cannot hold is no worse than it was. The cache's own budget is a separate, additional ceiling on
pixels waiting for a frame; the two never double-count one tile, because a tile leaves the cache's account as it
enters the frame's. A consumer sizes the cache with the in-flight budget in mind, since a window that fills the
cache and a frame that takes all of it hold the sum of the two for a moment.

ADR 0057's per-frame rasterisation budget bounds the work a frame adds, and a cache hit adds none, so hits are
taken before that budget is applied and do not count against it. A frame that takes six tiles from the cache and
has a budget of four draws no more fresh tiles than it did before. Residency of ancestors is untouched.

A cache hit is exactly what a fresh draw would have produced, so a frame deferring a tile to a resident ancestor
under ADR 0057 can instead present the exact tile when the cache has it, which is simply a better frame.

## A rendered tile is a function of its key

The key is `renderedTileKey`, the identity ADR 0069 remembers and `prepare` already uses for residency. It is a
SHA-256 over the compiled style's digest, the tile's level of detail, row and canonical column, and the tile's
output width and height. A rendered tile depends on the compiled style, the tile, the output size and the raw
bytes of the tile's sources, and `renderTile` and `renderTileRaw` take no other input; the engine documents that
two tiles drawn at different priorities are byte-identical and that the number of metatile workers does not
change a pixel (ADR 0076). The first three are in the key. The raw bytes are in the Store, and the worker reads
them from the same Store `prepare` reads, a moment earlier.

That leaves one honest gap, which is a tile whose source bytes change between the worker's draw and the frame's.
A consumer whose transport declares tiles fresh sees the same bytes at both times. One whose tiles revalidate
could see a draw from the older bytes, a difference of the kind the cache between the Store and the engine
already permits within a single batch. The exposure is bounded by how long an entry waits, which is the few
frames between a hint and its frame, and a `RELOAD` preparation asks for the bytes to be fetched again, so it
bypasses the cache and empties it: a consumer that reloads is saying it wants fresh drawing, and a cache hit
would answer that with pixels from before the reload.

The determinism claim is stated as a test, as ADR 0076 does: the same frames are prepared with read-ahead
rasterisation off and with it on, after a hint and a wait for the worker, and the tile pixels and the drawn
readback must be byte-identical, while the second run's frames must take tiles from the cache and rasterise
fewer in the foreground.

## The foreground goes first

The drawing the worker does competes with the drawing a frame does for the same metatile workers, and a hint
that made the frame being prepared wait behind its own read-ahead would make the feature a regression. Rentile
already has the mechanism, which ADR 0053 threaded through RenG: each tile's draw waits for a permit in one of
two lanes, a freed worker is offered to the urgent lane first, and a draw already holding a permit is not
preempted, so an urgent request waits at most for the tiles currently in the workers. The worker draws at
`NORMAL`. When the cache is enabled, `prepare`'s own render goes out as `URGENT`, whatever priority the consumer
passed, so that background rasterisation can never delay the frame being prepared. A consumer that passed
`URGENT` already gets that, and one that passed `NORMAL` is promoted, because with a background worker in the
same gate `NORMAL` would otherwise mean sharing a lane with read-ahead. The price is that ADR 0053's way of
marking one frame of a batch as the urgent one and the rest as not is flattened while the cache is on: every
foreground render is in the first lane together, and ordering among them is first-in first-out again. With the
cache disabled the consumer's priority is forwarded unchanged, as ADR 0053 has it.

The lanes order draws and nothing else. The worker's `prepareBatch` is not covered by them: it acquires through
the engine's first lane, as any acquisition does, and decodes tiles under the engine's own decode limit. Both are
cheap here, because the bytes were stored a moment earlier and the decode limit is separate from the draw
workers, but a window's decoding can briefly contend with a frame's. That is the one place the foreground does
not strictly go first, and it is bounded by the window's sixteen-tile slices.

## Lifecycle

The worker draws a slice at a time, sixteen tiles, and puts each slice's tiles into the cache as it finishes,
so the tiles a frame wants first are available first and a cancellation loses at most one slice. Each batch is
closed in a `finally`, so a window cancelled mid-draw releases its engine-side resources, and nothing the worker
holds outlives the window.

The cache holds pixels and keys, and no GL object, so no operation on it needs the render context and none can
leak a handle. It is cleared in four situations:

- A style change. The key embeds the style digest, so an entry from an old style can never be hit, but it would
  still occupy the budget. The cache remembers the digest of the style the last preparation committed, and
  `prepare` adopting a different one drops everything held for the old. The worker's insertions are checked
  against the same digest, so a window planned against an old style that finishes after the change is refused
  rather than inserted.
- `freeResources`. The rendered tiles are resources the consumer can ask to be freed: the cache drops what the
  selector matches, and `ResourceSelector.All` empties it.
- `close()`, which cancels the read-ahead worker first, as ADR 0075 has it, and then closes the cache. A closed
  cache refuses insertions, so a draw that was already finishing when `close()` ran cannot leave pixels behind.
- A `RELOAD` preparation, for the reason above.

`clearFrameHistory()` leaves it alone, for ADR 0035's reason that it clears history and a cache is not history.
`readAhead` after `close()` continues to do nothing.

## Counting

Three counters join ADR 0049's report, kept by RenG itself, appended after the read-ahead counters so no ordinal
moves: `READ_AHEAD_RASTERISED`, tiles the worker drew into the cache; `RENDERED_TILE_CACHE_HITS`, tiles a
`prepare` took from it instead of drawing; and `RENDERED_TILE_CACHE_EVICTIONS`, tiles the budget pushed out
before any frame wanted them. Tiles a frame had to draw are `ENGINE_TILES_RENDERED` less those the worker drew,
which is why the engine's own counter is kept as it is. A rising eviction count beside few hits says the budget
is too small for the window, or the window too far ahead for the frames to reach it.

## What this accepts

A consumer that enables the cache pays CPU on a worker thread and memory for the budget, and it pays them
whether or not its frames arrive as hinted: a hint the consumer abandons has drawn tiles nobody uses. That is
the cost the cap and the budget bound, and it is why the default is off. The engine's concurrency is relied on
to be safe for a second `prepareBatch` and `renderRaw` beside a foreground preparation's own, as it already is
for a warm; a test runs the two together to put that reliance on record.
