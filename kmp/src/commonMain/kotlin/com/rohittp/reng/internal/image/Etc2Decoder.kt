package com.rohittp.reng.internal.image

// Decodes ETC2 and EAC blocks to RGBA8 exactly as the OpenGL ES 3.0 specification (appendix C.1)
// defines them, for a context that cannot sample them itself (ADR 0084).
//
// Every mode is here: ETC1's individual and differential modes, ETC2's T, H and planar modes, the
// punch-through variants in which a cleared opaque bit makes index `10b` transparent and zeroes the
// small modifier, and the EAC alpha block. Nothing is premultiplied: the producer premultiplied the
// colour block, and a punch-through texel that is transparent decodes to `(0, 0, 0, 0)` by the
// specification itself.

/**
 * Decodes block rows `[firstBlockRow, firstBlockRow + blockRowCount)` of [image]'s level [level]
 * into [out] as tightly packed RGBA8 rows, the first written row being that first block row's top
 * texel row. Texels past the level's own width and height -- a partial block's padding -- are dropped.
 */
internal fun decodeEtc2Rows(
    image: CompressedImage,
    level: Int,
    firstBlockRow: Int,
    blockRowCount: Int,
    out: ByteArray,
) {
    val width = image.levelWidth(level)
    val height = image.levelHeight(level)
    val blocksAcross = (width + 3) / 4
    val blockBytes = image.format.blockBytes
    val texels = ByteArray(BLOCK_RGBA_BYTES)
    image.readLevel(level) { bytes, offset, _ ->
        for (blockRow in firstBlockRow until firstBlockRow + blockRowCount) {
            val outBlockRow = blockRow - firstBlockRow
            val rows = minOf(4, height - blockRow * 4)
            for (blockColumn in 0 until blocksAcross) {
                decodeEtc2Block(
                    image.format,
                    bytes,
                    offset + (blockRow * blocksAcross + blockColumn) * blockBytes,
                    texels,
                )
                val columnBytes = minOf(4, width - blockColumn * 4) * 4
                for (y in 0 until rows) {
                    texels.copyInto(
                        destination = out,
                        destinationOffset = ((outBlockRow * 4 + y) * width + blockColumn * 4) * 4,
                        startIndex = y * 16,
                        endIndex = y * 16 + columnBytes,
                    )
                }
            }
        }
    }
}

/**
 * Decodes the one [format] block at [offset] in [bytes] into [out]'s 64 bytes: 4x4 RGBA8 texels in
 * row-major order, top row first.
 */
internal fun decodeEtc2Block(format: Etc2Format, bytes: ByteArray, offset: Int, out: ByteArray) {
    when (format) {
        Etc2Format.RGB8 -> decodeColourBlock(readBlock(bytes, offset), punchThrough = false, out)
        Etc2Format.RGB8_PUNCHTHROUGH_ALPHA1 -> decodeColourBlock(readBlock(bytes, offset), punchThrough = true, out)
        Etc2Format.RGBA8_EAC -> {
            decodeColourBlock(readBlock(bytes, offset + 8), punchThrough = false, out)
            decodeEacAlpha(readBlock(bytes, offset), out)
        }
    }
}

/** A block's eight bytes as one big-endian 64-bit word, so the specification's bit numbers index it. */
private fun readBlock(bytes: ByteArray, offset: Int): Long {
    var word = 0L
    for (index in 0 until 8) word = (word shl 8) or (bytes[offset + index].toLong() and 0xFF)
    return word
}

private fun Long.bits(high: Int, low: Int): Int = ((this ushr low) and ((1L shl (high - low + 1)) - 1L)).toInt()

private fun Long.bit(index: Int): Int = ((this ushr index) and 1L).toInt()

/** A texel's two-bit index: its most significant bit at `16 + i` and its least at `i`, `i = 4x + y`. */
private fun Long.pixelIndex(x: Int, y: Int): Int {
    val i = x * 4 + y
    return (bit(16 + i) shl 1) or bit(i)
}

private fun signed3(value: Int): Int = if (value >= 4) value - 8 else value

private fun extend4(value: Int): Int = (value shl 4) or value

private fun extend5(value: Int): Int = (value shl 3) or (value shr 2)

private fun extend6(value: Int): Int = (value shl 2) or (value shr 4)

private fun extend7(value: Int): Int = (value shl 1) or (value shr 6)

private fun clamp255(value: Int): Int = if (value < 0) 0 else if (value > 255) 255 else value

private fun ByteArray.putTexel(x: Int, y: Int, red: Int, green: Int, blue: Int, alpha: Int) {
    val at = (y * 4 + x) * 4
    this[at] = red.toByte()
    this[at + 1] = green.toByte()
    this[at + 2] = blue.toByte()
    this[at + 3] = alpha.toByte()
}

