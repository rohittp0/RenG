package com.rohittp.reng.internal.image

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Every fixture is a real JPEG produced by libjpeg-turbo 3.2.0's `cjpeg`, and every reference is
 * libjpeg-turbo's own `djpeg` decode of it (default islow IDCT, fancy upsampling) -- the same
 * anti-circularity rule `PngDecoderTest` states: the expectation must not come from the code under
 * test. The source picture is 19x13, so no dimension is a whole number of 16-pixel MCUs and every
 * edge path (partial blocks, chroma clamped at the last column and row) runs. Regenerate with:
 *
 *     python3 - <<'PY'
 *     import subprocess
 *     W, H = 19, 13
 *     px = bytearray()
 *     for y in range(H):
 *         for x in range(W):
 *             px += bytes([int(255 * x / (W - 1)), int(255 * y / (H - 1)),
 *                          200 if (x // 5 + y // 4) % 2 else 40])
 *     open("src.ppm", "wb").write(b"P6\n%d %d\n255\n" % (W, H) + px)
 *     grey = bytes(int(0.3 * px[3 * i] + 0.59 * px[3 * i + 1] + 0.11 * px[3 * i + 2]) for i in range(W * H))
 *     open("src.pgm", "wb").write(b"P5\n%d %d\n255\n" % (W, H) + grey)
 *     for name, args in {
 *         "baseline420": ["-sample", "2x2"], "baseline422": ["-sample", "2x1"],
 *         "baseline444": ["-sample", "1x1"], "progressive420": ["-sample", "2x2", "-progressive"],
 *         "restart420": ["-sample", "2x2", "-restart", "1B"],
 *         "progressiveRestart": ["-sample", "2x2", "-progressive", "-restart", "1B"],
 *     }.items():
 *         subprocess.check_call(["cjpeg", "-quality", "90", *args, "-outfile", name + ".jpg", "src.ppm"])
 *     subprocess.check_call(["cjpeg", "-quality", "90", "-grayscale", "-outfile", "grey.jpg", "src.pgm"])
 *  *     for sampling in ["1x2", "4x1", "4x2", "2x4", "1x1,2x2,1x1", "2x2,1x2,2x1"]:
 *         subprocess.check_call(["cjpeg", "-quality", "90", "-sample", sampling, "-outfile", ..., "src.ppm"])
 *     # then, per file: djpeg -pnm -outfile <name>.ref <name>.jpg, keeping the pixels after the header
 *     PY
 *
 * [TWO_PIXEL_JPEG] is `cjpeg -quality 100 -sample 1x1 -optimize` of a 2x1 picture `(16, 32, 48)`,
 * `(192, 128, 64)`, which `djpeg` decodes exactly back to those bytes.
 */
class JpegDecoderTest {
    @Test
    fun everyAdmittedProcessDecodesWithinTwoLevelsOfLibjpeg() {
        // Two levels is the measured worst case of an integer IDCT that is libjpeg's algorithm but not
        // its exact rounding; a wrong zig-zag, a wrong upsampler or a dropped refinement scan moves
        // whole tens of levels. The decoder is integer-only, so this bound holds on every target.
        val fixtures = listOf(
            "baseline 4:2:0" to (BASELINE_420_JPEG to BASELINE_420_REFERENCE),
            "baseline 4:2:2" to (BASELINE_422_JPEG to BASELINE_422_REFERENCE),
            "baseline 4:4:4" to (BASELINE_444_JPEG to BASELINE_444_REFERENCE),
            "progressive 4:2:0" to (PROGRESSIVE_420_JPEG to PROGRESSIVE_420_REFERENCE),
            "restart interval" to (RESTART_420_JPEG to RESTART_420_REFERENCE),
            "progressive with restarts" to (PROGRESSIVE_RESTART_JPEG to PROGRESSIVE_RESTART_REFERENCE),
            "greyscale" to (GREY_JPEG to GREY_REFERENCE),
            "sampling 1x2" to (SAMPLED_1X2_JPEG to SAMPLED_1X2_REFERENCE),
            "sampling 4x1" to (SAMPLED_4X1_JPEG to SAMPLED_4X1_REFERENCE),
            "sampling 4x2" to (SAMPLED_4X2_JPEG to SAMPLED_4X2_REFERENCE),
            "sampling 2x4" to (SAMPLED_2X4_JPEG to SAMPLED_2X4_REFERENCE),
            "chroma finer than luma" to (SAMPLED_CHROMA_FINER_JPEG to SAMPLED_CHROMA_FINER_REFERENCE),
            "mixed chroma sampling" to (SAMPLED_MIXED_JPEG to SAMPLED_MIXED_REFERENCE),
        )
        for ((name, fixture) in fixtures) {
            val (jpeg, reference) = fixture
            val image = decoded(jpeg)
            assertEquals(WIDTH, image.width, name)
            assertEquals(HEIGHT, image.height, name)
            val channels = reference.size / (WIDTH * HEIGHT)
            val rgba = image.rgbaSnapshot()
            var worst = 0
            for (pixel in 0 until WIDTH * HEIGHT) {
                for (channel in 0 until 3) {
                    val actual = rgba[pixel * 4 + channel].toInt() and 0xFF
                    val expected = reference[pixel * channels + if (channels == 1) 0 else channel].toInt() and 0xFF
                    worst = maxOf(worst, abs(actual - expected))
                }
                assertEquals(-1, rgba[pixel * 4 + 3].toInt(), "$name: a JPEG has no alpha, so every texel is opaque")
            }
            assertTrue(worst <= 2, "$name: worst channel differs from libjpeg by $worst levels")
        }
    }

    @Test
    fun aLosslessQualityFileDecodesToItsExactPixels() {
        val image = decoded(TWO_PIXEL_JPEG)

        assertEquals(2, image.width)
        assertEquals(1, image.height)
        assertEquals(
            listOf<Byte>(0x10, 0x20, 0x30, -1, -64, -128, 0x40, -1),
            image.rgbaSnapshot().toList(),
        )
    }

    @Test
    fun theProjectionIsTheFrameHeadersRasterWithoutDecoding() {
        assertEquals(WIDTH * HEIGHT * 4L, projectedJpegRgbaBytes(BASELINE_420_JPEG))
        assertEquals(WIDTH * HEIGHT * 4L, projectedJpegRgbaBytes(PROGRESSIVE_420_JPEG))
        assertNull(projectedJpegRgbaBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte())))
    }

    @Test
    fun aRasterOverEitherBudgetIsTooLarge() {
        val raster = WIDTH * HEIGHT * 4L
        assertIs<JpegDecodeResult.TooLarge>(decodeJpeg(BASELINE_420_JPEG, maximumDecodedBytes = raster - 1))
        assertIs<JpegDecodeResult.Success>(decodeJpeg(BASELINE_420_JPEG, maximumDecodedBytes = raster))
        // The working figure adds every coefficient and sample plane to the raster.
        assertIs<JpegDecodeResult.TooLarge>(
            decodeJpeg(BASELINE_420_JPEG, maximumDecodedBytes = raster, maximumWorkingBytes = raster),
        )
    }

    @Test
    fun aValidProcessOutsideTheSubsetIsUnsupportedNotMalformed() {
        assertEquals(JpegReject.LOSSLESS, unsupportedReason(withFrameMarker(0xC3)))
        assertEquals(JpegReject.HIERARCHICAL, unsupportedReason(withFrameMarker(0xC5)))
        assertEquals(JpegReject.ARITHMETIC_CODING, unsupportedReason(withFrameMarker(0xC9)))
        val twelveBit = BASELINE_420_JPEG.copyOf().also { it[frameMarkerOffset() + 4] = 12 }
        assertEquals(JpegReject.SAMPLE_PRECISION, unsupportedReason(twelveBit))
        // A file whose only frame is unsupported projects nothing, so a model refuses it unreserved.
        assertNull(projectedJpegRgbaBytes(withFrameMarker(0xC3)))
    }

    @Test
    fun bytesThatAreNotAJpegAreMalformed() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        assertEquals(JpegReject.SIGNATURE, assertIs<JpegDecodeResult.Malformed>(decodeJpeg(png, Long.MAX_VALUE)).reason)
        assertEquals(
            JpegReject.SIGNATURE,
            assertIs<JpegDecodeResult.Malformed>(decodeJpeg(ByteArray(0), Long.MAX_VALUE)).reason,
        )
        // Headers alone, cut before the first scan: a frame with nothing coded.
        val headersOnly = BASELINE_420_JPEG.copyOf(frameMarkerOffset() + 19)
        assertIs<JpegDecodeResult.Malformed>(decodeJpeg(headersOnly, Long.MAX_VALUE))
    }

    @Test
    fun noTruncationDecodesAndNoCorruptedByteEscapesAsAnException() {
        // A model's texture is untrusted bytes: every outcome must be one of the four typed results,
        // checked without decodeJpeg's last-resort trap so a missed validation fails here. And a
        // truncated texture is refused rather than drawn part grey.
        for (fixture in listOf(BASELINE_420_JPEG, PROGRESSIVE_RESTART_JPEG, RESTART_420_JPEG)) {
            for (length in 0 until fixture.size) {
                val result = decodeJpegUntrapped(fixture.copyOf(length), Long.MAX_VALUE)
                assertFalse(result is JpegDecodeResult.Success, "a ${fixture.size}-byte file cut to $length decoded")
            }
            for (index in fixture.indices) {
                for (mask in intArrayOf(0x01, 0x10, 0xFF)) {
                    val corrupted = fixture.copyOf()
                    corrupted[index] = (corrupted[index].toInt() xor mask).toByte()
                    decodeJpegUntrapped(corrupted, maximumDecodedBytes = 1L shl 24, maximumWorkingBytes = 1L shl 26)
                    projectedJpegRgbaBytes(corrupted)
                }
            }
        }
    }

    @Test
    fun anOversubscribedHuffmanTableIsMalformedBeforeAnyTableIsBuilt() {
        // Three one-bit codes: the bytes that once wrote past the 512-entry lookup.
        val threeOneBitCodes = hex("ffd8 ffc4 0016 00 03 000000000000000000000000000000 000102")
        assertEquals(JpegReject.HUFFMAN_TABLE, malformedReason(threeOneBitCodes))
        // Five two-bit codes after none of one bit: oversubscribed below the lookup's width too.
        val fiveTwoBitCodes = hex("ffd8 ffc4 0018 00 0005 0000000000000000000000000000 0001020304")
        assertEquals(JpegReject.HUFFMAN_TABLE, malformedReason(fiveTwoBitCodes))
        // One code at each length 1..15 leaves two sixteen-bit codes; three is oversubscribed only at
        // the last length, past the lookup's width.
        val counts = ByteArray(16) { if (it < 15) 1 else 3 }
        val sixteenBit = byteArrayOf(-1, -40) + segment(0xC4, byteArrayOf(0) + counts + ByteArray(18) { it.toByte() })
        assertEquals(JpegReject.HUFFMAN_TABLE, malformedReason(sixteenBit))
        // An incomplete table is legal -- every real JPEG's is, the all-ones code being reserved.
        assertIs<JpegDecodeResult.Success>(decodeJpegUntrapped(progressive(scans = baseScans()), Long.MAX_VALUE))
    }

    @Test
    fun aScanThatDoesNotAdvanceTheProgressionIsRefusedAtOnce() {
        // The amplification input: one DC scan, then thousands of refinement headers with no data,
        // each of which used to walk every block of a 1024x1024 frame on synthetic zero bits.
        val clock = TimeSource.Monotonic.markNow()
        val repeatedRefinement = progressive(
            side = 1024,
            scans = listOf(scan(0, 0, 0, 0, ByteArray(1024 * 1024 / 64 / 8))) +
                List(10_000) { scan(0, 0, 1, 0, ByteArray(0)) },
        )
        assertEquals(JpegReject.SCAN_PROGRESSION, malformedReason(repeatedRefinement))
        // The same refinement at the right bit, with no data: refused after a handful of bits.
        val emptyRefinement = progressive(
            side = 1024,
            scans = listOf(scan(0, 0, 0, 1, ByteArray(1024 * 1024 / 64 / 8)), scan(0, 0, 1, 0, ByteArray(0))),
        )
        assertEquals(JpegReject.TRUNCATED, malformedReason(emptyRefinement))
        assertTrue(clock.elapsedNow() < 2.seconds, "refusing both took ${clock.elapsedNow()}")

        val dcTwice = baseScans() + scan(0, 0, 0, 0, byteArrayOf(0x7F))
        assertEquals(JpegReject.SCAN_PROGRESSION, malformedReason(progressive(scans = dcTwice)))
        val acBeforeDc = listOf(scan(1, 63, 0, 0, byteArrayOf(0x7F)), scan(0, 0, 0, 0, byteArrayOf(0x7F)))
        assertEquals(JpegReject.SCAN_PROGRESSION, malformedReason(progressive(scans = acBeforeDc + endOfImage())))
        val refinementAtTheWrongBit = listOf(scan(0, 0, 0, 2, byteArrayOf(0x7F)), scan(0, 0, 1, 0, byteArrayOf(0x7F)))
        assertEquals(JpegReject.SCAN_PROGRESSION, malformedReason(progressive(scans = refinementAtTheWrongBit)))
        val acRefinementBeforeItsFirstScan = baseScans() + scan(1, 5, 1, 0, byteArrayOf(0x7F))
        assertEquals(JpegReject.SCAN_PROGRESSION, malformedReason(progressive(scans = acRefinementBeforeItsFirstScan)))
    }

    @Test
    fun moreScansThanTheCapAreUnsupported() {
        // DC first and refinement, then one first scan per AC coefficient: 65 scans, each legal.
        val dc = listOf(scan(0, 0, 0, 1, byteArrayOf(0x7F)), scan(0, 0, 1, 0, byteArrayOf(0x7F)))
        val everyCoefficient = dc + (1..63).map { scan(it, it, 0, 0, byteArrayOf(0x7F)) }
        assertEquals(MAXIMUM_SCANS + 1, everyCoefficient.size)
        val result = decodeJpegUntrapped(progressive(scans = everyCoefficient), Long.MAX_VALUE)
        assertEquals(JpegReject.SCAN_COUNT, assertIs<JpegDecodeResult.Unsupported>(result).reason)
        // One fewer is decoded.
        assertIs<JpegDecodeResult.Success>(
            decodeJpegUntrapped(progressive(scans = everyCoefficient.dropLast(1)), Long.MAX_VALUE),
        )
    }

    @Test
    fun theProjectionAndTheDecoderReadTheSameMarkers() {
        // Standalone markers (RSTn, TEM) and fill bytes before the frame once made the projection
        // read a length where the decoder read none, so a decodable file projected nothing.
        val injections = listOf(hex("ffd0"), hex("ff01"), hex("ffff"), hex("ffd7ffd0"))
        val corpus = listOf(
            BASELINE_420_JPEG, BASELINE_444_JPEG, PROGRESSIVE_420_JPEG, RESTART_420_JPEG, GREY_JPEG,
            SAMPLED_4X2_JPEG, SAMPLED_MIXED_JPEG,
        )
        for (fixture in corpus) {
            for (injection in injections) {
                for (position in listOf(2, frameMarkerOffset(fixture))) {
                    val bytes = fixture.copyOfRange(0, position) + injection + fixture.copyOfRange(position, fixture.size)
                    val decodedResult = decodeJpegUntrapped(bytes, Long.MAX_VALUE)
                    val image = assertIs<JpegDecodeResult.Success>(decodedResult).image
                    assertEquals(image.width * image.height * 4L, projectedJpegRgbaBytes(bytes))
                }
            }
            // And whatever the projection refuses, the decoder refuses.
            val garbageBeforeFrame = fixture.copyOfRange(0, 2) + hex("00") + fixture.copyOfRange(2, fixture.size)
            assertNull(projectedJpegRgbaBytes(garbageBeforeFrame))
            assertIs<JpegDecodeResult.Malformed>(decodeJpegUntrapped(garbageBeforeFrame, Long.MAX_VALUE))
        }
    }

    @Test
    fun aFileWithoutItsEndOfImageIsTruncated() {
        val withoutEnd = BASELINE_420_JPEG.copyOf(BASELINE_420_JPEG.size - 2)
        assertEquals(JpegReject.TRUNCATED, malformedReason(withoutEnd))
    }

    @Test
    fun restartMarkersMustCountZeroToSevenInOrder() {
        val first = firstMarkerAfterScanStart(RESTART_420_JPEG, 0xD0)
        val skipped = RESTART_420_JPEG.copyOf().also { it[first + 1] = 0xD1.toByte() }
        assertEquals(JpegReject.RESTART_SEQUENCE, malformedReason(skipped))
        val removed = RESTART_420_JPEG.copyOfRange(0, first) + RESTART_420_JPEG.copyOfRange(first + 2, RESTART_420_JPEG.size)
        assertIs<JpegDecodeResult.Malformed>(decodeJpegUntrapped(removed, Long.MAX_VALUE))
    }

    @Test
    fun coloursFollowComponentIdsNotFrameOrder() {
        // The 4:4:4 file with its two chroma entries swapped in the frame header: ids, sampling and
        // tables travel with them, so the right decode is the original picture, byte for byte.
        val frame = frameMarkerOffset(BASELINE_444_JPEG)
        val cb = frame + 2 + 2 + 6 + 3
        val swapped = BASELINE_444_JPEG.copyOf()
        for (i in 0 until 3) {
            swapped[cb + i] = BASELINE_444_JPEG[cb + 3 + i]
            swapped[cb + 3 + i] = BASELINE_444_JPEG[cb + i]
        }
        assertEquals(2, swapped[cb + 3].toInt(), "the swap must have moved Cb (id 2) second")
        assertEquals(decoded(BASELINE_444_JPEG).rgbaSnapshot().toList(), decoded(swapped).rgbaSnapshot().toList())
    }

    @Test
    fun theLargestFrameIsRefusedFromItsHeaderAlone() {
        val frame = frameMarkerOffset(BASELINE_420_JPEG)
        val largest = BASELINE_420_JPEG.copyOf().also {
            for (i in 0 until 4) it[frame + 5 + i] = -1 // height and width 65535
        }
        assertEquals(65535L * 65535L * 4L, projectedJpegRgbaBytes(largest))
        val clock = TimeSource.Monotonic.markNow()
        assertIs<JpegDecodeResult.TooLarge>(decodeJpegUntrapped(largest, maximumDecodedBytes = 64L shl 20))
        assertTrue(clock.elapsedNow() < 1.seconds)
        // The raster boundary is exact for this file too.
        val raster = WIDTH * HEIGHT * 4L
        assertIs<JpegDecodeResult.TooLarge>(decodeJpegUntrapped(BASELINE_420_JPEG, raster - 1))
        assertIs<JpegDecodeResult.Success>(decodeJpegUntrapped(BASELINE_420_JPEG, raster))
    }

    @Test
    fun structuredRandomFilesNeverThrowAndAgreeWithTheirProjection() {
        val random = Random(0x4A504547)
        repeat(4000) {
            val bytes = randomJpeg(random)
            val result = decodeJpegUntrapped(bytes, maximumDecodedBytes = 1L shl 22, maximumWorkingBytes = 1L shl 24)
            val projection = projectedJpegRgbaBytes(bytes)
            if (result is JpegDecodeResult.Success) {
                assertEquals(result.image.width * result.image.height * 4L, projection)
            }
        }
    }

    private fun decoded(bytes: ByteArray): DecodedImage =
        assertIs<JpegDecodeResult.Success>(decodeJpegUntrapped(bytes, Long.MAX_VALUE)).image

    private fun unsupportedReason(bytes: ByteArray): JpegReject =
        assertIs<JpegDecodeResult.Unsupported>(decodeJpegUntrapped(bytes, Long.MAX_VALUE)).reason

    private fun malformedReason(bytes: ByteArray): JpegReject =
        assertIs<JpegDecodeResult.Malformed>(decodeJpegUntrapped(bytes, Long.MAX_VALUE)).reason

    /** Offset of the baseline fixture's `FF C0`. */
    private fun frameMarkerOffset(): Int = frameMarkerOffset(BASELINE_420_JPEG)

    private fun withFrameMarker(marker: Int): ByteArray =
        BASELINE_420_JPEG.copyOf().also { it[frameMarkerOffset() + 1] = marker.toByte() }
}

