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
 *
 * [storedFastPath] exists for tests alone: `false` forces every input through [InflateStream], so a
 * test can prove [decodeStoredRows] answers byte for byte what the general path does.
 */
internal fun decodePng(
    bytes: ByteArray,
    maximumDecodedBytes: Long,
    maximumWorkingBytes: Long = Long.MAX_VALUE,
    storedFastPath: Boolean = true,
): PngDecodeResult = prescanPng(bytes).decode(maximumDecodedBytes, maximumWorkingBytes, storedFastPath)

/**
 * Exact final RGBA8 payload for an admitted PNG, without allocating decoded storage. `null` means
 * the container is not admitted or the declared dimensions cannot be represented as a Long byte
 * count; [decodePng] remains the authority on the eventual typed rejection.
 */
internal fun projectedPngRgbaBytes(bytes: ByteArray): Long? = prescanPng(bytes).projectedRgbaBytes

/** Walks [bytes]' container once, for a caller that needs both its projection and its decode. */
internal fun prescanPng(bytes: ByteArray): PngPrescan = PngPrescan(bytes, scanPng(bytes))

/**
 * One [scanPng] of a candidate PNG, answering both [projectedPngRgbaBytes] and [decodePng] without
 * walking the chunks and checking their CRCs a second time. The scan's ranges index into [bytes], so
 * the caller must not mutate them while this is in use.
 */
internal class PngPrescan internal constructor(private val bytes: ByteArray, private val scan: PngScan) {
    /** Exactly what [projectedPngRgbaBytes] returns for the same bytes. */
    val projectedRgbaBytes: Long?
        get() {
            val admitted = scan as? PngScan.Admitted ?: return null
            val pixels = admitted.header.width.toLong() * admitted.header.height.toLong()
            if (pixels > Long.MAX_VALUE / 4L) return null
            return pixels * 4L
        }

    /** Exactly what [decodePng] returns for the same bytes and limits. */
    fun decode(
        maximumDecodedBytes: Long,
        maximumWorkingBytes: Long = Long.MAX_VALUE,
        storedFastPath: Boolean = true,
    ): PngDecodeResult = when (scan) {
        is PngScan.Malformed -> PngDecodeResult.Malformed(scan.reason)
        is PngScan.Unsupported -> PngDecodeResult.Unsupported(scan.reason)
        is PngScan.Admitted -> decodeAdmitted(bytes, scan, maximumDecodedBytes, maximumWorkingBytes, storedFastPath)
    }
}

