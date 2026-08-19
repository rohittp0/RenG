package com.rohittp.reng.internal.cache

import com.rohittp.reng.ResourceFreeResult
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceReport
import com.rohittp.reng.ResourceReportEntry
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.ResourceUsage
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.internal.image.DecodedImage
import kotlinx.coroutines.sync.Mutex

/**
 * One resolved generation of a resident resource: the exact bytes RenG resolved for [key], plus its
 * decoded CPU form when this resource class decodes ([decoded] is null for classes RenG never decodes
 * itself, e.g. an engine-validated GLB). A generation is immutable — installing new content for the same
 * key creates a NEW generation rather than mutating this one, so a [Lease] taken before supersession keeps
 * observing the exact bytes it was handed even after [ResidentCache.install] moves `current` on.
 */
internal class ResidentGeneration internal constructor(
    internal val id: Long,
    internal val key: ResourceKey,
    internal val stored: StoredRawResource,
    internal val decoded: DecodedImage?,
)

/**
 * A hold against one [ResidentGeneration] that keeps it resident across a [ResidentCache.free] even after
 * a newer generation has superseded it. Whoever takes a lease must [ResidentCache.releaseLease] it exactly
 * once when it no longer needs the bytes.
 */
internal class Lease internal constructor(
    internal val id: Long,
    internal val key: ResourceKey,
    internal val generationId: Long,
)

private class GenerationRecord(val generation: ResidentGeneration) {
    var leaseCount: Int = 0
}

private class Entry {
    var current: GenerationRecord? = null
    val retired: MutableList<GenerationRecord> = mutableListOf()
    var freed: Boolean = false
}

/**
 * RenG's CPU-side table of resolved resource bytes, keyed by canonical [ResourceKey]. This is the state
 * ADR 0019 means when it says the renderer's mutex guards the resident cache: every public method here is
 * one state transition, taking the lock only across its own bookkeeping and never across an adapter call,
 * a decode, or a parse, so a long-running consumer callback can never hold this class's lock.
 *
 * Raw bytes are retained for the lifetime of a generation — never dropped after decode — because Cycle B's
 * `NORMAL` access mode uses a stale resident as a conditional-request baseline, and `ObserveResident` is
 * typed to answer with a `StoredRawResource`.
 *
 * At most one generation per key is ever [current]; installing a new one retires the previous current
 * generation. A retired generation with no outstanding lease is dropped immediately. [free] retires every
 * generation a selector matches (current and already-retired alike), marks the key reload-required, and
 * drops only the ones with no outstanding lease — the rest are reported deferred until their last
 * [releaseLease] drops them too. There is no automatic eviction: nothing here is ever dropped except by an
 * explicit [free] or by losing its last lease after being superseded or freed by one.
 */
internal class ResidentCache {
    private val lock = Mutex()
    private val entries = mutableMapOf<ResourceKey, Entry>()
    private var nextGenerationId = 1L
    private var nextLeaseId = 1L

    fun current(key: ResourceKey): ResidentGeneration? = guarded {
        entries[key]?.current?.generation
    }

    fun install(key: ResourceKey, stored: StoredRawResource, decoded: DecodedImage?): ResidentGeneration =
        guarded {
            val entry = entries.getOrPut(key) { Entry() }
            entry.freed = false
            entry.current?.let { previous ->
                if (previous.leaseCount > 0) entry.retired += previous
            }
            val generation = ResidentGeneration(nextGenerationId++, key, stored, decoded)
            entry.current = GenerationRecord(generation)
            generation
        }

    fun takeLease(generation: ResidentGeneration): Lease = guarded {
        val record = recordFor(generation.key, generation.id)
            ?: error("takeLease requires a generation this cache currently tracks")
        record.leaseCount += 1
        Lease(nextLeaseId++, generation.key, generation.id)
    }

    /**
     * Atomically installs a fresh generation for [key] and takes the caller's own lease on it in one
     * locked step. This exists because a visibility install is one conceptual action — "install the
     * generation and take the owner's lease" — that a separate [install] then [takeLease] pair cannot
     * deliver under concurrency: a freshly installed generation sits at `leaseCount == 0` in the gap
     * between the two calls, where a racing [free] or a competing installer for the same key can retire
     * it there — dropping it immediately, with no outstanding lease to defer it — before this caller's
     * [takeLease] ever runs. The generation still exists and the eventual lease would still technically
     * function once taken, but [report] and retired bookkeeping never see it, and a [takeLease] call
     * that arrives after the drop finds nothing left to track. Locking both steps together closes that
     * gap entirely: no observer can ever see this generation with a zero lease count.
     */
    fun installAndTakeLease(key: ResourceKey, stored: StoredRawResource, decoded: DecodedImage?): Lease =
        guarded {
            val entry = entries.getOrPut(key) { Entry() }
            entry.freed = false
            entry.current?.let { previous ->
                if (previous.leaseCount > 0) entry.retired += previous
            }
            val generation = ResidentGeneration(nextGenerationId++, key, stored, decoded)
            val record = GenerationRecord(generation)
            record.leaseCount += 1
            entry.current = record
            Lease(nextLeaseId++, key, generation.id)
        }

