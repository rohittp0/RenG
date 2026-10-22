package com.rohittp.reng.internal.gl

import com.rohittp.reng.Bloom
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile

private const val BLOOM_SOURCE_UNIFORM: String = "rengBloomSource"
private const val BLOOM_LEVEL_UNIFORM: String = "rengBloomLevel"
private const val BLOOM_EMISSION_UNIFORM: String = "rengBloomEmission"
private const val BLOOM_SOURCE_TEXEL_UNIFORM: String = "rengBloomSourceTexel"
private const val BLOOM_TARGET_TEXEL_UNIFORM: String = "rengBloomTargetTexel"
private const val BLOOM_ACCUMULATION_WEIGHT_UNIFORM: String = "rengBloomAccumulationWeight"
private const val BLOOM_LEVEL_WEIGHT_UNIFORM: String = "rengBloomLevelWeight"
private const val BLOOM_INTENSITY_UNIFORM: String = "rengBloomIntensity"
private const val BLOOM_INSIDE_SUPPRESSION_UNIFORM: String = "rengBloomInsideSuppression"
private const val BLOOM_WHITENESS_UNIFORM: String = "rengBloomWhiteness"
private const val BLOOM_DESTINATION_UNIFORM: String = "rengBloomDestination"

internal const val BLOOM_TARGET_LEVEL_COUNT: Int = 7

/**
 * The largest value the chain reads as light from any channel (ADR 0085). A half-float emission
 * image holds whatever an emitter writes, up to half-float's 65504 or an infinity, or below zero,
 * and the composite's soft shoulder divides by the light's peak. Every sample the downsample takes,
 * and the level sample the upsample adds, is clamped to `0..`this ([BLOOM_LIGHT_GLSL]), so the
 * emission image, the reach image and a coarse redraw's level, none of which the chain wrote, are
 * bounded wherever they are read as light, and negative light adds nothing instead of darkening.
 * Light from an RGBA8 image is already in `0..1`, so for it the clamp is a no-op and the chain
 * unchanged.
 */
internal const val BLOOM_LIGHT_CEILING: String = "64.0"

/**
 * The GLSL helper every bloom program reads emission-derived light through; see
 * [BLOOM_LIGHT_CEILING]. A NaN is zeroed first: `clamp` of a NaN is undefined in GLSL, and one NaN
 * texel spreads through every tap of the chain that reads it.
 */
internal const val BLOOM_LIGHT_GLSL: String =
    "vec4 rengBloomLight(vec4 c) {\n" +
        "    c = mix(c, vec4(0.0), isnan(c));\n" +
        "    return clamp(c, 0.0, $BLOOM_LIGHT_CEILING);\n" +
        "}\n"

/**
 * Where the composite's soft shoulder starts, as a fraction of full scale. Below the knee the
 * composite is the plain sum of frame and bloom; above it the brightest channel of that sum rolls
 * off exponentially towards 1 and the other channels are scaled by the same factor, so a bright
 * coloured fill keeps its hue instead of clipping channel by channel to white. The knee is raised
 * to the frame's own brightest channel at each pixel, so the shoulder itself never compresses the
 * frame. The hue steer that follows it ([BLOOM_HUE_STEER_START], ADR 0083) keeps the shoulder's
 * linear luminance but can lower the brightest channel of a bright fill under strong light.
 *
 * A constant rather than a [com.rohittp.reng.Bloom] field: nothing needs to tune it yet. 0.8 was
 * chosen by measurement in Travel Animator: under a white 6 px route glowing at 60% over a lime
 * region, the band of clipped pixels beyond the stroke went from 5 px with the additive composite
 * to 0 (29 to 0 at 12 px and 100%), while the light added on a dark ground stayed within 1% of it.
 */
internal const val BLOOM_SHOULDER_KNEE: String = "0.8"

/**
 * The bloom strength (the brightest channel of the bloom light at a pixel, after intensity and
 * inside suppression) below which the composite keeps the soft shoulder's colour unchanged. Between
 * this and [BLOOM_HUE_STEER_FULL] the shouldered colour's Oklab chroma is steered, along a
 * smoothstep, towards the bloom light's own hue; lightness and linear luminance stay the
 * shoulder's. At or below it the steer, whose weight would be zero there, is skipped: the bloom's
 * long faint tail stays plain light added to the ground, so it does not desaturate the ground far
 * from the emitter, and most of the frame skips the Oklab round trip (the composite's extra cost on
 * the CPH2629 fell from ~2.0 to ~1.0 ms per 1080x1920 frame).
 *
 * Measured in Travel Animator (ADR 0083): with a red #FF3B30 glow at alpha 0x8B, 6 px, the halo 4 px
 * past the stroke is 2 degrees off the glow's Oklab hue on the teal sea and 0 off on a lime region
 * (91 and 65 with the shoulder alone), while the light added on a dark ground stays 0.96-1.01x it.
 */
internal const val BLOOM_HUE_STEER_START: String = "0.03"

/**
 * The bloom strength at and above which the composite's chroma is the bloom light's own hue in
 * full (scaled to the pixel's lightness, never above the light's own chroma). See
 * [BLOOM_HUE_STEER_START].
 */
internal const val BLOOM_HUE_STEER_FULL: String = "0.30"

