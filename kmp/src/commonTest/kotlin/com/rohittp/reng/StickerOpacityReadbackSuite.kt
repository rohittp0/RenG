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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal const val STICKER_OPACITY_READBACK_PIXELS: Int = 32

internal suspend fun runStickerOpacityReadbackSuite(
    binding: GlBinding,
    probe: RenderContextProbe,
    dialect: ShaderDialect,
) {
    val target = createStickerOpacityReadbackTarget(binding)
    val renderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(
                STICKER_OPACITY_READBACK_PIXELS,
                STICKER_OPACITY_READBACK_PIXELS,
            ),
            transport = StickerOpacityTransport(),
            store = RecordingStyleStore(),
            basemapStyle = null,
        ),
        binding,
        probe,
    )
    val failures = StickerOpacityCollectedFailures()
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))
        listOf(
            StickerOpacityReadbackCase(
                "half-opacity over",
                0.5f,
                BlendMode.OVER,
                AnchoringMode.SCREEN,
            ),
            StickerOpacityReadbackCase(
                "screen blend",
                1f,
                BlendMode.SCREEN,
                AnchoringMode.SCREEN,
            ),
            StickerOpacityReadbackCase(
                "map-anchored additive",
                1f,
                BlendMode.ADDITIVE,
                AnchoringMode.MAP,
            ),
            // ADR 0084. Decoded on the CPU where the context cannot sample ETC2 (macOS), uploaded as
            // blocks everywhere else. Its texels arrive premultiplied, so a second multiply would
            // darken the colour this reads.
            StickerOpacityReadbackCase(
                "etc2 premultiplied over",
                1f,
                BlendMode.OVER,
                AnchoringMode.SCREEN,
                url = ETC2_PREMULTIPLIED_URL,
                texel = ETC2_PREMULTIPLIED_TEXEL,
            ),
            // Sixteen texels drawn across 3.2 pixels minify to level 2 and below, which the container
            // paints a colour of its own; sampling level 0, or a generated chain, would read red.
            StickerOpacityReadbackCase(
                "etc2 mip chain minified",
                1f,
                BlendMode.OVER,
                AnchoringMode.SCREEN,
                url = ETC2_MIP_URL,
                texel = ETC2_SMALL_LEVEL_TEXEL,
                scale = 0.2,
            ),
        ).forEachIndexed { frameIndex, case ->
            failures.run(case.name) {
                val actual = clearDrawAndReadSticker(
                    binding,
                    renderer,
                    renderTarget,
                    target,
                    stickerOpacityFramePlan(
                        frameIndex.toLong(),
                        case.opacity,
                        case.blend,
                        case.positionMode,
                        case.url,
                        case.scale,
                    ),
                )
                val expected = expectedStickerPixel(case.texel, case.opacity, case.blend)
                println(
                    "RenG sticker opacity readback: dialect=$dialect case=${case.name} " +
                        "actual=${actual.contentToString()} expected=${expected.contentToString()}",
                )
                assertTrue(
                    actual.indices.all { abs(actual[it] - expected[it]) <= 2 },
                    "${case.name} must read ${expected.contentToString()} within 2, " +
                        "was ${actual.contentToString()}",
                )
            }
        }

        failures.run("geometry texture decodes only on GPU misses") {
            var decodeCalls = 0
            (renderer as RenGRenderer).geometryTextureDecodeObserver = { decodeCalls += 1 }

            val first = clearDrawAndReadSticker(
                binding = binding,
                renderer = renderer,
                renderTarget = renderTarget,
                targetFramebuffer = target,
                plan = geometryTextureFramePlan(frameIndex = 100L),
            )
            assertContentEquals(STICKER_SOURCE, first, "the first Geometry texture upload must draw its texel")
            assertEquals(1, decodeCalls, "the first draw must decode for its GPU upload")

            val second = clearDrawAndReadSticker(
                binding = binding,
                renderer = renderer,
                renderTarget = renderTarget,
                targetFramebuffer = target,
                plan = geometryTextureFramePlan(frameIndex = 101L),
            )
            assertContentEquals(STICKER_SOURCE, second, "the GPU-hit frame must draw the same texel")
            assertEquals(1, decodeCalls, "the second frame's GPU hit must not decode")

            renderer.notifyGpuObjectsGone()
            renderer.adoptCurrentRenderContext()
            val replacementTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))
            val afterContextLoss = clearDrawAndReadSticker(
                binding = binding,
                renderer = renderer,
                renderTarget = replacementTarget,
                targetFramebuffer = target,
                plan = geometryTextureFramePlan(frameIndex = 102L),
            )
            assertContentEquals(
                STICKER_SOURCE,
                afterContextLoss,
                "the first frame after context loss must redraw the decoded texel",
            )
            assertEquals(2, decodeCalls, "context loss must make the next GPU upload decode again")
        }
    } finally {
        renderer.close()
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
    failures.throwIfAny()
}

