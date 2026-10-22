package com.rohittp.reng.internal.cache

import com.rohittp.reng.ResourceFreeResult
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceReport
import com.rohittp.reng.ResourceReportEntry
import com.rohittp.reng.ResourceResidency
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.ResourceUsage
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.internal.GpuByteAccount
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.model.DecodedModel
import com.rohittp.reng.internal.thread.PlatformLock

/**
 * Renderer-local identity of one installed CPU generation.
 *
 * Equality of bytes is deliberately irrelevant: reinstalling byte-identical content after a free is
 * still a new generation and must never revive GPU objects owned by the retired one.
 */
internal data class ResidentGenerationId(val value: Long)

/**
 * One resident copy of a [ResourceKey]'s raw bytes and, for image classes, its decoded pixels. A
 * generation is an identity, not a value: [ResidentCache.install] never interns by content, so two
 * installs of byte-identical bytes still produce two distinct, independently-leased generations — see
 * [ResidentCache] for why a retired generation is never resurrected. Raw bytes are retained for as long
 * as the generation itself is resident, never dropped after decode, because Cycle B's `NORMAL` rules use
 * a stale resident as a `304` baseline.
 */
internal class ResidentGeneration(
    val id: ResidentGenerationId,
    val key: ResourceKey,
    val stored: StoredRawResource,
    decoded: DecodedImage?,
) {
    /**
     * This generation's decoded pixels, once something has decoded them (ADR 0059).
     *
     * Attachable after construction rather than passed at install, because the resource driver that
     * installs bytes knows nothing about images: the decode needs `ResourceLimits` and a failure
     * vocabulary that belong to the renderer, and happens a step later.
     */
    var decoded: DecodedImage? = decoded
        private set

    /** The immutable draw-ready GLB expansion shared by every occurrence of this generation. */
    var decodedModel: DecodedModel? = null
        private set

    var leaseCount: Int = 0
        private set

    /**
     * What this generation costs the byte budget (ADR 0047), read once at construction.
     *
     * `byteSnapshot` is the internal no-copy accessor, so `.size` is a field read rather than the
     * array copy `bytes` would make — this is computed for every install and must not be the
     * expensive one. The decoded half is always zero in production today, because every install
     * passes `decoded = null`; it is included so the figure stays right the day one does not.
     */
    var byteSize: Long = stored.byteSnapshot.size.toLong() + (decoded?.byteCount ?: 0).toLong()
        private set

    /**
     * Attaches [image] unless something already has, and answers how many bytes that added — zero
     * when it did not, which is what keeps the cache's account true if two callers race.
     *
     * First writer wins. Two decodes of one generation produce equal pixels (the bytes are the same
     * bytes), so which is kept cannot matter; charging once is the only thing that can.
     */
    fun attachDecoded(image: DecodedImage): Long {
        if (decoded != null) return 0L
        decoded = image
        val added = image.byteCount.toLong()
        byteSize += added
        return added
    }

    /**
     * Attaches [model] once and returns the retained instance plus newly chargeable bytes.
     *
     * The cache lock makes observation and attachment one state transition. A caller may have done
     * duplicate speculative decode work before entering it, but only one expansion is retained or
     * charged. Renderer preparation is itself exclusive, so ordinary repeated model occurrences do
     * not even perform that speculative duplicate: they observe [decodedModel] first.
     */
    fun attachDecodedModel(model: DecodedModel): Pair<DecodedModel, Long> {
        decodedModel?.let { return it to 0L }
        decodedModel = model
        byteSize += model.decodedCpuBytes
        return model to model.decodedCpuBytes
    }

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

    /** When this key was last touched, from [ResidentCache]'s recency clock; larger is more recent. */
    var recencyStamp: Long = 0L

    /** An eviction candidate: a current generation that no lease holds. */
    val evictable: Boolean get() = current?.let { it.leaseCount == 0 } == true
}

