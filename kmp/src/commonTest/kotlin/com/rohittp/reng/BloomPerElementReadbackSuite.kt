package com.rohittp.reng

import com.rohittp.reng.internal.gl.BloomFarBandStrategy
import com.rohittp.reng.internal.gl.GL_BLEND
import com.rohittp.reng.internal.gl.GL_COLOR_ATTACHMENT0
import com.rohittp.reng.internal.gl.GL_COLOR_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_DRAW_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_FRAMEBUFFER_COMPLETE
import com.rohittp.reng.internal.gl.GL_PACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_READ_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_RGBA
import com.rohittp.reng.internal.gl.GL_RGBA8
import com.rohittp.reng.internal.gl.GL_SCISSOR_TEST
import com.rohittp.reng.internal.gl.GL_TEXTURE_2D
import com.rohittp.reng.internal.gl.GL_UNSIGNED_BYTE
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.RenderContextAdoption
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.gl.ShaderDialect
import com.rohittp.reng.internal.gl.adoptRenderContext
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The per-element bloom suite's square output: wide enough for two halos and a far band apart. */
internal const val BLOOM_PER_ELEMENT_PIXELS: Int = 256

/**
 * ADR 0085 on a real rasteriser: the far band (`Geometry.bloomReach` through `Bloom.reachLevel` and
 * `Bloom.reachGain`) and the half-float emission chain (`Bloom.highPrecisionEmission`).
 *
 * Every claim prints what it measured before it asserts, so a failure reads as a measurement. The
 * scene is a dark grey ground with white or emission-aware grey emitters: below the composite's
 * knee and with neutral light, so the soft shoulder and the hue steer leave the bloom a plain add
 * and halo light can be compared by subtraction.
 */