private fun stickerOpacityFramePlan(
    frameIndex: Long,
    opacity: Float,
    blend: BlendMode,
    positionMode: AnchoringMode,
    url: String,
    scale: Double,
): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = Camera(-55.0, -135.0, 4.0, 0.0, 0.0),
    drawBasemap = false,
    drawLabels = false,
    stickers = listOf(
        Sticker(
            placement = Placement(
                positionMode = positionMode,
                position = when (positionMode) {
                    AnchoringMode.MAP -> Vector3(-55.0, -135.0, 0.0)
                    AnchoringMode.SCREEN -> Vector3(16.0, 16.0, 0.0)
                },
                rotationMode = AnchoringMode.SCREEN,
                rotation = Vector3(0.0, 0.0, 0.0),
                scaleMode = AnchoringMode.SCREEN,
                scale = scale,
            ),
            image = ResourceLocator(url),
            opacity = opacity,
            blend = blend,
        ),
    ),
    geometries = listOf(
        Geometry(
            topLeft = Vector3(-54.4, -136.0, 0.0),
            bottomRight = Vector3(-55.6, -134.0, 0.0),
            shaderPair = ShaderPair(STICKER_BACKGROUND_VERTEX, STICKER_BACKGROUND_FRAGMENT),
        ),
    ),
)

private fun geometryTextureFramePlan(frameIndex: Long): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = Camera(latitude = -55.0, unwrappedLongitude = -135.0, zoom = 4.0, bearing = 0.0, pitch = 0.0),
    drawBasemap = false,
    drawLabels = false,
    geometries = listOf(
        Geometry(
            topLeft = Vector3(x = -54.4, y = -136.0, z = 0.0),
            bottomRight = Vector3(x = -55.6, y = -134.0, z = 0.0),
            shaderPair = ShaderPair(
                vertexSource = GEOMETRY_TEXTURE_VERTEX,
                fragmentSource = GEOMETRY_TEXTURE_FRAGMENT,
            ),
            textures = mapOf("uData" to ResourceLocator(GEOMETRY_TEXTURE_URL)),
        ),
    ),
)

private class StickerOpacityReadbackCase(
    val name: String,
    val opacity: Float,
    val blend: BlendMode,
    val positionMode: AnchoringMode,
    val url: String = STICKER_OPACITY_URL,
    /** The premultiplied texel the sticker samples at the read pixel. */
    val texel: IntArray = STICKER_SOURCE,
    val scale: Double = 4.0,
)

private fun expectedStickerPixel(texel: IntArray, opacity: Float, blend: BlendMode): IntArray {
    val sourceAlpha = texel[3] / 255.0 * opacity
    val rgb = texel.take(3).mapIndexed { channel, byte ->
        val source = byte / 255.0 * opacity
        val destination = STICKER_BACKGROUND[channel] / 255.0
        val result = when (blend) {
            BlendMode.OVER -> source + destination * (1.0 - sourceAlpha)
            BlendMode.ADDITIVE -> source + destination
            BlendMode.MULTIPLY -> source * destination + destination * (1.0 - sourceAlpha)
            BlendMode.SCREEN -> source + destination * (1.0 - source)
        }
        (result * 255.0).roundToInt().coerceIn(0, 255)
    }
    return (rgb + 255).toIntArray()
}

private suspend fun clearDrawAndReadSticker(
    binding: GlBinding,
    renderer: Renderer,
    renderTarget: RenderTarget,
    targetFramebuffer: Int,
    plan: FramePlan,
): IntArray {
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
    binding.disable(GL_BLEND)
    binding.disable(GL_SCISSOR_TEST)
    binding.colorMask(true, true, true, true)
    binding.viewport(0, 0, STICKER_OPACITY_READBACK_PIXELS, STICKER_OPACITY_READBACK_PIXELS)
    binding.clearColor(0f, 0f, 0f, 0f)
    binding.clear(GL_COLOR_BUFFER_BIT)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)

    renderer.prepare(plan).use { renderer.draw(it, renderTarget) }

    val pixel = ByteArray(4)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
    binding.readPixels(
        STICKER_OPACITY_READBACK_PIXELS / 2,
        STICKER_OPACITY_READBACK_PIXELS / 2,
        1,
        1,
        GL_RGBA,
        GL_UNSIGNED_BYTE,
        pixel,
    )
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    return IntArray(4) { pixel[it].toInt() and 0xff }
}

private fun createStickerOpacityReadbackTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(
        GL_TEXTURE_2D,
        1,
        GL_RGBA8,
        STICKER_OPACITY_READBACK_PIXELS,
        STICKER_OPACITY_READBACK_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the sticker opacity readback target must be complete",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

private class StickerOpacityTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        val (body, contentType) = when (request.locator.value) {
            STICKER_OPACITY_URL -> STICKER_OPACITY_PNG to "image/png"
            GEOMETRY_TEXTURE_URL -> STICKER_OPACITY_PNG to "image/png"
            ETC2_PREMULTIPLIED_URL -> ETC2_PREMULTIPLIED to "image/x-reng-etc2"
            ETC2_MIP_URL -> ETC2_MIP_CHAIN to "image/x-reng-etc2"
            else -> error("unexpected sticker locator")
        }
        return TransportResponse(
            statusCode = 200,
            body = body,
            metadata = TransportResponseMetadata(
                contentType = contentType,
                freshUntilEpochMillis = Long.MAX_VALUE,
            ),
        )
    }
}

