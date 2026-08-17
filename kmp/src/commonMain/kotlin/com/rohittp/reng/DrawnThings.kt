package com.rohittp.reng

import com.rohittp.reng.internal.canonicalDouble
import com.rohittp.reng.internal.freshListCopy
import com.rohittp.reng.internal.requireUnicodeScalars

public data class Sticker(
    public val placement: Placement,
    public val image: ResourceLocator,
)

public sealed interface AnimationSelector {
    public data class Name(public val value: String) : AnimationSelector {
        init {
            requireUnicodeScalars(value, "animationName", nonBlank = true)
        }
    }

    public data class Index(public val value: Long) : AnimationSelector {
        init {
            require(value >= 0L) { "animation index must be non-negative" }
        }
    }
}

public class AnimationTrack(animation: AnimationSelector, timeSeconds: Double) {
    public val animation: AnimationSelector
    public val timeSeconds: Double

    init {
        val validatedAnimation = animation
        val canonicalTimeSeconds = canonicalDouble(timeSeconds, "timeSeconds")
        require(canonicalTimeSeconds >= 0.0) { "timeSeconds must be non-negative" }

        this.animation = validatedAnimation
        this.timeSeconds = canonicalTimeSeconds
    }

    override fun equals(other: Any?): Boolean =
        other is AnimationTrack && animation == other.animation && timeSeconds == other.timeSeconds

    override fun hashCode(): Int = 31 * animation.hashCode() + timeSeconds.hashCode()
}

public class Model(
    placement: Placement,
    glb: ResourceLocator,
    texture: ResourceLocator? = null,
    animationTracks: List<AnimationTrack> = emptyList(),
) {
    public val placement: Placement
    public val glb: ResourceLocator
    public val texture: ResourceLocator?
    private val animationTrackSnapshot: ArrayList<AnimationTrack>
    public val animationTracks: List<AnimationTrack>
        get() = freshListCopy(animationTrackSnapshot)

    init {
        val validatedPlacement = placement
        val validatedGlb = glb
        val validatedTexture = texture
        val snapshot = ArrayList(animationTracks)

        this.placement = validatedPlacement
        this.glb = validatedGlb
        this.texture = validatedTexture
        this.animationTrackSnapshot = snapshot
    }

    override fun equals(other: Any?): Boolean =
        other is Model &&
            placement == other.placement &&
            glb == other.glb &&
            texture == other.texture &&
            animationTrackSnapshot == other.animationTrackSnapshot

    override fun hashCode(): Int {
        var result = placement.hashCode()
        result = 31 * result + glb.hashCode()
        result = 31 * result + (texture?.hashCode() ?: 0)
        result = 31 * result + animationTrackSnapshot.hashCode()
        return result
    }
}

internal fun Model.animationTracksForCore(): List<AnimationTrack> = animationTracks

public data class ShaderPair(
    public val vertexSource: String,
    public val fragmentSource: String,
) {
    init {
        requireUnicodeScalars(vertexSource, "vertexSource", nonBlank = true)
        requireUnicodeScalars(fragmentSource, "fragmentSource", nonBlank = true)
    }

    override fun toString(): String = "ShaderPair(<redacted>)"
}

public data class Geometry(
    public val topLeft: Vector3,
    public val bottomRight: Vector3,
    public val shaderPair: ShaderPair,
) {
    init {
        require(topLeft.x in -90.0..90.0) { "topLeft latitude must be within the supported range" }
        require(bottomRight.x in -90.0..90.0) { "bottomRight latitude must be within the supported range" }
        require(topLeft.x > bottomRight.x) { "topLeft latitude must be north of bottomRight latitude" }
        require(topLeft.y < bottomRight.y) { "topLeft longitude must be west of bottomRight longitude" }
        require(bottomRight.y - topLeft.y <= 360.0) { "geometry longitude span must not exceed 360 degrees" }
    }

    override fun toString(): String =
        "Geometry(topLeft=$topLeft, bottomRight=$bottomRight, shaderPair=<redacted>)"
}
