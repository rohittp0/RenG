package com.rohittp.reng.internal.gl

import com.rohittp.reng.BlendMode
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile

/**
 * RenG's own sticker shader. It is written as a GLES 3.00 source and travels through
 * [com.rohittp.reng.internal.shader.ShaderProfilePlan.sourceFor] exactly as a consumer's [ShaderPair]
 * does — RenG is not exempt from its own dialect-substitution rule, so compiling this pair exercises
 * that path on every platform RenG ever runs on, not only on frames carrying a consumer `Geometry`.
 *
 * The quad is a unit square centred on the origin; [ResolvedSticker.modelViewProjection] carries the
 * per-sticker position, rotation and scale, so [createStickerPipeline] allocates exactly one vertex
 * buffer, reused for every sticker in every frame.
 */
internal const val STICKER_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengStickerPosition;\n" +
        "layout(location = 1) in vec2 rengStickerTexCoord;\n" +
        "uniform mat4 rengStickerModelViewProjection;\n" +
        "out vec2 rengStickerUv;\n" +
        "void main() {\n" +
        "    rengStickerUv = rengStickerTexCoord;\n" +
        "    gl_Position = rengStickerModelViewProjection * vec4(rengStickerPosition, 0.0, 1.0);\n" +
        "}\n"

internal const val STICKER_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengStickerTexture;\n" +
        "uniform float rengStickerOpacity;\n" +
        "in vec2 rengStickerUv;\n" +
        "layout(location = 0) out vec4 rengStickerColour;\n" +
        "void main() {\n" +
        "    rengStickerColour = texture(rengStickerTexture, rengStickerUv) * rengStickerOpacity;\n" +
        "}\n"

internal val STICKER_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = STICKER_VERTEX_SOURCE, fragmentSource = STICKER_FRAGMENT_SOURCE)

internal const val STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME: String = "rengStickerModelViewProjection"
internal const val STICKER_TEXTURE_UNIFORM_NAME: String = "rengStickerTexture"
internal const val STICKER_OPACITY_UNIFORM_NAME: String = "rengStickerOpacity"

/**
 * The window-pixel-to-ground-rectangle homography a consumer sticker program reads through
 * `rengStickerGroundUv()` (ADR 0087), as a `mat4` acting on `vec4(gl_FragCoord.xy, 0.0, 1.0)` whose
 * `xy / w` is the rectangle's texture coordinate. A `mat4` rather than a `mat3` only because
 * [GlBinding] carries no `glUniformMatrix3fv`, and one projective map in the `z = 0` slice of a 4x4 is
 * the same nine numbers.
 */
internal const val STICKER_WINDOW_TO_GROUND_UNIFORM_NAME: String = "rengStickerWindowToGround"

/**
 * The whole window pixel the homography above is relative to (ADR 0087), subtracted from
 * `gl_FragCoord.xy` before the matrix applies so no frame-sized term enters the `Float` product.
 */
internal const val STICKER_GROUND_ORIGIN_UNIFORM_NAME: String = "rengStickerGroundOrigin"

/** `1.0` when this frame gives the program a ground rectangle, `0.0` otherwise (ADR 0087). */
internal const val STICKER_GROUND_PRESENT_UNIFORM_NAME: String = "rengStickerGroundPresent"

/**
 * The prefix every name of [STICKER_SHADER_PREAMBLE] shares, reserved against a `StickerShader`'s
 * uniform and texture names (ADR 0087) on ADR 0071's reasoning: a name RenG adds later under it
 * cannot collide with one a consumer already uses.
 */
internal const val STICKER_RESERVED_NAME_PREFIX: String = "rengSticker"

/**
 * The most consumer textures a `StickerShader` may declare: [MAXIMUM_CONSUMER_TEXTURES] less the one
 * unit the sticker's own image holds, so a sticker program keeps exactly the headroom below GLES 3.0's
 * sixteen guaranteed units that a Geometry's does.
 */
internal const val MAXIMUM_STICKER_SHADER_TEXTURES: Int = MAXIMUM_CONSUMER_TEXTURES - 1

