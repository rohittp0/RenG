package com.rohittp.reng

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
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.gl.ShaderDialect
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sinh
import kotlin.io.encoding.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

internal const val BLOOM_READBACK_PIXELS: Int = 128

internal fun runBloomReadbackSuite(
    binding: GlBinding,
    probe: RenderContextProbe,
    dialect: ShaderDialect,
) {
    val target = createBloomReadbackTarget(binding)
    val renderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(BLOOM_READBACK_PIXELS, BLOOM_READBACK_PIXELS),
            transport = BloomReadbackTransport(),
            store = RecordingStyleStore(),
            basemapStyle = null,
        ),
        binding,
        probe,
    )
    val failures = BloomCollectedFailures()
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.framebuffer.toUInt()))
        val nullBloomEmissive = clearDrawAndReadBloom(
            binding, renderer, renderTarget, target.framebuffer,
            bloomPlan(frameIndex = 0L, bloom = null, emissive = true),
        )
        val nullBloomOrdinary = clearDrawAndReadBloom(
            binding, renderer, renderTarget, target.framebuffer,
            bloomPlan(frameIndex = 1L, bloom = null, emissive = false),
        )
        val emptyBloom = clearDrawAndReadBloom(
            binding, renderer, renderTarget, target.framebuffer,
            bloomPlan(frameIndex = 2L, bloom = Bloom(), emissive = false),
        )
        val activeBloom = clearDrawAndReadBloom(
            binding, renderer, renderTarget, target.framebuffer,
            bloomPlan(frameIndex = 3L, bloom = Bloom(), emissive = true),
        )
        val suppressedBloom = clearDrawAndReadBloom(
            binding, renderer, renderTarget, target.framebuffer,
            bloomPlan(
                frameIndex = 4L,
                bloom = Bloom(insideSuppression = 1f),
                emissive = true,
            ),
        )
        val uniformSuppressedBloom = clearDrawAndReadBloom(
            binding, renderer, renderTarget, target.framebuffer,
            bloomPlan(
                frameIndex = 5L,
                bloom = Bloom(),
                emissive = true,
                emissionAwareShader = true,
            ),
        )
        val laterGeometryWithoutBloom = clearDrawAndReadBloom(
            binding, renderer, renderTarget, target.framebuffer,
            bloomPlan(frameIndex = 6L, bloom = null, emissive = true, laterGeometry = true),
        )
        val laterGeometryWithBloom = clearDrawAndReadBloom(
            binding, renderer, renderTarget, target.framebuffer,
            bloomPlan(frameIndex = 7L, bloom = Bloom(), emissive = true, laterGeometry = true),
        )
        val mapStickerWithoutBloom = clearDrawAndReadBloom(
            binding, renderer, renderTarget, target.framebuffer,
            bloomPlan(frameIndex = 8L, bloom = null, emissive = true, mapSticker = true),
        )
        val mapStickerWithBloom = clearDrawAndReadBloom(
            binding, renderer, renderTarget, target.framebuffer,
            bloomPlan(frameIndex = 9L, bloom = Bloom(), emissive = true, mapSticker = true),
        )
        val modelWithoutBloom = clearDrawAndReadBloom(
            binding, renderer, renderTarget, target.framebuffer,
            bloomPlan(frameIndex = 10L, bloom = null, emissive = true, model = true),
        )
        val modelWithBloom = clearDrawAndReadBloom(
            binding, renderer, renderTarget, target.framebuffer,
            bloomPlan(frameIndex = 11L, bloom = Bloom(), emissive = true, model = true),
        )

        failures.run("bloom null ignores emissive") {
            val differentBytes = differingBytes(nullBloomEmissive, nullBloomOrdinary)
            println("RenG bloom readback case 1: dialect=$dialect differingBytes=$differentBytes")
            assertContentEquals(nullBloomOrdinary, nullBloomEmissive)
        }
        failures.run("bloom with no emissive Geometry is a no-op") {
            val differentBytes = differingBytes(nullBloomOrdinary, emptyBloom)
            println("RenG bloom readback case 2: dialect=$dialect differingBytes=$differentBytes")
            assertContentEquals(nullBloomOrdinary, emptyBloom)
        }
        failures.run("active bloom is bright, local, and unflipped") {
            val outsideBase = nullBloomEmissive.pixel(OUTSIDE_X, OUTSIDE_Y)
            val outsideBloom = activeBloom.pixel(OUTSIDE_X, OUTSIDE_Y)
            val outsideIncrease = outsideBloom[0] - outsideBase[0]
            val farDelta = maxChannelDelta(
                activeBloom.pixel(FAR_X, FAR_Y),
                nullBloomEmissive.pixel(FAR_X, FAR_Y),
            )
            val mirroredDelta = maxChannelDelta(
                activeBloom.pixel(OUTSIDE_X, MIRRORED_OUTSIDE_Y),
                nullBloomEmissive.pixel(OUTSIDE_X, MIRRORED_OUTSIDE_Y),
            )
            println(
                "RenG bloom readback case 3: dialect=$dialect " +
                    "outsideBase=${outsideBase.describe()} outsideBloom=${outsideBloom.describe()} " +
                    "outsideIncrease=$outsideIncrease farDelta=$farDelta mirroredDelta=$mirroredDelta " +
                    "noEmitterSha256=${nullBloomOrdinary.sha256()} " +
                    "emitterLastSha256=${activeBloom.sha256()}",
            )
            assertTrue(outsideIncrease > 8, "six pixels outside must brighten by more than 8")
            assertTrue(farDelta <= 2, "bloom must remain local, far delta was $farDelta")
            assertTrue(mirroredDelta <= 2, "the vertically mirrored pixel must remain unchanged")
        }
        failures.run("inside suppression removes bloom from the source interior") {
            val baseline = nullBloomEmissive.pixel(SQUARE_CENTRE_X, SQUARE_CENTRE_Y)
            val suppressed = suppressedBloom.pixel(SQUARE_CENTRE_X, SQUARE_CENTRE_Y)
            val delta = maxChannelDelta(baseline, suppressed)
            println(
                "RenG bloom readback case 4: dialect=$dialect baseline=${baseline.describe()} " +
                    "suppressed=${suppressed.describe()} delta=$delta",
            )
            assertTrue(delta <= 2, "insideSuppression=1 must leave the source centre unchanged")
        }
        failures.run("the documented emission-pass uniform reaches the redraw") {
            val baseline = nullBloomEmissive.pixel(OUTSIDE_X, OUTSIDE_Y)
            val suppressed = uniformSuppressedBloom.pixel(OUTSIDE_X, OUTSIDE_Y)
            val delta = maxChannelDelta(baseline, suppressed)
            println(
                "RenG bloom readback case 5: dialect=$dialect baseline=${baseline.describe()} " +
                    "uniformSuppressed=${suppressed.describe()} delta=$delta",
            )
            assertTrue(delta <= 2, "a zero emission-pass output must produce no outside bloom")
        }
        failures.run("later opaque Geometry covers bloom") {
            val withoutBloom = laterGeometryWithoutBloom.pixel(OUTSIDE_X, OUTSIDE_Y)
            val withBloom = laterGeometryWithBloom.pixel(OUTSIDE_X, OUTSIDE_Y)
            println(
                "RenG bloom readback case 6: dialect=$dialect " +
                    "withoutBloom=${withoutBloom.describe()} withBloom=${withBloom.describe()}",
            )
            assertContentEquals(LATER_GEOMETRY_COLOUR, withoutBloom, "the cover Geometry must be opaque here")
            assertContentEquals(
                withoutBloom,
                withBloom,
                "a Geometry declared after the last emitter must cover its bloom byte-for-byte",
            )
        }
        failures.run("map sticker drawn after an emitter is not brightened") {
            val withoutBloom = mapStickerWithoutBloom.pixel(OUTSIDE_X, OUTSIDE_Y)
            val withBloom = mapStickerWithBloom.pixel(OUTSIDE_X, OUTSIDE_Y)
            println(
                "RenG bloom readback case 7: dialect=$dialect " +
                    "withoutBloom=${withoutBloom.describe()} withBloom=${withBloom.describe()}",
            )
            assertContentEquals(MAP_STICKER_COLOUR, withoutBloom, "the opaque sticker must cover this pixel")
            assertContentEquals(
                withoutBloom,
                withBloom,
                "a map sticker drawn after the emitter must not receive bloom light",
            )
        }
        failures.run("model drawn after an emitter is not brightened") {
            val withoutBloom = modelWithoutBloom.pixel(OUTSIDE_X, OUTSIDE_Y)
            val withBloom = modelWithBloom.pixel(OUTSIDE_X, OUTSIDE_Y)
            val fixtureDelta = maxChannelDelta(BLOCKER_COLOUR, withoutBloom)
            println(
                "RenG bloom readback case 8: dialect=$dialect " +
                    "withoutBloom=${withoutBloom.describe()} withBloom=${withBloom.describe()} " +
                    "fixtureDelta=$fixtureDelta",
            )
            assertTrue(fixtureDelta <= 2, "the opaque model must cover this pixel with its own colour")
            assertContentEquals(
                withoutBloom,
                withBloom,
                "a model drawn after the emitter must not receive bloom light",
            )
        }
    } finally {
        renderer.close()
        binding.deleteFramebuffers(1, intArrayOf(target.framebuffer))
        binding.deleteTextures(1, intArrayOf(target.texture))
    }
    failures.throwIfAny()
}