/**
 * The resident cache: one entry per [ResourceKey] holding generations, leases, and a reload marker.
 *
 * Exactly one generation per key is `current`; superseding it (a fresh [install]) or freeing its key
 * retires it. A retired generation with no outstanding lease is dropped immediately, and since ADR
 * 0047 an **unleased current** generation is evicted too, least-recently-used first, once
 * [residentByteBudget] is exceeded — a leased one never is, at any size, because a budget bounds what
 * may stay resident rather than what must go. An evicted key is removed outright and is never marked
 * `freed`: that marker means the consumer asked, and [wasFreed] turns it into a diagnostic that would
 * then blame them for this cache's own decision. [free] retires every
 * generation for a matched key, marks the key `freed` (the reload marker [wasFreed] answers), deletes
 * every unleased generation, and reports the rest deferred; a key with no current generation and no
 * retired generation counts as already free. Accessing a freed key never fails here: the next [install]
 * simply installs a fresh generation and clears the marker, because freeing is never an error the caller
 * must recover from.
 *
 * This class carries the renderer lock, because its per-key state is exactly what that lock exists to
 * guard: every public method locks for its own state transition only, and never across an adapter call, a
 * decode, or a parse — none of which this cache ever performs itself. [free] and [report] share the same
 * locked snapshot, which is what makes the free/release race well defined: whichever call locks first
 * decides the outcome, so a free that wins reports its generation deferred and a release that wins lets
 * the following free see nothing left to defer.
 */
