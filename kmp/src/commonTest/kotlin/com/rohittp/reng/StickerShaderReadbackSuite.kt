package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_BLEND
import com.rohittp.reng.internal.gl.GL_COLOR_ATTACHMENT0
import com.rohittp.reng.internal.gl.GL_COLOR_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_DRAW_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_FRAMEBUFFER_COMPLETE
import com.rohittp.reng.internal.gl.GL_PACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_READ_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_RGBA
import com.rohittp.reng.internal.gl.GL_LINEAR
import com.rohittp.reng.internal.gl.GL_RGBA8
import com.rohittp.reng.internal.gl.GL_SAMPLER_BINDING
import com.rohittp.reng.internal.gl.GL_TEXTURE0
import com.rohittp.reng.internal.gl.GL_TEXTURE_MAG_FILTER
import com.rohittp.reng.internal.gl.GL_TEXTURE_MIN_FILTER
import com.rohittp.reng.internal.gl.GL_SCISSOR_TEST
import com.rohittp.reng.internal.gl.GL_TEXTURE_2D
import com.rohittp.reng.internal.gl.GL_UNSIGNED_BYTE
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.gl.ShaderDialect
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

internal const val STICKER_SHADER_READBACK_PIXELS: Int = 128

/** At most one pixel in this many of the rectangle may differ from the Geometry's; see the ground case. */
private const val MAXIMUM_DIFFERING_DIVISOR: Int = 200

/**
 * ADR 0087 on a real driver: a sticker drawn with a consumer fragment program.
 *
 * Every claim compares two frames this renderer drew rather than a frame against a predicted pixel,
 * so a failure is a finding about the program's interface and not about where the fixture happens to
 * put things (`predictions-about-the-GPU` is a lesson this project has paid for):
 *
 * - **Pass-through is the plain sticker.** A program writing only `rengStickerColour()` must read back
 *   byte-for-byte the frame the same sticker draws without one -- PNG and ETC2, both regimes, at an
 *   opacity and a blend that would expose a missed multiply or a changed blend state.
 * - **`rengStickerUv` is the image's orientation.** A program painting each quadrant of its own
 *   texture coordinate the colour the fixture image has in that quadrant must read the same colours,
 *   at the same pixels, as the plain sticker drawing that image.
 * - **`rengStickerGroundUv()` is the Geometry's texture coordinate.** A program painting a coordinate
 *   checkerboard from it must land on the same pixels as a Geometry over the same rectangle painting
 *   the same checkerboard from `aTexCoord`, at pitch 0 and 45, for a billboard, a sticker lying on the
 *   map and a screen-composited one. The checker encodes each cell's column and row in red and green,
 *   so a swapped or flipped axis cannot pass as a symmetric pattern would; a mismatch is tolerated
 *   only where the other frame has the same colour within one pixel, which is a cell or rectangle
 *   edge rounding the other way, and only at one pixel in [MAXIMUM_DIFFERING_DIVISOR], because a
 *   stretch too small to move any edge a whole pixel would otherwise pass.
 */
