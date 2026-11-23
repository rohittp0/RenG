package com.rohittp.reng.internal.image

/**
 * The outcome of decoding a candidate JPEG's bytes into one [DecodedImage]: the same four outcomes a
 * PNG decode has, so a caller can map both formats through one `when`.
 */
internal sealed interface JpegDecodeResult {
    data class Success(val image: DecodedImage) : JpegDecodeResult
    data class Malformed(val reason: JpegReject) : JpegDecodeResult
    data class Unsupported(val reason: JpegReject) : JpegDecodeResult
    data object TooLarge : JpegDecodeResult
}

/**
 * Every reason a JPEG decode can reject a file. Only [ARITHMETIC_CODING], [LOSSLESS], [HIERARCHICAL],
 * [SAMPLE_PRECISION], [COMPONENT_COUNT], [SAMPLING_FACTORS] and [HEIGHT_DEFINED_LATER] surface as
 * [JpegDecodeResult.Unsupported] -- each is a valid ITU T.81 file outside the subset below. Every
 * other reason surfaces as [JpegDecodeResult.Malformed].
 */
internal enum class JpegReject {
    /** The bytes do not begin with SOI (`FF D8`). */
    SIGNATURE,

    /** A marker segment's declared length runs past the end of the bytes, or is shorter than its own
     * fixed fields. */
    SEGMENT_LENGTH,

    /** A frame header (SOF) whose fields contradict the specification, or a second frame. */
    FRAME_HEADER,

    /** A DQT segment with an invalid precision or destination, or a zero quantiser. */
    QUANTIZATION_TABLE,

    /** A DHT segment with an invalid class or destination, too many codes for its lengths, or more
     * than 256 symbols. */
    HUFFMAN_TABLE,

    /** A scan header (SOS) naming a component the frame lacks, a spectral selection or successive
     * approximation the frame's process does not permit, or a scan before any frame. */
    SCAN_HEADER,

    /** A scan referring to a Huffman or quantization table no segment ever defined. */
    MISSING_TABLE,

    /** Entropy-coded data containing a code no table defines, or a coefficient past the block. */
    ENTROPY_DATA,

    /** A file with no frame, or a frame with no scan. */
    NO_IMAGE,

    /** SOF9-SOF15 and DAC: arithmetic entropy coding. */
    ARITHMETIC_CODING,

    /** SOF3: the lossless process. */
    LOSSLESS,

    /** SOF5-SOF7 and SOF8: hierarchical (differential) processes and the reserved JPG marker. */
    HIERARCHICAL,

    /** Any sample precision but 8 bits. */
    SAMPLE_PRECISION,

    /** Any component count but 1 (greyscale) and 3 (YCbCr, or RGB under an Adobe marker). CMYK and
     * YCCK are the cases this excludes in practice. */
    COMPONENT_COUNT,

    /** A component whose sampling factors do not divide the frame's maximum -- 3:2 sampling, say. */
    SAMPLING_FACTORS,

    /** A frame height of zero, deferring the real height to a DNL marker after the first scan. */
    HEIGHT_DEFINED_LATER,

    /** More scans than [MAXIMUM_SCANS]. Each scan revisits every block of its components, so an
     * unbounded count is unbounded work over a bounded raster. */
    SCAN_COUNT,

    /** Bytes between marker segments that are neither a marker nor fill, or the bytes ending
     * before EOI. A truncated texture is refused rather than drawn part grey. */
    TRUNCATED,

    /** A restart marker out of its `RST0`..`RST7` sequence, or a marker other than the expected
     * restart where one is due. */
    RESTART_SEQUENCE,

    /** A progressive scan that does not advance its coefficients' successive approximation (ITU T.81
     * G.1.1.1): a first scan of a coefficient already begun, a refinement of one not begun or at the
     * wrong bit, an AC scan before its component's DC, or a sequential component scanned twice. */
    SCAN_PROGRESSION,

    /** A component the image's scans never code -- its DC was never sent. */
    INCOMPLETE_IMAGE,

    /** A fault the checks above did not anticipate, caught at the boundary so no exception escapes. */
    UNEXPECTED,
}

/**
 * Exact final RGBA8 payload for a JPEG, from its frame header, without decoding anything. It walks the
 * markers with the decoder's own parser, so it reads the same frame header [decodeJpeg] would and every
 * marker sequence the decoder refuses before its frame makes this `null`. A caller must treat `null` as
 * a refusal, never as permission to decode unreserved.
 */
internal fun projectedJpegRgbaBytes(bytes: ByteArray): Long? = try {
    JpegDecoder(bytes, Long.MAX_VALUE, Long.MAX_VALUE).projectedRgbaBytes()
} catch (signal: JpegSignal) {
    null
} catch (unexpected: RuntimeException) {
    null
}

/**
 * Decodes a baseline, extended-sequential (Huffman) or progressive JPEG into one canonical RGBA8
 * [DecodedImage], opaque (alpha 255) and with its first row the image's top row, exactly as
 * [decodePng] orders rows. 8-bit samples, one or three components, any sampling factor from 1 to 4
 * that divides the frame's maximum, and restart intervals are admitted.
 *
 * Pure Kotlin on purpose, and in `commonMain`, like the PNG decoder: every target RenG builds
 * (Android, iOS, macOS, Linux) decodes with this one implementation, so a model renders the same
 * texels on every platform, and none needs a platform codec it might not have (Linux has none).
 *
 * The IDCT is the integer "islow" algorithm (stb_image's formulation of libjpeg's `jidctint`), the
 * colour conversion is libjpeg's 16-bit fixed-point BT.601 full-range transform, and chroma is
 * upsampled with libjpeg's "fancy" triangle filter for 2x factors, so the output stays within a few
 * levels of libjpeg's own. EXIF orientation is ignored: glTF addresses a texture by its stored rows,
 * not its display orientation.
 *
 * `maximumDecodedBytes` gates the declared raster (`width * height * 4`) and `maximumWorkingBytes` the
 * raster plus every coefficient and sample plane, both from the frame header alone, before any of
 * those arrays is allocated.
 */
