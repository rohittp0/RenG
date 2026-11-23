package com.rohittp.reng.internal.firewall

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * The remembered tile keys (ADR 0069) are reached from `prepare` and from the draw path, and CONTEXT.md
 * lets a draw overlap GL-free preparation, so the map behind them is read and evicted from several
 * threads at once. This drives that on real threads, past the bound so eviction runs too, and asserts
 * what a lost update or a torn eviction would break: no exception, and every caller sees the one key a
 * single thread would have derived.
 */
class RememberedTileKeyConcurrencyTest {

    @Test
    fun concurrentAskersPastTheBoundAllSeeTheKeyASingleThreadDerives() = runTest {
        val tileCount = MAXIMUM_REMEMBERED_TILE_KEYS + 600
        fun tile(index: Int) = CanonicalBasemapTile(lod = 22, tileY = index, canonicalX = index % 97)
        val reference = basemapEngineHost()
        val expected: List<ResourceKey> = try {
            (0 until tileCount).map { reference.renderedTileKey(DIGEST, tile(it)) }
        } finally {
            reference.close()
        }

        val host = basemapEngineHost()
        try {
            val seen = withContext(Dispatchers.Default) {
                (0 until 8).map { worker ->
                    async {
                        // Each worker walks the whole range from a different offset, twice, so misses,
                        // hits and evictions all interleave across workers.
                        (0 until tileCount * 2).map { step ->
                            val index = (step + worker * 517) % tileCount
                            index to host.renderedTileKey(DIGEST, tile(index))
                        }
                    }
                }.awaitAll()
            }
            seen.forEach { worker ->
                worker.forEach { (index, key) -> assertEquals(expected[index], key, "tile $index") }
            }
        } finally {
            host.close()
        }
    }

    private companion object {
        const val DIGEST: String = "0123456789abcdef"
    }
}