private const val BLOOM_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengBloomPosition;\n" +
        "layout(location = 1) in vec2 rengBloomTexCoord;\n" +
        "out vec2 rengBloomUv;\n" +
        "void main() {\n" +
        "    rengBloomUv = rengBloomTexCoord;\n" +
        "    gl_Position = vec4(rengBloomPosition, 0.0, 1.0);\n" +
        "}\n"

private const val BLOOM_DOWNSAMPLE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengBloomSource;\n" +
        "uniform vec2 rengBloomSourceTexel;\n" +
        "in vec2 rengBloomUv;\n" +
        "layout(location = 0) out vec4 rengBloomColour;\n" +
        BLOOM_LIGHT_GLSL +
        "void main() {\n" +
        "    vec4 rengBloomCentre = rengBloomLight(texture(rengBloomSource, rengBloomUv));\n" +
        "    vec4 rengBloomCorners =\n" +
        "        rengBloomLight(texture(rengBloomSource, rengBloomUv + rengBloomSourceTexel * vec2(-1.0, -1.0))) +\n" +
        "        rengBloomLight(texture(rengBloomSource, rengBloomUv + rengBloomSourceTexel * vec2( 1.0, -1.0))) +\n" +
        "        rengBloomLight(texture(rengBloomSource, rengBloomUv + rengBloomSourceTexel * vec2(-1.0,  1.0))) +\n" +
        "        rengBloomLight(texture(rengBloomSource, rengBloomUv + rengBloomSourceTexel * vec2( 1.0,  1.0)));\n" +
        "    rengBloomColour = (4.0 * rengBloomCentre + rengBloomCorners) / 8.0;\n" +
        "}\n"

private const val BLOOM_UPSAMPLE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengBloomSource;\n" +
        "uniform sampler2D rengBloomLevel;\n" +
        "uniform vec2 rengBloomTargetTexel;\n" +
        "uniform float rengBloomAccumulationWeight;\n" +
        "uniform float rengBloomLevelWeight;\n" +
        "in vec2 rengBloomUv;\n" +
        "layout(location = 0) out vec4 rengBloomColour;\n" +
        BLOOM_LIGHT_GLSL +
        "void main() {\n" +
        // A zero accumulation weight skips the tent, so a source the chain never wrote (the
        // emission image, in a one-level chain) cannot turn `0 * Inf` into NaN.
        "    vec4 rengBloomTent = vec4(0.0);\n" +
        "    if (rengBloomAccumulationWeight != 0.0) {\n" +
        "    vec4 rengBloomEdges =\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2(-1.0,  0.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2( 1.0,  0.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2( 0.0, -1.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2( 0.0,  1.0));\n" +
        "    vec4 rengBloomDiagonals =\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2(-1.0, -1.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2( 1.0, -1.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2(-1.0,  1.0)) +\n" +
        "        texture(rengBloomSource, rengBloomUv + rengBloomTargetTexel * vec2( 1.0,  1.0));\n" +
        "    rengBloomTent = (rengBloomEdges + 2.0 * rengBloomDiagonals) / 12.0;\n" +
        "    }\n" +
        "    rengBloomColour = rengBloomAccumulationWeight * rengBloomTent +\n" +
        "        rengBloomLevelWeight * rengBloomLight(texture(rengBloomLevel, rengBloomUv));\n" +
        "}\n"

