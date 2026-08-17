package com.rohittp.reng.internal.planning

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun hysteresisUsesClosedUpperAndOpenLowerThresholdsExactly() {
        assertEquals(LodObservation(10), observeMercatorLod(10.749999999, previousSelectedLod = 10))
        assertEquals(LodObservation(11), observeMercatorLod(10.75, previousSelectedLod = 10))
        assertEquals(LodObservation(10), observeMercatorLod(9.25, previousSelectedLod = 10))
        assertEquals(LodObservation(9), observeMercatorLod(9.249999999, previousSelectedLod = 10))
    }

    @Test
    fun hysteresisRepeatsOneLevelRuleForMultiLevelJumpsAndStopsAtBounds() {
        assertEquals(LodObservation(22), observeMercatorLod(22.0, previousSelectedLod = 0))
        assertEquals(LodObservation(0), observeMercatorLod(0.0, previousSelectedLod = 22))
        assertEquals(LodObservation(5), observeMercatorLod(5.749999999, previousSelectedLod = 0))
        assertEquals(LodObservation(6), observeMercatorLod(5.75, previousSelectedLod = 0))
        assertEquals(LodObservation(22), observeMercatorLod(22.0, previousSelectedLod = 22))
        assertEquals(LodObservation(0), observeMercatorLod(0.0, previousSelectedLod = 0))
    }

    @Test
    fun nullHistoryAfterResetUsesNoHistoryRuleInsteadOfAStaleLod() {
        val beforeReset = observeMercatorLod(8.6, previousSelectedLod = 8)
        val afterReset = observeMercatorLod(8.6, previousSelectedLod = null)

        assertEquals(LodObservation(8), beforeReset)
        assertEquals(LodObservation(9), afterReset)
    }

    @Test
    fun lodObservationRemainsAvailableWhenNoTileSelectionIsRequested() {
        val observation = observeMercatorLod(12.75, previousSelectedLod = 12)

        assertEquals(LodObservation(13), observation)
    }
}
