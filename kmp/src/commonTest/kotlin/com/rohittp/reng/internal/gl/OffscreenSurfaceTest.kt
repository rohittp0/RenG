package com.rohittp.reng.internal.gl

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OffscreenSurfaceTest {
    private val deriver = ResourceKeyDeriver()

    @Test fun theSurfaceIsColourAndDepthAndCompleteBeforeItIsReturned() {
        val binding = RecordingGlBinding()
        val created = createSurface(binding) as OffscreenSurfaceResult.Created
        assertTrue(created.surface.colourTexture > 0)
        assertTrue(created.surface.depthRenderbuffer > 0)
        assertTrue(created.surface.framebuffer > 0)
        assertTrue(
            binding.log.indexOfFirst { it.startsWith("texStorage2D") } <
                binding.log.indexOfFirst { it.startsWith("framebufferTexture2D") },
        )
        assertTrue(binding.log.any { it.startsWith("renderbufferStorage(0x8D41,0x81A6") })
        assertTrue(binding.log.any { it.startsWith("checkFramebufferStatus") })
    }

    @Test fun anIncompleteFramebufferDeletesEverythingItCreated() {
        val binding = RecordingGlBinding()
        binding.framebufferStatus = 0x8CD6
        val failed = createSurface(binding) as OffscreenSurfaceResult.Failed
        assertEquals(RenGErrorCode.GPU_OPERATION_FAILED, failed.failure.code)
        assertEquals(PipelineStage.GPU_RESOURCE, failed.failure.stage)
        assertTrue(binding.log.any { it.startsWith("deleteFramebuffers") })
        assertTrue(binding.log.any { it.startsWith("deleteRenderbuffers") })
        assertTrue(binding.log.any { it.startsWith("deleteTextures") })
    }

    @Test fun aSurfaceLargerThanTheContextAllowsFailsBeforeAnyAllocation() {
        val binding = RecordingGlBinding()
        val result = createOffscreenSurface(
            binding = binding,
            profile = profile(maxTextureSize = 256),
            key = deriver.offscreenSurface(descriptor(1024, 1024)).key,
            descriptor = descriptor(1024, 1024),
        )
        assertTrue(result is OffscreenSurfaceResult.Failed)
        assertTrue(binding.log.isEmpty())
    }

    @Test fun deletionRemovesAllThreeObjectsExactlyOnce() {
        val binding = RecordingGlBinding()
        val surface = (createSurface(binding) as OffscreenSurfaceResult.Created).surface
        binding.log.clear()
        deleteOffscreenSurface(binding, surface)
        assertEquals(1, binding.log.count { it.startsWith("deleteFramebuffers") })
        assertEquals(1, binding.log.count { it.startsWith("deleteRenderbuffers") })
        assertEquals(1, binding.log.count { it.startsWith("deleteTextures") })
    }

    @Test fun theCapturedStateGuardRestoresEvenWhenTheBlockThrows() {
        val binding = RecordingGlBinding()
        val before = captureGlState(binding, profile(), textureUnitCount = 1)
        runCatching {
            withCapturedGlState(binding, profile(), textureUnitCount = 1) {
                binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 7)
                throw IllegalStateException("frame content failed")
            }
        }
        assertTrue(binding.log.any { it == "bindFramebuffer(0x8CA9,7)" })
        assertEquals(before, captureGlState(binding, profile(), textureUnitCount = 1))
    }

    private fun createSurface(binding: RecordingGlBinding): OffscreenSurfaceResult {
        val descriptor = descriptor(64, 64)
        return createOffscreenSurface(
            binding = binding,
            profile = profile(),
            key = deriver.offscreenSurface(descriptor).key,
            descriptor = descriptor,
        )
    }

    private fun descriptor(width: Int, height: Int): OffscreenSurfaceDescriptor =
        OffscreenSurfaceDescriptor(
            widthPixels = width,
            heightPixels = height,
            colourFormat = OffscreenColourFormat.RGBA8,
            depthFormat = OffscreenDepthFormat.DEPTH_COMPONENT24,
        )

    private fun profile(maxTextureSize: Int = 16384): RenderContextProfile = RenderContextProfile(
        dialect = ShaderDialect.GLES,
        version = GlVersion(3, 2),
        vendorName = "Mesa",
        rendererName = "llvmpipe (LLVM 20.1.2, 256 bits)",
        shadingLanguageVersionText = "OpenGL ES GLSL ES 3.20",
        supportsEs3Compatibility = false,
        supportsSrgbWriteControl = true,
        maxTextureSize = maxTextureSize,
        maxColorAttachments = 8,
        maxCombinedTextureImageUnits = 192,
    )
}