internal fun decodeJpeg(
    bytes: ByteArray,
    maximumDecodedBytes: Long,
    maximumWorkingBytes: Long = Long.MAX_VALUE,
): JpegDecodeResult = try {
    decodeJpegUntrapped(bytes, maximumDecodedBytes, maximumWorkingBytes)
} catch (unexpected: RuntimeException) {
    // Defence in depth only: every fault the format can carry is validated above and signalled as a
    // typed result. Tests call [decodeJpegUntrapped] so a fault reaching here fails them instead.
    JpegDecodeResult.Malformed(JpegReject.UNEXPECTED)
}

/** [decodeJpeg] without its last-resort trap, so a test sees any exception the validation missed. */
internal fun decodeJpegUntrapped(
    bytes: ByteArray,
    maximumDecodedBytes: Long,
    maximumWorkingBytes: Long = Long.MAX_VALUE,
): JpegDecodeResult = try {
    JpegDecoder(bytes, maximumDecodedBytes, maximumWorkingBytes).decode()
} catch (signal: JpegSignal) {
    signal.result
}

/** Internal control flow, unwound by [decodeJpeg] into the [result] it carries. */
private class JpegSignal(val result: JpegDecodeResult) : RuntimeException()

private fun malformed(reason: JpegReject): Nothing = throw JpegSignal(JpegDecodeResult.Malformed(reason))

private fun unsupported(reason: JpegReject): Nothing = throw JpegSignal(JpegDecodeResult.Unsupported(reason))

private fun tooLarge(): Nothing = throw JpegSignal(JpegDecodeResult.TooLarge)

private fun readUInt16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)


/** Natural (row-major) index of each zig-zag position. */
private val ZIGZAG_TO_NATURAL = intArrayOf(
    0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
    12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
)

private const val BLOCK_SIZE = 64
private const val HUFFMAN_LOOKUP_BITS = 9
private const val MAXIMUM_SAMPLING_FACTOR = 4
private const val MAXIMUM_SUCCESSIVE_APPROXIMATION = 13

/** Scans per image. Real progressive files carry about ten (libjpeg's default script) to a few dozen
 * (optimised scripts); every scan walks all blocks of its components, so this caps decode work at a
 * constant multiple of the raster the budget already admitted. */
internal const val MAXIMUM_SCANS = 64

/** Entropy-coded bits a scan may consume past its real data (after a marker or the end of the bytes)
 * before it is refused as truncated. An encoder pads its last byte with ones, so a well-formed scan
 * consumes none; the allowance only tolerates a sloppy final pad. */
private const val MAXIMUM_SYNTHETIC_BITS = 16
private class HuffmanTable(counts: IntArray, private val symbols: IntArray) {
    /** For each [HUFFMAN_LOOKUP_BITS]-bit prefix: `(codeLength shl 8) or symbol`, or -1. */
    val lookup = IntArray(1 shl HUFFMAN_LOOKUP_BITS) { -1 }

    /** Per code length, one past the last code of that length (left-justified within the length). */
    val endCode = IntArray(17)

    /** Per code length, what to add to a code of that length to index [symbols]. */
    val symbolOffset = IntArray(17)

    init {
        // Validate the whole length table first (Kraft: no length may need more codes than remain),
        // so the lookup below can never be filled past its end by an oversubscribed table.
        var available = 1
        for (length in 1..16) {
            available = (available shl 1) - counts[length - 1]
            if (available < 0) malformed(JpegReject.HUFFMAN_TABLE)
        }
        if (counts.sum() != symbols.size) malformed(JpegReject.HUFFMAN_TABLE)
        var code = 0
        var index = 0
        for (length in 1..16) {
            symbolOffset[length] = index - code
            repeat(counts[length - 1]) {
                if (length <= HUFFMAN_LOOKUP_BITS) {
                    val shift = HUFFMAN_LOOKUP_BITS - length
                    val first = code shl shift
                    for (prefix in first until first + (1 shl shift)) {
                        lookup[prefix] = (length shl 8) or symbols[index]
                    }
                }
                code++
                index++
            }
            if (code > (1 shl length)) malformed(JpegReject.HUFFMAN_TABLE)
            endCode[length] = code
            code = code shl 1
        }
    }

    fun symbolAt(index: Int): Int = symbols[index]
}

private class FrameComponent(
    val id: Int,
    val horizontal: Int,
    val vertical: Int,
    val quantizationDestination: Int,
) {
    /** Blocks per line and column as stored: padded to whole MCUs of the frame. */
    var blocksPerLine = 0
    var blocksPerColumn = 0

    /** Blocks per line and column a non-interleaved scan of this component actually codes. */
    var codedBlocksPerLine = 0
    var codedBlocksPerColumn = 0

    /** Samples per line and column the component really carries, before MCU padding. */
    var sampleWidth = 0
    var sampleHeight = 0

    var coefficients: ShortArray? = null
    var quantization: IntArray? = null
    var samples: ByteArray? = null
    var dcPrediction = 0
    var dcTable: HuffmanTable? = null
    var acTable: HuffmanTable? = null

    /** Per zig-zag coefficient, the successive-approximation bit its last
     * scan left it at (`Al`), or -1 before any scan coded it. Sequential scans set every entry to 0. */
    val approximation = IntArray(BLOCK_SIZE) { -1 }
}

