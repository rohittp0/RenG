package com.rohittp.reng.internal.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        assertEquals(PngReject.DIMENSION_OUT_OF_RANGE, rejectionOf(hugeWidthWrapsNegative))
        assertEquals(PngReject.ZERO_DIMENSION, rejectionOf(zeroWidth))
        assertEquals(PngReject.PALETTE_MISSING, rejectionOf(colourTypeThreeWithoutPlte))
        assertEquals(PngReject.PALETTE_FORBIDDEN, rejectionOf(greyscaleWithPlte))
        assertEquals(PngReject.TRNS_LENGTH, rejectionOf(greyWithZeroByteTrns))
        assertEquals(PngReject.TRNS_LENGTH, rejectionOf(rgbWithFourByteTrns))
        assertEquals(PngReject.DUPLICATE_CRITICAL_CHUNK, rejectionOf(duplicateIhdr))
        assertEquals(PngReject.DUPLICATE_CRITICAL_CHUNK, rejectionOf(duplicatePlte))
        assertEquals(PngReject.IEND_LENGTH, rejectionOf(nonEmptyIend))
    }

    @Test
    fun reportsOutOfSubsetFeaturesAsUnsupportedRatherThanMalformed() {
        assertIs<PngScan.Unsupported>(scanPng(sixteenBitGreyscale))
        assertIs<PngScan.Unsupported>(scanPng(paletteAtBitDepthFour))
        assertIs<PngScan.Unsupported>(scanPng(adam7Interlaced))
        // An APNG carries acTL/fcTL/fdAT, which are ancillary, so it decodes as its base frame.
        assertIs<PngScan.Admitted>(scanPng(apngBaseFrame))
    }

    @Test
    fun rejectsEveryUnsupportedReasonSpecifically() {
        // The three assertIs checks above confirm the classification bucket; this pins the exact
        // PngReject each fixture reports, including colour type, which none of the fixtures above
        // happens to exercise (bit depth wins first on paletteAtBitDepthFour).
        assertEquals(PngReject.BIT_DEPTH, rejectionOf(sixteenBitGreyscale))
        assertEquals(PngReject.BIT_DEPTH, rejectionOf(paletteAtBitDepthFour))
        assertEquals(PngReject.INTERLACE, rejectionOf(adam7Interlaced))
        assertEquals(PngReject.COLOUR_TYPE, rejectionOf(invalidColourType))
    }

    private fun rejectionOf(bytes: ByteArray): PngReject = when (val scan = scanPng(bytes)) {
        is PngScan.Malformed -> scan.reason
        is PngScan.Unsupported -> scan.reason
        is PngScan.Admitted -> error("unexpectedly admitted")
    }

    // Positive counterpart to rejectsADuplicateTrnsChunk below: a single, correctly-sized tRNS chunk
    // must still be admitted with its bytes captured intact. Without this, a fix that rejected every
    // tRNS (not just a second one) would also pass the negative test for the wrong reason.
    @Test
    fun admitsASingleTrnsChunk() {
        val scan = assertIs<PngScan.Admitted>(scanPng(rgb8SingleTrns))
        assertContentEquals(byteArrayOf(0, 10, 0, 20, 0, 30), scan.transparency)
    }

    // A second tRNS chunk after the first. Before this fix, scanPng captured whichever tRNS came last,
    // silently discarding the first — so the exact same bytes could render differently depending on
    // chunk order, which is exactly the ambiguity DUPLICATE_CRITICAL_CHUNK exists to remove for IHDR
    // and PLTE. tRNS is ancillary (its type code's case bit is set), not critical, so this must NOT
    // reuse DUPLICATE_CRITICAL_CHUNK — that would misreport an ancillary duplicate as a critical one.
    @Test
    fun rejectsADuplicateTrnsChunk() {
        assertEquals(PngReject.DUPLICATE_ANCILLARY_CHUNK, rejectionOf(rgb8DuplicateTrns))
    }

    // Positive counterpart to rejectsAPaletteChunkAfterImageData below: PLTE correctly placed before
    // the first IDAT must still be admitted with its bytes captured intact. Without this, a fix that
    // rejected PLTE regardless of position would also pass the negative test for the wrong reason.
    @Test
    fun admitsAPaletteChunkBeforeImageData() {
        val scan = assertIs<PngScan.Admitted>(scanPng(paletteBeforeIdat))
        assertContentEquals(byteArrayOf(-1, 0, 0, 0, -1, 0), scan.palette)
    }

    // A PLTE chunk appearing after the first IDAT. The specification requires PLTE to precede image
    // data; previously this was captured and used regardless of position. This is colour type 2
    // (truecolour), where a PLTE is only ever a suggested palette — never required for a valid decode
    // — so the fixture stays a perfectly decodable image but for this one ordering violation, proving
    // the rejection is about position, not about whether the file could otherwise decode.
    @Test
    fun rejectsAPaletteChunkAfterImageData() {
        assertEquals(PngReject.PALETTE_AFTER_IMAGE_DATA, rejectionOf(paletteAfterIdat))
    }

    // A PLTE that is BOTH a duplicate (a first, correctly placed PLTE already exists) AND
    // mispositioned (this second one comes after the first IDAT). PngContainer.kt's PLTE branch checks
    // position before checking duplicate, so this pins PALETTE_AFTER_IMAGE_DATA as the winner —
    // matching the precedent this file already sets in rejectsEveryUnsupportedReasonSpecifically ("bit
    // depth wins first on paletteAtBitDepthFour"). The two codes are not otherwise distinguishable in
    // consequence (either way the fix is deleting the second PLTE), but an untested precedence is
    // incidental rather than deliberate and one accidental if-reorder away from flipping silently.
    @Test
    fun positionWinsOverDuplicateWhenAPlteIsBothMispositionedAndDuplicated() {
        assertEquals(PngReject.PALETTE_AFTER_IMAGE_DATA, rejectionOf(duplicatePlteAfterImageData))
    }

    // colour type 2 (truecolour), 1x1, a single correctly-CRC'd 6-byte tRNS chunk before IDAT.
    private val rgb8SingleTrns: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, -112, 119, 83, -34, 0, 0, 0, 6, 116, 82, 78, 83, 0, 10, 0, 20, 0, 30, -59, 54, 41, -1, 0, 0, 0, 12, 73, 68, 65, 84, 120, -100, 99, -32, 18, -111, 3, 0, 0, 104, 0, 61, 84, 8, -93, -9, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Same as rgb8SingleTrns, with a second, differently-valued tRNS chunk appended right after the
    // first (both correctly CRC'd) — so "last one wins" would be observably different from the first.
    private val rgb8DuplicateTrns: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, -112, 119, 83, -34, 0, 0, 0, 6, 116, 82, 78, 83, 0, 10, 0, 20, 0, 30, -59, 54, 41, -1, 0, 0, 0, 6, 116, 82, 78, 83, 0, 99, 0, 98, 0, 97, -63, -46, 68, -116, 0, 0, 0, 12, 73, 68, 65, 84, 120, -100, 99, -32, 18, -111, 3, 0, 0, 104, 0, 61, 84, 8, -93, -9, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // colour type 3 (palette), 1x1, a two-entry PLTE chunk correctly placed before IDAT.
    private val paletteBeforeIdat: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 3, 0, 0, 0, 40, -53, 52, -69, 0, 0, 0, 6, 80, 76, 84, 69, -1, 0, 0, 0, -1, 0, -46, -121, -17, 113, 0, 0, 0, 10, 73, 68, 65, 84, 120, -100, 99, 96, 0, 0, 0, 2, 0, 1, 72, -81, -92, 113, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // colour type 2 (truecolour), 1x1, a real IDAT followed by a PLTE chunk — PLTE here is a suggested
    // palette per specification, never required for a valid truecolour decode, but its position after
    // the first IDAT is itself the violation.
    private val paletteAfterIdat: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, -112, 119, 83, -34, 0, 0, 0, 12, 73, 68, 65, 84, 120, -100, 99, -32, 18, -111, 3, 0, 0, 104, 0, 61, 84, 8, -93, -9, 0, 0, 0, 6, 80, 76, 84, 69, 1, 2, 3, 4, 5, 6, -107, 83, 111, 72, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Same base image as paletteAfterIdat, but with a first PLTE correctly placed before IDAT too, so
    // the second PLTE (after IDAT) is both a duplicate and mispositioned.
    private val duplicatePlteAfterImageData: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, -112, 119, 83, -34, 0, 0, 0, 6, 80, 76, 84, 69, 1, 2, 3, 4, 5, 6, -107, 83, 111, 72, 0, 0, 0, 12, 73, 68, 65, 84, 120, -100, 99, -32, 18, -111, 3, 0, 0, 104, 0, 61, 84, 8, -93, -9, 0, 0, 0, 6, 80, 76, 84, 69, 7, 8, 9, 10, 11, 12, 18, -49, -99, 10, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

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

    // Width declared as 0x80000000 (2^31) — a value which, as an unchecked 32-bit reinterpretation,
    // wraps to a negative Int. height=1, depth=8, colour=2 (truecolour), otherwise a complete, validly
    // CRC'd 1x1 image (real IDAT/IEND included, though scanPng rejects from inside IHDR parsing before
    // it ever needs them).
    private val hugeWidthWrapsNegative: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, -128, 0, 0, 0, 0, 0, 0, 1, 8, 2, 0, 0, 0, -33, -33, 29, -9, 0, 0, 0, 12, 73, 68, 65, 84, 120, -38, 99, 96, 100, 98, 6, 0, 0, 14, 0, 7, -23, -110, 55, -44, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Colour type 0 (greyscale) with a 0-byte tRNS chunk; colour type 0 requires exactly 2 bytes.
    private val greyWithZeroByteTrns: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0, 58, 126, -101, 85, 0, 0, 0, 0, 116, 82, 78, 83, 54, -71, 112, -52, 0, 0, 0, 10, 73, 68, 65, 84, 120, -38, 99, 72, 1, 0, 0, 102, 0, 101, -41, 40, -68, 31, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // Colour type 2 (truecolour) with a 4-byte tRNS chunk; colour type 2 requires exactly 6 bytes.
    private val rgbWithFourByteTrns: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, -112, 119, 83, -34, 0, 0, 0, 4, 116, 82, 78, 83, 0, 1, 2, 3, 25, 110, 63, -107, 0, 0, 0, 12, 73, 68, 65, 84, 120, -38, 99, -32, 18, -111, 3, 0, 0, 104, 0, 61, 106, -11, 112, 91, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    // A second IHDR chunk after the first (before IEND). This otherwise falls through to the generic
    // isCritical() catch-all and misreports as UNKNOWN_CRITICAL_CHUNK — IHDR is the best-known chunk in
    // the format, not an unknown one.
    private val duplicateIhdr: ByteArray = byteArrayOf(
        -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2,
        8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0,
        2, 8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126
    )

    // Colour type 3, two PLTE chunks. Previously the second silently overwrote `palette`, so the
    // rendered output would silently change depending on which PLTE "won" — the specification permits
    // only one.
    private val duplicatePlte: ByteArray = byteArrayOf(
        -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1,
        8, 3, 0, 0, 0, 40, -53, 52, -69, 0, 0, 0, 3, 80, 76, 84, 69, -1, 0, 0, 25, -30, 9, 55,
        0, 0, 0, 3, 80, 76, 84, 69, 0, -1, 0, 52, 94, -64, -88, 0, 0, 0, 0, 73, 69, 78, 68, -82,
        66, 96, -126
    )

    // A complete, validly-CRC'd 1x1 truecolour file whose IEND carries a 1-byte payload; the
    // specification requires IEND's payload exactly empty.
    private val nonEmptyIend: ByteArray = byteArrayOf(
        -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1,
        8, 2, 0, 0, 0, -112, 119, 83, -34, 0, 0, 0, 12, 73, 68, 65, 84, 120, -38, 99, -32, 18, -111, 3,
        0, 0, 104, 0, 61, 106, -11, 112, 91, 0, 0, 0, 1, 73, 69, 78, 68, 1, -90, 29, 127, 119
    )

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

    // Colour type 1, which is not one of the accepted {0, 2, 3, 4, 6}; every other field is valid.
    private val invalidColourType: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2, 8, 1, 0, 0, 0, -17, 97, 53, -99, 0, 0, 0, 11, 73, 68, 65, 84, 120, -100, 99, 96, 96, 0, 0, 0, 3, 0, 1, -72, -83, 58, 99, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)
}
