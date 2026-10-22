package com.rohittp.reng.internal.preparation

import com.rohittp.reng.PreparedGroundInstance
import com.rohittp.reng.RGBA_BYTES_PER_PIXEL
import com.rohittp.reng.RenGMetricName
import com.rohittp.reng.RenGRenderPriority
import com.rohittp.reng.RendererConfiguration
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.internal.basemap.BasemapStyleManifest
import com.rohittp.reng.internal.basemap.tileTimeRoutes
import com.rohittp.reng.internal.firewall.BasemapEngineHost
import com.rohittp.reng.internal.firewall.BasemapTilePixels
import com.rohittp.reng.internal.firewall.BasemapTilePreparation
import com.rohittp.reng.internal.firewall.PreparedBasemapTiles
import com.rohittp.reng.internal.firewall.RenderedBasemapTile
import com.rohittp.reng.internal.gl.GlObjectRegistry
import com.rohittp.reng.internal.gl.GroundTileWindow
import com.rohittp.reng.internal.gl.ancestorOf
import com.rohittp.reng.internal.gl.windowWithin
import com.rohittp.reng.internal.metrics.EngineMetricRecorder
import com.rohittp.reng.internal.planning.BasemapTileInstance
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.planning.presentableFailedTiles
import com.rohittp.reng.internal.readahead.RenderedTileCache
import com.rohittp.rentile.PreparedStyle

/**
 * Provisions a frame's ground tiles during `prepare`: which tiles the engine rasterises and under which
 * budgets (ADRs 0044, 0046, 0052, 0057, 0077), and the frame's ground instances over them.
 */
