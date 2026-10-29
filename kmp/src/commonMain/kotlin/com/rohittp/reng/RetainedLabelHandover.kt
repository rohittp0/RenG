package com.rohittp.reng

import com.rohittp.reng.internal.firewall.AcquiredLabelCandidates
import com.rohittp.reng.internal.firewall.BasemapEngineHost
import com.rohittp.reng.internal.firewall.SpriteAtlasManifest

/**
 * One Label handover held for reuse by a later frame, under the key Rentile itself published for the
 * purpose ([BasemapEngineHost.labelCandidateRequestKey]).
 *
 * **The sprite manifest travels with it, and leaving it behind would be a visible defect rather than a
 * missed optimisation.** [FrameAcquisition.spriteAtlas] can only be read from inside the invocation that
 * proxied the sprite pair, and on a frame whose style was compiled earlier the Label acquisition is the
 * only thing left that makes the engine ask for it. Reusing the batch without it would therefore hand
 * every cached frame a `null` atlas and silently drop the frame's icons — a cache that changed how the
 * map looks, which is the one thing a cache may not do.
 */
internal class RetainedLabelHandover(
    val requestKey: String,
    val candidates: AcquiredLabelCandidates,
    val spriteAtlas: SpriteAtlasManifest?,
)
