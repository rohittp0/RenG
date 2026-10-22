package com.rohittp.reng.internal.gl

import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.ShaderValue
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
/** `1` only while an emissive Geometry is redrawn into the frame emission image. */
internal const val UNIFORM_GEOMETRY_EMISSION_PASS: String = "rengGeometryEmissionPass"
internal const val GEOMETRY_RESERVED_NAME_PREFIX: String = "rengGeometry"

/** Every documented name, for Task 7's reserved-name rejection at `Geometry` construction. */
internal val RESERVED_SHADER_NAMES: Set<String> = setOf(
    ATTRIBUTE_POSITION,
    ATTRIBUTE_TEXTURE_COORDINATE,
    UNIFORM_MODEL_VIEW_PROJECTION,
    UNIFORM_RESOLUTION,
    UNIFORM_GEOMETRY_BOUNDS,
    UNIFORM_FRAME_INDEX,
    UNIFORM_GEOMETRY_EMISSION_PASS,
)

/**
 * The most consumer textures one `Geometry` may declare.
 *
 * GLES 3.0 guarantees only sixteen fragment texture image units (`GL_MAX_TEXTURE_IMAGE_UNITS`).
 * This budget stays strictly below that guaranteed minimum — rather than exactly saturating it —
 * so RenG's own draw of a geometry keeps headroom for a unit of its own, now or in a later cycle,
 * instead of the guarantee becoming a driver-dependent failure the moment RenG needs one. Exceeding
 * it is rejected at `Geometry` construction (loudly, at the point of the mistake) rather than
 * silently dropped or wrapped modulo sixteen at bind time.
 */
internal const val MAXIMUM_CONSUMER_TEXTURES: Int = 15

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
    /**
     * The element array buffer every [drawGeometry] call refills with [GeometryGrid.triangleIndices].
     *
     * It exists because a `Geometry` is no longer four vertices: RenG subdivides it and projects the
     * grid's vertices on the CPU so the view-projection stays linear and a consumer's shader pair
     * survives a globe unchanged (ADR 0008's 2026-08-29 erratum). Its binding is recorded in
     * [vertexArray] at creation, which is why nothing rebinds it at draw time.
     */
    val indexBuffer: Int,
    val positionAttributeLocation: Int,
    val texCoordAttributeLocation: Int,
    val modelViewProjectionLocation: Int,
    val resolutionLocation: Int,
    val geometryBoundsLocation: Int,
    val frameIndexLocation: Int,
    val emissionPassLocation: Int,
) : ConsumerValueProgram {
    /**
     * Consumer uniform and sampler names resolved against [program], memoised on first use.
     *
     * The six names above are known at creation and resolved there; a consumer's arrive with each
     * `Geometry`, and without this memo [drawGeometry] would ask the driver for every one on every
     * draw. A location is a property of the linked program, and the two are deleted together in
     * [deleteGeometryPipeline], so the memo cannot outlive what it describes.
     *
     * Negative locations are deliberately not cached. Consumer-authored names can vary without
     * bound between frames, while positive locations are bounded by what the linked program
     * actually declares.
     */
    private val consumerLocations: MutableMap<String, Int> = HashMap()

    /** [name]'s location in [program], from the memo above or from the driver exactly once. */
    override fun consumerLocation(binding: GlBinding, name: String): Int {
        consumerLocations[name]?.let { return it }
        val location = binding.getUniformLocation(program, name)
        if (location >= 0) consumerLocations[name] = location
        return location
    }

    override val uniformNameOrder: ConsumerNameOrder = ConsumerNameOrder()
    override val textureNameOrder: ConsumerNameOrder = ConsumerNameOrder()

    /**
     * Every consumer value RenG has set on [program] since it was linked (ADR 0080's 2026-10-22 note).
     * Owned by this pipeline because the program is: both are created together and deleted together in
     * [deleteGeometryPipeline], and a lost context replaces the pipeline and so starts a fresh shadow
     * for the freshly linked program.
     */
    override val consumerShadow: ConsumerValueShadow = ConsumerValueShadow()

    /**
     * The CPU byte buffers [drawGeometry] packs this pipeline's grid into before uploading it, reused
     * across draws and grown by doubling.
     *
     * Reused because on the globe and drape paths, where a grid reaches
     * [MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE] cells a side, fresh arrays would be about a megabyte
     * per geometry per frame.
     *
     * Safe to reuse only because they reach [GlBinding.bufferData] with an explicit byte count,
     * which every platform actual passes to `glBufferData` while pinning from index zero -- so an
     * oversized scratch uploads the prefix just packed, never the stale tail. Neither is passed
     * anywhere that would take its length from the array.
     */
    private var vertexScratch: ByteArray = ByteArray(0)
    private var indexScratch: ByteArray = ByteArray(0)

    /** The scratch [packVertexBytes] filled. Valid until the next call on this pipeline. */
    val vertexBytes: ByteArray get() = vertexScratch

    /** The scratch [packIndexBytes] filled. Valid until the next call on this pipeline. */
    val indexBytes: ByteArray get() = indexScratch

    /** Packs [values] little-endian into [vertexBytes] and returns how many bytes that took. */
    fun packVertexBytes(values: FloatArray): Int {
        val byteCount = values.size * Float.SIZE_BYTES
        if (vertexScratch.size < byteCount) {
            vertexScratch = ByteArray(grownScratchSize(vertexScratch.size, byteCount))
        }
        writeLittleEndianFloats(values, vertexScratch)
        return byteCount
    }

    /** Packs [values] little-endian into [indexBytes] and returns how many bytes that took. */
    fun packIndexBytes(values: ShortArray): Int {
        val byteCount = values.size * Short.SIZE_BYTES
        if (indexScratch.size < byteCount) {
            indexScratch = ByteArray(grownScratchSize(indexScratch.size, byteCount))
        }
        writeLittleEndianShorts(values, indexScratch)
        return byteCount
    }
}

