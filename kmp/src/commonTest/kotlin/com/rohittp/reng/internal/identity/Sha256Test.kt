package com.rohittp.reng.internal.identity

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class Sha256Test {
    @Test
    fun hashesStandardKnownAnswers() {
        val vectors = listOf(
            ByteArray(0) to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "abc".encodeToByteArray() to
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray() to
                "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        )

        vectors.forEach { (input, expected) ->
            assertEquals(expected, PureKotlinSha256.digest(CanonicalBytes(input)).lowercaseHex)
        }
    }

    @Test
    fun hashesOneMillionAsciiABytes() {
        val input = ByteArray(1_000_000) { 'a'.code.toByte() }

        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            PureKotlinSha256.digest(CanonicalBytes(input)).lowercaseHex,
        )
    }

    @Test
    fun hashesFinalPaddingBoundaries() {
        val vectors = listOf(
            55 to "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318",
            56 to "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a",
            64 to "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
        )

        vectors.forEach { (size, expected) ->
            val input = ByteArray(size) { 'a'.code.toByte() }
            assertEquals(expected, PureKotlinSha256.digest(CanonicalBytes(input)).lowercaseHex)
        }
    }

    /** FIPS 180-2's two-block (896-bit) message, so a known answer spans more than one block. */
    @Test
    fun hashesTheTwoBlockStandardKnownAnswer() {
        val input = (
            "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno" +
                "ijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"
            ).encodeToByteArray()

        assertEquals(112, input.size)
        assertEquals(
            "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            PureKotlinSha256.digest(CanonicalBytes(input)).lowercaseHex,
        )
    }

    @Test
    fun canonicalBytesSupportNonMutatingBlockReadsAndRemainderCopies() {
        val canonicalBytes = CanonicalBytes(byteArrayOf(0x10, 0x20, 0x30, 0x40))
        val destination = ByteArray(4) { 0x7f }

        assertEquals(0x20.toByte(), canonicalBytes.byteAt(1))
        canonicalBytes.copyRangeInto(
            destination = destination,
            destinationOffset = 1,
            startIndex = 1,
            endIndex = 3,
        )
        assertContentEquals(byteArrayOf(0x7f, 0x20, 0x30, 0x7f), destination)

        destination[1] = 0
        assertEquals(0x20.toByte(), canonicalBytes.byteAt(1))
        assertContentEquals(byteArrayOf(0x10, 0x20, 0x30, 0x40), canonicalBytes.bytes)
    }

    @Test
    fun digestRequiresExactly32BytesAndDefensivelyCopies() {
        assertFailsWith<IllegalArgumentException> { Sha256Digest(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { Sha256Digest(ByteArray(33)) }

        val input = ByteArray(32) { it.toByte() }
        val original = input.copyOf()
        val digest = Sha256Digest(input)
        val equal = Sha256Digest(original)

        input.fill(0)
        assertContentEquals(original, digest.bytes)

        val returned = digest.bytes
        returned.fill(0)
        assertContentEquals(original, digest.bytes)
        assertEquals(original.toTestLowercaseHex(), digest.lowercaseHex)
        assertEquals(digest, equal)
        assertEquals(digest.hashCode(), equal.hashCode())
        assertNotEquals(digest, Sha256Digest(ByteArray(32) { 0x5a }))
        assertEquals("Sha256Digest(<redacted>)", digest.toString())
        assertFalse(digest.toString().contains(digest.lowercaseHex))
    }
}

private fun ByteArray.toTestLowercaseHex(): String = buildString(size * 2) {
    this@toTestLowercaseHex.forEach { byte ->
        append(TEST_HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
        append(TEST_HEX_DIGITS[byte.toInt() and 0x0f])
    }
}

private const val TEST_HEX_DIGITS: String = "0123456789abcdef"
