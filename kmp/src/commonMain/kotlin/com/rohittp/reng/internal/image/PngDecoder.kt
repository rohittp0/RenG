package com.rohittp.reng.internal.image

/**
 * The outcome of decoding a candidate PNG's bytes into one [DecodedImage]. [Malformed] and
 * [Unsupported] carry [PngReject] reasons, shared with [scanPng] (Task 4): most decode-time rejections
 * below reuse a container-walk reason where its meaning genuinely fits (e.g. [PngReject.FILTER_METHOD]
 * for an invalid per-scanline filter byte); where none does, this file adds its own —
 * [PngReject.TRNS_FORBIDDEN], [PngReject.IMAGE_DATA_LENGTH], and [PngReject.PALETTE_INDEX_OUT_OF_RANGE] —
 * rather than misdirecting a caller at the wrong fault. A reject code must be true of the fault, not
 * merely defensible.
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
    // width and height are each individually bounded to 1..2^31-1 by scanPng's DIMENSION_OUT_OF_RANGE
    // and ZERO_DIMENSION checks, so their product alone cannot overflow Long: at most (2^31-1)^2 ≈
    // 4.6e18, comfortably under Long.MAX_VALUE (~9.22e18). It is multiplying that product by 4 (for
    // RGBA) that can overflow: width = height = 2^31-1 wraps `* 4L` to a negative Long, so the
    // comparison is never true for any positive maximumDecodedBytes and TooLarge never fires — a
    // 58-byte file could otherwise defeat any ceiling. Comparing by DIVISION instead keeps this check
    // correct for every admitted dimension pair, with no wraparound: for maximumDecodedBytes >= 0,
    // `pixelCount > maximumDecodedBytes / 4` is exactly equivalent to
    // `pixelCount * 4 > maximumDecodedBytes` (Kotlin's Long division truncates toward zero, so for a
    // non-negative dividend the remainder it drops is always in 0..3, which never changes which side of
    // the comparison wins), and neither operand here is ever a product of two width/height-scale values.
    val pixelCount = width.toLong() * height.toLong()
    if (pixelCount > maximumDecodedBytes / 4) return PngDecodeResult.TooLarge

    if (scan.transparency != null && (colourType == 4 || colourType == 6)) {
        // Colour types 4 and 6 already carry a full alpha channel; a tRNS chunk is only meaningful
        // as a colour-key for types 0, 2, and 3.
        return PngDecodeResult.Malformed(PngReject.TRNS_FORBIDDEN)
    }

    val channels = channelsFor(colourType)
    // "bpp" for filtering purposes: bytes per complete pixel, rounded up to at least 1. Every colour
    // type scanPng admits is bit depth 8 only, so channels is already >= 1 and never fractional.
    val bpp = maxOf(1, channels)

    // strideLong and rawSizeLong are each proven in range BEFORE the `.toInt()` narrowing that uses
    // them, not after — a value that has already wrapped (as Long or as Int) cannot be rescued by a
    // later check. strideLong cannot overflow Long on its own (width <= 2^31-1, channels <= 4, product
    // at most ~8.6e9); bounding it to Int range here — before it feeds rawSizeLong's multiplication —
    // is what keeps THAT multiplication (height, up to ~2.1e9, times strideLong+1, now also bounded to
    // ~2.1e9, product at most ~4.6e18) safely under Long.MAX_VALUE too. This deliberately does not lean
    // on the ceiling check above to establish the bound, since that check's own correctness was exactly
    // this round's finding — this stays correct for every admitted width/height/colourType regardless
    // of what maximumDecodedBytes the caller passes.
    val strideLong = width.toLong() * channels
    if (strideLong > Int.MAX_VALUE.toLong()) return PngDecodeResult.TooLarge
    val stride = strideLong.toInt()

    val rawSizeLong = height.toLong() * (strideLong + 1)
    // The `- 1` leaves room for inflateExactly's own `+ 1` guard byte without that addition overflowing
    // Int either.
    if (rawSizeLong > Int.MAX_VALUE - 1L) return PngDecodeResult.TooLarge
    val rawSize = rawSizeLong.toInt()

    val inflated = inflateExactly(bytes, scan.imageDataRanges, rawSize)
        // The decompressed byte count doesn't match the raster size IHDR's own dimensions declare,
        // whether the stream ends earlier or keeps producing past it. This is a content-length
        // mismatch, not a container-framing fault — the chunk lengths themselves are fine.
        ?: return PngDecodeResult.Malformed(PngReject.IMAGE_DATA_LENGTH)

    val raster = unfilter(inflated, height, stride, bpp)
        ?: return PngDecodeResult.Malformed(PngReject.FILTER_METHOD)

    val rgba = widenToRgba(colourType, raster, width, height, channels, stride, scan.palette, scan.transparency)
        ?: return PngDecodeResult.Malformed(PngReject.PALETTE_INDEX_OUT_OF_RANGE)
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
    // height * stride is plain Int multiplication, unlike the Long ceiling arithmetic in
    // decodeAdmitted. This is only safe because that ceiling (maximumDecodedBytes) is itself bounded
    // to fit an Int by every caller today; decodePng has no caller yet. Whoever wires the first real
    // one must keep maximumDecodedBytes within Int range, or this needs to move to checked/Long math.
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

/**
 * Widens unfiltered raw pixel bytes into tightly packed RGBA8, applying `tRNS` for types 0, 2, 3.
 * Returns `null` if a colour-type-3 raster byte indexes past the end of the admitted `PLTE` payload —
 * `scanPng` only checks that a palette exists for colour type 3, never that every index a (possibly
 * hostile) raster contains actually fits it.
 */
private fun widenToRgba(
    colourType: Int,
    raster: ByteArray,
    width: Int,
    height: Int,
    channels: Int,
    stride: Int,
    palette: ByteArray?,
    transparency: ByteArray?,
): ByteArray? {
    // width * height * 4 is plain Int multiplication, unlike the Long ceiling arithmetic in
    // decodeAdmitted. Safe today only because maximumDecodedBytes — the value that bounds width and
    // height together — is itself assumed to fit an Int by every caller; decodePng has no caller yet.
    // Whoever wires the first real one must keep maximumDecodedBytes within Int range, or this needs
    // checked/Long math.
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
                    if (paletteOffset + 3 > paletteBytes.size) return null
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

/**
 * Colour type 0's `tRNS` is a single 2-byte grey sample; at bit depth 8 only the low byte matters.
 * `transparency` is either `null` or exactly 2 bytes here — `scanPng` rejects any other length for
 * colour type 0 as `Malformed(TRNS_LENGTH)` before this is ever reached, so no bounds check is needed.
 */
private fun greyKeyAlpha(transparency: ByteArray?, grey: Byte): Byte {
    if (transparency == null) return OPAQUE
    val keyGrey = transparency[1].toInt() and 0xFF
    return if ((grey.toInt() and 0xFF) == keyGrey) 0 else OPAQUE
}

/**
 * Colour type 2's `tRNS` is three 2-byte samples (R, G, B); at bit depth 8 only the low bytes matter.
 * `transparency` is either `null` or exactly 6 bytes here — `scanPng` rejects any other length for
 * colour type 2 as `Malformed(TRNS_LENGTH)` before this is ever reached, so no bounds check is needed.
 */
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
