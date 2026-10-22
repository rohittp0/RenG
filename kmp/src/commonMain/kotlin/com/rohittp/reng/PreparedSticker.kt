package com.rohittp.reng

import com.rohittp.reng.internal.cache.ResidentGenerationId
import com.rohittp.reng.internal.image.TextureImage
import com.rohittp.reng.internal.planning.FramePlanningCore

/**
 * One [Sticker]'s [Placement] paired with its not-yet-uploaded image -- a decoded PNG, or an ETC2 chain
 * admitted without decoding (ADR 0084) -- and the [ResourceKey]
 * [FramePlanningCore]'s static traversal already derived for it — the same key
 * [RenGRenderer.performDraw]'s texture cache uses so a repeated draw of an unchanged sticker image
 * reuses its GL texture (Task 9b item 1) instead of re-uploading it every frame.
 */
internal class PreparedSticker(
    internal val placement: Placement,
    internal val resourceKey: ResourceKey,
    internal val generationId: ResidentGenerationId,
    internal val image: TextureImage,
    internal val opacity: Float = 1f,
    internal val blend: BlendMode = BlendMode.OVER,
)