private const val BLOOM_COMPOSITE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengBloomSource;\n" +
        "uniform sampler2D rengBloomEmission;\n" +
        "uniform float rengBloomIntensity;\n" +
        "uniform float rengBloomInsideSuppression;\n" +
        "uniform float rengBloomWhiteness;\n" +
        "uniform sampler2D rengBloomDestination;\n" +
        "in vec2 rengBloomUv;\n" +
        "layout(location = 0) out vec4 rengBloomColour;\n" +
        BLOOM_LIGHT_GLSL +
        "vec3 rengBloomToLinear(vec3 c) {\n" +
        "    c = clamp(c, 0.0, 1.0);\n" +
        "    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));\n" +
        "}\n" +
        "vec3 rengBloomToSrgb(vec3 c) {\n" +
        "    c = clamp(c, 0.0, 1.0);\n" +
        "    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));\n" +
        "}\n" +
        "vec3 rengBloomLinearToOklab(vec3 c) {\n" +
        "    vec3 lms = mat3(0.4122214708, 0.2119034982, 0.0883024619,\n" +
        "        0.5363325363, 0.6806995451, 0.2817188376,\n" +
        "        0.0514459929, 0.1073969566, 0.6299787005) * c;\n" +
        "    lms = pow(max(lms, 0.0), vec3(1.0 / 3.0));\n" +
        "    return mat3(0.2104542553, 1.9779984951, 0.0259040371,\n" +
        "        0.7936177850, -2.4285922050, 0.7827717662,\n" +
        "        -0.0040720468, 0.4505937099, -0.8086757660) * lms;\n" +
        "}\n" +
        "vec3 rengBloomOklabToLinear(vec3 lab) {\n" +
        "    vec3 lms = mat3(1.0, 1.0, 1.0,\n" +
        "        0.3963377774, -0.1055613458, -0.0894841775,\n" +
        "        0.2158037573, -0.0638541728, -1.2914855480) * lab;\n" +
        "    lms = lms * lms * lms;\n" +
        "    return mat3(4.0767416621, -1.2684380046, -0.0041960863,\n" +
        "        -3.3077115913, 2.6097574011, -0.7034186147,\n" +
        "        0.2309699292, -0.3413193965, 1.7076147010) * lms;\n" +
        "}\n" +
        "void main() {\n" +
        "    vec3 rengBloomC = texture(rengBloomSource, rengBloomUv).rgb;\n" +
        "    float rengBloomMaximum = max(rengBloomC.r, max(rengBloomC.g, rengBloomC.b));\n" +
        "    rengBloomC = mix(rengBloomC, vec3(rengBloomMaximum), rengBloomWhiteness);\n" +
        "    float rengBloomInside =\n" +
        "        smoothstep(0.02, 0.30, rengBloomLight(texture(rengBloomEmission, rengBloomUv)).a);\n" +
        "    rengBloomC *= rengBloomIntensity *\n" +
        "        (1.0 - rengBloomInsideSuppression * rengBloomInside);\n" +
        "    vec3 rengBloomD = texture(rengBloomDestination, rengBloomUv).rgb;\n" +
        "    vec3 rengBloomSum = rengBloomD + rengBloomC;\n" +
        "    float rengBloomPeak = max(rengBloomSum.r, max(rengBloomSum.g, rengBloomSum.b));\n" +
        "    float rengBloomKnee = max($BLOOM_SHOULDER_KNEE,\n" +
        "        max(rengBloomD.r, max(rengBloomD.g, rengBloomD.b)));\n" +
        "    float rengBloomHeadroom = max(1.0 - rengBloomKnee, 1e-4);\n" +
        "    float rengBloomMapped = rengBloomPeak <= rengBloomKnee ? rengBloomPeak :\n" +
        "        rengBloomKnee + rengBloomHeadroom *\n" +
        "            (1.0 - exp(-(rengBloomPeak - rengBloomKnee) / rengBloomHeadroom));\n" +
        "    vec3 rengBloomShouldered =\n" +
        "        clamp(rengBloomSum * (rengBloomMapped / max(rengBloomPeak, 1e-6)), 0.0, 1.0);\n" +
        "    float rengBloomStrength = max(rengBloomC.r, max(rengBloomC.g, rengBloomC.b));\n" +
        "    vec3 rengBloomOut = rengBloomShouldered;\n" +
        "    if (rengBloomStrength > $BLOOM_HUE_STEER_START) {\n" +
        "        vec3 rengBloomLinear = rengBloomToLinear(rengBloomShouldered);\n" +
        "        vec3 rengBloomLab = rengBloomLinearToOklab(rengBloomLinear);\n" +
        "        vec3 rengBloomHue =\n" +
        "            rengBloomLinearToOklab(rengBloomToLinear(rengBloomC / rengBloomStrength));\n" +
        "        vec2 rengBloomTarget =\n" +
        "            rengBloomHue.yz * min(1.0, rengBloomLab.x / max(rengBloomHue.x, 1e-4));\n" +
        "        float rengBloomSteer =\n" +
        "            smoothstep($BLOOM_HUE_STEER_START, $BLOOM_HUE_STEER_FULL, rengBloomStrength);\n" +
        "        vec3 rengBloomSteered = rengBloomOklabToLinear(\n" +
        "            vec3(rengBloomLab.x, mix(rengBloomLab.yz, rengBloomTarget, rengBloomSteer)));\n" +
        "        const vec3 rengBloomLuma = vec3(0.2126, 0.7152, 0.0722);\n" +
        "        rengBloomSteered = max(rengBloomSteered *\n" +
        "            (dot(rengBloomLinear, rengBloomLuma) / max(dot(rengBloomSteered, rengBloomLuma), 1e-6)),\n" +
        "            0.0);\n" +
        "        rengBloomSteered /= max(max(rengBloomSteered.r,\n" +
        "            max(rengBloomSteered.g, rengBloomSteered.b)), 1.0);\n" +
        "        rengBloomOut = rengBloomToSrgb(rengBloomSteered);\n" +
        "    }\n" +
        "    rengBloomColour = vec4(clamp(rengBloomOut, 0.0, 1.0), 0.0);\n" +
        "}\n"

private val BLOOM_DOWNSAMPLE_SHADER_PAIR =
    ShaderPair(BLOOM_VERTEX_SOURCE, BLOOM_DOWNSAMPLE_FRAGMENT_SOURCE)
private val BLOOM_UPSAMPLE_SHADER_PAIR =
    ShaderPair(BLOOM_VERTEX_SOURCE, BLOOM_UPSAMPLE_FRAGMENT_SOURCE)
private val BLOOM_COMPOSITE_SHADER_PAIR =
    ShaderPair(BLOOM_VERTEX_SOURCE, BLOOM_COMPOSITE_FRAGMENT_SOURCE)

internal data class BloomTarget(
    val framebuffer: Int,
    val texture: Int,
    val width: Int,
    val height: Int,
)

internal class BloomDownsampleProgram(
    val key: ResourceKey,
    val program: Int,
    val sourceLocation: Int,
    val sourceTexelLocation: Int,
)

internal class BloomUpsampleProgram(
    val key: ResourceKey,
    val program: Int,
    val sourceLocation: Int,
    val levelLocation: Int,
    val targetTexelLocation: Int,
    val accumulationWeightLocation: Int,
    val levelWeightLocation: Int,
)

internal class BloomCompositeProgram(
    val key: ResourceKey,
    val program: Int,
    val sourceLocation: Int,
    val emissionLocation: Int,
    val intensityLocation: Int,
    val insideSuppressionLocation: Int,
    val whitenessLocation: Int,
    val destinationLocation: Int,
)

