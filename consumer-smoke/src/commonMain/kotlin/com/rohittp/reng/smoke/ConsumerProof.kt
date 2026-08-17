package com.rohittp.reng.smoke

import com.rohittp.reng.AnimationSelector
import com.rohittp.reng.AnimationTrack
import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.Camera
import com.rohittp.reng.Diagnostic
import com.rohittp.reng.DiagnosticCode
import com.rohittp.reng.DiagnosticSeverity
import com.rohittp.reng.DiagnosticSink
import com.rohittp.reng.FramePlan
import com.rohittp.reng.FramebufferName
import com.rohittp.reng.Geometry
import com.rohittp.reng.Model
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.Placement
import com.rohittp.reng.PreparedFrame
import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.RenderTarget
import com.rohittp.reng.Renderer
import com.rohittp.reng.RendererConfiguration
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceFreeResult
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ResourceReport
import com.rohittp.reng.ResourceReportEntry
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.ResourceUsage
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Sticker
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportRequestMetadata
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.Vector3

private val transport = Transport { request ->
    transportRequestProperties(request)
    TransportResponse(200, byteArrayOf(1), TransportResponseMetadata())
}

private object SmokeStore : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? {
        rawResourceKeyProperties(key)
        return null
    }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        rawResourceKeyProperties(key)
        storedRawResourceProperties(resource)
    }
}

internal fun consumerCompilationProof(): FramePlan {
    enumEntryReferences()
    valueConstructionReferences()
    adapterConstructionReferences()

    val placement = samplePlacement()
    val sticker = Sticker(placement, ResourceLocator("smoke:sticker"))
    sticker.placement
    sticker.image

    val defaults = FramePlan(
        frameIndex = 0,
        camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
    )
    framePlanProperties(defaults)

    return FramePlan(
        frameIndex = 1,
        camera = Camera(1.0, 2.0, 3.0, 4.0, 5.0),
        projectionMode = ProjectionMode.MERCATOR,
        drawBasemap = false,
        stickers = listOf(sticker),
        models = listOf(sampleModel(placement)),
        geometries = listOf(sampleGeometry()),
    ).also(::framePlanProperties)
}

@Suppress("unused")
private val defaultConfiguration = RendererConfiguration(
    outputPixelSize = OutputPixelSize(1, 1),
    transport = transport,
    store = SmokeStore,
).also(::rendererConfigurationProperties)

@Suppress("unused")
private val explicitConfiguration = RendererConfiguration(
    outputPixelSize = OutputPixelSize(2, 3),
    transport = transport,
    store = SmokeStore,
    basemapStyle = ResourceLocator("smoke:style"),
    resourceLimits = ResourceLimits(
        maximumBasemapStyleBytes = 1,
        maximumBasemapMetadataBytes = 2,
        maximumBasemapTileBytes = 3,
        maximumBasemapSpriteImageBytes = 4,
        maximumBasemapGeoJsonBytes = 5,
        maximumStickerImageBytes = 6,
        maximumModelGlbBytes = 7,
        maximumModelTextureBytes = 8,
    ),
    maximumBasemapTileInstances = 1,
    maximumPreparationBatchSize = 2,
    maximumConcurrentResourceOperations = 3,
    diagnosticSink = DiagnosticSink { diagnostic -> diagnosticProperties(diagnostic) },
).also(::rendererConfigurationProperties)

@Suppress("unused")
private suspend fun protocolReference(
    renderer: Renderer,
    plan: FramePlan,
    frame: PreparedFrame,
    target: RenderTarget,
) {
    renderer.prepare(plan)
    renderer.prepare(plan, ResourceAccessMode.RELOAD)
    renderer.prepareBatch(listOf(plan))
    renderer.prepareBatch(listOf(plan), ResourceAccessMode.CACHE_ONLY)
    renderer.cancelPreparations()
    renderer.clearFrameHistory()

    val report = renderer.queryResources()
    renderer.queryResources(ResourceSelector.ByKind(ResourceKind.EXTERNAL))
    resourceReportProperties(report, renderer)

    val freed = renderer.freeResources()
    renderer.freeResources(ResourceSelector.All)
    resourceFreeResultProperties(freed)

    renderer.notifyGpuObjectsGone()
    renderer.adoptCurrentRenderContext()
    val minted = renderer.mintRenderTarget(FramebufferName(0u))
    minted.framebufferName.value
    frame.frameIndex
    target.framebufferName.value
    renderer.draw(frame, target)
    renderer.draw(frame, minted)
    frame.close()
    renderer.close()
}

