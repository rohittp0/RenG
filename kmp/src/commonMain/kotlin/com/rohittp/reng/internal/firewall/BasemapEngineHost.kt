package com.rohittp.reng.internal.firewall

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.Transport
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.cache.Lease
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.cache.ResidentGeneration
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failure.toException
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.identity.Sha256Function
import com.rohittp.reng.internal.planning.BasemapTileInstance
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.resource.RentilePrivateKeyResolver
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.ResourceAccessMode as RenGResourceAccessMode
import com.rohittp.rentile.BasemapRasterizer
import com.rohittp.rentile.CredentialProvider
import com.rohittp.rentile.MapSessionProvider
import com.rohittp.rentile.MetricsSink
import com.rohittp.rentile.PreparedBatch
import com.rohittp.rentile.PreparedStyle
import com.rohittp.rentile.RawResourceKey as EngineRawResourceKey
import com.rohittp.rentile.RawResourceStore as EngineRawResourceStore
import com.rohittp.rentile.RenderOptions
import com.rohittp.rentile.Rentile
import com.rohittp.rentile.RentileClock
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.ResourceAccessMode as EngineResourceAccessMode
import com.rohittp.rentile.ResourceSubstitution
import com.rohittp.rentile.ResourceTransport as EngineResourceTransport
import com.rohittp.rentile.StoredRawResource as EngineStoredRawResource
import com.rohittp.rentile.StyleInput
import com.rohittp.rentile.TileId
import com.rohittp.rentile.TileSubstitutionPolicy
import com.rohittp.rentile.TransportRequest as EngineTransportRequest
import com.rohittp.rentile.TransportResponse as EngineTransportResponse
import kotlinx.coroutines.CancellationException

/**
 * The one place in RenG that constructs and drives a real Rentile engine (ADR 0016, ADR 0017).
 *
 * **Ownership.** One renderer owns one long-lived [BasemapRasterizer], created here at setup with no
 * I/O and no suspension: `Rentile.create` is not `suspend`, allocates no thread (its scope's
 * `Dispatchers.Default` spawns nothing until work is submitted), and touches neither adapter. The
 * engine's [close] is **not** GL-scoped, so it is entirely independent of the exact-current-context rule
 * governing RenG's own GL deletion (ADRs 0007/0015) — this class deletes no GL object at all.
 *
 * **Firewall lifetime.** ADR 0016 says the operation registry "is discarded when the invocation
 * terminates; it is neither a renderer-lifetime response cache nor permission to share work across
 * access modes," while the engine's own adapters are fixed for the engine's whole life. Those two facts
 * cannot both hold if [RentileConfiguration.transport] literally *is* a `FirewallTransport` bound to one
 * registry, so this class supplies the indirection ADR 0016's own wording implies: the configuration's
 * adapters are fixed for the engine's lifetime and route every call to the [FirewallTransport] /
 * [FirewallStore] of whichever preparation invocation is currently open ([withOperation]). Outside any
 * invocation they fail closed with the same sanitized `AMBIGUOUS_RESOURCE_ROUTE` an unrecognised route
 * gets, because an engine call with no active invocation is exactly an exchange RenG never planned.
 *
 * That choice also settles the question `SuspendJoin` raises: a latched cancellation (or failure) never
 * evicts, so it replays for the registry's whole lifetime. Bounding that lifetime to one invocation is
 * what keeps a later, healthy preparation from inheriting a cancellation it never earned.
 *
 * **Failures.** Every engine call routes through [engineCall]: no `RentileException` escapes this file,
 * `CancellationException` propagates unwrapped, and a [RenGException] the firewall itself raised is
 * preserved rather than reclassified. Descriptors leaving here have their identity translated out of
 * Rentile's digest namespace and into RenG's own — see [translateForeignIdentity].
 *
 * **Content policy.** [TileSubstitutionPolicy.Disabled] and the operation's access mode are passed
 * explicitly at every call, never left to the dependency's defaults: RenG "performs no repeated consumer
 * exchanges, retries, repairs, or fallbacks", and tile substitution is exactly such a fallback, so a
 * future Rentile release must not be able to turn one on for us by changing a default.
 */
