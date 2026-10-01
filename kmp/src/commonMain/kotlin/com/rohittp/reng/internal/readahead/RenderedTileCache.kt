package com.rohittp.reng.internal.readahead

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.internal.firewall.BasemapTilePixels
import com.rohittp.reng.internal.firewall.RenderedBasemapTile
import com.rohittp.reng.internal.thread.PlatformLock

/**
 * The tiles the read-ahead worker has already drawn, waiting for the frames that will want them (ADR 0077).
 *
 * **This is a bounded queue of read-ahead output and not a memo.** ADR 0052 refused a renderer-lifetime
 * memo of raw pixels. This holds only what the worker drew, is charged by byte size against an explicit
 * budget, evicts the entry used least recently when an insertion would pass it, and gives an entry up when
 * a frame takes it ([remove]). Nothing a `prepare` drew is ever put here.
 *
 * **It holds no GL object**, only keys and pixel arrays, so no operation on it needs the render context.
 *
 * **Locked.** The worker inserts and the preparing thread reads and removes, concurrently. Every critical
 * section is a few map operations: pixel arrays arrive already built and are only ever referenced, never
 * copied, under the lock.
 *
 * **One style at a time.** [adoptStyle] is called by `prepare` with the digest of the style it committed
 * and drops entries held for any other. An insertion for a digest other than the adopted one is refused,
 * so a window planned against a style that has since changed cannot repopulate the cache with tiles
 * nothing can hit. Before the first adoption nothing is accepted.
 *
 * [close] ends it: the cache empties and every later insertion is refused, which is what lets
 * `RenGRenderer.close()` promise that a draw that was already finishing leaves no pixels behind.
 */
internal class RenderedTileCache(private val budgetBytes: Long) {
    init {
        require(budgetBytes > 0L) { "a rendered-tile cache with no budget is no cache; leave it out instead" }
    }

    private class Entry(val tile: RenderedBasemapTile, val bytes: Long, val styleDigest: String)

    private val lock = PlatformLock()

    // Insertion order is recency order: an entry that is used is removed and re-inserted at the end, so
    // the first entry is always the least recently used.
    private val entries = LinkedHashMap<ResourceKey, Entry>()
    private var heldBytes = 0L
    private var adoptedStyleDigest: String? = null
    private var closed = false

    /** What one [put] did. */
    internal class PutOutcome(val stored: Boolean, val evicted: Int)

    /** Tiles held. For tests and the renderer's own gating. */
    val size: Int get() = lock.withLock { entries.size }

    /** Bytes held. For tests. */
    val bytes: Long get() = lock.withLock { heldBytes }

    /** Whether [key] is held, without touching its recency. */
    fun contains(key: ResourceKey): Boolean = lock.withLock { entries.containsKey(key) }

    /**
     * Declares [styleDigest] the style `prepare` has committed and drops every entry held for another,
     * returning how many were dropped. Idempotent for the digest already adopted.
     */
    fun adoptStyle(styleDigest: String): Int = lock.withLock {
        if (closed) return@withLock 0
        val dropped = if (adoptedStyleDigest != null && adoptedStyleDigest != styleDigest) dropAll() else 0
        adoptedStyleDigest = styleDigest
        dropped
    }

    /**
     * Stores [tile] under its own key, charged at its raw pixel size, and evicts least-recently-used
     * entries until the cache is within budget again. Refused, with nothing evicted, when the tile is not
     * raw pixels, is larger than the whole budget, was drawn for a style other than the adopted one, or the
     * cache is closed. A tile already held is replaced and counts as used.
     */
    fun put(styleDigest: String, tile: RenderedBasemapTile): PutOutcome {
        val pixels = tile.pixels as? BasemapTilePixels.Raw ?: return REFUSED
        val size = pixels.rgba.size.toLong()
        if (size <= 0L || size > budgetBytes) return REFUSED
        return lock.withLock {
            if (closed || adoptedStyleDigest != styleDigest) return@withLock REFUSED
            entries.remove(tile.key)?.let { heldBytes -= it.bytes }
            var evicted = 0
            while (heldBytes + size > budgetBytes) {
                val oldest = entries.entries.iterator()
                if (!oldest.hasNext()) break
                val victim = oldest.next()
                heldBytes -= victim.value.bytes
                oldest.remove()
                evicted += 1
            }
            entries[tile.key] = Entry(tile, size, styleDigest)
            heldBytes += size
            PutOutcome(stored = true, evicted = evicted)
        }
    }

    /**
     * The tile held under [key], or `null`, marking it most recently used. It stays held: a frame that
     * cannot use it after all has not consumed it. A frame that does use it calls [remove].
     */
    fun peek(key: ResourceKey): RenderedBasemapTile? = lock.withLock {
        val entry = entries.remove(key) ?: return@withLock null
        entries[key] = entry
        entry.tile
    }

    /** Gives up the entry under [key], returning whether there was one. */
    fun remove(key: ResourceKey): Boolean = lock.withLock {
        val entry = entries.remove(key) ?: return@withLock false
        heldBytes -= entry.bytes
        true
    }

    /** Drops every entry [selector] matches and returns how many went. [ResourceSelector.All] empties it. */
    fun free(selector: ResourceSelector): Int = lock.withLock {
        if (selector is ResourceSelector.All) return@withLock dropAll()
        val matching = entries.keys.filter { it.matches(selector) }
        for (key in matching) entries.remove(key)?.let { heldBytes -= it.bytes }
        matching.size
    }

    /** Empties the cache and forgets the adopted style, returning how many entries went. */
    fun clear(): Int = lock.withLock {
        adoptedStyleDigest = null
        dropAll()
    }

    /** Empties the cache and refuses every later insertion. Idempotent. */
    fun close() {
        lock.withLock {
            closed = true
            adoptedStyleDigest = null
            dropAll()
        }
    }

    private fun dropAll(): Int {
        val count = entries.size
        entries.clear()
        heldBytes = 0L
        return count
    }

    private fun ResourceKey.matches(selector: ResourceSelector): Boolean = when (selector) {
        is ResourceSelector.All -> true
        is ResourceSelector.ByKind -> kind == selector.kind
        is ResourceSelector.ByClass -> resourceClass == selector.resourceClass
        is ResourceSelector.ByKey -> this == selector.key
    }

    private companion object {
        val REFUSED = PutOutcome(stored = false, evicted = 0)
    }
}
