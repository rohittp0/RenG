package com.rohittp.reng

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.basemapNotConfiguredDiagnostic
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.driver.PreparationDriver
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failure.toException
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.firewall.BasemapEngineHost
import com.rohittp.reng.internal.firewall.ProductionRentilePrivateKeyResolver
import com.rohittp.reng.internal.gl.CompositePipeline
import com.rohittp.reng.internal.gl.CompositePipelineResult
import com.rohittp.reng.internal.gl.GeometryPipeline
import com.rohittp.reng.internal.gl.GeometryPipelineResult
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.GlLifecycleDriver
import com.rohittp.reng.internal.gl.GlObjectHandle
import com.rohittp.reng.internal.gl.GlObjectRegistry
import com.rohittp.reng.internal.gl.GlObjectType
import com.rohittp.reng.internal.gl.GlProgramCache
import com.rohittp.reng.internal.gl.OffscreenSurface
import com.rohittp.reng.internal.gl.OffscreenSurfaceResult
import com.rohittp.reng.internal.gl.RenderContextProfile
import com.rohittp.reng.internal.gl.Scene
import com.rohittp.reng.internal.gl.SceneContent
import com.rohittp.reng.internal.gl.SceneGeometry
import com.rohittp.reng.internal.gl.SceneSticker
import com.rohittp.reng.internal.gl.StickerPipeline
import com.rohittp.reng.internal.gl.StickerPipelineResult
import com.rohittp.reng.internal.gl.TextureContent
import com.rohittp.reng.internal.gl.createCompositePipeline
import com.rohittp.reng.internal.gl.createGeometryPipeline
import com.rohittp.reng.internal.gl.createOffscreenSurface
import com.rohittp.reng.internal.gl.createStickerPipeline
import com.rohittp.reng.internal.gl.deleteCompositePipeline
import com.rohittp.reng.internal.gl.deleteGeometryPipeline
import com.rohittp.reng.internal.gl.deleteGlObjects
import com.rohittp.reng.internal.gl.deleteOffscreenSurface
import com.rohittp.reng.internal.gl.deleteStickerPipeline
import com.rohittp.reng.internal.gl.drawFrame
import com.rohittp.reng.internal.gl.offscreenSurfaceDescriptorFor
import com.rohittp.reng.internal.gl.requireResolvedAtDrawTime
import com.rohittp.reng.internal.gl.uploadTexture
import com.rohittp.reng.internal.identity.CanonicalIdentityRegistry
import com.rohittp.reng.internal.identity.EncodedFramePlan
import com.rohittp.reng.internal.identity.FramePlanCanonicalEncoder
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.image.PngDecodeResult
import com.rohittp.reng.internal.image.decodePng
import com.rohittp.reng.internal.lifecycle.GpuLedger
import com.rohittp.reng.internal.lifecycle.PreparedFrameFact
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOperation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOutcome
import com.rohittp.reng.internal.lifecycle.RendererLifecycleSnapshot
import com.rohittp.reng.internal.lifecycle.RendererOwnerState
import com.rohittp.reng.internal.lifecycle.RenderTargetFact
import com.rohittp.reng.internal.maximumBytesFor
import com.rohittp.reng.internal.planning.FramePlanningCore
import com.rohittp.reng.internal.planning.FramePlanningOutcome
import com.rohittp.reng.internal.planning.FramePlanningRequest
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.StaticResourceReference
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import com.rohittp.reng.internal.renGFailure
import com.rohittp.reng.internal.resource.CanonicalIdentityRecord
import com.rohittp.reng.internal.resource.ResourceCommitBinding
import com.rohittp.reng.internal.resource.ResourceOccurrence
import com.rohittp.reng.internal.resource.ResourceOccurrenceId
import com.rohittp.reng.internal.resource.ResourceOperationDefinition
import com.rohittp.reng.internal.resource.ResourceOperationOutcome
import com.rohittp.reng.internal.resource.ResourceOwnerId
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.internal.resource.ResourceRouteRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

// This file lives in the `com.rohittp.reng` package rather than under `internal/` on disk because
// Renderer, PreparedFrame, and RenderTarget are all `sealed`, and Kotlin requires a sealed type's
// direct implementers to share its package (not merely its module). Every declaration below stays
// `internal` visibility regardless -- none of it appears in the ABI dump.

/**
 * One [Sticker]'s [Placement] paired with its decoded, not-yet-uploaded image and the [ResourceKey]
 * [FramePlanningCore]'s static traversal already derived for it — the same key
 * [RenGRenderer.performDraw]'s texture cache uses so a repeated draw of an unchanged sticker image
 * reuses its GL texture (Task 9b item 1) instead of re-uploading it every frame.
 */
internal class PreparedSticker(
    internal val placement: Placement,
    internal val resourceKey: ResourceKey,
    internal val image: DecodedImage,
)

/**
 * One [Geometry] consumer texture already fetched and decoded at `prepare()` time, paired with the
 * [ResourceKey] its sampler name resolved to — the same shape [PreparedSticker] establishes for a
 * sticker's image, reused here so [RenGRenderer.performDraw]'s texture cache covers both draw paths
 * identically.
 */
