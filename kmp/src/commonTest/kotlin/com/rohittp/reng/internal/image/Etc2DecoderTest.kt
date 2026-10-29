package com.rohittp.reng.internal.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * One hand-built block per mode and format, each field placed at the bit position the OpenGL ES 3.0
 * specification (appendix C.1) gives it, and every expected texel worked out by hand from the
 * specification's formulas in the comment beside it -- never from the decoder. Where a mode is chosen
 * by an overflowing sum, the comment shows the sum. The GL conformance suite then checks the same
 * decoder against real drivers over random blocks.
 */
class Etc2DecoderTest {
    private fun decode(format: Etc2Format, block: ByteArray): List<List<Int>> {
        val out = ByteArray(64)
        decodeEtc2Block(format, block, 0, out)
        return (0 until 16).map { texel -> (0 until 4).map { out[texel * 4 + it].toInt() and 0xFF } }
    }

    private fun List<List<Int>>.at(x: Int, y: Int): List<Int> = this[y * 4 + x]

    // Individual mode (bit 33 = 0): 4-bit base colours per half, extended by replication.
    // Left half (68, 136, 204), table 0 = {2, 8}; right half (204, 34, 102), table 7 = {47, 183}.
    private fun individualBlock(flip: Int): ByteArray = etc2Block(
        Triple(63, 60, 0x4), Triple(59, 56, 0xC),
        Triple(55, 52, 0x8), Triple(51, 48, 0x2),
        Triple(47, 44, 0xC), Triple(43, 40, 0x6),
        Triple(39, 37, 0), Triple(36, 34, 7), Triple(33, 33, 0), Triple(32, 32, flip),
        *colourIndices(Triple(1, 0, 1), Triple(2, 0, 2), Triple(3, 0, 3), Triple(0, 3, 0), Triple(2, 3, 0)),
    )

    @Test
    fun individualModeSplitsTheBlockIntoColumnHalves() {
        val texels = decode(Etc2Format.RGB8, individualBlock(flip = 0))
        assertEquals(listOf(70, 138, 206, 255), texels.at(0, 0), "left, index 0: +2")
        assertEquals(listOf(76, 144, 212, 255), texels.at(1, 0), "left, index 1: +8")
        assertEquals(listOf(157, 0, 55, 255), texels.at(2, 0), "right, index 2: -47, green clamped from -13")
        assertEquals(listOf(21, 0, 0, 255), texels.at(3, 0), "right, index 3: -183")
        assertEquals(listOf(70, 138, 206, 255), texels.at(0, 3), "left, index 0")
        assertEquals(listOf(251, 81, 149, 255), texels.at(2, 3), "right, index 0: +47")
    }

    @Test
    fun theFlipBitSplitsTheBlockIntoRowHalves() {
        val texels = decode(Etc2Format.RGB8, individualBlock(flip = 1))
        assertEquals(listOf(66, 134, 202, 255), texels.at(2, 0), "top, index 2: -2")
        assertEquals(listOf(60, 128, 196, 255), texels.at(3, 0), "top, index 3: -8")
        assertEquals(listOf(251, 81, 149, 255), texels.at(0, 3), "bottom, index 0: +47")
    }

    // Differential mode: R 10 + (-3) = 7, G 20 + 3 = 23, B 31 + 0 = 31, none overflowing.
    // 5-bit extension: 10 -> 82, 7 -> 57, 20 -> 165, 23 -> 189, 31 -> 255.
    // Table 1 = {5, 17} on the left, table 2 = {9, 29} on the right.
    private fun differentialBlock(flag: Int): ByteArray = etc2Block(
        Triple(63, 59, 10), Triple(58, 56, 0b101),
        Triple(55, 51, 20), Triple(50, 48, 0b011),
        Triple(47, 43, 31), Triple(42, 40, 0),
        Triple(39, 37, 1), Triple(36, 34, 2), Triple(33, 33, flag), Triple(32, 32, 0),
        *colourIndices(Triple(0, 0, 1), Triple(1, 1, 0), Triple(0, 1, 3), Triple(3, 3, 2)),
    )

    @Test
    fun differentialModeOffsetsTheSecondBaseColourFromTheFirst() {
        val texels = decode(Etc2Format.RGB8, differentialBlock(flag = 1))
        assertEquals(listOf(99, 182, 255, 255), texels.at(0, 0), "left, +17, blue clamped from 272")
        assertEquals(listOf(87, 170, 255, 255), texels.at(1, 1), "left, +5")
        assertEquals(listOf(65, 148, 238, 255), texels.at(0, 1), "left, -17")
        assertEquals(listOf(48, 180, 246, 255), texels.at(3, 3), "right, -9")
    }

