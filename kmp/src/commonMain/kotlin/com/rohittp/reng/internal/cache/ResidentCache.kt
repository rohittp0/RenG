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
 * One resident copy of a [ResourceKey]'s raw bytes and, for image classes, its decoded pixels. A
 * generation is an identity, not a value: [ResidentCache.install] never interns by content, so two
 * installs of byte-identical bytes still produce two distinct, independently-leased generations — see
 * [ResidentCache] for why a retired generation is never resurrected. Raw bytes are retained for as long
 * as the generation itself is resident, never dropped after decode, because Cycle B's `NORMAL` rules use
 * a stale resident as a `304` baseline.
 */
internal class ResidentGeneration(
    val key: ResourceKey,
    val stored: StoredRawResource,
    val decoded: DecodedImage?,
) {
    var leaseCount: Int = 0
        private set

    fun addLease() {
        leaseCount += 1
    }

    fun removeLease() {
        check(leaseCount > 0) { "cannot release a lease from a generation with no outstanding lease" }
        leaseCount -= 1
    }
}

/**
 * One outstanding claim on a [ResidentGeneration] that keeps it resident even after it is superseded by
 * a fresh install or its key is freed. Single-use: a lease token is consumed by its one matching
 * [ResidentCache.releaseLease] call, and releasing it again is a caller error rather than a silent no-op.
 */
internal class Lease(val generation: ResidentGeneration) {
    private var released: Boolean = false

    fun markReleased() {
        require(!released) { "a lease cannot be released more than once" }
        released = true
    }
}

/**
 * Every generation this cache holds for one [ResourceKey]: at most one [current] generation plus zero or
 * more [retired] generations kept alive only because a lease taken before they were superseded or freed
 * has not yet been released. [freed] is the reload marker: true exactly while the key has no current
 * generation because of an explicit [ResidentCache.free], and cleared by the next [ResidentCache.install].
 */
private class KeyEntry {
    var current: ResidentGeneration? = null
    val retired: MutableList<ResidentGeneration> = mutableListOf()
    var freed: Boolean = false
}

/**
 * The resident cache: one entry per [ResourceKey] holding generations, leases, and a reload marker.
 *
 * Exactly one generation per key is `current`; superseding it (a fresh [install]) or freeing its key
 * retires it. A retired generation with no outstanding lease is dropped immediately — there is no
 * automatic eviction of a leased one, and no automatic eviction at all otherwise. [free] retires every
 * generation for a matched key, marks the key `freed` (the reload marker [wasFreed] answers), deletes
 * every unleased generation, and reports the rest deferred; a key with no current generation and no
 * retired generation counts as already free. Accessing a freed key never fails here: the next [install]
 * simply installs a fresh generation and clears the marker, because freeing is never an error the caller
 * must recover from.
 *
 * This class carries the renderer mutex, because its per-key state is exactly what that mutex exists to
 * guard: every public method locks for its own state transition only, and never across an adapter call, a
 * decode, or a parse — none of which this cache ever performs itself. [free] and [report] share the same
 * locked snapshot, which is what makes the free/release race well defined: whichever call locks first
 * decides the outcome, so a free that wins reports its generation deferred and a release that wins lets
 * the following free see nothing left to defer.
 */
internal class ResidentCache {
    private val mutex = Mutex()
    private val entries: MutableMap<ResourceKey, KeyEntry> = mutableMapOf()

    fun current(key: ResourceKey): ResidentGeneration? = locked {
        entries[key]?.current
    }

    fun install(
        key: ResourceKey,
        stored: StoredRawResource,
        decoded: DecodedImage?,
    ): ResidentGeneration = locked {
        val entry = entries.getOrPut(key) { KeyEntry() }
        retireCurrent(entry)
        val generation = ResidentGeneration(key = key, stored = stored, decoded = decoded)
        entry.current = generation
        entry.freed = false
        generation
    }

    fun takeLease(generation: ResidentGeneration): Lease = locked {
        generation.addLease()
        Lease(generation)
    }

