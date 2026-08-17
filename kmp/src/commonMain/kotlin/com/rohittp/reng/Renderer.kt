package com.rohittp.reng

import kotlin.jvm.JvmInline

@JvmInline
public value class FramebufferName(public val value: UInt)

public sealed interface PreparedFrame : AutoCloseable {
    public val frameIndex: Long

    override fun close(): Unit
}

public sealed interface RenderTarget {
    public val framebufferName: FramebufferName
}

public class RendererConfiguration(
    outputPixelSize: OutputPixelSize,
    transport: Transport,
    store: Store,
    basemapStyle: ResourceLocator? = null,
    resourceLimits: ResourceLimits = ResourceLimits(),
    maximumBasemapTileInstances: Int = 512,
    maximumPreparationBatchSize: Int = 256,
    maximumConcurrentResourceOperations: Int = 8,
    diagnosticSink: DiagnosticSink = DiagnosticSink.None,
) {
    public val outputPixelSize: OutputPixelSize
    public val transport: Transport
    public val store: Store
    public val basemapStyle: ResourceLocator?
    public val resourceLimits: ResourceLimits
    public val maximumBasemapTileInstances: Int
    public val maximumPreparationBatchSize: Int
    public val maximumConcurrentResourceOperations: Int
    public val diagnosticSink: DiagnosticSink

    init {
        require(maximumBasemapTileInstances in 1..4096) {
            "maximumBasemapTileInstances must be within the supported range"
        }
        require(maximumPreparationBatchSize in 1..4096) {
            "maximumPreparationBatchSize must be within the supported range"
        }
        require(maximumConcurrentResourceOperations in 1..64) {
            "maximumConcurrentResourceOperations must be within the supported range"
        }

        this.outputPixelSize = outputPixelSize
        this.transport = transport
        this.store = store
        this.basemapStyle = basemapStyle
        this.resourceLimits = resourceLimits
        this.maximumBasemapTileInstances = maximumBasemapTileInstances
        this.maximumPreparationBatchSize = maximumPreparationBatchSize
        this.maximumConcurrentResourceOperations = maximumConcurrentResourceOperations
        this.diagnosticSink = diagnosticSink
    }
}

public sealed interface Renderer : AutoCloseable {
    public suspend fun prepare(
        plan: FramePlan,
        accessMode: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): PreparedFrame

    public suspend fun prepareBatch(
        plans: List<FramePlan>,
        accessMode: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): List<PreparedFrame>

    public suspend fun cancelPreparations(): Unit

    public fun clearFrameHistory(): Unit

    public fun queryResources(selector: ResourceSelector = ResourceSelector.All): ResourceReport

    public fun freeResources(selector: ResourceSelector = ResourceSelector.All): ResourceFreeResult

    public fun notifyGpuObjectsGone(): Unit

    public fun adoptCurrentRenderContext(): Unit

    public fun mintRenderTarget(framebufferName: FramebufferName): RenderTarget

    public fun draw(preparedFrame: PreparedFrame, renderTarget: RenderTarget): Unit

    override fun close(): Unit
}
