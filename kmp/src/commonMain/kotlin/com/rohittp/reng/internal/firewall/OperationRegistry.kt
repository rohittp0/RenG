package com.rohittp.reng.internal.firewall

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportRequestMetadata
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.RawResourceKey as RenGRawResourceKey
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failure.toException
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.identity.Sha256Function
import com.rohittp.reng.internal.image.PngDecodeResult
import com.rohittp.reng.internal.image.PngScan
import com.rohittp.reng.internal.image.decodePng
import com.rohittp.reng.internal.image.scanPng
import com.rohittp.reng.internal.json.JsonParse
import com.rohittp.reng.internal.json.JsonValue
import com.rohittp.reng.internal.json.parseJson
import com.rohittp.reng.internal.resource.RentilePrivateKey
import com.rohittp.reng.internal.resource.RentilePrivateKeyResolver
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.internal.resource.TransportLatchKey
import com.rohittp.reng.internal.resource.copyValidStoredResource
import com.rohittp.rentile.RawResourceKey as EngineRawResourceKey
import com.rohittp.rentile.RawResourceMetadata as EngineRawResourceMetadata
import com.rohittp.rentile.ResourceClass as EngineResourceClass
import com.rohittp.rentile.StoredRawResource as EngineStoredRawResource
import com.rohittp.rentile.TransportRequest as EngineTransportRequest
import com.rohittp.rentile.TransportResponse as EngineTransportResponse
import com.rohittp.rentile.TransportResponseMetadata as EngineTransportResponseMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ADR 0016's operation-scoped firewall state: the routes one active preparation invocation
 * preregistered, and the joined answers those routes have produced so far for Rentile's own
 * `ResourceTransport`/`RawResourceStore` callbacks. [FirewallTransport] and [FirewallStore] are thin
 * adapters over this class; this is where the actual join/latch/validate work happens.
 *
 * One instance lives exactly as long as one preparation invocation: "discarded when the invocation
 * terminates" (ADR 0016), never a renderer-lifetime cache and never shared across access modes.
 * [BasemapEngineHost.withOperation] is what enforces that lifetime against the renderer's one long-lived
 * Rentile engine, and it is also what makes this class's never-evicting [SuspendJoin] safe: a latched
 * cancellation or failure replays for the registry's whole lifetime, so bounding that lifetime to one
 * invocation is what stops a later, healthy preparation inheriting an outcome it never earned.
 *
 * [preregister] is the setup step a caller (a later task's driver) uses to declare, before Rentile is
 * ever invoked, every `(accessMode, locator, resourceClass, maximumResponseBytes)` route this
 * invocation may need. [executeTransport], [readStore], [writeStore], and [removeStore] are the
 * answer paths Rentile's own adapters call into.
 */
internal class OperationRegistry(
    private val transport: Transport,
    private val store: Store,
    private val privateKeyResolver: RentilePrivateKeyResolver,
    private val sha256: Sha256Function = PureKotlinSha256,
) {
    private val resourceKeyDeriver = ResourceKeyDeriver(sha256)

    // Every preregistered route, indexed three ways: by the private Rentile key (collision
    // detection only -- ADR 0016's "detects static private Rentile key collisions"), by the exact
    // (url, engine class) pair Transport requests carry, and by the (stableId, engine class) pair
    // Store keys carry. A route absent from the relevant index fails closed rather than being
    // forwarded -- this is the whole point of the firewall.
    private val routeByPrivateKey = mutableMapOf<RentilePrivateKey, ResourceRouteKey>()
    private val transportRoutes = mutableMapOf<TransportIndexKey, ResourceRouteKey>()
    private val storeRoutes = mutableMapOf<StoreIndexKey, ResourceRouteKey>()

    // The same `sha256Hex(withRedactedAuthenticationQuery(url))` digest Rentile embeds in every
    // `ResourceAcquisitionException.sanitizedResourceId` and `ResourceDecodeException.sanitizedResourceId`,
    // read in reverse: engine digest -> the RenG route it names. [BasemapEngineHost] needs this because
    // that digest is a FOREIGN namespace -- a consumer handed one as a `ResourceSelector.ByKey` gets a
    // silent empty selection rather than an error -- and this registry is the only place that already
    // holds both the locator and the digest derived from it. Indexed for every class Rentile can name in
    // a failure, which is one wider than [storeRoutes]: [ResourceClass.BASEMAP_STYLE] has no Rentile
    // raw-store entry but `acquireRemoteStyle` still reports `sha256Hex(redacted url)` on a style
    // transport failure.
    private val routesByEngineDigest = mutableMapOf<EngineDigestKey, ResourceRouteKey>()

    // The digest of the most recent successfully latched Transport response for a route, so a
    // subsequent Store write can be checked against what the engine actually fetched (ADR 0016: "a
    // Rentile write callback may perform the consumer write only after RenG verifies that it matches
    // the latched response"). Written from inside a SuspendJoin.run block, which releases its own
    // Mutex before the block runs (concurrent routes' Transport calls genuinely run in parallel --
    // ADR 0016's 256-tile batch at concurrency eight) and read from writeStore, which races those same
    // writes for other routes -- so this map needs its own guard, distinct from transportJoin's.
    private val lastTransportDigestMutex = Mutex()
    private val lastTransportDigestByRoute = mutableMapOf<ResourceRouteKey, String>()

    private suspend fun recordLatchedTransportDigest(route: ResourceRouteKey, digest: String) {
        lastTransportDigestMutex.withLock { lastTransportDigestByRoute[route] = digest }
    }

    private suspend fun latchedTransportDigestFor(route: ResourceRouteKey): String? =
        lastTransportDigestMutex.withLock { lastTransportDigestByRoute[route] }

    private val transportJoin = SuspendJoin<TransportLatchKey, EngineTransportResponse>()
    private val storeReadJoin = SuspendJoin<ResourceRouteKey, EngineStoredRawResource?>()
    private val storeWriteJoin = SuspendJoin<ResourceRouteKey, Unit>()

    // The sprite pair's two members, indexed by the base url Rentile's own `appendSpriteExtension`
    // derived both of them from, so [approveSpriteMemberWrite] can name a member's sibling without
    // guessing. Written only from [preregister] -- which [BasemapEngineHost.withOperation] runs to
    // completion before the engine can call any answer path -- exactly like the three route indices
    // above, which is why it needs no guard while [spriteRendezvous] does.
    private val spriteMemberRoutes = mutableMapOf<SpriteMemberKey, ResourceRouteKey>()

    // ADR 0016: "RenG jointly prevalidates the complete sprite JSON-and-PNG pair before writing either
    // fetched member." Per-member validation cannot see the one check that actually matters here --
    // whether each atlas entry's rect lies inside the atlas image -- and Rentile writes each member
    // *before* compiling the pair, so without this a pair RenG can already prove will never compile is
    // persisted into a consumer Store that has no remove operation, and fails identically on every later
    // prepare(). [spriteRendezvous] carries each member's arrival; [spritePairJoin] runs the joint
    // verdict exactly once per pair and replays it to whichever member did not run it.
    private val spriteRendezvous = SpriteRendezvous()
    private val spritePairJoin = SuspendJoin<SpriteGroupKey, Boolean>()

    /**
     * Declares one static prelookup route this invocation may need. Idempotent for an identical
     * repeat registration (two occurrences joining the same route); throws if a second, genuinely
     * different route would collide with an already-registered one on any of the three indices --
     * every such collision is exactly the "two different resources answered by one engine call"
     * failure mode ADR 0016 exists to rule out, so it is caught here rather than at answer time.
     */
    fun preregister(route: ResourceRouteKey) {
        val privateKey = privateKeyResolver.resolve(route.locator, route.resourceClass)
        requireNoCollision(routeByPrivateKey[privateKey], route)
        routeByPrivateKey[privateKey] = route

        val transportClass = engineTransportClassFor(route.resourceClass)
        if (transportClass != null) {
            val transportIndex = TransportIndexKey(route.locator.value, transportClass)
            requireNoCollision(transportRoutes[transportIndex], route)
            transportRoutes[transportIndex] = route
        }

        val storeClass = engineKeyedResourceClassOf(route.resourceClass)
        // Computed once and shared by the two indices below, and not at all for the three classes the
        // engine never touches (sticker, GLB, model texture) -- a pure-Kotlin SHA-256 per route adds up
        // across a 512-instance tile plan.
        val expectedStableId = if (storeClass != null || transportClass != null) {
            redactedLocatorHex(route.locator.value)
        } else {
            null
        }
        if (storeClass != null) {
            val storeIndex = StoreIndexKey(requireNotNull(expectedStableId), storeClass)
            requireNoCollision(storeRoutes[storeIndex], route)
            storeRoutes[storeIndex] = route
        }

        if (transportClass != null) {
            val digestIndex = EngineDigestKey(requireNotNull(expectedStableId), route.resourceClass)
            requireNoCollision(routesByEngineDigest[digestIndex], route)
            routesByEngineDigest[digestIndex] = route
        }

        spriteMemberKeyOf(route)?.let { member ->
            requireNoCollision(spriteMemberRoutes[member], route)
            spriteMemberRoutes[member] = route
        }
    }

    /**
     * Translates one of Rentile's own `sanitizedResourceId` digests back into RenG's canonical
     * [ResourceKey] for the route that digest names, or `null` when this invocation preregistered no
     * such route. `null` is the honest answer, not a fallback: a digest this registry cannot place
     * belongs to a resource this invocation never routed.
     */
    fun renGResourceKeyForEngineDigest(resourceClass: ResourceClass, engineDigest: String): ResourceKey? {
        val route = routesByEngineDigest[EngineDigestKey(engineDigest, resourceClass)] ?: return null
        return renGResourceKeyFor(route)
    }

    private fun requireNoCollision(existing: ResourceRouteKey?, route: ResourceRouteKey) {
        if (existing != null && existing != route) throw ambiguousRouteFailure()
    }

    // ---- Transport ------------------------------------------------------------------------------

    suspend fun executeTransport(request: EngineTransportRequest): EngineTransportResponse {
        val route = transportRoutes[TransportIndexKey(request.url, request.resourceClass)]
            ?: throw ambiguousRouteFailure()
        val latchKey = TransportLatchKey(
            route = route,
            ifNoneMatch = request.metadata.ifNoneMatch,
            ifModifiedSince = request.metadata.ifModifiedSince,
            accept = request.metadata.accept,
        )
        return transportJoin.run(latchKey) {
            try {
                val response = transport.execute(
                    TransportRequest(
                        locator = route.locator,
                        resourceClass = route.resourceClass,
                        // The route's own ceiling always wins; the engine's own number in `request` is
                        // never trusted for this (ADR 0016).
                        maximumResponseBytes = route.maximumResponseBytes,
                        metadata = TransportRequestMetadata(
                            ifNoneMatch = request.metadata.ifNoneMatch,
                            ifModifiedSince = request.metadata.ifModifiedSince,
                            accept = request.metadata.accept,
                        ),
                    ),
                )
                recordLatchedTransportDigest(route, sha256Hex(response.bodySnapshot))
                response.toEngineResponse()
            } catch (cancellation: CancellationException) {
                // Deliberately NOT latched as an unreachable sprite member: a cancelled route is a
                // cancelled invocation, and a sibling parked at the rendezvous must observe that as its
                // own cancellation rather than as a sanitized store-write failure.
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") adapterFailure: Throwable) {
                markSpriteMemberUnreachable(route)
                throw transportFailure(route).toException()
            }
        }
    }

    // ---- Store ----------------------------------------------------------------------------------

    suspend fun readStore(key: EngineRawResourceKey): EngineStoredRawResource? {
        val route = storeRoutes[StoreIndexKey(key.stableId, key.resourceClass)] ?: throw ambiguousRouteFailure()
        return storeReadJoin.run(route) {
            try {
                val stored = store.read(RenGRawResourceKey(stableId = key.stableId, resourceClass = route.resourceClass))
                val validated = stored?.let { copyValidStoredResource(it, route.maximumResponseBytes, sha256) }
                if (validated == null || !passesClassSpecificReadValidation(route.resourceClass, validated)) {
                    // A store miss and a "digest-consistent but the engine could never use it" record
                    // are answered identically: null. Sprite's own acquirer validates only size and
                    // digest on a hit and never parses, so a record it accepts but cannot use would be
                    // permanently unrecoverable inside it (ADR 0016) -- the firewall must never let
                    // such a record reach it as if it were a genuine hit.
                    null
                } else {
                    // The mixed case the rendezvous would otherwise deadlock on: Rentile's sprite
                    // acquirer returns a store hit's bytes without ever writing them, so the sibling's
                    // write would wait for an arrival that is never coming. The firewall performed this
                    // read itself and therefore holds the very bytes the pair must be validated against,
                    // so a validated hit contributes the member here. The result is deliberately
                    // ignored: a conflicting contribution is a write-path concern, and this path's
                    // contract is to answer the engine's read honestly.
                    contributeSpriteMember(route, validated)
                    validated.toEngineStoredRawResource()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") adapterFailure: Throwable) {
                // A member whose own store read failed threw out of Rentile's `acquireRaw` before it
                // could fetch, so it can never reach a write: release its sibling rather than park it.
                markSpriteMemberUnreachable(route)
                throw storeReadFailure(route).toException()
            }
        }
    }

    suspend fun writeStore(key: EngineRawResourceKey, resource: EngineStoredRawResource) {
        val route = storeRoutes[StoreIndexKey(key.stableId, key.resourceClass)] ?: throw ambiguousRouteFailure()
        // Digest self-consistency and the byte ceiling are NOT checked here: `copyValidStoredResource`
        // below enforces both, and throws the identical failure. Checking them twice cost a second
        // pure-Kotlin SHA-256 over the same bytes on every engine store write.
        // ADR 0016 permits a Rentile write callback to reach the consumer "only after RenG verifies that
        // it matches the latched response". A route with NO latched transport response has nothing to
        // verify against, so it is refused outright rather than skipping verification: Rentile 0.2.0
        // cannot produce such a write -- every raw-store write sits immediately after a transport on the
        // same key -- but the firewall's whole premise is that the engine is untrusted, and "no latch"
        // was previously the one shape that walked straight through.
        val latchedDigest = latchedTransportDigestFor(route) ?: throw refusedWrite(route)
        if (latchedDigest != resource.contentDigest) {
            throw refusedWrite(route)
        }

        // The same stricter RenG record rules the read path already applies (empty bytes, digest shape,
        // metadata validity, byte ceiling). Without them a record with a negative `storedAtEpochMillis`
        // or a CRLF-bearing `etag` -- neither of which the digest check can see -- reached the consumer's
        // Store through the write path while being rejected on the read path.
        val validated = copyValidStoredResource(
            StoredRawResource(
                bytes = resource.bytes,
                contentDigest = resource.contentDigest,
                metadata = resource.metadata.toRenGMetadata(),
            ),
            route.maximumResponseBytes,
            sha256,
        ) ?: throw refusedWrite(route)

        // Gap the read path already closed, applied to the write it was always missing from: a fetched
        // sprite member whose png cannot decode or whose json cannot parse is bytes RenG can already
        // prove Rentile's own sprite compiler will refuse, and ADR 0016 additionally puts DEM terrain
        // encoding on this exact path.
        if (!passesClassSpecificWriteValidation(route.resourceClass, validated)) throw refusedWrite(route)

        storeWriteJoin.run(route) {
            // Inside the join, not before it, for two reasons. The join releases its own Mutex before
            // running this block, so parking here holds no lock and blocks no other route. And the
            // rendezvous outcome becomes this route's latched write outcome, so the engine's later
            // attempt on the same key replays it instead of parking a second time.
            approveSpriteMemberWrite(route, validated)
            try {
                store.write(
                    RenGRawResourceKey(stableId = key.stableId, resourceClass = route.resourceClass),
                    validated,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") adapterFailure: Throwable) {
                throw storeWriteFailure(route).toException()
            }
        }
    }

    /**
     * The sanitized refusal every pre-join [writeStore] check throws, plus the one side effect those
     * checks owe the rendezvous: a refused sprite member can never write, so its sibling must be
     * released rather than left parked on an arrival that is now impossible. Refusals are latched for
     * the registry's lifetime exactly like every other outcome here -- within one invocation "the
     * engine's second attempt is not a consumer retry" (ADR 0016) -- which for a sprite member also
     * makes the refusal itself sticky: a member latched unreachable can no longer contribute, so a
     * second write on that member is refused too. Every pre-join check is a function of the route and
     * the bytes the engine just fetched, so a second attempt could only differ by presenting different
     * bytes for one member, which is the collision this file exists to refuse.
     */
    private suspend fun refusedWrite(route: ResourceRouteKey): RenGException {
        markSpriteMemberUnreachable(route)
        return storeWriteFailure(route).toException()
    }

    /**
     * ADR 0016's joint sprite prevalidation, as a suspending rendezvous between the pair's two member
     * routes: this member publishes its own validated bytes, waits for its sibling's, and then both
     * replay one joint verdict, so both writes proceed or neither does. A no-op for every other class.
     *
     * Four ways a sibling can fail to arrive, all of which fail rather than hang:
     *  - **never preregistered** -- refused immediately, before any wait, since this invocation can
     *    never produce the arrival;
     *  - **its fetch or its store read failed** -- that path latched it unreachable, which completes
     *    the wait with "no content";
     *  - **its own write was refused** -- same latch, via [refusedWrite];
     *  - **the invocation is cancelled** -- the wait is an ordinary suspension point, so cancellation
     *    surfaces here unwrapped and is latched as this route's write outcome.
     *
     * There is deliberately no timeout. Rentile launches both members' `acquireRaw` calls inside one
     * `coroutineScope` and awaits both, so a genuine failure in one cancels the other rather than
     * leaving it parked, and its `SingleFlight` independently cancels the shared work once its last
     * waiter detaches -- two independent releases, neither of which needs a clock. A wall-clock bound
     * could not tell a sibling whose fetch is merely slow from one that will never come, so it would
     * only convert a slow consumer transport into a spurious failure.
     */
    private suspend fun approveSpriteMemberWrite(route: ResourceRouteKey, validated: StoredRawResource) {
        val member = spriteMemberKeyOf(route) ?: return
        val sibling = SpriteMemberKey(member.group, siblingSpriteClassOf(member.resourceClass))
        if (spriteMemberRoutes[sibling] == null) throw storeWriteFailure(route).toException()

        val mine = SpriteMemberContent(validated.contentDigest, validated.bytes)
        // A second, different content for one member inside one invocation is the same "two different
        // resources answered by one engine call" shape [requireNoCollision] rules out at preregistration
        // -- refuse it rather than letting a later write silently redefine an already-validated pair.
        if (!spriteRendezvous.contribute(member, mine)) throw refusedWrite(route)
        val theirs = spriteRendezvous.awaitContent(sibling) ?: throw storeWriteFailure(route).toException()

        val jsonMember = if (member.resourceClass == ResourceClass.BASEMAP_SPRITE_JSON) mine else theirs
        val imageMember = if (member.resourceClass == ResourceClass.BASEMAP_SPRITE_IMAGE) mine else theirs
        val approved = spritePairJoin.run(member.group) {
            spritePairIsJointlyValid(jsonMember.bytes, imageMember.bytes)
        }
        // Each member raises its own sanitized failure rather than replaying the other's descriptor, so
        // a refusal always names the route the engine was actually writing.
        if (!approved) throw storeWriteFailure(route).toException()
    }

    private suspend fun contributeSpriteMember(route: ResourceRouteKey, validated: StoredRawResource) {
        val member = spriteMemberKeyOf(route) ?: return
        spriteRendezvous.contribute(member, SpriteMemberContent(validated.contentDigest, validated.bytes))
    }

    private suspend fun markSpriteMemberUnreachable(route: ResourceRouteKey) {
        spriteMemberKeyOf(route)?.let { spriteRendezvous.markUnreachable(it) }
    }

    /**
     * Private and terminal (ADR 0016): performs no consumer removal, no repair, and no follow-on
     * exchange, whether or not [key] matches a preregistered route. RenG's own [Store] has no remove
     * operation at all, so there is nothing to forward even if this looked one up.
     */
    fun removeStore(key: EngineRawResourceKey) {
        // Intentionally empty.
    }

    // ---- validation / translation ----------------------------------------------------------------

    private fun passesClassSpecificReadValidation(resourceClass: ResourceClass, stored: StoredRawResource): Boolean =
        when (resourceClass) {
            ResourceClass.BASEMAP_SPRITE_IMAGE ->
                decodePng(stored.bytes, SPRITE_IMAGE_DECODE_CEILING_BYTES) is PngDecodeResult.Success
            ResourceClass.BASEMAP_SPRITE_JSON ->
                parseJson(stored.bytes, 0, stored.bytes.size, SPRITE_JSON_MAXIMUM_DEPTH) is JsonParse.Parsed
            // Deliberate, and the reason is specific rather than "nothing else needs checking". Rentile's
            // raster, vector, TileJSON and GeoJSON acquirers each re-run their own bounded parser or
            // decoder on a store hit and remove-then-refetch when it fails (e.g. `RasterResourceAcquirer`
            // at Rentile 0.2.0), so a bad record in one of those classes self-heals on the next access and
            // is never terminal. The sprite pair is the one exception -- its acquirer checks only size and
            // digest on a hit and never parses -- which is exactly why ADR 0016 records only the sprite
            // pair as terminal and why only it earns a class-specific gate here. Widening this branch
            // would buy nothing and would turn a self-healing engine record into a hard RenG refusal.
            else -> true
        }

    /**
     * Everything [passesClassSpecificReadValidation] proves, plus the one obligation ADR 0016 places on
     * writes alone: *"A fetched DEM write additionally requires RenG's terrain encoding validation."*
     * Rentile's DEM path is `RasterResourceAcquirer`, which reaches its raw-store write after generic
     * bounded image validation only, so nothing but this checks that a DEM tile is actually terrain-
     * encoded before it lands in the consumer's Store.
     */
    private fun passesClassSpecificWriteValidation(resourceClass: ResourceClass, stored: StoredRawResource): Boolean =
        when (resourceClass) {
            ResourceClass.BASEMAP_DEM_TILE ->
                validatesDemTerrainEncoding(stored.bytes, DEM_TILE_DECODE_CEILING_BYTES)
            else -> passesClassSpecificReadValidation(resourceClass, stored)
        }

    private fun redactedLocatorHex(url: String): String = sha256Hex(redactAuthenticationQuery(url))

    private fun sha256Hex(value: String): String = sha256Hex(value.encodeToByteArray())

    private fun sha256Hex(bytes: ByteArray): String = sha256.digest(CanonicalBytes(bytes)).lowercaseHex

    private fun TransportResponse.toEngineResponse(): EngineTransportResponse = EngineTransportResponse(
        statusCode = statusCode,
        body = body,
        metadata = EngineTransportResponseMetadata(
            contentType = metadata.contentType,
            etag = metadata.etag,
            lastModified = metadata.lastModified,
            cacheControl = null,
            expiresAtEpochMillis = metadata.freshUntilEpochMillis,
            vary = emptyList(),
            retryAfterMillis = null,
            redirectLocation = null,
            wireByteCount = null,
        ),
    )

    private fun StoredRawResource.toEngineStoredRawResource(): EngineStoredRawResource = EngineStoredRawResource(
        bytes = bytes,
        contentDigest = contentDigest,
        metadata = EngineRawResourceMetadata(
            contentType = metadata.contentType,
            etag = metadata.etag,
            lastModified = metadata.lastModified,
            freshUntilEpochMillis = metadata.freshUntilEpochMillis,
            storedAtEpochMillis = metadata.storedAtEpochMillis,
        ),
    )

    private fun EngineRawResourceMetadata.toRenGMetadata(): StoredRawResourceMetadata = StoredRawResourceMetadata(
        contentType = contentType,
        etag = etag,
        lastModified = lastModified,
        freshUntilEpochMillis = freshUntilEpochMillis,
        storedAtEpochMillis = storedAtEpochMillis,
    )

    // ---- failures ---------------------------------------------------------------------------------
    // No adapter message, cause, locator, or engine key ever reaches these -- every diagnostic below
    // carries only an enum-valued resourceClass and RenG's own canonical resourceKey (a different
    // hash from anything Rentile derives), never the engine's own stableId.

    private fun renGResourceKeyFor(route: ResourceRouteKey) =
        resourceKeyDeriver.external(route.resourceClass, route.locator).key

    private fun ambiguousRouteFailure(): RenGException = FailureDescriptor(
        code = RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
        stage = PipelineStage.RESOURCE_LOOKUP,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_LOOKUP,
            fieldName = DiagnosticField.RESOURCE,
        ),
    ).toException()

    private fun transportFailure(route: ResourceRouteKey): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.TRANSPORT_EXECUTION_FAILED,
        stage = PipelineStage.TRANSPORT,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.TRANSPORT,
            resourceClass = route.resourceClass,
            resourceKey = renGResourceKeyFor(route),
        ),
    )

    private fun storeReadFailure(route: ResourceRouteKey): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.STORE_READ_FAILED,
        stage = PipelineStage.STORE_READ,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.STORE_READ,
            resourceClass = route.resourceClass,
            resourceKey = renGResourceKeyFor(route),
        ),
    )

    private fun storeWriteFailure(route: ResourceRouteKey): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.STORE_WRITE_FAILED,
        stage = PipelineStage.STORE_WRITE,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.STORE_WRITE,
            resourceClass = route.resourceClass,
            resourceKey = renGResourceKeyFor(route),
        ),
    )
}

