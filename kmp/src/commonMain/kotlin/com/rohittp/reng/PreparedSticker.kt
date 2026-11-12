package com.rohittp.reng

import com.rohittp.reng.internal.cache.ResidentGenerationId
import com.rohittp.reng.internal.image.TextureImage
import com.rohittp.reng.internal.planning.FramePlanningCore

/**
 * One [Sticker]'s [Placement] paired with its not-yet-uploaded image -- a decoded PNG, or an ETC2 chain
 * admitted without decoding (ADR 0084) -- and the [ResourceKey]
 * [FramePlanningCore]'s static traversal already derived for it — the same key
 * [RenGRenderer.performDraw]'s texture cache uses so a repeated draw of an unchanged sticker image
 * reuses its GL texture instead of re-uploading it every frame.
 */
internal class PreparedSticker(
    internal val placement: Placement,
    internal val resourceKey: ResourceKey,
    internal val generationId: ResidentGenerationId,
    internal val image: TextureImage,
    internal val opacity: Float = 1f,
    internal val blend: BlendMode = BlendMode.OVER,
    internal val shader: PreparedStickerShader? = null,
    /** [Sticker.transient] (ADR 0093); already marked on the leased generation at prepare. */
    internal val transient: Boolean = false,
)

/**
 * A [StickerShader] with its consumer-mutable inputs snapshotted at `prepare()` time, as
 * [PreparedGeometry] snapshots a Geometry's: the uniform map copied, and each texture locator replaced
 * by its fetched, decoded content (ADR 0087). The program compiles at draw, where the context is.
 */
internal class PreparedStickerShader(
    internal val fragmentSource: String,
    internal val uniforms: Map<String, ShaderValue>,
    internal val consumerTextures: Map<String, PreparedGeometryTexture>,
    internal val ground: GroundRectangle?,
)
