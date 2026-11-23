package com.rohittp.reng.internal.planning

import com.rohittp.reng.Camera
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.admissibleGroundRows
import com.rohittp.reng.internal.projection.fullScanAdmissibleGroundRows
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The sampled-and-searched row scans must give exactly what casting a ray for every row gives: the
 * admissible row range ([admissibleGroundRows]) and the merged level-of-detail bands
 * ([groundLodBands]), across pitch, bearing, latitude, zoom and output size, including the 66.75
 * degree horizon-angle ceiling, pitches past it where the top of the frame is sky, and frames small
 * enough that a sample lands on every row.
 */
class MercatorGroundRowSearchTest {

    @Test
    fun sampled_row_search_matches_the_full_scan_across_cameras() {
        var compared = 0
        var withSkyRows = 0
        var withSeveralBands = 0
        for (size in SIZES) {
            for (latitude in LATITUDES) {
                for (bearing in BEARINGS) {
                    for (zoom in ZOOMS) {
                        for (pitch in PITCHES) {
                            val camera = resolved(latitude, bearing, zoom, pitch, size)
                            val expectedRows = fullScanAdmissibleGroundRows(camera)
                            assertEquals(expectedRows, admissibleGroundRows(camera), "rows at $size $latitude $bearing $zoom $pitch")
                            val selectedLod = zoom.toInt()
                            val expectedBands = fullScanGroundLodBands(camera, selectedLod)
                            assertEquals(expectedBands, groundLodBands(camera, selectedLod), "bands at $size $latitude $bearing $zoom $pitch")
                            compared++
                            if (expectedRows != null && expectedRows.first > 0) withSkyRows++
                            if (expectedBands.size > 1) withSeveralBands++
                        }
                    }
                }
            }
        }
        // The sweep has to exercise what it claims to: frames whose top rows miss (the edge search does
        // real work), frames that are ground throughout, and multi-band frames.
        assertTrue(withSkyRows in 1 until compared, "sweep must include frames with and without sky rows")
        assertTrue(withSeveralBands > 0, "sweep must include frames with more than one band")
    }

    private fun resolved(
        latitude: Double,
        bearing: Double,
        zoom: Double,
        pitch: Double,
        size: OutputPixelSize,
    ): ResolvedMercatorCamera = assertIs<SpatialOutcome.Success<ResolvedMercatorCamera>>(
        resolveMercatorCamera(Camera(latitude, 0.0, zoom, bearing, pitch), size),
    ).value

    private companion object {
        val SIZES = listOf(
            OutputPixelSize(width = 1080, height = 1920),
            OutputPixelSize(width = 692, height = 1231),
            OutputPixelSize(width = 346, height = 616),
            OutputPixelSize(width = 1920, height = 1080),
            OutputPixelSize(width = 37, height = 23),
            OutputPixelSize(width = 1, height = 1),
        )
        val LATITUDES = listOf(0.0, 51.5)
        val BEARINGS = listOf(0.0, 137.0)
        val ZOOMS = listOf(4.0, 17.0)

        // Every half degree to past the ceiling, plus every hundredth around 66.75, where the angle
        // bound starts to cut rows off the top of the frame.
        val PITCHES: List<Double> =
            (0..160).map { it * 0.5 } + (6650..6700).map { it / 100.0 }
    }
}