/** The exact `(url, engine resource class)` pair Rentile's own [EngineTransportRequest] carries. */
private data class TransportIndexKey(val url: String, val engineClass: EngineResourceClass)

/** The exact `(stableId, engine resource class)` pair Rentile's own [EngineRawResourceKey] carries. */
private data class StoreIndexKey(val stableId: String, val engineClass: EngineResourceClass)

/**
 * `(Rentile's sanitizedResourceId, RenG resource class)`. Keyed by RenG's own [ResourceClass] rather
 * than Rentile's, because the caller reaches this having already translated the engine's class through
 * [rengResourceClassOf] -- and because that translation is where `STYLE` -> `BASEMAP_STYLE` is spelled
 * out at all.
 */
private data class EngineDigestKey(val engineDigest: String, val resourceClass: ResourceClass)

/**
 * Which of Rentile's own [EngineResourceClass] values every declared RenG [ResourceClass] maps to on
 * the Transport side. Unlike [engineKeyedResourceClassOf] (Store-side, and `null` for
 * [ResourceClass.BASEMAP_STYLE] since the engine's `RawResourceStore` never sees style), Transport
 * *does* see style -- Rentile fetches style bytes to compile privately but never writes them to its
 * raw store (ADR 0016) -- so this mapping is a strict superset of one more class.
 */
