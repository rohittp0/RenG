package com.rohittp.reng.internal.readahead

import com.rohittp.reng.FramePlan
import com.rohittp.reng.RenGMetricName
import com.rohittp.reng.RenGRenderPriority
import com.rohittp.reng.RendererConfiguration
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.internal.basemap.BasemapStyleManifest
import com.rohittp.reng.internal.basemap.tileTimeRoutes
import com.rohittp.reng.internal.firewall.BasemapEngineHost
import com.rohittp.reng.internal.firewall.ReadAheadWarmSummary
import com.rohittp.reng.internal.gl.GlObjectRegistry
import com.rohittp.reng.internal.metrics.EngineMetricRecorder
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.planSpatial
import com.rohittp.reng.internal.shader.ShaderProfileMemo
import com.rohittp.reng.internal.thread.PlatformLock
import com.rohittp.rentile.PreparedStyle
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The renderer's read-ahead (ADR 0075, ADR 0077): the snapshot `prepare` publishes, the worker that warms and
 * draws ahead of the frames being prepared, and the rendered-tile cache that worker fills.
 */
internal class ReadAheadCoordinator(
    private val configuration: RendererConfiguration,
    private val basemapEngineHost: BasemapEngineHost,
    private val glObjectRegistry: GlObjectRegistry,
    private val metricRecorder: EngineMetricRecorder,
    private val shaderProfileMemo: ShaderProfileMemo,
    /** The renderer's raw-tile byte figure for a tile count; see `GroundTileProvisioner.checkedRawTileBytes`. */
    private val checkedRawTileBytes: (Int) -> Long?,
) {
    /**
     * What the read-ahead worker may know about this renderer: the style, manifest, access mode and
     * committed LOD of the last successful `prepare`. Published by `prepare`'s commit and read from
     * `readAhead`, hence volatile; `null` until one `prepare` has succeeded, and after close.
     */
    @Volatile
    internal var readAheadSnapshot: ReadAheadSnapshot? = null

    /**
     * Created on the first hint that has something to warm, so a renderer that never reads ahead
     * allocates no scope and starts no coroutine. Its scope is the renderer's own, and [close] cancels
     * it.
     *
     * Guarded by [readAheadLock] together with [readAheadClosed], so creation-and-submit and close's
     * cancellation are atomic with respect to each other: a hint racing [close] either submits to a
     * scheduler close then cancels, or finds the renderer closed and creates nothing. A lazy here let
     * a hint admitted before close create the scope after close had found none to cancel.
     */
    private var readAheadScheduler: ReadAheadScheduler<ReadAheadRequest>? = null
    private var readAheadClosed: Boolean = false
    private val readAheadLock = PlatformLock()

    /** Whether a read-ahead worker was ever started. For tests only. */
    internal val readAheadWorkerStarted: Boolean
        get() = readAheadLock.withLock { readAheadScheduler != null }

    private fun submitReadAhead(window: ReadAheadWindow<ReadAheadRequest>) = readAheadLock.withLock {
        if (readAheadClosed) return@withLock
        val scheduler = readAheadScheduler ?: ReadAheadScheduler(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            warmWhenNothingIsCold = renderedTileCache != null,
            warm = ::warmReadAheadWindow,
        ).also { readAheadScheduler = it }
        scheduler.submit(window)
    }

    /**
     * The tiles the read-ahead worker has drawn for frames not yet prepared (ADR 0077), or `null` when
     * `maximumReadAheadRasterisedTileBytes` is zero, which is what "disabled" means everywhere below: no
     * cache, no worker rasterisation, and `prepare` forwards the caller's priority as it always did.
     */
    internal val renderedTileCache: RenderedTileCache? =
        configuration.maximumReadAheadRasterisedTileBytes.takeIf { it > 0L }?.let(::RenderedTileCache)

    /**
     * One read-ahead window: warms the cold tiles' bytes, then draws the window's tiles into
     * [renderedTileCache] while the window's operation is still open (ADR 0077).
     *
     * Reads only [request]'s snapshot, never the host's unlocked style maps, which belong to the
     * preparing thread.
     */
    private suspend fun warmReadAheadWindow(
        request: ReadAheadRequest,
        cold: List<CanonicalBasemapTile>,
    ): ReadAheadWarmSummary {
        val snapshot = request.snapshot
        val cache = renderedTileCache
        val toDraw = if (cache == null) emptyList() else rasterisationCandidates(cache, snapshot.style, request.tiles)
        if (cold.isEmpty() && toDraw.isEmpty()) return ReadAheadWarmSummary(fetched = 0, alreadyCached = 0, failed = 0)
        val routed = if (toDraw.isEmpty()) cold else LinkedHashSet<CanonicalBasemapTile>(cold).apply { addAll(toDraw) }.toList()
        return basemapEngineHost.warmWindow(
            style = snapshot.style,
            tiles = cold,
            routes = tileTimeRoutes(
                manifest = snapshot.manifest,
                tiles = routed,
                accessMode = ResourceAccessMode.NORMAL,
                limits = configuration.resourceLimits,
            ),
            afterWarm = if (cache == null || toDraw.isEmpty()) {
                null
            } else {
                { rasteriseReadAheadTiles(cache, snapshot.style, toDraw) }
            },
        )
    }

    /**
     * The tiles of [wanted], in cursor order, a window should draw: those neither held by [cache] nor
     * resident on the GPU, at most a window's worth and at most as many as the cache can hold. A tile
     * drawn only to be evicted before its frame arrives is work thrown away, and the later tiles of a
     * window are the ones that would push out the earlier.
     */
    private fun rasterisationCandidates(
        cache: RenderedTileCache,
        style: PreparedStyle,
        wanted: List<CanonicalBasemapTile>,
    ): List<CanonicalBasemapTile> {
        val perTile = checkedRawTileBytes(1) ?: return emptyList()
        if (perTile <= 0L) return emptyList()
        val capacity = minOf(
            configuration.maximumReadAheadRasterisedTileBytes / perTile,
            MAX_READ_AHEAD_TILES.toLong(),
        ).toInt()
        if (capacity <= 0) return emptyList()
        val chosen = ArrayList<CanonicalBasemapTile>(minOf(capacity, wanted.size))
        for (tile in wanted) {
            if (chosen.size == capacity) break
            val key = basemapEngineHost.renderedTileKey(style, tile)
            if (cache.contains(key) || glObjectRegistry.resident(key) != null) continue
            chosen += tile
        }
        return chosen
    }

    /**
     * Draws [tiles] at `NORMAL` priority, a slice at a time, and puts each slice's pixels into [cache] as
     * it finishes (ADR 0077). Runs inside the read-ahead window's operation, from the worker.
     *
     * Reuses the host's `prepareTiles` and `renderTiles`, which `prepare` uses, under an explicit `NORMAL`
     * access mode: the invocation open at this moment can be a foreground `prepare` in any mode. Each batch
     * is closed in a `finally` as soon as its pixels are in hand, exactly as `prepare` closes its own, so a
     * cancellation mid-draw releases the engine's resources and leaves nothing here.
     */
    private suspend fun rasteriseReadAheadTiles(
        cache: RenderedTileCache,
        style: PreparedStyle,
        tiles: List<CanonicalBasemapTile>,
    ) {
        for (slice in tiles.chunked(READ_AHEAD_RASTERISE_SLICE_TILES)) {
            currentCoroutineContext().ensureActive()
            // Re-checked per slice: a foreground frame may have drawn or uploaded a tile meanwhile.
            val todo = slice.filter { tile ->
                val key = basemapEngineHost.renderedTileKey(style, tile)
                !cache.contains(key) && glObjectRegistry.resident(key) == null
            }
            if (todo.isEmpty()) continue
            val rendered = basemapEngineHost.prepareTiles(style, todo, ResourceAccessMode.NORMAL).use { prepared ->
                basemapEngineHost.renderTiles(
                    prepared,
                    asRawPixels = true,
                    priority = RenGRenderPriority.NORMAL,
                    forReadAhead = true,
                )
            }
            var stored = 0L
            var evicted = 0L
            for (tile in rendered) {
                val outcome = cache.put(style.digest, tile)
                if (outcome.stored) stored += 1L
                evicted += outcome.evicted.toLong()
            }
            metricRecorder.record(RenGMetricName.READ_AHEAD_RASTERISED, stored)
            metricRecorder.record(RenGMetricName.RENDERED_TILE_CACHE_EVICTIONS, evicted)
        }
    }

    internal fun readAheadSnapshotOf(
        style: PreparedStyle?,
        manifest: BasemapStyleManifest?,
        accessMode: ResourceAccessMode,
        selectedLod: Int?,
    ): ReadAheadSnapshot? =
        if (style == null || manifest == null) null else ReadAheadSnapshot(style, manifest, accessMode, selectedLod)

    internal fun readAhead(plans: List<FramePlan>) {
        if (plans.isEmpty() || basemapEngineHost.isClosed) return
        val snapshot = readAheadSnapshot ?: return
        if (snapshot.accessMode == ResourceAccessMode.CACHE_ONLY) return

        // The same spatial step prepare runs, minus everything that touches state: no frame identity
        // is registered and no history moves. The LOD chain is prepareBatch's own -- each plan's
        // selected LOD is the next one's previous -- starting from the last committed one.
        val wanted = LinkedHashSet<CanonicalBasemapTile>()
        var lod = snapshot.selectedLod
        for (plan in plans) {
            val outcome = try {
                planSpatial(
                    plan,
                    configuration.outputPixelSize,
                    lod,
                    configuration.maximumBasemapTileInstances,
                    configuration.basemapStyle != null,
                    shaderProfileMemo,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") unplannable: Throwable) {
                // A hint about a frame RenG cannot plan is dropped; prepare will say why when it
                // reaches that frame.
                continue
            }
            if (outcome !is SpatialOutcome.Success) continue
            val spatial = outcome.value
            lod = spatial.lodObservation.selectedLod
            // The ground draw's own gate: labels acquire their vector tiles on their own path.
            if (plan.drawBasemap) spatial.tileSelection?.canonicalResources?.let(wanted::addAll)
        }

        // Tiles already resident on the GPU need nothing. The residency registry and the tile-key memo
        // are locked, so this is safe from any thread, as `readAhead` documents.
        val cold = wanted.filter { tile ->
            glObjectRegistry.resident(basemapEngineHost.renderedTileKey(snapshot.style, tile)) == null
        }
        if (cold.isEmpty()) return
        submitReadAhead(
            ReadAheadWindow(
                context = ReadAheadRequest(snapshot, cold),
                styleDigest = snapshot.style.digest,
                tiles = cold,
            ),
        )
    }

    internal suspend fun awaitReadAheadIdle() {
        readAheadLock.withLock { readAheadScheduler }?.awaitIdle()
    }

    /** The read-ahead half of the renderer's `close()`. */
    internal fun close() {
        // Read-ahead stops first (ADR 0075): the snapshot is dropped so no new window can form,
        // and the worker is cancelled so no new one can start. `close()` cannot suspend, so the
        // worker is not joined; the host clears its read-ahead operation before it closes the
        // engine, which is what refuses any callback still on its way.
        readAheadSnapshot = null
        readAheadLock.withLock {
            readAheadClosed = true
            readAheadScheduler?.cancel()
        }
        // After the worker is cancelled, so nothing is inserting. Closed rather than merely
        // emptied: a draw already finishing in the worker must not leave pixels behind (ADR 0077).
        renderedTileCache?.close()
    }
}

/**
 * How many tiles the read-ahead worker draws in one engine batch (ADR 0077). Small enough that the tiles a
 * frame wants first reach the cache first and a cancellation loses little; large enough that sources
 * shared by neighbouring tiles are still acquired and decoded together.
 */
private const val READ_AHEAD_RASTERISE_SLICE_TILES: Int = 16
