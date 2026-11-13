package com.rohittp.reng.internal.image

/** A small RFC 1950/1951 inflater used where the browser exposes no synchronous zlib API. */
internal actual class InflateStream actual constructor() {
    private var state: State = State.ZLIB_HEADER
    private var bitBuffer: Long = 0L
    private var bitCount: Int = 0
    private var headerCmf: Int = -1
    private var finalBlock: Boolean = false
    private var storedRemaining: Int = 0
    private var literalTree: Huffman? = null
    private var distanceTree: Huffman? = null

    private var dynamicPhase: Int = 0
    private var dynamicLiteralCount: Int = 0
    private var dynamicDistanceCount: Int = 0
    private var dynamicCodeLengthCount: Int = 0
    private val codeLengthLengths = IntArray(19)
    private var codeLengthIndex: Int = 0
    private var codeLengthTree: Huffman? = null
    private var dynamicLengths = IntArray(0)
    private var dynamicLengthIndex: Int = 0
    private var pendingRepeatSymbol: Int = -1

    private var compressedPhase: Int = 0
    private var lengthBase: Int = 0
    private var lengthExtraBits: Int = 0
    private var matchLength: Int = 0
    private var distanceBase: Int = 0
    private var distanceExtraBits: Int = 0
    private var matchDistance: Int = 0
    private var pendingLiteral: Int = -1

    private val history = ByteArray(WINDOW_SIZE)
    private var historyPosition: Int = 0
    private var historySize: Int = 0
    private var adlerA: Int = 1
    private var adlerB: Int = 0
    private var trailerValue: Long = 0L
    private var trailerBytes: Int = 0

    actual fun inflate(
        input: ByteArray,
        inputOffset: Int,
        inputLength: Int,
        output: ByteArray,
        outputOffset: Int,
        outputLength: Int,
    ): InflateStep {
        require(state != State.CLOSED) { "inflate stream is closed" }
        require(inputOffset >= 0 && inputLength >= 0 && inputOffset <= input.size - inputLength) {
            "input range out of bounds"
        }
        require(outputOffset >= 0 && outputLength >= 0 && outputOffset <= output.size - outputLength) {
            "output range out of bounds"
        }
        if (state == State.FINISHED) return InflateStep(0, 0, true)

        val cursor = InputCursor(input, inputOffset, inputOffset + inputLength)
        var outputIndex = outputOffset
        val outputEnd = outputOffset + outputLength

        fun result(): InflateStep = InflateStep(
            consumed = cursor.index - inputOffset,
            produced = outputIndex - outputOffset,
            finished = state == State.FINISHED,
        )

        fun emit(value: Int): Boolean {
            if (outputIndex >= outputEnd) return false
            val byte = value.toByte()
            output[outputIndex++] = byte
            history[historyPosition] = byte
            historyPosition = (historyPosition + 1) and (WINDOW_SIZE - 1)
            if (historySize < WINDOW_SIZE) historySize += 1
            adlerA += value
            if (adlerA >= ADLER_MODULUS) adlerA -= ADLER_MODULUS
            adlerB += adlerA
            if (adlerB >= ADLER_MODULUS) adlerB -= ADLER_MODULUS
            return true
        }

        while (true) {
            when (state) {
                State.ZLIB_HEADER -> {
                    if (headerCmf < 0) headerCmf = readAlignedByte(cursor) ?: return result()
                    val flg = readAlignedByte(cursor) ?: return result()
                    if ((headerCmf and 0x0F) != 8 || (headerCmf ushr 4) > 7) {
                        fail("unsupported zlib compression method or window")
                    }
                    if (((headerCmf shl 8) + flg) % 31 != 0) fail("invalid zlib header check")
                    if ((flg and 0x20) != 0) fail("preset zlib dictionaries are unsupported")
                    headerCmf = -1
                    state = State.BLOCK_HEADER
                }

                State.BLOCK_HEADER -> {
                    val header = readBits(3, cursor) ?: return result()
                    finalBlock = (header and 1) != 0
                    when (header ushr 1) {
                        0 -> {
                            alignToByte()
                            state = State.STORED_LENGTH
                        }
                        1 -> {
                            literalTree = FIXED_LITERAL_TREE
                            distanceTree = FIXED_DISTANCE_TREE
                            compressedPhase = 0
                            state = State.COMPRESSED
                        }
                        2 -> {
                            resetDynamicBlock()
                            state = State.DYNAMIC_COUNTS
                        }
                        else -> fail("reserved deflate block type")
                    }
                }

                State.STORED_LENGTH -> {
                    val length = readBits(16, cursor) ?: return result()
                    val complement = readBits(16, cursor) ?: run {
                        storedRemaining = length
                        state = State.STORED_COMPLEMENT
                        return result()
                    }
                    if ((length xor 0xFFFF) != complement) fail("stored block length check failed")
                    storedRemaining = length
                    state = State.STORED_DATA
                }

                State.STORED_COMPLEMENT -> {
                    val complement = readBits(16, cursor) ?: return result()
                    if ((storedRemaining xor 0xFFFF) != complement) fail("stored block length check failed")
                    state = State.STORED_DATA
                }

                State.STORED_DATA -> {
                    while (storedRemaining > 0) {
                        if (outputIndex >= outputEnd) return result()
                        val value = readAlignedByte(cursor) ?: return result()
                        emit(value)
                        storedRemaining -= 1
                    }
                    finishBlock()
                }

                State.DYNAMIC_COUNTS -> {
                    when (dynamicPhase) {
                        0 -> {
                            dynamicLiteralCount = (readBits(5, cursor) ?: return result()) + 257
                            dynamicPhase = 1
                        }
                        1 -> {
                            dynamicDistanceCount = (readBits(5, cursor) ?: return result()) + 1
                            dynamicPhase = 2
                        }
                        else -> {
                            dynamicCodeLengthCount = (readBits(4, cursor) ?: return result()) + 4
                            state = State.DYNAMIC_CODE_LENGTHS
                        }
                    }
                }

                State.DYNAMIC_CODE_LENGTHS -> {
                    while (codeLengthIndex < dynamicCodeLengthCount) {
                        val length = readBits(3, cursor) ?: return result()
                        codeLengthLengths[CODE_LENGTH_ORDER[codeLengthIndex++]] = length
                    }
                    codeLengthTree = Huffman.fromLengths(codeLengthLengths, "code-length")
                    dynamicLengths = IntArray(dynamicLiteralCount + dynamicDistanceCount)
                    state = State.DYNAMIC_LENGTHS
                }

                State.DYNAMIC_LENGTHS -> {
                    while (dynamicLengthIndex < dynamicLengths.size) {
                        val symbol = if (pendingRepeatSymbol >= 0) {
                            pendingRepeatSymbol
                        } else {
                            codeLengthTree!!.decode(this, cursor) ?: return result()
                        }
                        if (symbol in 0..15) {
                            dynamicLengths[dynamicLengthIndex++] = symbol
                            pendingRepeatSymbol = -1
                            continue
                        }
                        pendingRepeatSymbol = symbol
                        val extraBits: Int
                        val base: Int
                        val repeated: Int
                        when (symbol) {
                            16 -> {
                                if (dynamicLengthIndex == 0) fail("repeat code has no previous length")
                                extraBits = 2
                                base = 3
                                repeated = dynamicLengths[dynamicLengthIndex - 1]
                            }
                            17 -> {
                                extraBits = 3
                                base = 3
                                repeated = 0
                            }
                            18 -> {
                                extraBits = 7
                                base = 11
                                repeated = 0
                            }
                            else -> fail("invalid code-length symbol $symbol")
                        }
                        val count = base + (readBits(extraBits, cursor) ?: return result())
                        if (dynamicLengthIndex + count > dynamicLengths.size) fail("code-length repeat overrun")
                        repeat(count) { dynamicLengths[dynamicLengthIndex++] = repeated }
                        pendingRepeatSymbol = -1
                    }
                    val literalLengths = dynamicLengths.copyOfRange(0, dynamicLiteralCount)
                    if (literalLengths[256] == 0) fail("dynamic block has no end-of-block code")
                    literalTree = Huffman.fromLengths(literalLengths, "literal/length")
                    distanceTree = Huffman.fromLengths(
                        dynamicLengths.copyOfRange(dynamicLiteralCount, dynamicLengths.size),
                        "distance",
                    )
                    compressedPhase = 0
                    state = State.COMPRESSED
                }

                State.COMPRESSED -> {
                    if (pendingLiteral >= 0) {
                        if (!emit(pendingLiteral)) return result()
                        pendingLiteral = -1
                    }
                    while (compressedPhase == 0 && matchLength > 0) {
                        if (outputIndex >= outputEnd) return result()
                        if (matchDistance <= 0 || matchDistance > historySize) fail("invalid deflate distance")
                        val source = (historyPosition - matchDistance) and (WINDOW_SIZE - 1)
                        emit(history[source].toInt() and 0xFF)
                        matchLength -= 1
                    }

                    when (compressedPhase) {
                        0 -> {
                            val symbol = literalTree!!.decode(this, cursor) ?: return result()
                            when {
                                symbol < 256 -> pendingLiteral = symbol
                                symbol == 256 -> finishBlock()
                                symbol in 257..285 -> {
                                    val index = symbol - 257
                                    lengthBase = LENGTH_BASE[index]
                                    lengthExtraBits = LENGTH_EXTRA[index]
                                    compressedPhase = 1
                                }
                                else -> fail("invalid literal/length symbol $symbol")
                            }
                        }
                        1 -> {
                            matchLength = lengthBase + (readBits(lengthExtraBits, cursor) ?: return result())
                            compressedPhase = 2
                        }
                        2 -> {
                            val symbol = distanceTree!!.decode(this, cursor) ?: return result()
                            if (symbol !in 0..29) fail("invalid distance symbol $symbol")
                            distanceBase = DISTANCE_BASE[symbol]
                            distanceExtraBits = DISTANCE_EXTRA[symbol]
                            compressedPhase = 3
                        }
                        else -> {
                            matchDistance = distanceBase + (readBits(distanceExtraBits, cursor) ?: return result())
                            compressedPhase = 0
                        }
                    }
                }

                State.TRAILER -> {
                    while (trailerBytes < 4) {
                        val value = readAlignedByte(cursor) ?: return result()
                        trailerValue = (trailerValue shl 8) or value.toLong()
                        trailerBytes += 1
                    }
                    val actual = ((adlerB.toLong() shl 16) or adlerA.toLong()) and 0xFFFF_FFFFL
                    if (trailerValue != actual) fail("zlib Adler-32 check failed")
                    state = State.FINISHED
                    return result()
                }

                State.FINISHED -> return result()
                State.CLOSED -> error("unreachable")
            }
        }
    }

    actual fun close() {
        state = State.CLOSED
    }

    private fun resetDynamicBlock() {
        dynamicPhase = 0
        dynamicLiteralCount = 0
        dynamicDistanceCount = 0
        dynamicCodeLengthCount = 0
        codeLengthLengths.fill(0)
        codeLengthIndex = 0
        codeLengthTree = null
        dynamicLengths = IntArray(0)
        dynamicLengthIndex = 0
        pendingRepeatSymbol = -1
    }

    private fun finishBlock() {
        if (finalBlock) {
            alignToByte()
            trailerValue = 0L
            trailerBytes = 0
            state = State.TRAILER
        } else {
            state = State.BLOCK_HEADER
        }
    }

    private fun ensureBits(count: Int, cursor: InputCursor): Boolean {
        while (bitCount < count) {
            val next = cursor.nextByte() ?: return false
            bitBuffer = bitBuffer or (next.toLong() shl bitCount)
            bitCount += 8
        }
        return true
    }

    private fun readBits(count: Int, cursor: InputCursor): Int? {
        if (count == 0) return 0
        if (!ensureBits(count, cursor)) return null
        val mask = (1L shl count) - 1L
        val value = (bitBuffer and mask).toInt()
        bitBuffer = bitBuffer ushr count
        bitCount -= count
        return value
    }

    private fun readAlignedByte(cursor: InputCursor): Int? {
        check(bitCount % 8 == 0) { "unaligned byte read" }
        return readBits(8, cursor)
    }

    private fun alignToByte() {
        val discard = bitCount and 7
        bitBuffer = bitBuffer ushr discard
        bitCount -= discard
    }

    private fun fail(message: String): Nothing = throw InflateException(message)

    private enum class State {
        ZLIB_HEADER,
        BLOCK_HEADER,
        STORED_LENGTH,
        STORED_COMPLEMENT,
        STORED_DATA,
        DYNAMIC_COUNTS,
        DYNAMIC_CODE_LENGTHS,
        DYNAMIC_LENGTHS,
        COMPRESSED,
        TRAILER,
        FINISHED,
        CLOSED,
    }

    private class InputCursor(
        private val bytes: ByteArray,
        var index: Int,
        private val end: Int,
    ) {
        fun nextByte(): Int? = if (index < end) bytes[index++].toInt() and 0xFF else null
    }

    private class Huffman private constructor(
        private val symbolsByLength: Array<IntArray>,
        private val maximumBits: Int,
    ) {
        fun decode(stream: InflateStream, cursor: InputCursor): Int? {
            for (length in 1..maximumBits) {
                if (!stream.ensureBits(length, cursor)) return null
                val code = (stream.bitBuffer and ((1L shl length) - 1L)).toInt()
                val symbol = symbolsByLength[length][code]
                if (symbol >= 0) {
                    stream.bitBuffer = stream.bitBuffer ushr length
                    stream.bitCount -= length
                    return symbol
                }
            }
            stream.fail("invalid Huffman code")
        }

        companion object {
            fun fromLengths(lengths: IntArray, label: String): Huffman {
                val maximumBits = lengths.maxOrNull() ?: 0
                if (maximumBits == 0 || maximumBits > 15) {
                    throw InflateException("invalid $label Huffman tree")
                }
                val counts = IntArray(maximumBits + 1)
                lengths.forEach { length ->
                    if (length < 0 || length > maximumBits) throw InflateException("invalid $label code length")
                    if (length > 0) counts[length] += 1
                }
                var remaining = 1
                for (bits in 1..maximumBits) {
                    remaining = (remaining shl 1) - counts[bits]
                    if (remaining < 0) throw InflateException("oversubscribed $label Huffman tree")
                }

                var code = 0
                val nextCode = IntArray(maximumBits + 1)
                for (bits in 1..maximumBits) {
                    code = (code + counts[bits - 1]) shl 1
                    nextCode[bits] = code
                }
                val tables = Array(maximumBits + 1) { bits ->
                    if (bits == 0) IntArray(0) else IntArray(1 shl bits) { -1 }
                }
                lengths.forEachIndexed { symbol, length ->
                    if (length > 0) {
                        val canonical = nextCode[length]++
                        tables[length][reverseBits(canonical, length)] = symbol
                    }
                }
                return Huffman(tables, maximumBits)
            }

            private fun reverseBits(value: Int, count: Int): Int {
                var source = value
                var reversed = 0
                repeat(count) {
                    reversed = (reversed shl 1) or (source and 1)
                    source = source ushr 1
                }
                return reversed
            }
        }
    }

    private companion object {
        const val WINDOW_SIZE: Int = 32768
        const val ADLER_MODULUS: Int = 65521

        val CODE_LENGTH_ORDER: IntArray = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
        val LENGTH_BASE: IntArray = intArrayOf(
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27,
            31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
        )
        val LENGTH_EXTRA: IntArray = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2,
            2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
        )
        val DISTANCE_BASE: IntArray = intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129,
            193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
            8193, 12289, 16385, 24577,
        )
        val DISTANCE_EXTRA: IntArray = intArrayOf(
            0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6,
            6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
        )

        val FIXED_LITERAL_TREE: Huffman = Huffman.fromLengths(
            IntArray(288) { symbol ->
                when (symbol) {
                    in 0..143 -> 8
                    in 144..255 -> 9
                    in 256..279 -> 7
                    else -> 8
                }
            },
            "fixed literal/length",
        )
        val FIXED_DISTANCE_TREE: Huffman = Huffman.fromLengths(IntArray(32) { 5 }, "fixed distance")
    }
}

internal actual class Adler32Stream actual constructor() {
    private var a: Int = 1
    private var b: Int = 0

    actual fun update(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "adler range out of bounds" }
        for (index in offset until offset + length) {
            a += bytes[index].toInt() and 0xFF
            if (a >= 65521) a -= 65521
            b += a
            if (b >= 65521) b -= 65521
        }
    }

    actual val value: Long get() = (b.toLong() shl 16) or a.toLong()
}

internal actual fun crc32(seed: UInt, bytes: ByteArray, offset: Int, length: Int): UInt {
    require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "crc range out of bounds" }
    var value = seed xor UInt.MAX_VALUE
    for (index in offset until offset + length) {
        value = CRC32_TABLE[((value xor bytes[index].toUByte().toUInt()) and 0xFFu).toInt()] xor (value shr 8)
    }
    return value xor UInt.MAX_VALUE
}

private val CRC32_TABLE: UIntArray = UIntArray(256) { entry ->
    var value = entry.toUInt()
    repeat(8) {
        value = if ((value and 1u) != 0u) 0xEDB88320u xor (value shr 1) else value shr 1
    }
    value
}
