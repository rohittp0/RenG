package com.rohittp.reng

import com.rohittp.reng.internal.canonicalDouble
import com.rohittp.reng.internal.canonicalFloat
import com.rohittp.reng.internal.freshListCopy
import com.rohittp.reng.internal.requireFiniteFloat
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

/**
 * A value a consumer shader pair may bind by documented uniform name (see [Geometry.uniforms]).
 *
 * Every finite-numeric variant follows the same canonicalization discipline `Vector3` establishes in
 * `CONTEXT.md`: non-finite components are rejected at construction, and `-0.0f`/`0.0f` compare and hash
 * identically. [Scalar], [Vec2], [Vec3], and [Vec4] stay `data class`es — so `copy` and `componentN` work
 * as a consumer expects — but hand-write `equals`/`hashCode` rather than accepting the generated ones:
 * Kotlin's data-class codegen compares `Float` properties with `Float.compare` (matching boxed
 * `Float.equals`, which treats `-0.0f` and `0.0f` as unequal so that hash codes stay consistent with a
 * naive implementation), not with the `==` operator's IEEE-754 semantics. Writing `x == other.x` by hand
 * over statically-typed `Float` uses the IEEE-754 comparison instead, so `-0.0f` and `0.0f` compare equal;
 * `hashCode` then has to normalize the sign of zero itself to stay consistent. [Integer] has no
 * floating-point component and needs neither. The canonical frame-identity encoder
 * (`FramePlanCanonicalEncoding.kt`) independently re-canonicalizes every component before hashing anyway,
 * exactly as it already does for `Vector3`'s `Double` components, so this is defense in depth rather than
 * the only place the guarantee is enforced. [Mat4] is a plain `class` rather than a `data class`
 * specifically so its backing array can be copied defensively and its elements canonicalized at
 * construction; a `data class` over a `FloatArray` would compare by reference.
 */
public sealed interface ShaderValue {
    public data class Scalar(public val value: Float) : ShaderValue {
        init {
            requireFiniteFloat(value, "value")
        }

        override fun equals(other: Any?): Boolean = other is Scalar && value == other.value

        override fun hashCode(): Int = zeroCanonicalizedHash(value)
    }

    public data class Vec2(public val x: Float, public val y: Float) : ShaderValue {
        init {
            requireFiniteFloat(x, "x")
            requireFiniteFloat(y, "y")
        }

        override fun equals(other: Any?): Boolean = other is Vec2 && x == other.x && y == other.y

        override fun hashCode(): Int = 31 * zeroCanonicalizedHash(x) + zeroCanonicalizedHash(y)
    }

    public data class Vec3(public val x: Float, public val y: Float, public val z: Float) : ShaderValue {
        init {
            requireFiniteFloat(x, "x")
            requireFiniteFloat(y, "y")
            requireFiniteFloat(z, "z")
        }

        override fun equals(other: Any?): Boolean =
            other is Vec3 && x == other.x && y == other.y && z == other.z

        override fun hashCode(): Int {
            var result = zeroCanonicalizedHash(x)
            result = 31 * result + zeroCanonicalizedHash(y)
            result = 31 * result + zeroCanonicalizedHash(z)
            return result
        }
    }

    public data class Vec4(
        public val x: Float,
        public val y: Float,
        public val z: Float,
        public val w: Float,
    ) : ShaderValue {
        init {
            requireFiniteFloat(x, "x")
            requireFiniteFloat(y, "y")
            requireFiniteFloat(z, "z")
            requireFiniteFloat(w, "w")
        }

        override fun equals(other: Any?): Boolean =
            other is Vec4 && x == other.x && y == other.y && z == other.z && w == other.w

        override fun hashCode(): Int {
            var result = zeroCanonicalizedHash(x)
            result = 31 * result + zeroCanonicalizedHash(y)
            result = 31 * result + zeroCanonicalizedHash(z)
            result = 31 * result + zeroCanonicalizedHash(w)
            return result
        }
    }

    public data class Integer(public val value: Int) : ShaderValue

    public class Mat4(elements: FloatArray) : ShaderValue {
        private val elementSnapshot: FloatArray

        init {
            require(elements.size == MAT4_ELEMENT_COUNT) {
                "Mat4 requires exactly $MAT4_ELEMENT_COUNT elements"
            }
            elementSnapshot = FloatArray(MAT4_ELEMENT_COUNT) { index ->
                canonicalFloat(elements[index], "elements[$index]")
            }
        }

        override fun equals(other: Any?): Boolean =
            other is Mat4 && elementSnapshot.contentEquals(other.elementSnapshot)

        override fun hashCode(): Int = elementSnapshot.contentHashCode()

        override fun toString(): String = "ShaderValue.Mat4(<redacted>)"

        /**
         * A fresh defensive copy of the canonicalized 16 elements, for internal use only (the canonical
         * frame-identity encoder and, later, the GL uniform binder). Not part of the public API: a
         * consumer has no supported way to read a [Mat4] back out.
         */
        internal fun elementsForCore(): FloatArray = elementSnapshot.copyOf()
    }
}

// Float.hashCode() (java.lang.Float.floatToIntBits under the hood on JVM, equivalently bit-based on
// every other target) gives -0.0f and 0.0f different results, even though the `==` operator above
// treats them as equal. Normalizing the sign of zero before hashing keeps equals()/hashCode() consistent
// without needing to canonicalize a ShaderValue's stored value itself.
private fun zeroCanonicalizedHash(value: Float): Int = (if (value == 0.0f) 0.0f else value).hashCode()

private const val MAT4_ELEMENT_COUNT = 16

public data class Geometry(
    public val topLeft: Vector3,
    public val bottomRight: Vector3,
    public val shaderPair: ShaderPair,
    public val uniforms: Map<String, ShaderValue> = emptyMap(),
    public val textures: Map<String, ResourceLocator> = emptyMap(),
) {
    init {
        require(topLeft.x in -90.0..90.0) { "topLeft latitude must be within the supported range" }
        require(bottomRight.x in -90.0..90.0) { "bottomRight latitude must be within the supported range" }
        require(topLeft.x > bottomRight.x) { "topLeft latitude must be north of bottomRight latitude" }
        require(topLeft.y < bottomRight.y) { "topLeft longitude must be west of bottomRight longitude" }
        require(bottomRight.y - topLeft.y <= 360.0) { "geometry longitude span must not exceed 360 degrees" }
    }

    override fun toString(): String =
        "Geometry(topLeft=$topLeft, bottomRight=$bottomRight, shaderPair=<redacted>, " +
            "uniforms=<redacted>, textures=<redacted>)"
}
