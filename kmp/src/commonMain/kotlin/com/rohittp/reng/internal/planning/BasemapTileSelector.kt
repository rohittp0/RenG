package com.rohittp.reng.internal.planning

import com.rohittp.reng.internal.projection.ClosedMercatorFootprint
import com.rohittp.reng.internal.projection.MercatorGroundPoint
import kotlin.math.ceil
import kotlin.math.floor

/** Which Mercator edge an otherwise ordinary edge-row resource is stretched beyond. */
internal enum class GroundCap { NORTH, SOUTH }

internal data class BasemapTileInstance(
    val lod: Int,
    val tileY: Int,
    val unwrappedX: Long,
    val instanceCopy: Int,
    val canonicalX: Int,
    /** `null` for an ordinary tile; otherwise the half-plane this edge-row instance covers. */
    val cap: GroundCap? = null,
    /** Rectangle height in tile units, rounded up to cover the selected footprint. */
    val capSpanTiles: Double = 1.0,
) {
    init {
        require(capSpanTiles.isFinite() && capSpanTiles >= 1.0 && capSpanTiles == ceil(capSpanTiles)) {
            "a basemap cap span is a positive whole number of tile heights"
        }
        require(cap != null || capSpanTiles == 1.0) {
            "an ordinary basemap tile spans exactly one tile height"
        }
    }
}

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
    extendGroundPastPoles: Boolean = false,
): TileSelectionOutcome {
    require(lod in MINIMUM_LOD..MAXIMUM_LOD) { "lod must be within the Mercator LOD range" }
    require(maximumInstances > 0) { "maximumInstances must be positive" }
    if (footprint is ClosedMercatorFootprint.Empty) return emptyTileSelection()

    val tileCount = 1L shl lod
    val scaledFootprint = footprint.scaledBy(tileCount.toDouble())
    if (scaledFootprint.vertices.isEmpty()) return emptyTileSelection()
    check(scaledFootprint.vertices.all { it.x.isFinite() && it.y.isFinite() })
    val candidateRows = candidateRows(scaledFootprint, tileCount)
    val admissibleMinimumX = MINIMUM_WORLD_COPY.toLong() * tileCount
    val admissibleMaximumX = MAXIMUM_SUPPORT_COPY.toLong() * tileCount - 1L
    val capRanges = if (extendGroundPastPoles) {
        intersectingCapRanges(
            footprint = scaledFootprint,
            tileCount = tileCount,
            admissibleMinimumX = admissibleMinimumX,
            admissibleMaximumX = admissibleMaximumX,
        )
    } else {
        emptyList()
    }

    var actual = candidateRows?.let { rows ->
        countIntersectingInstances(
            footprint = scaledFootprint,
            rows = rows,
            admissibleMinimumX = admissibleMinimumX,
            admissibleMaximumX = admissibleMaximumX,
        )
    } ?: 0L
    capRanges.forEach { range ->
        actual = checkedAdd(actual, checkedInclusiveCount(range.firstX, range.lastX))
    }
    if (actual > maximumInstances.toLong()) {
        return TileSelectionOutcome.OverBudget(limit = maximumInstances, actual = actual)
    }

    val instances = ArrayList<BasemapTileInstance>(actual.toInt())
    fun appendRange(
        tileY: Long,
        firstX: Long,
        lastX: Long,
        cap: GroundCap? = null,
        capSpanTiles: Double = 1.0,
    ) {
        var unwrappedX = firstX
        while (true) {
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
                cap = cap,
                capSpanTiles = capSpanTiles,
            )
            if (unwrappedX == lastX) break
            unwrappedX += 1L
        }
    }
    if (candidateRows != null) {
        forEachIntersectingRowRange(
            footprint = scaledFootprint,
            rows = candidateRows,
            admissibleMinimumX = admissibleMinimumX,
            admissibleMaximumX = admissibleMaximumX,
        ) { tileY, firstX, lastX ->
            appendRange(tileY = tileY, firstX = firstX, lastX = lastX)
        }
    }
    capRanges.forEach { range ->
        appendRange(
            tileY = if (range.cap == GroundCap.NORTH) 0L else tileCount - 1L,
            firstX = range.firstX,
            lastX = range.lastX,
            cap = range.cap,
            capSpanTiles = range.spanTiles,
        )
    }
    instances.sortWith(
        compareBy<BasemapTileInstance> { it.tileY }
            .thenBy { it.unwrappedX }
            .thenBy { instance ->
                when (instance.cap) {
                    GroundCap.NORTH -> 0
                    null -> 1
                    GroundCap.SOUTH -> 2
                }
            },
    )

    val canonicalResources = buildList {
        instances.forEach { instance ->
            add(
                CanonicalBasemapTile(
                    lod = instance.lod,
                    tileY = instance.tileY,
                    canonicalX = instance.canonicalX,
                ),
            )
        }
        if (instances.any { instance -> instance.cap != null }) add(WORLD_BASEMAP_TILE)
    }
        .distinct()
        .sortedWith(
            compareBy<CanonicalBasemapTile> { it.lod }
                .thenBy { it.tileY }
                .thenBy { it.canonicalX },
        )
    return TileSelectionOutcome.Success(instances, canonicalResources)
}