/** The unit a consumer sticker program's first texture lands on; unit zero is the image's. */
internal const val STICKER_FIRST_CONSUMER_TEXTURE_UNIT: Int = 1

/**
 * What RenG inserts after a consumer sticker program's `#version` line (ADR 0087), and the whole of
 * the interface it promises that program.
 *
 * `rengStickerColour()` is [STICKER_FRAGMENT_SOURCE]'s expression verbatim, so a program returning it
 * draws the plain sticker's pixels; the readback suite holds it to bit-identity. The ground arm
 * subtracts the whole-pixel origin first -- exact in `Float` -- and divides only where the homogeneous
 * `w` is positive, which is exactly where the fragment's line of sight meets the rectangle's plane in
 * front of the camera; everywhere else -- and wherever the uniform is the zero matrix, which is what a
 * frame without ground binds -- it answers `vec2(-1.0)`, a point outside the rectangle.
 * `rengStickerHasGround()` is per frame, not per pixel: it says whether there is a rectangle to read
 * at all, so a consumer can tell "no ground this frame" from "this pixel is off the rectangle".
 *
 * Every line ends in LF and no line is a directive, so inserting it leaves the source a Shader
 * Profile whose directive is still the consumer's own first one.
 */
internal const val STICKER_SHADER_PREAMBLE: String =
    "precision highp float;\n" +
        "uniform sampler2D rengStickerTexture;\n" +
        "uniform float rengStickerOpacity;\n" +
        "uniform mat4 rengStickerWindowToGround;\n" +
        "uniform vec2 rengStickerGroundOrigin;\n" +
        "uniform float rengStickerGroundPresent;\n" +
        "in vec2 rengStickerUv;\n" +
        "vec4 rengStickerColour() {\n" +
        "    return texture(rengStickerTexture, rengStickerUv) * rengStickerOpacity;\n" +
        "}\n" +
        "bool rengStickerHasGround() {\n" +
        "    return rengStickerGroundPresent > 0.5;\n" +
        "}\n" +
        "vec2 rengStickerGroundUv() {\n" +
        "    vec2 rengStickerWindow = gl_FragCoord.xy - rengStickerGroundOrigin;\n" +
        "    vec4 rengStickerGround = rengStickerWindowToGround * vec4(rengStickerWindow, 0.0, 1.0);\n" +
        "    return rengStickerGround.w > 0.0\n" +
        "        ? rengStickerGround.xy / rengStickerGround.w\n" +
        "        : vec2(-1.0);\n" +
        "}\n"

/**
 * [fragmentSource] with [STICKER_SHADER_PREAMBLE] inserted on the line after its `#version 300 es`
 * directive, or `null` when it is not a Shader Profile at all.
 *
 * Inserted after the directive line's own span and before its terminator, so the directive keeps its
 * terminator and desktop substitution still replaces exactly that one line.
 */
internal fun composeStickerFragmentSource(fragmentSource: String): String? {
    val plan = scanShaderProfile(fragmentSource) ?: return null
    val end = plan.directiveEndExclusiveUtf16
    return fragmentSource.substring(0, end) + "\n" + STICKER_SHADER_PREAMBLE.removeSuffix("\n") +
        fragmentSource.substring(end)
}

/**
 * The pair a consumer sticker program is keyed by under [InternalPipelineRole.CONSUMER_STICKER]: RenG's
 * own vertex stage and the consumer's fragment source as written. The preamble is not in it because it
 * is a constant of this build, as the vertex stage is.
 */
internal fun consumerStickerShaderPair(fragmentSource: String): ShaderPair =
    ShaderPair(vertexSource = STICKER_VERTEX_SOURCE, fragmentSource = fragmentSource)

/** A unit quad (side length 1, centred on the origin) with texture coordinates spanning `[0, 1]`. */
internal val STICKER_QUAD: FloatArray = floatArrayOf(
    -0.5f, -0.5f, 0.0f, 1.0f,
    0.5f, -0.5f, 1.0f, 1.0f,
    -0.5f, 0.5f, 0.0f, 0.0f,
    0.5f, 0.5f, 1.0f, 0.0f,
)

