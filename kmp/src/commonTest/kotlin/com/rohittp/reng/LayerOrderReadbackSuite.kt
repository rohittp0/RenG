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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

internal const val LAYER_ORDER_READBACK_PIXELS: Int = 32

internal fun runLayerOrderReadbackSuite(
    binding: GlBinding,
    probe: RenderContextProbe,
    dialect: ShaderDialect,
) {
    val target = createLayerOrderReadbackTarget(binding)
    val renderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(LAYER_ORDER_READBACK_PIXELS, LAYER_ORDER_READBACK_PIXELS),
            transport = LayerOrderTransport(),
            store = RecordingStyleStore(),
            basemapStyle = null,
        ),
        binding,
        probe,
    )
    val cases = listOf(
        LayerOrderCase(
            "sticker layer 0 under Geometry layer 1",
            stickerLayer = 0,
            geometryLayer = 1,
            expected = GREEN,
        ),
        LayerOrderCase(
            "Geometry layer 0 under sticker layer 1",
            stickerLayer = 1,
            geometryLayer = 0,
            expected = RED,
        ),
        LayerOrderCase(
            "layer zero keeps Geometry before Sticker",
            stickerLayer = 0,
            geometryLayer = 0,
            expected = RED,
        ),
    )
    val failures = ArrayList<String>()
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))
        cases.forEachIndexed { index, case ->
            try {
                val actual = clearDrawAndReadLayerOrder(
                    binding = binding,
                    renderer = renderer,
                    renderTarget = renderTarget,
                    targetFramebuffer = target,
                    plan = layerOrderFramePlan(index.toLong(), case.stickerLayer, case.geometryLayer),
                )
                println(
                    "RenG layer order readback: dialect=$dialect case=${case.name} " +
                        "actual=${actual.contentToString()} expected=${case.expected.contentToString()}",
                )
                assertContentEquals(case.expected, actual, case.name)
            } catch (failure: Throwable) {
                failures += "${case.name}: ${failure.message ?: failure::class.simpleName}"
            }
        }
    } finally {
        renderer.close()
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
    if (failures.isNotEmpty()) {
        throw AssertionError(failures.joinToString(prefix = "Layer order readback failures:\n", separator = "\n"))
    }
}

private fun layerOrderFramePlan(frameIndex: Long, stickerLayer: Int, geometryLayer: Int): FramePlan =
    FramePlan(
        frameIndex = frameIndex,
        camera = Camera(-55.0, -135.0, 4.0, 0.0, 0.0),
        drawBasemap = false,
        drawLabels = false,
        stickers = listOf(
            Sticker(
                placement = Placement(
                    positionMode = AnchoringMode.MAP,
                    position = Vector3(-55.0, -135.0, 0.0),
                    rotationMode = AnchoringMode.SCREEN,
                    rotation = Vector3(0.0, 0.0, 0.0),
                    scaleMode = AnchoringMode.SCREEN,
                    scale = 4.0,
                ),
                image = ResourceLocator(RED_STICKER_URL),
                layer = stickerLayer,
            ),
        ),
        geometries = listOf(
            Geometry(
                topLeft = Vector3(-54.4, -136.0, 0.0),
                bottomRight = Vector3(-55.6, -134.0, 0.0),
                shaderPair = ShaderPair(GREEN_GEOMETRY_VERTEX, GREEN_GEOMETRY_FRAGMENT),
                layer = geometryLayer,
            ),
        ),
    )

private fun clearDrawAndReadLayerOrder(
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
    binding.viewport(0, 0, LAYER_ORDER_READBACK_PIXELS, LAYER_ORDER_READBACK_PIXELS)
    binding.clearColor(0f, 0f, 0f, 0f)
    binding.clear(GL_COLOR_BUFFER_BIT)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)

    runBlocking { renderer.prepare(plan) }.use { renderer.draw(it, renderTarget) }

    val pixel = ByteArray(4)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
    binding.readPixels(
        LAYER_ORDER_READBACK_PIXELS / 2,
        LAYER_ORDER_READBACK_PIXELS / 2,
        1,
        1,
        GL_RGBA,
        GL_UNSIGNED_BYTE,
        pixel,
    )
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    return IntArray(4) { pixel[it].toInt() and 0xff }
}

private fun createLayerOrderReadbackTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(
        GL_TEXTURE_2D,
        1,
        GL_RGBA8,
        LAYER_ORDER_READBACK_PIXELS,
        LAYER_ORDER_READBACK_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the layer-order readback target must be complete",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

private class LayerOrderCase(
    val name: String,
    val stickerLayer: Int,
    val geometryLayer: Int,
    val expected: IntArray,
)

private class LayerOrderTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        require(request.locator.value == RED_STICKER_URL) { "unexpected layer-order sticker locator" }
        return TransportResponse(
            statusCode = 200,
            body = RED_STICKER_PNG,
            metadata = TransportResponseMetadata(
                contentType = "image/png",
                freshUntilEpochMillis = Long.MAX_VALUE,
            ),
        )
    }
}

private val RED: IntArray = intArrayOf(255, 0, 0, 255)
private val GREEN: IntArray = intArrayOf(0, 255, 0, 255)
private const val RED_STICKER_URL: String = "https://layers.example/red.png"

private val GREEN_GEOMETRY_VERTEX: String =
    "#version 300 es\n" +
        "in vec3 aPosition;\n" +
        "uniform mat4 uModelViewProjection;\n" +
        "void main() { gl_Position = uModelViewProjection * vec4(aPosition, 1.0); }\n"

private val GREEN_GEOMETRY_FRAGMENT: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 colour;\n" +
        "void main() { colour = vec4(0.0, 1.0, 0.0, 1.0); }\n"

private val RED_STICKER_PNG: ByteArray = byteArrayOf(
    -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82,
    0, 0, 0, 4, 0, 0, 0, 4, 8, 6, 0, 0, 0, -87, -15, -98,
    126, 0, 0, 0, 18, 73, 68, 65, 84, 120, -100, 99, -8, -49, -64,
    -16, 31, 25, 51, -112, 46, 0, 0, 60, 64, 31, -31, -32, -127, 119, -76,
    0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126,
)
