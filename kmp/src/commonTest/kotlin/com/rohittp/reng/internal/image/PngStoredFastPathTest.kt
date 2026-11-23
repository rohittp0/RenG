package com.rohittp.reng.internal.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins `PngDecoder.kt`'s stored-block fast path to the general path it short-cuts: for every input
 * here, `decodePng` with the fast path allowed answers exactly what it answers with every byte forced
 * through [InflateStream] -- the same result type, the same reject reason, the same pixels.
 *
 * The encoder below is this file's own: a zlib stream of stored blocks is a two-byte header, then
 * per block one header byte, `LEN`, `NLEN` and the bytes themselves, then the Adler-32 big-endian, so
 * it is written straight from RFC 1950/1951 with nothing borrowed from the decoder. The row filters are
 * written forward, from the PNG specification, likewise. Each case that should take the fast path also
 * asserts [storedFastPathDecodes], so an identity that held only because the fast path silently
 * declined everything would fail here rather than pass.
 */
class PngStoredFastPathTest {

    @Test
    fun theAppsShapeTakesTheFastPathAndDecodesTheSamePixels() {
        // RGBA8, filter 0 on every row, one stored block per row -- what the app's PngWriter emits.
        val pixels = pixelsOf(width = 7, height = 5, channels = 4, seed = 1)
        val png = storedPng(width = 7, height = 5, colourType = 6, raw = filtered(pixels, 7, 4) { 0 }, blockSize = 7 * 4 + 1)
        assertTrue(storedFastPathDecodes(png))
        val fast = assertIs<PngDecodeResult.Success>(decodePng(png, CEILING))
        assertContentEquals(pixels.flatten(), fast.image.rgbaSnapshot())
        assertSameAsGeneralPath(png)
    }

    @Test
    fun aWideRowOfHighBytesKeepsTheAdlerCheckExact() {
        // 2000 RGBA pixels of 0xFF: an 8001-byte row, longer than zlib's 5552-byte NMAX run, where the
        // sums overflow a signed Int accumulator. With the check wrong the fast path would decline this
        // valid stream -- and could accept a crafted one zlib rejects.
        val width = 2000
        val rows = List(3) { ByteArray(width * 4) { -1 } }
        val png = storedPng(width = width, height = 3, colourType = 6, raw = filtered(rows, width, 4) { 0 }, blockSize = 65535)
        assertTrue(storedFastPathDecodes(png))
        assertSameAsGeneralPath(png)
    }

    @Test
    fun everyFilterTypeAndEveryColourTypeAgreeWithTheGeneralPath() {
        for ((colourType, channels) in listOf(0 to 1, 2 to 3, 4 to 2, 6 to 4)) {
            for (width in listOf(1, 2, 3, 5, 17)) {
                val pixels = pixelsOf(width, height = 6, channels = channels, seed = width * 31 + colourType)
                val mixed = filtered(pixels, width, channels) { row -> row % 5 }
                val png = storedPng(width, 6, colourType, mixed, blockSize = 11)
                assertTrue(storedFastPathDecodes(png), "colour $colourType width $width")
                assertSameAsGeneralPath(png)
            }
        }
    }

    @Test
    fun blocksAndIdatChunksThatCrossRowBoundariesAgreeWithTheGeneralPath() {
        val pixels = pixelsOf(width = 9, height = 7, channels = 4, seed = 9)
        val raw = filtered(pixels, 9, 4) { row -> (row * 3) % 5 }
        for (blockSize in listOf(1, 2, 13, 36, 37, 38, 100, raw.size, 65535)) {
            for (idatSize in listOf(1, 3, 64, Int.MAX_VALUE)) {
                val png = storedPng(9, 7, 6, raw, blockSize, idatSize)
                assertTrue(storedFastPathDecodes(png), "block $blockSize idat $idatSize")
                assertSameAsGeneralPath(png)
            }
        }
    }

    @Test
    fun emptyBlocksBeforeTheFinalOneAreCrossed() {
        val pixels = pixelsOf(width = 3, height = 2, channels = 4, seed = 4)
        val png = storedPng(3, 2, 6, filtered(pixels, 3, 4) { 0 }, blockSize = 26, trailingEmptyBlocks = 2)
        assertTrue(storedFastPathDecodes(png))
        assertSameAsGeneralPath(png)
    }

    @Test
    fun aPaletteImageTakesTheFastPathAndAnOutOfRangeIndexStillFailsTheGeneralWay() {
        val palette = byteArrayOf(1, 2, 3, 4, 5, 6)
        val inRange = byteArrayOf(0, 0, 1, 1, 0)
        val good = storedPng(4, 1, 3, inRange, blockSize = 5, palette = palette)
        assertTrue(storedFastPathDecodes(good))
        assertSameAsGeneralPath(good)

        val outOfRange = storedPng(4, 1, 3, byteArrayOf(0, 0, 2, 1, 0), blockSize = 5, palette = palette)
        assertFalse(storedFastPathDecodes(outOfRange))
        assertEquals(PngDecodeResult.Malformed(PngReject.PALETTE_INDEX_OUT_OF_RANGE), decodePng(outOfRange, CEILING))
        assertSameAsGeneralPath(outOfRange)
    }

