package com.rohittp.reng

import com.rohittp.reng.internal.firewall.AcquiredDemTile
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.terrain.DemEncoding
import com.rohittp.reng.internal.terrain.DemTexels
import com.rohittp.reng.internal.terrain.DemTileCoordinate
import com.rohittp.reng.internal.terrain.GroundSurface
import com.rohittp.reng.internal.terrain.GroundSurfaceTile
import com.rohittp.reng.internal.terrain.canPadDemTexture
import com.rohittp.reng.internal.terrain.demTileWindowFor

/**
 * One frame's terrain: everything the ground needs to displace itself, decoded but not yet padded and
 * not yet on the GPU.
 *
 * **Nothing here decodes anything, and until Rentile `0.7.0` that sentence was the opposite.** The
 * encoded bytes used to be carried to the draw and inflated there by RenG's own PNG decoder, which
 * cost a decode of every DEM in every frame's request -- the visible set plus its perimeter ring --
 * even when every padded texture was already resident, and which reached exactly **one** of the six
 * corpus styles that ask for terrain, because the other five serve WebP. The engine now hands over
 * the pixels it decoded to validate the tile, so that debt is not paid down but deleted: there is no
 * decode left to skip.
 *
 * **What is still per-frame is the padding**, because `uploadDemTexture` consults residency only
 * after it is handed an assembled `PaddedDemTexture`. The repair `PaddedDemTexture.contentKey`'s own
 * KDoc names -- composing that key from the nine digests, which are known here, before assembling
 * anything -- is now fully available and is not taken in this task.
 *
 * [demTiles] is keyed by **requested** canonical tile, which is how the acquisition matched it
 * (ADR 0041: never by list index). Its `sourceTile` may differ -- an overzoom or a world wrap -- and
 * [texelsBySource] is therefore keyed by the source, since several requested tiles can share one DEM
 * image and one padded texture.
 */
internal class PreparedTerrain(
    internal val encoding: DemEncoding,
    internal val tileSizePx: Int,
    internal val exaggeration: Double,
    demTiles: Map<CanonicalBasemapTile, AcquiredDemTile>,
) {
    private val demSnapshot: Map<CanonicalBasemapTile, AcquiredDemTile> = LinkedHashMap(demTiles)

    /**
     * One entry per distinct DEM image, which is what a padded texture is built around: several
     * requested tiles overzooming into one ancestor arrive as several entries carrying the identical
     * texels and the identical digest.
     */
    internal val texelsBySource: Map<DemTileCoordinate, DemTexels> =
        LinkedHashMap<DemTileCoordinate, DemTexels>().apply {
            demSnapshot.values.forEach { dem -> if (dem.sourceTile !in this) put(dem.sourceTile, dem.texels) }
        }

    /**
     * Every ground tile this frame can actually displace, and the whole of what ADR 0041's coverage
     * diagnostic counts against.
     *
     * **This is the answer the draw would otherwise have reached alone and silently.** A tile
     * displaces only if it has a window onto its source ([demTileWindowFor]) and a source that can be
     * padded ([canPadDemTexture]); both are pure functions of what this class already holds, so the
     * frame can know its own coverage during `prepare()`, where the diagnostic fires, instead of
     * discovering it at draw time where nothing was listening. Task 13's harness pass printed
     * `diagnostics: none` for a frame that discarded every DEM it fetched, and this is why it could.
     *
     * **[canPadDemTexture] excludes nothing today, and is here on purpose.** Every tile that reached
     * this class came through `TerrainAcquisition.matchDemTiles`, which admits only a positive square
     * of exactly [tileSizePx]; a window then bounds the source's zoom below the padding's own bound
     * and the texels are keyed by the very source tiles being asked about. So all three of
     * [padDemTexture]'s refusals are already unreachable from here -- by construction, which is
     * precisely the kind of agreement that stops holding without saying so. Naming the probe means a
     * refusal added to [padDemTexture] later reaches the diagnostic instead of reaching the draw's
     * silence, which is the defect this whole task exists to close.
     *
     * `resolveDemTextures` pads and uploads exactly these tiles, so the report and the picture cannot
     * disagree.
     */
    internal val elevatedTiles: Set<CanonicalBasemapTile> =
        demSnapshot.filterValues { dem ->
            demTileWindowFor(dem.requestedTile, dem.sourceTile) != null &&
                canPadDemTexture(dem.sourceTile, texelsBySource)
        }.keys

    /** Every acquired DEM, visible tiles and ring alike, in request order. */
    internal val demTiles: Map<CanonicalBasemapTile, AcquiredDemTile> get() = LinkedHashMap(demSnapshot)

    /**
     * The same terrain as a CPU-readable surface, or `null` when nothing at [lod] displaces.
     *
     * **It is assembled from [elevatedTiles] and [demTileWindowFor], which is `resolveDemTextures`'
     * own pair**, so the surface a `GROUND_RELATIVE` altitude rides and the surface the GPU draws are
     * built from one answer about which tiles displace and one answer about where each one samples.
     * The two could otherwise disagree tile by tile, and the picture of that disagreement -- content
     * riding a surface the ground is not drawing -- looks exactly like a lookup that is merely
     * imprecise.
     *
     * [texelsBySource] is handed over whole rather than filtered to the displacing tiles, because the
     * ring is what a padded lookup at a tile's far edge reads and a filtered map would replicate the
     * tile's own edge there instead -- reopening, for the drape alone, the seam
     * [com.rohittp.reng.internal.terrain.padDemTexture] closed for the ground.
     */
    internal fun groundSurface(lod: Int): GroundSurface? {
        val tiles = LinkedHashMap<DemTileCoordinate, GroundSurfaceTile>(elevatedTiles.size)
        elevatedTiles.forEach { tile ->
            if (tile.lod != lod) return@forEach
            val dem = demSnapshot[tile] ?: return@forEach
            val window = demTileWindowFor(dem.requestedTile, dem.sourceTile) ?: return@forEach
            tiles[DemTileCoordinate(z = tile.lod, x = tile.canonicalX, y = tile.tileY)] =
                GroundSurfaceTile(source = dem.sourceTile, window = window)
        }
        if (tiles.isEmpty()) return null
        return GroundSurface(
            lod = lod,
            interiorSizePx = tileSizePx,
            exaggeration = exaggeration,
            encoding = encoding,
            tiles = tiles,
            texelsBySource = texelsBySource,
        )
    }

    internal fun demTileFor(tile: CanonicalBasemapTile): AcquiredDemTile? = demSnapshot[tile]

    override fun toString(): String =
        "PreparedTerrain(encoding=$encoding, tileSizePx=$tileSizePx, exaggeration=$exaggeration, " +
            "dem=${demSnapshot.size}, elevated=${elevatedTiles.size})"
}