private const val WIDTH = 19
private const val HEIGHT = 13

private fun frameMarkerOffset(bytes: ByteArray): Int =
    (0 until bytes.size - 1).first { bytes[it] == 0xFF.toByte() && (bytes[it + 1].toInt() and 0xFF) in 0xC0..0xC2 }

private fun firstMarkerAfterScanStart(bytes: ByteArray, marker: Int): Int {
    val scanStart = (0 until bytes.size - 1).first { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xDA.toByte() }
    return (scanStart until bytes.size - 1).first {
        bytes[it] == 0xFF.toByte() && (bytes[it + 1].toInt() and 0xFF) == marker
    }
}

private fun segment(marker: Int, payload: ByteArray): ByteArray =
    byteArrayOf(-1, marker.toByte(), ((payload.size + 2) ushr 8).toByte(), (payload.size + 2).toByte()) + payload

/** A one-symbol table: symbol 0 coded by the single bit `0`. */
private fun oneSymbolTable(tableClass: Int): ByteArray =
    segment(0xC4, byteArrayOf((tableClass shl 4).toByte(), 1) + ByteArray(15) + byteArrayOf(0))

/** A progressive scan of the one grey component: `Ss`, `Se`, `Ah`, `Al`, then [data]. */
private fun scan(start: Int, end: Int, high: Int, low: Int, data: ByteArray): ByteArray =
    segment(0xDA, byteArrayOf(1, 1, 0, start.toByte(), end.toByte(), ((high shl 4) or low).toByte())) + data

