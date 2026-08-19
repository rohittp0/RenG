package com.rohittp.reng.internal.image

/** The subset of `IHDR` fields the container walk needs to decide admission and drive decode. */
internal data class PngHeader(
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val colourType: Int,
    val interlaceMethod: Int,
)

/**
 * The outcome of walking a candidate PNG's container. [Admitted] is a well-formed file inside RenG's
 * accepted subset. [Malformed] means the bytes are not a valid PNG at all. [Unsupported] means the file
 * is a perfectly valid PNG outside what RenG decodes (bit depth, colour type, or interlace only).
 */
internal sealed interface PngScan {
    data class Admitted(
        val header: PngHeader,
        val palette: ByteArray?,
        val transparency: ByteArray?,
        val imageDataRanges: List<IntRange>,
    ) : PngScan

    data class Malformed(val reason: PngReject) : PngScan

    data class Unsupported(val reason: PngReject) : PngScan
}

/**
 * Every reason a scan (or, downstream, a decode — see `PngDecoder.kt`) can reject a file. Only
 * [BIT_DEPTH], [COLOUR_TYPE], and [INTERLACE] surface as [PngScan.Unsupported]; every other reason
 * surfaces as [PngScan.Malformed].
 */
internal enum class PngReject {
    SIGNATURE,
    IHDR_NOT_FIRST,
    IHDR_LENGTH,
    IEND_NOT_LAST,
    TRAILING_BYTES,
    CHUNK_LENGTH,
    CHUNK_CRC,
    UNKNOWN_CRITICAL_CHUNK,
    COMPRESSION_METHOD,
    FILTER_METHOD,
    ZERO_DIMENSION,

    /**
     * A declared `IHDR` width or height outside the PNG specification's valid range of 1 to 2^31-1. A
     * value of 2^31 or above cannot be represented as a positive 32-bit `Int`, so unchecked bit-math
     * over such a value would silently produce a negative width/height instead of failing loudly —
     * this check exists specifically to catch that before it ever reaches decode arithmetic.
     */
    DIMENSION_OUT_OF_RANGE,
    PALETTE_MISSING,
    PALETTE_FORBIDDEN,
    BIT_DEPTH,
    COLOUR_TYPE,
    INTERLACE,

    /** A `tRNS` chunk on a colour type (4 or 6) that already carries a full alpha channel. */
    TRNS_FORBIDDEN,

    /** The inflated `IDAT` stream's decompressed byte count does not match the declared raster size. */
    IMAGE_DATA_LENGTH,

    /** A colour-type-3 raster byte indexes past the end of the `PLTE` payload it was admitted with. */
    PALETTE_INDEX_OUT_OF_RANGE,
}

