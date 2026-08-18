package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderContextProfileTest {
    @Test fun theEsPrefixIsTheOnlyDialectSignal() {
        assertEquals(ShaderDialect.GLES, detectShaderDialect("OpenGL ES GLSL ES 3.20"))
        assertEquals(ShaderDialect.GLES, detectShaderDialect("OpenGL ES GLSL ES 3.00"))
        assertEquals(ShaderDialect.DESKTOP, detectShaderDialect("4.50"))
        assertEquals(ShaderDialect.DESKTOP, detectShaderDialect("4.10"))
        assertEquals(ShaderDialect.DESKTOP, detectShaderDialect("3.30 NVIDIA via Cg compiler"))
    }

    @Test fun versionsParseFromTheThreeMeasuredVersionStrings() {
        assertEquals(
            GlVersion(3, 2),
            parseGlVersion("OpenGL ES 3.2 Mesa 25.2.8-0ubuntu0.24.04.2", ShaderDialect.GLES),
        )
        assertEquals(
            GlVersion(4, 5),
            parseGlVersion("4.5 (Core Profile) Mesa 25.2.8-0ubuntu0.24.04.2", ShaderDialect.DESKTOP),
        )
        assertEquals(GlVersion(4, 1), parseGlVersion("4.1 APPLE-23.1.1", ShaderDialect.DESKTOP))
        assertEquals(GlVersion(4, 1), parseGlVersion("4.1 Metal - 90.5", ShaderDialect.DESKTOP))
        assertEquals(GlVersion(3, 3), parseGlVersion("3.3.0 NVIDIA 550.54", ShaderDialect.DESKTOP))
    }

    @Test fun malformedOrMismatchedVersionTextIsRejectedRatherThanGuessed() {
        assertNull(parseGlVersion("4.5 (Core Profile)", ShaderDialect.GLES))
        assertNull(parseGlVersion("OpenGL ES 3.2", ShaderDialect.DESKTOP))
        assertNull(parseGlVersion("", ShaderDialect.DESKTOP))
        assertNull(parseGlVersion("4", ShaderDialect.DESKTOP))
        assertNull(parseGlVersion("4.", ShaderDialect.DESKTOP))
        assertNull(parseGlVersion("x.y", ShaderDialect.DESKTOP))
    }

    @Test fun anEsThreeContextIsAdoptedWithItsMeasuredCapabilities() {
        val binding = esContextBinding()
        val adoption = adoptRenderContext(binding) as RenderContextAdoption.Adopted
        assertEquals(ShaderDialect.GLES, adoption.profile.dialect)
        assertEquals(GlVersion(3, 2), adoption.profile.version)
        assertEquals("llvmpipe (LLVM 20.1.2, 256 bits)", adoption.profile.rendererName)
        assertTrue(adoption.profile.supportsSrgbWriteControl)
        assertTrue(!adoption.profile.supportsEs3Compatibility)
        assertEquals(16384, adoption.profile.maxTextureSize)
    }

    @Test fun aDesktopCoreContextIsAdoptedAndAdvertisesEsCompatibility() {
        val binding = desktopContextBinding()
        val adoption = adoptRenderContext(binding) as RenderContextAdoption.Adopted
        assertEquals(ShaderDialect.DESKTOP, adoption.profile.dialect)
        assertEquals(GlVersion(4, 5), adoption.profile.version)
        assertTrue(adoption.profile.supportsEs3Compatibility)
        assertTrue(adoption.profile.supportsSrgbWriteControl)
    }

    @Test fun aContextBelowTheRequirementIsRejectedWithoutModifyingState() {
        val binding = RecordingGlBinding()
        binding.strings[GL_SHADING_LANGUAGE_VERSION] = "1.20"
        binding.strings[GL_VERSION] = "2.1 INTEL-16.4.5"
        binding.strings[GL_RENDERER] = "Intel HD Graphics"
        binding.strings[GL_VENDOR] = "Intel Inc."
        val rejection = adoptRenderContext(binding) as RenderContextAdoption.Rejected
        assertEquals(RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT, rejection.failure.code)
        assertEquals(PipelineStage.CONTEXT_ADOPTION, rejection.failure.stage)
        assertNull(rejection.failure.diagnostic)
        assertTrue(binding.log.none { it.startsWith("enable") || it.startsWith("bind") })
    }

    @Test fun anEsContextBelowThreePointZeroIsRejected() {
        val binding = RecordingGlBinding()
        binding.strings[GL_SHADING_LANGUAGE_VERSION] = "OpenGL ES GLSL ES 1.00"
        binding.strings[GL_VERSION] = "OpenGL ES 2.0 Mesa"
        binding.strings[GL_RENDERER] = "llvmpipe"
        binding.strings[GL_VENDOR] = "Mesa"
        assertTrue(adoptRenderContext(binding) is RenderContextAdoption.Rejected)
    }

    @Test fun extensionsAreReadThroughGetStringiAndNeverThroughGetString() {
        val binding = desktopContextBinding()
        adoptRenderContext(binding)
        assertTrue(binding.log.none { it == "getString(0x1F03)" })
        assertTrue(binding.log.any { it.startsWith("getStringi(0x1F03") })
    }

    private fun esContextBinding(): RecordingGlBinding = RecordingGlBinding().apply {
        strings[GL_SHADING_LANGUAGE_VERSION] = "OpenGL ES GLSL ES 3.20"
        strings[GL_VERSION] = "OpenGL ES 3.2 Mesa 25.2.8-0ubuntu0.24.04.2"
        strings[GL_RENDERER] = "llvmpipe (LLVM 20.1.2, 256 bits)"
        strings[GL_VENDOR] = "Mesa"
        indexedStrings += listOf("GL_EXT_sRGB_write_control", "GL_OES_texture_float")
        integers[GL_NUM_EXTENSIONS] = intArrayOf(2)
        integers[GL_MAX_TEXTURE_SIZE] = intArrayOf(16384)
        integers[GL_MAX_COLOR_ATTACHMENTS] = intArrayOf(8)
        integers[GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS] = intArrayOf(192)
    }

    private fun desktopContextBinding(): RecordingGlBinding = RecordingGlBinding().apply {
        strings[GL_SHADING_LANGUAGE_VERSION] = "4.50"
        strings[GL_VERSION] = "4.5 (Core Profile) Mesa 25.2.8-0ubuntu0.24.04.2"
        strings[GL_RENDERER] = "llvmpipe (LLVM 20.1.2, 256 bits)"
        strings[GL_VENDOR] = "Mesa"
        indexedStrings += listOf("GL_ARB_ES3_compatibility", "GL_ARB_texture_storage")
        integers[GL_NUM_EXTENSIONS] = intArrayOf(2)
        integers[GL_MAX_TEXTURE_SIZE] = intArrayOf(16384)
        integers[GL_MAX_COLOR_ATTACHMENTS] = intArrayOf(8)
        integers[GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS] = intArrayOf(192)
    }
}