private fun endOfImage(): List<ByteArray> = listOf(byteArrayOf(-1, 0xD9.toByte()))

/** DC first and an EOB AC first over one 8x8 block, then EOI: the smallest whole progressive image.
 * Each scan's one coded symbol is the bit `0`, padded with ones to `0x7F`. */
private fun baseScans(): List<ByteArray> =
    listOf(scan(0, 0, 0, 0, byteArrayOf(0x7F)), scan(1, 63, 0, 0, byteArrayOf(0x7F)))

/**
 * A greyscale progressive JPEG of [side]x[side] whose two Huffman tables each hold the one symbol 0
 * (a DC difference of 0, and an AC end-of-band), so any scan's data is one `0` bit per block. EOI is
 * appended unless [scans] already ends with it.
 */
private fun progressive(side: Int = 8, scans: List<ByteArray>): ByteArray {
    val dqt = segment(0xDB, byteArrayOf(0) + ByteArray(64) { 1 })
    val sof = segment(0xC2, byteArrayOf(8, (side ushr 8).toByte(), side.toByte(), (side ushr 8).toByte(), side.toByte(), 1, 1, 0x11, 0))
    val end = if (scans.lastOrNull()?.contentEquals(endOfImage()[0]) == true) emptyList() else endOfImage()
    return (listOf(byteArrayOf(-1, 0xD8.toByte()), dqt, sof, oneSymbolTable(0), oneSymbolTable(1)) + scans + end)
        .fold(ByteArray(0)) { all, part -> all + part }
}

/** Length-consistent segments in a random order, with random fields and entropy bytes. */
private fun randomJpeg(random: Random): ByteArray {
    val parts = mutableListOf(byteArrayOf(-1, 0xD8.toByte()))
    repeat(random.nextInt(1, 9)) {
        parts += when (random.nextInt(7)) {
            0 -> segment(0xDB, byteArrayOf(random.nextInt(2).toByte()) + ByteArray(64) { random.nextInt(0, 4).toByte() })
            1 -> {
                val counts = ByteArray(16) { if (random.nextInt(3) == 0) random.nextInt(0, 6).toByte() else 0 }
                val total = counts.sumOf { it.toInt() }
                segment(0xC4, byteArrayOf((random.nextInt(2) shl 4 or random.nextInt(4)).toByte()) + counts + random.nextBytes(total))
            }
            2 -> {
                val count = if (random.nextBoolean()) 1 else 3
                val header = byteArrayOf(8, 0, random.nextInt(1, 40).toByte(), 0, random.nextInt(1, 40).toByte(), count.toByte())
                val entries = (1..count).fold(ByteArray(0)) { all, id ->
                    all + byteArrayOf(id.toByte(), ((random.nextInt(1, 3) shl 4) or random.nextInt(1, 3)).toByte(), random.nextInt(2).toByte())
                }
                segment(if (random.nextBoolean()) 0xC0 else 0xC2, header + entries)
            }
            3 -> {
                val count = random.nextInt(1, 4)
                val entries = (1..count).fold(ByteArray(0)) { all, id -> all + byteArrayOf(id.toByte(), random.nextInt(0, 0x22).toByte()) }
                val start = random.nextInt(0, 3)
                segment(0xDA, byteArrayOf(count.toByte()) + entries + byteArrayOf(start.toByte(), (start + random.nextInt(0, 64 - start)).toByte(), random.nextInt(0, 0x22).toByte())) +
                    random.nextBytes(random.nextInt(0, 64)).map { if (it == (-1).toByte()) 0 else it }.toByteArray()
            }
            4 -> segment(0xDD, byteArrayOf(0, random.nextInt(0, 4).toByte()))
            5 -> byteArrayOf(-1, (0xD0 + random.nextInt(8)).toByte())
            else -> segment(0xE0 + random.nextInt(16), random.nextBytes(random.nextInt(0, 20)))
        }
    }
    if (random.nextInt(4) != 0) parts += byteArrayOf(-1, 0xD9.toByte())
    return parts.fold(ByteArray(0)) { all, part -> all + part }
}

