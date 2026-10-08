package com.rohittp.reng.internal.driver

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.cache.Lease
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.firewall.basemapEngineHost
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.resource.CanonicalIdentityRecord
import com.rohittp.reng.internal.resource.ContentProvenance
import com.rohittp.reng.internal.resource.InstallVisibility
import com.rohittp.reng.internal.resource.ReadStore
import com.rohittp.reng.internal.resource.RentilePrivateKey
import com.rohittp.reng.internal.resource.ResolvedResourceContent
import com.rohittp.reng.internal.resource.ResourceActionId
import com.rohittp.reng.internal.resource.ResourceCommitBinding
import com.rohittp.reng.internal.resource.ResourceOccurrence
import com.rohittp.reng.internal.resource.ResourceOccurrenceId
import com.rohittp.reng.internal.resource.ResourceOperationDefinition
import com.rohittp.reng.internal.resource.ResourceOperationOutcome
import com.rohittp.reng.internal.resource.ResourceOwnerId
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.internal.resource.ResourceRouteRegistration
import com.rohittp.reng.internal.resource.SuppliedInstallOutcome
import com.rohittp.reng.internal.resource.VisibilityInstallCompleted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreparationDriverTest {
    @Test
    fun performsExactlyOneConsumerExchangePerStructuralIdentity() = runTest {
        val transport = CountingTransport(); val store = CountingStore()
        val outcome = driver(transport, store).run(twoOccurrencesOfOneRoute())
        // Call counts alone would stay green even if InstallVisibility (which runs after every count
        // here is already final) silently started reporting Failed for a normal round trip -- a
        // fresh-content route wrongly re-leasing instead of installing, say. Asserting Success as well
        // is what actually pins ValidateResourceClass and InstallVisibility genuinely completing.
        assertIs<ResourceOperationOutcome.Success>(outcome)
        assertEquals(1, transport.executeCalls)
        assertEquals(1, store.readCalls)
        assertEquals(1, store.writeCalls)
    }

    @Test
    fun neverExceedsTheConfiguredConcurrency() = runTest {
        val transport = ConcurrencyRecordingTransport()
        driver(transport, CountingStore(), maximumConcurrentOperations = 4).run(sixteenDistinctRoutes())
        assertTrue(transport.maximumObservedConcurrency <= 4)
    }

    // KNOWN COVERAGE GAP (see task-13-report.md): this does NOT exercise latch replay. Its fixture
    // (routeJoinedByTwoOwners) merges both occurrences into a single route at preRegister, so there is
    // structurally only ever one Transport call for a reason unrelated to replay — deleting the replay
    // branch entirely would leave this test green. Genuine coverage needs the next task's discovery
    // machinery.
    @Test
    fun replaysALatchedOutcomeWithoutASecondExchange() = runTest {
        val transport = CountingTransport()
        driver(transport, CountingStore()).run(routeJoinedByTwoOwners())
        assertEquals(1, transport.executeCalls)
    }

    @Test
    fun sanitizesAnAdapterThrowableIntoATypedFailure() = runTest {
        val transport = ThrowingTransport(IllegalStateException("signed-url-SECRET"))
        val outcome = driver(transport, CountingStore()).run(oneStickerRoute())
        val failure = assertIs<ResourceOperationOutcome.Failure>(outcome)
        assertEquals(RenGErrorCode.TRANSPORT_EXECUTION_FAILED, failure.failure.code)
        assertEquals(PipelineStage.TRANSPORT, failure.failure.stage)
        assertFalse(failure.toString().contains("SECRET"))
    }

    @Test
    fun performsNoRetryRepairOrFallbackOnAnyStatus() = runTest {
        for (status in listOf(301, 404, 429, 500, 503)) {
            val transport = CountingTransport(status = status)
            val outcome = driver(transport, CountingStore()).run(oneStickerRoute())
            assertIs<ResourceOperationOutcome.Failure>(outcome)
            assertEquals(1, transport.executeCalls, "status $status must not be retried")
        }
    }

    @Test
    fun samplesTheClockExactlyOncePerOperation() = runTest {
        val clock = CountingClock()
        driver(CountingTransport(), CountingStore(), clock = clock).run(twoOccurrencesOfOneRoute())
        assertEquals(1, clock.samples)
    }

    // Not part of the brief's given suite, added because it pins the one behaviour the brief calls out
    // as the highest-risk rule in this task: cancelling the caller must cancel a run() in flight rather
    // than complete it with a typed Failure. A naive `catch (e: Exception)` around the adapter call in
    // ResourceActionExecutor would make this test hang (the caught cancellation would never produce the
    // event the driver is waiting on), which is exactly the failure mode a wrap-everything catch creates.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun cancellingTheCallerCancelsAnInFlightRunInsteadOfCompletingItAsAFailure() = runTest {
        val job = launch {
            driver(SuspendingTransport(), CountingStore()).run(oneStickerRoute())
        }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    // A sharper, unit-level companion to the test above. Driving cancellation through a whole `run()`
    // only proves the OUTER job ends up cancelled — job.cancel() guarantees that regardless of what
    // happens inside, so it cannot by itself catch a `suppliedCall` that swallows the adapter's
    // CancellationException into a `SuppliedCallOutcome.Failed`. Calling the executor directly, with no
    // launch/coroutineScope boundary in between, means the adapter's CancellationException propagates
    // (or doesn't) through a plain suspend call chain with nothing else able to mask the difference.
    @Test
    fun theExecutorPropagatesAnAdapterCancellationRatherThanConvertingItToAnEvent() = runTest {
        val executor = ResourceActionExecutor(
            transport = CountingTransport(),
            store = ThrowingStore(CancellationException("boom")),
            cache = ResidentCache(),
            classGateRunner = RenGClassGateRunner(ResourceLimits()),
            resourceLimits = ResourceLimits(),
            basemapEngineHost = basemapEngineHost(),
            clock = FixedClock,
        )
        assertFailsWith<CancellationException> {
            executor.execute(ReadStore(ResourceActionId(1L), 0L, RawResourceKey("raw", ResourceClass.STICKER_IMAGE)))
        }
    }

    // Guards against ValidateResourceClass ever again being (or silently becoming) the placeholder that
    // unconditionally reported Valid: this fixture's Transport response is not a valid PNG, so a real
    // DECODE_PNG gate must run() end to end into a typed Failure carrying RESOURCE_DECODE_FAILED. Under
    // the old placeholder this test would have observed ResourceOperationOutcome.Success instead.
    @Test
    fun aGenuinelyCorruptStickerSurfacesResourceDecodeFailedRatherThanSucceeding() = runTest {
        val transport = CountingTransport(body = corruptStickerPng)
        val outcome = driver(transport, CountingStore()).run(oneStickerRoute())
        val failure = assertIs<ResourceOperationOutcome.Failure>(outcome)
        assertEquals(RenGErrorCode.RESOURCE_DECODE_FAILED, failure.failure.code)
        assertEquals(PipelineStage.RESOURCE_DECODING, failure.failure.stage)
    }

    // Guards against InstallVisibility ever again being (or silently becoming) the placeholder that
    // unconditionally reported Succeeded: RESIDENT-provenance content whose key the consumer freed
    // between the lookup and this install must report a typed Failure, not a bare "succeeded", and must
    // not be resurrected behind the consumer's back.
    @Test
    fun theExecutorReportsAFailedInstallWhenAResidentKeyWasFreedMeanwhile() = runTest {
        val cache = ResidentCache()
        val content = residentContent("freed", digest = "c")
        cache.install(content.resourceKey, content.stored, decoded = null)
        cache.free(ResourceSelector.ByKey(content.resourceKey))

        val outcome = residentInstall(cache, content)

        val failed = assertIs<SuppliedInstallOutcome.Failed>(outcome)
        assertEquals(RenGErrorCode.RESOURCE_UNAVAILABLE, failed.failure.code)
        assertEquals(PipelineStage.RESOURCE_LOOKUP, failed.failure.stage)
        assertNull(cache.current(content.resourceKey), "a freed key must not be restored")
    }

    // A closed renderer's cache is empty exactly as an evicted key is; restoring into it would leak a
    // generation nothing ever releases.
    @Test
    fun theExecutorReportsAFailedInstallWhenTheCacheClosedMeanwhile() = runTest {
        val cache = ResidentCache()
        val content = residentContent("closed", digest = "e")
        cache.install(content.resourceKey, content.stored, decoded = null)
        cache.closeAll()

        val outcome = residentInstall(cache, content)

        val failed = assertIs<SuppliedInstallOutcome.Failed>(outcome)
        assertEquals(RenGErrorCode.RESOURCE_UNAVAILABLE, failed.failure.code)
        assertNull(cache.current(content.resourceKey), "a closed cache must stay empty")
    }

    // The race that failed 1080p exports with 16 stickers (STICKER_IMAGE) or 16 vehicles (MODEL_TEXTURE)
    // on an OPPO CPH2629, 2026-10-08: the lookup saw the key resident without leasing it, the budget
    // sweep evicted it during the class gates, and the install then failed though the route held every
    // byte. An evicted key is restored from those bytes, leased, under a new generation.
    @Test
    fun anEvictedResidentKeyIsRestoredFromTheBytesTheRouteHolds() = runTest {
        val cache = ResidentCache(residentByteBudget = 1L)
        val content = residentContent("evicted", digest = "f")
        cache.install(content.resourceKey, content.stored, decoded = null)
        assertNull(cache.current(content.resourceKey), "precondition: the 1-byte budget evicts the unleased key")

        val outcome = residentInstall(cache, content)

        assertIs<SuppliedInstallOutcome.Succeeded>(outcome)
        val restored = assertNotNull(cache.current(content.resourceKey), "the key is resident again")
        assertEquals(content.stored.contentDigest, restored.stored.contentDigest)
        assertEquals(1, restored.leaseCount, "restored and leased in one step, so the budget cannot take it")
    }

    private fun residentContent(name: String, digest: String): ResolvedResourceContent {
        val registration = registration(name)
        return ResolvedResourceContent(
            route = registration.route,
            resourceKey = registration.resourceKey,
            stored = StoredRawResource(
                bytes = validStickerPng,
                contentDigest = digest.repeat(64),
                metadata = StoredRawResourceMetadata(storedAtEpochMillis = 0L),
            ),
            provenance = ContentProvenance.RESIDENT,
        )
    }

    private suspend fun residentInstall(cache: ResidentCache, content: ResolvedResourceContent): SuppliedInstallOutcome {
        val executor = ResourceActionExecutor(
            transport = CountingTransport(),
            store = CountingStore(),
            cache = cache,
            classGateRunner = RenGClassGateRunner(ResourceLimits()),
            resourceLimits = ResourceLimits(),
            basemapEngineHost = basemapEngineHost(cache = cache),
            clock = FixedClock,
        )
        val event = executor.execute(InstallVisibility(ResourceActionId(1L), 0L, content))
        return assertIs<VisibilityInstallCompleted>(event).outcome
    }

    @Test
    fun aLeaseIsOwnedForRollbackBeforeItsAdmissionObserverRuns() = runTest {
        val cache = ResidentCache()
        val leases = mutableListOf<Lease>()
        var observed = false
        val executor = ResourceActionExecutor(
            transport = CountingTransport(),
            store = CountingStore(),
            cache = cache,
            classGateRunner = RenGClassGateRunner(ResourceLimits()),
            resourceLimits = ResourceLimits(),
            basemapEngineHost = basemapEngineHost(cache = cache),
            clock = FixedClock,
            leaseSink = leases,
            leaseObserver = { lease ->
                assertTrue(leases.single() === lease, "rollback ownership must be published first")
                observed = true
                throw AdmissionRejected
            },
        )
        val registration = registration("admission")
        val content = ResolvedResourceContent(
            route = registration.route,
            resourceKey = registration.resourceKey,
            stored = StoredRawResource(
                bytes = validStickerPng,
                contentDigest = "d".repeat(64),
                metadata = StoredRawResourceMetadata(storedAtEpochMillis = 0L),
            ),
            provenance = ContentProvenance.TRANSPORT_200,
        )

        assertFailsWith<AdmissionRejected> {
            executor.execute(InstallVisibility(ResourceActionId(1L), 0L, content))
        }
        assertTrue(observed)
        cache.releaseLease(leases.single())
    }
}

