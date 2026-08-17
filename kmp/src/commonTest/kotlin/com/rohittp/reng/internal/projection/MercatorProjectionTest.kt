package com.rohittp.reng.internal.projection

import com.rohittp.reng.DiagnosticCode
import com.rohittp.reng.DiagnosticSeverity
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MercatorProjectionTest {
    @Test
    fun projectMercatorUsesExactLatitudeEndpointBranches() {
        val north = projectMercator(GeographicPosition(MERCATOR_MAXIMUM_LATITUDE_DEGREES, -180.0, 0.0))
        val south = projectMercator(GeographicPosition(-MERCATOR_MAXIMUM_LATITUDE_DEGREES, 180.0, 0.0))

        assertEquals(0.0, north.x)
        assertEquals(0.0, north.y)
        assertEquals(0.0, north.z)
        assertEquals(1.0, south.x)
        assertEquals(1.0, south.y)
        assertEquals(0.0, south.z)
    }

    @Test
    fun projectedXIsNonperiodicAcrossWorldCopies() {
        val base = projectMercator(GeographicPosition(0.0, 12.5, 0.0))
        val shifted = projectMercator(GeographicPosition(0.0, 12.5 + 720.0, 0.0))

        assertEquals(base.x + 2.0, shifted.x)
        assertEquals(base.y, shifted.y)
        assertEquals(base.z, shifted.z)
    }

    @Test
    fun copyDomainAndClosedPlanningSupportHaveDistinctUpperEdges() {
        val firstCopy = validateMercatorMapPosition(
            GeographicPosition(0.0, -16384.0 * 360.0 - 180.0, 0.0),
        )
        val lastCopy = validateMercatorMapPosition(
            GeographicPosition(0.0, 16384.0 * 360.0 - 180.0, 0.0),
        )
        val beyondLastCopy = validateMercatorMapPosition(
            GeographicPosition(0.0, 16385.0 * 360.0 - 180.0, 0.0),
        )

        assertEquals(-16384.0, assertIs<SpatialOutcome.Success<MercatorPosition>>(firstCopy).value.x)
        assertEquals(16384.0, assertIs<SpatialOutcome.Success<MercatorPosition>>(lastCopy).value.x)
        assertFailure(beyondLastCopy, "mapPosition.unwrappedLongitude")
        assertTrue(isWithinMercatorPlanningSupport(-16384.0, 0.0))
        assertTrue(isWithinMercatorPlanningSupport(16385.0, 1.0))
        assertFalse(isWithinMercatorPlanningSupport(-16384.0000000001, 0.5))
        assertFalse(isWithinMercatorPlanningSupport(16385.0000000001, 0.5))
        assertFalse(isWithinMercatorPlanningSupport(0.5, -0.0000000001))
        assertFalse(isWithinMercatorPlanningSupport(0.5, 1.0000000001))
    }

    @Test
    fun wgs84FrameUsesEllipsoidAtEquatorAndBothPoles() {
        val equator = wgs84LocalFrame(GeographicPosition(0.0, 0.0, 0.0))
        assertVectorClose(DoubleVector3(6378137.0, 0.0, 0.0), equator.ecefPosition)
        assertVectorClose(DoubleVector3(0.0, 1.0, 0.0), equator.basisEastNorthUp.column(0))
        assertVectorClose(DoubleVector3(0.0, 0.0, 1.0), equator.basisEastNorthUp.column(1))
        assertVectorClose(DoubleVector3(1.0, 0.0, 0.0), equator.basisEastNorthUp.column(2))

        val northPole = wgs84LocalFrame(GeographicPosition(90.0, 0.0, 0.0))
        assertVectorClose(DoubleVector3(0.0, 0.0, 6356752.314245179), northPole.ecefPosition, 1e-8)
        assertVectorClose(DoubleVector3(0.0, 1.0, 0.0), northPole.basisEastNorthUp.column(0), 1e-12)
        assertVectorClose(DoubleVector3(-1.0, 0.0, 0.0), northPole.basisEastNorthUp.column(1), 1e-12)
        assertVectorClose(DoubleVector3(0.0, 0.0, 1.0), northPole.basisEastNorthUp.column(2), 1e-12)

        val southPole = wgs84LocalFrame(GeographicPosition(-90.0, 0.0, 0.0))
        assertVectorClose(DoubleVector3(0.0, 0.0, -6356752.314245179), southPole.ecefPosition, 1e-8)
        assertVectorClose(DoubleVector3(0.0, 1.0, 0.0), southPole.basisEastNorthUp.column(0), 1e-12)
        assertVectorClose(DoubleVector3(1.0, 0.0, 0.0), southPole.basisEastNorthUp.column(1), 1e-12)
        assertVectorClose(DoubleVector3(0.0, 0.0, -1.0), southPole.basisEastNorthUp.column(2), 1e-12)
    }

    @Test
    fun wgs84FrameUsesSpecifiedFlatteningAndCanonicalLongitude() {
        val frame = wgs84LocalFrame(GeographicPosition(45.0, 45.0, 1000.0))
        val copyEquivalent = wgs84LocalFrame(GeographicPosition(45.0, 405.0, 1000.0))

        assertVectorClose(
            DoubleVector3(3194919.1450605746, 3194919.145060574, 4488055.515647106),
            frame.ecefPosition,
            1e-8,
        )
        assertEquals(frame, copyEquivalent)
    }

    @Test
    fun altitudeScaleUsesThePointLatitude() {
        val equator = projectMercator(GeographicPosition(0.0, 0.0, WORLD_CIRCUMFERENCE_METRES))
        val sixtyDegrees = projectMercator(
            GeographicPosition(60.0, 0.0, WORLD_CIRCUMFERENCE_METRES * kotlin.math.cos(kotlin.math.PI / 3.0)),
        )

        assertClose(1.0, equator.z)
        assertClose(1.0, sixtyDegrees.z)
    }

    @Test
    fun mercatorValidationUsesSanitizedFieldsAndLatitudeCopyAltitudePrecedence() {
        val allInvalid = validateMercatorMapPosition(
            GeographicPosition(
                MERCATOR_MAXIMUM_LATITUDE_DEGREES + 1.0,
                16385.0 * 360.0 - 180.0,
                Double.POSITIVE_INFINITY,
            ),
        )
        val invalidCopyAndAltitude = validateMercatorMapPosition(
            GeographicPosition(0.0, 16385.0 * 360.0 - 180.0, Double.POSITIVE_INFINITY),
        )
        val invalidAltitude = validateMercatorMapPosition(
            GeographicPosition(0.0, 0.0, Double.POSITIVE_INFINITY),
        )
        val invalidCameraLatitude = validateMercatorCamera(
            GeographicPosition(MERCATOR_MAXIMUM_LATITUDE_DEGREES + 1.0, 0.0, 0.0),
        )
        val invalidGeometryCopy = validateMercatorGeometryPosition(
            GeographicPosition(0.0, Double.NaN, 0.0),
        )

        assertFailure(allInvalid, "mapPosition.latitude")
        assertFailure(invalidCopyAndAltitude, "mapPosition.unwrappedLongitude")
        assertFailure(invalidAltitude, "mapPosition.altitude")
        assertFailure(invalidCameraLatitude, "camera.latitude")
        assertFailure(invalidGeometryCopy, "geometry.unwrappedLongitude")
    }

    private fun assertFailure(outcome: SpatialOutcome<*>, fieldName: String) {
        val failure = assertIs<SpatialOutcome.Failure>(outcome).failure
        assertEquals(RenGErrorCode.INVALID_VALUE, failure.code)
        assertEquals(PipelineStage.FRAME_PLANNING, failure.stage)
        val diagnostic = requireNotNull(failure.diagnostic)
        assertEquals(DiagnosticCode.FAILURE_CONTEXT, diagnostic.code)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(PipelineStage.FRAME_PLANNING, diagnostic.stage)
        assertEquals(fieldName, diagnostic.fieldName)
        assertNull(diagnostic.resourceClass)
        assertNull(diagnostic.resourceKey)
        assertNull(diagnostic.statusCode)
        assertNull(diagnostic.limit)
        assertNull(diagnostic.actual)
    }

    private fun assertVectorClose(expected: DoubleVector3, actual: DoubleVector3, tolerance: Double = 1e-10) {
        assertClose(expected.x, actual.x, tolerance)
        assertClose(expected.y, actual.y, tolerance)
        assertClose(expected.z, actual.z, tolerance)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-12) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected $expected but was $actual")
    }
}
