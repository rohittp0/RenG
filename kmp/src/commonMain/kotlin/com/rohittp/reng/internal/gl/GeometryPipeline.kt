package com.rohittp.reng.internal.gl

import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile

/**
 * The documented shader interface (design spec "The shader interface"; ADR 0008; ADR 0024).
 *
 * **SILENT-RENAME HAZARD.** RenG binds a documented name only when the compiled program declares
 * it (ADR 0008): [GlBinding.getUniformLocation] and [GlBinding.getAttribLocation] both return a
 * negative location for a name the program never declared, and setting a uniform at a negative
 * location is a silent no-op in GL. [GeometryPipeline] and [drawGeometry] treat that negative
 * location as "do not bind," which is exactly the mechanism that lets a consumer shader naming
 * none of these compile and draw normally. It also means renaming one of the constants below is a
 * SILENT breaking change later: every consumer shader still using the old name keeps compiling and
 * keeps drawing, simply without that value ever being set again — no error, no warning, wrong
 * pixels. See ADR 0024 before touching any string here.
 */
internal const val ATTRIBUTE_POSITION: String = "aPosition"
internal const val ATTRIBUTE_TEXTURE_COORDINATE: String = "aTexCoord"
internal const val UNIFORM_MODEL_VIEW_PROJECTION: String = "uModelViewProjection"
internal const val UNIFORM_RESOLUTION: String = "uResolution"

/**
 * West, south, east, north in degrees. **INFORMATIONAL ONLY** — never derive a vertex position
 * from this uniform, and never let a future change to the vertex path read it. Vertex placement
 * stays camera-relative and exact through [ATTRIBUTE_POSITION] and
 * [UNIFORM_MODEL_VIEW_PROJECTION]: Cycle B's spikes measured camera-relative Float error below
 * 0.001 px, and this uniform's absolute degrees in a 32-bit float would discard that at the final
 * step. Mercator latitude is also not linear in screen space, so interpolating it across a quad
 * (which is what deriving `aPosition` from these bounds plus `aTexCoord` would do) is quietly
 * wrong — `CONTEXT.md` specifies that altitude interpolates north-to-south and deliberately says
 * nothing about latitude. A consumer wanting an approximate geographic position from inside a
 * shader may combine this uniform with `aTexCoord` themselves, visibly and by their own choice.
 */
internal const val UNIFORM_GEOMETRY_BOUNDS: String = "uGeometryBounds"
internal const val UNIFORM_FRAME_INDEX: String = "uFrameIndex"

/** Every documented name, for Task 7's reserved-name rejection at `Geometry` construction. */
internal val RESERVED_SHADER_NAMES: Set<String> = setOf(
    ATTRIBUTE_POSITION,
    ATTRIBUTE_TEXTURE_COORDINATE,
    UNIFORM_MODEL_VIEW_PROJECTION,
    UNIFORM_RESOLUTION,
    UNIFORM_GEOMETRY_BOUNDS,
    UNIFORM_FRAME_INDEX,
)

/**
 * One compiled consumer geometry program plus the quad it draws with. Every location field is
 * whatever [GlBinding.getAttribLocation] / [GlBinding.getUniformLocation] returned at creation —
 * negative when the program does not declare that name, per the hazard documented above.
 */
internal class GeometryPipeline(
    val key: ResourceKey,
    val program: Int,
    val vertexArray: Int,
    val vertexBuffer: Int,
    val positionAttributeLocation: Int,
    val texCoordAttributeLocation: Int,
    val modelViewProjectionLocation: Int,
    val resolutionLocation: Int,
    val geometryBoundsLocation: Int,
    val frameIndexLocation: Int,
)

internal sealed interface GeometryPipelineResult {
    data class Created(val pipeline: GeometryPipeline) : GeometryPipelineResult

    data class Failed(val failure: FailureDescriptor) : GeometryPipelineResult
}

/**
 * Compiles (or reuses, via [cache]) the program for [shaderPair] and builds the one quad every
 * draw of this geometry reuses. Follows [createCompositePipeline]'s shape as the reference call
 * site for compiling and caching a program.
 */
