package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * `MacosGlBinding`'s name-array entry points pin the caller's [IntArray] and hand GL a raw
 * pointer into it. Nothing in this module can make a GL context current, so these tests cannot
 * exercise the pinned GL call itself — they exist to prove the capacity guard rejects an
 * undersized array *before* any pinning or GL call happens, which is exactly what stands between
 * a short-array caller and heap corruption (an over-wide GL write) or a bare
 * `addressOf(0)` crash on an empty array reached with `count > 0`.
 */
class MacosGlBindingCapacityGuardTest {
    @Test fun genFramebuffersRejectsAnOutArrayShorterThanCount() {
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.genFramebuffers(2, IntArray(1)) }
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.genFramebuffers(1, IntArray(0)) }
    }

    @Test fun deleteFramebuffersRejectsANamesArrayShorterThanCount() {
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.deleteFramebuffers(2, IntArray(1)) }
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.deleteFramebuffers(1, IntArray(0)) }
    }

    @Test fun genRenderbuffersRejectsAnOutArrayShorterThanCount() {
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.genRenderbuffers(2, IntArray(1)) }
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.genRenderbuffers(1, IntArray(0)) }
    }

    @Test fun deleteRenderbuffersRejectsANamesArrayShorterThanCount() {
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.deleteRenderbuffers(2, IntArray(1)) }
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.deleteRenderbuffers(1, IntArray(0)) }
    }

    @Test fun drawBuffersRejectsABuffersArrayShorterThanCount() {
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.drawBuffers(2, IntArray(1)) }
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.drawBuffers(1, IntArray(0)) }
    }

    @Test fun genTexturesRejectsAnOutArrayShorterThanCount() {
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.genTextures(2, IntArray(1)) }
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.genTextures(1, IntArray(0)) }
    }

    @Test fun deleteTexturesRejectsANamesArrayShorterThanCount() {
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.deleteTextures(2, IntArray(1)) }
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.deleteTextures(1, IntArray(0)) }
    }

    @Test fun genSamplersRejectsAnOutArrayShorterThanCount() {
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.genSamplers(2, IntArray(1)) }
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.genSamplers(1, IntArray(0)) }
    }

    @Test fun deleteSamplersRejectsANamesArrayShorterThanCount() {
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.deleteSamplers(2, IntArray(1)) }
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.deleteSamplers(1, IntArray(0)) }
    }

    @Test fun genBuffersRejectsAnOutArrayShorterThanCount() {
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.genBuffers(2, IntArray(1)) }
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.genBuffers(1, IntArray(0)) }
    }

    @Test fun deleteBuffersRejectsANamesArrayShorterThanCount() {
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.deleteBuffers(2, IntArray(1)) }
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.deleteBuffers(1, IntArray(0)) }
    }

    @Test fun genVertexArraysRejectsAnOutArrayShorterThanCount() {
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.genVertexArrays(2, IntArray(1)) }
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.genVertexArrays(1, IntArray(0)) }
    }

    @Test fun deleteVertexArraysRejectsANamesArrayShorterThanCount() {
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.deleteVertexArrays(2, IntArray(1)) }
        assertFailsWith<IllegalArgumentException> { MacosGlBinding.deleteVertexArrays(1, IntArray(0)) }
    }

    @Test fun zeroCountIsANoOpEvenAgainstAnEmptyArray() {
        // count == 0 must short-circuit before the guard would ever see an out-of-range request;
        // this is the "guard on emptiness, not just on count" half of the original defect.
        MacosGlBinding.genFramebuffers(0, IntArray(0))
        MacosGlBinding.deleteFramebuffers(0, IntArray(0))
    }
}
