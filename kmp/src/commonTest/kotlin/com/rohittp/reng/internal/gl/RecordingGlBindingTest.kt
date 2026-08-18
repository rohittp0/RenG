package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingGlBindingTest {
    @Test fun outParametersAreWrittenThroughAndCallsAreLogged() {
        val binding = RecordingGlBinding()
        binding.integers[GL_ACTIVE_TEXTURE] = intArrayOf(GL_TEXTURE0 + 3)
        val out = IntArray(1)
        binding.getIntegerv(GL_ACTIVE_TEXTURE, out)
        assertContentEquals(intArrayOf(GL_TEXTURE0 + 3), out)
        assertEquals(listOf("getIntegerv(0x84E0)"), binding.log)
    }

    @Test fun generatedNamesAreDistinctAndNonZero() {
        val binding = RecordingGlBinding()
        val first = IntArray(2)
        val second = IntArray(1)
        binding.genTextures(2, first)
        binding.genBuffers(1, second)
        assertTrue(first.all { it > 0 })
        assertTrue(second.single() > 0)
        assertEquals(3, (first + second).toSet().size)
    }

    @Test fun emptyOutputArraysAreRejectedRatherThanSilentlyIgnored() {
        val binding = RecordingGlBinding()
        val failure = runCatching { binding.getIntegerv(GL_VIEWPORT, IntArray(0)) }
        assertTrue(failure.isFailure)
    }
}