internal fun runBloomPerElementReadbackSuite(
    binding: GlBinding,
    probe: RenderContextProbe,
    dialect: ShaderDialect,
) {
    val supportsHalfFloat = when (val adoption = adoptRenderContext(binding)) {
        is RenderContextAdoption.Adopted -> adoption.profile.supportsHalfFloatColourBuffer
        is RenderContextAdoption.Rejected -> error("the per-element bloom suite needs an adoptable context")
    }
    val target = createPerElementTarget(binding)
    val diagnostics = ArrayList<Diagnostic>()
    val renderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(BLOOM_PER_ELEMENT_PIXELS, BLOOM_PER_ELEMENT_PIXELS),
            transport = PerElementTransport(),
            store = RecordingStyleStore(),
            basemapStyle = null,
            diagnosticSink = DiagnosticSink { diagnostics += it },
        ),
        binding,
        probe,
    )
    val rengRenderer = renderer as RenGRenderer
    val failures = PerElementFailures()
    var frameIndex = 0L
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.framebuffer.toUInt()))
        fun draw(bloom: Bloom?, emitters: List<Geometry>): ByteArray {
            diagnostics.clear()
            return drawAndRead(
                binding, renderer, renderTarget, target.framebuffer,
                FramePlan(
                    frameIndex = frameIndex++,
                    camera = Camera(0.0, 0.0, PER_ELEMENT_ZOOM, 0.0, 0.0),
                    drawBasemap = false,
                    geometries = listOf(ground()) + emitters,
                    bloom = bloom,
                ),
            )
        }

        // (a) Defaults and inert values draw exactly ADR 0083's frame.
        failures.run("a: reach fields without a reaching emitter, and a zero gain, change no byte") {
            val stroke = stroke(STROKE_A_LEFT, STROKE_WIDTH)
            val plain = draw(Bloom(), listOf(stroke))
            val explicitDefaults = draw(
                Bloom(highPrecisionEmission = false, reachLevel = 3, reachGain = 1f),
                listOf(stroke.copy(bloomReach = 0f)),
            )
            val otherReachFields = draw(Bloom(reachLevel = 5, reachGain = 2f), listOf(stroke))
            val zeroGain = draw(Bloom(reachGain = 0f), listOf(stroke.copy(bloomReach = 1f)))
            val reachOnAnOrdinaryGeometry = draw(Bloom(), listOf(stroke, stroke(FAR_LEFT, 4).copy(emissive = false, bloomReach = 1f)))
            val ordinaryWithoutReach = draw(Bloom(), listOf(stroke, stroke(FAR_LEFT, 4).copy(emissive = false)))
            println(
                "RenG per-element bloom case a: dialect=$dialect defaultSha256=${plain.sha256()} " +
                    "explicitDefaults=${differingBytes(plain, explicitDefaults)} " +
                    "otherReachFields=${differingBytes(plain, otherReachFields)} " +
                    "zeroGain=${differingBytes(plain, zeroGain)} " +
                    "reachOnOrdinary=${differingBytes(ordinaryWithoutReach, reachOnAnOrdinaryGeometry)}",
            )
            assertContentEquals(plain, explicitDefaults, "explicit default values must draw the default frame")
            assertContentEquals(plain, otherReachFields, "reachLevel and reachGain alone must draw nothing")
            assertContentEquals(plain, zeroGain, "a zero reachGain must draw no far band")
            assertContentEquals(
                ordinaryWithoutReach, reachOnAnOrdinaryGeometry,
                "bloomReach on a geometry that is not emissive must draw nothing",
            )
        }

        // (b) Reach widens one emitter's halo, measured along a horizontal line through its middle.
        // The emitter draws mid-grey and emits white, so its own pixels are not saturated: with
        // insideSuppression = 1 they must not change at all, and with 0 the case prints how much the
        // far band adds on the stroke itself.
        failures.run("b: a reaching emitter adds relatively more light far out, and leaves its stroke unchanged") {
            val emitter = valueEmitter(1f)
            val noBloom = draw(null, listOf(emitter))
            val bloom = Bloom(intensity = HALO_INTENSITY, insideSuppression = 1f)
            val reach0 = draw(bloom, listOf(emitter))
            val reach1 = draw(bloom, listOf(emitter.copy(bloomReach = 1f)))
            val unsuppressed = Bloom(intensity = HALO_INTENSITY)
            val unsuppressed0 = draw(unsuppressed, listOf(emitter))
            val unsuppressed1 = draw(unsuppressed, listOf(emitter.copy(bloomReach = 1f)))
            val strokeRight = STROKE_A_LEFT + STROKE_WIDTH
            val centreX = STROKE_A_LEFT + STROKE_WIDTH / 2
            fun added(frame: ByteArray, x: Int): Int = frame.pixel(x, MID_Y)[0] - noBloom.pixel(x, MID_Y)[0]
            val profile = listOf(0, 2, 8, 16, 24, 32, 40, 48, 64).joinToString(" ") { d ->
                val x = strokeRight + d
                "d$d=${added(reach0, x)}/${added(reach1, x)}"
            }
            val nearX = strokeRight + NEAR_DISTANCE
            val farX = strokeRight + FAR_DISTANCE
            val near0 = added(reach0, nearX)
            val near1 = added(reach1, nearX)
            val far0 = added(reach0, farX)
            val far1 = added(reach1, farX)
            val centre0 = reach0.pixel(centreX, MID_Y)
            val centre1 = reach1.pixel(centreX, MID_Y)
            val unsuppressedCentreDelta =
                unsuppressed1.pixel(centreX, MID_Y)[0] - unsuppressed0.pixel(centreX, MID_Y)[0]
            println(
                "RenG per-element bloom case b: dialect=$dialect added light reach0/reach1 by distance " +
                    "past the stroke: $profile strokeCentre=${centre0.describe()}/${centre1.describe()} " +
                    "strokeCentreAddedByReachWithoutSuppression=$unsuppressedCentreDelta",
            )
            assertTrue(far1 > far0, "reach 1 must add light $FAR_DISTANCE px out ($far0 -> $far1)")
            assertTrue(
                far1 * near0 > near1 * far0,
                "reach must widen the halo: its relative gain $FAR_DISTANCE px out ($far0 -> $far1) must " +
                    "exceed its relative gain $NEAR_DISTANCE px out ($near0 -> $near1)",
            )
            assertTrue(
                maxChannelDelta(centre0, centre1) <= STROKE_TOLERANCE,
                "with insideSuppression = 1 the stroke itself must stay within $STROKE_TOLERANCE of reach 0's " +
                    "(${centre0.describe()} -> ${centre1.describe()})",
            )
        }

        // (c) Reach is per element: B's own halo is the same whether or not A reaches.
        failures.run("c: reach on one emitter leaves a distant emitter's halo its own") {
            val a = stroke(STROKE_A_LEFT, STROKE_WIDTH)
            val b = stroke(STROKE_B_LEFT, STROKE_WIDTH)
            val bloom = Bloom(intensity = HALO_INTENSITY)
            val noBloom = draw(null, listOf(a, b))
            val aOnly0 = draw(bloom, listOf(a))
            val aOnly1 = draw(bloom, listOf(a.copy(bloomReach = 1f)))
            val both0 = draw(bloom, listOf(a, b))
            val both1 = draw(bloom, listOf(a.copy(bloomReach = 1f), b))
            val x = STROKE_B_LEFT + STROKE_WIDTH + B_HALO_DISTANCE
            val aFarLight = aOnly1.pixel(x, MID_Y)[0] - aOnly0.pixel(x, MID_Y)[0]
            val bothDelta = both1.pixel(x, MID_Y)[0] - both0.pixel(x, MID_Y)[0]
            val bHalo0 = both0.pixel(x, MID_Y)[0] - aOnly0.pixel(x, MID_Y)[0]
            val bHalo1 = both1.pixel(x, MID_Y)[0] - aOnly1.pixel(x, MID_Y)[0]
            println(
                "RenG per-element bloom case c: dialect=$dialect at B+$B_HALO_DISTANCE px: " +
                    "ground=${noBloom.pixel(x, MID_Y).describe()} A's far light alone=$aFarLight " +
                    "change with B present=$bothDelta B's own halo reach0=$bHalo0 reachA=$bHalo1",
            )
            assertTrue(bHalo0 > 8, "B must have a visible halo of its own here, had $bHalo0")
            assertTrue(
                abs(bothDelta - aFarLight) <= SUPERPOSITION_TOLERANCE,
                "A's reach must add only A's own far light at B's halo ($aFarLight alone, $bothDelta with B)",
            )
        }

        // Point 2 of the design review: the far band's DC gain is sum(levelWeights[reachLevel..]).
        failures.run("dc: the far band's gain over a uniform field is the sum of its levels' weights") {
            val field = fullScreenEmitter(DC_LIGHT)
            val noBloom = draw(null, listOf(field))
            val reach0 = draw(Bloom(), listOf(field))
            val reach1 = draw(Bloom(), listOf(field.copy(bloomReach = 1f)))
            val c = BLOOM_PER_ELEMENT_PIXELS / 2
            val base = noBloom.pixel(c, c)[0]
            val near = (reach0.pixel(c, c)[0] - base) / 255.0 / DC_LIGHT
            val far = (reach1.pixel(c, c)[0] - reach0.pixel(c, c)[0]) / 255.0 / DC_LIGHT
            val weights = Bloom.DEFAULT_LEVEL_WEIGHTS
            val expectedNear = weights.sum().toDouble()
            val expectedFar = weights.drop(Bloom.DEFAULT_REACH_LEVEL).sum().toDouble()
            println(
                "RenG per-element bloom case dc: dialect=$dialect base=$base " +
                    "nearGain=${near.rounded()} (sum of all weights ${expectedNear.rounded()}) " +
                    "farGain=${far.rounded()} (sum of weights from level 3 ${expectedFar.rounded()})",
            )
            assertTrue(abs(near - expectedNear) <= DC_RELATIVE_TOLERANCE * expectedNear, "near DC gain $near")
            assertTrue(abs(far - expectedFar) <= DC_RELATIVE_TOLERANCE * expectedFar, "far DC gain $far")
        }

        // (d) Half-float emission carries light above one; RGBA8 clips it.
        failures.run("d: half-float lets an emitter write more than one; RGBA8 clips it") {
            val bloom = Bloom(intensity = HDR_INTENSITY)
            val hp = Bloom(intensity = HDR_INTENSITY, highPrecisionEmission = true)
            val noBloom = draw(null, listOf(valueEmitter(1f)))
            val clipped1 = draw(bloom, listOf(valueEmitter(1f)))
            val clipped2 = draw(bloom, listOf(valueEmitter(2f)))
            val hp1 = draw(hp, listOf(valueEmitter(1f)))
            val hp1Diagnostics = diagnostics.map { it.code }
            val hp2 = draw(hp, listOf(valueEmitter(2f)))
            val hpHuge = draw(hp, listOf(valueEmitter(HUGE_LIGHT)))
            // Level 0 is the unclamped emission image itself: the final upsample reads it at a zero
            // weight by default, at a positive one here, and a single-level chain reads nothing else.
            val hpHugeLevel0 = draw(
                Bloom(
                    intensity = HDR_INTENSITY,
                    highPrecisionEmission = true,
                    levelWeights = listOf(LEVEL0_WEIGHT) + Bloom.DEFAULT_LEVEL_WEIGHTS.drop(1),
                ),
                listOf(valueEmitter(HUGE_LIGHT)),
            )
            val hpHugeSingleLevel = draw(
                Bloom(intensity = HDR_INTENSITY, highPrecisionEmission = true, levelWeights = listOf(1f)),
                listOf(valueEmitter(HUGE_LIGHT)),
            )
            val hpNegative = draw(hp, listOf(valueEmitter(-1f)))
            val x = STROKE_A_LEFT + STROKE_WIDTH + HDR_DISTANCE
            val strokeX = STROKE_A_LEFT + STROKE_WIDTH / 2
            fun added(frame: ByteArray): Int = frame.pixel(x, MID_Y)[0] - noBloom.pixel(x, MID_Y)[0]
            val farX = BLOOM_PER_ELEMENT_PIXELS - 8
            val strokeBase = noBloom.pixel(strokeX, MID_Y)
            fun saneStroke(frame: ByteArray): Boolean {
                val pixel = frame.pixel(strokeX, MID_Y)
                return pixel[0] >= strokeBase[0] && abs(pixel[0] - pixel[1]) <= 2 &&
                    abs(pixel[0] - pixel[2]) <= 2 && pixel[3] == strokeBase[3]
            }
            println(
                "RenG per-element bloom case d: dialect=$dialect supportsHalfFloat=$supportsHalfFloat " +
                    "diagnostics=$hp1Diagnostics added at +$HDR_DISTANCE px: rgba8 k1=${added(clipped1)} " +
                    "k2=${added(clipped2)} halfFloat k1=${added(hp1)} k2=${added(hp2)} " +
                    "k$HUGE_LIGHT=${added(hpHuge)} hugeFar=${hpHuge.pixel(farX, MID_Y).describe()} " +
                    "rgba8VsHalfFloatK1=${maxChannelDelta(clipped1.pixel(x, MID_Y), hp1.pixel(x, MID_Y))} " +
                    "stroke base=${strokeBase.describe()} huge=${hpHuge.pixel(strokeX, MID_Y).describe()} " +
                    "hugeLevel0=${hpHugeLevel0.pixel(strokeX, MID_Y).describe()} " +
                    "hugeSingleLevel=${hpHugeSingleLevel.pixel(strokeX, MID_Y).describe()} " +
                    "negative stroke=${hpNegative.pixel(strokeX, MID_Y).describe()} " +
                    "negative +$HDR_DISTANCE px=${hpNegative.pixel(x, MID_Y).describe()} " +
                    "(no bloom ${noBloom.pixel(x, MID_Y).describe()})",
            )
            assertContentEquals(clipped1, clipped2, "RGBA8 emission must clip a light of 2 to the light of 1")
            if (supportsHalfFloat) {
                assertTrue(
                    DiagnosticCode.BLOOM_EMISSION_PRECISION_REDUCED !in hp1Diagnostics,
                    "a half-float context must not report reduced precision: $hp1Diagnostics",
                )
                assertTrue(
                    maxChannelDelta(clipped1.pixel(x, MID_Y), hp1.pixel(x, MID_Y)) <= 2,
                    "for light no brighter than one, half-float must match RGBA8 within quantisation",
                )
                assertTrue(
                    added(hp2) >= (HDR_MINIMUM_RATIO * added(hp1)).roundToInt(),
                    "a light of 2 must add at least ${HDR_MINIMUM_RATIO}x the light of 1 " +
                        "(${added(hp1)} -> ${added(hp2)})",
                )
                assertTrue(added(hpHuge) >= added(hp2), "a huge light must stay finite and bright, not NaN")
                assertTrue(
                    maxChannelDelta(hpHuge.pixel(farX, MID_Y), noBloom.pixel(farX, MID_Y)) <= 2 ||
                        hpHuge.pixel(farX, MID_Y)[0] >= noBloom.pixel(farX, MID_Y)[0],
                    "a huge light must not darken or corrupt the far frame",
                )
                assertTrue(saneStroke(hpHuge), "a huge light's own stroke must stay finite and neutral")
                assertTrue(saneStroke(hpHugeLevel0), "a weighted level 0 must bound a huge light")
                assertTrue(saneStroke(hpHugeSingleLevel), "a single-level chain must bound a huge light")
                assertTrue(
                    hpNegative.pixel(strokeX, MID_Y)[0] >= strokeBase[0] &&
                        hpNegative.pixel(x, MID_Y)[0] >= noBloom.pixel(x, MID_Y)[0],
                    "negative emission must add no light rather than darken the frame",
                )
            } else {
                assertEquals(
                    listOf(DiagnosticCode.BLOOM_EMISSION_PRECISION_REDUCED),
                    hp1Diagnostics.filter { it == DiagnosticCode.BLOOM_EMISSION_PRECISION_REDUCED },
                    "a context without half-float colour must report the RGBA8 fallback",
                )
                assertContentEquals(clipped1, hp1, "the fallback chain is the RGBA8 chain")
                assertContentEquals(clipped1, hp2, "the fallback chain clips as RGBA8 does")
            }
        }

        // The design review's shimmer risk, measured: a 4 px line moved through one level-3 pixel.
        failures.run("shimmer: the reach image holds a thin line's far light steadier than a coarse redraw") {
            fun farLightAcrossPhases(strategy: BloomFarBandStrategy): List<Int> {
                rengRenderer.bloomFarBandStrategy = strategy
                try {
                    return (0 until 8).map { phase ->
                        val line = stroke(STROKE_A_LEFT + phase, THIN_WIDTH)
                        val x = STROKE_A_LEFT + phase + THIN_WIDTH + SHIMMER_DISTANCE
                        val bloom = Bloom(intensity = 1f)
                        val reach0 = draw(bloom, listOf(line))
                        val reach1 = draw(bloom, listOf(line.copy(bloomReach = 1f)))
                        reach1.pixel(x, MID_Y)[0] - reach0.pixel(x, MID_Y)[0]
                    }
                } finally {
                    rengRenderer.bloomFarBandStrategy = BloomFarBandStrategy.REACH_IMAGE
                }
            }
            val reachImage = farLightAcrossPhases(BloomFarBandStrategy.REACH_IMAGE)
            val coarse = farLightAcrossPhases(BloomFarBandStrategy.COARSE_REDRAW)
            val reachSpread = reachImage.max() - reachImage.min()
            val coarseSpread = coarse.max() - coarse.min()
            println(
                "RenG per-element bloom case shimmer: dialect=$dialect far light $SHIMMER_DISTANCE px past a " +
                    "$THIN_WIDTH px line at phases 0..7: reachImage=$reachImage (spread $reachSpread) " +
                    "coarse=$coarse (spread $coarseSpread)",
            )
            assertTrue(reachImage.min() > 0, "the reach image must add light at every phase: $reachImage")
            assertTrue(
                reachSpread <= coarseSpread,
                "the reach image must vary with phase no more than the coarse redraw " +
                    "($reachSpread vs $coarseSpread)",
            )
        }
    } finally {
        renderer.close()
        binding.deleteFramebuffers(1, intArrayOf(target.framebuffer))
        binding.deleteTextures(1, intArrayOf(target.texture))
    }
    failures.throwIfAny()
}