private class JpegDecoder(
    private val bytes: ByteArray,
    private val maximumDecodedBytes: Long,
    private val maximumWorkingBytes: Long,
) {
    private var offset = 0
    private val quantizationTables = arrayOfNulls<IntArray>(4)
    private val dcTables = arrayOfNulls<HuffmanTable>(4)
    private val acTables = arrayOfNulls<HuffmanTable>(4)
    private var restartInterval = 0
    private var adobeTransform: Int? = null

    private var progressive = false
    private var width = 0
    private var height = 0
    private var components: List<FrameComponent> = emptyList()
    private var maxHorizontal = 1
    private var maxVertical = 1
    private var mcusPerLine = 0
    private var mcusPerColumn = 0
    private var scans = 0
    private var frameParsed = false

    /** Parse only up to and including the frame header, allocating nothing: the projection. */
    private var projectionOnly = false

    // Entropy-coded segment state.
    private var bitBuffer = 0
    private var bitCount = 0
    private var markerReached = false
    private var endOfBandRun = 0

    /** Zero bits supplied past the real data since the last reset; see [MAXIMUM_SYNTHETIC_BITS]. */
    private var syntheticBits = 0
    private var nextRestartMarker = 0

    fun projectedRgbaBytes(): Long? {
        projectionOnly = true
        walkMarkers()
        return if (frameParsed) width.toLong() * height.toLong() * 4L else null
    }

    fun decode(): JpegDecodeResult {
        walkMarkers()
        if (!frameParsed || scans == 0) malformed(JpegReject.NO_IMAGE)
        if (components.any { it.approximation[0] < 0 }) malformed(JpegReject.INCOMPLETE_IMAGE)
        return JpegDecodeResult.Success(produceImage())
    }

    /**
     * The one marker parser both [decode] and [projectedRgbaBytes] run, so the two can never read a
     * different frame header. Returns at EOI, or, projecting, right after the frame header; bytes that
     * end before EOI are [JpegReject.TRUNCATED].
     */
    private fun walkMarkers() {
        if (bytes.size < 2 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
            malformed(JpegReject.SIGNATURE)
        }
        offset = 2
        while (true) {
            if (projectionOnly && frameParsed) return
            val marker = nextMarker() ?: malformed(JpegReject.TRUNCATED)
            when (marker) {
                0xD9 -> return
                0xC0, 0xC1 -> readFrame(isProgressive = false)
                0xC2 -> readFrame(isProgressive = true)
                0xC3 -> unsupported(JpegReject.LOSSLESS)
                0xC5, 0xC6, 0xC7, 0xC8 -> unsupported(JpegReject.HIERARCHICAL)
                0xC9, 0xCA, 0xCB, 0xCC, 0xCD, 0xCE, 0xCF -> unsupported(JpegReject.ARITHMETIC_CODING)
                0xC4 -> readHuffmanTables()
                0xDB -> readQuantizationTables()
                0xDD -> readRestartInterval()
                0xDA -> readScan()
                0xEE -> readAdobe()
                0x01, in 0xD0..0xD7 -> Unit // standalone markers outside a scan carry no payload
                else -> skipSegment()
            }
        }
    }

    // ---- marker segments ----

    /** The next marker's code, skipping `FF` fill bytes; null when the bytes end first. Anything
     * else between segments is not a JPEG. */
    private fun nextMarker(): Int? {
        while (offset + 1 < bytes.size) {
            if (byteAt(offset) != 0xFF) malformed(JpegReject.TRUNCATED)
            val code = byteAt(offset + 1)
            if (code == 0xFF) {
                offset++
                continue
            }
            if (code == 0x00) malformed(JpegReject.TRUNCATED)
            offset += 2
            return code
        }
        return null
    }

    /** Reads the length field at [offset] and returns the payload's bounds, leaving [offset] past it. */
    private fun segmentPayload(minimumLength: Int): IntRange {
        if (offset + 2 > bytes.size) malformed(JpegReject.SEGMENT_LENGTH)
        val length = readUInt16(bytes, offset)
        if (length < 2 + minimumLength || offset + length > bytes.size) malformed(JpegReject.SEGMENT_LENGTH)
        val start = offset + 2
        offset += length
        return start until offset
    }

    private fun skipSegment() {
        segmentPayload(minimumLength = 0)
    }

    private fun byteAt(index: Int): Int = bytes[index].toInt() and 0xFF

    private fun readAdobe() {
        val payload = segmentPayload(minimumLength = 0)
        val adobe = byteArrayOf(0x41, 0x64, 0x6F, 0x62, 0x65) // "Adobe"
        if (payload.size() >= 12 && (0 until 5).all { bytes[payload.first + it] == adobe[it] }) {
            adobeTransform = byteAt(payload.first + 11)
        }
    }

    private fun readRestartInterval() {
        val payload = segmentPayload(minimumLength = 2)
        restartInterval = readUInt16(bytes, payload.first)
    }

    private fun readQuantizationTables() {
        val payload = segmentPayload(minimumLength = 0)
        var index = payload.first
        while (index <= payload.last) {
            val precision = byteAt(index) ushr 4
            val destination = byteAt(index) and 0x0F
            index++
            if (precision > 1 || destination > 3) malformed(JpegReject.QUANTIZATION_TABLE)
            val entryBytes = if (precision == 0) 1 else 2
            if (index + BLOCK_SIZE * entryBytes > payload.last + 1) malformed(JpegReject.SEGMENT_LENGTH)
            val table = IntArray(BLOCK_SIZE)
            for (k in 0 until BLOCK_SIZE) {
                val value = if (precision == 0) byteAt(index) else readUInt16(bytes, index)
                if (value == 0) malformed(JpegReject.QUANTIZATION_TABLE)
                table[ZIGZAG_TO_NATURAL[k]] = value
                index += entryBytes
            }
            quantizationTables[destination] = table
        }
    }

    private fun readHuffmanTables() {
        val payload = segmentPayload(minimumLength = 0)
        var index = payload.first
        while (index <= payload.last) {
            if (index + 17 > payload.last + 1) malformed(JpegReject.SEGMENT_LENGTH)
            val tableClass = byteAt(index) ushr 4
            val destination = byteAt(index) and 0x0F
            if (tableClass > 1 || destination > 3) malformed(JpegReject.HUFFMAN_TABLE)
            val counts = IntArray(16) { byteAt(index + 1 + it) }
            val total = counts.sum()
            index += 17
            if (total > 256) malformed(JpegReject.HUFFMAN_TABLE)
            if (index + total > payload.last + 1) malformed(JpegReject.SEGMENT_LENGTH)
            val symbols = IntArray(total) { byteAt(index + it) }
            index += total
            val table = HuffmanTable(counts, symbols)
            if (tableClass == 0) dcTables[destination] = table else acTables[destination] = table
        }
    }

    private fun readFrame(isProgressive: Boolean) {
        if (frameParsed) malformed(JpegReject.FRAME_HEADER)
        val payload = segmentPayload(minimumLength = 6)
        val start = payload.first
        val precision = byteAt(start)
        height = readUInt16(bytes, start + 1)
        width = readUInt16(bytes, start + 3)
        val count = byteAt(start + 5)
        if (precision != 8) unsupported(JpegReject.SAMPLE_PRECISION)
        if (width == 0 || count == 0) malformed(JpegReject.FRAME_HEADER)
        if (payload.size() < 6 + 3 * count) malformed(JpegReject.SEGMENT_LENGTH)
        if (height == 0) unsupported(JpegReject.HEIGHT_DEFINED_LATER)
        if (count != 1 && count != 3) unsupported(JpegReject.COMPONENT_COUNT)

        val parsed = List(count) {
            val at = start + 6 + 3 * it
            val sampling = byteAt(at + 1)
            FrameComponent(
                id = byteAt(at),
                horizontal = sampling ushr 4,
                vertical = sampling and 0x0F,
                quantizationDestination = byteAt(at + 2),
            )
        }
        if (parsed.map { it.id }.toSet().size != count) malformed(JpegReject.FRAME_HEADER)
        for (component in parsed) {
            if (component.horizontal !in 1..MAXIMUM_SAMPLING_FACTOR ||
                component.vertical !in 1..MAXIMUM_SAMPLING_FACTOR ||
                component.quantizationDestination > 3
            ) {
                malformed(JpegReject.FRAME_HEADER)
            }
        }
        maxHorizontal = parsed.maxOf { it.horizontal }
        maxVertical = parsed.maxOf { it.vertical }
        for (component in parsed) {
            if (maxHorizontal % component.horizontal != 0 || maxVertical % component.vertical != 0) {
                unsupported(JpegReject.SAMPLING_FACTORS)
            }
        }

        components = parsed
        progressive = isProgressive
        frameParsed = true
        if (projectionOnly) return

        // The raster ceiling first, from the header alone, as the PNG decoder decides it.
        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount > maximumDecodedBytes / 4) tooLarge()

        mcusPerLine = ceilDiv(width, 8 * maxHorizontal)
        mcusPerColumn = ceilDiv(height, 8 * maxVertical)
        var workingBytes = pixelCount * 4L
        for (component in parsed) {
            component.blocksPerLine = mcusPerLine * component.horizontal
            component.blocksPerColumn = mcusPerColumn * component.vertical
            component.sampleWidth = ceilDiv(width * component.horizontal, maxHorizontal)
            component.sampleHeight = ceilDiv(height * component.vertical, maxVertical)
            component.codedBlocksPerLine = ceilDiv(component.sampleWidth, 8)
            component.codedBlocksPerColumn = ceilDiv(component.sampleHeight, 8)
            // Two bytes per coefficient plus one per output sample.
            workingBytes += component.blocksPerLine.toLong() * component.blocksPerColumn * BLOCK_SIZE * 3L
        }
        // One upsampled row per component, as Ints.
        workingBytes += count.toLong() * width * 4L
        if (workingBytes > maximumWorkingBytes) tooLarge()
        for (component in parsed) {
            val coefficientCount = component.blocksPerLine.toLong() * component.blocksPerColumn * BLOCK_SIZE
            if (coefficientCount > Int.MAX_VALUE) tooLarge()
            component.coefficients = ShortArray(coefficientCount.toInt())
        }
    }

    private fun readScan() {
        if (!frameParsed) malformed(JpegReject.SCAN_HEADER)
        if (scans >= MAXIMUM_SCANS) unsupported(JpegReject.SCAN_COUNT)
        val payload = segmentPayload(minimumLength = 1)
        val start = payload.first
        val count = byteAt(start)
        if (count !in 1..4 || payload.size() < 1 + 2 * count + 3) malformed(JpegReject.SEGMENT_LENGTH)
        val scanComponents = List(count) {
            val at = start + 1 + 2 * it
            val id = byteAt(at)
            val component = components.firstOrNull { c -> c.id == id } ?: malformed(JpegReject.SCAN_HEADER)
            val tables = byteAt(at + 1)
            val dc = tables ushr 4
            val ac = tables and 0x0F
            if (dc > 3 || ac > 3) malformed(JpegReject.SCAN_HEADER)
            component.dcTable = dcTables[dc]
            component.acTable = acTables[ac]
            component
        }
        if (scanComponents.toSet().size != count) malformed(JpegReject.SCAN_HEADER)
        val tail = start + 1 + 2 * count
        val spectralStart = byteAt(tail)
        val spectralEnd = byteAt(tail + 1)
        val approximationHigh = byteAt(tail + 2) ushr 4
        val approximationLow = byteAt(tail + 2) and 0x0F

        if (progressive) {
            val validBand = if (spectralStart == 0) spectralEnd == 0 else spectralEnd in spectralStart..63 && count == 1
            if (!validBand ||
                approximationHigh > MAXIMUM_SUCCESSIVE_APPROXIMATION ||
                approximationLow > MAXIMUM_SUCCESSIVE_APPROXIMATION ||
                (approximationHigh != 0 && approximationHigh != approximationLow + 1)
            ) {
                malformed(JpegReject.SCAN_HEADER)
            }
        } else if (spectralStart != 0 || spectralEnd != 63 || approximationHigh != 0 || approximationLow != 0) {
            malformed(JpegReject.SCAN_HEADER)
        }

        for (component in scanComponents) {
            if (component.quantization == null) {
                val table = quantizationTables[component.quantizationDestination] ?: malformed(JpegReject.MISSING_TABLE)
                component.quantization = table.copyOf()
            }
            val needsDc = spectralStart == 0 && approximationHigh == 0
            val needsAc = spectralStart != 0 || !progressive
            if (needsDc && component.dcTable == null) malformed(JpegReject.MISSING_TABLE)
            if (needsAc && component.acTable == null) malformed(JpegReject.MISSING_TABLE)
        }

        for (component in scanComponents) {
            advanceApproximation(component, spectralStart, spectralEnd, approximationHigh, approximationLow)
        }
        decodeScan(scanComponents, spectralStart, spectralEnd, approximationHigh, approximationLow)
        scans++
    }

    /**
     * ITU T.81 G.1.1.1's progression, per component and coefficient: a first scan (`Ah` 0) only of
     * coefficients no scan has coded, a refinement only at the bit the previous scan stopped at
     * (`Ah` equal to its `Al`), and an AC band only after the component's DC. A sequential component
     * is coded exactly once. Every accepted scan therefore lowers some coefficient's bit for good,
     * which together with [MAXIMUM_SCANS] bounds the work a file can demand.
     */
    private fun advanceApproximation(component: FrameComponent, start: Int, end: Int, high: Int, low: Int) {
        val state = component.approximation
        if (!progressive) {
            if (state[0] >= 0) malformed(JpegReject.SCAN_PROGRESSION)
            state.fill(0)
            return
        }
        if (start > 0 && state[0] < 0) malformed(JpegReject.SCAN_PROGRESSION)
        for (k in start..end) {
            val expected = if (high == 0) -1 else high
            if (state[k] != expected) malformed(JpegReject.SCAN_PROGRESSION)
        }
        for (k in start..end) state[k] = low
    }

    // ---- entropy-coded data ----

    private fun decodeScan(
        scanComponents: List<FrameComponent>,
        spectralStart: Int,
        spectralEnd: Int,
        approximationHigh: Int,
        approximationLow: Int,
    ) {
        resetEntropyState(scanComponents)
        nextRestartMarker = 0
        val block: (FrameComponent, Int) -> Unit = when {
            !progressive -> { component, base -> decodeBaselineBlock(component, base) }
            spectralStart == 0 && approximationHigh == 0 -> { component, base ->
                decodeDcFirst(component, base, approximationLow)
            }
            spectralStart == 0 -> { component, base -> decodeDcRefine(component, base, approximationLow) }
            approximationHigh == 0 -> { component, base ->
                decodeAcFirst(component, base, spectralStart, spectralEnd, approximationLow)
            }
            else -> { component, base ->
                decodeAcRefine(component, base, spectralStart, spectralEnd, approximationLow)
            }
        }

        var restartsToGo = restartInterval
        if (scanComponents.size == 1) {
            val component = scanComponents[0]
            val total = component.codedBlocksPerLine * component.codedBlocksPerColumn
            var unit = 0
            for (row in 0 until component.codedBlocksPerColumn) {
                for (column in 0 until component.codedBlocksPerLine) {
                    block(component, (row * component.blocksPerLine + column) * BLOCK_SIZE)
                    unit++
                    if (restartInterval > 0 && --restartsToGo == 0 && unit < total) {
                        restart(scanComponents)
                        restartsToGo = restartInterval
                    }
                }
            }
        } else {
            val total = mcusPerLine * mcusPerColumn
            var unit = 0
            for (mcuRow in 0 until mcusPerColumn) {
                for (mcuColumn in 0 until mcusPerLine) {
                    for (component in scanComponents) {
                        for (v in 0 until component.vertical) {
                            for (h in 0 until component.horizontal) {
                                val row = mcuRow * component.vertical + v
                                val column = mcuColumn * component.horizontal + h
                                block(component, (row * component.blocksPerLine + column) * BLOCK_SIZE)
                            }
                        }
                    }
                    unit++
                    if (restartInterval > 0 && --restartsToGo == 0 && unit < total) {
                        restart(scanComponents)
                        restartsToGo = restartInterval
                    }
                }
            }
        }
        // Leave [offset] at the marker that ends the entropy-coded segment, so the marker loop resumes
        // there; any byte between is padding.
        findSegmentEnd()
    }

    private fun resetEntropyState(scanComponents: List<FrameComponent>) {
        bitBuffer = 0
        bitCount = 0
        syntheticBits = 0
        markerReached = false
        endOfBandRun = 0
        for (component in scanComponents) component.dcPrediction = 0
    }

    /**
     * Discards the bits of the interval just finished and steps past the `RSTn` that must follow it,
     * `n` counting 0..7 from the scan's start. The reader never reads past a marker and fetches the
     * padded last byte of an interval with its last code, so the marker must be right here; anything
     * else -- leftover data, another marker, the wrong `n` -- is damage, and refused.
     */
    private fun restart(scanComponents: List<FrameComponent>) {
        resetEntropyState(scanComponents)
        while (offset + 1 < bytes.size && byteAt(offset) == 0xFF && byteAt(offset + 1) == 0xFF) offset++
        if (offset + 1 >= bytes.size || byteAt(offset) != 0xFF || byteAt(offset + 1) != 0xD0 + nextRestartMarker) {
            malformed(JpegReject.RESTART_SEQUENCE)
        }
        offset += 2
        nextRestartMarker = (nextRestartMarker + 1) and 7
    }

    /** Moves [offset] to the first marker that is not part of the entropy-coded data. */
    private fun findSegmentEnd() {
        while (offset + 1 < bytes.size) {
            if (byteAt(offset) != 0xFF) {
                offset++
                continue
            }
            val code = byteAt(offset + 1)
            if (code == 0x00 || code == 0xFF || code in 0xD0..0xD7) {
                offset += if (code == 0xFF) 1 else 2
                continue
            }
            return
        }
        offset = bytes.size
    }

    /** One more byte of entropy-coded data, with `FF 00` unstuffed. A marker, or the end of the
     * bytes, yields zeros from then on, as libjpeg does with a truncated scan. */
    private fun nextEntropyByte(): Int {
        if (markerReached || offset >= bytes.size) {
            syntheticBits += 8
            return 0
        }
        val value = byteAt(offset)
        if (value != 0xFF) {
            offset++
            return value
        }
        if (offset + 1 < bytes.size && byteAt(offset + 1) == 0x00) {
            offset += 2
            return 0xFF
        }
        markerReached = true
        syntheticBits += 8
        return 0
    }

    /** Synthetic bits sit at the tail of everything fetched, so those already consumed are the ones
     * no longer in the buffer. */
    private fun checkSyntheticBits() {
        if (syntheticBits - bitCount > MAXIMUM_SYNTHETIC_BITS) malformed(JpegReject.TRUNCATED)
    }

    private fun fillBits() {
        while (bitCount <= 24) {
            bitBuffer = bitBuffer or (nextEntropyByte() shl (24 - bitCount))
            bitCount += 8
        }
    }

    /** The next [count] bits, 1..16, most significant first. */
    private fun readBits(count: Int): Int {
        fillBits()
        val value = bitBuffer ushr (32 - count)
        bitBuffer = bitBuffer shl count
        bitCount -= count
        checkSyntheticBits()
        return value
    }

    private fun readBit(): Int = readBits(1)

    private fun decodeSymbol(table: HuffmanTable): Int {
        fillBits()
        val fast = table.lookup[bitBuffer ushr (32 - HUFFMAN_LOOKUP_BITS)]
        if (fast >= 0) {
            val length = fast ushr 8
            bitBuffer = bitBuffer shl length
            bitCount -= length
            checkSyntheticBits()
            return fast and 0xFF
        }
        for (length in HUFFMAN_LOOKUP_BITS + 1..16) {
            val code = bitBuffer ushr (32 - length)
            if (code < table.endCode[length]) {
                bitBuffer = bitBuffer shl length
                bitCount -= length
                checkSyntheticBits()
                val index = code + table.symbolOffset[length]
                return table.symbolAt(index)
            }
        }
        malformed(JpegReject.ENTROPY_DATA)
    }

    /** ITU T.81's EXTEND of a [size]-bit magnitude category. */
    private fun receiveExtend(size: Int): Int {
        if (size == 0) return 0
        if (size > 16) malformed(JpegReject.ENTROPY_DATA)
        val value = readBits(size)
        return if (value < (1 shl (size - 1))) value - (1 shl size) + 1 else value
    }

    private fun decodeBaselineBlock(component: FrameComponent, base: Int) {
        val coefficients = component.coefficients!!
        val dcSize = decodeSymbol(component.dcTable!!)
        component.dcPrediction += receiveExtend(dcSize)
        coefficients[base] = component.dcPrediction.toShort()
        val acTable = component.acTable!!
        var k = 1
        while (k < BLOCK_SIZE) {
            val symbol = decodeSymbol(acTable)
            val run = symbol ushr 4
            val size = symbol and 0x0F
            if (size == 0) {
                if (run != 15) break
                k += 16
                continue
            }
            k += run
            if (k >= BLOCK_SIZE) malformed(JpegReject.ENTROPY_DATA)
            coefficients[base + ZIGZAG_TO_NATURAL[k]] = receiveExtend(size).toShort()
            k++
        }
    }

    private fun decodeDcFirst(component: FrameComponent, base: Int, approximationLow: Int) {
        val size = decodeSymbol(component.dcTable!!)
        component.dcPrediction += receiveExtend(size)
        component.coefficients!![base] = (component.dcPrediction shl approximationLow).toShort()
    }

    private fun decodeDcRefine(component: FrameComponent, base: Int, approximationLow: Int) {
        if (readBit() != 0) {
            val coefficients = component.coefficients!!
            coefficients[base] = (coefficients[base].toInt() or (1 shl approximationLow)).toShort()
        }
    }

    private fun decodeAcFirst(component: FrameComponent, base: Int, start: Int, end: Int, approximationLow: Int) {
        if (endOfBandRun > 0) {
            endOfBandRun--
            return
        }
        val coefficients = component.coefficients!!
        val table = component.acTable!!
        var k = start
        while (k <= end) {
            val symbol = decodeSymbol(table)
            val run = symbol ushr 4
            val size = symbol and 0x0F
            if (size == 0) {
                if (run < 15) {
                    endOfBandRun = (1 shl run) - 1
                    if (run > 0) endOfBandRun += readBits(run)
                    break
                }
                k += 16
                continue
            }
            k += run
            if (k > end) malformed(JpegReject.ENTROPY_DATA)
            coefficients[base + ZIGZAG_TO_NATURAL[k]] = (receiveExtend(size) * (1 shl approximationLow)).toShort()
            k++
        }
    }

    private fun decodeAcRefine(component: FrameComponent, base: Int, start: Int, end: Int, approximationLow: Int) {
        val coefficients = component.coefficients!!
        val bit = 1 shl approximationLow
        fun refine(index: Int) {
            val current = coefficients[index].toInt()
            if (readBit() != 0 && current and bit == 0) {
                coefficients[index] = (if (current > 0) current + bit else current - bit).toShort()
            }
        }
        var k = start
        if (endOfBandRun > 0) {
            endOfBandRun--
            while (k <= end) {
                val index = base + ZIGZAG_TO_NATURAL[k]
                if (coefficients[index].toInt() != 0) refine(index)
                k++
            }
            return
        }
        val table = component.acTable!!
        while (k <= end) {
            val symbol = decodeSymbol(table)
            var run = symbol ushr 4
            val size = symbol and 0x0F
            var value = 0
            if (size == 0) {
                if (run < 15) {
                    endOfBandRun = (1 shl run) - 1
                    if (run > 0) endOfBandRun += readBits(run)
                    // Refine every remaining non-zero coefficient of this block; no new one appears.
                    run = BLOCK_SIZE
                }
            } else {
                if (size != 1) malformed(JpegReject.ENTROPY_DATA)
                value = if (readBit() != 0) bit else -bit
            }
            while (k <= end) {
                val index = base + ZIGZAG_TO_NATURAL[k++]
                if (coefficients[index].toInt() != 0) {
                    refine(index)
                } else {
                    if (run == 0) {
                        if (value != 0) coefficients[index] = value.toShort()
                        break
                    }
                    run--
                }
            }
        }
    }

    // ---- reconstruction ----

    private fun produceImage(): DecodedImage {
        val workspace = IntArray(BLOCK_SIZE)
        val rowScratch = IntArray(8)
        for (component in components) {
            val coefficients = component.coefficients!!
            val quantization = component.quantization ?: IntArray(BLOCK_SIZE) { 1 }
            val stride = component.blocksPerLine * 8
            val samples = ByteArray(stride * component.blocksPerColumn * 8)
            for (row in 0 until component.blocksPerColumn) {
                for (column in 0 until component.blocksPerLine) {
                    val base = (row * component.blocksPerLine + column) * BLOCK_SIZE
                    for (i in 0 until BLOCK_SIZE) workspace[i] = coefficients[base + i] * quantization[i]
                    inverseDctBlock(workspace, rowScratch, samples, row * 8 * stride + column * 8, stride)
                }
            }
            component.samples = samples
            component.coefficients = null
        }

        val rgba = ByteArray(width * height * 4)
        val rows = Array(components.size) { IntArray(width) }
        val ids = components.map { it.id }
        val rgbIds = listOf('R'.code, 'G'.code, 'B'.code)
        val rgb = components.size == 3 && (adobeTransform == 0 || ids.toSet() == rgbIds.toSet())
        val ordered = colourOrder(ids, rgb, rgbIds)
        for (y in 0 until height) {
            for (index in ordered.indices) upsampleRow(ordered[index], y, rows[index])
            var out = y * width * 4
            if (components.size == 1) {
                val grey = rows[0]
                for (x in 0 until width) {
                    val value = grey[x].toByte()
                    rgba[out] = value
                    rgba[out + 1] = value
                    rgba[out + 2] = value
                    rgba[out + 3] = -1
                    out += 4
                }
            } else if (rgb) {
                for (x in 0 until width) {
                    rgba[out] = rows[0][x].toByte()
                    rgba[out + 1] = rows[1][x].toByte()
                    rgba[out + 2] = rows[2][x].toByte()
                    rgba[out + 3] = -1
                    out += 4
                }
            } else {
                val luma = rows[0]
                val blue = rows[1]
                val red = rows[2]
                for (x in 0 until width) {
                    val yValue = luma[x]
                    val cb = blue[x] - 128
                    val cr = red[x] - 128
                    rgba[out] = clampToByte(yValue + ((CR_TO_R * cr + ONE_HALF) shr SCALE_BITS))
                    rgba[out + 1] = clampToByte(yValue + ((CB_TO_G * cb + CR_TO_G * cr + ONE_HALF) shr SCALE_BITS))
                    rgba[out + 2] = clampToByte(yValue + ((CB_TO_B * cb + ONE_HALF) shr SCALE_BITS))
                    rgba[out + 3] = -1
                    out += 4
                }
            }
        }
        for (component in components) component.samples = null
        return DecodedImage.takeOwnership(width, height, rgba)
    }

    /**
     * The components in colour order -- Y, Cb, Cr or R, G, B -- by their ids where those ids say so:
     * JFIF's 1, 2, 3, the common 0, 1, 2, and `R`, `G`, `B`, in whatever order the frame lists them.
     * Any other ids carry no colour meaning, and the frame's order stands, as libjpeg reads it.
     */
    private fun colourOrder(ids: List<Int>, rgb: Boolean, rgbIds: List<Int>): List<FrameComponent> {
        if (components.size != 3) return components
        val byId = components.associateBy { it.id }
        val named = when {
            rgb && ids.toSet() == rgbIds.toSet() -> rgbIds
            ids.toSet() == setOf(1, 2, 3) -> listOf(1, 2, 3)
            ids.toSet() == setOf(0, 1, 2) -> listOf(0, 1, 2)
            else -> return components
        }
        return named.map { byId.getValue(it) }
    }

    /**
     * One full-resolution row of [component] for output row [y]. A 2x factor in either direction uses
     * libjpeg's triangle ("fancy") filter -- 3/4 of the nearer sample, 1/4 of the farther -- and any
     * other factor replicates the nearest sample.
     */
    private fun upsampleRow(component: FrameComponent, y: Int, out: IntArray) {
        val samples = component.samples!!
        val stride = component.blocksPerLine * 8
        val horizontalFactor = maxHorizontal / component.horizontal
        val verticalFactor = maxVertical / component.vertical
        // libjpeg filters only the 2x1, 2x2 and 1x2 cases; every other factor replicates, in both
        // directions, so a 4x2 component does not mix a triangle filter with replication.
        val fancy = (horizontalFactor == 2 && verticalFactor <= 2) || (horizontalFactor == 1 && verticalFactor == 2)
        val verticalTriangle = fancy && verticalFactor == 2
        val horizontalTriangle = fancy && horizontalFactor == 2
        val lastColumn = component.sampleWidth - 1
        val lastRow = component.sampleHeight - 1

        val near: Int
        val far: Int
        if (verticalTriangle) {
            near = minOf(y shr 1, lastRow)
            far = (if (y and 1 == 0) near - 1 else near + 1).coerceIn(0, lastRow)
        } else {
            near = minOf(y / verticalFactor, lastRow)
            far = near
        }
        val nearBase = near * stride
        val farBase = far * stride
        // Column sums scaled by 4, so both directions share one rounding at the end.
        fun columnSum(column: Int): Int {
            val nearSample = samples[nearBase + column].toInt() and 0xFF
            return if (verticalTriangle) {
                3 * nearSample + (samples[farBase + column].toInt() and 0xFF)
            } else {
                4 * nearSample
            }
        }

        if (horizontalTriangle) {
            // libjpeg's rounding biases: h2v2 alternates 8 and 7 out of 16, h2v1 1 and 2 out of 4.
            val leftBias = if (verticalTriangle) 8 else 4
            val rightBias = if (verticalTriangle) 7 else 8
            var x = 0
            var column = 0
            while (x < width) {
                val here = columnSum(column)
                val previous = if (column == 0) here else columnSum(column - 1)
                val next = if (column >= lastColumn) here else columnSum(column + 1)
                out[x] = (3 * here + previous + leftBias) shr 4
                if (x + 1 < width) out[x + 1] = (3 * here + next + rightBias) shr 4
                x += 2
                column++
            }
        } else {
            for (x in 0 until width) out[x] = (columnSum(minOf(x / horizontalFactor, lastColumn)) + 2) shr 2
        }
    }
}