private fun decodeAdmitted(
    bytes: ByteArray,
    scan: PngScan.Admitted,
    maximumDecodedBytes: Long,
    maximumWorkingBytes: Long,
    storedFastPath: Boolean,
): PngDecodeResult {
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

    // Prove the row width fits before narrowing it for the two streaming row buffers. This check does
    // not rely on callers keeping their configured ceiling within Int range; decodePng remains safe for
    // every admitted IHDR and any Long ceiling a direct internal test supplies.
    val strideLong = width.toLong() * channels
    if (strideLong > Int.MAX_VALUE.toLong()) return PngDecodeResult.TooLarge
    val stride = strideLong.toInt()

    val rgbaSizeLong = pixelCount * 4L
    if (rgbaSizeLong > Int.MAX_VALUE.toLong()) return PngDecodeResult.TooLarge
    val rowWorkspaceBytes = 2L * (strideLong + 1L)
    val uploadWorkspaceBytes = minOf(rgbaSizeLong, MAXIMUM_PREMULTIPLY_UPLOAD_WORKSPACE_BYTES)
    val additionalWorkingBytes = maxOf(rowWorkspaceBytes, uploadWorkspaceBytes)
    if (rgbaSizeLong > maximumWorkingBytes || additionalWorkingBytes > maximumWorkingBytes - rgbaSizeLong) {
        return PngDecodeResult.TooLarge
    }

    var currentRow = ByteArray(stride + 1)
    var previousRow = ByteArray(stride + 1)
    val rgba = ByteArray(rgbaSizeLong.toInt())
    if (storedFastPath && decodeStoredRows(bytes, scan, bpp, channels, currentRow, previousRow, rgba)) {
        return PngDecodeResult.Success(DecodedImage.takeOwnership(width, height, rgba))
    }
    // The fast path declined, possibly part-way through: start again from a zero row above the first,
    // in the same buffers, so a declined attempt costs no working bytes beyond what was budgeted above.
    previousRow.fill(0)
    // Constructed inside the try: a native `inflateInit_` failure throws InflateException from the
    // constructor, and it must reach the typed result below rather than escape decodePng.
    var openedInflater: IdatInflater? = null
    return try {
        val inflater = IdatInflater(bytes, scan.imageDataRanges).also { openedInflater = it }
        for (row in 0 until height) {
            if (!inflater.fillExactly(currentRow)) {
                return PngDecodeResult.Malformed(PngReject.IMAGE_DATA_LENGTH)
            }
            if (!unfilterRow(currentRow, previousRow, bpp)) {
                return PngDecodeResult.Malformed(PngReject.FILTER_METHOD)
            }
            if (!widenRowToRgba(
                    colourType = colourType,
                    row = currentRow,
                    width = width,
                    channels = channels,
                    palette = scan.palette,
                    transparency = scan.transparency,
                    rgba = rgba,
                    rgbaOffset = row * width * 4,
                )
            ) {
                return PngDecodeResult.Malformed(PngReject.PALETTE_INDEX_OUT_OF_RANGE)
            }
            val swap = previousRow
            previousRow = currentRow
            currentRow = swap
        }
        if (!inflater.finishWithoutOutput()) {
            PngDecodeResult.Malformed(PngReject.IMAGE_DATA_LENGTH)
        } else {
            PngDecodeResult.Success(DecodedImage.takeOwnership(width, height, rgba))
        }
    } catch (failure: InflateException) {
        PngDecodeResult.Malformed(PngReject.IMAGE_DATA_LENGTH)
    } finally {
        openedInflater?.close()
    }
}

/**
 * One zlib stream spanning arbitrary IDAT ranges, keeping only offsets into [bytes]. No compressed
 * range is copied. Output is deliberately caller-sized so PNG decoding can retain one row at a time.
 */
private class IdatInflater(private val bytes: ByteArray, private val ranges: List<IntRange>) {
    val stream = InflateStream()
    private var rangeIndex = 0
    private var inputOffset = ranges.firstOrNull()?.first ?: 0
    private var finished = false

    fun fillExactly(output: ByteArray): Boolean {
        var outputOffset = 0
        while (outputOffset < output.size) {
            val step = inflate(output, outputOffset, output.size - outputOffset)
            outputOffset += step.produced
            if (step.finished) return outputOffset == output.size
            if (step.consumed == 0 && step.produced == 0) return false
        }
        return true
    }

    /** Finishes the zlib trailer while proving there is no decompressed byte beyond the last row. */
    fun finishWithoutOutput(): Boolean {
        if (finished) return true
        val guard = ByteArray(1)
        while (true) {
            val step = inflate(guard, 0, 1)
            if (step.produced != 0) return false
            if (step.finished) return true
            if (step.consumed == 0) return false
        }
    }

    fun close() {
        stream.close()
    }

    private fun inflate(output: ByteArray, outputOffset: Int, outputLength: Int): InflateStep {
        advanceEmptyRanges()
        val range = ranges.getOrNull(rangeIndex)
        val end = range?.let { it.last + 1 } ?: inputOffset
        val inputLength = if (range == null) 0 else end - inputOffset
        val step = stream.inflate(
            input = bytes,
            inputOffset = inputOffset,
            inputLength = inputLength,
            output = output,
            outputOffset = outputOffset,
            outputLength = outputLength,
        )
        inputOffset += step.consumed
        if (step.finished) finished = true
        advanceEmptyRanges()
        return step
    }

