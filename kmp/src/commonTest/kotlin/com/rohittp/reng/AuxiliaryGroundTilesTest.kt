package com.rohittp.reng

import com.rohittp.reng.internal.planning.BasemapTileInstance
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.planning.GroundCap
import com.rohittp.reng.internal.planning.TileSelectionOutcome
import com.rohittp.reng.internal.planning.WORLD_BASEMAP_TILE
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR 0094's world tile is optional only when the frame does not also draw it: at LOD 0 it is the
 * ground itself, and an optional ground tile would let an export encode a hole.
 */
class AuxiliaryGroundTilesTest {
    @Test
    fun theWorldTileIsAuxiliaryWhenOnlyTheCapsNeedIt() {
        val northCap = instance(lod = 1, tileY = 0, canonicalX = 0, cap = GroundCap.NORTH)
        val selection = TileSelectionOutcome.Success(
            instances = listOf(northCap),
            canonicalResources = listOf(
                CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 0),
                WORLD_BASEMAP_TILE,
            ),
        )

        assertEquals(setOf(WORLD_BASEMAP_TILE), auxiliaryGroundTilesOf(selection))
    }

    @Test
    fun theWorldTileIsRequiredWhenTheFrameDrawsItAsGround() {
        val selection = TileSelectionOutcome.Success(
            instances = listOf(
                instance(lod = 0, tileY = 0, canonicalX = 0, cap = null),
                instance(lod = 0, tileY = 0, canonicalX = 0, cap = GroundCap.NORTH),
            ),
            canonicalResources = listOf(WORLD_BASEMAP_TILE),
        )

        assertEquals(emptySet(), auxiliaryGroundTilesOf(selection))
    }

    @Test
    fun nothingIsAuxiliaryWithoutCaps() {
        val selection = TileSelectionOutcome.Success(
            instances = listOf(instance(lod = 3, tileY = 2, canonicalX = 5, cap = null)),
            canonicalResources = listOf(CanonicalBasemapTile(lod = 3, tileY = 2, canonicalX = 5)),
        )

        assertEquals(emptySet(), auxiliaryGroundTilesOf(selection))
        assertEquals(emptySet(), auxiliaryGroundTilesOf(selection = null))
    }

    private fun instance(lod: Int, tileY: Int, canonicalX: Int, cap: GroundCap?): BasemapTileInstance =
        BasemapTileInstance(
            lod = lod,
            tileY = tileY,
            unwrappedX = canonicalX.toLong(),
            instanceCopy = 0,
            canonicalX = canonicalX,
            cap = cap,
        )
}
