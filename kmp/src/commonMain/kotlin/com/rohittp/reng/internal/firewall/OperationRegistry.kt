package com.rohittp.reng.internal.firewall

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceClass
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
import com.rohittp.reng.internal.image.decodePng
import com.rohittp.reng.internal.json.JsonParse
import com.rohittp.reng.internal.json.parseJson
import com.rohittp.reng.internal.resource.RentilePrivateKey
import com.rohittp.reng.internal.resource.RentilePrivateKeyResolver
import com.rohittp.reng.internal.resource.ResourceRouteKey
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
 * One instance is meant to live exactly as long as one preparation invocation: "discarded when the
 * invocation terminates" (ADR 0016), never a renderer-lifetime cache and never shared across access
 * modes. Wiring a fresh instance per invocation into an actual long-lived Rentile engine instance is
 * later basemap-cycle work; this class only has to be correct once constructed and preregistered.
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

    // The digest of the most recent successfully latched Transport response for a route, so a
    // subsequent Store write can be checked against what the engine actually fetched (ADR 0016: "a
    // Rentile write callback may perform the consumer write only after RenG verifies that it matches
    // the latched response").
    private val lastTransportDigestByRoute = mutableMapOf<ResourceRouteKey, String>()

    private val transportJoin = SuspendJoin<TransportLatchKey, EngineTransportResponse>()
    private val storeReadJoin = SuspendJoin<ResourceRouteKey, EngineStoredRawResource?>()
    private val storeWriteJoin = SuspendJoin<ResourceRouteKey, Unit>()

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
        if (storeClass != null) {
            val expectedStableId = redactedLocatorHex(route.locator.value)
            val storeIndex = StoreIndexKey(expectedStableId, storeClass)
            requireNoCollision(storeRoutes[storeIndex], route)
            storeRoutes[storeIndex] = route
        }
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
                lastTransportDigestByRoute[route] = sha256Hex(response.bodySnapshot)
                response.toEngineResponse()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") adapterFailure: Throwable) {
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
                    validated.toEngineStoredRawResource()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") adapterFailure: Throwable) {
                throw storeReadFailure(route).toException()
            }
        }
    }

    suspend fun writeStore(key: EngineRawResourceKey, resource: EngineStoredRawResource) {
        val route = storeRoutes[StoreIndexKey(key.stableId, key.resourceClass)] ?: throw ambiguousRouteFailure()
        val recomputedDigest = sha256Hex(resource.bytes)
        if (recomputedDigest != resource.contentDigest) throw storeWriteFailure(route).toException()
        if (resource.bytes.size.toLong() > route.maximumResponseBytes) throw storeWriteFailure(route).toException()
        val latchedDigest = lastTransportDigestByRoute[route]
        if (latchedDigest != null && latchedDigest != resource.contentDigest) {
            throw storeWriteFailure(route).toException()
        }

        storeWriteJoin.run(route) {
            try {
                store.write(
                    RenGRawResourceKey(stableId = key.stableId, resourceClass = route.resourceClass),
                    StoredRawResource(
                        bytes = resource.bytes,
                        contentDigest = resource.contentDigest,
                        metadata = resource.metadata.toRenGMetadata(),
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") adapterFailure: Throwable) {
                throw storeWriteFailure(route).toException()
            }
        }
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
            else -> true
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
 * One joined Transport exchange's identity: the route plus the three allowlisted metadata values
 * (ADR 0016's "the final Transport latch key is the route plus all three exact metadata values").
 * `ifNoneMatch`/`ifModifiedSince` are measured always null and `accept` is measured non-null on
 * exactly the sprite classes -- this class does not assume either, it only latches whatever the
 * three fields actually are.
 */
private data class TransportLatchKey(
    val route: ResourceRouteKey,
    val ifNoneMatch: String?,
    val ifModifiedSince: String?,
    val accept: String?,
)

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

/** Generous enough for any sticker/sprite-sized image; only used to decide whether a sprite image
 *  record decodes at all before answering the engine's read, never to size an actual buffer. */
private const val SPRITE_IMAGE_DECODE_CEILING_BYTES: Long = 64L * 1024L * 1024L

/** Sprite JSON documents are small, flat atlas manifests; this is a generous nesting ceiling used
 *  only to prove the document parses at all before answering the engine's read. */
private const val SPRITE_JSON_MAXIMUM_DEPTH: Int = 64
