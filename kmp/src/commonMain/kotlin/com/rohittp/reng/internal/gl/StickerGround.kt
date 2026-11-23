package com.rohittp.reng.internal.gl

import com.rohittp.reng.GroundRectangle
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolveMercatorRectangleCorners
import com.rohittp.reng.internal.projection.ResolvedFrameCamera
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What one sticker program's `rengStickerGroundUv()` and `rengStickerHasGround()` read this frame
 * (ADR 0087): a projective map from a window pixel **relative to [originX], [originY]** to the ground
 * rectangle's texture coordinate, and whether there is a rectangle at all.
 *
 * [windowToGround] is column-major, the 3x3 embedded in rows and columns 0, 1 and 3 of a 4x4 (see
 * [STICKER_WINDOW_TO_GROUND_UNIFORM_NAME]); the shader applies it to
 * `vec4(gl_FragCoord.xy - origin, 0.0, 1.0)`.
 *
 * [unavailable] is the one case worth a diagnostic: the sticker declared a rectangle and the frame's
 * Mercator camera could not carry it. A globe frame or a sticker with no rectangle has no ground
 * without anything having gone wrong.
 */
internal class StickerGroundMapping(
    val windowToGround: FloatArray,
    val originX: Float,
    val originY: Float,
    val present: Boolean,
    val unavailable: Boolean = false,
) {
    companion object {
        /** No ground: the zero matrix, which the shader reads as off-rectangle everywhere. */
        val NONE: StickerGroundMapping = StickerGroundMapping(FloatArray(MATRIX_ELEMENT_COUNT), 0f, 0f, false)
        val UNAVAILABLE: StickerGroundMapping =
            StickerGroundMapping(FloatArray(MATRIX_ELEMENT_COUNT), 0f, 0f, present = false, unavailable = true)
    }
}

/**
 * The [StickerGroundMapping] for [ground] under [camera] (ADR 0087).
 *
 * **Why a homography is exact.** The rectangle's four corners are coplanar ([GeometryGrid]'s KDoc
 * says why) and a Geometry's coordinate is affine across that plane -- `NW + u (NE - NW) + v (SW - NW)`
 * -- so the camera maps `(u, v, 1)` to clip `(x, y, w)` by one 3x3 matrix and the viewport maps clip
 * to window by another. The inverse of their product sends a window pixel to `(u, v, 1) / w`, which
 * is what a Geometry's perspective-correct interpolation computes there; `1 / w` is positive exactly
 * where the line of sight meets the plane in front of the camera.
 *
 * **Why the origin, and why it is an integer.** The shader evaluates in `Float`. Against raw
 * `gl_FragCoord` a small rectangle near the far edge of a large frame is a coordinate of order one
 * computed from terms the size of the frame that cancel, which on a 16K frame measured 1.5e-3 of a
 * pixel -- enough to move a nearest-sampled texel a Geometry would not. Centred, 1.3e-6. So the map is built relative to the window
 * pixel nearest the rectangle's projected centre, clamped into the frame: around the rectangle every
 * term is the size of the coordinate itself. The origin is a whole pixel, so the shader's
 * `gl_FragCoord.xy - origin` is exact in `Float` (half-integers below 2^24 minus integers).
 *
 * Everything before the single narrowing is `Double` and camera-relative ([resolveMercatorRectangleCorners]
 * is the Geometry's own corner path); the columns are normalised before inverting and the result scaled
 * to a largest element of one, and a homography means the same at any positive scale, so neither
 * changes an answer.
 */