private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

private fun clampToByte(value: Int): Byte = when {
    value < 0 -> 0
    value > 255 -> -1
    else -> value.toByte()
}

// libjpeg's jdcolor.c constants: FIX(x) = x * 2^16 rounded.
private const val SCALE_BITS = 16
private const val ONE_HALF = 1 shl (SCALE_BITS - 1)
private const val CR_TO_R = 91881 // 1.40200
private const val CB_TO_B = 116130 // 1.77200
private const val CR_TO_G = -46802 // -0.71414
private const val CB_TO_G = -22554 // -0.34414

// The islow IDCT's constants: (int)(x * 4096 + 0.5), as stb_image's stbi__f2f computes them.
private const val C0_541196100 = 2217
private const val C_1_847759065 = -7567
private const val C0_765366865 = 3135
private const val C1_175875602 = 4816
private const val C0_298631336 = 1223
private const val C2_053119869 = 8410
private const val C3_072711026 = 12586
private const val C1_501321110 = 6149
private const val C_0_899976223 = -3685
private const val C_2_562915447 = -10497
private const val C_1_961570560 = -8034
private const val C_0_390180644 = -1597

/**
 * Integer 8x8 inverse DCT of dequantized natural-order [input] (which it overwrites with column
 * results), writing level-shifted, clamped samples into [output] at [outputOffset] with [stride].
 * [row] is an eight-entry scratch array.
 */
