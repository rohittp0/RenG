package com.rohittp.reng.internal.readahead

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.internal.firewall.BasemapTilePixels
import com.rohittp.reng.internal.firewall.RenderedBasemapTile
import com.rohittp.reng.internal.firewall.basemapTileKey
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The rendered-tile cache (ADR 0077) on its own: a bounded, least-recently-used queue of the pixels the
 * read-ahead worker drew, with no engine and no GL.
 *
 * Every tile is [TILE_BYTES] so the budget arithmetic is exact, and every one is a distinct tile with its
 * own derived key, so a build that confused two entries would be caught by the identity assertions rather
 * than pass on equal sizes.
 */
class RenderedTileCacheTest {

    private fun tile(index: Int, bytes: Int = TILE_BYTES, styleDigest: String = STYLE): RenderedBasemapTile {
        val canonical = CanonicalBasemapTile(lod = 6, tileY = index, canonicalX = index * 3)
        return RenderedBasemapTile(
            key = basemapTileKey(
                styleDigest,
                canonical,
                OutputPixelSize(width = 16, height = 16),
            ),
            tile = canonical,
            pixels = BasemapTilePixels.Raw(ByteArray(bytes) { (index + it).toByte() }, 16, bytes / 64),
            contentKey = "content-$index",
            substitutions = emptyList(),
        )
    }

    private fun cache(tiles: Int): RenderedTileCache =
        RenderedTileCache(budgetBytes = tiles.toLong() * TILE_BYTES).also { it.adoptStyles(setOf(STYLE)) }

    @Test
    fun theBudgetEvictsTheLeastRecentlyUsedTileFirst() {
        val cache = cache(tiles = 3)
        val a = tile(1)
        val b = tile(2)
        val c = tile(3)
        val d = tile(4)
        listOf(a, b, c).forEach { assertTrue(cache.put(STYLE, it).stored) }
        assertEquals(3, cache.size)

        // Using `a` makes `b` the oldest, which is the point: recency, not insertion order, decides.
        assertSame(a, cache.peek(a.key))

        val outcome = cache.put(STYLE, d)
        assertTrue(outcome.stored)
        assertEquals(1, outcome.evicted, "one tile over budget pushes out exactly one")
        assertFalse(cache.contains(b.key), "the least recently used tile is the one dropped")
        assertTrue(cache.contains(a.key) && cache.contains(c.key) && cache.contains(d.key))
        assertEquals(3L * TILE_BYTES, cache.bytes, "the account follows the contents")
    }

    @Test
    fun anEvictionChoiceThatInsertionOrderAloneWouldGetWrongIsGotRight() {
        // The same shape without the touch evicts `a`; with it, `b`. Distinguishes LRU from FIFO.
        val untouched = cache(tiles = 2)
        untouched.put(STYLE, tile(1))
        untouched.put(STYLE, tile(2))
        untouched.put(STYLE, tile(3))
        assertFalse(untouched.contains(tile(1).key), "without a use, the first in is the first out")

        val touched = cache(tiles = 2)
        touched.put(STYLE, tile(1))
        touched.put(STYLE, tile(2))
        touched.peek(tile(1).key)
        touched.put(STYLE, tile(3))
        assertTrue(touched.contains(tile(1).key), "a tile that was used survives")
        assertFalse(touched.contains(tile(2).key))
    }

    @Test
    fun aTileTakenByAFrameLeavesTheCacheAndFreesItsBytes() {
        val cache = cache(tiles = 3)
        val a = tile(1)
        cache.put(STYLE, a)
        cache.put(STYLE, tile(2))
        assertSame(a, cache.peek(a.key), "peeking does not consume")
        assertEquals(2, cache.size)

        assertTrue(cache.remove(a.key))
        assertFalse(cache.remove(a.key), "a tile is taken once")
        assertNull(cache.peek(a.key))
        assertEquals(TILE_BYTES.toLong(), cache.bytes)
    }

    @Test
    fun aStyleChangeDropsEverythingHeldForTheOldStyle() {
        val cache = cache(tiles = 4)
        cache.put(STYLE, tile(1))
        cache.put(STYLE, tile(2))

        assertEquals(0, cache.adoptStyles(setOf(STYLE)), "adopting the style already adopted drops nothing")
        assertEquals(
            2,
            cache.adoptStyles(setOf("another-style")),
            "adopting a different one drops what the old one held",
        )
        assertEquals(0, cache.size)
        assertEquals(0L, cache.bytes)

        // A window planned against the old style that finishes late is refused, not inserted.
        assertFalse(cache.put(STYLE, tile(3)).stored, "a tile for a style no longer adopted is refused")
        assertEquals(0, cache.size)
        assertTrue(cache.put("another-style", tile(3)).stored)
    }

