package com.rohittp.reng

import com.rohittp.reng.internal.cache.Lease
import com.rohittp.reng.internal.firewall.BasemapTilePixels
import com.rohittp.reng.internal.firewall.RenderedBasemapTile
import com.rohittp.reng.internal.gl.Scene
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.lifecycle.PreparedFrameFact
import com.rohittp.reng.internal.planning.DrawnThingReference
import com.rohittp.reng.internal.preparation.PreparedFrameCpuBudget
import com.rohittp.reng.internal.preparation.RawTileBudget
import com.rohittp.reng.internal.terrain.GroundSurface
import com.rohittp.reng.internal.thread.PlatformLock

/**
 * The concrete [PreparedFrame] this cycle's factory produces. Deliberately thin: it retains exactly
 * what [RenGRenderer.draw] needs to assemble a [Scene] — the raw [Camera] value, each sticker's
 * already-decoded image, and each geometry's already-snapshotted uniforms/textures — and nothing
 * GL-shaped, since GPU texture upload and geometry-pipeline compilation both need a live render
 * context and therefore happen in [RenGRenderer.draw], not here.
 *
 * **[geometries] never retains a consumer's live `Map`.** Every [PreparedGeometry] carries a fixed
 * `.toMap()` snapshot of `uniforms` and fully resolved, already-decoded `consumerTextures`, both taken
 * once, synchronously, inside `RenGRenderer.prepare()`, so a consumer mutating their own
 * `uniforms`/`textures` map after `prepare()` returns cannot change what a later `draw()` of this frame
 * renders.
 */
