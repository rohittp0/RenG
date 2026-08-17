package com.rohittp.reng.internal.identity

internal sealed interface IdentityRegistration {
    data object Registered : IdentityRegistration

    data object AlreadyRegistered : IdentityRegistration

    data class Collision(
        val established: HashedCanonicalBytes,
        val attempted: HashedCanonicalBytes,
    ) : IdentityRegistration
}

internal class CanonicalIdentityRegistry {
    private val establishedByDigest: MutableMap<Sha256Digest, HashedCanonicalBytes> = mutableMapOf()

    internal fun register(identity: HashedCanonicalBytes): IdentityRegistration {
        val attempted = identity.deepCopy()
        val established = establishedByDigest[attempted.digest]
        if (established == null) {
            establishedByDigest[attempted.digest] = attempted
            return IdentityRegistration.Registered
        }
        if (established.canonicalBytes == attempted.canonicalBytes) {
            return IdentityRegistration.AlreadyRegistered
        }
        return IdentityRegistration.Collision(
            established = established.deepCopy(),
            attempted = attempted.deepCopy(),
        )
    }
}

private fun HashedCanonicalBytes.deepCopy(): HashedCanonicalBytes = HashedCanonicalBytes(
    digest = Sha256Digest(digest.bytes),
    canonicalBytes = CanonicalBytes(canonicalBytes.bytes),
)
