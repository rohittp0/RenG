package com.rohittp.reng.internal.planning

import com.rohittp.reng.internal.projection.ClosedMercatorFootprint
import com.rohittp.reng.internal.projection.MercatorGroundPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame

class BasemapTileSelectorTest {
    @Test
    fun emptyFootprintSelectsNoInstancesOrResources() {
        val success = selectSuccess(ClosedMercatorFootprint.Empty, lod = 22, maximumInstances = 1)

        assertEquals(emptyList(), success.instances)
        assertEquals(emptyList(), success.canonicalResources)
    }

    @Test
    fun pointAtSharedVertexSelectsAllFourClosedCellsInRowMajorOrder() {
        val success = selectSuccess(
            ClosedMercatorFootprint.Point(MercatorGroundPoint(0.5, 0.5)),
            lod = 1,
        )

        assertEquals(
            listOf(
                instance(lod = 1, tileY = 0, unwrappedX = 0, copy = 0, canonicalX = 0),
                instance(lod = 1, tileY = 0, unwrappedX = 1, copy = 0, canonicalX = 1),
                instance(lod = 1, tileY = 1, unwrappedX = 0, copy = 0, canonicalX = 0),
                instance(lod = 1, tileY = 1, unwrappedX = 1, copy = 0, canonicalX = 1),
            ),
            success.instances,
        )
        assertEquals(
            listOf(
                CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 0),
                CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 1),
                CanonicalBasemapTile(lod = 1, tileY = 1, canonicalX = 0),
                CanonicalBasemapTile(lod = 1, tileY = 1, canonicalX = 1),
            ),
            success.canonicalResources,
        )
    }

    @Test
    fun segmentOnSharedEdgeSelectsBothAdjacentClosedCells() {
        val success = selectSuccess(
            ClosedMercatorFootprint.Segment(
                start = MercatorGroundPoint(0.5, 0.6),
                end = MercatorGroundPoint(0.5, 0.9),
            ),
            lod = 1,
        )

        assertEquals(
            listOf(
                instance(lod = 1, tileY = 1, unwrappedX = 0, copy = 0, canonicalX = 0),
                instance(lod = 1, tileY = 1, unwrappedX = 1, copy = 0, canonicalX = 1),
            ),
            success.instances,
        )
    }

    @Test
    fun epsilonIncludesOnlyPointsWithinOneEMinusTenTileUnits() {
        val within = selectSuccess(
            ClosedMercatorFootprint.Point(MercatorGroundPoint(1.0 + 0.5e-10, 0.5)),
            lod = 0,
        )
        val outside = selectSuccess(
            ClosedMercatorFootprint.Point(MercatorGroundPoint(1.0 + 2.0e-10, 0.5)),
            lod = 0,
        )

        assertEquals(listOf(0L, 1L), within.instances.map(BasemapTileInstance::unwrappedX))
        assertEquals(listOf(1L), outside.instances.map(BasemapTileInstance::unwrappedX))
    }

    @Test
    fun supportClampsAndLongCoordinatesRemainExactAtLodTwentyTwo() {
        val minimum = selectSuccess(
            ClosedMercatorFootprint.Point(MercatorGroundPoint(-16384.0, 0.0)),
            lod = 22,
        )
        val maximum = selectSuccess(
            ClosedMercatorFootprint.Point(MercatorGroundPoint(16385.0, 1.0)),
            lod = 22,
        )

        assertEquals(
            listOf(
                instance(
                    lod = 22,
                    tileY = 0,
                    unwrappedX = -68_719_476_736L,
                    copy = -16384,
                    canonicalX = 0,
                ),
            ),
            minimum.instances,
        )
        assertEquals(
            listOf(
                instance(
                    lod = 22,
                    tileY = 4_194_303,
                    unwrappedX = 68_723_671_039L,
                    copy = 16384,
                    canonicalX = 4_194_303,
                ),
            ),
            maximum.instances,
        )
    }

    @Test
    fun negativeUnwrappedXUsesMathematicalFloorDivision() {
        val success = selectSuccess(
            ClosedMercatorFootprint.Point(MercatorGroundPoint(-0.125, 0.125)),
            lod = 2,
        )

        assertEquals(
            listOf(instance(lod = 2, tileY = 0, unwrappedX = -1, copy = -1, canonicalX = 3)),
            success.instances,
        )
        assertEquals(
            listOf(CanonicalBasemapTile(lod = 2, tileY = 0, canonicalX = 3)),
            success.canonicalResources,
        )
    }

    @Test
    fun polygonUsesCompleteClosedIntersectionPredicatesRatherThanOnlyBounds() {
        val success = selectSuccess(
            ClosedMercatorFootprint.Polygon(
                listOf(
                    MercatorGroundPoint(0.025, 0.025),
                    MercatorGroundPoint(0.725, 0.025),
                    MercatorGroundPoint(0.025, 0.725),
                ),
            ),
            lod = 2,
        )

        assertEquals(
            listOf(
                0 to 0L,
                0 to 1L,
                0 to 2L,
                1 to 0L,
                1 to 1L,
                1 to 2L,
                2 to 0L,
                2 to 1L,
            ),
            success.instances.map { it.tileY to it.unwrappedX },
        )
    }

    @Test
    fun unwrappedInstancesSortBeforeCanonicalResourcesDeduplicateAndSort() {
        val success = selectSuccess(
            ClosedMercatorFootprint.Segment(
                start = MercatorGroundPoint(-0.125, 0.25),
                end = MercatorGroundPoint(1.125, 0.25),
            ),
            lod = 1,
        )

        assertEquals(
            listOf(
                instance(lod = 1, tileY = 0, unwrappedX = -1, copy = -1, canonicalX = 1),
                instance(lod = 1, tileY = 0, unwrappedX = 0, copy = 0, canonicalX = 0),
                instance(lod = 1, tileY = 0, unwrappedX = 1, copy = 0, canonicalX = 1),
                instance(lod = 1, tileY = 0, unwrappedX = 2, copy = 1, canonicalX = 0),
            ),
            success.instances,
        )
        assertEquals(
            listOf(
                CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 0),
                CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 1),
            ),
            success.canonicalResources,
        )
    }

    @Test
    fun budgetCountsEveryWorldCopyBeforeCanonicalDeduplication() {
        val outcome = selectBasemapTiles(
            footprint = ClosedMercatorFootprint.Segment(
                start = MercatorGroundPoint(-16384.0, 0.5),
                end = MercatorGroundPoint(16385.0, 0.5),
            ),
            lod = 0,
            maximumInstances = 4096,
        )

        assertEquals(TileSelectionOutcome.OverBudget(limit = 4096, actual = 32_769L), outcome)
    }

    @Test
    fun overBudgetReportsExactIntersectionCountWithoutReturningPartialSelection() {
        val outcome = selectBasemapTiles(
            footprint = ClosedMercatorFootprint.Polygon(
                listOf(
                    MercatorGroundPoint(-0.3, 0.1),
                    MercatorGroundPoint(1.1, 0.1),
                    MercatorGroundPoint(1.1, 0.9),
                    MercatorGroundPoint(-0.3, 0.9),
                ),
            ),
            lod = 1,
            maximumInstances = 1,
        )

        assertEquals(TileSelectionOutcome.OverBudget(limit = 1, actual = 8L), outcome)
    }

    @Test
    fun successSnapshotsListsAndReturnsFreshCopies() {
        val success = selectSuccess(
            ClosedMercatorFootprint.Point(MercatorGroundPoint(0.25, 0.25)),
            lod = 1,
        )

        val firstInstances = success.instances as MutableList<BasemapTileInstance>
        val secondInstances = success.instances as MutableList<BasemapTileInstance>
        val firstResources = success.canonicalResources as MutableList<CanonicalBasemapTile>
        val secondResources = success.canonicalResources as MutableList<CanonicalBasemapTile>
        firstInstances.clear()
        firstResources.clear()

        assertNotSame(firstInstances, secondInstances)
        assertNotSame(firstResources, secondResources)
        assertEquals(secondInstances, success.instances)
        assertEquals(secondResources, success.canonicalResources)
    }

    private fun selectSuccess(
        footprint: ClosedMercatorFootprint,
        lod: Int,
        maximumInstances: Int = 4096,
    ): TileSelectionOutcome.Success = assertIs<TileSelectionOutcome.Success>(
        selectBasemapTiles(footprint, lod, maximumInstances),
    )

    private fun instance(
        lod: Int,
        tileY: Int,
        unwrappedX: Long,
        copy: Int,
        canonicalX: Int,
    ): BasemapTileInstance = BasemapTileInstance(
        lod = lod,
        tileY = tileY,
        unwrappedX = unwrappedX,
        instanceCopy = copy,
        canonicalX = canonicalX,
    )
}
