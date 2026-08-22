package com.rohittp.reng.smoke.harness

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference

/**
 * The consumer's persistent cache, which for a harness is a map that dies with the process.
 *
 * Deliberately in-memory: RenG owns no cache of its own, so a store that forgets everything on exit
 * keeps the harness honest about what RenG actually re-fetches between frames. A real consumer puts
 * a disk cache here.
 *
 * Copy-on-write through one atomic reference rather than a lock, because RenG may read and write
 * from several coroutine workers at once and Kotlin/Native's stdlib ships no mutex.
 */
internal class MemoryStore : Store {
    private val entries = AtomicReference<Map<String, StoredRawResource>>(emptyMap())
    private val readCount = AtomicInt(0)
    private val hitCount = AtomicInt(0)
    private val writeCount = AtomicInt(0)

    override suspend fun read(key: RawResourceKey): StoredRawResource? {
        readCount.addAndGet(1)
        val found = entries.value[identity(key)]
        if (found != null) hitCount.addAndGet(1)
        return found
    }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        writeCount.addAndGet(1)
        val id = identity(key)
        while (true) {
            val current = entries.value
            if (entries.compareAndSet(current, current + (id to resource))) return
        }
    }

    fun summary(): String =
        "store: ${entries.value.size} entries, ${readCount.value} reads " +
            "(${hitCount.value} hits), ${writeCount.value} writes"

    private fun identity(key: RawResourceKey): String = key.resourceClass.name + " " + key.stableId
}
