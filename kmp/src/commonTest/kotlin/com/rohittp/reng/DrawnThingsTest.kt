package com.rohittp.reng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class DrawnThingsTest {
    @Test
    fun stickerAndModelOpacityDefaultsAndValidationAreExact() {
        val placement = screenPlacement()
        val sticker = Sticker(placement, ResourceLocator("sticker"))
        val model = Model(placement, ResourceLocator("model"))

        assertEquals(1f, sticker.opacity)
        assertEquals(BlendMode.OVER, sticker.blend)
        assertEquals(1f, model.opacity)
        assertNotEquals(sticker, sticker.copy(opacity = 0.5f, blend = BlendMode.SCREEN))
        assertNotEquals(model, Model(placement, ResourceLocator("model"), opacity = 0.5f))

        listOf(-0.01f, 1.01f, Float.NaN).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                Sticker(placement, ResourceLocator("sticker"), opacity = invalid)
            }
            assertFailsWith<IllegalArgumentException> {
                Model(placement, ResourceLocator("model"), opacity = invalid)
            }
        }
        Sticker(placement, ResourceLocator("sticker"), opacity = 0f)
        val negativeZeroFailure = assertFailsWith<IllegalArgumentException> {
            Sticker(placement, ResourceLocator("sticker"), opacity = -0f)
        }
        assertEquals("opacity must not be negative zero", negativeZeroFailure.message)
        assertEquals(
            0f.toBits(),
            Model(placement, ResourceLocator("model"), opacity = -0f).opacity.toBits(),
        )
    }

    @Test
    fun animationSelectorAndTimeValidationIsExact() {
        AnimationSelector.Index(0)
        AnimationSelector.Name("é")
        assertFailsWith<IllegalArgumentException> { AnimationSelector.Index(-1) }
        assertFailsWith<IllegalArgumentException> { AnimationSelector.Name(" ") }
        assertFailsWith<IllegalArgumentException> {
            AnimationTrack(AnimationSelector.Index(0), Double.NaN)
        }
    }

    @Test
    fun animationSelectorsPreserveExactUnicodeWithoutNormalization() {
        val composed = AnimationSelector.Name("é")
        val decomposed = AnimationSelector.Name("é")

        assertFalse(composed == decomposed)
        assertFailsWith<IllegalArgumentException> { AnimationSelector.Name("\uD800") }
    }

    @Test
    fun animationTrackCanonicalizesNegativeZeroAndUsesStructuralEquality() {
        val first = AnimationTrack(AnimationSelector.Name("walk"), -0.0)
        val equal = AnimationTrack(AnimationSelector.Name("walk"), 0.0)
        val different = AnimationTrack(AnimationSelector.Name("walk"), 1.0)

        assertEquals(0L, first.timeSeconds.toBits())
        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertFalse(first == different)
    }

    @Test
    fun animationTrackRequiresFiniteNonNegativeTime() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0001).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                AnimationTrack(AnimationSelector.Index(0), invalid)
            }
        }
    }

    @Test
    fun modelCopiesInputAndEveryListResultIncludingEmptyAndSingleton() {
        val track = AnimationTrack(AnimationSelector.Index(0), 1.0)
        assertModelListIsIndependent(emptyList())
        assertModelListIsIndependent(listOf(track))
        assertModelListIsIndependent(listOf(track, track))
    }

    @Test
    fun modelListSnapshotKeepsEqualityAndHashStableAfterMutation() {
        val track = AnimationTrack(AnimationSelector.Index(0), 1.0)
        val input = mutableListOf(track)
        val model = Model(screenPlacement(), ResourceLocator("model"), animationTracks = input)
        val equal = Model(screenPlacement(), ResourceLocator("model"), animationTracks = listOf(track))
        val hashCode = model.hashCode()

        input.clear()
        (model.animationTracks as MutableList<AnimationTrack>).clear()

        assertEquals(listOf(track), model.animationTracks)
        assertEquals(model, equal)
        assertEquals(hashCode, model.hashCode())
        assertEquals(listOf(track), model.animationTracksForCore())
    }

    @Test
    fun shaderPairValidatesSourcesButDoesNotScanProfiles() {
        ShaderPair("not a version directive", "also not a version directive")

        assertFailsWith<IllegalArgumentException> { ShaderPair("", "fragment") }
        assertFailsWith<IllegalArgumentException> { ShaderPair("vertex", "") }
        assertFailsWith<IllegalArgumentException> { ShaderPair("\uD800", "fragment") }
        assertFailsWith<IllegalArgumentException> { ShaderPair("vertex", "\uDFFF") }
    }

    @Test
    fun shaderPairToStringRedactsBothSources() {
        val vertexSource = "vertex-secret"
        val fragmentSource = "fragment-secret"
        val pair = ShaderPair(vertexSource, fragmentSource)

        assertFalse(pair.toString().contains(vertexSource))
        assertFalse(pair.toString().contains(fragmentSource))
    }

    @Test
    fun geometryRequiresStrictNorthWestBoundsAndAtMostOneWorldSpan() {
        val shaderPair = ShaderPair("vertex", "fragment")
        Geometry(Vector3(90.0, -180.0, 0.0), Vector3(-90.0, 180.0, 0.0), shaderPair)

        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(0.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(1.0, 180.0, 0.0), Vector3(0.0, -180.0, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 360.0001, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(90.0001, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(0.0, 0.0, 0.0), Vector3(-90.0001, 1.0, 0.0), shaderPair)
        }
    }

    /**
     * A `Geometry` carries no `Placement` — `CONTEXT.md` says so — so the mode has to reach it on a
     * field of its own, and one mode governs both corners.
     *
     * **The destructuring line is the source-compatibility gate**, not a style choice. [Geometry] is
     * a `data class`, so declaring the new field anywhere but last would renumber `component3`
     * through `component5` and silently re-bind every consumer that destructures one; this
     * statement stops compiling if that ever happens.
     *
     * **Nothing constrains the value.** `Placement` rejects a ground-relative altitude on a
     * screen-anchored position because a screen `z` is a compositing index; a `Geometry` is a
     * geographic rectangle and has no such alternative, so both modes construct on every geometry.
     */
    @Test
    fun aGeometryCarriesItsOwnAltitudeModeDefaultedToAbsolute() {
        val shaderPair = ShaderPair("vertex", "fragment")
        val absolute = Geometry(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), shaderPair)
        val groundRelative = absolute.copy(altitudeMode = AltitudeMode.GROUND_RELATIVE)

        assertEquals(AltitudeMode.ABSOLUTE, absolute.altitudeMode)
        assertEquals(AltitudeMode.GROUND_RELATIVE, groundRelative.altitudeMode)
        assertNotEquals(absolute, groundRelative)
        assertEquals(absolute, absolute.copy(altitudeMode = AltitudeMode.ABSOLUTE))

        val emissive = groundRelative.copy(emissive = true)
        assertEquals(false, absolute.emissive)
        assertNotEquals(groundRelative, emissive)

        val blended = emissive.copy(blend = BlendMode.MULTIPLY)
        assertEquals(BlendMode.OVER, emissive.blend)
        assertNotEquals(emissive, blended)

        val (topLeft, bottomRight, pair, uniforms, textures, altitudeMode, emits, blend) = blended
        assertEquals(Vector3(1.0, 0.0, 0.0), topLeft)
        assertEquals(Vector3(0.0, 1.0, 0.0), bottomRight)
        assertEquals(shaderPair, pair)
        assertEquals(emptyMap(), uniforms)
        assertEquals(emptyMap(), textures)
        assertEquals(AltitudeMode.GROUND_RELATIVE, altitudeMode)
        assertEquals(true, emits)
        assertEquals(BlendMode.MULTIPLY, blend)
    }

    /** ADR 0093: `transient` defaults false and is `component6`, so no existing component moved. */
    @Test
    fun aStickerIsNotTransientUnlessAskedAndTransientIsItsLastComponent() {
        val placement = Placement(
            AnchoringMode.SCREEN, Vector3(0.0, 0.0, 0.0), AnchoringMode.SCREEN, Vector3(0.0, 0.0, 0.0),
            AnchoringMode.SCREEN, 1.0,
        )
        val plain = Sticker(placement, ResourceLocator("frame.rrgb"))
        val transient = plain.copy(transient = true)
        assertEquals(false, plain.transient)
        assertNotEquals(plain, transient)

        val (_, image, opacity, blend, shader, isTransient) = transient
        assertEquals(ResourceLocator("frame.rrgb"), image)
        assertEquals(1f, opacity)
        assertEquals(BlendMode.OVER, blend)
        assertEquals(null, shader)
        assertEquals(true, isTransient)
    }

    @Test
    fun aConsumerMayNotClaimAnyNameUnderRengsGeometryPrefix() {
        val geometry = Geometry(
            Vector3(1.0, 0.0, 0.0),
            Vector3(0.0, 1.0, 0.0),
            ShaderPair("vertex", "fragment"),
        )
        assertFailsWith<IllegalArgumentException> {
            geometry.copy(uniforms = mapOf("rengGeometryEmissionPass" to ShaderValue.Integer(0)))
        }
        assertFailsWith<IllegalArgumentException> {
            geometry.copy(uniforms = mapOf("rengGeometryFuture" to ShaderValue.Scalar(1f)))
        }
        assertFailsWith<IllegalArgumentException> {
            geometry.copy(textures = mapOf("rengGeometryMask" to ResourceLocator("mask")))
        }
        geometry.copy(uniforms = mapOf("myRengGeometryTint" to ShaderValue.Scalar(1f)))
    }

    @Test
    fun geometryToStringRedactsShaderSourcesTransitively() {
        val vertexSource = "vertex-secret"
        val fragmentSource = "fragment-secret"
        val geometry = Geometry(
            Vector3(1.0, 2.0, 3.0),
            Vector3(0.0, 3.0, 4.0),
            ShaderPair(vertexSource, fragmentSource),
        )

        assertFalse(geometry.toString().contains(vertexSource))
        assertFalse(geometry.toString().contains(fragmentSource))
    }

    private fun assertModelListIsIndependent(tracks: List<AnimationTrack>) {
        val input = ArrayList(tracks)
        val model = Model(screenPlacement(), ResourceLocator("model"), animationTracks = input)

        input.clear()
        val first = model.animationTracks
        val returned = first as MutableList<AnimationTrack>
        returned.clear()
        val second = model.animationTracks

        assertEquals(tracks, second)
        assertNotSame(first, second)
        assertEquals(tracks, model.animationTracksForCore())
    }

    private fun screenPlacement(): Placement =
        Placement(
            AnchoringMode.SCREEN,
            Vector3(0.0, 0.0, 0.0),
            AnchoringMode.SCREEN,
            Vector3(0.0, 0.0, 0.0),
            AnchoringMode.SCREEN,
            1.0,
        )
}

