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
