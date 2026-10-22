package com.rohittp.reng.internal.preparation

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.PreparedFrameClosePayload
import com.rohittp.reng.PreparedTerrain
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGPreparedFrame
import com.rohittp.reng.RendererConfiguration
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.cache.Lease
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.firewall.BasemapTilePixels
import com.rohittp.reng.internal.firewall.RenderedBasemapTile
import com.rohittp.reng.internal.gl.ResolvedIconQuad
import com.rohittp.reng.internal.label.FadedLabel
import com.rohittp.reng.internal.model.DecodedModel
import com.rohittp.reng.internal.renGFailure
import com.rohittp.reng.internal.thread.PlatformLock

/**
 * Who owns a renderer's Prepared Frames: the intrusive registry of open frames, their disposal and the
 * release of every ownership token they hold, and the prepared-frame CPU admission check.
 */
internal class PreparedFrameOwnership(
    private val configuration: RendererConfiguration,
    private val residentCache: ResidentCache,
    /** The owning renderer's identity; only frames carrying it may register. */
    private val ownerIdentity: Any,
) {
    private val preparedFrameRegistryLock = PlatformLock()
    private var firstPreparedFrame: RenGPreparedFrame? = null

    /** Intrusive registration allocates no owner-side node and is complete before frame publication. */
    internal fun registerPreparedFrame(frame: RenGPreparedFrame) {
        preparedFrameRegistryLock.withLock {
            check(frame.ownerIdentity === ownerIdentity) { "cannot register a foreign prepared frame" }
            check(!frame.isRegistered) { "prepared frame is already registered" }
            val previousFirst = firstPreparedFrame
            frame.registryPrevious = null
            frame.registryNext = previousFirst
            previousFirst?.registryPrevious = frame
            frame.isRegistered = true
            firstPreparedFrame = frame
        }
    }

    /** Idempotently detaches one frame and clears every intrusive neighbour reference it held. */
    internal fun unregisterPreparedFrame(frame: RenGPreparedFrame) {
        preparedFrameRegistryLock.withLock {
            if (!frame.isRegistered) return@withLock
            val previous = frame.registryPrevious
            val next = frame.registryNext
            if (previous == null) {
                check(firstPreparedFrame === frame) { "registered frame must be the owner-list head" }
                firstPreparedFrame = next
            } else {
                previous.registryNext = next
            }
            next?.registryPrevious = previous
            frame.registryPrevious = null
            frame.registryNext = null
            frame.isRegistered = false
        }
    }

    /** Drains the owner list without snapshot allocation; called only under the GL operation gate. */
    internal fun drainPreparedFrames() {
        while (true) {
            val frame = preparedFrameRegistryLock.withLock { firstPreparedFrame } ?: return
            disposePreparedFrame(frame)
        }
    }

    internal fun requirePreparedFrameCpuAdmission(update: PreparedFrameCpuBudget.Update) {
        if (update is PreparedFrameCpuBudget.Update.Rejected) {
            throw renGFailure(
                RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
                PipelineStage.FRAME_PREPARATION,
                failureContextDiagnostic(
                    stage = PipelineStage.FRAME_PREPARATION,
                    fieldName = DiagnosticField.PREPARED_FRAME_CPU_BYTES,
                    limit = configuration.resourceLimits.maximumInFlightPreparedFrameCpuBytes,
                    actual = update.projectedBytes,
                ),
            )
        }
    }

    internal fun disposePreparedFrame(frame: RenGPreparedFrame) {
        val payload = frame.takeClosePayload()
        if (payload == null) {
            unregisterPreparedFrame(frame)
            return
        }
        try {
            unregisterPreparedFrame(frame)
        } finally {
            try {
                // Rollback frames were never published, so no draw can be reading this content.
                frame.clearHeavyweightContent()
            } finally {
                releasePreparedFrameOwnership(payload)
            }
        }
    }

    /**
     * Best-effort terminal drain in truthful admission order. The aggregate CPU token is last so a
     * replacement frame cannot be admitted while raw pixels or resident generations remain retained.
     */
    internal fun releasePreparedFrameOwnership(payload: PreparedFrameClosePayload) {
        try {
            try {
                payload.rawTileReservation?.release()
            } catch (_: Throwable) {
                // Continue draining resident generations.
            }
            releaseLeases(payload.leases)
        } finally {
            try {
                payload.preparedFrameCpuReservation.release()
            } catch (_: Throwable) {
                // Ownership is already terminal; no later token remains to drain.
            }
        }
    }

    /**
     * Releases leases no [RenGPreparedFrame] will ever own, tolerating a cache that has already dropped
     * the generation they name -- [ResidentCache.closeAll] clears its own bookkeeping without consulting
     * any outstanding lease, so a release arriving after the renderer closed is a real, harmless ordering
     * rather than a fault to propagate out of a `finally`.
     */
    internal fun releaseLeases(leases: List<Lease>) {
        var index = 0
        while (index < leases.size) {
            try {
                residentCache.releaseLease(leases[index])
            } catch (_: Throwable) {
                // A renderer close may already have dropped this generation; drain the rest.
            }
            index += 1
        }
    }
}

