package com.rohittp.reng.internal.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Etc2ContainerTest {
    // 6x5 has a full chain of three levels: 6x5 (2x2 blocks), 3x2 (1 block) and 1x1 (1 block).
    private val sixByFive = etc2Container(formatCode = 1, width = 6, height = 5, levelCount = 3) {
        ByteArray(8) { 0x11 }
    }

    @Test
    fun aFullChainIsAdmittedAsAViewOfItsOwnBytes() {
        val image = assertIs<Etc2ContainerResult.Admitted>(parseEtc2Container(sixByFive, Long.MAX_VALUE)).image
        assertEquals(6, image.width)
        assertEquals(5, image.height)
        assertEquals(Etc2Format.RGB8, image.format)
        assertEquals(3, image.levelCount)
        assertEquals(listOf(6 to 5, 3 to 2, 1 to 1), (0 until 3).map { image.levelWidth(it) to image.levelHeight(it) })
        assertEquals(48L, image.payloadByteCount, "4 + 1 + 1 blocks of 8 bytes")
        assertEquals(148L, image.rgbaByteCount, "(30 + 6 + 1) texels of 4 bytes")
        assertEquals(
            listOf(Triple(16, 32, true), Triple(48, 8, true), Triple(56, 8, true)),
            (0 until 3).map { level ->
                image.readLevel(level) { bytes, offset, length -> Triple(offset, length, bytes === sixByFive) }
            },
        )
    }

    @Test
    fun eachFormatCodeNamesItsBlockSize() {
        listOf(1 to Etc2Format.RGB8, 2 to Etc2Format.RGB8_PUNCHTHROUGH_ALPHA1, 3 to Etc2Format.RGBA8_EAC)
            .forEach { (code, format) ->
                val blockBytes = if (code == 3) 16 else 8
                val bytes = etc2Container(code, 8, 4) { ByteArray(blockBytes) }
                assertEquals(16 + 2 * blockBytes, bytes.size)
                val image = assertIs<Etc2ContainerResult.Admitted>(parseEtc2Container(bytes, Long.MAX_VALUE)).image
                assertSame(format, image.format)
            }
    }

    @Test
    fun framingFaultsAreMalformed() {
        val payload = sixByFive.copyOfRange(16, sixByFive.size)
        val cases = mapOf(
            "wrong magic" to etc2Header(1, 3, 6, 5, magic = "RET3") + payload,
            "short header" to sixByFive.copyOf(15),
            "reserved byte" to etc2Header(1, 3, 6, 5, reserved = 1) + payload,
            "zero width" to etc2Header(1, 1, 0, 5) + ByteArray(16),
            "zero height" to etc2Header(1, 1, 6, 0) + ByteArray(16),
            "no levels" to etc2Header(1, 0, 6, 5),
            "a level past 1x1" to etc2Header(1, 4, 6, 5) + payload + ByteArray(8),
            "one byte short" to sixByFive.copyOf(sixByFive.size - 1),
            "a trailing byte" to sixByFive + 0.toByte(),
            "a level count the payload does not match" to etc2Header(1, 2, 6, 5) + payload,
            "a width past Int" to etc2Header(1, 1, 0x80000000L, 1) + ByteArray(8),
        )
        cases.forEach { (name, bytes) ->
            assertSame(Etc2ContainerResult.Malformed, parseEtc2Container(bytes, Long.MAX_VALUE), name)
        }
    }

    @Test
    fun anUnknownVersionOrFormatIsUnsupported() {
        val payload = ByteArray(8)
        listOf(
            etc2Header(1, 1, 4, 4, version = 2) + payload,
            etc2Header(0, 1, 4, 4) + payload,
            etc2Header(4, 1, 4, 4) + payload,
        ).forEach { assertSame(Etc2ContainerResult.Unsupported, parseEtc2Container(it, Long.MAX_VALUE)) }
    }

    @Test
    fun theCeilingBoundsTheRgbaProjectionOfEveryLevel() {
        assertSame(Etc2ContainerResult.TooLarge, parseEtc2Container(sixByFive, 147L))
        assertIs<Etc2ContainerResult.Admitted>(parseEtc2Container(sixByFive, 148L))
    }

    @Test
    fun theFormIsToldByMagicAlone() {
        assertTrue(isEtc2Container(sixByFive))
        assertTrue(isEtc2Container("RET2".encodeToByteArray()))
        assertFalse(isEtc2Container("RET".encodeToByteArray()))
        assertFalse(isEtc2Container(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
    }

    @Test
    fun levelsAreReadWhereTheyLie() {
        val image = assertIs<Etc2ContainerResult.Admitted>(parseEtc2Container(sixByFive, Long.MAX_VALUE)).image
        image.readLevel(0) { bytes, offset, length ->
            assertContentEquals(sixByFive.copyOfRange(16, 48), bytes.copyOfRange(offset, offset + length))
        }
    }
}
