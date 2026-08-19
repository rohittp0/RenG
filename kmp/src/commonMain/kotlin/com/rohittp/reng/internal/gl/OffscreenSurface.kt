package com.rohittp.reng.internal.gl

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.internal.failure.FailureDescriptor

internal enum class OffscreenColourFormat(
    internal val wireValue: Int,
    internal val glInternalFormat: Int,
) {
    RGBA8(1, GL_RGBA8),
}

internal enum class OffscreenDepthFormat(
    internal val wireValue: Int,
    internal val glInternalFormat: Int,
) {
    DEPTH_COMPONENT24(1, GL_DEPTH_COMPONENT24),
}

internal data class OffscreenSurfaceDescriptor(
    val widthPixels: Int,
    val heightPixels: Int,
    val colourFormat: OffscreenColourFormat,
    val depthFormat: OffscreenDepthFormat,
) {
    init {
        require(widthPixels > 0 && heightPixels > 0) { "an offscreen surface has positive dimensions" }
    }
}

/**
 * RenG renders into its own offscreen colour-and-depth surface at the configured output pixel
 * size and then composites it into the caller's `RenderTarget`, so a target only has to be a
 * colour-writable framebuffer of the configured dimensions (ADR 0005). The surface is allocated
 * once and never resized, because output size is fixed at setup (ADR 0012).
 */
internal class OffscreenSurface(
    val key: ResourceKey,
    val descriptor: OffscreenSurfaceDescriptor,
    val framebuffer: Int,
    val colourTexture: Int,
    val depthRenderbuffer: Int,
)

internal sealed interface OffscreenSurfaceResult {
    data class Created(val surface: OffscreenSurface) : OffscreenSurfaceResult

    data class Failed(val failure: FailureDescriptor) : OffscreenSurfaceResult
}

internal fun offscreenSurfaceDescriptorFor(size: OutputPixelSize): OffscreenSurfaceDescriptor =
    OffscreenSurfaceDescriptor(
        widthPixels = size.width,
        heightPixels = size.height,
        colourFormat = OffscreenColourFormat.RGBA8,
        depthFormat = OffscreenDepthFormat.DEPTH_COMPONENT24,
    )

/**
 * Allocates the offscreen colour-and-depth surface: an immutably-storaged `GL_RGBA8` texture
 * colour attachment, a `GL_DEPTH_COMPONENT24` renderbuffer depth attachment, and the framebuffer
 * that binds them, checked for completeness before it is ever handed back.
 *
 * The colour attachment uses immutable `glTexStorage2D` storage rather than `glTexImage2D`, so
 * the surface is allocated once with a fixed level count and a fixed format and can never be
 * silently reshaped.
 *
 * Creation deliberately leaves its objects bound; the caller wraps it in [withCapturedGlState],
 * which is the single place restoration happens.
 *
 * A surface larger than the context allows fails before any allocation is issued, so a rejected
 * request never leaves objects to delete. An incomplete or otherwise-failed framebuffer deletes
 * every object it created before returning, leaking nothing.
 */
internal fun createOffscreenSurface(
    binding: GlBinding,
    profile: RenderContextProfile,
    key: ResourceKey,
    descriptor: OffscreenSurfaceDescriptor,
): OffscreenSurfaceResult {
    if (
        descriptor.widthPixels > profile.maxTextureSize ||
        descriptor.heightPixels > profile.maxTextureSize
    ) {
        return OffscreenSurfaceResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    }

    GlErrorQueue.drainOnEntry(binding)
    val names = IntArray(1)

    binding.genTextures(1, names)
    val colourTexture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, colourTexture)
    binding.texStorage2D(
        GL_TEXTURE_2D, 1, descriptor.colourFormat.glInternalFormat,
        descriptor.widthPixels, descriptor.heightPixels,
    )
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

    binding.genRenderbuffers(1, names)
    val depthRenderbuffer = names[0]
    binding.bindRenderbuffer(GL_RENDERBUFFER, depthRenderbuffer)
    binding.renderbufferStorage(
        GL_RENDERBUFFER, descriptor.depthFormat.glInternalFormat,
        descriptor.widthPixels, descriptor.heightPixels,
    )

    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(
        GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colourTexture, 0,
    )
    binding.framebufferRenderbuffer(
        GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRenderbuffer,
    )

    val status = binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER)
    val error = GlErrorQueue.firstOwnError(binding)
    if (status != GL_FRAMEBUFFER_COMPLETE || error != GL_NO_ERROR) {
        binding.deleteFramebuffers(1, intArrayOf(framebuffer))
        binding.deleteRenderbuffers(1, intArrayOf(depthRenderbuffer))
        binding.deleteTextures(1, intArrayOf(colourTexture))
        return OffscreenSurfaceResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    }

    return OffscreenSurfaceResult.Created(
        OffscreenSurface(
            key = key,
            descriptor = descriptor,
            framebuffer = framebuffer,
            colourTexture = colourTexture,
            depthRenderbuffer = depthRenderbuffer,
        ),
    )
}

/** Deletes every object [createOffscreenSurface] allocated for [surface], exactly once each. */
internal fun deleteOffscreenSurface(binding: GlBinding, surface: OffscreenSurface) {
    binding.deleteFramebuffers(1, intArrayOf(surface.framebuffer))
    binding.deleteRenderbuffers(1, intArrayOf(surface.depthRenderbuffer))
    binding.deleteTextures(1, intArrayOf(surface.colourTexture))
}
