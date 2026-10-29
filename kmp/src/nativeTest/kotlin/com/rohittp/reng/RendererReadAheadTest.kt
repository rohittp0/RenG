package com.rohittp.reng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * `Renderer.readAhead` against a real engine (ADR 0075): after a window has been warmed, preparing the
 * frames it named makes no tile request at the consumer's Transport, because their tiles are already in
 * the consumer's Store.
 *
 * **Native-only** for the reason [RendererBasemapTileTest] gives: preparing a frame rasterizes its
 * tiles, and Rentile rasterizes through Skia, which this project's `androidHostTest` runtime lacks.
 */
class RendererReadAheadTest {

    @Test
    fun aBlendHintWarmsTheNamedTargetWithoutCompilingItOnTheWorker() = runTest {
        val transport = TwoStyleReadAheadTransport()
        val renderer = createRenderer(
            configuration = RendererConfiguration(
                outputPixelSize = OutputPixelSize(width = 64, height = 64),
                transport = transport,
                store = PersistentStore(),
                basemapStyle = ResourceLocator(value = STYLE_URL),
                additionalBasemapStyles = listOf(ResourceLocator(value = READ_AHEAD_SECOND_STYLE_URL)),
            ),
            binding = styleGlBinding(),
            probe = com.rohittp.reng.internal.gl.RenderContextProbe {
                com.rohittp.reng.internal.gl.RenderContextIdentity(value = 1L)
            },
        ) as RenGRenderer
        renderer.prepare(basemapPlan(frameIndex = 0L)).close()
        val styleRequestsAfterForeground = transport.secondStyleRequests()
        assertEquals(0, transport.secondStyleTileRequests())

        val shifted = shiftedPlan(frameIndex = 1L)
        renderer.readAhead(
            listOf(
                FramePlan(
                    frameIndex = shifted.frameIndex,
                    camera = shifted.camera,
                    drawBasemap = true,
                    drawLabels = false,
                    basemap = BasemapSelection(
                        style = 0,
                        blend = BasemapBlend(
                            toStyle = 1,
                            mask = BlendMask.Uniform(amount = 0.5f),
                        ),
                    ),
                ),
            ),
        )
        real { renderer.awaitReadAheadIdle() }

        assertTrue(transport.secondStyleTileRequests() > 0, "the hint must warm its toStyle")
        assertEquals(
            styleRequestsAfterForeground,
            transport.secondStyleRequests(),
            "read-ahead warms only styles the foreground already compiled",
        )
        renderer.close()
    }

    @Test
    fun preparingFramesTheWindowWarmedMakesNoTileRequestAtTheTransport() = runTest {
        val transport = FreshTileTransport()
        val store = PersistentStore()
        val renderer = styleRenderer(transport, store = store) as RenGRenderer

        renderer.prepare(basemapPlan(frameIndex = 0L)).close()
        val tileRequestsAfterFirstFrame = transport.tileRequests()
        assertTrue(tileRequestsAfterFirstFrame > 0, "the first frame fetched its own tiles")

        val ahead = listOf(shiftedPlan(frameIndex = 1L), farShiftedPlan(frameIndex = 2L))
        renderer.readAhead(ahead)
        real { renderer.awaitReadAheadIdle() }

        val warmedRequests = transport.tileRequests() - tileRequestsAfterFirstFrame
        assertTrue(warmedRequests > 0, "the hint fetched tiles the first frame never needed")
        assertEquals(0L, renderer.queryMetrics()[RenGMetricName.READ_AHEAD_FAILED])
        assertEquals(
            warmedRequests.toLong(),
            renderer.queryMetrics()[RenGMetricName.READ_AHEAD_FETCHED],
            "every warmed request is counted as a fetch, and each was one exchange",
        )

        // The frames the hint named now read their tiles from the Store.
        val beforePreparing = transport.tileRequests()
        renderer.prepare(ahead[0]).close()
        renderer.prepare(ahead[1]).close()
        assertEquals(
            beforePreparing,
            transport.tileRequests(),
            "preparing the warmed frames makes zero tile requests at the consumer's Transport",
        )
        renderer.close()
    }

