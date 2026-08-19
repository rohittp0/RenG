package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StickerPipelineTest {
    @Test fun theRosterAddsStickerAtWireValueTwoWithoutRenumberingComposite() {
        // The regression guard that actually matters is InternalResourceKeyTest's pinned hex vector for
        // COMPOSITE; this is a direct, readable statement of the same fact.
        assertEquals(1, InternalPipelineRole.COMPOSITE.wireValue)
        assertEquals(2, InternalPipelineRole.STICKER.wireValue)
    }

    @Test fun theStickerSourcesAreAcceptedShaderProfileSources() {
        assertTrue(STICKER_VERTEX_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(STICKER_FRAGMENT_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(scanShaderProfile(STICKER_VERTEX_SOURCE) != null)
        assertTrue(scanShaderProfile(STICKER_FRAGMENT_SOURCE) != null)
    }

    @Test fun creationBuildsAProgramAQuadAndTwoAttributes() {
        val binding = newBinding()
        val pipeline = createStickerPipeline(binding, ShaderDialect.GLES, GlProgramCache())
            as StickerPipelineResult.Created
        assertTrue(pipeline.pipeline.program > 0)
        assertTrue(pipeline.pipeline.vertexArray > 0)
        assertTrue(pipeline.pipeline.vertexBuffer > 0)
        assertEquals(2, binding.log.count { it.startsWith("enableVertexAttribArray") })
        assertEquals(2, binding.log.count { it.startsWith("vertexAttribPointer") })
        assertTrue(binding.log.any { it.startsWith("bufferData(0x8892,64") })
        // The fake mirrors a real driver: an undeclared name resolves to -1. This pins that the
        // pipeline actually queries both of its documented uniform names against the compiled program,
        // not merely that it builds something.
        assertEquals(
            MODEL_VIEW_PROJECTION_LOCATION,
            pipeline.pipeline.modelViewProjectionUniformLocation,
        )
        assertEquals(TEXTURE_LOCATION, pipeline.pipeline.textureUniformLocation)
    }

    @Test fun deletionRemovesTheQuadAndTheProgram() {
        val binding = newBinding()
        val cache = GlProgramCache()
        val pipeline =
            (createStickerPipeline(binding, ShaderDialect.GLES, cache) as StickerPipelineResult.Created).pipeline
        binding.log.clear()
        deleteStickerPipeline(binding, cache, pipeline)
        assertEquals(1, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(1, binding.log.count { it.startsWith("deleteBuffers") })
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram") })
        assertNull(cache.program(pipeline.key))
    }

    @Test fun theMapRegimeDrawsDepthTestedBeforeTheScreenRegimeComposites() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        val mapTexture = 11
        val screenTexture = 22
        val world = StickerWorld(
            mapAnchored = listOf(resolvedSticker(texture = mapTexture)),
            screenAnchored = listOf(resolvedSticker(texture = screenTexture, z = 5.0)),
        )
        binding.log.clear()
        drawStickers(binding, pipeline, world)

        val depthEnabled = binding.log.indexOfFirst { it == "enable(0xB71)" } // GL_DEPTH_TEST
        val depthDisabled = binding.log.indexOfFirst { it == "disable(0xB71)" }
        val mapDraw = binding.log.indexOfFirst { it.startsWith("drawArrays") }
        val screenDraw = binding.log.indexOfLast { it.startsWith("drawArrays") }

        assertTrue(depthEnabled in 0 until mapDraw, "the map regime must be depth-tested")
        assertTrue(mapDraw < depthDisabled, "depth must stay on until the map regime is finished")
        assertTrue(depthDisabled < screenDraw, "the screen regime must composite with depth off")

        // The assertions above only check drawArrays call *positions*, so a bug that swaps which of
        // mapAnchored/screenAnchored feeds the depth-tested loop versus the depth-off loop -- while
        // keeping the enable/disable/draw call shape intact -- would still pass them. Tie each
        // sticker's specific texture to the depth state active when its texture bound, so that swap
        // fails here instead.
        val mapBind = binding.log.indexOfFirst { it == "bindTexture(0xDE1,$mapTexture)" } // GL_TEXTURE_2D
        val screenBind = binding.log.indexOfFirst { it == "bindTexture(0xDE1,$screenTexture)" }
        assertTrue(
            mapBind in depthEnabled until depthDisabled,
            "the map-anchored sticker's texture must bind while depth testing is on",
        )
        assertTrue(
            screenBind > depthDisabled,
            "the screen-anchored sticker's texture must bind after depth testing is turned off",
        )
    }

    @Test fun equalZIndexCompositesInStablePlanOrder() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        val firstTexture = 11
        val secondTexture = 22
        val world = StickerWorld(
            screenAnchored = listOf(
                resolvedSticker(texture = firstTexture, z = 1.0),
                resolvedSticker(texture = secondTexture, z = 1.0),
            ),
        )
        binding.log.clear()
        drawStickers(binding, pipeline, world)

        val firstBind = binding.log.indexOfFirst { it == "bindTexture(0xDE1,$firstTexture)" } // GL_TEXTURE_2D
        val secondBind = binding.log.indexOfFirst { it == "bindTexture(0xDE1,$secondTexture)" }
        assertTrue(firstBind < secondBind, "later plan entries composite on top, so they draw later")
    }

    // Equal z-index alone cannot distinguish an ascending sort from a descending one: a stable sort
    // preserves the relative order of elements the comparator treats as equal no matter which direction
    // it sorts in, so a bug that reverses the sort direction would slip past
    // `equalZIndexCompositesInStablePlanOrder` unnoticed. This test uses two DIFFERENT z values instead,
    // so only the correct (ascending) direction draws the greater one last.
    @Test fun greaterZIndexComposesOnTopOfLesserZIndex() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        val lowTexture = 11
        val highTexture = 22
        val world = StickerWorld(
            screenAnchored = listOf(
                resolvedSticker(texture = highTexture, z = 5.0),
                resolvedSticker(texture = lowTexture, z = 1.0),
            ),
        )
        binding.log.clear()
        drawStickers(binding, pipeline, world)

        val lowBind = binding.log.indexOfFirst { it == "bindTexture(0xDE1,$lowTexture)" }
        val highBind = binding.log.indexOfFirst { it == "bindTexture(0xDE1,$highTexture)" }
        assertTrue(lowBind < highBind, "greater position.z must composite on top, so it draws last")
    }

    @Test fun theBlendModeIsPremultipliedNotStraightAlpha() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawStickers(binding, pipeline, StickerWorld(mapAnchored = listOf(resolvedSticker())))

        // GL_ONE(0x1), GL_ONE_MINUS_SRC_ALPHA(0x303) for both rgb and alpha: the premultiplied function
        // Task 4's premultiplied upload requires. GL_SRC_ALPHA(0x302) would be the straight-alpha
        // function a caller might reach for instead, and nothing else in this suite pins the distinction.
        assertTrue(binding.log.any { it == "blendFuncSeparate(0x1,0x303,0x1,0x303)" })
        assertFalse(binding.log.any { it.startsWith("blendFuncSeparate(0x302,") })
    }

    @Test fun anEntirelyEmptyWorldIssuesNoGlCallsAtAll() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawStickers(binding, pipeline, StickerWorld())
        assertContentEquals(emptyList(), binding.log)
    }

    // RecordingGlBinding's getUniformLocation/getAttribLocation return -1 for any name the binding was
    // not told to declare (matching a real driver for a name the linked program never declares). Every
    // other test in this file uses newBinding(), which declares both of this pipeline's uniform names,
    // so drawStickers's `>= 0` guards are exercised on their true (bind) branch throughout this suite --
    // not merely left untested because the fake happened to answer every query the same way. This test
    // pins that behaviour directly: with both names declared, drawing must actually issue the uniform
    // binds, at the exact locations createStickerPipeline resolved.
    @Test fun drawingBindsBothDeclaredUniformsAtTheirResolvedLocations() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        assertEquals(MODEL_VIEW_PROJECTION_LOCATION, pipeline.modelViewProjectionUniformLocation)
        assertEquals(TEXTURE_LOCATION, pipeline.textureUniformLocation)

        binding.log.clear()
        drawStickers(binding, pipeline, StickerWorld(mapAnchored = listOf(resolvedSticker())))

        assertTrue(
            binding.log.any { it == "uniformMatrix4fv($MODEL_VIEW_PROJECTION_LOCATION,1,false)" },
            "the model-view-projection uniform must be bound when the shader declares it",
        )
        assertTrue(
            binding.log.any { it == "uniform1i($TEXTURE_LOCATION,0)" },
            "the texture uniform must be bound to texture unit 0 when the shader declares it",
        )
    }

    // The mirror image of the test above: when the shader declares neither name (RecordingGlBinding's
    // own default), createStickerPipeline resolves both locations to -1 and drawStickers's `>= 0` guards
    // must skip the binds entirely, exactly as they would for a real compiled program missing both
    // uniforms.
    @Test fun drawingSkipsBothUniformBindsWhenNeitherIsDeclared() {
        val binding = RecordingGlBinding().withNoDeclaredNames()
        val pipeline = createdPipeline(binding)
        assertEquals(-1, pipeline.modelViewProjectionUniformLocation)
        assertEquals(-1, pipeline.textureUniformLocation)

        binding.log.clear()
        drawStickers(binding, pipeline, StickerWorld(mapAnchored = listOf(resolvedSticker())))

        assertFalse(binding.log.any { it.startsWith("uniformMatrix4fv") })
        assertFalse(binding.log.any { it.startsWith("uniform1i") })
    }

    private fun createdPipeline(binding: RecordingGlBinding): StickerPipeline =
        (createStickerPipeline(binding, ShaderDialect.GLES, GlProgramCache()) as StickerPipelineResult.Created)
            .pipeline

    private fun resolvedSticker(texture: Int = 1, z: Double = 0.0): ResolvedSticker =
        ResolvedSticker(modelViewProjection = FloatArray(16), texture = texture, screenCompositeZ = z)

    private fun newBinding(): RecordingGlBinding = RecordingGlBinding().withDeclaredNames(
        STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME to MODEL_VIEW_PROJECTION_LOCATION,
        STICKER_TEXTURE_UNIFORM_NAME to TEXTURE_LOCATION,
    )

    private companion object {
        const val MODEL_VIEW_PROJECTION_LOCATION: Int = 3
        const val TEXTURE_LOCATION: Int = 7
    }
}
