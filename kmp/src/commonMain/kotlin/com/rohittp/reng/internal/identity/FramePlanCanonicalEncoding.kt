package com.rohittp.reng.internal.identity

import com.rohittp.reng.AltitudeMode
import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.AnimationSelector
import com.rohittp.reng.AnimationTrack
import com.rohittp.reng.Camera
import com.rohittp.reng.FramePlan
import com.rohittp.reng.Geometry
import com.rohittp.reng.Model
import com.rohittp.reng.Placement
import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.Sticker
import com.rohittp.reng.Vector3
import com.rohittp.reng.animationTracksForCore
import com.rohittp.reng.Backdrop
import com.rohittp.reng.backdropForCore
import com.rohittp.reng.Bloom
import com.rohittp.reng.BlendMode
import com.rohittp.reng.bloomForCore
import com.rohittp.reng.geometriesForCore
import com.rohittp.reng.modelsForCore
import com.rohittp.reng.stickersForCore

/**
 * The Frame Plan field-tag table. ADR 0018 calls this table **permanent**, and a root is "a strictly
 * increasing sequence of unique fields", so a field added later takes the next free tag and encodes
 * last — it never takes a tag in the middle and renumbers the fields after it. `DRAW_LABELS` reads
 * beside `DRAW_BASEMAP` in [FramePlan] and encodes after `GEOMETRIES` for exactly that reason; the
 * two orders are allowed to differ, and the tag is what identity depends on.
 */
internal enum class FramePlanSegment(internal val tag: Int) {
    FRAME_INDEX(1),
    CAMERA(2),
    PROJECTION_MODE(3),
    DRAW_BASEMAP(4),
    STICKERS(5),
    MODELS(6),
    GEOMETRIES(7),
    DRAW_LABELS(8),

    /**
     * ADR 0068's backdrop, appended so no existing tag moves. Every frame encodes this segment,
     * absent ones as `CanonicalBinary.optional(null)`, so a Frame Identity computed before this
     * field existed and one computed after differ even for an identical plan -- which costs one
     * full structural diff on the first frame after an upgrade and nothing after that.
     */
    BACKDROP(9),

    /** ADR 0074's bloom, appended so no existing tag moves. */
    BLOOM(10),
}

internal class EncodedFramePlan(
    internal val identity: HashedCanonicalBytes,
    segmentPayloads: List<CanonicalBytes>,
) {
    private val segmentPayloadSnapshot: List<CanonicalBytes> = ArrayList(segmentPayloads)

    internal val segmentPayloads: List<CanonicalBytes>
        get() = ArrayList(segmentPayloadSnapshot)

    override fun equals(other: Any?): Boolean =
        other is EncodedFramePlan &&
            identity == other.identity &&
            segmentPayloadSnapshot == other.segmentPayloadSnapshot

    override fun hashCode(): Int = 31 * identity.hashCode() + segmentPayloadSnapshot.hashCode()
}

