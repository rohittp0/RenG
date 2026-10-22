package com.rohittp.reng.internal.firewall

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.RenGMetricName
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.metrics.EngineMetricRecorder
import com.rohittp.rentile.PreparedStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * ADR 0075's read-ahead operation, at the host: a warm window is a second, short-lived operation, and
 * the engine's callbacks are routed between it and the foreground preparation by what each knows.
 *
 * **The two ordering tests are the point of this file.** A warm and a foreground acquisition of one url
 * share one Rentile flight, so the exchange must happen once whichever of them registered first, and the
 * raw-store write that follows must be judged by the registry that latched the bytes rather than by the
 * one that merely knows the route. Both are made deterministic with a transport that holds its answer
 * until told to release it, so every interleaving is forced rather than raced.
 *
 * Tiles are served as valid PNGs, which Rentile's warm never decodes. The foreground half does decode,
 * and on `androidHostTest` Skia's native library is absent, so a foreground acquisition may end in a
 * decode failure there. What is asserted is therefore never that it succeeded, but that it was not
 * refused by the firewall: no `AMBIGUOUS_RESOURCE_ROUTE`, no `STORE_WRITE_FAILED`, and one exchange.
 */
class ReadAheadRoutingTest {

    @Test
    fun aWarmWindowLandsInTheStoreAndIsCounted() = runTest {
        val transport = CountingHostTransport()
        val store = MemoryHostStore()
        val recorder = EngineMetricRecorder()
        val host = readAheadHost(transport, store, recorder)
        try {
            val style = host.compile()

            val summary = host.warmWindow(style, listOf(HOST_RASTER_TILE), listOf(hostRasterRoute))

            assertEquals(ReadAheadWarmSummary(fetched = 1, alreadyCached = 0, failed = 0), summary)
            assertEquals(1, transport.executeCalls, "one tile is one exchange")
            assertEquals(1, store.writeCount(), "the fetched bytes are written to the consumer's Store")
            assertEquals(1L, recorder.snapshot()[RenGMetricName.READ_AHEAD_FETCHED])
            assertEquals(0L, recorder.snapshot()[RenGMetricName.READ_AHEAD_FAILED])

            // A second window over the same tile finds it stored and fetches nothing.
            val again = host.warmWindow(style, listOf(HOST_RASTER_TILE), listOf(hostRasterRoute))
            assertEquals(ReadAheadWarmSummary(fetched = 0, alreadyCached = 1, failed = 0), again)
            assertEquals(1, transport.executeCalls, "an already-stored tile costs no exchange")
            assertEquals(1L, recorder.snapshot()[RenGMetricName.READ_AHEAD_ALREADY_CACHED])
        } finally {
            host.close()
        }
    }

