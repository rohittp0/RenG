package com.rohittp.reng.internal.gl

import com.rohittp.reng.FramebufferName
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.internal.failure.FailureDescriptor

internal fun interface GlFrameContent {
    fun draw(binding: GlBinding)
}

/** Cycle D draws no frame content of its own; Cycle E replaces this with the real scene draw. */
internal val EmptyGlFrameContent: GlFrameContent = GlFrameContent { }

/**
 * Cycle B's projection maps the near plane to `+1` and infinity to `-1` in clip space, so window
 * depth runs from `1` at the near plane down to `0` at infinity. RenG therefore clears depth to `0`
 * and tests with `GL_GREATER`.
 */
internal const val REVERSE_Z_FAR_DEPTH: Float = 0.0f

/**
 * Draws one frame: clears and renders [content] into RenG's own offscreen surface, then composites
 * that surface into [targetFramebuffer] with a blended draw rather than a framebuffer blit, because
 * a blit does not blend and a consumer compositing RenG's output over existing content needs it to
 * (ADR 0005).
 *
 * The entire body runs inside [withCapturedGlState], so the caller's GL state is captured before
 * either pass and restored afterward unconditionally — including when [content] leaves state dirty
 * or a driver error is detected at the end.
 *
 * `GL_FRAMEBUFFER_SRGB` arrives **enabled** on Mesa's ES context and **disabled** on its desktop
 * core context — a pixel-affecting difference between two contexts on the same machine — so RenG
 * sets it explicitly to disabled rather than inheriting it: the colour attachment is a linear
 * `GL_RGBA8` texture and an sRGB write conversion would encode those texels twice. On an ES context
 * lacking `GL_EXT_sRGB_write_control` the token is not queryable at all, so RenG neither reads nor
 * writes it.
 */
internal fun drawFrame(
    binding: GlBinding,
    profile: RenderContextProfile,
    surface: OffscreenSurface,
    composite: CompositePipeline,
    targetFramebuffer: FramebufferName,
    content: GlFrameContent = EmptyGlFrameContent,
): FailureDescriptor? {
    GlErrorQueue.drainOnEntry(binding)

    return withCapturedGlState(binding, profile, COMPOSITE_TEXTURE_UNIT_COUNT) {
        binding.pixelStorei(GL_UNPACK_ALIGNMENT, GL_UNPACK_ALIGNMENT_DEFAULT)
        binding.pixelStorei(GL_UNPACK_ROW_LENGTH, 0)
        binding.pixelStorei(GL_UNPACK_SKIP_ROWS, 0)
        binding.pixelStorei(GL_UNPACK_SKIP_PIXELS, 0)
        binding.bindBuffer(GL_PIXEL_UNPACK_BUFFER, 0)

        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, surface.framebuffer)
        binding.viewport(0, 0, surface.descriptor.widthPixels, surface.descriptor.heightPixels)
        binding.disable(GL_SCISSOR_TEST)
        binding.colorMask(true, true, true, true)
        binding.depthMask(true)
        binding.clearColor(0.0f, 0.0f, 0.0f, 0.0f)
        binding.clearDepthf(REVERSE_Z_FAR_DEPTH)
        binding.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        binding.enable(GL_DEPTH_TEST)
        binding.depthFunc(GL_GREATER)
        binding.frontFace(GL_CCW)
        binding.cullFace(GL_BACK)
        profile.setFramebufferSrgb(binding, enabled = false)

        content.draw(binding)

        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer.value.toInt())
        binding.viewport(0, 0, surface.descriptor.widthPixels, surface.descriptor.heightPixels)
        binding.disable(GL_DEPTH_TEST)
        binding.depthMask(false)
        binding.disable(GL_CULL_FACE)
        binding.disable(GL_SCISSOR_TEST)
        binding.colorMask(true, true, true, true)
        binding.enable(GL_BLEND)
        binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
        binding.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        binding.activeTexture(GL_TEXTURE0)
        binding.bindTexture(GL_TEXTURE_2D, surface.colourTexture)
        binding.bindSampler(0, 0)
        binding.useProgram(composite.program)
        if (composite.sourceUniformLocation >= 0) {
            binding.uniform1i(composite.sourceUniformLocation, 0)
        }
        binding.bindVertexArray(composite.vertexArray)
        binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)

        if (GlErrorQueue.firstOwnError(binding) == GL_NO_ERROR) {
            null
        } else {
            glOperationFailure(PipelineStage.DRAW, resourceKey = null)
        }
    }
}

private fun RenderContextProfile.setFramebufferSrgb(binding: GlBinding, enabled: Boolean) {
    if (!supportsSrgbWriteControl) return
    if (enabled) binding.enable(GL_FRAMEBUFFER_SRGB) else binding.disable(GL_FRAMEBUFFER_SRGB)
}
