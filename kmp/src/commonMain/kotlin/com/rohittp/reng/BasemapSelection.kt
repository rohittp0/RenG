package com.rohittp.reng

import com.rohittp.reng.internal.canonicalFloat
import com.rohittp.reng.internal.requireFiniteFloat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The configured basemap style, and optional second style, drawn by one frame (ADR 0086). */
@kotlinx.serialization.Serializable(with = BasemapSelectionSerializer::class)
public class BasemapSelection(
    style: Int = 0,
    blend: BasemapBlend? = null,
) {
    public val style: Int
    public val blend: BasemapBlend?

    init {
        blend?.validateAgainst(style)
        this.style = style
        this.blend = blend
    }

    override fun equals(other: Any?): Boolean =
        other is BasemapSelection && style == other.style && blend == other.blend

    override fun hashCode(): Int = 31 * style + blend.hashCode()

    override fun toString(): String = "BasemapSelection(style=$style, blend=$blend)"
}

/** A second configured style composited over a frame's primary style (ADR 0086). */
@kotlinx.serialization.Serializable(with = BasemapBlendSerializer::class)
public class BasemapBlend(
    toStyle: Int,
    mask: BlendMask,
) {
    public val toStyle: Int = toStyle
    public val mask: BlendMask = mask

    internal fun validateAgainst(primaryStyle: Int) {
        require(toStyle != primaryStyle) { "toStyle must differ from style" }
    }

    override fun equals(other: Any?): Boolean =
        other is BasemapBlend && toStyle == other.toStyle && mask == other.mask

    override fun hashCode(): Int = 31 * toStyle + mask.hashCode()

    override fun toString(): String = "BasemapBlend(toStyle=$toStyle, mask=$mask)"
}

/** The exact screen-space coverage of a [BasemapBlend] (ADR 0086). */
@kotlinx.serialization.Serializable
public sealed interface BlendMask {
    @kotlinx.serialization.Serializable(with = UniformBlendMaskSerializer::class)
    public class Uniform(amount: Float) : BlendMask {
        public val amount: Float

        init {
            require(requireFiniteFloat(amount, "amount") in 0f..1f) {
                "amount must be between zero and one"
            }
            this.amount = canonicalFloat(amount, "amount")
        }

        override fun equals(other: Any?): Boolean = other is Uniform && amount == other.amount
        override fun hashCode(): Int = amount.hashCode()
        override fun toString(): String = "Uniform(amount=$amount)"
    }

    @kotlinx.serialization.Serializable(with = RadialBlendMaskSerializer::class)
    public class Radial(
        centreX: Float,
        centreY: Float,
        radius: Float,
        feather: Float,
    ) : BlendMask {
        public val centreX: Float
        public val centreY: Float
        public val radius: Float
        public val feather: Float

        init {
            requireFiniteFloat(centreX, "centreX")
            requireFiniteFloat(centreY, "centreY")
            require(requireFiniteFloat(radius, "radius") >= 0f) { "radius must be non-negative" }
            require(requireFiniteFloat(feather, "feather") >= 0f) { "feather must be non-negative" }
            this.centreX = canonicalFloat(centreX, "centreX")
            this.centreY = canonicalFloat(centreY, "centreY")
            this.radius = canonicalFloat(radius, "radius")
            this.feather = canonicalFloat(feather, "feather")
        }

        override fun equals(other: Any?): Boolean =
            other is Radial &&
                centreX == other.centreX &&
                centreY == other.centreY &&
                radius == other.radius &&
                feather == other.feather

        override fun hashCode(): Int {
            var result = centreX.hashCode()
            result = 31 * result + centreY.hashCode()
            result = 31 * result + radius.hashCode()
            result = 31 * result + feather.hashCode()
            return result
        }

        override fun toString(): String =
            "Radial(centreX=$centreX, centreY=$centreY, radius=$radius, feather=$feather)"
    }

    @kotlinx.serialization.Serializable(with = LinearBlendMaskSerializer::class)
    public class Linear(
        angleRadians: Float,
        position: Float,
        feather: Float,
    ) : BlendMask {
        public val angleRadians: Float
        public val position: Float
        public val feather: Float

        init {
            requireFiniteFloat(angleRadians, "angleRadians")
            require(requireFiniteFloat(position, "position") in 0f..1f) {
                "position must be between zero and one"
            }
            require(requireFiniteFloat(feather, "feather") >= 0f) { "feather must be non-negative" }
            this.angleRadians = canonicalFloat(angleRadians, "angleRadians")
            this.position = canonicalFloat(position, "position")
            this.feather = canonicalFloat(feather, "feather")
        }

        override fun equals(other: Any?): Boolean =
            other is Linear &&
                angleRadians == other.angleRadians &&
                position == other.position &&
                feather == other.feather

        override fun hashCode(): Int {
            var result = angleRadians.hashCode()
            result = 31 * result + position.hashCode()
            result = 31 * result + feather.hashCode()
            return result
        }

        override fun toString(): String =
            "Linear(angleRadians=$angleRadians, position=$position, feather=$feather)"
    }
}

/** Pure Kotlin mirror of ADR 0086's GLSL mask, using top-left frame-pixel coordinates. */
internal fun basemapBlendAmount(
    mask: BlendMask,
    pixelX: Float,
    pixelY: Float,
    frameWidth: Float,
    frameHeight: Float,
): Float {
    val diagonal = sqrt(frameWidth * frameWidth + frameHeight * frameHeight)
    return when (mask) {
        is BlendMask.Uniform -> mask.amount
        is BlendMask.Radial -> {
            val dx = pixelX - mask.centreX * frameWidth
            val dy = pixelY - mask.centreY * frameHeight
            val radius = sqrt(dx * dx + dy * dy) / diagonal
            if (mask.feather == 0f) {
                if (radius <= mask.radius) 1f else 0f
            } else {
                1f - smoothstep(mask.radius - mask.feather, mask.radius, radius)
            }
        }
        is BlendMask.Linear -> {
            val cosine = cos(mask.angleRadians)
            val sine = sin(mask.angleRadians)
            val extent = 0.5f * (frameWidth * abs(cosine) + frameHeight * abs(sine)) / diagonal
            val projection = (
                (pixelX - frameWidth * 0.5f) * cosine -
                    (pixelY - frameHeight * 0.5f) * sine
                ) / diagonal
            val edge = -extent - mask.feather + mask.position * (2f * extent + mask.feather)
            if (mask.feather == 0f) {
                if (projection < edge) 1f else 0f
            } else {
                1f - smoothstep(edge, edge + mask.feather, projection)
            }
        }
    }
}

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