private fun hex(text: String): ByteArray {
    val digits = text.filterNot { it.isWhitespace() }
    return ByteArray(digits.length / 2) { digits.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

private val BASELINE_420_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a07
    0706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb0043010304
    0405040509050509140d0b0d141414141414141414141414141414141414141414141414141414141414141414141414
    1414141414141414141414141414ffc0001108000d001303012200021101031101ffc4001f0000010501010101010100
    000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221
    31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a
    434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596979899
    9aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1
    f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100
    020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015
    6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869
    6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4
    c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00f9
    a7c33f017fb0f64fe4f9fbbe4dbb76fbe7bfa57adf863e02fdab6ea3e4edc7cfe56dcfdded9fc3d2be97f0c7c32d3743
    d93e7cfddf26dd9b71df3d4fa57ad7863e1969b75b351cedc7cfe56ccfdded9cfb7a57959b719558df05ec5ae4f7fd87
    35fd9ffd3ef6bf6ed7fe1df5beda1f13e1f78935bdc9733d7af7f2b7ea7ca76df0bff709fe81dbfbdffd6a2bef1b7f0e
    69be427fa376f5ff00eb515f9ebf15eadffe478fff0009ff00e01fd874fc44adc8bdc7b773ffd9
    """,
)

private val BASELINE_420_REFERENCE: ByteArray = hex(
    """
    05002a0800271005251a063725025e410d934c02ad6004c17000ba86089a890048960222a70423b20435b50053cf0b89
    df08aee806c2f306c6080f2c0f132c1817291f15382b0f634a1e995517b86615c97714c78717a18a0e4c9a1228ab1427
    b81439bf105ddc1f95e417b4ef16c7fa16cb0527260f2e291a2f262028352d23604a2f96562db96129c8732bcd802aa5
    8827529b2d2cac2c29b62838c0225dd92d91e62cb3f12ac5fb2bc9003541063a45143f46233e53353d6e4c458b5b479e
    6846a57848ac804393843c61913a4aa23948b43955c63e70dd4689ea4498f644a2ff45a60f5ca4135ba31d5ca12b5c97
    385783404f664e4e4c5c4e43674f4578535b8d5c899f5ea0b05caabe5aa2c85785cc4e64db5151e95249f2534d0967c9
    0c66c6196ac52c72b73d738f40645a566836666922716a247b6b4a8f709a986bc0a867cbbb6cbdce7396ce665ddd6936
    ec6d28f76e2a0b78d3117bd11a80ca2b86b53e878e3f7657587a3d667928747d2c7d7a4d9384a39f81c9ab7fceba80bb
    cd8694c97658e07d39ee8029f9812b0c92ab1697ad209aa92d9b9c3d9a873e8a6457905d628b4f72905478895d939892
    a499a7af97a7be979cd29f8ece8e68dc8d52eb8f4cf4915001a54408a64718a74f27a55c33a26f51b09058ad9a69b2a7
    74ada487b49789a66497a44caaa54ab8a556be9f68daaf8ce9ae9cf5afa7ffb0ab00bf190ac01e1abe2925b93d31b562
    4ec69953c1b45fc2c76dc0c67fc2a385b94d98bc26acbd21b8bc33beb75ddcc798e6c2b6efc0c8fac0cc02d8220edb28
    1ad92b24d13a32ce604ddd9555dbba5ed6ca70d9cb7fd7a58ad2549ed82cafd822b7d42ebed05ad8dc90e4d8b2efd5c4
    fad6c800dd4404e04813e24c22e35635e5694ced855aef9e65eca777efa880e88f88e16395df4aa3df3fb2e147c2e766
    d8ef85e7ef99f5efa3fef0a716ffb01cffaf28ffb235ffa541fe8647f76857f55865f64f6ff54983f9649bff96adffac
    bcffaac6ff9eceff87d1f86be0f656ecf84ef6f952
    """,
)

private val BASELINE_422_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a07
    0706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb0043010304
    0405040509050509140d0b0d141414141414141414141414141414141414141414141414141414141414141414141414
    1414141414141414141414141414ffc0001108000d001303012100021101031101ffc4001f0000010501010101010100
    000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221
    31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a
    434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596979899
    9aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1
    f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100
    020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015
    6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869
    6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4
    c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00f9
    a7c33f017fb0f64fe4f9fbbe4dbb76fbe7bfa57adf863e02fdab6ea3e4edc7cfe56dcfdded9fc3d2bebf36c77b1bf0ff
    00b4bfb0fdff003dbe2b69c9cb7d3e2f8b99edb1f43e1ff15df92adf7d0f45b6f85ffb84ff0040edfdeffeb515f9ebf1
    a75ff75ffca9ff00da1fd874f3df723fbce9fd753d4bc31f0cb4dd0f64f9f3f77c9b766dc77cf53e95eb5e18f865a6dd
    6cd473b71f3f95b33f77b673ede95f9c66ceb51bf0ff003dfd87eff9edf15b4e4e5be9f17c5ccf6d8ff23fc3fcf310f9
    2af7d0f45b7f0e69be427fa376f5ff00eb515f9ebf172b5ffdd3ff002a7ff687f61d3c6e23917bdd0fffd9
    """,
)

private val BASELINE_422_REFERENCE: ByteArray = hex(
    """
    0600270d00291a002f27004033005a4c098a5001ac6003c56c01c38307a587004d980027aa012ab8003cbb0055d6078b
    e404b4f100c9fa02cc08121e1114251d132b290f3f330b63521a975817b36616c77415cb8417a8870f4c9a122aab1230
    bb1044c10e60de1f93e118b2ec18c5f619c9052729102d2b1b2f2423273031225b4e2e935b2bb56726ca7728d18328a7
    8829479b2f23aa2c2db62542be2166d82c98e52cb6f02ac7f92bcb003c1e064123164523244238363e634f41965f3fbe
    6c3cce7d40c9823f9c873f4b924124a24120b24133c4425ada478be143b4ed41cbf742cd1252ce1751cf2151cf3053bb
    3a548d40515b4d553059561f6655297854549255a3a453ccb553cec053bec85495cd4d64df5437f05624f957260968c2
    0d66c41c67cd306ec13f71943f645c52693b606b266b6e217a6b4a906ca99d66ccac67c2be6dafcd7689cc6956dd6a34
    ed6c28f76e2a057cce0d7ecc1a80c82d85b33d888d3e7757537d3d607c286d8227777e4a9284a59f81c9aa82beba83ab
    ca8991c7765be07d39f17f29fa802b0790c61194c81d96c12d97b13e97954288645d9244678f2c76952e798c4c9296a2
    a194c9ab93c5bb93b5d09d9acf8e64dc9337ec9625f6982708a7260fa8291eaa302aa94035a55b4eb38b53abac64afc6
    6eaac286b2a589ab4d9aaa26abab29baaa3bbda356d8b08ce4adb2f0abc8f9adcb07bb1c0fbc221dbd2928b83d33b461
    53c49657c0b166bfc573bdc883bfa587b94a99bb28aabb2eb9b941c0b65ddec793e6c2b2efc0c6fac1c806d42a10d92b
    1cd92324d33032cf5c52db955cd8b668d1ca78d4d183d5a788d5479bdb23aad82db5d041becd66d9d999e4d7b5edd6c6
    f7d7ca00e51b02e91d0fed1e1feb3232e85f4beb915ce8bb68e4ca77e9c57de89883e9488eeb219ee91cade82ebfeb56
    d6ef87e6ebb3f2e9cafdeacc15fcd01afbd125fcd333febf3dff8f43fb5d50ff345eff2568fe2b7cff5996ffa8a8fed1
    b8fdd1c3fdc0cbfe99cff967ddfd38ebff25f6ff27
    """,
)

private val BASELINE_444_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a07
    0706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb0043010304
    0405040509050509140d0b0d141414141414141414141414141414141414141414141414141414141414141414141414
    1414141414141414141414141414ffc0001108000d001303011100021101031101ffc4001f0000010501010101010100
    000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221
    31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a
    434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596979899
    9aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1
    f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100
    020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015
    6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869
    6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4
    c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00f9
    a7c33f017fb0f64fe4f9fbbe4dbb76fbe7bfa57e918bc77fa857ccfda7b7e7fddf2db92d7f7af7bcbf96d6b75df43e53
    87f8afeb5cb0bdba9eb7e18f80bf6adba8f93b71f3f95b73f77b67f0f4af8cc5e3bfb56fc5ded39797f79eced7fe1696
    e7baf8b937e5d2fb3b6bfd2fc3fc57c96a17f2bfa9e8b6df0bff00709fe81dbfbdff00d6af98978d3ef3ff0065ff00ca
    9ffda1fba50cf7f751fde7f5f79ea5e18f865a6e87b27cf9fbbe4dbb36e3be7a9f4af91c5badc057ccf9fdbf3feef96d
    c96bfbd7bde5fcb6b5baefa1fe2470fe7988c572c36ea7ad7863e1969b75b351cedc7cfe56ccfdded9cfb7a57c662dd6
    cd7fe32ee7e5b7ef3d9daffc2d2dcf75f1726fcba5f676d7fa5b87b3cc442d43e57f53d16dfc39a6f909fe8ddbd7ff00
    ad5f312f172b733ff64ffca9ff00da1fba50c6e23d9c7de3ffd9
    """,
)

private val BASELINE_444_REFERENCE: ByteArray = hex(
    """
    01021e04022a16012a2f00273703294501c64b00c86303bf6f00ca8101c88c052397002e9d0923bb0420c90025cf00cb
    e600cbf200c9ff00c8060e320d16271d12303013173712314a14ca5115cc6517c37117c68112c98e13279f0e2ba71430
    bd1427cb1426d419ccdf16c6ed17c7fb17c605262d102e261e2f1f2a2720332a2b4724d8532cc56227d1702dca7d25c5
    8f2b29a42b22ac2c2bba272dc4292dcb29cae529c8f129c9ff29c7003e19063e3118432527452135492e493dbd5b3fc7
    693fc97a41d0813abe8a471d963e26a34020b44033c64c23d53fc4e03fcbef40cbfc40ca0756d91356be2451c8304fce
    384bc34058344f55295b55236d53227a5a318f4ecea056c7b054d1be54c2ca48ccd0572ae35525f15524ff5522006fc6
    0a66c91d66cd2d6dcf3b6ac642701859692b636c1b7666287e6f2a8b6ac3946acca868c6b76dc6cb6bc4d86c23e06b28
    ef6c28fc6c260085c00d7fc81e80bd2880d73682c03f80265a7e245f7c28757c2c7a83268b82c59886c2ad80c3b480cb
    c480c9d17c21e67d2af27e29ff7e280091d41593c62492c32994c83691c842932c60962662922e7895297993268e90c9
    a196c0b68cceb88fd5c798ccd59823de9526ed9625fb962404ab1f07ac2a1bac2b32a92739ac2948aac54fa8ca67aec2
    71a7cb83acc88fb02199a92c9fb225bdad22cca524d1a8c8e4a8caf0abcafeabc906b7310cbd251dbb302fbc1637bc2f
    4abecb52becb66bfc370bfc67fbac88ebc259fb72ba6bd2fbdbb28cbbc25d5c1cae4bfc7f1c0c6fec0c506d32e10da26
    1fd91e2bd22034d82c49d2dc55d8c662d3d170d9cb7ed1c78fd829a4d723add72db9d22ec4d52dced6cbe2d4c9efd5c8
    fbd6c600e71602e62f13ea2023ed1d32f22b46e6ba58e8c465e7c574e9cc7ae3bb86f01c92e8259ee91cafe72cc2f41f
    d1e7c1e6e6caf3e8caffe9c90affdd17ffc128fccc33fad33bf5c643ff3853ff2d60ff286dfd217dff3593f8d6a3ffcd
    b3fed3c1fec2cdf2d0d4ff32e0ff26ecff25f9ff24
    """,
)

private val PROGRESSIVE_420_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a07
    0706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb0043010304
    0405040509050509140d0b0d141414141414141414141414141414141414141414141414141414141414141414141414
    1414141414141414141414141414ffc2001108000d001303012200021101031101ffc400160001010100000000000000
    000000000000070006ffc40017010003010000000000000000000000000004050607ffda000c03010002100310000001
    34d6a66b4448533c53db0fffc4001c1000020300030100000000000000000000040500020311121321ffda0008010100
    010502190f84190f69557f06599e106599da547cf8ffc4001a1100020203000000000000000000000000020600110121
    31ffda0008010301013f015f643d66e0b11d727fffc40024110000030509000000000000000000000000021201040511
    162123313241546272a3ffda0008010201013f017b8c999728c2d44f2f356bd45566df798fffc4001a10000202030000
    00000000000000000000001113323181a1ffda0008010100063f0269922d141e493854ffc4001a100003000301000000
    0000000000000000001121314151c1ffda0008010100013f219b3e2489e3b8e10fb156f89215e1b8e09c7fffda000c03
    01000200030000001048ff00ffc4001b110001040300000000000000000000000000112131514161c1ffda0008010301
    013f109d1f37a4e882f8b3ffc4001d11000104020300000000000000000000001100012131416171d1f0ffda00080102
    01013f102466df7467293509cf6f385fffc4001f1000010207010100000000000000000000510011012131417181f061
    e1ffda0008010100013f10e1a88bdc2110ec1f416afca29e7aaaef58853ce43f077f02c2e7e2ffd9
    """,
)

