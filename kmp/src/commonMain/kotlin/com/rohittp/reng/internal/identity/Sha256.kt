package com.rohittp.reng.internal.identity

internal object PureKotlinSha256 : Sha256Function {
    override fun digest(bytes: CanonicalBytes): Sha256Digest {
        val source = bytes.byteSnapshot
        val state = INITIAL_STATE.copyOf()
        val schedule = IntArray(SCHEDULE_WORDS)

        var offset = 0
        while (source.size - offset >= BLOCK_BYTES) {
            compress(source, offset, state, schedule)
            offset += BLOCK_BYTES
        }

        // Build the padded final block directly in the reused schedule. This avoids allocating and
        // then rereading a 64- or 128-byte tail for every digest.
        schedule.fill(0)
        val remainder = source.size - offset
        for (index in 0 until remainder) {
            val shift = 24 - (index and 3) * Byte.SIZE_BITS
            schedule[index ushr 2] = schedule[index ushr 2] or
                ((source[offset + index].toInt() and 0xff) shl shift)
        }
        val paddingShift = 24 - (remainder and 3) * Byte.SIZE_BITS
        schedule[remainder ushr 2] = schedule[remainder ushr 2] or (0x80 shl paddingShift)
        val bitLength = source.size.toLong() * Byte.SIZE_BITS.toLong()
        if (remainder > MAX_SINGLE_BLOCK_REMAINDER) {
            compressSchedule(state, schedule)
            schedule.fill(0)
        }
        schedule[14] = (bitLength ushr Int.SIZE_BITS).toInt()
        schedule[15] = bitLength.toInt()
        compressSchedule(state, schedule)

        val digest = ByteArray(DIGEST_BYTES)
        state.forEachIndexed { index, word ->
            writeIntBigEndian(word, digest, index * Int.SIZE_BYTES)
        }
        return Sha256Digest.adopting(digest)
    }

    private fun compress(
        block: ByteArray,
        blockOffset: Int,
        state: IntArray,
        schedule: IntArray,
    ) {
        schedule[0] = readIntBigEndian(block, blockOffset)
        schedule[1] = readIntBigEndian(block, blockOffset + 4)
        schedule[2] = readIntBigEndian(block, blockOffset + 8)
        schedule[3] = readIntBigEndian(block, blockOffset + 12)
        schedule[4] = readIntBigEndian(block, blockOffset + 16)
        schedule[5] = readIntBigEndian(block, blockOffset + 20)
        schedule[6] = readIntBigEndian(block, blockOffset + 24)
        schedule[7] = readIntBigEndian(block, blockOffset + 28)
        schedule[8] = readIntBigEndian(block, blockOffset + 32)
        schedule[9] = readIntBigEndian(block, blockOffset + 36)
        schedule[10] = readIntBigEndian(block, blockOffset + 40)
        schedule[11] = readIntBigEndian(block, blockOffset + 44)
        schedule[12] = readIntBigEndian(block, blockOffset + 48)
        schedule[13] = readIntBigEndian(block, blockOffset + 52)
        schedule[14] = readIntBigEndian(block, blockOffset + 56)
        schedule[15] = readIntBigEndian(block, blockOffset + 60)
        compressSchedule(state, schedule)
    }