// ---- driver + fixture wiring -------------------------------------------------------------------

private fun driver(
    transport: Transport,
    store: Store,
    maximumConcurrentOperations: Int = 8,
    clock: () -> Long = FixedClock,
): PreparationDriver {
    val cache = ResidentCache()
    return PreparationDriver(
        transport = transport,
        store = store,
        cache = cache,
        classGateRunner = RenGClassGateRunner(ResourceLimits()),
        resourceLimits = ResourceLimits(),
        // The engine host shares this driver's own resident cache, exactly as RendererFactory wires them.
        basemapEngineHost = basemapEngineHost(cache = cache),
        maximumConcurrentOperations = maximumConcurrentOperations,
        clock = clock,
    )
}

private object FixedClock : () -> Long {
    override fun invoke(): Long = 1_700_000_000_000L
}

private object AdmissionRejected : RuntimeException()

private class CountingClock : () -> Long {
    var samples: Int = 0
        private set

    override fun invoke(): Long {
        samples += 1
        return 1_700_000_000_000L
    }
}

/** Counts every [Store.read] and [Store.write] call. Reads always miss so a route must go on to
 *  Transport; writes always succeed. */
private class CountingStore : Store {
    var readCalls: Int = 0
        private set
    var writeCalls: Int = 0
        private set

