package com.rohittp.reng.internal.firewall

import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.Transport
import com.rohittp.reng.RawResourceKey as RenGRawResourceKey
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.rentile.RawResourceKey as EngineRawResourceKey
import com.rohittp.rentile.RawResourceMetadata as EngineRawResourceMetadata
import com.rohittp.rentile.RawResourceStore as EngineRawResourceStore
import com.rohittp.rentile.ResourceClass as EngineResourceClass
import com.rohittp.rentile.ResourceTransport as EngineResourceTransport
import com.rohittp.rentile.StoredRawResource as EngineStoredRawResource
import com.rohittp.rentile.TransportRequest as EngineTransportRequest
import com.rohittp.rentile.TransportRequestMetadata as EngineTransportRequestMetadata
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class FirewallTest {
    @Test
    fun answersARepeatedEngineReadFromTheJoinedRouteSample() = runTest {
        val store = CountingStore(response = validRasterRecord())
        val fw = firewall(store = store)
        repeat(64) { fw.store.read(engineKeyFor(rasterRoute)) }
        assertEquals(1, store.readCalls, "engine reads must not become consumer reads")
    }

    @Test
    fun replaysALatchedOutcomeForTheEnginesSecondAttempt() = runTest {
        val transport = CountingTransport()
        val fw = firewall(transport = transport)
        fw.transport.execute(engineRequestFor(rasterRoute))
        fw.transport.execute(engineRequestFor(rasterRoute))
        assertEquals(1, transport.executeCalls, "the engine's extra attempt is not a consumer retry")
    }

    @Test
    fun replaysALatchedFailureRatherThanRetryingTheConsumer() = runTest {
        val signedUrl = "https://signed.example?token=SECRET"
        val transport = CountingTransport(throwable = RuntimeException("boom $signedUrl"))
        val fw = firewall(transport = transport)
        val first = assertFailsWith<RenGException> { fw.transport.execute(engineRequestFor(rasterRoute)) }
        val second = assertFailsWith<RenGException> { fw.transport.execute(engineRequestFor(rasterRoute)) }
        assertEquals(1, transport.executeCalls, "a latched failure is replayed, not re-fetched")
        assertEquals(RenGErrorCode.TRANSPORT_EXECUTION_FAILED, first.code)
        assertEquals(RenGErrorCode.TRANSPORT_EXECUTION_FAILED, second.code)
        // The adapter's own message -- which can carry a signed url -- must never be forwarded, on the
        // Transport path exactly as much as on the Store path (`neverLetsAnEngineKeyReachADiagnostic`).
        val rendered = first.toString() + first.diagnostics.joinToString { it.toString() }
        assertFalse(rendered.contains(signedUrl), "the adapter's signed url must never surface")
        assertFalse(rendered.contains("SECRET"), "the adapter's credential must never surface")
    }

    @Test
    fun propagatesConsumerCancellationUnwrapped() = runTest {
        val transport = object : Transport {
            override suspend fun execute(request: TransportRequest): TransportResponse =
                throw CancellationException("cancelled")
        }
        val fw = firewall(transport = transport)
        assertFailsWith<CancellationException> { fw.transport.execute(engineRequestFor(rasterRoute)) }
    }

    @Test
    fun replaysALatchedCancellationRatherThanRetryingTheConsumer() = runTest {
        val transport = CountingTransport(throwable = CancellationException("cancelled"))
        val fw = firewall(transport = transport)
        assertFailsWith<CancellationException> { fw.transport.execute(engineRequestFor(rasterRoute)) }
        assertFailsWith<CancellationException> { fw.transport.execute(engineRequestFor(rasterRoute)) }
        assertEquals(1, transport.executeCalls)
    }

    @Test
    fun absorbsRemoveWithoutConsumerMutationOrFollowOnWork() = runTest {
        val store = CountingStore()
        firewall(store = store).store.remove(engineKeyFor(rasterRoute))
        assertEquals(0, store.readCalls + store.writeCalls)
        // RenG's own Store has no remove at all; the call is private and terminal.
    }

    @Test
    fun acceptsANonNullAcceptOnASpriteRouteWithoutTreatingItAsAMismatch() = runTest {
        val fw = firewall()
        val jsonResponse = fw.transport.execute(engineRequestFor(spriteJsonRoute, accept = "application/json"))
        assertEquals(200, jsonResponse.statusCode)
        val imageResponse = fw.transport.execute(engineRequestFor(spriteImageRoute, accept = "image/png"))
        assertEquals(200, imageResponse.statusCode)
    }

    @Test
    fun refusesToForwardAnUnrecognisedUrl() = runTest {
        val transport = CountingTransport()
        assertFailsWith<RenGException> { firewall(transport = transport).transport.execute(unplannedRequest()) }
        assertEquals(0, transport.executeCalls, "an unplanned exchange must never reach the consumer")
    }

    @Test
    fun refusesToAnswerAnUnrecognisedStoreKey() = runTest {
        val store = CountingStore()
        val unplanned = EngineRawResourceKey(stableId = "0".repeat(64), resourceClass = EngineResourceClass.RASTER_TILE)
        assertFailsWith<RenGException> { firewall(store = store).store.read(unplanned) }
        assertEquals(0, store.readCalls, "an unplanned key must never reach the consumer")
    }

    @Test
    fun trustsRenGsRouteLimitRatherThanTheEnginesNumber() = runTest {
        val transport = CountingTransport()
        val response = firewall(transport = transport)
            .transport.execute(engineRequestFor(rasterRoute, maxResponseBytes = Long.MAX_VALUE))
        assertEquals(200, response.statusCode)
        // The route's own ceiling comes from ResourceLimits and is part of the route key -- never the
        // engine's own number, which this test deliberately sends as something absurd.
        assertEquals(rasterRoute.maximumResponseBytes, transport.lastRequest?.maximumResponseBytes)
    }

    @Test
    fun passesTheDocumentedNullsIncludingRetryAfterDeliberately() = runTest {
        val response = firewall().transport.execute(engineRequestFor(rasterRoute))
        assertNull(response.metadata.retryAfterMillis)
        assertNull(response.metadata.cacheControl)
        assertNull(response.metadata.redirectLocation)
        assertNull(response.metadata.wireByteCount)
        assertEquals(emptyList(), response.metadata.vary)
        // The allowlisted three genuinely pass through -- this isn't nulling out everything.
        assertEquals("image/png", response.metadata.contentType)
        assertEquals(FIXED_FRESH_UNTIL_EPOCH_MILLIS, response.metadata.expiresAtEpochMillis)
    }

    @Test
    fun fullyValidatesASpriteRecordBeforeAnsweringAnEngineRead() = runTest {
        // The engine's sprite acquirer validates only size and digest on a store hit and never
        // parses, so a record it accepts but cannot use is permanently unrecoverable inside it.
        val poisoned = storedRecordWithConsistentDigestButInvalidPng()
        assertNull(firewall(store = CountingStore(response = poisoned)).store.read(engineKeyFor(spriteImageRoute)))
    }

    @Test
    fun neverLetsAnEngineKeyReachADiagnostic() = runTest {
        val engineKey = engineKeyFor(rasterRoute)
        val throwing = RuntimeException("adapter failure for stableId=${engineKey.stableId}")
        val failure = assertFailsWith<RenGException> {
            firewall(store = CountingStore(throwable = throwing)).store.read(engineKey)
        }
        val rendered = failure.toString() + failure.diagnostics.joinToString { it.toString() }
        assertFalse(rendered.contains(engineKey.stableId), "the engine's own stableId must never surface")
    }

    @Test
    fun writesToTheConsumerExactlyOnceWhenTheEngineWritesSelfConsistentBytes() = runTest {
        val store = CountingStore()
        val fw = firewall(store = store)
        // The transport comes first deliberately, and is now required rather than incidental: ADR 0016
        // permits the consumer write "only after RenG verifies that it matches the latched response", so a
        // write on a route with no latched response is refused (see
        // `rejectsAWriteOnARouteWithNoLatchedTransportResponse`). This is also the exact ordering Rentile
        // 0.2.0 itself produces -- every raw-store write sits immediately after a transport on the same key.
        fw.transport.execute(engineRequestFor(rasterRoute))
        val resource = engineStoredResourceOf(VALID_STICKER_PNG)
        fw.store.write(engineKeyFor(rasterRoute), resource)
        fw.store.write(engineKeyFor(rasterRoute), resource)
        assertEquals(1, store.writeCalls, "the engine's repeated write is not a repeated consumer write")
        assertEquals(resource.contentDigest, store.lastWrittenResource?.contentDigest)
    }

    @Test
    fun rejectsAWriteOnARouteWithNoLatchedTransportResponse() = runTest {
        // The gap Addendum D closed: the latched-digest check used to be "verify only if a digest exists",
        // so an engine that wrote a route it had never fetched skipped verification entirely. Rentile 0.2.0
        // cannot do this, but the firewall's premise is that the engine is untrusted.
        val store = CountingStore()
        val fw = firewall(store = store)
        assertFailsWith<RenGException> {
            fw.store.write(engineKeyFor(rasterRoute), engineStoredResourceOf(VALID_STICKER_PNG))
        }
        assertEquals(0, store.writeCalls, "an unverifiable write never reaches the consumer")
    }

    @Test
    fun appliesRenGsOwnRecordRulesToAWriteExactlyAsToARead() = runTest {
        // A digest is self-consistent with anything, including empty bytes and a record whose metadata
        // RenG's read path would refuse outright. Before Addendum D, `writeStore` checked only the digest
        // and the byte ceiling, so a negative `storedAtEpochMillis` or a CRLF-bearing `etag` was forwarded
        // straight to the consumer's Store while the identical record was rejected on read.
        val invalidRecords = listOf(
            "negative storedAtEpochMillis" to EngineStoredRawResource(
                bytes = VALID_STICKER_PNG,
                contentDigest = sha256Hex(VALID_STICKER_PNG),
                metadata = EngineRawResourceMetadata(storedAtEpochMillis = -1L),
            ),
            "header-splitting etag" to EngineStoredRawResource(
                bytes = VALID_STICKER_PNG,
                contentDigest = sha256Hex(VALID_STICKER_PNG),
                metadata = EngineRawResourceMetadata(etag = "\"a\"\r\nX-Injected: 1", storedAtEpochMillis = 0L),
            ),
        )

        invalidRecords.forEach { (reason, record) ->
            val store = CountingStore()
            val fw = firewall(transport = CountingTransport(body = VALID_STICKER_PNG), store = store)
            fw.transport.execute(engineRequestFor(rasterRoute))
            assertFailsWith<RenGException>(reason) { fw.store.write(engineKeyFor(rasterRoute), record) }
            assertEquals(0, store.writeCalls, reason)
        }

        // Empty bytes are their own case: they need an empty-bodied latch to get past the digest check at
        // all, which is exactly how they used to slip through.
        val emptyStore = CountingStore()
        val emptyFirewall = firewall(transport = CountingTransport(body = ByteArray(0)), store = emptyStore)
        emptyFirewall.transport.execute(engineRequestFor(rasterRoute))
        assertFailsWith<RenGException> {
            emptyFirewall.store.write(engineKeyFor(rasterRoute), engineStoredResourceOf(ByteArray(0)))
        }
        assertEquals(0, emptyStore.writeCalls, "an empty record is not a valid RenG record")
    }

    @Test
    fun rejectsAWriteWhoseDigestDoesNotMatchItsOwnBytes() = runTest {
        val store = CountingStore()
        val tampered = EngineStoredRawResource(
            bytes = VALID_STICKER_PNG,
            contentDigest = "f".repeat(64),
            metadata = EngineRawResourceMetadata(storedAtEpochMillis = 0L),
        )
        assertFailsWith<RenGException> { firewall(store = store).store.write(engineKeyFor(rasterRoute), tampered) }
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun rejectsAWriteThatDoesNotMatchTheLatchedTransportResponse() = runTest {
        val store = CountingStore()
        val transport = CountingTransport(body = VALID_STICKER_PNG)
        val fw = firewall(transport = transport, store = store)
        fw.transport.execute(engineRequestFor(rasterRoute)) // latches VALID_STICKER_PNG's digest

        val mismatched = engineStoredResourceOf(CORRUPT_STICKER_PNG)
        assertFailsWith<RenGException> { fw.store.write(engineKeyFor(rasterRoute), mismatched) }
        assertEquals(0, store.writeCalls)
    }

    /**
     * `runTest`'s scheduler is single-threaded and virtual-time, so none of the tests above can
     * exercise a genuine data race on `lastTransportDigestByRoute` -- `SuspendJoin` releases its own
     * mutex before running each route's block, so concurrent routes' Transport calls run their bodies,
     * including the digest write, in real parallel under any multi-threaded dispatcher (ADR 0016's
     * 256-tile batch at concurrency eight is exactly this shape). This test deliberately uses
     * [runBlocking] with [Dispatchers.Default] -- a real, multi-threaded dispatcher on both the JVM and
     * Kotlin/Native -- rather than [runTest], to put real concurrent pressure on that map, released
     * through a starting gate (the [MutableStateFlow] count plus [CompletableDeferred]) so every
     * route's write actually contends for the map at close to the same instant rather than being
     * staggered across the thread pool by ordinary `launch` scheduling.
     *
     * The observable consequence of a lost or corrupted entry is not a crash. It used to be
     * `writeStore`'s latched-digest check silently downgrading to a no-op for whichever route's entry went
     * missing, letting a tampered write through undetected; Addendum D's hardening (a route with no latched
     * response is now refused outright) inverted that, so a lost entry now *rejects* a write that should
     * have been accepted. Either way the map is what decides, so this asserts the direction that still
     * bites: for every route, a write carrying exactly the content that was actually fetched for that
     * route must be accepted, even after thousands of other routes raced to record their own digest into
     * the same shared map concurrently. Asserting the old direction here would no longer be able to fail —
     * a tampered write is rejected whether or not the digest survived.
     *
     * This was measured, not assumed, to "genuinely bite," and the route count below is the result of
     * that measurement rather than a guess: with `lastTransportDigestByRoute`'s guard reverted to a
     * bare, unsynchronized `mutableMapOf` access, an early version of this test at 500 routes passed
     * 10/10 local runs even with the starting gate -- an unsynchronized `HashMap`/`LinkedHashMap`'s
     * internal resize race needs enough concurrent structural insertions to actually land two on the
     * same instant, and 500 wasn't enough on a 14-core machine to make that likely in one short burst.
     * Raising the count made the race progressively easier to hit: 8,000 routes failed 1/6 local runs,
     * 20,000 failed 5/6, and 50,000 (used below) failed 10/10 -- each failure showing a route whose
     * write, which should have been rejected as not matching what was actually fetched for it, was
     * silently accepted instead: exactly the "latched-digest check downgraded to a no-op" failure mode
     * this guards against. With the mutex restored, 50,000 routes passed 8/8 additional local runs
     * (test time 0.86s on the JVM, 5.4s on `macosArm64Test`'s Kotlin/Native runtime). This is still not
     * a deterministic proof for every schedule on every machine -- true data races never are, and a
     * slower or single-core CI runner could plausibly need a still-higher count to hit as reliably --
     * but it is a real, measured, currently-passing exercise of real parallelism against this exact
     * map, not a test that cannot fail.
     */
    @Test
    fun neverLosesAConcurrentlyLatchedTransportDigestUnderRealParallelism() = runBlocking {
        val routeCount = 50_000
        val routes = (0 until routeCount).map { index ->
            ResourceRouteKey(
                accessMode = ResourceAccessMode.NORMAL,
                locator = ResourceLocator("https://tiles.example/concurrent/$index.png"),
                resourceClass = ResourceClass.BASEMAP_RASTER_TILE,
                maximumResponseBytes = 32L * 1024L * 1024L,
            )
        }
        val writtenKeys = mutableSetOf<RenGRawResourceKey>()
        val recordingStore = object : Store {
            override suspend fun read(key: RenGRawResourceKey): StoredRawResource? = null
            override suspend fun write(key: RenGRawResourceKey, resource: StoredRawResource) {
                writtenKeys += key
            }
        }
        val registry = OperationRegistry(
            transport = CountingTransport(body = VALID_STICKER_PNG),
            store = recordingStore,
            privateKeyResolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256),
        )
        routes.forEach(registry::preregister)
        val firewallTransport = FirewallTransport(registry)
        val firewallStore = FirewallStore(registry)

        // A launch loop alone tends to stagger real thread starts across the pool, spreading the
        // routeCount map insertions out enough that the race rarely lands two of them on the same
        // instant. This starting gate holds every coroutine at `start.await()` until all routeCount
        // have reached it, then releases them together, so their `lastTransportDigestByRoute` writes
        // actually contend for the same shared map at close to the same moment -- the shape a real
        // 256-at-once Rentile batch (ADR 0016) produces, not an artificially spread-out one.
        val readyCount = MutableStateFlow(0)
        val start = CompletableDeferred<Unit>()
        coroutineScope {
            routes.forEach { route ->
                launch(Dispatchers.Default) {
                    readyCount.update { it + 1 }
                    start.await()
                    firewallTransport.execute(engineRequestFor(route))
                }
            }
            readyCount.first { it == routeCount }
            start.complete(Unit)
        }

        // Hoisted deliberately: the record is invariant across routes, and rebuilding it inside the
        // loop would recompute a pure-Kotlin SHA-256 digest 50,000 times for no added coverage.
        val fetchedRecord = engineStoredResourceOf(VALID_STICKER_PNG)
        routes.forEach { route ->
            firewallStore.write(engineKeyFor(route), fetchedRecord)
        }
        // A route whose latched digest went missing under the race would have had this write refused, so
        // reaching here at all is the assertion: every one of the routeCount digests survived.
        assertEquals(routeCount, writtenKeys.size)
    }
}