    @Test
    fun anAdoptedStyleSetKeepsItsTwoStylesAndDropsOnlyOutsiders() {
        val firstStyle = "first-style"
        val secondStyle = "second-style"
        val thirdStyle = "third-style"
        val cache = RenderedTileCache(budgetBytes = 4L * TILE_BYTES)
        cache.adoptStyles(setOf(firstStyle, secondStyle))
        val first = tile(index = 1, styleDigest = firstStyle)
        val second = tile(index = 1, styleDigest = secondStyle)
        val third = tile(index = 1, styleDigest = thirdStyle)

        assertTrue(cache.put(firstStyle, first).stored)
        assertTrue(cache.put(secondStyle, second).stored)
        assertFalse(cache.put(thirdStyle, third).stored)
        assertEquals(2, cache.size)

        assertEquals(1, cache.adoptStyles(setOf(secondStyle, thirdStyle)))
        assertFalse(cache.contains(first.key))
        assertTrue(cache.contains(second.key))
        assertTrue(cache.put(thirdStyle, third).stored)
        assertEquals(2, cache.size)
    }

    @Test
    fun nothingIsAcceptedBeforeAStyleIsAdopted() {
        val cache = RenderedTileCache(budgetBytes = 4L * TILE_BYTES)
        assertFalse(cache.put(STYLE, tile(1)).stored)
        assertEquals(0, cache.size)
    }

    @Test
    fun aTileThatCannotFitOrIsNotRawIsRefusedWithoutEvictingAnything() {
        val cache = cache(tiles = 2)
        cache.put(STYLE, tile(1))

        val oversize = cache.put(STYLE, tile(2, bytes = 3 * TILE_BYTES))
        assertFalse(oversize.stored)
        assertEquals(0, oversize.evicted, "a tile larger than the whole budget evicts nothing to make room")
        assertTrue(cache.contains(tile(1).key))

        val encoded = RenderedBasemapTile(
            key = tile(3).key,
            tile = tile(3).tile,
            pixels = BasemapTilePixels.Encoded(ByteArray(8)),
            contentKey = "png",
            substitutions = emptyList(),
        )
        assertFalse(cache.put(STYLE, encoded).stored, "only raw pixels are cached: there is no encoder to fall back on")
        assertEquals(1, cache.size)
    }

    @Test
    fun freeingBySelectorDropsWhatMatches() {
        val cache = cache(tiles = 4)
        val a = tile(1)
        val b = tile(2)
        cache.put(STYLE, a)
        cache.put(STYLE, b)

        assertEquals(1, cache.free(ResourceSelector.ByKey(a.key)))
        assertFalse(cache.contains(a.key))
        assertTrue(cache.contains(b.key))
        assertEquals(0, cache.free(ResourceSelector.ByKind(ResourceKind.MODEL_IMAGE)), "another kind matches nothing")
        assertEquals(1, cache.free(ResourceSelector.ByKind(ResourceKind.BASEMAP_TILE)))
        assertEquals(0, cache.size)

        cache.put(STYLE, a)
        cache.put(STYLE, b)
        assertEquals(2, cache.free(ResourceSelector.All))
        assertEquals(0L, cache.bytes)
    }

    @Test
    fun aClosedCacheIsEmptyAndRefusesEverything() {
        val cache = cache(tiles = 2)
        cache.put(STYLE, tile(1))

        cache.close()
        assertEquals(0, cache.size)
        assertFalse(cache.put(STYLE, tile(2)).stored, "a draw already finishing when close ran leaves nothing behind")
        assertFalse(cache.adoptStyles(setOf(STYLE)) > 0)
        assertFalse(cache.put(STYLE, tile(3)).stored, "adopting after close does not reopen it")
        cache.close()
    }

    @Test
    fun discardingEntriesForReloadRetainsTheAdoptedStyleSet() {
        val cache = cache(tiles = 2)
        cache.put(STYLE, tile(1))

        assertEquals(1, cache.discardEntries())
        assertEquals(0, cache.size)
        assertTrue(cache.put(STYLE, tile(2)).stored)
    }

    @Test
    fun replacingATileKeepsOneEntryAndTheRightByteCount() {
        val cache = cache(tiles = 2)
        cache.put(STYLE, tile(1))
        val again = cache.put(STYLE, tile(1))
        assertTrue(again.stored)
        assertEquals(0, again.evicted)
        assertEquals(1, cache.size)
        assertEquals(TILE_BYTES.toLong(), cache.bytes)
        assertNotNull(cache.peek(tile(1).key))
    }

    @Test
    fun aCacheWithNoBudgetIsRejected() {
        assertFailsWith<IllegalArgumentException> { RenderedTileCache(budgetBytes = 0L) }
    }

    private companion object {
        const val TILE_BYTES = 1024
        const val STYLE = "a-style-digest"
    }
}
