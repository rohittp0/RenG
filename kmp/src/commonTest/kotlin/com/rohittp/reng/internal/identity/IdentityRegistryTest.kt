package com.rohittp.reng.internal.identity

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame

class IdentityRegistryTest {
    @Test
    fun equalDigestAndCanonicalBytesRegisterOnce() {
        val registry = CanonicalIdentityRegistry()
        val first = fakeIdentity("same bytes")
        val equal = fakeIdentity("same bytes")

        assertEquals(IdentityRegistration.Registered, registry.register(first))
        assertEquals(IdentityRegistration.AlreadyRegistered, registry.register(equal))
        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
    }

    @Test
    fun digestCollisionReturnsDefensiveValuesAndPreservesFirstBytes() {
        val registry = CanonicalIdentityRegistry()
        val established = fakeIdentity("first canonical bytes")
        val attempted = fakeIdentity("different canonical bytes")

        assertEquals(IdentityRegistration.Registered, registry.register(established))
        val collision = assertIs<IdentityRegistration.Collision>(registry.register(attempted))

        assertEquals(established, collision.established)
        assertEquals(attempted, collision.attempted)
        assertNotSame(established, collision.established)
        assertNotSame(established.digest, collision.established.digest)
        assertNotSame(established.canonicalBytes, collision.established.canonicalBytes)
        assertNotSame(attempted, collision.attempted)

        val returnedEstablishedBytes = collision.established.canonicalBytes.bytes
        returnedEstablishedBytes.fill(0)

        val repeatedCollision = assertIs<IdentityRegistration.Collision>(registry.register(attempted))
        assertContentEquals(
            "first canonical bytes".encodeToByteArray(),
            repeatedCollision.established.canonicalBytes.bytes,
        )
        assertEquals(IdentityRegistration.AlreadyRegistered, registry.register(established))
        assertFalse(collision.toString().contains("first canonical bytes"))
        assertFalse(collision.toString().contains("different canonical bytes"))
    }

    private fun fakeIdentity(value: String): HashedCanonicalBytes {
        val canonicalBytes = CanonicalBytes(value.encodeToByteArray())
        return HashedCanonicalBytes(
            digest = REPEATED_5A_SHA256.digest(canonicalBytes),
            canonicalBytes = canonicalBytes,
        )
    }

    private companion object {
        val REPEATED_5A_SHA256: Sha256Function = Sha256Function {
            Sha256Digest(ByteArray(32) { 0x5a })
        }
    }
}