internal class RenGPreparedFrame(
    owner: RenGRenderer,
    internal val ownerIdentity: Any,
    override val frameIndex: Long,
    internal val camera: Camera,
    /**
     * Which projection the plan asked for, carried because [camera] alone cannot say: [Camera]'s
     * five fields mean the same thing in both modes and it is the *mode* that decides whether they
     * describe a plane or a sphere (ADR 0037). Without it the draw would have to guess, and the
     * guess it would make is the one that draws a globe frame flat.
     */
    internal val projectionMode: ProjectionMode,
    internal val drawBasemap: Boolean,
    /**
     * Carried beside [drawBasemap] because a Prepared Frame records the whole plan's draw switches,
     * not the subset the current draw happens to consult, and orthogonal to [drawBasemap] rather than
     * implied by it. The draw does not consult it: by the time a frame exists, `prepare()` has already
     * answered the flag by leaving [labels] `null`, and re-reading a switch whose consequence is
     * already decided is how two authorities on one rule get created.
     */
    internal val drawLabels: Boolean,
    /**
     * The compiled style this frame's ground tiles were rendered from, or `null` when it rendered
     * none.
     *
     * Carried for the reason `FrameAcquisition` gives for carrying it out of acquisition at all: the
     * renderer's own `preparedBasemapStyle` describes the *most recently* prepared frame, which for a
     * frame prepared earlier and drawn later is a different style entirely. A draw resolving an
     * ancestor tile (ADR 0057) needs the digest of the style **this** frame used, because a tile key
     * is a function of it and a key cannot be walked back to one.
     */
    internal val basemapStyleDigest: String?,
    /** The style digest used by the optional second ground pass (ADR 0086). */
    internal val blendBasemapStyleDigest: String? = null,
    stickers: List<PreparedSticker>,
    geometries: List<PreparedGeometry>,
    /**
     * This frame's models, each already decoded, posed and skinned for exactly this frame — see
     * [PreparedModel] for why every one of those derivations happened during `prepare()` rather than
     * being deferred to the draw.
     */
    models: List<PreparedModel>,
    /**
     * This frame's ground, already rendered by the Rentile engine and already named by RenG's own
     * canonical identity (ADR 0018) — one entry per **canonical** tile, so N Mercator world copies of one
     * tile share one entry, one engine render and one later texture upload. Empty whenever the frame
     * draws no basemap, no style is configured, or the frame selected no tile.
     *
     * The bytes are either the engine's PNG or its already-premultiplied pixels, whichever ADR 0044's
     * budget admitted when this frame was prepared. Either way they are uploaded in
     * [RenGRenderer.performDraw] rather than here, because a tile whose GL texture is still resident
     * from an earlier frame must cost nothing: `prepare()` has no render context, and touching every
     * tile of every frame unconditionally is precisely the per-frame cliff the resident texture budget
     * exists to avoid.
     */
    basemapTiles: List<RenderedBasemapTile>,
    /**
     * Where this frame's ground goes — one entry per **unwrapped** draw instance, so N Mercator world
     * copies of one canonical tile are N placements sharing one entry in [basemapTiles]. Empty exactly
     * when [basemapTiles] is empty.
     */
    groundInstances: List<PreparedGroundInstance> = emptyList(),
    /** The same tile selection keyed for [blendBasemapStyleDigest] (ADR 0086). */
    blendGroundInstances: List<PreparedGroundInstance> = emptyList(),
    /**
     * The LOD `observeMercatorLod` chose for this frame, which is **not** the same thing as the LOD
     * of any particular ground tile since ADR 0065: the ground is banded, and a band's level is an
     * offset from this one. Terrain reads this rather than a tile's, because a [GroundSurface] is
     * built at a single level by construction and the frame's own is the only level with a claim to
     * be it -- the centre band always carries offset zero.
     */
    internal val groundSelectedLod: Int? = null,
    /** What this frame paints behind everything else, or `null` for none (ADR 0068). */
    backdrop: PreparedBackdrop? = null,
    /** The frame's screen-space bloom parameters, or `null` when the pass is absent. */
    bloom: Bloom? = null,
    /** The mask for the optional second ground pass. */
    basemapBlend: BasemapBlend? = null,
    /**
     * This frame's terrain, or `null` when its style declared none, when RenG and the engine did not
     * name the same source, or when the acquisition failed — the last two being ADR 0041's two
     * degradations, which have already been reported by the time a frame exists and leave the ground
     * flat here.
     */
    terrain: PreparedTerrain? = null,
    /**
     * The CPU-readable view of the surface this frame's ground draws, or `null` when nothing in the
     * frame asked where the ground is — see `RenGRenderer.groundSurfaceFor` for that condition.
     *
     * **It is built during `prepare()` and carried rather than derived at draw time, and that is the
     * one thing about it worth reading twice.** Labels are placed and collided inside `prepare()`
     * (ADR 0035), so their anchors have already ridden this exact object by the time a frame exists;
     * a `GROUND_RELATIVE` sticker, model or geometry rides it during the draw. Deriving it twice
     * would duplicate its tile indexes and create two independent answers to which of this frame's
     * tiles displace — and the picture of that disagreement is content riding a surface the ground
     * is not drawing, which looks exactly like a lookup that is merely imprecise. Pixel storage is
     * shared directly through immutable [DecodedImage] references; no raster copy is made here.
     */
    groundSurface: GroundSurface? = null,
    /**
     * This frame's labels, or `null` when it drew none — `drawLabels = false`, no configured style, no
     * selected tile, a style that declares no text, or a frame every candidate of which lost its place.
     *
     * Unlike [basemapTiles] this is a *result* rather than raw material: ADR 0035 puts collision and
     * fade inside `prepare()`, so by the time a frame exists its labels are already decided. The draw
     * uploads the atlas and issues the batch; it places nothing.
     */
    labels: PreparedLabelFrame? = null,
    /**
     * Which draw regime each of this frame's drawn things is in, and in what order — taken straight
     * off `MercatorSpatialPlan.mapEntries` / `.screenEntries` at `prepare()` time and carried, never
     * re-derived at draw time.
     *
     * Snapshotting them here makes the planner the one authority for the regime split and the
     * screen-compositing sort: `internal.gl.Scene` consumes these lists and derives neither, so no
     * second resolution of a `Placement` can disagree with the planner's.
     *
     * [mapOrder] is the planner's declaration order (stickers, then models) and is a statement of
     * regime membership rather than a draw order — ADR 0030's phase order is applied over it by
     * `SceneContent`. [screenOrder] is already sorted into `CONTEXT.md`'s compositing order and is a
     * complete draw order.
     */
    mapOrder: List<DrawnThingReference> = emptyList(),
    screenOrder: List<DrawnThingReference> = emptyList(),
    rawTileReservation: RawTileBudget.Reservation? = null,
    blendRawTileReservation: RawTileBudget.Reservation? = null,
    preparedFrameCpuReservation: PreparedFrameCpuBudget.Reservation,
    /**
     * Every resident-cache lease this frame's own preparation took, which `close()` releases exactly once.
     *
     * `CONTEXT.md` defines a lease as held "by a **Prepared Frame** for every distinct resource its plan
     * needs", and this is where that ownership actually lives: the preparation driver collects the leases
     * it takes into the list handed here. Without it every lease a preparation took stayed outstanding for
     * the renderer's whole life, so `freeResources()` could only ever report a matched key deferred and a
     * superseded generation was never reclaimed -- an unbounded leak for a consumer that prepares many
     * frames.
     */
    leases: List<Lease> = emptyList(),
) : PreparedFrame {
    private var stickerSnapshot: List<PreparedSticker> = ArrayList(stickers)
    private var geometrySnapshot: List<PreparedGeometry> = ArrayList(geometries)
    private var modelSnapshot: List<PreparedModel> = ArrayList(models)
    private var basemapTileSnapshot: List<RenderedBasemapTile> = ArrayList(basemapTiles)
    private var groundInstanceSnapshot: List<PreparedGroundInstance> = ArrayList(groundInstances)
    private var blendGroundInstanceSnapshot: List<PreparedGroundInstance> =
        if (blendGroundInstances.isEmpty()) emptyList() else ArrayList(blendGroundInstances)
    private var mapOrderSnapshot: List<DrawnThingReference> = ArrayList(mapOrder)
    private var screenOrderSnapshot: List<DrawnThingReference> = ArrayList(screenOrder)
    private var backdropSnapshot: PreparedBackdrop? = backdrop
    private var bloomSnapshot: Bloom? = bloom
    private var basemapBlendSnapshot: BasemapBlend? = basemapBlend
    private var terrainSnapshot: PreparedTerrain? = terrain
    private var groundSurfaceSnapshot: GroundSurface? = groundSurface
    private var labelSnapshot: PreparedLabelFrame? = labels
    private var closeOwner: RenGRenderer? = owner
    internal var registryPrevious: RenGPreparedFrame? = null
    internal var registryNext: RenGPreparedFrame? = null
    internal var isRegistered: Boolean = false
    private val ownershipLock = PlatformLock()

    /**
     * Preallocated while preparation still owns all rollback inputs. Closing must remain possible when
     * the process is already under memory pressure, so the terminal ownership swap below performs no
     * list copy or payload allocation.
     */
    private var closePayload: PreparedFrameClosePayload? = PreparedFrameClosePayload(
        leases = ArrayList(leases),
        rawTileReservation = rawTileReservation,
        blendRawTileReservation = blendRawTileReservation,
        preparedFrameCpuReservation = preparedFrameCpuReservation,
    )

    // Each list snapshot is a private copy made at construction and never mutated afterwards -- close
    // replaces it with an empty list rather than clearing it -- so a read hands out the snapshot itself:
    // a reader that outlives a close keeps the contents it read, exactly as a copy would.
    internal val stickers: List<PreparedSticker> get() = ownershipLock.withLock { stickerSnapshot }
    internal val geometries: List<PreparedGeometry> get() = ownershipLock.withLock { geometrySnapshot }
    internal val models: List<PreparedModel> get() = ownershipLock.withLock { modelSnapshot }
    internal val basemapTiles: List<RenderedBasemapTile> get() = ownershipLock.withLock { basemapTileSnapshot }
    internal val groundInstances: List<PreparedGroundInstance> get() = ownershipLock.withLock {
        groundInstanceSnapshot
    }
    internal val blendGroundInstances: List<PreparedGroundInstance> get() = ownershipLock.withLock {
        blendGroundInstanceSnapshot
    }
    internal val mapOrder: List<DrawnThingReference> get() = ownershipLock.withLock { mapOrderSnapshot }
    internal val screenOrder: List<DrawnThingReference> get() = ownershipLock.withLock { screenOrderSnapshot }
    internal val backdrop: PreparedBackdrop? get() = ownershipLock.withLock { backdropSnapshot }
    internal val bloom: Bloom? get() = ownershipLock.withLock { bloomSnapshot }
    internal val basemapBlend: BasemapBlend? get() = ownershipLock.withLock { basemapBlendSnapshot }
    internal val terrain: PreparedTerrain? get() = ownershipLock.withLock { terrainSnapshot }
    internal val groundSurface: GroundSurface? get() = ownershipLock.withLock { groundSurfaceSnapshot }
    internal val labels: PreparedLabelFrame? get() = ownershipLock.withLock { labelSnapshot }
    internal val retainsCloseOwner: Boolean get() = ownershipLock.withLock { closeOwner != null }

    /**
     * How many bytes of raw, not-yet-uploaded tile pixels this frame holds (ADR 0044); zero for a
     * frame whose tiles are encoded.
     *
     * Derived from the tiles rather than passed in beside them, so the figure the renderer subtracts
     * at [RenGRenderer.closePreparedFrame] cannot disagree with what this frame actually carries.
     */
    internal val rawTileBytes: Long = basemapTileSnapshot.sumOf { tile ->
        when (val pixels = tile.pixels) {
            is BasemapTilePixels.Raw -> pixels.rgba.size.toLong()
            is BasemapTilePixels.Encoded -> 0L
        }
    }

    init {
        val reservedRawTileBytes = (rawTileReservation?.bytes ?: 0L) +
            (blendRawTileReservation?.bytes ?: 0L)
        require(reservedRawTileBytes == rawTileBytes) {
            "raw tile reservations must exactly cover the frame's raw pixels"
        }
    }

    internal fun fact(): PreparedFrameFact = ownershipLock.withLock {
        if (closePayload == null) PreparedFrameFact.OwnedClosed else PreparedFrameFact.OwnedOpen
    }

    /** Atomically closes and drains every CPU-side ownership token exactly once. */
    internal fun takeClosePayload(): PreparedFrameClosePayload? = ownershipLock.withLock {
        val payload = closePayload ?: return@withLock null
        closePayload = null
        payload
    }

    /** Drops all terminal references after draw/close serialization, without retaining list capacity. */
    internal fun clearHeavyweightContent() = ownershipLock.withLock {
        stickerSnapshot = emptyList()
        geometrySnapshot = emptyList()
        modelSnapshot = emptyList()
        basemapTileSnapshot = emptyList()
        groundInstanceSnapshot = emptyList()
        blendGroundInstanceSnapshot = emptyList()
        mapOrderSnapshot = emptyList()
        screenOrderSnapshot = emptyList()
        backdropSnapshot = null
        bloomSnapshot = null
        basemapBlendSnapshot = null
        terrainSnapshot = null
        groundSurfaceSnapshot = null
        labelSnapshot = null
        closeOwner = null
    }

    override fun close() {
        ownershipLock.withLock { closeOwner }?.closePreparedFrame(this)
    }
}
