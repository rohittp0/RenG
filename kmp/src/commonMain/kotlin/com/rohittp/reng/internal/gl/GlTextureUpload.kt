package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.glb.GltfSampler
import com.rohittp.reng.internal.image.CompressedImage
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.image.Etc2Format
import com.rohittp.reng.internal.image.decodeEtc2Rows

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
 * The four `glTexParameteri` values [uploadTexture] sets on a texture: the minification and
 * magnification filters, and the `S`/`T` wrap modes. Grouping them lets a caller — currently only
 * [gltfSamplerState], mapping a glTF model's own sampler — express a complete sampler as one value
 * instead of four positional `Int` parameters a caller could transpose without the compiler noticing.
 */
internal data class TextureSamplerState(val minFilter: Int, val magFilter: Int, val wrapS: Int, val wrapT: Int)

/**
 * The sampler [uploadTexture] applies when [content] carries no glTF sampler of its own: stickers,
 * the ground, and a consumer's geometry data textures all upload without ever naming a
 * [TextureSamplerState], so this is the sampler they have always gotten, kept exact so this default
 * can change for glTF models without moving underneath any of them.
 *
 * GL's own default minification filter is `GL_NEAREST_MIPMAP_LINEAR`, which requires a mipmap chain;
 * a texture with none is *mipmap-incomplete* and samples black on a real driver. GL's default wrap
 * mode is `GL_REPEAT`, which under linear filtering samples the opposite edge at a texture's
 * boundary, bleeding the far side into every sticker edge. Both kinds get `GL_CLAMP_TO_EDGE` on both
 * wrap axes instead: nothing at this call site tiles a texture, and clamping is what makes the
 * edge-bleed fix take effect.
 *
 * The two content kinds get different filters, by owner decision:
 * - [TextureContent.IMAGE] gets `GL_LINEAR` for both minification and magnification. Linear filtering
 *   is exactly what [uploadTexture]'s premultiplication step exists to make correct.
 * - [TextureContent.DATA] gets `GL_NEAREST` for both, because nearest never invents a value:
 *   interpolating between an index of 3 and an index of 7 yields a meaningless index of 5. The
 *   accepted cost is that a signed-distance field wants linear filtering and loses its antialiasing
 *   under this rule; a per-texture filter choice — letting a consumer opt a specific data texture
 *   into linear — is the intended additive fix if that cost turns out to matter, not an oversight.
 */
