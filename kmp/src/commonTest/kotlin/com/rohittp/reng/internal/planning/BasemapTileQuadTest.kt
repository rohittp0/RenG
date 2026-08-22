package com.rohittp.reng.internal.planning

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ground quad's placement maths, in camera-relative logical pixels — the one computation that
 * decides where every basemap tile lands on screen, and the one an x/y transposition or a sign flip
 * corrupts silently.
 *
 * Every expected value here is hand-derived rather than recomputed from the function under test.
 * The camera is deliberately at `(0, 0)` zoom 4 for this file only, because that is the one camera
 * whose Mercator anchor is an exact binary fraction (`x = y = 0.5`, so `anchorTile = 8.0` at LOD 4)
 * and therefore the one where a whole-number expectation is legitimate. The *readback* fixture uses
 * the asymmetric `(-55, -135)` camera instead, for the transposition reason recorded there.
 */
class BasemapTileQuadTest {

    @Test
    fun aTileSideIsTheWorldWidthDividedByTheTileCountAtItsLod() {
        val quad = resolveBasemapTileQuad(instance(tileY = 8, unwrappedX = 8L), nullIslandCamera())
        // worldSizeLogicalPixels = 512 * 2^4 = 8192; tileCount at LOD 4 = 16; 8192 / 16 = 512.
        assertEquals(512.0, quad.sideLogicalPixels)
    }

    @Test
    fun theTileTheCameraSitsInIsHalfATileEastAndHalfATileSouthOfTheAnchor() {
        // The anchor is at tile (8.0, 8.0); tile (8, 8)'s centre is at tile (8.5, 8.5). East is +x
        // and NORTH is +y, so a centre half a tile south of the anchor has a NEGATIVE y.
        val quad = resolveBasemapTileQuad(instance(tileY = 8, unwrappedX = 8L), nullIslandCamera())
        assertEquals(256.0, quad.centreXLogicalPixels)
        assertEquals(-256.0, quad.centreYLogicalPixels)
    }

    @Test
    fun aTileNorthAndWestOfTheAnchorIsPlacedNorthAndWestOfIt() {
        val quad = resolveBasemapTileQuad(instance(tileY = 7, unwrappedX = 7L), nullIslandCamera())
        assertEquals(-256.0, quad.centreXLogicalPixels)
        assertEquals(256.0, quad.centreYLogicalPixels)
    }

    /**
     * The x/y transposition guard at the level it originates. Tile `(x = 9, y = 3)` and its
     * transpose `(x = 3, y = 9)` must land in different places; a resolver that read `tileY` as the
     * horizontal index would place them identically, and no call-log assertion anywhere could see it.
     */
    @Test
    fun aTileAndItsOwnTransposeAreNeverPlacedInTheSameSpot() {
        val camera = nullIslandCamera()
        val original = resolveBasemapTileQuad(instance(tileY = 3, unwrappedX = 9L), camera)
        val transposed = resolveBasemapTileQuad(instance(tileY = 9, unwrappedX = 3L), camera)
        assertEquals(768.0, original.centreXLogicalPixels)
        assertEquals(2304.0, original.centreYLogicalPixels)
        assertEquals(-2304.0, transposed.centreXLogicalPixels)
        assertEquals(-768.0, transposed.centreYLogicalPixels)
        assertTrue(original.centreXLogicalPixels != transposed.centreXLogicalPixels)
        assertTrue(original.centreYLogicalPixels != transposed.centreYLogicalPixels)
    }

    /**
     * `BasemapTileSelector` emits one instance per **unwrapped** x, and `basemapTileKey`'s instance
     * overload deliberately projects the world copy away so N copies share one texture. Placement is
     * therefore the only place the copy may still be read, and it must be: two copies of the same
     * canonical tile sit exactly one world width apart.
     */
    @Test
    fun aWorldCopyInstanceIsPlacedExactlyOneWorldWidthFromItsCanonicalTile() {
        val camera = nullIslandCamera()
        val canonical = resolveBasemapTileQuad(instance(tileY = 8, unwrappedX = 8L), camera)
        val eastCopy = resolveBasemapTileQuad(
            instance(tileY = 8, unwrappedX = 24L, instanceCopy = 1),
            camera,
        )
        val westCopy = resolveBasemapTileQuad(
            instance(tileY = 8, unwrappedX = -8L, instanceCopy = -1),
            camera,
        )
        assertEquals(canonical.centreXLogicalPixels + 8192.0, eastCopy.centreXLogicalPixels)
        assertEquals(canonical.centreXLogicalPixels - 8192.0, westCopy.centreXLogicalPixels)
        assertEquals(canonical.centreYLogicalPixels, eastCopy.centreYLogicalPixels)
        assertEquals(canonical.centreYLogicalPixels, westCopy.centreYLogicalPixels)
    }

    @Test
    fun aFinerLodPlacesFourTilesWhereTheCoarserOnePlacedOne() {
        val camera = nullIslandCamera()
        val coarse = resolveBasemapTileQuad(instance(lod = 4, tileY = 8, unwrappedX = 8L), camera)
        val fine = resolveBasemapTileQuad(instance(lod = 5, tileY = 16, unwrappedX = 16L), camera)
        assertEquals(256.0, fine.sideLogicalPixels)
        assertEquals(coarse.sideLogicalPixels / 2.0, fine.sideLogicalPixels)
        // Tile (16, 16) at LOD 5 is the north-west quarter of tile (8, 8) at LOD 4, so its centre is
        // a quarter of a coarse tile north-west of the coarse centre.
        assertEquals(coarse.centreXLogicalPixels - 128.0, fine.centreXLogicalPixels)
        assertEquals(coarse.centreYLogicalPixels + 128.0, fine.centreYLogicalPixels)
    }

    private fun instance(
        lod: Int = 4,
        tileY: Int,
        unwrappedX: Long,
        instanceCopy: Int = 0,
    ): BasemapTileInstance {
        val tileCount = 1L shl lod
        val copy = if (unwrappedX >= 0) unwrappedX / tileCount else (unwrappedX - tileCount + 1) / tileCount
        return BasemapTileInstance(
            lod = lod,
            tileY = tileY,
            unwrappedX = unwrappedX,
            instanceCopy = if (instanceCopy != 0) instanceCopy else copy.toInt(),
            canonicalX = (unwrappedX - copy * tileCount).toInt(),
        )
    }

    /** Latitude 0, longitude 0: the one camera whose Mercator anchor is exactly `(0.5, 0.5)`. */
    private fun nullIslandCamera(): ResolvedMercatorCamera = (
        resolveMercatorCamera(
            camera = Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 4.0, bearing = 0.0, pitch = 0.0),
            outputPixelSize = OutputPixelSize(width = 128, height = 128),
        ) as SpatialOutcome.Success
        ).value
}