    // T mode: R 30 + 3 = 33 overflows (bits 63..61 set, bit 58 clear).
    // c1 = (0b1011, 3, 15) -> (187, 51, 255); c2 = (0, 8, 4) -> (0, 136, 68); distance index 0b011 -> 16.
    private fun tBlock(flag: Int): ByteArray = etc2Block(
        Triple(63, 61, 0b111), Triple(60, 59, 0b10), Triple(58, 58, 0), Triple(57, 56, 0b11),
        Triple(55, 52, 3), Triple(51, 48, 15),
        Triple(47, 44, 0), Triple(43, 40, 8), Triple(39, 36, 4),
        Triple(35, 34, 0b01), Triple(33, 33, flag), Triple(32, 32, 1),
        *colourIndices(Triple(0, 0, 0), Triple(1, 0, 1), Triple(2, 0, 2), Triple(3, 0, 3)),
    )

    @Test
    fun tModePaintsOneBaseColourAndThreeAroundTheOther() {
        val texels = decode(Etc2Format.RGB8, tBlock(flag = 1))
        assertEquals(listOf(187, 51, 255, 255), texels.at(0, 0), "c1")
        assertEquals(listOf(16, 152, 84, 255), texels.at(1, 0), "c2 + 16")
        assertEquals(listOf(0, 136, 68, 255), texels.at(2, 0), "c2")
        assertEquals(listOf(0, 120, 52, 255), texels.at(3, 0), "c2 - 16, red clamped from -16")
    }

    // H mode: R 9 + (-3) = 6 does not overflow; G 1 + (-2) = -1 does (bits 55..53 clear, bit 50 set).
    // c1 = (9, 0b1010, 0b1100) -> (153, 170, 204); c2 = (9, 10, 11) -> (153, 170, 187).
    // 0x9AC >= 0x9AB, so the distance index is (1 << 2) | (0 << 1) | 1 = 5 -> 32.
    private fun hBlock(): ByteArray = etc2Block(
        Triple(63, 63, 0), Triple(62, 59, 9), Triple(58, 56, 0b101),
        Triple(55, 53, 0), Triple(52, 52, 0), Triple(51, 51, 1), Triple(50, 50, 1), Triple(49, 47, 0b100),
        Triple(46, 43, 9), Triple(42, 39, 10), Triple(38, 35, 11),
        Triple(34, 34, 1), Triple(33, 33, 1), Triple(32, 32, 0),
        *colourIndices(Triple(0, 0, 0), Triple(1, 0, 1), Triple(2, 0, 2), Triple(3, 0, 3)),
    )

    @Test
    fun hModePaintsTwoColoursAroundEachBaseAndDerivesTheLowDistanceBitFromTheirOrder() {
        val texels = decode(Etc2Format.RGB8, hBlock())
        assertEquals(listOf(185, 202, 236, 255), texels.at(0, 0), "c1 + 32")
        assertEquals(listOf(121, 138, 172, 255), texels.at(1, 0), "c1 - 32")
        assertEquals(listOf(185, 202, 219, 255), texels.at(2, 0), "c2 + 32")
        assertEquals(listOf(121, 138, 155, 255), texels.at(3, 0), "c2 - 32")
    }

    // Planar mode: R 8 + 1 and G 0 + 0 do not overflow; B 30 + 2 = 32 does.
    // O = (32, 64, 21) -> (130, 129, 85); H = (63, 0, 32) -> (255, 0, 130); V = (0, 127, 21) -> (0, 255, 85).
    // R = (125x - 130y + 522) >> 2, G = (-129x + 126y + 518) >> 2, B = (45x + 342) >> 2.
    private fun planarBlock(): ByteArray = etc2Block(
        Triple(63, 63, 0), Triple(62, 57, 0b100000), Triple(56, 56, 1),
        Triple(55, 55, 0), Triple(54, 49, 0), Triple(48, 48, 0),
        Triple(47, 45, 0b111), Triple(44, 43, 0b10), Triple(42, 42, 0), Triple(41, 39, 0b101),
        Triple(38, 34, 0b11111), Triple(33, 33, 1), Triple(32, 32, 1),
        Triple(31, 25, 0), Triple(24, 19, 0b100000),
        Triple(18, 13, 0), Triple(12, 6, 0b1111111), Triple(5, 0, 0b010101),
    )

    @Test
    fun planarModeInterpolatesThreeColoursAcrossTheBlock() {
        val texels = decode(Etc2Format.RGB8, planarBlock())
        assertEquals(listOf(130, 129, 85, 255), texels.at(0, 0))
        assertEquals(listOf(224, 32, 119, 255), texels.at(3, 0))
        assertEquals(listOf(33, 224, 85, 255), texels.at(0, 3))
        assertEquals(listOf(126, 127, 119, 255), texels.at(3, 3))
    }

    @Test
    fun aPunchThroughBlockWithItsOpaqueBitSetIsTheDifferentialBlock() {
        assertEquals(
            decode(Etc2Format.RGB8, differentialBlock(flag = 1)),
            decode(Etc2Format.RGB8_PUNCHTHROUGH_ALPHA1, differentialBlock(flag = 1)),
        )
    }