private val PROGRESSIVE_420_REFERENCE: ByteArray = hex(
    """
    05002a0800271005251a063725025e410d934c02ad6004c17000ba86089a890048960222a70423b20435b50053cf0b89
    df08aee806c2f306c6080f2c0f132c1817291f15382b0f634a1e995517b86615c97714c78717a18a0e4c9a1228ab1427
    b81439bf105ddc1f95e417b4ef16c7fa16cb0527260f2e291a2f262028352d23604a2f96562db96129c8732bcd802aa5
    8827529b2d2cac2c29b62838c0225dd92d91e62cb3f12ac5fb2bc9003541063a45143f46233e53353d6e4c458b5b479e
    6846a57848ac804393843c61913a4aa23948b43955c63e70dd4689ea4498f644a2ff45a60f5ca4135ba31d5ca12b5c97
    385783404f664e4e4c5c4e43674f4578535b8d5c899f5ea0b05caabe5aa2c85785cc4e64db5151e95249f2534d0967c9
    0c66c6196ac52c72b73d738f40645a566836666922716a247b6b4a8f709a986bc0a867cbbb6cbdce7396ce665ddd6936
    ec6d28f76e2a0b78d3117bd11a80ca2b86b53e878e3f7657587a3d667928747d2c7d7a4d9384a39f81c9ab7fceba80bb
    cd8694c97658e07d39ee8029f9812b0c92ab1697ad209aa92d9b9c3d9a873e8a6457905d628b4f72905478895d939892
    a499a7af97a7be979cd29f8ece8e68dc8d52eb8f4cf4915001a54408a64718a74f27a55c33a26f51b09058ad9a69b2a7
    74ada487b49789a66497a44caaa54ab8a556be9f68daaf8ce9ae9cf5afa7ffb0ab00bf190ac01e1abe2925b93d31b562
    4ec69953c1b45fc2c76dc0c67fc2a385b94d98bc26acbd21b8bc33beb75ddcc798e6c2b6efc0c8fac0cc02d8220edb28
    1ad92b24d13a32ce604ddd9555dbba5ed6ca70d9cb7fd7a58ad2549ed82cafd822b7d42ebed05ad8dc90e4d8b2efd5c4
    fad6c800dd4404e04813e24c22e35635e5694ced855aef9e65eca777efa880e88f88e16395df4aa3df3fb2e147c2e766
    d8ef85e7ef99f5efa3fef0a716ffb01cffaf28ffb235ffa541fe8647f76857f55865f64f6ff54983f9649bff96adffac
    bcffaac6ff9eceff87d1f86be0f656ecf84ef6f952
    """,
)

private val RESTART_420_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a07
    0706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb0043010304
    0405040509050509140d0b0d141414141414141414141414141414141414141414141414141414141414141414141414
    1414141414141414141414141414ffc0001108000d001303012200021101031101ffc4001f0000010501010101010100
    000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221
    31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a
    434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596979899
    9aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1
    f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100
    020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015
    6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869
    6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4
    c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffdd00040001ffda000c0301000211
    0311003f00f9a7c33f017fb0f64fe4f9fbbe4dbb76fbe7bfa57adf863e02fdab6ea3e4edc7cfe56dcfdded9fc3d2be97
    f0c7c32d3743d93e7cfddf26dd9b71df3d4fa57ad7863e1969b75b351cedc7cfe56ccfdded9cfb7a57959b719558df05
    ec5ae4f7fd8735fd9ffd3ef6bf6ed7fe1df5beda1f13e1f78935bdc9733d7af7f2b7ea7fffd0b56df0bff709fe81dbfb
    dffd6a2bef1b7f0e69be427fa376f5ff00eb515f943f15eadffe478fff0009ff00e01fd1b4fc44adc8bdc7b773ffd9
    """,
)

private val RESTART_420_REFERENCE: ByteArray = hex(
    """
    05002a0800271005251a063725025e410d934c02ad6004c17000ba86089a890048960222a70423b20435b50053cf0b89
    df08aee806c2f306c6080f2c0f132c1817291f15382b0f634a1e995517b86615c97714c78717a18a0e4c9a1228ab1427
    b81439bf105ddc1f95e417b4ef16c7fa16cb0527260f2e291a2f262028352d23604a2f96562db96129c8732bcd802aa5
    8827529b2d2cac2c29b62838c0225dd92d91e62cb3f12ac5fb2bc9003541063a45143f46233e53353d6e4c458b5b479e
    6846a57848ac804393843c61913a4aa23948b43955c63e70dd4689ea4498f644a2ff45a60f5ca4135ba31d5ca12b5c97
    385783404f664e4e4c5c4e43674f4578535b8d5c899f5ea0b05caabe5aa2c85785cc4e64db5151e95249f2534d0967c9
    0c66c6196ac52c72b73d738f40645a566836666922716a247b6b4a8f709a986bc0a867cbbb6cbdce7396ce665ddd6936
    ec6d28f76e2a0b78d3117bd11a80ca2b86b53e878e3f7657587a3d667928747d2c7d7a4d9384a39f81c9ab7fceba80bb
    cd8694c97658e07d39ee8029f9812b0c92ab1697ad209aa92d9b9c3d9a873e8a6457905d628b4f72905478895d939892
    a499a7af97a7be979cd29f8ece8e68dc8d52eb8f4cf4915001a54408a64718a74f27a55c33a26f51b09058ad9a69b2a7
    74ada487b49789a66497a44caaa54ab8a556be9f68daaf8ce9ae9cf5afa7ffb0ab00bf190ac01e1abe2925b93d31b562
    4ec69953c1b45fc2c76dc0c67fc2a385b94d98bc26acbd21b8bc33beb75ddcc798e6c2b6efc0c8fac0cc02d8220edb28
    1ad92b24d13a32ce604ddd9555dbba5ed6ca70d9cb7fd7a58ad2549ed82cafd822b7d42ebed05ad8dc90e4d8b2efd5c4
    fad6c800dd4404e04813e24c22e35635e5694ced855aef9e65eca777efa880e88f88e16395df4aa3df3fb2e147c2e766
    d8ef85e7ef99f5efa3fef0a716ffb01cffaf28ffb235ffa541fe8647f76857f55865f64f6ff54983f9649bff96adffac
    bcffaac6ff9eceff87d1f86be0f656ecf84ef6f952
    """,
)

private val PROGRESSIVE_RESTART_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a07
    0706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb0043010304
    0405040509050509140d0b0d141414141414141414141414141414141414141414141414141414141414141414141414
    1414141414141414141414141414ffc2001108000d001303012200021101031101ffc400170000030100000000000000
    00000000000000060703ffc40017010003010000000000000000000000000004050607ffdd00040001ffda000c030100
    021003100000019a36d31b4449ffd0d4bc127a37ffc4001c100002030003010000000000000000000004050002031112
    1321ffda0008010100010502190f84ffd0190f69ffd1aabf9fffd21966784fffd31966769fffd4a8f9f1ffc4001a1100
    02020300000000000000000000000002060011012131ffda0008010301013f015f643d66e7ffd01623ae4fffc4002411
    0000030509000000000000000000000000021201040511162123313241546272a3ffda0008010201013f017b8c999728
    c2d44f2f356bd47fffd0aacdbef31fffc4001a1000020203000000000000000000000000001113323181a1ffda000801
    0100063f02699fffd09168ffd1a1ffd2793fffd39387ffd4a9ffc4001a10000300030100000000000000000000000011
    21314151c1ffda0008010100013f219b3e248fffd09e3b8e1fffd187d9ffd255be248fffd35786e387ffd44e3fffda00
    0c030100020003000000104bffd03fffc4001b110001040300000000000000000000000000112131514161c1ffda0008
    010301013f109d1f37a4e9ffd0417c59ffc4001d11000104020300000000000000000000001100012131416171d1f0ff
    da0008010201013f102466df746729350bffd073dbce17ffc4001f100001020701010000000000000000000051001101
    2131417181f061e1ffda0008010100013f10e1a88bdc2fffd0110ec1f417ffd1d5f945ffd29e7aaaef5885ffd39e721f
    83bf817fffd4c2e7e2ffd9
    """,
)

