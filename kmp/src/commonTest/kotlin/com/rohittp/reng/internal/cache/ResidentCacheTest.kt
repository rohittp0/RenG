package com.rohittp.reng.internal.cache

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.internal.image.DecodedImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResidentCacheTest {
    @Test
    fun onlyTheCurrentGenerationSatisfiesALookup() {
        val cache = ResidentCache()
        val first = cache.install(key, storedA, null)
        val lease = cache.takeLease(first)
        val second = cache.install(key, storedB, null)
        assertEquals(second, cache.current(key))
        // The superseded generation stays usable while leased.
        assertEquals(2, cache.report(ResourceSelector.ByKey(key)).entries.single().residentGenerationCount)
        cache.releaseLease(lease)
        assertEquals(1, cache.report(ResourceSelector.ByKey(key)).entries.single().residentGenerationCount)
    }

    @Test
    fun freeRetiresEveryGenerationAndDefersThoseStillLeased() {
        val cache = ResidentCache()
        val generation = cache.install(key, storedA, null)
        val lease = cache.takeLease(generation)
        val result = cache.free(ResourceSelector.ByKey(key))
        assertEquals(
            com.rohittp.reng.ResourceFreeResult(matchedKeys = 1, fullyFreedKeys = 0, deferredKeys = 1, alreadyFreeKeys = 0),
            result,
        )
        assertNull(cache.current(key))
        cache.releaseLease(lease)
        assertEquals(0, cache.report(ResourceSelector.ByKey(key)).entries.single().retiredGenerationCount)
    }

    @Test
    fun freeWithNoLeaseReportsFullyFreedAndASecondFreeReportsAlreadyFree() {
        val cache = ResidentCache()
        cache.install(key, storedA, null)
        assertEquals(1, cache.free(ResourceSelector.ByKey(key)).fullyFreedKeys)
        assertEquals(1, cache.free(ResourceSelector.ByKey(key)).alreadyFreeKeys)
    }

    @Test
    fun aFreedKeyIsDistinguishableFromOneNeverLoaded() {
        val cache = ResidentCache()
        assertFalse(cache.wasFreed(key))
        cache.install(key, storedA, null)
        cache.free(ResourceSelector.ByKey(key))
        assertTrue(cache.wasFreed(key))
        assertTrue(cache.report(ResourceSelector.ByKey(key)).entries.single().reloadRequired)
    }

    @Test
    fun aRetiredGenerationIsNeverResurrectedByIdenticalBytes() {
        val cache = ResidentCache()
        val first = cache.install(key, storedA, null)
        val lease = cache.takeLease(first)
        cache.free(ResourceSelector.ByKey(key))
        val reloaded = cache.install(key, storedA, null)
        assertNotSame(first, reloaded)
        assertEquals(1, cache.report(ResourceSelector.ByKey(key)).entries.single().retiredGenerationCount)
        cache.releaseLease(lease)
    }

    @Test
    fun manyLeasesShareOneGeneration() {
        val cache = ResidentCache()
        val generation = cache.install(key, storedA, null)
        val leases = List(8) { cache.takeLease(generation) }
        assertEquals(8, cache.report(ResourceSelector.ByKey(key)).entries.single().leaseCount)
        leases.forEach(cache::releaseLease)
        assertEquals(0, cache.report(ResourceSelector.ByKey(key)).entries.single().leaseCount)
    }

    @Test
    fun reportAccountsRawAndDecodedBytesWithNoGpuAllocation() {
        val cache = ResidentCache()
        cache.install(key, storedA, decodedOf(64))
        val entry = cache.report(ResourceSelector.ByKey(key)).entries.single()
        assertEquals(storedA.bytes.size.toLong(), entry.usage.rawBytes)
        assertEquals(64L, entry.usage.decodedCpuBytes)
        assertEquals(0L, entry.usage.knownGpuBytes)
        assertFalse(entry.usage.hasUnknownGpuBytes)
    }

    @Test
    fun concurrentLeaseAndFreeLinearizeAtTheStateBoundary() = runTest {
        val cache = ResidentCache()
        val generation = cache.install(key, storedA, null)
        // Free racing the last lease release must report one or the other, never both and never
        // neither: deferred if free wins, fully freed if the release wins.
        val lease = cache.takeLease(generation)
        val results = listOf(
            async { cache.free(ResourceSelector.ByKey(key)) },
            async { cache.releaseLease(lease); null },
        ).awaitAll()
        val free = results.filterIsInstance<com.rohittp.reng.ResourceFreeResult>().single()
        assertEquals(1, free.deferredKeys + free.fullyFreedKeys)
        assertEquals(0, cache.report(ResourceSelector.ByKey(key)).entries.single().leaseCount)
    }

    @Test
    fun selectorsMatchAllByKindByClassAndByKey() {
        val cache = ResidentCache()
        cache.install(externalStickerKey, storedA, null)
        cache.install(externalModelKey, storedB, null)
        assertEquals(2, cache.report(ResourceSelector.All).entries.size)
        assertEquals(2, cache.report(ResourceSelector.ByKind(ResourceKind.EXTERNAL)).entries.size)
        assertEquals(1, cache.report(ResourceSelector.ByClass(ResourceClass.STICKER_IMAGE)).entries.size)
        assertEquals(1, cache.report(ResourceSelector.ByKey(externalStickerKey)).entries.size)
    }
}

private val key = ResourceKey(ResourceKind.EXTERNAL, "1".repeat(64), ResourceClass.STICKER_IMAGE)
private val externalStickerKey = ResourceKey(ResourceKind.EXTERNAL, "2".repeat(64), ResourceClass.STICKER_IMAGE)
private val externalModelKey = ResourceKey(ResourceKind.EXTERNAL, "3".repeat(64), ResourceClass.MODEL_GLB)

private val storedA = storedResource(1)
private val storedB = storedResource(2)

private fun storedResource(marker: Int): StoredRawResource = StoredRawResource(
    bytes = byteArrayOf(marker.toByte(), marker.toByte(), marker.toByte()),
    contentDigest = marker.toString().repeat(64).take(64),
    metadata = StoredRawResourceMetadata(storedAtEpochMillis = 0L),
)

private fun decodedOf(byteCount: Int): DecodedImage = DecodedImage(width = byteCount / 4, height = 1, rgba = ByteArray(byteCount))