internal fun runStickerShaderReadbackSuite(
    binding: GlBinding,
    probe: RenderContextProbe,
    dialect: ShaderDialect,
) {
    val target = createStickerShaderReadbackTarget(binding)
    val diagnostics = ArrayList<Diagnostic>()
    val renderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(STICKER_SHADER_READBACK_PIXELS, STICKER_SHADER_READBACK_PIXELS),
            transport = StickerShaderTransport(),
            store = RecordingStyleStore(),
            basemapStyle = null,
            diagnosticSink = DiagnosticSink { diagnostics += it },
        ),
        binding,
        probe,
    )
    val failures = StickerShaderCollectedFailures()
    var frameIndex = 0L
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))
        fun drawFrame(plan: FramePlan): IntArray =
            clearDrawAndReadFrame(binding, renderer, target, renderTarget, plan)

        PASS_THROUGH_CASES.forEach { case ->
            failures.run("pass-through ${case.name}") {
                val plain = drawFrame(passThroughPlan(frameIndex++, case, shader = null))
                val shaded = drawFrame(
                    passThroughPlan(frameIndex++, case, shader = StickerShader(PASS_THROUGH_FRAGMENT)),
                )
                val stickerless = drawFrame(passThroughPlan(frameIndex++, case, stickerless = true))
                val covered = plain.countDiffering(stickerless)
                val differing = plain.countDiffering(shaded)
                println(
                    "RenG sticker shader readback: dialect=$dialect case=pass-through ${case.name} " +
                        "stickerPixels=$covered differingPixels=$differing",
                )
                assertTrue(covered > 100, "the sticker must cover pixels for pass-through ${case.name} to measure it")
                assertEquals(0, differing, "a pass-through program must draw the plain sticker's exact bytes")
            }
        }

        ORIENTATION_POSITIONS.forEach { positionMode ->
            failures.run("orientation $positionMode") {
                val plain = drawFrame(orientationPlan(frameIndex++, positionMode, shader = null))
                val tinted = drawFrame(
                    orientationPlan(frameIndex++, positionMode, shader = StickerShader(ORIENTATION_FRAGMENT)),
                )
                var compared = 0
                for (colour in QUADRANT_COLOURS) {
                    val pixel = plain.findCentreOf(colour)
                        ?: throw AssertionError("the plain sticker must draw ${colour.contentToString()}")
                    val seen = tinted.pixelAt(pixel.first, pixel.second)
                    println(
                        "RenG sticker shader readback: dialect=$dialect case=orientation $positionMode " +
                            "colour=${colour.contentToString()} at=$pixel tinted=${seen.contentToString()}",
                    )
                    assertTrue(
                        seen.indices.all { abs(seen[it] - colour[it]) <= 2 },
                        "rengStickerUv must put ${colour.contentToString()} where the image does, read " +
                            seen.contentToString(),
                    )
                    compared += 1
                }
                assertEquals(QUADRANT_COLOURS.size, compared)
            }
        }

        HAS_GROUND_CASES.forEach { case ->
            failures.run("has ground ${case.name}") {
                diagnostics.clear()
                val frame = drawFrame(hasGroundPlan(frameIndex++, case))
                val seen = frame.pixelAt(64, 64)
                val expected = if (case.expected) HAS_GROUND_COLOUR else NO_GROUND_COLOUR
                val warnings = diagnostics.filter { it.code == DiagnosticCode.STICKER_GROUND_UNAVAILABLE }
                println(
                    "RenG sticker shader readback: dialect=$dialect case=has ground ${case.name} " +
                        "pixel=${seen.contentToString()} warnings=${warnings.map { it.actual }}",
                )
                assertTrue(
                    seen.indices.all { abs(seen[it] - expected[it]) <= 2 },
                    "rengStickerHasGround() must be ${case.expected}, read ${seen.contentToString()}",
                )
                if (case.reportsUnavailable) {
                    assertEquals(1, warnings.size, "an uncarried rectangle must be reported once")
                    assertEquals("sticker.shader.ground", warnings.single().fieldName)
                    assertEquals(1L, warnings.single().actual)
                } else {
                    assertEquals(0, warnings.size, "only an uncarried rectangle is reported")
                }
            }
        }

        failures.run("a host sampler object on a consumer unit") {
            // A host's GL_LINEAR sampler object on unit 1, where the program's data texture lands. If
            // it applied, sampling the two-texel field at u = 0.4 would blend 30% of the white texel in.
            val names = IntArray(1)
            binding.genSamplers(1, names)
            val hostSampler = names[0]
            binding.samplerParameteri(hostSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            binding.samplerParameteri(hostSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            binding.bindSampler(1, hostSampler)
            try {
                val frame = drawFrame(dataSamplingPlan(frameIndex++))
                val seen = frame.pixelAt(64, 64)
                binding.activeTexture(GL_TEXTURE0 + 1)
                val bound = IntArray(1).also { binding.getIntegerv(GL_SAMPLER_BINDING, it) }.single()
                binding.activeTexture(GL_TEXTURE0)
                println(
                    "RenG sticker shader readback: dialect=$dialect case=host sampler " +
                        "pixel=${seen.contentToString()} hostSamplerAfter=${bound == hostSampler}",
                )
                assertTrue(seen[0] <= 2 && seen[3] == 255, "a data texture must sample nearest, read ${seen.contentToString()}")
                assertEquals(hostSampler, bound, "the host's sampler object must be back on unit 1 after the frame")
            } finally {
                binding.bindSampler(1, 0)
                binding.deleteSamplers(1, names)
            }
        }

        GROUND_CASES.forEach { case ->
            failures.run("ground ${case.name}") {
                val geometry = drawFrame(groundGeometryPlan(frameIndex++, case))
                val sticker = drawFrame(groundStickerPlan(frameIndex++, case))
                val geometryPixels = geometry.countDrawn()
                val stickerPixels = sticker.countDrawn()
                val unexplained = geometry.unexplainedDifferences(sticker)
                val differing = geometry.countDiffering(sticker)
                println(
                    "RenG sticker shader readback: dialect=$dialect case=ground ${case.name} " +
                        "geometryPixels=$geometryPixels stickerPixels=$stickerPixels " +
                        "differingPixels=$differing unexplainedPixels=$unexplained",
                )
                assertTrue(geometryPixels > 2000, "the Geometry must draw the rectangle for ${case.name} to measure it")
                assertEquals(0, unexplained, "rengStickerGroundUv must land each cell on the Geometry's pixels")
                // The neighbourhood rule alone admits a sub-pixel error everywhere along an edge: a
                // 1% stretch of v, at most 0.74 pixels here, measured 239 differing pixels and not
                // one unexplained. Edge rounding alone measured at most one, so the count is bounded.
                assertTrue(
                    differing <= geometryPixels / MAXIMUM_DIFFERING_DIVISOR,
                    "rengStickerGroundUv may differ from the Geometry only at a few edge pixels, " +
                        "differed at $differing",
                )
            }
        }
    } finally {
        renderer.close()
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
    failures.throwIfAny()
}

// ---------------------------------------------------------------------------------------------
// Programs
// ---------------------------------------------------------------------------------------------

private const val PASS_THROUGH_FRAGMENT: String =
    "#version 300 es\n" +
        "out vec4 colour;\n" +
        "void main() { colour = rengStickerColour(); }\n"

/** Each quadrant of the sticker's own coordinate painted [QUADRANT_COLOURS]' colour for it. */
private const val ORIENTATION_FRAGMENT: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 colour;\n" +
        "void main() {\n" +
        "    bool east = rengStickerUv.x >= 0.5;\n" +
        "    bool south = rengStickerUv.y >= 0.5;\n" +
        "    if (!east && !south) colour = vec4(1.0, 0.0, 0.0, 1.0);\n" +
        "    else if (east && !south) colour = vec4(0.0, 1.0, 0.0, 1.0);\n" +
        "    else if (!east && south) colour = vec4(0.0, 0.0, 1.0, 1.0);\n" +
        "    else colour = vec4(1.0, 1.0, 1.0, 1.0);\n" +
        "}\n"

/** A cell's column and row in red and green, and its parity in blue, so every axis error shows. */
private const val CHECKER_FUNCTION: String =
    "vec4 checker(vec2 uv) {\n" +
        "    vec2 cell = floor(uv * 8.0);\n" +
        "    return vec4(cell.x / 8.0, cell.y / 8.0, mod(cell.x + cell.y, 2.0), 1.0);\n" +
        "}\n"

private const val GROUND_STICKER_FRAGMENT: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 colour;\n" +
        CHECKER_FUNCTION +
        "void main() {\n" +
        "    vec2 uv = rengStickerGroundUv();\n" +
        "    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {\n" +
        "        colour = vec4(0.0);\n" +
        "    } else {\n" +
        "        colour = checker(uv);\n" +
        "    }\n" +
        "}\n"

private const val GROUND_GEOMETRY_VERTEX: String =
    "#version 300 es\n" +
        "in vec3 aPosition;\n" +
        "in vec2 aTexCoord;\n" +
        "uniform mat4 uModelViewProjection;\n" +
        "out vec2 groundUv;\n" +
        "void main() {\n" +
        "    groundUv = aTexCoord;\n" +
        "    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);\n" +
        "}\n"

private const val GROUND_GEOMETRY_FRAGMENT: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "in vec2 groundUv;\n" +
        "out vec4 colour;\n" +
        CHECKER_FUNCTION +
        "void main() { colour = checker(groundUv); }\n"

private const val BACKGROUND_VERTEX: String =
    "#version 300 es\n" +
        "in vec3 aPosition;\n" +
        "uniform mat4 uModelViewProjection;\n" +
        "void main() { gl_Position = uModelViewProjection * vec4(aPosition, 1.0); }\n"

private const val BACKGROUND_FRAGMENT: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 colour;\n" +
        "void main() { colour = vec4(0.125490196, 0.376470588, 0.627450980, 1.0); }\n"

/** Red where the frame gives the program ground, green where it does not, whatever the pixel. */
private const val HAS_GROUND_FRAGMENT: String =
    "#version 300 es\n" +
        "out vec4 colour;\n" +
        "void main() {\n" +
        "    colour = rengStickerHasGround() ? vec4(1.0, 0.0, 0.0, 1.0) : vec4(0.0, 1.0, 0.0, 1.0);\n" +
        "}\n"

/** Samples a two-texel data field just inside its first (black) texel; nearest reads 0, linear 77. */
private const val DATA_SAMPLING_FRAGMENT: String =
    "#version 300 es\n" +
        "uniform sampler2D uData;\n" +
        "out vec4 colour;\n" +
        "void main() { colour = vec4(texture(uData, vec2(0.4, 0.5)).rgb, 1.0); }\n"

private fun dataSamplingPlan(frameIndex: Long): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = Camera(CENTRE_LATITUDE, CENTRE_LONGITUDE, 9.0, 0.0, 0.0),
    drawBasemap = false,
    drawLabels = false,
    stickers = listOf(
        Sticker(
            placement = billboardPlacement(AnchoringMode.SCREEN, 10.0),
            image = ResourceLocator(QUADRANT_URL),
            shader = StickerShader(DATA_SAMPLING_FRAGMENT, textures = mapOf("uData" to ResourceLocator(DATA_URL))),
        ),
    ),
)

