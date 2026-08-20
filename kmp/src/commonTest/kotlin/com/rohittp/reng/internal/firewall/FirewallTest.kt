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
        val transport = CountingTransport(throwable = RuntimeException("boom https://signed.example?token=SECRET"))
        val fw = firewall(transport = transport)
        val first = assertFailsWith<RenGException> { fw.transport.execute(engineRequestFor(rasterRoute)) }
        val second = assertFailsWith<RenGException> { fw.transport.execute(engineRequestFor(rasterRoute)) }
        assertEquals(1, transport.executeCalls, "a latched failure is replayed, not re-fetched")
        assertEquals(RenGErrorCode.TRANSPORT_EXECUTION_FAILED, first.code)
        assertEquals(RenGErrorCode.TRANSPORT_EXECUTION_FAILED, second.code)
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
        val resource = engineStoredResourceOf(VALID_STICKER_PNG)
        fw.store.write(engineKeyFor(rasterRoute), resource)
        fw.store.write(engineKeyFor(rasterRoute), resource)
        assertEquals(1, store.writeCalls, "the engine's repeated write is not a repeated consumer write")
        assertEquals(resource.contentDigest, store.lastWrittenResource?.contentDigest)
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