private fun bloomPlan(
    frameIndex: Long,
    bloom: Bloom?,
    emissive: Boolean,
    emissionAwareShader: Boolean = false,
    laterGeometry: Boolean = false,
    mapSticker: Boolean = false,
    model: Boolean = false,
): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = Camera(0.0, 0.0, BLOOM_ZOOM, 0.0, 0.0),
    drawBasemap = false,
    geometries = buildList {
        add(
            Geometry(
                topLeft = geoAtPixel(FULL_LEFT, FULL_TOP, altitude = 0.0),
                bottomRight = geoAtPixel(FULL_RIGHT, FULL_BOTTOM, altitude = 0.0),
                shaderPair = ShaderPair(BLOOM_VERTEX_SOURCE, GREY_FRAGMENT_SOURCE),
            ),
        )
        add(
            Geometry(
                topLeft = geoAtPixel(SQUARE_LEFT, SQUARE_TOP, altitude = 1.0),
                bottomRight = geoAtPixel(SQUARE_RIGHT, SQUARE_BOTTOM, altitude = 1.0),
                shaderPair = ShaderPair(
                    BLOOM_VERTEX_SOURCE,
                    if (emissionAwareShader) EMISSION_AWARE_WHITE_FRAGMENT_SOURCE else WHITE_FRAGMENT_SOURCE,
                ),
                emissive = emissive,
            ),
        )
        if (laterGeometry) {
            add(
                Geometry(
                    topLeft = geoAtPixel(COVER_LEFT, COVER_TOP, altitude = 2.0),
                    bottomRight = geoAtPixel(COVER_RIGHT, COVER_BOTTOM, altitude = 2.0),
                    shaderPair = ShaderPair(BLOOM_VERTEX_SOURCE, COVER_FRAGMENT_SOURCE),
                ),
            )
        }
    },
    stickers = if (mapSticker) {
        listOf(
            Sticker(
                placement = bloomPlacement(OUTSIDE_X, OUTSIDE_Y, altitude = 3.0, scale = 5.0),
                image = ResourceLocator(BLOOM_STICKER_URL),
            ),
        )
    } else {
        emptyList()
    },
    models = if (model) {
        listOf(
            Model(
                placement = bloomPlacement(
                    BLOOM_READBACK_PIXELS / 2,
                    BLOOM_READBACK_PIXELS / 2,
                    altitude = 3.0,
                    scale = MODEL_SCREEN_SCALE,
                ),
                glb = ResourceLocator(BLOOM_MODEL_URL),
            ),
        )
    } else {
        emptyList()
    },
    bloom = bloom,
)

