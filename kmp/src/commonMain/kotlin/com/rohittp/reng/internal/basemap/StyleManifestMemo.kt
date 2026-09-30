package com.rohittp.reng.internal.basemap

import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.internal.thread.PlatformLock

/**
 * Remembers the manifest [deriveBasemapStyleManifest] produced for a style document, by the document's
 * content digest and the base URI it was read against.
 *
 * The derivation is a pure function of those two inputs (the digest names the bytes), and it parses the
 * whole document: a profiled video export spent 13% of its preparation pool re-reading one unchanged
 * style on every frame. A prepare whose style bytes are unchanged now skips the parse; bytes that
 * change carry a different digest, miss, and are validated in full.
 *
 * Only a [BasemapStyleManifestOutcome.Derived] outcome is remembered. A document RenG cannot read is not
 * a result to remember, so a rejection is re-derived, and fails, exactly as it did before. The bound is
 * small and spent oldest-first, since a style rarely changes.
 *
 * Reached from wherever the consumer prepares, so the map sits under a lock. The parse runs outside it:
 * two threads that both miss derive the same manifest, and the insert keeps whichever arrived first.
 */
internal class StyleManifestMemo(
    private val capacity: Int = MAXIMUM_REMEMBERED_STYLE_MANIFESTS,
    private val derive: (ByteArray, String) -> BasemapStyleManifestOutcome = ::deriveBasemapStyleManifest,
) {
    private data class Identity(val contentDigest: String, val baseUri: String)

    private val remembered: MutableMap<Identity, BasemapStyleManifestOutcome.Derived> = LinkedHashMap()
    private val lock = PlatformLock()

    init {
        require(capacity >= 1) { "capacity must be at least 1" }
    }

    fun manifestFor(stored: StoredRawResource, baseUri: String): BasemapStyleManifestOutcome {
        val identity = Identity(stored.contentDigest, baseUri)
        lock.withLock { remembered[identity] }?.let { return it }

        val outcome = derive(stored.byteSnapshot, baseUri)
        if (outcome !is BasemapStyleManifestOutcome.Derived) return outcome
        return lock.withLock {
            remembered[identity]?.let { return@withLock it }
            remembered[identity] = outcome
            val oldest = remembered.iterator()
            while (remembered.size > capacity) {
                oldest.next()
                oldest.remove()
            }
            outcome
        }
    }
}

internal const val MAXIMUM_REMEMBERED_STYLE_MANIFESTS: Int = 4
