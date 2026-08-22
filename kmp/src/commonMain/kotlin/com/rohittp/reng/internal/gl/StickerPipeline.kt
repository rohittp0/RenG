package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
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
        "in vec2 rengStickerUv;\n" +
        "layout(location = 0) out vec4 rengStickerColour;\n" +
        "void main() {\n" +
        "    rengStickerColour = texture(rengStickerTexture, rengStickerUv);\n" +
        "}\n"

internal val STICKER_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = STICKER_VERTEX_SOURCE, fragmentSource = STICKER_FRAGMENT_SOURCE)

internal const val STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME: String = "rengStickerModelViewProjection"
internal const val STICKER_TEXTURE_UNIFORM_NAME: String = "rengStickerTexture"

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
 * One sticker instance ready to draw: its already-resolved per-instance model-view-projection matrix
 * (column-major, matching [GlBinding.uniformMatrix4fv]) and its already-uploaded GL texture name.
 *
 * [screenCompositeZ] is meaningless for a map-anchored sticker — the depth buffer decides visibility
 * there wherever depths differ at all — and is read only by [drawStickers] while sorting
 * [StickerWorld.screenAnchored].
 */
internal class ResolvedSticker(
    val modelViewProjection: FloatArray,
    val texture: Int,
    val screenCompositeZ: Double = 0.0,
)

/**
 * The two draw regimes ADR 0024 fixes for one frame's stickers.
 *
 * [mapAnchored] draws depth-tested, **in declaration order** — which, since ADR 0025, is a contract
 * rather than an incidental detail. This KDoc used to say the opposite ("in any order — the GPU depth
 * buffer decides visibility between map-anchored things, not draw order"), and that sentence stopped
 * being true the moment `drawFrame` switched from `GL_GREATER` to `GL_GEQUAL`: the depth buffer still
 * decides visibility wherever depths differ, but an exact tie — an altitude-0 sticker over the
 * coplanar basemap ground, or two altitude-0 stickers over each other — now passes the test, so the
 * last thing drawn is the thing seen. [drawStickers] therefore preserves the order it is given, and
 * `SceneContent` builds that order from `FramePlan.stickers` with the ground already drawn beneath.
 *
 * [screenAnchored] then composites on top as a single ordered stack: `CONTEXT.md` says greater
 * `position.z` composites on top and equal values keep stable plan order, so [drawStickers] sorts it
 * by [ResolvedSticker.screenCompositeZ] with a stable ascending sort and draws it after disabling
 * depth testing.
 */
internal class StickerWorld(
    val mapAnchored: List<ResolvedSticker> = emptyList(),
    val screenAnchored: List<ResolvedSticker> = emptyList(),
)

/**
 * Draws both regimes of [world] through [pipeline], in ADR 0024's order: the map regime first,
 * depth-tested, then the screen regime composited on top with depth testing off. Within the map
 * regime the given order is preserved exactly, because ADR 0025 makes it decide every depth tie.
 *
 * Blend state is set explicitly to the premultiplied `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` function that
 * Task 4's premultiplied image upload requires, rather than inherited from whatever the caller left
 * bound — `drawFrame` restores GL state around a frame, so this pipeline cannot rely on it to leave its
 * own dependencies set up and must establish them itself.
 */
internal fun drawStickers(binding: GlBinding, pipeline: StickerPipeline, world: StickerWorld) {
    if (world.mapAnchored.isEmpty() && world.screenAnchored.isEmpty()) return

    binding.useProgram(pipeline.program)
    binding.bindVertexArray(pipeline.vertexArray)
    binding.enable(GL_BLEND)
    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
    binding.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
    binding.activeTexture(GL_TEXTURE0)
    if (pipeline.textureUniformLocation >= 0) {
        binding.uniform1i(pipeline.textureUniformLocation, 0)
    }

    binding.enable(GL_DEPTH_TEST)
    world.mapAnchored.forEach { drawOneSticker(binding, pipeline, it) }
    binding.disable(GL_DEPTH_TEST)

    world.screenAnchored.sortedBy { it.screenCompositeZ }.forEach { drawOneSticker(binding, pipeline, it) }
}

private fun drawOneSticker(binding: GlBinding, pipeline: StickerPipeline, sticker: ResolvedSticker) {
    if (pipeline.modelViewProjectionUniformLocation >= 0) {
        binding.uniformMatrix4fv(
            pipeline.modelViewProjectionUniformLocation,
            1,
            false,
            sticker.modelViewProjection,
        )
    }
    binding.bindTexture(GL_TEXTURE_2D, sticker.texture)
    binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
}

private const val STICKER_STRIDE_BYTES: Int = 16
private const val STICKER_UV_OFFSET_BYTES: Int = 8
