package com.rohittp.reng.internal.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Every fixture below is generated once via CPython's zlib/struct modules and pasted as a byte
// literal, so every target's test task asserts against the exact same bytes rather than
// recomputing an expectation with the same container-walk code under test. Regenerate with:
//
// python3 - <<'PY'
// import zlib, struct
// SIG = b"\x89PNG\r\n\x1a\n"
// def chunk(kind, payload):
//     return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)
// def ihdr_payload(w, h, depth, colour, compression=0, filt=0, interlace=0):
//     return struct.pack(">IIBBBBB", w, h, depth, colour, compression, filt, interlace)
// def build(w=2, h=2, depth=8, colour=2, compression=0, filt=0, interlace=0,
//           pre_ihdr_chunks=(), post_ihdr_chunks=(), idat_chunks=None, include_iend=True,
//           ihdr_payload_override=None):
//     out = SIG
//     for c in pre_ihdr_chunks:
//         out += c
//     payload = ihdr_payload_override if ihdr_payload_override is not None else ihdr_payload(w, h, depth, colour, compression, filt, interlace)
//     out += chunk(b"IHDR", payload)
//     for c in post_ihdr_chunks:
//         out += c
//     if idat_chunks is None:
//         raw = b"".join(b"\x00" + bytes(row) for row in ([255, 0, 0, 0, 255, 0], [0, 0, 255, 255, 255, 255]))
//         idat_chunks = [zlib.compress(raw)]
//     for payload_bytes in idat_chunks:
//         out += chunk(b"IDAT", payload_bytes)
//     if include_iend:
//         out += chunk(b"IEND", b"")
//     return out
// # ... see task-4-report.md for the full script that emits every named fixture below.
// PY
class PngContainerTest {
    @Test
    fun admitsAMinimalEightBitTruecolourImage() {
        val scan = assertIs<PngScan.Admitted>(scanPng(rgb8TwoByTwo))
        assertEquals(PngHeader(2, 2, 8, 2, 0), scan.header)
        assertEquals(1, scan.imageDataRanges.size)
    }

    @Test
    fun admitsImageDataSplitAcrossSeveralChunks() {
        val scan = assertIs<PngScan.Admitted>(scanPng(rgb8SplitImageData))
        assertEquals(3, scan.imageDataRanges.size)
    }

    @Test
    fun skipsAncillaryChunksButStillValidatesTheirCrc() {
        assertIs<PngScan.Admitted>(scanPng(rgb8WithAncillaryChunks))
        assertEquals(PngReject.CHUNK_CRC, rejectionOf(rgb8WithBadAncillaryCrc))
    }

    @Test
    fun rejectsEveryMalformedShape() {
        assertEquals(PngReject.SIGNATURE, rejectionOf(wrongSignature))
        assertEquals(PngReject.IHDR_NOT_FIRST, rejectionOf(ihdrNotFirst))
        assertEquals(PngReject.IHDR_LENGTH, rejectionOf(ihdrWrongLength))
        assertEquals(PngReject.IEND_NOT_LAST, rejectionOf(iendNotLast))
        assertEquals(PngReject.TRAILING_BYTES, rejectionOf(trailingAfterIend))
        assertEquals(PngReject.CHUNK_LENGTH, rejectionOf(chunkLengthPastEnd))
        assertEquals(PngReject.CHUNK_CRC, rejectionOf(badCriticalCrc))
        assertEquals(PngReject.UNKNOWN_CRITICAL_CHUNK, rejectionOf(unknownCriticalChunk))
        assertEquals(PngReject.COMPRESSION_METHOD, rejectionOf(compressionMethodOne))
        assertEquals(PngReject.FILTER_METHOD, rejectionOf(filterMethodOne))
        assertEquals(PngReject.ZERO_DIMENSION, rejectionOf(zeroWidth))
        assertEquals(PngReject.PALETTE_MISSING, rejectionOf(colourTypeThreeWithoutPlte))
        assertEquals(PngReject.PALETTE_FORBIDDEN, rejectionOf(greyscaleWithPlte))
    }

    @Test
    fun reportsOutOfSubsetFeaturesAsUnsupportedRatherThanMalformed() {
        assertIs<PngScan.Unsupported>(scanPng(sixteenBitGreyscale))
        assertIs<PngScan.Unsupported>(scanPng(paletteAtBitDepthFour))
        assertIs<PngScan.Unsupported>(scanPng(adam7Interlaced))
        // An APNG carries acTL/fcTL/fdAT, which are ancillary, so it decodes as its base frame.
        assertIs<PngScan.Admitted>(scanPng(apngBaseFrame))
    }

    private fun rejectionOf(bytes: ByteArray): PngReject = when (val scan = scanPng(bytes)) {
        is PngScan.Malformed -> scan.reason
        is PngScan.Unsupported -> scan.reason
        is PngScan.Admitted -> error("unexpectedly admitted")
    }