internal class BloomResources internal constructor(
    /**
     * Whether [emission] and [downsampleLevels] are half-float (ADR 0085) rather than RGBA8. Fixed
     * at creation: a frame asking for the other precision gets a new set of resources.
     */
    internal val highPrecisionEmission: Boolean,
    internal val emission: BloomTarget,
    internal val destinationCopy: BloomTarget,
    internal val downsampleLevels: List<BloomTarget>,
    internal val upsampleLevels: List<BloomTarget>,
    internal val sharedDepthRenderbuffer: Int,
    internal val vertexArray: Int,
    internal val vertexBuffer: Int,
    internal val downsample: BloomDownsampleProgram,
    internal val upsample: BloomUpsampleProgram,
    internal val composite: BloomCompositeProgram,
) {
    /**
     * ADR 0085's reach image and its own downsample levels, allocated on the first frame with a
     * positive `bloomReach` and freed with everything else here; `null` until then.
     */
    internal var reach: BloomReachTargets? = null
}

/**
 * ADR 0085's full-resolution reach image, sharing the main depth renderbuffer exactly as the emission
 * image does, in the emission image's format, and its downsample levels `1..levels.size` at the main
 * chain's sizes. Only the levels a far band has needed so far exist -- a far band at level `n` reads
 * levels `1..n` and nothing deeper -- and [ensureBloomReachTargets] adds deeper ones when a frame first
 * asks for them.
 */
internal class BloomReachTargets(
    val image: BloomTarget,
    levels: List<BloomTarget>,
) {
    private val allocatedLevels: MutableList<BloomTarget> = ArrayList(levels)

    val levels: List<BloomTarget> get() = allocatedLevels

    internal fun addLevel(level: BloomTarget) {
        allocatedLevels += level
    }
}

/**
 * Allocates [resources]' reach image and its downsample levels `1..`[throughLevel] where they are not
 * allocated yet, and returns the reach targets, or `null` when the context refused one (every target
 * allocated by this call is deleted again, and what an earlier call allocated is kept).
 */
internal fun ensureBloomReachTargets(
    binding: GlBinding,
    resources: BloomResources,
    throughLevel: Int,
): BloomReachTargets? {
    require(throughLevel in 1..resources.downsampleLevels.size) {
        "a reach level must name one of the main chain's downsample levels"
    }
    val format = if (resources.highPrecisionEmission) GL_RGBA16F else GL_RGBA8
    val existing = resources.reach
    if (existing != null && existing.levels.size >= throughLevel) return existing
    val allocated = ArrayList<BloomTarget>(1 + throughLevel)
    fun fail(): BloomReachTargets? {
        allocated.forEach { deleteBloomTarget(binding, it) }
        return null
    }
    val image = existing?.image ?: (
        createBloomTarget(
            binding, resources.emission.width, resources.emission.height, format,
            resources.sharedDepthRenderbuffer,
        ) ?: return fail()
    ).also { allocated += it }
    val levels = ArrayList<BloomTarget>(throughLevel)
    existing?.levels?.let { levels += it }
    while (levels.size < throughLevel) {
        val mainLevel = resources.downsampleLevels[levels.size]
        val level = createBloomTarget(binding, mainLevel.width, mainLevel.height, format, null)
            ?: return fail()
        allocated += level
        levels += level
    }
    if (existing == null) return BloomReachTargets(image, levels).also { resources.reach = it }
    for (index in existing.levels.size until levels.size) existing.addLevel(levels[index])
    return existing
}

internal sealed interface BloomResourcesResult {
    data class Created(val resources: BloomResources) : BloomResourcesResult
    /**
     * [halfFloatTargetRefused] is true only when an `RGBA16F` target -- the emission image or a
     * downsample level of a chain with `highPrecisionEmission`, or an upsample level -- was refused as
     * a format: an incomplete framebuffer or a GL error other than `GL_OUT_OF_MEMORY` (ADR 0085). A
     * program that fails to link, memory running out, or any RGBA8 target failing leaves it false,
     * since a wholly RGBA8 chain would fail the same way.
     */
    data class Failed(
        val failure: FailureDescriptor,
        val halfFloatTargetRefused: Boolean = false,
    ) : BloomResourcesResult
}