private val PROGRESSIVE_RESTART_REFERENCE: ByteArray = hex(
    """
    05002a0800271005251a063725025e410d934c02ad6004c17000ba86089a890048960222a70423b20435b50053cf0b89
    df08aee806c2f306c6080f2c0f132c1817291f15382b0f634a1e995517b86615c97714c78717a18a0e4c9a1228ab1427
    b81439bf105ddc1f95e417b4ef16c7fa16cb0527260f2e291a2f262028352d23604a2f96562db96129c8732bcd802aa5
    8827529b2d2cac2c29b62838c0225dd92d91e62cb3f12ac5fb2bc9003541063a45143f46233e53353d6e4c458b5b479e
    6846a57848ac804393843c61913a4aa23948b43955c63e70dd4689ea4498f644a2ff45a60f5ca4135ba31d5ca12b5c97
    385783404f664e4e4c5c4e43674f4578535b8d5c899f5ea0b05caabe5aa2c85785cc4e64db5151e95249f2534d0967c9
    0c66c6196ac52c72b73d738f40645a566836666922716a247b6b4a8f709a986bc0a867cbbb6cbdce7396ce665ddd6936
    ec6d28f76e2a0b78d3117bd11a80ca2b86b53e878e3f7657587a3d667928747d2c7d7a4d9384a39f81c9ab7fceba80bb
    cd8694c97658e07d39ee8029f9812b0c92ab1697ad209aa92d9b9c3d9a873e8a6457905d628b4f72905478895d939892
    a499a7af97a7be979cd29f8ece8e68dc8d52eb8f4cf4915001a54408a64718a74f27a55c33a26f51b09058ad9a69b2a7
    74ada487b49789a66497a44caaa54ab8a556be9f68daaf8ce9ae9cf5afa7ffb0ab00bf190ac01e1abe2925b93d31b562
    4ec69953c1b45fc2c76dc0c67fc2a385b94d98bc26acbd21b8bc33beb75ddcc798e6c2b6efc0c8fac0cc02d8220edb28
    1ad92b24d13a32ce604ddd9555dbba5ed6ca70d9cb7fd7a58ad2549ed82cafd822b7d42ebed05ad8dc90e4d8b2efd5c4
    fad6c800dd4404e04813e24c22e35635e5694ced855aef9e65eca777efa880e88f88e16395df4aa3df3fb2e147c2e766
    d8ef85e7ef99f5efa3fef0a716ffb01cffaf28ffb235ffa541fe8647f76857f55865f64f6ff54983f9649bff96adffac
    bcffaac6ff9eceff87d1f86be0f656ecf84ef6f952
    """,
)

private val GREY_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a07
    0706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffc0000b08000d
    001301011100ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b51000020103
    03020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f024336272
    82090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475
    767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9
    cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffda0008010100003f00f99fc31f017fb136
    4fe4f9dbbe4dbb76fbe7bfa57adf863e02fda766a3e4edc7cfe5edcfdded9fc3d2bd1adbe16fee13fd03b7afff005abd
    4bc31f0cb4dd1364f9f3b77c9b766dc77cf53e95eb5e18f867a6dd6cd473b71f3f97b33f77b67f0f4af46b6f0d69be42
    7fa3f6f5ff00eb57ffd9
    """,
)

private val GREY_REFERENCE: ByteArray = hex(
    """
    04060b11162c2c34363e3031363d3e55595e620f14191b20383b4144463a3d43484c65666b6f1d2327272c42474b5150
    494e5253576b72777b252b32373f4e565a615d565659606b7a7e83874c4c5053524c4e51545f70787d7f7e767a7f8353
    545a6466585d61646c7d80848c9285868b8f6164696f7162696b70758c9293979c8b92979b6c7276797c6e79777f7e96
    9ea0a2ab9b9fa4a8686b72777a8f909a9ba295959aa1a2b9bec2c673787c7e849c9fa4a9ac9fa2a7acb1c9caced28489
    8c8c93a9adafb5b4adb2b6b6bacfd5dade878d9399a1b1b8bcc5c1bababdc4cfdee3e7ebb1b3b6bab8b1b4b8b7c2d3dc
    e0e2e1d9dde1e5
    """,
)

internal val TWO_PIXEL_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000101010101010101010101010101010101010101010101
    0101010101010101010101010101010101010101010101010101010101010101010101010101010101ffdb0043010101
    010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101
    0101010101010101010101010101ffc00011080001000203011100021101031101ffc400140001000000000000000000
    00000000000004ffc4001a1000010501000000000000000000000000080005073877b6ffc40015010101000000000000
    00000000000000000809ffc4001b11000105010100000000000000000000000600040776b53637ffda000c0301000211
    0311003f0000cb5b87cc4228e0d814dd92fd1e40bb15eebf4fa8ff008309a88de3325fffd9
    """,
)

private val SAMPLED_1X2_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a07
    0706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb0043010304
    0405040509050509140d0b0d141414141414141414141414141414141414141414141414141414141414141414141414
    1414141414141414141414141414ffc0001108000d001303011200021101031101ffc4001f0000010501010101010100
    000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221
    31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a
    434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596979899
    9aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1
    f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100
    020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015
    6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869
    6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4
    c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00f9
    a7c33f017fb0f64fe4f9fbbe4dbb76fbe7bfa57de5e18f865a6e87b27cf9fbbe4dbb36e3be7a9f4ad717c655726be23d
    8bcbefeefb4e6f6f7ebc9c9adaf6e6e6e9cb6ea7f3ff000ff8955aa72ae673f2dbe67cd1e18f80bf6adba8f93b71f3f9
    5b73f77b67f0f4afbc7c31f0cb4dbad9a8e76e3e7f2b667eef6ce7dbd2be3317c655713ff0a7ec5d6b6bf5ae6e5b72fd
    bf63d7d9dadcb6f7f97fbc7f4bf0f7895595a1ccd7f77f4bf99f29db7c2ffdc27fa076fef7ff005abef1b7f0e69be427
    fa376f5ffeb57cc4bc58abccff00e171ff00e13ffc03f74a1e2256f651f71fde7fffd9
    """,
)

private val SAMPLED_1X2_REFERENCE: ByteArray = hex(
    """
    00031c04022917002a2e011e3605224401cb4704b65b05c86503c87a04ca8b042f9b011ba4061fbb051bc50029ca04bd
    dd04c7eb04c6f904c40313230d14301d12302e12213615284b12d05216c46216ce7016cd8012cb8b12339f1022a91523
    bd1526ca132fd718cae313cbf214cbff14c9012a26102c2f202b2f262920302c294a25ca5828ca6228ca752dc18025c2
    8b2b399f2b2baf2b26b6292fbe2d2ad425cae628c8f228c9ff28c700364709374e1a3b4e2340463144534c40a46142aa
    6a45a57e489c843f9c863d5a94394ba73745b13a56c0474edd40a9ea41a4f743a6ff43a40560a91659aa24599d295d97
    31569a425252554b4a5c4e416e4c407c534d8b58a39f5da5b65aa3b95aabc15893d44e4ddc514cea514bf85149006dc9
    0e64cb2067bf2c70c3386bc7436b2f5d6826646a20766724806e2c8b69c7966bc5ad66c4b86acdc96dc2db692ae06a2a
    ef6b2afc6b280081c70f7ccf227dc62d80cc3b7cd0427d2b5b7d26647b21787b2c7d812a8d7fca9c83c7b07dcab87ecb
    cd7bcfd57924e37f2af17f29ff7f280498a61598ac2796a73196a83d93aa3f8d4f59924e608d4a758e54778d4c9096a6
    a29aa7b196a5be95a7d397b0d5904fde8d4eed8e4efb8e4d00a35008a54a1ba64d2ba64c33a94b4daea756aba66bb0ab
    74aea087b0ac8ca85399a24fa7a54eb8a74dc1a446d5aca8e8ada7f7aea7ffaea503ba2a0abf221bbd292cbb2533c121
    49c0c850becb62c0cc6dc1c37dbbc88bbd289cb929a6bf26babd24c4c021d2c2cfe4becbf2becaffbec803d52a0bdd22
    1cd82c2dd02936d62a48d7c351daca61d5cc6fdaca7bd3c58ed82da2d62ca8da29b9d520c8d32dccd7c7e4d4c5f0d5c4
    ffd4c300dd4501e34115e14a2cdf503ae65446eb9c55f0a266eca774f0aa7ae89f8be44c96df489ce148b6e040cde458
    d1eda2e9eda3f6eea3ffeea20fffb019ffa72bffa93dfcaf44f7a345fc5452fa4b62f84f6df3567dfe5b9bff9fabffac
    b5ffb0c9ff9ed8f4a7d2fc50e1f651eef750fbf74e
    """,
)

private val SAMPLED_4X1_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a07
    0706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb0043010304
    0405040509050509140d0b0d141414141414141414141414141414141414141414141414141414141414141414141414
    1414141414141414141414141414ffc0001108000d001303014100021101031101ffc4001f0000010501010101010100
    000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221
    31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a
    434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596979899
    9aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1
    f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100
    020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015
    6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869
    6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4
    c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00f9
    a7c33f017fb0f64fe4f9fbbe4dbb76fbe7bfa57adf863e02fdab6ea3e4edc7cfe56dcfdded9fc3d2bd16dbe17fee13fd
    03b7f7bffad4577e77c77feab6239393da5ff77bf2ff000bddbed2f8af7b74eecfe82f0bf893dbe4e9f3db67f79ea5e1
    8f865a6e87b27cf9fbbe4dbb36e3be7a9f4af5af0c7c32d36eb66a39db8f9fcad99fbbdb39f6f4af45b7f0e69be427fa
    376f5ffeb515fced9df17d6e15c4727b3f6b7fddfc5cbfc2f76fb4be2bdedd3bb3f94fc2fcd3115f274ef6d9fde7ffd9
    """,
)