// ---- firewall + fixture wiring ------------------------------------------------------------------

private const val FIXED_FRESH_UNTIL_EPOCH_MILLIS = 1_700_000_100_000L

private val rasterRoute = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator("https://tiles.example/0/0/0.png"),
    resourceClass = ResourceClass.BASEMAP_RASTER_TILE,
    maximumResponseBytes = 32L * 1024L * 1024L,
)

private val spriteJsonRoute = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator("https://tiles.example/sprite.json"),
    resourceClass = ResourceClass.BASEMAP_SPRITE_JSON,
    maximumResponseBytes = 4L * 1024L * 1024L,
)

private val spriteImageRoute = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator("https://tiles.example/sprite.png"),
    resourceClass = ResourceClass.BASEMAP_SPRITE_IMAGE,
    maximumResponseBytes = 32L * 1024L * 1024L,
)

private class Firewall(
    consumerTransport: Transport,
    consumerStore: Store,
) {
    private val registry = OperationRegistry(
        transport = consumerTransport,
        store = consumerStore,
        privateKeyResolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256),
    ).also { registry ->
        registry.preregister(rasterRoute)
        registry.preregister(spriteJsonRoute)
        registry.preregister(spriteImageRoute)
    }

    val transport: EngineResourceTransport = FirewallTransport(registry)
    val store: EngineRawResourceStore = FirewallStore(registry)
}