internal fun stickerGroundMapping(camera: ResolvedFrameCamera, ground: GroundRectangle?): StickerGroundMapping {
    if (ground == null) return StickerGroundMapping.NONE
    return when (camera) {
        // ADR 0087: a globe's ground is a sphere, which no homography maps; the program is told there
        // is no ground rather than handed a plane that is wrong toward the limb.
        is ResolvedGlobeCamera -> StickerGroundMapping.NONE
        is ResolvedMercatorCamera -> {
            val corners = when (
                val outcome = resolveMercatorRectangleCorners(ground.topLeft, ground.bottomRight, camera)
            ) {
                is SpatialOutcome.Success -> outcome.value
                is SpatialOutcome.Failure -> return StickerGroundMapping.UNAVAILABLE
            }
            val exact = windowToRectangle(
                viewProjection = camera.viewProjection,
                northWest = corners[0],
                northEast = corners[1],
                southWest = corners[3],
                widthPixels = camera.outputPixelSize.width.toDouble(),
                heightPixels = camera.outputPixelSize.height.toDouble(),
            ) ?: return StickerGroundMapping.NONE
            exact.narrowed()
        }
    }
}

/**
 * The window-to-rectangle homography in `Double`, before narrowing: [inverse] is row-major 3x3 acting
 * on `(windowX - originX, windowY - originY, 1)`. Kept separate so a test can compare the shader's
 * `Float` evaluation against it.
 */
internal class ExactWindowToRectangle(
    val inverse: Array<DoubleArray>,
    val originX: Double,
    val originY: Double,
) {
    /** `(u, v)` at window pixel [windowX], [windowY], or `null` where the line of sight misses the plane. */
    fun groundUv(windowX: Double, windowY: Double): Pair<Double, Double>? {
        val x = windowX - originX
        val y = windowY - originY
        val w = inverse[2][0] * x + inverse[2][1] * y + inverse[2][2]
        if (w <= 0.0) return null
        return (inverse[0][0] * x + inverse[0][1] * y + inverse[0][2]) / w to
            (inverse[1][0] * x + inverse[1][1] * y + inverse[1][2]) / w
    }

    fun narrowed(): StickerGroundMapping {
        fun m(row: Int, column: Int): Float = inverse[row][column].toFloat()
        return StickerGroundMapping(
            windowToGround = floatArrayOf(
                m(0, 0), m(1, 0), 0f, m(2, 0),
                m(0, 1), m(1, 1), 0f, m(2, 1),
                0f, 0f, 0f, 0f,
                m(0, 2), m(1, 2), 0f, m(2, 2),
            ),
            originX = originX.toFloat(),
            originY = originY.toFloat(),
            present = true,
        )
    }
}

/**
 * [stickerGroundMapping]'s arithmetic for a parallelogram `northWest`, `northEast`, `southWest` in the
 * space [viewProjection] projects from, or `null` when the rectangle is seen edge-on. [origin] overrides
 * the chosen origin, for a test measuring what the origin buys.
 */
