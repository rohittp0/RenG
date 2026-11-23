package com.rohittp.reng

import com.rohittp.reng.internal.cache.ResidentGenerationId
import com.rohittp.reng.internal.image.DecodedImage

/**
 * One external consumer texture paired with the resident generation its sampler resolved to.
 *
 * A [Geometry]'s instance carries [encodedPngBytes] and the IHDR [width]/[height], not decoded
 * pixels. The prepared frame's resident lease pins that byte array through draw, where the texture
 * cache decodes it only on a GPU miss. Backdrop and model-override instances retain their historical
 * eager [image] payload; those image classes deliberately do not adopt geometry's lazy policy.
 */
internal class PreparedGeometryTexture private constructor(
    internal val resourceKey: ResourceKey,
    internal val generationId: ResidentGenerationId,
    internal val image: DecodedImage?,
    internal val encodedPngBytes: ByteArray?,
    internal val width: Int,
    internal val height: Int,
) {
    constructor(
        resourceKey: ResourceKey,
        generationId: ResidentGenerationId,
        image: DecodedImage,
    ) : this(
        resourceKey = resourceKey,
        generationId = generationId,
        image = image,
        encodedPngBytes = null,
        width = image.width,
        height = image.height,
    )

    constructor(
        resourceKey: ResourceKey,
        generationId: ResidentGenerationId,
        encodedPngBytes: ByteArray,
        width: Int,
        height: Int,
    ) : this(
        resourceKey = resourceKey,
        generationId = generationId,
        image = null,
        encodedPngBytes = encodedPngBytes,
        width = width,
        height = height,
    )
}
