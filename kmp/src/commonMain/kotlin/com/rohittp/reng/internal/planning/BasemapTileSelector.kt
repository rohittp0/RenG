package com.rohittp.reng.internal.planning

import com.rohittp.reng.internal.projection.ClosedMercatorFootprint
import com.rohittp.reng.internal.projection.MercatorGroundPoint
import kotlin.math.ceil
import kotlin.math.floor

internal data class BasemapTileInstance(
    val lod: Int,
    val tileY: Int,
    val unwrappedX: Long,
    val instanceCopy: Int,
    val canonicalX: Int,
)

internal data class CanonicalBasemapTile(
    val lod: Int,
    val tileY: Int,
    val canonicalX: Int,
)

internal sealed interface TileSelectionOutcome {
    class Success(
        instances: List<BasemapTileInstance>,
        canonicalResources: List<CanonicalBasemapTile>,
    ) : TileSelectionOutcome {
        private val instanceSnapshot: List<BasemapTileInstance> = ArrayList(instances)
        private val canonicalResourceSnapshot: List<CanonicalBasemapTile> = ArrayList(canonicalResources)

        val instances: List<BasemapTileInstance> get() = ArrayList(instanceSnapshot)
        val canonicalResources: List<CanonicalBasemapTile> get() = ArrayList(canonicalResourceSnapshot)

        override fun equals(other: Any?): Boolean =
            other is Success &&
                instanceSnapshot == other.instanceSnapshot &&
                canonicalResourceSnapshot == other.canonicalResourceSnapshot

        override fun hashCode(): Int = 31 * instanceSnapshot.hashCode() + canonicalResourceSnapshot.hashCode()

        override fun toString(): String =
            "Success(instances=$instanceSnapshot, canonicalResources=$canonicalResourceSnapshot)"
    }

    data class OverBudget(val limit: Int, val actual: Long) : TileSelectionOutcome
}