internal fun defaultSamplerStateFor(content: TextureContent): TextureSamplerState = when (content) {
    TextureContent.IMAGE -> TextureSamplerState(GL_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
    TextureContent.DATA -> TextureSamplerState(GL_NEAREST, GL_NEAREST, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE)
}

/**
 * Maps [sampler]'s glTF fields straight through to a [TextureSamplerState], performing no
 * validation of its own: `GltfFeatures` already rejects any filter or wrap enum outside the GL set
 * during parsing, so every value here is already a legal `glTexParameteri` argument by the time it
 * reaches this function.
 *
 * A `null` [sampler] — a glTF texture reference with no `sampler` index — takes the specification's
 * own defaults: `wrapS`/`wrapT` default to `REPEAT` per the glTF specification (also
 * [GltfSampler]'s own parse-time default), and the specification leaves both filters unspecified, for
 * which RenG picks `GL_LINEAR`. A non-null [sampler] with `magFilter`/`minFilter` still absent — legal
 * per glTF, since only `wrapS`/`wrapT` are mandatory — falls back to that same `GL_LINEAR` per field,
 * independently of the other.
 */
internal fun gltfSamplerState(sampler: GltfSampler?): TextureSamplerState {
    if (sampler == null) return TextureSamplerState(GL_LINEAR, GL_LINEAR, GL_REPEAT, GL_REPEAT)
    return TextureSamplerState(
        minFilter = sampler.minFilter ?: GL_LINEAR,
        magFilter = sampler.magFilter ?: GL_LINEAR,
        wrapS = sampler.wrapS,
        wrapT = sampler.wrapT,
    )
}

private fun isMipmapMinificationFilter(minFilter: Int): Boolean = minFilter == GL_NEAREST_MIPMAP_NEAREST ||
    minFilter == GL_LINEAR_MIPMAP_NEAREST ||
    minFilter == GL_NEAREST_MIPMAP_LINEAR ||
    minFilter == GL_LINEAR_MIPMAP_LINEAR

/** Exact RGBA8 storage for level zero and every mip level [sampler] requests. */
internal fun textureAllocationBytes(
    width: Int,
    height: Int,
    sampler: TextureSamplerState,
): Long {
    require(width > 0 && height > 0) { "texture dimensions must be positive" }
    var levelWidth = width.toLong()
    var levelHeight = height.toLong()
    var total = 0L
    while (true) {
        val pixels = checkedPositiveProduct(levelWidth, levelHeight)
        val levelBytes = checkedPositiveProduct(pixels, 4L)
        check(total <= Long.MAX_VALUE - levelBytes) { "texture byte size overflow" }
        total += levelBytes
        if (!isMipmapMinificationFilter(sampler.minFilter) || (levelWidth == 1L && levelHeight == 1L)) {
            return total
        }
        levelWidth = maxOf(1L, levelWidth / 2L)
        levelHeight = maxOf(1L, levelHeight / 2L)
    }
}

private fun checkedPositiveProduct(left: Long, right: Long): Long {
    check(left == 0L || right <= Long.MAX_VALUE / left) { "texture byte size overflow" }
    return left * right
}

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
 * [image] is never mutated by either path. DATA is consumed synchronously from its immutable backing;
 * IMAGE premultiplication writes only into a bounded upload workspace. RenG's canonical decoded
 * form stays unpremultiplied.
 *
 * [sampler] sets the four `glTexParameteri` calls this upload always makes, and defaults to exactly
 * today's behaviour for [content] — see [defaultSamplerStateFor] — so stickers, the ground, and
 * consumer data textures, none of which ever pass this parameter, are unaffected by its existence.
 * glTF models pass [gltfSamplerState]'s mapping of the model's own sampler instead.
 *
 * A mipmap chain is generated, with `glGenerateMipmap`, **only** when `sampler.minFilter` names one
 * of the four mipmap minification filters — `GL_NEAREST_MIPMAP_NEAREST`,
 * `GL_LINEAR_MIPMAP_NEAREST`, `GL_NEAREST_MIPMAP_LINEAR`, or `GL_LINEAR_MIPMAP_LINEAR`. Honouring
 * such a filter without generating the chain it requires leaves the texture mipmap-incomplete, which
 * samples black on a real driver — the identical failure an earlier cycle hit on sticker textures
 * with GL's own default filter. Generating the chain always, for every filter, would change sampling
 * behaviour and cost memory for no benefit when nothing asked for it, so an explicit non-mipmap
 * minification filter — every default in [defaultSamplerStateFor] — still generates none. The
 * generate call is issued after the level-zero `glTexImage2D` upload, never before: a chain built
 * from no level-zero image is built from nothing.
 */
internal fun uploadTexture(
    binding: GlBinding,
    image: DecodedImage,
    content: TextureContent,
    sampler: TextureSamplerState = defaultSamplerStateFor(content),
): Int {
    return when (content) {
        TextureContent.IMAGE -> uploadPremultipliedInChunks(binding, image, sampler)
        TextureContent.DATA -> image.readRgbaBytes { bytes ->
            uploadRgba(binding, bytes, image.width, image.height, sampler)
        }
    }
}

/** Uploads premultiplied RGBA with a reusable <=1 MiB workspace instead of a second full raster. */
private fun uploadPremultipliedInChunks(
    binding: GlBinding,
    image: DecodedImage,
    sampler: TextureSamplerState,
): Int {
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
        pixels = null,
    )

    val maximumChunkPixels = PREMULTIPLY_UPLOAD_CHUNK_BYTES / RGBA_CHANNELS
    image.readRgbaBytes { source ->
        val rowPixels = image.width
        if (rowPixels <= maximumChunkPixels) {
            val rowsPerChunk = maxOf(1, maximumChunkPixels / rowPixels)
            val chunk = ByteArray(minOf(image.height, rowsPerChunk) * rowPixels * RGBA_CHANNELS)
            var y = 0
            while (y < image.height) {
                val rows = minOf(rowsPerChunk, image.height - y)
                val pixels = rows * rowPixels
                premultiplyRange(source, y * rowPixels * RGBA_CHANNELS, pixels, chunk)
                binding.texSubImage2D(
                    GL_TEXTURE_2D, 0, 0, y, rowPixels, rows, GL_RGBA, GL_UNSIGNED_BYTE, chunk,
                )
                y += rows
            }
        } else {
            val chunk = ByteArray(maximumChunkPixels * RGBA_CHANNELS)
            for (y in 0 until image.height) {
                var x = 0
                while (x < rowPixels) {
                    val pixels = minOf(maximumChunkPixels, rowPixels - x)
                    val sourceOffset = (y * rowPixels + x) * RGBA_CHANNELS
                    premultiplyRange(source, sourceOffset, pixels, chunk)
                    binding.texSubImage2D(
                        GL_TEXTURE_2D, 0, x, y, pixels, 1, GL_RGBA, GL_UNSIGNED_BYTE, chunk,
                    )
                    x += pixels
                }
            }
        }
    }

    applySampler(binding, sampler)
    return texture
}