private fun bloomPlacement(x: Int, y: Int, altitude: Double, scale: Double): Placement = Placement(
    positionMode = AnchoringMode.MAP,
    position = geoAtPixel(x, y, altitude),
    rotationMode = AnchoringMode.SCREEN,
    rotation = Vector3(0.0, 0.0, 0.0),
    scaleMode = AnchoringMode.SCREEN,
    scale = scale,
)

/** Converts one output pixel edge to the geographic coordinate a north-up top-down camera sees. */
private fun geoAtPixel(x: Int, y: Int, altitude: Double): Vector3 {
    val worldSize = 512.0 * (1 shl BLOOM_ZOOM.toInt())
    val worldX = worldSize / 2.0 + (x - BLOOM_READBACK_PIXELS / 2.0)
    val worldY = worldSize / 2.0 - (y - BLOOM_READBACK_PIXELS / 2.0)
    val longitude = worldX / worldSize * 360.0 - 180.0
    val mercator = PI * (1.0 - 2.0 * worldY / worldSize)
    val latitude = atan(sinh(mercator)) * 180.0 / PI
    return Vector3(latitude, longitude, altitude)
}

private fun clearDrawAndReadBloom(
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
    binding.viewport(0, 0, BLOOM_READBACK_PIXELS, BLOOM_READBACK_PIXELS)
    binding.clearColor(0f, 0f, 0f, 0f)
    binding.clear(GL_COLOR_BUFFER_BIT)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)

    val frame = runBlocking { renderer.prepare(plan) }
    try {
        renderer.draw(frame, renderTarget)
    } finally {
        frame.close()
    }

    val pixels = ByteArray(BLOOM_READBACK_PIXELS * BLOOM_READBACK_PIXELS * 4)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
    binding.readPixels(
        0, 0, BLOOM_READBACK_PIXELS, BLOOM_READBACK_PIXELS,
        GL_RGBA, GL_UNSIGNED_BYTE, pixels,
    )
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    return pixels
}

