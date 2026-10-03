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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

internal const val STICKER_OPACITY_READBACK_PIXELS: Int = 32

internal fun runStickerOpacityReadbackSuite(
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
                    ),
                )
                val expected = expectedStickerPixel(case.opacity, case.blend)
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
                scale = 4.0,
            ),
            image = ResourceLocator(STICKER_OPACITY_URL),
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

private data class StickerOpacityReadbackCase(
    val name: String,
    val opacity: Float,
    val blend: BlendMode,
    val positionMode: AnchoringMode,
)

private fun expectedStickerPixel(opacity: Float, blend: BlendMode): IntArray {
    val sourceAlpha = opacity.toDouble()
    val rgb = STICKER_SOURCE.take(3).mapIndexed { channel, byte ->
        val source = byte / 255.0 * sourceAlpha
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

private fun clearDrawAndReadSticker(
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

    runBlocking { renderer.prepare(plan) }.use { renderer.draw(it, renderTarget) }

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
        check(request.locator.value == STICKER_OPACITY_URL)
        return TransportResponse(
            statusCode = 200,
            body = STICKER_OPACITY_PNG,
            metadata = TransportResponseMetadata(contentType = "image/png"),
        )
    }
}

private class StickerOpacityCollectedFailures {
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

private val STICKER_OPACITY_PNG: ByteArray = byteArrayOf(
    -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82,
    0, 0, 0, 4, 0, 0, 0, 4, 8, 6, 0, 0, 0, -87, -15, -98,
    126, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, 112, 104, 56,
    -16, 31, 25, 51, -112, 46, 0, 0, 58, -121, 39, -15, -79, 107, -113, 10,
    0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126,
)
