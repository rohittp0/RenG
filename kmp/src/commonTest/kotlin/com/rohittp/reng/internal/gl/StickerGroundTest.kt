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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sinh
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR 0087's window-to-ground homography on the CPU, against the camera's own projection, and in the
 * shader's own `Float` arithmetic: `gl_FragCoord.xy - origin` (exact), then the narrowed matrix.
 */
class StickerGroundTest {

    @Test
    fun eachCornerProjectedToTheWindowMapsBackToItsTextureCoordinate() {
        for (pitch in listOf(0.0, 30.0, 60.0)) {
            for (bearing in listOf(0.0, 37.0, 240.0)) {
                val camera = mercator(Camera(10.0, 20.0, 9.0, bearing, pitch), OUTPUT)
                val corners = (resolveMercatorRectangleCorners(RECTANGLE.topLeft, RECTANGLE.bottomRight, camera)
                    as SpatialOutcome.Success).value
                val mapping = stickerGroundMapping(camera, RECTANGLE)
                assertTrue(mapping.present)
                val expected = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
                corners.forEachIndexed { index, corner ->
                    val (windowX, windowY) = window(camera, OUTPUT, corner)
                    val (u, v) = assertNotNull(shaderGroundUv(mapping, windowX.toFloat(), windowY.toFloat()))
                    assertTrue(
                        abs(u - expected[index].first) < 1e-4 && abs(v - expected[index].second) < 1e-4,
                        "pitch $pitch bearing $bearing corner $index read ($u, $v)",
                    )
                }
            }
        }
    }

    /**
     * The review's case: a small rectangle hard against each edge and corner of a 16K frame. Every pixel
     * of it, evaluated as the shader does, must agree with the `Double` map to within 1e-4 of a pixel
     * (measured 1.3e-6 on Apple M3 Max) -- and, so the test is known to measure what the origin buys,
     * the same map against raw `gl_FragCoord` must do measurably worse (measured 1.5e-3 of a pixel).
     */
    @Test
    fun aSmallRectangleNearEveryEdgeOfTheLargestFrameIsExactInFloat() {
        val size = OutputPixelSize(MAXIMUM_SIDE, MAXIMUM_SIDE)
        val camera = mercator(Camera(0.0, 0.0, ZOOM.toDouble(), 0.0, 0.0), size)
        val placements = listOf(0.02, 0.5, 0.98)
        var worstUncentred = 0.0
        var worstCentred = 0.0
        for (fx in placements) {
            for (fy in placements) {
                if (fx == 0.5 && fy == 0.5) continue
                val rectangle = rectangleAroundPixel(size, fx * size.width, fy * size.height, sidePixels = 24.0)
                val centred = measure(camera, size, rectangle, centred = true)
                assertTrue(centred.pixels > 200, "fraction ($fx, $fy): the rectangle must land in the frame")
                assertTrue(centred.worst < 1e-4, "fraction ($fx, $fy): centred map erred by ${centred.worst} pixels")
                worstCentred = max(worstCentred, centred.worst)
                worstUncentred = max(worstUncentred, measure(camera, size, rectangle, centred = false).worst)
            }
        }
        println(
            "RenG sticker ground precision: 16K frame, centred worst = $worstCentred pixels, " +
                "uncentred control worst = $worstUncentred pixels",
        )
        assertTrue(worstUncentred > 4 * worstCentred, "the uncentred control must show the error the origin removes")
    }

    /**
     * A large rectangle at street zoom, read over the whole frame: the coordinate is one `Float` across
     * the whole rectangle, so its resolution on screen shrinks as the rectangle grows. Measured 1.07
     * pixels for 40 degrees and 5.4 for 170; bounded for 40 degrees. Consumers keep the rectangle tight
     * (ADR 0087).
     */
    @Test
    fun aContinentSizedRectangleAtStreetZoomIsReportedNotHidden() {
        val size = OutputPixelSize(1920, 1080)
        val camera = mercator(Camera(30.0, 10.0, 18.0, 0.0, 45.0), size)
        for ((span, bound) in listOf(40.0 to 2.0, 170.0 to Double.MAX_VALUE)) {
            val rectangle = GroundRectangle(
                Vector3(30.0 + span / 4, 10.0 - span / 2, 0.0),
                Vector3(30.0 - span / 4, 10.0 + span / 2, 0.0),
            )
            val result = measure(camera, size, rectangle, centred = true, wholeFrame = true)
            println("RenG sticker ground precision: z18, ${span.toInt()} degree rectangle, worst = ${result.worst} pixels")
            assertTrue(result.pixels > 1000)
            assertTrue(result.worst < bound, "a $span degree rectangle erred by ${result.worst} pixels")
        }
    }

