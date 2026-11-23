package com.rohittp.reng

import com.rohittp.reng.internal.firewall.BasemapTilePixels
import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.RenderContextProbe
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Render-ahead against a real engine (ADR 0077): the read-ahead worker draws a window's tiles into a
 * bounded cache and `prepare` takes them from it.
 *
 * **Native-only** for [RendererBasemapTileTest]'s reason: drawing a tile goes through Skia, which this
 * project's `androidHostTest` runtime lacks.
 *
 * **The fixture can tell two orderings apart.** Every tile the transport serves is a different colour
 * derived from its own url, and the determinism assertions require the frames' tiles to disagree with one
 * another, so a cache that handed tile A's pixels to tile B would fail instead of pass on equal solids.
 */
class RendererReadAheadRasterisationTest {

    /**
     * The claim the whole feature rests on. The same frames are prepared twice from a fresh renderer, once
     * with render-ahead off and once with it on after a hint and a wait for the worker; every tile's pixels
     * and content key must be byte-identical, and the enabled run's frames must have taken tiles from the
     * cache and drawn fewer themselves.
     */
    @Test
    fun framesPreparedFromTheCacheAreByteIdenticalToFramesDrawnFromScratch() = runTest {
        val disabled = scenario(budgetBytes = 0L)
        val enabled = scenario(budgetBytes = ROOMY_BUDGET)

        // The fixture varies what it must: tiles within a frame differ, and the frames name enough of them.
        disabled.frames.forEach { frame ->
            assertTrue(frame.size >= 4, "each frame names at least four tiles: ${disabled.frames.map { it.size }}")
            assertTrue(
                frame.values.map { it.pixels.toList() }.toSet().size > 1,
                "the tiles of a frame must differ, or a swapped tile would pass",
            )
        }

        assertEquals(disabled.frames.size, enabled.frames.size)
        disabled.frames.indices.forEach { index ->
            val expected = disabled.frames[index]
            val actual = enabled.frames[index]
            assertEquals(expected.keys, actual.keys, "frame $index: the same tiles")
            expected.forEach { (tile, fromScratch) ->
                val fromCache = actual.getValue(tile)
                assertContentEquals(fromScratch.pixels, fromCache.pixels, "frame $index, tile $tile: same pixels")
                assertEquals(fromScratch.contentKey, fromCache.contentKey, "frame $index, tile $tile: same content")
                assertEquals(fromScratch.key, fromCache.key, "frame $index, tile $tile: same identity")
            }
        }

        val hits = enabled.aheadHits
        assertTrue(hits > 0L, "the enabled run's frames took tiles from the cache: $hits")
        assertEquals(0L, disabled.aheadHits)
        assertEquals(0L, disabled.workerDrew, "with render-ahead off the worker draws nothing")
        assertTrue(enabled.workerDrew >= hits, "every hit was drawn by the worker first")
        assertTrue(
            enabled.foregroundDrew < disabled.foregroundDrew,
            "the frames drew ${enabled.foregroundDrew} tiles themselves with the cache and " +
                "${disabled.foregroundDrew} without",
        )
        assertEquals(
            disabled.foregroundDrew,
            enabled.foregroundDrew + hits,
            "every tile the cache supplied is a tile the frames did not have to draw, and no other tile is missing",
        )
        assertEquals(0, enabled.cachedAfterwards, "a tile a frame takes leaves the cache")
    }

