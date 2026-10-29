package com.rohittp.reng.internal.gl

import com.rohittp.reng.Camera
import com.rohittp.reng.GroundRectangle
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolveMercatorRectangleCorners
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * ADR 0087's window-to-ground homography on the CPU, against the camera's own projection: each
 * rectangle corner, projected to its window pixel through the frame's view-projection and viewport,
 * must come back as that corner's texture coordinate through the matrix the shader receives -- in the
 * shader's own `Float` arithmetic.
 */
class StickerGroundTest {

    @Test
    fun eachCornerProjectedToTheWindowMapsBackToItsTextureCoordinate() {
        for (pitch in listOf(0.0, 30.0, 60.0)) {
            for (bearing in listOf(0.0, 37.0, 240.0)) {
                val camera = mercator(Camera(10.0, 20.0, 9.0, bearing, pitch))
                val corners = (resolveMercatorRectangleCorners(RECTANGLE.topLeft, RECTANGLE.bottomRight, camera)
                    as SpatialOutcome.Success).value
                val matrix = stickerWindowToGround(camera, RECTANGLE)
                val expected = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
                corners.forEachIndexed { index, corner ->
                    val (windowX, windowY) = window(camera, corner)
                    val (u, v) = groundUv(matrix, windowX.toFloat(), windowY.toFloat())
                    assertTrue(
                        abs(u - expected[index].first) < 1e-4 && abs(v - expected[index].second) < 1e-4,
                        "pitch $pitch bearing $bearing corner $index read ($u, $v)",
                    )
                }
            }
        }
    }

    /** Only the scale-free ratio matters, so the matrix is normalised to a largest element of one. */
    @Test
    fun theMatrixIsNormalisedAndLeavesTheZRowAndColumnEmpty() {
        val matrix = stickerWindowToGround(mercator(Camera(10.0, 20.0, 9.0, 0.0, 45.0)), RECTANGLE)
        assertTrue(abs(matrix.maxOf { abs(it) } - 1f) < 1e-6f, matrix.contentToString())
        for (index in listOf(2, 6, 8, 9, 10, 11, 14)) assertTrue(matrix[index] == 0f, "element $index")
    }

    /** No rectangle, or a globe, is the zero matrix, which the shader reads as off-rectangle everywhere. */
    @Test
    fun noRectangleAndAGlobeCameraBothBindTheZeroMatrix() {
        val mercator = mercator(Camera(10.0, 20.0, 9.0, 0.0, 0.0))
        assertContentEquals(FloatArray(16), stickerWindowToGround(mercator, null))
        val globe = (resolveGlobeCamera(Camera(10.0, 20.0, 3.0, 0.0, 0.0), OUTPUT) as SpatialOutcome.Success).value
        assertContentEquals(FloatArray(16), stickerWindowToGround(globe, RECTANGLE))
    }

    /** Above the horizon the line of sight meets the plane behind the camera: `w` is not positive. */
    @Test
    fun aPixelAboveTheHorizonHasANonPositiveW() {
        val camera = mercator(Camera(10.0, 20.0, 9.0, 0.0, 85.0))
        val matrix = stickerWindowToGround(camera, RECTANGLE)
        val top = OUTPUT.height - 0.5f
        val w = matrix[3] * (OUTPUT.width / 2f) + matrix[7] * top + matrix[15]
        assertTrue(w <= 0f, "w at the top row was $w")
    }

    private fun groundUv(matrix: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val hx = matrix[0] * x + matrix[4] * y + matrix[12]
        val hy = matrix[1] * x + matrix[5] * y + matrix[13]
        val hw = matrix[3] * x + matrix[7] * y + matrix[15]
        return (hx / hw) to (hy / hw)
    }

    private fun window(camera: ResolvedMercatorCamera, point: DoubleVector3): Pair<Double, Double> {
        val m = camera.viewProjection
        fun row(r: Int) = m[r, 0] * point.x + m[r, 1] * point.y + m[r, 2] * point.z + m[r, 3]
        val w = row(3)
        return (row(0) / w + 1.0) * 0.5 * OUTPUT.width to (row(1) / w + 1.0) * 0.5 * OUTPUT.height
    }

    private fun mercator(camera: Camera): ResolvedMercatorCamera =
        (resolveMercatorCamera(camera, OUTPUT) as SpatialOutcome.Success).value

    private companion object {
        val OUTPUT: OutputPixelSize = OutputPixelSize(320, 240)
        val RECTANGLE: GroundRectangle = GroundRectangle(Vector3(10.05, 19.96, 120.0), Vector3(9.97, 20.03, 40.0))
    }
}
