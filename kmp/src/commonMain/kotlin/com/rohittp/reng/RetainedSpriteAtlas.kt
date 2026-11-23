package com.rohittp.reng

import com.rohittp.reng.internal.firewall.SpriteAtlasManifest
import com.rohittp.reng.internal.image.DecodedImage

/**
 * One decoded sprite atlas, the [SpriteAtlasManifest] it was decoded out of, and the [ResourceKey]
 * `ResourceKeyDeriver.spriteAtlas` named it with.
 *
 * [manifest] is held for reference identity alone -- it is the memo's key, never read for content --
 * which is what makes a cached label handover cost neither a decode nor a digest of the atlas.
 */
internal class RetainedSpriteAtlas(
    val manifest: SpriteAtlasManifest,
    val key: ResourceKey,
    val image: DecodedImage,
)
