package com.rohittp.reng.internal.image

/**
 * The three ETC2 block formats a RenG ETC2 container may carry (ADR 0084), by their container [code].
 * [blockBytes] is what one 4x4 block costs: 8 for the two colour-only formats, 16 when an EAC alpha
 * block precedes the colour block.
 */
internal enum class Etc2Format(val code: Int, val blockBytes: Int) {
    RGB8(code = 1, blockBytes = 8),
    RGB8_PUNCHTHROUGH_ALPHA1(code = 2, blockBytes = 8),
    RGBA8_EAC(code = 3, blockBytes = 16),
}

/**
 * Either form an image reaches a texture upload in: a [DecodedImage] raster, or a [CompressedImage]
 * chain that is never decoded on the way to a GPU that can sample it.
 */
internal sealed interface TextureImage {
    /** Level-zero width in pixels -- what a sticker's local size is measured in. */
    val width: Int

    /** Level-zero height in pixels. */
    val height: Int
}

/**
 * One admitted ETC2 container: level zero and up to the rest of its mip chain, in [format] blocks,
 * sRGB-encoded and already premultiplied by its producer (ADR 0084), so nothing on the way to a
 * texture multiplies it again.
 *
 * A view, not a copy. The levels stay where they arrived, inside the stored raw resource's immutable
 * snapshot, which this image shares rather than duplicating: the generation already pays for those
 * bytes, and an animated sticker presents one of these per frame.
 */
internal class CompressedImage internal constructor(
    override val width: Int,
    override val height: Int,
    val format: Etc2Format,
    private val container: ByteArray,
    private val levelOffsets: IntArray,
) : TextureImage {
    val levelCount: Int get() = levelOffsets.size - 1

    fun levelWidth(level: Int): Int = maxOf(1, width shr level)

    fun levelHeight(level: Int): Int = maxOf(1, height shr level)

    /** What every level costs as blocks: a native upload's GPU bytes. */
    val payloadByteCount: Long get() = (levelOffsets.last() - levelOffsets.first()).toLong()

    /** What every level costs as RGBA8: a decoded upload's GPU bytes and the decoded-image ceiling's measure. */
    val rgbaByteCount: Long get() = rgbaBytesOfChain(width.toLong(), height.toLong(), levelCount)

    /**
     * Hands [block] level [level]'s blocks as a range of the shared container, for a synchronous
     * consumer that neither mutates nor retains the array -- [DecodedImage.readRgbaBytes]'s contract.
     */
    fun <T> readLevel(level: Int, block: (bytes: ByteArray, offset: Int, length: Int) -> T): T {
        require(level in 0 until levelCount) { "level is outside the chain" }
        val offset = levelOffsets[level]
        return block(container, offset, levelOffsets[level + 1] - offset)
    }
}

internal sealed interface Etc2ContainerResult {
    class Admitted(val image: CompressedImage) : Etc2ContainerResult

    /** Not a well-formed version-1 container: wrong magic, header fields, level count or exact length. */
    data object Malformed : Etc2ContainerResult

    /** A well-framed header naming a version or block format RenG does not read. */
    data object Unsupported : Etc2ContainerResult

    /** Well-formed, but its RGBA8 projection is over the decoded-image ceiling. */
    data object TooLarge : Etc2ContainerResult
}

/** Whether [bytes] begin with the container magic -- the whole of how a sticker image's form is told. */
internal fun isEtc2Container(bytes: ByteArray): Boolean =
    bytes.size >= ETC2_MAGIC.size && ETC2_MAGIC.indices.all { bytes[it] == ETC2_MAGIC[it] }

