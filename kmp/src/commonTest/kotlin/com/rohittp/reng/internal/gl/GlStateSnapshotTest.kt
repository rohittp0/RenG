package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlStateSnapshotTest {
    @Test fun activeTextureIsReadFirstAndReinstatedAfterThePerUnitLoop() {
        val binding = populatedBinding()
        captureGlState(binding, esProfile(), textureUnitCount = 2)
        val activeReads = binding.log.indexOfFirst { it == "getIntegerv(0x84E0)" }
        val firstUnitSwitch = binding.log.indexOfFirst { it == "activeTexture(0x84C0)" }
        val firstBindingRead = binding.log.indexOfFirst { it == "getIntegerv(0x8069)" }
        assertEquals(0, activeReads)
        assertTrue(activeReads < firstUnitSwitch)
        assertTrue(firstUnitSwitch < firstBindingRead)
        assertEquals("activeTexture(0x84C3)", binding.log.last { it.startsWith("activeTexture") })
    }

    @Test fun restoreReinstatesTheActiveUnitLast() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, esProfile(), textureUnitCount = 2)
        binding.log.clear()
        restoreGlState(binding, snapshot)
        assertEquals("activeTexture(0x84C3)", binding.log.last())
    }

    @Test fun theElementArrayBufferBindingIsNeverQueried() {
        val binding = populatedBinding()
        captureGlState(binding, esProfile(), textureUnitCount = 1)
        assertTrue(binding.log.none { it == "getIntegerv(0x8895)" })
        assertTrue(binding.log.any { it == "getIntegerv(0x8894)" })
    }

    @Test fun desktopOnlyTokensAreQueriedOnlyOnADesktopContext() {
        val esBinding = populatedBinding()
        val esSnapshot = captureGlState(esBinding, esProfile(), textureUnitCount = 1)
        assertNull(esSnapshot.drawBuffer)
        assertNull(esSnapshot.lineSmoothEnabled)
        assertTrue(esBinding.log.none { it == "getIntegerv(0xC01)" })
        assertTrue(esBinding.log.none { it == "isEnabled(0xB20)" })

        val desktopBinding = populatedBinding()
        val desktopSnapshot = captureGlState(desktopBinding, desktopProfile(), textureUnitCount = 1)
        assertEquals(GL_BACK, desktopSnapshot.drawBuffer)
        assertEquals(false, desktopSnapshot.lineSmoothEnabled)
    }

    @Test fun theUnpackAlignmentDefaultIsFourNotOne() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, esProfile(), textureUnitCount = 1)
        assertEquals(4, snapshot.unpackAlignment)
        assertEquals(4, snapshot.packAlignment)
        assertEquals(GL_UNPACK_ALIGNMENT_DEFAULT, snapshot.unpackAlignment)
    }

    @Test fun captureRestoreCaptureIsIdenticalOnTheFake() {
        val binding = populatedBinding()
        val first = captureGlState(binding, desktopProfile(), textureUnitCount = 3)
        restoreGlState(binding, first)
        val second = captureGlState(binding, desktopProfile(), textureUnitCount = 3)
        assertEquals(first, second)
    }

    @Test fun theSnapshotCoversEverySetMemberTheSpecificationNames() {
        val snapshot = captureGlState(populatedBinding(), desktopProfile(), textureUnitCount = 1)
        assertEquals(listOf(0f, 0f, 0f, 0f), snapshot.blendColour)
        assertEquals(listOf(0f, 1f), snapshot.depthRange)
        assertEquals(listOf(0, 0, 64, 64), snapshot.viewport)
        assertEquals(listOf(0, 0, 64, 64), snapshot.scissorBox)
        assertEquals(listOf(true, true, true, true), snapshot.colourWriteMask)
        assertEquals(listOf(0f, 0f, 0f, 0f), snapshot.colourClearValue)
        assertEquals(1f, snapshot.depthClearValue)
    }

    /**
     * Seeds `integers`, `floats`, `booleans`, and `enabled` for every token [captureGlState]
     * reads, across both [esProfile] and [desktopProfile]. The active texture unit is seeded at
     * `GL_TEXTURE0 + 3` rather than `GL_TEXTURE0` so the reinstatement assertions are not testing
     * zero against zero.
     */
    private fun populatedBinding(): RecordingGlBinding = RecordingGlBinding().apply {
        integers[GL_ACTIVE_TEXTURE] = intArrayOf(GL_TEXTURE0 + 3)
        integers[GL_TEXTURE_BINDING_2D] = intArrayOf(7)
        integers[GL_SAMPLER_BINDING] = intArrayOf(2)
        integers[GL_DRAW_FRAMEBUFFER_BINDING] = intArrayOf(11)
        integers[GL_READ_FRAMEBUFFER_BINDING] = intArrayOf(12)
        integers[GL_RENDERBUFFER_BINDING] = intArrayOf(13)
        integers[GL_CURRENT_PROGRAM] = intArrayOf(21)
        integers[GL_VERTEX_ARRAY_BINDING] = intArrayOf(31)
        integers[GL_ARRAY_BUFFER_BINDING] = intArrayOf(41)
        integers[GL_PIXEL_UNPACK_BUFFER_BINDING] = intArrayOf(42)
        integers[GL_UNIFORM_BUFFER_BINDING] = intArrayOf(43)
        integers[GL_BLEND_SRC_RGB] = intArrayOf(GL_SRC_ALPHA)
        integers[GL_BLEND_DST_RGB] = intArrayOf(GL_ONE_MINUS_SRC_ALPHA)
        integers[GL_BLEND_SRC_ALPHA] = intArrayOf(GL_ONE)
        integers[GL_BLEND_DST_ALPHA] = intArrayOf(GL_ZERO)
        integers[GL_BLEND_EQUATION_RGB] = intArrayOf(GL_FUNC_ADD)
        integers[GL_BLEND_EQUATION_ALPHA] = intArrayOf(GL_FUNC_ADD)
        integers[GL_DEPTH_FUNC] = intArrayOf(GL_LESS)
        integers[GL_CULL_FACE_MODE] = intArrayOf(GL_BACK)
        integers[GL_FRONT_FACE] = intArrayOf(GL_CCW)
        integers[GL_VIEWPORT] = intArrayOf(0, 0, 64, 64)
        integers[GL_SCISSOR_BOX] = intArrayOf(0, 0, 64, 64)
        integers[GL_UNPACK_ALIGNMENT] = intArrayOf(GL_UNPACK_ALIGNMENT_DEFAULT)
        integers[GL_UNPACK_ROW_LENGTH] = intArrayOf(0)
        integers[GL_UNPACK_SKIP_ROWS] = intArrayOf(0)
        integers[GL_UNPACK_SKIP_PIXELS] = intArrayOf(0)
        integers[GL_PACK_ALIGNMENT] = intArrayOf(GL_PACK_ALIGNMENT_DEFAULT)
        integers[GL_DRAW_BUFFER] = intArrayOf(GL_BACK)

        floats[GL_BLEND_COLOR] = floatArrayOf(0f, 0f, 0f, 0f)
        floats[GL_DEPTH_RANGE] = floatArrayOf(0f, 1f)
        floats[GL_DEPTH_CLEAR_VALUE] = floatArrayOf(1f)
        floats[GL_COLOR_CLEAR_VALUE] = floatArrayOf(0f, 0f, 0f, 0f)

        booleans[GL_DEPTH_WRITEMASK] = booleanArrayOf(true)
        booleans[GL_COLOR_WRITEMASK] = booleanArrayOf(true, true, true, true)

        enabled[GL_BLEND] = true
        enabled[GL_DEPTH_TEST] = true
        enabled[GL_CULL_FACE] = false
        enabled[GL_SCISSOR_TEST] = false
        enabled[GL_FRAMEBUFFER_SRGB] = true
        enabled[GL_LINE_SMOOTH] = false
    }

    private fun esProfile(): RenderContextProfile = RenderContextProfile(
        dialect = ShaderDialect.GLES,
        version = GlVersion(3, 2),
        vendorName = "Mesa",
        rendererName = "llvmpipe (LLVM 20.1.2, 256 bits)",
        shadingLanguageVersionText = "OpenGL ES GLSL ES 3.20",
        supportsEs3Compatibility = false,
        supportsSrgbWriteControl = false,
        maxTextureSize = 16384,
        maxColorAttachments = 8,
        maxCombinedTextureImageUnits = 192,
    )

    private fun desktopProfile(): RenderContextProfile = esProfile().copy(
        dialect = ShaderDialect.DESKTOP,
        supportsSrgbWriteControl = true,
    )
}
