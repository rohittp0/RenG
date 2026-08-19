package com.rohittp.reng.internal.gl

import com.rohittp.reng.Camera
import com.rohittp.reng.Geometry
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolveGeometry
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeometryPipelineTest {

    @Test
    fun theReservedNamesAreExactlyTheSixDocumentedShaderNames() {
        assertEquals(
            setOf(
                "aPosition", "aTexCoord",
                "uModelViewProjection", "uResolution", "uGeometryBounds", "uFrameIndex",
            ),
            RESERVED_SHADER_NAMES,
        )
    }

    // --- ADR 0008: bind only when the compiled program declares the name ---------------------

    @Test
    fun aShaderDeclaringNoDocumentedNameStillCompilesAndDraws() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createPipeline(binding)

        drawGeometry(binding, pipeline, testCorners(), IDENTITY_4X4, 800f, 600f, testBounds(), frameIndex = 7L)

        assertTrue(binding.log.any { it.startsWith("drawArrays") }, "it must still draw")
        assertTrue(binding.log.none { it.startsWith("uniform") }, "and set nothing it cannot set")
    }

    @Test
    fun creationSkipsAttributesTheProgramDoesNotDeclare() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createPipeline(binding)

        assertEquals(-1, pipeline.positionAttributeLocation)
        assertEquals(-1, pipeline.texCoordAttributeLocation)
        assertEquals(0, binding.log.count { it.startsWith("enableVertexAttribArray") })
    }

    @Test
    fun aShaderDeclaringOnlyFrameIndexGetsOnlyThatOneSet() {
        val binding = RecordingGlBinding().withDeclaredNames(UNIFORM_FRAME_INDEX to 4)
        val pipeline = createPipeline(binding)

        drawGeometry(binding, pipeline, testCorners(), IDENTITY_4X4, 800f, 600f, testBounds(), frameIndex = 7L)

        assertEquals(listOf("uniform1ui(4,7)"), binding.log.filter { it.startsWith("uniform") })
    }

    @Test
    fun allSixDocumentedNamesAreBoundWhenAllAreDeclared() {
        val binding = RecordingGlBinding().withDeclaredNames(
            ATTRIBUTE_POSITION to 0,
            ATTRIBUTE_TEXTURE_COORDINATE to 1,
            UNIFORM_MODEL_VIEW_PROJECTION to 2,
            UNIFORM_RESOLUTION to 3,
            UNIFORM_GEOMETRY_BOUNDS to 4,
            UNIFORM_FRAME_INDEX to 5,
        )
        val pipeline = createPipeline(binding)
        assertEquals(0, pipeline.positionAttributeLocation)
        assertEquals(1, pipeline.texCoordAttributeLocation)
        binding.log.clear()

        drawGeometry(
            binding, pipeline, testCorners(), IDENTITY_4X4,
            resolutionWidthPixels = 800f, resolutionHeightPixels = 600f,
            boundsWestSouthEastNorthDegrees = floatArrayOf(1f, 2f, 3f, 4f),
            frameIndex = 7L,
        )

        assertEquals(
            listOf(
                "uniformMatrix4fv(2,1,false)",
                "uniform2f(3,800.0,600.0)",
                "uniform4f(4,1.0,2.0,3.0,4.0)",
                "uniform1ui(5,7)",
            ),
            binding.log.filter { it.startsWith("uniform") },
        )
    }

    // --- frame index narrowing: FramePlan.frameIndex is a Long, uFrameIndex is a uint --------

    @Test
    fun aFrameIndexBeyondThirtyTwoBitsWrapsAsDocumented() {
        val binding = RecordingGlBinding().withDeclaredNames(UNIFORM_FRAME_INDEX to 4)
        val pipeline = createPipeline(binding)

        drawGeometry(
            binding, pipeline, testCorners(), IDENTITY_4X4, 800f, 600f, testBounds(),
            frameIndex = 0x1_0000_0007L,
        )

        assertEquals(listOf("uniform1ui(4,7)"), binding.log.filter { it.startsWith("uniform") })
    }

    @Test
    fun aNegativeFrameIndexBitPatternIsForwardedUnchanged() {
        // -1L's low 32 bits are all set, i.e. the uint bit pattern 0xFFFFFFFF. This pins that the
        // narrowing is a raw bit-pattern truncation, not a clamp.
        val binding = RecordingGlBinding().withDeclaredNames(UNIFORM_FRAME_INDEX to 4)
        val pipeline = createPipeline(binding)

        drawGeometry(binding, pipeline, testCorners(), IDENTITY_4X4, 800f, 600f, testBounds(), frameIndex = -1L)

        assertEquals(listOf("uniform1ui(4,-1)"), binding.log.filter { it.startsWith("uniform") })
    }

    // --- uGeometryBounds is informational only: it must never leak into aPosition ------------

    @Test
    fun theVertexBufferCarriesTheReorderedCornersAndUvsNeverTheBounds() {
        val binding = RecordingGlBinding().withDeclaredNames(ATTRIBUTE_POSITION to 0, UNIFORM_GEOMETRY_BOUNDS to 4)
        val pipeline = createPipeline(binding)
        val corners = floatArrayOf(
            1f, 2f, 3f, // top-left
            4f, 5f, 6f, // top-right
            7f, 8f, 9f, // bottom-right
            10f, 11f, 12f, // bottom-left
        )
        val bounds = floatArrayOf(100f, 200f, 300f, 400f)

        drawGeometry(binding, pipeline, corners, IDENTITY_4X4, 1f, 1f, bounds, frameIndex = 0L)

        assertEquals(listOf("uniform4f(4,100.0,200.0,300.0,400.0)"), binding.log.filter { it.startsWith("uniform") })

        val uploaded = decodeLittleEndianFloats(requireNotNull(binding.bufferDataPayloads[GL_ARRAY_BUFFER]))
        val expected = floatArrayOf(
            10f, 11f, 12f, 0f, 1f, // bottom-left
            7f, 8f, 9f, 1f, 1f, // bottom-right
            1f, 2f, 3f, 0f, 0f, // top-left
            4f, 5f, 6f, 1f, 0f, // top-right
        )
        assertContentEquals(expected, uploaded)
        assertTrue(bounds.none { boundsValue -> uploaded.any { it == boundsValue } }, "bounds must never appear in vertex data")
    }

    @Test
    fun vertexPositionsMatchTheCameraRelativeResolutionBitExactly() {
        // Proves the claim in GeometryPipeline's KDoc: drawGeometry never recomputes a vertex
        // position, so whatever internal.planning.resolveGeometry / resolveMercatorCamera resolve
        // through the camera-relative path survives into the uploaded buffer untouched (aside from
        // the intentional, already-measured Double-to-Float narrowing).
        val camera = (
            resolveMercatorCamera(
                camera = Camera(
                    latitude = 37.7749,
                    unwrappedLongitude = -122.4194,
                    zoom = 15.0,
                    bearing = 30.0,
                    pitch = 45.0,
                ),
                outputPixelSize = OutputPixelSize(width = 1024, height = 768),
            ) as SpatialOutcome.Success
            ).value
        val geometry = Geometry(
            topLeft = Vector3(37.7752, -122.4198, 12.0),
            bottomRight = Vector3(37.7746, -122.4190, 3.0),
            shaderPair = minimalShaderPair(),
        )
        val resolved = (resolveGeometry(geometry, camera) as SpatialOutcome.Success).value
        val cornersFloat = FloatArray(12)
        resolved.cornersClockwiseFromTopLeft.forEachIndexed { index, corner ->
            cornersFloat[index * 3] = corner.x.toFloat()
            cornersFloat[index * 3 + 1] = corner.y.toFloat()
            cornersFloat[index * 3 + 2] = corner.z.toFloat()
        }

        val binding = RecordingGlBinding().withDeclaredNames(ATTRIBUTE_POSITION to 0)
        val pipeline = createPipeline(binding)

        drawGeometry(
            binding, pipeline, cornersFloat, IDENTITY_4X4,
            resolutionWidthPixels = 1024f, resolutionHeightPixels = 768f,
            boundsWestSouthEastNorthDegrees = floatArrayOf(0f, 0f, 0f, 0f),
            frameIndex = 0L,
        )

        val uploaded = decodeLittleEndianFloats(requireNotNull(binding.bufferDataPayloads[GL_ARRAY_BUFFER]))
        // Layout is bottom-left, bottom-right, top-left, top-right; stride 5 floats (xyz + uv).
        val topLeftResolved = resolved.cornersClockwiseFromTopLeft[0]
        assertEquals(topLeftResolved.x.toFloat(), uploaded[10])
        assertEquals(topLeftResolved.y.toFloat(), uploaded[11])
        assertEquals(topLeftResolved.z.toFloat(), uploaded[12])
    }

    // --- Task 7: consumer uniforms dispatch by type, bound only when declared -----------------

    @Test
    fun consumerUniformsAreDispatchedByTypeToTheMatchingUniformSetter() {
        val binding = RecordingGlBinding().withDeclaredNames(
            "uScalar" to 10, "uVec2" to 11, "uVec3" to 12, "uVec4" to 13, "uInt" to 14, "uMat" to 15,
        )
        val pipeline = createPipeline(binding)
        binding.log.clear()

        drawGeometry(
            binding, pipeline, testCorners(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
            consumerUniforms = mapOf(
                "uScalar" to ShaderValue.Scalar(1f),
                "uVec2" to ShaderValue.Vec2(1f, 2f),
                "uVec3" to ShaderValue.Vec3(1f, 2f, 3f),
                "uVec4" to ShaderValue.Vec4(1f, 2f, 3f, 4f),
                "uInt" to ShaderValue.Integer(7),
                "uMat" to ShaderValue.Mat4(FloatArray(16) { it.toFloat() }),
            ),
        )

        // Sorted by name: uInt, uMat, uScalar, uVec2, uVec3, uVec4.
        assertEquals(
            listOf(
                "uniform1i(14,7)",
                "uniformMatrix4fv(15,1,false)",
                "uniform1f(10,1.0)",
                "uniform2f(11,1.0,2.0)",
                "uniform3f(12,1.0,2.0,3.0)",
                "uniform4f(13,1.0,2.0,3.0,4.0)",
            ),
            binding.log.filter { it.startsWith("uniform") },
        )
    }

    @Test
    fun aConsumerUniformNotDeclaredByTheProgramIsNeverSet() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createPipeline(binding)
        binding.log.clear()

        drawGeometry(
            binding, pipeline, testCorners(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
            consumerUniforms = mapOf("uUnused" to ShaderValue.Scalar(1f)),
        )

        assertTrue(binding.log.none { it.startsWith("uniform") }, "an undeclared consumer name binds nothing")
    }

    // --- Task 7: consumer textures take deterministic, name-sorted units ----------------------

    @Test
    fun consumerTexturesTakeStableUnitsSortedByNameAndTheirSamplersReceiveTheUnitIndex() {
        val binding = RecordingGlBinding().withDeclaredNames("uMaskB" to 9, "uMaskA" to 8)
        val pipeline = createPipeline(binding)
        binding.log.clear()

        drawGeometry(
            binding, pipeline, testCorners(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
            consumerTextures = linkedMapOf(
                "uMaskB" to DecodedImage(1, 1, byteArrayOf(0, 0, 0, -1)),
                "uMaskA" to DecodedImage(1, 1, byteArrayOf(0, 0, 0, -1)),
            ),
        )

        // Sorted by name regardless of map insertion order: uMaskA takes unit 0, uMaskB takes unit 1.
        assertEquals(listOf("uniform1i(8,0)", "uniform1i(9,1)"), binding.log.filter { it.startsWith("uniform1i") })
    }

    @Test
    fun aConsumerTextureSamplerNotDeclaredByTheProgramIsNeverSet() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createPipeline(binding)
        binding.log.clear()

        drawGeometry(
            binding, pipeline, testCorners(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
            consumerTextures = mapOf("uUnused" to DecodedImage(1, 1, byteArrayOf(0, 0, 0, -1))),
        )

        assertTrue(binding.log.none { it.startsWith("uniform1i") })
    }

    @Test
    fun drawGeometryRejectsMoreConsumerTexturesThanTheBudgetAllows() {
        val binding = RecordingGlBinding()
        val pipeline = createPipeline(binding)
        val tooMany = (0 until MAXIMUM_CONSUMER_TEXTURES + 1).associate {
            "uMask$it" to DecodedImage(1, 1, byteArrayOf(0, 0, 0, -1))
        }

        assertFailsWithIllegalArgument {
            drawGeometry(
                binding, pipeline, testCorners(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
                consumerTextures = tooMany,
            )
        }
    }

    // --- Task 7: the single most damaging error available here — DATA, never IMAGE ------------

    @Test
    fun consumerTexturesUploadBitExactAndAreNeverPremultiplied() {
        val binding = RecordingGlBinding().withDeclaredNames("uMask" to 8)
        val pipeline = createPipeline(binding)
        binding.log.clear()

        // 255, 0, 0, 128 unpremultiplied. If the implementation uploaded this through
        // TextureContent.IMAGE instead of TextureContent.DATA, byte 0 would come out premultiplied
        // to 128 (255 * 128 / 255, rounded), not stay 255 (as -1 in a signed byte) — silently
        // corrupting whatever a mask or signed-distance field packed into that channel. Swapping
        // DATA for IMAGE in GeometryPipeline's consumer-texture path must make this assertion fail.
        drawGeometry(
            binding, pipeline, testCorners(), IDENTITY_4X4, 1f, 1f, testBounds(), frameIndex = 0L,
            consumerTextures = mapOf("uMask" to DecodedImage(1, 1, byteArrayOf(-1, 0, 0, -128))),
        )

        assertEquals(listOf<Byte>(-1, 0, 0, -128), binding.lastTexImageBytes())
    }

    // --- creation / deletion, mirroring CompositePipelineTest's shape ------------------------

    @Test
    fun creationBuildsAProgramAndAQuad() {
        val binding = RecordingGlBinding().withDeclaredNames(ATTRIBUTE_POSITION to 0, ATTRIBUTE_TEXTURE_COORDINATE to 1)
        val pipeline = createPipeline(binding)

        assertTrue(pipeline.program > 0)
        assertTrue(pipeline.vertexArray > 0)
        assertTrue(pipeline.vertexBuffer > 0)
        assertEquals(2, binding.log.count { it.startsWith("enableVertexAttribArray") })
        assertEquals(2, binding.log.count { it.startsWith("vertexAttribPointer") })
    }

    @Test
    fun deletionRemovesTheQuadAndTheProgram() {
        val binding = RecordingGlBinding().withDeclaredNames(ATTRIBUTE_POSITION to 0)
        val cache = GlProgramCache()
        val pipeline =
            (createGeometryPipeline(binding, ShaderDialect.GLES, cache, minimalShaderPair()) as GeometryPipelineResult.Created).pipeline
        binding.log.clear()

        deleteGeometryPipeline(binding, cache, pipeline)

        assertEquals(1, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(1, binding.log.count { it.startsWith("deleteBuffers") })
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram") })
        assertEquals(null, cache.program(pipeline.key))
    }

    @Test
    fun theProgramIsCachedByShaderPairAcrossCreations() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val cache = GlProgramCache()
        val shaderPair = minimalShaderPair()

        val first =
            (createGeometryPipeline(binding, ShaderDialect.GLES, cache, shaderPair) as GeometryPipelineResult.Created).pipeline
        val compileCallsAfterFirst = binding.log.count { it.startsWith("compileShader") }
        val second =
            (createGeometryPipeline(binding, ShaderDialect.GLES, cache, shaderPair) as GeometryPipelineResult.Created).pipeline

        assertEquals(compileCallsAfterFirst, binding.log.count { it.startsWith("compileShader") })
        assertEquals(first.program, second.program)
    }

    @Test
    fun aShaderMissingTheVersionDirectiveIsATypedShaderCompileFailure() {
        val binding = RecordingGlBinding()
        val badPair = ShaderPair(vertexSource = "void main() {}", fragmentSource = minimalShaderPair().fragmentSource)

        val result = createGeometryPipeline(binding, ShaderDialect.GLES, GlProgramCache(), badPair)

        val failure = (result as GeometryPipelineResult.Failed).failure
        assertEquals(RenGErrorCode.SHADER_COMPILE_FAILED, failure.code)
    }

    // --- argument validation ------------------------------------------------------------------

    @Test
    fun drawGeometryRejectsAWrongCornerCount() {
        val binding = RecordingGlBinding()
        val pipeline = createPipeline(binding)
        assertFailsWithIllegalArgument {
            drawGeometry(binding, pipeline, FloatArray(11), IDENTITY_4X4, 1f, 1f, testBounds(), 0L)
        }
    }

    @Test
    fun drawGeometryRejectsAWrongBoundsCount() {
        val binding = RecordingGlBinding()
        val pipeline = createPipeline(binding)
        assertFailsWithIllegalArgument {
            drawGeometry(binding, pipeline, testCorners(), IDENTITY_4X4, 1f, 1f, FloatArray(3), 0L)
        }
    }

    private fun createPipeline(binding: RecordingGlBinding): GeometryPipeline =
        (createGeometryPipeline(binding, ShaderDialect.GLES, GlProgramCache(), minimalShaderPair()) as GeometryPipelineResult.Created).pipeline
}

private fun assertFailsWithIllegalArgument(block: () -> Unit) {
    var threw = false
    try {
        block()
    } catch (expected: IllegalArgumentException) {
        threw = true
    }
    assertTrue(threw, "expected an IllegalArgumentException")
}

private fun minimalShaderPair(): ShaderPair = ShaderPair(
    vertexSource = "#version 300 es\nvoid main() {\n    gl_Position = vec4(0.0, 0.0, 0.0, 1.0);\n}\n",
    fragmentSource = "#version 300 es\nprecision highp float;\nout vec4 rengOut;\nvoid main() {\n    rengOut = vec4(1.0);\n}\n",
)

private fun testCorners(): FloatArray = floatArrayOf(
    0f, 1f, 0f,
    1f, 1f, 0f,
    1f, 0f, 0f,
    0f, 0f, 0f,
)

private fun testBounds(): FloatArray = floatArrayOf(-1.0f, -1.0f, 1.0f, 1.0f)

private val IDENTITY_4X4: FloatArray = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    0f, 0f, 0f, 1f,
)

private fun decodeLittleEndianFloats(bytes: ByteArray): FloatArray {
    require(bytes.size % Float.SIZE_BYTES == 0) { "byte payload must hold whole floats" }
    return FloatArray(bytes.size / Float.SIZE_BYTES) { index ->
        val offset = index * Float.SIZE_BYTES
        val bits = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
        Float.fromBits(bits)
    }
}
