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

    /**
     * Records [attempted] by its digest, or reports that an equal one -- or a colliding one -- is already
     * recorded.
     *
     * [attempted] is retained as given, not deep-copied first. Its two parts, [CanonicalBytes] and
     * [Sha256Digest], are immutable: each copies its array in at construction, keeps it private, never
     * mutates it, and copies it out of every external read. So a deep copy of one is indistinguishable
     * from the original to everyone, and the copy this used to take -- `canonicalBytes.bytes` then
     * `CanonicalBytes(...)`, two full copies of the frame plan's canonical encoding, geometry vertices
     * included, on every `prepare` -- defended nothing. It showed in simpleperf on an OPPO CPH2629
     * exporting a 28 s 720p24 project, with the encoder's own copies, as about 0.8% of all of the app's
     * CPU. Retaining the caller's instance also means the registry, which keeps every frame's identity
     * until the frame history is cleared, no longer keeps a private second copy of each encoding.
     *
     * A [IdentityRegistration.Collision] still returns deep copies: it is a diagnostic path that never
     * runs in a correct frame, and its values leave this class.
     */
    internal fun register(attempted: HashedCanonicalBytes): IdentityRegistration {
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