class BloomTest {
    @Test
    fun defaultsAndValueSemanticsAreExactAndTheLevelListIsDefensive() {
        val bloom = Bloom()
        assertEquals(1f, bloom.intensity)
        assertEquals(Bloom.DEFAULT_LEVEL_WEIGHTS, bloom.levelWeights)
        assertEquals(0f, bloom.insideSuppression)
        assertEquals(0f, bloom.whiteness)
        assertEquals(bloom, Bloom())
        assertEquals(bloom.hashCode(), Bloom().hashCode())
        assertEquals(
            Bloom(intensity = 0f),
            Bloom(
                intensity = -0f,
                levelWeights = listOf(-0f, 0.9f, 0.8f, 0.6f, 0.45f, 0.35f, 0.3f),
            ),
        )

        (bloom.levelWeights as MutableList<Float>).clear()
        assertEquals(Bloom.DEFAULT_LEVEL_WEIGHTS, bloom.levelWeights)
    }

    @Test
    fun invalidBloomParametersAreRejected() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f).forEach { value ->
            assertFailsWith<IllegalArgumentException> { Bloom(intensity = value) }
        }
        assertFailsWith<IllegalArgumentException> { Bloom(levelWeights = emptyList()) }
        assertFailsWith<IllegalArgumentException> { Bloom(levelWeights = List(9) { 1f }) }
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f).forEach { value ->
            assertFailsWith<IllegalArgumentException> { Bloom(levelWeights = listOf(value)) }
        }
        listOf(Float.NaN, -0.1f, 1.1f).forEach { value ->
            assertFailsWith<IllegalArgumentException> { Bloom(insideSuppression = value) }
            assertFailsWith<IllegalArgumentException> { Bloom(whiteness = value) }
        }
        listOf(0, -1, 8).forEach { level ->
            assertFailsWith<IllegalArgumentException> { Bloom(reachLevel = level) }
        }
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f).forEach { value ->
            assertFailsWith<IllegalArgumentException> { Bloom(reachGain = value) }
        }
    }

    /** ADR 0085's fields: defaults, range, equality, and a reach level independent of levelWeights. */
    @Test
    fun bloomReachAndPrecisionFieldsDefaultValidateAndCompare() {
        val bloom = Bloom()
        assertEquals(false, bloom.highPrecisionEmission)
        assertEquals(Bloom.DEFAULT_REACH_LEVEL, bloom.reachLevel)
        assertEquals(3, bloom.reachLevel)
        assertEquals(1f, bloom.reachGain)
        assertEquals(bloom, Bloom(highPrecisionEmission = false, reachLevel = 3, reachGain = 1f))
        // A default reach level deeper than a short chain is legal: the far band then lands in the
        // deepest level the chain has, so existing short-chain blooms keep constructing.
        Bloom(levelWeights = listOf(1f, 1f), reachLevel = 3)
        Bloom(reachLevel = Bloom.MAXIMUM_REACH_LEVEL, reachGain = 0f)
        listOf(
            Bloom(highPrecisionEmission = true),
            Bloom(reachLevel = 4),
            Bloom(reachGain = 2f),
        ).forEach { different ->
            assertNotEquals(bloom, different)
            assertNotEquals(bloom.hashCode(), different.hashCode())
        }
        assertTrue(1f / Bloom(reachGain = -0f).reachGain > 0f, "a negative-zero gain is canonicalised to zero")
        assertTrue("reachGain=2.0" in Bloom(reachGain = 2f).toString())
    }

    @Test
    fun geometryBloomReachDefaultsToZeroAndIsBoundedFiniteAndNotNegativeZero() {
        val geometry = Geometry(
            Vector3(1.0, 2.0, 3.0),
            Vector3(0.0, 3.0, 4.0),
            ShaderPair("vertex", "fragment"),
        )
        assertEquals(0f, geometry.bloomReach)
        assertEquals(0.5f, geometry.copy(bloomReach = 0.5f).component9())
        assertNotEquals(geometry, geometry.copy(bloomReach = 0.5f))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f, -0f).forEach { value ->
            assertFailsWith<IllegalArgumentException> { geometry.copy(bloomReach = value) }
        }
        assertTrue("bloomReach=1.0" in geometry.copy(bloomReach = 1f).toString())
    }
}