    override suspend fun read(key: RawResourceKey): StoredRawResource? {
        readCalls += 1
        return null
    }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        writeCalls += 1
    }
}

/** Counts every [Transport.execute] call and answers every request with the same [status] and [body]
 *  (a valid PNG by default, so a route's class gates genuinely pass), unless [status] is not 200 — then
 *  the body is empty, matching what a real non-2xx response looks like. */
private class CountingTransport(
    private val status: Int = 200,
    private val body: ByteArray = validStickerPng,
) : Transport {
    var executeCalls: Int = 0
        private set

    override suspend fun execute(request: TransportRequest): TransportResponse {
        executeCalls += 1
        return response(status, body)
    }
}

/** Always throws [throwable] from [execute], to prove its message and cause never reach a diagnostic. */
private class ThrowingTransport(private val throwable: Throwable) : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse = throw throwable
}

/** Always throws [throwable] from [read]. */
private class ThrowingStore(private val throwable: Throwable) : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? = throw throwable

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        error("not used by this fixture")
    }
}

/** Never returns on its own; [execute] only ever ends by the calling coroutine's own job being
 *  cancelled, which is exactly the shape needed to prove caller cancellation propagates through a
 *  `run()` in flight rather than completing it. */
private class SuspendingTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse = awaitCancellation()
}

