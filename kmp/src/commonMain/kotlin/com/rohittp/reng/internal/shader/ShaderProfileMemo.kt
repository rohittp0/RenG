package com.rohittp.reng.internal.shader

import com.rohittp.reng.internal.thread.PlatformLock

/**
 * [scanShaderProfile]'s answer for each shader source a renderer has already scanned, so frame planning
 * scans a consumer's shader text once rather than on every frame.
 *
 * **Why.** The spatial planner scans the vertex and fragment source of every geometry, and of a shader
 * backdrop, on every plan -- `prepare`'s and every `readAhead` hint's -- to turn an unusable profile
 * directive into `INVALID_VALUE` at `FRAME_PLANNING` rather than a GL fault at draw (ADR 0071). The scan
 * is a character walk of the whole source looking for `#version` lines and comments, and the sources do
 * not change between frames. Simpleperf on an OPPO CPH2629 exporting a 28 s 720p24 project charged 1.5%
 * of all of the app's CPU to `scanShaderProfile` and its `startsWithAt`, under `planSpatial`.
 *
 * **It cannot be stale.** The scan is a pure function of one `String`, and a `String` is immutable, so a
 * remembered answer -- including "no usable profile", remembered as a `null` plan -- is exactly the
 * answer a fresh scan would give. [ShaderProfilePlan] is immutable too, so handing the same instance to
 * two frames shares nothing either could change. Lookup is by `String` equality, whose hash is cached by
 * the runtime, and the same source instance -- the common case, since a consumer reuses one
 * [com.rohittp.reng.ShaderPair] -- compares by identity before any character.
 *
 * **It is bounded**, oldest-first, exactly as [com.rohittp.reng.internal.identity.ShaderPairMemo] is and
 * for its reason: an entry retains its source, and a consumer that generated a shader per frame must not
 * grow it without limit. Two sources per pair, so twice that memo's pair capacity.
 *
 * **It is locked**, because `readAhead` may plan on another thread while `prepare` plans. The scan runs
 * outside the lock; two threads that both miss scan equal answers and the insert keeps the first.
 *
 * [scan] exists for the test that proves a second plan does not rescan; production always uses
 * [scanShaderProfile].
 */
internal class ShaderProfileMemo(
    private val capacity: Int = MAXIMUM_REMEMBERED_SHADER_SOURCES,
    private val scan: (String) -> ShaderProfilePlan? = ::scanShaderProfile,
) {
    init {
        require(capacity >= 1) { "capacity must be at least 1" }
    }

    /** A remembered scan; [plan] is `null` when the source has no usable profile directive. */
    private class Scanned(val plan: ShaderProfilePlan?)

    private val scanned: MutableMap<String, Scanned> = LinkedHashMap()
    private val lock = PlatformLock()

    /** Exactly `scanShaderProfile(source)`, scanned at most once per remembered [source]. */
    internal fun profileOf(source: String): ShaderProfilePlan? {
        lock.withLock { scanned[source] }?.let { return it.plan }
        val derived = Scanned(scan(source))
        return lock.withLock {
            val existing = scanned[source]
            if (existing != null) {
                existing.plan
            } else {
                scanned[source] = derived
                val oldest = scanned.iterator()
                while (scanned.size > capacity) {
                    oldest.next()
                    oldest.remove()
                }
                derived.plan
            }
        }
    }
}

internal const val MAXIMUM_REMEMBERED_SHADER_SOURCES: Int = 128
