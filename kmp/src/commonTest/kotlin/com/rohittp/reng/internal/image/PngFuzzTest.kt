package com.rohittp.reng.internal.image

import kotlin.test.Test
import kotlin.test.fail

/**
 * Property test for RenG's stated PNG-decoding contract: for ANY byte sequence whatsoever,
 * [decodePng] returns one of its typed [PngDecodeResult] outcomes and NEVER throws. Four inspection
 * passes over this decoder have each found a distinct crash-or-silent-failure class (an unchecked
 * palette index, a declared dimension of 2^31 or more wrapping to a negative `Int`, an unchecked
 * `tRNS` payload length for colour types 0 and 2, and the size-ceiling check's own multiplication
 * overflowing `Long`) — inspection clearly works, but it does not scale and it does not stay fixed: a
 * future edit can reintroduce any of those classes, or a new one nobody has thought of yet, and no
 * existing test would notice. This test asserts the contract itself, machine-checked on every build,
 * rather than re-deriving one inspection pass's specific findings as a fixed list of cases.
 *
 * It does not care WHICH typed result comes back for a given mutated input — [PngDecodeResult.Success],
 * [PngDecodeResult.Malformed], [PngDecodeResult.Unsupported], and [PngDecodeResult.TooLarge] are all
 * correct outcomes here. It cares only that one of them comes back and nothing is thrown; catching
 * [Throwable] (not merely [Exception]) is deliberate, since an uncaught [OutOfMemoryError] would be
 * just as real a contract violation as a [NullPointerException] or an
 * [kotlin.native.internal.KonanException] would be.
 *
 * Deterministic by construction: [Xorshift32] is a from-scratch, fixed-seed PRNG — no `Math.random()`,
 * no platform entropy source — so the exact same sequence of mutated inputs is generated on every
 * target (Android host, macOS ARM64) on every run, and any failure is reproducible from the printed
 * seed, mutation kind, iteration index, and exact byte array alone, with no dependence on machine
 * state.
 *
 * Mutation, not pure noise: a purely random byte sequence dies at the 8-byte signature check almost
 * every time, exercising nothing past the first comparison. This instead starts from real, valid PNGs
 * — copied byte-for-byte from fixtures already proven valid in [PngDecoderTest] — and applies
 * structured mutations chosen to land past the signature and actually inside the chunk grammar:
 * bit flips anywhere in the buffer; corrupted chunk length and CRC fields; corrupted IHDR fields
 * (bit depth, colour type, compression method, filter method, interlace, and — weighted heavily,
 * since two of the four bugs found so far lived exactly here — width and height, including every
 * value on both sides of the 2^31-1 admitted boundary) with that chunk's own CRC recomputed so the
 * mutation reaches `scanPng`'s field-specific validation instead of merely tripping a CRC mismatch;
 * truncation at chunk boundaries and at uniformly random offsets; duplicated, dropped, and reordered
 * chunks; and trailing garbage appended after a structurally complete file.
 */
class PngFuzzTest {

    @Test
    fun decodePngNeverThrowsForAnyMutatedInput() {
        val rng = Xorshift32(FUZZ_SEED)
        // A realistic ceiling — matching the `1L shl 20` convention this module's own tests already
        // use — so a mutated declared width/height can never legitimately request a huge allocation
        // and turn this into an accidental OOM stress test instead of a decoder-correctness test.
        val ceiling = 1L shl 20
        var exercised = 0

        for (seed in SEEDS) {
            val spans = chunkSpans(seed.bytes)
            for (iteration in 0 until ITERATIONS_PER_SEED) {
                val kind = rng.nextIntBound(MUTATION_KIND_NAMES.size)
                val candidate = mutate(rng, seed.bytes, spans, kind)
                exercised++
                try {
                    decodePng(candidate, ceiling)
                } catch (failure: Throwable) {
                    fail(
                        "decodePng threw ${failure::class.simpleName ?: "Throwable"} " +
                            "(\"${failure.message}\") for FUZZ_SEED=$FUZZ_SEED, seed=\"${seed.name}\", " +
                            "mutation=${MUTATION_KIND_NAMES[kind]} (kind $kind), iteration=$iteration. " +
                            "Reproducing input (${candidate.size} bytes): " +
                            candidate.joinToString(", ", prefix = "byteArrayOf(", postfix = ")"),
                    )
                }
            }
        }

        println(
            "PngFuzzTest.decodePngNeverThrowsForAnyMutatedInput: exercised $exercised mutated " +
                "inputs across ${SEEDS.size} seeds (FUZZ_SEED=$FUZZ_SEED, " +
                "$ITERATIONS_PER_SEED iterations/seed) — decodePng never threw.",
        )
    }
}

