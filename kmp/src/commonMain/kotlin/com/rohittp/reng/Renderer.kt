package com.rohittp.reng

import kotlin.jvm.JvmInline

@JvmInline
public value class FramebufferName(public val value: UInt)

public sealed interface PreparedFrame : AutoCloseable {
    public val frameIndex: Long

    override fun close(): Unit
}

public sealed interface RenderTarget {
    public val framebufferName: FramebufferName
}

/** The largest `maximumReadAheadRasterisedTileBytes` accepted: 1 GiB (ADR 0077). */
private const val MAXIMUM_READ_AHEAD_RASTERISED_TILE_BYTES: Long = 1024L * 1024L * 1024L

/**
 * Everything a renderer is fixed by for its whole life: where it draws, what it draws through, and
 * the handful of options that are properties of the renderer rather than of any one [FramePlan].
 *
 * **A plain class rather than a `data class`, deliberately**, so a new option costs one constructor
 * parameter and one property and no `copy`/`componentN` fallout on the public ABI.
 */
public class RendererConfiguration(
    outputPixelSize: OutputPixelSize,
    transport: Transport,
    store: Store,
    basemapStyle: ResourceLocator? = null,
    resourceLimits: ResourceLimits = ResourceLimits(),
    maximumBasemapTileInstances: Int = 512,
    maximumPreparationBatchSize: Int = 256,
    maximumConcurrentResourceOperations: Int = 8,
    diagnosticSink: DiagnosticSink = DiagnosticSink.None,
    terrainShading: Boolean = false,
    maximumCachedConsumerPipelines: Int = 64,
    maximumConsumerPipelinesPerFrame: Int = DEFAULT_MAXIMUM_CONSUMER_PIPELINES_PER_FRAME,
    maximumConcurrentTileRasterisations: Int = 1,
    maximumReadAheadRasterisedTileBytes: Long = 0L,
    presentFailedTilesProvisionally: Boolean = false,
) {
    public val outputPixelSize: OutputPixelSize
    public val transport: Transport
    public val store: Store
    public val basemapStyle: ResourceLocator?
    public val resourceLimits: ResourceLimits
    public val maximumBasemapTileInstances: Int
    public val maximumPreparationBatchSize: Int
    public val maximumConcurrentResourceOperations: Int
    public val diagnosticSink: DiagnosticSink

    /**
     * Whether the displaced ground is shaded from a normal derived from the elevation data, **off by
     * default**.
     *
     * **Off is the default because it is what the styles asked for.** MapLibre does not shade terrain
     * either: it displaces, and relief shading comes from a `hillshade` layer the basemap engine
     * draws into the tile image. Of the 34 styles RenG is verified against, the 3 that wanted relief
     * shading declared `hillshade` and the 6 that declare `terrain` declined it — two perfectly
     * disjoint sets. Inventing light a style never requested is the divergence RenG's firewall
     * posture exists to prevent, so off, the ground stays unlit exactly as ADR 0026 says and draws
     * the pixels three published releases drew.
     *
     * **It exists at all because two of those six styles are pure vector** — 10 and 55 fill layers,
     * no raster layer — and a flat fill reads as flat however far it moves. The other four carry
     * satellite imagery whose pixels already contain relief shading.
     *
     * On, the ground is lit by ADR 0026's one directional light — azimuth 335 degrees, elevation 45
     * degrees, with its ambient term — rather than by a second lighting concept, and **normalised so
     * that ground with no relief is left exactly alone**: turning this on changes the pixels of
     * slopes and of nothing else. It is a property of the renderer rather than of a [FramePlan], so
     * it cannot vary frame to frame — the ground's displacing program is compiled with or without it
     * at setup, which is what makes "off draws the same bytes" a fact about which program ran rather
     * than an argument about floating point.
     *
     * This is **not** the style's own `lights` block. That compiles to a flat colour multiplier
     * independent of `terrain` entirely; it tints, and it cannot make relief visible.
     */
    public val terrainShading: Boolean

    /** Maximum unleased consumer Geometry/Backdrop pipelines retained between draws. */
    public val maximumCachedConsumerPipelines: Int

    /** Hard planning limit on distinct consumer Geometry/Backdrop shader pairs in one frame. */
    public val maximumConsumerPipelinesPerFrame: Int

    /**
     * How many of one frame's basemap tiles the engine may draw at once, from `1` through `16`; **`1`
     * by default**, which draws them one after another exactly as before (ADR 0076).
     *
     * Drawing a tile is CPU work, and a frame needing dozens of them spends most of its preparation on it
     * while the device's other cores idle. A higher count draws that many at a time. It changes how long
     * preparation takes and nothing it produces: the tiles' pixels, and the order they come back in, are
     * identical at every count.
     *
     * **It is the consumer's to set, because the consumer owns the cores.** Each busy worker holds a
     * raster surface and a bitmap, a few megabytes, and occupies one `Dispatchers.Default` thread for the
     * whole of a tile's draw, since the draw blocks rather than suspends. `Dispatchers.Default` has as
     * many threads as the device has cores, so a count at or above that starves everything else scheduled
     * on it, RenG's own coroutines and the resource acquisition a frame is waiting on among them. Leave
     * cores free: an offline export might pick the core count less one or two, an interactive view should
     * keep the default. RenG does not clamp the value to the device.
     */
    public val maximumConcurrentTileRasterisations: Int

    /**
     * How many bytes of already-drawn basemap tiles [Renderer.readAhead] may hold for frames not yet
     * prepared, from `0` through 1 GiB (`1_073_741_824`); **`0` by default**, which draws nothing ahead and
     * leaves `readAhead` and `prepare` exactly as they were without this option (ADR 0077).
     *
     * With a positive budget the read-ahead worker does more than fetch a window's tile bytes. It also draws
     * the window's tiles that are neither on the GPU nor already held, up to the 64-tile window cap and what
     * the budget can hold, and keeps their pixels, so that the `prepare` which later needs one takes it
     * instead of drawing it. Drawing is CPU work that a cold frame otherwise waits for, and on a phone that
     * wait, not the network, was most of an export's wall time. The pixels are identical to the ones
     * `prepare` would have drawn.
     *
     * **The budget is memory you are choosing to hold.** A tile is `width * height * 4` bytes of pixels,
     * about a megabyte at 512 by 512, charged against this budget as it is stored. When storing a tile would
     * pass the budget the least recently used tile is dropped first. A tile a `prepare` takes leaves the
     * cache as it enters that frame, and from then on the frame holds it under
     * [ResourceLimits.maximumInFlightRawBasemapTileBytes] like any tile it drew itself; the two budgets are
     * separate ceilings and are never charged for the same tile at once, so size this one with the
     * in-flight budget in mind. Tiles drawn for frames that never arrive stay until they are evicted, a
     * style change or [Renderer.freeResources] drops them, or the renderer closes.
     *
     * **While this is positive, `prepare` renders its own tiles as [RenGRenderPriority.URGENT]**, whatever
     * priority its caller passed, so that the worker's drawing can never delay the frame being prepared.
     * Among a consumer's own concurrent frames that is then one first-in first-out lane. A `prepare` in
     * [ResourceAccessMode.RELOAD] does not use the cache and empties it, since it asked for the tiles to be
     * fetched again. The worker draws on the same workers a frame does, so raise
     * [maximumConcurrentTileRasterisations] with this.
     *
     * Does nothing unless [Renderer.readAhead] is called. Counted by
     * [RenGMetricName.READ_AHEAD_RASTERISED], [RenGMetricName.RENDERED_TILE_CACHE_HITS] and
     * [RenGMetricName.RENDERED_TILE_CACHE_EVICTIONS].
     */
    public val maximumReadAheadRasterisedTileBytes: Long

    /**
     * Whether a `prepare` may draw a basemap tile whose own imagery could not be downloaded from a resident
     * ancestor of that tile instead of failing; **off by default**, which fails the frame exactly as every
     * earlier release did (ADR 0057, "A tile that could not be downloaded").
     *
     * **Why it exists.** Measured in an editor on a phone with no network, two imagery tiles missing from
     * the Store could not be fetched. Every `prepare` whose view needed either one threw
     * [RenGErrorCode.RESOURCE_UNAVAILABLE] at [PipelineStage.RESOURCE_LOOKUP]: 397 failures, and 5.6 s of
     * playback in which the preview did not move, although the ground around both tiles, and a coarser
     * tile covering each of them, was already on the GPU. Which tile the engine could not acquire is
     * known exactly, so the frame can do what [ResourceLimits.maximumTilesRasterisedPerFrame] already lets
     * it do for a tile it chose not to draw: magnify the nearest resident ancestor over it, and say so.
     *
     * **On**, a `prepare` whose basemap tiles fail to acquire leaves out every failed tile that has a
     * resident ancestor and asks for the rest once more. The frame draws those tiles from their
     * ancestors, reports [DiagnosticCode.GROUND_PRESENTED_PROVISIONALLY] when it is drawn, and counts them
     * in [RenGMetricName.TILES_PRESENTED_AFTER_FAILURE]. A tile drawn that way is still not resident, so
     * the next `prepare` asks for it again, and the real tile replaces the ancestor's pixels on the first
     * frame its download succeeds; nothing runs in the background.
     *
     * The frame still fails, with exactly the failure it fails with today, when:
     * - a failed tile has no resident ancestor. Zooming *out* into ground never drawn before has nothing
     *   coarser to borrow, and a frame that cannot draw its ground is not an improvement on one that fails;
     * - anything other than a raster or vector colour tile failed: a `raster-dem` tile (a hillshade
     *   layer's or terrain's), the style, a sprite, a TileJSON or a glyph range;
     * - the failure is not a failure to *acquire* the resource: a tile that arrived and does not decode, a
     *   store fault, or any rendering error;
     * - the engine could not name which tiles the failure belongs to, or named a resource RenG never
     *   routed (that is RenG's own defect, and an ancestor must not hide it);
     * - the second request fails too.
     *
     * **Leave it off for an export.** A frame drawn this way has a coarser picture where the missing tile
     * belongs, and a video encoder cannot tell; the diagnostic is the only record of it. It is meant for an
     * interactive preview, where a blurred patch for a few frames is better than a frozen view. Tile
     * substitution inside the engine stays disabled either way (ADR 0016): this never draws a tile the
     * frame did not already have on the GPU.
     */
    public val presentFailedTilesProvisionally: Boolean

    init {
        require(maximumBasemapTileInstances in 1..4096) {
            "maximumBasemapTileInstances must be within the supported range"
        }
        require(maximumPreparationBatchSize in 1..4096) {
            "maximumPreparationBatchSize must be within the supported range"
        }
        require(maximumConcurrentResourceOperations in 1..64) {
            "maximumConcurrentResourceOperations must be within the supported range"
        }
        require(maximumCachedConsumerPipelines in 1..4096) {
            "maximumCachedConsumerPipelines must be within the supported range"
        }
        require(maximumConsumerPipelinesPerFrame in 1..4096) {
            "maximumConsumerPipelinesPerFrame must be within the supported range"
        }
        require(maximumConcurrentTileRasterisations in 1..16) {
            "maximumConcurrentTileRasterisations must be within 1 through 16"
        }
        require(maximumReadAheadRasterisedTileBytes in 0L..MAXIMUM_READ_AHEAD_RASTERISED_TILE_BYTES) {
            "maximumReadAheadRasterisedTileBytes must be within 0 through 1073741824"
        }

        this.outputPixelSize = outputPixelSize
        this.transport = transport
        this.store = store
        this.basemapStyle = basemapStyle
        this.resourceLimits = resourceLimits
        this.maximumBasemapTileInstances = maximumBasemapTileInstances
        this.maximumPreparationBatchSize = maximumPreparationBatchSize
        this.maximumConcurrentResourceOperations = maximumConcurrentResourceOperations
        this.diagnosticSink = diagnosticSink
        this.terrainShading = terrainShading
        this.maximumCachedConsumerPipelines = maximumCachedConsumerPipelines
        this.maximumConsumerPipelinesPerFrame = maximumConsumerPipelinesPerFrame
        this.maximumConcurrentTileRasterisations = maximumConcurrentTileRasterisations
        this.maximumReadAheadRasterisedTileBytes = maximumReadAheadRasterisedTileBytes
        this.presentFailedTilesProvisionally = presentFailedTilesProvisionally
    }
}

