package com.rohittp.reng.internal.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RawRgbaContainerTest {
    private val pixels = ByteArray(3 * 2 * 4) { it.toByte() }
    private val threeByTwo = rawRgbaContainer(3, 2, pixels)

    @Test
    fun aWellFormedContainerIsAdmittedAsAViewOfItsOwnBytes() {
        val image = assertIs<RawRgbaContainerResult.Admitted>(parseRawRgbaContainer(threeByTwo, Long.MAX_VALUE)).image
        assertEquals(3, image.width)
        assertEquals(2, image.height)
        assertFalse(image.premultiplied)
        assertEquals(24L, image.rgbaByteCount)
        image.readRgba { bytes, offset, length ->
            assertSame(threeByTwo, bytes)
            assertEquals(16, offset)
            assertContentEquals(pixels, bytes.copyOfRange(offset, offset + length))
        }
    }

    @Test
    fun flagBitZeroSaysPremultiplied() {
        val image = assertIs<RawRgbaContainerResult.Admitted>(
            parseRawRgbaContainer(rawRgbaContainer(3, 2, pixels, premultiplied = true), Long.MAX_VALUE),
        ).image
        assertTrue(image.premultiplied)
    }

    @Test
    fun framingFaultsAreMalformed() {
        val cases = mapOf(
            "wrong magic" to rawRgbaHeader(3, 2, magic = "RRGA") + pixels,
            "short header" to threeByTwo.copyOf(15),
            "reserved bytes" to rawRgbaHeader(3, 2, reserved = 1) + pixels,
            "zero width" to rawRgbaHeader(0, 2),
            "zero height" to rawRgbaHeader(3, 0),
            "one byte short" to threeByTwo.copyOf(threeByTwo.size - 1),
            "a trailing byte" to threeByTwo + 0.toByte(),
            "a pixel short" to rawRgbaHeader(3, 2) + pixels.copyOf(20),
            "extents whose product overflows" to rawRgbaHeader(0xFFFFFFFFL, 0xFFFFFFFFL) + pixels,
            "extents that swap" to rawRgbaHeader(2, 4) + pixels,
        )
        cases.forEach { (name, bytes) ->
            assertSame(RawRgbaContainerResult.Malformed, parseRawRgbaContainer(bytes, Long.MAX_VALUE), name)
        }
    }

    @Test
    fun anUnknownVersionOrFlagIsUnsupported() {
        listOf(
            rawRgbaHeader(3, 2, version = 2) + pixels,
            rawRgbaHeader(3, 2, version = 0) + pixels,
            rawRgbaHeader(3, 2, flags = 2) + pixels,
            rawRgbaHeader(3, 2, flags = 0x81) + pixels,
        ).forEach { assertSame(RawRgbaContainerResult.Unsupported, parseRawRgbaContainer(it, Long.MAX_VALUE)) }
    }

    @Test
    fun theCeilingBoundsTheTexelBytes() {
        assertSame(RawRgbaContainerResult.TooLarge, parseRawRgbaContainer(threeByTwo, 23L))
        assertIs<RawRgbaContainerResult.Admitted>(parseRawRgbaContainer(threeByTwo, 24L))
    }

    @Test
    fun theFormIsToldByMagicAlone() {
        assertTrue(isRawRgbaContainer(threeByTwo))
        assertTrue(isRawRgbaContainer("RRGB".encodeToByteArray()))
        assertFalse(isRawRgbaContainer("RRG".encodeToByteArray()))
        assertFalse(isRawRgbaContainer("RET2".encodeToByteArray()))
    }
}