private val HAS_GROUND_COLOUR: IntArray = intArrayOf(255, 0, 0, 255)
private val NO_GROUND_COLOUR: IntArray = intArrayOf(0, 255, 0, 255)

// ---------------------------------------------------------------------------------------------
// Plans
// ---------------------------------------------------------------------------------------------

private class HasGroundCase(
    val name: String,
    val projectionMode: ProjectionMode,
    val ground: GroundRectangle?,
    val expected: Boolean,
    val reportsUnavailable: Boolean = false,
)

/** Beyond Mercator's latitude limit: a rectangle a Mercator frame cannot carry, and a globe ignores. */
private val UNCARRIED_RECTANGLE: GroundRectangle =
    GroundRectangle(Vector3(89.0, 0.0, 0.0), Vector3(80.0, 1.0, 0.0))

/** A getter, because [GROUND_RECTANGLE] is declared below and top-level values initialise in order. */
private val HAS_GROUND_CASES: List<HasGroundCase> get() = listOf(
    HasGroundCase("mercator with a rectangle", ProjectionMode.MERCATOR, GROUND_RECTANGLE, expected = true),
    HasGroundCase("mercator without one", ProjectionMode.MERCATOR, null, expected = false),
    HasGroundCase("globe with a rectangle", ProjectionMode.GLOBE, GROUND_RECTANGLE, expected = false),
    HasGroundCase(
        "mercator with an uncarried rectangle", ProjectionMode.MERCATOR, UNCARRIED_RECTANGLE,
        expected = false, reportsUnavailable = true,
    ),
    HasGroundCase("globe with an uncarried rectangle", ProjectionMode.GLOBE, UNCARRIED_RECTANGLE, expected = false),
)