    /**
     * Atomically observes the current generation for [key] and takes the caller's lease on it in one
     * locked step, or returns `null` if there is no current generation. Closes the same class of gap as
     * [installAndTakeLease], for a caller re-leasing an already-resident generation rather than
     * installing a new one: a separate [current] then [takeLease] pair could observe a generation that a
     * racing [free] or a superseding [install] has already retired with zero leases by the time
     * [takeLease] runs, which — unlike the [installAndTakeLease] gap — is not a bug to route around but
     * a genuine race outcome this method reports honestly as "nothing was resident to lease" rather than
     * crashing on a generation that no longer exists.
     */
    fun observeAndTakeLease(key: ResourceKey): Lease? = guarded {
        val record = entries[key]?.current
        if (record == null) {
            null
        } else {
            record.leaseCount += 1
            Lease(nextLeaseId++, key, record.generation.id)
        }
    }

    fun releaseLease(lease: Lease) {
        guarded {
            val entry = entries[lease.key] ?: return@guarded
            val record = recordFor(lease.key, lease.generationId) ?: return@guarded
            record.leaseCount -= 1
            if (record.leaseCount <= 0 && entry.current !== record) {
                entry.retired.remove(record)
            }
        }
    }

    fun free(selector: ResourceSelector): ResourceFreeResult = guarded {
        val matched = matchedKeysForMutation(selector)
        var fullyFreed = 0
        var deferred = 0
        var alreadyFree = 0
        matched.forEach { key ->
            val entry = entries.getOrPut(key) { Entry() }
            val all = entry.retired + listOfNotNull(entry.current)
            if (all.isEmpty()) {
                alreadyFree += 1
            } else {
                entry.current = null
                val stillLeased = all.filter { it.leaseCount > 0 }
                entry.retired.clear()
                entry.retired += stillLeased
                if (stillLeased.isEmpty()) fullyFreed += 1 else deferred += 1
            }
            entry.freed = true
        }
        ResourceFreeResult(
            matchedKeys = matched.size,
            fullyFreedKeys = fullyFreed,
            deferredKeys = deferred,
            alreadyFreeKeys = alreadyFree,
        )
    }

    fun report(selector: ResourceSelector): ResourceReport = guarded {
        val entries = matchedKeysForQuery(selector).mapNotNull { key ->
            this.entries[key]?.let { reportEntry(key, it) }
        }
        val totals = entries.fold(ResourceUsage(0L, 0L, 0L, false)) { acc, entry ->
            ResourceUsage(
                rawBytes = acc.rawBytes + entry.usage.rawBytes,
                decodedCpuBytes = acc.decodedCpuBytes + entry.usage.decodedCpuBytes,
                knownGpuBytes = (acc.knownGpuBytes ?: 0L) + (entry.usage.knownGpuBytes ?: 0L),
                hasUnknownGpuBytes = acc.hasUnknownGpuBytes || entry.usage.hasUnknownGpuBytes,
            )
        }
        ResourceReport(entries, totals)
    }

    fun wasFreed(key: ResourceKey): Boolean = guarded { entries[key]?.freed == true }

    /** Drops every tracked entry unconditionally, regardless of outstanding leases. Used at renderer
     * close, where every CPU-side handle this cache holds must be forgotten in one step. */
    fun closeAll() {
        guarded { entries.clear() }
    }

    private fun recordFor(key: ResourceKey, generationId: Long): GenerationRecord? {
        val entry = entries[key] ?: return null
        entry.current?.takeIf { it.generation.id == generationId }?.let { return it }
        return entry.retired.find { it.generation.id == generationId }
    }

    private fun reportEntry(key: ResourceKey, entry: Entry): ResourceReportEntry {
        val all = entry.retired + listOfNotNull(entry.current)
        val rawBytes = all.sumOf { it.generation.stored.byteSnapshot.size.toLong() }
        val decodedBytes = all.sumOf { (it.generation.decoded?.byteCount ?: 0).toLong() }
        val leaseCount = all.sumOf { it.leaseCount }
        return ResourceReportEntry(
            key = key,
            residentGenerationCount = all.size,
            retiredGenerationCount = entry.retired.size,
            leaseCount = leaseCount,
            reloadRequired = entry.freed && entry.current == null,
            usage = ResourceUsage(
                rawBytes = rawBytes,
                decodedCpuBytes = decodedBytes,
                knownGpuBytes = 0L,
                hasUnknownGpuBytes = false,
            ),
        )
    }

    private fun matchedKeysForQuery(selector: ResourceSelector): List<ResourceKey> = when (selector) {
        ResourceSelector.All -> entries.keys.toList()
        is ResourceSelector.ByKind -> entries.keys.filter { it.kind == selector.kind }
        is ResourceSelector.ByClass -> entries.keys.filter { it.resourceClass == selector.resourceClass }
        is ResourceSelector.ByKey -> if (entries.containsKey(selector.key)) listOf(selector.key) else emptyList()
    }

    private fun matchedKeysForMutation(selector: ResourceSelector): List<ResourceKey> = when (selector) {
        is ResourceSelector.ByKey -> listOf(selector.key)
        else -> matchedKeysForQuery(selector)
    }

    /**
     * A short, uncontended-fast critical section. [Mutex.tryLock] and [Mutex.unlock] are plain
     * (non-suspending) functions, which is exactly what a synchronous public API needs here: spinning on
     * a private in-memory bookkeeping structure guarded for microseconds is the right trade for never
     * introducing a suspension point into a state transition an adapter call must never see.
     */
    private fun <T> guarded(block: () -> T): T {
        while (!lock.tryLock()) {
            // Uncontended in every realistic caller today; the loop exists so a genuinely concurrent
            // caller (ADR 0019's draw/query/free/close overlap) still linearizes rather than corrupts.
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