internal class ResidentCache(
    /**
     * The byte budget unleased resources are evicted down to (ADR 0047).
     *
     * Defaults to `Long.MAX_VALUE`, a cache that never evicts — which is what every test constructing
     * this without an argument gets, and what every release before ADR 0047 behaved like.
     */
    private val residentByteBudget: Long = Long.MAX_VALUE,
) {
    private val lock = PlatformLock()
    private val entries: MutableMap<ResourceKey, KeyEntry> = mutableMapOf()

    /** Stamps [KeyEntry.recencyStamp] on every touch, so stamp order is least-recently-touched first. */
    private var recencyClock: Long = 0L

    /**
     * The eviction candidates, least recently touched first: only keys whose current generation no lease
     * holds, so a sweep never steps past a leased key.
     *
     * Ordered by stamp rather than by insertion because a key becomes a candidate on its last lease
     * release, which does not touch it: it must sort among the candidates by when it was last touched.
     * Kept lazily: a key is pushed with its stamp whenever it becomes a candidate or is touched as one,
     * and an entry whose key has since been touched again, leased, evicted, superseded or dropped is
     * discarded when it surfaces. So the invariant is one-directional -- every candidate has an entry
     * under its current stamp -- and [evictableKeys] checks it against [entries] for tests.
     */
    private val evictionQueue = EvictionQueue()

    /**
     * Bytes of every generation [entries] holds, maintained on install and eviction rather than
     * summed on demand.
     *
     * Summed would mean walking every generation of every key on every install, which is the cost
     * [report] already pays once per `queryResources` and must not pay once per resource per frame.
     */
    private var residentBytes: Long = 0L

    /** Monotonic within this renderer/cache; zero is reserved as an invalid/unassigned token. */
    private var nextGenerationId: Long = 1L

    /**
     * What the budget has cost since this cache was created, cumulative and never decreasing
     * (ADR 0048). Counted here rather than per key because [evictOverBudget] removes the key.
     */
    private var evictedKeyCount: Long = 0L
    private var evictedBytes: Long = 0L

    /**
     * True once [closeAll] has run. [closeAll] empties [entries] exactly as eviction empties a key, so
     * absence alone cannot tell "this cache evicted it" from "this renderer is gone";
     * [observeOrRestoreAndTakeLease] must restore the first and never repopulate the second.
     */
    private var closed: Boolean = false

    fun current(key: ResourceKey): ResidentGeneration? = locked {
        val generation = entries[key]?.current
        if (generation != null) touch(key)
        generation
    }

    fun install(
        key: ResourceKey,
        stored: StoredRawResource,
        decoded: DecodedImage?,
    ): ResidentGeneration = locked {
        val entry = entries.getOrPut(key) { KeyEntry() }
        retireCurrent(entry)
        val generation = newGeneration(key = key, stored = stored, decoded = decoded)
        entry.current = generation
        entry.freed = false
        residentBytes += generation.byteSize
        touch(key)
        evictOverBudget()
        generation
    }

    /**
     * Records [image] as [generation]'s decoded pixels and charges them to the budget (ADR 0059).
     *
     * The sweep afterwards may evict the very generation just attached to, when nothing leases it.
     * That is correct: the caller holds the image it just decoded and draws this frame with it, and
     * what eviction decides is only whether the next frame decodes again. A budget that would not
     * bind on the largest thing in this cache would not be a budget.
     */
    fun attachDecoded(generation: ResidentGeneration, image: DecodedImage): Unit = locked {
        val added = generation.attachDecoded(image)
        if (added == 0L) return@locked
        residentBytes += added
        touch(generation.key)
        evictOverBudget()
    }

    /**
     * Records one decoded model on [generation], charging the retained expansion exactly once, and
     * returns whichever immutable model won a racing attachment.
     */
    fun attachDecodedModel(generation: ResidentGeneration, model: DecodedModel): DecodedModel = locked {
        val (retained, added) = generation.attachDecodedModel(model)
        if (added == 0L) return@locked retained
        residentBytes += added
        touch(generation.key)
        evictOverBudget()
        retained
    }

    fun takeLease(generation: ResidentGeneration): Lease = locked {
        generation.addLease()
        Lease(generation)
    }

    /**
     * Atomically installs a fresh generation for [key] and takes the caller's own lease on it in one
     * locked step. This exists because a visibility install is one conceptual action — "install the
     * generation and take the owner's lease" — that a separate [install] then [takeLease] pair cannot
     * deliver under concurrency: a freshly installed generation sits at `leaseCount == 0` in the gap
     * between the two calls, where a racing [free] can retire it there — dropping it from this cache's
     * own bookkeeping entirely, since a zero-lease generation is never kept in [KeyEntry.retired] — before
     * this caller's [takeLease] ever runs. [takeLease] itself never rejects a stale generation reference
     * (it mutates the object it is given directly, by design — see [ResidentGeneration]'s reference
     * identity), so the failure mode here is not a crash: it is a lease that is taken successfully but on
     * a generation [report] and a subsequent [free] can no longer see at all, because dropping already
     * erased every trace of it from [entries]. Locking both steps together closes that gap entirely: no
     * racing [free] can ever observe this generation with a zero lease count, so it is always retired
     * (deferred), never dropped.
     */
    fun installAndTakeLease(
        key: ResourceKey,
        stored: StoredRawResource,
        decoded: DecodedImage?,
    ): Lease = locked {
        val entry = entries.getOrPut(key) { KeyEntry() }
        retireCurrent(entry)
        val generation = newGeneration(key = key, stored = stored, decoded = decoded)
        generation.addLease()
        entry.current = generation
        entry.freed = false
        residentBytes += generation.byteSize
        touch(key)
        // Safe to sweep here: this generation already holds the caller's lease, so it can never be
        // its own victim however far over budget the install pushed the cache.
        evictOverBudget()
        Lease(generation)
    }

    /**
     * Atomically observes the current generation for [key] and takes the caller's lease on it in one
     * locked step, or returns `null` if there is no current generation. Closes the same class of gap as
     * [installAndTakeLease], for a caller re-leasing an already-resident generation rather than installing
     * a new one: a separate [current] then [takeLease] pair could observe a generation that a racing
     * [free] has already dropped from [entries] by the time [takeLease] runs — which, unlike the
     * [installAndTakeLease] gap, is not a bug to route around but a genuine race outcome this method
     * reports honestly as "nothing was resident to lease" rather than leasing a generation this cache no
     * longer tracks.
     */
    fun observeAndTakeLease(key: ResourceKey, requiredDigest: String? = null): Lease? = locked {
        val generation = entries[key]?.current ?: return@locked null
        // A caller holding freshly resolved bytes asks for the digest it resolved: same key and same
        // digest is the same content, so the generation already here will do and a byte-identical
        // twin need not be installed (ADR 0059). Checked inside the lock for the reason everything
        // else here is: a check outside it is the two-call gap this method exists to close.
        if (requiredDigest != null && generation.stored.contentDigest != requiredDigest) {
            return@locked null
        }
        generation.addLease()
        touch(key)
        Lease(generation)
    }

    /**
     * [observeAndTakeLease] for a route that selected [key] as resident earlier in its own lookup and holds
     * the [stored] bytes it observed then: leases the current generation if there is one, and otherwise —
     * when the key was **evicted** in between — installs [stored] as a new generation and leases it, in
     * one locked step.
     *
     * **Why restoring is right.** A lookup observes residency without leasing it, then runs its class
     * gates (a PNG decode, a GLB parse), and only then installs visibility; the budget sweep that runs on
     * every install, attach and lease release may evict the key in that window, since nothing leased it,
     * and refusing then would fail a frame whose route holds every byte it needs (ADR 0047's 2026-10-22
     * note). An evicted key is one this cache forgot on its own
     * (ADR 0047), and the bytes in hand are the exact bytes the class gates just validated, so installing
     * them is the reload the next lookup would have made anyway (ADR 0059's same-key, same-digest content).
     *
     * **What it refuses**, answering `null` as [observeAndTakeLease] does:
     * - a key the consumer **freed** (its entry is kept with no current generation): restoring would undo
     *   the consumer's own `free()`;
     * - a **closed** cache: [closeAll] empties [entries] too, and repopulating a closed renderer's cache
     *   would leak a generation nothing will ever release.
     *
     * A key whose current generation was superseded by another route keeps today's behaviour: that
     * generation is leased. It reached `current` through its own validated install.
     */
    fun observeOrRestoreAndTakeLease(key: ResourceKey, stored: StoredRawResource): Lease? = locked {
        // First, before any lease: a generation installed after close by another path must not be
        // handed out either.
        if (closed) return@locked null
        val entry = entries[key]
        val current = entry?.current
        if (current != null) {
            current.addLease()
            touch(key)
            return@locked Lease(current)
        }
        // An entry with no current generation is either a consumer's free(), which is refused, or a
        // key whose current generation the budget evicted while a retired one is still leased, which
        // is restored into the same entry so its retired generations stay tracked.
        if (entry != null && entry.freed) return@locked null
        val restored = entry ?: KeyEntry().also { entries[key] = it }
        val generation = newGeneration(key = key, stored = stored, decoded = null)
        generation.addLease()
        restored.current = generation
        residentBytes += generation.byteSize
        touch(key)
        // Safe here for the reason it is safe in installAndTakeLease: the new generation is leased.
        evictOverBudget()
        Lease(generation)
    }

    fun releaseLease(lease: Lease): Unit = locked {
        lease.markReleased()
        val generation = lease.generation
        generation.removeLease()
        if (generation.leaseCount == 0) {
            val entry = entries[generation.key]
            // The last lease on a current generation makes its key a candidate, at its last touch.
            if (entry != null && entry.current === generation) {
                enqueueCandidate(generation.key, entry)
            }
            val removed = entry?.retired?.remove(generation) == true
            if (removed) residentBytes -= generation.byteSize
            // An entry whose current generation was evicted while this one was leased has nothing left.
            if (removed && entry != null) removeIfEmptyAndUnfreed(generation.key, entry)
            // A current generation can have kept the cache over budget only because this lease pinned
            // it. The moment the last lease goes it is a candidate, so enforce the ceiling before the
            // release returns rather than waiting for an unrelated future install.
            evictOverBudget()
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

    /**
     * The point-in-time account of every key matching [selector], with each key's GPU bytes supplied
     * by [gpuBytes] rather than known here.
     *
     * A lookup rather than a registry, deliberately: this class owns raw bytes and decoded pixels and
     * has never known what a GL texture is, so the alternative — handing it the GL object registry —
     * would put the whole GPU layer inside the CPU cache to answer two fields. [GpuByteAccount] is
     * the seam, and it names no GL concept.
     *
     * [gpuBytes] is called under this cache's own lock, which is safe for exactly the reason the lock
     * holds at all: like everything else in a [locked] block it must be a synchronous handful of
     * reads, never an adapter call, a decode or a parse. The GL registry's answer is a map lookup.
     */
    fun report(selector: ResourceSelector, gpuBytes: (ResourceKey) -> GpuByteAccount): ResourceReport = locked {
        val reportEntries = entries
            .filterKeys { it.matches(selector) }
            .map { (key, entry) -> entry.toReportEntry(key, gpuBytes(key)) }
        ResourceReport(
            entries = reportEntries,
            totals = reportEntries.totalUsage(),
            // Unfiltered on purpose: the budget governs every key, so reporting only the selected
            // ones' bytes against it would invite a comparison that means nothing.
            cpuResidency = ResourceResidency(
                residentBytes = residentBytes,
                budgetBytes = residentByteBudget,
                evictedKeyCount = evictedKeyCount,
                evictedBytes = evictedBytes,
            ),
        )
    }

    fun wasFreed(key: ResourceKey): Boolean = locked {
        entries[key]?.freed ?: false
    }

    /**
     * Point-in-time generation identities selected for retirement. Callers use this immediately
     * before [free] to retire generation-owned GPU groups, including CPU generations that have not
     * uploaded anything yet. The public free result remains keyed/count-based and unchanged.
     */
    fun generationIds(selector: ResourceSelector): Map<ResourceKey, Set<ResidentGenerationId>> = locked {
        entries
            .filterKeys { it.matches(selector) }
            .mapValues { (_, entry) -> (listOfNotNull(entry.current) + entry.retired).mapTo(linkedSetOf()) { it.id } }
    }

    fun closeAll(): Unit = locked {
        closed = true
        entries.clear()
        evictionQueue.clear()
        residentBytes = 0L
        // The cumulative counters go with it. They are "since this renderer was created" (ADR 0048)
        // and a closed renderer answers `emptyResourceReport()` regardless, so keeping a history
        // nothing can read would only be state to get wrong.
        evictedKeyCount = 0L
        evictedBytes = 0L
    }

    private fun newGeneration(
        key: ResourceKey,
        stored: StoredRawResource,
        decoded: DecodedImage?,
    ): ResidentGeneration {
        check(nextGenerationId != Long.MAX_VALUE) { "resident generation identity space exhausted" }
        val id = ResidentGenerationId(nextGenerationId)
        nextGenerationId += 1L
        return ResidentGeneration(id = id, key = key, stored = stored, decoded = decoded)
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
        } else {
            // Dropped entirely, so its bytes leave with it. A retired-but-leased generation stays
            // counted, because it is still held.
            residentBytes -= superseded.byteSize
        }
    }

    /** Makes [key] the most recently touched; a key with no entry has no recency to bump. */
    private fun touch(key: ResourceKey) {
        val entry = entries[key] ?: return
        check(recencyClock != Long.MAX_VALUE) { "resident cache recency space exhausted" }
        recencyClock += 1L
        entry.recencyStamp = recencyClock
        if (entry.evictable) enqueueCandidate(key, entry)
    }

    private fun enqueueCandidate(key: ResourceKey, entry: KeyEntry) {
        // Stale entries are discarded only when they surface; rebuild once they outnumber the keys.
        if (evictionQueue.size >= 2 * entries.size + EVICTION_QUEUE_SLACK) {
            evictionQueue.clear()
            // Every current candidate, [key] among them, under its current stamp.
            entries.forEach { (candidate, candidateEntry) ->
                if (candidateEntry.evictable) evictionQueue.push(candidateEntry.recencyStamp, candidate)
            }
            return
        }
        evictionQueue.push(entry.recencyStamp, key)
    }

    /**
     * Evicts least-recently-used current generations until [residentBytes] is within
     * [residentByteBudget].
     *
     * A current generation is a candidate whenever it holds no lease. A leased one is skipped rather
     * than stopping the sweep, so one live frame cannot pin the whole cache above its budget. A key's
     * retired generations are not candidates -- each exists only because something leases it -- and
     * they do not shield the key's unleased current generation, or an old frame holding the previous
     * generation would keep the new one resident for as long as it lived.
     *
     * An evicted key is **never marked `freed`** (ADR 0047): `freed` means the consumer asked, and
     * `wasFreed` turns it into a diagnostic blaming them for a decision this cache made on its own. With
     * no retired generation it is removed from [entries] outright; with one, its entry stays, holding
     * only the retired generations, until [releaseLease] drops the last of them. Either way it has no
     * current generation, so every lookup treats it as not resident and the next install reloads it.
     */
    private fun evictOverBudget() {
        while (residentBytes > residentByteBudget && evictionQueue.size > 0) {
            val stamp = evictionQueue.minimumStamp()
            val key = evictionQueue.popMinimum()
            val entry = entries[key] ?: continue
            // Stale: touched again since (a newer entry stands for it), or no longer a candidate.
            if (entry.recencyStamp != stamp || !entry.evictable) continue
            val generation = checkNotNull(entry.current)
            residentBytes -= generation.byteSize
            evictedKeyCount += 1L
            evictedBytes += generation.byteSize
            entry.current = null
            if (isEmptyAndUnfreed(entry)) entries.remove(key)
        }
    }

    /** The eviction candidates as the queue holds them, least recently touched first; for tests. */
    internal fun evictableKeys(): List<ResourceKey> = locked {
        evictionQueue.entries()
            .filter { (stamp, key) -> entries[key]?.let { it.recencyStamp == stamp && it.evictable } == true }
            .sortedBy { it.first }
            .map { it.second }
            .distinct()
    }

    /** The eviction candidates recomputed from every entry, least recently touched first; for tests. */
    internal fun recountEvictableKeys(): List<ResourceKey> = locked {
        entries.filterValues { it.evictable }.entries.sortedBy { it.value.recencyStamp }.map { it.key }
    }

    /** Forgets an entry eviction emptied: no current, no retired, and no consumer free to remember. */
    private fun removeIfEmptyAndUnfreed(key: ResourceKey, entry: KeyEntry) {
        if (!isEmptyAndUnfreed(entry)) return
        entries.remove(key)
    }

    private fun isEmptyAndUnfreed(entry: KeyEntry): Boolean =
        entry.current == null && entry.retired.isEmpty() && !entry.freed

    private fun ResourceKey.matches(selector: ResourceSelector): Boolean = when (selector) {
        is ResourceSelector.All -> true
        is ResourceSelector.ByKind -> kind == selector.kind
        is ResourceSelector.ByClass -> resourceClass == selector.resourceClass
        is ResourceSelector.ByKey -> this == selector.key
    }

    private fun KeyEntry.toReportEntry(key: ResourceKey, gpu: GpuByteAccount): ResourceReportEntry {
        val resident = listOfNotNull(current) + retired
        return ResourceReportEntry(
            key = key,
            residentGenerationCount = resident.size,
            retiredGenerationCount = retired.size,
            leaseCount = resident.sumOf { it.leaseCount },
            reloadRequired = freed,
            usage = ResourceUsage(
                rawBytes = resident.sumOf { it.stored.byteSnapshot.size.toLong() },
                decodedCpuBytes = resident.sumOf {
                    (it.decoded?.byteCount ?: 0).toLong() + (it.decodedModel?.decodedCpuBytes ?: 0L)
                },
                knownGpuBytes = gpu.knownBytes,
                hasUnknownGpuBytes = gpu.hasUnknownBytes,
            ),
        )
    }

    /**
     * Sums usage across matched entries. The two GPU fields aggregate differently on purpose: an
     * entry that knows nothing contributes nothing to the known sum but does set the flag, so a total
     * of "1 MiB known" beside `hasUnknownGpuBytes = true` reads as "at least this much, and there is
     * more nobody counted" — CONTEXT.md's rule that when known and unknown allocations coexist the
     * known sum stays present and the flag goes up. Dropping unknown entries from the sum silently
     * would be the same lie one level up.
     */
    private fun List<ResourceReportEntry>.totalUsage(): ResourceUsage = ResourceUsage(
        rawBytes = sumOf { it.usage.rawBytes },
        decodedCpuBytes = sumOf { it.usage.decodedCpuBytes },
        knownGpuBytes = sumOf { it.usage.knownGpuBytes ?: 0L },
        hasUnknownGpuBytes = any { it.usage.hasUnknownGpuBytes },
    )

    /**
     * Runs [block] as this cache's one state transition at a time. Held only across the synchronous
     * bookkeeping in [block] — never across an adapter call, a decode, or a parse, none of which any
     * [ResidentCache] method performs. [PlatformLock] blocks a contending thread rather than busy-spinning,
     * so a holder descheduled between those few field operations cannot make another worker peg a core or
     * starve it indefinitely. [free] and [releaseLease] still linearize at this exact boundary.
     */
    private fun <T> locked(block: () -> T): T = lock.withLock(block)
}

private const val EVICTION_QUEUE_SLACK: Int = 64

/** A binary min-heap of (recency stamp, key), smallest stamp first. Confined to [ResidentCache]'s lock. */
private class EvictionQueue {
    private var stamps = LongArray(16)
    private var keys = arrayOfNulls<ResourceKey>(16)

    var size: Int = 0
        private set

    fun clear() {
        keys.fill(null, 0, size)
        size = 0
    }

    fun push(stamp: Long, key: ResourceKey) {
        if (size == stamps.size) {
            stamps = stamps.copyOf(size * 2)
            keys = keys.copyOf(size * 2)
        }
        var index = size
        size += 1
        while (index > 0) {
            val parent = (index - 1) / 2
            if (stamps[parent] <= stamp) break
            stamps[index] = stamps[parent]
            keys[index] = keys[parent]
            index = parent
        }
        stamps[index] = stamp
        keys[index] = key
    }

    fun minimumStamp(): Long {
        check(size > 0) { "the eviction queue is empty" }
        return stamps[0]
    }

    fun popMinimum(): ResourceKey {
        check(size > 0) { "the eviction queue is empty" }
        val minimum = checkNotNull(keys[0])
        size -= 1
        val lastStamp = stamps[size]
        val lastKey = keys[size]
        keys[size] = null
        if (size > 0) {
            var index = 0
            while (true) {
                val left = 2 * index + 1
                if (left >= size) break
                val right = left + 1
                val child = if (right < size && stamps[right] < stamps[left]) right else left
                if (stamps[child] >= lastStamp) break
                stamps[index] = stamps[child]
                keys[index] = keys[child]
                index = child
            }
            stamps[index] = lastStamp
            keys[index] = lastKey
        }
        return minimum
    }

    /** Every queued `(stamp, key)`, in heap order; read only by [ResidentCache.evictableKeys]. */
    fun entries(): List<Pair<Long, ResourceKey>> = List(size) { stamps[it] to checkNotNull(keys[it]) }
}