public sealed interface Renderer : AutoCloseable {
    /**
     * [priority] chooses the engine's own resource-gate lane for this frame's basemap tiles (ADR
     * 0053). It orders nothing inside RenG, which still refuses a second concurrent preparation
     * rather than queueing it, and it reaches only the basemap: a style, a sticker and a model GLB
     * are acquired on RenG's driver path, where there is no lane to put them in.
     *
     * Declared last, and that is an ABI decision: every parameter before it is positional in shipped
     * consumer code.
     */
    public suspend fun prepare(
        plan: FramePlan,
        accessMode: ResourceAccessMode = ResourceAccessMode.NORMAL,
        priority: RenGRenderPriority = RenGRenderPriority.NORMAL,
    ): PreparedFrame

    /**
     * [priority] applies to every frame in [plans] alike. A batch is where a consumer most often
     * knows which frame it needs first, and the way to say so is two calls — the urgent frame, then
     * the rest — rather than a per-plan priority that would make one batch mean several engine
     * lanes at once.
     *
     * An empty [plans] is refused with [RenGErrorCode.INVALID_VALUE] at
     * [PipelineStage.FRAME_PLANNING], before any consumer exchange.
     */
    public suspend fun prepareBatch(
        plans: List<FramePlan>,
        accessMode: ResourceAccessMode = ResourceAccessMode.NORMAL,
        priority: RenGRenderPriority = RenGRenderPriority.NORMAL,
    ): List<PreparedFrame>