internal class PreparedGeometryTexture(
    internal val resourceKey: ResourceKey,
    internal val image: DecodedImage,
)

/**
 * One [Geometry] with both of Task 9b item 3's snapshots already taken at `prepare()` time:
 * [uniformsSnapshot] is a `.toMap()` copy of [Geometry.uniforms] and [consumerTextures] replaces
 * [Geometry.textures]' locators with their already-fetched, already-decoded content — never the
 * caller's own live `Map` references. [geometry] itself is retained only for its immutable
 * `topLeft`/`bottomRight`/`shaderPair` fields, which carry no such live-reference hazard.
 */
internal class PreparedGeometry(
    internal val geometry: Geometry,
    internal val uniformsSnapshot: Map<String, ShaderValue>,
    internal val consumerTextures: Map<String, PreparedGeometryTexture>,
)

/**
 * The concrete [PreparedFrame] this cycle's factory produces. Deliberately thin: it retains exactly
 * what [RenGRenderer.draw] needs to assemble a [Scene] — the raw [Camera] value, each sticker's
 * already-decoded image, and each geometry's already-snapshotted uniforms/textures — and nothing
 * GL-shaped, since GPU texture upload and geometry-pipeline compilation both need a live render
 * context and therefore happen in [RenGRenderer.draw], not here.
 *
 * **Task 9b item 3, closed here.** [geometries] no longer retains the exact [Geometry] instances
 * [FramePlan] carried with their live `uniforms`/`textures` `Map` references — every [PreparedGeometry]
 * in [geometries] carries a fixed `.toMap()` snapshot of `uniforms` and fully resolved, already-decoded
 * `consumerTextures`, both taken once, synchronously, inside `RenGRenderer.prepare()`. A consumer
 * mutating their own `uniforms`/`textures` map after `prepare()` returns can no longer change what a
 * later `draw()` on this same frame renders, closing the divergence [Geometry]'s own KDoc used to
 * document as an open gap.
 */
internal class RenGPreparedFrame(
    internal val owner: RenGRenderer,
    override val frameIndex: Long,
    internal val camera: Camera,
    internal val drawBasemap: Boolean,
    stickers: List<PreparedSticker>,
    geometries: List<PreparedGeometry>,
) : PreparedFrame {
    private val stickerSnapshot: List<PreparedSticker> = ArrayList(stickers)
    private val geometrySnapshot: List<PreparedGeometry> = ArrayList(geometries)

    internal val stickers: List<PreparedSticker> get() = ArrayList(stickerSnapshot)
    internal val geometries: List<PreparedGeometry> get() = ArrayList(geometrySnapshot)

    internal var closed: Boolean = false
        private set

    internal fun markClosed() {
        closed = true
    }

    override fun close() {
        owner.closePreparedFrame(this)
    }
}

/** The concrete [RenderTarget] [RenGRenderer.mintRenderTarget] produces. */
internal class RenGRenderTarget(
    internal val owner: RenGRenderer,
    override val framebufferName: FramebufferName,
    internal val mintedAtGeneration: Long,
) : RenderTarget

/** Every GL object [createInternalGlState] allocates, bundled so the factory and re-adoption share one shape. */
internal class InternalGlState(
    val offscreenSurface: OffscreenSurface,
    val compositePipeline: CompositePipeline,
    val stickerPipeline: StickerPipeline,
)

internal sealed interface InternalGlStateResult {
    data class Created(val state: InternalGlState) : InternalGlStateResult

    data class Failed(val failure: FailureDescriptor) : InternalGlStateResult
}

/**
 * Allocates the offscreen surface plus RenG's own composite and sticker pipelines — every piece of
 * GL state the renderer holds independent of any one frame's content.
 *
 * Follows [createOffscreenSurface]'s own leak-discipline shape: every allocation is attempted
 * unconditionally, and every result is checked at one point at the end, rather than checking after
 * each step and returning early. If any allocation failed, every allocation that DID succeed is
 * deleted before the first failure is reported, so a partial construction never leaks a GL object.
 */
internal fun createInternalGlState(
    binding: GlBinding,
    profile: RenderContextProfile,
    programs: GlProgramCache,
    outputPixelSize: OutputPixelSize,
): InternalGlStateResult {
    val deriver = ResourceKeyDeriver()
    val surfaceDescriptor = offscreenSurfaceDescriptorFor(outputPixelSize)
    val surfaceKey = deriver.offscreenSurface(surfaceDescriptor).key

    val surfaceResult = createOffscreenSurface(binding, profile, surfaceKey, surfaceDescriptor)
    val compositeResult = createCompositePipeline(binding, profile.dialect, programs, deriver)
    val stickerResult = createStickerPipeline(binding, profile.dialect, programs, deriver)

    val surface = (surfaceResult as? OffscreenSurfaceResult.Created)?.surface
    val composite = (compositeResult as? CompositePipelineResult.Created)?.pipeline
    val sticker = (stickerResult as? StickerPipelineResult.Created)?.pipeline

    if (surface != null && composite != null && sticker != null) {
        return InternalGlStateResult.Created(InternalGlState(surface, composite, sticker))
    }

    surface?.let { deleteOffscreenSurface(binding, it) }
    composite?.let { deleteCompositePipeline(binding, programs, it) }
    sticker?.let { deleteStickerPipeline(binding, programs, it) }

    val failure = (surfaceResult as? OffscreenSurfaceResult.Failed)?.failure
        ?: (compositeResult as? CompositePipelineResult.Failed)?.failure
        ?: (stickerResult as? StickerPipelineResult.Failed)?.failure
        ?: error("createInternalGlState: no result failed despite an incomplete allocation set")
    return InternalGlStateResult.Failed(failure)
}

