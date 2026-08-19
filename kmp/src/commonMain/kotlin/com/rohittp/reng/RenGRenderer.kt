package com.rohittp.reng

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.basemapNotConfiguredDiagnostic
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.driver.PreparationDriver
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failure.toException
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.gl.CompositePipeline
import com.rohittp.reng.internal.gl.CompositePipelineResult
import com.rohittp.reng.internal.gl.GeometryPipeline
import com.rohittp.reng.internal.gl.GeometryPipelineResult
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.GlLifecycleDriver
import com.rohittp.reng.internal.gl.GlObjectRegistry
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
import com.rohittp.reng.internal.gl.deleteOffscreenSurface
import com.rohittp.reng.internal.gl.deleteStickerPipeline
import com.rohittp.reng.internal.gl.drawFrame
import com.rohittp.reng.internal.gl.offscreenSurfaceDescriptorFor
import com.rohittp.reng.internal.gl.uploadTexture
import com.rohittp.reng.internal.identity.CanonicalIdentityRegistry
import com.rohittp.reng.internal.identity.EncodedFramePlan
import com.rohittp.reng.internal.identity.FramePlanCanonicalEncoder
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
import com.rohittp.reng.internal.planning.FramePlanningCore
import com.rohittp.reng.internal.planning.FramePlanningOutcome
import com.rohittp.reng.internal.planning.FramePlanningRequest
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.StaticResourceReference
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import com.rohittp.reng.internal.renGFailure
import com.rohittp.reng.internal.resource.CanonicalIdentityRecord
import com.rohittp.reng.internal.resource.RentilePrivateKey
import com.rohittp.reng.internal.resource.RentilePrivateKeyResolver
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
 * A private-key derivation with no real Rentile integration to draw on yet — no cycle has wired one
 * (`CLAUDE.md`'s repository state: "There is still no ... Rentile acquisition"). Deterministic per
 * `(locator, resourceClass)` so the same external resource always derives the same key within one
 * process, which is all [FramePlanningCore]'s bookkeeping needs today. Every resource class this
 * cycle actually fetches (`STICKER_IMAGE`) goes straight through RenG's own configured [Transport]
 * and [Store], never through Rentile's shared cache, so no real cross-tenant firewall exists for it
 * to protect yet. Replacing this with a genuine derivation is the acquisition cycle's job, not this
 * one's.
 */
internal object DeterministicRentilePrivateKeyResolver : RentilePrivateKeyResolver {
    override fun resolve(
        locator: ResourceLocator,
        resourceClass: ResourceClass,
    ): RentilePrivateKey = RentilePrivateKey("${resourceClass.name}:${locator.value}")
}

/** One [Sticker]'s [Placement] paired with its decoded, not-yet-uploaded image. */
internal typealias PreparedSticker = Pair<Placement, DecodedImage>

/**
 * The concrete [PreparedFrame] this cycle's factory produces. Deliberately thin: it retains exactly
 * what [RenGRenderer.draw] needs to assemble a [Scene] — the raw [Camera] and [Geometry] values plus
 * each sticker's already-decoded image — and nothing GL-shaped, since GPU texture upload and
 * geometry-pipeline compilation both need a live render context and therefore happen in
 * [RenGRenderer.draw], not here.
 *
 * **Known gap, left to Task 9b by design.** [geometries] retains the exact [Geometry] instances
 * [FramePlan] carried, including their live `uniforms`/`textures` `Map` references — not a snapshot.
 * [Geometry]'s own KDoc documents the resulting no-mutation-after-construction contract: a consumer
 * mutating one of those maps between `prepare()` and a later `draw()` on this same frame would render
 * content that differs from what the frame's canonical identity already hashed. Closing that gap
 * requires snapshotting both maps here, which is explicitly Task 9b's job.
 */
internal class RenGPreparedFrame(
    internal val owner: RenGRenderer,
    override val frameIndex: Long,
    internal val camera: Camera,
    internal val drawBasemap: Boolean,
    stickers: List<PreparedSticker>,
    geometries: List<Geometry>,
) : PreparedFrame {
    private val stickerSnapshot: List<PreparedSticker> = ArrayList(stickers)
    private val geometrySnapshot: List<Geometry> = ArrayList(geometries)

    internal val stickers: List<PreparedSticker> get() = ArrayList(stickerSnapshot)
    internal val geometries: List<Geometry> get() = ArrayList(geometrySnapshot)

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
 * **Scope carried by this task (9a).** `createRenderer`, this class, lifecycle delegation to
 * [GlLifecycleDriver], `drawBasemap` warn-and-degrade, and wiring [SceneContent] into [drawFrame].
 *
 * **Scope explicitly left to Task 9b**, so a reviewer can reject one without the other: texture
 * caching and deletion through [GlObjectRegistry] (every sticker texture this class uploads in
 * [draw] is leaked — re-uploaded fresh on every draw call with nothing to delete it, exactly as the
 * plan's Task 9 description accepts for the MVP); [RenGPreparedFrame] snapshotting each [Geometry]'s
 * `uniforms`/`textures` maps rather than retaining the caller's own; sticker quad sizing from
 * [DecodedImage] dimensions (today's sticker quad is [com.rohittp.reng.internal.gl.STICKER_QUAD], a
 * fixed unit square); populating [SceneGeometry.consumerTextures] (this class fetches no consumer
 * geometry textures at all — [FramePlanningCore]'s own static-resource traversal does not yet walk
 * [Geometry.textures] either, so there is nothing here for Task 9b to have already half-wired); and
 * the untyped `error(...)` decision below (see [resolveFrameCamera]).
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
    private val programs: GlProgramCache,
    initialGlState: InternalGlState,
) : Renderer {

    private val preparationMutex: Mutex = Mutex()
    private val geometryKeyDeriver: ResourceKeyDeriver = ResourceKeyDeriver()
    private val geometryPipelines: MutableMap<ResourceKey, GeometryPipeline> = mutableMapOf()

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
        rentilePrivateKeyResolver = DeterministicRentilePrivateKeyResolver,
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
            // emitting exactly one External reference per sticker (and nothing for a Geometry's own
            // consumer textures -- Task 9b's job, not traversed by this pure core yet either), so
            // zipping the two lists back together by position is safe.
            val stickerImageReferences = planned.staticResourceTraversal
                .filterIsInstance<StaticResourceReference.External>()
                .filter { it.resourceClass == ResourceClass.STICKER_IMAGE }
            check(stickerImageReferences.size == plan.stickers.size) {
                "sticker image traversal must have exactly one entry per sticker"
            }
            val decodedByKey = acquireStickerImages(stickerImageReferences, accessMode)
            val stickers = plan.stickers.zip(stickerImageReferences) { sticker, reference ->
                sticker.placement to requireNotNull(decodedByKey[reference.resourceKey]) {
                    "a successful sticker acquisition must decode every traversed image"
                }
            }

            previousEncodedPlan = planned.encodedPlan
            previousSelectedLod = planned.spatialPlan.lodObservation.selectedLod

            return RenGPreparedFrame(
                owner = this,
                frameIndex = plan.frameIndex,
                camera = plan.camera,
                drawBasemap = plan.drawBasemap,
                stickers = stickers,
                geometries = plan.geometries,
            )
        } finally {
            preparationMutex.unlock()
        }
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
     * Fetches every traversed sticker image through [preparationDriver] — one [ResourceOccurrence]
     * per sticker, each its own owner so a merged route (two stickers sharing one image locator)
     * still resolves independently — then decodes each resulting resident generation's bytes through
     * Cycle C's PNG decoder. Returns nothing and performs no adapter call at all when [references] is
     * empty, which is what keeps `prepare()` on a sticker-free, basemap-unconfigured plan honestly
     * free of consumer exchange too.
     *
     * A [ResourceOperationOutcome.Cancelled] outcome — reached when one route's adapter call observes
     * its own cancellation while a sibling route is still active, exactly the multi-route scenario
     * Task 1 fixed in [com.rohittp.reng.internal.driver.ResourceActionExecutor] — is rethrown as a
     * genuine [CancellationException] rather than a [RenGException], consistent with keeping
     * cancellation unwrapped throughout this codebase. This function, called from [prepare], is what
     * makes that fixed `CancelRoute` path reachable through the public API for the first time: before
     * this factory existed, [preparationDriver]'s multi-route machinery had no caller at all.
     */
    private suspend fun acquireStickerImages(
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
        // its own). This class's own GL-object fields are outside both of those, so they are forgotten
        // here, unconditionally -- idempotent to call even when already forgotten.
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

        val sceneStickers = frame.stickers.map { (placement, image) ->
            SceneSticker(placement, uploadTexture(binding, image, TextureContent.IMAGE))
        }

        val sceneGeometries = ArrayList<SceneGeometry>(frame.geometries.size)
        for (geometry in frame.geometries) {
            val key = geometryKeyDeriver.geometryProgram(geometry.shaderPair).key
            val pipeline = geometryPipelines[key] ?: when (
                val result = createGeometryPipeline(binding, profile.dialect, programs, geometry.shaderPair)
            ) {
                is GeometryPipelineResult.Created -> result.pipeline.also { geometryPipelines[key] = it }
                is GeometryPipelineResult.Failed -> return result.failure
            }
            sceneGeometries += SceneGeometry(geometry = geometry, pipeline = pipeline)
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
     * Resolves [camera] against the fixed output pixel size a second time (the first was inside
     * `FramePlanningCore.plan()`'s own `planMercatorSpatial` call, at `prepare()` time). This mirrors
     * [SceneContent]'s own re-resolution of `Placement`/`Geometry` at draw time and accepts the same
     * redundant-but-safe reasoning: [resolveMercatorCamera] is pure and deterministic in its two
     * inputs, [camera] cannot have changed since `prepare()` validated it (it is `RenGPreparedFrame`'s
     * own immutable field, not the caller's live [FramePlan]), and `configuration.outputPixelSize` is
     * fixed for the renderer's whole lifetime (ADR 0012). A failure here is therefore a caller
     * contract violation rather than a legitimate runtime outcome — exactly [SceneContent]'s own
     * `requireResolvedAtDrawTime` reasoning — and is reported the same untyped way pending Task 9b's
     * decision on whether to convert every such site to a typed [RenGException].
     */
    private fun resolveFrameCamera(camera: Camera): ResolvedMercatorCamera =
        when (val outcome = resolveMercatorCamera(camera, configuration.outputPixelSize)) {
            is SpatialOutcome.Success -> outcome.value
            is SpatialOutcome.Failure -> error(
                "RenGRenderer received a Camera that failed to resolve at draw time; camera " +
                    "resolution must already have succeeded once during FRAME_PLANNING before a " +
                    "PreparedFrame is ever produced, so this indicates a caller contract violation",
            )
        }

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
                offscreenSurface = null
                compositePipeline = null
                stickerPipeline = null
                residentCache.closeAll()
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