    /**
     * Moved here from `RendererReadAheadGuardTest.aHintAfterCloseDoesNothingAndDoesNotThrow`, which
     * closed a renderer that had never prepared: with no read-ahead snapshot a hint returns before any
     * close check, so that test passed whatever close did. This one prepares first, so the renderer
     * has a snapshot, and the hint after close names tiles the first frame never fetched.
     */
    @Test
    fun aHintAfterCloseFetchesNothingAndStartsNoWorker() = runTest {
        val transport = FreshTileTransport()
        val renderer = styleRenderer(transport, store = PersistentStore()) as RenGRenderer
        renderer.prepare(basemapPlan(frameIndex = 0L)).close()
        val beforeClose = transport.tileRequests()
        renderer.close()

        renderer.readAhead(listOf(shiftedPlan(frameIndex = 1L), farShiftedPlan(frameIndex = 2L)))
        real { renderer.awaitReadAheadIdle() }

        assertEquals(beforeClose, transport.tileRequests(), "a hint after close fetches nothing")
        assertEquals(false, renderer.readAheadWorkerStarted, "a hint after close starts no worker")
        assertEquals(0L, renderer.queryMetrics()[RenGMetricName.READ_AHEAD_FETCHED], "a closed renderer reports nothing")
    }

    @Test
    fun aHintAfterACacheOnlyPrepareDoesNothing() = runTest {
        val transport = FreshTileTransport()
        val store = PersistentStore()
        val renderer = styleRenderer(transport, store = store) as RenGRenderer

        renderer.prepare(basemapPlan(frameIndex = 0L)).close()
        renderer.prepare(basemapPlan(frameIndex = 1L), accessMode = ResourceAccessMode.CACHE_ONLY).close()
        val before = transport.tileRequests()

        renderer.readAhead(listOf(shiftedPlan(frameIndex = 2L)))
        real { renderer.awaitReadAheadIdle() }

        assertEquals(before, transport.tileRequests(), "CACHE_ONLY never reaches the network, hint or not")
        assertEquals(0L, renderer.queryMetrics()[RenGMetricName.READ_AHEAD_FETCHED])
        renderer.close()
    }

    @Test
    fun aHintNamingOnlyTilesTheFirstFrameFetchedWarmsNothing() = runTest {
        val transport = FreshTileTransport()
        val store = PersistentStore()
        val renderer = styleRenderer(transport, store = store) as RenGRenderer

        renderer.prepare(basemapPlan(frameIndex = 0L)).close()
        val before = transport.tileRequests()

        // Nothing has been drawn, so no tile is GPU-resident; the frame's tiles are simply already in
        // the Store, and the window finds them there instead of fetching them again.
        renderer.readAhead(listOf(basemapPlan(frameIndex = 1L)))
        real { renderer.awaitReadAheadIdle() }

        assertEquals(before, transport.tileRequests(), "an already-stored tile costs no exchange")
        assertEquals(0L, renderer.queryMetrics()[RenGMetricName.READ_AHEAD_FETCHED])
        assertTrue(renderer.queryMetrics()[RenGMetricName.READ_AHEAD_ALREADY_CACHED] > 0L)
        renderer.close()
    }

    @Test
    fun hintingFromAnotherThreadWhilePreparingAndDrawingIsSafe() = runTest {
        val transport = FreshTileTransport()
        val renderer = styleRenderer(transport, store = PersistentStore()) as RenGRenderer
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        renderer.prepare(basemapPlan(frameIndex = 0L)).close()

        val hints = listOf(shiftedPlan(frameIndex = 100L), farShiftedPlan(frameIndex = 101L), basemapPlan(frameIndex = 102L))
        // Hints come from a worker thread; preparing and drawing stay on the test's own thread, which
        // is the one the render context belongs to.
        val hinting = async(Dispatchers.Default) {
            runCatching { repeat(400) { renderer.readAhead(hints) } }.exceptionOrNull()
        }
        val driving = runCatching {
            for (index in 1L..6L) {
                val frame = renderer.prepare(if (index % 2L == 0L) shiftedPlan(index) else basemapPlan(index))
                renderer.draw(frame, target)
                frame.close()
            }
        }.exceptionOrNull()
        val failure = driving ?: real { hinting.await() }
        assertEquals(null, failure, "a hint concurrent with prepare and draw must neither throw nor break them")
        real { renderer.awaitReadAheadIdle() }
        renderer.close()
    }