private fun hasGroundPlan(frameIndex: Long, case: HasGroundCase): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = Camera(CENTRE_LATITUDE, CENTRE_LONGITUDE, 9.0, 0.0, 0.0),
    projectionMode = case.projectionMode,
    drawBasemap = false,
    drawLabels = false,
    stickers = listOf(
        Sticker(
            placement = billboardPlacement(AnchoringMode.SCREEN, 10.0),
            image = ResourceLocator(QUADRANT_URL),
            shader = StickerShader(HAS_GROUND_FRAGMENT, ground = case.ground),
        ),
    ),
)

private const val CENTRE_LATITUDE: Double = 10.0
private const val CENTRE_LONGITUDE: Double = 20.0

private class PassThroughCase(
    val name: String,
    val url: String,
    val positionMode: AnchoringMode,
    val opacity: Float,
    val blend: BlendMode,
    val scale: Double,
)

private val PASS_THROUGH_CASES: List<PassThroughCase> = listOf(
    // 2x2 texels across 50 pixels, so most pixels are a bilinear mix of four texels.
    PassThroughCase("png screen over", QUADRANT_URL, AnchoringMode.SCREEN, 0.6f, BlendMode.OVER, 25.0),
    PassThroughCase("png map screen-blend", QUADRANT_URL, AnchoringMode.MAP, 0.8f, BlendMode.SCREEN, 25.0),
    PassThroughCase("etc2 screen over", ETC2_URL, AnchoringMode.SCREEN, 0.7f, BlendMode.OVER, 10.0),
    PassThroughCase("etc2 map additive", ETC2_URL, AnchoringMode.MAP, 1f, BlendMode.ADDITIVE, 10.0),
)

