package com.rohittp.reng.internal.cache

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.internal.bench.RengBench
import kotlin.test.Test

/**
 * B2: what [ResidentCache.releaseLease] costs while the cache is over its byte budget and most of what it
 * holds is leased.
 *
 * [KEYS] keys are resident, every one leased -- [PINNED] by leases this benchmark never releases (other
 * open frames), the last [FRAME] by "this frame". The budget is [BUDGET_KEYS] keys' worth, so the cache
 * stays over budget throughout and every last-lease release runs the eviction sweep. Releasing the
 * frame's leases is timed; reinstalling the keys that sweep evicted, for the next iteration, is not.
 * The frame's keys are the most recently used, so a sweep walks past every pinned key first.
 *
 * The under-budget shape is the same cache with an unbounded budget, where a release never sweeps.
 */
class ResidentCacheReleaseBenchmarkTest {
    @Test
    fun releasingAFramesLeasesOverBudget() {
        measure("b2.resident_cache.release_frame.over_budget", budgetBytes = BUDGET_KEYS * ENTRY_BYTES)
        measure("b2.resident_cache.release_frame.under_budget", budgetBytes = Long.MAX_VALUE)
    }

    private fun measure(name: String, budgetBytes: Long) {
        val cache = ResidentCache(residentByteBudget = budgetBytes)
        val keys = List(KEYS) { index -> key(index) }
        val stored = List(KEYS) { index ->
            StoredRawResource(
                bytes = ByteArray(ENTRY_BYTES.toInt()) { (index + it).toByte() },
                contentDigest = index.toString(16).padStart(64, 'd'),
                metadata = StoredRawResourceMetadata(storedAtEpochMillis = 1L),
            )
        }
        // The pinned keys keep their leases for the whole run.
        for (index in 0 until PINNED) cache.installAndTakeLease(keys[index], stored[index], null)
        val frame = (PINNED until KEYS).toList()
        var frameLeases = frame.map { cache.installAndTakeLease(keys[it], stored[it], null) }
        RengBench.info(name, "keys=$KEYS pinned=$PINNED frame=$FRAME budget_keys=${budgetBytes / ENTRY_BYTES}")
        RengBench.measure(name, warmupIterations = 5, minimumIterations = 20) {
            timed {
                for (lease in frameLeases) cache.releaseLease(lease)
            }
            frameLeases = frame.map { index ->
                cache.observeAndTakeLease(keys[index]) ?: cache.installAndTakeLease(keys[index], stored[index], null)
            }
            fold(frameLeases.size)
            fold(frameLeases.last().generation.id.value)
        }
        frameLeases.forEach(cache::releaseLease)
    }

    private fun key(index: Int): ResourceKey =
        ResourceKey(ResourceKind.EXTERNAL, index.toString(16).padStart(64, 'c'), ResourceClass.STICKER_IMAGE)

    private companion object {
        const val KEYS = 2_000
        const val FRAME = 200
        const val PINNED = KEYS - FRAME
        const val BUDGET_KEYS = 100L
        const val ENTRY_BYTES = 1024L
    }
}
