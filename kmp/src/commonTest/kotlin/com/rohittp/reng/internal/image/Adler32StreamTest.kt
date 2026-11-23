package com.rohittp.reng.internal.image

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Adler32Stream] is the platform's zlib; this holds it to RFC 1950 written out per byte, over the shapes
 * the stored-PNG fast path feeds it: many row-sized updates chained, empty updates, and long runs of 0xFF,
 * where a 32-bit signed sum would have overflowed.
 */
class Adler32StreamTest {

    private fun reference(chunks: List<ByteArray>): Long {
        var a = 1L
        var b = 0L
        for (chunk in chunks) {
            for (byte in chunk) {
                a = (a + (byte.toInt() and 0xFF)) % 65521
                b = (b + a) % 65521
            }
        }
        return (b shl 16) or a
    }

    private fun platform(chunks: List<ByteArray>): Long {
        val stream = Adler32Stream()
        chunks.forEach { stream.update(it, 0, it.size) }
        return stream.value
    }

    @Test
    fun no_bytes_is_one() {
        assertEquals(1L, Adler32Stream().value)
        assertEquals(1L, platform(listOf(ByteArray(0))))
    }

    @Test
    fun chained_rows_of_random_bytes_match_the_reference() {
        val random = Random(20261009)
        repeat(20) {
            val chunks = List(random.nextInt(1, 40)) { random.nextBytes(random.nextInt(0, 9000)) }
            assertEquals(reference(chunks), platform(chunks))
        }
    }

    @Test
    fun long_runs_of_high_bytes_match_the_reference() {
        val chunks = List(5) { ByteArray(8001) { -1 } }
        assertEquals(reference(chunks), platform(chunks))
    }

    @Test
    fun a_range_inside_a_larger_array_is_only_that_range() {
        val bytes = Random(7).nextBytes(1000)
        val stream = Adler32Stream()
        stream.update(bytes, 100, 300)
        assertEquals(reference(listOf(bytes.copyOfRange(100, 400))), stream.value)
    }
}