private fun engineTransportClassFor(resourceClass: ResourceClass): EngineResourceClass? =
    if (resourceClass == ResourceClass.BASEMAP_STYLE) EngineResourceClass.STYLE else engineKeyedResourceClassOf(resourceClass)

/**
 * A join keyed by [K]: the first caller for a given key actually runs [block] and latches its
 * outcome (success, sanitized failure, or an unwrapped [CancellationException] -- [block] is
 * responsible for producing that shape, this class only replays whatever escapes or is returned);
 * every other caller -- concurrent or sequential, including the engine's own private retry -- replays
 * that exact outcome without running [block] again. Never removes a key once latched: within one
 * operation-scoped registry, "the engine's second attempt is not a consumer retry" (ADR 0016) holds
 * for the lifetime of the registry, not just for the duration of the first call.
 */
private class SuspendJoin<K : Any, V> {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<K, CompletableDeferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        var owner = false
        val deferred = mutex.withLock {
            inFlight.getOrPut(key) {
                owner = true
                CompletableDeferred()
            }
        }
        if (!owner) return deferred.await()
        return try {
            val result = block()
            deferred.complete(result)
            result
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        }
    }
}

/**
 * The two members of one sprite atlas, identified by the base url Rentile derived both from. Rentile's
 * `SpriteResourceAcquirer` composes `<base>.json` and `<base>.png` through its own
 * `appendSpriteExtension` (extension before the query and fragment, not after), so stripping that
 * class's extension back off a member's locator recovers the base the two share. Rentile 0.2.0 admits
 * exactly one string-valued `sprite` reference per style, so one invocation holds at most one pair --
 * this derivation's job is to confirm two routes belong together, not to disambiguate among several.
 */
