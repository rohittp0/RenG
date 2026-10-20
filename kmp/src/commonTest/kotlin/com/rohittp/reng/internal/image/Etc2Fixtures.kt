package com.rohittp.reng.internal.image

/**
 * A RenG ETC2 container (ADR 0084) assembled field by field from the specification rather than by the
 * code under test: a little-endian header, then every level filled with [block]'s bytes for that level,
 * repeated once per 4x4 block.
 */
internal fun etc2Container(
    formatCode: Int,
    width: Int,
    height: Int,
    levelCount: Int = 1,
    block: (level: Int) -> ByteArray,
): ByteArray {
    var payload = ByteArray(0)
    for (level in 0 until levelCount) {
        val levelWidth = maxOf(1, width shr level)
        val levelHeight = maxOf(1, height shr level)
        val blocks = ((levelWidth + 3) / 4) * ((levelHeight + 3) / 4)
        val one = block(level)
        repeat(blocks) { payload += one }
    }
    return etc2Header(formatCode, levelCount, width.toLong(), height.toLong()) + payload
}

internal fun etc2Header(
    formatCode: Int,
    levelCount: Int,
    width: Long,
    height: Long,
    version: Int = 1,
    reserved: Int = 0,
    magic: String = "RET2",
): ByteArray = magic.encodeToByteArray() +
    byteArrayOf(version.toByte(), formatCode.toByte(), levelCount.toByte(), reserved.toByte()) +
    littleEndianU32(width) + littleEndianU32(height)

private fun littleEndianU32(value: Long): ByteArray = ByteArray(4) { (value ushr (8 * it)).toByte() }

/**
 * One 64-bit ETC2 or EAC block from `(highBit, lowBit, value)` fields, numbered as the specification
 * numbers them -- bit 63 is the first byte's most significant bit.
 */
internal fun etc2Block(vararg fields: Triple<Int, Int, Int>): ByteArray {
    var word = 0L
    for ((high, low, value) in fields) {
        val width = high - low + 1
        require(value in 0 until (1 shl width)) { "field $high..$low cannot hold $value" }
        word = word or (value.toLong() shl low)
    }
    return ByteArray(8) { (word ushr (56 - 8 * it)).toByte() }
}

/** The 32 index bits of a colour block: texel `(x, y)`'s two bits at `16 + 4x + y` and `4x + y`. */
internal fun colourIndices(vararg texels: Triple<Int, Int, Int>): Array<Triple<Int, Int, Int>> =
    texels.flatMap { (x, y, index) ->
        val i = x * 4 + y
        listOf(Triple(16 + i, 16 + i, index shr 1), Triple(i, i, index and 1))
    }.toTypedArray()

/** The 48 index bits of an EAC block: texel `(x, y)`'s three bits from `47 - 3(4x + y)` down. */
internal fun eacIndices(vararg texels: Triple<Int, Int, Int>): Array<Triple<Int, Int, Int>> =
    texels.map { (x, y, index) ->
        val i = x * 4 + y
        Triple(47 - 3 * i, 45 - 3 * i, index)
    }.toTypedArray()
