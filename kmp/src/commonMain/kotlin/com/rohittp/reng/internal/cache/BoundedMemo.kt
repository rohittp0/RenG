package com.rohittp.reng.internal.cache

/**
 * A map of derived values bounded at [capacity] entries, spending its bound by second chance (CLOCK):
 * entries that have been asked for since they were last considered outlive entries that have not.
 *
 * A hit only marks its entry referenced, so a lookup costs what a plain map lookup costs and never
 * reorders the map. An insert into a full memo walks from the oldest entry: a referenced entry has its
 * mark cleared and moves to the newest end, and the first unmarked entry is forgotten. Values a frame asks
 * for every frame therefore stay remembered however many one-off keys pass through, and the new entry is
 * never the one forgotten to make room for itself.
 *
 * Not synchronised: every owner that is reached from more than one thread wraps each call in its own lock,
 * and runs the derivation the memo saves outside it.
 */
internal class BoundedMemo<K : Any, V : Any>(private val capacity: Int) {
    init {
        require(capacity >= 1) { "capacity must be at least 1" }
    }

    private class Slot<V>(val value: V) {
        var referenced: Boolean = false
    }

    /** Insertion order, oldest first; an entry given a second chance is re-inserted at the newest end. */
    private val slots: LinkedHashMap<K, Slot<V>> = LinkedHashMap()

    val size: Int get() = slots.size

    /** The value remembered for [key], now marked referenced, or `null`. */
    fun get(key: K): V? {
        val slot = slots[key] ?: return null
        slot.referenced = true
        return slot.value
    }

    /**
     * Remembers [value] for [key] unless a value is already remembered, and returns whichever is now
     * remembered: two callers that both missed derive equal values, and the first insert wins.
     */
    fun putIfAbsent(key: K, value: V): V {
        get(key)?.let { return it }
        while (slots.size >= capacity) evictOne()
        slots[key] = Slot(value)
        return value
    }

    /**
     * Forgets the oldest unreferenced entry, clearing and moving to the newest end every referenced entry
     * in front of it. Terminates within one pass plus one entry: every entry it passes loses its mark.
     */
    private fun evictOne() {
        while (true) {
            val oldest = slots.entries.iterator()
            val entry = oldest.next()
            val key = entry.key
            val slot = entry.value
            oldest.remove()
            if (!slot.referenced) return
            slot.referenced = false
            slots[key] = slot
        }
    }
}