private val SAMPLED_4X1_REFERENCE: ByteArray = hex(
    """
    14001c16001e1b002321062936008a4b069f4b069f540fa884027c8b09837e00767f0077b80043be0049be0049d4115f
    f200bcf600c0fa03c4140b1c1910211d14252017283700984f17b0521ab35820b989197b8b1b7d7d0d6f801072b60748
    bc0d4ec01152d8296af10dc6f612cbfa16cf10222417292b1b2d2f1a2c2e38129b4e28b1542eb75731ba883176873075
    7e276c832c71b42050b62252ba2656ce3a6af024c1f428c5f82cc90538170c3f1e134625184b2a412f93503ea25846aa
    5c4aae8c477e88437a803b72803b72ad3443b43b4abf4655ce5564f33ab1f83fb6fc43ba194fc91a50ca1d53cd2056d0
    4b575545514f4652504955537541687f4b72905c8399658cc6539ec855a0c6539ebe4b96ea4e38ee523cf256401066bd
    1066bd166cc31f75cc52744f446641496b464c6e49795770815f7893718a97758ebf6692c66d99cb729ebd6490ee6426
    f3692bf76d2f0b7cc20e7fc51384ca198ad05388504378404a7f474b8048786e6c7d7371938987998f8dbc8099bf839c
    c488a1b2768fed7b23f17f27f5832b0b8ec41194ca1598ce189bd156955e49885153925b52915a7d866b7c856a939c81
    9ba489bb939ebe96a1c79faab8909beb8d2df09232f4963613a22216a5251cab2b21b030359a924aafa74bb0a855bab2
    83ac828bb48a7ea77d7ea77db89e49bea44fbea44fd4ba65f0a4bef5a9c3f9adc715b51319b9171ebe1c21c11f38a88f
    51c1a853c3aa59c9b088c3738ac5757db86880bb6bb7b143bcb648c0ba4cd9d365f2b9c2f6bdc6fac1ca14cf201ad526
    1dd8291dd8293cbe9853d5af58dab45adcb68bdc758adb7481d26b86d770b6cc50b7cd51bcd256d1e76bf0cec4f4d2c8
    f8d6cc03e01609e61c0fec2215f2283ed7944de6a355eeab58f1ae88ef7c84eb787de4717de471aadb44b0e14abced56
    cbfc65f2e4b5f7e9bafbedbe1cfacb1dfbcc21ffd024ffd34cff5546fc4f48fe514cff5576eb6782f77393ff849cff8d
    c7ff9cc9ff9ec7ff9cc0f895ebf733effb37f3ff3b
    """,
)

private val SAMPLED_4X2_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a07
    0706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb0043010304
    0405040509050509140d0b0d141414141414141414141414141414141414141414141414141414141414141414141414
    1414141414141414141414141414ffc0001108000d001303014200021101031101ffc4001f0000010501010101010100
    000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221
    31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a
    434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596979899
    9aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1
    f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100
    020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015
    6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869
    6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4
    c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00f9
    a7c33f017fb0f64fe4f9fbbe4dbb76fbe7bfa57adf863e02fdab6ea3e4edc7cfe56dcfdded9fc3d2bd16dbe17fee13fd
    03b7f7bffad457a97863e1969ba1ec9f3e7eef936ecdb8ef9ea7d2bd6bc31f0cb4dbad9a8e76e3e7f2b667eef6ce7dbd
    2bd16dfc39a6f909fe8ddbd7ff00ad457c4e77e22d5cbb116fadbc27d9b727b5bf2e9c9d7f877b737dbbdfa1f59e17f8
    8f5a793ad1cb6f2f9fccffd9
    """,
)

private val SAMPLED_4X2_REFERENCE: ByteArray = hex(
    """
    0b001c0d001e120523180b2930008845099d45099d4e12a67d067c840d83770076780177b10043b70049b70049cd155f
    eb00c2ef01c6f305ca1609271b0e2c1f12302215333a00925216aa5519ad5b1fb38c158b8e178d80097f830c82bc044e
    c20a54c60e58de2670f608cdfb0dd2ff11d6082a120f311913351d12341c3f138455299a5b2fa05e32a38b2f7a8a2e79
    812570862a75ac2939ae2b3bb22f3fc64353f125b8f529bcf92dc010321a1739211e402823452d5226976135a6693dae
    6d41b29a3e89963a858e327d8e327db43141bb3848c64353d55262fd31c4ff36c9ff3acd085bb7095cb80c5fbb0f62be
    4b5561454f5b46505c49535f6e4567784f7189608292698bb55f90b76192b55f90ad5788e55520e95924ed5d281164c0
    1164c0176ac62073cf5e6874505a66555f6b58626e7d5476855c7e976e909b7294bd6798c46e9fc973a4bb6596f1612c
    f66631fa6a35087ebb0b81be1086c3168cc94b8c523b7c4242834943844a7174617679668c8f7c929582b9819cbc849f
    c189a4af7792e6801cea8420ee88241389c6198fcc1d93d02096d355965c48894f5293595192588083707f826f969986
    9ea18ec48ca7c78faad098b3c189a4f18b27f6902cfa94300fa61d12a92018af261db42b39998e4eaea34fafa459b9ae
    85ad798db58180a87480a874b2a33eb8a944b8a944cebf5aeea5c3f3aac8f7aecc19b0271db42b22b93025bc3342a297
    5bbbb05dbdb263c3b892ba8694bc8887af7b8ab27ebcad48c1b24dc5b651decf6afbb2d0ffb6d4ffbad80dd31a13d920
    16dc2316dc232ecd7145e4884ae98d4ceb8f81e36c80e26b77d9627cde67b4cf44b5d045bad54acfea5fe9d4b5edd8b9
    f1dcbd11d71e17dd241de32a23e9303ddc804ceb8f54f39757f69a8cee7788ea7381e36c81e36cb8d348bed94ecae55a
    d9f469f5e0c1fae5c6fee9ca19fbcb1afccc1effd021ffd342ff583cff523eff5442ff5870ef627cfb6e8dff7f96ff88
    c8fca7cafea9c8fca7c1f5a0e8fa2aecfe2ef0ff32
    """,
)

private val SAMPLED_2X4_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a07
    0706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb0043010304
    0405040509050509140d0b0d141414141414141414141414141414141414141414141414141414141414141414141414
    1414141414141414141414141414ffc0001108000d001303012400021101031101ffc4001f0000010501010101010100
    000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221
    31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a
    434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596979899
    9aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1
    f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100
    020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015
    6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869
    6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4
    c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00f9
    a7c33f017fb0f64fe4f9fbbe4dbb76fbe7bfa57adf863e02fdab6ea3e4edc7cfe56dcfdded9fc3d2be97f0c7c32d3743
    d93e7cfddf26dd9b71df3d4fa57ad7863e1969b75b351cedc7cfe56ccfdded9cfb7a51451457c5673c4199c332af95aa
    cf929d3751691f895beca5ecd6ff0014611a9fdfdeff0087787dc719ef2539fb77abb75ff3bfe363e53b6f85ff00b84f
    f40edfdeff00eb515f78dbf8734df213fd1bb7afff005a8a28a28afe7a7e2bf11ddfef3ff2a623ff00971fd9b4f8bf3a
    e45fbf7b765fe47fffd9
    """,
)

