package com.rohittp.reng

import com.rohittp.reng.internal.cache.ResidentGenerationId
import com.rohittp.reng.internal.image.DecodedImage

/**
 * One frame's backdrop as `prepare()` leaves it (ADR 0068, ADR 0071).
 *
 * [Pattern] keeps its [ResourceKey] for the same reason [PreparedSticker] does: `performDraw`'s
 * texture cache is keyed on it, so an unchanged backdrop uploads once rather than every frame. Its
 * sampler is not a sticker's, though -- see `BACKDROP_SAMPLER_STATE`.
 *
 * [Shader] decodes nothing of its own beyond its consumer textures; the program is compiled at draw,
 * where the GL context is, and memoised by shader source from there on.
 */
internal sealed interface PreparedBackdrop {
    class Pattern(
        internal val resourceKey: ResourceKey,
        internal val generationId: ResidentGenerationId,
        internal val image: DecodedImage,
        internal val tileSizeLogicalPixels: Double,
    ) : PreparedBackdrop

    class Shader(
        internal val shaderPair: ShaderPair,
        internal val uniforms: Map<String, ShaderValue>,
        internal val consumerTextures: Map<String, PreparedGeometryTexture>,
    ) : PreparedBackdrop
}