/** ADR 0071's public surface: the two backdrop cases and what `Shader` refuses. */
class BackdropTest {

    /**
     * The `rengBackdrop` prefix is RenG's, and holding all of it -- not just the two names in use
     * today -- is what lets a later cycle add a uniform without turning a legal consumer document
     * into a constructor failure. ADR 0008 records that hazard; this is the guard against it.
     */
    @Test
    fun aConsumerMayNotClaimAnyNameUnderRengsBackdropPrefix() {
        assertFailsWith<IllegalArgumentException> {
            Backdrop.Shader(SHADER_PAIR, uniforms = mapOf("rengBackdropRepeat" to ShaderValue.Scalar(1f)))
        }
        // Not yet a name RenG uses, and refused anyway -- which is the whole point of a prefix.
        assertFailsWith<IllegalArgumentException> {
            Backdrop.Shader(SHADER_PAIR, uniforms = mapOf("rengBackdropHorizon" to ShaderValue.Scalar(1f)))
        }
        assertFailsWith<IllegalArgumentException> {
            Backdrop.Shader(SHADER_PAIR, textures = mapOf("rengBackdropTexture" to ResourceLocator("a")))
        }
    }

    /** The documented interface names stay refused here exactly as they are on a `Geometry`. */
    @Test
    fun aConsumerMayNotClaimADocumentedInterfaceName() {
        assertFailsWith<IllegalArgumentException> {
            Backdrop.Shader(SHADER_PAIR, uniforms = mapOf("uResolution" to ShaderValue.Scalar(1f)))
        }
        assertFailsWith<IllegalArgumentException> {
            Backdrop.Shader(SHADER_PAIR, textures = mapOf("uFrameIndex" to ResourceLocator("a")))
        }
    }