/** Records the maximum number of [execute] calls ever simultaneously in flight. Each call holds its
 *  slot across a [delay] so genuinely concurrent callers under `runTest`'s virtual scheduler overlap. */
private class ConcurrencyRecordingTransport : Transport {
    private val mutex = Mutex()
    private var active = 0
    var maximumObservedConcurrency: Int = 0
        private set

    override suspend fun execute(request: TransportRequest): TransportResponse {
        mutex.withLock {
            active += 1
            if (active > maximumObservedConcurrency) maximumObservedConcurrency = active
        }
        delay(10)
        mutex.withLock { active -= 1 }
        return response(200)
    }
}

private fun response(status: Int, body: ByteArray = validStickerPng): TransportResponse = TransportResponse(
    statusCode = status,
    body = if (status == 200) body else ByteArray(0),
    metadata = TransportResponseMetadata(freshUntilEpochMillis = 1_700_000_100_000L),
)

// A real, valid 2x2 truecolour PNG (colour type 2), generated once via CPython's zlib/struct/
// zlib.crc32 modules exactly as PngDecoderTest.kt documents, so every fixture's own STICKER_IMAGE
// content genuinely passes DECODE_PNG rather than merely resembling bytes.
private val validStickerPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mPgEpHTMLJhcAuISsmrAAAPGAMNubnoZAAAAABJRU5ErkJggg==",
)

