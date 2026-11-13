package com.rohittp.reng.internal.gl

import com.rohittp.reng.BlendMode
import com.rohittp.reng.ExternalTextureTarget
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        assertEquals(OPACITY_LOCATION, pipeline.pipeline.opacityUniformLocation)
        assertEquals(TEXTURE_TRANSFORM_LOCATION, pipeline.pipeline.textureTransformUniformLocation)
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

    /**
     * This pass owns the map regime and nothing else. It draws depth-tested, and — the half that used
     * to be here — it neither disables depth testing nor composites a screen stack afterwards, because
     * `SceneContent.drawScreenStack` owns that now. A `disable(GL_DEPTH_TEST)` left behind here would
     * turn the test off part-way through a map regime whose stickers need not be its last content.
     */
    @Test fun thisPassDrawsTheMapRegimeDepthTestedAndTurnsNothingOffAfterwards() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        val mapTexture = 11
        binding.log.clear()
        drawStickers(binding, pipeline, StickerWorld(mapAnchored = listOf(resolvedSticker(texture = mapTexture))))

        val depthEnabled = binding.log.indexOfFirst { it == "enable(0xB71)" } // GL_DEPTH_TEST
        val mapBind = binding.log.indexOfFirst { it == "bindTexture(0xDE1,$mapTexture)" } // GL_TEXTURE_2D
        val mapDraw = binding.log.indexOfFirst { it.startsWith("drawArrays") }

        assertTrue(depthEnabled in 0 until mapBind, "the map regime must be depth-tested")
        assertTrue(mapBind in 0 until mapDraw, "the map-anchored sticker's texture must bind before it draws")
        assertFalse(
            binding.log.any { it == "disable(0xB71)" },
            "turning depth testing off is the screen stack's job, not this pass's: ${binding.log}",
        )
    }

    /**
     * The second copy this task deleted. `MercatorSpatialPlanner` sorts the screen stack once, and
     * [drawStickers] used to sort its own screen half a second time by a `screenCompositeZ` threaded
     * down from a draw-time re-resolution. Nothing here may reorder what it is handed, and the three
     * textures below are in no sorted order under any key a re-derived sort could reach for.
     */
    @Test fun drawStickersNeitherSortsNorSplitsWhatItIsHanded() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        val first = 91
        val second = 12
        val third = 53
        binding.log.clear()
        drawStickers(
            binding,
            pipeline,
            StickerWorld(
                mapAnchored = listOf(
                    resolvedSticker(texture = first),
                    resolvedSticker(texture = second),
                    resolvedSticker(texture = third),
                ),
            ),
        )

        assertEquals(
            listOf(first, second, third),
            boundTexturesInDrawOrder(binding),
            "the map half draws in exactly the order it is given",
        )
    }

    /**
     * ADR 0027. The map regime is depth-tested and writes nothing, so a map-anchored billboard —
     * a screen-parallel quad carrying its anchor's single depth — cannot be sliced in half by the
     * varying depth of the map plane it stands on, and cannot slice a later one either.
     */
    @Test fun theMapRegimeTurnsDepthWritesOffBeforeItDraws() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawStickers(binding, pipeline, StickerWorld(mapAnchored = listOf(resolvedSticker(texture = 11))))

        val maskedOff = binding.log.indexOfFirst { it == "depthMask(false)" }
        val firstDraw = binding.log.indexOfFirst { it.startsWith("drawArrays") }
        assertTrue(firstDraw >= 0, "the map-anchored sticker must actually draw")
        assertTrue(
            maskedOff in 0 until firstDraw,
            "the map regime must turn depth writes off before it draws (ADR 0027)",
        )
        assertFalse(binding.log.any { it == "depthMask(true)" }, "nothing here re-enables depth writes")
    }

    // `equalZIndexCompositesInStablePlanOrder` and `greaterZIndexComposesOnTopOfLesserZIndex` used to
    // sit here, asserting the z-sort this function ran over its own screen half. Both the sort and the
    // half are gone: `MercatorSpatialPlanner`'s `screenCompositingOrder` is the one authority for the
    // screen stack's order, and `MercatorSpatialPlannerTest.screenEntriesSortByZThenSourceIndex` is
    // where those two properties are pinned now. `SceneContentTest` pins that the GL layer consumes
    // that order rather than deriving one of its own.

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

    @Test fun opacityUploadsPerStickerAndBlendChangesOnlyWhenTheModeChangesThenRestoresOver() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        binding.log.clear()
        drawStickers(
            binding,
            pipeline,
            StickerWorld(
                mapAnchored = listOf(
                    resolvedSticker(texture = 1, opacity = 0.25f),
                    resolvedSticker(texture = 2, opacity = 0.5f, blend = BlendMode.ADDITIVE),
                    resolvedSticker(texture = 3, opacity = 0.75f, blend = BlendMode.ADDITIVE),
                    resolvedSticker(texture = 4, opacity = 1f, blend = BlendMode.SCREEN),
                ),
            ),
        )

        assertEquals(
            listOf(0.25f, 0.5f, 0.75f, 1f),
            binding.log.filter { it.startsWith("uniform1f($OPACITY_LOCATION,") }
                .map { it.substringAfter(',').removeSuffix(")").toFloat() },
        )
        assertEquals(
            listOf(
                "blendFuncSeparate(0x1,0x303,0x1,0x303)",
                "blendFuncSeparate(0x1,0x1,0x1,0x303)",
                "blendFuncSeparate(0x1,0x301,0x1,0x303)",
                "blendFuncSeparate(0x1,0x303,0x1,0x303)",
            ),
            binding.log.filter { it.startsWith("blendFuncSeparate") },
        )
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
    @Test fun drawingBindsEveryDeclaredUniformAtItsResolvedLocation() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        assertEquals(MODEL_VIEW_PROJECTION_LOCATION, pipeline.modelViewProjectionUniformLocation)
        assertEquals(TEXTURE_LOCATION, pipeline.textureUniformLocation)
        assertEquals(OPACITY_LOCATION, pipeline.opacityUniformLocation)
        assertEquals(TEXTURE_TRANSFORM_LOCATION, pipeline.textureTransformUniformLocation)

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
        assertTrue(binding.log.any { it == "uniform1f($OPACITY_LOCATION,1.0)" })
        assertContentEquals(IDENTITY_TEXTURE_TRANSFORM, binding.uniformMatrix4fvValues[TEXTURE_TRANSFORM_LOCATION])
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

    @Test fun aTransformed2dExternalTextureUsesThePlainProgramAndUploadsItsMatrix() {
        val binding = newBinding()
        val pipeline = createdPipeline(binding)
        val transform = FloatArray(16) { index -> index + 0.25f }
        binding.log.clear()

        drawStickers(
            binding,
            pipeline,
            StickerWorld(
                mapAnchored = listOf(
                    resolvedSticker(texture = 1).withExternal(
                        ResolvedExternalSticker(ExternalTextureTarget.TEXTURE_2D, 77, transform),
                    ),
                ),
            ),
        )

        assertTrue(binding.log.any { it == "bindTexture(0xDE1,77)" })
        assertContentEquals(transform, binding.uniformMatrix4fvValues[TEXTURE_TRANSFORM_LOCATION])
    }

    @Test fun theOesVariantIsLazyAndItsExtensionPrecedesDeclarations() {
        val binding = newBinding().apply { strings[GL_EXTENSIONS] = EXTERNAL_OES_EXTENSION_NAME }
        val cache = GlProgramCache()
        val pipeline = (createStickerPipeline(binding, ShaderDialect.GLES, cache) as StickerPipelineResult.Created)
            .pipeline
        val shadersBefore = binding.shaderSources.size

        val first = assertNotNull(ensureExternalOesStickerProgram(binding, ShaderDialect.GLES, cache, pipeline))
        val second = assertNotNull(ensureExternalOesStickerProgram(binding, ShaderDialect.GLES, cache, pipeline))

        assertEquals(first, second)
        assertEquals(shadersBefore + 2, binding.shaderSources.size, "the variant must compile exactly once")
        val source = binding.shaderSources.values.single { EXTERNAL_OES_EXTENSION_NAME in it }
        assertTrue(source.startsWith("#version 300 es\n#extension $EXTERNAL_OES_EXTENSION_NAME : require\nprecision"))
    }

    @Test fun aMissingOesExtensionPermanentlyUsesTheFallback() {
        val binding = newBinding().apply { strings[GL_EXTENSIONS] = "GL_OES_texture_float" }
        val cache = GlProgramCache()
        val pipeline = (createStickerPipeline(binding, ShaderDialect.GLES, cache) as StickerPipelineResult.Created)
            .pipeline
        val shadersBefore = binding.shaderSources.size

        assertNull(ensureExternalOesStickerProgram(binding, ShaderDialect.GLES, cache, pipeline))
        assertNull(ensureExternalOesStickerProgram(binding, ShaderDialect.GLES, cache, pipeline))

        assertTrue(pipeline.externalOesUnsupported)
        assertEquals(shadersBefore, binding.shaderSources.size)
    }

    @Test fun anOesDrawBindsAndUnbindsTheExternalTargetThenRestoresThePlainProgram() {
        val binding = newBinding().apply { strings[GL_EXTENSIONS] = EXTERNAL_OES_EXTENSION_NAME }
        val cache = GlProgramCache()
        val pipeline = (createStickerPipeline(binding, ShaderDialect.GLES, cache) as StickerPipelineResult.Created)
            .pipeline
        val oes = assertNotNull(ensureExternalOesStickerProgram(binding, ShaderDialect.GLES, cache, pipeline))
        binding.log.clear()

        drawStickers(
            binding,
            pipeline,
            StickerWorld(
                mapAnchored = listOf(
                    resolvedSticker().withExternal(
                        ResolvedExternalSticker(
                            ExternalTextureTarget.EXTERNAL_OES,
                            81,
                            IDENTITY_TEXTURE_TRANSFORM,
                            oes,
                        ),
                    ),
                ),
            ),
        )

        val bind = binding.log.indexOf("bindTexture(0x8D65,81)")
        val draw = binding.log.indexOfFirst { it.startsWith("drawArrays") }
        val unbind = binding.log.indexOf("bindTexture(0x8D65,0)")
        assertTrue(bind in 0 until draw)
        assertTrue(unbind > draw)
        assertTrue(binding.log.drop(unbind).contains("useProgram(${pipeline.program})"))
    }

    // ---------------------------------------------------------------------------------------------
    // ADR 0087: a consumer fragment program
    // ---------------------------------------------------------------------------------------------

    @Test fun theConsumerStickerRoleIsAppendedAtSixteen() {
        assertEquals(16, InternalPipelineRole.CONSUMER_STICKER.wireValue)
    }

    /**
     * The preamble lands on the line after the directive, the directive keeps its own terminator, and
     * the result is still a Shader Profile whose directive is the consumer's -- so desktop substitution
     * replaces exactly that line and nothing the consumer wrote moves.
     */
    @Test fun thePreambleIsInsertedAfterTheDirectiveAndTheResultIsStillAShaderProfile() {
        val source = "// a comment\r\n#version 300 es\r\nout vec4 c;\nvoid main() { c = rengStickerColour(); }\n"
        val composed = assertNotNull(composeStickerFragmentSource(source))

        assertEquals(
            "// a comment\r\n#version 300 es\n" + STICKER_SHADER_PREAMBLE.removeSuffix("\n") +
                "\r\nout vec4 c;\nvoid main() { c = rengStickerColour(); }\n",
            composed,
        )
        val plan = assertNotNull(scanShaderProfile(composed))
        assertEquals(source.indexOf("#version"), plan.directiveStartUtf16)
        assertTrue(plan.desktop330Source().startsWith("// a comment\r\n#version 330 core\nprecision highp float;"))
        assertNull(composeStickerFragmentSource("void main() {}\n"))
        assertNull(composeStickerFragmentSource("#version 310 es\nvoid main() {}\n"))
    }

    /** Every name the preamble declares is under the reserved prefix, so no consumer name can collide. */
    @Test fun everyPreambleNameIsUnderTheReservedPrefix() {
        val declared = Regex("""\b(reng[A-Za-z]*)""").findAll(STICKER_SHADER_PREAMBLE).map { it.value }.toSet()
        assertTrue(declared.isNotEmpty())
        assertTrue(declared.all { it.startsWith(STICKER_RESERVED_NAME_PREFIX) }, declared.toString())
        // The colour expression is the plain program's, character for character.
        assertTrue(
            STICKER_FRAGMENT_SOURCE.contains("texture(rengStickerTexture, rengStickerUv) * rengStickerOpacity;"),
        )
        assertTrue(STICKER_SHADER_PREAMBLE.contains("return texture(rengStickerTexture, rengStickerUv) * rengStickerOpacity;"))
        assertEquals(MAXIMUM_CONSUMER_TEXTURES - 1, MAXIMUM_STICKER_SHADER_TEXTURES)
    }

    /** A program keys by its source under its own role, so it can never name RenG's own sticker program. */
    @Test fun aConsumerProgramIsKeyedByItsSourceAndNeverAsRenGsOwn() {
        val deriver = ResourceKeyDeriver()
        val first = deriver.internalPipeline(InternalPipelineRole.CONSUMER_STICKER, consumerStickerShaderPair(CONSUMER_SOURCE)).key
        val again = deriver.internalPipeline(InternalPipelineRole.CONSUMER_STICKER, consumerStickerShaderPair(CONSUMER_SOURCE)).key
        val other = deriver.internalPipeline(
            InternalPipelineRole.CONSUMER_STICKER,
            consumerStickerShaderPair(CONSUMER_SOURCE + "\n"),
        ).key
        val identicalToRenGs = deriver.internalPipeline(
            InternalPipelineRole.CONSUMER_STICKER,
            consumerStickerShaderPair(STICKER_FRAGMENT_SOURCE),
        ).key
        val rengsOwn = deriver.internalPipeline(InternalPipelineRole.STICKER, STICKER_SHADER_PAIR).key

        assertEquals(first, again)
        assertTrue(first != other)
        assertTrue(identicalToRenGs != rengsOwn)
    }

    /** The composed source is what compiles, and a source that is not a Shader Profile is the consumer's fault. */
    @Test fun creationCompilesTheComposedSourceAndBlamesTheConsumerForANonProfileSource() {
        val binding = newConsumerBinding()
        val created = createConsumerStickerPipeline(binding, ShaderDialect.GLES, GlProgramCache(), CONSUMER_SOURCE)
        val pipeline = (created as ConsumerStickerPipelineResult.Created).pipeline
        assertTrue(binding.shaderSources.values.any { it == composeStickerFragmentSource(CONSUMER_SOURCE) })
        assertTrue(binding.shaderSources.values.any { it == STICKER_VERTEX_SOURCE })
        assertEquals(WINDOW_TO_GROUND_LOCATION, pipeline.windowToGroundUniformLocation)

        val refused = createConsumerStickerPipeline(
            RecordingGlBinding().withNoDeclaredNames(), ShaderDialect.GLES, GlProgramCache(), "void main() {}\n",
        )
        val failure = (refused as ConsumerStickerPipelineResult.Failed).failure
        assertEquals(RenGErrorCode.SHADER_COMPILE_FAILED, failure.code)
        assertEquals(PipelineStage.SHADER_COMPILATION, failure.stage)
    }

    /**
     * A consumer sticker draws through its own program with its values bound and its textures from unit
     * one, then hands back exactly the state `beginStickerPass` set: the plain program bound and unit
     * zero active. Its blend is the loop's, so it never touches the blend function itself.
     */
    @Test fun aConsumerStickerBindsItsProgramAndValuesAndLeavesThePlainPassState() {
        val binding = newConsumerBinding()
        val plain = createdPipeline(binding)
        val consumer = (
            createConsumerStickerPipeline(binding, ShaderDialect.GLES, GlProgramCache(), CONSUMER_SOURCE)
                as ConsumerStickerPipelineResult.Created
            ).pipeline
        val ground = StickerGroundMapping(FloatArray(16) { it.toFloat() }, 12f, 34f, present = true)
        binding.log.clear()

        drawStickers(
            binding,
            plain,
            StickerWorld(
                mapAnchored = listOf(
                    resolvedSticker(texture = 41, opacity = 0.5f).withShader(
                        ResolvedStickerShader(
                            pipeline = consumer,
                            uniforms = mapOf("uTint" to ShaderValue.Scalar(0.25f)),
                            textures = mapOf("uMaskB" to 52, "uMaskA" to 51),
                            ground = ground,
                        ),
                    ),
                    resolvedSticker(texture = 42),
                ),
            ),
        )

        // A consumer name's location is asked of the driver once and memoised; not part of the draw.
        val consumerDraw = binding.log.subList(
            binding.log.indexOf("useProgram(${consumer.program})"),
            binding.log.indexOfFirst { it.startsWith("drawArrays") } + 1,
        ).filterNot { it.startsWith("getUniformLocation") }
        assertEquals(
            listOf(
                "useProgram(${consumer.program})",
                "uniformMatrix4fv($MODEL_VIEW_PROJECTION_LOCATION,1,false)",
                "uniform1f($OPACITY_LOCATION,0.5)",
                "uniformMatrix4fv($TEXTURE_TRANSFORM_LOCATION,1,false)",
                "uniformMatrix4fv($WINDOW_TO_GROUND_LOCATION,1,false)",
                "uniform2f($GROUND_ORIGIN_LOCATION,12.0,34.0)",
                "uniform1f($GROUND_PRESENT_LOCATION,1.0)",
                "uniform1f($TINT_LOCATION,0.25)",
                "activeTexture(0x84C1)",
                "bindTexture(0xDE1,51)",
                "bindSampler(1,0)",
                "uniform1i($MASK_A_LOCATION,1)",
                "activeTexture(0x84C2)",
                "bindTexture(0xDE1,52)",
                "bindSampler(2,0)",
                "uniform1i($MASK_B_LOCATION,2)",
                "activeTexture(0x84C0)",
                "uniform1i($TEXTURE_LOCATION,0)",
                "bindTexture(0xDE1,41)",
                "drawArrays(0x5,0,4)",
            ),
            consumerDraw,
        )
        assertContentEquals(ground.windowToGround, binding.uniformMatrix4fvValues[WINDOW_TO_GROUND_LOCATION])
        val afterConsumer = binding.log.drop(binding.log.indexOfFirst { it.startsWith("drawArrays") } + 1)
        assertEquals("useProgram(${plain.program})", afterConsumer.first())
        // The plain sticker after it binds its image on unit zero, which the consumer left active.
        assertTrue(afterConsumer.contains("bindTexture(0xDE1,42)"))
        assertFalse(afterConsumer.takeWhile { it != "bindTexture(0xDE1,42)" }.any { it.startsWith("activeTexture") })
        // The pass's own two -- set on entry, restored to OVER on exit -- and none from the consumer draw.
        assertEquals(2, binding.log.count { it.startsWith("blendFuncSeparate") })
    }

    @Test fun aConsumerStickerIgnoresItsExternalTextureAndDrawsItsImage() {
        val binding = newConsumerBinding()
        val plain = createdPipeline(binding)
        val consumer = (
            createConsumerStickerPipeline(binding, ShaderDialect.GLES, GlProgramCache(), CONSUMER_SOURCE)
                as ConsumerStickerPipelineResult.Created
            ).pipeline
        val sticker = resolvedSticker(texture = 41)
            .withExternal(
                ResolvedExternalSticker(
                    ExternalTextureTarget.TEXTURE_2D,
                    77,
                    IDENTITY_TEXTURE_TRANSFORM,
                ),
            )
            .withShader(ResolvedStickerShader(consumer, emptyMap(), emptyMap(), StickerGroundMapping.NONE))
        binding.log.clear()

        drawStickers(binding, plain, StickerWorld(mapAnchored = listOf(sticker)))

        assertTrue(binding.log.contains("bindTexture(0xDE1,41)"))
        assertFalse(binding.log.contains("bindTexture(0xDE1,77)"))
    }

    private fun ResolvedSticker.withShader(shader: ResolvedStickerShader): ResolvedSticker = ResolvedSticker(
        modelViewProjection = modelViewProjection,
        texture = texture,
        opacity = opacity,
        blend = blend,
        shader = shader,
        external = external,
    )

    private fun ResolvedSticker.withExternal(external: ResolvedExternalSticker): ResolvedSticker = ResolvedSticker(
        modelViewProjection = modelViewProjection,
        texture = texture,
        opacity = opacity,
        blend = blend,
        shader = shader,
        external = external,
    )

    private fun newConsumerBinding(): RecordingGlBinding = RecordingGlBinding().withDeclaredNames(
        STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME to MODEL_VIEW_PROJECTION_LOCATION,
        STICKER_TEXTURE_UNIFORM_NAME to TEXTURE_LOCATION,
        STICKER_OPACITY_UNIFORM_NAME to OPACITY_LOCATION,
        STICKER_TEXTURE_TRANSFORM_UNIFORM_NAME to TEXTURE_TRANSFORM_LOCATION,
        STICKER_WINDOW_TO_GROUND_UNIFORM_NAME to WINDOW_TO_GROUND_LOCATION,
        STICKER_GROUND_ORIGIN_UNIFORM_NAME to GROUND_ORIGIN_LOCATION,
        STICKER_GROUND_PRESENT_UNIFORM_NAME to GROUND_PRESENT_LOCATION,
        "uTint" to TINT_LOCATION,
        "uMaskA" to MASK_A_LOCATION,
        "uMaskB" to MASK_B_LOCATION,
    )

    private fun createdPipeline(binding: RecordingGlBinding): StickerPipeline =
        (createStickerPipeline(binding, ShaderDialect.GLES, GlProgramCache()) as StickerPipelineResult.Created)
            .pipeline

    private fun resolvedSticker(
        texture: Int = 1,
        opacity: Float = 1f,
        blend: BlendMode = BlendMode.OVER,
    ): ResolvedSticker = ResolvedSticker(
        modelViewProjection = FloatArray(16),
        texture = texture,
        opacity = opacity,
        blend = blend,
    )

    private fun newBinding(): RecordingGlBinding = RecordingGlBinding().withDeclaredNames(
        STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME to MODEL_VIEW_PROJECTION_LOCATION,
        STICKER_TEXTURE_UNIFORM_NAME to TEXTURE_LOCATION,
        STICKER_OPACITY_UNIFORM_NAME to OPACITY_LOCATION,
        STICKER_TEXTURE_TRANSFORM_UNIFORM_NAME to TEXTURE_TRANSFORM_LOCATION,
    )

    private companion object {
        const val MODEL_VIEW_PROJECTION_LOCATION: Int = 3
        const val TEXTURE_LOCATION: Int = 7
        const val OPACITY_LOCATION: Int = 9
        const val TEXTURE_TRANSFORM_LOCATION: Int = 10
        const val WINDOW_TO_GROUND_LOCATION: Int = 11
        const val TINT_LOCATION: Int = 12
        const val MASK_A_LOCATION: Int = 13
        const val MASK_B_LOCATION: Int = 14
        const val GROUND_ORIGIN_LOCATION: Int = 15
        const val GROUND_PRESENT_LOCATION: Int = 16
        const val CONSUMER_SOURCE: String =
            "#version 300 es\nuniform float uTint;\nout vec4 c;\nvoid main() { c = rengStickerColour() * uTint; }\n"
    }
}
