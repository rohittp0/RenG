package com.rohittp.reng.internal.planning

import com.rohittp.reng.Camera
import com.rohittp.reng.DiagnosticCode
import com.rohittp.reng.DiagnosticSeverity
import com.rohittp.reng.Geometry
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.projectMercator
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryResolverTest {
    @Test
    fun geometryUsesFrozenClockwiseCornersAndNorthSouthAltitudes() {
        val camera = resolvedCamera(unwrappedLongitude = 0.0, zoom = 0.0)
        val topLeft = Vector3(20.0, 360.0, 100.0)
        val bottomRight = Vector3(-10.0, 400.0, 20.0)
        val shaderPair = ShaderPair("vertex", "fragment")
        val resolved = resolveGeometry(
            Geometry(topLeft = topLeft, bottomRight = bottomRight, shaderPair = shaderPair),
            camera,
        ).successValue()
        val expectedGeographicCorners = listOf(
            GeographicPosition(topLeft.x, topLeft.y, topLeft.z),
            GeographicPosition(topLeft.x, bottomRight.y, topLeft.z),
            GeographicPosition(bottomRight.x, bottomRight.y, bottomRight.z),
            GeographicPosition(bottomRight.x, topLeft.y, bottomRight.z),
        )

        assertEquals(shaderPair, resolved.shaderPair)
        assertVectorListsClose(
            expectedGeographicCorners.map { mapLogicalPosition(it, camera) },
            resolved.cornersClockwiseFromTopLeft,
        )
        assertClose(camera.worldSizeLogicalPixels, resolved.cornersClockwiseFromTopLeft[0].x)
        assertTrue(
            resolved.cornersClockwiseFromTopLeft[1].x > camera.worldSizeLogicalPixels,
            "unwrapped longitudes must not select a nearest periodic copy",
        )
        assertEquals(
            resolved.cornersClockwiseFromTopLeft[0].z,
            resolved.cornersClockwiseFromTopLeft[1].z,
        )
        assertEquals(
            resolved.cornersClockwiseFromTopLeft[2].z,
            resolved.cornersClockwiseFromTopLeft[3].z,
        )
    }

    @Test
    fun resolvedGeometryRequiresFourSnapshotsAndDefensivelyReturnsStructuralCorners() {
        val originalFirst = DoubleVector3(1.0, 2.0, 3.0)
        val supplied = mutableListOf(
            originalFirst,
            DoubleVector3(4.0, 5.0, 6.0),
            DoubleVector3(7.0, 8.0, 9.0),
            DoubleVector3(10.0, 11.0, 12.0),
        )
        val shaderPair = ShaderPair("vertex", "fragment")
        val resolved = ResolvedGeometry(supplied, shaderPair)
        supplied[0] = DoubleVector3(99.0, 99.0, 99.0)

        val firstRead = resolved.cornersClockwiseFromTopLeft
        val secondRead = resolved.cornersClockwiseFromTopLeft
        val equal = ResolvedGeometry(firstRead, shaderPair)
        val differentCorner = ResolvedGeometry(
            firstRead.dropLast(1) + DoubleVector3(13.0, 14.0, 15.0),
            shaderPair,
        )
        val differentShader = ResolvedGeometry(firstRead, ShaderPair("other vertex", "fragment"))

        assertEquals(originalFirst, firstRead[0])
        assertNotSame(firstRead, secondRead)
        assertEquals(firstRead, secondRead)
        assertEquals(resolved, equal)
        assertEquals(resolved.hashCode(), equal.hashCode())
        assertNotEquals(resolved, differentCorner)
        assertNotEquals(resolved, differentShader)
        assertFailsWith<IllegalArgumentException> {
            ResolvedGeometry(firstRead.take(3), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            ResolvedGeometry(firstRead + DoubleVector3(13.0, 14.0, 15.0), shaderPair)
        }
    }

    @Test
    fun geometryFailuresUseExactLatitudeLongitudeAndAltitudeDiagnostics() {
        val camera = resolvedCamera()
        val invalidCopyLongitude = 16385.0 * 360.0 - 180.0

        assertFailure(
            resolveGeometry(
                Geometry(
                    topLeft = Vector3(86.0, 0.0, 0.0),
                    bottomRight = Vector3(0.0, 1.0, 0.0),
                    shaderPair = ShaderPair("vertex", "fragment"),
                ),
                camera,
            ),
            "geometry.latitude",
        )
        assertFailure(
            resolveGeometry(
                Geometry(
                    topLeft = Vector3(10.0, invalidCopyLongitude, 0.0),
                    bottomRight = Vector3(0.0, invalidCopyLongitude + 1.0, 0.0),
                    shaderPair = ShaderPair("vertex", "fragment"),
                ),
                camera,
            ),
            "geometry.unwrappedLongitude",
        )
        assertFailure(
            resolveGeometry(
                Geometry(
                    topLeft = Vector3(10.0, 0.0, Double.MAX_VALUE),
                    bottomRight = Vector3(0.0, 1.0, 0.0),
                    shaderPair = ShaderPair("vertex", "fragment"),
                ),
                camera,
            ),
            "geometry.altitude",
        )
    }

    private fun SpatialOutcome<ResolvedGeometry>.successValue(): ResolvedGeometry =
        assertIs<SpatialOutcome.Success<ResolvedGeometry>>(this).value

    private fun resolvedCamera(
        latitude: Double = 0.0,
        unwrappedLongitude: Double = 0.0,
        zoom: Double = 0.0,
    ): ResolvedMercatorCamera = assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
        resolveMercatorCamera(
            Camera(latitude, unwrappedLongitude, zoom, bearing = 0.0, pitch = 0.0),
            OutputPixelSize(width = 100, height = 100),
        ),
    ).value

    private fun mapLogicalPosition(
        position: GeographicPosition,
        camera: ResolvedMercatorCamera,
    ): DoubleVector3 {
        val projected = projectMercator(position)
        return DoubleVector3(
            x = (projected.x - camera.mercatorAnchor.x) * camera.worldSizeLogicalPixels,
            y = (camera.mercatorAnchor.y - projected.y) * camera.worldSizeLogicalPixels,
            z = projected.z * camera.worldSizeLogicalPixels,
        )
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

    private fun assertVectorListsClose(expected: List<DoubleVector3>, actual: List<DoubleVector3>) {
        assertEquals(expected.size, actual.size)
        for (index in expected.indices) {
            assertClose(expected[index].x, actual[index].x)
            assertClose(expected[index].y, actual[index].y)
            assertClose(expected[index].z, actual[index].z)
        }
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-11) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected $expected but was $actual")
    }
}