internal fun createGeometryPipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    shaderPair: ShaderPair,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): GeometryPipelineResult {
    val key = deriver.geometryProgram(shaderPair).key
    val vertexPlan = scanShaderProfile(shaderPair.vertexSource)
        ?: return GeometryPipelineResult.Failed(shaderProgramFailure(RenGErrorCode.SHADER_COMPILE_FAILED, key))
    val fragmentPlan = scanShaderProfile(shaderPair.fragmentSource)
        ?: return GeometryPipelineResult.Failed(shaderProgramFailure(RenGErrorCode.SHADER_COMPILE_FAILED, key))

    val program = when (
        val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return GeometryPipelineResult.Failed(result.failure)
    }

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]

    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)

    // ADR 0008: bind only the attributes this program actually declares. Unlike an unset uniform,
    // enabling a negative vertex attrib index is a genuine GL error rather than a harmless no-op,
    // so the guard here is load-bearing for correctness, not only for the "bind only when
    // declared" contract.
    val positionLocation = binding.getAttribLocation(program, ATTRIBUTE_POSITION)
    if (positionLocation >= 0) {
        binding.enableVertexAttribArray(positionLocation)
        binding.vertexAttribPointer(
            positionLocation,
            GEOMETRY_POSITION_COMPONENT_COUNT,
            GL_FLOAT,
            false,
            GEOMETRY_STRIDE_BYTES,
            0,
        )
    }
    val texCoordLocation = binding.getAttribLocation(program, ATTRIBUTE_TEXTURE_COORDINATE)
    if (texCoordLocation >= 0) {
        binding.enableVertexAttribArray(texCoordLocation)
        binding.vertexAttribPointer(
            texCoordLocation,
            GEOMETRY_TEXCOORD_COMPONENT_COUNT,
            GL_FLOAT,
            false,
            GEOMETRY_STRIDE_BYTES,
            GEOMETRY_TEXCOORD_OFFSET_BYTES,
        )
    }

    return GeometryPipelineResult.Created(
        GeometryPipeline(
            key = key,
            program = program,
            vertexArray = vertexArray,
            vertexBuffer = vertexBuffer,
            positionAttributeLocation = positionLocation,
            texCoordAttributeLocation = texCoordLocation,
            modelViewProjectionLocation = binding.getUniformLocation(program, UNIFORM_MODEL_VIEW_PROJECTION),
            resolutionLocation = binding.getUniformLocation(program, UNIFORM_RESOLUTION),
            geometryBoundsLocation = binding.getUniformLocation(program, UNIFORM_GEOMETRY_BOUNDS),
            frameIndexLocation = binding.getUniformLocation(program, UNIFORM_FRAME_INDEX),
        ),
    )
}