private fun enumEntryReferences(): List<Any> =
    listOf(
        ProjectionMode.MERCATOR,
        ProjectionMode.GLOBE,
        AnchoringMode.MAP,
        AnchoringMode.SCREEN,
        ResourceClass.BASEMAP_STYLE,
        ResourceClass.BASEMAP_TILE_JSON,
        ResourceClass.BASEMAP_VECTOR_TILE,
        ResourceClass.BASEMAP_RASTER_TILE,
        ResourceClass.BASEMAP_DEM_TILE,
        ResourceClass.BASEMAP_SPRITE_JSON,
        ResourceClass.BASEMAP_SPRITE_IMAGE,
        ResourceClass.BASEMAP_GEO_JSON,
        ResourceClass.STICKER_IMAGE,
        ResourceClass.MODEL_GLB,
        ResourceClass.MODEL_TEXTURE,
        ResourceKind.EXTERNAL,
        ResourceKind.GEOMETRY_PROGRAM,
        ResourceKind.INTERNAL_PIPELINE,
        ResourceKind.OFFSCREEN_SURFACE,
        ResourceAccessMode.NORMAL,
        ResourceAccessMode.CACHE_ONLY,
        ResourceAccessMode.RELOAD,
        PipelineStage.CONFIGURATION,
        PipelineStage.FRAME_PLANNING,
        PipelineStage.FRAME_PREPARATION,
        PipelineStage.RESOURCE_LOOKUP,
        PipelineStage.STORE_READ,
        PipelineStage.STORE_VALIDATION,
        PipelineStage.TRANSPORT,
        PipelineStage.TRANSPORT_VALIDATION,
        PipelineStage.STORE_WRITE,
        PipelineStage.RESOURCE_DECODING,
        PipelineStage.RESOURCE_PARSING,
        PipelineStage.SHADER_COMPILATION,
        PipelineStage.GPU_RESOURCE,
        PipelineStage.RENDER_TARGET,
        PipelineStage.DRAW,
        PipelineStage.RESOURCE_FREE,
        PipelineStage.RENDERER_CLOSE,
        PipelineStage.CONTEXT_ADOPTION,
        RenGErrorCode.INVALID_VALUE,
        RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
        RenGErrorCode.UNSUPPORTED_PROJECTION_MODE,
        RenGErrorCode.PREPARATION_ORDER_VIOLATION,
        RenGErrorCode.PREPARATION_IN_PROGRESS,
        RenGErrorCode.RENDERER_CLOSED,
        RenGErrorCode.RENDER_CONTEXT_ADOPTION_REQUIRED,
        RenGErrorCode.NO_CURRENT_RENDER_CONTEXT,
        RenGErrorCode.DIFFERENT_CURRENT_RENDER_CONTEXT,
        RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT,
        RenGErrorCode.FOREIGN_PREPARED_FRAME,
        RenGErrorCode.PREPARED_FRAME_CLOSED,
        RenGErrorCode.FOREIGN_RENDER_TARGET,
        RenGErrorCode.STALE_RENDER_TARGET,
        RenGErrorCode.INVALID_RENDER_TARGET,
        RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
        RenGErrorCode.RESOURCE_UNAVAILABLE,
        RenGErrorCode.TRANSPORT_EXECUTION_FAILED,
        RenGErrorCode.INVALID_TRANSPORT_RESPONSE,
        RenGErrorCode.STORE_READ_FAILED,
        RenGErrorCode.STORE_WRITE_FAILED,
        RenGErrorCode.STORE_INTEGRITY_FAILED,
        RenGErrorCode.RESOURCE_DECODE_FAILED,
        RenGErrorCode.RESOURCE_PARSE_FAILED,
        RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE,
        RenGErrorCode.SHADER_COMPILE_FAILED,
        RenGErrorCode.SHADER_LINK_FAILED,
        RenGErrorCode.GPU_OPERATION_FAILED,
        RenGErrorCode.IDENTITY_COLLISION,
        DiagnosticSeverity.INFO,
        DiagnosticSeverity.WARNING,
        DiagnosticSeverity.ERROR,
        DiagnosticCode.RESOURCE_RELOADED_AFTER_FREE,
        DiagnosticCode.FAILURE_CONTEXT,
    )