    @Test
    fun noRectangleAndAGlobeCameraHaveNoGroundAndAreNotUnavailable() {
        val mercator = mercator(Camera(10.0, 20.0, 9.0, 0.0, 0.0), OUTPUT)
        val none = stickerGroundMapping(mercator, null)
        assertFalse(none.present)
        assertFalse(none.unavailable)
        assertContentEquals(FloatArray(16), none.windowToGround)
        val globe = (resolveGlobeCamera(Camera(10.0, 20.0, 3.0, 0.0, 0.0), OUTPUT) as SpatialOutcome.Success).value
        val onGlobe = stickerGroundMapping(globe, RECTANGLE)
        assertFalse(onGlobe.present)
        assertFalse(onGlobe.unavailable)
        assertContentEquals(FloatArray(16), onGlobe.windowToGround)
    }

    /** A rectangle beyond Mercator's latitude limit is no ground, and the one case reported. */
    @Test
    fun aRectangleMercatorCannotCarryIsUnavailableRatherThanAFailure() {
        val camera = mercator(Camera(10.0, 20.0, 9.0, 0.0, 0.0), OUTPUT)
        val mapping = stickerGroundMapping(camera, GroundRectangle(Vector3(89.0, 0.0, 0.0), Vector3(80.0, 1.0, 0.0)))
        assertFalse(mapping.present)
        assertTrue(mapping.unavailable)
        assertContentEquals(FloatArray(16), mapping.windowToGround)
    }

    /** Above the horizon there is no ground coordinate, while the frame still has ground. */
    @Test
    fun aPixelAboveTheHorizonHasNoGroundCoordinateButTheFrameHasGround() {
        val camera = mercator(Camera(10.0, 20.0, 9.0, 0.0, 85.0), OUTPUT)
        val mapping = stickerGroundMapping(camera, RECTANGLE)
        assertTrue(mapping.present)
        assertEquals(null, shaderGroundUv(mapping, OUTPUT.width / 2f, OUTPUT.height - 0.5f))
    }

    /** The origin is a whole pixel inside the frame, so `gl_FragCoord.xy - origin` is exact in `Float`. */
    @Test
    fun theOriginIsAWholePixelInsideTheFrame() {
        val mapping = stickerGroundMapping(mercator(Camera(10.0, 20.0, 9.0, 0.0, 45.0), OUTPUT), RECTANGLE)
        assertEquals(mapping.originX, round(mapping.originX))
        assertEquals(mapping.originY, round(mapping.originY))
        assertTrue(mapping.originX in 0f..OUTPUT.width.toFloat() && mapping.originY in 0f..OUTPUT.height.toFloat())
    }

    private class Measurement(val worst: Double, val pixels: Int)