private data class SpriteGroupKey(val base: String)

private data class SpriteMemberKey(val group: SpriteGroupKey, val resourceClass: ResourceClass)

/** One member's validated bytes, immutable once contributed. */
private class SpriteMemberContent(val digest: String, val bytes: ByteArray)

private fun spriteMemberKeyOf(route: ResourceRouteKey): SpriteMemberKey? {
    val extension = when (route.resourceClass) {
        ResourceClass.BASEMAP_SPRITE_JSON -> SPRITE_JSON_EXTENSION
        ResourceClass.BASEMAP_SPRITE_IMAGE -> SPRITE_IMAGE_EXTENSION
        else -> return null
    }
    return SpriteMemberKey(SpriteGroupKey(spriteBaseUrl(route.locator.value, extension)), route.resourceClass)
}

private fun siblingSpriteClassOf(resourceClass: ResourceClass): ResourceClass =
    if (resourceClass == ResourceClass.BASEMAP_SPRITE_JSON) {
        ResourceClass.BASEMAP_SPRITE_IMAGE
    } else {
        ResourceClass.BASEMAP_SPRITE_JSON
    }

/**
 * The exact inverse of Rentile's `appendSpriteExtension`: split the fragment, split the query, drop
 * [extension] from the end of the path alone, and put the query and fragment back. A locator whose path
 * does not end in [extension] is returned unchanged, which simply groups it with itself -- it then finds
 * no sibling and its write is refused, rather than being silently paired with an unrelated route.
 */