// Same container shape as validStickerPng, but its IDAT payload is truncated to 4 bytes: a
// well-formed chunk (correct length and CRC over the truncated payload) whose zlib stream can never
// inflate to the declared raster size, so DECODE_PNG must genuinely fail rather than merely differ
// byte-for-byte from a valid file.
private val corruptStickerPng: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAABElEQVR42mPgKmwFjgAAAABJRU5ErkJggg==",
)

// ---- ResourceOperationDefinition fixtures -------------------------------------------------------

private fun oneStickerRoute(): ResourceOperationDefinition = definitionOf(occurrence(1L, registration("a")))

private fun twoOccurrencesOfOneRoute(): ResourceOperationDefinition {
    val shared = registration("shared")
    return definitionOf(
        occurrence(1L, shared, ownerId = 101L),
        occurrence(2L, shared, ownerId = 102L),
    )
}

private fun routeJoinedByTwoOwners(): ResourceOperationDefinition {
    val shared = registration("owners")
    return definitionOf(
        occurrence(1L, shared, ownerId = 201L),
        occurrence(2L, shared, ownerId = 202L),
    )
}

private fun sixteenDistinctRoutes(): ResourceOperationDefinition {
    val occurrences = (1..16).map { index -> occurrence(index.toLong(), registration("route-$index")) }
    return definitionOf(*occurrences.toTypedArray(), maximumConcurrentRoutes = 16)
}

private fun definitionOf(
    vararg occurrences: ResourceOccurrence,
    maximumConcurrentRoutes: Int = 8,
): ResourceOperationDefinition = ResourceOperationDefinition(
    maximumConcurrentRoutes = maximumConcurrentRoutes,
    staticOccurrences = occurrences.toList(),
    resourceIdentities = occurrences.map {
        CanonicalIdentityRecord(it.registration.resourceKey, it.registration.canonicalBytes)
    },
)

private fun occurrence(
    id: Long,
    registration: ResourceRouteRegistration,
    ownerId: Long = id + 1000L,
): ResourceOccurrence = ResourceOccurrence(
    id = ResourceOccurrenceId(id),
    ownerId = ResourceOwnerId(ownerId),
    registration = registration,
    discoveryRequired = false,
    commitBinding = ResourceCommitBinding.Single,
)

private fun registration(marker: String): ResourceRouteRegistration {
    val stableId = marker.hashCode().toUInt().toString(16).padStart(64, '0').takeLast(64)
    return ResourceRouteRegistration(
        route = ResourceRouteKey(
            accessMode = ResourceAccessMode.NORMAL,
            locator = ResourceLocator("locator-$marker"),
            resourceClass = ResourceClass.STICKER_IMAGE,
            maximumResponseBytes = 4096L,
        ),
        resourceKey = ResourceKey(ResourceKind.EXTERNAL, stableId, ResourceClass.STICKER_IMAGE),
        rawKey = RawResourceKey("raw-$marker", ResourceClass.STICKER_IMAGE),
        privateRentileKey = RentilePrivateKey("private-$marker"),
        canonicalBytes = CanonicalBytes("canonical-$marker".encodeToByteArray()),
    )
}