internal fun createBloomResources(
    binding: GlBinding,
    profile: RenderContextProfile,
    cache: GlProgramCache,
    outputSize: OutputPixelSize,
    sharedDepthRenderbuffer: Int,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
    highPrecisionEmission: Boolean = false,
    /**
     * Whether the upsample levels may be `RGBA16F` where the context can render it (ADR 0074). False
     * once the context has refused a half-float target, so the RGBA8 retry is RGBA8 throughout.
     */
    halfFloatUpsample: Boolean = true,
): BloomResourcesResult {
    require(!highPrecisionEmission || profile.supportsHalfFloatColourBuffer) {
        "a half-float emission chain requires a context that renders to half-float colour"
    }
    val emissionFormat = if (highPrecisionEmission) GL_RGBA16F else GL_RGBA8
    val downKey = deriver
        .internalPipeline(InternalPipelineRole.BLOOM_DOWNSAMPLE, BLOOM_DOWNSAMPLE_SHADER_PAIR).key
    val upKey = deriver
        .internalPipeline(InternalPipelineRole.BLOOM_UPSAMPLE, BLOOM_UPSAMPLE_SHADER_PAIR).key
    val compositeKey = deriver
        .internalPipeline(InternalPipelineRole.BLOOM_COMPOSITE, BLOOM_COMPOSITE_SHADER_PAIR).key

    val downProgram = compileBloomProgram(
        binding, profile.dialect, cache, downKey, BLOOM_DOWNSAMPLE_SHADER_PAIR,
    ) ?: return BloomResourcesResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, downKey))
    val upProgram = compileBloomProgram(
        binding, profile.dialect, cache, upKey, BLOOM_UPSAMPLE_SHADER_PAIR,
    ) ?: run {
        deleteBloomProgram(binding, cache, downKey)
        return BloomResourcesResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, upKey))
    }
    val compositeProgram = compileBloomProgram(
        binding, profile.dialect, cache, compositeKey, BLOOM_COMPOSITE_SHADER_PAIR,
    ) ?: run {
        deleteBloomProgram(binding, cache, downKey)
        deleteBloomProgram(binding, cache, upKey)
        return BloomResourcesResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, compositeKey))
    }

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]
    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    val quad = littleEndianBytes(COMPOSITE_QUAD)
    binding.bufferData(GL_ARRAY_BUFFER, quad.size, quad, GL_STATIC_DRAW)
    binding.enableVertexAttribArray(0)
    binding.vertexAttribPointer(0, 2, GL_FLOAT, false, BLOOM_QUAD_STRIDE_BYTES, 0)
    binding.enableVertexAttribArray(1)
    binding.vertexAttribPointer(1, 2, GL_FLOAT, false, BLOOM_QUAD_STRIDE_BYTES, BLOOM_QUAD_UV_OFFSET_BYTES)

    val allocated = ArrayList<BloomTarget>(1 + BLOOM_TARGET_LEVEL_COUNT * 2)
    var halfFloatTargetRefused = false
    val onEmissionTargetRefused: (Boolean) -> Unit = { formatRefused ->
        halfFloatTargetRefused = highPrecisionEmission && formatRefused
    }
    val upsampleFormat = if (highPrecisionEmission || (halfFloatUpsample && profile.supportsHalfFloatColourBuffer)) {
        GL_RGBA16F
    } else {
        GL_RGBA8
    }
    val onUpsampleTargetRefused: (Boolean) -> Unit = { formatRefused ->
        halfFloatTargetRefused = upsampleFormat == GL_RGBA16F && formatRefused
    }
    fun fail(): BloomResourcesResult.Failed {
        allocated.forEach { deleteBloomTarget(binding, it) }
        binding.deleteVertexArrays(1, intArrayOf(vertexArray))
        binding.deleteBuffers(1, intArrayOf(vertexBuffer))
        deleteBloomProgram(binding, cache, downKey)
        deleteBloomProgram(binding, cache, upKey)
        deleteBloomProgram(binding, cache, compositeKey)
        return BloomResourcesResult.Failed(
            glOperationFailure(PipelineStage.GPU_RESOURCE, downKey),
            halfFloatTargetRefused = halfFloatTargetRefused,
        )
    }

    val emission = createBloomTarget(
        binding, outputSize.width, outputSize.height, emissionFormat, sharedDepthRenderbuffer,
        onEmissionTargetRefused,
    ) ?: return fail()
    allocated += emission
    val destinationCopy = createBloomTarget(
        binding, outputSize.width, outputSize.height, GL_RGBA8, null,
    ) ?: return fail()
    allocated += destinationCopy

    val levelSizes = ArrayList<Pair<Int, Int>>(BLOOM_TARGET_LEVEL_COUNT + 1)
    var width = outputSize.width
    var height = outputSize.height
    levelSizes += width to height
    repeat(BLOOM_TARGET_LEVEL_COUNT) {
        width = maxOf(1, (width + 1) / 2)
        height = maxOf(1, (height + 1) / 2)
        levelSizes += width to height
    }

    val downsampleLevels = ArrayList<BloomTarget>(BLOOM_TARGET_LEVEL_COUNT)
    for (level in 1..BLOOM_TARGET_LEVEL_COUNT) {
        val size = levelSizes[level]
        val target = createBloomTarget(
            binding, size.first, size.second, emissionFormat, null, onEmissionTargetRefused,
        ) ?: return fail()
        allocated += target
        downsampleLevels += target
    }

    val upsampleLevels = ArrayList<BloomTarget>(BLOOM_TARGET_LEVEL_COUNT)
    for (level in 0 until BLOOM_TARGET_LEVEL_COUNT) {
        val size = levelSizes[level]
        val target = createBloomTarget(
            binding, size.first, size.second, upsampleFormat, null, onUpsampleTargetRefused,
        ) ?: return fail()
        allocated += target
        upsampleLevels += target
    }

    return BloomResourcesResult.Created(
        BloomResources(
            highPrecisionEmission = highPrecisionEmission,
            emission = emission,
            destinationCopy = destinationCopy,
            downsampleLevels = downsampleLevels,
            upsampleLevels = upsampleLevels,
            sharedDepthRenderbuffer = sharedDepthRenderbuffer,
            vertexArray = vertexArray,
            vertexBuffer = vertexBuffer,
            downsample = BloomDownsampleProgram(
                downKey,
                downProgram,
                binding.getUniformLocation(downProgram, BLOOM_SOURCE_UNIFORM),
                binding.getUniformLocation(downProgram, BLOOM_SOURCE_TEXEL_UNIFORM),
            ),
            upsample = BloomUpsampleProgram(
                upKey,
                upProgram,
                binding.getUniformLocation(upProgram, BLOOM_SOURCE_UNIFORM),
                binding.getUniformLocation(upProgram, BLOOM_LEVEL_UNIFORM),
                binding.getUniformLocation(upProgram, BLOOM_TARGET_TEXEL_UNIFORM),
                binding.getUniformLocation(upProgram, BLOOM_ACCUMULATION_WEIGHT_UNIFORM),
                binding.getUniformLocation(upProgram, BLOOM_LEVEL_WEIGHT_UNIFORM),
            ),
            composite = BloomCompositeProgram(
                compositeKey,
                compositeProgram,
                binding.getUniformLocation(compositeProgram, BLOOM_SOURCE_UNIFORM),
                binding.getUniformLocation(compositeProgram, BLOOM_EMISSION_UNIFORM),
                binding.getUniformLocation(compositeProgram, BLOOM_INTENSITY_UNIFORM),
                binding.getUniformLocation(compositeProgram, BLOOM_INSIDE_SUPPRESSION_UNIFORM),
                binding.getUniformLocation(compositeProgram, BLOOM_WHITENESS_UNIFORM),
                binding.getUniformLocation(compositeProgram, BLOOM_DESTINATION_UNIFORM),
            ),
        ),
    )
}