private fun ground(): Geometry = Geometry(
    topLeft = pixelGeo(-4, BLOOM_PER_ELEMENT_PIXELS + 4, altitude = 0.0),
    bottomRight = pixelGeo(BLOOM_PER_ELEMENT_PIXELS + 4, -4, altitude = 0.0),
    shaderPair = ShaderPair(PER_ELEMENT_VERTEX_SOURCE, DARK_GROUND_FRAGMENT_SOURCE),
)

/** A white emissive vertical stroke [width] px wide from x = [left], spanning [STROKE_BOTTOM]..[STROKE_TOP]. */
private fun stroke(left: Int, width: Int): Geometry = Geometry(
    topLeft = pixelGeo(left, STROKE_TOP, altitude = 1.0),
    bottomRight = pixelGeo(left + width, STROKE_BOTTOM, altitude = 1.0),
    shaderPair = ShaderPair(PER_ELEMENT_VERTEX_SOURCE, WHITE_FRAGMENT_SOURCE),
    emissive = true,
)

/** The stroke at [STROKE_A_LEFT], drawn mid-grey but writing [light] to every RGB channel when emitting. */
private fun valueEmitter(light: Float): Geometry = stroke(STROKE_A_LEFT, STROKE_WIDTH).copy(
    shaderPair = ShaderPair(PER_ELEMENT_VERTEX_SOURCE, VALUE_FRAGMENT_SOURCE),
    uniforms = mapOf("uLight" to ShaderValue.Scalar(light)),
)