private val PNG_SIGNATURE = byteArrayOf(-0x77, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private const val IHDR_PAYLOAD_LENGTH = 13

private val ACCEPTED_COLOUR_TYPES = setOf(0, 2, 3, 4, 6)

private fun typeCode(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF shl 24) or
        (bytes[offset + 1].toInt() and 0xFF shl 16) or
        (bytes[offset + 2].toInt() and 0xFF shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

private val TYPE_IHDR = typeCode(byteArrayOf(0x49, 0x48, 0x44, 0x52), 0)
private val TYPE_PLTE = typeCode(byteArrayOf(0x50, 0x4C, 0x54, 0x45), 0)
private val TYPE_IDAT = typeCode(byteArrayOf(0x49, 0x44, 0x41, 0x54), 0)
private val TYPE_IEND = typeCode(byteArrayOf(0x49, 0x45, 0x4E, 0x44), 0)
private val TYPE_TRNS = typeCode(byteArrayOf(0x74, 0x52, 0x4E, 0x53), 0)

/** The fifth bit of a chunk type's first byte is clear for critical chunks, set for ancillary ones. */
private fun isCritical(type: Int): Boolean = (type ushr 24) and 0x20 == 0

private fun readUInt32BE(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
        (bytes[offset + 3].toLong() and 0xFF)

/**
 * Walks a candidate PNG's container: the eight-byte signature, then every chunk's four-byte length,
 * four-byte type, payload, and four-byte CRC. Every chunk's CRC is validated, including ancillary ones.
 * `IHDR` must be the first chunk and exactly 13 bytes; `IEND` must be the last chunk with nothing after
 * it. Unknown critical chunks are rejected; unknown ancillary chunks are skipped. `IDAT` payload ranges
 * are collected in the order they appear, not concatenated, so a decoder can stream them without
 * buffering the whole compressed image.
 */
internal fun scanPng(bytes: ByteArray): PngScan {
    if (bytes.size < PNG_SIGNATURE.size || !bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
        return PngScan.Malformed(PngReject.SIGNATURE)
    }

    var header: PngHeader? = null
    var palette: ByteArray? = null
    var transparency: ByteArray? = null
    val imageDataRanges = mutableListOf<IntRange>()
    var sawIhdr = false
    var sawIend = false

    var pos = PNG_SIGNATURE.size
    val total = bytes.size.toLong()

    while (pos < bytes.size) {
        if (pos + 8L > total) return PngScan.Malformed(PngReject.CHUNK_LENGTH)

        val length = readUInt32BE(bytes, pos)
        val typeOffset = pos + 4
        val payloadOffset = pos + 8
        val crcOffset = payloadOffset + length

        if (crcOffset + 4L > total) return PngScan.Malformed(PngReject.CHUNK_LENGTH)

        val type = typeCode(bytes, typeOffset)
        val storedCrc = readUInt32BE(bytes, crcOffset.toInt())
        val computedCrc = crc32(0u, bytes, typeOffset, (4 + length).toInt()).toLong()
        if (storedCrc != computedCrc) return PngScan.Malformed(PngReject.CHUNK_CRC)

        val payloadLength = length.toInt()
        val payloadStart = payloadOffset

        if (!sawIhdr) {
            if (type != TYPE_IHDR) return PngScan.Malformed(PngReject.IHDR_NOT_FIRST)
            if (payloadLength != IHDR_PAYLOAD_LENGTH) return PngScan.Malformed(PngReject.IHDR_LENGTH)

            val width = ((bytes[payloadStart].toInt() and 0xFF) shl 24) or
                ((bytes[payloadStart + 1].toInt() and 0xFF) shl 16) or
                ((bytes[payloadStart + 2].toInt() and 0xFF) shl 8) or
                (bytes[payloadStart + 3].toInt() and 0xFF)
            val height = ((bytes[payloadStart + 4].toInt() and 0xFF) shl 24) or
                ((bytes[payloadStart + 5].toInt() and 0xFF) shl 16) or
                ((bytes[payloadStart + 6].toInt() and 0xFF) shl 8) or
                (bytes[payloadStart + 7].toInt() and 0xFF)
            val bitDepth = bytes[payloadStart + 8].toInt() and 0xFF
            val colourType = bytes[payloadStart + 9].toInt() and 0xFF
            val compressionMethod = bytes[payloadStart + 10].toInt() and 0xFF
            val filterMethod = bytes[payloadStart + 11].toInt() and 0xFF
            val interlaceMethod = bytes[payloadStart + 12].toInt() and 0xFF

            if (bitDepth != 8) return PngScan.Unsupported(PngReject.BIT_DEPTH)
            if (colourType !in ACCEPTED_COLOUR_TYPES) return PngScan.Unsupported(PngReject.COLOUR_TYPE)
            if (interlaceMethod != 0) return PngScan.Unsupported(PngReject.INTERLACE)
            if (compressionMethod != 0) return PngScan.Malformed(PngReject.COMPRESSION_METHOD)
            if (filterMethod != 0) return PngScan.Malformed(PngReject.FILTER_METHOD)
            // width/height above are raw 32-bit reinterpretations of unsigned IHDR fields: a declared
            // value of 2^31 or above (0x80000000..0xFFFFFFFF) wraps to a negative Int here. The PNG
            // specification's valid dimension range is 1 to 2^31-1, so a negative value is out of range
            // — checked before ZERO_DIMENSION so a decoder never has to reason about a negative width or
            // height downstream.
            if (width < 0 || height < 0) return PngScan.Malformed(PngReject.DIMENSION_OUT_OF_RANGE)
            if (width == 0 || height == 0) return PngScan.Malformed(PngReject.ZERO_DIMENSION)

            header = PngHeader(width, height, bitDepth, colourType, interlaceMethod)
            sawIhdr = true
        } else if (type == TYPE_IEND) {
            pos = crcOffset.toInt() + 4
            if (pos.toLong() != total) return PngScan.Malformed(PngReject.TRAILING_BYTES)
            sawIend = true
            break
        } else if (type == TYPE_IDAT) {
            imageDataRanges.add(payloadStart until (payloadStart + payloadLength))
        } else if (type == TYPE_PLTE) {
            palette = bytes.copyOfRange(payloadStart, payloadStart + payloadLength)
        } else if (type == TYPE_TRNS) {
            transparency = bytes.copyOfRange(payloadStart, payloadStart + payloadLength)
        } else if (isCritical(type)) {
            return PngScan.Malformed(PngReject.UNKNOWN_CRITICAL_CHUNK)
        }
        // Unknown ancillary chunks (and recognised ancillary chunks with no admission role) are skipped.

        pos = crcOffset.toInt() + 4
    }

    if (!sawIend) return PngScan.Malformed(PngReject.IEND_NOT_LAST)
    val admittedHeader = header ?: return PngScan.Malformed(PngReject.IHDR_NOT_FIRST)

    val hasPalette = palette != null
    when (admittedHeader.colourType) {
        3 -> if (!hasPalette) return PngScan.Malformed(PngReject.PALETTE_MISSING)
        0, 4 -> if (hasPalette) return PngScan.Malformed(PngReject.PALETTE_FORBIDDEN)
    }

    return PngScan.Admitted(admittedHeader, palette, transparency, imageDataRanges)
}
