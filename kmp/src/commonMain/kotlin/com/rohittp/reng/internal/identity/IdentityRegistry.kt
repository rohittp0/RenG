package com.rohittp.reng.internal.identity

internal sealed interface IdentityRegistration {
    data object Registered : IdentityRegistration

    data object AlreadyRegistered : IdentityRegistration
}

/**
 * The Frame Identities a renderer has prepared since its frame history was last cleared, by digest only.
 *
 * **Digests, not bytes (ADR 0081, relaxing ADR 0018).** This used to keep every frame's complete
 * canonical encoding beside its digest so that a second, different encoding with the same digest -- a
 * SHA-256 collision -- could be refused. That never happens in practice, and the check cost every frame's
 * whole encoding in memory until `clearFrameHistory` (most of a Java heap in a long export) plus a full
 * comparison whenever a digest repeated. A digest that repeats is now simply the same frame.
 */
internal class CanonicalIdentityRegistry {
    private val established: MutableSet<Sha256Digest> = HashSet()

    internal fun register(attempted: HashedCanonicalBytes): IdentityRegistration =
        if (established.add(attempted.digest)) IdentityRegistration.Registered else IdentityRegistration.AlreadyRegistered
}