    @Test
    fun aWarmAndAForegroundAcquireShareOneExchangeWhenTheWarmRegistersFirst() = runTest {
        val transport = GatedHostTransport()
        val store = MemoryHostStore()
        val recorder = EngineMetricRecorder()
        val host = readAheadHost(transport, store, recorder)
        try {
            val style = host.compile()

            // The warm registers first: its flight makes the one exchange, through the read-ahead
            // registry, which is the only one that knows the url so far.
            val warm = async { host.warmWindow(style, listOf(HOST_RASTER_TILE), listOf(hostRasterRoute)) }
            transport.awaitEntered()

            // Now the foreground registers the same url and asks for the same tile. It joins the warm
            // flight: it makes no exchange of its own.
            val foreground = async {
                runCatching {
                    host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                        host.prepareTiles(style, listOf(HOST_RASTER_TILE)).close()
                    }
                }
            }
            awaitUntil("the foreground acquire joins the warm flight") {
                recorder.snapshot()[RenGMetricName.ENGINE_SINGLE_FLIGHT_JOINS] >= 1L
            }
            assertEquals(1, transport.callCount(), "still one exchange with the foreground waiting on it")

            // The bytes arrive. The write must follow the digest back to the read-ahead registry that
            // latched it, although the foreground registry now knows the route too.
            transport.release()
            val summary = awaitResult(warm)
            val outcome = awaitResult(foreground)

            assertEquals(1, summary.fetched, "the warm fetched it")
            assertEquals(0, summary.failed)
            assertEquals(1, transport.callCount(), "one exchange in this order")
            assertEquals(1, store.writeCount(), "and one Store write, accepted by the registry that latched it")
            assertNoFirewallRefusal(outcome.exceptionOrNull())
        } finally {
            host.close()
        }
    }

    @Test
    fun aWarmAndAForegroundAcquireShareOneExchangeWhenTheForegroundRegistersFirst() = runTest {
        val transport = GatedHostTransport()
        val store = MemoryHostStore()
        val recorder = EngineMetricRecorder()
        val host = readAheadHost(transport, store, recorder)
        try {
            val style = host.compile()

            // The foreground registers first and its flight makes the exchange, held at the gate.
            val foreground = async {
                runCatching {
                    host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                        host.prepareTiles(style, listOf(HOST_RASTER_TILE)).close()
                    }
                }
            }
            transport.awaitEntered()

            // The warm arrives while that flight is in the air. Its probe finds nothing stored, but its
            // flight attempt finds one already running and does nothing: it does not join it, so it
            // returns without waiting on the gate, and makes no exchange.
            val summary = host.warmWindow(style, listOf(HOST_RASTER_TILE), listOf(hostRasterRoute))
            assertEquals(
                ReadAheadWarmSummary(fetched = 0, alreadyCached = 1, failed = 0),
                summary,
                "a resource someone else is already fetching is not the warm's to fetch",
            )
            assertEquals(1, transport.callCount(), "the warm added no exchange")

            transport.release()
            val outcome = awaitResult(foreground)

            assertEquals(1, transport.callCount(), "one exchange in this order too")
            assertNoFirewallRefusal(outcome.exceptionOrNull())
            if (outcome.isSuccess) {
                // Rentile validates a raster tile before it writes it, so the write only happens where
                // the tile decodes: on every target with Skia linked in, but not on `androidHostTest`.
                assertEquals(1, store.writeCount(), "the foreground's own write is accepted by its own registry")
            } else {
                assertEquals(0, store.writeCount(), "a tile that does not decode is not written")
            }
        } finally {
            host.close()
        }
    }

    /**
     * The production review's case. A foreground knows the url but has not acquired it yet when the
     * warm's flight makes the exchange, so the exchange is routed to -- and latched by -- the
     * foreground registry. The foreground then closes (a cancelled or failed prepare) before the bytes
     * arrive. The warm's write must still be accepted: it used to fall through to the read-ahead
     * registry, which never latched those bytes, and was refused as an integrity failure.
     */
    @Test
    fun aWarmWriteSurvivesTheForegroundThatServedItsExchangeClosing() = runTest {
        val transport = GatedHostTransport()
        val store = MemoryHostStore()
        val recorder = EngineMetricRecorder()
        val host = readAheadHost(transport, store, recorder)
        try {
            val style = host.compile()

            val foregroundMayClose = CompletableDeferred<Unit>()
            val foreground = async {
                host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                    foregroundMayClose.await()
                }
            }
            // Lets the foreground open its operation and register the route before the warm starts.
            kotlinx.coroutines.yield()
            val warm = async { host.warmWindow(style, listOf(HOST_RASTER_TILE), listOf(hostRasterRoute)) }
            transport.awaitEntered()

            foregroundMayClose.complete(Unit)
            awaitResult(foreground)
            transport.release()
            val summary = awaitResult(warm)

            assertEquals(ReadAheadWarmSummary(fetched = 1, alreadyCached = 0, failed = 0), summary)
            assertEquals(1, transport.callCount(), "one exchange")
            assertEquals(1, store.writeCount(), "the warm's bytes reach the Store")
        } finally {
            host.close()
        }
    }

    @Test
    fun aFailedTileIsCountedNeverThrowsAndTheNextWindowRetriesIt() = runTest {
        val transport = ScriptedHostTransport(failFirst = 1)
        val store = MemoryHostStore()
        val recorder = EngineMetricRecorder()
        val host = readAheadHost(transport, store, recorder)
        try {
            val style = host.compile()

            // A 503. The engine absorbs a per-resource failure, so this returns rather than throws.
            val first = host.warmWindow(style, listOf(HOST_RASTER_TILE), listOf(hostRasterRoute))
            assertEquals(ReadAheadWarmSummary(fetched = 0, alreadyCached = 0, failed = 1), first)
            assertEquals(0, store.writeCount(), "nothing failed is stored")
            assertEquals(1L, recorder.snapshot()[RenGMetricName.READ_AHEAD_FAILED])
            val callsAfterFailure = transport.callCount()
            assertTrue(callsAfterFailure >= 1, "the consumer was asked at least once")

            // The next window is a fresh registry: the failure latched in the last one is gone, so the
            // consumer is asked again, and this time it answers.
            val second = host.warmWindow(style, listOf(HOST_RASTER_TILE), listOf(hostRasterRoute))
            assertEquals(ReadAheadWarmSummary(fetched = 1, alreadyCached = 0, failed = 0), second)
            assertTrue(transport.callCount() > callsAfterFailure, "a fresh window retries the tile")
            assertEquals(1, store.writeCount())
            assertEquals(1L, recorder.snapshot()[RenGMetricName.READ_AHEAD_FETCHED])
            assertEquals(1L, recorder.snapshot()[RenGMetricName.READ_AHEAD_FAILED], "the failure count only grows")
        } finally {
            host.close()
        }
    }

    @Test
    fun aTileWithNoPreregisteredRouteIsRefusedAndCountedNotThrown() = runTest {
        val transport = CountingHostTransport()
        val host = readAheadHost(transport, MemoryHostStore(), EngineMetricRecorder())
        try {
            val style = host.compile()

            val summary = host.warmWindow(style, listOf(HOST_RASTER_TILE), routes = emptyList())

            assertEquals(1, summary.failed, "an exchange RenG never planned fails closed, as it always has")
            assertEquals(0, transport.executeCalls, "and never reaches the consumer")
        } finally {
            host.close()
        }
    }

    @Test
    fun closingDuringAWarmWithAJoinedForegroundAcquireLeavesNoStrayCall() = runTest {
        val transport = GatedHostTransport()
        val store = MemoryHostStore()
        val recorder = EngineMetricRecorder()
        val host = readAheadHost(transport, store, recorder)
        val style = host.compile()

        val warm = async {
            runCatching { host.warmWindow(style, listOf(HOST_RASTER_TILE), listOf(hostRasterRoute)) }
        }
        transport.awaitEntered()
        val foreground = async {
            runCatching {
                host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                    host.prepareTiles(style, listOf(HOST_RASTER_TILE)).close()
                }
            }
        }
        awaitUntil("the foreground acquire joins the warm flight") {
            recorder.snapshot()[RenGMetricName.ENGINE_SINGLE_FLIGHT_JOINS] >= 1L
        }

        // Closed while the flight is in the air with a joined waiter. The consumer's answer then
        // arrives, and the engine may still try to write it: that must find no operation.
        val readsBefore = store.readCount()
        val writesBefore = store.writeCount()
        host.close()
        transport.release()
        awaitResult(warm)
        awaitResult(foreground)
        settle()

        assertEquals(1, transport.callCount(), "no exchange after the one that was already in the air")
        assertEquals(writesBefore, store.writeCount(), "no Store write once the host is closed")
        assertEquals(readsBefore, store.readCount(), "and no Store read either")
        assertEquals(0, writesBefore, "the gate was still closed, so nothing had been written before it")
    }

    @Test
    fun aClosedHostRefusesAWarmWindow() = runTest {
        val host = readAheadHost(CountingHostTransport(), MemoryHostStore(), EngineMetricRecorder())
        val style = host.compile()
        host.close()

        val failure = assertFailsWith<RenGException> {
            host.warmWindow(style, listOf(HOST_RASTER_TILE), listOf(hostRasterRoute))
        }
        assertEquals(RenGErrorCode.BASEMAP_RENDER_FAILED, failure.code)
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private fun readAheadHost(
        transport: Transport,
        store: Store,
        recorder: EngineMetricRecorder,
    ): BasemapEngineHost = BasemapEngineHost(
        transport = transport,
        store = store,
        cache = ResidentCache(),
        tileOutputSizePixels = 512,
        sha256 = PureKotlinSha256,
        metricRecorder = recorder,
    )

    private suspend fun BasemapEngineHost.compile(): PreparedStyle =
        withOperation(ResourceAccessMode.NORMAL) {
            preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
        }

    private fun assertNoFirewallRefusal(failure: Throwable?) {
        if (failure is RenGException) {
            assertNotEquals(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, failure.code, "the firewall must not refuse a planned tile")
            assertNotEquals(RenGErrorCode.STORE_WRITE_FAILED, failure.code, "the firewall must not reject a genuine write")
        }
    }

    /** Polls on real time: the engine works on `Dispatchers.Default`, which a test scheduler cannot skip. */
    private suspend fun awaitUntil(what: String, condition: suspend () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(30_000) {
                while (!condition()) delay(5)
            }
        }
    }

    /** Awaits on real time for the same reason [awaitUntil] polls on it: a virtual timeout would fire at once. */
    private suspend fun <T> awaitResult(deferred: kotlinx.coroutines.Deferred<T>): T =
        withContext(Dispatchers.Default) { withTimeout(30_000) { deferred.await() } }

    /** Lets any callback still on its way reach the host, so an assertion about silence is not early. */
    private suspend fun settle() {
        withContext(Dispatchers.Default) { delay(200) }
    }
}