private fun compileBloomProgram(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    key: ResourceKey,
    shaderPair: ShaderPair,
): Int? {
    val vertexPlan = scanShaderProfile(shaderPair.vertexSource) ?: return null
    val fragmentPlan = scanShaderProfile(shaderPair.fragmentSource) ?: return null
    return when (val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> null
    }
}

private fun createBloomTarget(
    binding: GlBinding,
    width: Int,
    height: Int,
    internalFormat: Int,
    depthRenderbuffer: Int?,
    /**
     * Told, on a refusal, whether it looked like a refused format (an incomplete framebuffer or a GL
     * error other than `GL_OUT_OF_MEMORY`) rather than memory running out.
     */
    onRefused: (formatRefused: Boolean) -> Unit = {},
): BloomTarget? {
    GlErrorQueue.drainOnEntry(binding)
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, internalFormat, width, height)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(
        GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0,
    )
    depthRenderbuffer?.let {
        binding.framebufferRenderbuffer(
            GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, it,
        )
    }
    val status = binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER)
    val error = GlErrorQueue.firstOwnError(binding)
    if (status != GL_FRAMEBUFFER_COMPLETE || error != GL_NO_ERROR) {
        binding.deleteFramebuffers(1, intArrayOf(framebuffer))
        binding.deleteTextures(1, intArrayOf(texture))
        onRefused(status != GL_FRAMEBUFFER_COMPLETE || error != GL_OUT_OF_MEMORY)
        return null
    }
    return BloomTarget(framebuffer, texture, width, height)
}

internal fun deleteBloomResources(
    binding: GlBinding,
    cache: GlProgramCache,
    resources: BloomResources,
) {
    (listOf(resources.emission, resources.destinationCopy) + resources.downsampleLevels + resources.upsampleLevels)
        .forEach { deleteBloomTarget(binding, it) }
    resources.reach?.let { reach ->
        (listOf(reach.image) + reach.levels).forEach { deleteBloomTarget(binding, it) }
    }
    resources.reach = null
    binding.deleteVertexArrays(1, intArrayOf(resources.vertexArray))
    binding.deleteBuffers(1, intArrayOf(resources.vertexBuffer))
    deleteBloomProgram(binding, cache, resources.downsample.key)
    deleteBloomProgram(binding, cache, resources.upsample.key)
    deleteBloomProgram(binding, cache, resources.composite.key)
}

private fun deleteBloomTarget(binding: GlBinding, target: BloomTarget) {
    binding.deleteFramebuffers(1, intArrayOf(target.framebuffer))
    binding.deleteTextures(1, intArrayOf(target.texture))
}

private fun deleteBloomProgram(binding: GlBinding, cache: GlProgramCache, key: ResourceKey) {
    cache.remove(key)?.let(binding::deleteProgram)
}

/**
 * Whether a frame with [bloom] gets ADR 0085's half-float emission chain on a context with [profile]:
 * only when it asks for one and the context can render to half-float colour. A frame that asks and
 * cannot draws with the RGBA8 chain and reports `BLOOM_EMISSION_PRECISION_REDUCED`.
 */
internal fun bloomUsesHighPrecisionEmission(bloom: Bloom, profile: RenderContextProfile): Boolean =
    bloom.highPrecisionEmission && profile.supportsHalfFloatColourBuffer

/**
 * The downsample level a frame's far band is drawn into (ADR 0085): [Bloom.reachLevel], or the
 * deepest level [Bloom.levelWeights] computes when that is shallower, or `null` when the chain has no
 * downsample level at all.
 */
internal fun bloomFarBandLevel(bloom: Bloom): Int? {
    val lastLevel = bloom.levelWeights.lastIndex
    return if (lastLevel < 1) null else minOf(bloom.reachLevel, lastLevel)
}

/**
 * Whether a frame with [bloom] whose emissive geometries are [emitters] draws a far band at all
 * (ADR 0085): some emitter must have a positive reach, `reachGain` must be positive, and the chain
 * must have a downsample level to add it to. Otherwise nothing is drawn or allocated for it.
 */
