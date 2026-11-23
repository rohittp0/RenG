package com.rohittp.reng

import com.rohittp.reng.internal.gl.ResolvedIconQuad
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.label.FadedLabel
import com.rohittp.reng.internal.label.placeLabels

/**
 * One frame's labels, everything CPU-shaped about them already done: placed, collided, faded, and
 * with the atlas they sample already decoded to canonical RGBA8.
 *
 * **Every derivation here happened during `prepare()`, and ADR 0035 requires that rather than merely
 * permitting it.** Collision resolves once per preparation and never during a draw, because a frame
 * drawn twice must not fade twice; and the fade this carries was folded into `previousLabelFade`
 * at the same moment, so these quads are already this frame's answer rather than an input to one.
 *
 * What is *not* done is the upload: the atlas reaches the GPU in [RenGRenderer.drawResolvedFrame],
 * under [atlasKey], on exactly the terms a ground tile's texture does — `prepare()` holds no render
 * context, and an atlas already resident from an earlier frame must cost neither a decode nor an
 * upload. [atlas] is retained for the miss.
 */
internal class PreparedLabelFrame(
    internal val atlasKey: ResourceKey,
    internal val atlas: DecodedImage,
    labels: List<FadedLabel>,
    /**
     * This frame's icons, already faded, or empty when no symbol kept one — because the style
     * declares no sprite, because no icon resolved against the manifest, or because collision took
     * every one of them.
     *
     * **Flattened here rather than left inside [labels], and that is what makes the draw's phase
     * order expressible.** An icon is drawn out of the sprite atlas and a glyph out of the glyph
     * atlas, so the two halves of one symbol are two batches whatever the data structure says; a
     * draw that walked [labels] would have to re-separate them per frame. They are in
     * [com.rohittp.reng.internal.label.placeLabels]' own order, so the flattening preserves exactly
     * the precedence the glyph half is drawn in.
     *
     * The fade is applied at this point rather than by `advanceLabelFade`, which knows only about
     * glyph quads: [FadedLabel.opacity] is one number per *symbol*, so multiplying it into the icon
     * here is applying the same number to the same symbol's other half, not a second fade.
     */
    icons: List<ResolvedIconQuad> = emptyList(),
    /**
     * The sprite atlas those icons sample and the identity it is resident under, or `null` when
     * [icons] is empty. Retained for the upload's miss on exactly [atlas]'s terms — `prepare()` holds
     * no render context, and an atlas already on the GPU must cost neither a decode nor an upload.
     */
    internal val spriteAtlasKey: ResourceKey? = null,
    internal val spriteAtlas: DecodedImage? = null,
) {
    private val labelSnapshot: List<FadedLabel> = ArrayList(labels)
    private val iconSnapshot: List<ResolvedIconQuad> = ArrayList(icons)

    init {
        require(iconSnapshot.isEmpty() == (spriteAtlasKey == null)) {
            "an icon can only be drawn out of an atlas, and an atlas is only retained for icons"
        }
        require((spriteAtlasKey == null) == (spriteAtlas == null)) {
            "a retained sprite atlas is its bytes and its identity together"
        }
    }

    /** This frame's icons, in the order they are drawn — see the constructor parameter. */
    internal val icons: List<ResolvedIconQuad> get() = ArrayList(iconSnapshot)

    /**
     * This frame's surviving labels, lowest priority first — [placeLabels]' own order, which the
     * label pass consumes verbatim so that where two labels do share pixels the one that would have
     * won the collision paints last.
     */
    internal val labels: List<FadedLabel> get() = ArrayList(labelSnapshot)
}
