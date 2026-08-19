package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.image.DecodedImage

/**
 * What an uploaded texture is *for*, which decides whether GL upload may premultiply it.
 *
 * The distinction is by purpose, not by file format: [IMAGE] and [DATA] both decode identically
 * through Cycle C's PNG decoder into the same [DecodedImage] shape, and differ only here, at upload.
 * Expressing the decision in this type means a caller cannot get it wrong by silently omitting a
 * documented convention — the compiler forces a choice.
 */
internal enum class TextureContent { IMAGE, DATA }

/**
 * Uploads [image]'s current unpremultiplied RGBA8 bytes as a `GL_TEXTURE_2D` and returns its object name.
 *
 * [TextureContent.IMAGE] premultiplies alpha into RGB before upload; [TextureContent.DATA] uploads
 * [image]'s bytes bit-exact. This is a filtering correctness fix, not a blend-arithmetic convenience:
 * GL's bilinear filter resamples the texture's stored bytes before any fragment shader ever sees a
 * sampled value, so an unpremultiplied transparent texel — which carries arbitrary, meaningless RGB —
 * bleeds that RGB into the visible result at any edge a filtered sample crosses. Map-anchored stickers
 * under a pitched camera always filter, since only scale exactly 1.0 avoids it. Premultiplying in the
 * fragment shader cannot fix this, because filtering has already happened by the time the shader runs.
 *
 * A consumer data texture — a boundary mask, a signed-distance field, or any other payload packed
 * across RGBA channels — must never take this path: a multiply destroys it silently, with no error.
 * `CONTEXT.md` sets this same precedent for terrain samples, which must stay bit-exact.
 *
 * [image] is never mutated by either path: [DecodedImage.rgbaSnapshot] already returns a fresh copy on
 * every read, and premultiplication (when it happens at all) runs on that copy. Cycle C's canonical
 * decoded form stays unpremultiplied.
 *
 * **Sampler state is set here, per content kind, because leaving it at GL's default is a correctness
 * bug rather than a style omission.** GL's default minification filter is
 * `GL_NEAREST_MIPMAP_LINEAR`, which requires a mipmap chain; a texture with none is
 * *mipmap-incomplete* and samples black on a real driver. GL's default wrap mode is `GL_REPEAT`,
 * which under linear filtering samples the opposite edge at a texture's boundary, bleeding the far
 * side into every sticker edge. No mipmap chain is generated — an explicit non-mipmap minification
 * filter is the correct fix on its own, and generating one would change sampling behaviour and cost
 * memory for no benefit at this stage.
 *
 * The two content kinds get different filters, by owner decision:
 * - [TextureContent.IMAGE] gets `GL_LINEAR` for both minification and magnification. Linear filtering
 *   is exactly what premultiplication above exists to make correct — see the class-level rationale.
 * - [TextureContent.DATA] gets `GL_NEAREST` for both, because nearest never invents a value:
 *   interpolating between an index of 3 and an index of 7 yields a meaningless index of 5. The
 *   accepted cost is that a signed-distance field wants linear filtering and loses its antialiasing
 *   under this rule; a per-texture filter choice — letting a consumer opt a specific data texture
 *   into linear — is the intended additive fix if that cost turns out to matter, not an oversight.
 *
 * Both kinds get `GL_CLAMP_TO_EDGE` on both wrap axes: nothing in this cycle tiles a texture, and
 * clamping is what makes the edge-bleed fix above actually take effect.
 */
internal fun uploadTexture(binding: GlBinding, image: DecodedImage, content: TextureContent): Int {
    val bytes = image.rgbaSnapshot()
    val uploadBytes = when (content) {
        TextureContent.IMAGE -> premultiplyAlpha(bytes)
        TextureContent.DATA -> bytes
    }

    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texImage2D(
        target = GL_TEXTURE_2D,
        level = 0,
        internalFormat = GL_RGBA8,
        width = image.width,
        height = image.height,
        border = 0,
        format = GL_RGBA,
        type = GL_UNSIGNED_BYTE,
        pixels = uploadBytes,
    )

    val filter = when (content) {
        TextureContent.IMAGE -> GL_LINEAR
        TextureContent.DATA -> GL_NEAREST
    }
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

    return texture
}

/**
 * Premultiplies tightly packed RGBA8 [bytes] into a fresh array; [bytes] itself is never written to.
 *
 * Rounding is pinned to round-half-up: `(component * alpha + 127) / 255`, not the truncating
 * `(component * alpha) / 255`. The two differ whenever the product does not divide 255 evenly — for
 * example `137 * 137`, where truncation yields 73 and this rule yields 74 — and an unpinned choice is
 * exactly the kind of thing that silently diverges between platforms. Alpha itself is carried through
 * untouched.
 */
private fun premultiplyAlpha(bytes: ByteArray): ByteArray {
    val premultiplied = bytes.copyOf()
    var pixelStart = 0
    while (pixelStart < premultiplied.size) {
        val alpha = premultiplied[pixelStart + 3].toInt() and 0xFF
        for (channel in 0 until 3) {
            val index = pixelStart + channel
            val component = premultiplied[index].toInt() and 0xFF
            premultiplied[index] = ((component * alpha + 127) / 255).toByte()
        }
        pixelStart += 4
    }
    return premultiplied
}
