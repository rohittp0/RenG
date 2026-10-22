package com.rohittp.reng

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.basemap.BasemapStyleManifest
import com.rohittp.reng.internal.basemap.BasemapStyleManifestOutcome
import com.rohittp.reng.internal.basemap.completeBasemapStyleManifest
import com.rohittp.reng.internal.basemap.tileTimeRoutes
import com.rohittp.reng.internal.basemapNotConfiguredDiagnostic
import com.rohittp.reng.internal.cache.Lease
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.cache.ResidentGeneration
import com.rohittp.reng.internal.cache.ResidentGenerationId
import com.rohittp.reng.internal.driver.PreparationDriver
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failure.toException
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.firewall.AcquiredDemTile
import com.rohittp.reng.internal.firewall.AcquiredLabelCandidates
import com.rohittp.reng.internal.firewall.BasemapEngineHost
import com.rohittp.reng.internal.firewall.BasemapTilePixels
import com.rohittp.reng.internal.firewall.ProductionRentilePrivateKeyResolver
import com.rohittp.reng.internal.firewall.RenderedBasemapTile
import com.rohittp.reng.internal.firewall.SpriteAtlasManifest
import com.rohittp.reng.internal.firewall.TerrainAcquisition
import com.rohittp.reng.internal.firewall.TerrainAcquisitionOutcome
import com.rohittp.reng.internal.firewall.reportLabelContentExclusions
import com.rohittp.reng.internal.firewall.reportTerrainDegradation
import com.rohittp.reng.internal.gl.BACKDROP_SAMPLER_STATE
import com.rohittp.reng.internal.gl.BackdropPipeline
import com.rohittp.reng.internal.gl.BloomFarBandStrategy
import com.rohittp.reng.internal.gl.BloomResourceManager
import com.rohittp.reng.internal.gl.CompositePipeline
import com.rohittp.reng.internal.gl.ConsumerBackdropPipelineResult
import com.rohittp.reng.internal.gl.ConsumerPipelineCache
import com.rohittp.reng.internal.gl.ConsumerPipelineLease
import com.rohittp.reng.internal.gl.ContentTextureCache
import com.rohittp.reng.internal.gl.GeometryPipelineResult
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.GlLifecycleDriver
import com.rohittp.reng.internal.gl.GlObjectHandle
import com.rohittp.reng.internal.gl.GlObjectRegistry
import com.rohittp.reng.internal.gl.GlObjectType
import com.rohittp.reng.internal.gl.GlProgramCache
import com.rohittp.reng.internal.gl.GlobeGroundPipeline
import com.rohittp.reng.internal.gl.GlobeGroundPipelineResult
import com.rohittp.reng.internal.gl.GpuAllocationLease
import com.rohittp.reng.internal.gl.GpuGenerationRetirement
import com.rohittp.reng.internal.gl.GpuResourceIdentity
import com.rohittp.reng.internal.gl.GpuSubresource
import com.rohittp.reng.internal.gl.GpuTextureResidency
import com.rohittp.reng.internal.gl.GroundPipeline
import com.rohittp.reng.internal.gl.IconBatch
import com.rohittp.reng.internal.gl.IconPipeline
import com.rohittp.reng.internal.gl.InternalPipelineRole
import com.rohittp.reng.internal.gl.LabelBatch
import com.rohittp.reng.internal.gl.LabelPipeline
import com.rohittp.reng.internal.gl.ModelPipeline
import com.rohittp.reng.internal.gl.ModelPipelineResult
import com.rohittp.reng.internal.gl.ModelShaderVariant
import com.rohittp.reng.internal.gl.OffscreenSurface
import com.rohittp.reng.internal.gl.RenderContextProfile
import com.rohittp.reng.internal.gl.ResolvedBackdrop
import com.rohittp.reng.internal.gl.Scene
import com.rohittp.reng.internal.gl.SceneContent
import com.rohittp.reng.internal.gl.SceneGeometry
import com.rohittp.reng.internal.gl.SceneGroundTile
import com.rohittp.reng.internal.gl.SceneModel
import com.rohittp.reng.internal.gl.SceneSticker
import com.rohittp.reng.internal.gl.SceneTerrain
import com.rohittp.reng.internal.gl.SceneTileDem
import com.rohittp.reng.internal.gl.StickerPipeline
import com.rohittp.reng.internal.gl.TextureContent
import com.rohittp.reng.internal.gl.TextureLease
import com.rohittp.reng.internal.gl.TextureSamplerState
import com.rohittp.reng.internal.gl.UploadedPrimitive
import com.rohittp.reng.internal.gl.allModelShaderVariants
import com.rohittp.reng.internal.gl.composeBackdropInverseViewProjection
import com.rohittp.reng.internal.gl.createConsumerBackdropPipeline
import com.rohittp.reng.internal.gl.createGeometryPipeline
import com.rohittp.reng.internal.gl.createGlobeGroundPipeline
import com.rohittp.reng.internal.gl.createModelPipeline
import com.rohittp.reng.internal.gl.defaultSamplerStateFor
import com.rohittp.reng.internal.gl.deleteBackdropPipeline
import com.rohittp.reng.internal.gl.deleteCompositePipeline
import com.rohittp.reng.internal.gl.deleteGlObjects
import com.rohittp.reng.internal.gl.deleteGlobeGroundPipeline
import com.rohittp.reng.internal.gl.deleteGroundPipeline
import com.rohittp.reng.internal.gl.deleteIconPipeline
import com.rohittp.reng.internal.gl.deleteLabelPipeline
import com.rohittp.reng.internal.gl.deleteModelPipeline
import com.rohittp.reng.internal.gl.deleteOffscreenSurface
import com.rohittp.reng.internal.gl.deleteStickerPipeline
import com.rohittp.reng.internal.gl.demDecodeCoefficients
import com.rohittp.reng.internal.gl.drawFrame
import com.rohittp.reng.internal.gl.jointMatricesForSkin
import com.rohittp.reng.internal.gl.modelPrimitiveAllocationBytes
import com.rohittp.reng.internal.gl.requireResolvedAtDrawTime
import com.rohittp.reng.internal.gl.resolvedBackdropFor
import com.rohittp.reng.internal.gl.uploadDemTexture
import com.rohittp.reng.internal.gl.uploadGlyphAtlas
import com.rohittp.reng.internal.gl.uploadModelPrimitive
import com.rohittp.reng.internal.gl.uploadPremultipliedTexture
import com.rohittp.reng.internal.gl.uploadSpriteAtlas
import com.rohittp.reng.internal.gl.uploadTexture
import com.rohittp.reng.internal.gl.withCapturedGlState
import com.rohittp.reng.internal.groundPresentedProvisionallyDiagnostic
import com.rohittp.reng.internal.identity.AcceleratedSha256
import com.rohittp.reng.internal.identity.CanonicalIdentityRegistry
import com.rohittp.reng.internal.identity.DerivedResourceKey
import com.rohittp.reng.internal.identity.EncodedFramePlan
import com.rohittp.reng.internal.identity.FramePlanCanonicalEncoder
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.identity.RouteMemo
import com.rohittp.reng.internal.identity.ShaderPairMemo
import com.rohittp.reng.internal.identity.shaderPairMemoCapacity
import com.rohittp.reng.internal.image.CompressedImage
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.image.Etc2ContainerResult
import com.rohittp.reng.internal.image.PngDecodeResult
import com.rohittp.reng.internal.image.PngPrescan
import com.rohittp.reng.internal.image.decodePng
import com.rohittp.reng.internal.image.isEtc2Container
import com.rohittp.reng.internal.image.parseEtc2Container
import com.rohittp.reng.internal.image.prescanPng
import com.rohittp.reng.internal.label.LabelFadeState
import com.rohittp.reng.internal.label.LabelGroundElevation
import com.rohittp.reng.internal.label.advanceLabelFade
import com.rohittp.reng.internal.label.placeLabels
import com.rohittp.reng.internal.lifecycle.PreparedFrameFact
import com.rohittp.reng.internal.lifecycle.RenderTargetFact
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOperation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOutcome
import com.rohittp.reng.internal.math.DoubleMatrix4
import com.rohittp.reng.internal.maximumBytesFor
import com.rohittp.reng.internal.metrics.EngineMetricRecorder
import com.rohittp.reng.internal.model.AnimationResolution
import com.rohittp.reng.internal.model.ModelDecodeResult
import com.rohittp.reng.internal.model.composeGlobalTransforms
import com.rohittp.reng.internal.model.decodeModel
import com.rohittp.reng.internal.model.resolveAnimationSelectors
import com.rohittp.reng.internal.model.sampleAnimationTracks
import com.rohittp.reng.internal.planning.BasemapTileInstance
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.planning.FramePlanningCore
import com.rohittp.reng.internal.planning.FramePlanningOutcome
import com.rohittp.reng.internal.planning.FramePlanningRequest
import com.rohittp.reng.internal.planning.StaticResourceReference
import com.rohittp.reng.internal.planning.observeLabelTiles
import com.rohittp.reng.internal.preparation.GroundTileProvisioner
import com.rohittp.reng.internal.preparation.PreparationSessionCoordinator
import com.rohittp.reng.internal.preparation.PreparedFrameCpuBudget
import com.rohittp.reng.internal.preparation.PreparedFrameOwnership
import com.rohittp.reng.internal.preparation.RawTileBudget
import com.rohittp.reng.internal.preparation.SaturatingByteCounter
import com.rohittp.reng.internal.preparation.buildResourceOperationDefinition
import com.rohittp.reng.internal.preparation.preparedBasemapAndTerrainCpuBytes
import com.rohittp.reng.internal.preparation.preparedLabelGeometryCpuBytes
import com.rohittp.reng.internal.preparation.projectedModelPoseCpuBytes
import com.rohittp.reng.internal.projection.ResolvedFrameCamera
import com.rohittp.reng.internal.projection.ResolvedGlobeCamera
import com.rohittp.reng.internal.projection.resolveGlobeCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import com.rohittp.reng.internal.readahead.ReadAheadCoordinator
import com.rohittp.reng.internal.renGFailure
import com.rohittp.reng.internal.residentGpuTexturesOverBudgetDiagnostic
import com.rohittp.reng.internal.resource.RentilePrivateKey
import com.rohittp.reng.internal.resource.ResourceOperationDefinition
import com.rohittp.reng.internal.resource.ResourceOperationOutcome
import com.rohittp.reng.internal.shader.ShaderProfileMemo
import com.rohittp.reng.internal.shader.shaderProfileMemoCapacity
import com.rohittp.reng.internal.terrain.DemTileCoordinate
import com.rohittp.reng.internal.terrain.GroundSurface
import com.rohittp.reng.internal.terrain.TerrainGranularityInputs
import com.rohittp.reng.internal.terrain.demTileWindowFor
import com.rohittp.reng.internal.terrain.frameGroundCellsPerTileSide
import com.rohittp.reng.internal.terrain.planPaddedDemTexture
import com.rohittp.reng.internal.terrain.terrainCellsPerTileSide
import com.rohittp.reng.internal.thread.CallingThread
import com.rohittp.reng.internal.thread.currentCallingThread
import com.rohittp.reng.internal.thread.isCurrentThread
import com.rohittp.rentile.PreparedStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