private fun valueConstructionReferences() {
    val size = OutputPixelSize(2, 3)
    size.width
    size.height

    val vector = Vector3(1.0, 2.0, 3.0)
    vector.x
    vector.y
    vector.z

    val camera = Camera(1.0, 2.0, 3.0, 4.0, 5.0)
    camera.latitude
    camera.unwrappedLongitude
    camera.zoom
    camera.bearing
    camera.pitch

    val placement = Placement(
        positionMode = AnchoringMode.MAP,
        position = vector,
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.MAP,
        scale = 1.0,
    )
    placement.positionMode
    placement.position
    placement.rotationMode
    placement.rotation
    placement.scaleMode
    placement.scale

    val locator = ResourceLocator("smoke:resource")
    locator.value

    val defaultLimits = ResourceLimits()
    resourceLimitProperties(defaultLimits)
    resourceLimitProperties(
        ResourceLimits(
            maximumBasemapStyleBytes = 1,
            maximumBasemapMetadataBytes = 2,
            maximumBasemapTileBytes = 3,
            maximumBasemapSpriteImageBytes = 4,
            maximumBasemapGeoJsonBytes = 5,
            maximumStickerImageBytes = 6,
            maximumModelGlbBytes = 7,
            maximumModelTextureBytes = 8,
        ),
    )

    val name = AnimationSelector.Name("walk")
    name.value
    val index = AnimationSelector.Index(0)
    index.value
    val track = AnimationTrack(name, 1.0)
    track.animation
    track.timeSeconds

    val defaultModel = Model(placement, ResourceLocator("smoke:default-model"))
    modelProperties(defaultModel)
    modelProperties(
        Model(
            placement = placement,
            glb = ResourceLocator("smoke:model"),
            texture = ResourceLocator("smoke:texture"),
            animationTracks = listOf(track, AnimationTrack(index, 2.0)),
        ),
    )

    val shaderPair = ShaderPair("#version 300 es\nvoid main() {}", "#version 300 es\nvoid main() {}")
    shaderPair.vertexSource
    shaderPair.fragmentSource
    val geometry = Geometry(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), shaderPair)
    geometry.topLeft
    geometry.bottomRight
    geometry.shaderPair

    FramebufferName(0u).value
    DiagnosticSink.None
}

private fun adapterConstructionReferences() {
    transportResponseMetadataProperties(TransportResponseMetadata())
    val responseMetadata = TransportResponseMetadata(
        contentType = "image/png",
        etag = "etag",
        lastModified = "date",
        freshUntilEpochMillis = 1,
    )
    transportResponseMetadataProperties(responseMetadata)

    transportResponseProperties(TransportResponse(200, byteArrayOf(1)))
    transportResponseProperties(TransportResponse(304, byteArrayOf(), responseMetadata))

    storedRawResourceMetadataProperties(StoredRawResourceMetadata(storedAtEpochMillis = 0))
    val storedMetadata = StoredRawResourceMetadata(
        contentType = "image/png",
        etag = "etag",
        lastModified = "date",
        freshUntilEpochMillis = 1,
        storedAtEpochMillis = 2,
    )
    storedRawResourceMetadataProperties(storedMetadata)
    storedRawResourceProperties(StoredRawResource(byteArrayOf(1), "digest", storedMetadata))
}

private fun samplePlacement(): Placement =
    Placement(
        positionMode = AnchoringMode.SCREEN,
        position = Vector3(0.5, 0.5, 0.0),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.0,
    )

private fun sampleModel(placement: Placement): Model =
    Model(
        placement = placement,
        glb = ResourceLocator("smoke:model"),
        texture = ResourceLocator("smoke:texture"),
        animationTracks = listOf(
            AnimationTrack(AnimationSelector.Name("walk"), 0.0),
            AnimationTrack(AnimationSelector.Index(0), 1.0),
        ),
    )

private fun sampleGeometry(): Geometry =
    Geometry(
        topLeft = Vector3(1.0, 0.0, 0.0),
        bottomRight = Vector3(0.0, 1.0, 0.0),
        shaderPair = ShaderPair("#version 300 es\nvoid main() {}", "#version 300 es\nvoid main() {}"),
    )

private fun framePlanProperties(plan: FramePlan) {
    plan.frameIndex
    plan.camera
    plan.projectionMode
    plan.drawBasemap
    plan.stickers
    plan.models
    plan.geometries
}

