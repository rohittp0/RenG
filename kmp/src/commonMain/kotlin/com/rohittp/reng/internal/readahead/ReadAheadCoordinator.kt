package com.rohittp.reng.internal.readahead

import com.rohittp.reng.FramePlan
import com.rohittp.reng.RenGMetricName
import com.rohittp.reng.RenGRenderPriority
import com.rohittp.reng.RendererConfiguration
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.CommittedBasemapStyleUpdate
import com.rohittp.reng.basemapForCore
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

internal class ReadAheadStyleRollback(
    internal val before: List<ReadAheadStyleBefore>,
    internal val adoptedStyleDigests: Set<String>?,
)

internal class ReadAheadStyleBefore(
    val index: Int,
    val snapshot: ReadAheadSnapshot?,
)

/**
 * The renderer's read-ahead (ADRs 0075, 0077, and 0086): committed preparation state, per-style
 * snapshots, the worker that warms and draws ahead, and the rendered-tile cache that worker fills.
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
    private val styleSnapshots: MutableMap<Int, ReadAheadSnapshot> = mutableMapOf()

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
     * Reads only [request]'s immutable per-style snapshots, never the host's unlocked style maps.
     */
    private suspend fun warmReadAheadWindow(
        request: ReadAheadRequest,
        cold: List<ReadAheadTile>,
    ): ReadAheadWarmSummary {
        var fetched = 0
        var alreadyCached = 0
        var failed = 0
        val coldSet = cold.toHashSet()
        for (batch in request.batches) {
            val snapshot = batch.snapshot
            val cache = renderedTileCache
            val styleCold = batch.tiles.filter { tile ->
                ReadAheadTile(styleDigest = snapshot.style.digest, tile = tile) in coldSet
            }
            val toDraw = if (cache == null) {
                emptyList()
            } else {
                rasterisationCandidates(cache, snapshot.style, batch.tiles)
            }
            if (styleCold.isEmpty() && toDraw.isEmpty()) continue
            val routed = if (toDraw.isEmpty()) {
                styleCold
            } else {
                LinkedHashSet<CanonicalBasemapTile>(styleCold).apply { addAll(toDraw) }.toList()
            }
            val summary = basemapEngineHost.warmWindow(
                style = snapshot.style,
                tiles = styleCold,
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
            fetched += summary.fetched
            alreadyCached += summary.alreadyCached
            failed += summary.failed
        }
        return ReadAheadWarmSummary(
            fetched = fetched,
            alreadyCached = alreadyCached,
            failed = failed,
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

    /** The committed digest at [styleIndex], used to stage only actual changes without allocation. */
    internal fun committedStyleDigest(styleIndex: Int): String? = readAheadLock.withLock {
        styleSnapshots[styleIndex]?.style?.digest
    }

    /** Publishes changed named styles and the final frame's read-ahead cursor (ADR 0086). */
    internal fun publishCommittedStyles(
        updates: Array<CommittedBasemapStyleUpdate?>?,
        primaryStyleIndex: Int?,
        primaryStyle: PreparedStyle?,
        primaryManifest: BasemapStyleManifest?,
        accessMode: ResourceAccessMode,
        selectedLod: Int?,
    ): ReadAheadStyleRollback? = readAheadLock.withLock {
        if (readAheadClosed) {
            readAheadSnapshot = null
            return@withLock null
        }
        val before = updates?.let { ArrayList<ReadAheadStyleBefore>() }
        val adoptedBefore = if (updates != null && renderedTileCache != null) {
            styleSnapshots.values.mapTo(linkedSetOf()) { snapshot -> snapshot.style.digest }
        } else {
            null
        }
        updates?.forEach { update ->
            if (update == null) return@forEach
            val previous = styleSnapshots[update.index]
            if (previous?.style?.digest == update.acquired.prepared.digest) return@forEach
            before?.add(ReadAheadStyleBefore(index = update.index, snapshot = previous))
            styleSnapshots[update.index] = ReadAheadSnapshot(
                style = update.acquired.prepared,
                manifest = update.acquired.manifest,
                accessMode = accessMode,
                selectedLod = null,
            )
        }
        if (before?.isNotEmpty() == true) adoptCommittedStyleDigests()
        readAheadSnapshot = if (primaryStyleIndex == null || primaryStyle == null || primaryManifest == null) {
            null
        } else {
            ReadAheadSnapshot(
                style = primaryStyle,
                manifest = primaryManifest,
                accessMode = accessMode,
                selectedLod = selectedLod,
            )
        }
        if (before?.isNotEmpty() == true) {
            ReadAheadStyleRollback(before = before, adoptedStyleDigests = adoptedBefore)
        } else {
            null
        }
    }

    /** Restores the before-image when a committed delivery is cancelled before reaching its caller. */
    internal fun restoreCommittedSnapshot(
        snapshot: ReadAheadSnapshot?,
        rollback: ReadAheadStyleRollback?,
    ) = readAheadLock.withLock {
        val before = rollback?.before
        if (before != null) {
            var index = before.lastIndex
            while (index >= 0) {
                val entry = before[index]
                if (entry.snapshot == null) {
                    styleSnapshots.remove(entry.index)
                } else {
                    styleSnapshots[entry.index] = entry.snapshot
                }
                index -= 1
            }
            rollback.adoptedStyleDigests?.let { digests ->
                renderedTileCache?.restoreAdoptedStyles(digests)
            }
        }
        readAheadSnapshot = snapshot
    }

    private fun adoptCommittedStyleDigests() {
        val cache = renderedTileCache ?: return
        val digests = styleSnapshots.values.mapTo(linkedSetOf()) { snapshot -> snapshot.style.digest }
        cache.adoptStyles(digests)
    }

    internal fun readAhead(plans: List<FramePlan>) {
        if (plans.isEmpty() || basemapEngineHost.isClosed) return
        val snapshot = readAheadSnapshot ?: return
        if (snapshot.accessMode == ResourceAccessMode.CACHE_ONLY) return

        // The same spatial step prepare runs, minus everything that touches state: no frame identity
        // is registered and no history moves. The LOD chain is prepareBatch's own -- each plan's
        // selected LOD is the next one's previous -- starting from the last committed one.
        val wantedByStyle = LinkedHashMap<Int, LinkedHashSet<CanonicalBasemapTile>>()
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
            if (plan.drawBasemap) {
                val selection = plan.basemapForCore()
                val styleIndices = buildList {
                    add(selection?.style ?: 0)
                    selection?.blend?.let { blend -> add(blend.toStyle) }
                }
                spatial.tileSelection?.canonicalResources?.let { tiles ->
                    styleIndices.forEach { styleIndex ->
                        wantedByStyle.getOrPut(styleIndex) { LinkedHashSet() }.addAll(tiles)
                    }
                }
            }
        }

        val configuredStyleIndices = configuration.basemapStylesForCore().indices
        val batches = readAheadLock.withLock {
            wantedByStyle.mapNotNull { (styleIndex, wanted) ->
                if (styleIndex !in configuredStyleIndices) return@mapNotNull null
                val styleSnapshot = styleSnapshots[styleIndex] ?: return@mapNotNull null
                val cold = wanted.filter { tile ->
                    glObjectRegistry.resident(
                        basemapEngineHost.renderedTileKey(styleSnapshot.style, tile),
                    ) == null
                }
                if (cold.isEmpty()) {
                    null
                } else {
                    StyleReadAheadRequest(
                        styleIndex = styleIndex,
                        snapshot = styleSnapshot,
                        tiles = cold,
                    )
                }
            }
        }
        if (batches.isEmpty()) return
        val candidates = batches.flatMap { batch ->
            batch.tiles.map { tile ->
                ReadAheadTile(styleDigest = batch.snapshot.style.digest, tile = tile)
            }
        }
        submitReadAhead(
            ReadAheadWindow(
                context = ReadAheadRequest(batches = batches),
                tiles = candidates,
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
            styleSnapshots.clear()
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