    @Test
    fun aClearedOpaqueBitZeroesTheSmallModifierAndMakesIndexTwoTransparentBlack() {
        // In RGB8 this bit pattern would be individual mode; punch-through has none.
        val texels = decode(Etc2Format.RGB8_PUNCHTHROUGH_ALPHA1, differentialBlock(flag = 0))
        assertEquals(listOf(99, 182, 255, 255), texels.at(0, 0), "index 1 keeps +17")
        assertEquals(listOf(82, 165, 255, 255), texels.at(1, 1), "index 0 is +0, the base itself")
        assertEquals(listOf(65, 148, 238, 255), texels.at(0, 1), "index 3 keeps -17")
        assertEquals(listOf(0, 0, 0, 0), texels.at(3, 3), "index 2 is transparent, already premultiplied")
    }

    @Test
    fun aClearedOpaqueBitMakesIndexTwoTransparentInTModeToo() {
        val texels = decode(Etc2Format.RGB8_PUNCHTHROUGH_ALPHA1, tBlock(flag = 0))
        assertEquals(listOf(187, 51, 255, 255), texels.at(0, 0))
        assertEquals(listOf(16, 152, 84, 255), texels.at(1, 0))
        assertEquals(listOf(0, 0, 0, 0), texels.at(2, 0))
        assertEquals(listOf(0, 120, 52, 255), texels.at(3, 0))
    }

    @Test
    fun planarModeIgnoresTheOpaqueBit() {
        val opaque = decode(Etc2Format.RGB8_PUNCHTHROUGH_ALPHA1, planarBlock())
        assertEquals(decode(Etc2Format.RGB8, planarBlock()), opaque)
        // Bit 33 is part of nothing in planar mode beyond the mode choice it already made.
        assertEquals(listOf(130, 129, 85, 255), opaque.at(0, 0))
    }

    // EAC: alpha = clamp(base + table[index] * multiplier). Table 13 = {-1, -2, -3, -10, 0, 1, 2, 9}.
    @Test
    fun eacAlphaReplacesTheColourBlocksAlphaAndLeavesItsColourAlone() {
        val alpha = etc2Block(
            Triple(63, 56, 100), Triple(55, 52, 3), Triple(51, 48, 13),
            *eacIndices(Triple(0, 0, 3), Triple(0, 1, 7), Triple(1, 0, 4), Triple(3, 3, 5)),
        )
        val texels = decode(Etc2Format.RGBA8_EAC, alpha + individualBlock(flip = 0))
        assertEquals(listOf(70, 138, 206, 70), texels.at(0, 0), "100 - 10 * 3; the colour is not multiplied")
        assertEquals(listOf(70, 138, 206, 127), texels.at(0, 1), "100 + 9 * 3")
        assertEquals(listOf(76, 144, 212, 100), texels.at(1, 0), "100 + 0 * 3")
        assertEquals(listOf(251, 81, 149, 97), texels.at(2, 3), "index 0: 100 - 1 * 3")
        assertEquals(103, texels.at(3, 3)[3], "index 5: 100 + 1 * 3")
    }

    @Test
    fun eacAlphaClampsBothWays() {
        // Table 0 = {-3, -6, -9, -15, 2, 5, 8, 14}, multiplier 15.
        fun alpha(base: Int, index: Int) =
            etc2Block(Triple(63, 56, base), Triple(55, 52, 15), Triple(51, 48, 0), *eacIndices(Triple(0, 0, index)))
        val high = alpha(base = 250, index = 7)
        val low = alpha(base = 10, index = 3)
        assertEquals(255, decode(Etc2Format.RGBA8_EAC, high + individualBlock(0)).at(0, 0)[3], "250 + 210")
        assertEquals(0, decode(Etc2Format.RGBA8_EAC, low + individualBlock(0)).at(0, 0)[3], "10 - 225")
    }

    @Test
    fun rowsAreDecodedInPlaceAndPartialBlocksAreCropped() {
        // 5x5: four blocks, each a flat individual-mode colour (index 0 everywhere: base + 2).
        val colours = listOf(0x1, 0x5, 0x9, 0xD)
        var blocks = ByteArray(0)
        colours.forEach { nibble ->
            blocks += etc2Block(
                Triple(63, 60, nibble), Triple(59, 56, nibble), Triple(55, 52, nibble), Triple(51, 48, nibble),
                Triple(47, 44, nibble), Triple(43, 40, nibble),
            )
        }
        val container = etc2Header(1, 1, 5, 5) + blocks
        val image = assertIs<Etc2ContainerResult.Admitted>(parseEtc2Container(container, Long.MAX_VALUE)).image
        val whole = ByteArray(5 * 5 * 4)
        decodeEtc2Rows(image, 0, 0, 2, whole)
        fun texel(x: Int, y: Int) = whole[(y * 5 + x) * 4].toInt() and 0xFF
        assertEquals(19, texel(0, 0), "block (0, 0): 0x11 + 2")
        assertEquals(87, texel(4, 0), "block (1, 0): 0x55 + 2")
        assertEquals(155, texel(3, 4), "block (0, 1): 0x99 + 2")
        assertEquals(223, texel(4, 4), "block (1, 1): 0xDD + 2")

        val secondBlockRow = ByteArray(5 * 4)
        decodeEtc2Rows(image, 0, 1, 1, secondBlockRow)
        assertContentEquals(whole.copyOfRange(4 * 5 * 4, 5 * 5 * 4), secondBlockRow, "one row in the last block row")
    }
}