    /** A name that merely resembles the prefix is a consumer's to use. */
    @Test
    fun aNameThatOnlyResemblesThePrefixIsAccepted() {
        Backdrop.Shader(SHADER_PAIR, uniforms = mapOf("rengBackdro" to ShaderValue.Scalar(1f)))
        Backdrop.Shader(SHADER_PAIR, uniforms = mapOf("myRengBackdropTint" to ShaderValue.Scalar(1f)))
    }

    /**
     * Not a `data class`, because a generated `toString` would print the consumer's shader source
     * into every log that touches a frame plan.
     */
    @Test
    fun aShaderBackdropNeverPrintsItsSource() {
        val rendered = Backdrop.Shader(
            ShaderPair(vertexSource = "#version 300 es\nSECRET_VERTEX", fragmentSource = "#version 300 es\nSECRET_FRAGMENT"),
            uniforms = mapOf("uTint" to ShaderValue.Scalar(1f)),
            textures = mapOf("uMask" to ResourceLocator("secret/mask.png")),
        ).toString()

        assertFalse(rendered.contains("SECRET_VERTEX"), rendered)
        assertFalse(rendered.contains("SECRET_FRAGMENT"), rendered)
        assertFalse(rendered.contains("uTint"), rendered)
        assertFalse(rendered.contains("secret/mask.png"), rendered)
    }

