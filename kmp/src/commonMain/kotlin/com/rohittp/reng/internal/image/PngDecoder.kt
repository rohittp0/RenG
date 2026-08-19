package com.rohittp.reng.internal.image

import com.rohittp.reng.internal.freshCopy

/**
 * One decoded image in RenG's single canonical form: tightly packed RGBA8, unpremultiplied, with no
 * row padding. Every read returns a fresh copy so a caller can never observe or corrupt the bytes this
 * holds, and mutating a returned array can never reach back into this instance.
 */
internal class DecodedImage(val width: Int, val height: Int, rgba: ByteArray) {
    private val bytes: ByteArray = rgba.freshCopy()

    val byteCount: Int get() = bytes.size

    fun rgbaSnapshot(): ByteArray = bytes.freshCopy()
}

/**
 * The outcome of decoding a candidate PNG's bytes into one [DecodedImage]. [Malformed] and
 * [Unsupported] carry [scanPng]'s own [PngReject] reasons — Task 4's enum is a closed, independently
 * reviewed set of 16 branches, and every decode-time rejection below reuses one of those values rather
 * than adding a new one.
 */
internal sealed interface PngDecodeResult {
    data class Success(val image: DecodedImage) : PngDecodeResult
    data class Malformed(val reason: PngReject) : PngDecodeResult
    data class Unsupported(val reason: PngReject) : PngDecodeResult
    data object TooLarge : PngDecodeResult
}

/** Channels per pixel for each colour type [scanPng] admits; also this format's filtering "bpp". */
private fun channelsFor(colourType: Int): Int = when (colourType) {
    0 -> 1 // greyscale
    2 -> 3 // truecolour
    3 -> 1 // palette index
    4 -> 2 // greyscale + alpha
    6 -> 4 // truecolour + alpha
    else -> error("colour type already validated by scanPng to be one of 0, 2, 3, 4, 6")
}

/**
 * Decodes a candidate PNG into one canonical RGBA8 [DecodedImage]. Walks the container via [scanPng]
 * (Task 4), then — for an admitted file only — reassembles the `IDAT` ranges into one zlib stream via
 * [InflateStream] (Task 3), undoes the five PNG row filters, and widens greyscale/palette pixels into
 * RGBA8, applying `tRNS` alpha for colour types 0, 2, and 3. `maximumDecodedBytes` gates the *declared*
 * raster size — `width * height * 4` from the header alone — before any array is allocated.
 */
internal fun decodePng(bytes: ByteArray, maximumDecodedBytes: Long): PngDecodeResult {
    return when (val scan = scanPng(bytes)) {
        is PngScan.Malformed -> PngDecodeResult.Malformed(scan.reason)
        is PngScan.Unsupported -> PngDecodeResult.Unsupported(scan.reason)
        is PngScan.Admitted -> decodeAdmitted(bytes, scan, maximumDecodedBytes)
    }
}

private fun decodeAdmitted(bytes: ByteArray, scan: PngScan.Admitted, maximumDecodedBytes: Long): PngDecodeResult {
    val header = scan.header
    val width = header.width
    val height = header.height
    val colourType = header.colourType

    // Decide the ceiling from the header's own dimensions alone, before any array — raster or
    // output — is allocated, so a maliciously large declared size can never force a huge allocation.
    if (width.toLong() * height.toLong() * 4L > maximumDecodedBytes) return PngDecodeResult.TooLarge

    if (scan.transparency != null && (colourType == 4 || colourType == 6)) {
        // Colour types 4 and 6 already carry a full alpha channel; a tRNS chunk is only meaningful
        // as a colour-key for types 0, 2, and 3. Reusing PALETTE_FORBIDDEN: like an out-of-place
        // PLTE, this is an ancillary chunk present where the colour type forbids it.
        return PngDecodeResult.Malformed(PngReject.PALETTE_FORBIDDEN)
    }

    val channels = channelsFor(colourType)
    // "bpp" for filtering purposes: bytes per complete pixel, rounded up to at least 1. Every colour
    // type scanPng admits is bit depth 8 only, so channels is already >= 1 and never fractional.
    val bpp = maxOf(1, channels)
    val strideLong = width.toLong() * channels
    val rawSizeLong = height.toLong() * (strideLong + 1)
    val rawSize = rawSizeLong.toInt()
    val stride = strideLong.toInt()

    val inflated = inflateExactly(bytes, scan.imageDataRanges, rawSize)
        // Reusing CHUNK_LENGTH: like a chunk whose declared length does not fit the buffer, this is a
        // declared raster length (from IHDR's own dimensions) that the decompressed byte count does
        // not match, whether the stream ends earlier or keeps producing past it.
        ?: return PngDecodeResult.Malformed(PngReject.CHUNK_LENGTH)

    val raster = unfilter(inflated, height, stride, bpp)
        ?: return PngDecodeResult.Malformed(PngReject.FILTER_METHOD)

    val rgba = widenToRgba(colourType, raster, width, height, channels, stride, scan.palette, scan.transparency)
    return PngDecodeResult.Success(DecodedImage(width, height, rgba))
}