private const val FUZZ_SEED = 0x2463_9C5A
private const val ITERATIONS_PER_SEED = 50_000

/** One valid PNG's exact bytes plus a name, so a fuzz failure message says which seed it mutated. */
private class PngSeed(val name: String, val bytes: ByteArray)

// Every seed below is copied byte-for-byte from a fixture already proven a real, valid, correctly
// CRC'd PNG in PngDecoderTest (see that file's header comment for how they were generated and
// independently verified). Chosen to cover every colour type scanPng admits (0, 2, 3, 4, 6),
// including one with both PLTE and tRNS present (paletteWithTrns) and one with several rows of real
// filtered IDAT content to mutate (filterNoneFixture).
private val SEEDS: List<PngSeed> = listOf(
    // Colour type 0 (greyscale), 2x1, no ancillary chunks. Decodes as Success.
    PngSeed(
        "grey8TwoPixels",
        byteArrayOf(
            -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 1,
            8, 0, 0, 0, 0, -47, 73, 32, 86, 0, 0, 0, 11, 73, 68, 65, 84, 120, -38, 99, 112, 104, 0, 0,
            1, 3, 0, -63, 71, -123, -105, 29, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126,
        ),
    ),
    // Colour type 2 (truecolour), 2x2, no ancillary chunks. Decodes as Success.
    PngSeed(
        "rgb8Reference",
        byteArrayOf(
            -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 2,
            8, 2, 0, 0, 0, -3, -44, -102, 115, 0, 0, 0, 22, 73, 68, 65, 84, 120, -38, 99, 96, 100, 98, 102,
            97, 101, 99, 96, -25, -32, -28, -30, -26, 1, 0, 1, -113, 0, 79, 6, -88, -27, -118, 0, 0, 0, 0, 73,
            69, 78, 68, -82, 66, 96, -126,
        ),
    ),
    // Colour type 3 (palette), 2x1, PLTE plus tRNS both present. Decodes as Success. This seed gives
    // the fuzzer its only palette-bearing, tRNS-bearing input, so it covers colour-type-3 chunk-shape
    // and IHDR-field mutations generally. It does NOT reach widenToRgba's PALETTE_INDEX_OUT_OF_RANGE
    // path: that needs either altered IDAT/PLTE payload bytes with a fixed-up CRC (no mutation kind
    // touches those payloads while fixing the chunk's own CRC) or a dimension/colour-type
    // reinterpretation of this seed's bytes that still satisfies IMAGE_DATA_LENGTH, and this seed's
    // raw size (height*(width+1) = 3) has no positive-integer factorisation other than the original
    // (1, 2). The palette-index path is pinned instead by the hand-crafted deterministic fixture in
    // PngDecoderTest.rejectsAPaletteIndexOutOfRange.
    PngSeed(
        "paletteWithTrns",
        byteArrayOf(
            -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 2, 0, 0, 0, 1,
            8, 3, 0, 0, 0, -61, -4, -113, -72, 0, 0, 0, 6, 80, 76, 84, 69, -1, 0, 0, 0, 0, -1, 108,
            -95, -3, -114, 0, 0, 0, 2, 116, 82, 78, 83, -1, -128, 8, 15, -77, 106, 0, 0, 0, 11, 73, 68, 65,
            84, 120, -38, 99, 96, 96, 4, 0, 0, 4, 0, 2, 44, -34, 72, -83, 0, 0, 0, 0, 73, 69, 78, 68,
            -82, 66, 96, -126,
        ),
    ),
    // Colour type 2 (truecolour), 4x4, several real filtered scanlines — the richest IDAT body among
    // these seeds, giving chunk-shape mutations (duplicate/drop/reorder/truncate) a bigger IDAT chunk
    // to operate against. Decodes as Success.
    PngSeed(
        "filterNoneFixture",
        byteArrayOf(
            -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 4, 0, 0, 0, 4,
            8, 2, 0, 0, 0, 38, -109, 9, 41, 0, 0, 0, 60, 73, 68, 65, 84, 120, -38, 99, 96, 98, -27, 96,
            -26, 17, -30, 23, -109, -111, 84, 80, 99, 96, -48, 50, 48, 50, -79, -46, -75, 115, 49, -9, -16, 99, 112, 100,
            -108, -12, 102, 86, 14, -43, -43, -115, 55, 55, 103, -88, 101, -104, -40, 30, 53, 123, 98, -26, -46, -39, -59, -21,
            1, -66, 91, 11, 117, -64, -52, -47, -15, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126,
        ),
    ),
    // Colour type 4 (greyscale + alpha), 1x1, tRNS present — valid container, but decodes as
    // Malformed(TRNS_FORBIDDEN) since colour type 4 already carries a full alpha channel. Still a
    // real, valid, correctly CRC'd PNG, so a fine mutation seed: not every seed needs to be a Success.
    PngSeed(
        "greyAlphaWithForbiddenTrns",
        byteArrayOf(
            -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1,
            8, 4, 0, 0, 0, -75, 28, 12, 2, 0, 0, 0, 2, 116, 82, 78, 83, 0, 50, -66, 68, -100, -72, 0,
            0, 0, 11, 73, 68, 65, 84, 120, -38, 99, 72, 57, 1, 0, 1, -109, 1, 45, 18, -25, -55, -111, 0, 0,
            0, 0, 73, 69, 78, 68, -82, 66, 96, -126,
        ),
    ),
    // Colour type 6 (truecolour + alpha), 1x1, tRNS present — same reasoning as above, colour type 6.
    PngSeed(
        "rgbaWithForbiddenTrns",
        byteArrayOf(
            -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1,
            8, 6, 0, 0, 0, 31, 21, -60, -119, 0, 0, 0, 6, 116, 82, 78, 83, 1, 2, 3, 4, 5, 6, 94,
            -110, -47, 22, 0, 0, 0, 13, 73, 68, 65, 84, 120, -38, 99, -32, 18, -111, -5, 15, 0, 1, -92, 1, 60,
            76, -43, 28, -89, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126,
        ),
    ),
)