/**
 * The colour half of every format. Bit 33 is the differential bit of `RGB8` and the opaque bit of
 * punch-through, which has no individual mode; in the differential family an overflowing red, green or
 * blue sum selects T, H or planar mode, in that order of precedence.
 */
private fun decodeColourBlock(block: Long, punchThrough: Boolean, out: ByteArray) {
    val flag = block.bit(33)
    if (!punchThrough && flag == 0) {
        decodeSubblocks(block, differential = false, opaque = true, out)
        return
    }
    val opaque = !punchThrough || flag == 1
    val red = block.bits(63, 59) + signed3(block.bits(58, 56))
    val green = block.bits(55, 51) + signed3(block.bits(50, 48))
    val blue = block.bits(47, 43) + signed3(block.bits(42, 40))
    when {
        red !in 0..31 -> decodeT(block, opaque, out)
        green !in 0..31 -> decodeH(block, opaque, out)
        blue !in 0..31 -> decodePlanar(block, out)
        else -> decodeSubblocks(block, differential = true, opaque, out)
    }
}

/** ETC1's two half-block modes: a base colour per half, shifted per texel by a table modifier. */
private fun decodeSubblocks(block: Long, differential: Boolean, opaque: Boolean, out: ByteArray) {
    val red1: Int
    val green1: Int
    val blue1: Int
    val red2: Int
    val green2: Int
    val blue2: Int
    if (differential) {
        val r = block.bits(63, 59)
        val g = block.bits(55, 51)
        val b = block.bits(47, 43)
        red1 = extend5(r)
        green1 = extend5(g)
        blue1 = extend5(b)
        red2 = extend5(r + signed3(block.bits(58, 56)))
        green2 = extend5(g + signed3(block.bits(50, 48)))
        blue2 = extend5(b + signed3(block.bits(42, 40)))
    } else {
        red1 = extend4(block.bits(63, 60))
        red2 = extend4(block.bits(59, 56))
        green1 = extend4(block.bits(55, 52))
        green2 = extend4(block.bits(51, 48))
        blue1 = extend4(block.bits(47, 44))
        blue2 = extend4(block.bits(43, 40))
    }
    val table1 = block.bits(39, 37)
    val table2 = block.bits(36, 34)
    val flipped = block.bit(32) == 1
    for (y in 0 until 4) {
        for (x in 0 until 4) {
            val index = block.pixelIndex(x, y)
            if (!opaque && index == TRANSPARENT_INDEX) {
                out.putTexel(x, y, 0, 0, 0, 0)
                continue
            }
            val second = if (flipped) y >= 2 else x >= 2
            val table = if (second) table2 else table1
            val small = if (opaque) SMALL_MODIFIERS[table] else 0
            val large = LARGE_MODIFIERS[table]
            // Index 00b, 01b, 10b, 11b: +a, +b, -a, -b.
            val modifier = when (index) {
                0 -> small
                1 -> large
                2 -> -small
                else -> -large
            }
            out.putTexel(
                x, y,
                clamp255((if (second) red2 else red1) + modifier),
                clamp255((if (second) green2 else green1) + modifier),
                clamp255((if (second) blue2 else blue1) + modifier),
                255,
            )
        }
    }
}

/** T mode: paint colours `c1`, `c2 + d`, `c2`, `c2 - d`. */
private fun decodeT(block: Long, opaque: Boolean, out: ByteArray) {
    val red1 = extend4((block.bits(60, 59) shl 2) or block.bits(57, 56))
    val green1 = extend4(block.bits(55, 52))
    val blue1 = extend4(block.bits(51, 48))
    val red2 = extend4(block.bits(47, 44))
    val green2 = extend4(block.bits(43, 40))
    val blue2 = extend4(block.bits(39, 36))
    val distance = DISTANCES[(block.bits(35, 34) shl 1) or block.bit(32)]
    paint(
        block, opaque, out,
        intArrayOf(
            red1, green1, blue1,
            red2 + distance, green2 + distance, blue2 + distance,
            red2, green2, blue2,
            red2 - distance, green2 - distance, blue2 - distance,
        ),
    )
}

/**
 * H mode: paint colours `c1 + d`, `c1 - d`, `c2 + d`, `c2 - d`. The distance index's lowest bit is
 * not stored: it is one exactly when the first base colour, read as a 12-bit `RGB` value, is not
 * smaller than the second.
 */
private fun decodeH(block: Long, opaque: Boolean, out: ByteArray) {
    val r1 = block.bits(62, 59)
    val g1 = (block.bits(58, 56) shl 1) or block.bit(52)
    val b1 = (block.bit(51) shl 3) or block.bits(49, 47)
    val r2 = block.bits(46, 43)
    val g2 = block.bits(42, 39)
    val b2 = block.bits(38, 35)
    val ordering = if (((r1 shl 8) or (g1 shl 4) or b1) >= ((r2 shl 8) or (g2 shl 4) or b2)) 1 else 0
    val distance = DISTANCES[(block.bit(34) shl 2) or (block.bit(32) shl 1) or ordering]
    val red1 = extend4(r1)
    val green1 = extend4(g1)
    val blue1 = extend4(b1)
    val red2 = extend4(r2)
    val green2 = extend4(g2)
    val blue2 = extend4(b2)
    paint(
        block, opaque, out,
        intArrayOf(
            red1 + distance, green1 + distance, blue1 + distance,
            red1 - distance, green1 - distance, blue1 - distance,
            red2 + distance, green2 + distance, blue2 + distance,
            red2 - distance, green2 - distance, blue2 - distance,
        ),
    )
}