/**
 * The one concrete [Renderer]. Wires Cycle C's resource driver (frame planning plus static resource
 * acquisition), Cycle D's GL foundation (lifecycle state machine, offscreen surface, composite draw),
 * and this cycle's scene draw ([SceneContent]) into the renderer the public factory hands out.
 *
 * **Scope carried by Task 9a.** `createRenderer`, this class, lifecycle delegation to
 * [GlLifecycleDriver], `drawBasemap` warn-and-degrade, and wiring [SceneContent] into [drawFrame].
 *
 * **Scope carried by Task 9b, closing every gap 9a left**: texture caching and deletion through
 * [glObjectRegistry] (a repeated draw of an unchanged sticker or consumer texture now reuses its GL
 * texture — see [cachedTexture] — and [close] deletes every cached one); [RenGPreparedFrame]
 * snapshotting each [Geometry]'s `uniforms`/`textures` maps in [prepare] rather than retaining the
 * caller's own (see [PreparedGeometry]); sticker quad sizing from each [DecodedImage]'s own
 * dimensions (see [SceneSticker]'s `imageWidthPixels`/`imageHeightPixels`); populating
 * [SceneGeometry.consumerTextures] by fetching and decoding a [Geometry]'s consumer textures in
 * [prepare] alongside its stickers (see [acquireExternalImages] and [geometryTextureReference]); and
 * converting the untyped `error(...)` a draw-time resolution failure used to throw into a typed
 * [RenGException] (see [resolveFrameCamera] and `internal.gl.requireResolvedAtDrawTime`).
 *
 * **Why `preparationActive` on [GlLifecycleDriver]'s own snapshot cannot serialize [prepare] calls
 * across coroutines.** [GlLifecycleDriver.run] is a single synchronous call: `BeginPreparation`
 * flips `preparationActive` true, invokes this class's executor callback synchronously, and flips it
 * back false before `run` returns — all within that one call. A second, later
 * `driver.run(BeginPreparation, ...)` from a concurrent coroutine sees `preparationActive` already
 * false again, so it observes no contention no matter how long the first [prepare] call's actual
 * suspend work (resource fetch, decode) takes. [preparationMutex] is this class's own, genuinely
 * cross-suspend guard for that; `driver.run(BeginPreparation, ...)` is still called on every
 * [prepare] because it is the thing that reports `RENDERER_CLOSED` correctly.
 *
 * **Why [cancelPreparations] calls [preparationDriver] directly rather than relying solely on
 * [GlLifecycleDriver]'s own `CancelPreparations` operation.** For the same reason: that operation
 * only issues a preparation-cancellation action when `preparationActive` is observed true, which —
 * per the note above — it never is by the time a separate `cancelPreparations()` call reaches it.
 * `driver.run(CancelPreparations, ...)` is still called for state-machine consistency, but the actual
 * cancellation is [preparationDriver]'s own `cancel()`, called unconditionally alongside it. This is
 * also what first makes the [ResourceOperationOutcome.Cancelled] path — and the `CancelRoute` handling
 * it depends on — reachable through the public API: see [acquireStickerImages]'s KDoc.
 */