private fun passThroughPlan(
    frameIndex: Long,
    case: PassThroughCase,
    shader: StickerShader? = null,
    stickerless: Boolean = false,
): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = Camera(CENTRE_LATITUDE, CENTRE_LONGITUDE, 9.0, 0.0, 30.0),
    drawBasemap = false,
    drawLabels = false,
    stickers = if (stickerless) {
        emptyList()
    } else {
        listOf(
            Sticker(
                placement = billboardPlacement(case.positionMode, case.scale),
                image = ResourceLocator(case.url),
                opacity = case.opacity,
                blend = case.blend,
                shader = shader,
            ),
        )
    },
    geometries = listOf(backgroundGeometry()),
)

private val ORIENTATION_POSITIONS: List<AnchoringMode> = listOf(AnchoringMode.SCREEN, AnchoringMode.MAP)

private fun orientationPlan(frameIndex: Long, positionMode: AnchoringMode, shader: StickerShader?): FramePlan =
    FramePlan(
        frameIndex = frameIndex,
        camera = Camera(CENTRE_LATITUDE, CENTRE_LONGITUDE, 9.0, 0.0, 0.0),
        drawBasemap = false,
        drawLabels = false,
        stickers = listOf(
            Sticker(
                placement = billboardPlacement(positionMode, 30.0),
                image = ResourceLocator(QUADRANT_URL),
                shader = shader,
            ),
        ),
    )

private fun billboardPlacement(positionMode: AnchoringMode, scale: Double): Placement = Placement(
    positionMode = positionMode,
    position = when (positionMode) {
        AnchoringMode.MAP -> Vector3(CENTRE_LATITUDE, CENTRE_LONGITUDE, 0.0)
        AnchoringMode.SCREEN -> Vector3(64.0, 64.0, 0.0)
    },
    rotationMode = AnchoringMode.SCREEN,
    rotation = Vector3(0.0, 0.0, 0.0),
    scaleMode = AnchoringMode.SCREEN,
    scale = scale,
)

/**
 * About 90 logical pixels a side, inside the frame. Not larger: `Apple Software Renderer` drops quads
 * reaching far outside the viewport, erratically (`measureLargeQuadRasterisation`), and a 2-degree
 * background here was dropped from one frame of a pair and not the other.
 */
private fun backgroundGeometry(): Geometry = Geometry(
    topLeft = Vector3(CENTRE_LATITUDE + 0.06, CENTRE_LONGITUDE - 0.06, 0.0),
    bottomRight = Vector3(CENTRE_LATITUDE - 0.06, CENTRE_LONGITUDE + 0.06, 0.0),
    shaderPair = ShaderPair(BACKGROUND_VERTEX, BACKGROUND_FRAGMENT),
)

