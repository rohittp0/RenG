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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
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
    fun declinesToCacheARecordWhoseMetadataRenGsOwnRulesRefuse() = runTest {
        // A digest is self-consistent with anything, including empty bytes and a record whose metadata
        // RenG's read path would refuse outright. Such a record must not reach the consumer's Store --
        // but it must not fail the engine's acquisition either, because the metadata is the CONSUMER's
        // own, echoed back by the engine, so refusing it proves nothing about provenance. A consumer
        // adapter written as `etag = headers["ETag"].orEmpty()` produces a blank etag these rules reject;
        // throwing on it would kill every style with a required sprite atlas.
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
            fw.store.write(engineKeyFor(rasterRoute), record)
            assertEquals(0, store.writeCalls, reason)
        }

        // The idiomatic-adapter case that motivates the decline rather than a throw.
        val blankStore = CountingStore()
        val blankFirewall = firewall(transport = CountingTransport(body = VALID_STICKER_PNG), store = blankStore)
        blankFirewall.transport.execute(engineRequestFor(rasterRoute))
        blankFirewall.store.write(
            engineKeyFor(rasterRoute),
            EngineStoredRawResource(
                bytes = VALID_STICKER_PNG,
                contentDigest = sha256Hex(VALID_STICKER_PNG),
                metadata = EngineRawResourceMetadata(etag = "", storedAtEpochMillis = 0L),
            ),
        )
        assertEquals(0, blankStore.writeCalls, "a blank etag declines the cache write")

        // Empty bytes stay INTEGRITY, not a content verdict: they are their own case: they need an empty-bodied latch to get past the digest check at
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

    // ---- Gap 1: class-specific validation on the write path -------------------------------------

    @Test
    fun declinesToCacheASpriteImageThatCannotDecode() = runTest {
        // The read gate already refuses such a record; before this, `writeStore` applied only
        // `copyValidStoredResource`'s generic record rules, so the firewall handed the consumer's Store
        // bytes it could already prove RenG will never read back.
        //
        // The write returns normally. A content verdict declines to cache; it does not fail the engine's
        // acquisition, because Rentile's `writeStore` turns a thrown failure into `ResourceStoreException`
        // and `StyleCompiler.resolveRequiredSpriteAtlas` catches nothing -- so failing here would break
        // every style with a pattern fill or a POI icon, including for bytes Rentile decodes perfectly
        // well (a bit-depth-reduced atlas, which RenG's stricter container walk refuses and Skia does not).
        // Zero consumer writes is the assertion that actually encodes ADR 0016's obligation.
        val store = RoutedStore()
        val transport = RoutedTransport(mapOf(ResourceClass.BASEMAP_SPRITE_IMAGE to CORRUPT_STICKER_PNG))
        val fw = firewall(transport = transport, store = store)
        fw.transport.execute(engineRequestFor(spriteImageRoute))
        fw.store.write(engineKeyFor(spriteImageRoute), engineStoredResourceOf(CORRUPT_STICKER_PNG))
        assertEquals(0, store.writeCalls, "a sprite image that cannot decode never reaches the consumer")
    }

    @Test
    fun declinesToCacheSpriteJsonThatCannotParse() = runTest {
        val store = RoutedStore()
        val transport = RoutedTransport(mapOf(ResourceClass.BASEMAP_SPRITE_JSON to UNPARSEABLE_SPRITE_JSON))
        val fw = firewall(transport = transport, store = store)
        fw.transport.execute(engineRequestFor(spriteJsonRoute))
        fw.store.write(engineKeyFor(spriteJsonRoute), engineStoredResourceOf(UNPARSEABLE_SPRITE_JSON))
        assertEquals(0, store.writeCalls, "sprite json that cannot parse never reaches the consumer")
    }

    @Test
    fun writesTheEngineRevalidatedClassesWithoutAClassSpecificWriteGate() = runTest {
        // Deliberate, not an oversight: Rentile's raster, vector, TileJSON and GeoJSON acquirers all
        // re-validate on a store hit and remove-then-refetch when that validation fails, so a bad record
        // in one of those classes self-heals and is never terminal. Only the sprite pair is terminal, so
        // only the sprite pair (and ADR 0016's DEM obligation) earns a write-path format gate. A raster
        // tile whose bytes are not even a PNG is therefore still written.
        val store = RoutedStore()
        val transport = RoutedTransport(mapOf(ResourceClass.BASEMAP_RASTER_TILE to CORRUPT_STICKER_PNG))
        val fw = firewall(transport = transport, store = store)
        fw.transport.execute(engineRequestFor(rasterRoute))
        fw.store.write(engineKeyFor(rasterRoute), engineStoredResourceOf(CORRUPT_STICKER_PNG))
        assertEquals(1, store.writeCalls, "an engine-revalidated class keeps its generic-only write gate")
    }

    // ---- Gap 2: the sprite-pair rendezvous ------------------------------------------------------

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun parksASpriteMemberWriteUntilItsSiblingArrivesAndThenWritesBoth() = runTest {
        val store = RoutedStore()
        val fw = firewall(transport = spritePairTransport(VALID_SPRITE_JSON), store = store)
        fw.transport.execute(engineRequestFor(spriteJsonRoute))
        fw.transport.execute(engineRequestFor(spriteImageRoute))

        val jsonWrite = launch {
            fw.store.write(engineKeyFor(spriteJsonRoute), engineStoredResourceOf(VALID_SPRITE_JSON))
        }
        runCurrent()
        assertEquals(0, store.writeCalls, "a sprite member must not reach the consumer before its sibling")

        val imageWrite = launch {
            fw.store.write(engineKeyFor(spriteImageRoute), engineStoredResourceOf(SPRITE_ATLAS_PNG))
        }
        jsonWrite.join()
        imageWrite.join()
        assertEquals(2, store.writeCalls, "a jointly valid pair writes both members")
        assertEquals(
            setOf(ResourceClass.BASEMAP_SPRITE_JSON, ResourceClass.BASEMAP_SPRITE_IMAGE),
            store.writtenClasses.toSet(),
        )
    }

    @Test
    fun declinesToCacheBothSpriteMembersWhenAnAtlasEntryLiesOutsideTheImage() = runTest {
        // Neither member is individually malformed -- the json parses, the png decodes -- so no
        // per-member gate can see this. Rentile writes both members and only then compiles, so without
        // the joint gate this pair is persisted and fails identically on every later prepare().
        val store = RoutedStore()
        val fw = firewall(transport = spritePairTransport(OUT_OF_BOUNDS_SPRITE_JSON), store = store)
        fw.transport.execute(engineRequestFor(spriteJsonRoute))
        fw.transport.execute(engineRequestFor(spriteImageRoute))

        // Both writes return normally -- one joint verdict, replayed, declining both members' caches.
        val jsonWrite = launch {
            fw.store.write(engineKeyFor(spriteJsonRoute), engineStoredResourceOf(OUT_OF_BOUNDS_SPRITE_JSON))
        }
        val imageWrite = launch {
            fw.store.write(engineKeyFor(spriteImageRoute), engineStoredResourceOf(SPRITE_ATLAS_PNG))
        }
        jsonWrite.join()
        imageWrite.join()
        assertEquals(0, store.writeCalls, "neither member is written when the pair cannot compile")
    }

    @Test
    fun validatesTheSpritePairAgainstASiblingServedFromTheStore() = runTest {
        // The mixed case: the firewall performed the store read itself, so it holds the hit member's
        // bytes and the fetched member's write never waits for a write that is never coming.
        val store = RoutedStore(reads = mapOf(ResourceClass.BASEMAP_SPRITE_JSON to storedRecordOf(VALID_SPRITE_JSON)))
        val fw = firewall(transport = spritePairTransport(VALID_SPRITE_JSON), store = store)
        assertNotNull(fw.store.read(engineKeyFor(spriteJsonRoute)))
        fw.transport.execute(engineRequestFor(spriteImageRoute))
        fw.store.write(engineKeyFor(spriteImageRoute), engineStoredResourceOf(SPRITE_ATLAS_PNG))
        assertEquals(1, store.writeCalls, "the fetched member writes once its store-hit sibling is known")
    }

    @Test
    fun declinesToCacheASpriteMemberWhoseStoreHitSiblingCannotCompleteThePair() = runTest {
        val store = RoutedStore(
            reads = mapOf(ResourceClass.BASEMAP_SPRITE_JSON to storedRecordOf(OUT_OF_BOUNDS_SPRITE_JSON)),
        )
        val fw = firewall(transport = spritePairTransport(VALID_SPRITE_JSON), store = store)
        assertNotNull(fw.store.read(engineKeyFor(spriteJsonRoute)))
        fw.transport.execute(engineRequestFor(spriteImageRoute))
        fw.store.write(engineKeyFor(spriteImageRoute), engineStoredResourceOf(SPRITE_ATLAS_PNG))
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun declinesRatherThanHangsWhenTheSiblingsAdapterThrew() = runTest {
        // Rentile launches both members inside one coroutineScope, so a failed member cancels the parked
        // sibling. The firewall does not rely on that alone: a sanitized transport failure on a sprite
        // route latches that member as one that will contribute no content, so the sibling resolves on
        // its own -- and, being a content verdict rather than an integrity refusal, it declines quietly.
        //
        // This is the consumer-adapter-throws shape. The far more common real one -- a 403 or 404, which
        // returns *successfully* through RenG's transport -- is
        // `leavesASpriteMemberParkedWhenItsSiblingFetchReturnedANonSuccessStatus` below, and it resolves
        // by a different mechanism.
        val store = RoutedStore()
        val transport = RoutedTransport(
            bodies = mapOf(ResourceClass.BASEMAP_SPRITE_IMAGE to SPRITE_ATLAS_PNG),
            failures = mapOf(ResourceClass.BASEMAP_SPRITE_JSON to RuntimeException("boom")),
        )
        val fw = firewall(transport = transport, store = store)
        assertFailsWith<RenGException> { fw.transport.execute(engineRequestFor(spriteJsonRoute)) }
        fw.transport.execute(engineRequestFor(spriteImageRoute))
        fw.store.write(engineKeyFor(spriteImageRoute), engineStoredResourceOf(SPRITE_ATLAS_PNG))
        assertEquals(0, store.writeCalls)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun leavesASpriteMemberParkedWhenItsSiblingFetchReturnedANonSuccessStatus() = runTest {
        // The single most common real sprite failure, and its shape is genuinely different from an
        // adapter that throws. A 403 or 404 is a *successful* consumer exchange: RenG returns the
        // response and latches its body digest, so the member is neither contributed nor latched
        // contentless. Rentile is what rejects it -- `acquireRaw` throws `ResourceAcquisitionException`
        // on `statusCode !in 200..299`, before ever calling `writeStore` -- so the firewall never learns
        // that this member is finished, and the parked sibling is released by exactly one mechanism: the
        // `coroutineScope` cancellation that failure triggers. This test pins that the sibling really is
        // parked (not written, not silently declined) and that cancellation is what frees it.
        val store = RoutedStore()
        val transport = RoutedTransport(
            bodies = mapOf(
                ResourceClass.BASEMAP_SPRITE_JSON to FORBIDDEN_BODY,
                ResourceClass.BASEMAP_SPRITE_IMAGE to SPRITE_ATLAS_PNG,
            ),
            statusCodes = mapOf(ResourceClass.BASEMAP_SPRITE_JSON to 403),
        )
        val fw = firewall(transport = transport, store = store)
        val forbidden = fw.transport.execute(engineRequestFor(spriteJsonRoute))
        assertEquals(403, forbidden.statusCode, "a non-success status is a successful consumer exchange")
        fw.transport.execute(engineRequestFor(spriteImageRoute))

        var parkedFailure: Throwable? = null
        val imageWrite = launch {
            try {
                fw.store.write(engineKeyFor(spriteImageRoute), engineStoredResourceOf(SPRITE_ATLAS_PNG))
            } catch (failure: Throwable) {
                parkedFailure = failure
                throw failure
            }
        }
        runCurrent()
        assertEquals(0, store.writeCalls, "the surviving member is parked, not cached")
        assertNull(parkedFailure, "and it is parked rather than resolved, because nothing latched a verdict")

        // Exactly what Rentile's own coroutineScope does when the 403 member's async fails.
        imageWrite.cancel()
        imageWrite.join()
        assertTrue(parkedFailure is CancellationException, "the engine scope's cancellation is the release")
        assertEquals(0, store.writeCalls)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun propagatesCancellationUnwrappedWhileParkedAtTheSpriteRendezvous() = runTest {
        val store = RoutedStore()
        val fw = firewall(transport = spritePairTransport(VALID_SPRITE_JSON), store = store)
        fw.transport.execute(engineRequestFor(spriteJsonRoute))

        var parkedFailure: Throwable? = null
        val jsonWrite = launch {
            try {
                fw.store.write(engineKeyFor(spriteJsonRoute), engineStoredResourceOf(VALID_SPRITE_JSON))
            } catch (failure: Throwable) {
                parkedFailure = failure
                throw failure
            }
        }
        runCurrent()
        assertEquals(0, store.writeCalls, "the member is genuinely parked, not already written")
        jsonWrite.cancel()
        jsonWrite.join()
        // Asserted on type, never identity: Kotlin's stack recovery may hand back a copy carrying the
        // original as its immediate cause.
        assertTrue(parkedFailure is CancellationException, "a parked rendezvous propagates cancellation")
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun declinesToCacheASpriteMemberWhoseSiblingRouteWasNeverPreregistered() = runTest {
        // No sibling route means no pair can ever be assembled, so the member declines immediately
        // rather than parking on an arrival that this invocation can never produce.
        val store = RoutedStore()
        val registry = OperationRegistry(
            transport = spritePairTransport(VALID_SPRITE_JSON),
            store = store,
            privateKeyResolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256),
        ).also { it.preregister(spriteImageRoute) }
        FirewallTransport(registry).execute(engineRequestFor(spriteImageRoute))
        FirewallStore(registry).write(engineKeyFor(spriteImageRoute), engineStoredResourceOf(SPRITE_ATLAS_PNG))
        assertEquals(0, store.writeCalls)
    }

    // ---- Gap 3: DEM terrain-encoding validation on the write path -------------------------------

    @Test
    fun declinesToCacheADemTileThatIsNotAnEightBitRgbTerrainEncoding() = runTest {
        // ADR 0016: "A fetched DEM write additionally requires RenG's terrain encoding validation."
        // Rentile's DEM path reaches its raw-store write after generic bounded image validation only.
        // The write still *requires* the check -- a negative verdict means no write -- which is the whole
        // of what the ADR sentence constrains.
        val store = RoutedStore()
        val transport = RoutedTransport(mapOf(ResourceClass.BASEMAP_DEM_TILE to TRANSLUCENT_PNG))
        val fw = firewall(transport = transport, store = store)
        fw.transport.execute(engineRequestFor(demRoute))
        fw.store.write(engineKeyFor(demRoute), engineStoredResourceOf(TRANSLUCENT_PNG))
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun writesADemTileWhoseTerrainEncodingIsEightBitRgb() = runTest {
        val store = RoutedStore()
        val transport = RoutedTransport(mapOf(ResourceClass.BASEMAP_DEM_TILE to OPAQUE_RGBA_PNG))
        val fw = firewall(transport = transport, store = store)
        fw.transport.execute(engineRequestFor(demRoute))
        fw.store.write(engineKeyFor(demRoute), engineStoredResourceOf(OPAQUE_RGBA_PNG))
        assertEquals(1, store.writeCalls, "a fully opaque eight-bit encoding satisfies either DEM scheme")
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

private val demRoute = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator("https://tiles.example/dem/0/0/0.png"),
    resourceClass = ResourceClass.BASEMAP_DEM_TILE,
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
        registry.preregister(demRoute)
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
    ResourceClass.BASEMAP_DEM_TILE -> EngineResourceClass.DEM_TILE
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

/** Answers each [ResourceClass] its own body, or throws that class's [failures] entry instead. The
 *  sprite pair needs two genuinely different bodies on one firewall, which [CountingTransport]'s single
 *  fixed body cannot express. Not thread-safe, for the same reason [CountingTransport] is not. */
private class RoutedTransport(
    private val bodies: Map<ResourceClass, ByteArray> = emptyMap(),
    private val failures: Map<ResourceClass, Throwable> = emptyMap(),
    private val statusCodes: Map<ResourceClass, Int> = emptyMap(),
) : Transport {
    var executeCalls: Int = 0
        private set

    override suspend fun execute(request: TransportRequest): TransportResponse {
        executeCalls += 1
        failures[request.resourceClass]?.let { throw it }
        return TransportResponse(
            statusCode = statusCodes[request.resourceClass] ?: 200,
            body = bodies.getValue(request.resourceClass),
            metadata = TransportResponseMetadata(
                contentType = "application/octet-stream",
                freshUntilEpochMillis = FIXED_FRESH_UNTIL_EPOCH_MILLIS,
            ),
        )
    }
}

/** Answers a read per [ResourceClass] (absent means a miss) and records every write's class. */
private class RoutedStore(
    private val reads: Map<ResourceClass, StoredRawResource> = emptyMap(),
) : Store {
    var readCalls: Int = 0
        private set
    var writeCalls: Int = 0
        private set
    val writtenClasses: MutableList<ResourceClass> = mutableListOf()

    override suspend fun read(key: RenGRawResourceKey): StoredRawResource? {
        readCalls += 1
        return reads[key.resourceClass]
    }

    override suspend fun write(key: RenGRawResourceKey, resource: StoredRawResource) {
        writeCalls += 1
        writtenClasses += key.resourceClass
    }
}

/** The two sprite members' real bodies, plus [jsonBody] for whichever atlas manifest a test needs. */
private fun spritePairTransport(jsonBody: ByteArray): RoutedTransport = RoutedTransport(
    mapOf(
        ResourceClass.BASEMAP_SPRITE_JSON to jsonBody,
        ResourceClass.BASEMAP_SPRITE_IMAGE to SPRITE_ATLAS_PNG,
    ),
)

private fun storedRecordOf(bytes: ByteArray): StoredRawResource = StoredRawResource(
    bytes = bytes,
    contentDigest = sha256Hex(bytes),
    metadata = StoredRawResourceMetadata(storedAtEpochMillis = 0L),
)

// The sprite atlas image every pair fixture below is measured against: the same real 2x2 truecolour PNG
// the sticker fixture uses, so an entry rect of exactly 2x2 fits and 3x2 does not.
private val SPRITE_ATLAS_PNG: ByteArray = VALID_STICKER_PNG

private val VALID_SPRITE_JSON: ByteArray =
    """{"icon":{"x":0,"y":0,"width":2,"height":2}}""".encodeToByteArray()

// Parses, and every member gate accepts it; only the atlas image's own dimensions reveal that the
// entry's rect runs one pixel past the right edge.
private val OUT_OF_BOUNDS_SPRITE_JSON: ByteArray =
    """{"icon":{"x":1,"y":0,"width":2,"height":2}}""".encodeToByteArray()

private val UNPARSEABLE_SPRITE_JSON: ByteArray = "{".encodeToByteArray()

// A real 2x2 colour-type-6 PNG whose last pixel carries alpha 0x80: it decodes, so generic image
// validation admits it, but its alpha channel carries data, so it is not an eight-bit RGB terrain
// encoding under either Mapbox Terrain-RGB or Terrarium.
private val TRANSLUCENT_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAGklEQVR4nGPgEpH7r2Fk85/BLSDqf0peRQMAMlsGiqkec00AAAAASUVORK5CYII=",
)

// The same 2x2 colour-type-6 shape with every alpha byte 0xFF: a four-channel file whose alpha carries
// nothing, which either terrain scheme reads as plain eight-bit RGB triples.
private val OPAQUE_RGBA_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAGklEQVR4nGPgEpH7r2Fk85/BLSDqf0pexX8AMtoHCdC1xmkAAAAASUVORK5CYII=",
)

// A 403's body: an ordinary error page, not sprite content. It travels through RenG's transport as a
// perfectly successful exchange and latches its own digest; Rentile is what rejects the status.
private val FORBIDDEN_BODY: ByteArray = "forbidden".encodeToByteArray()
