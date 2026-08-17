package com.rohittp.reng

import com.rohittp.reng.internal.freshListCopy

public enum class ProjectionMode {
    MERCATOR,
    GLOBE,
}

public class FramePlan(
    frameIndex: Long,
    camera: Camera,
    projectionMode: ProjectionMode = ProjectionMode.MERCATOR,
    drawBasemap: Boolean = true,
    stickers: List<Sticker> = emptyList(),
    models: List<Model> = emptyList(),
    geometries: List<Geometry> = emptyList(),
) {
    public val frameIndex: Long
    public val camera: Camera
    public val projectionMode: ProjectionMode
    public val drawBasemap: Boolean
    private val stickerSnapshot: ArrayList<Sticker>
    private val modelSnapshot: ArrayList<Model>
    private val geometrySnapshot: ArrayList<Geometry>
    public val stickers: List<Sticker>
        get() = freshListCopy(stickerSnapshot)
    public val models: List<Model>
        get() = freshListCopy(modelSnapshot)
    public val geometries: List<Geometry>
        get() = freshListCopy(geometrySnapshot)

    init {
        require(frameIndex >= 0L) { "frameIndex must be non-negative" }
        val validatedCamera = camera
        val validatedProjectionMode = projectionMode
        val stickerCopy = ArrayList(stickers)
        val modelCopy = ArrayList(models)
        val geometryCopy = ArrayList(geometries)

        this.frameIndex = frameIndex
        this.camera = validatedCamera
        this.projectionMode = validatedProjectionMode
        this.drawBasemap = drawBasemap
        this.stickerSnapshot = stickerCopy
        this.modelSnapshot = modelCopy
        this.geometrySnapshot = geometryCopy
    }

    override fun equals(other: Any?): Boolean =
        other is FramePlan &&
            frameIndex == other.frameIndex &&
            camera == other.camera &&
            projectionMode == other.projectionMode &&
            drawBasemap == other.drawBasemap &&
            stickerSnapshot == other.stickerSnapshot &&
            modelSnapshot == other.modelSnapshot &&
            geometrySnapshot == other.geometrySnapshot

    override fun hashCode(): Int {
        var result = frameIndex.hashCode()
        result = 31 * result + camera.hashCode()
        result = 31 * result + projectionMode.hashCode()
        result = 31 * result + drawBasemap.hashCode()
        result = 31 * result + stickerSnapshot.hashCode()
        result = 31 * result + modelSnapshot.hashCode()
        result = 31 * result + geometrySnapshot.hashCode()
        return result
    }
}

internal fun FramePlan.stickersForCore(): List<Sticker> = stickers

internal fun FramePlan.modelsForCore(): List<Model> = models

internal fun FramePlan.geometriesForCore(): List<Geometry> = geometries