    /** Equality is by value across all three fields, since it is hand-written rather than generated. */
    @Test
    fun twoShaderBackdropsAreEqualWhenEveryFieldIs() {
        val one = Backdrop.Shader(SHADER_PAIR, mapOf("uTint" to ShaderValue.Scalar(1f)))
        val same = Backdrop.Shader(SHADER_PAIR, mapOf("uTint" to ShaderValue.Scalar(1f)))
        val different = Backdrop.Shader(SHADER_PAIR, mapOf("uTint" to ShaderValue.Scalar(2f)))

        assertEquals(one, same)
        assertEquals(one.hashCode(), same.hashCode())
        assertNotEquals(one, different)
        assertNotEquals<Backdrop>(one, Backdrop.Pattern(ResourceLocator("p.png")))
    }

    /** The pattern case keeps every rule ADR 0068 gave it. */
    @Test
    fun theRepeatDistanceMustStillBePositive() {
        Backdrop.Pattern(ResourceLocator("p.png"), tileSizeLogicalPixels = 1.0)
        assertFailsWith<IllegalArgumentException> {
            Backdrop.Pattern(ResourceLocator("p.png"), tileSizeLogicalPixels = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            Backdrop.Pattern(ResourceLocator("p.png"), tileSizeLogicalPixels = -1.0)
        }
    }

    private companion object {
        val SHADER_PAIR: ShaderPair = ShaderPair(
            vertexSource = "#version 300 es\nvoid main() {}\n",
            fragmentSource = "#version 300 es\nvoid main() {}\n",
        )
    }
}

/** ADR 0087's public surface: what a `StickerShader` and its `GroundRectangle` accept and refuse. */
class StickerShaderTest {

    /** The whole `rengSticker` prefix is RenG's, including names it does not use yet. */
    @Test
    fun aConsumerMayNotClaimAnyNameUnderRengsStickerPrefix() {
        assertFailsWith<IllegalArgumentException> {
            StickerShader(SOURCE, uniforms = mapOf("rengStickerOpacity" to ShaderValue.Scalar(1f)))
        }
        assertFailsWith<IllegalArgumentException> {
            StickerShader(SOURCE, uniforms = mapOf("rengStickerSomethingLater" to ShaderValue.Scalar(1f)))
        }
        assertFailsWith<IllegalArgumentException> {
            StickerShader(SOURCE, textures = mapOf("rengStickerTexture" to ResourceLocator("a")))
        }
        assertFailsWith<IllegalArgumentException> {
            StickerShader(SOURCE, uniforms = mapOf("uResolution" to ShaderValue.Scalar(1f)))
        }
        assertFailsWith<IllegalArgumentException> {
            StickerShader(SOURCE, textures = mapOf("uFrameIndex" to ResourceLocator("a")))
        }
        StickerShader(SOURCE, uniforms = mapOf("rengStickeOpacity" to ShaderValue.Scalar(1f)))
        StickerShader(SOURCE, uniforms = mapOf("myRengStickerTint" to ShaderValue.Scalar(1f)))
    }

    /** The image holds unit zero, so a program gets one consumer texture fewer than a Geometry. */
    @Test
    fun aProgramMayDeclareFourteenTexturesAndNotFifteen() {
        fun textures(count: Int) = (0 until count).associate { "uMask$it" to ResourceLocator("mask$it.png") }
        StickerShader(SOURCE, textures = textures(14))
        assertFailsWith<IllegalArgumentException> { StickerShader(SOURCE, textures = textures(15)) }
    }

    @Test
    fun theSourceMustBeNonBlankUnicodeScalars() {
        assertFailsWith<IllegalArgumentException> { StickerShader("") }
        assertFailsWith<IllegalArgumentException> { StickerShader("  \n") }
        assertFailsWith<IllegalArgumentException> { StickerShader("#version 300 es\n\uD800") }
    }