private enum class GroundStickerKind { BILLBOARD, LYING_ON_THE_MAP, SCREEN_COMPOSITED }

private class GroundCase(val name: String, val pitch: Double, val kind: GroundStickerKind)

private val GROUND_CASES: List<GroundCase> = listOf(0.0, 45.0).flatMap { pitch ->
    GroundStickerKind.entries.map { kind -> GroundCase("$kind pitch $pitch", pitch, kind) }
}

/** About 75 logical pixels a side at zoom 9, west of and north of the camera centre by a little. */
private val GROUND_RECTANGLE: GroundRectangle = GroundRectangle(
    topLeft = Vector3(CENTRE_LATITUDE + 0.055, CENTRE_LONGITUDE - 0.045, 0.0),
    bottomRight = Vector3(CENTRE_LATITUDE - 0.045, CENTRE_LONGITUDE + 0.055, 0.0),
)

private fun groundCamera(case: GroundCase): Camera =
    Camera(CENTRE_LATITUDE, CENTRE_LONGITUDE, 9.0, 20.0, case.pitch)

private fun groundGeometryPlan(frameIndex: Long, case: GroundCase): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = groundCamera(case),
    drawBasemap = false,
    drawLabels = false,
    geometries = listOf(
        Geometry(
            topLeft = GROUND_RECTANGLE.topLeft,
            bottomRight = GROUND_RECTANGLE.bottomRight,
            shaderPair = ShaderPair(GROUND_GEOMETRY_VERTEX, GROUND_GEOMETRY_FRAGMENT),
        ),
    ),
)

/**
 * A sticker larger than the rectangle on screen, so it covers every pixel the Geometry draws, and
 * still inside the frame, for the reason [backgroundGeometry] gives.
 */
private fun groundStickerPlan(frameIndex: Long, case: GroundCase): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = groundCamera(case),
    drawBasemap = false,
    drawLabels = false,
    stickers = listOf(
        Sticker(
            placement = when (case.kind) {
                GroundStickerKind.BILLBOARD -> billboardPlacement(AnchoringMode.MAP, 55.0)
                GroundStickerKind.SCREEN_COMPOSITED -> billboardPlacement(AnchoringMode.SCREEN, 55.0)
                // 2 image pixels of 7 km each: a 14 km square lying flat on the map and centred on
                // the rectangle, about 93 logical pixels a side against the rectangle's 75.
                GroundStickerKind.LYING_ON_THE_MAP -> Placement(
                    positionMode = AnchoringMode.MAP,
                    position = Vector3(CENTRE_LATITUDE + 0.005, CENTRE_LONGITUDE + 0.005, 0.0),
                    rotationMode = AnchoringMode.MAP,
                    rotation = Vector3(0.0, 0.0, 0.0),
                    scaleMode = AnchoringMode.MAP,
                    scale = 7_000.0,
                )
            },
            image = ResourceLocator(QUADRANT_URL),
            shader = StickerShader(GROUND_STICKER_FRAGMENT, ground = GROUND_RECTANGLE),
        ),
    ),
)

// ---------------------------------------------------------------------------------------------
// Readback
// ---------------------------------------------------------------------------------------------

private fun clearDrawAndReadFrame(
    binding: GlBinding,
    renderer: Renderer,
    targetFramebuffer: Int,
    renderTarget: RenderTarget,
    plan: FramePlan,
): IntArray {
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
    binding.disable(GL_BLEND)
    binding.disable(GL_SCISSOR_TEST)
    binding.colorMask(true, true, true, true)
    binding.viewport(0, 0, STICKER_SHADER_READBACK_PIXELS, STICKER_SHADER_READBACK_PIXELS)
    binding.clearColor(0f, 0f, 0f, 0f)
    binding.clear(GL_COLOR_BUFFER_BIT)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)

    runBlocking { renderer.prepare(plan) }.use { renderer.draw(it, renderTarget) }

    val bytes = ByteArray(STICKER_SHADER_READBACK_PIXELS * STICKER_SHADER_READBACK_PIXELS * 4)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
    binding.readPixels(
        0, 0, STICKER_SHADER_READBACK_PIXELS, STICKER_SHADER_READBACK_PIXELS, GL_RGBA, GL_UNSIGNED_BYTE, bytes,
    )
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    return IntArray(bytes.size) { bytes[it].toInt() and 0xff }
}