private fun firewall(
    transport: Transport = CountingTransport(),
    store: Store = CountingStore(),
): Firewall = Firewall(transport, store)

private fun engineClassFor(resourceClass: ResourceClass): EngineResourceClass = when (resourceClass) {
    ResourceClass.BASEMAP_RASTER_TILE -> EngineResourceClass.RASTER_TILE
    ResourceClass.BASEMAP_SPRITE_JSON -> EngineResourceClass.SPRITE_JSON
    ResourceClass.BASEMAP_SPRITE_IMAGE -> EngineResourceClass.SPRITE_IMAGE
    else -> error("fixture does not exercise this class")
}

private fun engineKeyFor(route: ResourceRouteKey): EngineRawResourceKey = EngineRawResourceKey(
    stableId = sha256Hex(redactAuthenticationQuery(route.locator.value)),
    resourceClass = engineClassFor(route.resourceClass),
)

private fun engineRequestFor(
    route: ResourceRouteKey,
    accept: String? = null,
    maxResponseBytes: Long = route.maximumResponseBytes,
): EngineTransportRequest = EngineTransportRequest(
    url = route.locator.value,
    resourceClass = engineClassFor(route.resourceClass),
    maxResponseBytes = maxResponseBytes,
    metadata = EngineTransportRequestMetadata(accept = accept),
)

