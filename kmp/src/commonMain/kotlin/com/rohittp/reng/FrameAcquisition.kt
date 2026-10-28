package com.rohittp.reng

import com.rohittp.reng.internal.basemap.BasemapStyleManifest
import com.rohittp.reng.internal.cache.ResidentGeneration
import com.rohittp.reng.internal.firewall.AcquiredLabelCandidates
import com.rohittp.reng.internal.firewall.RenderedBasemapTile
import com.rohittp.reng.internal.firewall.SpriteAtlasManifest
import com.rohittp.reng.internal.firewall.TerrainAcquisitionOutcome
import com.rohittp.reng.internal.image.CompressedImage
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.rentile.PreparedStyle

/** One configured style compiled and manifested by the first foreground preparation (ADR 0086). */
internal class AcquiredBasemapStyle(
    val prepared: PreparedStyle,
    val manifest: BasemapStyleManifest,
)

internal class CommittedBasemapStyleUpdate(
    val index: Int,
    val acquired: AcquiredBasemapStyle,
)

/**
 * Everything one `prepare()` acquired through the consumer's adapters: the decoded images the frame's
 * stickers and geometry textures need, and the ground the engine rendered for it. Two results rather
 * than one because they are two different kinds of thing — a `Map` keyed for lookup by the caller, and
 * an ordered list carried whole onto the prepared frame.
 */
internal class FrameAcquisition(
    val decodedImagesByKey: Map<ResourceKey, DecodedImage>,
    /** The sticker images that arrived as ETC2 containers, which are admitted rather than decoded (ADR 0084). */
    val compressedImagesByKey: Map<ResourceKey, CompressedImage>,
    val basemapTiles: List<RenderedBasemapTile>,
    val preparedStyle: PreparedStyle?,
    /**
     * The compiled style [basemapTiles] were rendered from, or `null` when this frame rendered none.
     * Carried out of acquisition rather than read back off the renderer, because `basemapTileKey` is a
     * function of it and `preparedBasemapStyle` describes the *most recently* prepared frame — which,
     * for a frame prepared earlier and drawn later, is a different style entirely.
     */
    val basemapStyleDigest: String?,
    /** The blend target's style digest, or `null` when this frame has no basemap blend (ADR 0086). */
    val blendBasemapStyleDigest: String? = null,
    val blendPreparedStyle: PreparedStyle? = null,
    val blendStyleManifest: BasemapStyleManifest? = null,
    /**
     * This frame's engine-derived label candidates, or `null` when the frame asked for no labels, had
     * no style configured, or selected no tile to ask about. The batch is handed on unplaced: placement
     * and collision are `prepare()`'s next step, and both are pure functions of it and the camera.
     */
    val labelCandidates: AcquiredLabelCandidates? = null,
    /**
     * The sprite atlas manifest this frame's style declared, as the firewall's own joint sprite gate
     * parsed it, or `null` when this invocation held no jointly valid pair.
     *
     * **Carried out of acquisition because it cannot be read afterwards.** The `OperationRegistry`
     * holding it is discarded when the invocation terminates (ADR 0016), which is also what guarantees
     * one frame's icons can never resolve against another frame's atlas. It is the only route by which
     * `LabelIconRef.imageName` -- an opaque key into resources Rentile deliberately does not expose --
     * becomes atlas geometry.
     */
    val spriteAtlas: SpriteAtlasManifest? = null,
    /**
     * What this frame's terrain acquisition produced, or `null` when the frame rendered no ground at
     * all and therefore asked for none.
     *
     * Carried out of acquisition for [basemapStyleDigest]'s reason and one stronger: DEM tiles are
     * acquired inside the invocation, through its preregistered routes, and the registry that
     * authorised them is discarded when it ends (ADR 0016). It is `NotDeclared` for the 28 of 34
     * corpus styles with no `terrain` block, which is not a degradation and reports nothing.
     */
    val terrain: TerrainAcquisitionOutcome? = null,
    /**
     * The style's own `terrain.exaggeration`, carried beside [terrain] because the two are read from
     * two different documents and only meet here: the multiplier is RenG's reading of the style JSON
     * ([com.rohittp.reng.internal.basemap.BasemapStyleManifest.terrainExaggeration]), while
     * everything else about the source is the engine's compiled descriptor. Rentile ignores the
     * property entirely, so nothing in the descriptor could carry it.
     */
    val terrainExaggeration: Double = 1.0,
    /**
     * The completed style manifest this frame's ground and labels read, or `null` when the frame
     * needed none. Carried out so the read-ahead snapshot (ADR 0075) can be built from what this
     * `prepare` actually used, rather than asking the host's unlocked manifest map afterwards.
     */
    val styleManifest: BasemapStyleManifest? = null,
    /**
     * The rendered-tile keys `renderBasemapTiles` left out of [basemapTiles] because their texture was
     * resident when this frame selected them -- the tiles it chose to draw from the GPU rather than ask
     * the engine for. Empty when the frame rendered no ground.
     *
     * Carried out because residency is not a fact that stays true for the rest of `prepare`: a draw may
     * overlap preparation (CONTEXT.md), and that draw's byte-budget eviction can take an unleased
     * texture between selection and `groundInstances`. What this frame *decided* at selection is the
     * only stable thing to check the ground against; see `groundInstances`.
     */
    val residentAtSelection: Set<ResourceKey> = emptySet(),
    /**
     * The generation this frame holds a lease on, per resource key: what its images and models were
     * decoded from and what their GPU identities name. See acquireFrameResources.
     */
    val leasedGenerations: Map<ResourceKey, ResidentGeneration> = emptyMap(),
)