private fun intersectingCapRanges(
    footprint: ScaledFootprint,
    tileCount: Long,
    admissibleMinimumX: Long,
    admissibleMaximumX: Long,
): List<CapRange> {
    val minimumY = footprint.vertices.minOf(TilePoint::y)
    val maximumY = footprint.vertices.maxOf(TilePoint::y)
    val projection = XIntervalAccumulator()
    return buildList {
        if (minimumY < 0.0) {
            footprint.projectXWithinHorizontalStrip(minimumY, 0.0, projection)
            intersectingXRange(projection, admissibleMinimumX, admissibleMaximumX)?.let { range ->
                add(
                    CapRange(
                        cap = GroundCap.NORTH,
                        firstX = range.first,
                        lastX = range.last,
                        spanTiles = ceil(-minimumY).coerceAtLeast(1.0),
                    ),
                )
            }
        }
        if (maximumY > tileCount.toDouble()) {
            footprint.projectXWithinHorizontalStrip(tileCount.toDouble(), maximumY, projection)
            intersectingXRange(projection, admissibleMinimumX, admissibleMaximumX)?.let { range ->
                add(
                    CapRange(
                        cap = GroundCap.SOUTH,
                        firstX = range.first,
                        lastX = range.last,
                        spanTiles = ceil(maximumY - tileCount.toDouble()).coerceAtLeast(1.0),
                    ),
                )
            }
        }
    }
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

private fun candidateRows(
    footprint: ScaledFootprint,
    tileCount: Long,
): CandidateRows? {
    val minimumY = footprint.vertices.minOf(TilePoint::y)
    val maximumY = footprint.vertices.maxOf(TilePoint::y)
    val firstY = conservativeFirstCandidate(minimumY, admissibleMinimum = 0L)
    val lastY = conservativeLastCandidate(maximumY, admissibleMaximum = tileCount - 1L)
    return if (firstY <= lastY) CandidateRows(firstY, lastY) else null
}

private fun countIntersectingInstances(
    footprint: ScaledFootprint,
    rows: CandidateRows,
    admissibleMinimumX: Long,
    admissibleMaximumX: Long,
): Long {
    var actual = 0L
    forEachIntersectingRowRange(
        footprint = footprint,
        rows = rows,
        admissibleMinimumX = admissibleMinimumX,
        admissibleMaximumX = admissibleMaximumX,
    ) { _, firstX, lastX ->
        actual = checkedAdd(actual, checkedInclusiveCount(firstX, lastX))
    }
    return actual
}

private inline fun forEachIntersectingRowRange(
    footprint: ScaledFootprint,
    rows: CandidateRows,
    admissibleMinimumX: Long,
    admissibleMaximumX: Long,
    action: (tileY: Long, firstX: Long, lastX: Long) -> Unit,
) {
    val projection = XIntervalAccumulator()
    var tileY = rows.firstY
    while (true) {
        val minimumY = tileY.toDouble() - CELL_EPSILON
        val maximumY = tileY.toDouble() + 1.0 + CELL_EPSILON
        footprint.projectXWithinHorizontalStrip(minimumY, maximumY, projection)
        intersectingXRange(projection, admissibleMinimumX, admissibleMaximumX)?.let { range ->
            action(tileY, range.first, range.last)
        }
        if (tileY == rows.lastY) break
        tileY += 1L
    }
}

private fun intersectingXRange(
    projection: XIntervalAccumulator,
    admissibleMinimumX: Long,
    admissibleMaximumX: Long,
): LongRange? {
    if (projection.isEmpty) return null
    var firstX = conservativeFirstCandidate(projection.minimum, admissibleMinimumX)
    var lastX = conservativeLastCandidate(projection.maximum, admissibleMaximumX)
    while (firstX <= lastX && !expandedCellXIntersects(firstX, projection.minimum, projection.maximum)) {
        firstX += 1L
    }
    while (lastX >= firstX && !expandedCellXIntersects(lastX, projection.minimum, projection.maximum)) {
        lastX -= 1L
    }
    return if (firstX <= lastX) firstX..lastX else null
}

private fun ScaledFootprint.projectXWithinHorizontalStrip(
    minimumY: Double,
    maximumY: Double,
    projection: XIntervalAccumulator,
) {
    projection.reset()
    vertices.forEach { point ->
        if (point.y >= minimumY && point.y <= maximumY) projection.include(point.x)
    }
    when (kind) {
        FootprintKind.EMPTY,
        FootprintKind.POINT,
        -> Unit
        FootprintKind.SEGMENT -> includeEdgeStripCrossings(
            start = vertices[0],
            end = vertices[1],
            minimumY = minimumY,
            maximumY = maximumY,
            projection = projection,
        )
        FootprintKind.POLYGON -> vertices.indices.forEach { index ->
            includeEdgeStripCrossings(
                start = vertices[index],
                end = vertices[(index + 1) % vertices.size],
                minimumY = minimumY,
                maximumY = maximumY,
                projection = projection,
            )
        }
    }
}

private fun includeEdgeStripCrossings(
    start: TilePoint,
    end: TilePoint,
    minimumY: Double,
    maximumY: Double,
    projection: XIntervalAccumulator,
) {
    if (strictlyCrossesHorizontalPlane(start.y, end.y, minimumY)) {
        projection.include(horizontalIntersectionX(start, end, minimumY))
    }
    if (strictlyCrossesHorizontalPlane(start.y, end.y, maximumY)) {
        projection.include(horizontalIntersectionX(start, end, maximumY))
    }
}

private fun strictlyCrossesHorizontalPlane(startY: Double, endY: Double, planeY: Double): Boolean =
    (startY < planeY && endY > planeY) || (startY > planeY && endY < planeY)

private fun horizontalIntersectionX(start: TilePoint, end: TilePoint, planeY: Double): Double {
    val fraction = (planeY - start.y) / (end.y - start.y)
    return start.x + (end.x - start.x) * fraction
}

private fun expandedCellXIntersects(unwrappedX: Long, minimumX: Double, maximumX: Double): Boolean {
    val expandedMinimumX = unwrappedX.toDouble() - CELL_EPSILON
    val expandedMaximumX = unwrappedX.toDouble() + 1.0 + CELL_EPSILON
    return expandedMinimumX <= maximumX && expandedMaximumX >= minimumX
}

private fun mathematicalFloorDivision(value: Long, positiveDivisor: Long): Long {
    val quotient = value / positiveDivisor
    return if (value % positiveDivisor < 0L) quotient - 1L else quotient
}

private fun conservativeFirstCandidate(value: Double, admissibleMinimum: Long): Long =
    (floor(value).toLong() - CANDIDATE_NEIGHBOUR_PADDING).coerceAtLeast(admissibleMinimum)

private fun conservativeLastCandidate(value: Double, admissibleMaximum: Long): Long =
    (floor(value).toLong() + CANDIDATE_NEIGHBOUR_PADDING).coerceAtMost(admissibleMaximum)

private fun checkedInclusiveCount(first: Long, last: Long): Long {
    check(last >= first)
    val difference = last - first
    check(difference < Long.MAX_VALUE)
    return difference + 1L
}

private fun checkedAdd(first: Long, second: Long): Long {
    check(first >= 0L && second >= 0L)
    check(second <= Long.MAX_VALUE - first) { "tile intersection count overflow" }
    return first + second
}

private class XIntervalAccumulator {
    var isEmpty: Boolean = true
        private set
    var minimum: Double = 0.0
        private set
    var maximum: Double = 0.0
        private set

    fun reset() {
        isEmpty = true
    }

    fun include(value: Double) {
        if (isEmpty) {
            minimum = value
            maximum = value
            isEmpty = false
        } else {
            if (value < minimum) minimum = value
            if (value > maximum) maximum = value
        }
    }
}

private enum class FootprintKind { EMPTY, POINT, SEGMENT, POLYGON }

private data class ScaledFootprint(
    val kind: FootprintKind,
    val vertices: List<TilePoint>,
)

private data class TilePoint(val x: Double, val y: Double)

private data class CandidateRows(
    val firstY: Long,
    val lastY: Long,
)

private data class CapRange(
    val cap: GroundCap,
    val firstX: Long,
    val lastX: Long,
    val spanTiles: Double,
)

private const val MINIMUM_LOD: Int = 0
private const val MAXIMUM_LOD: Int = 22
private const val MINIMUM_WORLD_COPY: Int = -16384
private const val MAXIMUM_SUPPORT_COPY: Int = 16385
private const val CELL_EPSILON: Double = 1e-10
private const val CANDIDATE_NEIGHBOUR_PADDING: Long = 2L

/** Auxiliary continuous source for cap smudging; it is provisioned but never creates a draw instance. */
internal val WORLD_BASEMAP_TILE: CanonicalBasemapTile =
    CanonicalBasemapTile(lod = 0, tileY = 0, canonicalX = 0)
