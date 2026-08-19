package com.rohittp.reng.internal.glb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GlbContainerTest {
    @Test
    fun classifiesEveryContainerFixtureAsIntended() {
        assertIs<GlbScan.Admitted>(scan("01-valid-json-and-bin"))
        assertIs<GlbScan.Admitted>(scan("02-valid-json-only"))
        assertEquals(GlbReject.BAD_MAGIC, reject("03-bad-magic"))
        assertEquals(GlbReject.UNSUPPORTED_CONTAINER_VERSION, reject("04-version-1"))
        assertEquals(GlbReject.UNSUPPORTED_CONTAINER_VERSION, reject("05-version-3"))
        // Five different authoring accidents collapse into one equality comparison.
        for (name in listOf(
            "06-truncated-chunk-data", "07-declared-length-too-large",
            "08-declared-length-not-multiple-of-4", "13-truncated-chunk-header",
            "31-trailing-garbage-length-unchanged",
        )) {
            assertEquals(GlbReject.DECLARED_LENGTH_MISMATCH, reject(name), name)
        }
        assertEquals(GlbReject.BIN_CHUNK_NOT_SECOND, reject("10-json-chunk-second"))
        assertEquals(GlbReject.JSON_CHUNK_NOT_FIRST, reject("15-two-json-chunks"))
        assertEquals(GlbReject.UNKNOWN_CHUNK_IN_BIN_POSITION, reject("18-bin-after-unknown-chunk"))
        assertEquals(GlbReject.EMPTY_JSON_CHUNK, reject("14-empty-json-chunk"))
        assertIs<GlbScan.Admitted>(scan("17-unknown-chunk-third"))
        assertEquals(GlbReject.HEADER_TOO_SHORT, reject("30-empty-file"))
    }

    @Test
    fun adoptsTheStrictSpacePaddingRule() {
        assertIs<GlbScan.Admitted>(scan("19-json-padded-with-spaces"))
        assertEquals(GlbReject.JSON_TRAILING_CONTENT, reject("20-json-padded-with-nulls"))
        // Tab is JSON whitespace, so only the strict rule catches it.
        assertEquals(GlbReject.JSON_PADDING_NOT_SPACE, reject("21-json-padded-with-tabs"))
    }

    @Test
    fun boundsTheBinChunkByTheDeclaredBufferLength() {
        assertIs<GlbScan.Admitted>(scan("27-buffer-3-shorter-than-bin-chunk"))
        assertIs<GlbScan.Admitted>(scan("22-bin-padded-with-zeros"))
        // Padding bytes are unverifiable: chunkLength includes them and nothing records the
        // unpadded length, so 22 and 23 differ only in pad bytes and both are admitted.
        assertIs<GlbScan.Admitted>(scan("23-bin-padded-with-spaces"))
    }

    @Test
    fun boundsTheJsonChunkIndependentlyOfTheWholeGlbCeiling() {
        assertEquals(GlbReject.JSON_CHUNK_TOO_LARGE, rejectWithCeiling("02-valid-json-only", 8L))
    }

    // ---- fixture-name plumbing ----

    private val defaultCeiling = 1_048_576L

    private fun scan(name: String): GlbScan = scanGlb(fixture(name), defaultCeiling)

    private fun reject(name: String): GlbReject =
        assertIs<GlbScan.Malformed>(scan(name), "fixture $name").reason

    private fun rejectWithCeiling(name: String, ceiling: Long): GlbReject =
        assertIs<GlbScan.Malformed>(scanGlb(fixture(name), ceiling), "fixture $name").reason

    private fun fixture(name: String): ByteArray = when (name) {
        "01-valid-json-and-bin" -> validGlb()
        "02-valid-json-only" -> validJsonOnlyGlb()
        "03-bad-magic" -> badMagicGlb()
        "04-version-1" -> versionGlb(1L)
        "05-version-3" -> versionGlb(3L)
        "06-truncated-chunk-data" -> truncatedTailGlb()
        "07-declared-length-too-large" -> declaredLengthOffsetGlb(+100L)
        "08-declared-length-not-multiple-of-4" -> declaredLengthOffsetGlb(-1L)
        "13-truncated-chunk-header" -> truncatedIntoChunkHeaderGlb()
        "31-trailing-garbage-length-unchanged" -> trailingGarbageGlb()
        "10-json-chunk-second" -> binFirstJsonSecondGlb()
        "15-two-json-chunks" -> twoJsonChunksGlb()
        "18-bin-after-unknown-chunk" -> binAfterUnknownChunkGlb()
        "14-empty-json-chunk" -> emptyJsonChunkGlb()
        "17-unknown-chunk-third" -> unknownChunkThirdGlb()
        "30-empty-file" -> ByteArray(0)
        "19-json-padded-with-spaces" -> jsonPaddedGlb(0x20)
        "20-json-padded-with-nulls" -> jsonPaddedGlb(0x00)
        "21-json-padded-with-tabs" -> jsonPaddedGlb(0x09)
        // Task 7's container walk never inspects a buffer length declared inside the JSON chunk
        // against the BIN chunk's length — that cross-check needs the parsed glTF document and is
        // a later stage's job. All three of these fixtures are therefore just ordinary two-chunk
        // containers as far as scanGlb is concerned.
        "27-buffer-3-shorter-than-bin-chunk" -> binPaddedGlb(0x00)
        "22-bin-padded-with-zeros" -> binPaddedGlb(0x00)
        "23-bin-padded-with-spaces" -> binPaddedGlb(0x20)
        else -> error("no fixture named $name")
    }

    // ---- fixture construction ----

    private fun u32le(value: Long): ByteArray = byteArrayOf(
        (value and 0xFFL).toByte(),
        ((value shr 8) and 0xFFL).toByte(),
        ((value shr 16) and 0xFFL).toByte(),
        ((value shr 24) and 0xFFL).toByte(),
    )

    private val jsonType = 0x4E4F534AL
    private val binType = 0x004E4942L

    // Neither JSON nor BIN: an arbitrary chunk type used to exercise the unknown-chunk rules.
    private val unknownType = 0x12345678L

    private fun chunk(type: Long, payload: ByteArray): ByteArray =
        u32le(payload.size.toLong()) + u32le(type) + payload

    /** Assembles a well-formed GLB: header (with a correct, matching declared length) plus the
     * given already-built chunks, concatenated in order. */
    private fun glbFrom(chunks: List<ByteArray>, version: Long = 2L): ByteArray {
        val body = chunks.fold(ByteArray(0)) { acc, next -> acc + next }
        val total = 12L + body.size
        return u32le(0x46546C67L) + u32le(version) + u32le(total) + body
    }

    private fun spacePadded(text: String): ByteArray {
        val raw = text.encodeToByteArray()
        val padCount = (4 - raw.size % 4) % 4
        return raw + ByteArray(padCount) { 0x20 }
    }

    private fun simpleJsonChunk(): ByteArray = chunk(jsonType, spacePadded("{}"))

    private fun simpleBinChunk(): ByteArray = chunk(binType, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

    private fun validGlb(): ByteArray = glbFrom(listOf(simpleJsonChunk(), simpleBinChunk()))

    private fun validJsonOnlyGlb(): ByteArray =
        // 9 bytes of text, padded to 12: comfortably above an 8-byte ceiling and comfortably
        // below the default one.
        glbFrom(listOf(chunk(jsonType, spacePadded("""{"abc":1}"""))))

    private fun badMagicGlb(): ByteArray {
        val bytes = validJsonOnlyGlb().copyOf()
        bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
        return bytes
    }

    private fun versionGlb(version: Long): ByteArray =
        glbFrom(listOf(simpleJsonChunk()), version = version)

    /** Truncates a valid file's tail without correcting the header's declared length, so the
     * declared length (still the original, larger value) no longer equals the actual byte count. */
    private fun truncatedTailGlb(): ByteArray {
        val full = validGlb()
        return full.copyOfRange(0, full.size - 4)
    }

    /** Overwrites only the header's declared-length field with `actualTotal + delta`, leaving the
     * rest of a valid file's bytes untouched. */
    private fun declaredLengthOffsetGlb(delta: Long): ByteArray {
        val chunks = listOf(simpleJsonChunk(), simpleBinChunk())
        val body = chunks.fold(ByteArray(0)) { acc, next -> acc + next }
        val actualTotal = 12L + body.size
        val header = u32le(0x46546C67L) + u32le(2L) + u32le(actualTotal + delta)
        return header + body
    }

    /** A valid two-chunk file cut off 4 bytes into the second chunk's 8-byte header, with the
     * declared length left at the original (now too large) value. */
    private fun truncatedIntoChunkHeaderGlb(): ByteArray {
        val full = validGlb()
        val cutAt = 12 + simpleJsonChunk().size + 4
        return full.copyOfRange(0, cutAt)
    }

    /** A valid file with garbage appended and the declared length left unchanged. */
    private fun trailingGarbageGlb(): ByteArray = validGlb() + byteArrayOf(0x01, 0x02, 0x03, 0x04)

    private fun binFirstJsonSecondGlb(): ByteArray =
        glbFrom(listOf(simpleBinChunk(), simpleJsonChunk()))

    private fun twoJsonChunksGlb(): ByteArray =
        glbFrom(listOf(simpleJsonChunk(), simpleJsonChunk()))

    private fun binAfterUnknownChunkGlb(): ByteArray =
        glbFrom(listOf(simpleJsonChunk(), chunk(unknownType, byteArrayOf(9, 9, 9, 9)), simpleBinChunk()))

    private fun emptyJsonChunkGlb(): ByteArray = glbFrom(listOf(chunk(jsonType, ByteArray(0))))

    private fun unknownChunkThirdGlb(): ByteArray =
        glbFrom(listOf(simpleJsonChunk(), simpleBinChunk(), chunk(unknownType, byteArrayOf(7, 7, 7, 7))))

    /** A JSON chunk whose payload is `{}` followed by six extra padding bytes, all [padByte]. */
    private fun jsonPaddedGlb(padByte: Int): ByteArray {
        val payload = "{}".encodeToByteArray() + ByteArray(6) { padByte.toByte() }
        return glbFrom(listOf(chunk(jsonType, payload)))
    }

    private fun binPaddedGlb(padByte: Int): ByteArray {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6) + ByteArray(2) { padByte.toByte() }
        return glbFrom(listOf(simpleJsonChunk(), chunk(binType, payload)))
    }
}
