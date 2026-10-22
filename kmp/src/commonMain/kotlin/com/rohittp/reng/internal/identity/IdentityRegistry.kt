package com.rohittp.reng.internal.identity

internal sealed interface IdentityRegistration {
    data object Registered : IdentityRegistration

    data object AlreadyRegistered : IdentityRegistration
}

/**
 * The Frame Identities a renderer has prepared since its frame history was last cleared, by digest only.
 *
 * **Digests, not bytes (ADR 0081, relaxing ADR 0018).** No canonical encoding is kept to refuse a
 * SHA-256 collision: that never happens in practice, and keeping every frame's encoding until
 * `clearFrameHistory` costs a long export most of its heap. A digest that repeats is the same frame.
 */
internal class CanonicalIdentityRegistry {
    private val established: MutableSet<Sha256Digest> = HashSet()

    internal fun register(attempted: HashedCanonicalBytes): IdentityRegistration =
        if (established.add(attempted.digest)) IdentityRegistration.Registered else IdentityRegistration.AlreadyRegistered
}