internal fun deleteGeometryPipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: GeometryPipeline,
) {
    binding.deleteVertexArrays(1, intArrayOf(pipeline.vertexArray))
    binding.deleteBuffers(1, intArrayOf(pipeline.vertexBuffer))
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

/**
 * Draws one geometry instance using an already-created [pipeline].
 *
 * [cameraRelativeCornersXyz] must already be resolved through the camera-relative path — see
 * `internal.planning.resolveGeometry` and `internal.projection.resolveMercatorCamera` — in
 * clockwise-from-top-left order (top-left, top-right, bottom-right, bottom-left), three floats
 * each. This function never reads a raw latitude/longitude/altitude and never computes a vertex
 * position itself, so it cannot be the place that discards Cycle B's camera-relative precision.
 *
 * [boundsWestSouthEastNorthDegrees] is the *separate*, informational `uGeometryBounds` payload
 * documented on [UNIFORM_GEOMETRY_BOUNDS]. It travels only to that one uniform and is never folded
 * into [cameraRelativeCornersXyz] or [modelViewProjection].
 *
 * Every uniform and attribute is set only when [pipeline] recorded a non-negative location for it
 * (ADR 0008): a shader declaring none of the six documented names still compiles and draws here,
 * and this function issues no `uniform*` call for a name [pipeline] did not resolve to a location
 * at creation.
 *
 * `frameIndex` is a `Long` narrowed to the `uint` `uFrameIndex` expects; the narrowing wraps at
 * 2^32 exactly as [GlBinding.uniform1ui] documents.
 */
internal fun drawGeometry(
    binding: GlBinding,
    pipeline: GeometryPipeline,
    cameraRelativeCornersXyz: FloatArray,
    modelViewProjection: FloatArray,
    resolutionWidthPixels: Float,
    resolutionHeightPixels: Float,
    boundsWestSouthEastNorthDegrees: FloatArray,
    frameIndex: Long,
) {
    require(cameraRelativeCornersXyz.size == GEOMETRY_CORNER_FLOAT_COUNT) {
        "a geometry requires exactly four camera-relative xyz corners"
    }
    require(modelViewProjection.size == GEOMETRY_MVP_FLOAT_COUNT) {
        "a model-view-projection matrix requires exactly sixteen elements"
    }
    require(boundsWestSouthEastNorthDegrees.size == GEOMETRY_BOUNDS_FLOAT_COUNT) {
        "geometry bounds require exactly west, south, east and north degrees"
    }

    binding.useProgram(pipeline.program)
    binding.bindVertexArray(pipeline.vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, pipeline.vertexBuffer)
    binding.bufferData(
        GL_ARRAY_BUFFER,
        GEOMETRY_VERTEX_BUFFER_BYTES,
        buildInterleavedVertexBytes(cameraRelativeCornersXyz),
        GL_DYNAMIC_DRAW,
    )

    if (pipeline.modelViewProjectionLocation >= 0) {
        binding.uniformMatrix4fv(pipeline.modelViewProjectionLocation, 1, false, modelViewProjection)
    }
    if (pipeline.resolutionLocation >= 0) {
        binding.uniform2f(pipeline.resolutionLocation, resolutionWidthPixels, resolutionHeightPixels)
    }
    if (pipeline.geometryBoundsLocation >= 0) {
        binding.uniform4f(
            pipeline.geometryBoundsLocation,
            boundsWestSouthEastNorthDegrees[0],
            boundsWestSouthEastNorthDegrees[1],
            boundsWestSouthEastNorthDegrees[2],
            boundsWestSouthEastNorthDegrees[3],
        )
    }
    if (pipeline.frameIndexLocation >= 0) {
        binding.uniform1ui(pipeline.frameIndexLocation, frameIndex.toInt())
    }

    binding.drawArrays(GL_TRIANGLE_STRIP, 0, GEOMETRY_VERTEX_COUNT)
}

/**
 * Reorders the resolver's clockwise-from-top-left corners into bottom-left, bottom-right,
 * top-left, top-right — the same triangle-strip layout [COMPOSITE_QUAD] already uses — and pairs
 * each with a texture coordinate so the top edge (north) reads `v=0` and the bottom edge (south)
 * reads `v=1`.
 */
private fun buildInterleavedVertexBytes(cameraRelativeCornersXyz: FloatArray): ByteArray {
    fun corner(index: Int): FloatArray =
        cameraRelativeCornersXyz.copyOfRange(index * 3, index * 3 + GEOMETRY_POSITION_COMPONENT_COUNT)

    val topLeft = corner(0)
    val topRight = corner(1)
    val bottomRight = corner(2)
    val bottomLeft = corner(3)

    val interleaved = floatArrayOf(
        bottomLeft[0], bottomLeft[1], bottomLeft[2], 0.0f, 1.0f,
        bottomRight[0], bottomRight[1], bottomRight[2], 1.0f, 1.0f,
        topLeft[0], topLeft[1], topLeft[2], 0.0f, 0.0f,
        topRight[0], topRight[1], topRight[2], 1.0f, 0.0f,
    )
    return littleEndianBytes(interleaved)
}

private const val GEOMETRY_VERTEX_COUNT: Int = 4
private const val GEOMETRY_POSITION_COMPONENT_COUNT: Int = 3
private const val GEOMETRY_TEXCOORD_COMPONENT_COUNT: Int = 2
private const val GEOMETRY_VERTEX_COMPONENT_COUNT: Int =
    GEOMETRY_POSITION_COMPONENT_COUNT + GEOMETRY_TEXCOORD_COMPONENT_COUNT
private const val GEOMETRY_STRIDE_BYTES: Int = GEOMETRY_VERTEX_COMPONENT_COUNT * Float.SIZE_BYTES
private const val GEOMETRY_TEXCOORD_OFFSET_BYTES: Int = GEOMETRY_POSITION_COMPONENT_COUNT * Float.SIZE_BYTES
private const val GEOMETRY_VERTEX_BUFFER_BYTES: Int = GEOMETRY_STRIDE_BYTES * GEOMETRY_VERTEX_COUNT
private const val GEOMETRY_CORNER_FLOAT_COUNT: Int = GEOMETRY_VERTEX_COUNT * GEOMETRY_POSITION_COMPONENT_COUNT
private const val GEOMETRY_BOUNDS_FLOAT_COUNT: Int = 4
private const val GEOMETRY_MVP_FLOAT_COUNT: Int = 16
