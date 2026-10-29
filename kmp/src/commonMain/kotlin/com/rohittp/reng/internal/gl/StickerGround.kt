package com.rohittp.reng.internal.gl

import com.rohittp.reng.GroundRectangle
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.resolveMercatorRectangleCorners
import com.rohittp.reng.internal.projection.ResolvedFrameCamera
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import kotlin.math.abs

/**
 * `rengStickerWindowToGround` for one sticker: the projective map from a window pixel
 * (`gl_FragCoord.xy`, origin bottom-left, pixel centres at half-integers) to [ground]'s texture
 * coordinate, column-major, in the `z = 0` slice of a 4x4 (see [STICKER_WINDOW_TO_GROUND_UNIFORM_NAME]).
 * The zero matrix -- which `rengStickerGroundUv()` reads as "off the rectangle" everywhere -- when
 * there is no [ground], under a globe camera, and when the rectangle is seen exactly edge-on (ADR 0087).
 *
 * **Why a homography is exact here.** The rectangle's four corners are coplanar
 * ([com.rohittp.reng.internal.gl.GeometryGrid]'s KDoc says why), and a Geometry's texture coordinate
 * is affine across that plane: its corners are `NW + u * (NE - NW) + v * (SW - NW)`, so the camera
 * maps `(u, v, 1)` to clip `(x, y, w)` by one 3x3 matrix `A`, and the viewport maps clip to window
 * by another. The inverse of their product sends a window pixel to `(u, v, 1) / w`, which is what a
 * Geometry's perspective-correct interpolation computes at that pixel. The sign survives too: `1 / w`
 * is positive exactly where the line of sight meets the plane in front of the camera, which is the
 * test the shader divides under.
 *
 * **Why it is safe to narrow to `Float`.** Everything up to the narrowing is `Double`, and the corners
 * arrive camera-relative ([resolveMercatorRectangleCorners] is the Geometry's own path), so no
 * absolute Mercator coordinate is ever involved. The map is computed with its columns normalised and
 * the result scaled to a largest element of one: a homography means the same thing at any positive
 * scale, so this changes no answer, and it keeps every element far from `Float`'s range limits. What
 * reaches the shader is window pixels in and a texture coordinate out, both small numbers, so the
 * shader's error is a few `Float` ulps of the coordinate itself -- the same order as a Geometry's own
 * interpolated `aTexCoord`.
 */
internal fun stickerWindowToGround(camera: ResolvedFrameCamera, ground: GroundRectangle?): FloatArray {
    if (ground == null) return FloatArray(MATRIX_ELEMENT_COUNT)
    return when (camera) {
        // ADR 0087: the ground under a globe is a sphere, which no homography maps; the program is
        // told every fragment is off the rectangle rather than handed a plane that is wrong toward
        // the limb.
        is ResolvedGlobeCamera -> FloatArray(MATRIX_ELEMENT_COUNT)
        is ResolvedMercatorCamera -> {
            val corners = resolveMercatorRectangleCorners(ground.topLeft, ground.bottomRight, camera)
                .requireResolvedAtDrawTime()
            windowToRectangle(
                viewProjection = camera.viewProjection,
                northWest = corners[0],
                northEast = corners[1],
                southWest = corners[3],
                widthPixels = camera.outputPixelSize.width.toDouble(),
                heightPixels = camera.outputPixelSize.height.toDouble(),
            )
        }
    }
}

/**
 * The window-to-rectangle homography for a parallelogram `northWest`, `northEast`, `southWest` in the
 * space [viewProjection] projects from; see [stickerWindowToGround].
 */
internal fun windowToRectangle(
    viewProjection: DoubleMatrix4,
    northWest: DoubleVector3,
    northEast: DoubleVector3,
    southWest: DoubleVector3,
    widthPixels: Double,
    heightPixels: Double,
): FloatArray {
    // Columns of window-from-(u, v, 1): the two edge directions (w = 0) and the north-west corner
    // (w = 1), each through the projection's x, y and w rows and then the viewport, written out:
    // window x = W / 2 * (x + w), window y = H / 2 * (y + w), homogeneous w = w.
    val columns = arrayOf(
        windowColumn(viewProjection, northEast - northWest, 0.0, widthPixels, heightPixels),
        windowColumn(viewProjection, southWest - northWest, 0.0, widthPixels, heightPixels),
        windowColumn(viewProjection, northWest, 1.0, widthPixels, heightPixels),
    )
    // Bn = B * inverse(D) has every column scaled to a largest element of one, and is undone after
    // the inverse (inverse(B) = inverse(D) * inverse(Bn)), so it only conditions the arithmetic; D
    // is positive, so it keeps the determinant's sign.
    val scales = DoubleArray(3) { column -> maxAbs(columns[column]) }
    if (scales.any { it == 0.0 || !it.isFinite() }) return FloatArray(MATRIX_ELEMENT_COUNT)
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
    if (abs(determinant) < SINGULAR_RECTANGLE_DETERMINANT || !determinant.isFinite()) {
        return FloatArray(MATRIX_ELEMENT_COUNT)
    }

    // inverse(B) = D^-1 * adj(Bn) / det(Bn); any positive multiple is the same map, so the
    // determinant contributes only its sign and the result is scaled to a largest element of one.
    val sign = if (determinant > 0.0) 1.0 else -1.0
    val inverse = Array(3) { row -> DoubleArray(3) { column -> sign * adjugate[row][column] / scales[row] } }
    val largest = inverse.maxOf { maxAbs(it) }
    if (largest == 0.0 || !largest.isFinite()) return FloatArray(MATRIX_ELEMENT_COUNT)

    // Row-major 3x3 [m] embedded as rows 0, 1 and 3 and columns 0, 1 and 3 of a 4x4, column-major.
    fun m(row: Int, column: Int): Float = (inverse[row][column] / largest).toFloat()
    return floatArrayOf(
        m(0, 0), m(1, 0), 0f, m(2, 0),
        m(0, 1), m(1, 1), 0f, m(2, 1),
        0f, 0f, 0f, 0f,
        m(0, 2), m(1, 2), 0f, m(2, 2),
    )
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
