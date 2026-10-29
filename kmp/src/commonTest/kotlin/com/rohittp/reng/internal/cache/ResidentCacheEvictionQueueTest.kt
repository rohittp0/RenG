package com.rohittp.reng.internal.cache

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ResidentCache] keeps its eviction candidates -- keys whose current generation no lease holds -- in a
 * queue of their own, so a sweep never steps past a leased key. This drives every transition that makes
 * or unmakes a candidate (install, lease, release, supersession, eviction, free, close) and recomputes the
 * candidates from the entries after each step.
 */
class ResidentCacheEvictionQueueTest {
    @Test
    fun theEvictionQueueHoldsExactlyTheUnleasedCurrentGenerationsAfterEveryOperation() {
        val random = Random(7)
        val cache = ResidentCache(residentByteBudget = 12L * ENTRY_BYTES)
        val keys = List(24) { key(it) }
        val leases = mutableListOf<Lease>()
        repeat(6_000) { step ->
            val key = keys[random.nextInt(keys.size)]
            when (random.nextInt(12)) {
                0 -> cache.install(key, stored(step), null)
                1 -> leases += cache.installAndTakeLease(key, stored(step), null)
                2, 3 -> cache.observeAndTakeLease(key)?.let { leases += it }
                4 -> cache.observeOrRestoreAndTakeLease(key, stored(step))?.let { leases += it }
                5 -> cache.current(key)?.let { leases += cache.takeLease(it) }
                6, 7, 8 -> if (leases.isNotEmpty()) cache.releaseLease(leases.removeAt(random.nextInt(leases.size)))
                9 -> cache.current(key)
                10 -> cache.free(ResourceSelector.ByKey(key))
                else -> if (random.nextInt(50) == 0) cache.closeAll()
            }
            assertEquals(cache.recountEvictableKeys(), cache.evictableKeys(), "step $step")
        }
    }

    // A key whose last lease is released re-joins the candidates at its last touch, not at the end:
    // the sweep still takes the least recently touched candidate first.
    @Test
    fun aReleasedKeyIsEvictedInTheOrderItWasLastTouched() {
        val cache = ResidentCache(residentByteBudget = 2L * ENTRY_BYTES)
        val lease = cache.installAndTakeLease(key(0), stored(0), null)
        cache.install(key(1), stored(1), null)
        cache.releaseLease(lease)
        assertEquals(listOf(key(0), key(1)), cache.evictableKeys())
        cache.install(key(2), stored(2), null)
        assertEquals(null, cache.current(key(0)), "key 0 was touched least recently")
        assertEquals(listOf(key(1), key(2)), cache.recountEvictableKeys())
    }

    private fun key(index: Int): ResourceKey =
        ResourceKey(ResourceKind.EXTERNAL, index.toString(16).padStart(64, 'c'), ResourceClass.STICKER_IMAGE)

    private fun stored(seed: Int) = StoredRawResource(
        bytes = ByteArray(ENTRY_BYTES.toInt()) { (seed + it).toByte() },
        contentDigest = seed.toString(16).padStart(64, 'd'),
        metadata = StoredRawResourceMetadata(storedAtEpochMillis = 1L),
    )

    private companion object {
        const val ENTRY_BYTES = 64L
    }
}