private val SAMPLED_2X4_REFERENCE: ByteArray = hex(
    """
    00091e000b200c0c0c12121228015a3d166f4901ba520ac36806b16f0db8810c1e820d1fa10a0fa71015b2005ac81370
    d50cb4d910b8ea0eb800142904192e1919191c1c1c320b644a237c5911ca5f17d07715c07917c28a15278d182aac151a
    b21b20c10c69d92481e017bfe51cc4f61ac40c213613283d2828282727273e1770542d86661ed76921da8422cd8321cc
    9924369e293bbb2429bd262bcc1774e02b88ed24ccf128d0ff26d014293e1b3045333333383838512a83603992742ce5
    7830e99331dc8f2dd8a63143a63143c32c31ca3338e02b88ef3a97f930d8fe35ddff33dd0067a60068a70762bf0a65c2
    266666206060445f104762135f5c17696621815bb08a64b99e64b0a066b2af6a65a7625dd75e15db6219ea61190070af
    0070af126dca1b76d33979792b6b6b536e1f5671226e6b2676732e8f69be936dc2a66cb8ad73bfc37e79b5706be36a21
    e86f26f76e260c7cbb0f7fbe207bd82681de4585853575755f7a2b607b2c7b7833807d389d77cca37dd2b57bc7b87eca
    ce8984bc7772f0772ef47b32ff7a321787c61d8dcc2d88e5308be84f8f8f4282826f8a3b6e893a8a8742898641a781d6
    af89dec086d2c389d5dd9893ce8984fb8239ff873eff863e00b12200b42511b70b16bc1028ab5b3dc07045b4a34fbead
    62b6a66abeae7eba1c7eba1ca4b01caab622b8a650cebc66d3b5b7d8babce7b9bb01bb2c05bf301bc1151ec41831b464
    4acd7d53c2b159c8b76fc3b371c5b585c12388c426aeba26b3bf2bc5b35ddecc76e0c2c4e4c6c8f3c5c711cb3c17d142
    2bd1252bd12540c37357da8a63d2c165d4c37fd3c37ed2c296d2349bd739bfcb37c0cc38d3c16be8d680ecced0f0d2d4
    ffd1d315cf401bd54632d82c38de324fd2825ee1916ddccb70dfce8adece86dacaa0dc3ea0dc3ec3cf3bc9d541e3d17b
    f2e08af8dadcfddfe1ffdee007ffcb08ffcc1dfedc20ffdf3eff7538fd6f5efc2362ff2773fb1f7fff2b94f8d49dffdd
    b1ffd3b3ffd5c4ff75bdff6eeafb1feeff23fdfe24
    """,
)

private val SAMPLED_CHROMA_FINER_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a07
    0706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb0043010304
    0405040509050509140d0b0d141414141414141414141414141414141414141414141414141414141414141414141414
    1414141414141414141414141414ffc0001108000d001303011100022201031101ffc4001f0000010501010101010100
    000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221
    31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a
    434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596979899
    9aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1
    f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100
    020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015
    6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869
    6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4
    c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00d0
    f865fb18d80f2a2fb34236e1fecfb87c9fedeeeff4f7afd23178eff50af99fb4f6fcff00bbe5b725afef5ef797f2dad6
    ebbe87c662f1dfdab7e2ef69cbcbfbcf676bff000b4b73dd7c5c9bf2e97d9db5fcab16eb7015f33e7f6fcffbbe5b725a
    fef5ef797f2dad6ebbe87c662dd6cd7fe32ee7e5b7ef3d9daffc2d2dcf75f1726fcba5f676d7e27c3ef126b7b92e67af
    5efe56fd4f758bf643b0f2d3fd3a1e83fe580afd865e34fbcffd97ff002a7ff6807f3bcbc5cadccffd93ff002a7ff680
    7f61d3f112b722f71eddcfffd9
    """,
)

private val SAMPLED_CHROMA_FINER_REFERENCE: ByteArray = hex(
    """
    0e092710073215092f1f0f2c2f16333f01c45304cf6509c47805d28700c9900e2a9b0533ac0928b70e25c41534d000cc
    e306cfed09cef80bcd0c12360f142717142f211e19322038420cc25210c96414c17613c5830bc58f1a2c9b122cab1230
    b81727c5212cd20ac2e312c5ee15c6f916c50829300d2c24182f1d263226353533441dd25428c36228d27029c77e22c3
    8c332d992d20a92928b72b2ec73234d41dc5e426c6f028c8fa2ac60648240d4538194a2a284c26374b304534b65438c0
    6239c36e37c67c36ba8a4f2399492ea84726b84437c64c23d533bce535c6f036c5f939c40648d00c4fb71950c4274dca
    3548c0455d39555c30635b2a6f5e2a7c5f358a4ccb9951c1a74bc8b64cbac647cad46132e15f2bed5d28f65f260867c5
    0e67cb1b6acf2668c93560be45761d566a2b666b1b726a297d732c8d66c19a6acea969c7b766c2c662bcd47729e06f2b
    eb6c27f66d250c7dc1127ec91b84be277bd33776b946872d587f24687b2a757e2d82872b8f79c19e81c1aa80c2b87bc9
    c776c3d58c2de3832ded7f28f881270b89d3118dc11c90bf298ec43888c3479e35589b27679733759b2c839f318f89c5
    9e8fbaab8bcabb89d2c889c2d5a32ae39d2def9b29f89d2807b2250db02f1bb02e2bb32b3ab6304a9fbe56a1c862a6bb
    72a4c97fa2c08cb6249caf31adaf28bab124c7b52dd49ec2e4a0c5efa2c4f9a4c305bf360cc1271bbe3129c51a3bc939
    48b6c553bdcb5fc3c36fc2c87eb9c78bc72b9abd2dadbb30bac02ac5c92cd3b5c1e3bbc4eebfc5f8c0c304d7300bd823
    17d81b28da2439df3346c8d550d3c15dd4d06dd6c87dcec58cdc2b9ad61facd32ab9d630c4de33d1cac4e1d0c6eed4c7
    f8d4c405f3230ced3719f02627f32239f52f47deb552dfbc5fe1bf70e1c57fdfba8df4219bec2babee24baee34c4f621
    d1dcbadfdec2eddfc2f5e1c009ecd010f1b51cf0c029edc73aebbf47ff3856fc2c63fc2674ff2682ff348fe8ca9eedbe
    aeecc5baf0b6c6e9c8d2ff33e2ff28ecff25f5ff23
    """,
)

private val SAMPLED_MIXED_JPEG: ByteArray = hex(
    """
    ffd8ffe000104a46494600010100000100010000ffdb0043000302020302020303030304030304050805050404050a07
    0706080c0a0c0c0b0a0b0b0d0e12100d0e110e0b0b1016101113141515150c0f171816141812141514ffdb0043010304
    0405040509050509140d0b0d141414141414141414141414141414141414141414141414141414141414141414141414
    1414141414141414141414141414ffc0001108000d001303012200021201032101ffc4001f0000010501010101010100
    000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411051221
    31410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a
    434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596979899
    9aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1
    f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b51100
    020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015
    6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a63646566676869
    6a737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4
    c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00f9
    a7c33f017fb0f64fe4f9fbbe4dbb76fbe7bfa57adf863e02fdab6ea3e4edc7cfe56dcfdded9fc3d2be97f0c7c32d3743
    d93e7cfddf26dd9b71df3d4fa57ad7863e1969b75b351cedc7cfe56ccfdded9cfb7a57d7e6d8ef637e1ff697f61fbfe7
    b7c56d39396fa7c5f1733db63f9db3675a8df87f9efec3f7fcf6f8ada7272df4f8be2e67b6c7f3ff000ff8955aa72ae6
    73f2dbe67f4bf0f7895595a1ccd7f77f4bf99f29db7c2ffdc27fa076fef7ff005a8afbc6dfc39a6f909fe8ddbd7ffad4
    57e8efc69d7fdd7ff2a7ff00687f3b3f172b5ffdd3ff002a7ff687ee943c44adeca3ee3fbc0fffd9
    """,
)

private val SAMPLED_MIXED_REFERENCE: ByteArray = hex(
    """
    00012704022917002f2e004036005a440e8a4706ac5b06c56504c37a0ba58b004d9b0027a4042abb003cc50055ca0d8b
    dd07b4eb03c9f902cc03141e0d16251d132b2e0c3f360a634b1d97521ab36218c77017cb8019a88b0d4c9f0f2aa91330
    bd0f44ca0a60d72293e318b2f215c5ff14c9012929102d2b202d2426263030235b4a3093582cb56228ca7529d1802aa7
    8b28479f2d23af2a2db62542be2166d42f98e62bb6f229c7ff28cb003e1e0940231a43232343383141634c4396613ebe
    6a3dce7e40c9843f9c86404b944024a73e20b14133c0455add468bea3eb4f73ccbff3bcd0558ce1651cf244fcf2956bb
    31598d42505b5550305c551f6e51297c52548b58a39f56ccb652ceb957bec15795d44a64dc5537ea5924f85826006ec2
    0e65c42065cd2c70c138759443625c5d643b64692676682180694a8b6fa9966accad66c2b870afc97889db6156e06834
    ef6c28fc6b2a0080ce0f7dcc227cc82d85b33b898d4274575b793d647a28787c277d7b4a8d86a59c82c9b080beb884ab
    cd8791d56e5be37c39f17f29ff7e2b0491c61592c82791c13194b13d97953f896459944460932c75962e778d4c9096a2
    a294c9b190c5be92b5d39b9ad58b64de9137ed9625fb952700ac2608ac291bab302ba84033a65b4db38b56aaac6babc6
    74a7c287b1a58ca94d99aa26a7ad29b8ab3bc1a156d5b28ce8abb2f7a8c8ffa7cb03bd1c0abf221bbd292cb63d33b461
    49c99650c4b162c1c56dc0c87dc2a58bb64a9cba28a6bd2ebab841c4b45dd2cd93e4c3b2f2bfc6ffbec803d52a0bdc2b
    1cd9232dce3036cd5c48e09551ddb661d5ca6fd8d17bd9a78ed247a2d723a8da2db9ce41c8c866cce099e4d7b5f0d4c6
    ffd3ca00e51b01ea1d15ea1e2ce5323ae45f46ed9155ebbb66e5ca74ebc57aea988be54896e6219cea1cb6e32ecde456
    d1f287e9e9b3f6e7caffe5cc0fffd019fcd12bf9d33df9bf44fb8f45fa5d52ff3462ff256dfb2b7dfe599bfda8abfcd1
    b5fed1c9fbc0d8f799d2f867e1fb38eeff25fbfe27
    """,
)