/**
 * Feeds every `IDAT` range into one [InflateStream], in order, so a zlib stream split across chunk
 * boundaries — including mid-symbol — decodes as the single logical stream it is; a new
 * [InflateStream] per range would corrupt exactly that split. The scratch buffer carries one guard
 * byte past [expectedSize]: a stream that decodes to fewer bytes leaves `produced` short of
 * [expectedSize] when it finishes; a stream that decodes to more bytes either fills the guard byte
 * (`produced` overshoots [expectedSize]) or runs out of output room before finishing (`finished` stays
 * false) — either mismatch is visible from `produced`/`finished` alone, with no separate probe buffer
 * needed. Returns `null` for any of those mismatches, or if the compressed bytes are not a valid zlib
 * stream at all.
 */
private fun inflateExactly(bytes: ByteArray, ranges: List<IntRange>, expectedSize: Int): ByteArray? {
    val scratch = ByteArray(expectedSize + 1)
    val stream = InflateStream()
    return try {
        var produced = 0
        var finished = false
        for (range in ranges) {
            var pos = range.first
            val end = range.last + 1
            while (pos < end) {
                val step = stream.inflate(bytes.copyOfRange(pos, end), scratch, produced)
                pos += step.consumed
                produced += step.produced
                if (step.finished) {
                    finished = true
                    break
                }
                if (step.consumed == 0 && step.produced == 0) break
            }
            if (finished) break
        }
        if (!finished || produced != expectedSize) null else scratch.copyOf(expectedSize)
    } catch (failure: InflateException) {
        null
    } finally {
        stream.close()
    }
}

/**
 * Undoes the five PNG row filters, producing tightly packed raw pixel bytes (no filter-type bytes, no
 * row padding). `a` is the byte `bpp` positions back in the row being reconstructed, `b` the byte
 * directly above (already reconstructed), and `c` the byte `bpp` positions back in the row above; all
 * three are zero outside the image. All arithmetic is modulo 256 on unsigned bytes. Returns `null` if
 * any scanline's filter-type byte is outside 0..4.
 */
private fun unfilter(scratch: ByteArray, height: Int, stride: Int, bpp: Int): ByteArray? {
    val raster = ByteArray(height * stride)
    for (row in 0 until height) {
        val rowStart = row * (stride + 1)
        val filterType = scratch[rowStart].toInt() and 0xFF
        if (filterType > 4) return null
        val dataStart = rowStart + 1
        val outRowStart = row * stride
        for (i in 0 until stride) {
            val x = scratch[dataStart + i].toInt() and 0xFF
            val a = if (i >= bpp) raster[outRowStart + i - bpp].toInt() and 0xFF else 0
            val b = if (row > 0) raster[outRowStart - stride + i].toInt() and 0xFF else 0
            val c = if (row > 0 && i >= bpp) raster[outRowStart - stride + i - bpp].toInt() and 0xFF else 0
            val recon = when (filterType) {
                0 -> x
                1 -> x + a
                2 -> x + b
                3 -> x + ((a + b) / 2)
                4 -> x + paeth(a, b, c)
                else -> error("unreachable: filterType already validated to be in 0..4")
            }
            raster[outRowStart + i] = (recon and 0xFF).toByte()
        }
    }
    return raster
}