private fun spriteBaseUrl(url: String, extension: String): String {
    val fragmentIndex = url.indexOf('#').let { if (it < 0) url.length else it }
    val withoutFragment = url.substring(0, fragmentIndex)
    val fragment = url.substring(fragmentIndex)
    val queryIndex = withoutFragment.indexOf('?').let { if (it < 0) withoutFragment.length else it }
    val path = withoutFragment.substring(0, queryIndex)
    val query = withoutFragment.substring(queryIndex)
    val base = if (path.endsWith(extension)) path.dropLast(extension.length) else path
    return base + query + fragment
}

/**
 * The cross-member checks Rentile's own `SpriteResourceAcquirer.compile` performs, and only those that
 * are unconditional and independent of Rentile's configuration: the manifest is an object of entry
 * objects, each entry carries integer `x`/`y`/`width`/`height`, each rect is non-degenerate and lies
 * wholly inside the atlas image, a present `pixelRatio` is finite and positive, and no entry carries the
 * `stretchX`/`stretchY`/`content` fields Rentile refuses. Rentile's entry-count ceiling and its
 * `maxRasterDimensionPx` bound are deliberately not mirrored: both are limit-shaped numbers owned by the
 * engine's own configuration, and a firewall that guessed one too strictly would refuse a write the
 * engine could have used, which -- since Rentile turns a refused write into a failed acquisition -- is a
 * worse outcome than the poisoning this gate exists to prevent.
 *
 * Only the image's IHDR is needed, so this scans the container ([scanPng]) rather than decoding it; the
 * member gate already proved the same bytes decode in full.
 */