/** An emitter covering the whole output, drawn and emitting [light] on every RGB channel. */
private fun fullScreenEmitter(light: Double): Geometry = Geometry(
    topLeft = pixelGeo(-4, BLOOM_PER_ELEMENT_PIXELS + 4, altitude = 1.0),
    bottomRight = pixelGeo(BLOOM_PER_ELEMENT_PIXELS + 4, -4, altitude = 1.0),
    shaderPair = ShaderPair(PER_ELEMENT_VERTEX_SOURCE, UNIFORM_LIGHT_FRAGMENT_SOURCE),
    uniforms = mapOf("uLight" to ShaderValue.Scalar(light.toFloat())),
    emissive = true,
)

/** One output pixel edge as the geographic coordinate a north-up top-down camera sees there. */
private fun pixelGeo(x: Int, y: Int, altitude: Double): Vector3 {
    val worldSize = 512.0 * (1 shl PER_ELEMENT_ZOOM.toInt())
    val worldX = worldSize / 2.0 + (x - BLOOM_PER_ELEMENT_PIXELS / 2.0)
    val worldY = worldSize / 2.0 - (y - BLOOM_PER_ELEMENT_PIXELS / 2.0)
    val longitude = worldX / worldSize * 360.0 - 180.0
    val mercator = PI * (1.0 - 2.0 * worldY / worldSize)
    val latitude = atan(sinh(mercator)) * 180.0 / PI
    return Vector3(latitude, longitude, altitude)
}