/**
 * The PNG Paeth predictor. Ties are broken in the specified order: `a` (left) wins a tie with `b`
 * (above), and `b` wins a tie with `c` (upper-left) — implemented as the exact comparison order below,
 * not whatever a naive chain of comparisons would yield.
 */
private fun paeth(a: Int, b: Int, c: Int): Int {
    val p = a + b - c
    val pa = kotlin.math.abs(p - a)
    val pb = kotlin.math.abs(p - b)
    val pc = kotlin.math.abs(p - c)
    return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
}

private const val OPAQUE: Byte = -1 // 0xFF unsigned

/** Widens unfiltered raw pixel bytes into tightly packed RGBA8, applying `tRNS` for types 0, 2, 3. */
private fun widenToRgba(
    colourType: Int,
    raster: ByteArray,
    width: Int,
    height: Int,
    channels: Int,
    stride: Int,
    palette: ByteArray?,
    transparency: ByteArray?,
): ByteArray {
    val rgba = ByteArray(width * height * 4)
    var out = 0
    for (row in 0 until height) {
        val rowStart = row * stride
        for (col in 0 until width) {
            val pixelStart = rowStart + col * channels
            when (colourType) {
                0 -> {
                    val grey = raster[pixelStart]
                    rgba[out] = grey
                    rgba[out + 1] = grey
                    rgba[out + 2] = grey
                    rgba[out + 3] = greyKeyAlpha(transparency, grey)
                }
                2 -> {
                    val r = raster[pixelStart]
                    val g = raster[pixelStart + 1]
                    val b = raster[pixelStart + 2]
                    rgba[out] = r
                    rgba[out + 1] = g
                    rgba[out + 2] = b
                    rgba[out + 3] = rgbKeyAlpha(transparency, r, g, b)
                }
                3 -> {
                    val index = raster[pixelStart].toInt() and 0xFF
                    val paletteBytes = requireNotNull(palette) { "colour type 3 requires a palette" }
                    val paletteOffset = index * 3
                    rgba[out] = paletteBytes[paletteOffset]
                    rgba[out + 1] = paletteBytes[paletteOffset + 1]
                    rgba[out + 2] = paletteBytes[paletteOffset + 2]
                    rgba[out + 3] = paletteAlpha(transparency, index)
                }
                4 -> {
                    val grey = raster[pixelStart]
                    rgba[out] = grey
                    rgba[out + 1] = grey
                    rgba[out + 2] = grey
                    rgba[out + 3] = raster[pixelStart + 1]
                }
                else -> { // 6: truecolour + alpha, copied through unchanged.
                    rgba[out] = raster[pixelStart]
                    rgba[out + 1] = raster[pixelStart + 1]
                    rgba[out + 2] = raster[pixelStart + 2]
                    rgba[out + 3] = raster[pixelStart + 3]
                }
            }
            out += 4
        }
    }
    return rgba
}

/** Colour type 0's `tRNS` is a single 2-byte grey sample; at bit depth 8 only the low byte matters. */
private fun greyKeyAlpha(transparency: ByteArray?, grey: Byte): Byte {
    if (transparency == null) return OPAQUE
    val keyGrey = transparency[1].toInt() and 0xFF
    return if ((grey.toInt() and 0xFF) == keyGrey) 0 else OPAQUE
}

/** Colour type 2's `tRNS` is three 2-byte samples (R, G, B); at bit depth 8 only the low bytes matter. */
private fun rgbKeyAlpha(transparency: ByteArray?, r: Byte, g: Byte, b: Byte): Byte {
    if (transparency == null) return OPAQUE
    val keyR = transparency[1].toInt() and 0xFF
    val keyG = transparency[3].toInt() and 0xFF
    val keyB = transparency[5].toInt() and 0xFF
    val isKeyed = (r.toInt() and 0xFF) == keyR && (g.toInt() and 0xFF) == keyG && (b.toInt() and 0xFF) == keyB
    return if (isKeyed) 0 else OPAQUE
}

/** Colour type 3's `tRNS` is one alpha byte per palette entry, in order; entries past it default opaque. */
private fun paletteAlpha(transparency: ByteArray?, index: Int): Byte {
    if (transparency == null || index >= transparency.size) return OPAQUE
    return transparency[index]
}