/**
 * Doubling growth that always clears [required], from a zero-length start.
 *
 * Doubling rather than exact, matching [growIconBuffers]: exact growth reallocates on a grid that
 * gained one cell, which is the allocation this exists to remove.
 */
private fun grownScratchSize(current: Int, required: Int): Int {
    var size = if (current == 0) required else current
    while (size < required) size *= 2
    return size
}

/** Writes [values] little-endian into the first `values.size * 4` bytes of [out]. */
private fun writeLittleEndianFloats(values: FloatArray, out: ByteArray) {
    var offset = 0
    values.forEach { value ->
        val bits = value.toRawBits()
        out[offset] = (bits and 0xff).toByte()
        out[offset + 1] = ((bits ushr 8) and 0xff).toByte()
        out[offset + 2] = ((bits ushr 16) and 0xff).toByte()
        out[offset + 3] = ((bits ushr 24) and 0xff).toByte()
        offset += Float.SIZE_BYTES
    }
}

/**
 * Writes [values] little-endian into the first `values.size * 2` bytes of [out], as
 * `GL_UNSIGNED_SHORT` expects.
 *
 * A 16-bit index is what caps [MAXIMUM_GLOBE_GROUND_CELLS_PER_TILE_SIDE] at 128 for the globe ground
 * and for a geometry grid alike: 129 x 129 is 16,641 vertices where 257 x 257 would overflow.
 */
private fun writeLittleEndianShorts(values: ShortArray, out: ByteArray) {
    values.forEachIndexed { index, value ->
        val bits = value.toInt()
        out[index * 2] = (bits and 0xff).toByte()
        out[index * 2 + 1] = ((bits ushr 8) and 0xff).toByte()
    }
}

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
    binding.genBuffers(1, names)
    val indexBuffer = names[0]

    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    // Bound while the vertex array is bound and never unbound: an element array buffer binding is
    // vertex-array state, so re-binding the VAO at draw time restores it. `globeGroundGrid` does the
    // same thing for the same reason.
    binding.bindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer)

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
            indexBuffer = indexBuffer,
            positionAttributeLocation = positionLocation,
            texCoordAttributeLocation = texCoordLocation,
            modelViewProjectionLocation = binding.getUniformLocation(program, UNIFORM_MODEL_VIEW_PROJECTION),
            resolutionLocation = binding.getUniformLocation(program, UNIFORM_RESOLUTION),
            geometryBoundsLocation = binding.getUniformLocation(program, UNIFORM_GEOMETRY_BOUNDS),
            frameIndexLocation = binding.getUniformLocation(program, UNIFORM_FRAME_INDEX),
            emissionPassLocation = binding.getUniformLocation(program, UNIFORM_GEOMETRY_EMISSION_PASS),
        ),
    )
}