/**
 * One flat individual-mode ETC2 colour block (ADR 0084): both halves the 4-bit colour
 * `(red, green, blue)`, codeword 0 and every index 0, so every texel is `nibble * 17 + 2`, clamped.
 * Bit 63 is the first byte's top bit; the differential bit 33 and everything below it stay zero.
 */
private fun flatEtc2Block(red: Int, green: Int, blue: Int): ByteArray =
    byteArrayOf((red shl 4 or red).toByte(), (green shl 4 or green).toByte(), (blue shl 4 or blue).toByte(), 0, 0, 0, 0, 0)

/** The 16-byte container header for a one-format container of [levels] levels, little-endian. */
private fun etc2Header(format: Int, levels: Int, width: Int, height: Int): ByteArray =
    "RET2".encodeToByteArray() + byteArrayOf(1, format.toByte(), levels.toByte(), 0) +
        byteArrayOf(width.toByte(), 0, 0, 0, height.toByte(), 0, 0, 0)

// 4x4, RGBA8 EAC: an EAC block of base 128 and multiplier 0 (alpha 128 everywhere) before a flat
// (2, 4, 6) colour block, which reads (36, 70, 104) -- already premultiplied by 128 / 255.
private const val ETC2_PREMULTIPLIED_URL: String = "https://stickers.example/premultiplied.reng-etc2"
private val ETC2_PREMULTIPLIED: ByteArray =
    etc2Header(format = 3, levels = 1, width = 4, height = 4) + byteArrayOf(-128, 0, 0, 0, 0, 0, 0, 0) +
        flatEtc2Block(2, 4, 6)
private val ETC2_PREMULTIPLIED_TEXEL: IntArray = intArrayOf(36, 70, 104, 128)

// 16x16 RGB8, all five levels: 16x16 and 8x8 flat red (255, 2, 2); 4x4, 2x2 and 1x1 flat green (2, 255, 2).
private const val ETC2_MIP_URL: String = "https://stickers.example/mip-chain.reng-etc2"
private val ETC2_MIP_CHAIN: ByteArray = run {
    var bytes = etc2Header(format = 1, levels = 5, width = 16, height = 16)
    listOf(16, 4, 1, 1, 1).forEachIndexed { level, blocks ->
        val block = if (level < 2) flatEtc2Block(15, 0, 0) else flatEtc2Block(0, 15, 0)
        repeat(blocks) { bytes += block }
    }
    bytes
}
private val ETC2_SMALL_LEVEL_TEXEL: IntArray = intArrayOf(2, 255, 2, 255)

private class StickerOpacityCollectedFailures {
    private val failures = ArrayList<String>()

    inline fun run(name: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            failures += "$name: ${failure.message ?: failure::class.simpleName}"
        }
    }

    fun throwIfAny() {
        if (failures.isNotEmpty()) {
            throw AssertionError(
                failures.joinToString(prefix = "Sticker opacity readback failures:\n", separator = "\n"),
            )
        }
    }
}

private const val STICKER_OPACITY_URL: String = "https://stickers.example/opacity.png"
private val STICKER_SOURCE: IntArray = intArrayOf(64, 128, 192, 255)
private val STICKER_BACKGROUND: IntArray = intArrayOf(32, 96, 160, 255)

private val STICKER_BACKGROUND_VERTEX: String =
    "#version 300 es\n" +
        "in vec3 aPosition;\n" +
        "uniform mat4 uModelViewProjection;\n" +
        "void main() { gl_Position = uModelViewProjection * vec4(aPosition, 1.0); }\n"

private val STICKER_BACKGROUND_FRAGMENT: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 colour;\n" +
        "void main() { colour = vec4(0.125490196, 0.376470588, 0.627450980, 1.0); }\n"

private const val GEOMETRY_TEXTURE_URL: String = "https://stickers.example/geometry.png"

private val GEOMETRY_TEXTURE_VERTEX: String =
    "#version 300 es\n" +
        "in vec3 aPosition;\n" +
        "uniform mat4 uModelViewProjection;\n" +
        "void main() { gl_Position = uModelViewProjection * vec4(aPosition, 1.0); }\n"

private val GEOMETRY_TEXTURE_FRAGMENT: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D uData;\n" +
        "out vec4 colour;\n" +
        "void main() { colour = texture(uData, vec2(0.5)); }\n"

private val STICKER_OPACITY_PNG: ByteArray = byteArrayOf(
    -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82,
    0, 0, 0, 4, 0, 0, 0, 4, 8, 6, 0, 0, 0, -87, -15, -98,
    126, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, 112, 104, 56,
    -16, 31, 25, 51, -112, 46, 0, 0, 58, -121, 39, -15, -79, 107, -113, 10,
    0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126,
)