private fun inverseDctBlock(input: IntArray, row: IntArray, output: ByteArray, outputOffset: Int, stride: Int) {
    // Columns, scaled up by 2^12 and brought back down keeping two extra bits of precision.
    for (column in 0 until 8) {
        val s0 = input[column]
        if (input[8 + column] == 0 && input[16 + column] == 0 && input[24 + column] == 0 &&
            input[32 + column] == 0 && input[40 + column] == 0 && input[48 + column] == 0 &&
            input[56 + column] == 0
        ) {
            val dc = s0 * 4
            for (r in 0 until 8) input[r * 8 + column] = dc
            continue
        }
        idct1d(
            s0, input[8 + column], input[16 + column], input[24 + column],
            input[32 + column], input[40 + column], input[48 + column], input[56 + column],
            bias = 512, shift = 10, target = input, first = column, step = 8,
        )
    }
    // Rows: remove 2^12 * 2^2 * 2^3 = 2^17 with rounding, and level-shift by 128.
    for (r in 0 until 8) {
        val base = r * 8
        idct1d(
            input[base], input[base + 1], input[base + 2], input[base + 3],
            input[base + 4], input[base + 5], input[base + 6], input[base + 7],
            bias = 65536 + (128 shl 17), shift = 17, target = row, first = 0, step = 1,
        )
        val out = outputOffset + r * stride
        for (i in 0 until 8) output[out + i] = clampToByte(row[i])
    }
}