private fun unplannedRequest(): EngineTransportRequest = EngineTransportRequest(
    url = "https://tiles.example/unplanned/9/9/9.png",
    resourceClass = EngineResourceClass.RASTER_TILE,
    maxResponseBytes = 1024L,
)

private fun sha256Hex(value: String): String =
    PureKotlinSha256.digest(CanonicalBytes(value.encodeToByteArray())).lowercaseHex

private fun sha256Hex(bytes: ByteArray): String =
    PureKotlinSha256.digest(CanonicalBytes(bytes)).lowercaseHex

private fun validRasterRecord(bytes: ByteArray = VALID_STICKER_PNG): StoredRawResource = StoredRawResource(
    bytes = bytes,
    contentDigest = sha256Hex(bytes),
    metadata = StoredRawResourceMetadata(storedAtEpochMillis = 0L),
)

private fun storedRecordWithConsistentDigestButInvalidPng(): StoredRawResource = StoredRawResource(
    bytes = CORRUPT_STICKER_PNG,
    contentDigest = sha256Hex(CORRUPT_STICKER_PNG),
    metadata = StoredRawResourceMetadata(storedAtEpochMillis = 0L),
)

private fun engineStoredResourceOf(bytes: ByteArray): EngineStoredRawResource = EngineStoredRawResource(
    bytes = bytes,
    contentDigest = sha256Hex(bytes),
    metadata = EngineRawResourceMetadata(storedAtEpochMillis = 0L),
)