    private fun compressSchedule(state: IntArray, schedule: IntArray) {
        for (index in INITIAL_SCHEDULE_WORDS until SCHEDULE_WORDS) {
            val first = schedule[index - 15]
            val second = schedule[index - 2]
            val sigma0 = rotateRight(first, 7) xor rotateRight(first, 18) xor (first ushr 3)
            val sigma1 = rotateRight(second, 17) xor rotateRight(second, 19) xor (second ushr 10)
            schedule[index] = schedule[index - 16] + sigma0 + schedule[index - 7] + sigma1
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        for (index in 0 until SCHEDULE_WORDS) {
            val sum1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
            val choose = (e and f) xor (e.inv() and g)
            val temporary1 = h + sum1 + choose + ROUND_CONSTANTS[index] + schedule[index]
            val sum0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temporary2 = sum0 + majority

            h = g
            g = f
            f = e
            e = d + temporary1
            d = c
            c = b
            b = a
            a = temporary1 + temporary2
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    private fun readIntBigEndian(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private fun writeIntBigEndian(value: Int, destination: ByteArray, offset: Int) {
        destination[offset] = (value ushr 24).toByte()
        destination[offset + 1] = (value ushr 16).toByte()
        destination[offset + 2] = (value ushr 8).toByte()
        destination[offset + 3] = value.toByte()
    }

    private fun rotateRight(value: Int, distance: Int): Int =
        value.rotateRight(distance)

    private const val BLOCK_BYTES: Int = 64
    private const val DIGEST_BYTES: Int = 32
    private const val INITIAL_SCHEDULE_WORDS: Int = 16
    private const val SCHEDULE_WORDS: Int = 64
    private const val MAX_SINGLE_BLOCK_REMAINDER: Int = 55

    private val INITIAL_STATE: IntArray = intArrayOf(
        0x6a09e667u.toInt(),
        0xbb67ae85u.toInt(),
        0x3c6ef372u.toInt(),
        0xa54ff53au.toInt(),
        0x510e527fu.toInt(),
        0x9b05688cu.toInt(),
        0x1f83d9abu.toInt(),
        0x5be0cd19u.toInt(),
    )

    private val ROUND_CONSTANTS: IntArray = intArrayOf(
        0x428a2f98u.toInt(), 0x71374491u.toInt(), 0xb5c0fbcfu.toInt(), 0xe9b5dba5u.toInt(),
        0x3956c25bu.toInt(), 0x59f111f1u.toInt(), 0x923f82a4u.toInt(), 0xab1c5ed5u.toInt(),
        0xd807aa98u.toInt(), 0x12835b01u.toInt(), 0x243185beu.toInt(), 0x550c7dc3u.toInt(),
        0x72be5d74u.toInt(), 0x80deb1feu.toInt(), 0x9bdc06a7u.toInt(), 0xc19bf174u.toInt(),
        0xe49b69c1u.toInt(), 0xefbe4786u.toInt(), 0x0fc19dc6u.toInt(), 0x240ca1ccu.toInt(),
        0x2de92c6fu.toInt(), 0x4a7484aau.toInt(), 0x5cb0a9dcu.toInt(), 0x76f988dau.toInt(),
        0x983e5152u.toInt(), 0xa831c66du.toInt(), 0xb00327c8u.toInt(), 0xbf597fc7u.toInt(),
        0xc6e00bf3u.toInt(), 0xd5a79147u.toInt(), 0x06ca6351u.toInt(), 0x14292967u.toInt(),
        0x27b70a85u.toInt(), 0x2e1b2138u.toInt(), 0x4d2c6dfcu.toInt(), 0x53380d13u.toInt(),
        0x650a7354u.toInt(), 0x766a0abbu.toInt(), 0x81c2c92eu.toInt(), 0x92722c85u.toInt(),
        0xa2bfe8a1u.toInt(), 0xa81a664bu.toInt(), 0xc24b8b70u.toInt(), 0xc76c51a3u.toInt(),
        0xd192e819u.toInt(), 0xd6990624u.toInt(), 0xf40e3585u.toInt(), 0x106aa070u.toInt(),
        0x19a4c116u.toInt(), 0x1e376c08u.toInt(), 0x2748774cu.toInt(), 0x34b0bcb5u.toInt(),
        0x391c0cb3u.toInt(), 0x4ed8aa4au.toInt(), 0x5b9cca4fu.toInt(), 0x682e6ff3u.toInt(),
        0x748f82eeu.toInt(), 0x78a5636fu.toInt(), 0x84c87814u.toInt(), 0x8cc70208u.toInt(),
        0x90befffau.toInt(), 0xa4506cebu.toInt(), 0xbef9a3f7u.toInt(), 0xc67178f2u.toInt(),
    )
}
