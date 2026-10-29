package com.rohittp.reng.internal.shader

import com.rohittp.reng.DEFAULT_MAXIMUM_CONSUMER_PIPELINES_PER_FRAME
import com.rohittp.reng.internal.cache.BoundedMemo
import com.rohittp.reng.internal.identity.shaderPairMemoCapacity
import com.rohittp.reng.internal.thread.PlatformLock

/**
 * [scanShaderProfile]'s answer for each shader source a renderer has already scanned, so frame planning
 * scans a consumer's shader text once rather than on every frame.
 *
 * **Why.** The spatial planner scans the vertex and fragment source of every geometry, and of a shader
 * backdrop, on every plan -- `prepare`'s and every `readAhead` hint's -- to turn an unusable profile
 * directive into `INVALID_VALUE` at `FRAME_PLANNING` rather than a GL fault at draw (ADR 0071). The scan
 * is a character walk of the whole source looking for `#version` lines and comments, and the sources do
 * not change between frames.
 *
 * **It cannot be stale.** The scan is a pure function of one `String`, and a `String` is immutable, so a
 * remembered answer -- including "no usable profile", remembered as a `null` plan -- is exactly the
 * answer a fresh scan would give. [ShaderProfilePlan] is immutable too, so handing the same instance to
 * two frames shares nothing either could change. Lookup is by `String` equality, whose hash is cached by
 * the runtime, and the same source instance -- the common case, since a consumer reuses one
 * [com.rohittp.reng.ShaderPair] -- compares by identity before any character.
 *
 * **It is bounded, and one frame's sources always fit**, by second chance, exactly as
 * [com.rohittp.reng.internal.identity.ShaderPairMemo] is and for its reason: an entry retains its source,
 * and a consumer that generated a shader per frame must not grow it without limit, while a bound below one
 * frame's working set would make a busy frame rescan every source. Each pair contributes two sources, so
 * the renderer sizes it with [shaderProfileMemoCapacity]: twice the shader-pair memo's capacity for the
 * same `maximumConsumerPipelinesPerFrame`.
 *
 * **It is locked**, because `readAhead` may plan on another thread while `prepare` plans. The scan runs
 * outside the lock; two threads that both miss scan equal answers and the insert keeps the first.
 *
 * [scan] exists for the test that proves a second plan does not rescan; production always uses
 * [scanShaderProfile].
 */
internal class ShaderProfileMemo(
    private val capacity: Int = shaderProfileMemoCapacity(DEFAULT_MAXIMUM_CONSUMER_PIPELINES_PER_FRAME),
    private val scan: (String) -> ShaderProfilePlan? = ::scanShaderProfile,
) {
    init {
        require(capacity >= 1) { "capacity must be at least 1" }
    }

    /** A remembered scan; [plan] is `null` when the source has no usable profile directive. */
    private class Scanned(val plan: ShaderProfilePlan?)

    private val scanned: BoundedMemo<String, Scanned> = BoundedMemo(capacity)
    private val lock = PlatformLock()

    /** Exactly `scanShaderProfile(source)`, scanned at most once per remembered [source]. */
    internal fun profileOf(source: String): ShaderProfilePlan? {
        lock.withLock { scanned.get(source) }?.let { return it.plan }
        val derived = Scanned(scan(source))
        return lock.withLock { scanned.putIfAbsent(source, derived) }.plan
    }
}

/** Twice [shaderPairMemoCapacity] for the same limit: a vertex and a fragment source per pair. */
internal fun shaderProfileMemoCapacity(maximumConsumerPipelinesPerFrame: Int): Int =
    2 * shaderPairMemoCapacity(maximumConsumerPipelinesPerFrame)