internal class StickerPipeline(
    val key: ResourceKey,
    val program: Int,
    val vertexArray: Int,
    val vertexBuffer: Int,
    val modelViewProjectionUniformLocation: Int,
    val textureUniformLocation: Int,
    val opacityUniformLocation: Int = -1,
)

internal sealed interface StickerPipelineResult {
    data class Created(val pipeline: StickerPipeline) : StickerPipelineResult

    data class Failed(val failure: FailureDescriptor) : StickerPipelineResult
}

internal fun createStickerPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): StickerPipelineResult {
    val key = deriver.internalPipeline(InternalPipelineRole.STICKER, STICKER_SHADER_PAIR).key
    val vertexPlan = scanShaderProfile(STICKER_VERTEX_SOURCE)
        ?: return StickerPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    val fragmentPlan = scanShaderProfile(STICKER_FRAGMENT_SOURCE)
        ?: return StickerPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))

    val program = when (
        val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return StickerPipelineResult.Failed(result.failure)
    }

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]

    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    val quad = littleEndianBytes(STICKER_QUAD)
    binding.bufferData(GL_ARRAY_BUFFER, quad.size, quad, GL_STATIC_DRAW)
    binding.enableVertexAttribArray(0)
    binding.vertexAttribPointer(0, 2, GL_FLOAT, false, STICKER_STRIDE_BYTES, 0)
    binding.enableVertexAttribArray(1)
    binding.vertexAttribPointer(1, 2, GL_FLOAT, false, STICKER_STRIDE_BYTES, STICKER_UV_OFFSET_BYTES)

    return StickerPipelineResult.Created(
        StickerPipeline(
            key = key,
            program = program,
            vertexArray = vertexArray,
            vertexBuffer = vertexBuffer,
            modelViewProjectionUniformLocation = binding.getUniformLocation(
                program,
                STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME,
            ),
            textureUniformLocation = binding.getUniformLocation(program, STICKER_TEXTURE_UNIFORM_NAME),
            opacityUniformLocation = binding.getUniformLocation(program, STICKER_OPACITY_UNIFORM_NAME),
        ),
    )
}

internal fun deleteStickerPipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: StickerPipeline,
) {
    binding.deleteVertexArrays(1, intArrayOf(pipeline.vertexArray))
    binding.deleteBuffers(1, intArrayOf(pipeline.vertexBuffer))
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

/**
 * A sticker drawn with a fragment program the consumer wrote (ADR 0087), over RenG's own vertex stage.
 *
 * It owns no quad: a consumer program's vertex stage is [STICKER_VERTEX_SOURCE], whose attributes are
 * bound by layout location, so it draws from the plain [StickerPipeline]'s vertex array exactly as the
 * plain program does. [consumerLocations] memoises as [GeometryPipeline]'s does, and for its reason.
 */
internal class ConsumerStickerPipeline(
    val key: ResourceKey,
    val program: Int,
    val modelViewProjectionUniformLocation: Int,
    val textureUniformLocation: Int,
    val opacityUniformLocation: Int,
    val windowToGroundUniformLocation: Int,
    val groundOriginUniformLocation: Int = -1,
    val groundPresentUniformLocation: Int = -1,
) : ConsumerValueProgram {
    private val consumerLocations: MutableMap<String, Int> = HashMap()

    override val uniformNameOrder: ConsumerNameOrder = ConsumerNameOrder()
    override val textureNameOrder: ConsumerNameOrder = ConsumerNameOrder()

    override fun consumerLocation(binding: GlBinding, name: String): Int {
        consumerLocations[name]?.let { return it }
        val location = binding.getUniformLocation(program, name)
        if (location >= 0) consumerLocations[name] = location
        return location
    }
}

internal sealed interface ConsumerStickerPipelineResult {
    data class Created(val pipeline: ConsumerStickerPipeline) : ConsumerStickerPipelineResult

    data class Failed(val failure: FailureDescriptor) : ConsumerStickerPipelineResult
}

/**
 * Compiles [fragmentSource], with [STICKER_SHADER_PREAMBLE] inserted, against RenG's sticker vertex
 * stage, reporting a failure as the consumer's -- a syntax error in their program must not read as
 * RenG's GPU failing (ADR 0071's reasoning, which applies unchanged).
 */
internal fun createConsumerStickerPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    fragmentSource: String,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): ConsumerStickerPipelineResult {
    val key = deriver
        .internalPipeline(InternalPipelineRole.CONSUMER_STICKER, consumerStickerShaderPair(fragmentSource))
        .key
    val vertexPlan = scanShaderProfile(STICKER_VERTEX_SOURCE)
        ?: return ConsumerStickerPipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    val fragmentPlan = composeStickerFragmentSource(fragmentSource)?.let(::scanShaderProfile)
        ?: return ConsumerStickerPipelineResult.Failed(
            shaderProgramFailure(RenGErrorCode.SHADER_COMPILE_FAILED, key, consumerAuthored = true),
        )

    val program = when (
        val result = cache.getOrCompile(
            binding, dialect, key, vertexPlan, fragmentPlan, consumerAuthored = true,
        )
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return ConsumerStickerPipelineResult.Failed(result.failure)
    }

    return ConsumerStickerPipelineResult.Created(
        ConsumerStickerPipeline(
            key = key,
            program = program,
            modelViewProjectionUniformLocation =
                binding.getUniformLocation(program, STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME),
            textureUniformLocation = binding.getUniformLocation(program, STICKER_TEXTURE_UNIFORM_NAME),
            opacityUniformLocation = binding.getUniformLocation(program, STICKER_OPACITY_UNIFORM_NAME),
            windowToGroundUniformLocation =
                binding.getUniformLocation(program, STICKER_WINDOW_TO_GROUND_UNIFORM_NAME),
            groundOriginUniformLocation = binding.getUniformLocation(program, STICKER_GROUND_ORIGIN_UNIFORM_NAME),
            groundPresentUniformLocation = binding.getUniformLocation(program, STICKER_GROUND_PRESENT_UNIFORM_NAME),
        ),
    )
}