internal fun bloomHasFarBand(bloom: Bloom, emitters: List<com.rohittp.reng.Geometry>): Boolean =
    bloomHasFarBand(bloom, emitters.any { it.emissive && it.bloomReach > 0f })

/** [bloomHasFarBand] for a caller that already knows whether [someEmitterReaches] with a positive reach. */
internal fun bloomHasFarBand(bloom: Bloom, someEmitterReaches: Boolean): Boolean =
    bloomFarBandLevel(bloom) != null && bloom.reachGain > 0f && someEmitterReaches

/**
 * How a frame's far band reaches downsample level [BloomFarBand.level] (ADR 0085).
 *
 * [REACH_IMAGE] is the only strategy a consumer can get. [COARSE_REDRAW] is internal and exists to
 * measure what the reach image buys: it rasterises the emitters straight into the level, at its
 * resolution, with the depth test off, so a line thinner than the level's pixel drops out between
 * sample centres and shimmers as the camera moves.
 */
internal enum class BloomFarBandStrategy {
    /**
     * Every reaching emitter is redrawn, scaled by its reach and depth-tested like the emission
     * redraw, into a full-resolution reach image; the identical downsample kernel takes that image
     * down to the level, where it is added scaled by `reachGain`.
     */
    REACH_IMAGE,

    /** Every reaching emitter is drawn straight into the level, scaled by `reach * reachGain`. */
    COARSE_REDRAW,
}

/**
 * ADR 0085's far band. [draw] draws every reaching emitter into the target it is handed, which is
 * bound with its viewport set: the cleared reach image under [BloomFarBandStrategy.REACH_IMAGE], or
 * the main chain's level [level] after the chain has written it under
 * [BloomFarBandStrategy.COARSE_REDRAW]. It owns its own depth and blend state; [drawBloom]
 * re-establishes its own afterwards.
 */
internal class BloomFarBand(
    val level: Int,
    val strategy: BloomFarBandStrategy,
    val draw: (BloomTarget) -> Unit,
)

/**
 * Runs the dual-Kawase chain and composites its result into [surfaceFramebuffer] without changing
 * alpha.
 *
 * The composite is not a fixed-function add: it reads the frame through a copy blitted into
 * [BloomResources.destinationCopy] (a texture cannot be sampled while it is the bound colour
 * attachment), sums frame and bloom, and soft-shoulders the brightest channel above
 * [BLOOM_SHOULDER_KNEE]. It is drawn with blending off and alpha writes masked, then the colour
 * mask is reopened, so the caller sees the same state the additive composite left except for the
 * read framebuffer binding, which is in the Restore Set.
 */
internal fun drawBloom(
    binding: GlBinding,
    resources: BloomResources,
    bloom: Bloom,
    surfaceFramebuffer: Int,
    farBand: BloomFarBand? = null,
) {
    val weights = bloom.levelWeights
    val lastLevel = weights.lastIndex
    binding.disable(GL_DEPTH_TEST)
    binding.depthMask(false)
    binding.disable(GL_BLEND)
    binding.bindVertexArray(resources.vertexArray)
    binding.bindSampler(0, 0)
    binding.bindSampler(1, 0)

    val reach = if (farBand?.strategy == BloomFarBandStrategy.REACH_IMAGE) {
        val targets = requireNotNull(resources.reach) { "a reach-image far band requires the reach targets" }
        check(targets.levels.size >= farBand.level) { "the reach targets must reach the far band's level" }
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targets.image.framebuffer)
        binding.viewport(0, 0, targets.image.width, targets.image.height)
        binding.clearColor(0f, 0f, 0f, 0f)
        binding.clear(GL_COLOR_BUFFER_BIT)
        farBand.draw(targets.image)
        restoreBloomChainState(binding, resources)
        var reachSource = targets.image
        for (level in 1..farBand.level) {
            val target = targets.levels[level - 1]
            drawBloomDownsample(binding, resources, reachSource, target)
            reachSource = target
        }
        targets
    } else {
        null
    }

    var source = resources.emission
    for (level in 1..lastLevel) {
        val target = resources.downsampleLevels[level - 1]
        drawBloomDownsample(binding, resources, source, target)
        if (farBand != null && farBand.level == level) {
            when (farBand.strategy) {
                BloomFarBandStrategy.REACH_IMAGE -> {
                    // The reach image's level, the same size as this one, added at reachGain with the
                    // level's alpha kept. The upsample program with no accumulation is exactly
                    // `reachGain * texture(level)`: both its samplers read the reach level, so the
                    // zero-weighted tent reads nothing undefined.
                    val reachLevel = checkNotNull(reach).levels[level - 1]
                    binding.enable(GL_BLEND)
                    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
                    binding.blendFuncSeparate(GL_ONE, GL_ONE, GL_ZERO, GL_ONE)
                    drawBloomUpsample(
                        binding = binding,
                        resources = resources,
                        target = target,
                        accumulation = reachLevel,
                        accumulationWeight = 0f,
                        level = reachLevel,
                        levelWeight = bloom.reachGain,
                    )
                    binding.disable(GL_BLEND)
                }
                BloomFarBandStrategy.COARSE_REDRAW -> {
                    farBand.draw(target)
                    restoreBloomChainState(binding, resources)
                }
            }
        }
        source = target
    }

    if (lastLevel == 0) {
        val target = resources.upsampleLevels[0]
        drawBloomUpsample(
            binding = binding,
            resources = resources,
            target = target,
            accumulation = resources.emission,
            accumulationWeight = 0f,
            level = resources.emission,
            levelWeight = weights[0],
        )
    } else {
        for (levelIndex in lastLevel - 1 downTo 0) {
            val target = resources.upsampleLevels[levelIndex]
            val first = levelIndex == lastLevel - 1
            val accumulation = if (first) {
                levelTarget(resources, lastLevel)
            } else {
                resources.upsampleLevels[levelIndex + 1]
            }
            drawBloomUpsample(
                binding = binding,
                resources = resources,
                target = target,
                accumulation = accumulation,
                accumulationWeight = if (first) weights[lastLevel] else 1f,
                level = levelTarget(resources, levelIndex),
                levelWeight = weights[levelIndex],
            )
        }
    }

    val composite = resources.composite
    val width = resources.emission.width
    val height = resources.emission.height
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, surfaceFramebuffer)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, resources.destinationCopy.framebuffer)
    binding.blitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, surfaceFramebuffer)
    binding.viewport(0, 0, resources.emission.width, resources.emission.height)
    binding.useProgram(composite.program)
    binding.bindVertexArray(resources.vertexArray)
    binding.activeTexture(GL_TEXTURE0)
    binding.bindTexture(GL_TEXTURE_2D, resources.upsampleLevels[0].texture)
    bindSamplerUniform(binding, composite.sourceLocation, 0)
    binding.activeTexture(GL_TEXTURE0 + 1)
    binding.bindTexture(GL_TEXTURE_2D, resources.emission.texture)
    bindSamplerUniform(binding, composite.emissionLocation, 1)
    binding.activeTexture(GL_TEXTURE0 + 2)
    binding.bindSampler(2, 0)
    binding.bindTexture(GL_TEXTURE_2D, resources.destinationCopy.texture)
    bindSamplerUniform(binding, composite.destinationLocation, 2)
    bindFloat(binding, composite.intensityLocation, bloom.intensity)
    bindFloat(binding, composite.insideSuppressionLocation, bloom.insideSuppression)
    bindFloat(binding, composite.whitenessLocation, bloom.whiteness)
    binding.disable(GL_BLEND)
    binding.colorMask(true, true, true, false)
    binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
    binding.colorMask(true, true, true, true)
    binding.activeTexture(GL_TEXTURE0)
}