private fun spritePairIsJointlyValid(jsonBytes: ByteArray, imageBytes: ByteArray): Boolean {
    val header = (scanPng(imageBytes) as? PngScan.Admitted)?.header ?: return false
    val parsed = parseJson(jsonBytes, 0, jsonBytes.size, SPRITE_JSON_MAXIMUM_DEPTH) as? JsonParse.Parsed
    val root = parsed?.value as? JsonValue.Obj ?: return false
    return root.members.values.all { entry -> spriteEntryFitsAtlas(entry, header.width, header.height) }
}

private fun spriteEntryFitsAtlas(entry: JsonValue, imageWidth: Int, imageHeight: Int): Boolean {
    val members = (entry as? JsonValue.Obj)?.members ?: return false
    if (members.keys.any { it in UNSUPPORTED_SPRITE_ENTRY_FIELDS }) return false
    val x = spriteEntryInt(members["x"]) ?: return false
    val y = spriteEntryInt(members["y"]) ?: return false
    val width = spriteEntryInt(members["width"]) ?: return false
    val height = spriteEntryInt(members["height"]) ?: return false
    if (x < 0 || y < 0 || width <= 0 || height <= 0) return false
    // Widened deliberately: two in-range Ints can overflow their sum, and an overflowed rect would
    // compare as comfortably inside the atlas.
    if (x.toLong() + width.toLong() > imageWidth.toLong()) return false
    if (y.toLong() + height.toLong() > imageHeight.toLong()) return false
    // Absent, or present but unreadable as a number, is not a rejection: Rentile falls back to 1.0 in
    // both cases, so refusing here would refuse a pair the engine compiles.
    val pixelRatio = spriteEntryDouble(members["pixelRatio"]) ?: return true
    return pixelRatio.isFinite() && pixelRatio > 0.0
}