    /**
     * The largest error, over pixel centres inside [rectangle]'s window footprint (or the whole frame),
     * between the shader's `Float` evaluation and the `Double` map, in screen pixels: the coordinate
     * error over the coordinate's change per pixel there.
     */
    private fun measure(
        camera: ResolvedMercatorCamera,
        size: OutputPixelSize,
        rectangle: GroundRectangle,
        centred: Boolean,
        wholeFrame: Boolean = false,
    ): Measurement {
        val corners = (resolveMercatorRectangleCorners(rectangle.topLeft, rectangle.bottomRight, camera)
            as SpatialOutcome.Success).value
        val exact = assertNotNull(
            windowToRectangle(
                camera.viewProjection, corners[0], corners[1], corners[3],
                size.width.toDouble(), size.height.toDouble(),
                origin = if (centred) null else 0.0 to 0.0,
            ),
        )
        val narrowed = exact.narrowed()
        val windowCorners = corners.map { window(camera, size, it) }
        fun clampX(value: Double) = value.toInt().coerceIn(0, size.width - 1)
        fun clampY(value: Double) = value.toInt().coerceIn(0, size.height - 1)
        val xs = if (wholeFrame) 0 until size.width else clampX(windowCorners.minOf { it.first })..clampX(windowCorners.maxOf { it.first })
        val ys = if (wholeFrame) 0 until size.height else clampY(windowCorners.minOf { it.second })..clampY(windowCorners.maxOf { it.second })
        val stride = if (wholeFrame) 7 else 1
        var worst = 0.0
        var pixels = 0
        for (y in ys step stride) {
            for (x in xs step stride) {
                val windowX = x + 0.5
                val windowY = y + 0.5
                val truth = exact.groundUv(windowX, windowY) ?: continue
                if (!wholeFrame && (truth.first !in 0.0..1.0 || truth.second !in 0.0..1.0)) continue
                val shader = shaderGroundUv(narrowed, windowX.toFloat(), windowY.toFloat()) ?: continue
                val stepX = exact.groundUv(windowX + 1.0, windowY) ?: continue
                val stepY = exact.groundUv(windowX, windowY + 1.0) ?: continue
                val perPixel = max(
                    max(abs(stepX.first - truth.first), abs(stepX.second - truth.second)),
                    max(abs(stepY.first - truth.first), abs(stepY.second - truth.second)),
                )
                val error = max(abs(shader.first - truth.first), abs(shader.second - truth.second)) / perPixel
                worst = max(worst, error)
                pixels += 1
            }
        }
        return Measurement(worst, pixels)
    }

    /**
     * The rectangle whose window footprint is a square of [sidePixels] centred on the given window pixel
     * (origin bottom-left), for a pitch-0, bearing-0 camera at latitude 0, longitude 0 and [ZOOM], where
     * a logical pixel is one pixel of a `512 * 2^zoom` Mercator world.
     */
    private fun rectangleAroundPixel(
        size: OutputPixelSize,
        windowX: Double,
        windowY: Double,
        sidePixels: Double,
    ): GroundRectangle {
        val worldSize = 512.0 * (1 shl ZOOM)
        fun lon(px: Double) = (px - size.width / 2.0) / worldSize * 360.0
        fun lat(py: Double): Double {
            val worldY = 0.5 * worldSize - (py - size.height / 2.0)
            return atan(sinh(PI * (1.0 - 2.0 * worldY / worldSize))) * 180.0 / PI
        }
        val half = sidePixels / 2.0
        return GroundRectangle(
            Vector3(lat(windowY + half), lon(windowX - half), 0.0),
            Vector3(lat(windowY - half), lon(windowX + half), 0.0),
        )
    }

    /** The preamble's `rengStickerGroundUv()`, in `Float`, or `null` for its `vec2(-1.0)`. */
    private fun shaderGroundUv(mapping: StickerGroundMapping, x: Float, y: Float): Pair<Float, Float>? {
        val m = mapping.windowToGround
        val px = x - mapping.originX
        val py = y - mapping.originY
        val hx = m[0] * px + m[4] * py + m[12]
        val hy = m[1] * px + m[5] * py + m[13]
        val hw = m[3] * px + m[7] * py + m[15]
        return if (hw > 0f) (hx / hw) to (hy / hw) else null
    }

    private fun window(camera: ResolvedMercatorCamera, size: OutputPixelSize, point: DoubleVector3): Pair<Double, Double> {
        val m = camera.viewProjection
        fun row(r: Int) = m[r, 0] * point.x + m[r, 1] * point.y + m[r, 2] * point.z + m[r, 3]
        val w = row(3)
        return (row(0) / w + 1.0) * 0.5 * size.width to (row(1) / w + 1.0) * 0.5 * size.height
    }

    private fun mercator(camera: Camera, size: OutputPixelSize): ResolvedMercatorCamera =
        (resolveMercatorCamera(camera, size) as SpatialOutcome.Success).value

    private companion object {
        val OUTPUT: OutputPixelSize = OutputPixelSize(320, 240)
        val RECTANGLE: GroundRectangle = GroundRectangle(Vector3(10.05, 19.96, 120.0), Vector3(9.97, 20.03, 40.0))
        const val MAXIMUM_SIDE: Int = 16_384
        const val ZOOM: Int = 12
    }
}
