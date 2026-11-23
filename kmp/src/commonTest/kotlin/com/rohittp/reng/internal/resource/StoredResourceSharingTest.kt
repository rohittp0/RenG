package com.rohittp.reng.internal.resource

import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * ADR 0062's erratum: a resource RenG already holds is re-wrapped by sharing its snapshot, never by
 * copying it, while a `ByteArray` from outside is still copied by the public constructor. Each test
 * states both halves -- nothing observable changed, and the copy really is gone -- so a regression in
 * either direction fails here.
 */
class StoredResourceSharingTest {

    @Test
    fun theBoundaryCopySurvivesAConsumerMutatingItsArrayAfterConstruction() {
        val supplied = byteArrayOf(1, 2, 3, 4)
        val stored = StoredRawResource(supplied, digestOf(supplied), metadata(storedAt = 1L))
        val shared = stored.withSharedBytes(metadata(storedAt = 2L))
        val validated = assertNotNull(copyValidStoredResource(stored, 1024L, PureKotlinSha256))

        // The consumer still holds `supplied`, and the public constructor is the only thing between it
        // and every resource derived from it.
        assertNotSame(supplied, stored.byteSnapshot)
        supplied.fill(9)
        stored.bytes.fill(7)
        shared.bytes.fill(7)
        validated.bytes.fill(7)

        for (resource in listOf(stored, shared, validated)) {
            assertContentEquals(byteArrayOf(1, 2, 3, 4), resource.bytes)
            assertContentEquals(byteArrayOf(1, 2, 3, 4), resource.byteSnapshot)
        }
    }

    @Test
    fun aSharedResourceIsTheSameResourceWithoutTheCopy() {
        val stored = StoredRawResource(byteArrayOf(5, 6, 7), digestOf(byteArrayOf(5, 6, 7)), metadata(storedAt = 3L))

        val sameMetadata = stored.withSharedBytes(metadata(storedAt = 3L))
        assertSame(stored.byteSnapshot, sameMetadata.byteSnapshot, "the snapshot is shared, not copied")
        assertEquals(stored, sameMetadata)
        assertEquals(stored.hashCode(), sameMetadata.hashCode())
        assertEquals(stored.contentDigest, sameMetadata.contentDigest)
        assertContentEquals(stored.bytes, sameMetadata.bytes)
        // Equal to a resource built through the public, copying path from the same bytes.
        assertEquals(
            StoredRawResource(byteArrayOf(5, 6, 7), stored.contentDigest, metadata(storedAt = 3L)),
            sameMetadata,
        )

        val otherMetadata = stored.withSharedBytes(metadata(storedAt = 4L, etag = "\"v2\""))
        assertSame(stored.byteSnapshot, otherMetadata.byteSnapshot)
        assertEquals(metadata(storedAt = 4L, etag = "\"v2\""), otherMetadata.metadata)
        assertEquals(stored.contentDigest, otherMetadata.contentDigest)
        assertContentEquals(stored.bytes, otherMetadata.bytes)
    }

    @Test
    fun validatingAStoredResourceSharesItsSnapshotAndChangesNoVerdict() {
        val bytes = "a stored tile".encodeToByteArray()
        val stored = StoredRawResource(bytes, digestOf(bytes), metadata(storedAt = 5L, etag = "\"e\""))

        val validated = assertNotNull(copyValidStoredResource(stored, 1024L, PureKotlinSha256))
        assertSame(stored.byteSnapshot, validated.byteSnapshot)
        assertEquals(stored, validated)
        assertEquals(stored.metadata, validated.metadata)
        assertNotSame(stored.metadata, validated.metadata, "the metadata is still rebuilt, as before")

        // The refusals are unchanged: a lying digest, an over-limit body, an empty body.
        val lyingDigest = StoredRawResource(bytes, "0".repeat(64), metadata(5L))
        val empty = StoredRawResource(byteArrayOf(), digestOf(byteArrayOf()), metadata(5L))
        assertNull(copyValidStoredResource(lyingDigest, 1024L, PureKotlinSha256))
        assertNull(copyValidStoredResource(stored, bytes.size.toLong() - 1, PureKotlinSha256))
        assertNull(copyValidStoredResource(empty, 1024L, PureKotlinSha256))
    }

    private fun digestOf(bytes: ByteArray): String = PureKotlinSha256.digest(CanonicalBytes(bytes)).lowercaseHex

    private fun metadata(storedAt: Long, etag: String? = null) = StoredRawResourceMetadata(
        contentType = "application/octet-stream",
        etag = etag,
        storedAtEpochMillis = storedAt,
    )
}
