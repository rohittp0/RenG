package com.rohittp.reng.internal.math

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.gl.composeGroundModelViewProjection
import com.rohittp.reng.internal.planning.BasemapTileQuad
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.ScreenProjection
import com.rohittp.reng.internal.projection.projectCameraRelativeLogicalPosition
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Wall-clock figures for the matrix work a frame does: the raw [DoubleMatrix4] operations, and the two
 * per-object paths that rebuilt the camera's view-projection for every point and every ground tile.
 * It asserts nothing about speed -- it prints, so a change has a before and an after to compare -- and
 * folds every result into a checksum so no optimiser can drop the work.
 */
class DoubleMatrix4BenchmarkTest {
    @Test
    fun reportsMatrixAndPerObjectProjectionCost() {
        val random = Random(20261010)
        val matrices = List(256) { DoubleMatrix4(List(16) { random.nextDouble(-2.0, 2.0) }) }
        val affine = List(256) {
            DoubleMatrix4.fromRows(
                List(4) { row -> List(4) { column -> if (row == 3) (if (column == 3) 1.0 else 0.0) else random.nextDouble(-2.0, 2.0) } },
            )
        }
        val camera = assertIsSuccess(
            resolveMercatorCamera(Camera(48.85, 2.35, 12.5, 30.0, 55.0), OutputPixelSize(1080, 1920)),
        )
        val points = List(1024) { DoubleVector3(random.nextDouble(-900.0, 900.0), random.nextDouble(-1600.0, 1600.0), random.nextDouble(0.0, 20.0)) }
        val quads = List(256) { BasemapTileQuad(random.nextDouble(-2000.0, 2000.0), random.nextDouble(-2000.0, 2000.0), 512.0) }

        var checksum = 0.0
        repeat(WARMUP_ROUNDS) { checksum += round(matrices, affine, camera, points, quads).second }
        val timings = mutableMapOf<String, Long>()
        repeat(MEASURED_ROUNDS) {
            val (round, sum) = round(matrices, affine, camera, points, quads)
            checksum += sum
            round.forEach { (name, millis) -> timings[name] = (timings[name] ?: 0L) + millis }
        }
        timings.forEach { (name, millis) -> println("matrix benchmark $name: ${millis}ms over $MEASURED_ROUNDS rounds") }
        println("matrix benchmark checksum: $checksum")
        assertTrue(checksum.isFinite(), "checksum $checksum")
    }

    private fun round(
        matrices: List<DoubleMatrix4>,
        affine: List<DoubleMatrix4>,
        camera: ResolvedMercatorCamera,
        points: List<DoubleVector3>,
        quads: List<BasemapTileQuad>,
    ): Pair<Map<String, Long>, Double> {
        val timings = LinkedHashMap<String, Long>()
        var sum = 0.0

        var mark = TimeSource.Monotonic.markNow()
        repeat(MULTIPLY_PASSES) {
            for (index in matrices.indices) {
                sum += (matrices[index] * matrices[(index + 1) % matrices.size])[2, 3]
            }
        }
        timings["multiply x${MULTIPLY_PASSES * matrices.size}"] = mark.elapsedNow().inWholeMilliseconds

        mark = TimeSource.Monotonic.markNow()
        repeat(INVERSE_PASSES) {
            for (matrix in matrices) sum += matrix.inverse()?.get(1, 2) ?: 0.0
        }
        timings["inverse x${INVERSE_PASSES * matrices.size}"] = mark.elapsedNow().inWholeMilliseconds

        mark = TimeSource.Monotonic.markNow()
        repeat(INVERSE_PASSES) {
            for (matrix in affine) sum += matrix.inverseAffine()?.get(0, 3) ?: 0.0
        }
        timings["inverseAffine x${INVERSE_PASSES * affine.size}"] = mark.elapsedNow().inWholeMilliseconds

        mark = TimeSource.Monotonic.markNow()
        repeat(PROJECTION_PASSES) {
            for (point in points) {
                val projected = projectCameraRelativeLogicalPosition(camera, point)
                if (projected is ScreenProjection.Projected) sum += projected.pixelX
            }
        }
        timings["projectPoint x${PROJECTION_PASSES * points.size}"] = mark.elapsedNow().inWholeMilliseconds

        mark = TimeSource.Monotonic.markNow()
        repeat(TILE_PASSES) {
            for (quad in quads) sum += composeGroundModelViewProjection(camera, quad)[5]
        }
        timings["groundTileMvp x${TILE_PASSES * quads.size}"] = mark.elapsedNow().inWholeMilliseconds
        return timings to sum
    }

    private fun assertIsSuccess(outcome: SpatialOutcome<ResolvedMercatorCamera>): ResolvedMercatorCamera =
        (outcome as SpatialOutcome.Success).value

    private companion object {
        const val WARMUP_ROUNDS = 3
        const val MEASURED_ROUNDS = 5
        const val MULTIPLY_PASSES = 400
        const val INVERSE_PASSES = 100
        const val PROJECTION_PASSES = 100
        const val TILE_PASSES = 100
    }
}