internal class RenGRenderer(
    private val configuration: RendererConfiguration,
    private val binding: GlBinding,
    private val driver: GlLifecycleDriver,
    private val preparationDriver: PreparationDriver,
    private val residentCache: ResidentCache,
    private val basemapEngineHost: BasemapEngineHost,
    private val programs: GlProgramCache,
    private val glObjectRegistry: GlObjectRegistry,
    initialGlState: InternalGlState,
) : Renderer {

    private val preparationMutex: Mutex = Mutex()
    private val geometryKeyDeriver: ResourceKeyDeriver = ResourceKeyDeriver()
    private val geometryPipelines: MutableMap<ResourceKey, GeometryPipeline> = mutableMapOf()

    /**
     * Reproduces Rentile's actual `sha256Hex(withRedactedAuthenticationQuery(url))` key for the seven
     * classes Rentile itself fetches and keys, and RenG's own canonical identity for the four it does
     * not (basemap task 16). This is a pure, stateless function of `(locator, resourceClass)`, so one
     * shared instance is correct for every call this renderer makes across every frame preparation.
     */
    private val rentilePrivateKeyResolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256)

    private var offscreenSurface: OffscreenSurface? = initialGlState.offscreenSurface
    private var compositePipeline: CompositePipeline? = initialGlState.compositePipeline
    private var stickerPipeline: StickerPipeline? = initialGlState.stickerPipeline

    private var identityRegistry: CanonicalIdentityRegistry = CanonicalIdentityRegistry()
    private var framePlanningCore: FramePlanningCore = newFramePlanningCore(identityRegistry)
    private var previousEncodedPlan: EncodedFramePlan? = null
    private var previousSelectedLod: Int? = null

    /** Once per renderer, never per frame — see the design spec's `drawBasemap` decision. */
    private var basemapWarningEmitted: Boolean = false

    private fun newFramePlanningCore(registry: CanonicalIdentityRegistry): FramePlanningCore = FramePlanningCore(
        frameEncoder = FramePlanCanonicalEncoder(),
        frameIdentityRegistry = registry,
        resourceKeyDeriver = ResourceKeyDeriver(),
        rentilePrivateKeyResolver = rentilePrivateKeyResolver,
    )

    // ---- Preparation --------------------------------------------------------------------------

    override suspend fun prepare(plan: FramePlan, accessMode: ResourceAccessMode): PreparedFrame {
        if (!preparationMutex.tryLock()) {
            throw renGFailure(RenGErrorCode.PREPARATION_IN_PROGRESS, PipelineStage.FRAME_PREPARATION)
        }
        try {
            val beginOutcome = driver.run(RendererLifecycleOperation.BeginPreparation) { null }
            if (beginOutcome is RendererLifecycleOutcome.Failed) throw beginOutcome.failure.toException()

            val planningOutcome = framePlanningCore.plan(
                FramePlanningRequest(
                    plan = plan,
                    outputPixelSize = configuration.outputPixelSize,
                    basemapStyle = configuration.basemapStyle,
                    resourceLimits = configuration.resourceLimits,
                    maximumBasemapTileInstances = configuration.maximumBasemapTileInstances,
                    previousPlan = previousEncodedPlan,
                    previousSelectedLod = previousSelectedLod,
                ),
            )
            val planned = when (planningOutcome) {
                is FramePlanningOutcome.Success -> planningOutcome.planned
                is FramePlanningOutcome.Failure -> throw planningOutcome.failure.toException()
            }

            // FramePlanningCore.staticResourceTraversal() walks plan.stickersForCore() in order,
            // emitting exactly one External reference per sticker, so zipping the two lists back
            // together by position is safe.
            val stickerImageReferences = planned.staticResourceTraversal
                .filterIsInstance<StaticResourceReference.External>()
                .filter { it.resourceClass == ResourceClass.STICKER_IMAGE }
            check(stickerImageReferences.size == plan.stickers.size) {
                "sticker image traversal must have exactly one entry per sticker"
            }

            // Task 9b item 4: a Geometry's consumer textures are fetched and decoded here too, one
            // geometry at a time, sorted by sampler name -- the exact same order
            // FramePlanningCore.staticResourceTraversal() now traverses them in, computed
            // independently through geometryKeyDeriver rather than re-parsed out of the traversal
            // list. Both derivations are the same pure function of (resourceClass, locator), so they
            // always agree -- the same "recompute, don't consume the traversal" pattern this
            // function already uses for a geometry's PROGRAM key (see the `draw` section below).
            val geometryTextureReferencesByGeometry: List<List<Pair<String, StaticResourceReference.External>>> =
                plan.geometries.map { geometry ->
                    geometry.textures.entries.sortedBy { it.key }.map { (name, locator) ->
                        name to geometryTextureReference(locator)
                    }
                }

            val decodedByKey = acquireExternalImages(
                stickerImageReferences + geometryTextureReferencesByGeometry.flatten().map { it.second },
                accessMode,
            )

            val stickers = plan.stickers.zip(stickerImageReferences) { sticker, reference ->
                PreparedSticker(
                    placement = sticker.placement,
                    resourceKey = reference.resourceKey,
                    image = requireNotNull(decodedByKey[reference.resourceKey]) {
                        "a successful sticker acquisition must decode every traversed image"
                    },
                )
            }

            // Task 9b item 3: both of Geometry's live Map references are snapshotted right here,
            // synchronously, before this suspend function ever returns -- uniforms via .toMap(),
            // textures by replacing every ResourceLocator with its already-fetched, already-decoded
            // content. Neither snapshot can be affected by a consumer mutating their own map after
            // prepare() returns.
            val geometries = plan.geometries.mapIndexed { index, geometry ->
                val consumerTextures = geometryTextureReferencesByGeometry[index].associate { (name, reference) ->
                    name to PreparedGeometryTexture(
                        resourceKey = reference.resourceKey,
                        image = requireNotNull(decodedByKey[reference.resourceKey]) {
                            "a successful geometry-texture acquisition must decode every traversed image"
                        },
                    )
                }
                PreparedGeometry(
                    geometry = geometry,
                    uniformsSnapshot = geometry.uniforms.toMap(),
                    consumerTextures = consumerTextures,
                )
            }

            previousEncodedPlan = planned.encodedPlan
            previousSelectedLod = planned.spatialPlan.lodObservation.selectedLod

            return RenGPreparedFrame(
                owner = this,
                frameIndex = plan.frameIndex,
                camera = plan.camera,
                drawBasemap = plan.drawBasemap,
                stickers = stickers,
                geometries = geometries,
            )
        } finally {
            preparationMutex.unlock()
        }
    }

    /**
     * Derives the same [StaticResourceReference.External] shape
     * `FramePlanningCore.staticResourceTraversal()`'s own `external(...)` helper produces for a
     * geometry consumer texture, independently — both are the same pure function of
     * `(ResourceClass.MODEL_TEXTURE, locator)`, so they always agree without this function needing
     * to parse the traversal list back apart by position (which — unlike a sticker, where exactly one
     * traversal entry exists per plan sticker — would require distinguishing a geometry's own texture
     * entries from a [Model]'s, since both traverse under the same reused [ResourceClass.MODEL_TEXTURE]).
     */
    private fun geometryTextureReference(locator: ResourceLocator): StaticResourceReference.External {
        val derived = geometryKeyDeriver.external(ResourceClass.MODEL_TEXTURE, locator)
        return StaticResourceReference.External(
            resourceClass = ResourceClass.MODEL_TEXTURE,
            locator = locator,
            maximumResponseBytes = configuration.resourceLimits.maximumBytesFor(ResourceClass.MODEL_TEXTURE),
            resourceKey = derived.key,
            rawKey = requireNotNull(derived.rawKey),
            privateRentileKey = rentilePrivateKeyResolver.resolve(locator, ResourceClass.MODEL_TEXTURE),
            canonicalIdentity = derived.identity,
        )
    }

    override suspend fun prepareBatch(plans: List<FramePlan>, accessMode: ResourceAccessMode): List<PreparedFrame> {
        if (plans.size > configuration.maximumPreparationBatchSize) {
            throw RenGException(
                code = RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
                stage = PipelineStage.FRAME_PLANNING,
                diagnostics = listOf(
                    failureContextDiagnostic(
                        stage = PipelineStage.FRAME_PLANNING,
                        fieldName = DiagnosticField.PLANS,
                        limit = configuration.maximumPreparationBatchSize.toLong(),
                        actual = plans.size.toLong(),
                    ),
                ),
            )
        }
        return plans.map { prepare(it, accessMode) }
    }

    /**
     * Fetches every traversed external image — a sticker's, or (Task 9b) a geometry consumer
     * texture's — through [preparationDriver], one [ResourceOccurrence] per [references] entry, each
     * its own owner so a merged route (two entries sharing one locator) still resolves independently
     * — then decodes each resulting resident generation's bytes through Cycle C's PNG decoder.
     * Returns nothing and performs no adapter call at all when [references] is empty, which is what
     * keeps `prepare()` on a plan with no stickers, no geometry consumer textures, and no configured
     * basemap honestly free of consumer exchange too.
     *
     * A [ResourceOperationOutcome.Cancelled] outcome — reached when one route's adapter call observes
     * its own cancellation while a sibling route is still active, exactly the multi-route scenario
     * Task 1 fixed in [com.rohittp.reng.internal.driver.ResourceActionExecutor] — is rethrown as a
     * genuine [CancellationException] rather than a [RenGException], consistent with keeping
     * cancellation unwrapped throughout this codebase. This function, called from [prepare], is what
     * makes that fixed `CancelRoute` path reachable through the public API for the first time: before
     * this factory existed, [preparationDriver]'s multi-route machinery had no caller at all.
     */
    private suspend fun acquireExternalImages(
        references: List<StaticResourceReference.External>,
        accessMode: ResourceAccessMode,
    ): Map<ResourceKey, DecodedImage> {
        if (references.isEmpty()) return emptyMap()

        val occurrences = references.mapIndexed { index, reference ->
            // ResourceOccurrenceId and ResourceOwnerId both require a strictly positive value, so
            // this is 1-based rather than the list's own 0-based index.
            val ordinal = (index + 1).toLong()
            ResourceOccurrence(
                id = ResourceOccurrenceId(ordinal),
                ownerId = ResourceOwnerId(ordinal),
                registration = ResourceRouteRegistration(
                    route = ResourceRouteKey(
                        accessMode = accessMode,
                        locator = reference.locator,
                        resourceClass = reference.resourceClass,
                        maximumResponseBytes = reference.maximumResponseBytes,
                    ),
                    resourceKey = reference.resourceKey,
                    rawKey = reference.rawKey,
                    privateRentileKey = reference.privateRentileKey,
                    canonicalBytes = reference.canonicalIdentity.canonicalBytes,
                ),
                discoveryRequired = false,
                commitBinding = ResourceCommitBinding.Single,
            )
        }
        val definition = ResourceOperationDefinition(
            maximumConcurrentRoutes = minOf(configuration.maximumConcurrentResourceOperations, occurrences.size),
            staticOccurrences = occurrences,
            resourceIdentities = occurrences.map {
                CanonicalIdentityRecord(it.registration.resourceKey, it.registration.canonicalBytes)
            },
        )

        when (val outcome = preparationDriver.run(definition)) {
            is ResourceOperationOutcome.Success -> Unit
            is ResourceOperationOutcome.Failure -> throw outcome.failure.toException()
            is ResourceOperationOutcome.Cancelled ->
                throw CancellationException("resource preparation observed a route cancellation")
        }

        return references.associate { reference ->
            val stored = residentCache.current(reference.resourceKey)?.stored
                ?: error("a successful resource operation must leave its content resident")
            val image = when (
                val decoded = decodePng(stored.bytes, configuration.resourceLimits.maximumDecodedImageBytes)
            ) {
                is PngDecodeResult.Success -> decoded.image
                else -> throw RenGException(
                    code = RenGErrorCode.RESOURCE_DECODE_FAILED,
                    stage = PipelineStage.RESOURCE_DECODING,
                    diagnostics = listOf(
                        failureContextDiagnostic(
                            stage = PipelineStage.RESOURCE_DECODING,
                            fieldName = DiagnosticField.RESOURCE,
                            resourceClass = reference.resourceClass,
                            resourceKey = reference.resourceKey,
                        ),
                    ),
                )
            }
            reference.resourceKey to image
        }
    }

    override suspend fun cancelPreparations() {
        val outcome = driver.run(RendererLifecycleOperation.CancelPreparations) { null }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
        preparationDriver.cancel()
    }

    override fun clearFrameHistory() {
        val outcome = driver.run(RendererLifecycleOperation.ClearFrameHistory) { operation ->
            if (operation == RendererLifecycleOperation.ClearFrameHistory) {
                previousEncodedPlan = null
                previousSelectedLod = null
                identityRegistry = CanonicalIdentityRegistry()
                framePlanningCore = newFramePlanningCore(identityRegistry)
                null
            } else {
                unexpectedOperation(operation)
            }
        }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
    }

    // ---- Resource lifecycle ---------------------------------------------------------------------

    override fun queryResources(selector: ResourceSelector): ResourceReport {
        val outcome = driver.run(RendererLifecycleOperation.QueryResources(selector)) { null }
        return when (outcome) {
            RendererLifecycleOutcome.EmptyResourceResult -> emptyResourceReport()
            RendererLifecycleOutcome.Succeeded, RendererLifecycleOutcome.NoOp -> residentCache.report(selector)
            is RendererLifecycleOutcome.Failed -> throw outcome.failure.toException()
        }
    }

    override fun freeResources(selector: ResourceSelector): ResourceFreeResult {
        var result: ResourceFreeResult? = null
        val outcome = driver.run(RendererLifecycleOperation.FreeResources(selector)) { operation ->
            if (operation is RendererLifecycleOperation.FreeResources) {
                result = residentCache.free(operation.selector)
                null
            } else {
                unexpectedOperation(operation)
            }
        }
        return when (outcome) {
            RendererLifecycleOutcome.EmptyResourceResult, RendererLifecycleOutcome.NoOp ->
                ResourceFreeResult(matchedKeys = 0, fullyFreedKeys = 0, deferredKeys = 0, alreadyFreeKeys = 0)
            RendererLifecycleOutcome.Succeeded ->
                requireNotNull(result) { "a successful free must have run its permitted operation" }
            is RendererLifecycleOutcome.Failed -> throw outcome.failure.toException()
        }
    }

    override fun notifyGpuObjectsGone() {
        // NotifyGpuObjectsGone never reaches the permitted-operation executor (see GlLifecycleDriver /
        // RendererLifecycleStateMachine: it routes through AwaitRenderCallQuiescence instead, and
        // GlLifecycleDriver.forgetWithoutDeleting() already handles the registry and program cache on
        // its own). Because `registry` there is this exact `glObjectRegistry` instance (constructed
        // once, in RendererFactory, and threaded into both GlLifecycleDriver and this class), every
        // cachedTexture()-registered sticker/geometry-consumer texture is ALSO forgotten there,
        // without an extra line here -- forgotten, never deleted (ADR 0007/0015): the GL handles are
        // already gone with the lost context, so there is nothing left to validly delete, and the
        // decoded CPU-side content those handles cached stays resident and valid regardless. This
        // class's own GL-object fields are outside both of those, so they are forgotten here,
        // unconditionally -- idempotent to call even when already forgotten.
        driver.run(RendererLifecycleOperation.NotifyGpuObjectsGone) { null }
        offscreenSurface = null
        compositePipeline = null
        stickerPipeline = null
        geometryPipelines.clear()
    }

    override fun adoptCurrentRenderContext() {
        val outcome = driver.run(RendererLifecycleOperation.AdoptCurrentRenderContext) { null }
        when (outcome) {
            is RendererLifecycleOutcome.Failed -> throw outcome.failure.toException()
            RendererLifecycleOutcome.Succeeded -> {
                val profile = requireNotNull(driver.profile) {
                    "a successful adoption must have recorded a profile"
                }
                when (
                    val recreated = createInternalGlState(binding, profile, programs, configuration.outputPixelSize)
                ) {
                    is InternalGlStateResult.Created -> {
                        offscreenSurface = recreated.state.offscreenSurface
                        compositePipeline = recreated.state.compositePipeline
                        stickerPipeline = recreated.state.stickerPipeline
                    }

                    is InternalGlStateResult.Failed -> {
                        // Nothing was actually recreated: push the machine back to
                        // AWAITING_CONTEXT_ADOPTION rather than leaving it LIVE with no offscreen
                        // surface, then report the real failure.
                        driver.run(RendererLifecycleOperation.NotifyGpuObjectsGone) { null }
                        throw recreated.failure.toException()
                    }
                }
            }

            RendererLifecycleOutcome.NoOp, RendererLifecycleOutcome.EmptyResourceResult -> Unit
        }
    }

    override fun mintRenderTarget(framebufferName: FramebufferName): RenderTarget {
        val outcome = driver.run(RendererLifecycleOperation.MintRenderTarget(framebufferName)) { null }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
        return RenGRenderTarget(this, framebufferName, driver.snapshot.contextGeneration)
    }

    // ---- Drawing ----------------------------------------------------------------------------------

    override fun draw(preparedFrame: PreparedFrame, renderTarget: RenderTarget) {
        val frameFact = when {
            preparedFrame !is RenGPreparedFrame || preparedFrame.owner !== this -> PreparedFrameFact.Foreign
            preparedFrame.closed -> PreparedFrameFact.OwnedClosed
            else -> PreparedFrameFact.OwnedOpen
        }
        val targetFact = when {
            renderTarget !is RenGRenderTarget || renderTarget.owner !== this -> RenderTargetFact.Foreign
            renderTarget.mintedAtGeneration != driver.snapshot.contextGeneration -> RenderTargetFact.Stale
            else -> RenderTargetFact.OwnedCurrent(renderTarget.framebufferName)
        }

        val outcome = driver.run(RendererLifecycleOperation.Draw(frameFact, targetFact)) { operation ->
            val drawOperation = operation as? RendererLifecycleOperation.Draw ?: unexpectedOperation(operation)
            val ownedTarget = drawOperation.target as RenderTargetFact.OwnedCurrent
            performDraw(preparedFrame as RenGPreparedFrame, ownedTarget.framebufferName)
        }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
    }

    /**
     * Runs as the `Draw` operation's permitted-operation executor, which only ever runs once the
     * state machine has already proved the exact context is current and the target framebuffer is
     * complete. Everything here is therefore real GL work: uploading each sticker's decoded image,
     * compiling (or reusing) each distinct geometry program, and drawing the assembled [Scene]
     * through [drawFrame].
     */
    private fun performDraw(frame: RenGPreparedFrame, framebufferName: FramebufferName): FailureDescriptor? {
        if (frame.drawBasemap && configuration.basemapStyle == null && !basemapWarningEmitted) {
            basemapWarningEmitted = true
            configuration.diagnosticSink.emit(basemapNotConfiguredDiagnostic())
        }

        val profile = requireNotNull(driver.profile) { "drawing requires an adopted profile" }
        val surface = requireNotNull(offscreenSurface) { "drawing requires an offscreen surface" }
        val composite = requireNotNull(compositePipeline) { "drawing requires the composite pipeline" }
        val sticker = requireNotNull(stickerPipeline) { "drawing requires the sticker pipeline" }

        val resolvedCamera = resolveFrameCamera(frame.camera)

        val sceneStickers = frame.stickers.map { preparedSticker ->
            val texture = cachedTexture(preparedSticker.resourceKey) {
                uploadTexture(binding, preparedSticker.image, TextureContent.IMAGE)
            }
            SceneSticker(
                placement = preparedSticker.placement,
                texture = texture,
                imageWidthPixels = preparedSticker.image.width,
                imageHeightPixels = preparedSticker.image.height,
            )
        }

        val sceneGeometries = ArrayList<SceneGeometry>(frame.geometries.size)
        for (preparedGeometry in frame.geometries) {
            val geometry = preparedGeometry.geometry
            val key = geometryKeyDeriver.geometryProgram(geometry.shaderPair).key
            val pipeline = geometryPipelines[key] ?: when (
                val result = createGeometryPipeline(binding, profile.dialect, programs, geometry.shaderPair)
            ) {
                is GeometryPipelineResult.Created -> result.pipeline.also { geometryPipelines[key] = it }
                is GeometryPipelineResult.Failed -> return result.failure
            }
            val consumerTextures = preparedGeometry.consumerTextures.mapValues { (_, texture) ->
                cachedTexture(texture.resourceKey) { uploadTexture(binding, texture.image, TextureContent.DATA) }
            }
            sceneGeometries += SceneGeometry(
                geometry = geometry,
                pipeline = pipeline,
                consumerUniforms = preparedGeometry.uniformsSnapshot,
                consumerTextures = consumerTextures,
            )
        }

        val scene = Scene(
            outputPixelSize = configuration.outputPixelSize,
            frameIndex = frame.frameIndex,
            stickers = sceneStickers,
            geometries = sceneGeometries,
        )
        val content = SceneContent(resolvedCamera, scene, sticker)

        return drawFrame(
            binding = binding,
            profile = profile,
            surface = surface,
            composite = composite,
            targetFramebuffer = framebufferName,
            content = content,
        )
    }

    /**
     * Task 9b item 1: the texture-lifetime fix. Looks [key] up in [glObjectRegistry] first — the
     * SAME registry [driver]'s own `forgetWithoutDeleting()` clears on context loss and on
     * [notifyGpuObjectsGone] (ADR 0007/0015: forgotten, not deleted, since the GL handles are already
     * gone and there is nothing valid left to delete) — and only calls [upload] on a genuine cache
     * miss, registering the freshly uploaded name under [key] so the NEXT draw of the same
     * [ResourceKey] (an unchanged sticker image or geometry consumer texture) reuses it instead of
     * calling [uploadTexture] (and therefore `genTextures`) again. [close] deletes every handle this
     * ever registers; nothing here calls a GL delete directly, mirroring how [geometryPipelines] is
     * cached by [ResourceKey] and deleted only in [close] / forgotten only in [notifyGpuObjectsGone].
     */
    private fun cachedTexture(key: ResourceKey, upload: () -> Int): Int {
        val existing = glObjectRegistry.handles(key).firstOrNull { it.type == GlObjectType.TEXTURE }
        if (existing != null) return existing.name
        val name = upload()
        glObjectRegistry.register(key, listOf(GlObjectHandle(GlObjectType.TEXTURE, name)))
        return name
    }

    /**
     * Resolves [camera] against the fixed output pixel size a second time (the first was inside
     * `FramePlanningCore.plan()`'s own `planMercatorSpatial` call, at `prepare()` time). This mirrors
     * [SceneContent]'s own re-resolution of `Placement`/`Geometry` at draw time and accepts the same
     * redundant-but-safe reasoning: [resolveMercatorCamera] is pure and deterministic in its two
     * inputs, [camera] cannot have changed since `prepare()` validated it (it is `RenGPreparedFrame`'s
     * own immutable field, not the caller's live [FramePlan]), and `configuration.outputPixelSize` is
     * fixed for the renderer's whole lifetime (ADR 0012). A failure here is therefore a caller
     * contract violation rather than a legitimate runtime outcome — exactly
     * `internal.gl.requireResolvedAtDrawTime`'s own reasoning — and (Task 9b) is now reported through
     * that exact same shared function, as a typed [RenGException] (`INVALID_VALUE` at `DRAW`)
     * rather than the bare `error(...)` this used to throw directly.
     */
    private fun resolveFrameCamera(camera: Camera): ResolvedMercatorCamera =
        resolveMercatorCamera(camera, configuration.outputPixelSize).requireResolvedAtDrawTime()

    // ---- Prepared-frame lifecycle -----------------------------------------------------------------

    internal fun closePreparedFrame(frame: RenGPreparedFrame) {
        val fact = if (frame.closed) PreparedFrameFact.OwnedClosed else PreparedFrameFact.OwnedOpen
        val outcome = driver.run(RendererLifecycleOperation.ClosePreparedFrame(fact)) { operation ->
            if (operation is RendererLifecycleOperation.ClosePreparedFrame) {
                frame.markClosed()
                null
            } else {
                unexpectedOperation(operation)
            }
        }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
    }

    // ---- Close --------------------------------------------------------------------------------

    override fun close() {
        val outcome = driver.run(RendererLifecycleOperation.CloseRenderer) { operation ->
            if (operation == RendererLifecycleOperation.CloseRenderer) {
                offscreenSurface?.let { deleteOffscreenSurface(binding, it) }
                compositePipeline?.let { deleteCompositePipeline(binding, programs, it) }
                stickerPipeline?.let { deleteStickerPipeline(binding, programs, it) }
                geometryPipelines.values.forEach { deleteGeometryPipeline(binding, programs, it) }
                geometryPipelines.clear()
                // Task 9b item 1: every sticker/geometry-consumer texture cachedTexture() has ever
                // registered gets deleted here, exactly once, on close -- the same ADR 0007/0015
                // "close() deletes" half geometryPipelines already establishes above. The registry's
                // own `forgetEverything()` still runs afterwards (GlLifecycleDriver.applyTerminal,
                // once this operation succeeds and the machine reaches CLOSED), which is harmless
                // here since every handle is already gone by then.
                glObjectRegistry.liveKeys().forEach { key -> deleteGlObjects(binding, glObjectRegistry.handles(key)) }
                offscreenSurface = null
                compositePipeline = null
                stickerPipeline = null
                residentCache.closeAll()
                // The renderer owns exactly one Rentile engine (ADR 0016), so closing the renderer closes
                // it. Its close() is idempotent and, unlike everything above it here, not GL-scoped -- so
                // it neither needs nor consults the exact current context (ADRs 0007/0015).
                basemapEngineHost.close()
                null
            } else {
                unexpectedOperation(operation)
            }
        }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
    }
}

private fun emptyResourceReport(): ResourceReport = ResourceReport(
    entries = emptyList(),
    totals = ResourceUsage(rawBytes = 0L, decodedCpuBytes = 0L, knownGpuBytes = 0L, hasUnknownGpuBytes = false),
)

private fun unexpectedOperation(operation: RendererLifecycleOperation): Nothing =
    error("RenGRenderer's executor received an operation its caller did not request: $operation")