/**
 * Validates [bytes] as a RenG ETC2 container (ADR 0084) and admits it as a [CompressedImage] view.
 *
 * The 16-byte little-endian header is the ASCII magic `RET2`, version `1`, a format code, a level count
 * in `1..floor(log2(max(w, h))) + 1`, a zero reserved byte, and a nonzero `u32` width and height. The
 * levels follow back to back with nothing after them; level `i` is `max(1, w >> i)` by
 * `max(1, h >> i)` texels in whole 4x4 blocks.
 *
 * [maximumDecodedBytes] bounds the chain's RGBA8 projection, not its compressed size, because a context
 * that cannot sample ETC2 decodes every level, and the ceiling has to hold for the path that
 * allocates most. Reading blocks is never part of validation: every bit pattern of an ETC2 or EAC
 * block decodes to some texels, so a container whose framing is right cannot fail later.
 */
internal fun parseEtc2Container(bytes: ByteArray, maximumDecodedBytes: Long): Etc2ContainerResult {
    if (bytes.size < ETC2_HEADER_BYTES || !isEtc2Container(bytes)) return Etc2ContainerResult.Malformed
    if (bytes.unsignedByte(4) != ETC2_CONTAINER_VERSION) return Etc2ContainerResult.Unsupported
    val format = Etc2Format.entries.firstOrNull { it.code == bytes.unsignedByte(5) }
        ?: return Etc2ContainerResult.Unsupported
    val levelCount = bytes.unsignedByte(6)
    val width = bytes.unsignedInt(8)
    val height = bytes.unsignedInt(12)
    if (bytes.unsignedByte(7) != 0 || width !in 1L..Int.MAX_VALUE || height !in 1L..Int.MAX_VALUE) {
        return Etc2ContainerResult.Malformed
    }
    if (levelCount !in 1..fullChainLevels(maxOf(width, height))) return Etc2ContainerResult.Malformed

    val levelOffsets = IntArray(levelCount + 1)
    var offset = ETC2_HEADER_BYTES.toLong()
    levelOffsets[0] = ETC2_HEADER_BYTES
    for (level in 0 until levelCount) {
        val blocksAcross = (maxOf(1L, width shr level) + 3L) / 4L
        val blocksDown = (maxOf(1L, height shr level) + 3L) / 4L
        // Each factor is under 2^29, so the product and the running sum stay far inside a Long, and a
        // sum past the array's own length already proves the framing wrong.
        offset += blocksAcross * blocksDown * format.blockBytes
        if (offset > bytes.size) return Etc2ContainerResult.Malformed
        levelOffsets[level + 1] = offset.toInt()
    }
    if (offset != bytes.size.toLong()) return Etc2ContainerResult.Malformed

    // Bounded by the exact length check above: at most 2^31 bytes of blocks is at most 2^32 texels.
    if (rgbaBytesOfChain(width, height, levelCount) > maximumDecodedBytes) return Etc2ContainerResult.TooLarge
    return Etc2ContainerResult.Admitted(
        CompressedImage(width.toInt(), height.toInt(), format, bytes, levelOffsets),
    )
}

private fun rgbaBytesOfChain(width: Long, height: Long, levelCount: Int): Long {
    var total = 0L
    for (level in 0 until levelCount) {
        total += maxOf(1L, width shr level) * maxOf(1L, height shr level) * 4L
    }
    return total
}

/** `floor(log2(extent)) + 1`: the level count of a full chain down to 1x1. */
private fun fullChainLevels(extent: Long): Int = 64 - extent.countLeadingZeroBits()

private fun ByteArray.unsignedByte(index: Int): Int = this[index].toInt() and 0xFF

private fun ByteArray.unsignedInt(index: Int): Long =
    (unsignedByte(index).toLong()) or
        (unsignedByte(index + 1).toLong() shl 8) or
        (unsignedByte(index + 2).toLong() shl 16) or
        (unsignedByte(index + 3).toLong() shl 24)

private val ETC2_MAGIC: ByteArray = "RET2".encodeToByteArray()
private const val ETC2_HEADER_BYTES: Int = 16
private const val ETC2_CONTAINER_VERSION: Int = 1