internal fun selectBasemapTiles(
    footprint: ClosedMercatorFootprint,
    lod: Int,
    maximumInstances: Int,
): TileSelectionOutcome {
    require(lod in MINIMUM_LOD..MAXIMUM_LOD) { "lod must be within the Mercator LOD range" }
    require(maximumInstances > 0) { "maximumInstances must be positive" }
    if (footprint is ClosedMercatorFootprint.Empty) return emptyTileSelection()

    val tileCount = 1L shl lod
    val scaledFootprint = footprint.scaledBy(tileCount.toDouble())
    if (scaledFootprint.vertices.isEmpty()) return emptyTileSelection()
    val candidates = candidateRange(scaledFootprint, tileCount) ?: return emptyTileSelection()

    val actual = countIntersectingCells(scaledFootprint, candidates)
    if (actual > maximumInstances.toLong()) {
        return TileSelectionOutcome.OverBudget(limit = maximumInstances, actual = actual)
    }

    val instances = ArrayList<BasemapTileInstance>(actual.toInt())
    forEachIntersectingCell(scaledFootprint, candidates) { unwrappedX, tileY ->
        val copy = mathematicalFloorDivision(unwrappedX, tileCount)
        val canonicalX = unwrappedX - copy * tileCount
        check(copy in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        check(canonicalX in 0 until tileCount)
        instances += BasemapTileInstance(
            lod = lod,
            tileY = tileY.toInt(),
            unwrappedX = unwrappedX,
            instanceCopy = copy.toInt(),
            canonicalX = canonicalX.toInt(),
        )
    }
    instances.sortWith(compareBy<BasemapTileInstance> { it.tileY }.thenBy { it.unwrappedX })

    val canonicalResources = instances
        .map { instance ->
            CanonicalBasemapTile(
                lod = instance.lod,
                tileY = instance.tileY,
                canonicalX = instance.canonicalX,
            )
        }
        .distinct()
        .sortedWith(
            compareBy<CanonicalBasemapTile> { it.lod }
                .thenBy { it.tileY }
                .thenBy { it.canonicalX },
        )
    return TileSelectionOutcome.Success(instances, canonicalResources)
}

private fun emptyTileSelection(): TileSelectionOutcome.Success =
    TileSelectionOutcome.Success(emptyList(), emptyList())

private fun ClosedMercatorFootprint.scaledBy(scale: Double): ScaledFootprint = when (this) {
    ClosedMercatorFootprint.Empty -> ScaledFootprint(FootprintKind.EMPTY, emptyList())
    is ClosedMercatorFootprint.Point -> ScaledFootprint(FootprintKind.POINT, listOf(point.scaledBy(scale)))
    is ClosedMercatorFootprint.Segment -> ScaledFootprint(
        FootprintKind.SEGMENT,
        listOf(start.scaledBy(scale), end.scaledBy(scale)),
    )
    is ClosedMercatorFootprint.Polygon -> ScaledFootprint(
        FootprintKind.POLYGON,
        vertices.map { it.scaledBy(scale) },
    )
}

private fun MercatorGroundPoint.scaledBy(scale: Double): TilePoint =
    TilePoint(x = x * scale, y = y * scale)

private fun candidateRange(
    footprint: ScaledFootprint,
    tileCount: Long,
): CandidateRange? {
    val minimumX = footprint.vertices.minOf(TilePoint::x)
    val maximumX = footprint.vertices.maxOf(TilePoint::x)
    val minimumY = footprint.vertices.minOf(TilePoint::y)
    val maximumY = footprint.vertices.maxOf(TilePoint::y)
    check(minimumX.isFinite() && maximumX.isFinite() && minimumY.isFinite() && maximumY.isFinite())

    val admissibleMinimumX = MINIMUM_WORLD_COPY.toLong() * tileCount
    val admissibleMaximumX = MAXIMUM_SUPPORT_COPY.toLong() * tileCount - 1L
    val admissibleMinimumY = 0L
    val admissibleMaximumY = tileCount - 1L
    val firstX = ceilMinusEpsilon(minimumX - 1.0).coerceAtLeast(admissibleMinimumX)
    val lastX = floorPlusEpsilon(maximumX).coerceAtMost(admissibleMaximumX)
    val firstY = ceilMinusEpsilon(minimumY - 1.0).coerceAtLeast(admissibleMinimumY)
    val lastY = floorPlusEpsilon(maximumY).coerceAtMost(admissibleMaximumY)
    if (firstX > lastX || firstY > lastY) return null

    val xCount = checkedInclusiveCount(firstX, lastX)
    val yCount = checkedInclusiveCount(firstY, lastY)
    checkedMultiply(xCount, yCount)
    return CandidateRange(firstX, lastX, firstY, lastY)
}

private fun countIntersectingCells(
    footprint: ScaledFootprint,
    candidates: CandidateRange,
): Long {
    var actual = 0L
    forEachIntersectingCell(footprint, candidates) { _, _ ->
        check(actual < Long.MAX_VALUE) { "tile intersection count overflow" }
        actual += 1L
    }
    return actual
}

private inline fun forEachIntersectingCell(
    footprint: ScaledFootprint,
    candidates: CandidateRange,
    action: (unwrappedX: Long, tileY: Long) -> Unit,
) {
    var tileY = candidates.firstY
    while (true) {
        var unwrappedX = candidates.firstX
        while (true) {
            if (intersectsExpandedCell(footprint, unwrappedX, tileY)) action(unwrappedX, tileY)
            if (unwrappedX == candidates.lastX) break
            unwrappedX += 1L
        }
        if (tileY == candidates.lastY) break
        tileY += 1L
    }
}

private fun intersectsExpandedCell(
    footprint: ScaledFootprint,
    unwrappedX: Long,
    tileY: Long,
): Boolean {
    val originX = unwrappedX.toDouble()
    val originY = tileY.toDouble()
    val localVertices = footprint.vertices.map { point ->
        TilePoint(x = point.x - originX, y = point.y - originY)
    }

    if (localVertices.any(::isInsideExpandedCell)) return true
    if (EXPANDED_CELL_CORNERS.any { corner -> footprint.contains(corner, localVertices) }) return true

    val footprintEdges = footprint.edges(localVertices)
    if (footprintEdges.isEmpty()) return false
    return footprintEdges.any { footprintEdge ->
        EXPANDED_CELL_EDGES.any { cellEdge -> segmentsIntersect(footprintEdge, cellEdge) }
    }
}

private fun ScaledFootprint.contains(point: TilePoint, localVertices: List<TilePoint>): Boolean =
    when (kind) {
        FootprintKind.EMPTY -> false
        FootprintKind.POINT -> point == localVertices.single()
        FootprintKind.SEGMENT -> pointOnSegment(point, localVertices[0], localVertices[1])
        FootprintKind.POLYGON -> pointInConvexPolygon(point, localVertices)
    }

private fun ScaledFootprint.edges(localVertices: List<TilePoint>): List<TileSegment> = when (kind) {
    FootprintKind.EMPTY,
    FootprintKind.POINT,
    -> emptyList()
    FootprintKind.SEGMENT -> listOf(TileSegment(localVertices[0], localVertices[1]))
    FootprintKind.POLYGON -> localVertices.indices.map { index ->
        TileSegment(localVertices[index], localVertices[(index + 1) % localVertices.size])
    }
}

private fun isInsideExpandedCell(point: TilePoint): Boolean =
    point.x >= -CELL_EPSILON &&
        point.x <= 1.0 + CELL_EPSILON &&
        point.y >= -CELL_EPSILON &&
        point.y <= 1.0 + CELL_EPSILON

private fun pointInConvexPolygon(point: TilePoint, vertices: List<TilePoint>): Boolean {
    var hasPositive = false
    var hasNegative = false
    for (index in vertices.indices) {
        val orientation = orientation(vertices[index], vertices[(index + 1) % vertices.size], point)
        if (orientation > 0.0) hasPositive = true
        if (orientation < 0.0) hasNegative = true
        if (hasPositive && hasNegative) return false
    }
    return true
}

private fun segmentsIntersect(first: TileSegment, second: TileSegment): Boolean {
    val firstStart = orientation(first.start, first.end, second.start)
    val firstEnd = orientation(first.start, first.end, second.end)
    val secondStart = orientation(second.start, second.end, first.start)
    val secondEnd = orientation(second.start, second.end, first.end)

    if (firstStart == 0.0 && pointWithinSegmentBounds(second.start, first.start, first.end)) return true
    if (firstEnd == 0.0 && pointWithinSegmentBounds(second.end, first.start, first.end)) return true
    if (secondStart == 0.0 && pointWithinSegmentBounds(first.start, second.start, second.end)) return true
    if (secondEnd == 0.0 && pointWithinSegmentBounds(first.end, second.start, second.end)) return true
    return oppositeSigns(firstStart, firstEnd) && oppositeSigns(secondStart, secondEnd)
}

private fun pointOnSegment(point: TilePoint, start: TilePoint, end: TilePoint): Boolean =
    orientation(start, end, point) == 0.0 && pointWithinSegmentBounds(point, start, end)

private fun pointWithinSegmentBounds(point: TilePoint, start: TilePoint, end: TilePoint): Boolean =
    point.x >= minOf(start.x, end.x) &&
        point.x <= maxOf(start.x, end.x) &&
        point.y >= minOf(start.y, end.y) &&
        point.y <= maxOf(start.y, end.y)

private fun orientation(start: TilePoint, end: TilePoint, point: TilePoint): Double =
    (end.x - start.x) * (point.y - start.y) - (end.y - start.y) * (point.x - start.x)

private fun oppositeSigns(first: Double, second: Double): Boolean =
    (first < 0.0 && second > 0.0) || (first > 0.0 && second < 0.0)

private fun mathematicalFloorDivision(value: Long, positiveDivisor: Long): Long {
    val quotient = value / positiveDivisor
    return if (value % positiveDivisor < 0L) quotient - 1L else quotient
}

private fun ceilMinusEpsilon(value: Double): Long {
    val lowerInteger = floor(value)
    val rounded = if (value - lowerInteger <= CELL_EPSILON) lowerInteger else lowerInteger + 1.0
    return rounded.toLong()
}

private fun floorPlusEpsilon(value: Double): Long {
    val upperInteger = ceil(value)
    val rounded = if (upperInteger - value <= CELL_EPSILON) upperInteger else floor(value)
    return rounded.toLong()
}

private fun checkedInclusiveCount(first: Long, last: Long): Long {
    check(last >= first)
    val difference = last - first
    check(difference < Long.MAX_VALUE)
    return difference + 1L
}

private fun checkedMultiply(first: Long, second: Long): Long {
    check(first >= 0L && second >= 0L)
    check(first == 0L || second <= Long.MAX_VALUE / first) { "tile candidate count overflow" }
    return first * second
}

private enum class FootprintKind { EMPTY, POINT, SEGMENT, POLYGON }

private data class ScaledFootprint(
    val kind: FootprintKind,
    val vertices: List<TilePoint>,
)

private data class TilePoint(val x: Double, val y: Double)

private data class TileSegment(val start: TilePoint, val end: TilePoint)

private data class CandidateRange(
    val firstX: Long,
    val lastX: Long,
    val firstY: Long,
    val lastY: Long,
)

private const val MINIMUM_LOD: Int = 0
private const val MAXIMUM_LOD: Int = 22
private const val MINIMUM_WORLD_COPY: Int = -16384
private const val MAXIMUM_SUPPORT_COPY: Int = 16385
private const val CELL_EPSILON: Double = 1e-10
private val EXPANDED_CELL_CORNERS: List<TilePoint> = listOf(
    TilePoint(-CELL_EPSILON, -CELL_EPSILON),
    TilePoint(1.0 + CELL_EPSILON, -CELL_EPSILON),
    TilePoint(1.0 + CELL_EPSILON, 1.0 + CELL_EPSILON),
    TilePoint(-CELL_EPSILON, 1.0 + CELL_EPSILON),
)
private val EXPANDED_CELL_EDGES: List<TileSegment> = EXPANDED_CELL_CORNERS.indices.map { index ->
    TileSegment(
        EXPANDED_CELL_CORNERS[index],
        EXPANDED_CELL_CORNERS[(index + 1) % EXPANDED_CELL_CORNERS.size],
    )
}