private fun IntArray.pixelAt(x: Int, y: Int): IntArray {
    val offset = (y * STICKER_SHADER_READBACK_PIXELS + x) * 4
    return intArrayOf(this[offset], this[offset + 1], this[offset + 2], this[offset + 3])
}

private fun IntArray.countDiffering(other: IntArray): Int {
    var count = 0
    for (pixel in 0 until size / 4) {
        if ((0 until 4).any { this[pixel * 4 + it] != other[pixel * 4 + it] }) count += 1
    }
    return count
}

private fun IntArray.countDrawn(): Int = (0 until size / 4).count { this[it * 4 + 3] != 0 }

/**
 * Pixels where [other] differs from this frame and neither frame has the other's colour anywhere in
 * the 3x3 neighbourhood: a difference that is not an edge rounding one pixel the other way.
 */
private fun IntArray.unexplainedDifferences(other: IntArray): Int {
    val side = STICKER_SHADER_READBACK_PIXELS
    var count = 0
    for (y in 0 until side) {
        for (x in 0 until side) {
            val mine = pixelAt(x, y)
            val theirs = other.pixelAt(x, y)
            if (mine.contentEquals(theirs)) continue
            val explained = neighbourhoodHas(x, y, theirs) && other.neighbourhoodHas(x, y, mine)
            if (!explained) count += 1
        }
    }
    return count
}

private fun IntArray.neighbourhoodHas(x: Int, y: Int, colour: IntArray): Boolean {
    val side = STICKER_SHADER_READBACK_PIXELS
    for (dy in -1..1) {
        for (dx in -1..1) {
            val nx = x + dx
            val ny = y + dy
            if (nx !in 0 until side || ny !in 0 until side) continue
            val candidate = pixelAt(nx, ny)
            if (candidate.indices.all { abs(candidate[it] - colour[it]) <= 2 }) return true
        }
    }
    return false
}

/** The middle pixel of every pixel within 2 of [colour], by bounding box, or `null` when none is. */
private fun IntArray.findCentreOf(colour: IntArray): Pair<Int, Int>? {
    val side = STICKER_SHADER_READBACK_PIXELS
    var minX = side
    var minY = side
    var maxX = -1
    var maxY = -1
    for (y in 0 until side) {
        for (x in 0 until side) {
            val pixel = pixelAt(x, y)
            if (pixel.indices.all { abs(pixel[it] - colour[it]) <= 2 }) {
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
        }
    }
    return if (maxX < 0) null else ((minX + maxX) / 2) to ((minY + maxY) / 2)
}

private fun createStickerShaderReadbackTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, STICKER_SHADER_READBACK_PIXELS, STICKER_SHADER_READBACK_PIXELS)
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the sticker shader readback target must be complete",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

private class StickerShaderCollectedFailures {
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
            throw AssertionError(
                failures.joinToString(prefix = "Sticker shader readback failures:\n", separator = "\n"),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Fixture images
// ---------------------------------------------------------------------------------------------

private class StickerShaderTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        val (body, contentType) = when (request.locator.value) {
            QUADRANT_URL -> QUADRANT_PNG to "image/png"
            ETC2_URL -> ETC2_CONTAINER to "image/x-reng-etc2"
            DATA_URL -> DATA_PNG to "image/png"
            else -> error("unexpected sticker locator")
        }
        return TransportResponse(
            statusCode = 200,
            body = body,
            metadata = TransportResponseMetadata(contentType = contentType),
        )
    }
}

private const val QUADRANT_URL: String = "https://stickers.example/quadrants.png"
private const val ETC2_URL: String = "https://stickers.example/translucent.reng-etc2"
private const val DATA_URL: String = "https://stickers.example/two-texel-field.png"