/** Rentile reads these through `JsonPrimitive.intOrNull`, which parses a quoted primitive's content
 *  exactly as it parses a bare one, so a string-spelled integer is admitted here for the same reason. */
private fun spriteEntryInt(value: JsonValue?): Int? = when (value) {
    is JsonValue.Integer -> if (value.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.value.toInt() else null
    is JsonValue.Text -> value.value.toIntOrNull()
    else -> null
}

private fun spriteEntryDouble(value: JsonValue?): Double? = when (value) {
    is JsonValue.Integer -> value.value.toDouble()
    is JsonValue.Real -> value.value
    is JsonValue.Text -> value.value.toDoubleOrNull()
    else -> null
}

/**
 * ADR 0016's DEM write obligation. Mapbox Terrain-RGB and Terrarium are both plain eight-bit RGB byte
 * triples -- mathematically indistinguishable from the decoded bytes alone, since both are only a
 * caller-side formula's interpretation of R/G/B -- so any PNG whose source colour type carries no
 * meaningful alpha (RenG's canonical decode leaves such pixels fully opaque) satisfies either encoding,
 * and a genuinely four-channel encoding fails the instant any pixel is not fully opaque.
 *
 * **This is a seam, not a second implementation to keep.** The concurrent gate-deletion task promoted
 * the identical check out of `RenGClassGateRunner`'s private gate path as
 * `internal fun validatesDemTerrainEncoding(bytes: ByteArray, maximumDecodedImageBytes: Long): Boolean`
 * in `internal/driver/ClassGateRunner.kt`, on a sibling branch that this branch's base predates -- so
 * this file cannot call it and still compile. The signature here is deliberately identical to that one,
 * and the sole call site passes the same two arguments, so integrating the two branches is exactly:
 * delete this function and import that one. Nothing else moves.
 */
private fun validatesDemTerrainEncoding(bytes: ByteArray, maximumDecodedImageBytes: Long): Boolean {
    val decoded = decodePng(bytes, maximumDecodedImageBytes) as? PngDecodeResult.Success ?: return false
    val rgba = decoded.image.rgbaSnapshot()
    var index = 3
    while (index < rgba.size) {
        if (rgba[index] != OPAQUE_ALPHA) return false
        index += 4
    }
    return true
}

/**
 * The sprite pair's arrival board: each member is latched exactly once, either with the validated bytes
 * that arrived for it or as one that can never arrive, and a waiter suspends until its member reaches
 * one of those two states. Never evicts, for the same reason [SuspendJoin] never does -- the registry it
 * lives in is discarded when the invocation terminates (ADR 0016), so a latched "unreachable" can never
 * be inherited by a later, healthy preparation.
 *
 * Concurrency, stated because this file has already had one unsynchronised map on a genuinely concurrent
 * answer path: every read and write of [slots] and of a slot's own fields happens under [mutex], the
 * suspending wait happens with **no** lock held (the deferred is captured under the lock and awaited
 * outside it), and [mutex] is a leaf -- no other lock is ever acquired while it is held, and it is only
 * ever acquired from a [SuspendJoin] block, which has already released its own mutex. Each slot's
 * [Slot.arrived] is completed by exactly one caller: the one whose critical section performed the
 * transition, which completes it after leaving the lock.
 */
private class SpriteRendezvous {
    private val mutex = Mutex()
    private val slots = mutableMapOf<SpriteMemberKey, Slot>()

    /**
     * Publishes [content] for [member]. Returns `false` -- and publishes nothing -- when this member is
     * already latched with different content, or already latched unreachable.
     */
    suspend fun contribute(member: SpriteMemberKey, content: SpriteMemberContent): Boolean {
        var signal: CompletableDeferred<Unit>? = null
        val accepted = mutex.withLock {
            val slot = slots.getOrPut(member) { Slot() }
            val existing = slot.content
            when {
                existing != null -> existing.digest == content.digest
                slot.unreachable -> false
                else -> {
                    slot.content = content
                    signal = slot.arrived
                    true
                }
            }
        }
        signal?.complete(Unit)
        return accepted
    }

    /** Latches [member] as one whose write can never arrive, releasing any sibling waiting on it. */
    suspend fun markUnreachable(member: SpriteMemberKey) {
        var signal: CompletableDeferred<Unit>? = null
        mutex.withLock {
            val slot = slots.getOrPut(member) { Slot() }
            if (slot.content == null && !slot.unreachable) {
                slot.unreachable = true
                signal = slot.arrived
            }
        }
        signal?.complete(Unit)
    }

    /** Suspends until [member] is latched either way; `null` means it can never arrive. */
    suspend fun awaitContent(member: SpriteMemberKey): SpriteMemberContent? {
        val slot = mutex.withLock { slots.getOrPut(member) { Slot() } }
        slot.arrived.await()
        return mutex.withLock { slot.content }
    }

    private class Slot {
        val arrived = CompletableDeferred<Unit>()
        var content: SpriteMemberContent? = null
        var unreachable: Boolean = false
    }
}

private const val SPRITE_JSON_EXTENSION = ".json"
private const val SPRITE_IMAGE_EXTENSION = ".png"

/** The three entry fields Rentile's sprite compiler rejects outright rather than ignoring. */
private val UNSUPPORTED_SPRITE_ENTRY_FIELDS: Set<String> = setOf("stretchX", "stretchY", "content")

private const val OPAQUE_ALPHA: Byte = -1 // 0xFF unsigned

/** Generous enough for any DEM tile; only used to decide whether a fetched DEM record is terrain-encoded
 *  at all before writing it, never to size an actual buffer. */
private const val DEM_TILE_DECODE_CEILING_BYTES: Long = 64L * 1024L * 1024L

/** Generous enough for any sticker/sprite-sized image; only used to decide whether a sprite image
 *  record decodes at all before answering the engine's read, never to size an actual buffer. */
private const val SPRITE_IMAGE_DECODE_CEILING_BYTES: Long = 64L * 1024L * 1024L

/** Sprite JSON documents are small, flat atlas manifests; this is a generous nesting ceiling used
 *  only to prove the document parses at all before answering the engine's read. */
private const val SPRITE_JSON_MAXIMUM_DEPTH: Int = 64