private fun drawAndRead(
    binding: GlBinding,
    renderer: Renderer,
    renderTarget: RenderTarget,
    targetFramebuffer: Int,
    plan: FramePlan,
): ByteArray {
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
    binding.disable(GL_BLEND)
    binding.disable(GL_SCISSOR_TEST)
    binding.colorMask(true, true, true, true)
    binding.viewport(0, 0, BLOOM_PER_ELEMENT_PIXELS, BLOOM_PER_ELEMENT_PIXELS)
    binding.clearColor(0f, 0f, 0f, 0f)
    binding.clear(GL_COLOR_BUFFER_BIT)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)

    val frame = runBlocking { renderer.prepare(plan) }
    try {
        renderer.draw(frame, renderTarget)
    } finally {
        frame.close()
    }

    val pixels = ByteArray(BLOOM_PER_ELEMENT_PIXELS * BLOOM_PER_ELEMENT_PIXELS * 4)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
    binding.readPixels(
        0, 0, BLOOM_PER_ELEMENT_PIXELS, BLOOM_PER_ELEMENT_PIXELS,
        GL_RGBA, GL_UNSIGNED_BYTE, pixels,
    )
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    return pixels
}

private data class PerElementTarget(val framebuffer: Int, val texture: Int)

private fun createPerElementTarget(binding: GlBinding): PerElementTarget {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, BLOOM_PER_ELEMENT_PIXELS, BLOOM_PER_ELEMENT_PIXELS)
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the per-element bloom readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return PerElementTarget(framebuffer, texture)
}