/** Holds every answer until [release], and says when the first request has arrived. */
private class GatedHostTransport : Transport {
    private val gate = CompletableDeferred<Unit>()
    private val entered = CompletableDeferred<Unit>()
    private val mutex = Mutex()
    private var calls = 0

    suspend fun callCount(): Int = mutex.withLock { calls }

    suspend fun awaitEntered() {
        withContext(Dispatchers.Default) { withTimeout(30_000) { entered.await() } }
    }

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun execute(request: TransportRequest): TransportResponse {
        mutex.withLock { calls += 1 }
        entered.complete(Unit)
        gate.await()
        return TransportResponse(
            statusCode = 200,
            body = VALID_TILE_PNG,
            metadata = TransportResponseMetadata(contentType = "image/png"),
        )
    }
}

/** Answers 503 to its first [failFirst] requests and a valid PNG afterwards. */
private class ScriptedHostTransport(private val failFirst: Int) : Transport {
    private val mutex = Mutex()
    private var calls = 0

    suspend fun callCount(): Int = mutex.withLock { calls }

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val call = mutex.withLock { ++calls }
        return if (call <= failFirst) {
            TransportResponse(statusCode = 503, body = ByteArray(0))
        } else {
            TransportResponse(
                statusCode = 200,
                body = VALID_TILE_PNG,
                metadata = TransportResponseMetadata(contentType = "image/png"),
            )
        }
    }
}

/** A Store that keeps what it is given, and counts reads and writes under real parallelism. */
private class MemoryHostStore : Store {
    private val mutex = Mutex()
    private val entries = HashMap<RawResourceKey, StoredRawResource>()
    private var reads = 0
    private var writes = 0

    suspend fun readCount(): Int = mutex.withLock { reads }

    suspend fun writeCount(): Int = mutex.withLock { writes }

    override suspend fun read(key: RawResourceKey): StoredRawResource? = mutex.withLock {
        reads += 1
        entries[key]
    }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        mutex.withLock {
            writes += 1
            entries[key] = resource
        }
    }
}
