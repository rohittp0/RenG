package com.rohittp.reng.internal.gl

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.Camera
import com.rohittp.reng.Geometry
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.Placement
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolveGeometry
import com.rohittp.reng.internal.planning.resolvePlacement
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SceneContentTest {

    // --- the empty-scene case: the first thing a consumer hits while wiring an integration -----

    @Test
    fun anEmptySceneIssuesNoGlCallsAtAll() {
        val binding = RecordingGlBinding()
        SceneContent(topDownCamera(), Scene(outputPixelSize = OUTPUT_SIZE, frameIndex = 0L), newStickerPipeline())
            .draw(binding)
        assertContentEquals(emptyList(), binding.log)
    }

    // --- ADR 0024: both regimes compose in one frame, not merely in isolation ------------------

    @Test
    fun theMapRegimeDrawsGeometriesAndMapAnchoredStickersBeforeTheScreenRegimeComposites() {
        val binding = RecordingGlBinding()
        val camera = topDownCamera()
        val geometryPipeline = newGeometryPipeline(binding)
        val stickerPipeline = newStickerPipeline(binding)
        val mapTexture = 101
        val screenTexture = 202

        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(
                SceneSticker(mapPlacement(), texture = mapTexture),
                SceneSticker(screenPlacement(z = 5.0), texture = screenTexture),
            ),
            geometries = listOf(SceneGeometry(testGeometry(), geometryPipeline)),
        )
        binding.log.clear()

        SceneContent(camera, scene, stickerPipeline).draw(binding)

        val geometryDraw = binding.log.indexOfFirst { it.startsWith("drawArrays") }
        val depthDisabled = binding.log.indexOfFirst { it == "disable(${hex(GL_DEPTH_TEST)})" }
        val mapStickerBind = binding.log.indexOfFirst { it == "bindTexture(${hex(GL_TEXTURE_2D)},$mapTexture)" }
        val screenStickerBind = binding.log.indexOfFirst { it == "bindTexture(${hex(GL_TEXTURE_2D)},$screenTexture)" }
        val screenDraw = binding.log.indexOfLast { it.startsWith("drawArrays") }

        assertTrue(geometryDraw in 0 until depthDisabled, "the geometry must draw before depth testing is turned off")
        assertTrue(mapStickerBind in geometryDraw until depthDisabled, "the map-anchored sticker draws depth-tested too")
        assertTrue(depthDisabled < screenStickerBind, "the screen-anchored sticker composites after depth testing is off")
        assertTrue(depthDisabled in 0 until screenDraw, "every depth-tested thing draws before the screen regime composites")
    }

    // --- the precision path: SceneContent must not be the layer that discards it ---------------

    @Test
    fun geometryVertexPositionsSurviveSceneContentBitExactly() {
        // Mirrors GeometryPipelineTest's vertexPositionsMatchTheCameraRelativeResolutionBitExactly,
        // one layer up: SceneContent must convert Geometry plus the resolved camera into the same
        // camera-relative bytes that calling resolveGeometry directly produces.
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
        val expectedTopLeft = (resolveGeometry(geometry, camera) as SpatialOutcome.Success).value
            .cornersClockwiseFromTopLeft[0]

        val binding = RecordingGlBinding().withDeclaredNames(ATTRIBUTE_POSITION to 0)
        val pipeline = newGeometryPipeline(binding)
        val scene = Scene(
            outputPixelSize = OutputPixelSize(width = 1024, height = 768),
            frameIndex = 0L,
            geometries = listOf(SceneGeometry(geometry, pipeline)),
        )

        SceneContent(camera, scene, newStickerPipeline(RecordingGlBinding())).draw(binding)

        val uploaded = decodeLittleEndianFloats(requireNotNull(binding.bufferDataPayloads[GL_ARRAY_BUFFER]))
        // Layout is bottom-left, bottom-right, top-left, top-right; stride 5 floats (xyz + uv).
        assertEquals(expectedTopLeft.x.toFloat(), uploaded[10])
        assertEquals(expectedTopLeft.y.toFloat(), uploaded[11])
        assertEquals(expectedTopLeft.z.toFloat(), uploaded[12])
    }

    // --- the MVP composer: the largest piece of unwritten work this task supplies --------------

    @Test
    fun aGeometrysModelViewProjectionIsExactlyTheCamerasViewProjection() {
        // CONTEXT.md: "A Geometry carries no Placement" -- so uModelViewProjection for a geometry
        // has no per-object model term at all, only the camera's own projection * view.
        val camera = topDownCamera()
        val expected = expectedProjectionTimesView(camera)

        val binding = RecordingGlBinding().withDeclaredNames(UNIFORM_MODEL_VIEW_PROJECTION to 2)
        val pipeline = newGeometryPipeline(binding)
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            geometries = listOf(SceneGeometry(testGeometry(), pipeline)),
        )
        binding.log.clear()

        SceneContent(camera, scene, newStickerPipeline(RecordingGlBinding())).draw(binding)

        assertContentEquals(expected, binding.uniformMatrix4fvValues.getValue(2))
    }

    // --- the Task 7 / Task 8 seam: a SceneGeometry's consumer data must reach drawGeometry ------
    //
    // Merging Task 8's SceneContent (written against drawGeometry's pre-Task-7 signature) against
    // Task 7's added consumerUniforms/consumerTextures parameters compiles cleanly either way,
    // because both new parameters carry defaults -- so a merge that leaves SceneContent's call
    // site unchanged would build green and pass every pre-existing test while silently dropping
    // every consumer uniform and texture a Geometry declares. These two tests pin the actual wire:
    // a value present on the Geometry/SceneGeometry a caller hands SceneContent must show up in
    // the GL calls SceneContent.draw() issues, not merely compile.

    @Test
    fun aGeometrysConsumerUniformIsBoundWhenSceneContentDraws() {
        val binding = RecordingGlBinding().withDeclaredNames("uTint" to 20)
        val pipeline = newGeometryPipeline(binding)
        val geometryWithUniform = Geometry(
            topLeft = Vector3(1.0, -1.0, 10.0),
            bottomRight = Vector3(-1.0, 1.0, 0.0),
            shaderPair = minimalShaderPair(),
            uniforms = mapOf("uTint" to ShaderValue.Scalar(0.5f)),
        )
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            geometries = listOf(SceneGeometry(geometryWithUniform, pipeline)),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, newStickerPipeline(RecordingGlBinding())).draw(binding)

        assertTrue(
            binding.log.contains("uniform1f(20,0.5)"),
            "a Geometry.uniforms entry must reach drawGeometry's consumerUniforms and get bound: " +
                "${binding.log}",
        )
    }

    @Test
    fun aSceneGeometrysConsumerTextureIsBoundWhenSceneContentDraws() {
        // As of Task 9b, SceneGeometry.consumerTextures carries each name's ALREADY-UPLOADED GL
        // texture name (an Int) -- uploading and caching it by ResourceKey through GlObjectRegistry
        // is the job of whoever assembles the SceneGeometry (RenGRenderer), one layer above
        // SceneContent, so this test asserts binding, not upload.
        val binding = RecordingGlBinding().withDeclaredNames("uMask" to 21)
        val pipeline = newGeometryPipeline(binding)
        val geometry = Geometry(
            topLeft = Vector3(1.0, -1.0, 10.0),
            bottomRight = Vector3(-1.0, 1.0, 0.0),
            shaderPair = minimalShaderPair(),
            textures = mapOf("uMask" to ResourceLocator("https://example.invalid/mask.png")),
        )
        val sceneGeometry = SceneGeometry(
            geometry = geometry,
            pipeline = pipeline,
            consumerTextures = mapOf("uMask" to 909),
        )
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            geometries = listOf(sceneGeometry),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, newStickerPipeline(RecordingGlBinding())).draw(binding)

        assertTrue(
            binding.log.contains("uniform1i(21,0)"),
            "a SceneGeometry.consumerTextures entry must reach drawGeometry's consumerTextures, " +
                "take a texture unit, and bind its sampler: ${binding.log}",
        )
        assertTrue(
            binding.log.any { it == "bindTexture(0xDE1,909)" },
            "the already-uploaded texture name must be the one bound: ${binding.log}",
        )
        assertTrue(binding.log.none { it.startsWith("genTextures") }, "SceneContent must never upload a texture itself")
    }

    @Test
    fun aGeometrysConsumerUniformSnapshotIsUsedNotItsLiveMap() {
        // Pins the item-3 fix: SceneGeometry.consumerUniforms is a separate, explicit field from
        // Geometry.uniforms. A caller (RenGRenderer) that passes a DIFFERENT snapshot than the
        // Geometry's own live map must see ITS snapshot drawn, not the Geometry's.
        val binding = RecordingGlBinding().withDeclaredNames("uTint" to 20)
        val pipeline = newGeometryPipeline(binding)
        val geometry = Geometry(
            topLeft = Vector3(1.0, -1.0, 10.0),
            bottomRight = Vector3(-1.0, 1.0, 0.0),
            shaderPair = minimalShaderPair(),
            uniforms = mapOf("uTint" to ShaderValue.Scalar(0.5f)),
        )
        val sceneGeometry = SceneGeometry(
            geometry = geometry,
            pipeline = pipeline,
            consumerUniforms = mapOf("uTint" to ShaderValue.Scalar(0.9f)),
        )
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            geometries = listOf(sceneGeometry),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, newStickerPipeline(RecordingGlBinding())).draw(binding)

        assertTrue(binding.log.contains("uniform1f(20,0.9)"), "the explicit snapshot must be drawn: ${binding.log}")
        assertTrue(!binding.log.contains("uniform1f(20,0.5)"), "the Geometry's own live map must not be read: ${binding.log}")
    }

    @Test
    fun aScreenAnchoredStickerAtTheFramebufferCentreProjectsToTheOrigin() {
        // 2*x/width - 1 and 1 - 2*y/height both land exactly on 0 when the placement sits at the
        // framebuffer's centre pixel with no rotation and unit scale -- an exact, independently
        // derivable check of the orthographic screen composition, not a re-statement of its code.
        val placement = Placement(
            positionMode = AnchoringMode.SCREEN,
            position = Vector3(OUTPUT_SIZE.width / 2.0, OUTPUT_SIZE.height / 2.0, 7.0),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(0.0, 0.0, 0.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = 1.0,
        )
        val camera = topDownCamera()
        val resolved = (resolvePlacement(placement, camera) as SpatialOutcome.Success).value

        val mvp = composeScreenModelViewProjection(OUTPUT_SIZE, resolved)

        // Column 3 (indices 12..15) is M * (0,0,0,1)^T: the local quad's own origin.
        assertEquals(0.0f, mvp[12])
        assertEquals(0.0f, mvp[13])
        assertEquals(0.0f, mvp[14])
        assertEquals(1.0f, mvp[15])
    }

    @Test
    fun mapAnchoredStickersAtDifferentPositionsShareTheSameRotationScaleBlock() {
        // With SCREEN rotation/scale anchoring, directionTransform and logicalScale never depend on
        // position, so only the translation column of the composed MVP may differ between two
        // otherwise-identical map-anchored stickers placed a world apart.
        val camera = topDownCamera()
        val near = mapPlacementAt(Vector3(0.0, 0.0, 0.0))
        val far = mapPlacementAt(Vector3(10.0, 10.0, 0.0))
        val resolvedNear = (resolvePlacement(near, camera) as SpatialOutcome.Success).value
        val resolvedFar = (resolvePlacement(far, camera) as SpatialOutcome.Success).value

        val mvpNear = composeMapModelViewProjection(camera, resolvedNear)
        val mvpFar = composeMapModelViewProjection(camera, resolvedFar)

        // Columns 0 and 1 (indices 0..7) are the rotation-and-scale block; column 3 (12..15) is
        // translation and is expected to differ.
        assertContentEquals(mvpNear.copyOfRange(0, 8), mvpFar.copyOfRange(0, 8))
        assertTrue(
            mvpNear.copyOfRange(12, 16).toList() != mvpFar.copyOfRange(12, 16).toList(),
            "different world positions must produce different translations",
        )
    }

    // --- Task 9b item 2: a sticker's quad is sized from its image's own pixel dimensions -------

    @Test
    fun aStickersImageDimensionsScaleItsRotationAndScaleBlockRelativeToAUnitQuad() {
        // CONTEXT.md: a Sticker draws "as a centred local XY quad whose width and height are the
        // image's pixel dimensions." Before this fix STICKER_QUAD was a fixed unit square with
        // nothing scaling it by the image's own size, so at scale 1.0 a sticker rendered one pixel
        // across instead of its image's size. This pins that SceneContent now threads
        // SceneSticker.imageWidthPixels/imageHeightPixels into the composed MVP as a local pre-scale.
        //
        // The MVP uniform location must be declared -- RecordingGlBinding.getUniformLocation
        // returns -1 for anything undeclared, and drawOneSticker's `>= 0` guard would then skip the
        // uniformMatrix4fv call entirely, letting this test pass while asserting nothing.
        val mvpLocation = 3
        val binding = RecordingGlBinding().withDeclaredNames(
            STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME to mvpLocation,
            STICKER_TEXTURE_UNIFORM_NAME to 7,
        )
        val stickerPipeline = newStickerPipeline(binding)
        val placement = Placement(
            positionMode = AnchoringMode.SCREEN,
            position = Vector3(0.0, 0.0, 0.0),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(0.0, 0.0, 0.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = 1.0,
        )
        val scene = Scene(
            outputPixelSize = OUTPUT_SIZE,
            frameIndex = 0L,
            stickers = listOf(SceneSticker(placement, texture = 1, imageWidthPixels = 4, imageHeightPixels = 2)),
        )
        binding.log.clear()

        SceneContent(topDownCamera(), scene, stickerPipeline).draw(binding)

        val resolved = (resolvePlacement(placement, topDownCamera()) as SpatialOutcome.Success).value
        val expected = composeScreenModelViewProjection(OUTPUT_SIZE, resolved, DoubleVector3(4.0, 2.0, 1.0))
        val unitQuad = composeScreenModelViewProjection(OUTPUT_SIZE, resolved)

        val actual = requireNotNull(binding.uniformMatrix4fvValues[mvpLocation]) {
            "the sticker's MVP must have been bound"
        }
        assertContentEquals(expected, actual)
        assertTrue(actual.toList() != unitQuad.toList(), "a non-1x1 image must not draw as a unit quad")
    }

    // --- Task 9b item 5: draw-time resolution failure is a typed RenGException, not error(...) --

    @Test
    fun requireResolvedAtDrawTimeConvertsAFailureToATypedRedactedRenGException() {
        // The wrapped failure deliberately carries a DIFFERENT code and stage (GPU_RESOURCE, not
        // DRAW) than the exception this must throw, proving requireResolvedAtDrawTime reports its
        // own generic, redacted draw-time failure rather than merely rethrowing whatever it was
        // handed -- exactly the shape GlFrameDrawer.kt's own driver-error path already uses for
        // "something is wrong at draw time, nothing more specific to say."
        val wrapped = SpatialOutcome.Failure(glOperationFailure(PipelineStage.GPU_RESOURCE, resourceKey = null))

        val failure = assertFailsWith<RenGException> { wrapped.requireResolvedAtDrawTime() }

        assertEquals(RenGErrorCode.GPU_OPERATION_FAILED, failure.code)
        assertEquals(PipelineStage.DRAW, failure.stage)
        val rendered = failure.toString() + failure.message.orEmpty()
        assertTrue(!rendered.contains("Mesa", ignoreCase = true))
        assertTrue(!rendered.contains("GL_", ignoreCase = false))
    }

    private fun mapPlacementAt(position: Vector3): Placement = Placement(
        positionMode = AnchoringMode.MAP,
        position = position,
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.0,
    )

    private fun mapPlacement(): Placement = mapPlacementAt(Vector3(0.0, 0.0, 0.0))

    private fun screenPlacement(z: Double): Placement = Placement(
        positionMode = AnchoringMode.SCREEN,
        position = Vector3(400.0, 300.0, z),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.0,
    )

    private fun testGeometry(): Geometry = Geometry(
        topLeft = Vector3(1.0, -1.0, 10.0),
        bottomRight = Vector3(-1.0, 1.0, 0.0),
        shaderPair = minimalShaderPair(),
    )

    private fun topDownCamera(): ResolvedMercatorCamera = (
        resolveMercatorCamera(
            camera = Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 10.0, bearing = 0.0, pitch = 0.0),
            outputPixelSize = OUTPUT_SIZE,
        ) as SpatialOutcome.Success
        ).value

    private fun newGeometryPipeline(binding: RecordingGlBinding = RecordingGlBinding().withNoDeclaredNames()): GeometryPipeline =
        (
            createGeometryPipeline(binding, ShaderDialect.GLES, GlProgramCache(), minimalShaderPair())
                as GeometryPipelineResult.Created
            ).pipeline

    private fun newStickerPipeline(binding: RecordingGlBinding = RecordingGlBinding().withNoDeclaredNames()): StickerPipeline =
        (createStickerPipeline(binding, ShaderDialect.GLES, GlProgramCache()) as StickerPipelineResult.Created).pipeline

    private fun expectedProjectionTimesView(camera: ResolvedMercatorCamera): FloatArray {
        val result = FloatArray(16)
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += camera.projectionMatrix[row, k] * camera.viewMatrix[k, column]
                }
                result[column * 4 + row] = sum.toFloat()
            }
        }
        return result
    }

    private fun hex(value: Int): String = "0x${value.toString(16).uppercase()}"

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
}

private val OUTPUT_SIZE = OutputPixelSize(width = 800, height = 600)

private fun minimalShaderPair(): ShaderPair = ShaderPair(
    vertexSource = "#version 300 es\nvoid main() {\n    gl_Position = vec4(0.0, 0.0, 0.0, 1.0);\n}\n",
    fragmentSource = "#version 300 es\nprecision highp float;\nout vec4 rengOut;\nvoid main() {\n    rengOut = vec4(1.0);\n}\n",
)