internal class FramePlanCanonicalEncoder(
    private val sha256: Sha256Function = AcceleratedSha256,
    /** Shared with the renderer's key derivers, so a shader pair's sources are encoded once in all. */
    private val shaderPairs: ShaderPairMemo = ShaderPairMemo(),
    /**
     * Where a geometry's program key comes from (ADR 0081): the renderer's own deriver, which remembers
     * it in [shaderPairs], so encoding a geometry costs no hashing at all.
     */
    private val programKeys: ResourceKeyDeriver = ResourceKeyDeriver(sha256, shaderPairs),
) {
    /** Uniform names already validated and encoded; see [encodeUniformList]. Only [encode]'s caller uses it. */
    private val uniformNames: MutableMap<String, CanonicalBytes> = HashMap()

    internal fun encode(plan: FramePlan): EncodedFramePlan {
        val stickers = plan.stickersForCore()
        val models = plan.modelsForCore()
        val geometries = plan.geometriesForCore()
        val segmentPayloads = listOf(
            CanonicalBinary.u64(plan.frameIndex),
            encodeCamera(plan.camera),
            CanonicalBinary.u16(plan.projectionMode.wireValue),
            CanonicalBinary.boolean(plan.drawBasemap),
            CanonicalBinary.list(stickers.map(::encodeSticker)),
            CanonicalBinary.list(models.map(::encodeModel)),
            CanonicalBinary.list(geometries.map(::encodeGeometry)),
            // Encoded as its own segment rather than folded into DRAW_BASEMAP's byte: the two flags
            // are orthogonal, so all four pairings must reach four distinct Frame Identities.
            CanonicalBinary.boolean(plan.drawLabels),
            CanonicalBinary.optional(plan.backdropForCore()?.let(::encodeBackdrop)),
            CanonicalBinary.optional(plan.bloomForCore()?.let(::encodeBloom)),
        )
        val root = CanonicalBinary.root(CanonicalRootKind.FRAME) {
            FramePlanSegment.entries.forEach { segment ->
                field(segment.tag, segmentPayloads[segment.tag - 1])
            }
        }
        return EncodedFramePlan(
            identity = HashedCanonicalBytes(
                digest = sha256.digest(root),
                canonicalBytes = root,
            ),
            segmentPayloads = segmentPayloads,
        )
    }

    private fun encodeCamera(camera: Camera): CanonicalBytes = CanonicalBinary.fields {
        field(1, CanonicalBinary.binary64(camera.latitude))
        field(2, CanonicalBinary.binary64(camera.unwrappedLongitude))
        field(3, CanonicalBinary.binary64(camera.zoom))
        field(4, CanonicalBinary.binary64(camera.bearing))
        field(5, CanonicalBinary.binary64(camera.pitch))
    }

    private fun encodeVector(vector: Vector3): CanonicalBytes = CanonicalBinary.fields {
        field(1, CanonicalBinary.binary64(vector.x))
        field(2, CanonicalBinary.binary64(vector.y))
        field(3, CanonicalBinary.binary64(vector.z))
    }

    private fun encodePlacement(placement: Placement): CanonicalBytes = CanonicalBinary.fields {
        field(1, CanonicalBinary.u16(placement.positionMode.wireValue))
        field(2, encodeVector(placement.position))
        field(3, CanonicalBinary.u16(placement.rotationMode.wireValue))
        field(4, encodeVector(placement.rotation))
        field(5, CanonicalBinary.u16(placement.scaleMode.wireValue))
        field(6, CanonicalBinary.binary64(placement.scale))
        // Tag 7 because 1 through 6 are pinned by the Cycle B specification and ADR 0018 calls the
        // table permanent. A ground-relative altitude and an absolute one are different frames, so
        // they must reach different Frame Identities or a GROUND_RELATIVE plan is served the cached
        // ABSOLUTE one.
        field(7, CanonicalBinary.u16(placement.altitudeMode.wireValue))
    }

    /**
     * Tags 1 and 2 stay the pattern's and tags 3 upward are the shader's, rather than a
     * discriminator field ahead of a shared body (ADR 0018 calls the table permanent, ADR 0071 adds
     * the case). Which tags are present *is* the discriminator, and the payoff is that a plan
     * carrying a pattern encodes to the same bytes it did before the shader case existed -- so its
     * Frame Identity does not move and no cache is invalidated by an upgrade.
     */
    private fun encodeBackdrop(backdrop: Backdrop): CanonicalBytes = CanonicalBinary.fields {
        when (backdrop) {
            is Backdrop.Pattern -> {
                field(1, CanonicalBinary.exactUtf8(backdrop.image.value))
                field(2, CanonicalBinary.binary64(backdrop.tileSizeLogicalPixels))
            }
            is Backdrop.Shader -> {
                field(3, encodeShaderPair(backdrop.shaderPair))
                field(4, encodeUniforms(backdrop.uniforms))
                field(5, encodeTextures(backdrop.textures))
            }
        }
    }

    private fun encodeBloom(bloom: Bloom): CanonicalBytes = CanonicalBinary.fields {
        field(1, CanonicalBinary.binary64(bloom.intensity.toDouble()))
        field(
            2,
            CanonicalBinary.list(
                bloom.levelWeights.map { CanonicalBinary.binary64(it.toDouble()) },
            ),
        )
        field(3, CanonicalBinary.binary64(bloom.insideSuppression.toDouble()))
        field(4, CanonicalBinary.binary64(bloom.whiteness.toDouble()))
        // ADR 0085's three fields, each encoded only when it differs from its default, so every
        // frame identity computed before they existed is unchanged.
        if (bloom.highPrecisionEmission) {
            field(5, CanonicalBinary.boolean(true))
        }
        if (bloom.reachLevel != Bloom.DEFAULT_REACH_LEVEL) {
            field(6, CanonicalBinary.u16(bloom.reachLevel))
        }
        if (bloom.reachGain != 1f) {
            field(7, CanonicalBinary.binary64(bloom.reachGain.toDouble()))
        }
    }

    private fun encodeSticker(sticker: Sticker): CanonicalBytes = CanonicalBinary.fields {
        field(1, encodePlacement(sticker.placement))
        field(2, CanonicalBinary.exactUtf8(sticker.image.value))
        if (sticker.opacity != 1f || sticker.blend != BlendMode.OVER) {
            field(3, CanonicalBinary.fields {
                field(1, CanonicalBinary.binary64(sticker.opacity.toDouble()))
                field(2, CanonicalBinary.u16(sticker.blend.wireValue))
            })
        }
    }

    private fun encodeModel(model: Model): CanonicalBytes {
        val animationTracks = model.animationTracksForCore()
        return CanonicalBinary.fields {
            field(1, encodePlacement(model.placement))
            field(2, CanonicalBinary.exactUtf8(model.glb.value))
            field(
                3,
                CanonicalBinary.optional(model.texture?.let { CanonicalBinary.exactUtf8(it.value) }),
            )
            field(4, CanonicalBinary.list(animationTracks.map(::encodeAnimationTrack)))
            if (model.opacity != 1f) {
                field(5, CanonicalBinary.binary64(model.opacity.toDouble()))
            }
        }
    }

    private fun encodeAnimationTrack(track: AnimationTrack): CanonicalBytes = CanonicalBinary.fields {
        field(1, encodeAnimationSelector(track.animation))
        field(2, CanonicalBinary.binary64(track.timeSeconds))
    }

    private fun encodeAnimationSelector(selector: AnimationSelector): CanonicalBytes = CanonicalBinary.fields {
        when (selector) {
            is AnimationSelector.Index -> {
                field(1, CanonicalBinary.u16(SELECTOR_INDEX_WIRE_VALUE))
                field(2, CanonicalBinary.u64(selector.value))
            }
            is AnimationSelector.Name -> {
                field(1, CanonicalBinary.u16(SELECTOR_NAME_WIRE_VALUE))
                field(2, CanonicalBinary.exactUtf8(selector.value))
            }
        }
    }

    /**
     * Tag 3, the shader pair's two sources, is retired (ADR 0081): the program is tag 9, its geometry
     * program's stable id -- the SHA-256 of the same two sources and the shader profile, which RenG
     * already derives and remembers to key the program itself. Every geometry carried both full sources
     * into the frame's encoding, its structural diff and, until a history clear, the identity registry,
     * on every `prepare`. A retired tag is never reused.
     */
    private fun encodeGeometry(geometry: Geometry): CanonicalBytes = CanonicalBinary.fields {
        field(1, encodeVector(geometry.topLeft))
        field(2, encodeVector(geometry.bottomRight))
        field(4, encodeUniforms(geometry.uniforms))
        field(5, encodeTextures(geometry.textures))
        // Tag 6, the next free one: a Geometry carries no Placement, so its altitude mode is its own
        // field here exactly as it is on the public type.
        field(6, CanonicalBinary.u16(geometry.altitudeMode.wireValue))
        field(7, CanonicalBinary.boolean(geometry.emissive))
        if (geometry.blend != BlendMode.OVER) {
            field(8, CanonicalBinary.u16(geometry.blend.wireValue))
        }
        field(9, CanonicalBinary.exactUtf8(programKeys.geometryProgram(geometry.shaderPair).key.stableId))
        // ADR 0085, after the program's tag because tags must increase; absent at the default so no
        // existing frame identity moves.
        if (geometry.bloomReach != 0f) {
            field(10, CanonicalBinary.binary64(geometry.bloomReach.toDouble()))
        }
    }

    private fun encodeShaderPair(shaderPair: ShaderPair): CanonicalBytes =
        shaderPairs.canonicalOf(shaderPair).encodedPair

    // A Map has no inherent iteration order, and the canonical encoding must be deterministic
    // regardless of how a consumer happened to build the map. Sorting by Kotlin's default String
    // ordering (a fixed UTF-16 code-unit comparison, never locale-sensitive) makes the encoded bytes
    // depend only on the map's content, not on insertion or hash-bucket order. Written in one pass by
    // [encodeUniformList]; the composed form it reproduces is kept in its test.
    private fun encodeUniforms(uniforms: Map<String, ShaderValue>): CanonicalBytes =
        encodeUniformList(uniforms, uniformNames)

    // Same fixed code-unit key ordering as encodeUniforms, and for the same reason.
    private fun encodeTextures(textures: Map<String, ResourceLocator>): CanonicalBytes =
        CanonicalBinary.list(
            textures.entries.sortedBy { it.key }.map { (name, locator) ->
                CanonicalBinary.fields {
                    field(1, CanonicalBinary.exactUtf8(name))
                    field(2, CanonicalBinary.exactUtf8(locator.value))
                }
            },
        )

}

