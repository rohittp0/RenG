package com.rohittp.reng

import com.rohittp.reng.internal.cache.ResidentGenerationId
import com.rohittp.reng.internal.image.DecodedImage

/**
 * One [Geometry] consumer texture already fetched and decoded at `prepare()` time, paired with the
 * [ResourceKey] its sampler name resolved to — the same shape [PreparedSticker] establishes for a
 * sticker's image, reused here so [RenGRenderer.performDraw]'s texture cache covers both draw paths
 * identically.
 */
internal class PreparedGeometryTexture(
    internal val resourceKey: ResourceKey,
    internal val generationId: ResidentGenerationId,
    internal val image: DecodedImage,
)