internal class BasemapEngineHost(
    transport: Transport,
    store: Store,
    private val cache: ResidentCache,
    private val tileOutputSizePixels: Int = RenderOptions.DEFAULT_OUTPUT_SIZE_PX,
    private val sha256: Sha256Function = PureKotlinSha256,
    private val privateKeyResolver: RentilePrivateKeyResolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256),
) : AutoCloseable {

    private val consumerTransport: Transport = transport
    private val consumerStore: Store = store
    private val renderOptions = RenderOptions(outputSizePx = tileOutputSizePixels)
    private val tileOutputSize = OutputPixelSize(tileOutputSizePixels, tileOutputSizePixels)

    /** The one preparation invocation currently allowed to reach the consumer, or `null` between them. */
    private var activeOperation: FirewallOperation? = null

    private var compiledStyle: CompiledStyleBinding? = null

    private var closed: Boolean = false

    val isClosed: Boolean get() = closed

    private val engine: BasemapRasterizer = Rentile.create(
        RentileConfiguration(
            transport = RoutedEngineTransport(),
            rawResourceStore = RoutedEngineStore(),
            // Stated rather than defaulted, for the same reason as TileSubstitutionPolicy.Disabled below:
            // RenG supplies no credential and no session of its own, and a future Rentile whose defaults
            // changed must not be able to start composing one into a url the firewall never preregistered.
            sessionProvider = MapSessionProvider.None,
            credentialProvider = CredentialProvider.None,
            clock = RentileClock.System,
            metricsSink = MetricsSink.None,
        ),
    )

    /**
     * Opens exactly one preparation invocation: a fresh [OperationRegistry] preregistered with [routes],
     * installed as the engine's answer path for the duration of [block], and discarded when [block]
     * terminates however it terminates.
     *
     * [accessMode] binds the invocation's mode explicitly rather than reconstructing it from a Rentile
     * key, which Rentile's callbacks do not carry at all (ADR 0016). Every route must already agree with
     * it, since one registry is never shared across modes.
     */
    suspend fun <T> withOperation(
        accessMode: RenGResourceAccessMode,
        routes: List<ResourceRouteKey>,
        block: suspend () -> T,
    ): T {
        check(activeOperation == null) { "a basemap engine host drives one preparation invocation at a time" }
        require(routes.all { it.accessMode == accessMode }) {
            "an operation-scoped registry is never shared across access modes"
        }
        val registry = OperationRegistry(
            transport = consumerTransport,
            store = consumerStore,
            privateKeyResolver = privateKeyResolver,
            sha256 = sha256,
        )
        routes.forEach(registry::preregister)
        activeOperation = FirewallOperation(accessMode, registry)
        return try {
            block()
        } finally {
            activeOperation = null
        }
    }

    /**
     * The compiled [PreparedStyle] for [styleKey], compiled lazily on first use and bound to the style's
     * current resident generation. A later call reuses it while that exact generation is still current;
     * once the generation is freed — or superseded by a fresh install — the next call reloads the style
     * and recompiles, because "accessing a freed resource reloads it" rather than failing.
     *
     * The lease is taken atomically with the observation or the install ([ResidentCache.observeAndTakeLease]
     * / [ResidentCache.installAndTakeLease]), never as a separate `current()`-then-`takeLease()` pair: a
     * freshly installed generation sits at `leaseCount == 0` in the gap between those two calls, where a
     * racing `free()` can drop it out of the cache's bookkeeping entirely.
     *
     * The bytes are handed to the engine as [StyleInput.Prefetched], never [StyleInput.Remote]: RenG has
     * already acquired them through its own driver under its own key, and a `Remote` style would make the
     * engine fetch them a second time.
     */
    suspend fun preparedStyle(
        styleKey: ResourceKey,
        stored: StoredRawResource,
        baseUri: String?,
    ): PreparedStyle {
        requireOpen()
        val existing = compiledStyle
        if (existing != null && existing.styleKey == styleKey && cache.current(styleKey) === existing.generation) {
            return existing.prepared
        }

        releaseCompiledStyle()
        val lease = cache.observeAndTakeLease(styleKey)
            ?: cache.installAndTakeLease(styleKey, stored, decoded = null)
        // The lease is taken before compilation because compilation reads the leased generation's own
        // bytes -- the resident generation is authoritative, not the caller's copy -- and is released again
        // if compilation fails, so a style that will not compile does not pin a generation for the
        // renderer's whole life.
        val prepared = try {
            engineCall {
                engine.prepare(
                    StyleInput.Prefetched(
                        bytes = lease.generation.stored.bytes,
                        canonicalIdentity = styleKey.stableId,
                        baseUri = baseUri,
                    ),
                )
            }
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            cache.releaseLease(lease)
            throw failure
        }
        compiledStyle = CompiledStyleBinding(styleKey, lease, prepared)
        return prepared
    }

    /**
     * Acquires everything [tiles] need — through the firewall, so through this invocation's preregistered
     * routes and nothing else — and returns a network-free batch. Substitution is disabled explicitly.
     */
    suspend fun prepareTiles(style: PreparedStyle, tiles: List<CanonicalBasemapTile>): PreparedBasemapTiles {
        requireOpen()
        val distinct = tiles.distinct()
        val batch = engineCall {
            engine.prepareBatch(
                style = style,
                tiles = distinct.map(::engineTileIdOf),
                options = renderOptions,
                resourceAccess = engineAccessModeOf(activeOperation?.accessMode ?: RenGResourceAccessMode.NORMAL),
                substitutionPolicy = TileSubstitutionPolicy.Disabled,
            )
        }
        return PreparedBasemapTiles(batch, style, distinct)
    }

    /**
     * Draws [prepared]'s tiles. Performs no adapter call whatsoever: everything was acquired by
     * [prepareTiles], which is the whole point of Rentile's prepare/render split.
     */
    suspend fun renderTiles(prepared: PreparedBasemapTiles): List<RenderedBasemapTile> {
        requireOpen()
        val styleDigest = prepared.style.digest
        val batch = engineCall { engine.render(prepared.batch) }
        val tilesById = prepared.tiles.associateBy(::engineTileIdOf)
        return batch.tiles.map { rendered ->
            val tile = tilesById[rendered.id]
                ?: error("the engine rendered a tile this batch never asked for")
            RenderedBasemapTile(
                key = basemapTileKey(styleDigest, tile, tileOutputSize, sha256),
                tile = tile,
                pngBytes = rendered.pngBytes,
                contentKey = rendered.contentKey,
                substitutions = prepared.batch.substitutions[rendered.id].orEmpty(),
            )
        }
    }

    /** RenG's own identity for the rendered tile [tile] of [style] at this host's tile output size. */
    fun renderedTileKey(style: PreparedStyle, tile: CanonicalBasemapTile): ResourceKey =
        basemapTileKey(style.digest, tile, tileOutputSize, sha256)

    /**
     * Idempotent. Releases the compiled style's lease, then closes the engine — whose own `close()` is
     * documented idempotent, non-blocking, and non-throwing, and needs no GL context of any kind.
     */
    override fun close() {
        if (closed) return
        closed = true
        releaseCompiledStyle()
        activeOperation = null
        engine.close()
    }

    // ---- internals -------------------------------------------------------------------------------

    private fun releaseCompiledStyle() {
        compiledStyle?.let { binding -> cache.releaseLease(binding.lease) }
        compiledStyle = null
    }

    private fun requireOpen() {
        if (closed) throw basemapRenderFailure().toException()
    }

    private fun engineTileIdOf(tile: CanonicalBasemapTile): TileId =
        TileId(z = tile.lod, x = tile.canonicalX, y = tile.tileY)

    /**
     * Exhaustive over RenG's own mode enum rather than defaulted, so a mode RenG adds later fails this
     * file's compilation instead of silently arriving at the engine as `NORMAL`.
     */
    private fun engineAccessModeOf(mode: RenGResourceAccessMode): EngineResourceAccessMode = when (mode) {
        RenGResourceAccessMode.NORMAL -> EngineResourceAccessMode.NORMAL
        RenGResourceAccessMode.CACHE_ONLY -> EngineResourceAccessMode.CACHE_ONLY
        RenGResourceAccessMode.RELOAD -> EngineResourceAccessMode.RELOAD
    }

    /**
     * The one seam every engine call passes through.
     *
     * A [CancellationException] propagates unwrapped ([classifyEngineFailure] rethrows it before
     * classifying anything), and is asserted on by type rather than identity everywhere downstream
     * because Kotlin's stack recovery may hand back a copy carrying the original as its immediate cause.
     *
     * A [RenGException] is rethrown as-is, so that a firewall failure which reaches this seam unwrapped
     * keeps its precise code, stage, and resource rather than being reclassified: [classifyEngineFailure]
     * would see a non-`RentileException` and answer the opaque `BASEMAP_RENDER_FAILED`. Stated honestly,
     * this branch is defensive and **no test covers it**, because Rentile 0.2.0 wraps every adapter
     * throwable at every call site it has — `RasterResourceAcquirer` turns a store read fault into its own
     * `ResourceStoreException` and a transport fault into its own `ResourceAcquisitionException`, dropping
     * the cause in both — so a RenG failure raised inside an adapter arrives here already converted. That
     * conversion is a genuine loss this branch cannot recover; it exists so that a Rentile release which
     * stops wrapping, or a path that never did, does not silently downgrade a good failure.
     */
    private inline fun <T> engineCall(block: () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (rengFailure: RenGException) {
            throw rengFailure
        } catch (@Suppress("TooGenericExceptionCaught") engineFailure: Throwable) {
            throw translateForeignIdentity(classifyEngineFailure(engineFailure)).toException()
        }

    /**
     * Rewrites a classified engine failure's resource identity out of **Rentile's** namespace and into
     * RenG's own.
     *
     * [classifyEngineFailure] can only report the digest Rentile gave it — `sha256Hex(redacted url)` —
     * because it holds no locator. That digest is well-formed, credential-free, and completely wrong as
     * an identity a consumer can act on: passed to `ResourceSelector.ByKey` it produces a **silent empty
     * selection**, not an error. This host does hold the locators, by way of the invocation's own
     * [OperationRegistry], so it is where the translation belongs.
     *
     * When the digest names no preregistered route the descriptor is returned untouched. That is the
     * least-bad answer available: the failure rules that admit an identity here require one
     * (`REQUIRED_EXTERNAL`), so the key cannot simply be dropped, and collapsing the whole failure to
     * `BASEMAP_RENDER_FAILED` would throw away a truthful code, stage, and resource class to hide one
     * untranslatable field.
     *
     * [com.rohittp.reng.ResourceClass.BASEMAP_STYLE] is the sub-case worth naming explicitly, because
     * `ProductionRentilePrivateKeyResolver` deliberately does *not* use Rentile's digest for the style —
     * it uses RenG's own canonical identity — so nothing in RenG's key derivation ever computes the
     * digest a style failure would carry. It is translatable all the same, and is translated here:
     * Rentile's `acquireRemoteStyle` reports exactly `sha256Hex(withRedactedAuthenticationQuery(url))`,
     * the same scheme as the seven engine-keyed classes, and [OperationRegistry] indexes style routes by
     * that digest even though the engine's raw store never sees a style. In RenG's own usage the case is
     * additionally unreachable: styles are handed to the engine as [StyleInput.Prefetched], so
     * `acquireRemoteStyle` never runs.
     */
    private fun translateForeignIdentity(descriptor: FailureDescriptor): FailureDescriptor {
        val diagnostic = descriptor.diagnostic ?: return descriptor
        val engineKey = diagnostic.resourceKey ?: return descriptor
        val resourceClass = engineKey.resourceClass ?: return descriptor
        val registry = activeOperation?.registry ?: return descriptor
        val rengKey = registry.renGResourceKeyForEngineDigest(resourceClass, engineKey.stableId)
            ?: return descriptor
        if (rengKey == engineKey) return descriptor

        return FailureDescriptor(
            code = descriptor.code,
            stage = descriptor.stage,
            diagnostic = failureContextDiagnostic(
                stage = diagnostic.stage,
                fieldName = DiagnosticField.RESOURCE,
                resourceClass = resourceClass,
                resourceKey = rengKey,
            ),
        )
    }

    /** One open preparation invocation: its access mode, its registry, and the firewall adapters over it. */
    private class FirewallOperation(
        val accessMode: RenGResourceAccessMode,
        val registry: OperationRegistry,
    ) {
        val transport: EngineResourceTransport = FirewallTransport(registry)
        val store: EngineRawResourceStore = FirewallStore(registry)
    }

    private inner class RoutedEngineTransport : EngineResourceTransport {
        override suspend fun execute(request: EngineTransportRequest): EngineTransportResponse {
            val operation = activeOperation ?: throw unplannedEngineExchangeFailure()
            return operation.transport.execute(request)
        }
    }

    private inner class RoutedEngineStore : EngineRawResourceStore {
        override suspend fun read(key: EngineRawResourceKey): EngineStoredRawResource? {
            val operation = activeOperation ?: throw unplannedEngineExchangeFailure()
            return operation.store.read(key)
        }

        override suspend fun write(key: EngineRawResourceKey, resource: EngineStoredRawResource) {
            val operation = activeOperation ?: throw unplannedEngineExchangeFailure()
            operation.store.write(key, resource)
        }

        override suspend fun remove(key: EngineRawResourceKey) {
            // Private and terminal whether or not an invocation is open (ADR 0016): RenG's own Store has
            // no remove operation at all, so there is nothing to forward and nothing to fail closed on.
            activeOperation?.store?.remove(key)
        }
    }

    private class CompiledStyleBinding(
        val styleKey: ResourceKey,
        val lease: Lease,
        val prepared: PreparedStyle,
    ) {
        val generation: ResidentGeneration get() = lease.generation
    }
}

/**
 * One prepared, fully-acquired basemap batch, plus the RenG tiles it was asked for. [close] is
 * idempotent, non-blocking, and non-throwing, because Rentile documents `PreparedBatch.close()` as
 * exactly that.
 */
internal class PreparedBasemapTiles(
    internal val batch: PreparedBatch,
    internal val style: PreparedStyle,
    internal val tiles: List<CanonicalBasemapTile>,
) : AutoCloseable {
    override fun close() {
        batch.close()
    }
}

/** One rendered ground tile: RenG's own identity, the encoded pixels, and the engine's own provenance. */
internal class RenderedBasemapTile(
    val key: ResourceKey,
    val tile: CanonicalBasemapTile,
    val pngBytes: ByteArray,
    val contentKey: String,
    val substitutions: List<ResourceSubstitution>,
)

/**
 * RenG's own canonical identity (ADR 0018) for one rendered basemap tile. See
 * [ResourceKeyDeriver.basemapTile] for why this is derived rather than taken from
 * `BasemapRasterizer.outputRequestKey`, and why it keys on the canonical tile rather than on an
 * unwrapped world-copy instance.
 */
internal fun basemapTileKey(
    styleDigest: String,
    tile: CanonicalBasemapTile,
    outputSize: OutputPixelSize,
    sha256: Sha256Function = PureKotlinSha256,
): ResourceKey = ResourceKeyDeriver(sha256).basemapTile(styleDigest, tile, outputSize).key

/**
 * The same identity for one **unwrapped draw instance**: the world copy is projected away before the key
 * is derived, so every instance of one canonical tile shares one rendered-tile resource and one engine
 * render. Deliberately drops [BasemapTileInstance.unwrappedX] and [BasemapTileInstance.instanceCopy] --
 * keying on either would re-render identical ground once per visible Mercator world copy, which is the
 * opposite of what `BasemapTileSelector` emitting `canonicalResources` separately from `instances` is for.
 */
internal fun basemapTileKey(
    styleDigest: String,
    instance: BasemapTileInstance,
    outputSize: OutputPixelSize,
    sha256: Sha256Function = PureKotlinSha256,
): ResourceKey = basemapTileKey(
    styleDigest = styleDigest,
    tile = CanonicalBasemapTile(
        lod = instance.lod,
        tileY = instance.tileY,
        canonicalX = instance.canonicalX,
    ),
    outputSize = outputSize,
    sha256 = sha256,
)

/**
 * An engine exchange with no preparation invocation open at all. Reported as the same sanitized
 * `AMBIGUOUS_RESOURCE_ROUTE` [OperationRegistry] uses for an unrecognised url or store key, because it is
 * the same fault seen one level earlier: an exchange RenG never planned.
 */
private fun unplannedEngineExchangeFailure(): RenGException = FailureDescriptor(
    code = RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
    stage = PipelineStage.RESOURCE_LOOKUP,
    diagnostic = failureContextDiagnostic(
        stage = PipelineStage.RESOURCE_LOOKUP,
        fieldName = DiagnosticField.RESOURCE,
    ),
).toException()

/** The failure for work asked of an already-closed host: the ground did not draw, and nothing more. */
private fun basemapRenderFailure(): FailureDescriptor =
    FailureDescriptor(code = RenGErrorCode.BASEMAP_RENDER_FAILED, stage = PipelineStage.BASEMAP_RENDER)