internal fun deleteGeometryPipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: GeometryPipeline,
) {
    binding.deleteVertexArrays(1, intArrayOf(pipeline.vertexArray))
    // Both buffers explicitly, for the reason `deleteUploadedPrimitive` already records: deleting a
    // VAO frees the VAO object alone, never the buffers whose bindings it recorded.
    binding.deleteBuffers(2, intArrayOf(pipeline.vertexBuffer, pipeline.indexBuffer))
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

/**
 * Draws one geometry instance using an already-created [pipeline].
 *
 * [grid] must already be resolved and projected through the camera-relative path — see
 * [geometryGrid] — and carries `x, y, z, u, v` per vertex plus the triangle indices over them. This
 * function never reads a raw latitude/longitude/altitude and never computes a vertex position
 * itself, so it cannot be the place that discards Cycle B's camera-relative precision.
 *
 * **A grid rather than four corners, in both projection modes.** A Mercator-to-sphere map is
 * nonlinear and so cannot be a matrix, so RenG subdivides a `Geometry` and projects its vertices on
 * the CPU, leaving [modelViewProjection] linear and a consumer's shader pair working unchanged in
 * both modes (ADR 0008's 2026-08-29 erratum, and the design spec's section 6). Two consequences are
 * recorded there rather than repaired: a shader reading `gl_VertexID` no longer sees 0 through 3 —
 * it sees an index into a grid whose size and ordering RenG chooses and may change between versions,
 * and a consumer wanting corner identity should read [ATTRIBUTE_TEXTURE_COORDINATE], which
 * subdivision interpolates correctly by construction — and `aPosition.z` carries pure altitude under
 * Mercator but altitude *and* the sphere's curvature on a globe.
 *
 * [boundsWestSouthEastNorthDegrees] is the *separate*, informational `uGeometryBounds` payload
 * documented on [UNIFORM_GEOMETRY_BOUNDS]. It travels only to that one uniform and is never folded
 * into [cameraRelativeCornersXyz] or [modelViewProjection].
 *
 * Every uniform and attribute is set only when [pipeline] recorded a non-negative location for it
 * (ADR 0008): a shader declaring none of the documented names still compiles and draws here,
 * and this function issues no `uniform*` call for a name [pipeline] did not resolve to a location
 * at creation.
 *
 * `frameIndex` is a `Long` narrowed to the `uint` `uFrameIndex` expects; the narrowing wraps at
 * 2^32 exactly as [GlBinding.uniform1ui] documents.
 *
 * [consumerUniforms] and [consumerTextures] are the [PreparedFrame][com.rohittp.reng.PreparedFrame]-time
 * SNAPSHOTS of `Geometry.uniforms` and `Geometry.textures` (Cycle F-1 Task 3), keyed by the
 * consumer's own sampler/uniform names rather than the documented ones above.
 * [consumerTextures] carries each name's ALREADY-UPLOADED GL texture object name, not a
 * [com.rohittp.reng.internal.image.DecodedImage] — the caller assembling one frame's
 * [SceneGeometry]s uploads (and caches, by `ResourceKey`, through `GlObjectRegistry`) once, so this
 * function issues no [uploadTexture] call of its own and cannot be the site of Task 9b's
 * texture-lifetime leak. Each name is resolved against [pipeline]'s program through
 * [GeometryPipeline.consumerLocation] — the same "bind only when declared" rule [pipeline]'s own six
 * locations already follow, reached differently because a consumer name's location cannot be
 * resolved at pipeline-creation time the way the documented ones are: the pipeline is shared and
 * content-keyed by [ShaderPair] alone, while the set of consumer names varies by `Geometry`
 * instance. So it is resolved on **first** draw that names it and memoised on the pipeline
 * thereafter, which is sound for the same reason creation-time resolution is: a location depends on
 * nothing but the linked program and the name, and the pipeline owns that program for its whole
 * life. Both maps are iterated in ascending key order (plain `String` comparison,
 * i.e. UTF-16 code-unit order — the same fixed, non-locale-sensitive ordering the canonical frame
 * encoding uses) purely so the same document always assigns the same texture units; the order
 * uniforms are set in has no observable effect since each lands at its own independent location.
 *
 * **`Geometry.uniforms`/`.textures` are never read here.** This function only ever sees the
 * `PreparedFrame`-time snapshot a caller already took (`com.rohittp.reng.RenGPreparedFrame`, Task
 * 9b) — never the live `Map` reference `Geometry` itself carries — so a consumer mutating either
 * map after `prepare()` cannot change what a later `draw()` on the same frame renders. This closes
 * the gap Task 7 could only document: the defensive `Map.toMap()` snapshot this function still
 * takes below guards only against the two arguments being structurally mutated mid-call, which is a
 * narrower, cheaper guarantee than the prepare-time snapshot that actually protects frame identity.
 *
 * Every consumer texture name in [consumerTextures] was uploaded through [TextureContent.DATA],
 * never [TextureContent.IMAGE], by the caller that resolved it: a consumer texture is a boundary
 * mask, a signed-distance field, or values packed across RGBA channels, and premultiplying any of
 * those corrupts it silently, with no error — see [uploadTexture]'s KDoc and `CONTEXT.md`'s
 * identical precedent for terrain samples.
 */
internal fun drawGeometry(
    binding: GlBinding,
    pipeline: GeometryPipeline,
    grid: GeometryGrid,
    modelViewProjection: FloatArray,
    resolutionWidthPixels: Float,
    resolutionHeightPixels: Float,
    boundsWestSouthEastNorthDegrees: FloatArray,
    frameIndex: Long,
    consumerUniforms: Map<String, ShaderValue> = emptyMap(),
    consumerTextures: Map<String, Int> = emptyMap(),
    emissionPass: Int = 0,
    /**
     * When set, the bloom phase's redraw of an emitter: instead of binding [consumerUniforms] and
     * [consumerTextures], the program and texture units are put back exactly as the emitter's normal
     * draw saw them (see [replayConsumerValues]). Both maps must then be empty.
     */
    consumerReplay: EmitterConsumerValues? = null,
) {
    require(modelViewProjection.size == GEOMETRY_MVP_FLOAT_COUNT) {
        "a model-view-projection matrix requires exactly sixteen elements"
    }
    require(boundsWestSouthEastNorthDegrees.size == GEOMETRY_BOUNDS_FLOAT_COUNT) {
        "geometry bounds require exactly west, south, east and north degrees"
    }
    require(consumerTextures.size <= MAXIMUM_CONSUMER_TEXTURES) {
        "a geometry may bind at most $MAXIMUM_CONSUMER_TEXTURES consumer textures"
    }

    // Iterated directly, not copied. Both maps are already private to the frame being drawn:
    // `PreparedGeometry.uniformsSnapshot` is a `.toMap()` taken at prepare() time and never the
    // caller's own reference, and the texture map is built fresh per frame from it, so no
    // production caller can mutate either during this iteration.
    //
    // That makes it the caller's obligation rather than this function's: pass maps nothing else can
    // mutate while this call runs.
    val uniformsSnapshot = consumerUniforms
    val texturesSnapshot = consumerTextures

    // A grid the camera pruned to nothing draws nothing, and says so by touching no GL state at
    // all rather than by uploading two empty buffers and issuing a zero-count draw. A geometry
    // entirely behind the limb is the ordinary way this happens on a globe.
    if (grid.triangleIndices.isEmpty()) return

    binding.useProgram(pipeline.program)
    binding.bindVertexArray(pipeline.vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, pipeline.vertexBuffer)
    // Packed into the pipeline's reused scratch and uploaded by explicit byte count, never by array
    // length: the scratch is a high-water buffer and its tail is whatever the last, larger draw left.
    val vertexByteCount = pipeline.packVertexBytes(grid.interleavedVertices)
    binding.bufferData(GL_ARRAY_BUFFER, vertexByteCount, pipeline.vertexBytes, GL_DYNAMIC_DRAW)
    val indexByteCount = pipeline.packIndexBytes(grid.triangleIndices)
    binding.bufferData(GL_ELEMENT_ARRAY_BUFFER, indexByteCount, pipeline.indexBytes, GL_DYNAMIC_DRAW)

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
    if (pipeline.emissionPassLocation >= 0) {
        binding.uniform1i(pipeline.emissionPassLocation, emissionPass)
    }

    if (consumerReplay != null) {
        replayConsumerValues(binding, pipeline, consumerReplay)
    } else {
        bindConsumerValues(
            binding = binding,
            program = pipeline,
            uniforms = uniformsSnapshot,
            textures = texturesSnapshot,
        )
    }

    binding.drawElements(GL_TRIANGLES, grid.triangleIndices.size, GL_UNSIGNED_SHORT, 0)
}

/** Restores the documented emission flag after an emission redraw has finished. */
internal fun endGeometryEmissionPass(binding: GlBinding, pipeline: GeometryPipeline) {
    if (pipeline.emissionPassLocation < 0) return
    binding.useProgram(pipeline.program)
    binding.uniform1i(pipeline.emissionPassLocation, 0)
}

/**
 * A linked program a consumer's uniforms and textures are bound to by name: the geometry pass's and
 * the consumer backdrop's (ADR 0071).
 */
internal interface ConsumerValueProgram {
    /** [name]'s location in this program, or a negative value when the program does not declare it. */
    fun consumerLocation(binding: GlBinding, name: String): Int

    /** The order [bindConsumerValues] sets consumer uniforms in. */
    val uniformNameOrder: ConsumerNameOrder

    /** The order [bindConsumerValues] assigns consumer textures to units `0..n-1` in. */
    val textureNameOrder: ConsumerNameOrder

    /** What [bindConsumerValues] has set on this program since link, for a program the bloom phase redraws. */
    val consumerShadow: ConsumerValueShadow? get() = null
}

/**
 * The names of a consumer value map in ascending `String` order, remembered for the last set of names
 * it was asked about, so a program drawn with the same names frame after frame sorts them once rather
 * than on every draw. A different set of names is sorted afresh and replaces the remembered one; the
 * answer is always exactly `keys.sorted()`.
 *
 * Confined to the GL thread, as the program it belongs to is.
 */
internal class ConsumerNameOrder {
    private var names: Array<String> = emptyArray()

    fun of(keys: Set<String>): Array<String> {
        val remembered = names
        if (remembered.size == keys.size && remembered.all { it in keys }) return remembered
        return keys.toTypedArray().also { it.sort() }.also { names = it }
    }
}

/**
 * Binds [uniforms] and then [textures] by name, each resolved against [program], in name order.
 *
 * Shared by the geometry pass and the consumer backdrop (ADR 0071) so the two cannot drift in how a
 * name resolves, what an undeclared one does (nothing -- ADR 0008), or which unit a texture lands
 * on. Name order rather than map order is what makes the unit assignment deterministic for a given
 * document, and plain `String` comparison is what keeps it locale-independent. The order comes from
 * [ConsumerValueProgram.textureNameOrder], which `ConsumerValueHistory.record` reads too, so the
 * history's model of which texture each unit holds is this assignment. Every value that reaches a
 * declared location is also written to the program's [ConsumerValueProgram.consumerShadow], a sampler
 * as the [ShaderValue.Integer] unit it was set to.
 *
 * The `activeTexture`/`bindTexture` pair runs even when the sampler is undeclared: the unit is
 * consumed either way, so skipping it would shift every later texture's unit.
 */
internal fun bindConsumerValues(
    binding: GlBinding,
    program: ConsumerValueProgram,
    uniforms: Map<String, ShaderValue>,
    textures: Map<String, Int>,
) {
    val shadow = program.consumerShadow
    if (uniforms.isNotEmpty()) {
        for (name in program.uniformNameOrder.of(uniforms.keys)) {
            val location = program.consumerLocation(binding, name)
            if (location >= 0) {
                val value = uniforms.getValue(name)
                bindConsumerUniform(binding, location, value)
                shadow?.set(name, value)
            }
        }
    }

    if (textures.isNotEmpty()) {
        val names = program.textureNameOrder.of(textures.keys)
        for (unitIndex in names.indices) {
            val name = names[unitIndex]
            val samplerLocation = program.consumerLocation(binding, name)
            binding.activeTexture(GL_TEXTURE0 + unitIndex)
            binding.bindTexture(GL_TEXTURE_2D, textures.getValue(name))
            if (samplerLocation >= 0) {
                binding.uniform1i(samplerLocation, unitIndex)
                shadow?.setSampler(name, unitIndex)
            }
        }
    }
}

/**
 * Every consumer value RenG has set on one geometry program since it was linked (ADR 0080's
 * 2026-10-22 note), a sampler as the [ShaderValue.Integer] unit it points at.
 *
 * Consumer programs are RenG's own: no consumer is ever handed the GL program name, so a value the
 * program holds is either one RenG set through [bindConsumerValues] or the bloom phase's replay and
 * restore, or the value the program was linked with -- zero of the uniform's type, since GLSL ES
 * allows no initialiser on a uniform. The shadow is therefore a complete account of the program's
 * consumer state: a name in it holds its value here, and a name absent from it holds zero.
 *
 * Only declared names are shadowed (a location of at least zero), so it is bounded by what the linked
 * program declares, however consumer-authored names vary between frames. The bloom phase's replay and
 * restore do not write it: they reproduce state it already describes.
 *
 * The map is copied on write: [snapshot] shares it as it stands, and the next [set] that changes a
 * value replaces it with a copy first. A [set] that changes nothing returns before copying, and a
 * frame with no emitter never shares the map, so a bloom-free frame pays one map lookup per value.
 *
 * Confined to the GL thread, as the program it belongs to is.
 */
internal class ConsumerValueShadow {
    private var values: HashMap<String, ShaderValue> = HashMap()
    private var shared: Boolean = false
    private val samplers: HashSet<String> = HashSet()

    /** Every name set on the program since link, with its current value. Do not retain; see [snapshot]. */
    val current: Map<String, ShaderValue> get() = values

    /** The names a texture was ever bound through; each one's value is the unit it points at. */
    val samplerNames: Set<String> get() = samplers

    fun set(name: String, value: ShaderValue) {
        if (values[name] == value) return
        if (shared) {
            values = HashMap(values)
            shared = false
        }
        values[name] = value
    }

    fun setSampler(name: String, unit: Int) {
        samplers.add(name)
        set(name, SAMPLER_UNIT_VALUES[unit])
    }

    /** The values as they stand, immutable from here on: a later [set] copies before it writes. */
    fun snapshot(): Map<String, ShaderValue> {
        shared = true
        return values
    }
}

/** The program value each consumer texture unit's sampler is set to, allocated once. */
private val SAMPLER_UNIT_VALUES: Array<ShaderValue.Integer> =
    Array(MAXIMUM_CONSUMER_TEXTURES) { ShaderValue.Integer(it) }

/**
 * One emitter's consumer state at its normal draw: every value its program held then, and the texture
 * each consumer unit held then (`-1` where the frame had bound none), indexed by unit.
 */
internal class EmitterConsumerValues(
    val uniforms: Map<String, ShaderValue>,
    val textureByUnit: IntArray,
)

/**
 * Puts [pipeline]'s bound program and the texture units its samplers read back exactly as [values]
 * records them, for the bloom phase's redraw of an emitter (ADR 0080's 2026-10-22 note).
 *
 * Every name the program's shadow holds now is set: to its value in [values], or, absent there, to
 * zero of its type -- a name the shadow did not yet hold at the emitter's draw had never been set since
 * link, so it held the link-time zero. Then each sampler's unit gets back the texture it held. No unit
 * is renumbered, so the units bound are ones the normal pass had already bound legally; a unit the
 * frame bound no consumer texture to is left as it is, because RenG does not know what it held.
 */
internal fun replayConsumerValues(binding: GlBinding, pipeline: GeometryPipeline, values: EmitterConsumerValues) {
    val shadow = pipeline.consumerShadow
    val snapshot = values.uniforms
    for ((name, current) in shadow.current) {
        val location = pipeline.consumerLocation(binding, name)
        if (location < 0) continue
        bindConsumerUniform(binding, location, snapshot[name] ?: zeroOf(current))
    }
    for (name in shadow.samplerNames) {
        val unit = (snapshot[name] as? ShaderValue.Integer)?.value ?: 0
        val texture = values.textureByUnit.getOrNull(unit) ?: -1
        if (texture < 0) continue
        binding.activeTexture(GL_TEXTURE0 + unit)
        binding.bindTexture(GL_TEXTURE_2D, texture)
    }
}

/** Sets every value [pipeline]'s shadow holds on its bound program: the state the normal pass left. */
internal fun restoreConsumerValues(binding: GlBinding, pipeline: GeometryPipeline) {
    for ((name, value) in pipeline.consumerShadow.current) {
        val location = pipeline.consumerLocation(binding, name)
        if (location >= 0) bindConsumerUniform(binding, location, value)
    }
}

/** The value a uniform of [like]'s type holds when its program is linked. */
private fun zeroOf(like: ShaderValue): ShaderValue = when (like) {
    is ShaderValue.Scalar -> ZERO_SCALAR
    is ShaderValue.Vec2 -> ZERO_VEC2
    is ShaderValue.Vec3 -> ZERO_VEC3
    is ShaderValue.Vec4 -> ZERO_VEC4
    is ShaderValue.Integer -> SAMPLER_UNIT_VALUES[0]
    is ShaderValue.Mat4 -> ZERO_MAT4
}

private val ZERO_SCALAR = ShaderValue.Scalar(0f)
private val ZERO_VEC2 = ShaderValue.Vec2(0f, 0f)
private val ZERO_VEC3 = ShaderValue.Vec3(0f, 0f, 0f)
private val ZERO_VEC4 = ShaderValue.Vec4(0f, 0f, 0f, 0f)
private val ZERO_MAT4 = ShaderValue.Mat4(FloatArray(16))

/**
 * Dispatches [value] to the one [GlBinding] setter matching its variant, at [location].
 *
 * Written as an expression body over an exhaustive `when` with **no `else` branch**, so a future
 * [ShaderValue] variant is a compiler error here rather than a silently unset uniform — the same
 * discipline Cycle D's `applyTerminal` applies to `RendererOwnerState`, and for the same reason: a
 * missing branch must fail loudly at compile time, not sample as an untouched GL default at
 * runtime.
 */
private fun bindConsumerUniform(binding: GlBinding, location: Int, value: ShaderValue): Unit = when (value) {
    is ShaderValue.Scalar -> binding.uniform1f(location, value.value)
    is ShaderValue.Vec2 -> binding.uniform2f(location, value.x, value.y)
    is ShaderValue.Vec3 -> binding.uniform3f(location, value.x, value.y, value.z)
    is ShaderValue.Vec4 -> binding.uniform4f(location, value.x, value.y, value.z, value.w)
    is ShaderValue.Integer -> binding.uniform1i(location, value.value)
    is ShaderValue.Mat4 -> binding.uniformMatrix4fv(location, 1, false, value.elementsForCore())
}

private const val GEOMETRY_POSITION_COMPONENT_COUNT: Int = 3
private const val GEOMETRY_TEXCOORD_COMPONENT_COUNT: Int = 2
internal const val GEOMETRY_VERTEX_COMPONENT_COUNT: Int =
    GEOMETRY_POSITION_COMPONENT_COUNT + GEOMETRY_TEXCOORD_COMPONENT_COUNT
private const val GEOMETRY_STRIDE_BYTES: Int = GEOMETRY_VERTEX_COMPONENT_COUNT * Float.SIZE_BYTES
private const val GEOMETRY_TEXCOORD_OFFSET_BYTES: Int = GEOMETRY_POSITION_COMPONENT_COUNT * Float.SIZE_BYTES
private const val GEOMETRY_BOUNDS_FLOAT_COUNT: Int = 4
private const val GEOMETRY_MVP_FLOAT_COUNT: Int = 16