/**
 * A tiny, from-scratch, fixed-seed xorshift PRNG. Deterministic by construction — pure `Int`
 * arithmetic with a caller-supplied seed, no platform entropy source of any kind — so it produces the
 * exact same sequence on every target this test runs on.
 */
private class Xorshift32(seed: Int) {
    private var state: Int = if (seed == 0) 0x2545F491 else seed

    fun nextInt(): Int {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        return x
    }

    /** A non-negative value in `0 until bound`. [bound] must be positive. */
    fun nextIntBound(bound: Int): Int = (nextInt() and Int.MAX_VALUE) % bound

    fun nextByte(): Byte = nextInt().toByte()

    fun nextBoolean(): Boolean = (nextInt() and 1) == 1
}

/**
 * One chunk's byte span in a KNOWN-VALID seed, as walked once via that seed's own (trustworthy)
 * length fields. [start] is the offset of the chunk's 4-byte length field; the full span covers
 * length + type + payload + CRC, i.e. `start until end`.
 */
private class ChunkSpan(val start: Int, val declaredLength: Int) {
    val typeOffset: Int get() = start + 4
    val payloadOffset: Int get() = start + 8
    val crcOffset: Int get() = payloadOffset + declaredLength
    val end: Int get() = crcOffset + 4
}

private fun readU32(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
        (bytes[offset + 3].toLong() and 0xFF)

private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
    bytes[offset] = ((value ushr 24) and 0xFF).toByte()
    bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    bytes[offset + 3] = (value and 0xFF).toByte()
}

private fun chunkTypeString(bytes: ByteArray, typeOffset: Int): String =
    buildString { for (i in 0 until 4) append(bytes[typeOffset + i].toInt().toChar()) }

/** Walks a KNOWN-VALID seed's chunk boundaries once, trusting its own length fields (it is valid by
 * construction), stopping after IEND. Never called on a mutated candidate. */
private fun chunkSpans(bytes: ByteArray): List<ChunkSpan> {
    val spans = mutableListOf<ChunkSpan>()
    var pos = 8 // past the 8-byte signature
    while (pos + 8 <= bytes.size) {
        val length = readU32(bytes, pos)
        val span = ChunkSpan(pos, length.toInt())
        if (span.end > bytes.size) break
        spans.add(span)
        pos = span.end
        if (chunkTypeString(bytes, span.typeOffset) == "IEND") break
    }
    return spans
}

/** Curated declared-length values clustering on every interesting boundary: zero, one, either side of
 * the 2^31-1 admitted dimension ceiling, and the maximum representable 32-bit value. */
private val EXTREME_U32 = longArrayOf(
    0L, 1L, 2L, 3L,
    0x7FFFFFFEL, 0x7FFFFFFFL, 0x80000000L, 0x80000001L,
    0xFFFFFFFEL, 0xFFFFFFFFL,
)

private fun fixCrc(bytes: ByteArray, span: ChunkSpan) {
    val computed = crc32(0u, bytes, span.typeOffset, 4 + span.declaredLength).toLong()
    writeU32(bytes, span.crcOffset, computed)
}