    /** A Geometry's corner rules, exactly. */
    @Test
    fun aGroundRectangleRequiresStrictNorthWestBoundsAndAtMostOneWorldSpan() {
        GroundRectangle(Vector3(1.0, -1.0, 0.0), Vector3(-1.0, 1.0, 0.0))
        GroundRectangle(Vector3(90.0, -180.0, 0.0), Vector3(-90.0, 180.0, 0.0))
        assertFailsWith<IllegalArgumentException> {
            GroundRectangle(Vector3(-1.0, -1.0, 0.0), Vector3(1.0, 1.0, 0.0))
        }
        assertFailsWith<IllegalArgumentException> {
            GroundRectangle(Vector3(1.0, 1.0, 0.0), Vector3(-1.0, -1.0, 0.0))
        }
        assertFailsWith<IllegalArgumentException> {
            GroundRectangle(Vector3(1.0, 0.0, 0.0), Vector3(1.0, 1.0, 0.0))
        }
        assertFailsWith<IllegalArgumentException> {
            GroundRectangle(Vector3(1.0, -180.5, 0.0), Vector3(-1.0, 180.0, 0.0))
        }
        assertFailsWith<IllegalArgumentException> {
            GroundRectangle(Vector3(91.0, -1.0, 0.0), Vector3(-1.0, 1.0, 0.0))
        }
    }

    @Test
    fun aStickerShaderNeverPrintsItsSourceValuesOrTextures() {
        val rendered = Sticker(
            placement = PLACEMENT,
            image = ResourceLocator("sticker.png"),
            shader = StickerShader(
                "#version 300 es\nSECRET_FRAGMENT",
                uniforms = mapOf("uSecretTint" to ShaderValue.Scalar(1f)),
                textures = mapOf("uMask" to ResourceLocator("secret/mask.png")),
                ground = GROUND,
            ),
        ).toString()

        assertFalse(rendered.contains("SECRET_FRAGMENT"), rendered)
        assertFalse(rendered.contains("uSecretTint"), rendered)
        assertFalse(rendered.contains("secret/mask.png"), rendered)
        assertTrue(rendered.contains("StickerShader(fragmentSource=<redacted>"), rendered)
    }

    @Test
    fun equalityAndHashAreByValueAcrossEveryField() {
        val one = StickerShader(SOURCE, mapOf("uTint" to ShaderValue.Scalar(1f)), mapOf("uMask" to ResourceLocator("m")), GROUND)
        val same = StickerShader(SOURCE, mapOf("uTint" to ShaderValue.Scalar(1f)), mapOf("uMask" to ResourceLocator("m")), GROUND)
        assertEquals(one, same)
        assertEquals(one.hashCode(), same.hashCode())
        assertNotEquals(one, StickerShader("$SOURCE ", one.uniforms, one.textures, GROUND))
        assertNotEquals(one, StickerShader(SOURCE, mapOf("uTint" to ShaderValue.Scalar(2f)), one.textures, GROUND))
        assertNotEquals(one, StickerShader(SOURCE, one.uniforms, mapOf("uMask" to ResourceLocator("n")), GROUND))
        assertNotEquals(one, StickerShader(SOURCE, one.uniforms, one.textures, null))
    }

    /** Appended last: every earlier component keeps its number, and the default is no program. */
    @Test
    fun theShaderIsTheStickersFifthComponentAndDefaultsToNone() {
        val plain = Sticker(PLACEMENT, ResourceLocator("sticker.png"), 0.5f, BlendMode.SCREEN)
        assertEquals(null, plain.shader)
        val shaded = plain.copy(shader = StickerShader(SOURCE))
        val (placement, image, opacity, blend, shader) = shaded
        assertEquals(PLACEMENT, placement)
        assertEquals(ResourceLocator("sticker.png"), image)
        assertEquals(0.5f, opacity)
        assertEquals(BlendMode.SCREEN, blend)
        assertEquals(StickerShader(SOURCE), shader)
        assertNotEquals(plain, shaded)
    }

    private companion object {
        const val SOURCE: String = "#version 300 es\nout vec4 c;\nvoid main() { c = rengStickerColour(); }\n"
        val GROUND: GroundRectangle = GroundRectangle(Vector3(1.0, -1.0, 0.0), Vector3(-1.0, 1.0, 0.0))
        val PLACEMENT: Placement = Placement(
            positionMode = AnchoringMode.SCREEN,
            position = Vector3(1.0, 2.0, 0.0),
            rotationMode = AnchoringMode.SCREEN,
            rotation = Vector3(0.0, 0.0, 0.0),
            scaleMode = AnchoringMode.SCREEN,
            scale = 1.0,
        )
    }
}