    /**
     * Tells RenG which frames the consumer expects to prepare next, so it can fetch their basemap
     * tiles into the consumer's [Store] while earlier frames are still being prepared and drawn
     * (ADR 0075). [plans] are in the order the consumer will prepare them; that order decides which
     * tiles are fetched first.
     *
     * **A hint, never a promise.** It returns at once, produces no result, and throws nothing for a
     * network or Store fault. It never changes what a later [prepare] returns, and it does not spare a
     * frame from failing on a tile that cannot be fetched: that frame fails when [prepare] reaches it,
     * as it would have without the hint. What the hint buys is that a tile which *can* be fetched is
     * usually already in the Store by then, so the frame reads it from disk instead of waiting on the
     * network. [queryMetrics] reports how many resources those fetches loaded, found already stored, and
     * failed to load ([RenGMetricName.READ_AHEAD_FETCHED], [RenGMetricName.READ_AHEAD_ALREADY_CACHED],
     * [RenGMetricName.READ_AHEAD_FAILED]), which is how a consumer can tell it is offline before a frame
     * fails on it.
     *
     * **It does nothing** until one [prepare] has succeeded (the tile set is planned against the style
     * that call compiled), when that call used [ResourceAccessMode.CACHE_ONLY], for a plan that draws no
     * basemap or that RenG cannot plan, and after [close]. Tiles already resident on the GPU are skipped.
     *
     * Only the frames' basemap ground tiles are fetched, through the ordinary [Transport] and [Store],
     * always in [ResourceAccessMode.NORMAL]. Each call replaces a window still queued and never cancels
     * one already running, so it may be called as often as the consumer likes; one window covers at most
     * 64 tiles that are not already warmed, taken in [plans]' order.
     *
     * With [RendererConfiguration.maximumReadAheadRasterisedTileBytes] above zero the worker also draws
     * the window's tiles and keeps their pixels for the [prepare] that will need them (ADR 0077), so the
     * frame skips the drawing as well as the fetch. The pixels are identical to a [prepare]'s own.
     *
     * Safe to call from any thread, including concurrently with [prepare] and [draw]: it reads only
     * immutable configuration, a snapshot [prepare] publishes, and residency and tile-key state that are
     * locked.
     */
    public fun readAhead(plans: List<FramePlan>): Unit