private fun ByteArray.pixel(x: Int, y: Int): IntArray {
    val offset = (y * BLOOM_PER_ELEMENT_PIXELS + x) * 4
    return IntArray(4) { channel -> this[offset + channel].toInt() and 0xff }
}

private fun differingBytes(first: ByteArray, second: ByteArray): Int =
    first.indices.count { first[it] != second[it] }

private fun maxChannelDelta(first: IntArray, second: IntArray): Int =
    first.indices.maxOf { abs(first[it] - second[it]) }

private fun IntArray.describe(): String = joinToString(prefix = "(", postfix = ")")

private fun Double.rounded(): Double = (this * 1000.0).roundToInt() / 1000.0

private fun ByteArray.sha256(): String = PureKotlinSha256.digest(CanonicalBytes(this)).lowercaseHex

private class PerElementFailures {
    private val failures = ArrayList<String>()

    fun run(name: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            failures += "$name: ${failure.message ?: failure::class.simpleName}"
        }
    }

    fun throwIfAny() {
        if (failures.isNotEmpty()) {
            throw AssertionError(failures.joinToString(prefix = "Per-element bloom failures:\n", separator = "\n"))
        }
    }
}

private class PerElementTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse =
        error("the per-element bloom fixture fetches nothing, asked for ${request.resourceClass}")
}