    // rgb8TwoByTwo: png(2, 2, 8, 2, zlib.compress(raw)) — a minimal admitted 2x2 8-bit truecolour image.
    private val rgb8TwoByTwo: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Same image, its zlib stream split across three IDAT chunks (sizes 6, 6, 6).
    private val rgb8SplitImageData: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 6, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -122, 51, 47, -55, 0, 0, 0, 6, 73, 68, 65, 84, -64, 0, -62, 12, -1, -127, -3, -37, -106, 89, 0, 0, 0, 6, 73, 68, 65, 84, 0, 0, 31, -18, 5, -5, -52, -24, 124, 114, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Adds a gAMA and a tEXt ancillary chunk (both correctly CRC'd) between IHDR and IDAT.
    private val rgb8WithAncillaryChunks: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 4, 103, 65, 77, 65, 0, 0, -79, -113, 11, -4, 97, 5, 0, 0, 0, 13, 116, 69, 88, 116, 67, 111, 109, 109, 101, 110, 116, 0, 104, 101, 108, 108, 111, -26, -1, -82, 36, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Same as above with the tEXt chunk's last CRC byte flipped.
    private val rgb8WithBadAncillaryCrc: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 4, 103, 65, 77, 65, 0, 0, -79, -113, 11, -4, 97, 5, 0, 0, 0, 13, 116, 69, 88, 116, 67, 111, 109, 109, 101, 110, 116, 0, 104, 101, 108, 108, 111, -26, -1, -82, -37, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // First signature byte flipped from 0x89.
    private val wrongSignature: ByteArray = byteArrayOf(0, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // A correctly-CRC'd gAMA chunk placed before IHDR.
    private val ihdrNotFirst: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 4, 103, 65, 77, 65, 0, 0, -79, -113, 11, -4, 97, 5, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // IHDR declared length 12 (one byte short of the required 13), correctly CRC'd over that payload.
    private val ihdrWrongLength: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 12, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, -71, 9, 121, 60, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // No IEND chunk at all: signature + IHDR + IDAT, then the buffer just ends.
    private val iendNotLast: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117)

    // A fully valid PNG with four junk bytes appended after IEND.
    private val trailingAfterIend: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126, -34, -83, -66, -17)

    // IDAT's declared length corrupted to run far past the buffer's actual end.
    private val chunkLengthPastEnd: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 1, -122, -78, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // IDAT's CRC byte flipped (a critical chunk).
    private val badCriticalCrc: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, -12, -39, 104, -117, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // An unknown critical chunk type "TEST" (uppercase first letter), correctly CRC'd, before IDAT.
    private val unknownCriticalChunk: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 4, 84, 69, 83, 84, 1, 2, 3, 4, 114, -116, 79, 8, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Compression method 1 instead of 0.
    private val compressionMethodOne: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 1, 0, 0, -4, 22, -16, 68, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Filter method 1 instead of 0.
    private val filterMethodOne: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 1, 0, -28, -49, -85, 50, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Width declared as 0.
    private val zeroWidth: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 0, 0, 0, 0, 2, 8, 2, 0, 0, 0, -7, 33, 74, 78, 0, 0, 0, 8, 73, 68, 65, 84, 120, -100, 3, 0, 0, 0, 0, 1, 72, 6, -119, -46, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Colour type 3 (palette) with no PLTE chunk present anywhere.
    private val colourTypeThreeWithoutPlte: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 3, 0, 0, 0, 69, 104, -3, 22, 0, 0, 0, 11, 73, 68, 65, 84, 120, -100, 99, 96, 96, 0, 0, 0, 3, 0, 1, -72, -83, 58, 99, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Colour type 0 (greyscale) with a PLTE chunk present, which is forbidden.
    private val greyscaleWithPlte: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 0, 0, 0, 0, 87, -35, 82, -8, 0, 0, 0, 6, 80, 76, 84, 69, -1, 0, 0, 0, -1, 0, -46, -121, -17, 113, 0, 0, 0, 11, 73, 68, 65, 84, 120, -100, 99, 96, 96, 0, 0, 0, 3, 0, 1, -72, -83, 58, 99, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Bit depth 16, otherwise a valid greyscale image — outside the accepted subset, not malformed.
    private val sixteenBitGreyscale: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 16, 0, 0, 0, 0, 7, 77, -114, -69, 0, 0, 0, 11, 73, 68, 65, 84, 120, -100, 99, 96, 0, 2, 0, 0, 5, 0, 1, 122, 94, -85, 63, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Colour type 3 (palette) at bit depth 4, with a valid PLTE chunk present — bit depth rejects first.
    private val paletteAtBitDepthFour: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 4, 3, 0, 0, 0, -128, -104, 16, 23, 0, 0, 0, 6, 80, 76, 84, 69, -1, 0, 0, 0, -1, 0, -46, -121, -17, 113, 0, 0, 0, 10, 73, 68, 65, 84, 120, -100, 99, 96, 0, 0, 0, 2, 0, 1, 72, -81, -92, 113, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Interlace method 1 (Adam7), otherwise a valid truecolour image.
    private val adam7Interlaced: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 1, -118, -45, -86, -27, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // IHDR, acTL, fcTL, IDAT (base frame), fdAT (a second frame), IEND — acTL/fcTL/fdAT are all
    // ancillary (lowercase first letter) so the base frame decodes normally.
    private val apngBaseFrame: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 8, 97, 99, 84, 76, 0, 0, 0, 2, 0, 0, 0, 0, -13, -115, -109, 112, 0, 0, 0, 30, 102, 99, 84, 76, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 100, 0, 0, 41, 4, -55, -93, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, 11, -39, 104, -117, 0, 0, 0, 22, 102, 100, 65, 84, 0, 0, 0, 1, 120, -100, 99, -8, -49, -64, -64, 0, -62, 12, -1, -127, 0, 0, 31, -18, 5, -5, -103, 12, -70, 92, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)
}