private data class BloomReadbackTarget(val framebuffer: Int, val texture: Int)

private fun createBloomReadbackTarget(binding: GlBinding): BloomReadbackTarget {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, BLOOM_READBACK_PIXELS, BLOOM_READBACK_PIXELS)
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the bloom readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return BloomReadbackTarget(framebuffer, texture)
}

private fun ByteArray.pixel(x: Int, y: Int): IntArray {
    val offset = (y * BLOOM_READBACK_PIXELS + x) * 4
    return IntArray(4) { channel -> this[offset + channel].toInt() and 0xff }
}

private fun differingBytes(first: ByteArray, second: ByteArray): Int =
    first.indices.count { first[it] != second[it] }

private fun maxChannelDelta(first: IntArray, second: IntArray): Int =
    first.indices.maxOf { abs(first[it] - second[it]) }

private class BloomCollectedFailures {
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
            throw AssertionError(failures.joinToString(prefix = "Bloom readback failures:\n", separator = "\n"))
        }
    }
}

private fun IntArray.describe(): String = joinToString(prefix = "(", postfix = ")")

private fun ByteArray.sha256(): String =
    PureKotlinSha256.digest(CanonicalBytes(this)).lowercaseHex

private class BloomReadbackTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        val (body, contentType) = when (request.locator.value) {
            BLOOM_STICKER_URL -> BLOOM_STICKER_PNG to "image/png"
            BLOOM_MODEL_URL -> tallBlockerGlb() to "model/gltf-binary"
            else -> error("the bloom readback fixture serves no body for ${request.resourceClass}")
        }
        return TransportResponse(
            statusCode = 200,
            body = body,
            metadata = TransportResponseMetadata(contentType = contentType),
        )
    }
}

private const val BLOOM_ZOOM: Double = 10.0
private const val FULL_LEFT: Int = -4
private const val FULL_RIGHT: Int = 132
private const val FULL_BOTTOM: Int = -4
private const val FULL_TOP: Int = 132
private const val SQUARE_LEFT: Int = 24
private const val SQUARE_RIGHT: Int = 40
private const val SQUARE_BOTTOM: Int = 88
private const val SQUARE_TOP: Int = 104
private const val SQUARE_CENTRE_X: Int = 32
private const val SQUARE_CENTRE_Y: Int = 96
private const val OUTSIDE_X: Int = 46
private const val OUTSIDE_Y: Int = 96
private const val COVER_LEFT: Int = 42
private const val COVER_RIGHT: Int = 50
private const val COVER_BOTTOM: Int = 92
private const val COVER_TOP: Int = 100
private const val MIRRORED_OUTSIDE_Y: Int = BLOOM_READBACK_PIXELS - 1 - OUTSIDE_Y
private const val FAR_X: Int = 100
private const val FAR_Y: Int = 16
private const val BLOOM_STICKER_URL: String = "https://images.example/bloom-cover.png"
private const val BLOOM_MODEL_URL: String = "https://models.example/bloom-cover.glb"

private val LATER_GEOMETRY_COLOUR: IntArray = intArrayOf(32, 96, 160, 255)
private val MAP_STICKER_COLOUR: IntArray = intArrayOf(255, 128, 0, 255)

/** A 2x2 opaque orange PNG, shared with the basemap suite's independently stated fixture colour. */
private val BLOOM_STICKER_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mP438DwH4QZYAwAWsoJ+ZJB9b8AAAAASUVORK5CYII=",
)

private const val BLOOM_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "in vec3 aPosition;\n" +
        "uniform mat4 uModelViewProjection;\n" +
        "void main() {\n" +
        "    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);\n" +
        "}\n"

private const val GREY_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 rengGeometryColour;\n" +
        "void main() { rengGeometryColour = vec4(0.25, 0.25, 0.25, 1.0); }\n"

private const val WHITE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 rengGeometryColour;\n" +
        "void main() { rengGeometryColour = vec4(1.0); }\n"

private const val COVER_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 rengGeometryColour;\n" +
        "void main() { rengGeometryColour = vec4(32.0 / 255.0, 96.0 / 255.0, 160.0 / 255.0, 1.0); }\n"

private const val EMISSION_AWARE_WHITE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform int rengGeometryEmissionPass;\n" +
        "out vec4 rengGeometryColour;\n" +
        "void main() {\n" +
        "    rengGeometryColour = rengGeometryEmissionPass == 1 ? vec4(0.0) : vec4(1.0);\n" +
        "}\n"
