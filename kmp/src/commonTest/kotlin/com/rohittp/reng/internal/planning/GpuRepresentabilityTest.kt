package com.rohittp.reng.internal.planning

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpuRepresentabilityTest {
    @Test
    fun finiteBinary32BoundaryAndSignedZeroAreRepresentable() {
        val maximum = Float.MAX_VALUE.toDouble()

        assertTrue(isGpuRepresentable(0.0))
        assertTrue(isGpuRepresentable(-0.0))
        assertTrue(isGpuRepresentable(maximum))
        assertTrue(isGpuRepresentable(-maximum))
    }

    @Test
    fun nonfiniteAndBinary32OverflowValuesAreNotRepresentable() {
        val overflow = Float.MAX_VALUE.toDouble() * 2.0

        assertFalse(isGpuRepresentable(Double.NaN))
        assertFalse(isGpuRepresentable(Double.POSITIVE_INFINITY))
        assertFalse(isGpuRepresentable(Double.NEGATIVE_INFINITY))
        assertFalse(isGpuRepresentable(overflow))
        assertFalse(isGpuRepresentable(-overflow))
    }
}