private val MUTATION_KIND_NAMES = arrayOf(
    "flipRandomBits",
    "corruptLengthField",
    "corruptCrcField",
    "corruptIhdrMiscField",
    "corruptIhdrDimension",
    "truncateAtChunkBoundary",
    "truncateAtRandomOffset",
    "duplicateChunk",
    "dropChunk",
    "reorderChunks",
    "appendTrailingGarbage",
    "flipChunkTypeBit",
)

private fun mutate(rng: Xorshift32, original: ByteArray, spans: List<ChunkSpan>, kind: Int): ByteArray = when (kind) {
    0 -> flipRandomBits(rng, original)
    1 -> corruptLengthField(rng, original, spans)
    2 -> corruptCrcField(rng, original, spans)
    3 -> corruptIhdrMiscField(rng, original, spans)
    4 -> corruptIhdrDimension(rng, original, spans)
    5 -> truncateAtChunkBoundary(rng, original, spans)
    6 -> truncateAtRandomOffset(rng, original)
    7 -> duplicateChunk(rng, original, spans)
    8 -> dropChunk(rng, original, spans)
    9 -> reorderChunks(rng, original, spans)
    10 -> appendTrailingGarbage(rng, original)
    else -> flipChunkTypeBit(rng, original, spans)
}

private fun flipRandomBits(rng: Xorshift32, bytes: ByteArray): ByteArray {
    if (bytes.isEmpty()) return bytes.copyOf()
    val out = bytes.copyOf()
    val flips = 1 + rng.nextIntBound(3)
    repeat(flips) {
        val idx = rng.nextIntBound(out.size)
        val bit = 1 shl rng.nextIntBound(8)
        out[idx] = (out[idx].toInt() xor bit).toByte()
    }
    return out
}

/** Corrupts a random chunk's declared length field to a curated extreme or a fully random 32-bit
 * value, without fixing its CRC — exercises scanPng's Long-arithmetic chunk-length bounds handling
 * (the class the ceiling-check overflow bug, though not this exact field, belonged to). */
private fun corruptLengthField(rng: Xorshift32, bytes: ByteArray, spans: List<ChunkSpan>): ByteArray {
    if (spans.isEmpty()) return flipRandomBits(rng, bytes)
    val out = bytes.copyOf()
    val span = spans[rng.nextIntBound(spans.size)]
    val value = if (rng.nextBoolean()) {
        EXTREME_U32[rng.nextIntBound(EXTREME_U32.size)]
    } else {
        rng.nextInt().toLong() and 0xFFFFFFFFL
    }
    writeU32(out, span.start, value)
    return out
}

/** Flips bits in a random chunk's CRC field — a guaranteed CRC mismatch, exercising CHUNK_CRC. */
private fun corruptCrcField(rng: Xorshift32, bytes: ByteArray, spans: List<ChunkSpan>): ByteArray {
    if (spans.isEmpty()) return flipRandomBits(rng, bytes)
    val out = bytes.copyOf()
    val span = spans[rng.nextIntBound(spans.size)]
    val byteIndex = span.crcOffset + rng.nextIntBound(4)
    val bit = 1 shl rng.nextIntBound(8)
    out[byteIndex] = (out[byteIndex].toInt() xor bit).toByte()
    return out
}

/** Corrupts one non-dimension IHDR field (bit depth, colour type, compression method, filter method,
 * or interlace) to a random byte, then recomputes IHDR's own CRC — without that fix-up, the mutation
 * would almost always just trip CHUNK_CRC rather than ever reaching the field-specific check it is
 * meant to exercise. */
private fun corruptIhdrMiscField(rng: Xorshift32, bytes: ByteArray, spans: List<ChunkSpan>): ByteArray {
    val ihdr = spans.firstOrNull() ?: return flipRandomBits(rng, bytes)
    val out = bytes.copyOf()
    val fieldOffset = ihdr.payloadOffset + 8 + rng.nextIntBound(5) // bitDepth, colourType, compression, filter, interlace
    out[fieldOffset] = rng.nextByte()
    fixCrc(out, ihdr)
    return out
}

/** Corrupts IHDR's declared width and/or height, weighted toward curated extreme/boundary values —
 * the exact shape two of the four bugs already found in this decoder lived in — then recomputes
 * IHDR's own CRC for the same reason as [corruptIhdrMiscField]. */