    @Test
    fun everyStreamFaultIsLeftToTheGeneralPath() {
        val pixels = pixelsOf(width = 4, height = 3, channels = 4, seed = 7)
        val raw = filtered(pixels, 4, 4) { 0 }
        val faults = mapOf(
            "bad adler" to storedPng(4, 3, 6, raw, 17, corruptAdler = true),
            "bad nlen" to storedPng(4, 3, 6, raw, 17, corruptNlen = true),
            "bad header check" to storedPng(4, 3, 6, raw, 17, zlibHeader = byteArrayOf(0x78, 0x02)),
            "preset dictionary" to storedPng(4, 3, 6, raw, 17, zlibHeader = byteArrayOf(0x78, 0x20)),
            "window too large" to storedPng(4, 3, 6, raw, 17, zlibHeader = byteArrayOf(-0x78, 0x1C)),
            "short raster" to storedPng(4, 3, 6, raw.copyOf(raw.size - 1), 17),
            "long raster" to storedPng(4, 3, 6, raw + byteArrayOf(0), 17),
            "trailing bytes" to storedPng(4, 3, 6, raw, 17, trailingBytes = byteArrayOf(0, 0)),
            "filter five" to storedPng(4, 3, 6, raw.copyOf().also { it[17] = 5 }, 17),
        )
        for ((name, png) in faults) {
            assertFalse(storedFastPathDecodes(png), name)
            assertSameAsGeneralPath(png, name)
        }
    }

    @Test
    fun mutatedStoredStreamsAlwaysAgreeWithTheGeneralPath() {
        // Every byte of the IDAT payload is fair game -- headers, LEN/NLEN, data, the Adler-32 -- with
        // the chunk CRC recomputed so the mutation reaches the zlib layer rather than the container.
        var state = 0x2545F491
        fun next(): Int {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return state and Int.MAX_VALUE
        }
        val seeds = listOf(
            storedPng(5, 4, 6, filtered(pixelsOf(5, 4, 4, 3), 5, 4) { 0 }, 21),
            storedPng(6, 3, 2, filtered(pixelsOf(6, 3, 3, 5), 6, 3) { it % 5 }, 7, idatSize = 10),
            storedPng(3, 3, 0, filtered(pixelsOf(3, 3, 1, 8), 3, 1) { (it + 2) % 5 }, 4),
        )
        var fastTaken = 0
        repeat(3000) { iteration ->
            val png = seeds[iteration % seeds.size].copyOf()
            val idats = idatPayloads(png)
            repeat(1 + next() % 3) {
                val (start, length) = idats[next() % idats.size]
                if (length > 0) png[start + next() % length] = (next() and 0xFF).toByte()
            }
            fixIdatCrcs(png)
            if (storedFastPathDecodes(png)) fastTaken += 1
            assertSameAsGeneralPath(png, "iteration $iteration")
        }
        // Some mutations land in pixel data or padding bits and leave a valid stream behind; if none did,
        // this test would only ever have compared the general path with itself.
        assertTrue(fastTaken > 0, "no mutated input took the fast path")
    }

    private fun assertSameAsGeneralPath(png: ByteArray, name: String = "") {
        val fast = decodePng(png, CEILING)
        val general = decodePng(png, CEILING, storedFastPath = false)
        if (fast is PngDecodeResult.Success && general is PngDecodeResult.Success) {
            assertEquals(general.image.width, fast.image.width, name)
            assertEquals(general.image.height, fast.image.height, name)
            assertContentEquals(general.image.rgbaSnapshot(), fast.image.rgbaSnapshot(), name)
        } else {
            assertEquals(general, fast, name)
        }
    }