/**
 * Counts the basemap/terrain payload owned directly by one frame, independently of resident generations.
 *
 * The count is exact for image/tile byte arrays and matrix/quad primitive arrays. Collection nodes and
 * object headers are deliberately excluded because their size is platform-runtime-specific. All additions
 * saturate, so hostile collection sizes can only cause admission to fail, never wrap beneath the ceiling.
 */
internal fun preparedBasemapAndTerrainCpuBytes(
    basemapTiles: List<RenderedBasemapTile>,
    terrain: PreparedTerrain?,
): Long {
    val counter = SaturatingByteCounter()
    basemapTiles.forEach { tile ->
        counter.add(
            when (val pixels = tile.pixels) {
                is BasemapTilePixels.Encoded -> pixels.pngBytes.size.toLong()
                is BasemapTilePixels.Raw -> pixels.rgba.size.toLong()
            },
        )
    }
    terrain?.texelsBySource?.values?.forEach { texels -> counter.add(texels.image.byteCount.toLong()) }
    return counter.bytes
}

/** Exact matrix payload one prepared instance retains, computed before those matrices are allocated. */
internal fun projectedModelPoseCpuBytes(model: DecodedModel): Long {
    val counter = SaturatingByteCounter()
    val scene = model.document.defaultScene
        ?.let { model.document.scenes[it] }
        ?: model.document.scenes.single()

    fun visit(nodeIndex: Int) {
        counter.add(MATRIX4_PAYLOAD_BYTES)
        model.document.nodes[nodeIndex].children.forEach(::visit)
    }

    scene.nodes.forEach(::visit)
    var itemIndex = 0
    while (itemIndex < model.drawItems.size) {
        val skinIndex = model.drawItems[itemIndex].skinIndex
        if (skinIndex != null) {
            var previouslySeen = false
            var previousIndex = 0
            while (previousIndex < itemIndex && !previouslySeen) {
                previouslySeen = model.drawItems[previousIndex].skinIndex == skinIndex
                previousIndex += 1
            }
            if (!previouslySeen) {
                counter.add(model.skins[skinIndex].jointNodes.size.toLong() * MATRIX4_PAYLOAD_BYTES)
            }
        }
        itemIndex += 1
    }
    return counter.bytes
}

/** Bulk quad/paint arrays retained by one label frame; atlases are admitted separately before decode. */
internal fun preparedLabelGeometryCpuBytes(
    labels: List<FadedLabel>,
    icons: List<ResolvedIconQuad>,
): Long {
    val counter = SaturatingByteCounter()
    labels.forEach { label ->
            label.quads.forEach { quad ->
                counter.add(quad.cornersXy.size.toLong() * FLOAT_BYTES)
                counter.add(quad.cornersUv.size.toLong() * FLOAT_BYTES)
                counter.add(quad.paint.textColour.size.toLong() * FLOAT_BYTES)
                counter.add(quad.paint.haloColour.size.toLong() * FLOAT_BYTES)
            }
    }
    icons.forEach { icon ->
        counter.add(icon.cornersXy.size.toLong() * FLOAT_BYTES)
        counter.add(icon.cornersUv.size.toLong() * FLOAT_BYTES)
        counter.add(icon.paint.colour.size.toLong() * FLOAT_BYTES)
        counter.add(icon.paint.haloColour.size.toLong() * FLOAT_BYTES)
    }
    return counter.bytes
}

internal class SaturatingByteCounter {
    var bytes: Long = 0L
        private set

    fun add(additional: Long) {
        require(additional >= 0L) { "CPU payload byte count must not be negative" }
        bytes = if (additional > Long.MAX_VALUE - bytes) Long.MAX_VALUE else bytes + additional
    }
}

private const val FLOAT_BYTES: Long = 4L
private const val MATRIX4_PAYLOAD_BYTES: Long = 16L * 8L
