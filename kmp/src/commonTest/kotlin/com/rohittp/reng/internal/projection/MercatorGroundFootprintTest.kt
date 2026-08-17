package com.rohittp.reng.internal.projection

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class MercatorGroundFootprintTest {
    @Test
    fun oneByOneOutputProducesTheOnlyPhysicalPixelCentreAsAPoint() {
        val footprint = clippedPhysicalPixelFootprint(resolve(OutputPixelSize(1, 1)))

        assertEquals(
            ClosedMercatorFootprint.Point(MercatorGroundPoint(0.5, 0.5)),
            footprint,
        )
    }

    @Test
    fun onePixelDimensionsProduceTopToBottomAndLeftToRightSegments() {
        val vertical = clippedPhysicalPixelFootprint(resolve(OutputPixelSize(1, 3)))
        val horizontal = clippedPhysicalPixelFootprint(resolve(OutputPixelSize(3, 1)))

        assertEquals(
            ClosedMercatorFootprint.Segment(
                start = MercatorGroundPoint(0.5, 0.498046875),
                end = MercatorGroundPoint(0.5, 0.501953125),
            ),
            vertical,
        )
        assertEquals(
            ClosedMercatorFootprint.Segment(
                start = MercatorGroundPoint(0.498046875, 0.5),
                end = MercatorGroundPoint(0.501953125, 0.5),
            ),
            horizontal,
        )
    }

    @Test
    fun physicalPixelCentreRectangleProducesAnOrderedConvexPolygonWithoutAClosingDuplicate() {
        val polygon = assertIs<ClosedMercatorFootprint.Polygon>(
            clippedPhysicalPixelFootprint(resolve(OutputPixelSize(2, 2))),
        )

        assertEquals(
            listOf(
                MercatorGroundPoint(0.4990234375, 0.4990234375),
                MercatorGroundPoint(0.5009765625, 0.4990234375),
                MercatorGroundPoint(0.5009765625, 0.5009765625),
                MercatorGroundPoint(0.4990234375, 0.5009765625),
            ),
            polygon.vertices,
        )
        assertEquals(4, polygon.vertices.size)
        assertNotEquals(polygon.vertices.first(), polygon.vertices.last())
    }

    @Test
    fun polygonSnapshotsItsInputReturnsFreshCopiesAndUsesStructuralEquality() {
        val expected = listOf(
            MercatorGroundPoint(0.0, 0.0),
            MercatorGroundPoint(1.0, 0.0),
            MercatorGroundPoint(0.0, 1.0),
        )
        val input = expected.toMutableList()
        val polygon = ClosedMercatorFootprint.Polygon(input)

        input.clear()
        val firstRead = polygon.vertices
        val secondRead = polygon.vertices
        val equalPolygon = ClosedMercatorFootprint.Polygon(expected.toMutableList())
        val emptyPolygon = ClosedMercatorFootprint.Polygon(emptyList())

        assertEquals(expected, firstRead)
        assertEquals(expected, secondRead)
        assertNotSame(firstRead, secondRead)
        assertNotSame(emptyPolygon.vertices, emptyPolygon.vertices)
        assertEquals(equalPolygon, polygon)
        assertEquals(equalPolygon.hashCode(), polygon.hashCode())
    }

    @Test
    fun leftSupportPlaneClipsAtItsExactClosedBoundary() {
        val footprint = clippedPhysicalPixelFootprint(
            syntheticCamera(
                outputPixelSize = OutputPixelSize(3, 3),
                mercatorAnchor = MercatorPosition(-16383.9375, 0.5, 0.0),
                worldSizeLogicalPixels = 8.0,
            ),
        )

        assertPolygon(
            footprint,
            listOf(
                MercatorGroundPoint(-16384.0, 0.375),
                MercatorGroundPoint(-16383.8125, 0.375),
                MercatorGroundPoint(-16383.8125, 0.625),
                MercatorGroundPoint(-16384.0, 0.625),
            ),
        )
    }

    @Test
    fun rightSupportPlaneClipsAtItsExactClosedBoundary() {
        val footprint = clippedPhysicalPixelFootprint(
            syntheticCamera(
                outputPixelSize = OutputPixelSize(3, 3),
                mercatorAnchor = MercatorPosition(16384.9375, 0.5, 0.0),
                worldSizeLogicalPixels = 8.0,
            ),
        )

        assertPolygon(
            footprint,
            listOf(
                MercatorGroundPoint(16384.8125, 0.375),
                MercatorGroundPoint(16385.0, 0.375),
                MercatorGroundPoint(16385.0, 0.625),
                MercatorGroundPoint(16384.8125, 0.625),
            ),
        )
    }

    @Test
    fun minimumYSupportPlaneClipsAtItsExactClosedBoundary() {
        val footprint = clippedPhysicalPixelFootprint(
            syntheticCamera(
                outputPixelSize = OutputPixelSize(3, 3),
                mercatorAnchor = MercatorPosition(0.5, 0.0625, 0.0),
                worldSizeLogicalPixels = 8.0,
            ),
        )

        assertPolygon(
            footprint,
            listOf(
                MercatorGroundPoint(0.375, 0.0),
                MercatorGroundPoint(0.625, 0.0),
                MercatorGroundPoint(0.625, 0.1875),
                MercatorGroundPoint(0.375, 0.1875),
            ),
        )
    }

    @Test
    fun maximumYSupportPlaneClipsAtItsExactClosedBoundary() {
        val footprint = clippedPhysicalPixelFootprint(
            syntheticCamera(
                outputPixelSize = OutputPixelSize(3, 3),
                mercatorAnchor = MercatorPosition(0.5, 0.9375, 0.0),
                worldSizeLogicalPixels = 8.0,
            ),
        )

        assertPolygon(
            footprint,
            listOf(
                MercatorGroundPoint(0.375, 0.8125),
                MercatorGroundPoint(0.625, 0.8125),
                MercatorGroundPoint(0.625, 1.0),
                MercatorGroundPoint(0.375, 1.0),
            ),
        )
    }

    @Test
    fun edgeAndCornerTangenciesRemainAClosedSegmentAndPoint() {
        val edgeTangent = clippedPhysicalPixelFootprint(
            syntheticCamera(
                outputPixelSize = OutputPixelSize(3, 3),
                mercatorAnchor = MercatorPosition(-16384.125, 0.5, 0.0),
                worldSizeLogicalPixels = 8.0,
            ),
        )
        val cornerTangent = clippedPhysicalPixelFootprint(
            syntheticCamera(
                outputPixelSize = OutputPixelSize(3, 3),
                mercatorAnchor = MercatorPosition(-16384.125, -0.125, 0.0),
                worldSizeLogicalPixels = 8.0,
            ),
        )

        assertEquals(
            ClosedMercatorFootprint.Segment(
                start = MercatorGroundPoint(-16384.0, 0.375),
                end = MercatorGroundPoint(-16384.0, 0.625),
            ),
            edgeTangent,
        )
        assertEquals(
            ClosedMercatorFootprint.Point(MercatorGroundPoint(-16384.0, 0.0)),
            cornerTangent,
        )
    }

    @Test
    fun exactHorizonRowIsExcludedBeforeProjectingTheClosedPixelCentreRectangle() {
        val inverseTwoFocalLengths = 0.5 / FOCAL_LENGTH_SCALE
        val camera = syntheticCamera(
            outputPixelSize = OutputPixelSize(3, 2),
            worldSizeLogicalPixels = 10.0,
            cameraUp = DoubleVector3(0.0, 0.0, 1.0),
            cameraBack = DoubleVector3(0.0, 0.0, inverseTwoFocalLengths),
            cameraDistanceLogicalPixels = 4.0,
        )

        assertEquals(GroundRayResult.HorizonOrSky, physicalPixelGroundRay(camera, 0, 0))
        assertSegment(
            footprint = clippedPhysicalPixelFootprint(camera),
            expectedStart = MercatorGroundPoint(0.41715728752538095, 0.7),
            expectedEnd = MercatorGroundPoint(0.582842712474619, 0.7),
        )
    }

    @Test
    fun nearClippedRowsAreExcludedFromBothEndsOfTheAdmissibleRowSet() {
        val camera = syntheticCamera(
            outputPixelSize = OutputPixelSize(3, 5),
            worldSizeLogicalPixels = 10.0,
            cameraUp = DoubleVector3(0.0, 0.0, 1.0),
            cameraBack = DoubleVector3(0.0, 0.0, 0.1),
            cameraDistanceLogicalPixels = 2.0,
        )

        assertEquals(GroundRayResult.HorizonOrSky, physicalPixelGroundRay(camera, 0, 1))
        assertIs<GroundRayResult.Hit>(physicalPixelGroundRay(camera, 0, 2))
        assertEquals(GroundRayResult.NearClipped, physicalPixelGroundRay(camera, 0, 3))
        assertSegment(
            footprint = clippedPhysicalPixelFootprint(camera),
            expectedStart = MercatorGroundPoint(0.4668629150101524, 0.5),
            expectedEnd = MercatorGroundPoint(0.5331370849898476, 0.5),
        )
    }

    @Test
    fun entirelySkyNearClippedAndOutOfSupportViewsProduceDeterministicEmptyFootprints() {
        val allSky = syntheticCamera(
            outputPixelSize = OutputPixelSize(1, 1),
            cameraUp = DoubleVector3(0.0, 0.0, 1.0),
            cameraBack = DoubleVector3(0.0, 0.0, 0.0),
        )
        val allNearClipped = syntheticCamera(
            outputPixelSize = OutputPixelSize(3, 3),
            cameraDistanceLogicalPixels = 0.5,
        )
        val outOfSupport = syntheticCamera(
            outputPixelSize = OutputPixelSize(3, 3),
            mercatorAnchor = MercatorPosition(17000.0, 0.5, 0.0),
            worldSizeLogicalPixels = 8.0,
        )

        assertEquals(ClosedMercatorFootprint.Empty, clippedPhysicalPixelFootprint(allSky))
        assertEquals(ClosedMercatorFootprint.Empty, clippedPhysicalPixelFootprint(allNearClipped))
        assertEquals(ClosedMercatorFootprint.Empty, clippedPhysicalPixelFootprint(outOfSupport))
    }

    private fun resolve(outputPixelSize: OutputPixelSize): ResolvedMercatorCamera =
        assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
            resolveMercatorCamera(
                camera = Camera(
                    latitude = 0.0,
                    unwrappedLongitude = 0.0,
                    zoom = 0.0,
                    bearing = 0.0,
                    pitch = 0.0,
                ),
                outputPixelSize = outputPixelSize,
            ),
        ).value

    private fun syntheticCamera(
        outputPixelSize: OutputPixelSize,
        mercatorAnchor: MercatorPosition = MercatorPosition(0.5, 0.5, 0.0),
        worldSizeLogicalPixels: Double = 512.0,
        right: DoubleVector3 = DoubleVector3(1.0, 0.0, 0.0),
        cameraUp: DoubleVector3 = DoubleVector3(0.0, 1.0, 0.0),
        cameraBack: DoubleVector3 = DoubleVector3(0.0, 0.0, 1.0),
        cameraDistanceLogicalPixels: Double =
            outputPixelSize.height.toDouble() * FOCAL_LENGTH_SCALE / 2.0,
    ): ResolvedMercatorCamera = ResolvedMercatorCamera(
        outputPixelSize = outputPixelSize,
        mercatorAnchor = mercatorAnchor,
        worldSizeLogicalPixels = worldSizeLogicalPixels,
        right = right,
        cameraUp = cameraUp,
        cameraBack = cameraBack,
        cameraDistanceLogicalPixels = cameraDistanceLogicalPixels,
        viewMatrix = DoubleMatrix4.identity,
        projectionMatrix = DoubleMatrix4.identity,
        geographicGroundAnchor = GeographicPosition(0.0, 0.0, 0.0),
    )

    private fun assertSegment(
        footprint: ClosedMercatorFootprint,
        expectedStart: MercatorGroundPoint,
        expectedEnd: MercatorGroundPoint,
    ) {
        val actual = assertIs<ClosedMercatorFootprint.Segment>(footprint)
        assertClose(expectedStart.x, actual.start.x)
        assertClose(expectedStart.y, actual.start.y)
        assertClose(expectedEnd.x, actual.end.x)
        assertClose(expectedEnd.y, actual.end.y)
    }

    private fun assertPolygon(
        footprint: ClosedMercatorFootprint,
        expected: List<MercatorGroundPoint>,
    ) {
        val actual = assertIs<ClosedMercatorFootprint.Polygon>(footprint).vertices
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedPoint, actualPoint) ->
            assertClose(expectedPoint.x, actualPoint.x)
            assertClose(expectedPoint.y, actualPoint.y)
        }
        assertNotEquals(actual.first(), actual.last())
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= GEOMETRIC_EPSILON,
            "Expected $expected but was $actual",
        )
    }

    private companion object {
        val FOCAL_LENGTH_SCALE: Double = 1.0 + sqrt(2.0)
        const val GEOMETRIC_EPSILON: Double = 1e-10
    }
}
