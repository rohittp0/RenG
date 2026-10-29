package com.rohittp.reng.internal.identity

import com.rohittp.reng.DEFAULT_MAXIMUM_CONSUMER_PIPELINES_PER_FRAME
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.cache.BoundedMemo
import com.rohittp.reng.internal.gl.InternalPipelineRole
import com.rohittp.reng.internal.thread.PlatformLock

/**
 * What RenG derives from a consumer's [ShaderPair] and would otherwise derive again on every frame: the
 * canonical encoding of its two sources, and the resource keys hashed from that encoding.
 *
 * A frame with one geometry encoded and SHA-256'd the same two shader sources about four times: frame
 * validation, frame planning's resource traversal, the plan's own canonical encoding, and the draw path
 * (plus a backdrop's pipeline key). A profile of a video export found 41% of the GL thread in the last of
 * those alone. All of them now ask one memo, which a renderer owns for its lifetime.
 *
 * **It cannot be stale.** A [ShaderPair] is an immutable value whose equality is exactly the two strings
 * the canonical form is a function of, so a hit is the answer a fresh derivation would give, byte for
 * byte. The `String` hash codes a lookup takes are cached by the runtime, and equal pairs that are not the
 * same instance are compared once, at memcpy speed, rather than encoded and hashed.
 *
 * **It is bounded, and one frame's working set always fits.** An entry holds a copy of both sources in
 * canonical form, and a consumer that generated a pair per frame must not grow it without limit. But a
 * bound smaller than what one legal frame names makes every lookup of a busy frame miss, because each
 * pair comes round again only after more distinct pairs than the memo holds. So the renderer sizes it
 * with [shaderPairMemoCapacity] from `maximumConsumerPipelinesPerFrame`: every consumer pipeline key a
 * frame may name (geometry programs and a shader backdrop, counted together by that limit) plus one per
 * [InternalPipelineRole] for RenG's own programs. Both maps spend the bound by second chance
 * ([com.rohittp.reng.internal.cache.BoundedMemo]), so pairs a frame names every frame outlive one-offs.
 *
 * **It is locked**, because drawing may overlap preparation. The derivation itself runs outside the lock,
 * since the SHA-256 is the cost being avoided; two threads that both miss derive equal values and the
 * insert keeps whichever arrived first.
 */
internal class ShaderPairMemo(
    private val capacity: Int = shaderPairMemoCapacity(DEFAULT_MAXIMUM_CONSUMER_PIPELINES_PER_FRAME),
) {
    init {
        require(capacity >= 1) { "capacity must be at least 1" }
    }

    /** The canonical forms of one pair's sources, each as [CanonicalBinary.exactUtf8] makes it. */
    internal class Canonical(
        val vertex: CanonicalBytes,
        val fragment: CanonicalBytes,
        /** Both sources as fields 1 and 2 of one record, which is how a frame plan encodes a pair. */
        val encodedPair: CanonicalBytes,
    )

    private data class KeyIdentity(val role: InternalPipelineRole?, val shaderPair: ShaderPair)

    private val canonical: BoundedMemo<ShaderPair, Canonical> = BoundedMemo(capacity)
    private val keys: BoundedMemo<KeyIdentity, DerivedResourceKey> = BoundedMemo(capacity)
    private val lock = PlatformLock()

    internal fun canonicalOf(shaderPair: ShaderPair): Canonical {
        lock.withLock { canonical.get(shaderPair) }?.let { return it }
        val vertex = CanonicalBinary.exactUtf8(shaderPair.vertexSource)
        val fragment = CanonicalBinary.exactUtf8(shaderPair.fragmentSource)
        val derived = Canonical(
            vertex = vertex,
            fragment = fragment,
            encodedPair = CanonicalBinary.fields {
                field(1, vertex)
                field(2, fragment)
            },
        )
        return lock.withLock { canonical.putIfAbsent(shaderPair, derived) }
    }

    /** The key of [shaderPair] as a geometry program when [role] is `null`, else as that internal pipeline. */
    internal inline fun keyOf(
        role: InternalPipelineRole?,
        shaderPair: ShaderPair,
        derive: (Canonical) -> DerivedResourceKey,
    ): DerivedResourceKey {
        lookupKey(role, shaderPair)?.let { return it }
        return rememberKey(role, shaderPair, derive(canonicalOf(shaderPair)))
    }

    internal fun lookupKey(role: InternalPipelineRole?, shaderPair: ShaderPair): DerivedResourceKey? =
        lock.withLock { keys.get(KeyIdentity(role, shaderPair)) }

    internal fun rememberKey(
        role: InternalPipelineRole?,
        shaderPair: ShaderPair,
        derived: DerivedResourceKey,
    ): DerivedResourceKey = lock.withLock { keys.putIfAbsent(KeyIdentity(role, shaderPair), derived) }
}

/**
 * A [ShaderPairMemo] capacity that holds one frame's full working set under
 * [maximumConsumerPipelinesPerFrame]: each consumer pipeline key at most once, plus every internal
 * pipeline role, and never fewer than [MINIMUM_REMEMBERED_SHADER_PAIRS].
 */
internal fun shaderPairMemoCapacity(maximumConsumerPipelinesPerFrame: Int): Int =
    maxOf(MINIMUM_REMEMBERED_SHADER_PAIRS, maximumConsumerPipelinesPerFrame + InternalPipelineRole.entries.size)

internal const val MINIMUM_REMEMBERED_SHADER_PAIRS: Int = 64