internal class GroundTileProvisioner(
    private val configuration: RendererConfiguration,
    private val basemapEngineHost: BasemapEngineHost,
    private val glObjectRegistry: GlObjectRegistry,
    private val metricRecorder: EngineMetricRecorder,
    private val rawTileBudget: RawTileBudget,
    /** The read-ahead worker's rendered tiles, or `null` when that cache is disabled (ADR 0077). */
    private val renderedTileCache: RenderedTileCache?,
) {
    /**
     * Every tile this `prepareBatch` has already rasterised, or `null` outside one (ADR 0052).
     *
     * ADR 0046's residency filter cannot see inside a batch, because nothing in a batch is drawn and
     * so no texture becomes resident until the consumer draws: without this, every frame
     * re-rasterises every tile the frame before it just rendered.
     *
     * Batch-scoped and nothing wider. A renderer-lifetime memo would be an unbounded store of raw
     * pixels beside the two budgets that already govern them, and outside a batch there is a draw
     * between frames, which is what makes the residency filter sufficient there.
     */
    internal var batchRenderedTiles: MutableMap<ResourceKey, RenderedBasemapTile>? = null

    /**
     * Pairs every unwrapped draw [instances] entry with the rendered tile it draws, by deriving RenG's
     * own [BasemapEngineHost.renderedTileKey] for it (ADR 0018) rather than by matching its
     * `(lod, tileY, canonicalX)` triple against [renderedTiles] structurally. The two agree by
     * construction, and that is the point: deriving the key here means the draw path resolves a tile
     * through the same identity the acquisition path named it with, so a divergence between them fails
     * this `check` loudly instead of drawing the wrong ground.
     *
     * The derivation is memoized per **canonical** tile, not performed per instance: the instance
     * overload projects the world copy away, so every copy of one tile derives the identical key, and a
     * frame at the 512-instance budget would otherwise pay 512 SHA-256 hashes for at most a few dozen
     * distinct answers.
     *
     * Returns empty whenever there is no basemap to place: no style configured, or no tile selected.
     *
     * **Rendering nothing is not having no ground.** `renderBasemapTiles` asks the engine only for
     * tiles whose texture is not already resident, so a frame that pans back over ground it has drawn
     * before legitimately renders nothing and still has ground to place; keying this on the rendered
     * list would drop the ground on exactly those frames.
     *
     * **"Already resident" means resident when this frame selected the tile, not resident now.**
     * [residentAtSelection] is the set `renderBasemapTiles` skipped for that reason, and it is accepted
     * here without asking the registry again. Asking again was a race: a skipped tile holds no lease,
     * a draw may overlap preparation by contract (CONTEXT.md), and that draw's byte-budget eviction
     * could delete the texture between selection and this `check`, which would then throw a bare
     * `IllegalStateException` out of `prepare` for a frame that was right at selection. The tile could
     * equally leave after this `check` passed and before the frame's own draw, so the draw is the one
     * place that answers it: `resolveGroundTiles` leases the texture if
     * it is still there, falls back to a resident ancestor, or fails with the typed
     * RESOURCE_UNAVAILABLE at DRAW that a consumer can answer by preparing again. The live registry
     * read stays as a fourth way to pass, for a tile that became resident since selection.
     *
     * The `check` keeps its real job: a key this frame neither rendered, nor saw resident at
     * selection, nor can find now, nor can cover from an ancestor is a key the acquisition path never
     * named -- the divergence between the two derivations this function exists to catch.
     */
    internal fun groundInstances(
        instances: List<BasemapTileInstance>,
        renderedTiles: List<RenderedBasemapTile>,
        residentAtSelection: Set<ResourceKey>,
        styleDigest: String?,
    ): List<PreparedGroundInstance> {
        if (instances.isEmpty() || styleDigest == null) return emptyList()
        val renderedKeys = renderedTiles.mapTo(HashSet()) { it.key }
        return instances.map { instance ->
            val canonical = CanonicalBasemapTile(
                lod = instance.lod,
                tileY = instance.tileY,
                canonicalX = instance.canonicalX,
            )
            // No local memo: the host's own remembrance (ADR 0069) collapses the world copies of one
            // canonical tile and shares the derivations `renderBasemapTiles` made for this frame.
            val key = basemapEngineHost.renderedTileKey(styleDigest, instance)
            // Rendered this frame, on the GPU when this frame selected it (and possibly evicted since
            // by an overlapping draw -- the draw answers that, see the KDoc), on the GPU now, or --
            // since ADR 0057 -- coming from a resident ancestor because this frame's budget declined
            // to rasterise it or, opted into, because it could not be downloaded.
            // All are ways for the draw to have pixels; only naming none of them is a defect.
            check(
                key in renderedKeys ||
                    key in residentAtSelection ||
                    glObjectRegistry.resident(key) != null ||
                    residentAncestorOf(styleDigest, canonical) != null,
            ) {
                "every selected tile instance must name a tile this frame rendered, one already " +
                    "resident, or a resident ancestor"
            }
            PreparedGroundInstance(instance = instance, resourceKey = key)
        }
    }

    /**
     * Declares the tile routes [manifest] says this frame's ground will make the engine ask for, then
     * acquires and draws it. Runs inside the style's own invocation, as [completedStyleManifest] does.
     *
     * Every tile it skips because its texture is already resident is added to [residentAtSelection],
     * which `groundInstances` reads instead of asking the registry a second time; see the comment at
     * the skip for why that second answer cannot be trusted.
     */
    internal suspend fun renderBasemapTiles(
        manifest: BasemapStyleManifest,
        style: PreparedStyle,
        canonicalTiles: List<CanonicalBasemapTile>,
        accessMode: ResourceAccessMode,
        priority: RenGRenderPriority,
        rawReservationSink: (RawTileBudget.Reservation) -> Unit,
        residentAtSelection: MutableSet<ResourceKey>,
    ): List<RenderedBasemapTile> {
        // Routes for EVERY visible tile, never only the missing ones. `tileTimeRoutes` expands a
        // `raster-dem` source through `demNeighbourhoodOrSelf`, so terrain's routes come from this
        // same list, and a resident colour texture says nothing about whether the DEM beneath it is
        // acquired, so narrowing this to `missing` would leave terrain unrouted.
        basemapEngineHost.registerRoutes(
            tileTimeRoutes(
                manifest = manifest,
                tiles = canonicalTiles,
                accessMode = accessMode,
                limits = configuration.resourceLimits,
            ),
        )

        // Only tiles that are neither resident on the GPU (ADR 0046) nor already rendered by this
        // batch (ADR 0052) go to the engine. The memo exists because residency cannot see inside a
        // batch, where nothing has been drawn yet.
        //
        // A memo hit is copied into THIS frame's tile list rather than left with the frame that
        // rendered it: a batch fixes no draw order, so a frame pointing at a sibling's texture
        // would answer RESOURCE_UNAVAILABLE for a tile the batch definitely rendered. The shared
        // RenderedBasemapTile is immutable and holds one copy of the pixels.
        //
        // Reading the registry here is the one cross-phase read: preparation touches it, every
        // other touch is under a draw. CONTEXT.md lets a draw overlap GL-free preparation, and a
        // consumer does exactly that -- an exporter prepares frame N+1 on a worker while frame N
        // draws on the GL thread. The read itself is safe, because `GlObjectRegistry` guards every
        // map behind its own PlatformLock. What it answers is not stable: a skipped tile is NOT
        // leased, so the overlapping draw's byte-budget eviction may delete its texture a moment
        // after this read. That is why a skip is recorded in [residentAtSelection] rather than
        // re-asked later in this `prepare`, and why the draw, not preparation, is where a tile gone
        // by then is answered (`resolveGroundTiles`' typed RESOURCE_UNAVAILABLE).
        val memo = batchRenderedTiles
        val alreadyRendered = ArrayList<RenderedBasemapTile>()
        val rememberedRaw = ArrayList<Pair<CanonicalBasemapTile, RenderedBasemapTile>>()
        val missing = ArrayList<CanonicalBasemapTile>(canonicalTiles.size)

        // ADR 0077. Tiles the read-ahead worker has already drawn are taken before anything is
        // rasterised, and enter the frame as raw tiles the batch memo handed back, so they take the same
        // raw-tile reservation and the same fallback when it does not fit. `RELOAD` asked for the bytes to
        // be fetched again, so it neither reads nor keeps what was drawn from the old ones.
        val cache = renderedTileCache
        if (cache != null) {
            if (accessMode == ResourceAccessMode.RELOAD) cache.clear()
            cache.adoptStyle(style.digest)
        }
        val takeFromCache = if (accessMode == ResourceAccessMode.RELOAD) null else cache
        val cachedKeys = ArrayList<ResourceKey>()
        val cachedHits = ArrayList<RenderedBasemapTile>()

        for (tile in canonicalTiles) {
            val key = basemapEngineHost.renderedTileKey(style, tile)
            if (glObjectRegistry.resident(key) != null) {
                residentAtSelection += key
                continue
            }
            val remembered = memo?.get(key)
            when (remembered?.pixels) {
                is BasemapTilePixels.Raw -> rememberedRaw += tile to remembered
                is BasemapTilePixels.Encoded -> alreadyRendered += remembered
                null -> {
                    val drawnAhead = takeFromCache?.peek(key)
                    if (drawnAhead == null) {
                        missing += tile
                    } else {
                        rememberedRaw += tile to drawnAhead
                        cachedKeys += key
                        cachedHits += drawnAhead
                    }
                }
            }
        }
        // ADR 0057's budget, applied here because here is where the rasterising happens. A tile left
        // out is not dropped: `resolveGroundTiles` draws it from a resident ancestor, and the next
        // `prepare()` selects it again -- it is still not resident -- and rasterises it under that
        // frame's own budget. Refinement rides the consumer's own loop and needs no background work.
        //
        // Only tiles that HAVE a resident ancestor may be deferred. A frame that cannot draw its
        // ground is not an improvement on a frame that is slow, so the budget bounds the work a
        // frame adds and can never make it draw nothing.
        applyRasterisationBudget(missing, style.digest)

        var rawReservation: RawTileBudget.Reservation? = null
        var useRememberedRaw = rememberedRaw.isNotEmpty()
        var renderFreshAsRaw = false
        val rememberedRawBytes = rememberedRaw.sumOf { (_, tile) -> tile.rawPixelBytes() }
        val combinedRawBytes = checkedRawTileBytes(missing.size)?.let { fresh ->
            if (rememberedRawBytes <= Long.MAX_VALUE - fresh) rememberedRawBytes + fresh else null
        }
        if (combinedRawBytes != null && combinedRawBytes > 0L) {
            rawReservation = rawTileBudget.tryReserve(combinedRawBytes)
            renderFreshAsRaw = rawReservation != null
        }
        if (rawReservation == null && rememberedRawBytes > 0L) {
            rawReservation = rawTileBudget.tryReserve(rememberedRawBytes)
            if (rawReservation == null) {
                useRememberedRaw = false
                missing += rememberedRaw.map { it.first }
                applyRasterisationBudget(missing, style.digest)
                val freshBytes = checkedRawTileBytes(missing.size)
                if (freshBytes != null && freshBytes > 0L) {
                    rawReservation = rawTileBudget.tryReserve(freshBytes)
                    renderFreshAsRaw = rawReservation != null
                }
            }
        }
        rawReservation?.let(rawReservationSink)
        if (useRememberedRaw) alreadyRendered += rememberedRaw.map { it.second }
        if (takeFromCache != null && cachedKeys.isNotEmpty()) {
            // A tile leaves the cache as it enters the frame (or, when the reservation did not fit, as it
            // is redrawn): the frame holds it now, and a copy left behind would be the unbounded store of
            // pixels ADR 0052 refused. Counted as a hit only when the frame actually took it.
            cachedKeys.forEach(takeFromCache::remove)
            if (useRememberedRaw) {
                metricRecorder.record(RenGMetricName.RENDERED_TILE_CACHE_HITS, cachedKeys.size.toLong())
                if (memo != null) cachedHits.forEach { memo[it.key] = it }
            }
        }
        if (missing.isEmpty()) return alreadyRendered
        // The batch owns engine-side resources and is closed as soon as the pixels are in hand: nothing
        // downstream of here reads it, because rendering is where a PreparedBatch's whole purpose ends.
        // `missing`, not `canonicalTiles`, on both halves: ADR 0044's raw budget is charged for the
        // tiles this frame actually rasterises rather than every tile it draws, which is where that
        // budget belongs now that a resident tile is never asked for (ADR 0046).
        val tilesAskedFor = missing.size
        val preparedFresh = prepareFreshTiles(style, missing)
        if (missing.size != tilesAskedFor) {
            // ADR 0057's failed tiles left `missing`, and the reservation above was taken for them too.
            // ADR 0044 holds a frame's reservation to exactly the raw pixels it carries (the frame's own
            // `init` enforces it), so give the difference back now, before anything is rendered. The
            // same arithmetic that sized it: what the remembered tiles hold if the frame keeps them raw,
            // plus the tiles still to rasterise if they are rendered raw. Zero is a legal reservation.
            rawReservation?.shrinkTo(
                (if (useRememberedRaw) rememberedRawBytes else 0L) +
                    (if (renderFreshAsRaw) checkNotNull(checkedRawTileBytes(missing.size)) else 0L),
            )
        }
        if (preparedFresh == null) return alreadyRendered
        val freshlyRendered = preparedFresh.use { prepared ->
            basemapEngineHost.renderTiles(
                prepared,
                asRawPixels = renderFreshAsRaw,
                // The foreground goes first (ADR 0077): with a read-ahead worker drawing at NORMAL in
                // the same gate, the frame being prepared is the one thing that must never wait for it.
                priority = if (cache != null) RenGRenderPriority.URGENT else priority,
            )
        }
        if (memo != null) freshlyRendered.forEach { memo[it.key] = it }
        return if (alreadyRendered.isEmpty()) freshlyRendered else alreadyRendered + freshlyRendered
    }

    /**
     * Acquires [missing] for rasterising, or answers `null` when nothing is left to rasterise.
     *
     * With [RendererConfiguration.presentFailedTilesProvisionally] off this is exactly
     * [BasemapEngineHost.prepareTiles], and fails exactly as it does. On, it is ADR 0057's "A tile that
     * could not be downloaded": a preparation whose only failure is colour tiles the engine could not
     * acquire drops those of them that have a resident ancestor from [missing] and asks for the rest once
     * more, and `resolveGroundTiles` then draws the dropped ones from their ancestors through the same
     * `colourWindow` a budget-deferred tile uses, reporting `GROUND_PRESENTED_PROVISIONALLY` the same way.
     *
     * **Why this exists.** Without it a tile that cannot download freezes every view that needs it,
     * though a coarser tile over it is resident on the GPU (ADR 0057 records the case).
     *
     * **All-or-nothing, deliberately.** If any tile the failure names has no resident ancestor, the
     * original failure is rethrown unchanged rather than the presentable ones being dropped and the
     * frame failing on the next request instead: the frame fails either way, and failing with the
     * failure it fails with today keeps the consumer's error handling exactly as it is. A frame that
     * cannot draw its ground is not an improvement on one that fails.
     *
     * **Once, deliberately.** The second request is [BasemapEngineHost.prepareTiles] itself, and whatever
     * it throws is the frame's failure. It costs no network for the tiles that succeeded the first
     * time: this invocation's firewall registry latched their responses and answers the engine's second
     * request from them. Nor does a failed route get a second attempt within the frame, because the tile
     * it belonged to is no longer asked for. The next `prepare` asks for it again: the tile is still not
     * resident, so refinement is ADR 0057's ordinary one and needs nothing here.
     *
     * The raw-tile reservation the caller took covered the tiles this leaves out, so the caller shrinks it
     * as soon as this returns with [missing] smaller: a frame's reservation must cover exactly the raw
     * pixels it carries (ADR 0044), and `RenGPreparedFrame`'s `init` refuses a frame for which it does not.
     */
    private suspend fun prepareFreshTiles(
        style: PreparedStyle,
        missing: MutableList<CanonicalBasemapTile>,
    ): PreparedBasemapTiles? {
        if (!configuration.presentFailedTilesProvisionally) return basemapEngineHost.prepareTiles(style, missing)
        val outcome = basemapEngineHost.prepareTilesReportingUnavailableColour(style, missing)
        val unavailable = when (outcome) {
            is BasemapTilePreparation.Prepared -> return outcome.tiles
            is BasemapTilePreparation.ColourUnavailable -> outcome
        }
        val presentable = presentableFailedTiles(
            failed = unavailable.tiles,
            requested = missing,
            hasResidentAncestor = { tile -> residentAncestorOf(style.digest, tile) != null },
        ) ?: throw unavailable.failure
        missing.removeAll(presentable)
        val retried = if (missing.isEmpty()) null else basemapEngineHost.prepareTiles(style, missing)
        // Counted only once the frame is going to prepare: a frame that failed on the second request
        // presents nothing, and a counter that said otherwise would be counting attempts, not frames.
        metricRecorder.record(RenGMetricName.TILES_PRESENTED_AFTER_FAILURE, presentable.size.toLong())
        return retried
    }

    /**
     * The nearest ancestor of [tile] whose rendered texture is already resident, with the window
     * [tile] occupies inside it — or `null` when no ancestor is resident (ADR 0057).
     *
     * Searched from the nearest upward, so a frame presents the sharpest ground it has rather than
     * the first it finds. The search stops at the root: there is no tile above LOD 0.
     *
     * Nothing here fetches, renders or guesses. A tile with no resident ancestor is rasterised
     * exactly as it always was.
     */
    internal fun residentAncestorOf(
        styleDigest: String,
        tile: CanonicalBasemapTile,
    ): Pair<ResourceKey, GroundTileWindow>? {
        for (levels in 1..tile.lod) {
            val ancestor = ancestorOf(tile, levels) ?: return null
            val key = basemapEngineHost.renderedTileKey(styleDigest, ancestor)
            if (glObjectRegistry.resident(key) != null) return key to windowWithin(tile, levels)
        }
        return null
    }

    /**
     * Whether [tileCount] tiles of raw pixels still fit under
     * [ResourceLimits.maximumInFlightRawBasemapTileBytes], counting what open Prepared Frames already
     * hold (ADR 0044).
     *
     * The estimate is exact rather than a guess: Rentile renders square tiles at the configured
     * output size, so a tile is `size * size * 4` bytes and the whole batch is that times its tile
     * count, all of it known before the render call. Answering `false` costs the frame an encode and
     * a decode -- the behaviour of every release before ADR 0044 -- and never fails it.
     */
    internal fun checkedRawTileBytes(tileCount: Int): Long? {
        if (tileCount < 0) return null
        val side = basemapEngineHost.tileOutputSizePixels.toLong()
        if (side < 0L || side > Long.MAX_VALUE / side.coerceAtLeast(1L)) return null
        val pixels = side * side
        if (pixels > Long.MAX_VALUE / RGBA_BYTES_PER_PIXEL) return null
        val perTile = pixels * RGBA_BYTES_PER_PIXEL
        return if (tileCount.toLong() > Long.MAX_VALUE / perTile.coerceAtLeast(1L)) {
            null
        } else {
            perTile * tileCount.toLong()
        }
    }

    private fun applyRasterisationBudget(
        missing: MutableList<CanonicalBasemapTile>,
        styleDigest: String,
    ) {
        val budget = configuration.resourceLimits.maximumTilesRasterisedPerFrame
        if (missing.size <= budget) return
        val deferred = missing.drop(budget).filter { tile -> residentAncestorOf(styleDigest, tile) != null }
        if (deferred.isNotEmpty()) missing.removeAll(deferred.toSet())
    }
}

private fun RenderedBasemapTile.rawPixelBytes(): Long = when (val value = pixels) {
    is BasemapTilePixels.Raw -> value.rgba.size.toLong()
    is BasemapTilePixels.Encoded -> 0L
}