/** Writes each texel as the paint colour its index names; [colours] holds four unclamped RGB triples. */
private fun paint(block: Long, opaque: Boolean, out: ByteArray, colours: IntArray) {
    for (y in 0 until 4) {
        for (x in 0 until 4) {
            val index = block.pixelIndex(x, y)
            if (!opaque && index == TRANSPARENT_INDEX) {
                out.putTexel(x, y, 0, 0, 0, 0)
            } else {
                out.putTexel(
                    x, y,
                    clamp255(colours[index * 3]),
                    clamp255(colours[index * 3 + 1]),
                    clamp255(colours[index * 3 + 2]),
                    255,
                )
            }
        }
    }
}

/**
 * Planar mode: an origin, horizontal and vertical colour, each texel
 * `clamp((x(H - O) + y(V - O) + 4O + 2) >> 2)`. Always opaque, in punch-through too.
 */
private fun decodePlanar(block: Long, out: ByteArray) {
    val redO = extend6(block.bits(62, 57))
    val greenO = extend7((block.bit(56) shl 6) or block.bits(54, 49))
    val blueO = extend6((block.bit(48) shl 5) or (block.bits(44, 43) shl 3) or block.bits(41, 39))
    val redH = extend6((block.bits(38, 34) shl 1) or block.bit(32))
    val greenH = extend7(block.bits(31, 25))
    val blueH = extend6(block.bits(24, 19))
    val redV = extend6(block.bits(18, 13))
    val greenV = extend7(block.bits(12, 6))
    val blueV = extend6(block.bits(5, 0))
    for (y in 0 until 4) {
        for (x in 0 until 4) {
            out.putTexel(
                x, y,
                clamp255((x * (redH - redO) + y * (redV - redO) + 4 * redO + 2) shr 2),
                clamp255((x * (greenH - greenO) + y * (greenV - greenO) + 4 * greenO + 2) shr 2),
                clamp255((x * (blueH - blueO) + y * (blueV - blueO) + 4 * blueO + 2) shr 2),
                255,
            )
        }
    }
}

/** EAC alpha: `clamp(base + modifier * multiplier)`, with a 3-bit index per texel from bit 47 down. */
private fun decodeEacAlpha(block: Long, out: ByteArray) {
    val base = block.bits(63, 56)
    val multiplier = block.bits(55, 52)
    val table = block.bits(51, 48) * 8
    for (x in 0 until 4) {
        for (y in 0 until 4) {
            val i = x * 4 + y
            val index = block.bits(47 - 3 * i, 45 - 3 * i)
            out[(y * 4 + x) * 4 + 3] = clamp255(base + EAC_MODIFIERS[table + index] * multiplier).toByte()
        }
    }
}

private const val BLOCK_RGBA_BYTES: Int = 64
private const val TRANSPARENT_INDEX: Int = 2

private val SMALL_MODIFIERS: IntArray = intArrayOf(2, 5, 9, 13, 18, 24, 33, 47)
private val LARGE_MODIFIERS: IntArray = intArrayOf(8, 17, 29, 42, 60, 80, 106, 183)
private val DISTANCES: IntArray = intArrayOf(3, 6, 11, 16, 23, 32, 41, 64)

private val EAC_MODIFIERS: IntArray = intArrayOf(
    -3, -6, -9, -15, 2, 5, 8, 14,
    -3, -7, -10, -13, 2, 6, 9, 12,
    -2, -5, -8, -13, 1, 4, 7, 12,
    -2, -4, -6, -13, 1, 3, 5, 12,
    -3, -6, -8, -12, 2, 5, 7, 11,
    -3, -7, -9, -11, 2, 6, 8, 10,
    -4, -7, -8, -11, 3, 6, 7, 10,
    -3, -5, -8, -11, 2, 4, 7, 10,
    -2, -6, -8, -10, 1, 5, 7, 9,
    -2, -5, -8, -10, 1, 4, 7, 9,
    -2, -4, -8, -10, 1, 3, 7, 9,
    -2, -5, -7, -10, 1, 4, 6, 9,
    -3, -4, -7, -10, 2, 3, 6, 9,
    -1, -2, -3, -10, 0, 1, 2, 9,
    -4, -6, -8, -9, 3, 5, 7, 8,
    -3, -5, -7, -9, 2, 4, 6, 8,
)