internal fun deleteConsumerStickerPipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: ConsumerStickerPipeline,
) {
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

/**
 * A consumer sticker program and what it is drawn with this frame: the uniform snapshot `prepare()`
 * took, each texture's uploaded name, and this frame's [StickerGroundMapping] (ADR 0087).
 */
internal class ResolvedStickerShader(
    val pipeline: ConsumerStickerPipeline,
    val uniforms: Map<String, ShaderValue>,
    val textures: Map<String, Int>,
    val ground: StickerGroundMapping,
)

/**
 * One sticker instance ready to draw: its already-resolved per-instance model-view-projection matrix
 * (column-major, matching [GlBinding.uniformMatrix4fv]) and its already-uploaded GL texture name.
 *
 * This used to carry a `screenCompositeZ` as well, for [drawStickers] to sort the screen stack by.
 * It no longer does: `MercatorSpatialPlanner` sorts that stack once, at `FRAME_PLANNING`, and
 * `SceneContent.drawScreenStack` walks the order it produced. A z carried down here would only be
 * an invitation to sort by it a second time, which is the duplicate that field's removal deletes.
 */
internal class ResolvedSticker(
    val modelViewProjection: FloatArray,
    val texture: Int,
    val opacity: Float = 1f,
    val blend: BlendMode = BlendMode.OVER,
    /** The consumer program it is drawn with instead of the plain one, or `null` (ADR 0087). */
    val shader: ResolvedStickerShader? = null,
)

/**
 * One frame's **map-anchored** stickers, in the order they are to be drawn.
 *
 * [mapAnchored] draws depth-tested but **depth-write-free**, **in the order it is given** — a
 * contract since ADR 0025 and, since ADR 0027, the whole of the rule rather than only its tie-break.
 * This KDoc once said the opposite ("in any order — the GPU depth buffer decides visibility between
 * map-anchored things, not draw order"); ADR 0025 replaced that with a tie-break, and ADR 0027
 * replaced the tie-break with an unconditional painter's order among map-regime content, because a
 * tie-break only ever resolved *exact* ties and the two defects that shipped were both near-ties.
 * The map regime still tests depth, so anything that genuinely wrote nearer depth still occludes
 * it; it just no longer writes depth itself. [drawStickers] therefore preserves the order it is
 * given and derives none of its own.
 *
 * **The screen regime is no longer this class's.** It used to carry a `screenAnchored` list that
 * [drawStickers] sorted and composited after the map half. Owning both regimes inside one
 * drawn-thing type is exactly what made that second sort possible, and the screen stack is one
 * ordered stack across *every* drawn-thing type (ADR 0024) rather than any one pipeline's. It now
 * lives in `SceneContent.drawScreenStack`, which walks the order `MercatorSpatialPlanner` already
 * produced.
 */
internal class StickerWorld(
    val mapAnchored: List<ResolvedSticker> = emptyList(),
)

/**
 * Draws the map-anchored half of one frame's stickers through [pipeline], depth-tested and
 * depth-write-free, **in exactly the order given** — this function sorts nothing and splits nothing.
 * Which stickers are map-anchored, and in what order, is `MercatorSpatialPlanner`'s answer, threaded
 * through `Scene.mapOrder`; re-deriving either here is the duplicate this pass no longer contains.
 *
 * **Why the map regime writes no depth (ADR 0027).** A map-anchored, screen-rotated sticker is a
 * billboard: its quad is screen-parallel, so every fragment carries the anchor's single depth while
 * the ground plane it stands on has depth varying down the screen. Under a pitched camera the half
 * of the quad below the anchor row is geometrically *under* the map plane and the depth test
 * deleted it — a cyan pin sliced exactly in half along a horizontal line through its own anchor,
 * visible at every pitch but zero. No constant or slope-scaled offset closes that, because the
 * deficit grows with the quad's own screen height. Removing the writes from every map-regime
 * surface removes the occluder instead, which is the only thing that makes the billboard whole.
 * The [GlBinding.depthMask] call below is **not** redundant with the model pass's exit mask
 * (ADR 0030 says so in as many words): it is the call actually holding that billboard fix up, and
 * every other map-regime pass sets the mask for itself for the same reason.
 *
 * The screen regime is drawn by `SceneContent.drawScreenStack`, not here, so this function neither
 * disables depth testing nor composites anything.
 */
internal fun drawStickers(binding: GlBinding, pipeline: StickerPipeline, world: StickerWorld) {
    if (world.mapAnchored.isEmpty()) return

    beginStickerPass(binding, pipeline)
    binding.enable(GL_DEPTH_TEST)
    binding.depthMask(false)
    var currentBlend = BlendMode.OVER
    world.mapAnchored.forEach { sticker ->
        if (sticker.blend != currentBlend) {
            applyPremultipliedBlendMode(binding, sticker.blend)
            currentBlend = sticker.blend
        }
        drawOneSticker(binding, pipeline, sticker)
    }
    applyPremultipliedBlendMode(binding, BlendMode.OVER)
}

/**
 * Binds [pipeline]'s program, quad and sampler unit, and establishes the premultiplied
 * `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` blend function that Task 4's premultiplied image upload requires,
 * rather than inheriting whatever the caller left bound — `drawFrame` restores GL state around a
 * frame, so this pipeline cannot rely on it to leave its own dependencies set up.
 *
 * Depth state is deliberately **not** set here: the two regimes that draw stickers want opposite
 * depth state ([drawStickers] tests depth, `SceneContent.drawScreenStack` turns testing off), and
 * folding it in would put one regime's rule inside the other's setup.
 */
internal fun beginStickerPass(binding: GlBinding, pipeline: StickerPipeline) {
    binding.useProgram(pipeline.program)
    binding.bindVertexArray(pipeline.vertexArray)
    binding.enable(GL_BLEND)
    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
    binding.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
    binding.activeTexture(GL_TEXTURE0)
    // The image's own sampler state, not a sampler object the host left on unit 0 (ADR 0055 saves
    // and restores it). A consumer sticker program's units 1 onwards are cleared as they are bound.
    binding.bindSampler(0, 0)
    if (pipeline.textureUniformLocation >= 0) {
        binding.uniform1i(pipeline.textureUniformLocation, 0)
    }
}

/**
 * Draws one already-resolved sticker with [pipeline] and its state already established by
 * [beginStickerPass]. Shared by both regimes, so a screen-composited sticker and a map-anchored one
 * differ only in the matrix they were composed with and the depth state around them.
 */
internal fun drawOneSticker(binding: GlBinding, pipeline: StickerPipeline, sticker: ResolvedSticker) {
    val shader = sticker.shader
    if (shader != null) {
        drawOneConsumerSticker(binding, pipeline, sticker, shader)
        return
    }
    if (pipeline.modelViewProjectionUniformLocation >= 0) {
        binding.uniformMatrix4fv(
            pipeline.modelViewProjectionUniformLocation,
            1,
            false,
            sticker.modelViewProjection,
        )
    }
    binding.bindTexture(GL_TEXTURE_2D, sticker.texture)
    if (pipeline.opacityUniformLocation >= 0) {
        binding.uniform1f(pipeline.opacityUniformLocation, sticker.opacity)
    }
    binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
}

/**
 * [drawOneSticker] for a sticker with a consumer program (ADR 0087): the same quad, matrix, image and
 * opacity, through the consumer's program, with its values bound and its textures on units one
 * onwards.
 *
 * It leaves exactly the state [beginStickerPass] established -- the plain program bound and unit zero
 * active -- so the callers, which bind that pass once and then draw stickers in a loop, need not know
 * which of their stickers carried a program. Blend and depth state it never touches, which is what
 * keeps a consumer sticker composited exactly as a plain one.
 */
private fun drawOneConsumerSticker(
    binding: GlBinding,
    pipeline: StickerPipeline,
    sticker: ResolvedSticker,
    shader: ResolvedStickerShader,
) {
    val consumer = shader.pipeline
    binding.useProgram(consumer.program)
    if (consumer.modelViewProjectionUniformLocation >= 0) {
        binding.uniformMatrix4fv(consumer.modelViewProjectionUniformLocation, 1, false, sticker.modelViewProjection)
    }
    if (consumer.opacityUniformLocation >= 0) {
        binding.uniform1f(consumer.opacityUniformLocation, sticker.opacity)
    }
    if (consumer.windowToGroundUniformLocation >= 0) {
        binding.uniformMatrix4fv(consumer.windowToGroundUniformLocation, 1, false, shader.ground.windowToGround)
    }
    if (consumer.groundOriginUniformLocation >= 0) {
        binding.uniform2f(consumer.groundOriginUniformLocation, shader.ground.originX, shader.ground.originY)
    }
    if (consumer.groundPresentUniformLocation >= 0) {
        binding.uniform1f(consumer.groundPresentUniformLocation, if (shader.ground.present) 1f else 0f)
    }
    bindConsumerValues(
        binding = binding,
        program = consumer,
        uniforms = shader.uniforms,
        textures = shader.textures,
        firstUnit = STICKER_FIRST_CONSUMER_TEXTURE_UNIT,
    )
    binding.activeTexture(GL_TEXTURE0)
    if (consumer.textureUniformLocation >= 0) {
        binding.uniform1i(consumer.textureUniformLocation, 0)
    }
    binding.bindTexture(GL_TEXTURE_2D, sticker.texture)
    binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
    binding.useProgram(pipeline.program)
}

internal fun applyPremultipliedBlendMode(binding: GlBinding, blend: BlendMode) {
    val (sourceRgb, destinationRgb) = when (blend) {
        BlendMode.OVER -> GL_ONE to GL_ONE_MINUS_SRC_ALPHA
        BlendMode.ADDITIVE -> GL_ONE to GL_ONE
        BlendMode.MULTIPLY -> GL_DST_COLOR to GL_ONE_MINUS_SRC_ALPHA
        BlendMode.SCREEN -> GL_ONE to GL_ONE_MINUS_SRC_COLOR
    }
    binding.blendFuncSeparate(sourceRgb, destinationRgb, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
}

private const val STICKER_STRIDE_BYTES: Int = 16
private const val STICKER_UV_OFFSET_BYTES: Int = 8