private fun modelProperties(model: Model) {
    model.placement
    model.glb
    model.texture
    model.animationTracks
}

private fun resourceLimitProperties(limits: ResourceLimits) {
    limits.maximumBasemapStyleBytes
    limits.maximumBasemapMetadataBytes
    limits.maximumBasemapTileBytes
    limits.maximumBasemapSpriteImageBytes
    limits.maximumBasemapGeoJsonBytes
    limits.maximumStickerImageBytes
    limits.maximumModelGlbBytes
    limits.maximumModelTextureBytes
}

private fun transportRequestProperties(request: TransportRequest) {
    request.locator.value
    request.resourceClass.name
    request.maximumResponseBytes
    transportRequestMetadataProperties(request.metadata)
}

private fun transportRequestMetadataProperties(metadata: TransportRequestMetadata) {
    metadata.ifNoneMatch
    metadata.ifModifiedSince
    metadata.accept
}

private fun transportResponseMetadataProperties(metadata: TransportResponseMetadata) {
    metadata.contentType
    metadata.etag
    metadata.lastModified
    metadata.freshUntilEpochMillis
}

private fun transportResponseProperties(response: TransportResponse) {
    response.statusCode
    response.body
    transportResponseMetadataProperties(response.metadata)
}

private fun rawResourceKeyProperties(key: RawResourceKey) {
    key.stableId
    key.resourceClass
}

private fun storedRawResourceMetadataProperties(metadata: StoredRawResourceMetadata) {
    metadata.contentType
    metadata.etag
    metadata.lastModified
    metadata.freshUntilEpochMillis
    metadata.storedAtEpochMillis
}

private fun storedRawResourceProperties(resource: StoredRawResource) {
    resource.bytes
    resource.contentDigest
    storedRawResourceMetadataProperties(resource.metadata)
}

private fun diagnosticProperties(diagnostic: Diagnostic) {
    diagnostic.code
    diagnostic.severity
    diagnostic.stage
    diagnostic.fieldName
    diagnostic.resourceClass
    diagnostic.resourceKey
    diagnostic.statusCode
    diagnostic.limit
    diagnostic.actual
}

@Suppress("unused")
private fun exceptionProperties(exception: RenGException) {
    exception.code
    exception.stage
    exception.diagnostics
    exception.message
    exception.cause
}

private fun rendererConfigurationProperties(configuration: RendererConfiguration) {
    configuration.outputPixelSize
    configuration.transport
    configuration.store
    configuration.basemapStyle
    configuration.resourceLimits
    configuration.maximumBasemapTileInstances
    configuration.maximumPreparationBatchSize
    configuration.maximumConcurrentResourceOperations
    configuration.diagnosticSink
}

private fun resourceReportProperties(report: ResourceReport, renderer: Renderer) {
    report.entries.forEach { entry ->
        resourceReportEntryProperties(entry)
        selectorReferences(entry.key).forEach(renderer::queryResources)
    }
    resourceUsageProperties(report.totals)
}

private fun selectorReferences(key: ResourceKey): List<ResourceSelector> {
    val byKind = ResourceSelector.ByKind(ResourceKind.EXTERNAL)
    byKind.kind
    val byClass = ResourceSelector.ByClass(ResourceClass.STICKER_IMAGE)
    byClass.resourceClass
    val byKey = ResourceSelector.ByKey(key)
    byKey.key
    return listOf(ResourceSelector.All, byKind, byClass, byKey)
}

private fun resourceReportEntryProperties(entry: ResourceReportEntry) {
    resourceKeyProperties(entry.key)
    entry.residentGenerationCount
    entry.retiredGenerationCount
    entry.leaseCount
    entry.reloadRequired
    resourceUsageProperties(entry.usage)
}

private fun resourceKeyProperties(key: ResourceKey) {
    key.kind
    key.stableId
    key.resourceClass
}

private fun resourceUsageProperties(usage: ResourceUsage) {
    usage.rawBytes
    usage.decodedCpuBytes
    usage.knownGpuBytes
    usage.hasUnknownGpuBytes
}

private fun resourceFreeResultProperties(result: ResourceFreeResult) {
    result.matchedKeys
    result.fullyFreedKeys
    result.deferredKeys
    result.alreadyFreeKeys
}