    fun releaseLease(lease: Lease): Unit = locked {
        lease.markReleased()
        val generation = lease.generation
        generation.removeLease()
        if (generation.leaseCount == 0) {
            entries[generation.key]?.retired?.remove(generation)
        }
    }

    fun free(selector: ResourceSelector): ResourceFreeResult = locked {
        var matched = 0
        var fullyFreed = 0
        var deferred = 0
        var alreadyFree = 0
        entries.forEach { (key, entry) ->
            if (!key.matches(selector)) return@forEach
            matched += 1
            if (entry.freed && entry.current == null && entry.retired.isEmpty()) {
                alreadyFree += 1
                return@forEach
            }
            entry.freed = true
            retireCurrent(entry)
            if (entry.retired.isEmpty()) fullyFreed += 1 else deferred += 1
        }
        ResourceFreeResult(
            matchedKeys = matched,
            fullyFreedKeys = fullyFreed,
            deferredKeys = deferred,
            alreadyFreeKeys = alreadyFree,
        )
    }

    fun report(selector: ResourceSelector): ResourceReport = locked {
        val reportEntries = entries
            .filterKeys { it.matches(selector) }
            .map { (key, entry) -> entry.toReportEntry(key) }
        ResourceReport(entries = reportEntries, totals = reportEntries.totalUsage())
    }

    fun wasFreed(key: ResourceKey): Boolean = locked {
        entries[key]?.freed ?: false
    }

    fun closeAll(): Unit = locked {
        entries.clear()
    }

    /**
     * Moves the key's current generation, if any, out of the `current` slot: retained in [KeyEntry.retired]
     * if it still has an outstanding lease, dropped immediately otherwise. Shared by [install] (supersession)
     * and [free] (retirement) — the two ways a current generation stops being current.
     */
    private fun retireCurrent(entry: KeyEntry) {
        val superseded = entry.current ?: return
        entry.current = null
        if (superseded.leaseCount > 0) {
            entry.retired += superseded
        }
    }

    private fun ResourceKey.matches(selector: ResourceSelector): Boolean = when (selector) {
        is ResourceSelector.All -> true
        is ResourceSelector.ByKind -> kind == selector.kind
        is ResourceSelector.ByClass -> resourceClass == selector.resourceClass
        is ResourceSelector.ByKey -> this == selector.key
    }

    private fun KeyEntry.toReportEntry(key: ResourceKey): ResourceReportEntry {
        val resident = listOfNotNull(current) + retired
        return ResourceReportEntry(
            key = key,
            residentGenerationCount = resident.size,
            retiredGenerationCount = retired.size,
            leaseCount = resident.sumOf { it.leaseCount },
            reloadRequired = freed,
            usage = ResourceUsage(
                rawBytes = resident.sumOf { it.stored.byteSnapshot.size.toLong() },
                decodedCpuBytes = resident.sumOf { (it.decoded?.byteCount ?: 0).toLong() },
                knownGpuBytes = 0L,
                hasUnknownGpuBytes = false,
            ),
        )
    }

    private fun List<ResourceReportEntry>.totalUsage(): ResourceUsage = ResourceUsage(
        rawBytes = sumOf { it.usage.rawBytes },
        decodedCpuBytes = sumOf { it.usage.decodedCpuBytes },
        knownGpuBytes = sumOf { it.usage.knownGpuBytes ?: 0L },
        hasUnknownGpuBytes = any { it.usage.hasUnknownGpuBytes },
    )

    /**
     * Runs [block] as this cache's one state transition at a time. Held only across the synchronous
     * bookkeeping in [block] — never across an adapter call, a decode, or a parse, none of which any
     * [ResidentCache] method performs. [Mutex.tryLock] and [Mutex.unlock] are safe to call from ordinary,
     * non-suspending code and across real threads, which is what lets [free] and [releaseLease] race from
     * separate coroutines and still linearize at this exact boundary.
     */
    private inline fun <T> locked(block: () -> T): T {
        while (!mutex.tryLock()) {
            // Uncontended in practice: every critical section here is a few field reads/writes.
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