    /**
     * Suspends until no read-ahead window is queued or running, and returns at once when there is none:
     * before any [readAhead] has queued work, and after [close], including when [close] runs while this is
     * waiting (ADR 0077).
     *
     * **It is for pacing, not for gating frames.** A consumer sweeping a long timeline can call [readAhead]
     * for the next stretch and wait here before hinting the one after, so the worker is never asked to
     * fetch and draw further ahead than the frames will reach. A [prepare] never needs this: it is correct
     * whether or not a window has finished, and waiting for one only delays a frame that could have gone
     * ahead and drawn for itself. A window that fails still ends, so a tile that will not load cannot hold
     * this up.
     *
     * Cancelling the caller cancels the wait and nothing else; the worker carries on.
     */
    public suspend fun awaitReadAheadIdle(): Unit

    /**
     * Cancels the [prepare] or [prepareBatch] running when it is called and returns once that
     * preparation has ended (ADR 0014). A preparation that starts after the call is not cancelled.
     */
    public suspend fun cancelPreparations(): Unit

    /**
     * Clears the ordering, diff and LOD baseline the next [prepare] plans against (ADR 0035).
     *
     * Throws [RenGErrorCode.PREPARATION_IN_PROGRESS] while a [prepare] or [prepareBatch] is running,
     * which is the whole of its suspended lifetime, not only its first step; call
     * [cancelPreparations] first to end one.
     */
    public fun clearFrameHistory(): Unit

    public fun queryResources(selector: ResourceSelector = ResourceSelector.All): ResourceReport

    /**
     * Every counter the basemap engine has reported to this renderer, as a running total since it
     * was created (ADR 0049). A closed renderer answers an empty report, exactly as
     * [queryResources] does.
     */
    public fun queryMetrics(): MetricReport

    /**
     * Throws [RenGErrorCode.PREPARATION_IN_PROGRESS] while a [prepare] or [prepareBatch] is running,
     * for the whole of its suspended lifetime; call [cancelPreparations] first to end one.
     */
    public fun freeResources(selector: ResourceSelector = ResourceSelector.All): ResourceFreeResult

    public fun notifyGpuObjectsGone(): Unit

    public fun adoptCurrentRenderContext(): Unit

    public fun mintRenderTarget(framebufferName: FramebufferName): RenderTarget

    public fun draw(preparedFrame: PreparedFrame, renderTarget: RenderTarget): Unit

    /**
     * Throws [RenGErrorCode.PREPARATION_IN_PROGRESS] while a [prepare] or [prepareBatch] is running,
     * for the whole of its suspended lifetime; call [cancelPreparations] first to end one.
     */
    override fun close(): Unit
}

/** [RendererConfiguration.maximumConsumerPipelinesPerFrame]'s default. */
internal const val DEFAULT_MAXIMUM_CONSUMER_PIPELINES_PER_FRAME: Int = 256