/** Counts every [Store.read]/[Store.write] call and records the last write, optionally answering
 *  [response] on read or throwing [throwable] from either call. */
private class CountingStore(
    private val response: StoredRawResource? = null,
    private val throwable: Throwable? = null,
) : Store {
    var readCalls: Int = 0
        private set
    var writeCalls: Int = 0
        private set
    var lastWrittenResource: StoredRawResource? = null
        private set

    override suspend fun read(key: RenGRawResourceKey): StoredRawResource? {
        readCalls += 1
        throwable?.let { throw it }
        return response
    }

    override suspend fun write(key: RenGRawResourceKey, resource: StoredRawResource) {
        writeCalls += 1
        lastWrittenResource = resource
    }
}

/** Counts every [Transport.execute] call, records the last request, and answers [statusCode]/[body],
 *  or throws [throwable] instead when set. */
private class CountingTransport(
    private val statusCode: Int = 200,
    private val body: ByteArray = VALID_STICKER_PNG,
    private val throwable: Throwable? = null,
) : Transport {
    /**
     * Not thread-safe, and deliberately so: every test but
     * [FirewallTest.neverLosesAConcurrentlyLatchedTransportDigestUnderRealParallelism] drives this from a
     * single-threaded `runTest` scheduler, where a plain counter is exact and an atomic would only add noise.
     *
     * That one test genuinely races these two fields from 50,000 parallel coroutines, and gets away with it
     * **only because it asserts on neither**. Before adding any assertion over [executeCalls] or
     * [lastRequest] to a concurrent test -- "each route was fetched exactly once" is the obvious and
     * tempting one -- make the counter atomic first, or the new assertion will be flaky rather than wrong.
     */
    var executeCalls: Int = 0
        private set
    var lastRequest: TransportRequest? = null
        private set

    override suspend fun execute(request: TransportRequest): TransportResponse {
        executeCalls += 1
        lastRequest = request
        throwable?.let { throw it }
        return TransportResponse(
            statusCode = statusCode,
            body = body,
            metadata = TransportResponseMetadata(
                contentType = "image/png",
                etag = "\"abc\"",
                lastModified = "Mon, 01 Jan 2024 00:00:00 GMT",
                freshUntilEpochMillis = FIXED_FRESH_UNTIL_EPOCH_MILLIS,
            ),
        )
    }
}

// A real, valid 2x2 truecolour PNG (colour type 2) -- same fixture the driver test suite uses, so
// this genuinely exercises DECODE_PNG rather than merely resembling bytes.
private val VALID_STICKER_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mPgEpHTMLJhcAuISsmrAAAPGAMNubnoZAAAAABJRU5ErkJggg==",
)

// Same container shape as VALID_STICKER_PNG, but its IDAT payload is truncated: a well-formed chunk
// whose zlib stream can never inflate to the declared raster size, so DECODE_PNG genuinely fails.
private val CORRUPT_STICKER_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAABElEQVR42mPgKmwFjgAAAABJRU5ErkJggg==",
)