    private fun advanceEmptyRanges() {
        while (rangeIndex < ranges.size && inputOffset > ranges[rangeIndex].last) {
            rangeIndex += 1
            inputOffset = ranges.getOrNull(rangeIndex)?.first ?: bytes.size
        }
    }
}

/**
 * The whole image, when its zlib stream is nothing but *stored* deflate blocks (`BTYPE = 00`), copied
 * row by row straight out of the `IDAT` ranges instead of through [InflateStream]; `false` when the
 * stream is anything else, or anything about it is wrong, and the caller then decodes it the general
 * way from the start.
 *
 * **Why.** The app writes its labels, glyph data and route and region bakes with a PNG writer that
 * stores rather than compresses -- filter 0 on every row, RGBA8, stored blocks -- and on an OPPO
 * CPH2629 export (2026-10-09) decoding about twenty newly published labels stalled one frame for
 * about 320 ms, almost all of it the inflate seam, the row unfilter and the widen. A stored block's
 * bytes *are* its output, so the inflate is a copy.
 *
 * **It answers only what the general path would.** ADR 0020 delegates inflate to the platform's zlib
 * rather than writing one, and this does not reopen that: it decodes no Huffman code and repairs
 * nothing. It returns `true` only for a stream zlib itself accepts and inflates to exactly these bytes
 * -- a header zlib accepts (method 8, a window no larger than 32 KiB, a valid check, no preset
 * dictionary), every block stored with `NLEN` the complement of `LEN`, a final block, exactly the
 * declared raster, the Adler-32 zlib verifies, and nothing after it -- *and* only when every row then
 * unfilters and widens cleanly. Every other outcome, including every failure, is left to the general
 * path, so where a malformed stream fails, and which [PngReject] it names, stay exactly zlib's.
 *
 * [currentRow] and [previousRow] arrive as the general path allocates them, `previousRow` zero; this
 * may leave anything in them, and in [rgba], when it declines.
 */
private fun decodeStoredRows(
    bytes: ByteArray,
    scan: PngScan.Admitted,
    bpp: Int,
    channels: Int,
    currentRow: ByteArray,
    previousRow: ByteArray,
    rgba: ByteArray,
): Boolean {
    val header = scan.header
    val stream = StoredIdatReader(bytes, scan.imageDataRanges)
    val cmf = stream.readByte()
    val flg = stream.readByte()
    if (cmf < 0 || flg < 0) return false
    // RFC 1950: method 8 (deflate), CINFO <= 7 (zlib's default 15-bit window refuses a larger one),
    // FCHECK making CMF*256+FLG a multiple of 31, and no preset dictionary.
    if (cmf and 0x0F != 8 || cmf ushr 4 > 7 || (cmf * 256 + flg) % 31 != 0 || flg and 0x20 != 0) return false

    var current = currentRow
    var previous = previousRow
    val adler = Adler32Stream()
    for (row in 0 until header.height) {
        if (!stream.fill(current)) return false
        adler.update(current, 0, current.size)
        if (!unfilterRow(current, previous, bpp)) return false
        if (!widenRowToRgba(
                colourType = header.colourType,
                row = current,
                width = header.width,
                channels = channels,
                palette = scan.palette,
                transparency = scan.transparency,
                rgba = rgba,
                rgbaOffset = row * header.width * 4,
            )
        ) {
            return false
        }
        val swap = previous
        previous = current
        current = swap
    }
    return stream.finish() && stream.readBigEndianInt() == adler.value && stream.exhausted
}

/**
 * Whether [decodeStoredRows] alone decodes [bytes], for tests that must know the fast path -- not the
 * general one it falls back to -- is what produced an answer.
 */
internal fun storedFastPathDecodes(bytes: ByteArray): Boolean {
    val scan = scanPng(bytes) as? PngScan.Admitted ?: return false
    val channels = channelsFor(scan.header.colourType)
    val stride = scan.header.width * channels
    return decodeStoredRows(
        bytes = bytes,
        scan = scan,
        bpp = channels,
        channels = channels,
        currentRow = ByteArray(stride + 1),
        previousRow = ByteArray(stride + 1),
        rgba = ByteArray(scan.header.width * scan.header.height * 4),
    )
}

