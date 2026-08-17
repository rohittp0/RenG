package com.rohittp.reng.internal.planning

import com.rohittp.reng.internal.projection.ClosedMercatorFootprint
import com.rohittp.reng.internal.projection.MercatorGroundPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
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
    fun exactEpsilonPlanesAtLodZeroAreIncludedWithoutAdmittingTwiceEpsilon() {
        val upperXAtEpsilon = selectedCoordinates(point(1.0 + CELL_EPSILON, 0.5), lod = 0)
        val upperXPastEpsilon = selectedCoordinates(point(1.0 + 2.0 * CELL_EPSILON, 0.5), lod = 0)
        val lowerXAtEpsilon = selectedCoordinates(point(1.0 - CELL_EPSILON, 0.5), lod = 0)
        val lowerXPastEpsilon = selectedCoordinates(point(1.0 - 2.0 * CELL_EPSILON, 0.5), lod = 0)
        val lowerYAtEpsilon = selectedCoordinates(point(0.5, -CELL_EPSILON), lod = 0)
        val lowerYPastEpsilon = selectedCoordinates(point(0.5, -2.0 * CELL_EPSILON), lod = 0)
        val upperYAtEpsilon = selectedCoordinates(point(0.5, 1.0 + CELL_EPSILON), lod = 0)
        val upperYPastEpsilon = selectedCoordinates(point(0.5, 1.0 + 2.0 * CELL_EPSILON), lod = 0)

        assertEquals(listOf(0 to 0L, 0 to 1L), upperXAtEpsilon)
        assertEquals(listOf(0 to 1L), upperXPastEpsilon)
        assertEquals(listOf(0 to 0L, 0 to 1L), lowerXAtEpsilon)
        assertEquals(listOf(0 to 0L), lowerXPastEpsilon)
        assertEquals(listOf(0 to 0L), lowerYAtEpsilon)
        assertEquals(emptyList(), lowerYPastEpsilon)
        assertEquals(listOf(0 to 0L), upperYAtEpsilon)
        assertEquals(emptyList(), upperYPastEpsilon)
    }

    @Test
    fun exactEpsilonPlanesAtHighLodUseTheSameTileCoordinateComparisons() {
        val tileCount = (1L shl 22).toDouble()
        fun tilePoint(tileX: Double, tileY: Double): ClosedMercatorFootprint =
            point(tileX / tileCount, tileY / tileCount)

        val upperXAtEpsilon = selectedCoordinates(tilePoint(1.0 + CELL_EPSILON, 2.5), lod = 22)
        val upperXPastEpsilon = selectedCoordinates(tilePoint(1.0 + 2.0 * CELL_EPSILON, 2.5), lod = 22)
        val lowerXAtEpsilon = selectedCoordinates(tilePoint(1.0 - CELL_EPSILON, 2.5), lod = 22)
        val lowerXPastEpsilon = selectedCoordinates(tilePoint(1.0 - 2.0 * CELL_EPSILON, 2.5), lod = 22)
        val upperYAtEpsilon = selectedCoordinates(tilePoint(0.5, 2.0 + CELL_EPSILON), lod = 22)
        val upperYPastEpsilon = selectedCoordinates(tilePoint(0.5, 2.0 + 2.0 * CELL_EPSILON), lod = 22)
        val lowerYAtEpsilon = selectedCoordinates(tilePoint(0.5, 2.0 - CELL_EPSILON), lod = 22)
        val lowerYPastEpsilon = selectedCoordinates(tilePoint(0.5, 2.0 - 2.0 * CELL_EPSILON), lod = 22)

        assertEquals(listOf(2 to 0L, 2 to 1L), upperXAtEpsilon)
        assertEquals(listOf(2 to 1L), upperXPastEpsilon)
        assertEquals(listOf(2 to 0L, 2 to 1L), lowerXAtEpsilon)
        assertEquals(listOf(2 to 0L), lowerXPastEpsilon)
        assertEquals(listOf(1 to 0L, 2 to 0L), upperYAtEpsilon)
        assertEquals(listOf(2 to 0L), upperYPastEpsilon)
        assertEquals(listOf(1 to 0L, 2 to 0L), lowerYAtEpsilon)
        assertEquals(listOf(1 to 0L), lowerYPastEpsilon)
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
    fun convexRowRangesMatchSmallBruteForceOracleForNarrowDiagonalAndTangentCases() {
        val cases = listOf(
            OracleCase(
                label = "narrow point",
                footprint = point(0.3, 0.3),
                lod = 2,
            ),
            OracleCase(
                label = "diagonal segment",
                footprint = ClosedMercatorFootprint.Segment(
                    MercatorGroundPoint(0.05, 0.05),
                    MercatorGroundPoint(0.7, 0.7),
                ),
                lod = 2,
            ),
            OracleCase(
                label = "shared-edge tangent segment",
                footprint = ClosedMercatorFootprint.Segment(
                    MercatorGroundPoint(0.5, 0.2),
                    MercatorGroundPoint(0.5, 0.8),
                ),
                lod = 2,
            ),
            OracleCase(
                label = "narrow convex polygon",
                footprint = ClosedMercatorFootprint.Polygon(
                    listOf(
                        MercatorGroundPoint(0.24, 0.1),
                        MercatorGroundPoint(0.26, 0.1),
                        MercatorGroundPoint(0.51, 0.9),
                        MercatorGroundPoint(0.49, 0.9),
                    ),
                ),
                lod = 2,
            ),
            OracleCase(
                label = "corner-tangent triangle",
                footprint = ClosedMercatorFootprint.Polygon(
                    listOf(
                        MercatorGroundPoint(0.025, 0.025),
                        MercatorGroundPoint(0.725, 0.025),
                        MercatorGroundPoint(0.025, 0.725),
                    ),
                ),
                lod = 2,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                bruteForceCoordinates(case.footprint, case.lod),
                selectedCoordinates(case.footprint, case.lod),
                case.label,
            )
        }
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
    fun fullSupportAtLodTwentyTwoCountsExactActualWithoutCellEnumeration() {
        val outcome = selectBasemapTiles(
            footprint = ClosedMercatorFootprint.Polygon(
                listOf(
                    MercatorGroundPoint(-16384.0, 0.0),
                    MercatorGroundPoint(16385.0, 0.0),
                    MercatorGroundPoint(16385.0, 1.0),
                    MercatorGroundPoint(-16384.0, 1.0),
                ),
            ),
            lod = 22,
            maximumInstances = 1,
        )

        assertEquals(
            TileSelectionOutcome.OverBudget(
                limit = 1,
                actual = 576_478_344_489_467_904L,
            ),
            outcome,
        )
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
    fun successSnapshotsConstructorInputsAndUsesStructuralEqualityAndHashing() {
        val tileInstance = instance(lod = 1, tileY = 0, unwrappedX = 0, copy = 0, canonicalX = 0)
        val canonicalTile = CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 0)
        val instanceInput = mutableListOf(tileInstance)
        val resourceInput = mutableListOf(canonicalTile)
        val success = TileSelectionOutcome.Success(instanceInput, resourceInput)
        val equalSuccess = TileSelectionOutcome.Success(listOf(tileInstance), listOf(canonicalTile))

        instanceInput.clear()
        resourceInput.clear()

        assertEquals(listOf(tileInstance), success.instances)
        assertEquals(listOf(canonicalTile), success.canonicalResources)
        assertEquals(equalSuccess, success)
        assertEquals(equalSuccess.hashCode(), success.hashCode())
        assertNotEquals(TileSelectionOutcome.Success(emptyList(), emptyList()), success)
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

    private fun bruteForceCoordinates(
        footprint: ClosedMercatorFootprint,
        lod: Int,
    ): List<Pair<Int, Long>> {
        val tileCount = (1L shl lod).toDouble()
        val vertices = when (footprint) {
            ClosedMercatorFootprint.Empty -> emptyList()
            is ClosedMercatorFootprint.Point -> listOf(footprint.point.toOraclePoint(tileCount))
            is ClosedMercatorFootprint.Segment -> listOf(
                footprint.start.toOraclePoint(tileCount),
                footprint.end.toOraclePoint(tileCount),
            )
            is ClosedMercatorFootprint.Polygon -> footprint.vertices.map { it.toOraclePoint(tileCount) }
        }
        val coordinates = mutableListOf<Pair<Int, Long>>()
        for (tileY in 0 until tileCount.toInt()) {
            for (unwrappedX in -4L..8L) {
                if (oracleIntersectsExpandedCell(footprint, vertices, unwrappedX, tileY)) {
                    coordinates += tileY to unwrappedX
                }
            }
        }
        return coordinates
    }

    private fun oracleIntersectsExpandedCell(
        footprint: ClosedMercatorFootprint,
        vertices: List<OraclePoint>,
        unwrappedX: Long,
        tileY: Int,
    ): Boolean {
        val minimumX = unwrappedX.toDouble() - CELL_EPSILON
        val maximumX = unwrappedX.toDouble() + 1.0 + CELL_EPSILON
        val minimumY = tileY.toDouble() - CELL_EPSILON
        val maximumY = tileY.toDouble() + 1.0 + CELL_EPSILON
        if (vertices.any { point ->
                point.x >= minimumX && point.x <= maximumX &&
                    point.y >= minimumY && point.y <= maximumY
            }
        ) {
            return true
        }

        val corners = listOf(
            OraclePoint(minimumX, minimumY),
            OraclePoint(maximumX, minimumY),
            OraclePoint(maximumX, maximumY),
            OraclePoint(minimumX, maximumY),
        )
        if (corners.any { oracleFootprintContains(footprint, vertices, it) }) return true

        val footprintEdges = oracleFootprintEdges(footprint, vertices)
        val cellEdges = corners.indices.map { index ->
            OracleSegment(corners[index], corners[(index + 1) % corners.size])
        }
        return footprintEdges.any { first -> cellEdges.any { second -> oracleSegmentsIntersect(first, second) } }
    }

    private fun oracleFootprintContains(
        footprint: ClosedMercatorFootprint,
        vertices: List<OraclePoint>,
        point: OraclePoint,
    ): Boolean = when (footprint) {
        ClosedMercatorFootprint.Empty -> false
        is ClosedMercatorFootprint.Point -> point == vertices.single()
        is ClosedMercatorFootprint.Segment -> oraclePointOnSegment(point, vertices[0], vertices[1])
        is ClosedMercatorFootprint.Polygon -> {
            var positive = false
            var negative = false
            vertices.indices.forEach { index ->
                val orientation = oracleOrientation(vertices[index], vertices[(index + 1) % vertices.size], point)
                if (orientation > 0.0) positive = true
                if (orientation < 0.0) negative = true
            }
            !(positive && negative)
        }
    }

    private fun oracleFootprintEdges(
        footprint: ClosedMercatorFootprint,
        vertices: List<OraclePoint>,
    ): List<OracleSegment> = when (footprint) {
        ClosedMercatorFootprint.Empty,
        is ClosedMercatorFootprint.Point,
        -> emptyList()
        is ClosedMercatorFootprint.Segment -> listOf(OracleSegment(vertices[0], vertices[1]))
        is ClosedMercatorFootprint.Polygon -> vertices.indices.map { index ->
            OracleSegment(vertices[index], vertices[(index + 1) % vertices.size])
        }
    }

    private fun oracleSegmentsIntersect(first: OracleSegment, second: OracleSegment): Boolean {
        val firstStart = oracleOrientation(first.start, first.end, second.start)
        val firstEnd = oracleOrientation(first.start, first.end, second.end)
        val secondStart = oracleOrientation(second.start, second.end, first.start)
        val secondEnd = oracleOrientation(second.start, second.end, first.end)
        if (firstStart == 0.0 && oracleWithinBounds(second.start, first.start, first.end)) return true
        if (firstEnd == 0.0 && oracleWithinBounds(second.end, first.start, first.end)) return true
        if (secondStart == 0.0 && oracleWithinBounds(first.start, second.start, second.end)) return true
        if (secondEnd == 0.0 && oracleWithinBounds(first.end, second.start, second.end)) return true
        return oracleOppositeSigns(firstStart, firstEnd) && oracleOppositeSigns(secondStart, secondEnd)
    }

    private fun oraclePointOnSegment(point: OraclePoint, start: OraclePoint, end: OraclePoint): Boolean =
        oracleOrientation(start, end, point) == 0.0 && oracleWithinBounds(point, start, end)

    private fun oracleWithinBounds(point: OraclePoint, start: OraclePoint, end: OraclePoint): Boolean =
        point.x >= minOf(start.x, end.x) && point.x <= maxOf(start.x, end.x) &&
            point.y >= minOf(start.y, end.y) && point.y <= maxOf(start.y, end.y)

    private fun oracleOrientation(start: OraclePoint, end: OraclePoint, point: OraclePoint): Double =
        (end.x - start.x) * (point.y - start.y) - (end.y - start.y) * (point.x - start.x)

    private fun oracleOppositeSigns(first: Double, second: Double): Boolean =
        (first < 0.0 && second > 0.0) || (first > 0.0 && second < 0.0)

    private fun MercatorGroundPoint.toOraclePoint(scale: Double): OraclePoint =
        OraclePoint(x * scale, y * scale)

    private fun selectedCoordinates(
        footprint: ClosedMercatorFootprint,
        lod: Int,
    ): List<Pair<Int, Long>> = selectSuccess(footprint, lod).instances.map { it.tileY to it.unwrappedX }

    private fun point(x: Double, y: Double): ClosedMercatorFootprint =
        ClosedMercatorFootprint.Point(MercatorGroundPoint(x, y))

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

    private data class OracleCase(
        val label: String,
        val footprint: ClosedMercatorFootprint,
        val lod: Int,
    )

    private data class OraclePoint(val x: Double, val y: Double)

    private data class OracleSegment(val start: OraclePoint, val end: OraclePoint)

    private companion object {
        const val CELL_EPSILON: Double = 1e-10
    }
}
