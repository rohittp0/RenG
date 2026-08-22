package com.rohittp.reng.internal.planning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MercatorLodTest {
    @Test
    fun noHistoryChoosesNearestIntegerWithMidpointTiesDown() {
        val cases = listOf(
            0.0 to 0,
            0.499999999 to 0,
            0.5 to 0,
            0.500000001 to 1,
            1.499999999 to 1,
            1.5 to 1,
            1.500000001 to 2,
            21.5 to 21,
            21.500000001 to 22,
            22.0 to 22,
        )

        cases.forEach { (zoom, expected) ->
            assertEquals(LodObservation(expected), observeMercatorLod(zoom, previousSelectedLod = null))
        }
    }

    @Test
    fun hysteresisUsesOpenUpperAndClosedLowerThresholdsExactly() {
        assertEquals(LodObservation(10), observeMercatorLod(10.5, previousSelectedLod = 10))
        assertEquals(LodObservation(11), observeMercatorLod(10.500000001, previousSelectedLod = 10))
        assertEquals(LodObservation(10), observeMercatorLod(9.000000001, previousSelectedLod = 10))
        assertEquals(LodObservation(9), observeMercatorLod(9.0, previousSelectedLod = 10))
    }

    /**
     * The band that stops a zoom hovering on a boundary from thrashing the tile set is half a level
     * wide, exactly as wide as it was when it straddled the historyless boundary symmetrically. It
     * has only moved to the finer side of that boundary.
     */
    @Test
    fun hysteresisBandIsHalfALevelWideAndSitsEntirelyBelowTheHistorylessBoundary() {
        assertEquals(LodObservation(10), observeMercatorLod(10.05, previousSelectedLod = 10))
        assertEquals(LodObservation(11), observeMercatorLod(10.05, previousSelectedLod = 11))
        assertEquals(LodObservation(11), observeMercatorLod(10.6, previousSelectedLod = 10))
        assertEquals(LodObservation(11), observeMercatorLod(10.6, previousSelectedLod = 11))
    }

    /**
     * A remembered LOD is never coarser than the one this same zoom would have selected with no
     * history at all. A coarser LOD stretches one fixed 512-texel tile across more screen pixels,
     * and the measured width of a road edge tracks that stretch almost linearly, so hysteresis
     * spending any of its band on the coarse side is hysteresis spending it on blur.
     */
    @Test
    fun aRememberedLodIsNeverCoarserThanTheHistorylessSelection() {
        var step = 0
        while (step <= 440) {
            val zoom = step * 0.05
            val historyless = observeMercatorLod(zoom, previousSelectedLod = null).selectedLod
            for (previous in 0..22) {
                val remembered = observeMercatorLod(zoom, previousSelectedLod = previous).selectedLod
                assertTrue(
                    remembered >= historyless,
                    "zoom $zoom with history $previous chose $remembered, coarser than $historyless",
                )
            }
            step += 1
        }
    }

    @Test
    fun hysteresisRepeatsOneLevelRuleForMultiLevelJumpsAndStopsAtBounds() {
        assertEquals(LodObservation(22), observeMercatorLod(22.0, previousSelectedLod = 0))
        assertEquals(LodObservation(0), observeMercatorLod(0.0, previousSelectedLod = 22))
        assertEquals(LodObservation(5), observeMercatorLod(5.5, previousSelectedLod = 0))
        assertEquals(LodObservation(6), observeMercatorLod(5.500000001, previousSelectedLod = 0))
        assertEquals(LodObservation(22), observeMercatorLod(22.0, previousSelectedLod = 22))
        assertEquals(LodObservation(0), observeMercatorLod(0.0, previousSelectedLod = 0))
    }

    @Test
    fun nullHistoryAfterResetUsesNoHistoryRuleInsteadOfAStaleLod() {
        val beforeReset = observeMercatorLod(8.4, previousSelectedLod = 9)
        val afterReset = observeMercatorLod(8.4, previousSelectedLod = null)

        assertEquals(LodObservation(9), beforeReset)
        assertEquals(LodObservation(8), afterReset)
    }

    @Test
    fun lodObservationRemainsAvailableWhenNoTileSelectionIsRequested() {
        val observation = observeMercatorLod(12.75, previousSelectedLod = 12)

        assertEquals(LodObservation(13), observation)
    }
}
