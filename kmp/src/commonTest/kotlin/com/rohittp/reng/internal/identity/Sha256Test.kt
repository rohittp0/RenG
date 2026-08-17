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

    @Test
    fun hashesTheTracked1431ByteCanonicalFrameControl() {
        val canonicalBytes = TRACKED_REPRESENTATIVE_FRAME_HEX.hexToByteArray()

        assertEquals(1_431, canonicalBytes.size)
        assertEquals(
            "447341d0410d7aea75e07153528b87609e7d408f1b7657e231e0381fb0a40599",
            PureKotlinSha256.digest(CanonicalBytes(canonicalBytes)).lowercaseHex,
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

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        val high = this[index * 2].digitToInt(16)
        val low = this[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }
}

private fun ByteArray.toTestLowercaseHex(): String = buildString(size * 2) {
    this@toTestLowercaseHex.forEach { byte ->
        append(TEST_HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
        append(TEST_HEX_DIGITS[byte.toInt() and 0x0f])
    }
}

private const val TEST_HEX_DIGITS: String = "0123456789abcdef"

private const val TRACKED_REPRESENTATIVE_FRAME_HEX: String =
    "524e47430101000100000008000000000000002a0002000000460001000000084029000000000000000200000008c0666800" +
        "0000000000030000000840210000000000000004000000084046800000000000000500000008403900000000000000030000" +
        "000200010004000000010100050000016800000002000000a4000100000086000100000002000100020000002a0001000000" +
        "084028000000000000000200000008c066600000000000000300000008402e00000000000000030000000200020004000000" +
        "2a0001000000080000000000000000000200000008402e000000000000000300000008000000000000000000050000000200" +
        "020006000000084000000000000000000200000012737469636b6572732f636166c3a92e706e67000000b800010000008600" +
        "0100000002000200020000002a00010000000840840000000000000002000000084076800000000000000300000008401000" +
        "0000000000000300000002000200040000002a00010000000800000000000000000002000000080000000000000000000300" +
        "000008000000000000000000050000000200020006000000083ff000000000000000020000002668747470733a2f2f657861" +
        "6d706c652e746573742f737469636b657225323074776f2e706e670006000001e20000000200000122000100000086000100" +
        "000002000100020000002a0001000000084026000000000000000200000008c0664000000000000003000000084034000000" +
        "000000000300000002000100040000002a000100000008402400000000000000020000000840340000000000000003000000" +
        "08403e000000000000000500000002000100060000000840080000000000000002000000106d6f64656c732f726f626f742e" +
        "676c620003000000180174657874757265732f726f626f742d626173652e706e6700040000005c0000000200000026000100" +
        "000012000100000002000200020000000469646c650002000000083ff80000000000000000002a0001000000160001000000" +
        "02000100020000000800000000000000070002000000084000000000000000000000b4000100000086000100000002000200" +
        "020000002a00010000000840690000000000000002000000084062c000000000000003000000084014000000000000000300" +
        "000002000200040000002a000100000008000000000000000000020000000800000000000000000003000000080000000000" +
        "00000000050000000200020006000000083fe00000000000000002000000116d6f64656c732f73637265656e2e676c620003" +
        "0000000100000400000004000000000007000001cc000000020000011400010000002a000100000008403400000000000000" +
        "0200000008c066800000000000000300000008402400000000000000020000002a0001000000084024000000000000000200" +
        "000008c06540000000000000030000000840340000000000000003000000ae00010000004e2376657273696f6e2033303020" +
        "65730a696e207665633320706f736974696f6e3b0a766f6964206d61696e28297b676c5f506f736974696f6e3d7665633428" +
        "706f736974696f6e2c312e30293b7d0002000000542376657273696f6e203330302065730a707265636973696f6e20686967" +
        "687020666c6f61743b0a6f7574207665633420636f6c6f723b0a766f6964206d61696e28297b636f6c6f723d766563342831" +
        "2e30293b7d000000ac00010000002a0001000000084044000000000000000200000008c06400000000000000030000000800" +
        "0000000000000000020000002a000100000008403e000000000000000200000008c062c00000000000000300000008000000" +
        "000000000000030000004600010000001d2376657273696f6e203330302065730a766f6964206d61696e28297b7d00020000" +
        "001d2376657273696f6e203330302065730a766f6964206d61696e28297b7d"
