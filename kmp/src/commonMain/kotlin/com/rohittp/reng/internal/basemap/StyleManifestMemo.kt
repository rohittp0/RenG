package com.rohittp.reng.internal.basemap

import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.internal.cache.BoundedMemo
import com.rohittp.reng.internal.cache.ResidentGeneration
import com.rohittp.reng.internal.resource.ResolvedResourceContent
import com.rohittp.reng.internal.thread.PlatformLock
import kotlin.jvm.JvmInline

/**
 * Stored bytes whose `contentDigest` RenG has itself established names them -- the precondition
 * [StyleManifestMemo] keys on (ADR 0061: a consumer-supplied digest is not trusted alone).
 *
 * Made only from the two places that hold such bytes: a [ResolvedResourceContent], which is built from
 * a Transport body RenG hashed itself, a 304 merged onto such a baseline, a Store read that
 * `copyValidStoredResource` re-hashed, or a resident copy of one of those; and a [ResidentGeneration],
 * installed from such content. Re-hashing here instead would cost a SHA-256 of the whole document on
 * every prepare, which is most of what the memo exists to save.
 */
@JvmInline
internal value class VerifiedStoredResource private constructor(val stored: StoredRawResource) {
    internal companion object {
        fun of(content: ResolvedResourceContent): VerifiedStoredResource = VerifiedStoredResource(content.stored)

        fun of(generation: ResidentGeneration): VerifiedStoredResource = VerifiedStoredResource(generation.stored)

        /** For tests whose fixtures name their documents by placeholder digests. */
        fun assumedForTest(stored: StoredRawResource): VerifiedStoredResource = VerifiedStoredResource(stored)
    }
}

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
 * small and spent by second chance, since a style rarely changes.
 *
 * Reached from wherever the consumer prepares, so the map sits under a lock. The parse runs outside it:
 * two threads that both miss derive the same manifest, and the insert keeps whichever arrived first.
 */
internal class StyleManifestMemo(
    private val capacity: Int = MAXIMUM_REMEMBERED_STYLE_MANIFESTS,
    private val derive: (ByteArray, String) -> BasemapStyleManifestOutcome = ::deriveBasemapStyleManifest,
) {
    private data class Identity(val contentDigest: String, val baseUri: String)

    private val remembered: BoundedMemo<Identity, BasemapStyleManifestOutcome.Derived> = BoundedMemo(capacity)
    private val lock = PlatformLock()

    init {
        require(capacity >= 1) { "capacity must be at least 1" }
    }

    fun manifestFor(resource: VerifiedStoredResource, baseUri: String): BasemapStyleManifestOutcome {
        val stored = resource.stored
        val identity = Identity(stored.contentDigest, baseUri)
        lock.withLock { remembered.get(identity) }?.let { return it }

        val outcome = derive(stored.byteSnapshot, baseUri)
        if (outcome !is BasemapStyleManifestOutcome.Derived) return outcome
        return lock.withLock { remembered.putIfAbsent(identity, outcome) }
    }
}

internal const val MAXIMUM_REMEMBERED_STYLE_MANIFESTS: Int = 4
