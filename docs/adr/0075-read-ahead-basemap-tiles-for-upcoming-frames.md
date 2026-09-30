# Read ahead basemap tiles for upcoming frames

A frame's `prepare` suspends until every basemap tile it needs has arrived, so a consumer that renders
many frames in a row spends most of its wall-clock waiting on the network, and one tile that never
answers blocks its frame until the consumer's own timeout. A consumer which knows its next frames can
tell RenG what they will look at, and RenG can fetch those tiles while the current frame is still being
prepared and drawn. Rentile already has the primitive, `warmRawResources`: it fetches raw tile bytes
into the consumer's Store without decoding them, in the engine's lowest-priority lane, on the same
single flight that on-demand acquisition uses. RenG has never called it, and the reason is ADR 0016:
every callback Rentile makes reaches the consumer only through the one preparation invocation that is
open, and between invocations there is none.

## The public surface is one non-suspending call

`Renderer.readAhead(plans)` takes the Frame Plans a consumer expects to prepare next, in the order it
expects to prepare them, and returns at once. It is a hint and never a promise: it produces no result,
throws nothing for a network or Store fault, and does nothing at all until one `prepare` has succeeded,
in `CACHE_ONLY` mode, or after close. It never alters what a later `prepare` returns. A tile that
cannot be fetched still fails the frame that needs it, exactly as today, when `prepare` reaches it.
What the hint adds is that a tile which can be fetched is usually in the Store by then, so that frame
reads it from disk.

RenG plans the tiles itself, with the code `prepare` uses. The spatial planners are pure, and the
step that selects tiles for a plan is factored out of `FramePlanningCore.plan` so that both callers
share one function. The one thing `plan` does that a hint must not do, registering the frame's
identity, stays in `plan`. Tiles come out in cursor order, deduplicated, without those already resident
on the GPU. Each plan's selected LOD chains into the next as it does in `prepareBatch`, starting from
the LOD the last committed `prepare` selected.

The hint is bounded. A window is capped at 64 tiles after tiles already warmed this session are removed,
because Rentile's own documentation records that a whole-session read-ahead built on `prepareBatch`
became a five- to seven-fold regression, spending the connection budget on tiles the cursor would not
reach for minutes. A newer hint
replaces one that is queued but not yet started, so a consumer can call it as often as it likes.

## Each window is its own operation, and that is the amendment to ADR 0016

ADR 0016 says the registry is discarded when the invocation ends and is "neither a renderer-lifetime
response cache nor permission to share work across access modes". That is kept whole. Two shortcuts were
rejected against it. A registry which lived as long as the renderer would hold every body it ever
latched, since a `SuspendJoin` never evicts, and would replay a transient failure to every later
request for that resource. And warming inside `prepare`'s own invocation fails differently: a warm-only
flight is cancelled when its last waiter leaves, so any fetch which outlasts one `prepare` would never
finish.

Instead, one worker takes windows one at a time. For each window it builds a fresh registry for
`NORMAL` mode with the same byte ceilings `prepare`'s routes carry, preregisters the tile routes the
window can ask for, installs it as a second, read-ahead operation, calls `warmRawResources`, and discards
the registry only after that call has returned. When it returns, every flight the window started has
finished, including flights an on-demand acquisition joined in the meantime, so no Store write can lose
the transport digest it must match. Nothing outlives the window. A failure is latched in that window's
registry and is gone with it, so the next window retries the tile.

The worker is cancelled only by `close()`. A newer hint replaces the queued window and never cancels the
running one, because a foreground acquisition which joined a warm flight is waiting on it.

## Routing between the two operations

Rentile's callbacks do not say which caller a request belongs to, and both operations may know the same
URL. The routing is therefore by what each operation knows, and the foreground operation goes first.
A Transport request or a Store read goes to the foreground operation if it registered that route, else to
the read-ahead operation if it did, and otherwise to the foreground operation, so an unplanned exchange
fails as `AMBIGUOUS_RESOURCE_ROUTE` exactly as before. A Store write goes to whichever operation holds a
latched transport digest equal to the digest of the bytes being written. That is what keeps the two
orders safe. If the warm registered first, the flight is the warm's, its transport call reached the
read-ahead registry and latched there, and its write follows the digest back; a foreground acquisition
that joins the flight shares its bytes and makes no exchange of its own. If the foreground registered
first, the flight is the foreground's and the warm finds it already in flight and does nothing. Routing
a write by the route alone would send a warm flight's bytes to a registry that never latched them and
report an integrity failure that did not happen.

Style-time work is unchanged. Identity translation and TileJSON harvesting read only the foreground
operation, because a warm triggers neither: it touches raster, vector and hillshade-DEM samples alone,
and no sprite, glyph, TileJSON or terrain callbacks. The foreground reference is now read from Rentile's
workers as well as its own, so it is volatile.

## Counting what a window did

ADR 0049's report gains three counters that RenG keeps itself rather than reads from the engine: resources
a read-ahead window fetched, found already stored, and failed to fetch. Rentile reports one summary per
window rather than one outcome per tile, so a tile joins the set that later windows skip only when its
whole window finished with no failure. A consumer that wants to know early that it is offline reads
the failed counter instead of waiting for a frame to fail.

Two costs are accepted. Rentile's probe for whether a resource is already stored falls back to a full
read, because RenG's Store has no metadata call, so each cached tile costs a read and a SHA-256 in a
window; the resident filter and the warmed set keep repeats rare. And warming competes with `prepare`
for Store I/O and CPU, which the cap bounds. `close()` cannot block, so it cancels the worker and
clears the read-ahead operation instead of joining, and a Rentile callback that arrives afterwards
finds no operation and is refused before it reaches the consumer.

This amends ADR 0016 only where it describes the engine as reachable through one operation at a time. The
firewall's other rules are kept unchanged: one registry per operation, never shared across access modes,
routes registered before the exchange, and every write checked against the latched response.