internal fun windowToRectangle(
    viewProjection: DoubleMatrix4,
    northWest: DoubleVector3,
    northEast: DoubleVector3,
    southWest: DoubleVector3,
    widthPixels: Double,
    heightPixels: Double,
    origin: Pair<Double, Double>? = null,
): ExactWindowToRectangle? {
    // Columns of window-from-(u, v, 1): the two edge directions (w = 0) and the north-west corner
    // (w = 1), through the projection's x, y and w rows and the viewport:
    // window x = W / 2 * (x + w), window y = H / 2 * (y + w), homogeneous w = w.
    val raw = arrayOf(
        windowColumn(viewProjection, northEast - northWest, 0.0, widthPixels, heightPixels),
        windowColumn(viewProjection, southWest - northWest, 0.0, widthPixels, heightPixels),
        windowColumn(viewProjection, northWest, 1.0, widthPixels, heightPixels),
    )
    val (originX, originY) = origin ?: chooseOrigin(raw, widthPixels, heightPixels)
    // Relative to the origin: window' = window - origin * w, for each homogeneous column.
    val columns = Array(3) { column ->
        val c = raw[column]
        doubleArrayOf(c[0] - originX * c[2], c[1] - originY * c[2], c[2])
    }
    // Bn = B * inverse(D) has every column scaled to a largest element of one, and is undone after the
    // inverse (inverse(B) = inverse(D) * inverse(Bn)), so it only conditions the arithmetic; D is
    // positive, so it keeps the determinant's sign.
    val scales = DoubleArray(3) { column -> maxAbs(columns[column]) }
    if (scales.any { it == 0.0 || !it.isFinite() }) return null
    val b = Array(3) { row -> DoubleArray(3) { column -> columns[column][row] / scales[column] } }

    val adjugate = Array(3) { DoubleArray(3) }
    for (row in 0 until 3) {
        for (column in 0 until 3) {
            // adj[row][column] is the cofactor of b[column][row].
            val r0 = if (column == 0) 1 else 0
            val r1 = if (column == 2) 1 else 2
            val c0 = if (row == 0) 1 else 0
            val c1 = if (row == 2) 1 else 2
            val minor = b[r0][c0] * b[r1][c1] - b[r0][c1] * b[r1][c0]
            adjugate[row][column] = if ((row + column) % 2 == 0) minor else -minor
        }
    }
    val determinant = b[0][0] * adjugate[0][0] + b[0][1] * adjugate[1][0] + b[0][2] * adjugate[2][0]
    if (abs(determinant) < SINGULAR_RECTANGLE_DETERMINANT || !determinant.isFinite()) return null

    // inverse(B) = D^-1 * adj(Bn) / det(Bn); any positive multiple is the same map, so the
    // determinant contributes only its sign and the result is scaled to a largest element of one.
    val sign = if (determinant > 0.0) 1.0 else -1.0
    val inverse = Array(3) { row -> DoubleArray(3) { column -> sign * adjugate[row][column] / scales[row] } }
    val largest = inverse.maxOf { maxAbs(it) }
    if (largest == 0.0 || !largest.isFinite()) return null
    return ExactWindowToRectangle(
        inverse = Array(3) { row -> DoubleArray(3) { column -> inverse[row][column] / largest } },
        originX = originX,
        originY = originY,
    )
}

/**
 * The whole window pixel nearest the rectangle's projected centre, clamped into the frame; the frame's
 * centre when the rectangle's centre is behind the camera.
 */
private fun chooseOrigin(columns: Array<DoubleArray>, widthPixels: Double, heightPixels: Double): Pair<Double, Double> {
    val x = 0.5 * columns[0][0] + 0.5 * columns[1][0] + columns[2][0]
    val y = 0.5 * columns[0][1] + 0.5 * columns[1][1] + columns[2][1]
    val w = 0.5 * columns[0][2] + 0.5 * columns[1][2] + columns[2][2]
    val (centreX, centreY) = if (w > 0.0 && (x / w).isFinite() && (y / w).isFinite()) {
        (x / w).coerceIn(0.0, widthPixels) to (y / w).coerceIn(0.0, heightPixels)
    } else {
        widthPixels / 2.0 to heightPixels / 2.0
    }
    return centreX.roundToInt().toDouble() to centreY.roundToInt().toDouble()
}

private fun windowColumn(
    viewProjection: DoubleMatrix4,
    vector: DoubleVector3,
    w: Double,
    widthPixels: Double,
    heightPixels: Double,
): DoubleArray {
    fun clip(row: Int): Double =
        viewProjection[row, 0] * vector.x + viewProjection[row, 1] * vector.y +
            viewProjection[row, 2] * vector.z + viewProjection[row, 3] * w
    val clipX = clip(0)
    val clipY = clip(1)
    val clipW = clip(3)
    return doubleArrayOf(
        widthPixels * 0.5 * (clipX + clipW),
        heightPixels * 0.5 * (clipY + clipW),
        clipW,
    )
}

private fun maxAbs(values: DoubleArray): Double = values.maxOf { abs(it) }

private const val MATRIX_ELEMENT_COUNT: Int = 16

/**
 * Below this, with every column normalised to a largest element of one, the rectangle is seen so
 * nearly edge-on that it covers no area on screen worth a coordinate.
 */
private const val SINGULAR_RECTANGLE_DETERMINANT: Double = 1e-12
