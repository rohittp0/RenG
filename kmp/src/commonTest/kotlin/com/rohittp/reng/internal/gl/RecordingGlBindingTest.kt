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

    @Test fun deleteCallsWithDifferentHandlesAreDistinguishableAfterTheFact() {
        val binding = RecordingGlBinding()
        binding.deleteFramebuffers(2, intArrayOf(5, 6))
        binding.deleteTextures(1, intArrayOf(9))
        assertEquals(listOf(5, 6, 9), binding.deletedNames)

        val other = RecordingGlBinding()
        other.deleteFramebuffers(2, intArrayOf(11, 12))
        assertEquals(listOf("deleteFramebuffers(2)"), other.log)
        assertEquals(binding.log, listOf("deleteFramebuffers(2)", "deleteTextures(1)"))
        assertTrue(binding.deletedNames != other.deletedNames)
    }

    @Test fun programmedPixelsAreWrittenThroughOnReadPixels() {
        val binding = RecordingGlBinding()
        binding.pixels[GL_RGBA] = byteArrayOf(1, 2, 3, 4)
        val out = ByteArray(4)
        binding.readPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, out)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), out)
        assertEquals(listOf("readPixels(0,0,1,1,0x1908,0x1401)"), binding.log)
    }

    @Test fun emptyReadPixelsDestinationIsRejectedRatherThanSilentlyIgnored() {
        val binding = RecordingGlBinding()
        val failure = runCatching { binding.readPixels(0, 0, 0, 0, GL_RGBA, GL_UNSIGNED_BYTE, ByteArray(0)) }
        assertTrue(failure.isFailure)
    }

    @Test fun bufferPayloadsAreRecoverableByTarget() {
        val binding = RecordingGlBinding()
        binding.bufferData(GL_ARRAY_BUFFER, 4, byteArrayOf(1, 2, 3, 4), GL_STATIC_DRAW)
        binding.bufferSubData(GL_ARRAY_BUFFER, 0, 2, byteArrayOf(9, 9))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), binding.bufferDataPayloads[GL_ARRAY_BUFFER])
        assertContentEquals(byteArrayOf(9, 9), binding.bufferSubDataPayloads[GL_ARRAY_BUFFER])
    }

    @Test fun uniformMatrixValuesAreRecoverableByLocation() {
        val binding = RecordingGlBinding()
        val matrix = FloatArray(16) { it.toFloat() }
        binding.uniformMatrix4fv(3, 1, false, matrix)
        assertContentEquals(matrix, binding.uniformMatrix4fvValues[3])
    }

    @Test fun lastDrawBuffersIsRecoverable() {
        val binding = RecordingGlBinding()
        binding.drawBuffers(2, intArrayOf(GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT0 + 1))
        assertContentEquals(intArrayOf(GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT0 + 1), binding.lastDrawBuffers)
    }
}
