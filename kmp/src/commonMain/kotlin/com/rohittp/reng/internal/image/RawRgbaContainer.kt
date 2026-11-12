package com.rohittp.reng.internal.image

/**
 * One admitted raw RGBA container (ADR 0092): sRGB-encoded RGBA8 rows, top row first, straight or
 * [premultiplied]. A view into the stored snapshot, never a copy and never attached to its generation.
 */
internal class RawRgbaImage internal constructor(
    override val width: Int,
    override val height: Int,
    val premultiplied: Boolean,
    private val container: ByteArray,
) : TextureImage {
    /** What the texels cost as RGBA8: the GPU bytes of its one level. */
    val rgbaByteCount: Long get() = width.toLong() * height.toLong() * RGBA_BYTES_PER_PIXEL

    /** Hands [block] the texels as a range of the shared container; [block] must not mutate or retain it. */
    fun <T> readRgba(block: (bytes: ByteArray, offset: Int, length: Int) -> T): T =
        block(container, RAW_RGBA_HEADER_BYTES, container.size - RAW_RGBA_HEADER_BYTES)
}

internal sealed interface RawRgbaContainerResult {
    class Admitted(val image: RawRgbaImage) : RawRgbaContainerResult

    /** Wrong magic, reserved bytes, zero extent, or a length other than the header plus `w * h * 4`. */
    data object Malformed : RawRgbaContainerResult

    /** A version or flag bit RenG does not read. */
    data object Unsupported : RawRgbaContainerResult

    /** Well-formed, but over the decoded-image ceiling. */
    data object TooLarge : RawRgbaContainerResult
}

/** Whether [bytes] begin with the raw RGBA magic `RRGB`. */
internal fun isRawRgbaContainer(bytes: ByteArray): Boolean =
    bytes.size >= RAW_RGBA_MAGIC.size && RAW_RGBA_MAGIC.indices.all { bytes[it] == RAW_RGBA_MAGIC[it] }

/**
 * Validates [bytes] as a raw RGBA container (ADR 0092). The 16-byte little-endian header is `RRGB`,
 * `u8` version 1, `u8` flags (bit 0 premultiplied), two zero bytes, `u32` width and height.
 */
internal fun parseRawRgbaContainer(bytes: ByteArray, maximumDecodedBytes: Long): RawRgbaContainerResult {
    if (bytes.size < RAW_RGBA_HEADER_BYTES || !isRawRgbaContainer(bytes)) return RawRgbaContainerResult.Malformed
    if (bytes.unsignedByte(4) != RAW_RGBA_VERSION) return RawRgbaContainerResult.Unsupported
    val flags = bytes.unsignedByte(5)
    if (flags and RAW_RGBA_PREMULTIPLIED.inv() != 0) return RawRgbaContainerResult.Unsupported
    val width = bytes.unsignedInt(8)
    val height = bytes.unsignedInt(12)
    if (bytes.unsignedByte(6) != 0 || bytes.unsignedByte(7) != 0 || width < 1L || height < 1L) {
        return RawRgbaContainerResult.Malformed
    }
    // Both extents are bounded by the pixels present before multiplying, so the product cannot overflow.
    val payload = (bytes.size - RAW_RGBA_HEADER_BYTES).toLong()
    val pixels = payload / RGBA_BYTES_PER_PIXEL
    if (payload % RGBA_BYTES_PER_PIXEL != 0L || width > pixels || height > pixels || width * height != pixels) {
        return RawRgbaContainerResult.Malformed
    }
    if (payload > maximumDecodedBytes) return RawRgbaContainerResult.TooLarge
    return RawRgbaContainerResult.Admitted(
        RawRgbaImage(width.toInt(), height.toInt(), flags and RAW_RGBA_PREMULTIPLIED != 0, bytes),
    )
}

private fun ByteArray.unsignedByte(index: Int): Int = this[index].toInt() and 0xFF

private fun ByteArray.unsignedInt(index: Int): Long =
    (unsignedByte(index).toLong()) or
        (unsignedByte(index + 1).toLong() shl 8) or
        (unsignedByte(index + 2).toLong() shl 16) or
        (unsignedByte(index + 3).toLong() shl 24)

private val RAW_RGBA_MAGIC: ByteArray = "RRGB".encodeToByteArray()
internal const val RAW_RGBA_HEADER_BYTES: Int = 16
private const val RAW_RGBA_VERSION: Int = 1
private const val RAW_RGBA_PREMULTIPLIED: Int = 1
private const val RGBA_BYTES_PER_PIXEL: Long = 4L