/** The 1-D islow pass, written into [target] from [first] every [step] entries. */
@Suppress("LongParameterList")
private fun idct1d(
    s0: Int, s1: Int, s2: Int, s3: Int, s4: Int, s5: Int, s6: Int, s7: Int,
    bias: Int, shift: Int, target: IntArray, first: Int, step: Int,
) {
    var p2 = s2
    var p3 = s6
    var p1 = (p2 + p3) * C0_541196100
    var t2 = p1 + p3 * C_1_847759065
    var t3 = p1 + p2 * C0_765366865
    p2 = s0
    p3 = s4
    var t0 = (p2 + p3) * 4096
    var t1 = (p2 - p3) * 4096
    val x0 = t0 + t3 + bias
    val x3 = t0 - t3 + bias
    val x1 = t1 + t2 + bias
    val x2 = t1 - t2 + bias
    t0 = s7
    t1 = s5
    t2 = s3
    t3 = s1
    p3 = t0 + t2
    var p4 = t1 + t3
    p1 = t0 + t3
    p2 = t1 + t2
    val p5 = (p3 + p4) * C1_175875602
    t0 *= C0_298631336
    t1 *= C2_053119869
    t2 *= C3_072711026
    t3 *= C1_501321110
    p1 = p5 + p1 * C_0_899976223
    p2 = p5 + p2 * C_2_562915447
    p3 *= C_1_961570560
    p4 *= C_0_390180644
    t3 += p1 + p4
    t2 += p2 + p3
    t1 += p2 + p4
    t0 += p1 + p3
    target[first] = (x0 + t3) shr shift
    target[first + 7 * step] = (x0 - t3) shr shift
    target[first + step] = (x1 + t2) shr shift
    target[first + 6 * step] = (x1 - t2) shr shift
    target[first + 2 * step] = (x2 + t1) shr shift
    target[first + 5 * step] = (x2 - t1) shr shift
    target[first + 3 * step] = (x3 + t0) shr shift
    target[first + 4 * step] = (x3 - t0) shr shift
}

private fun IntRange.size(): Int = last - first + 1
