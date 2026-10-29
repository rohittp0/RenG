package com.rohittp.reng

import com.rohittp.reng.internal.gl.BackdropPipelineResult
import com.rohittp.reng.internal.gl.CompositePipelineResult
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.GlProgramCache
import com.rohittp.reng.internal.gl.GroundPipelineResult
import com.rohittp.reng.internal.gl.IconPipelineResult
import com.rohittp.reng.internal.gl.LabelPipelineResult
import com.rohittp.reng.internal.gl.OffscreenSurfaceResult
import com.rohittp.reng.internal.gl.RenderContextProfile
import com.rohittp.reng.internal.gl.StickerPipelineResult
import com.rohittp.reng.internal.gl.createBackdropPipeline
import com.rohittp.reng.internal.gl.createCompositePipeline
import com.rohittp.reng.internal.gl.createGroundPipeline
import com.rohittp.reng.internal.gl.createIconPipeline
import com.rohittp.reng.internal.gl.createLabelPipeline
import com.rohittp.reng.internal.gl.createOffscreenSurface
import com.rohittp.reng.internal.gl.createStickerPipeline
import com.rohittp.reng.internal.gl.deleteBackdropPipeline
import com.rohittp.reng.internal.gl.deleteCompositePipeline
import com.rohittp.reng.internal.gl.deleteGroundPipeline
import com.rohittp.reng.internal.gl.deleteIconPipeline
import com.rohittp.reng.internal.gl.deleteLabelPipeline
import com.rohittp.reng.internal.gl.deleteOffscreenSurface
import com.rohittp.reng.internal.gl.deleteStickerPipeline
import com.rohittp.reng.internal.gl.offscreenSurfaceDescriptorFor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver

/**
 * Allocates the offscreen surface plus RenG's own composite and sticker pipelines — every piece of
 * GL state the renderer holds independent of any one frame's content.
 *
 * Follows [createOffscreenSurface]'s own leak-discipline shape: every allocation is attempted
 * unconditionally, and every result is checked at one point at the end, rather than checking after
 * each step and returning early. If any allocation failed, every allocation that DID succeed is
 * deleted before the first failure is reported, so a partial construction never leaks a GL object.
 */
internal fun createInternalGlState(
    binding: GlBinding,
    profile: RenderContextProfile,
    programs: GlProgramCache,
    // The whole configuration rather than the two fields it reads, deliberately: the ground's
    // displacing program is compiled shaded or unshaded from `terrainShading` here and nowhere else,
    // and a second setup parameter is a second thing a caller can forget to pass on.
    configuration: RendererConfiguration,
): InternalGlStateResult {
    val deriver = ResourceKeyDeriver()
    val surfaceDescriptor = offscreenSurfaceDescriptorFor(configuration.outputPixelSize)
    val surfaceKey = deriver.offscreenSurface(surfaceDescriptor).key

    val surfaceResult = createOffscreenSurface(binding, profile, surfaceKey, surfaceDescriptor)
    val compositeResult = createCompositePipeline(binding, profile.dialect, programs, deriver)
    val stickerResult = createStickerPipeline(binding, profile.dialect, programs, deriver)
    val backdropResult = createBackdropPipeline(binding, profile.dialect, programs, deriver)
    val groundResult = createGroundPipeline(
        binding,
        profile.dialect,
        programs,
        deriver,
        terrainShading = configuration.terrainShading,
    )
    val labelResult = createLabelPipeline(binding, profile.dialect, programs, deriver)
    val iconResult = createIconPipeline(binding, profile.dialect, programs, deriver)

    val surface = (surfaceResult as? OffscreenSurfaceResult.Created)?.surface
    val composite = (compositeResult as? CompositePipelineResult.Created)?.pipeline
    val sticker = (stickerResult as? StickerPipelineResult.Created)?.pipeline
    val backdrop = (backdropResult as? BackdropPipelineResult.Created)?.pipeline
    val ground = (groundResult as? GroundPipelineResult.Created)?.pipeline
    val label = (labelResult as? LabelPipelineResult.Created)?.pipeline
    val icon = (iconResult as? IconPipelineResult.Created)?.pipeline

    if (surface != null && composite != null && sticker != null && backdrop != null &&
        ground != null && label != null && icon != null
    ) {
        return InternalGlStateResult.Created(
            InternalGlState(surface, composite, sticker, backdrop, ground, label, icon),
        )
    }

    surface?.let { deleteOffscreenSurface(binding, it) }
    composite?.let { deleteCompositePipeline(binding, programs, it) }
    sticker?.let { deleteStickerPipeline(binding, programs, it) }
    backdrop?.let { deleteBackdropPipeline(binding, programs, it) }
    ground?.let { deleteGroundPipeline(binding, programs, it) }
    label?.let { deleteLabelPipeline(binding, programs, it) }
    icon?.let { deleteIconPipeline(binding, programs, it) }

    val failure = (surfaceResult as? OffscreenSurfaceResult.Failed)?.failure
        ?: (compositeResult as? CompositePipelineResult.Failed)?.failure
        ?: (stickerResult as? StickerPipelineResult.Failed)?.failure
        ?: (backdropResult as? BackdropPipelineResult.Failed)?.failure
        ?: (groundResult as? GroundPipelineResult.Failed)?.failure
        ?: (labelResult as? LabelPipelineResult.Failed)?.failure
        ?: (iconResult as? IconPipelineResult.Failed)?.failure
        ?: error("createInternalGlState: no result failed despite an incomplete allocation set")
    return InternalGlStateResult.Failed(failure)
}