    private companion object {
        const val CEILING = 1L shl 20

        /** Deterministic pixel rows: `height` rows of `width * channels` bytes each. */
        fun pixelsOf(width: Int, height: Int, channels: Int, seed: Int): List<ByteArray> =
            List(height) { row -> ByteArray(width * channels) { i -> ((seed * 97 + row * 53 + i * 29 + i * i) and 0xFF).toByte() } }

        fun List<ByteArray>.flatten(): ByteArray = fold(ByteArray(0)) { acc, row -> acc + row }

        /** Forward-filters each row with the type [filterOf] picks for it, from the PNG specification. */
        fun filtered(rows: List<ByteArray>, width: Int, bpp: Int, filterOf: (Int) -> Int): ByteArray {
            val out = ArrayList<Byte>()
            var previous = ByteArray(width * bpp)
            rows.forEachIndexed { index, row ->
                val type = filterOf(index)
                out += type.toByte()
                for (i in row.indices) {
                    val x = row[i].toInt() and 0xFF
                    val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                    val b = previous[i].toInt() and 0xFF
                    val c = if (i >= bpp) previous[i - bpp].toInt() and 0xFF else 0
                    val predictor = when (type) {
                        0 -> 0
                        1 -> a
                        2 -> b
                        3 -> (a + b) / 2
                        else -> {
                            val p = a + b - c
                            val pa = kotlin.math.abs(p - a)
                            val pb = kotlin.math.abs(p - b)
                            val pc = kotlin.math.abs(p - c)
                            if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                        }
                    }
                    out += ((x - predictor) and 0xFF).toByte()
                }
                previous = row
            }
            return out.toByteArray()
        }

        fun storedPng(
            width: Int,
            height: Int,
            colourType: Int,
            raw: ByteArray,
            blockSize: Int,
            idatSize: Int = Int.MAX_VALUE,
            palette: ByteArray? = null,
            zlibHeader: ByteArray = byteArrayOf(0x78, 0x01),
            trailingEmptyBlocks: Int = 0,
            corruptAdler: Boolean = false,
            corruptNlen: Boolean = false,
            trailingBytes: ByteArray = ByteArray(0),
        ): ByteArray {
            val zlib = ArrayList<Byte>()
            zlibHeader.forEach { zlib += it }
            val blocks = raw.toList().chunked(blockSize).ifEmpty { listOf(emptyList()) }
            blocks.forEachIndexed { index, block ->
                val last = index == blocks.lastIndex && trailingEmptyBlocks == 0
                appendStoredBlock(zlib, block, last, corruptNlen && index == 0)
            }
            repeat(trailingEmptyBlocks) { appendStoredBlock(zlib, emptyList(), it == trailingEmptyBlocks - 1, false) }
            var adler = adler32(raw)
            if (corruptAdler) adler = adler xor 1L
            for (shift in listOf(24, 16, 8, 0)) zlib += (adler ushr shift).toByte()
            trailingBytes.forEach { zlib += it }

            val ihdr = ByteArray(13)
            writeU32(ihdr, 0, width.toLong())
            writeU32(ihdr, 4, height.toLong())
            ihdr[8] = 8
            ihdr[9] = colourType.toByte()
            var png = byteArrayOf(-0x77, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", ihdr)
            if (palette != null) png += chunk("PLTE", palette)
            for (part in zlib.toByteArray().toList().chunked(idatSize)) png += chunk("IDAT", part.toByteArray())
            return png + chunk("IEND", ByteArray(0))
        }

        fun appendStoredBlock(out: MutableList<Byte>, block: List<Byte>, last: Boolean, corruptNlen: Boolean) {
            out += (if (last) 1 else 0).toByte()
            val length = block.size
            val nlen = length.inv() and 0xFFFF xor (if (corruptNlen) 1 else 0)
            out += (length and 0xFF).toByte()
            out += (length ushr 8).toByte()
            out += (nlen and 0xFF).toByte()
            out += (nlen ushr 8).toByte()
            out += block
        }

        fun adler32(data: ByteArray): Long {
            var a = 1L
            var b = 0L
            for (byte in data) {
                a = (a + (byte.toInt() and 0xFF)) % 65521
                b = (b + a) % 65521
            }
            return (b shl 16) or a
        }

        fun chunk(type: String, payload: ByteArray): ByteArray {
            val out = ByteArray(12 + payload.size)
            writeU32(out, 0, payload.size.toLong())
            type.encodeToByteArray().copyInto(out, 4)
            payload.copyInto(out, 8)
            writeU32(out, 8 + payload.size, crc32(0u, out, 4, 4 + payload.size).toLong())
            return out
        }

        fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 4) bytes[offset + i] = (value ushr (24 - 8 * i)).toByte()
        }

        fun readU32(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)

        /** `(payload start, payload length)` of every IDAT chunk. */
        fun idatPayloads(png: ByteArray): List<Pair<Int, Int>> {
            val found = ArrayList<Pair<Int, Int>>()
            var pos = 8
            while (pos + 12 <= png.size) {
                val length = readU32(png, pos)
                if (png.decodeToString(pos + 4, pos + 8) == "IDAT") found += (pos + 8) to length
                pos += 12 + length
            }
            return found
        }

        fun fixIdatCrcs(png: ByteArray) {
            for ((start, length) in idatPayloads(png)) {
                writeU32(png, start + length, crc32(0u, png, start - 4, 4 + length).toLong())
            }
        }
    }
}
