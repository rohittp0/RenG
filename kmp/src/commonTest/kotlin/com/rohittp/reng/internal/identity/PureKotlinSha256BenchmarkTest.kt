package com.rohittp.reng.internal.identity

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.time.Duration
import kotlin.time.measureTime

class PureKotlinSha256PerformanceTest {
    @Test fun optimizedDigestIsByteIdenticalToTheFormerImplementation() {
        val random = Random(0x5a17)
        val sizes = buildList {
            addAll(listOf(0, 1, 2, 3, 7, 31, 32, 54, 55, 56, 57, 63, 64, 65, 127, 128, 129, 1024, 65_537))
            repeat(128) { add(random.nextInt(0, 128 * 1024)) }
            add(1024 * 1024)
        }

        for (size in sizes) {
            val input = ByteArray(size).also(random::nextBytes)
            assertContentEquals(
                FormerPureKotlinSha256.digest(CanonicalBytes(input)),
                PureKotlinSha256.digest(CanonicalBytes(input)).bytes,
                "digest differs at $size bytes",
            )
        }
    }

    @Test fun printsSixteenMiBThroughputBeforeAndAfter() {
        val input = ByteArray(16 * 1024 * 1024) { index -> (index * 31 + 7).toByte() }
        val canonical = CanonicalBytes(input)
        val warmup = CanonicalBytes(input.copyOf(256 * 1024))
        FormerPureKotlinSha256.digest(warmup)
        PureKotlinSha256.digest(warmup)

        lateinit var beforeDigest: ByteArray
        val before = measureTime { beforeDigest = FormerPureKotlinSha256.digest(canonical) }
        lateinit var afterDigest: ByteArray
        val after = measureTime { afterDigest = PureKotlinSha256.digest(canonical).bytes }
        assertContentEquals(beforeDigest, afterDigest)

        println(
            "PureKotlinSha256 16 MiB: before=${throughputMiBPerSecond(16, before)} MB/s, " +
                "after=${throughputMiBPerSecond(16, after)} MB/s",
        )
    }
}

private fun throughputMiBPerSecond(mebibytes: Int, duration: Duration): String {
    val seconds = duration.inWholeNanoseconds.toDouble() / 1_000_000_000.0
    return ((mebibytes / seconds) * 10.0).toLong().toDouble().div(10.0).toString()
}

/** The implementation replaced by this change, retained only as a bit-for-bit benchmark oracle. */
private object FormerPureKotlinSha256 {
    fun digest(bytes: CanonicalBytes): ByteArray {
        val state = INITIAL_STATE.copyOf()
        val schedule = IntArray(SCHEDULE_WORDS)

        var offset = 0
        while (bytes.size - offset >= BLOCK_BYTES) {
            compress(bytes, offset, state, schedule)
            offset += BLOCK_BYTES
        }

        val remainder = bytes.size - offset
        val tailSize = if (remainder <= MAX_SINGLE_BLOCK_REMAINDER) BLOCK_BYTES else BLOCK_BYTES * 2
        val tail = ByteArray(tailSize)
        bytes.copyRangeInto(tail, 0, offset, bytes.size)
        tail[remainder] = 0x80.toByte()
        val bitLength = bytes.size.toLong() * Byte.SIZE_BITS.toLong()
        val lengthOffset = tail.size - Long.SIZE_BYTES
        for (index in 0 until Long.SIZE_BYTES) {
            tail[lengthOffset + index] =
                (bitLength ushr ((Long.SIZE_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte()
        }

        offset = 0
        while (offset < tail.size) {
            compress(tail, offset, state, schedule)
            offset += BLOCK_BYTES
        }

        return ByteArray(DIGEST_BYTES).also { digest ->
            state.forEachIndexed { index, word -> writeIntBigEndian(word, digest, index * Int.SIZE_BYTES) }
        }
    }

    private fun compress(block: CanonicalBytes, offset: Int, state: IntArray, schedule: IntArray) {
        for (index in 0 until INITIAL_SCHEDULE_WORDS) {
            schedule[index] =
                ((block.byteAt(offset + index * 4).toInt() and 0xff) shl 24) or
                    ((block.byteAt(offset + index * 4 + 1).toInt() and 0xff) shl 16) or
                    ((block.byteAt(offset + index * 4 + 2).toInt() and 0xff) shl 8) or
                    (block.byteAt(offset + index * 4 + 3).toInt() and 0xff)
        }
        compressSchedule(state, schedule)
    }

    private fun compress(block: ByteArray, offset: Int, state: IntArray, schedule: IntArray) {
        for (index in 0 until INITIAL_SCHEDULE_WORDS) {
            schedule[index] =
                ((block[offset + index * 4].toInt() and 0xff) shl 24) or
                    ((block[offset + index * 4 + 1].toInt() and 0xff) shl 16) or
                    ((block[offset + index * 4 + 2].toInt() and 0xff) shl 8) or
                    (block[offset + index * 4 + 3].toInt() and 0xff)
        }
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

    private fun rotateRight(value: Int, distance: Int): Int =
        (value ushr distance) or (value shl (Int.SIZE_BITS - distance))

    private fun writeIntBigEndian(value: Int, destination: ByteArray, offset: Int) {
        destination[offset] = (value ushr 24).toByte()
        destination[offset + 1] = (value ushr 16).toByte()
        destination[offset + 2] = (value ushr 8).toByte()
        destination[offset + 3] = value.toByte()
    }

    private const val BLOCK_BYTES = 64
    private const val DIGEST_BYTES = 32
    private const val INITIAL_SCHEDULE_WORDS = 16
    private const val SCHEDULE_WORDS = 64
    private const val MAX_SINGLE_BLOCK_REMAINDER = 55

    private val INITIAL_STATE = intArrayOf(
        0x6a09e667u.toInt(), 0xbb67ae85u.toInt(), 0x3c6ef372u.toInt(), 0xa54ff53au.toInt(),
        0x510e527fu.toInt(), 0x9b05688cu.toInt(), 0x1f83d9abu.toInt(), 0x5be0cd19u.toInt(),
    )

    private val ROUND_CONSTANTS = intArrayOf(
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
