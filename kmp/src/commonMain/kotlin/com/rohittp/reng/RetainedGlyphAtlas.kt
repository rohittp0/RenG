package com.rohittp.reng

import com.rohittp.reng.internal.image.DecodedImage

/** One decoded glyph atlas held under the [ResourceKey] `ResourceKeyDeriver.glyphAtlas` named it with. */
internal class RetainedGlyphAtlas(
    val key: ResourceKey,
    val image: DecodedImage,
)