private fun corruptIhdrDimension(rng: Xorshift32, bytes: ByteArray, spans: List<ChunkSpan>): ByteArray {
    val ihdr = spans.firstOrNull() ?: return flipRandomBits(rng, bytes)
    val out = bytes.copyOf()
    val payload = ihdr.payloadOffset
    fun extremeOrRandom(): Long =
        if (rng.nextBoolean()) EXTREME_U32[rng.nextIntBound(EXTREME_U32.size)] else (rng.nextInt().toLong() and 0x7FFFFFFFL)
    when (rng.nextIntBound(3)) {
        0 -> writeU32(out, payload, extremeOrRandom()) // width only
        1 -> writeU32(out, payload + 4, extremeOrRandom()) // height only
        else -> { // both
            writeU32(out, payload, extremeOrRandom())
            writeU32(out, payload + 4, extremeOrRandom())
        }
    }
    fixCrc(out, ihdr)
    return out
}

private fun truncateAtChunkBoundary(rng: Xorshift32, bytes: ByteArray, spans: List<ChunkSpan>): ByteArray {
    if (spans.isEmpty()) return bytes.copyOf(rng.nextIntBound(bytes.size + 1))
    val span = spans[rng.nextIntBound(spans.size)]
    val cut = if (rng.nextBoolean()) span.start else minOf(span.end, bytes.size)
    return bytes.copyOf(cut)
}

private fun truncateAtRandomOffset(rng: Xorshift32, bytes: ByteArray): ByteArray =
    bytes.copyOf(rng.nextIntBound(bytes.size + 1))

/** Duplicates a random whole chunk span in place — directly targets DUPLICATE_CRITICAL_CHUNK
 * (duplicate IHDR/PLTE) plus duplicate-ancillary shapes (e.g. a second tRNS) this fix round left
 * unchecked. */
private fun duplicateChunk(rng: Xorshift32, bytes: ByteArray, spans: List<ChunkSpan>): ByteArray {
    if (spans.isEmpty()) return bytes.copyOf()
    val span = spans[rng.nextIntBound(spans.size)]
    return bytes.copyOfRange(0, span.end) +
        bytes.copyOfRange(span.start, span.end) +
        bytes.copyOfRange(span.end, bytes.size)
}

private fun dropChunk(rng: Xorshift32, bytes: ByteArray, spans: List<ChunkSpan>): ByteArray {
    if (spans.isEmpty()) return bytes.copyOf()
    val span = spans[rng.nextIntBound(spans.size)]
    return bytes.copyOfRange(0, span.start) + bytes.copyOfRange(span.end, bytes.size)
}

/** Swaps two distinct whole chunk spans' positions, keeping each chunk's own bytes (and therefore its
 * own CRC) internally self-consistent — e.g. can move IHDR out of first position, or PLTE after IDAT. */
private fun reorderChunks(rng: Xorshift32, bytes: ByteArray, spans: List<ChunkSpan>): ByteArray {
    if (spans.size < 2) return bytes.copyOf()
    val i = rng.nextIntBound(spans.size)
    var j = rng.nextIntBound(spans.size)
    while (j == i) j = rng.nextIntBound(spans.size)
    val (first, second) = if (i < j) spans[i] to spans[j] else spans[j] to spans[i]
    return bytes.copyOfRange(0, first.start) +
        bytes.copyOfRange(second.start, second.end) +
        bytes.copyOfRange(first.end, second.start) +
        bytes.copyOfRange(first.start, first.end) +
        bytes.copyOfRange(second.end, bytes.size)
}

private fun appendTrailingGarbage(rng: Xorshift32, bytes: ByteArray): ByteArray {
    val extra = 1 + rng.nextIntBound(20)
    val out = bytes.copyOf(bytes.size + extra)
    for (i in bytes.size until out.size) out[i] = rng.nextByte()
    return out
}

/** Flips a bit in a non-IHDR chunk's 4-byte type field, then recomputes that chunk's own CRC — can
 * reclassify a known chunk into an unknown critical or unknown ancillary one, exercising
 * UNKNOWN_CRITICAL_CHUNK and the ancillary-skip path without merely tripping CHUNK_CRC. IHDR itself is
 * skipped: corrupting its type would only retread IHDR_NOT_FIRST/IHDR_LENGTH, already covered above. */
private fun flipChunkTypeBit(rng: Xorshift32, bytes: ByteArray, spans: List<ChunkSpan>): ByteArray {
    val candidates = spans.drop(1)
    if (candidates.isEmpty()) return flipRandomBits(rng, bytes)
    val span = candidates[rng.nextIntBound(candidates.size)]
    val out = bytes.copyOf()
    val byteIndex = span.typeOffset + rng.nextIntBound(4)
    val bit = 1 shl rng.nextIntBound(8)
    out[byteIndex] = (out[byteIndex].toInt() xor bit).toByte()
    fixCrc(out, span)
    return out
}