/**
 * One zlib stream of stored deflate blocks, read straight out of the `IDAT` ranges with no copy of
 * the compressed payload, for [decodeStoredRows]. Every method answers `false` or `-1` rather than
 * throwing for a stream it cannot read, because declining is the caller's whole recovery.
 */
private class StoredIdatReader(private val bytes: ByteArray, private val ranges: List<IntRange>) {
    private var rangeIndex = 0
    private var position = ranges.firstOrNull()?.first ?: 0

    /** Bytes still to copy from the current block, before the next block header. */
    private var blockRemaining = 0
    private var sawFinalBlock = false

    private fun skipExhaustedRanges() {
        while (rangeIndex < ranges.size && position > ranges[rangeIndex].last) {
            rangeIndex += 1
            position = ranges.getOrNull(rangeIndex)?.first ?: 0
        }
    }

    val exhausted: Boolean
        get() {
            skipExhaustedRanges()
            return rangeIndex >= ranges.size
        }

    fun readByte(): Int {
        skipExhaustedRanges()
        if (rangeIndex >= ranges.size) return -1
        return bytes[position++].toInt() and 0xFF
    }

    /** The next stored block's header; `false` for any other block type, a bad `NLEN`, or no input. */
    private fun readBlockHeader(): Boolean {
        val type = readByte()
        if (type < 0 || type and 0x06 != 0) return false
        val len0 = readByte()
        val len1 = readByte()
        val nlen0 = readByte()
        val nlen1 = readByte()
        if (len0 < 0 || len1 < 0 || nlen0 < 0 || nlen1 < 0) return false
        val length = len0 or (len1 shl 8)
        if (length != (nlen0 or (nlen1 shl 8)).inv() and 0xFFFF) return false
        // The header's five remaining bits are padding to the byte boundary, which zlib ignores too.
        sawFinalBlock = type and 0x01 != 0
        blockRemaining = length
        return true
    }

    /** Copies exactly [target]'s size of decompressed bytes into it; `false` if the stream ends first. */
    fun fill(target: ByteArray): Boolean {
        var filled = 0
        while (filled < target.size) {
            if (blockRemaining == 0) {
                if (sawFinalBlock || !readBlockHeader()) return false
                continue
            }
            skipExhaustedRanges()
            if (rangeIndex >= ranges.size) return false
            val available = ranges[rangeIndex].last + 1 - position
            val count = minOf(available, blockRemaining, target.size - filled)
            bytes.copyInto(target, filled, position, position + count)
            position += count
            filled += count
            blockRemaining -= count
        }
        return true
    }

    /**
     * Reads past the end of the raster to the end of the final block: `true` only if no decompressed
     * byte remains, which leaves just empty stored blocks to cross.
     */
    fun finish(): Boolean {
        while (true) {
            if (blockRemaining != 0) return false
            if (sawFinalBlock) return true
            if (!readBlockHeader()) return false
        }
    }

    /** The four big-endian bytes of the Adler-32 trailer, or `-1L` if the stream ends first. */
    fun readBigEndianInt(): Long {
        var value = 0L
        repeat(4) {
            val next = readByte()
            if (next < 0) return -1L
            value = (value shl 8) or next.toLong()
        }
        return value
    }
}

/**
 * Undoes one row's PNG filter into [current]. [previous] is the already reconstructed prior row.
 *
 * One loop per filter type rather than one `when` per byte: the filter is fixed for the whole row,
 * and the per-byte branch, with left and upper-left read for every type that does not use them, was
 * most of this function's time. A row's first [bpp] bytes have no left neighbour, so `a` and `c` are
 * zero there: Sub leaves them as they are, Average adds `b / 2`, and Paeth's predictor is `b`, kept as
 * a call to [paeth] so the tie order stays in one place. Byte addition wraps modulo 256, as `and 0xFF`
 * did.
 */