    private suspend fun <T> real(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(60_000) { block() } }
}

/** One camera shifted east of [styleCamera], so its four tiles are not the first frame's. */
private fun shiftedPlan(frameIndex: Long): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = Camera(latitude = -55.0, unwrappedLongitude = -112.5, zoom = 4.0, bearing = 0.0, pitch = 0.0),
    drawBasemap = true,
)

/** A third camera, so the window names tiles from two frames and the order is the cursor's. */
private fun farShiftedPlan(frameIndex: Long): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = Camera(latitude = -55.0, unwrappedLongitude = -90.0, zoom = 4.0, bearing = 0.0, pitch = 0.0),
    drawBasemap = true,
)

/**
 * Like [TileTransport], but every tile is declared fresh for a long time: a stored tile is then served
 * without a conditional request, which is what lets "zero tile requests" mean "read from the Store".
 * Counts tile requests only, since the style and sprite may legitimately be asked for again.
 */
private class FreshTileTransport : Transport {
    private val mutex = Mutex()
    private var tiles = 0

    suspend fun tileRequests(): Int = mutex.withLock { tiles }

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        return when (url) {
            STYLE_URL -> TransportResponse(
                statusCode = 200,
                body = STYLE_WITH_SPRITE_JSON.encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            SPRITE_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = "{}".encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            else -> {
                if (url.startsWith("https://tiles.example/")) mutex.withLock { tiles += 1 }
                TransportResponse(
                    statusCode = 200,
                    body = STYLE_TEST_PNG,
                    metadata = TransportResponseMetadata(
                        contentType = "image/png",
                        freshUntilEpochMillis = FAR_FUTURE_EPOCH_MILLIS,
                    ),
                )
            }
        }
    }
}

private const val READ_AHEAD_SECOND_STYLE_URL: String = "https://styles.example/read-ahead-second.json"
private val READ_AHEAD_SECOND_TILE_PREFIX: String = EDITED_STYLE_TILE_TEMPLATE.substringBefore("{z}")

private class TwoStyleReadAheadTransport : Transport {
    private val mutex = Mutex()
    private var secondStyles = 0
    private var secondTiles = 0

    suspend fun secondStyleRequests(): Int = mutex.withLock { secondStyles }
    suspend fun secondStyleTileRequests(): Int = mutex.withLock { secondTiles }

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        return when (url) {
            STYLE_URL -> TransportResponse(
                statusCode = 200,
                body = STYLE_WITH_SPRITE_JSON.encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            READ_AHEAD_SECOND_STYLE_URL -> {
                mutex.withLock { secondStyles += 1 }
                TransportResponse(
                    statusCode = 200,
                    body = EDITED_STYLE_WITH_SPRITE_JSON.encodeToByteArray(),
                    metadata = TransportResponseMetadata(contentType = "application/json"),
                )
            }
            SPRITE_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = "{}".encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            else -> {
                if (url.startsWith(READ_AHEAD_SECOND_TILE_PREFIX)) mutex.withLock { secondTiles += 1 }
                TransportResponse(
                    statusCode = 200,
                    body = STYLE_TEST_PNG,
                    metadata = TransportResponseMetadata(
                        contentType = "image/png",
                        freshUntilEpochMillis = FAR_FUTURE_EPOCH_MILLIS,
                    ),
                )
            }
        }
    }
}

/** A Store that keeps what it is given, under real parallelism. */
private class PersistentStore : Store {
    private val mutex = Mutex()
    private val entries = HashMap<RawResourceKey, StoredRawResource>()

    override suspend fun read(key: RawResourceKey): StoredRawResource? = mutex.withLock { entries[key] }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        mutex.withLock { entries[key] = resource }
    }
}

/** 2100-01-01, far enough that no test can outlive it. */
private const val FAR_FUTURE_EPOCH_MILLIS: Long = 4_102_444_800_000L