/** Two opaque texels, black then white: a field a linear filter would blend. */
private val DATA_PNG: ByteArray = rgbaPng(
    width = 2,
    height = 1,
    rgba = byteArrayOf(0, 0, 0, -1, -1, -1, -1, -1),
)

/** Top-left red, top-right green, bottom-left blue, bottom-right white, opaque, as RGBA bytes. */
private val QUADRANT_COLOURS: List<IntArray> = listOf(
    intArrayOf(255, 0, 0, 255),
    intArrayOf(0, 255, 0, 255),
    intArrayOf(0, 0, 255, 255),
    intArrayOf(255, 255, 255, 255),
)

/** A 2x2 PNG of [QUADRANT_COLOURS], in reading order. */
private val QUADRANT_PNG: ByteArray = rgbaPng(
    width = 2,
    height = 2,
    rgba = QUADRANT_COLOURS.flatMap { it.toList() }.map { it.toByte() }.toByteArray(),
)

/**
 * A 4x4 ETC2 RGBA8 EAC container (ADR 0084): alpha 128 over a flat (2, 4, 6) colour block, which reads
 * (36, 70, 104, 128), already premultiplied -- the opacity suite's fixture, rebuilt here.
 */
private val ETC2_CONTAINER: ByteArray =
    "RET2".encodeToByteArray() + byteArrayOf(1, 3, 1, 0) + byteArrayOf(4, 0, 0, 0, 4, 0, 0, 0) +
        byteArrayOf(-128, 0, 0, 0, 0, 0, 0, 0) +
        byteArrayOf(0x22, 0x44, 0x66, 0, 0, 0, 0, 0)

/** A truecolour-with-alpha PNG of [rgba], one stored (uncompressed) deflate block. */
private fun rgbaPng(width: Int, height: Int, rgba: ByteArray): ByteArray {
    val raw = ArrayList<Byte>()
    for (row in 0 until height) {
        raw += 0
        for (column in 0 until width * 4) raw += rgba[row * width * 4 + column]
    }
    val payload = raw.toByteArray()
    val zlib = ArrayList<Byte>()
    zlib += 0x78
    zlib += 0x01
    zlib += 1
    zlib += (payload.size and 0xff).toByte()
    zlib += (payload.size ushr 8).toByte()
    zlib += (payload.size.inv() and 0xff).toByte()
    zlib += ((payload.size.inv() ushr 8) and 0xff).toByte()
    payload.forEach { zlib += it }
    var a = 1L
    var b = 0L
    for (byte in payload) {
        a = (a + (byte.toInt() and 0xff)) % 65521
        b = (b + a) % 65521
    }
    val adler = (b shl 16) or a
    for (shift in listOf(24, 16, 8, 0)) zlib += (adler ushr shift).toByte()

    val header = ByteArray(13)
    writeBigEndian(header, 0, width.toLong())
    writeBigEndian(header, 4, height.toLong())
    header[8] = 8
    header[9] = 6
    return byteArrayOf(-0x77, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        pngChunk("IHDR", header) + pngChunk("IDAT", zlib.toByteArray()) + pngChunk("IEND", ByteArray(0))
}

private fun pngChunk(kind: String, payload: ByteArray): ByteArray {
    val kindBytes = kind.encodeToByteArray()
    val length = ByteArray(4).also { writeBigEndian(it, 0, payload.size.toLong()) }
    val crc = ByteArray(4).also { writeBigEndian(it, 0, crc32(kindBytes + payload)) }
    return length + kindBytes + payload + crc
}

private fun writeBigEndian(target: ByteArray, offset: Int, value: Long) {
    for (index in 0 until 4) target[offset + index] = (value ushr (24 - 8 * index)).toByte()
}

private fun crc32(bytes: ByteArray): Long {
    var crc = 0xffffffffL
    for (byte in bytes) {
        crc = crc xor (byte.toLong() and 0xff)
        repeat(8) { crc = if (crc and 1L != 0L) (crc ushr 1) xor 0xedb88320L else crc ushr 1 }
    }
    return crc xor 0xffffffffL
}
