package com.rohittp.reng.internal.identity

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The copies removed from canonical encoding and identity registration. The encoders' exact bytes are
 * pinned by [CanonicalBinaryTest]'s golden hex; this pins the two new paths against the copying ones.
 */
class CanonicalCopyEliminationTest {

    @Test
    fun anAdoptedArrayIsTheSameValueAsACopiedOne() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(1, 2, 3, -1))) {
            val copied = CanonicalBytes(bytes.copyOf())
            val adopted = CanonicalBytes.adopting(bytes.copyOf())

            assertEquals(copied, adopted)
            assertEquals(adopted, copied)
            assertEquals(copied.hashCode(), adopted.hashCode())
            assertEquals(copied.size, adopted.size)
            assertContentEquals(copied.bytes, adopted.bytes)
            assertEquals(PureKotlinSha256.digest(copied), PureKotlinSha256.digest(adopted))
        }
    }

    @Test
    fun anAdoptedValueStillCopiesOnTheWayOut() {
        val adopted = CanonicalBytes.adopting(byteArrayOf(4, 5, 6))
        adopted.bytes.fill(0)
        assertContentEquals(byteArrayOf(4, 5, 6), adopted.bytes)
    }

    @Test
    fun nestedEncodingsAreByteIdenticalToConcatenatingTheirParts() {
        // Every level here now adopts its own output; the expected bytes are written out by hand.
        val encoded = CanonicalBinary.fields {
            field(1, CanonicalBinary.list(listOf(CanonicalBinary.u16(0x0102), CanonicalBinary.boolean(true))))
            field(2, CanonicalBinary.optional(CanonicalBinary.exactUtf8("é")))
            field(3, CanonicalBinary.optional(null))
        }

        val expected = listOf(
            // field 1: tag, length 15, then list: count 2, (len 2, 0102), (len 1, 01)
            "0001", "0000000f", "00000002", "00000002", "0102", "00000001", "01",
            // field 2: tag, length 3, then optional present 01, c3a9
            "0002", "00000003", "01c3a9",
            // field 3: tag, length 1, then optional absent 00
            "0003", "00000001", "00",
        ).joinToString("")
        assertEquals(expected, encoded.bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
    }

    @Test
    fun registrationRetainsTheIdentityAndGivesTheSameVerdicts() {
        val registry = CanonicalIdentityRegistry()
        val first = identity("frame one")

        assertEquals(IdentityRegistration.Registered, registry.register(first))
        assertEquals(IdentityRegistration.AlreadyRegistered, registry.register(first))
        assertEquals(IdentityRegistration.AlreadyRegistered, registry.register(identity("frame one")))
        assertEquals(IdentityRegistration.Registered, registry.register(identity("frame two")))

        // A same-digest, different-bytes identity still collides, and reports the first bytes.
        val colliding = HashedCanonicalBytes(first.digest, CanonicalBytes("not frame one".encodeToByteArray()))
        val collision = assertIs<IdentityRegistration.Collision>(registry.register(colliding))
        assertContentEquals("frame one".encodeToByteArray(), collision.established.canonicalBytes.bytes)
        assertEquals(colliding, collision.attempted)
    }

    private fun identity(value: String): HashedCanonicalBytes {
        val canonical = CanonicalBytes(value.encodeToByteArray())
        return HashedCanonicalBytes(PureKotlinSha256.digest(canonical), canonical)
    }
}