private val ProjectionMode.wireValue: Int
    get() = when (this) {
        ProjectionMode.MERCATOR -> 1
        ProjectionMode.GLOBE -> 2
    }

private val AltitudeMode.wireValue: Int
    get() = when (this) {
        AltitudeMode.ABSOLUTE -> 1
        AltitudeMode.GROUND_RELATIVE -> 2
    }

private val BlendMode.wireValue: Int
    get() = when (this) {
        BlendMode.OVER -> 1
        BlendMode.ADDITIVE -> 2
        BlendMode.MULTIPLY -> 3
        BlendMode.SCREEN -> 4
    }

private val AnchoringMode.wireValue: Int
    get() = when (this) {
        AnchoringMode.MAP -> 1
        AnchoringMode.SCREEN -> 2
    }

private const val SELECTOR_INDEX_WIRE_VALUE: Int = 1
private const val SELECTOR_NAME_WIRE_VALUE: Int = 2

internal const val SHADER_VALUE_SCALAR_WIRE_VALUE: Int = 1
internal const val SHADER_VALUE_VEC2_WIRE_VALUE: Int = 2
internal const val SHADER_VALUE_VEC3_WIRE_VALUE: Int = 3
internal const val SHADER_VALUE_VEC4_WIRE_VALUE: Int = 4
internal const val SHADER_VALUE_INTEGER_WIRE_VALUE: Int = 5
internal const val SHADER_VALUE_MAT4_WIRE_VALUE: Int = 6