/**
 * Uploads [rgba] as a `GL_TEXTURE_2D` **without premultiplying it**, and returns its object name.
 *
 * The one caller is the basemap ground path on Rentile's `renderRaw`, whose tiles arrive already
 * premultiplied (ADR 0044). Its own entry point rather than a third [TextureContent] because the
 * bytes cannot travel in a [DecodedImage], whose own KDoc says unpremultiplied -- a variant quietly
 * carrying the other kind would make that sentence false.
 *
 * [sampler] defaults to [TextureContent.IMAGE]'s: linear filtering, clamped on both axes, which is
 * what a basemap tile has always had. Everything after the premultiply decision is [uploadRgba],
 * shared with [uploadTexture] so the two cannot drift in anything but the one statement they are
 * meant to differ in.
 */
internal fun uploadPremultipliedTexture(
    binding: GlBinding,
    rgba: ByteArray,
    width: Int,
    height: Int,
    sampler: TextureSamplerState = defaultSamplerStateFor(TextureContent.IMAGE),
): Int = uploadRgba(binding, rgba, width, height, sampler)

/**
 * The GL half both upload entry points share: everything that happens once the bytes are final.
 *
 * [uploadBytes] is uploaded exactly as given — whatever premultiplication the caller owed has
 * already happened, or deliberately has not.
 */
private fun uploadRgba(
    binding: GlBinding,
    uploadBytes: ByteArray,
    width: Int,
    height: Int,
    sampler: TextureSamplerState,
): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texImage2D(
        target = GL_TEXTURE_2D,
        level = 0,
        internalFormat = GL_RGBA8,
        width = width,
        height = height,
        border = 0,
        format = GL_RGBA,
        type = GL_UNSIGNED_BYTE,
        pixels = uploadBytes,
    )

    applySampler(binding, sampler)

    return texture
}

private fun applySampler(binding: GlBinding, sampler: TextureSamplerState) {
    setSamplerParameters(binding, sampler)
    if (isMipmapMinificationFilter(sampler.minFilter)) binding.generateMipmap(GL_TEXTURE_2D)
}

private fun setSamplerParameters(binding: GlBinding, sampler: TextureSamplerState) {
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, sampler.minFilter)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, sampler.magFilter)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, sampler.wrapS)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, sampler.wrapT)
}

/**
 * The sampler a [CompressedImage] is drawn with: [TextureContent.IMAGE]'s linear, clamped one, with
 * trilinear minification whenever the container brought levels to minify through.
 */
internal fun compressedSamplerState(image: CompressedImage): TextureSamplerState = TextureSamplerState(
    minFilter = if (image.levelCount > 1) GL_LINEAR_MIPMAP_LINEAR else GL_LINEAR,
    magFilter = GL_LINEAR,
    wrapS = GL_CLAMP_TO_EDGE,
    wrapT = GL_CLAMP_TO_EDGE,
)

/**
 * Uploads every level of [image] as a `GL_TEXTURE_2D` and returns its object name (ADR 0084).
 *
 * [native] -- the context samples ETC2 -- hands each level's blocks straight to
 * `glCompressedTexImage2D`, from where they already lie in the stored resource. Otherwise each level
 * is decoded on the CPU, one bounded run of block rows at a time, into immutable RGBA8 storage.
 * Neither path premultiplies: the container's texels already are.
 *
 * The chain is the container's own. `glGenerateMipmap` is never called, so [compressedSamplerState]
 * is applied directly rather than through [applySampler], and `GL_TEXTURE_MAX_LEVEL` stops sampling
 * at the last level the container brought, which is what keeps a partial chain complete.
 */