private const val PER_ELEMENT_ZOOM: Double = 10.0
private const val STROKE_A_LEFT: Int = 40
private const val STROKE_B_LEFT: Int = 168
private const val STROKE_WIDTH: Int = 8
private const val THIN_WIDTH: Int = 4
private const val STROKE_BOTTOM: Int = 64
private const val STROKE_TOP: Int = 192
private const val MID_Y: Int = 128
private const val FAR_LEFT: Int = 200

/** Bloom intensity for the halo cases: strong enough to measure, low enough to stay below the knee. */
private const val HALO_INTENSITY: Float = 0.5f

/** Where "far" is measured, past the stroke's right edge: beyond most of the near band's reach. */
private const val FAR_DISTANCE: Int = 40

/** Just past the stroke, where the near band dominates. */
private const val NEAR_DISTANCE: Int = 2

/** "Unchanged" at the stroke: RGBA8 rounding through two passes. */
private const val STROKE_TOLERANCE: Int = 2

private const val B_HALO_DISTANCE: Int = 6

/** Where the shimmer case reads a thin line's far light: out of the near band, still well lit. */
private const val SHIMMER_DISTANCE: Int = 16

/** Superposition holds below the knee, up to RGBA8 rounding of three frames. */
private const val SUPERPOSITION_TOLERANCE: Int = 2

private const val DC_LIGHT: Double = 0.1
private const val DC_RELATIVE_TOLERANCE: Double = 0.15

private const val HDR_INTENSITY: Float = 0.2f
private const val HDR_DISTANCE: Int = 6

/** Twice the light should add twice the halo; allow for rounding and the edge of the shoulder. */
private const val HDR_MINIMUM_RATIO: Double = 1.6
private const val HUGE_LIGHT: Float = 1.0e6f

/** Case d's positive level-0 weight, to read the unclamped emission image as light. */
private const val LEVEL0_WEIGHT: Float = 0.5f

private const val PER_ELEMENT_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "in vec3 aPosition;\n" +
        "uniform mat4 uModelViewProjection;\n" +
        "void main() {\n" +
        "    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);\n" +
        "}\n"

private const val DARK_GROUND_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 rengGeometryColour;\n" +
        "void main() { rengGeometryColour = vec4(0.1, 0.1, 0.1, 1.0); }\n"

private const val WHITE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 rengGeometryColour;\n" +
        "void main() { rengGeometryColour = vec4(1.0); }\n"

private const val VALUE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform int rengGeometryEmissionPass;\n" +
        "uniform float uLight;\n" +
        "out vec4 rengGeometryColour;\n" +
        "void main() {\n" +
        "    rengGeometryColour = rengGeometryEmissionPass == 1 ?\n" +
        "        vec4(vec3(uLight), 1.0) : vec4(0.5, 0.5, 0.5, 1.0);\n" +
        "}\n"

private const val UNIFORM_LIGHT_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform float uLight;\n" +
        "out vec4 rengGeometryColour;\n" +
        "void main() { rengGeometryColour = vec4(vec3(uLight), 1.0); }\n"