// This file lives in the `com.rohittp.reng` package rather than under `internal/` on disk because
// Renderer, PreparedFrame, and RenderTarget are all `sealed`, and Kotlin requires a sealed type's
// direct implementers to share its package (not merely its module). Every declaration below stays
// `internal` visibility regardless -- none of it appears in the ABI dump.

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
 * **One preparation admission spans the whole suspending invocation.** [preparationMutex] rejects a
 * second public preparation across suspension. `BeginPreparation` sets the lifecycle fact until the
 * explicit `EndPreparation` that follows either publication or complete rollback. In between,
 * [PreparationSessionCoordinator] publishes one cancellable outer worker spanning validation,
 * planning, acquisition, frame construction and commit. [cancelPreparations] cancels and joins that
 * published session; [PreparationDriver] remains the resource-operation driver, not the authority for
 * public preparation lifetime.
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
    private val metricRecorder: EngineMetricRecorder,
    initialGlState: InternalGlState,
) : Renderer {

    /** Ownership identity without a back-reference from frames or targets to this renderer. */
    private val ownerIdentity: Any = Any()
    private val frameOwnership = PreparedFrameOwnership(
        configuration = configuration,
        residentCache = residentCache,
        ownerIdentity = ownerIdentity,
    )
    private val preparationMutex: Mutex = Mutex()
    private val preparationSession = PreparationSessionCoordinator()
    private val rawTileBudget = RawTileBudget(
        configuration.resourceLimits.maximumInFlightRawBasemapTileBytes,
    )
    private val preparedFrameCpuBudget = PreparedFrameCpuBudget(
        configuration.resourceLimits.maximumInFlightPreparedFrameCpuBytes,
    )
    internal val inFlightPreparedFrameCpuBytes: Long
        get() = preparedFrameCpuBudget.outstandingBytes()

    /** Raw tile bytes open frames hold against ADR 0044's budget; tests compare it to the frames' own pixels. */
    internal val inFlightRawTileBytes: Long
        get() = rawTileBudget.outstandingBytes()

    /**
     * The canonical form and derived keys of every consumer shader pair this renderer has seen, shared
     * by the validation, planning, encoding and draw paths that each used to derive them again.
     */
    private val shaderPairMemo = ShaderPairMemo(shaderPairMemoCapacity(configuration.maximumConsumerPipelinesPerFrame))

    /**
     * Every external route's derived key and Rentile private key this renderer has met, shared by the
     * planning, draw-side and resolver derivations that each derived them again on every `prepare`.
     * Owned here rather than by a [FramePlanningCore], which `clearFrameHistory` rebuilds.
     */
    private val externalKeyMemo = RouteMemo<DerivedResourceKey>()
    private val privateKeyMemo = RouteMemo<RentilePrivateKey>()

    // Every planSpatial this renderer runs -- prepare's, through each FramePlanningCore, and readAhead's
    // -- shares it, so a consumer's shader sources are scanned for their profile directive once rather
    // than once a frame. Locked, because readAhead may plan on another thread.
    private val shaderProfileMemo =
        ShaderProfileMemo(shaderProfileMemoCapacity(configuration.maximumConsumerPipelinesPerFrame))
    private val geometryKeyDeriver: ResourceKeyDeriver =
        ResourceKeyDeriver(shaderPairs = shaderPairMemo, externalKeys = externalKeyMemo)

    /**
     * Cycle C's task 20, reached at last: the frame's terrain descriptor and its DEM ring, acquired
     * across the firewall.
     *
     * Constructed here rather than injected because it holds nothing -- it is a translation layer
     * over [basemapEngineHost], which is the thing with state -- and because a renderer that never
     * meets a `terrain` block never calls it.
     */
    private val terrainAcquisition: TerrainAcquisition = TerrainAcquisition(basemapEngineHost)
    private val consumerPipelines = ConsumerPipelineCache(configuration.maximumCachedConsumerPipelines)
    private val contentTextures = ContentTextureCache(glObjectRegistry = glObjectRegistry)

    /**
     * Every model program, compiled together on the first frame that carries a [Model] and never
     * before, keyed by the variant that selects it.
     *
     * **All eight at once, rather than only the variants this frame needs.**
     * `internal.gl.modelShaderVariantFor` is the one authority on which of the eight draws a primitive,
     * and its three answers depend on the primitive's *uploaded* attribute set, its joint palette and
     * its alpha mode. Asking that question here would mean a second copy of that rule outside the file
     * that owns it — the same duplication `Scene.mapOrder` exists to have removed — so this pays for
     * eight programs instead. **Not at setup**, though: a renderer that never draws a model must not
     * pay sixteen shader compilations for one, and `createModelPipeline` refuses a context whose
     * `GL_MAX_UNIFORM_BLOCK_SIZE` is below the guaranteed 16384, which would otherwise turn a context
     * that can draw everything else into a renderer that cannot be constructed at all.
     */
    private val modelPipelines: MutableMap<ModelShaderVariant, ModelPipeline> = mutableMapOf()

    /**
     * Reproduces Rentile's actual `sha256Hex(withRedactedAuthenticationQuery(url))` key for the seven
     * classes Rentile itself fetches and keys, and RenG's own canonical identity for the four it does
     * not (basemap task 16). This is a pure, stateless function of `(locator, resourceClass)`, so one
     * shared instance is correct for every call this renderer makes across every frame preparation.
     */
    private val rentilePrivateKeyResolver = ProductionRentilePrivateKeyResolver(
        sha256 = AcceleratedSha256,
        ownIdentityDeriver = geometryKeyDeriver,
        privateKeys = privateKeyMemo,
    )

    private var offscreenSurface: OffscreenSurface? = initialGlState.offscreenSurface
    private var compositePipeline: CompositePipeline? = initialGlState.compositePipeline
    private var stickerPipeline: StickerPipeline? = initialGlState.stickerPipeline
    private var backdropPipeline: BackdropPipeline? = initialGlState.backdropPipeline
    private var groundPipeline: GroundPipeline? = initialGlState.groundPipeline

    /**
     * ADR 0034's phase 5, allocated at setup with the composite, sticker and ground pipelines rather
     * than lazily with the model ones. There is exactly one label program with no variants, it needs
     * nothing of the context the other three do not, and `FramePlan.drawLabels` defaults to `true` —
     * so none of the three reasons `modelPipelines` is lazy (sixteen compilations, a
     * `GL_MAX_UNIFORM_BLOCK_SIZE` refusal, and a renderer that may never draw one) applies here.
     */
    private var labelPipeline: LabelPipeline? = initialGlState.labelPipeline

    /**
     * Phase 5's first half, allocated beside the label pipeline and on the same terms: one program,
     * no variants, and a `FramePlan.drawLabels` that defaults to `true`. A style declaring no sprite
     * pair simply never hands it a batch.
     */
    private var iconPipeline: IconPipeline? = initialGlState.iconPipeline

    /**
     * ADR 0038's globe ground, allocated lazily with the model pipelines rather than eagerly with the
     * five above.
     *
     * `FramePlan.projectionMode` defaults to `MERCATOR`, so a renderer that never draws a globe must
     * never compile the globe ground program — the same reason `modelPipelines` is lazy, and the
     * opposite of `labelPipeline`'s (`drawLabels` defaults to `true`). It is created on the first
     * globe frame that actually carries ground tiles, forgotten on a lost context exactly as the
     * model pipelines are, and deleted by `close()`.
     */
    private var globeGroundPipeline: GlobeGroundPipeline? = null

    private val bloomResourceManager = BloomResourceManager(configuration = configuration, programs = programs)

    /**
     * How a far band reaches its level (ADR 0085). Internal: [BloomFarBandStrategy.COARSE_REDRAW]
     * exists only so RenG's own tests can measure what the reach image buys.
     */
    internal var bloomFarBandStrategy: BloomFarBandStrategy = BloomFarBandStrategy.REACH_IMAGE

    private var identityRegistry: CanonicalIdentityRegistry = CanonicalIdentityRegistry()
    private var framePlanningCore: FramePlanningCore = newFramePlanningCore(identityRegistry)
    private var previousFrameIndex: Long? = null
    private var previousEncodedPlan: EncodedFramePlan? = null
    private var previousSelectedLod: Int? = null

    /**
     * ADR 0035's label fade: the fourth member of Frame History, on exactly [previousSelectedLod]'s
     * terms — read by preparation, fed to a pure function as inert data, committed only after every
     * fallible step of a `prepare` has succeeded, and cleared by the public `clearFrameHistory()`.
     * `prepareBatch` needs no rule of its own because it is `plans.map { prepare(it, accessMode) }`,
     * so a fade commits at the same boundary a LOD does.
     *
     * **What it is not is a cache.** Every other cross-frame mechanism in this renderer — decoded
     * images, uploaded textures, parsed GLBs, compiled shaders, resident tiles — changes how fast a
     * frame is produced and never how it looks. This one changes pixels: two frames carrying the same
     * plan can paint a label at different opacities, which is the whole point of it, and
     * `clearFrameHistory()` is the public call that takes a consumer back to a render that is a pure
     * function of the plan.
     */
    private var previousLabelFade: LabelFadeState = LabelFadeState.EMPTY

    /**
     * The Label handover this renderer is holding, and the exact opposite kind of thing to
     * [previousLabelFade] above: **a cache**, changing how fast a frame is produced and never how it
     * looks. It is therefore not a fifth member of Frame History and is deliberately not cleared by
     * `clearFrameHistory()` — see [labelHandover] for the whole decision.
     *
     * One entry, on [BasemapEngineHost.compiledStyle]'s own terms rather than as a map: a camera that
     * moves changes its tile set every frame and would evict any bounded cache continuously, while a
     * camera that sits still — the case measured at 514–520 ms of `prepare()` per frame, ~85% of it,
     * for a batch that was byte-identical all three times — needs exactly one. A second entry would buy
     * only an oscillation between two tile sets, which no consumer produces and no measurement asked
     * for.
     */
    private var retainedLabelHandover: RetainedLabelHandover? = null

    /**
     * The label tile set the last labelled frame settled on (ADR 0070), so the next one can be handed
     * the same list and hit [retainedLabelHandover] instead of re-acquiring every label tile and Glyph
     * Range. Committed after a successful preparation, exactly as the selected LOD and the fade are.
     */
    private var previousLabelTiles: List<CanonicalBasemapTile>? = null

    /**
     * The glyph atlas of [retainedLabelHandover]'s batch, decoded once rather than on every frame that
     * keeps a label.
     *
     * **Keyed by the atlas's own content, not by the handover's request key**, and the two are genuinely
     * different questions. `ResourceKeyDeriver.glyphAtlas` names the atlas by the digest Rentile packed
     * it under, so two tile sets whose label text happens to need the identical glyphs share this entry
     * even though their handover keys differ — a pan along a repetitive coastline is exactly that shape.
     * Keying it off the handover instead would throw the decode away on every invalidation and re-do
     * 122 ms of work (measured, 16 ranges, 6.88 MB) to arrive at the same pixels.
     *
     * Bounded by `ResourceLimits.maximumDecodedImageBytes` — the same ceiling `decodeGlyphAtlas` already
     * enforces — because that is what a `DecodedImage` of an atlas can weigh at all. One is held, for
     * the same reason one handover is.
     */
    private var retainedGlyphAtlas: RetainedGlyphAtlas? = null

    /**
     * The sprite atlas of [retainedLabelHandover]'s manifest, decoded once rather than on every frame
     * that draws an icon.
     *
     * **Memoised against the manifest *instance*, which is a stronger statement than it looks.** The
     * manifest is only ever built by `spritePairJointManifest`, once per proxied sprite pair, and is
     * then carried by [RetainedLabelHandover] for as long as that handover answers; so an identical
     * reference means literally the same bytes, decoded from the same fetch. A fresh manifest over
     * byte-identical bytes misses this and pays one decode, but still derives the same
     * `ResourceKeyDeriver.spriteAtlas` key and therefore re-uses the texture already on the GPU.
     *
     * The alternative -- keying it by the derived key, as [retainedGlyphAtlas] is -- would have to
     * derive that key, and deriving it hashes the whole encoded atlas. Doing that once per frame to
     * avoid a decode once per sprite pair is the wrong way round.
     */
    private var retainedSpriteAtlas: RetainedSpriteAtlas? = null

    /** Once per renderer, never per frame — see the design spec's `drawBasemap` decision. */
    private var basemapWarningEmitted: Boolean = false

    /**
     * The thread this renderer was created on, or last adopted a context on (ADR 0056).
     *
     * A `var` because `adoptCurrentRenderContext` moves it: adoption is where "a new exact identity
     * and context generation" is established (ADR 0015), and the calling thread is part of that
     * identity.
     */
    private var contextThread: CallingThread = currentCallingThread()

    /**
     * Refuses a GL-touching call that arrived on a thread nothing declared (ADR 0056).
     *
     * RenG cannot observe which context is current — `RendererFactory` explains at length why not —
     * but it can observe the thread, and a context current on one thread is not current on another.
     * This is a necessary condition, never a sufficient one: passing it proves nothing about the
     * context, and failing it proves the context cannot be right.
     *
     * Raised before any state change, so the call can be retried from the correct thread, or after
     * `adoptCurrentRenderContext()` on the new one.
     */
    private fun requireContextThread(stage: PipelineStage) {
        if (contextThread.isCurrentThread()) return
        throw renGFailure(RenGErrorCode.RENDER_CONTEXT_THREAD_CHANGED, stage)
    }

    /**
     * The engine gate lane the most recent tile render was forwarded under (ADR 0053).
     *
     * A narrow window onto the host's own observation rather than exposing the host itself: the
     * engine reports a priority nowhere, so this is the only point at which the thread-through from
     * `prepare`'s parameter to the engine call can be checked at all.
     */
    internal val lastForwardedRenderPriority: RenGRenderPriority?
        get() = basemapEngineHost.lastForwardedRenderPriority

    /** The lane the read-ahead worker's most recent draw was forwarded under (ADR 0077). For tests. */
    internal val lastReadAheadRenderPriority: RenGRenderPriority?
        get() = basemapEngineHost.lastReadAheadRenderPriority

    /**
     * The compiled basemap style of the **most recently prepared frame**, or `null` when that frame drew
     * neither the basemap nor its labels (or when no preparation has succeeded yet). Since E-labels task
     * 8b a `drawBasemap = false, drawLabels = true` frame holds its style here too: labels come from the
     * style, so that pairing acquires and compiles one. Cleared rather than left standing on a frame that
     * asked for neither, so that a reader never has to cross-check the plan to know whether
     * this belongs to the frame in front of it — "the last style compiled" is a subtly different claim
     * and would be a trap for Cycle E-C3, which consumes this. Nothing draws with it: the ground and
     * the Label handover both take the style from the acquisition that produced it rather than from
     * here, for exactly the reason the paragraph above gives.
     *
     * Read back from [basemapEngineHost] rather than taken from the driver's compile action, because a
     * `RESIDENT`-provenance frame emits no compile action at all.
     */
    internal var preparedBasemapStyle: PreparedStyle? = null
        private set

    // ---- Read-ahead (ADR 0075) ----------------------------------------------------------------

    private val readAheadCoordinator: ReadAheadCoordinator = ReadAheadCoordinator(
        configuration = configuration,
        basemapEngineHost = basemapEngineHost,
        glObjectRegistry = glObjectRegistry,
        metricRecorder = metricRecorder,
        shaderProfileMemo = shaderProfileMemo,
        checkedRawTileBytes = { tileCount -> groundTileProvisioner.checkedRawTileBytes(tileCount) },
    )

    private val groundTileProvisioner: GroundTileProvisioner = GroundTileProvisioner(
        configuration = configuration,
        basemapEngineHost = basemapEngineHost,
        glObjectRegistry = glObjectRegistry,
        metricRecorder = metricRecorder,
        rawTileBudget = rawTileBudget,
        renderedTileCache = readAheadCoordinator.renderedTileCache,
    )

    internal val readAheadWorkerStarted: Boolean
        get() = readAheadCoordinator.readAheadWorkerStarted

    override fun readAhead(plans: List<FramePlan>) {
        readAheadCoordinator.readAhead(plans)
    }

    internal val renderedTileCacheTiles: Int
        get() = readAheadCoordinator.renderedTileCacheTiles
    internal val renderedTileCacheBytes: Long
        get() = readAheadCoordinator.renderedTileCacheBytes

    override suspend fun awaitReadAheadIdle() {
        readAheadCoordinator.awaitReadAheadIdle()
    }

    private fun newFramePlanningCore(registry: CanonicalIdentityRegistry): FramePlanningCore = FramePlanningCore(
        frameEncoder = FramePlanCanonicalEncoder(shaderPairs = shaderPairMemo, programKeys = geometryKeyDeriver),
        frameIdentityRegistry = registry,
        resourceKeyDeriver = ResourceKeyDeriver(shaderPairs = shaderPairMemo, externalKeys = externalKeyMemo),
        rentilePrivateKeyResolver = rentilePrivateKeyResolver,
        shaderProfileMemo = shaderProfileMemo,
    )

    // ---- Preparation --------------------------------------------------------------------------

    override suspend fun prepare(
        plan: FramePlan,
        accessMode: ResourceAccessMode,
        priority: RenGRenderPriority,
    ): PreparedFrame = prepareInvocation(listOf(plan), accessMode, priority).single()

    override suspend fun prepareBatch(
        plans: List<FramePlan>,
        accessMode: ResourceAccessMode,
        priority: RenGRenderPriority,
    ): List<PreparedFrame> = prepareInvocation(ArrayList(plans), accessMode, priority)

    private suspend fun prepareInvocation(
        plans: List<FramePlan>,
        accessMode: ResourceAccessMode,
        priority: RenGRenderPriority,
    ): List<PreparedFrame> {
        if (!preparationMutex.tryLock()) {
            throw renGFailure(RenGErrorCode.PREPARATION_IN_PROGRESS, PipelineStage.FRAME_PREPARATION)
        }
        var lifecycleBegun = false
        try {
            return preparationSession.run(
                onUndelivered = ::rollbackPreparedInvocation,
                onSettled = {
                    if (lifecycleBegun) {
                        val outcome = driver.run(RendererLifecycleOperation.EndPreparation) { null }
                        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
                    }
                },
            ) {
                val beginOutcome = driver.run(RendererLifecycleOperation.BeginPreparation) { null }
                if (beginOutcome is RendererLifecycleOutcome.Failed) throw beginOutcome.failure.toException()
                lifecycleBegun = true

                validatePreparationPlans(plans)
                val initialHistory = preparationHistory()
                val initialStyle = preparedBasemapStyle
                val initialReadAheadSnapshot = readAheadCoordinator.readAheadSnapshot
                var planningPlan = initialHistory.encodedPlan
                var planningLod = initialHistory.selectedLod
                val plannedItems = plans.map { plan ->
                    currentCoroutineContext().ensureActive()
                    val planningOutcome = framePlanningCore.plan(
                        FramePlanningRequest(
                            plan = plan,
                            outputPixelSize = configuration.outputPixelSize,
                            basemapStyle = configuration.basemapStyle,
                            resourceLimits = configuration.resourceLimits,
                            maximumBasemapTileInstances = configuration.maximumBasemapTileInstances,
                            previousPlan = planningPlan,
                            previousSelectedLod = planningLod,
                        ),
                    )
                    val planned = when (planningOutcome) {
                        is FramePlanningOutcome.Success -> planningOutcome.planned
                        is FramePlanningOutcome.Failure -> throw planningOutcome.failure.toException()
                    }
                    planningPlan = planned.encodedPlan
                    planningLod = planned.spatialPlan.lodObservation.selectedLod
                    PlannedPreparationItem(plan, planned)
                }

                basemapEngineHost.withSharedOperation(accessMode) {
                    groundTileProvisioner.batchRenderedTiles = mutableMapOf()
                    // Capacity is reserved before the first frame owns anything, so publishing a
                    // completed item cannot itself become the OOM point which loses that ownership.
                    val provisional = ArrayList<RenGPreparedFrame>(plannedItems.size)
                    var history = initialHistory
                    var finalStyle: PreparedStyle? = initialStyle
                    var finalManifest: BasemapStyleManifest? = null
                    var committed = false
                    try {
                        for (item in plannedItems) {
                            currentCoroutineContext().ensureActive()
                            val prepared = prepareItem(item, history, accessMode, priority)
                            try {
                                provisional.add(prepared.frame)
                            } catch (failure: Throwable) {
                                frameOwnership.disposePreparedFrame(prepared.frame)
                                throw failure
                            }
                            history = prepared.nextHistory
                            finalStyle = prepared.preparedStyle
                            finalManifest = prepared.styleManifest
                        }

                        // Allocate the public snapshot and the coordinator's rollback record before
                        // mutating renderer history. Everything after commit is allocation-free.
                        val delivery = PreparedInvocationDelivery(
                            frames = ArrayList(provisional),
                            initialHistory = initialHistory,
                            initialStyle = initialStyle,
                            initialReadAheadSnapshot = initialReadAheadSnapshot,
                        )
                        currentCoroutineContext().ensureActive()
                        commitPreparationHistory(history)
                        preparedBasemapStyle = finalStyle
                        readAheadCoordinator.readAheadSnapshot =
                            readAheadCoordinator.readAheadSnapshotOf(finalStyle, finalManifest, accessMode, history.selectedLod)
                        committed = true
                        currentCoroutineContext().ensureActive()
                        delivery
                    } catch (failure: Throwable) {
                        if (committed) {
                            commitPreparationHistory(initialHistory)
                            preparedBasemapStyle = initialStyle
                            readAheadCoordinator.readAheadSnapshot = initialReadAheadSnapshot
                        }
                        disposePreparedFrames(provisional)
                        throw failure
                    } finally {
                        groundTileProvisioner.batchRenderedTiles = null
                    }
                }
            }.frames
        } finally {
            preparationMutex.unlock()
        }
    }

    private fun validatePreparationPlans(plans: List<FramePlan>) {
        if (plans.isEmpty()) {
            throw RenGException(
                code = RenGErrorCode.INVALID_VALUE,
                stage = PipelineStage.FRAME_PLANNING,
                diagnostics = listOf(
                    failureContextDiagnostic(
                        stage = PipelineStage.FRAME_PLANNING,
                        fieldName = DiagnosticField.PLANS,
                    ),
                ),
            )
        }
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
        var previous = previousFrameIndex
        plans.forEach { plan ->
            val consumerPipelineKeys = buildSet {
                plan.geometries.forEach { add(geometryKeyDeriver.geometryProgram(it.shaderPair).key) }
                (plan.backdrop as? Backdrop.Shader)?.let { backdrop ->
                    add(
                        geometryKeyDeriver
                            .internalPipeline(InternalPipelineRole.CONSUMER_BACKDROP, backdrop.shaderPair)
                            .key,
                    )
                }
            }
            if (consumerPipelineKeys.size > configuration.maximumConsumerPipelinesPerFrame) {
                throw renGFailure(
                    RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    PipelineStage.FRAME_PLANNING,
                    failureContextDiagnostic(
                        stage = PipelineStage.FRAME_PLANNING,
                        fieldName = DiagnosticField.SHADER_PAIR,
                        limit = configuration.maximumConsumerPipelinesPerFrame.toLong(),
                        actual = consumerPipelineKeys.size.toLong(),
                    ),
                )
            }
            val bound = previous
            if (bound != null && plan.frameIndex <= bound) {
                throw renGFailure(
                    RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                    PipelineStage.FRAME_PLANNING,
                    failureContextDiagnostic(
                        stage = PipelineStage.FRAME_PLANNING,
                        fieldName = DiagnosticField.FRAME_INDEX,
                    ),
                )
            }
            previous = plan.frameIndex
        }
    }

    private fun preparationHistory(): PreparationHistory = PreparationHistory(
        frameIndex = previousFrameIndex,
        encodedPlan = previousEncodedPlan,
        selectedLod = previousSelectedLod,
        labelFade = previousLabelFade,
        labelTiles = previousLabelTiles,
    )

    private fun commitPreparationHistory(history: PreparationHistory) {
        previousFrameIndex = history.frameIndex
        previousEncodedPlan = history.encodedPlan
        previousSelectedLod = history.selectedLod
        previousLabelFade = history.labelFade
        previousLabelTiles = history.labelTiles
    }

    /** Restores renderer state and drains every frame without allocating during terminal rollback. */
    private fun rollbackPreparedInvocation(delivery: PreparedInvocationDelivery) {
        commitPreparationHistory(delivery.initialHistory)
        preparedBasemapStyle = delivery.initialStyle
        readAheadCoordinator.readAheadSnapshot = delivery.initialReadAheadSnapshot
        disposePreparedFrames(delivery.frames)
    }

    private fun disposePreparedFrames(frames: List<RenGPreparedFrame>) {
        var index = frames.lastIndex
        while (index >= 0) {
            frameOwnership.disposePreparedFrame(frames[index])
            index -= 1
        }
    }

    private suspend fun prepareItem(
        item: PlannedPreparationItem,
        history: PreparationHistory,
        accessMode: ResourceAccessMode,
        priority: RenGRenderPriority,
    ): PreparedItemResult {
        val plan = item.plan
        val planned = item.planned
        val leases = mutableListOf<Lease>()
        var prepared: RenGPreparedFrame? = null
        var constructedFrame: RenGPreparedFrame? = null
        var rawTileReservation: RawTileBudget.Reservation? = null
        var preparedFrameCpuReservation: PreparedFrameCpuBudget.Reservation? = null
        try {
            val cpuReservation = when (
                val admission = preparedFrameCpuBudget.tryReserve(
                    leasedGenerations = emptyList(),
                    frameOwnedBytes = 0L,
                )
            ) {
                is PreparedFrameCpuBudget.Admission.Admitted -> admission.reservation
                is PreparedFrameCpuBudget.Admission.Rejected -> throw renGFailure(
                    RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    PipelineStage.FRAME_PREPARATION,
                    failureContextDiagnostic(
                        stage = PipelineStage.FRAME_PREPARATION,
                        fieldName = DiagnosticField.PREPARED_FRAME_CPU_BYTES,
                        limit = configuration.resourceLimits.maximumInFlightPreparedFrameCpuBytes,
                        actual = admission.projectedBytes,
                    ),
                )
            }
            preparedFrameCpuReservation = cpuReservation

            // Read once. `FramePlan.models` hands back a fresh copy of its own snapshot on every
            // access, so re-reading it below would be three copies of one immutable list rather than
            // three chances to observe a different one -- but naming it once is also what keeps the
            // traversal check below comparing against the same list the models are built from.
            val planModels = plan.models
            // FramePlanningCore.staticResourceTraversal() walks plan.stickersForCore() in order,
            // emitting exactly one External reference per sticker, so zipping the two lists back
            // together by position is safe.
            val stickerImageReferences = planned.staticResourceTraversal
                .filterIsInstance<StaticResourceReference.External>()
                .filter { it.resourceClass == ResourceClass.STICKER_IMAGE }
            check(stickerImageReferences.size == plan.stickers.size) {
                "sticker image traversal must have exactly one entry per sticker"
            }

            // FramePlanningCore.staticResourceTraversal() emits exactly one MODEL_GLB entry per
            // plan model, in plan order, and nothing else in the traversal carries that class -- so
            // unlike a MODEL_TEXTURE (which a Geometry's own consumer textures reuse) these can be
            // read straight back out of the traversal by position, exactly as the sticker images
            // above are.
            val modelGlbReferences = planned.staticResourceTraversal
                .filterIsInstance<StaticResourceReference.External>()
                .filter { it.resourceClass == ResourceClass.MODEL_GLB }
            check(modelGlbReferences.size == planModels.size) {
                "model GLB traversal must have exactly one entry per model"
            }
            // A Model.texture, on the other hand, traverses under the same reused MODEL_TEXTURE class
            // a Geometry's consumer textures do, so it is derived independently here for exactly the
            // reason `geometryTextureReference` documents rather than parsed back out by position.
            val modelTextureReferences: List<StaticResourceReference.External?> =
                planModels.map { model -> model.texture?.let { externalImageReference(it) } }

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
                        name to externalImageReference(locator)
                    }
                }

            // The style is the one traversal entry that is neither a sticker nor a texture: it is
            // acquired, validated, compiled through the engine, written and installed by the resource
            // driver's own basemap-style commit verbs, and never decoded as an image.
            val styleReference = planned.staticResourceTraversal
                .filterIsInstance<StaticResourceReference.External>()
                .singleOrNull { it.resourceClass == ResourceClass.BASEMAP_STYLE }

            // ADR 0068. At most one, and its own class, so it cannot be confused with the sticker
            // images that are paired back up by position above. Only a pattern has one: ADR 0071's
            // shader backdrop brings consumer textures instead, under MODEL_TEXTURE.
            val backdropReference = planned.staticResourceTraversal
                .filterIsInstance<StaticResourceReference.External>()
                .singleOrNull { it.resourceClass == ResourceClass.BACKDROP_IMAGE }
            check((backdropReference == null) == (plan.backdrop !is Backdrop.Pattern)) {
                "a backdrop image traverses exactly when the plan carries a pattern backdrop"
            }

            // Recomputed rather than read back out of the traversal, for the reason
            // `externalImageReference` gives: a MODEL_TEXTURE entry cannot be told from a
            // geometry's by position.
            val backdropTextureReferences: List<Pair<String, StaticResourceReference.External>> =
                (plan.backdrop as? Backdrop.Shader)
                    ?.textures?.entries?.sortedBy { it.key }
                    ?.map { (name, locator) -> name to externalImageReference(locator) }
                    .orEmpty()

            val imageReferences = stickerImageReferences +
                listOfNotNull(backdropReference) +
                backdropTextureReferences.map { it.second } +
                modelTextureReferences.filterNotNull() +
                geometryTextureReferencesByGeometry.flatten().map { it.second }
            // Post-world-copy-dedup by construction: `canonicalResources` is what BasemapTileSelector
            // emits separately from `instances` precisely so N visible copies of one tile are one
            // acquisition, one engine render and one identity. Since E-labels task 8b the selection is
            // non-null whenever the plan draws a basemap *or* its labels and a style is configured
            // (planMercatorSpatial), which is also exactly when `styleReference` is non-null.
            //
            // **This is the ground draw's own gate, and the only one.** These are the tiles the engine
            // rasterizes into the PNGs the ground is textured from, so a frame that draws no basemap
            // hands over none: `renderBasemapTiles` is skipped, `basemapStyleDigest` stays null, and
            // `groundInstances` therefore returns empty below. `drawLabels` deliberately does not widen
            // it -- the label handover takes the canonical tiles from the spatial plan and fetches its
            // own vector tiles through the engine, and nothing about a label needs the ground's raster.
            val groundCanonicalTiles = if (plan.drawBasemap) {
                planned.spatialPlan.tileSelection?.canonicalResources.orEmpty()
            } else {
                emptyList()
            }
            // **The label draw's own gate, and the same selection the ground reads.** Deriving a second
            // tile list here would be a defect rather than a duplication: `labelCandidateRequestKey`
            // deliberately does not canonicalise `x`, so a tile set that differs from this one by a
            // world copy is a different set of routes, a different cache key and a different set of
            // candidates. Task 8b is what makes one selection reachable from both switches -- since
            // that split the selection exists on a `drawBasemap = false, drawLabels = true` frame,
            // which is exactly the pairing that used to plan no tiles at all.
            // ADR 0070: not the ground's selection. See [observeLabelTiles] -- the ground's set moves
            // with the camera, and the label request key cannot afford to.
            val labelCanonicalTiles = if (plan.drawLabels) {
                observeLabelTiles(
                    required = planned.spatialPlan.tileSelection?.canonicalResources.orEmpty(),
                    previous = history.labelTiles,
                )
            } else {
                emptyList()
            }
            val acquired = acquireFrameResources(
                styleReference = styleReference,
                imageReferences = imageReferences,
                modelGlbReferences = modelGlbReferences,
                canonicalTiles = groundCanonicalTiles,
                labelTiles = labelCanonicalTiles,
                accessMode = accessMode,
                priority = priority,
                leaseSink = leases,
                preparedFrameCpuReservation = cpuReservation,
                rawReservationSink = { reservation ->
                    check(rawTileReservation == null) { "one frame can own at most one raw reservation" }
                    rawTileReservation = reservation
                },
            )
            currentCoroutineContext().ensureActive()
            val decodedByKey = acquired.decodedImagesByKey
            fun generationIdFor(key: ResourceKey): ResidentGenerationId =
                requireNotNull(acquired.leasedGenerations[key]) {
                    "a successful resource operation must leave this frame a lease on its generation"
                }.id

            // Hoisted above the label block rather than assembled with the frame below, because both
            // are what a label anchor needs to know where the ground is: `groundInstances` is the one
            // answer to "did this frame draw a ground at all, and at which LOD", and `preparedTerrain`
            // is the one answer to "which of its tiles displace". Both are pure functions of
            // `acquired` and of the plan, so hoisting them moves work rather than meaning.
            val groundInstances = groundTileProvisioner.groundInstances(
                instances = planned.spatialPlan.tileSelection?.instances.orEmpty(),
                renderedTiles = acquired.basemapTiles,
                residentAtSelection = acquired.residentAtSelection,
                styleDigest = acquired.basemapStyleDigest,
            )
            val terrain = preparedTerrain(acquired)
            // Engine-produced tiles are independently bounded during acquisition. As soon as their
            // exact retained byte arrays are available, charge them before building any more frame
            // payload so this frame cannot accumulate several admitted groups and fail only at handoff.
            frameOwnership.requirePreparedFrameCpuAdmission(
                cpuReservation.tryAddFrameOwned(
                    preparedBasemapAndTerrainCpuBytes(acquired.basemapTiles, terrain),
                ),
            )
            // **One surface per frame, built here and spent in two places at two different times.**
            // A label anchor rides it during this `prepare()`, because ADR 0035 puts collision here
            // and never in a draw; a `GROUND_RELATIVE` sticker, model or geometry rides it at draw
            // time through `sceneTerrain`. Building it twice would duplicate the surface indexes --
            // and, worse, create two objects that could disagree about which tiles displace the day
            // the two construction sites drift apart. Both read the same immutable DecodedImages;
            // neither copies a DEM raster.
            val groundSurface = groundSurfaceFor(
                plan = plan,
                planModels = planModels,
                hasLabelCandidates = acquired.labelCandidates?.batch?.candidates?.isNotEmpty() == true,
                groundInstances = groundInstances,
                terrain = terrain,
                groundSelectedLod = planned.spatialPlan.lodObservation.selectedLod,
            )

            val preparedBackdrop = when (val backdrop = plan.backdrop) {
                null -> null
                is Backdrop.Pattern -> {
                    val reference = requireNotNull(backdropReference)
                    PreparedBackdrop.Pattern(
                        resourceKey = reference.resourceKey,
                        generationId = generationIdFor(reference.resourceKey),
                        image = requireNotNull(decodedByKey[reference.resourceKey]) {
                            "a successful acquisition must decode a traversed backdrop image"
                        },
                        tileSizeLogicalPixels = backdrop.tileSizeLogicalPixels,
                    )
                }
                // The uniform map is snapshotted here for the reason Task 9b item 3 gives for a
                // geometry's: it is the consumer's own reference until this `.toMap()`.
                is Backdrop.Shader -> PreparedBackdrop.Shader(
                    shaderPair = backdrop.shaderPair,
                    uniforms = backdrop.uniforms.toMap(),
                    consumerTextures = backdropTextureReferences.associate { (name, reference) ->
                        name to PreparedGeometryTexture(
                            resourceKey = reference.resourceKey,
                            generationId = generationIdFor(reference.resourceKey),
                            image = requireNotNull(decodedByKey[reference.resourceKey]) {
                                "a successful backdrop-texture acquisition must decode every traversed image"
                            },
                        )
                    },
                )
            }

            val stickers = plan.stickers.zip(stickerImageReferences) { sticker, reference ->
                PreparedSticker(
                    placement = sticker.placement,
                    resourceKey = reference.resourceKey,
                    generationId = generationIdFor(reference.resourceKey),
                    image = acquired.compressedImagesByKey[reference.resourceKey]
                        ?: requireNotNull(decodedByKey[reference.resourceKey]) {
                            "a successful sticker acquisition must decode or admit every traversed image"
                        },
                    opacity = sticker.opacity,
                    blend = sticker.blend,
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
                        generationId = generationIdFor(reference.resourceKey),
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

            // Every model-shaped derivation happens right here, synchronously, before this suspend
            // function returns: decode, selector resolution, sampling, node composition and the joint
            // palettes. See [PreparedModel] for why the animation pose in particular cannot be left
            // to the draw.
            val models = planModels.mapIndexed { index, model ->
                currentCoroutineContext().ensureActive()
                prepareModel(
                    model,
                    modelGlbReferences[index],
                    modelTextureReferences[index],
                    decodedByKey,
                    cpuReservation,
                    acquired.leasedGenerations,
                )
            }

            // The label path's second and third steps, in the order ADR 0034 and ADR 0035 fix them.
            // `placeLabels` resolves point placement, collision and priority against the same resolved
            // camera the planner already derived -- not a second resolution of the raw `Camera` -- and
            // returns the survivors lowest-priority-first. `advanceLabelFade` then folds in the
            // previous frame's opacities by `LabelIdentity` and is committed below beside the LOD, on
            // the LOD's exact terms: only once every fallible step above has succeeded.
            //
            // A `null` batch is the honest input on a frame that asked for no labels, and it is not a
            // placeholder for a real one: a frame that places no label is a frame in which every label
            // is absent, so every carried entry decays toward zero and the map empties itself.
            val labelBatch = acquired.labelCandidates?.batch
            val placedLabels = if (labelBatch == null) {
                emptyList()
            } else {
                placeLabels(
                    planned.spatialPlan.camera,
                    labelBatch,
                    acquired.spriteAtlas,
                    labelGroundElevation(
                        plan = plan,
                        resolvedCamera = planned.spatialPlan.camera,
                        surface = groundSurface,
                        terrain = terrain,
                        selectedLod = planned.spatialPlan.lodObservation.selectedLod,
                    ),
                )
            }
            val labelFade = advanceLabelFade(
                previous = history.labelFade,
                batch = labelBatch,
                placed = placedLabels,
            )
            // The atlas is decoded only for a frame that actually kept a label. An empty batch still
            // carries one -- Skia will not encode a zero-dimension image, so Rentile packs a 1x1
            // placeholder -- and decoding, keying and uploading that would be a texture no draw ever
            // samples.
            val labels = if (labelBatch == null || labelFade.labels.isEmpty()) {
                null
            } else {
                val atlasKey = geometryKeyDeriver.glyphAtlas(labelBatch.atlas.contentKey).key
                // Both halves of every surviving symbol, faded from the one number the fade produced
                // for it. `advanceLabelFade` faded the glyph quads; the icon quad is faded here
                // because it never reached that function -- see `PreparedLabelFrame.icons`.
                val icons = labelFade.labels.mapNotNull { faded ->
                    faded.label.icon?.quad?.fadedBy(faded.opacity)
                }
                // Placement has produced the exact retained quad arrays. Charge them before either
                // atlas decode adds another large payload to the frame.
                frameOwnership.requirePreparedFrameCpuAdmission(
                    cpuReservation.tryAddFrameOwned(
                        preparedLabelGeometryCpuBytes(labelFade.labels, icons),
                    ),
                )
                // Decoded only for a frame that actually kept an icon. A style can declare a sprite
                // pair that every one of its icons then fails to resolve against, and decoding an
                // atlas nothing samples is the same waste the glyph atlas's own guard avoids.
                val sprites = if (icons.isEmpty()) {
                    null
                } else {
                    residentSpriteAtlas(acquired.spriteAtlas, cpuReservation)
                }
                PreparedLabelFrame(
                    atlasKey = atlasKey,
                    atlas = residentGlyphAtlas(atlasKey, labelBatch.atlas.pngBytes, cpuReservation),
                    labels = labelFade.labels,
                    icons = if (sprites == null) emptyList() else icons,
                    spriteAtlasKey = sprites?.key,
                    spriteAtlas = sprites?.image,
                )
            }

            // ADR 0041's two reports, and the only place RenG says out loud that a frame's ground is
            // flatter than its style asked for. Once per frame, never once per tile, and never a
            // failure: the frame prepared and it will draw. Over the frame's own ground tiles rather
            // than over the request, so the perimeter ring -- which costs a replicated border texel
            // and not a flat tile -- does not inflate the count.
            //
            // The terrain is prepared *before* the report rather than inside the frame below, because
            // the count is over the tiles that will actually displace and only the prepared terrain
            // knows which those are. Handing this the acquisition outcome instead is the defect Task
            // 13's harness pass caught: a frame that acquired every DEM and could use none of them
            // reported nothing at all. Task 18 moved the preparation itself further up still -- a
            // label anchor rides the same surface and is placed above -- and left the report here, so
            // a frame's diagnostics keep the order they were emitted in before labels rode anything.
            acquired.terrain?.let { outcome ->
                reportTerrainDegradation(
                    outcome = outcome,
                    groundTiles = groundCanonicalTiles,
                    elevatedTiles = terrain?.elevatedTiles.orEmpty(),
                    sink = configuration.diagnosticSink,
                )
            }

            // Bound to a local as well as to `prepared`, so the two statements below read the frame
            // without depending on a smart cast of a `var` the `finally` also reads (ADR 0045).
            val frame = RenGPreparedFrame(
                owner = this,
                ownerIdentity = ownerIdentity,
                frameIndex = plan.frameIndex,
                camera = plan.camera,
                projectionMode = plan.projectionMode,
                drawBasemap = plan.drawBasemap,
                drawLabels = plan.drawLabels,
                basemapStyleDigest = acquired.basemapStyleDigest,
                stickers = stickers,
                geometries = geometries,
                models = models,
                basemapTiles = acquired.basemapTiles,
                groundInstances = groundInstances,
                groundSelectedLod = planned.spatialPlan.lodObservation.selectedLod,
                backdrop = preparedBackdrop,
                bloom = plan.bloom,
                terrain = terrain,
                groundSurface = groundSurface,
                labels = labels,
                mapOrder = planned.spatialPlan.mapEntries.map { it.reference },
                screenOrder = planned.spatialPlan.screenEntries.map { it.reference },
                rawTileReservation = rawTileReservation,
                preparedFrameCpuReservation = cpuReservation,
                leases = leases,
            )
            constructedFrame = frame
            val result = PreparedItemResult(
                frame = frame,
                nextHistory = PreparationHistory(
                    frameIndex = plan.frameIndex,
                    encodedPlan = planned.encodedPlan,
                    selectedLod = planned.spatialPlan.lodObservation.selectedLod,
                    labelFade = labelFade.nextState,
                    labelTiles = if (plan.drawLabels) labelCanonicalTiles else history.labelTiles,
                ),
                preparedStyle = acquired.preparedStyle,
                styleManifest = acquired.styleManifest,
            )
            // This is the ownership handoff: every allocation which could still fail happened first,
            // and owner-wide close can now find the frame even if it wins immediately after publication.
            frameOwnership.registerPreparedFrame(frame)
            prepared = frame
            return result
        } finally {
            if (prepared == null) {
                val abandonedFrame = constructedFrame
                if (abandonedFrame != null) {
                    frameOwnership.disposePreparedFrame(abandonedFrame)
                } else {
                    try {
                        try {
                            rawTileReservation?.release()
                        } catch (_: Throwable) {
                            // Continue draining resident generations.
                        }
                        frameOwnership.releaseLeases(leases)
                    } finally {
                        try {
                            preparedFrameCpuReservation?.release()
                        } catch (_: Throwable) {
                            // Ownership is terminal; no later token remains to drain.
                        }
                    }
                }
            }
        }
    }

    /**
     * Decodes the glyph atlas the engine packed for this frame, to the same canonical RGBA8 every
     * other image in this renderer becomes.
     *
     * **The failure is the one the identical bytes already have elsewhere**, not a new policy: an
     * engine-produced PNG that `decodePng` will not take is `RESOURCE_DECODE_FAILED` at
     * `RESOURCE_DECODING`, exactly as a sticker or geometry texture is a few lines up in
     * [acquireFrameResources], and exactly as a rendered ground tile is at draw time. What is
     * genuinely reachable here is the budget rather than a malformed container: an atlas shares
     * [ResourceLimits.maximumDecodedImageBytes] with every raster RenG decodes, and a style whose
     * font stacks pack past it fails the frame rather than drawing text without its glyphs.
     */
    /**
     * [decodeGlyphAtlas], but paid once per distinct atlas rather than once per frame that keeps a label.
     *
     * **The decode is the atlas's real per-frame cost, and the upload never was.** `uploadGlyphAtlas`
     * has leased a resident texture by key since task 20, so the GPU half was already right; what was
     * not is that `prepare()` decoded 6.88 MB of PNG -- 120-130 ms measured, 24% of the whole label
     * path -- to produce a [DecodedImage] the upload then threw away on every frame after the first.
     * `prepare()` cannot consult [glObjectRegistry] to find out whether it needs one: it is a `suspend`
     * function that may resume off the thread holding the render context, and the registry belongs to
     * the draw. So the decode is memoized here instead, on the CPU side where it happens.
     *
     * [key] is the atlas's content identity, so a hit means the same bytes rather than the same request,
     * and a `RESIDENT` handover that repacked an identical atlas hits it too.
     */
    private fun residentGlyphAtlas(
        key: ResourceKey,
        pngBytes: ByteArray,
        preparedFrameCpuReservation: PreparedFrameCpuBudget.Reservation,
    ): DecodedImage {
        retainedGlyphAtlas?.takeIf { it.key == key }?.let { retained ->
            frameOwnership.requirePreparedFrameCpuAdmission(
                preparedFrameCpuReservation.tryAddFrameOwned(retained.image.byteCount.toLong()),
            )
            return retained.image
        }
        val png = prescanPng(pngBytes)
        val projectedBytes = png.projectedRgbaBytes
        if (
            projectedBytes != null &&
            projectedBytes <= configuration.resourceLimits.maximumDecodedImageBytes
        ) {
            frameOwnership.requirePreparedFrameCpuAdmission(
                preparedFrameCpuReservation.tryAddFrameOwned(projectedBytes),
            )
        }
        val decoded = decodeGlyphAtlas(png)
        check(projectedBytes == null || projectedBytes == decoded.byteCount.toLong()) {
            "glyph-atlas projection and decoded byte count must agree"
        }
        if (projectedBytes == null) {
            frameOwnership.requirePreparedFrameCpuAdmission(
                preparedFrameCpuReservation.tryAddFrameOwned(decoded.byteCount.toLong()),
            )
        }
        retainedGlyphAtlas = RetainedGlyphAtlas(key, decoded)
        return decoded
    }

    /**
     * [manifest]'s atlas image, decoded and named, or `null` when there is no manifest to decode.
     *
     * **A `null` manifest with icons in hand is unreachable rather than merely unlikely**, because
     * `resolveIcon` returns `null` for every icon when the manifest is absent -- an icon exists only
     * if a manifest resolved it. It is expressed as a nullable rather than a `requireNotNull` so that
     * a future path which decouples the two loses its icons instead of failing a frame over them.
     *
     * The decode is memoised against the manifest instance; see [retainedSpriteAtlas]. The key is
     * derived on the same miss, which is the only place the encoded atlas is hashed.
     */
    private fun residentSpriteAtlas(
        manifest: SpriteAtlasManifest?,
        preparedFrameCpuReservation: PreparedFrameCpuBudget.Reservation,
    ): RetainedSpriteAtlas? {
        if (manifest == null) return null
        retainedSpriteAtlas?.takeIf { it.manifest === manifest }?.let { retained ->
            frameOwnership.requirePreparedFrameCpuAdmission(
                preparedFrameCpuReservation.tryAddFrameOwned(retained.image.byteCount.toLong()),
            )
            return retained
        }
        val key = geometryKeyDeriver.spriteAtlas(manifest.atlasPngBytes).key
        val png = prescanPng(manifest.atlasPngBytes)
        val projectedBytes = png.projectedRgbaBytes
        if (
            projectedBytes != null &&
            projectedBytes <= configuration.resourceLimits.maximumDecodedImageBytes
        ) {
            frameOwnership.requirePreparedFrameCpuAdmission(
                preparedFrameCpuReservation.tryAddFrameOwned(projectedBytes),
            )
        }
        val decoded = decodeSpriteAtlas(png)
        check(projectedBytes == null || projectedBytes == decoded.byteCount.toLong()) {
            "sprite-atlas projection and decoded byte count must agree"
        }
        if (projectedBytes == null) {
            frameOwnership.requirePreparedFrameCpuAdmission(
                preparedFrameCpuReservation.tryAddFrameOwned(decoded.byteCount.toLong()),
            )
        }
        return RetainedSpriteAtlas(manifest, key, decoded).also { retainedSpriteAtlas = it }
    }

    /**
     * The sprite atlas, decoded to the same canonical RGBA8 every other image in this renderer
     * becomes, failing exactly as [decodeGlyphAtlas] does over the identical class of bytes.
     *
     * **What is reachable here is the budget, not a malformed container.** The firewall's own sprite
     * member gate already decoded these exact bytes in full before the pair was allowed to be
     * cached, at a ceiling of its own; `maximumDecodedImageBytes` is a consumer-settable limit that
     * can legitimately be lower. A style whose sprite sheet packs past the consumer's own ceiling
     * fails the frame rather than drawing a map with its icons quietly missing -- the same choice the
     * glyph atlas makes over the same limit, and for the same reason: a silent half-drawn symbol is
     * the failure this whole task exists to close.
     */
    private fun decodeSpriteAtlas(png: PngPrescan): DecodedImage =
        when (
            val decoded = png.decode(
                configuration.resourceLimits.maximumDecodedImageBytes,
                configuration.resourceLimits.maximumImageDecodeWorkingBytes,
            )
        ) {
            is PngDecodeResult.Success -> decoded.image
            else -> throw RenGException(
                code = RenGErrorCode.RESOURCE_DECODE_FAILED,
                stage = PipelineStage.RESOURCE_DECODING,
                diagnostics = listOf(
                    failureContextDiagnostic(
                        stage = PipelineStage.RESOURCE_DECODING,
                        fieldName = DiagnosticField.RESOURCE,
                        resourceClass = ResourceClass.BASEMAP_SPRITE_IMAGE,
                    ),
                ),
            )
        }

    private fun decodeGlyphAtlas(png: PngPrescan): DecodedImage =
        when (
            val decoded = png.decode(
                configuration.resourceLimits.maximumDecodedImageBytes,
                configuration.resourceLimits.maximumImageDecodeWorkingBytes,
            )
        ) {
            is PngDecodeResult.Success -> decoded.image
            else -> throw RenGException(
                code = RenGErrorCode.RESOURCE_DECODE_FAILED,
                stage = PipelineStage.RESOURCE_DECODING,
                diagnostics = listOf(
                    failureContextDiagnostic(
                        stage = PipelineStage.RESOURCE_DECODING,
                        fieldName = DiagnosticField.RESOURCE,
                        resourceClass = ResourceClass.BASEMAP_GLYPH_RANGE,
                    ),
                ),
            )
        }

    /**
     * This frame's terrain in the form the draw needs, or `null` when it has none to draw with.
     *
     * **`Degraded` collapses to `null` here rather than being carried**, because by this point ADR
     * 0041's report has already been emitted and the two degradations have exactly one remaining
     * consequence: the whole ground draws flat, which is what a `null` terrain means. Carrying the
     * reason onward would put a second authority on that decision inside the draw.
     *
     * An `Acquired` outcome with **no** DEM tiles is deliberately *not* collapsed. Its per-frame
     * uniforms — the decode, the interior size, the exaggeration — are properties of the source
     * rather than of the coverage, so the frame keeps them, every tile draws flat because each one
     * individually has no DEM, and the coverage diagnostic has already named the count. Collapsing
     * would make the frame's granularity fall back to the flat rule as a side effect of a coverage
     * gap, which is a different picture from the one the style asked for.
     */
    /**
     * The CPU-readable view of the surface this frame's ground will draw, or `null` when nothing in
     * the frame asked where the ground is.
     *
     * **This is the whole of what the design's "sparse CPU decode" becomes.** §6 argued that a CPU
     * elevation lookup must decode only the tiles containing ground-relative content, because decoding
     * the visible set would be about 224 MiB against a `maximumDecodedImageBytes` shared with every
     * raster. Rentile `0.7.0` deleted that premise -- `ValidatedDemTile.texels` arrives decoded and
     * `PreparedTerrain` already holds it -- so the only cost left to avoid is [GroundSurface]'s tile
     * index for a frame that never queries elevation. A surface references those immutable images
     * directly; it never snapshots their raster bytes.
     *
     * **[hasLabelCandidates] is Task 18's widening of that condition, and it is the batch's own
     * candidate list rather than `drawLabels`.** Every label anchor rides this surface, so a frame
     * with labels asks the same question a `GROUND_RELATIVE` sticker does -- but a frame whose style
     * declares no symbol layer, or whose tiles carry no labelled feature, has no anchor to place and
     * must keep paying nothing.
     *
     * **What it does cost is one lightweight surface index on every terrain frame that draws text**,
     * where before this task only a `GROUND_RELATIVE` placement needed one. Each elevation sample
     * reads the existing [DecodedImage] through its internal indexed accessor, so the visible DEM set
     * and perimeter ring retain no second pixel copy for labels.
     *
     * **[selectedLod] comes from the ground instances rather than from the plan**, for `sceneTerrain`'s
     * reason: both tile selectors emit one LOD per frame, and a frame with no ground instances drew no
     * ground at all -- which is ADR 0040's "absent terrain resolves `GROUND_RELATIVE` as `ABSOLUTE`",
     * and is why a `drawBasemap = false` frame's labels sit at sea level however steep the world is.
     * That case cannot even arise from an acquisition: terrain is acquired only for a frame whose
     * canonical tile list is non-empty, which is exactly a frame that draws its basemap.
     */
    private fun groundSurfaceFor(
        plan: FramePlan,
        planModels: List<Model>,
        hasLabelCandidates: Boolean,
        groundInstances: List<PreparedGroundInstance>,
        terrain: PreparedTerrain?,
        groundSelectedLod: Int?,
    ): GroundSurface? {
        if (terrain == null) return null
        if (groundInstances.isEmpty()) return null
        val selectedLod = groundSelectedLod ?: return null
        val ridesTheGround = hasLabelCandidates ||
            plan.geometries.any { it.altitudeMode == AltitudeMode.GROUND_RELATIVE } ||
            plan.stickers.any { it.placement.altitudeMode == AltitudeMode.GROUND_RELATIVE } ||
            planModels.any { it.placement.altitudeMode == AltitudeMode.GROUND_RELATIVE }
        if (!ridesTheGround) return null
        return terrain.groundSurface(selectedLod)
    }

    /**
     * [GroundSurface] bound to the one granularity this frame's ground is drawn at, in the shape the
     * label placement pass consumes — or `null` when this frame drew no displaced ground under
     * anything.
     *
     * **The granularity is `SceneContent`'s own, reached through the same
     * [frameGroundCellsPerTileSide] and given the same three inputs.** A lookup at any other number
     * finds a different ground cell and therefore a different height, and the picture of that is a
     * label sitting slightly off the hill it names — which reads as an imprecise lookup rather than
     * as a disagreement about the grid.
     *
     * **The `null` a lookup can still answer becomes zero here**, which is ADR 0040 in one line: where
     * the frame drew no displaced ground under an anchor, a ground-relative height is nothing at all
     * and the label stays exactly where it has always been.
     *
     * **No lift.** `GROUND_DRAPE_LIFT_METRES` exists for content drawn coplanar with the ground and
     * fighting it for depth; Task 14's spike measured that labels have no such fight — `drawLabels`
     * and `drawIcons` both disable the depth test outright — so a lift here would move a name off its
     * own feature to fix a defect it does not have.
     */
    private fun labelGroundElevation(
        plan: FramePlan,
        resolvedCamera: ResolvedFrameCamera,
        surface: GroundSurface?,
        terrain: PreparedTerrain?,
        selectedLod: Int?,
    ): LabelGroundElevation? {
        if (surface == null || terrain == null || surface.isEmpty) return null
        val lod = selectedLod ?: return null
        val cellsPerTileSide = frameGroundCellsPerTileSide(
            camera = resolvedCamera,
            selectedLod = lod,
            terrainCells = terrainCellsPerTileSide(
                terrain = TerrainGranularityInputs(terrain.tileSizePx),
                projectionMode = plan.projectionMode,
                zoom = plan.camera.zoom,
                latitude = plan.camera.latitude,
                selectedLod = lod,
            ),
        )
        return LabelGroundElevation { latitude, unwrappedLongitude ->
            surface.elevationMetresBeneath(latitude, unwrappedLongitude, cellsPerTileSide) ?: 0.0
        }
    }

    private fun preparedTerrain(acquired: FrameAcquisition): PreparedTerrain? {
        val outcome = acquired.terrain as? TerrainAcquisitionOutcome.Acquired ?: return null
        val demTiles = LinkedHashMap<CanonicalBasemapTile, AcquiredDemTile>()
        outcome.requestedTiles.forEach { tile ->
            outcome.demTileFor(tile)?.let { demTiles[tile] = it }
        }
        return PreparedTerrain(
            encoding = outcome.source.encoding,
            tileSizePx = outcome.source.tileSizePx,
            exaggeration = acquired.terrainExaggeration,
            demTiles = demTiles,
        )
    }

    /**
     * Derives the same [StaticResourceReference.External] shape
     * `FramePlanningCore.staticResourceTraversal()`'s own `external(...)` helper produces for a
     * [ResourceClass.MODEL_TEXTURE], independently — both are the same pure function of
     * `(ResourceClass.MODEL_TEXTURE, locator)`, so they always agree without this function needing
     * to parse the traversal list back apart by position.
     *
     * That position-parse is unavailable here specifically, and only here: exactly one traversal entry
     * exists per plan sticker and exactly one per plan model's GLB, so both of those *are* read back by
     * position — but a geometry's own consumer textures and a [Model.texture] override traverse under
     * the same reused [ResourceClass.MODEL_TEXTURE], so telling one from the other by position would
     * mean re-deriving the traversal's own interleaving here. Recomputing the key cannot drift.
     */
    private fun externalImageReference(locator: ResourceLocator): StaticResourceReference.External {
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

    /**
     * One [Model] turned into the [PreparedModel] the draw path consumes: the acquired GLB decoded,
     * its [AnimationTrack]s resolved and sampled, its node hierarchy composed against that pose, and
     * one joint palette per skin a draw item names.
     *
     * **This is the third parse of the same bytes in this prepared frame, and that is recorded rather
     * than fixed.** `PARSE_GLB` and `VALIDATE_GLB_FEATURES` each independently re-scan and re-parse the
     * container inside the resource driver, and [decodeModel] parses it once more here. `decodeModel`'s
     * own KDoc carries the measurement — 0.85 ms of a 3.6 ms total, 24%, over a real 133 KB model — and
     * the reason not to fix it in this cycle: the fix is a parsed-model residency belonging beside
     * `ResidentGeneration.decoded`, which is `null` in production for images too, and half of that fix
     * is worse than none of it.
     *
     * **Every failure below is typed and names the GLB it is about.** A [ModelDecodeResult] failure
     * separates into the three codes those classes already mean: a structural or BIN-chunk fault is
     * `RESOURCE_PARSE_FAILED`, a feature outside ADR 0021's subset is `UNSUPPORTED_RESOURCE_FEATURE`,
     * and an expanded form that will not fit `ResourceLimits.maximumDecodedImageBytes` is
     * `RESOURCE_DECODE_FAILED` — the same code, at the same stage, this class's own PNG path already
     * reports for an image that overruns the identical budget.
     *
     * A missing, out-of-range or duplicated [AnimationSelector] reports `RESOURCE_PARSE_FAILED` at
     * [PipelineStage.RESOURCE_PARSING] with `fieldName = animationSelector`. That field is allowlisted
     * at `RESOURCE_PARSING` and deliberately **not** at `FRAME_PLANNING`, which is a real ordering
     * constraint rather than a stylistic one: resolving a selector needs the GLB's own animation
     * catalogue, which does not exist until the bytes have been acquired, so the check cannot live in
     * `FramePlanningCore` at all.
     *
     * A `null` from [jointMatricesForSkin] is a failure here rather than a static draw. Its three
     * `null`s are a singular skinned-node transform, a joint node the default scene never reaches, and
     * a skin over [com.rohittp.reng.internal.gl.MAXIMUM_MODEL_JOINTS] joints; each could only be
     * papered over by inventing a pose, and posing a rig arbitrarily is a silently wrong picture.
     * [SceneModel]'s "a skin absent from the map draws its mesh statically" covers the *other* case —
     * a mesh instanced at a node that names no skin at all — which never enters this loop.
     */
    private fun prepareModel(
        model: Model,
        glbReference: StaticResourceReference.External,
        textureReference: StaticResourceReference.External?,
        decodedByKey: Map<ResourceKey, DecodedImage>,
        preparedFrameCpuReservation: PreparedFrameCpuBudget.Reservation,
        /** The frame's leased generation per key; see [FrameAcquisition.leasedGenerations]. */
        leasedGenerations: Map<ResourceKey, ResidentGeneration>,
    ): PreparedModel {
        val glbKey = glbReference.resourceKey
        val generation = leasedGenerations[glbKey]
            ?: error("a successful resource operation must leave this frame a lease on its content")
        val decoded = generation.decodedModel ?: run {
            val generationBytesBeforeDecode = generation.byteSize
            var projectedDecodedBytes = 0L
            val newlyDecoded = when (
                val result = decodeModel(
                    generation.stored.byteSnapshot,
                    configuration.resourceLimits,
                ) { additionalBytes ->
                    projectedDecodedBytes = SaturatingByteCounter().apply {
                        add(projectedDecodedBytes)
                        add(additionalBytes)
                    }.bytes
                    val projectedGenerationBytes = SaturatingByteCounter().apply {
                        add(generationBytesBeforeDecode)
                        add(projectedDecodedBytes)
                    }.bytes
                    frameOwnership.requirePreparedFrameCpuAdmission(
                        preparedFrameCpuReservation.tryProjectGeneration(
                            generation,
                            projectedGenerationBytes,
                        ),
                    )
                }
            ) {
                is ModelDecodeResult.Success -> result.model
                ModelDecodeResult.Malformed -> throw modelParseFailure(glbKey)
                ModelDecodeResult.Unsupported -> throw modelUnsupportedFailure(glbKey)
                ModelDecodeResult.TooLarge -> throw modelDecodeBudgetFailure(glbKey)
            }
            check(projectedDecodedBytes == newlyDecoded.decodedCpuBytes) {
                "model projection and decoded byte count must agree"
            }
            residentCache.attachDecodedModel(generation, newlyDecoded).also {
                frameOwnership.requirePreparedFrameCpuAdmission(
                    preparedFrameCpuReservation.tryProjectGeneration(generation, generation.byteSize),
                )
            }
        }

        // The scene walk and each unique skin palette have exact matrix counts in the decoded
        // document. Admit that payload before sampling/composition allocates any of those matrices.
        frameOwnership.requirePreparedFrameCpuAdmission(
            preparedFrameCpuReservation.tryAddFrameOwned(projectedModelPoseCpuBytes(decoded)),
        )

        val tracks = model.animationTracks
        val resolution = when (val resolved = resolveAnimationSelectors(decoded.document, tracks)) {
            is AnimationResolution.Resolved -> resolved
            AnimationResolution.Missing, AnimationResolution.Duplicate -> throw animationSelectorFailure(glbKey)
        }
        val sampled = sampleAnimationTracks(decoded.document, decoded.bin, tracks, resolution)
            ?: throw modelParseFailure(glbKey)
        val nodeTransforms = composeGlobalTransforms(decoded.document, sampled)

        val jointMatricesBySkin = LinkedHashMap<Int, List<DoubleMatrix4>>()
        for (item in decoded.drawItems) {
            val skinIndex = item.skinIndex ?: continue
            if (skinIndex in jointMatricesBySkin) continue
            val skin = decoded.skins.getOrNull(skinIndex) ?: throw modelParseFailure(glbKey)
            jointMatricesBySkin[skinIndex] = jointMatricesForSkin(skin, nodeTransforms, item.nodeIndex)
                ?: throw modelParseFailure(glbKey)
        }

        return PreparedModel(
            placement = model.placement,
            glbKey = glbKey,
            glbGenerationId = generation.id,
            model = decoded,
            overrideTexture = textureReference?.let { reference ->
                PreparedGeometryTexture(
                    resourceKey = reference.resourceKey,
                    generationId = requireNotNull(leasedGenerations[reference.resourceKey]) {
                        "a successful resource operation must leave this frame a lease on its generation"
                    }.id,
                    image = requireNotNull(decodedByKey[reference.resourceKey]) {
                        "a successful model-texture acquisition must decode every traversed image"
                    },
                )
            },
            nodeTransforms = nodeTransforms,
            jointMatricesBySkin = jointMatricesBySkin,
            opacity = model.opacity,
        )
    }

    /**
     * Acquires everything one frame plan asked for that is not already in hand: the configured basemap
     * style ([styleReference], when the plan draws one), every traversed external image
     * ([imageReferences] — a sticker's, a model's base-colour override, or a geometry consumer
     * texture's), every model GLB ([modelGlbReferences]), and the ground itself ([canonicalTiles]).
     * Returns the decoded images by key plus the rendered ground.
     *
     * **Two of those are acquired and not decoded here, for two different reasons.** A style is not an
     * image at all: it is validated, compiled through the engine, written and installed by the resource
     * driver's own basemap-style commit verbs. A GLB is not an image either, and its decode needs the
     * `ResourceLimits` and the failure vocabulary [prepareModel] owns — so this function's job for a
     * model ends at leaving its bytes resident, which is exactly what [prepareModel] then reads.
     *
     * Returns nothing and performs no adapter call at all when there is nothing to acquire, which is
     * what keeps `prepare()` on a plan with no stickers, no models, no geometry consumer textures, and
     * no configured basemap honestly free of consumer exchange.
     *
     * **One owner for the whole frame.** The occurrence set comes from [buildResourceOperationDefinition],
     * which assigns one [ResourceOwnerId] per preparation item rather than one per reference. That is
     * not a detail: it is the entire content of ADR 0016's style-owner barrier, which orders the style's
     * Store write behind the completion of every other resource the same items referenced. An owner per
     * reference gives the style an owner with no other work, and the barrier becomes vacuously true.
     *
     * **The firewall invocation spans the whole driver run**, because the engine's own sprite, TileJSON
     * and GeoJSON acquisition happens inside the style's compilation, which happens inside the driver.
     * Opening it afterwards would leave every one of those exchanges with no registry to be recognised
     * by, and each would fail closed as `AMBIGUOUS_RESOURCE_ROUTE`.
     *
     * **It also spans the tile phase**, for the reason ADR 0016 gives rather than for mechanical
     * necessity: one operation registry belongs to one preparation invocation. The style's routes are
     * knowable only once the document has been read (inside the driver) and the tiles' only once it has
     * been compiled, so both phases register incrementally into the one registry this invocation owns.
     *
     * A [ResourceOperationOutcome.Cancelled] outcome — reached when one route's adapter call observes
     * its own cancellation while a sibling route is still active — is rethrown as a genuine
     * [CancellationException] rather than a [RenGException], consistent with keeping cancellation
     * unwrapped throughout this codebase.
     */
    @Suppress("LongParameterList")
    private suspend fun acquireFrameResources(
        styleReference: StaticResourceReference.External?,
        imageReferences: List<StaticResourceReference.External>,
        modelGlbReferences: List<StaticResourceReference.External>,
        canonicalTiles: List<CanonicalBasemapTile>,
        labelTiles: List<CanonicalBasemapTile>,
        accessMode: ResourceAccessMode,
        /** The engine gate lane this frame's tiles are rendered in (ADR 0053). */
        priority: RenGRenderPriority,
        /**
         * Collects the lease of every generation this acquisition installs or re-observes, so the frame
         * this preparation is building can release them on `close()`. Owned by [prepare], which is also
         * what releases them when no frame ends up owning them.
         */
        leaseSink: MutableList<Lease>,
        /** Incrementally charged before resident image/model expansions are allocated. */
        preparedFrameCpuReservation: PreparedFrameCpuBudget.Reservation,
        /** Receives the raw-pixel reservation as soon as it is acquired. */
        rawReservationSink: (RawTileBudget.Reservation) -> Unit,
    ): FrameAcquisition {
        val references = listOfNotNull(styleReference) + imageReferences + modelGlbReferences
        if (references.isEmpty()) {
            return FrameAcquisition(emptyMap(), emptyMap(), emptyList(), null, basemapStyleDigest = null)
        }

        val admitLease: (Lease) -> Unit = { lease ->
            frameOwnership.requirePreparedFrameCpuAdmission(preparedFrameCpuReservation.tryAddGeneration(lease.generation))
        }
        // The steady-state shortcut: references resident, fresh and already validated are leased here and
        // left out of the operation, which then drives only the style and whatever is new or changed. See
        // PreparationDriver.leaseSteadyStateResident for what qualifies and why.
        val fullDefinition = buildResourceOperationDefinition(
            traversalsByItem = listOf(references),
            accessMode = accessMode,
            maximumConcurrentRoutes = minOf(configuration.maximumConcurrentResourceOperations, references.size),
        )
        val driven = preparationDriver.leaseSteadyStateResident(
            references = imageReferences + modelGlbReferences,
            accessMode = accessMode,
            leaseSink = leaseSink,
            leaseObserver = admitLease,
            registration = fullDefinition,
        )
        val drivenKeys = driven.mapTo(HashSet()) { it.resourceKey }
        styleReference?.let { drivenKeys += it.resourceKey }
        // The operation drives the style and what the shortcut left, but registers every identity the full
        // set carries, so a bypassed reference still takes part in collision checks. The shortcut bypasses
        // whole keys, so keeping the occurrences of the keys it left drives exactly the references it left.
        val drivenOccurrences = if (driven.size == imageReferences.size + modelGlbReferences.size) {
            fullDefinition.staticOccurrences
        } else {
            fullDefinition.staticOccurrences.filter { it.registration.resourceKey in drivenKeys }
        }
        val drivenCount = drivenOccurrences.size
        val definition = ResourceOperationDefinition(
            maximumConcurrentRoutes = minOf(
                configuration.maximumConcurrentResourceOperations,
                drivenCount.coerceAtLeast(1),
            ),
            staticOccurrences = drivenOccurrences,
            resourceIdentities = fullDefinition.resourceIdentities,
        )

        var basemapTiles: List<RenderedBasemapTile> = emptyList()
        val residentAtSelection = HashSet<ResourceKey>()
        var observedStyle: PreparedStyle? = null
        var observedManifest: BasemapStyleManifest? = null
        var basemapStyleDigest: String? = null
        var terrain: TerrainAcquisitionOutcome? = null
        var terrainExaggeration = 1.0
        var labelCandidates: AcquiredLabelCandidates? = null
        // Read inside the invocation and carried out of it, because the registry that holds it is
        // discarded when the invocation ends (ADR 0016). It is the manifest of the sprite pair this
        // frame's own style declared, and the only way an icon's `imageName` becomes atlas geometry.
        var spriteAtlas: SpriteAtlasManifest? = null
        val outcome = basemapEngineHost.withOperation(accessMode) {
            // Nothing left to drive when every reference was leased by the shortcut and there is no style.
            val drivenOutcome = if (drivenCount == 0) {
                ResourceOperationOutcome.Success(emptyList())
            } else {
                preparationDriver.run(
                    definition = definition,
                    leaseSink = leaseSink,
                    leaseObserver = admitLease,
                )
            }
            if (drivenOutcome is ResourceOperationOutcome.Success) {
                // Read back rather than taken from the compile action: on a RESIDENT-provenance frame
                // the pure core emits no CompileBasemapStyle at all, so there is no action to take it
                // from. The host retains the compilation across invocations, so this is the one place
                // the frame's style is obtainable on every frame alike. A frame that traversed no style
                // -- since E-labels task 8b, one that drew neither the basemap nor its labels -- clears
                // it, so this always describes the frame just prepared.
                // Asked for by content, not by key alone: a compilation whose visibility install never
                // ran -- the style's own Store write failing is enough -- would otherwise be handed back
                // here for bytes that are not resident, and paired with routes renderBasemapTiles derives
                // from the bytes that are. The host declines that, so both halves of the frame are keyed
                // off this one observation of what is resident.
                val style = if (styleReference == null) {
                    null
                } else {
                    basemapEngineHost.currentPreparedStyle(
                        styleKey = styleReference.resourceKey,
                        contentDigest = residentCache.current(styleReference.resourceKey)?.stored?.contentDigest,
                    )
                }
                observedStyle = style
                if (styleReference != null && style != null) {
                    // Both halves read one manifest. It is bound to the style's content digest, so the
                    // second call is a lookup rather than a second parse of a 248 KB document -- see
                    // `BasemapEngineHost.styleManifest`.
                    val manifest = if (canonicalTiles.isEmpty() && labelTiles.isEmpty()) {
                        null
                    } else {
                        completedStyleManifest(styleReference)
                    }
                    observedManifest = manifest
                    if (manifest != null && canonicalTiles.isNotEmpty()) {
                        basemapTiles =
                            groundTileProvisioner.renderBasemapTiles(
                                manifest,
                                style,
                                canonicalTiles,
                                accessMode,
                                priority,
                                rawReservationSink,
                                residentAtSelection,
                            )
                        basemapStyleDigest = style.digest
                        // Inside the invocation, on the routes `tileTimeRoutes` already preregistered
                        // for every `raster-dem` source (the 3x3 neighbourhood, which the ring is a
                        // subset of at any overzoom). Never throws for an acquisition failure: ADR
                        // 0041 makes terrain the one basemap resource that degrades to a picture RenG
                        // has already shipped rather than costing the frame.
                        terrain = terrainAcquisition.acquire(style, manifest, canonicalTiles)
                        terrainExaggeration = manifest.terrainExaggeration
                    }
                    if (manifest != null && labelTiles.isNotEmpty()) {
                        val handover = labelHandover(manifest, style, labelTiles, accessMode)
                        labelCandidates = handover.candidates
                        // Taken from the handover rather than read here, because on a frame that reused
                        // a retained one nothing in this invocation ever asked the engine for the sprite
                        // pair -- so the registry holds no manifest and reading it now would answer
                        // `null` for a style that declares one. See [RetainedLabelHandover].
                        spriteAtlas = handover.spriteAtlas
                    }
                }
            }
            drivenOutcome
        }
        when (outcome) {
            is ResourceOperationOutcome.Success -> Unit
            is ResourceOperationOutcome.Failure -> throw outcome.failure.toException()
            is ResourceOperationOutcome.Cancelled ->
                throw CancellationException("resource preparation observed a route cancellation")
        }

        // The generation this frame actually holds a lease on, per key: what it decodes, attaches to and
        // names, rather than whatever is current by then. They are the same unless something superseded or
        // freed the key after the lease was taken -- possible for a reference the steady-state shortcut
        // leased before the operation ran -- and then the leased generation is the one the frame pins.
        val leasedGenerations = HashMap<ResourceKey, ResidentGeneration>(leaseSink.size)
        leaseSink.forEach { lease ->
            // One generation per key, chosen explicitly rather than asserted: should a key ever be leased
            // twice (registration refuses a key named on two routes today) and an install land between the
            // leases, every lease stays held until the frame closes and the frame draws the first it took.
            leasedGenerations.getOrPut(lease.generation.key) { lease.generation }
        }

        val decodedByKey = LinkedHashMap<ResourceKey, DecodedImage>(imageReferences.size)
        val compressedByKey = HashMap<ResourceKey, CompressedImage>()
        for (reference in imageReferences) {
            currentCoroutineContext().ensureActive()
            val generation = leasedGenerations[reference.resourceKey]
                ?: error("a successful resource operation must leave this frame a lease on its content")
            // ADR 0084. Told by magic bytes alone, as the class gate told it, and only for the one class
            // whose gate admits it: any other class's container falls through to the PNG decode below and
            // fails there. Nothing is decoded and nothing is attached -- the image is a view of the bytes
            // this frame's lease already charged, and reading its header again is cheaper than a memo.
            if (
                reference.resourceClass == ResourceClass.STICKER_IMAGE &&
                isEtc2Container(generation.stored.byteSnapshot)
            ) {
                compressedByKey[reference.resourceKey] = admitEtc2Sticker(generation, reference)
                continue
            }
            // Decoded once per generation, not once per frame (ADR 0059). The only consumer of these
            // pixels is `cachedTexture`'s upload lambda, which does not run once a texture is
            // registered -- so before this, every frame after a sticker's first decoded its PNG and
            // threw the result away. Measured at 14.3 ms for one 512x512 image on a native build,
            // against a consumer frame budget of about 51 ms.
            val residentDecoded = generation.decoded
            if (residentDecoded != null) {
                decodedByKey[reference.resourceKey] = residentDecoded
                continue
            }
            val stored = generation.stored
            val png = prescanPng(stored.byteSnapshot)
            val projectedRgbaBytes = png.projectedRgbaBytes
            if (
                projectedRgbaBytes != null &&
                projectedRgbaBytes <= configuration.resourceLimits.maximumDecodedImageBytes
            ) {
                val projectedGenerationBytes = SaturatingByteCounter().apply {
                    add(generation.byteSize)
                    add(projectedRgbaBytes)
                }.bytes
                frameOwnership.requirePreparedFrameCpuAdmission(
                    preparedFrameCpuReservation.tryProjectGeneration(generation, projectedGenerationBytes),
                )
            }
            val image = when (
                val decoded = png.decode(
                    configuration.resourceLimits.maximumDecodedImageBytes,
                    configuration.resourceLimits.maximumImageDecodeWorkingBytes,
                )
            ) {
                is PngDecodeResult.Success -> decoded.image
                else -> throw imageDecodeFailure(reference)
            }
            // Decode is synchronous and may dominate an image-heavy frame. Observe cancellation
            // before retaining its result or starting another image.
            currentCoroutineContext().ensureActive()
            check(projectedRgbaBytes == null || projectedRgbaBytes == image.byteCount.toLong()) {
                "PNG projection and decoded byte count must agree"
            }
            if (projectedRgbaBytes == null) {
                // Successful PNGs normally take the header projection above. Keep this fallback so a
                // future decoder format cannot attach an uncharged expansion if its scanner lags it.
                val actualGenerationBytes = SaturatingByteCounter().apply {
                    add(generation.byteSize)
                    add(image.byteCount.toLong())
                }.bytes
                frameOwnership.requirePreparedFrameCpuAdmission(
                    preparedFrameCpuReservation.tryProjectGeneration(generation, actualGenerationBytes),
                )
            }
            // Charged to maximumResidentCpuResourceBytes here, which is what keeps this memo bounded
            // and what finally makes `queryResources`' decodedCpuBytes a number rather than a zero.
            residentCache.attachDecoded(generation, image)
            decodedByKey[reference.resourceKey] = image
        }
        return FrameAcquisition(
            decodedByKey,
            compressedByKey,
            basemapTiles,
            observedStyle,
            basemapStyleDigest,
            labelCandidates,
            spriteAtlas,
            terrain,
            terrainExaggeration,
            styleManifest = observedManifest,
            residentAtSelection = residentAtSelection,
            leasedGenerations = leasedGenerations,
        )
    }

    /**
     * Admits [generation]'s ETC2 container as the sticker image it names (ADR 0084), under the same
     * decoded-image ceiling and failure its class gate applied.
     */
    private fun admitEtc2Sticker(
        generation: ResidentGeneration,
        reference: StaticResourceReference.External,
    ): CompressedImage = when (
        val admitted = parseEtc2Container(
            generation.stored.byteSnapshot,
            configuration.resourceLimits.maximumDecodedImageBytes,
        )
    ) {
        is Etc2ContainerResult.Admitted -> admitted.image
        else -> throw imageDecodeFailure(reference)
    }

    private fun imageDecodeFailure(reference: StaticResourceReference.External): RenGException = RenGException(
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

    /**
     * This frame's style, read as the route-composition manifest both the ground and the labels need.
     * Called from **inside** the invocation that compiled the style, so every phase deriving routes
     * from it shares one operation registry (ADR 0016).
     *
     * **One manifest, two consumers, and a cache that makes the second read free.** The ground
     * ([renderBasemapTiles]) and the Label handover ([acquireLabelCandidates]) both compose urls out
     * of this, over the same tile selection, and E-labels task 8b made either of them reachable without
     * the other. Resolving it once per frame and passing it to whichever halves run is what keeps the
     * two from drifting -- and is why this returns the manifest rather than doing anything with it.
     *
     * The manifest is asked of the engine host rather than carried out of the driver. It is the same pure
     * function of the same two inputs the driver's own `ValidateBasemapStyle` ran — the style bytes and
     * the style's own locator, which is exactly what Rentile receives as `StyleInput.Prefetched.baseUri`
     * — so the two derivations cannot disagree. The bytes are read from the resident generation here,
     * which is sound *because this runs after* the driver's `InstallBasemapStyleVisibility`: what is
     * resident by now is this frame's own document. The compilation runs before that install, where the
     * resident generation is still the previous frame's, and so compiles the content the driver hands it
     * instead — see `BasemapEngineHost.preparedStyle`.
     *
     * **A basemap frame used to parse its style document twice** — once in `ValidateBasemapStyle` and
     * once again here — which was pure duplication measured at 1.9 ms per parse for a 248 KB
     * production-shaped style on the JVM (`internal.basemap.BasemapRouteDerivationCostTest`), on every
     * frame. [BasemapEngineHost.styleManifest] now binds the derivation to the style's content digest
     * beside the compilation it already binds that way, so a frame over byte-identical content reads the
     * document once. Preregistration itself stays uncached, and deliberately: it is immaterial (a
     * realistic 40-tile frame names 150 routes for a few ms of CPU, less than one of the 150 tile fetches
     * it then performs), and it is the one part of this that genuinely varies per frame.
     *
     * A rejected manifest is unreachable: the driver validated these exact bytes on this exact frame and
     * would have failed the operation otherwise, so reaching it means RenG's own derivation is not a
     * function — a defect, not a consumer condition.
     *
     * **The manifest is then completed with the TileJSON documents the compilation observed.** A
     * `url`-form source — 96 of the 98 tile sources across the verified style corpus — names a TileJSON
     * document rather than a template, and that document is fetched during `engine.prepare`, strictly
     * before this point. `completeBasemapStyleManifest` folds it back in so the source composes its tile
     * urls exactly as an inline one does; a source whose document never arrived stays degraded and
     * contributes nothing at all, rather than guessing a template that would fail closed anyway.
     */
    private fun completedStyleManifest(styleReference: StaticResourceReference.External): BasemapStyleManifest {
        val stored = residentCache.current(styleReference.resourceKey)?.stored
            ?: error("a successful style commit must leave the style document resident")
        val manifest = when (
            val derived = basemapEngineHost.styleManifest(
                styleKey = styleReference.resourceKey,
                stored = stored,
                baseUri = styleReference.locator.value,
            )
        ) {
            is BasemapStyleManifestOutcome.Derived -> derived.manifest
            is BasemapStyleManifestOutcome.Rejected ->
                error("the resource driver already validated this exact style document")
        }
        // The manifest the driver derived names each `url`-form source's TileJSON *document*, not its
        // tiles. Completing it here folds in the documents the firewall observed while the engine
        // compiled this exact content -- the same content digest both halves are keyed off -- so a
        // `url`-form source composes its tile urls exactly as an inline one does. A source whose document
        // never arrived stays degraded and contributes nothing, rather than guessing a template.
        return completeBasemapStyleManifest(
            manifest,
            basemapEngineHost.tileJsonDocuments(styleReference.resourceKey, stored.contentDigest),
        )
    }

    /**
     * Hands this frame's tiles to the Label handover, from inside the same invocation the style was
     * compiled in. Everything hard about the sequence -- the two rounds of preregistration, reading
     * `glyphUrls` before the plan is closed, closing it in a `finally`, and the three failures it
     * names -- belongs to [BasemapEngineHost.acquireLabelCandidates]; this supplies the two things
     * only the renderer holds and calls it once.
     *
     * **The label tile routes are the ground's own derivation over the label tile set.** A label layer
     * reads the vector tiles of the same sources the style declares, so [tileTimeRoutes] is already
     * the right function -- there is no second, label-specific route shape. On a frame that draws both,
     * the ground registered these same routes moments ago and this round is idempotent
     * ([OperationRegistry.preregister] takes an identical redeclaration without complaint); on a
     * `drawBasemap = false, drawLabels = true` frame it is the only round there is, which is precisely
     * why the handover registers them itself rather than trusting a caller to have done it.
     *
     * [BasemapStyleManifest.glyphTemplate] is RenG's own resolved copy and must stay so: `glyphUrls`
     * substitutes the caller's template, so the credential that reaches RenG's `Transport` is the one
     * in this string rather than one the engine composed. A style declaring no `glyphs` passes `null`,
     * which is a failure only if the engine nevertheless froze a non-empty closure -- the handover's
     * own judgement, not this call site's.
     *
     * ---
     *
     * **And it is called once per *tile set*, not once per frame.** Measured on a real context with a
     * stationary camera, the label path was 514-520 ms of a 605-610 ms `prepare()` -- ~85% of it, and
     * 5.5x the ground path over the same 17 tiles -- because nothing was retained: three acquisitions of
     * an identical tile set asked the consumer's `Transport` for all 17 label tiles and all 16 Glyph
     * Ranges every single time (17 -> 34 -> 51 and 16 -> 32 -> 48) and re-packed a **byte-identical**
     * atlas, the same `contentKey` all three times.
     *
     * **The key is [BasemapEngineHost.labelCandidateRequestKey] and RenG derives nothing of its own.**
     * Rentile publishes that key for precisely this cache, computed before any network from its own
     * compiled-style digest, the sorted de-duplicated tile identities, and a private label-semantics
     * version it bumps whenever a change to label evaluation, text layout or glyph packing would alter a
     * batch this key would otherwise leave looking unchanged. That last input is unreachable from here,
     * which is exactly why a key RenG derived itself would be wrong: it would be a second opinion about
     * someone else's cache validity, and it would keep looking valid on the day the engine's own answer
     * moved. What the key deliberately omits -- credentials, sessions, validators, the Glyph Closure --
     * is what makes it a *request* identity; [com.rohittp.rentile.LabelCandidateBatch.contentKey] is the
     * answer's, and RenG stores the atlas under it separately.
     *
     * **This is a cache and never Frame History, and `clearFrameHistory()` therefore leaves it alone.**
     * ADR 0035 draws the line in as many words: every cross-frame mechanism in this renderer except the
     * fade -- decoded images, uploaded textures, parsed GLBs, compiled shaders, resident tiles -- "changes
     * how fast a frame is produced, never how it looks", and this one is squarely on that side. Two
     * consecutive frames over one tile set draw the same labels whether or not the batch behind them was
     * re-fetched, so clearing it would make a history call into a cache-invalidation call as well, and
     * `CONTEXT.md`'s Frame History entry already says the opposite twice: `clearFrameHistory()` "neither
     * frees resources nor invalidates prepared frames", and its own `_Avoid_` list names "Cache". The
     * resident cache, which suppresses far more consumer traffic than this does, is likewise untouched
     * by it; `freeResources` and `close()` are the calls that own caches.
     *
     * **What it holds contradicts no GL opinion, because it holds no GL object.** The batch is an
     * immutable Rentile value -- candidates, layer styles, atlas PNG bytes, a content key, diagnostics --
     * with no `close()` and nothing engine-side behind it, unlike a `PreparedBatch`. The atlas's *texture*
     * lives where every other texture does, in [glObjectRegistry] under the content-derived key
     * `ResourceKeyDeriver.glyphAtlas`, so `freeResources`, LRU eviction, `notifyGpuObjectsGone` and
     * `close()` all keep exactly the authority over it they already had: a frame that lost the texture
     * re-uploads it from [RetainedGlyphAtlas], and a frame that lost both decodes it again from bytes
     * that are still correct. `close()` drops both retentions as the CPU state they are.
     */
    private suspend fun labelHandover(
        manifest: BasemapStyleManifest,
        style: PreparedStyle,
        labelTiles: List<CanonicalBasemapTile>,
        accessMode: ResourceAccessMode,
    ): RetainedLabelHandover {
        val requestKey = basemapEngineHost.labelCandidateRequestKey(style, labelTiles)
        val handover = retainedLabelHandover?.takeIf { it.requestKey == requestKey } ?: run {
            val acquired = basemapEngineHost.acquireLabelCandidates(
                style = style,
                tiles = labelTiles,
                glyphTemplate = manifest.glyphTemplate,
                labelTileRoutes = tileTimeRoutes(
                    manifest = manifest,
                    tiles = labelTiles,
                    accessMode = accessMode,
                    limits = configuration.resourceLimits,
                ),
                limits = configuration.resourceLimits,
            )
            // Committed only once the acquisition has fully succeeded, exactly as the LOD and the fade
            // are. A throw leaves the previous entry standing, which is correct rather than merely
            // convenient: it is keyed on a request this frame is not making, so it can only ever be
            // served to a later frame that asks the same question again.
            RetainedLabelHandover(
                requestKey = requestKey,
                candidates = acquired,
                spriteAtlas = basemapEngineHost.spriteAtlasManifest(),
            ).also { retainedLabelHandover = it }
        }
        // ADR 0036's "once per prepare", and this call site is the whole of the mechanism: the
        // handover runs once per prepare, so an aggregate emitted beside it does too, however many
        // exclusions the batch reported and however many layers or tiles they came from. The batch's
        // own diagnostics never travel further than this line -- `reportLabelContentExclusions`
        // returns nothing, and what it emits is RenG's own code with a severity and no other field.
        //
        // **Deliberately outside the `run` block**, so a frame served from the retained handover reports
        // exactly what a frame that re-acquired it would. The aggregate describes the content this frame
        // is drawing, not the exchange that fetched it, and a consumer whose warning appeared on frame 0
        // and vanished on frame 1 would be reading RenG's cache state rather than its own style.
        reportLabelContentExclusions(handover.candidates.batch.diagnostics, configuration.diagnosticSink)
        return handover
    }

    override suspend fun cancelPreparations() {
        preparationSession.cancelSnapshotAndJoin()
        val outcome = driver.run(RendererLifecycleOperation.CancelPreparations) { null }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
    }

    override fun clearFrameHistory() {
        val outcome = driver.run(RendererLifecycleOperation.ClearFrameHistory) { operation ->
            if (operation == RendererLifecycleOperation.ClearFrameHistory) {
                previousFrameIndex = null
                previousEncodedPlan = null
                previousSelectedLod = null
                // The hint's LOD chain starts where prepare's now does.
                readAheadCoordinator.readAheadSnapshot = readAheadCoordinator.readAheadSnapshot?.withSelectedLod(null)
                previousLabelFade = LabelFadeState.EMPTY
                // History, not a cache: ADR 0070's settled label tile set decides which candidates the
                // next frame plans over, so keeping it would make the frame after a clear depend on
                // the frames before it (ADR 0035).
                previousLabelTiles = null
                // `retainedLabelHandover`, `retainedGlyphAtlas` and `retainedSpriteAtlas` are
                // deliberately NOT cleared here,
                // and the omission is a decision rather than an oversight: they are caches, this call
                // clears history, and CONTEXT.md says a history clear "neither frees resources nor
                // invalidates prepared frames". ADR 0035's rule -- after `clearFrameHistory()` the render
                // is a pure function of the plan -- still holds, because neither of them can change what
                // a frame looks like. `labelHandover` carries the full argument.
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
        var report: ResourceReport? = null
        val outcome = driver.run(RendererLifecycleOperation.QueryResources(selector)) { operation ->
            if (operation is RendererLifecycleOperation.QueryResources) {
                report = resourceReportIncludingGpuOnlyOwners(operation.selector)
                null
            } else {
                unexpectedOperation(operation)
            }
        }
        return when (outcome) {
            RendererLifecycleOutcome.EmptyResourceResult -> emptyResourceReport()
            // The cache owns raw bytes and decoded pixels; the registry owns GL handles and is the
            // only thing that can answer for GPU bytes. Passing the lookup rather than the registry
            // is what keeps the cache free of GL knowledge -- and it is why the report can now say
            // "I do not know" about a sticker's texture instead of the literal zero it used to
            // assert while that texture was resident.
            RendererLifecycleOutcome.Succeeded, RendererLifecycleOutcome.NoOp ->
                requireNotNull(report) { "a successful resource query must have run its permitted operation" }
            is RendererLifecycleOutcome.Failed -> throw outcome.failure.toException()
        }
    }

    override fun queryMetrics(): MetricReport {
        val outcome = driver.run(RendererLifecycleOperation.QueryMetrics) { null }
        return when (outcome) {
            // A closed renderer answers empty rather than its final totals. The counters are alive
            // only while the renderer is, and reporting a frozen history would make a monotonic
            // series look as though it had simply stopped moving -- ADR 0049.
            RendererLifecycleOutcome.EmptyResourceResult -> MetricReport(emptyMap())
            RendererLifecycleOutcome.Succeeded, RendererLifecycleOutcome.NoOp -> metricRecorder.snapshot()
            is RendererLifecycleOutcome.Failed -> throw outcome.failure.toException()
        }
    }

    override fun freeResources(selector: ResourceSelector): ResourceFreeResult {
        var result: ResourceFreeResult? = null
        val outcome = driver.run(RendererLifecycleOperation.FreeResources(selector)) { operation ->
            if (operation is RendererLifecycleOperation.FreeResources) {
                if (driver.snapshot.gpuLedger.hasLiveGpuObjects) {
                    requireContextThread(PipelineStage.RESOURCE_FREE)
                }
                val generations = residentCache.generationIds(operation.selector)
                val gpu = glObjectRegistry.retireResources(operation.selector, generations, binding)
                val cpu = residentCache.free(operation.selector)
                // The tiles the read-ahead worker drew are resources the selector can name too (ADR
                // 0077). They are CPU pixels with no GL object, so this needs no context, and they are
                // not in the resident cache, so they add nothing to its counts.
                readAheadCoordinator.renderedTileCache?.free(operation.selector)
                result = mergeFreeResults(cpu, generations, gpu)
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

    private fun resourceReportIncludingGpuOnlyOwners(selector: ResourceSelector): ResourceReport {
        val cpu = residentCache.report(selector, glObjectRegistry::gpuByteAccount)
        val cpuEntries = cpu.entries
        val cpuKeys = cpuEntries.mapTo(hashSetOf()) { it.key }
        val gpuOnlyEntries = glObjectRegistry.allocationSnapshots(selector)
            .filterNot { it.key in cpuKeys }
            .map { snapshot ->
                ResourceReportEntry(
                    key = snapshot.key,
                    residentGenerationCount = snapshot.residentGenerationCount,
                    retiredGenerationCount = snapshot.retiredGenerationCount,
                    leaseCount = snapshot.leaseCount,
                    reloadRequired = snapshot.retiredGenerationCount > 0,
                    usage = ResourceUsage(
                        rawBytes = 0L,
                        decodedCpuBytes = 0L,
                        knownGpuBytes = snapshot.knownBytes,
                        hasUnknownGpuBytes = false,
                    ),
                )
            }
        if (gpuOnlyEntries.isEmpty()) return cpu
        val entries = cpuEntries + gpuOnlyEntries
        return ResourceReport(
            entries = entries,
            totals = ResourceUsage(
                rawBytes = entries.sumOf { it.usage.rawBytes },
                decodedCpuBytes = entries.sumOf { it.usage.decodedCpuBytes },
                knownGpuBytes = entries.sumOf { it.usage.knownGpuBytes ?: 0L },
                hasUnknownGpuBytes = entries.any { it.usage.hasUnknownGpuBytes },
            ),
            cpuResidency = cpu.cpuResidency,
        )
    }

    private fun mergeFreeResults(
        cpu: ResourceFreeResult,
        generations: Map<ResourceKey, Set<ResidentGenerationId>>,
        gpu: GpuGenerationRetirement,
    ): ResourceFreeResult {
        val cpuKeys = generations.keys
        val gpuOnlyKeys = gpu.matchedOwnerKeys - cpuKeys
        val formerlyAlreadyFree = gpu.matchedOwnerKeys.filterTo(linkedSetOf()) { key ->
            key in cpuKeys && generations[key].orEmpty().isEmpty()
        }
        val additional = gpuOnlyKeys + formerlyAlreadyFree
        val additionalDeferred = additional.count { it in gpu.deferredOwnerKeys }
        return ResourceFreeResult(
            matchedKeys = cpu.matchedKeys + gpuOnlyKeys.size,
            fullyFreedKeys = cpu.fullyFreedKeys + additional.size - additionalDeferred,
            deferredKeys = cpu.deferredKeys + additionalDeferred,
            alreadyFreeKeys = cpu.alreadyFreeKeys - formerlyAlreadyFree.size,
        )
    }

    override fun notifyGpuObjectsGone() {
        val outcome = driver.run(RendererLifecycleOperation.NotifyGpuObjectsGone) { operation ->
            if (operation == RendererLifecycleOperation.NotifyGpuObjectsGone) {
                forgetRendererGpuObjects()
                null
            } else {
                unexpectedOperation(operation)
            }
        }
        if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
    }

    /** Runs inside [GlLifecycleDriver]'s operation gate; declared loss never issues a GL delete. */
    private fun forgetRendererGpuObjects() {
        offscreenSurface = null
        compositePipeline = null
        stickerPipeline = null
        backdropPipeline = null
        groundPipeline = null
        labelPipeline = null
        iconPipeline = null
        globeGroundPipeline = null
        bloomResourceManager.forgetContext()
        consumerPipelines.forgetAll()
        modelPipelines.clear()
    }

    override fun adoptCurrentRenderContext(): Unit = driver.withOperationGate {
        // Adoption is the one entry point allowed to move the renderer between threads, but only a
        // successful adoption is a declaration. Stage the candidate until both lifecycle adoption
        // and core-object recreation have succeeded so a refused call cannot silently move a live
        // renderer and let later GL work run on the wrong thread.
        val adoptionThread = currentCallingThread()
        val outcome = driver.run(RendererLifecycleOperation.AdoptCurrentRenderContext) { null }
        when (outcome) {
            is RendererLifecycleOutcome.Failed -> throw outcome.failure.toException()
            RendererLifecycleOutcome.Succeeded -> {
                val profile = requireNotNull(driver.profile) {
                    "a successful adoption must have recorded a profile"
                }
                when (
                    val recreated = createInternalGlState(binding, profile, programs, configuration)
                ) {
                    is InternalGlStateResult.Created -> {
                        offscreenSurface = recreated.state.offscreenSurface
                        compositePipeline = recreated.state.compositePipeline
                        stickerPipeline = recreated.state.stickerPipeline
                        backdropPipeline = recreated.state.backdropPipeline
                        groundPipeline = recreated.state.groundPipeline
                        labelPipeline = recreated.state.labelPipeline
                        iconPipeline = recreated.state.iconPipeline
                        bloomResourceManager.forgetContext()
                        // The lifecycle adoption establishes the context first, while this call
                        // establishes that the replacement context now owns deletable objects. Both
                        // happen under the outer operation gate, so free/close can observe neither
                        // half alone; from this point onward they must validate the exact context.
                        driver.recordCoreGpuObjectsCreated()
                        contextThread = adoptionThread
                    }

                    is InternalGlStateResult.Failed -> {
                        // Nothing was actually recreated: push the machine back to
                        // AWAITING_CONTEXT_ADOPTION rather than leaving it LIVE with no offscreen
                        // surface, then report the real failure.
                        driver.run(RendererLifecycleOperation.NotifyGpuObjectsGone) { operation ->
                            if (operation == RendererLifecycleOperation.NotifyGpuObjectsGone) {
                                forgetRendererGpuObjects()
                                null
                            } else {
                                unexpectedOperation(operation)
                            }
                        }
                        throw recreated.failure.toException()
                    }
                }
            }

            RendererLifecycleOutcome.NoOp, RendererLifecycleOutcome.EmptyResourceResult -> Unit
        }
    }

    override fun mintRenderTarget(framebufferName: FramebufferName): RenderTarget =
        driver.withOperationGate {
            requireContextThread(PipelineStage.RENDER_TARGET)
            val outcome = driver.run(RendererLifecycleOperation.MintRenderTarget(framebufferName)) { null }
            if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
            RenGRenderTarget(ownerIdentity, framebufferName, driver.snapshot.contextGeneration)
        }

    // ---- Drawing ----------------------------------------------------------------------------------

    override fun draw(preparedFrame: PreparedFrame, renderTarget: RenderTarget) {
        val stagedDiagnostics = mutableListOf<Diagnostic>()
        val outcome = driver.withOperationGate {
            requireContextThread(PipelineStage.DRAW)
            val frameFact = when {
                preparedFrame !is RenGPreparedFrame || preparedFrame.ownerIdentity !== ownerIdentity ->
                    PreparedFrameFact.Foreign
                else -> preparedFrame.fact()
            }
            val targetFact = when {
                renderTarget !is RenGRenderTarget || renderTarget.ownerIdentity !== ownerIdentity ->
                    RenderTargetFact.Foreign
                renderTarget.mintedAtGeneration != driver.snapshot.contextGeneration -> RenderTargetFact.Stale
                else -> RenderTargetFact.OwnedCurrent(renderTarget.framebufferName)
            }
            driver.run(RendererLifecycleOperation.Draw(frameFact, targetFact)) { operation ->
                val drawOperation = operation as? RendererLifecycleOperation.Draw ?: unexpectedOperation(operation)
                val ownedTarget = drawOperation.target as RenderTargetFact.OwnedCurrent
                performDraw(
                    preparedFrame as RenGPreparedFrame,
                    ownedTarget.framebufferName,
                    stagedDiagnostics,
                )
            }
        }
        if (outcome is RendererLifecycleOutcome.Failed) {
            // The typed draw failure wins over the sink (the rule `performDraw` states for its own
            // diagnostics): a frame that failed still tells the consumer what it staged, but a sink
            // that throws while doing so is attached as suppressed rather than replacing the failure.
            val failure = outcome.failure.toException()
            try {
                stagedDiagnostics.forEach(configuration.diagnosticSink::emit)
            } catch (@Suppress("TooGenericExceptionCaught") sinkFailure: Throwable) {
                failure.addSuppressed(sinkFailure)
            }
            throw failure
        }
        stagedDiagnostics.forEach(configuration.diagnosticSink::emit)
    }

    /**
     * Runs as the `Draw` operation's permitted-operation executor, which only ever runs once the
     * state machine has already proved the exact context is current and the target framebuffer is
     * complete. Everything here is therefore real GL work: uploading each sticker's decoded image,
     * compiling (or reusing) each distinct geometry program, and drawing the assembled [Scene]
     * through [drawFrame].
     */
    private fun performDraw(
        frame: RenGPreparedFrame,
        framebufferName: FramebufferName,
        stagedDiagnostics: MutableList<Diagnostic>,
    ): FailureDescriptor? {
        if (frame.drawBasemap && configuration.basemapStyle == null && !basemapWarningEmitted) {
            basemapWarningEmitted = true
            stagedDiagnostics += basemapNotConfiguredDiagnostic()
        }

        val profile = requireNotNull(driver.profile) { "drawing requires an adopted profile" }
        val surface = requireNotNull(offscreenSurface) { "drawing requires an offscreen surface" }
        val composite = requireNotNull(compositePipeline) { "drawing requires the composite pipeline" }
        val sticker = requireNotNull(stickerPipeline) { "drawing requires the sticker pipeline" }
        val backdrop = requireNotNull(backdropPipeline) { "drawing requires the backdrop pipeline" }
        val ground = requireNotNull(groundPipeline) { "drawing requires the ground pipeline" }
        val label = requireNotNull(labelPipeline) { "drawing requires the label pipeline" }
        val icon = requireNotNull(iconPipeline) { "drawing requires the icon pipeline" }

        val resolvedCamera = resolveFrameCamera(frame.camera, frame.projectionMode)

        // Every ground texture lease this draw takes is released in the `finally` below -- on the
        // failure returns inside, and on a throw out of drawFrame. An unreleased lease is permanently
        // exempt from eviction (`GlObjectRegistry.evictOverBudget` iterates only unleased keys), so a
        // leaked one is a texture the byte budget can never reclaim for the renderer's whole life.
        val textureLeases = ArrayList<TextureLease>(frame.basemapTiles.size + 1)
        val gpuAllocationLeases = ArrayList<GpuAllocationLease>()
        val consumerPipelineLeases = ArrayList<ConsumerPipelineLease<*>>()
        var unrelievedResidency: GpuTextureResidency? = null
        val failure = try {
            // The captured region opens HERE, not inside drawFrame (ADR 0054). `resolveGroundTiles`
            // uploads every ground tile that is not already resident, and an upload ends by binding
            // its new texture without putting back what was there — on whatever unit the host left
            // active. Capturing after that recorded RenG's own texture as if it were the host's and
            // faithfully restored it, losing the host's binding: measured at four of a frame's
            // twenty-four texture binds landing before the capture began.
            //
            // `drawFrame` still opens a region of its own for every other caller, and finds this one
            // already open and joins it.
            withCapturedGlState(binding, profile) { captured ->
                when (val resolved = resolveGroundTiles(frame, textureLeases, captured)) {
                    is GroundTilesResult.Failed -> resolved.failure
                    is GroundTilesResult.Resolved -> {
                    // ADR 0057's "and say so", emitted before the draw rather than after it so that
                    // a frame which then fails has still told the consumer what it was about to
                    // present. One per frame, never one per tile.
                    if (resolved.provisionalTileCount > 0) {
                        stagedDiagnostics += groundPresentedProvisionallyDiagnostic(resolved.provisionalTileCount)
                    }
                    drawResolvedFrame(
                        backdropPipeline = backdrop,
                        frame = frame,
                        framebufferName = framebufferName,
                        profile = profile,
                        surface = surface,
                        composite = composite,
                        sticker = sticker,
                        ground = ground,
                        label = label,
                        icon = icon,
                        resolvedCamera = resolvedCamera,
                        sceneGroundTiles = resolved.tiles,
                        textureLeases = textureLeases,
                        gpuAllocationLeases = gpuAllocationLeases,
                        consumerPipelineLeases = consumerPipelineLeases,
                        stagedDiagnostics = stagedDiagnostics,
                        binding = captured,
                    )
                }
                }
            }
        } finally {
            consumerPipelineLeases.forEach { lease ->
                consumerPipelines.release(binding, programs, lease)
            }
            gpuAllocationLeases.forEach { lease ->
                glObjectRegistry.releaseAllocation(lease, binding)
            }
            unrelievedResidency = releaseTextureLeases(textureLeases)
        }
        // Only on a frame that actually drew. A failing draw reports its own typed failure, and
        // reaching into the consumer's sink while that failure is on its way out would add noise to
        // the one moment they are least able to use it -- and, from the `finally` above, could
        // replace the failure entirely with whatever a throwing sink raises.
        if (failure == null) {
            unrelievedResidency?.let { residency ->
                stagedDiagnostics += residentGpuTexturesOverBudgetDiagnostic(
                    residentBytes = residency.residentBytes,
                    budgetBytes = residency.budgetBytes,
                )
            }
        }
        return failure
    }

    /**
     * Releases every texture lease this draw took -- its ground tiles and, since E-labels, its glyph
     * atlas -- and reports the worst residency that eviction could not bring under budget, or `null` if
     * it always could.
     *
     * **One reading per frame, not one per texture.** Each release runs an eviction pass, so a frame
     * 39 tiles past the budget reaches the "out of unleased candidates, still over budget" exit 39
     * times -- 39 observations of one condition. This collapses them to the worst reading, and
     * `performDraw` emits at most one diagnostic from it. The maximum rather than the first, though
     * the two coincide today, because "the worst it got" should not depend on an ordering property
     * of a loop somewhere else.
     */
    private fun releaseTextureLeases(leases: List<TextureLease>): GpuTextureResidency? {
        var worst: GpuTextureResidency? = null
        for (lease in leases) {
            val residency = glObjectRegistry.releaseLease(lease, binding)
            if (!residency.overBudget) continue
            if (worst == null || residency.residentBytes > worst.residentBytes) worst = residency
        }
        return worst
    }

    /**
     * The rest of one draw, once its ground textures are in hand: upload each sticker's image, compile
     * or reuse each distinct geometry program, and hand the assembled [Scene] to [drawFrame].
     *
     * Split out of [performDraw] only so that the ground leases [performDraw] holds are released by one
     * `finally` covering every exit from this body, including its own failure returns.
     */
    @Suppress("LongParameterList")
    private fun drawResolvedFrame(
        frame: RenGPreparedFrame,
        framebufferName: FramebufferName,
        profile: RenderContextProfile,
        surface: OffscreenSurface,
        composite: CompositePipeline,
        sticker: StickerPipeline,
        ground: GroundPipeline,
        label: LabelPipeline,
        icon: IconPipeline,
        backdropPipeline: BackdropPipeline,
        resolvedCamera: ResolvedFrameCamera,
        sceneGroundTiles: List<SceneGroundTile>,
        textureLeases: MutableList<TextureLease>,
        gpuAllocationLeases: MutableList<GpuAllocationLease>,
        consumerPipelineLeases: MutableList<ConsumerPipelineLease<*>>,
        stagedDiagnostics: MutableList<Diagnostic>,
        /** The captured binding (ADR 0054), shadowing the renderer's own for this draw. */
        binding: GlBinding,
    ): FailureDescriptor? {
        val sceneStickers = frame.stickers.map { preparedSticker ->
            val texture = when (val image = preparedSticker.image) {
                is DecodedImage -> contentTextures.cachedTexture(
                    key = preparedSticker.resourceKey,
                    generationId = preparedSticker.generationId,
                    image = image,
                    content = TextureContent.IMAGE,
                    leases = gpuAllocationLeases,
                    binding = binding,
                )
                is CompressedImage -> contentTextures.cachedCompressedTexture(
                    key = preparedSticker.resourceKey,
                    generationId = preparedSticker.generationId,
                    image = image,
                    native = profile.supportsEtc2,
                    leases = gpuAllocationLeases,
                    binding = binding,
                )
            }
            SceneSticker(
                placement = preparedSticker.placement,
                texture = texture,
                imageWidthPixels = preparedSticker.image.width,
                imageHeightPixels = preparedSticker.image.height,
                opacity = preparedSticker.opacity,
                blend = preparedSticker.blend,
            )
        }

        val sceneGeometries = ArrayList<SceneGeometry>(frame.geometries.size)
        for (preparedGeometry in frame.geometries) {
            val geometry = preparedGeometry.geometry
            val key = geometryKeyDeriver.geometryProgram(geometry.shaderPair).key
            val pipelineLease = consumerPipelines.leaseGeometry(key) ?: when (
                val result = createGeometryPipeline(binding, profile.dialect, programs, geometry.shaderPair)
            ) {
                is GeometryPipelineResult.Created -> consumerPipelines.registerGeometry(result.pipeline)
                is GeometryPipelineResult.Failed -> return result.failure
            }
            consumerPipelineLeases += pipelineLease
            val consumerTextures = preparedGeometry.consumerTextures.mapValues { (_, texture) ->
                contentTextures.cachedTexture(
                    key = texture.resourceKey,
                    generationId = texture.generationId,
                    image = texture.image,
                    content = TextureContent.DATA,
                    leases = gpuAllocationLeases,
                    binding = binding,
                )
            }
            sceneGeometries += SceneGeometry(
                geometry = geometry,
                pipeline = pipelineLease.value,
                consumerUniforms = preparedGeometry.uniformsSnapshot,
                consumerTextures = consumerTextures,
            )
        }

        val preparedModels = frame.models
        if (preparedModels.isNotEmpty() && modelPipelines.isEmpty()) {
            allModelShaderVariants().forEach { variant ->
                when (val result = createModelPipeline(binding, profile.dialect, programs, variant)) {
                    is ModelPipelineResult.Created -> modelPipelines[variant] = result.pipeline
                    is ModelPipelineResult.Failed -> return result.failure
                }
            }
        }
        val sceneModels = ArrayList<SceneModel>(preparedModels.size)
        for (preparedModel in preparedModels) {
            sceneModels += sceneModel(preparedModel, gpuAllocationLeases, binding)
        }

        // The planner's own lists are handed to the Scene whole. Two `filterIsInstance` calls used to
        // stand here, dropping every ModelAt reference so a FramePlan carrying a Model would render
        // nothing rather than fail Scene's bijection check -- correct for exactly as long as no
        // SceneModel reached the Scene, and a silent un-drawing of every model the moment one did.
        val scene = Scene(
            outputPixelSize = configuration.outputPixelSize,
            frameIndex = frame.frameIndex,
            stickers = sceneStickers,
            geometries = sceneGeometries,
            groundTiles = sceneGroundTiles,
            terrain = sceneTerrain(frame),
            models = sceneModels,
            labels = sceneLabels(frame, textureLeases, binding),
            icons = sceneIcons(frame, textureLeases, binding),
            mapOrder = frame.mapOrder,
            screenOrder = frame.screenOrder,
        )
        val frameBloom = frame.bloom
        val resolvedBloomResources = if (
            frameBloom != null && sceneGeometries.any { it.geometry.emissive }
        ) {
            bloomResourceManager.resolveBloomResources(
                bloom = frameBloom,
                emitters = sceneGeometries.map { it.geometry },
                profile = profile,
                depthRenderbuffer = surface.depthRenderbuffer,
                stagedDiagnostics = stagedDiagnostics,
                binding = binding,
                bloomFarBandStrategy = bloomFarBandStrategy,
            )
        } else {
            null
        }
        // Compiled on the first globe frame that actually has ground to draw, and never on a
        // mercator one: a `Failed` here is this draw's failure, exactly as a model pipeline's is a
        // few lines above.
        val globeGround = if (resolvedCamera is ResolvedGlobeCamera && sceneGroundTiles.isNotEmpty()) {
            globeGroundPipeline ?: when (
                val result = createGlobeGroundPipeline(
                    binding,
                    profile.dialect,
                    programs,
                    terrainShading = configuration.terrainShading,
                )
            ) {
                is GlobeGroundPipelineResult.Created -> result.pipeline.also { globeGroundPipeline = it }
                is GlobeGroundPipelineResult.Failed -> return result.failure
            }
        } else {
            null
        }

        // ADR 0068. Uploaded through the same texture cache every other consumer image uses, so an
        // unchanged backdrop costs one upload for the life of the renderer, but with its own
        // wrapping sampler -- it is the only texture RenG repeats.
        val resolvedBackdrop = when (val prepared = frame.backdrop) {
            null -> null
            is PreparedBackdrop.Pattern -> ResolvedBackdrop.Pattern(
                pipeline = backdropPipeline,
                texture = contentTextures.cachedTexture(
                    key = prepared.resourceKey,
                    generationId = prepared.generationId,
                    image = prepared.image,
                    content = TextureContent.IMAGE,
                    leases = gpuAllocationLeases,
                    binding = binding,
                    sampler = BACKDROP_SAMPLER_STATE,
                ),
                repeat = resolvedBackdropFor(
                    outputPixelSize = resolvedCamera.outputPixelSize,
                    tileSizeLogicalPixels = prepared.tileSizeLogicalPixels,
                ),
            )
            // Memoised by shader source across frames, exactly as a geometry's program is: the two
            // maps are cleared and deleted on the same three occasions.
            is PreparedBackdrop.Shader -> {
                val key = geometryKeyDeriver
                    .internalPipeline(InternalPipelineRole.CONSUMER_BACKDROP, prepared.shaderPair).key
                val pipelineLease = consumerPipelines.leaseBackdrop(key) ?: when (
                    val result = createConsumerBackdropPipeline(
                        binding, profile.dialect, programs, prepared.shaderPair,
                    )
                ) {
                    is ConsumerBackdropPipelineResult.Created ->
                        consumerPipelines.registerBackdrop(result.pipeline)
                    is ConsumerBackdropPipelineResult.Failed -> return result.failure
                }
                consumerPipelineLeases += pipelineLease
                ResolvedBackdrop.Shader(
                    pipeline = pipelineLease.value,
                    uniforms = prepared.uniforms,
                    textures = prepared.consumerTextures.mapValues { (_, texture) ->
                        contentTextures.cachedTexture(
                            key = texture.resourceKey,
                            generationId = texture.generationId,
                            image = texture.image,
                            content = TextureContent.DATA,
                            leases = gpuAllocationLeases,
                            binding = binding,
                        )
                    },
                    // ADR 0072. The inverse of exactly the product a geometry is handed forward,
                    // and the same two factors in both projection modes.
                    inverseViewProjection = composeBackdropInverseViewProjection(resolvedCamera),
                )
            }
        }

        val content = SceneContent(
            camera = resolvedCamera,
            scene = scene,
            stickerPipeline = sticker,
            groundPipeline = ground,
            modelPipelines = modelPipelines,
            labelPipeline = label,
            iconPipeline = icon,
            globeGroundPipeline = globeGround,
            backdrop = resolvedBackdrop,
            bloom = frameBloom,
            bloomResources = resolvedBloomResources,
            surfaceFramebuffer = surface.framebuffer,
            surfaceDepthRenderbuffer = surface.depthRenderbuffer,
            bloomFarBandStrategy = bloomFarBandStrategy,
        )

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
     * This frame's terrain in the form the ground pass consumes, or `null` when it has none.
     *
     * **The granularity is derived here rather than in the GL layer because this is where the camera
     * is legible.** `terrainCellsPerTileSide` needs the plan's own zoom, latitude, projection mode
     * and selected LOD; `SceneContent` holds a resolved camera whose zoom has already been spent
     * into matrices. What crosses is terrain's *claim* on the granularity — `SceneContent`
     * reconciles it with the globe's curvature claim, because a frame draws its whole ground at one
     * granularity or a sliver of background shows between two tiles that disagree.
     *
     * The LOD is the frame's own rather than any tile's (ADR 0066). It used to be read off the first
     * ground instance, on the premise that both tile selectors emit one LOD per frame; ADR 0065 ended
     * that for Mercator, and `first()` became the coarsest band at the top of the frame. A frame with
     * no ground instances still returns `null` above before reaching here.
     */
    private fun sceneTerrain(frame: RenGPreparedFrame): SceneTerrain? {
        val terrain = frame.terrain ?: return null
        if (frame.groundInstances.isEmpty()) return null
        val selectedLod = frame.groundSelectedLod ?: return null
        return SceneTerrain(
            decode = demDecodeCoefficients(terrain.encoding),
            interiorSizePx = terrain.tileSizePx,
            exaggeration = terrain.exaggeration,
            cellsPerTileSide = terrainCellsPerTileSide(
                terrain = TerrainGranularityInputs(terrain.tileSizePx),
                projectionMode = frame.projectionMode,
                zoom = frame.camera.zoom,
                latitude = frame.camera.latitude,
                selectedLod = selectedLod,
            ),
            // Built during `prepare()` and carried, never rebuilt here: a label anchor rode this
            // exact object before the frame existed, and a second construction at draw time would be
            // a second answer to "which of this frame's tiles displace".
            surface = frame.groundSurface,
        )
    }

    /**
     * ADR 0034's fourth scene list, assembled from what `prepare()` already decided.
     *
     * **One batch, because a frame's text shares one atlas.** A [LabelBatch] is per *texture* -- the
     * one thing a single `glDrawElements` cannot vary -- and every glyph a `LabelCandidateBatch` hands
     * over is packed into that batch's single atlas. So this flattens every surviving label's quads
     * into one draw, in [PreparedLabelFrame.labels] order, which is [placeLabels]' own
     * lowest-priority-first order: where two labels legitimately share pixels, the one that would have
     * won the collision is the one that paints last. Nothing here sorts, places or fades anything --
     * all three happened during `prepare()`, and ADR 0035 requires that rather than merely allowing it.
     *
     * **The atlas is resident, not re-uploaded.** [uploadGlyphAtlas] leases what is already on the GPU
     * and uploads only on a miss, exactly as a ground tile does, and the lease joins the same list
     * [performDraw]'s `finally` releases. That lease is what makes the atlas unevictable for the
     * duration of this draw: a frame cannot lose the texture it is sampling to a sibling tile's
     * pressure, and after the release it is an ordinary least-recently-used tenant of the same
     * [com.rohittp.reng.ResourceLimits.maximumResidentGpuTextureBytes] budget the tiles compete for.
     */
    private fun sceneLabels(
        frame: RenGPreparedFrame,
        textureLeases: MutableList<TextureLease>,
        /** The captured binding (ADR 0054): an upload binds on the host's active unit. */
        binding: GlBinding,
    ): List<LabelBatch> {
        val prepared = frame.labels ?: return emptyList()
        val atlas = uploadGlyphAtlas(binding, glObjectRegistry, prepared.atlasKey, prepared.atlas)
        textureLeases += atlas.lease
        return listOf(
            LabelBatch(
                atlasTexture = atlas.handle.name,
                quads = prepared.labels.flatMap { it.quads },
            ),
        )
    }

    /**
     * Phase 5's icon half, assembled from what `prepare()` already decided.
     *
     * **One batch, because a style declares at most one sprite pair.** An [IconBatch] is per texture
     * for [LabelBatch]'s reason, and every icon in a frame resolved against the one manifest the
     * firewall retained, so every icon samples the one atlas. The quads are handed over in
     * [PreparedLabelFrame.icons] order, which is the placement pass's own -- nothing here sorts,
     * places or fades anything.
     *
     * **The atlas is resident, not re-uploaded**, and its lease joins the same list [performDraw]'s
     * `finally` releases, exactly as the glyph atlas's does. It is enrolled in the same
     * [com.rohittp.reng.ResourceLimits.maximumResidentGpuTextureBytes] budget, so a sprite atlas, a
     * glyph atlas and the ground's tiles compete honestly rather than one of them being exempt.
     */
    private fun sceneIcons(
        frame: RenGPreparedFrame,
        textureLeases: MutableList<TextureLease>,
        /** The captured binding (ADR 0054): an upload binds on the host's active unit. */
        binding: GlBinding,
    ): List<IconBatch> {
        val prepared = frame.labels ?: return emptyList()
        val key = prepared.spriteAtlasKey ?: return emptyList()
        val image = requireNotNull(prepared.spriteAtlas) {
            "a prepared frame naming a sprite atlas must carry its pixels"
        }
        val atlas = uploadSpriteAtlas(binding, glObjectRegistry, key, image)
        textureLeases += atlas.lease
        return listOf(IconBatch(atlasTexture = atlas.handle.name, quads = prepared.icons))
    }

    /**
     * One [PreparedModel] with everything GL-shaped in hand: its primitives uploaded, its embedded
     * images uploaded, and its override — if it has one — uploaded too.
     *
     * **Uploaded once, by key, exactly as a sticker's image is.** A primitive is keyed by
     * `ResourceKeyDeriver.modelGeometry` and an embedded image by `.modelImage`, both namespaced by the
     * model's own GLB key, so a second draw of the same prepared frame — or a later frame over the same
     * GLB — reuses what is already on the GPU rather than uploading it again. Two draw items instancing
     * one mesh derive one key and therefore share one [UploadedPrimitive], which is what
     * [SceneModel.uploaded]'s `(meshIndex, primitiveIndex)` key means.
     *
     * **[SceneModel.imageTextures] stays index-parallel with `model.images`, including the entries an
     * override makes unreachable.** Uploading only the images some material names — or none at all when
     * an override replaces every one of them — would be cheaper and would break that contract, and the
     * draw path indexes this list by a material's own `baseColourImageIndex`. A GLB's embedded images
     * are already decoded in CPU memory by the time this runs; the residency saved is not worth a list
     * whose indices mean something different from what its type says.
     *
     * Each image takes the sampler of the first material that names it, and glTF's own default
     * otherwise. One image sampled by two textures with two different samplers therefore gets the first
     * — a real ambiguity in keying a GPU texture by image index rather than by texture index, and the
     * one `ResourceKeyDeriver.modelImage` is defined in terms of.
     *
     * The override uploads as [TextureContent.IMAGE], not `DATA`, and so do the embedded images: the
     * textured model fragment shader un-premultiplies the texel it samples, so a texture uploaded
     * without premultiplication renders every partially transparent surface too dark.
     */
    private fun sceneModel(
        preparedModel: PreparedModel,
        leases: MutableList<GpuAllocationLease>,
        binding: GlBinding,
    ): SceneModel {
        val decoded = preparedModel.model
        val uploaded = HashMap<Pair<Int, Int>, UploadedPrimitive>(decoded.drawItems.size)
        for (item in decoded.drawItems) {
            val meshPrimitive = item.meshIndex to item.primitiveIndex
            if (meshPrimitive in uploaded) continue
            val identity = GpuResourceIdentity(
                ownerKey = preparedModel.glbKey,
                generationId = preparedModel.glbGenerationId,
                subresource = GpuSubresource.ModelPrimitive(item.meshIndex, item.primitiveIndex),
            )
            val resident = glObjectRegistry.leaseAllocation(identity)
            if (resident != null) {
                leases += resident.lease
                uploaded[meshPrimitive] = resident.payload as UploadedPrimitive
            } else {
                val decodedPrimitive = decoded.primitiveFor(item)
                val primitive = uploadModelPrimitive(binding, decodedPrimitive)
                leases += glObjectRegistry.registerAllocation(
                    identity = identity,
                    handles = primitiveHandles(primitive),
                    textureBytes = 0L,
                    bufferBytes = modelPrimitiveAllocationBytes(decodedPrimitive),
                    payload = primitive,
                )
                uploaded[meshPrimitive] = primitive
            }
        }

        val samplersByImage = HashMap<Int, TextureSamplerState>()
        for (primitive in decoded.primitives) {
            val imageIndex = primitive.material.baseColourImageIndex ?: continue
            samplersByImage.getOrPut(imageIndex) { primitive.material.baseColourSampler }
        }
        val imageTextures = decoded.images.mapIndexed { imageIndex, image ->
            contentTextures.cachedTexture(
                key = preparedModel.glbKey,
                generationId = preparedModel.glbGenerationId,
                image = image,
                content = TextureContent.IMAGE,
                leases = leases,
                binding = binding,
                sampler = samplersByImage[imageIndex] ?: defaultSamplerStateFor(TextureContent.IMAGE),
                subresource = GpuSubresource.ModelImage(imageIndex),
            )
        }

        return SceneModel(
            placement = preparedModel.placement,
            model = decoded,
            nodeTransforms = preparedModel.nodeTransforms,
            jointMatricesBySkin = preparedModel.jointMatricesBySkin,
            uploaded = uploaded,
            imageTextures = imageTextures,
            overrideTexture = preparedModel.overrideTexture?.let { override ->
                contentTextures.cachedTexture(
                    key = override.resourceKey,
                    generationId = override.generationId,
                    image = override.image,
                    content = TextureContent.IMAGE,
                    leases = leases,
                    binding = binding,
                )
            },
            opacity = preparedModel.opacity,
        )
    }

    private sealed interface GroundTilesResult {
        class Resolved(
            val tiles: List<SceneGroundTile>,
            /** How many of [tiles] were drawn from an ancestor rather than their own texture (ADR 0057). */
            val provisionalTileCount: Int = 0,
        ) : GroundTilesResult

        class Failed(val failure: FailureDescriptor) : GroundTilesResult
    }

    /**
     * One freshly uploaded ground texture and the dimensions it was uploaded at.
     *
     * The dimensions travel with the name because the two pixel forms learn them differently -- a
     * PNG only once decoded, raw pixels from the engine's own `widthPx`/`heightPx` -- and the GPU
     * byte figure the registry is charged must be the uploaded texture's, not either input's.
     */
    private class UploadedGroundTile(val name: Int, val widthPx: Int, val heightPx: Int)

    /**
     * Turns this frame's ground instances into drawable tiles, decoding and uploading only what is not
     * already on the GPU.
     *
     * **Residency, not a per-frame upload.** A tile whose GL texture is still registered is neither
     * decoded nor uploaded again -- [GlObjectRegistry.leaseResident] hands back the texture already on
     * the GPU. That is the whole point of the byte budget: panning back over ground already seen must
     * cost nothing, and a 512x512 tile costs a megabyte of decode plus a megabyte of upload every time
     * it is missed.
     *
     * **Leases, and why they are draw-scoped.** Both branches leave this loop holding a lease, and they
     * have to: a lease is what "an in-flight draw holds for its own duration" means, and a tile this
     * draw reuses is as much in use as one it just uploaded. A texture with an open lease is
     * structurally unreachable to eviction ([GlObjectRegistry] iterates only its unleased keys), so
     * nothing this frame needs can be evicted by a sibling tile part-way through the same draw --
     * "exceeding the budget because a live frame still needs a tile is the correct outcome; breaking a
     * drawable frame to honour a cache limit is not". Releasing the lease is also what marks the tile
     * most-recently-used, so the reuse branch needs no separate touch. The lease is released by
     * [performDraw]'s `finally` rather than held for the prepared frame's lifetime, because eviction
     * issues GL deletes and ADR 0015 requires the exact context to be current for those -- which is
     * guaranteed inside a `Draw` operation and guaranteed by nothing at `PreparedFrame.close()`. Between
     * draws the tile stays resident, unleased, up to the budget.
     *
     * A tile is uploaded as [TextureContent.IMAGE]: a basemap tile is an image, its alpha is a coverage
     * value, and it is filtered under every pitched camera, which is exactly what premultiplication
     * exists to make correct.
     */
    private fun resolveGroundTiles(
        frame: RenGPreparedFrame,
        groundLeases: MutableList<TextureLease>,
        /** The captured binding (ADR 0054): uploads here overwrite texture units the host owns. */
        binding: GlBinding,
    ): GroundTilesResult {
        val groundInstances = frame.groundInstances
        if (groundInstances.isEmpty()) return GroundTilesResult.Resolved(emptyList())

        val renderedByKey = frame.basemapTiles.associateBy { it.key }
        var provisionalTileCount = 0
        val texturesByKey = HashMap<ResourceKey, Int>(renderedByKey.size)
        val elevationByTile = resolveDemTextures(frame.terrain, groundLeases, binding)
        val tiles = ArrayList<SceneGroundTile>(groundInstances.size)
        for (groundInstance in groundInstances) {
            val key = groundInstance.resourceKey
            val cached = texturesByKey[key]
            val elevation = elevationByTile[canonicalTileOf(groundInstance.instance)]
            if (cached != null) {
                tiles += SceneGroundTile(
                    instance = groundInstance.instance,
                    texture = cached,
                    elevation = elevation,
                )
                continue
            }
            val reused = glObjectRegistry.leaseResident(key)
            // ADR 0057: a tile this frame's budget declined to rasterise, or whose own resources could
            // not be acquired under `presentFailedTilesProvisionally`, has no texture and no
            // rendered bytes, so it is drawn from the nearest resident ancestor, magnified through
            // the window it occupies there. Tried only when the two ordinary sources have failed,
            // and never instead of the typed failure below: a tile with no ancestor either is still
            // RESOURCE_UNAVAILABLE (ADR 0046).
            val provisional = if (reused != null || renderedByKey.containsKey(key)) {
                null
            } else {
                frame.basemapStyleDigest?.let { digest ->
                    groundTileProvisioner.residentAncestorOf(digest, canonicalTileOf(groundInstance.instance))
                }
            }
            if (provisional != null) {
                val (ancestorKey, window) = provisional
                val ancestor = glObjectRegistry.leaseResident(ancestorKey)
                    ?: return GroundTilesResult.Failed(basemapTileEvictedFailure(key))
                groundLeases += ancestor.lease
                provisionalTileCount += 1
                // Deliberately NOT recorded in `texturesByKey`: that memo is keyed by the tile's own
                // identity, and two tiles sharing one ancestor need two different windows into it.
                tiles += SceneGroundTile(
                    instance = groundInstance.instance,
                    texture = ancestor.handle.name,
                    elevation = elevation,
                    colourWindow = window,
                )
                continue
            }
            val texture = if (reused != null) {
                groundLeases += reused.lease
                reused.handle.name
            } else {
                // Not `requireNotNull` any more. Preparation now skips the engine for a tile whose
                // texture was already resident, so a ground instance names *either* a rendered tile
                // or a resident texture — and the `leaseResident` above is what distinguishes them.
                // The one path that reaches here with neither is a texture evicted between this
                // frame's preparation and its draw, which the byte budget can do to an unleased
                // entry. That is a frame this renderer cannot honour, and refusing it is right;
                // crashing the consumer for a cache decision it never made is not (ADR 0046).
                val rendered = renderedByKey[key]
                    ?: return GroundTilesResult.Failed(basemapTileEvictedFailure(key))
                // Exhaustive, with no `else`: a third pixel form must fail to compile here rather
                // than reach a driver as an unhandled tile (ADR 0044).
                val uploaded = when (val pixels = rendered.pixels) {
                    is BasemapTilePixels.Encoded -> {
                        val image = when (
                            val decoded = decodePng(
                                pixels.pngBytes,
                                configuration.resourceLimits.maximumDecodedImageBytes,
                                configuration.resourceLimits.maximumImageDecodeWorkingBytes,
                            )
                        ) {
                            is PngDecodeResult.Success -> decoded.image
                            else -> return GroundTilesResult.Failed(basemapTileDecodeFailure(key))
                        }
                        UploadedGroundTile(
                            name = uploadTexture(binding, image, TextureContent.IMAGE),
                            widthPx = image.width,
                            heightPx = image.height,
                        )
                    }
                    // `uploadPremultipliedTexture`, never `uploadTexture`: these bytes are already
                    // premultiplied and the other entry point would do it twice.
                    is BasemapTilePixels.Raw -> UploadedGroundTile(
                        name = uploadPremultipliedTexture(
                            binding,
                            pixels.rgba,
                            pixels.widthPx,
                            pixels.heightPx,
                        ),
                        widthPx = pixels.widthPx,
                        heightPx = pixels.heightPx,
                    )
                }
                groundLeases += glObjectRegistry.registerTexture(
                    key = key,
                    handle = GlObjectHandle(GlObjectType.TEXTURE, uploaded.name),
                    byteSize = uploaded.widthPx.toLong() * uploaded.heightPx.toLong() *
                        RGBA_BYTES_PER_PIXEL,
                )
                uploaded.name
            }
            texturesByKey[key] = texture
            tiles += SceneGroundTile(
                instance = groundInstance.instance,
                texture = texture,
                elevation = elevation,
            )
        }
        return GroundTilesResult.Resolved(tiles, provisionalTileCount)
    }

    /**
     * Decodes, pads and uploads this frame's DEMs, and answers which padded texture and which window
     * each **canonical** ground tile samples.
     *
     * **It decides nothing about coverage.** Every tile it pads is one
     * [PreparedTerrain.elevatedTiles] already named, which is what ADR 0041's diagnostic was counted
     * over during `prepare()`; a tile whose texels disagreed with the descriptor's `tileSizePx`,
     * whose source is not its request's ancestor, or whose source never arrived was excluded there
     * and reported there. A ground tile with no entry here draws flat beside its displaced
     * neighbours, and it has already been counted. Nothing here throws and nothing here retries
     * (ADR 0041).
     *
     * **The texture is the *source* tile's, and the window is what makes several requests share it.**
     * Under overzoom `sampleFor` answers many requested tiles from one DEM image, so the padded
     * texture is assembled once per source tile, keyed by content, and `demTileWindowFor` gives each
     * requested tile its own sub-rectangle of it. The ring the acquisition paid for is what
     * guarantees the source tile's own eight neighbours are present to be padded with.
     *
     * **The texels arrive already decoded** (Rentile `0.7.0`), so this function's name is now half a
     * lie kept for the two steps it still performs: RenG decodes no DEM anywhere, and the per-frame
     * inflate of every tile in the request -- ring included, resident or not -- is gone with it.
     *
     * **The key is [PaddedDemTexture.contentKey], never the centre tile's digest**, which is the one
     * trap in this function and is invisible when got wrong: a centre-keyed texture hits residency in
     * the frame after an absent neighbour arrives and serves the replicated ring to a frame that
     * could have closed the seam. `ResourceKeyDeriver.paddedDemTexture` takes the composed key by
     * signature so the correct input is the only one that type-checks.
     *
     * Every upload leaves a lease in [groundLeases], released by [performDraw]'s `finally` with the
     * ground textures': a DEM competes for `maximumResidentGpuTextureBytes` on exactly the terms a
     * tile does, and an unreleased lease is a texture the budget could never reclaim.
     */
    private fun resolveDemTextures(
        terrain: PreparedTerrain?,
        groundLeases: MutableList<TextureLease>,
        /** The captured binding (ADR 0054): an upload binds on the host's active unit. */
        binding: GlBinding,
    ): Map<CanonicalBasemapTile, SceneTileDem> {
        if (terrain == null) return emptyMap()
        val elevated = terrain.elevatedTiles
        if (elevated.isEmpty()) return emptyMap()

        val texelsBySource = terrain.texelsBySource
        val texturesBySource = HashMap<DemTileCoordinate, Int>(texelsBySource.size)
        val resolved = LinkedHashMap<CanonicalBasemapTile, SceneTileDem>(elevated.size)
        elevated.forEach { tile ->
            val dem = terrain.demTileFor(tile) ?: return@forEach
            val window = demTileWindowFor(dem.requestedTile, dem.sourceTile) ?: return@forEach
            val texture = texturesBySource.getOrElse(dem.sourceTile) {
                val paddedPlan = planPaddedDemTexture(dem.sourceTile, texelsBySource) ?: return@forEach
                val key = geometryKeyDeriver.paddedDemTexture(paddedPlan.contentKey).key
                val leased = uploadDemTexture(binding, glObjectRegistry, key, paddedPlan)
                groundLeases += leased.lease
                leased.handle.name.also { texturesBySource[dem.sourceTile] = it }
            }
            resolved[tile] = SceneTileDem(demTexture = texture, window = window)
        }
        return resolved
    }

    private fun canonicalTileOf(instance: BasemapTileInstance): CanonicalBasemapTile =
        CanonicalBasemapTile(lod = instance.lod, tileY = instance.tileY, canonicalX = instance.canonicalX)

    /**
     * Resolves [camera] against the fixed output pixel size a second time (the first was inside
     * `FramePlanningCore.plan()`'s own spatial-planning call, at `prepare()` time), through whichever
     * of the two resolvers [projectionMode] names — the same `when` `FramePlanningCore` runs, and the
     * reason [RenGPreparedFrame] carries a projection mode at all. This mirrors
     * [SceneContent]'s own re-resolution of `Placement`/`Geometry` at draw time and accepts the same
     * redundant-but-safe reasoning: both resolvers are pure and deterministic in their two
     * inputs, [camera] cannot have changed since `prepare()` validated it (it is `RenGPreparedFrame`'s
     * own immutable field, not the caller's live [FramePlan]), and `configuration.outputPixelSize` is
     * fixed for the renderer's whole lifetime (ADR 0012). A failure here is therefore a caller
     * contract violation rather than a legitimate runtime outcome — exactly
     * `internal.gl.requireResolvedAtDrawTime`'s own reasoning — and (Task 9b) is now reported through
     * that exact same shared function, as a typed [RenGException] (`INVALID_VALUE` at `DRAW`)
     * rather than the bare `error(...)` this used to throw directly.
     */
    private fun resolveFrameCamera(
        camera: Camera,
        projectionMode: ProjectionMode,
    ): ResolvedFrameCamera = when (projectionMode) {
        ProjectionMode.MERCATOR ->
            resolveMercatorCamera(camera, configuration.outputPixelSize).requireResolvedAtDrawTime()

        ProjectionMode.GLOBE ->
            resolveGlobeCamera(camera, configuration.outputPixelSize).requireResolvedAtDrawTime()
    }

    // ---- Prepared-frame lifecycle -----------------------------------------------------------------

    /**
     * [RenGPreparedFrame.takeClosePayload] atomically hands every ownership token over once, so a second
     * `close()` releases nothing. The swap marks the frame closed before its content stops being pinned,
     * and both the swap and the terminal drain remain allocation-free under memory pressure.
     */
    internal fun closePreparedFrame(frame: RenGPreparedFrame) = driver.withOperationGate {
        // Taking ownership inside the same gate as renderer close ensures exactly one operation drains
        // this frame before returning; renderer close cannot miss a payload already taken by a waiter.
        val payload = frame.takeClosePayload()
        val fact = if (payload == null) PreparedFrameFact.OwnedClosed else PreparedFrameFact.OwnedOpen
        try {
            val outcome = driver.run(RendererLifecycleOperation.ClosePreparedFrame(fact)) { operation ->
                if (operation is RendererLifecycleOperation.ClosePreparedFrame) null else unexpectedOperation(operation)
            }
            if (outcome is RendererLifecycleOutcome.Failed) throw outcome.failure.toException()
        } finally {
            if (payload != null) {
                try {
                    frameOwnership.unregisterPreparedFrame(frame)
                } finally {
                    try {
                        // The operation gate has serialized past every in-flight draw.
                        frame.clearHeavyweightContent()
                    } finally {
                        frameOwnership.releasePreparedFrameOwnership(payload)
                    }
                }
            }
        }
    }

    // ---- Close --------------------------------------------------------------------------------

    override fun close() {
        // Guarded only while there is something to delete. ADR 0015: "After declared loss there is
        // nothing to delete, so close is context-free" -- and a close that is context-free is
        // thread-free for the same reason, since the thread only ever stood in for the context. A
        // close with live objects still deletes them, which is the operation that rule was written
        // for, so that one is guarded.
        val outcome = driver.run(RendererLifecycleOperation.CloseRenderer) { operation ->
            if (operation == RendererLifecycleOperation.CloseRenderer) {
                if (driver.snapshot.gpuLedger.hasLiveGpuObjects) {
                    requireContextThread(PipelineStage.RENDERER_CLOSE)
                }
                // Owner-wide terminal close invalidates and releases Prepared Frames before clearing
                // resident generations. This also detaches their callback to this renderer.
                frameOwnership.drainPreparedFrames()
                offscreenSurface?.let { deleteOffscreenSurface(binding, it) }
                compositePipeline?.let { deleteCompositePipeline(binding, programs, it) }
                stickerPipeline?.let { deleteStickerPipeline(binding, programs, it) }
                backdropPipeline?.let { deleteBackdropPipeline(binding, programs, it) }
                groundPipeline?.let { deleteGroundPipeline(binding, programs, it) }
                globeGroundPipeline?.let { deleteGlobeGroundPipeline(binding, programs, it) }
                bloomResourceManager.delete(binding)
                labelPipeline?.let { deleteLabelPipeline(binding, programs, it) }
                iconPipeline?.let { deleteIconPipeline(binding, programs, it) }
                consumerPipelines.deleteAll(binding, programs)
                // Model pipelines own their joint uniform buffers directly. Uploaded primitives instead
                // live in the generalized allocation registry, so the registry sweep below deletes their
                // vertex arrays and buffers exactly once.
                modelPipelines.values.forEach { deleteModelPipeline(binding, programs, it) }
                modelPipelines.clear()
                // Task 9b item 1: every sticker/geometry-consumer texture cachedTexture() has ever
                // registered gets deleted here, exactly once, on close -- the same ADR 0007/0015
                // "close() deletes" rule used by the consumer-pipeline cache above. The registry's
                // own `forgetEverything()` still runs afterwards (GlLifecycleDriver.applyTerminal,
                // once this operation succeeds and the machine reaches CLOSED), which is harmless
                // here since every handle is already gone by then.
                deleteGlObjects(binding, glObjectRegistry.takeAllHandlesForDeletion())
                offscreenSurface = null
                compositePipeline = null
                stickerPipeline = null
                backdropPipeline = null
                groundPipeline = null
                labelPipeline = null
                iconPipeline = null
                globeGroundPipeline = null
                bloomResourceManager.forgetResources()
                residentCache.closeAll()
                // CONTEXT.md's Close entry says a close "releases CPU state", and these two are exactly
                // that and nothing more: an immutable Rentile batch and one decoded atlas, no GL handle
                // between them, so unlike everything above they need no context and can never fail. They
                // are dropped here rather than in `clearFrameHistory()` because they are caches -- see
                // [labelHandover] for why that distinction is the whole decision.
                retainedLabelHandover = null
                previousLabelTiles = null
                retainedGlyphAtlas = null
                retainedSpriteAtlas = null
                readAheadCoordinator.close()
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