internal fun uploadCompressedTexture(binding: GlBinding, image: CompressedImage, native: Boolean): Int {
    // Sized before any GL object exists, so a refusal leaves nothing behind.
    val workspaceBytes = if (native) 0 else decodeWorkspaceBytes(image)
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    try {
        binding.bindTexture(GL_TEXTURE_2D, texture)
        if (native) {
            val internalFormat = image.format.glInternalFormat
            for (level in 0 until image.levelCount) {
                image.readLevel(level) { bytes, offset, length ->
                    binding.compressedTexImage2D(
                        GL_TEXTURE_2D, level, internalFormat, image.levelWidth(level), image.levelHeight(level),
                        0, length, bytes, offset,
                    )
                }
            }
        } else {
            uploadDecodedLevels(binding, image, workspaceBytes)
        }
        binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, image.levelCount - 1)
        setSamplerParameters(binding, compressedSamplerState(image))
    } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
        // Not yet registered anywhere, so nothing else would ever delete it.
        binding.deleteTextures(1, intArrayOf(texture))
        throw failure
    }
    return texture
}

/**
 * The CPU path's RGBA workspace, computed in Long: whole block rows, at most
 * [DECODE_UPLOAD_CHUNK_BYTES] or one block row, and never more than the level-zero raster.
 *
 * Every admitted container's chain is within the decoded-image ceiling, which `ResourceLimits` caps
 * at `Int.MAX_VALUE`, so this always fits; it is checked rather than assumed because an `Int`
 * product that wrapped would allocate the wrong workspace silently.
 */
private fun decodeWorkspaceBytes(image: CompressedImage): Int {
    val blockRowBytes = image.width.toLong() * 4L * RGBA_CHANNELS
    val blockRowsPerChunk = maxOf(1L, DECODE_UPLOAD_CHUNK_BYTES / blockRowBytes)
    val rows = minOf(blockRowsPerChunk * 4L, image.height.toLong())
    val bytes = rows * image.width.toLong() * RGBA_CHANNELS
    require(bytes <= Int.MAX_VALUE.toLong()) { "the ETC2 decode workspace must fit an array" }
    return bytes.toInt()
}

/** The CPU path: one workspace of whole block rows, decoded and uploaded a run at a time. */
private fun uploadDecodedLevels(binding: GlBinding, image: CompressedImage, workspaceBytes: Int) {
    binding.texStorage2D(GL_TEXTURE_2D, image.levelCount, GL_RGBA8, image.width, image.height)
    val blockRowBytes = image.width.toLong() * 4L * RGBA_CHANNELS
    val blockRowsPerChunk = maxOf(1L, DECODE_UPLOAD_CHUNK_BYTES / blockRowBytes).toInt()
    val workspace = ByteArray(workspaceBytes)
    for (level in 0 until image.levelCount) {
        val width = image.levelWidth(level)
        val height = image.levelHeight(level)
        val blockRows = (height + 3) / 4
        var blockRow = 0
        while (blockRow < blockRows) {
            val count = minOf(blockRowsPerChunk, blockRows - blockRow)
            decodeEtc2Rows(image, level, blockRow, count, workspace)
            val y = blockRow * 4
            binding.texSubImage2D(
                GL_TEXTURE_2D, level, 0, y, width, minOf(count * 4, height - y), GL_RGBA, GL_UNSIGNED_BYTE, workspace,
            )
            blockRow += count
        }
    }
}

private val Etc2Format.glInternalFormat: Int
    get() = when (this) {
        Etc2Format.RGB8 -> GL_COMPRESSED_RGB8_ETC2
        Etc2Format.RGB8_PUNCHTHROUGH_ALPHA1 -> GL_COMPRESSED_RGB8_PUNCHTHROUGH_ALPHA1_ETC2
        Etc2Format.RGBA8_EAC -> GL_COMPRESSED_RGBA8_ETC2_EAC
    }

private fun premultiplyRange(source: ByteArray, sourceOffset: Int, pixelCount: Int, out: ByteArray) {
    var sourcePixel = sourceOffset
    var destinationPixel = 0
    repeat(pixelCount) {
        val alpha = source[sourcePixel + 3].toInt() and 0xFF
        for (channel in 0 until 3) {
            val component = source[sourcePixel + channel].toInt() and 0xFF
            out[destinationPixel + channel] = ((component * alpha + 127) / 255).toByte()
        }
        out[destinationPixel + 3] = source[sourcePixel + 3]
        sourcePixel += RGBA_CHANNELS
        destinationPixel += RGBA_CHANNELS
    }
}

private const val RGBA_CHANNELS: Int = 4
private const val PREMULTIPLY_UPLOAD_CHUNK_BYTES: Int = 1024 * 1024
private const val DECODE_UPLOAD_CHUNK_BYTES: Int = 1024 * 1024