    @Test
    fun aDisabledBudgetKeepsTodaysBehaviourWithNoWorkerRasterisation() = runTest {
        val renderer = renderer(budgetBytes = 0L)
        try {
            renderer.prepare(basemapPlan(frameIndex = 0L), priority = RenGRenderPriority.NORMAL).close()
            assertEquals(RenGRenderPriority.NORMAL, renderer.lastForwardedRenderPriority, "the caller's lane is forwarded")
            val drawnBefore = renderer.queryMetrics()[RenGMetricName.ENGINE_TILES_RENDERED]

            renderer.readAhead(listOf(shifted(1L), farShifted(2L)))
            real { renderer.awaitReadAheadIdle() }

            val metrics = renderer.queryMetrics()
            assertTrue(metrics[RenGMetricName.READ_AHEAD_FETCHED] > 0L, "the hint still warms bytes")
            assertEquals(drawnBefore, metrics[RenGMetricName.ENGINE_TILES_RENDERED], "the worker drew no tile")
            assertEquals(0L, metrics[RenGMetricName.READ_AHEAD_RASTERISED])
            assertEquals(0L, metrics[RenGMetricName.RENDERED_TILE_CACHE_HITS])
            assertEquals(0L, metrics[RenGMetricName.RENDERED_TILE_CACHE_EVICTIONS])
            assertEquals(0, renderer.renderedTileCacheTiles)
            assertNull(renderer.lastReadAheadRenderPriority, "nothing was forwarded for the worker")

            renderer.prepare(shifted(3L), priority = RenGRenderPriority.URGENT).close()
            assertEquals(RenGRenderPriority.URGENT, renderer.lastForwardedRenderPriority, "an explicit URGENT is kept")
            renderer.prepare(farShifted(4L), priority = RenGRenderPriority.NORMAL).close()
            assertEquals(RenGRenderPriority.NORMAL, renderer.lastForwardedRenderPriority)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun theForegroundRendersUrgentAndTheWorkerNormalWhenRenderAheadIsOn() = runTest {
        val renderer = renderer(budgetBytes = ROOMY_BUDGET)
        try {
            renderer.prepare(basemapPlan(frameIndex = 0L), priority = RenGRenderPriority.NORMAL).close()
            assertEquals(
                RenGRenderPriority.URGENT,
                renderer.lastForwardedRenderPriority,
                "a caller's NORMAL is promoted, so background drawing can never queue ahead of the frame",
            )

            renderer.readAhead(listOf(shifted(1L), farShifted(2L)))
            real { renderer.awaitReadAheadIdle() }

            assertEquals(RenGRenderPriority.NORMAL, renderer.lastReadAheadRenderPriority, "the worker draws at NORMAL")
            assertEquals(
                RenGRenderPriority.URGENT,
                renderer.lastForwardedRenderPriority,
                "and its draws do not overwrite what the foreground forwarded",
            )
            assertTrue(renderer.queryMetrics()[RenGMetricName.READ_AHEAD_RASTERISED] > 0L)

            renderer.prepare(shifted(3L), priority = RenGRenderPriority.URGENT).close()
            assertEquals(RenGRenderPriority.URGENT, renderer.lastForwardedRenderPriority)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun theBudgetBoundsWhatTheWorkerKeepsAndCountsWhatItDropped() = runTest {
        // Room for exactly two tiles (the engine draws 512 x 512 tiles whatever the frame size), against a hint naming six or more.
        val renderer = renderer(budgetBytes = 2L * TILE_BYTES)
        try {
            renderer.prepare(basemapPlan(frameIndex = 0L)).close()
            renderer.readAhead(listOf(shifted(1L), farShifted(2L)))
            real { renderer.awaitReadAheadIdle() }

            val metrics = renderer.queryMetrics()
            assertTrue(renderer.renderedTileCacheTiles <= 2, "never more than the budget holds")
            assertTrue(renderer.renderedTileCacheBytes <= 2L * TILE_BYTES)
            assertEquals(
                2L,
                metrics[RenGMetricName.READ_AHEAD_RASTERISED],
                "the worker draws only as many tiles as the cache can hold, rather than drawing and dropping",
            )
            assertEquals(0L, metrics[RenGMetricName.RENDERED_TILE_CACHE_EVICTIONS])

            // A second window, for other tiles, pushes the first out: least recently used goes first.
            renderer.readAhead(listOf(farShifted(3L)))
            real { renderer.awaitReadAheadIdle() }
            assertTrue(renderer.renderedTileCacheTiles <= 2)
            assertTrue(
                renderer.queryMetrics()[RenGMetricName.RENDERED_TILE_CACHE_EVICTIONS] > 0L,
                "tiles drawn for later frames displace the older ones, and each displacement is counted",
            )
        } finally {
            renderer.close()
        }
    }

    @Test
    fun aStyleChangeRefreshesTheCommittedSnapshotAndAdoptedDigestSet() = runTest {
        val transport = PaintedFreshTransport()
        val renderer = renderer(budgetBytes = ROOMY_BUDGET, transport = transport)
        try {
            renderer.prepare(basemapPlan(frameIndex = 0L)).close()
            renderer.readAhead(listOf(shifted(1L)))
            real { renderer.awaitReadAheadIdle() }
            assertTrue(renderer.renderedTileCacheTiles > 0, "the worker drew tiles for the first style")
            val hitsBefore = renderer.queryMetrics()[RenGMetricName.RENDERED_TILE_CACHE_HITS]

            transport.styleJson = RESTYLED_JSON
            renderer.prepare(shifted(2L)).close()

            assertEquals(
                0,
                renderer.renderedTileCacheTiles,
                "adopting the refreshed digest drops tiles rendered for the old one",
            )
            assertEquals(
                hitsBefore,
                renderer.queryMetrics()[RenGMetricName.RENDERED_TILE_CACHE_HITS],
                "and none of them was served to a frame under the new style",
            )

            renderer.readAhead(listOf(farShifted(3L)))
            real { renderer.awaitReadAheadIdle() }
            assertTrue(renderer.renderedTileCacheTiles > 0, "the worker draws for the refreshed style")
        } finally {
            renderer.close()
        }
    }

    @Test
    fun freeingAllResourcesEmptiesTheCache() = runTest {
        val renderer = renderer(budgetBytes = ROOMY_BUDGET)
        try {
            renderer.prepare(basemapPlan(frameIndex = 0L)).close()
            renderer.readAhead(listOf(shifted(1L)))
            real { renderer.awaitReadAheadIdle() }
            assertTrue(renderer.renderedTileCacheTiles > 0)

            renderer.freeResources(ResourceSelector.All)

            assertEquals(0, renderer.renderedTileCacheTiles)
            assertEquals(0L, renderer.renderedTileCacheBytes)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun aReloadPrepareNeitherReadsNorKeepsTheCache() = runTest {
        val renderer = renderer(budgetBytes = ROOMY_BUDGET)
        try {
            renderer.prepare(basemapPlan(frameIndex = 0L)).close()
            renderer.readAhead(listOf(shifted(1L)))
            real { renderer.awaitReadAheadIdle() }
            assertTrue(renderer.renderedTileCacheTiles > 0)

            renderer.prepare(shifted(1L), accessMode = ResourceAccessMode.RELOAD).close()

            assertEquals(0L, renderer.queryMetrics()[RenGMetricName.RENDERED_TILE_CACHE_HITS], "RELOAD asked for fresh bytes")
            assertEquals(0, renderer.renderedTileCacheTiles, "and what was drawn from the old ones is dropped")
        } finally {
            renderer.close()
        }
    }

    @Test
    fun closingDuringAWindowLeavesNoPixelsAndNoFurtherWork() = runTest {
        val store = SlowRasterStore()
        val renderer = renderer(budgetBytes = ROOMY_BUDGET, store = store)
        renderer.prepare(basemapPlan(frameIndex = 0L)).close()

        // Every Store read now takes long enough that the window is still working when we close it.
        store.delayMillis = 400L
        renderer.readAhead(listOf(shifted(1L), farShifted(2L)))
        real {
            // Wait until the warm has returned, so the window is in (or about to enter) its drawing.
            while (renderer.queryMetrics().let {
                    it[RenGMetricName.READ_AHEAD_FETCHED] + it[RenGMetricName.READ_AHEAD_ALREADY_CACHED] == 0L
                }
            ) {
                delay(5)
            }
        }
        renderer.close()

        real { delay(800) }
        val settled = store.reads()
        real { delay(1200) }
        assertEquals(settled, store.reads(), "no Store read after close has been given time to wind down")
        assertEquals(0, renderer.renderedTileCacheTiles, "the closed cache holds nothing")
        assertEquals(0L, renderer.queryMetrics()[RenGMetricName.READ_AHEAD_RASTERISED], "a closed renderer reports nothing")
    }

    @Test
    fun hintingAndDrawingWhilePreparingAndDrawingIsSafeAndStillCorrect() = runTest {
        val renderer = renderer(budgetBytes = ROOMY_BUDGET)
        try {
            val target = renderer.mintRenderTarget(FramebufferName(0u))
            renderer.prepare(basemapPlan(frameIndex = 0L)).close()
            val hints = listOf(shifted(100L), farShifted(101L), basemapPlan(frameIndex = 102L))
            val hinting = async(Dispatchers.Default) {
                runCatching { repeat(300) { renderer.readAhead(hints) } }.exceptionOrNull()
            }
            val driving = runCatching {
                for (index in 1L..8L) {
                    val frame = renderer.prepare(if (index % 2L == 0L) shifted(index) else farShifted(index))
                    renderer.draw(frame, target)
                    frame.close()
                }
            }.exceptionOrNull()
            val failure = driving ?: real { hinting.await() }
            assertEquals(null, failure, "worker drawing beside a foreground prepare and draw breaks neither")
            real { renderer.awaitReadAheadIdle() }
            val metrics = renderer.queryMetrics()
            assertTrue(metrics[RenGMetricName.READ_AHEAD_RASTERISED] > 0L, "the worker did draw during the run")
        } finally {
            renderer.close()
        }
    }

    @Test
    fun waitingForIdleReturnsAfterAWindowCompletesAndHoldsWhileOneRuns() = runTest {
        val store = SlowRasterStore()
        val renderer = renderer(budgetBytes = ROOMY_BUDGET, store = store)
        try {
            renderer.prepare(basemapPlan(frameIndex = 0L)).close()
            store.delayMillis = 200L
            renderer.readAhead(listOf(shifted(1L)))
            val waiter = async(Dispatchers.Default) { renderer.awaitReadAheadIdle() }
            real { delay(50) }
            assertTrue(!waiter.isCompleted, "a window is running, so the wait holds")

            real { waiter.await() }
            assertTrue(renderer.queryMetrics()[RenGMetricName.READ_AHEAD_RASTERISED] > 0L, "returned only after the drawing")
            real { renderer.awaitReadAheadIdle() }
        } finally {
            renderer.close()
        }
    }

    @Test
    fun waitingForIdleReturnsWhenCloseRunsWhileAWindowIsRunning() = runTest {
        val store = SlowRasterStore()
        val renderer = renderer(budgetBytes = ROOMY_BUDGET, store = store)
        renderer.prepare(basemapPlan(frameIndex = 0L)).close()
        store.delayMillis = 400L
        renderer.readAhead(listOf(shifted(1L), farShifted(2L)))
        val waiter = async(Dispatchers.Default) { renderer.awaitReadAheadIdle() }
        real { delay(100) }
        assertTrue(!waiter.isCompleted, "the window is still running")

        renderer.close()

        real { waiter.await() }
        real { renderer.awaitReadAheadIdle() }
    }

    /**
     * A tile the warm fetched and stored is read back from the Store by the render-ahead step that follows
     * it inside the same registry. Rentile's warm probe reads the Store before fetching, and that probe's
     * miss used to be latched, so the acquire after the write saw a miss, transported the tile again and
     * counted every resource twice.
     */
    @Test
    fun theRenderAheadStepReadsWhatTheWarmStoredInsteadOfFetchingAndCountingItAgain() = runTest {
        val transport = TileUrlCountingTransport(PaintedFreshTransport())
        val renderer = renderer(budgetBytes = ROOMY_BUDGET, transport = transport)
        try {
            renderer.prepare(basemapPlan(frameIndex = 0L)).close()
            val before = renderer.queryMetrics()
            val tilesBefore = transport.tileCalls()

            renderer.readAhead(listOf(shifted(1L), farShifted(2L)))
            real { renderer.awaitReadAheadIdle() }

            val after = renderer.queryMetrics()
            val fetched = transport.tileCalls() - tilesBefore
            assertTrue(fetched > 0, "the hint named tiles the first frame never needed")
            assertTrue(after[RenGMetricName.READ_AHEAD_RASTERISED] > 0L, "the worker drew them")
            assertEquals(
                transport.tileUrls().size,
                transport.tileCalls(),
                "no tile url reached the Transport twice: ${transport.tileUrls().filterValues { it > 1 }}",
            )
            assertEquals(
                fetched.toLong(),
                after[RenGMetricName.ENGINE_RESOURCE_REQUESTS] - before[RenGMetricName.ENGINE_RESOURCE_REQUESTS],
                "the engine counted one request per distinct tile it fetched, not two",
            )
            assertEquals(
                0L,
                after[RenGMetricName.ENGINE_STORE_MISSES] - before[RenGMetricName.ENGINE_STORE_MISSES],
                "the warm's probe is not an acquire miss, and the acquire after the write finds the record",
            )
            assertTrue(
                after[RenGMetricName.ENGINE_STORE_HITS] - before[RenGMetricName.ENGINE_STORE_HITS] > 0L,
                "the render-ahead step read the stored tiles",
            )
        } finally {
            renderer.close()
        }
    }

    /**
     * The same, with a foreground prepare open on the same tiles. The transport routes to the foreground
     * registry first when it knows the url, so a stale miss in the worker's registry used to become a real
     * second download through the consumer.
     */
    @Test
    fun noTileReachesTheTransportTwiceWhileAForegroundPrepareIsOpenOnTheSameTiles() = runTest {
        val transport = TileUrlCountingTransport(PaintedFreshTransport())
        val store = SlowRasterStore()
        val renderer = renderer(budgetBytes = ROOMY_BUDGET, transport = transport, store = store)
        try {
            renderer.prepare(basemapPlan(frameIndex = 0L)).close()
            store.delayMillis = 60L

            val foreground = async(Dispatchers.Default) {
                renderer.prepare(shifted(1L)).close()
                renderer.prepare(farShifted(2L)).close()
            }
            renderer.readAhead(listOf(shifted(1L), farShifted(2L), shifted(3L)))
            real { foreground.await() }
            real { renderer.awaitReadAheadIdle() }

            assertEquals(
                transport.tileUrls().size,
                transport.tileCalls(),
                "no tile url reached the Transport twice: ${transport.tileUrls().filterValues { it > 1 }}",
            )
        } finally {
            renderer.close()
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private class Outcome(
        val frames: List<Map<String, Tile>>,
        val aheadHits: Long,
        val workerDrew: Long,
        val foregroundDrew: Long,
        val cachedAfterwards: Int,
    )

    private class Tile(val pixels: ByteArray, val contentKey: String, val key: ResourceKey)

    /** One fresh renderer: frame 0, a hint for two more, a wait for the worker, then those two frames. */
    private suspend fun scenario(budgetBytes: Long): Outcome {
        val renderer = renderer(budgetBytes)
        try {
            renderer.prepare(basemapPlan(frameIndex = 0L)).close()
            renderer.readAhead(listOf(shifted(1L), farShifted(2L)))
            real { renderer.awaitReadAheadIdle() }

            val afterWindow = renderer.queryMetrics()
            val frames = listOf(shifted(1L), farShifted(2L)).map { plan ->
                val frame = renderer.prepare(plan) as RenGPreparedFrame
                try {
                    frame.basemapTiles.associate { rendered ->
                        val raw = assertIs<BasemapTilePixels.Raw>(rendered.pixels, "raw pixels, the default path")
                        rendered.tile.toString() to Tile(raw.rgba, rendered.contentKey, rendered.key)
                    }
                } finally {
                    frame.close()
                }
            }
            val afterFrames = renderer.queryMetrics()
            return Outcome(
                frames = frames,
                aheadHits = afterFrames[RenGMetricName.RENDERED_TILE_CACHE_HITS],
                workerDrew = afterWindow[RenGMetricName.READ_AHEAD_RASTERISED],
                foregroundDrew = afterFrames[RenGMetricName.ENGINE_TILES_RENDERED] -
                    afterWindow[RenGMetricName.ENGINE_TILES_RENDERED],
                cachedAfterwards = renderer.renderedTileCacheTiles,
            )
        } finally {
            renderer.close()
        }
    }

    private fun renderer(
        budgetBytes: Long,
        transport: Transport = PaintedFreshTransport(),
        store: Store = RasterStore(),
    ): RenGRenderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(TILE_SIDE, TILE_SIDE),
            transport = transport,
            store = store,
            basemapStyle = ResourceLocator(STYLE_URL),
            maximumReadAheadRasterisedTileBytes = budgetBytes,
        ),
        styleGlBinding(),
        RenderContextProbe { RenderContextIdentity(1L) },
    ) as RenGRenderer

    private suspend fun <T> real(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(120_000) { block() } }

    private companion object {
        const val TILE_SIDE = 64
        const val TILE_BYTES = 512 * 512 * 4
        const val ROOMY_BUDGET = 64L * 1024L * 1024L
    }
}

private fun shifted(frameIndex: Long): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = Camera(latitude = -55.0, unwrappedLongitude = -112.5, zoom = 4.0, bearing = 0.0, pitch = 0.0),
    drawBasemap = true,
)

private fun farShifted(frameIndex: Long): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = Camera(latitude = -55.0, unwrappedLongitude = -90.0, zoom = 4.0, bearing = 0.0, pitch = 0.0),
    drawBasemap = true,
)

/** The style with its raster layer made translucent: a different compiled style over the same tile urls. */
private val RESTYLED_JSON: String = STYLE_WITH_SPRITE_JSON.replace(
    """{"id":"r","type":"raster","source":"s"}""",
    """{"id":"r","type":"raster","source":"s","paint":{"raster-opacity":0.5}}""",
)

/**
 * Serves the style and sprite, and every tile as a PNG whose colour is a function of its url, declared fresh
 * for a long time so a stored tile is read from the Store and never revalidated. The style is switchable.
 */
private class PaintedFreshTransport : Transport {
    @kotlin.concurrent.Volatile
    var styleJson: String = STYLE_WITH_SPRITE_JSON

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        return when (url) {
            STYLE_URL -> TransportResponse(
                statusCode = 200,
                body = styleJson.encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            SPRITE_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = "{}".encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            else -> TransportResponse(
                statusCode = 200,
                body = if (url.startsWith("https://tiles.example/")) colouredPng(url) else STYLE_TEST_PNG,
                metadata = TransportResponseMetadata(
                    contentType = "image/png",
                    freshUntilEpochMillis = 4_102_444_800_000L,
                ),
            )
        }
    }
}

/** Counts the tile exchanges that reach the wrapped Transport, per url. */
private class TileUrlCountingTransport(private val inner: Transport) : Transport {
    private val mutex = Mutex()
    private val perUrl = HashMap<String, Int>()

    suspend fun tileUrls(): Map<String, Int> = mutex.withLock { HashMap(perUrl) }

    suspend fun tileCalls(): Int = mutex.withLock { perUrl.values.sum() }

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        if (url.startsWith("https://tiles.example/")) mutex.withLock { perUrl[url] = (perUrl[url] ?: 0) + 1 }
        return inner.execute(request)
    }
}

/** A Store that keeps what it is given, under real parallelism. */
private open class RasterStore : Store {
    private val mutex = Mutex()
    private val entries = HashMap<RawResourceKey, StoredRawResource>()

    override suspend fun read(key: RawResourceKey): StoredRawResource? = mutex.withLock { entries[key] }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        mutex.withLock { entries[key] = resource }
    }
}

/** A [RasterStore] whose reads can be made slow, and which counts them. */
private class SlowRasterStore : RasterStore() {
    @kotlin.concurrent.Volatile
    var delayMillis: Long = 0L
    private val countLock = Mutex()
    private var count = 0

    suspend fun reads(): Int = countLock.withLock { count }

    override suspend fun read(key: RawResourceKey): StoredRawResource? {
        countLock.withLock { count += 1 }
        if (delayMillis > 0L) delay(delayMillis)
        return super.read(key)
    }
}