private fun drawBloomDownsample(
    binding: GlBinding,
    resources: BloomResources,
    source: BloomTarget,
    target: BloomTarget,
) {
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, target.framebuffer)
    binding.viewport(0, 0, target.width, target.height)
    binding.useProgram(resources.downsample.program)
    binding.activeTexture(GL_TEXTURE0)
    binding.bindTexture(GL_TEXTURE_2D, source.texture)
    bindSamplerUniform(binding, resources.downsample.sourceLocation, 0)
    bindVec2(
        binding,
        resources.downsample.sourceTexelLocation,
        1f / source.width.toFloat(),
        1f / source.height.toFloat(),
    )
    binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
}

/** The state [drawBloom] sets on entry, re-established after a far band's geometry draws. */
private fun restoreBloomChainState(binding: GlBinding, resources: BloomResources) {
    binding.disable(GL_DEPTH_TEST)
    binding.depthMask(false)
    binding.disable(GL_BLEND)
    binding.bindVertexArray(resources.vertexArray)
    binding.bindSampler(0, 0)
    binding.bindSampler(1, 0)
}

private fun drawBloomUpsample(
    binding: GlBinding,
    resources: BloomResources,
    target: BloomTarget,
    accumulation: BloomTarget,
    accumulationWeight: Float,
    level: BloomTarget,
    levelWeight: Float,
) {
    val upsample = resources.upsample
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, target.framebuffer)
    binding.viewport(0, 0, target.width, target.height)
    binding.useProgram(upsample.program)
    binding.activeTexture(GL_TEXTURE0)
    binding.bindTexture(GL_TEXTURE_2D, accumulation.texture)
    bindSamplerUniform(binding, upsample.sourceLocation, 0)
    binding.activeTexture(GL_TEXTURE0 + 1)
    binding.bindTexture(GL_TEXTURE_2D, level.texture)
    bindSamplerUniform(binding, upsample.levelLocation, 1)
    bindVec2(
        binding,
        upsample.targetTexelLocation,
        1f / target.width.toFloat(),
        1f / target.height.toFloat(),
    )
    bindFloat(binding, upsample.accumulationWeightLocation, accumulationWeight)
    bindFloat(binding, upsample.levelWeightLocation, levelWeight)
    binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
}

private fun levelTarget(resources: BloomResources, level: Int): BloomTarget =
    if (level == 0) resources.emission else resources.downsampleLevels[level - 1]

private fun bindSamplerUniform(binding: GlBinding, location: Int, unit: Int) {
    if (location >= 0) binding.uniform1i(location, unit)
}

private fun bindFloat(binding: GlBinding, location: Int, value: Float) {
    if (location >= 0) binding.uniform1f(location, value)
}

private fun bindVec2(binding: GlBinding, location: Int, x: Float, y: Float) {
    if (location >= 0) binding.uniform2f(location, x, y)
}

private const val BLOOM_QUAD_STRIDE_BYTES: Int = 16
private const val BLOOM_QUAD_UV_OFFSET_BYTES: Int = 8
