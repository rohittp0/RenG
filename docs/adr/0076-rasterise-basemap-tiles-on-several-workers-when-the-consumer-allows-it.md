# Rasterise basemap tiles on several workers when the consumer allows it

Rentile draws the output tiles of one batch on its own metatile workers, and RenG has always asked for
the default number of them, which is one. A frame that needs thirty tiles therefore draws thirty tiles
one after another on a single thread, and in a profile of a video export on a phone that was 48% of the
preparation pool, with the other cores idle. Nothing about the drawing needs to be serial: Rentile's
`ExecutionPolicy.maxConcurrentMetatileWorkers` exists to run it in parallel, and RenG simply never
passed it.

## One option, off by default

`RendererConfiguration.maximumConcurrentTileRasterisations` is the number of output tiles Rentile may
draw at once, from `1` through `16`, and it is forwarded as `maxConcurrentMetatileWorkers`. Nothing else
in `ExecutionPolicy` moves: the exchange, per-origin, decode and resident-decode limits keep the engine's
own defaults, since they govern the network and the decoder, which this option does not touch.

The default stays `1`, and that is the decision this ADR is for. The drawing is the same at any count, so
a consumer that does nothing gets the pixels, the ordering and the memory it got before. The option is
also one only the consumer can set well, because the cost of a worker is not RenG's to spend. A consumer
that renders offline, on a phone it owns for the duration of an export, can afford several; a consumer
that draws interactively while the user scrolls cannot, and would be made worse by a default that
assumed otherwise.

## What a worker costs, and what it can starve

A worker holds one tile's drawing at a time: a Skia raster surface of the tile's output size, which is a
megabyte or so for a 512 pixel tile, the bitmaps a hillshade draw reads, the per-tile view of the sprite
sheet, and the tile's pixels until they are handed back. Four workers are a handful of megabytes above
one. The decoded source tiles and the sprite atlas are shared between them rather than duplicated, and
stay bounded by Rentile's own decoded-bytes ceiling.

The larger cost is threads. Rentile runs its work on `Dispatchers.Default`, and a tile's draw is a
blocking call, not a suspension: each busy worker occupies one of the dispatcher's threads, which number
as many as the device has cores. Workers equal to the core count would leave nothing for RenG's own
coroutines, the consumer's, or the resource acquisition a frame is waiting on, so a consumer should choose
a count that leaves cores free. RenG does not clamp the option to the device, because it cannot know what
else the consumer has scheduled; it validates only the range in which the option is meaningful.

## Why the output does not depend on it

Rentile documents this mode rather than merely permitting it. The per-tile sprite view and the bitmaps a
draw reads are private to the draw. The two things shared across tiles are an immutable Skia image,
published by compare-and-set so a worker that loses the race closes its copy and reads the winner's, and
the gate that hands out the permits. A tile's pixels are a function of the prepared batch and the tile,
and the batch's results come back in the order the tiles were requested whatever order the workers finish
in. RenG states the claim as a test rather than relying on that reading: the same frames are prepared
with one worker and with four, and the tile pixels and the drawn readback must be byte-identical.
