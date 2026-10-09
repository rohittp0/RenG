package com.rohittp.reng.internal.identity

import kotlin.test.Test
import kotlin.test.assertEquals

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

    // ADR 0081 relaxes ADR 0018's collision check for frames: the registry keeps digests, not bytes, so
    // a second encoding with a digest it has seen is the same frame, never a collision.
    @Test
    fun aRepeatedDigestIsTheSameFrameWhateverItsBytes() {
        val registry = CanonicalIdentityRegistry()
        val established = fakeIdentity("first canonical bytes")
        val attempted = fakeIdentity("different canonical bytes")

        assertEquals(IdentityRegistration.Registered, registry.register(established))
        assertEquals(IdentityRegistration.AlreadyRegistered, registry.register(attempted))
        assertEquals(IdentityRegistration.AlreadyRegistered, registry.register(established))
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