private fun unfilterRow(current: ByteArray, previous: ByteArray, bpp: Int): Boolean {
    val filterType = current[0].toInt() and 0xFF
    val size = current.size
    val firstWithLeft = minOf(bpp + 1, size)
    when (filterType) {
        0 -> Unit
        1 -> for (i in firstWithLeft until size) {
            current[i] = (current[i] + current[i - bpp]).toByte()
        }
        2 -> for (i in 1 until size) {
            current[i] = (current[i] + previous[i]).toByte()
        }
        3 -> {
            for (i in 1 until firstWithLeft) {
                current[i] = (current[i] + ((previous[i].toInt() and 0xFF) / 2)).toByte()
            }
            for (i in firstWithLeft until size) {
                val a = current[i - bpp].toInt() and 0xFF
                val b = previous[i].toInt() and 0xFF
                current[i] = (current[i] + ((a + b) / 2)).toByte()
            }
        }
        4 -> {
            for (i in 1 until firstWithLeft) {
                current[i] = (current[i] + paeth(0, previous[i].toInt() and 0xFF, 0)).toByte()
            }
            for (i in firstWithLeft until size) {
                val a = current[i - bpp].toInt() and 0xFF
                val b = previous[i].toInt() and 0xFF
                val c = previous[i - bpp].toInt() and 0xFF
                current[i] = (current[i] + paeth(a, b, c)).toByte()
            }
        }
        else -> return false
    }
    return true
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
private fun widenRowToRgba(
    colourType: Int,
    row: ByteArray,
    width: Int,
    channels: Int,
    palette: ByteArray?,
    transparency: ByteArray?,
    rgba: ByteArray,
    rgbaOffset: Int,
): Boolean {
    if (colourType == 6) {
        // Truecolour with alpha is already RGBA8: the row past its filter byte is the output.
        row.copyInto(rgba, rgbaOffset, 1, 1 + width * 4)
        return true
    }
    var out = rgbaOffset
    for (col in 0 until width) {
            val pixelStart = 1 + col * channels
            when (colourType) {
                0 -> {
                    val grey = row[pixelStart]
                    rgba[out] = grey
                    rgba[out + 1] = grey
                    rgba[out + 2] = grey
                    rgba[out + 3] = greyKeyAlpha(transparency, grey)
                }
                2 -> {
                    val r = row[pixelStart]
                    val g = row[pixelStart + 1]
                    val b = row[pixelStart + 2]
                    rgba[out] = r
                    rgba[out + 1] = g
                    rgba[out + 2] = b
                    rgba[out + 3] = rgbKeyAlpha(transparency, r, g, b)
                }
                3 -> {
                    val index = row[pixelStart].toInt() and 0xFF
                    val paletteBytes = requireNotNull(palette) { "colour type 3 requires a palette" }
                    val paletteOffset = index * 3
                    if (paletteOffset + 3 > paletteBytes.size) return false
                    rgba[out] = paletteBytes[paletteOffset]
                    rgba[out + 1] = paletteBytes[paletteOffset + 1]
                    rgba[out + 2] = paletteBytes[paletteOffset + 2]
                    rgba[out + 3] = paletteAlpha(transparency, index)
                }
                4 -> {
                    val grey = row[pixelStart]
                    rgba[out] = grey
                    rgba[out + 1] = grey
                    rgba[out + 2] = grey
                    rgba[out + 3] = row[pixelStart + 1]
                }
                else -> { // 6: truecolour + alpha, copied through unchanged.
                    rgba[out] = row[pixelStart]
                    rgba[out + 1] = row[pixelStart + 1]
                    rgba[out + 2] = row[pixelStart + 2]
                    rgba[out + 3] = row[pixelStart + 3]
                }
            }
            out += 4
    }
    return true
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

/** Must match GlTextureUpload's bounded premultiplication chunk. */
private const val MAXIMUM_PREMULTIPLY_UPLOAD_WORKSPACE_BYTES: Long = 1024L * 1024L
