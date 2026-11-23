package com.rohittp.reng

/** The concrete [RenderTarget] [RenGRenderer.mintRenderTarget] produces. */
internal class RenGRenderTarget(
    internal val ownerIdentity: Any,
    override val framebufferName: FramebufferName,
    internal val mintedAtGeneration: Long,
) : RenderTarget
