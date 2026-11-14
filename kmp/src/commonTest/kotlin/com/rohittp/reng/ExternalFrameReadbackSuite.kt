package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_BLEND
import com.rohittp.reng.internal.gl.GL_CLAMP_TO_EDGE
import com.rohittp.reng.internal.gl.GL_COLOR_ATTACHMENT0
import com.rohittp.reng.internal.gl.GL_COLOR_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_DRAW_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_FRAMEBUFFER_COMPLETE
import com.rohittp.reng.internal.gl.GL_NEAREST
import com.rohittp.reng.internal.gl.GL_PACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_READ_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_RGBA
import com.rohittp.reng.internal.gl.GL_RGBA8
import com.rohittp.reng.internal.gl.GL_SCISSOR_TEST
import com.rohittp.reng.internal.gl.GL_TEXTURE0
import com.rohittp.reng.internal.gl.GL_TEXTURE_2D
import com.rohittp.reng.internal.gl.GL_TEXTURE_MAG_FILTER
import com.rohittp.reng.internal.gl.GL_TEXTURE_MIN_FILTER
import com.rohittp.reng.internal.gl.GL_TEXTURE_WRAP_S
import com.rohittp.reng.internal.gl.GL_TEXTURE_WRAP_T
import com.rohittp.reng.internal.gl.GL_UNPACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_UNSIGNED_BYTE
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.image.rawRgbaContainer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

internal const val EXTERNAL_FRAME_READBACK_PIXELS: Int = 32

/** Real-GL coverage for ADR 0095's 2D external texture and every fallback arm. */
internal suspend fun runExternalFrameReadbackSuite(binding: GlBinding, probe: RenderContextProbe) {
    val target = createExternalFrameTarget(binding)
    val renderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(EXTERNAL_FRAME_READBACK_PIXELS, EXTERNAL_FRAME_READBACK_PIXELS),
            transport = ExternalFrameFallbackTransport(),
            store = ExternalFrameNoOpStore(),
            basemapStyle = null,
        ),
        binding,
        probe,
    )
    val solid = TextureExternalSource(binding, solidPixels(EXTERNAL_SOLID))
    val split = TextureExternalSource(binding, splitPixels(EXTERNAL_TOP, EXTERNAL_BOTTOM))
    val notReady = TextureExternalSource(binding, solidPixels(EXTERNAL_SOLID), ready = false)
    renderer.registerExternalSource(SOLID_SOURCE, solid)
    renderer.registerExternalSource(SPLIT_SOURCE, split)
    renderer.registerExternalSource(NULL_SOURCE, notReady)
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.framebuffer.toUInt()))

        assertContentEquals(
            EXTERNAL_SOLID,
            drawExternalFrameAndRead(binding, renderer, renderTarget, target.framebuffer, SOLID_SOURCE, 0L).single(),
            "a TEXTURE_2D latch must replace the prepared fallback image",
        )

        val identity = drawExternalFrameAndRead(
            binding, renderer, renderTarget, target.framebuffer, SPLIT_SOURCE, IDENTITY_FRAME, TWO_READ_Y,
        )
        val flipped = drawExternalFrameAndRead(
            binding, renderer, renderTarget, target.framebuffer, SPLIT_SOURCE, FLIPPED_FRAME, TWO_READ_Y,
        )
        assertFalse(identity[0].contentEquals(identity[1]), "the two source rows must be distinguishable")
        assertContentEquals(identity[1], flipped[0], "v -> 1-v must move the lower sample to the upper sample")
        assertContentEquals(identity[0], flipped[1], "v -> 1-v must move the upper sample to the lower sample")

        assertContentEquals(
            FALLBACK,
            drawExternalFrameAndRead(binding, renderer, renderTarget, target.framebuffer, NULL_SOURCE, 3L).single(),
            "a null latch must draw the prepared image",
        )
        assertContentEquals(
            FALLBACK,
            drawExternalFrameAndRead(binding, renderer, renderTarget, target.framebuffer, "unknown", 4L).single(),
            "an unknown source id must draw the prepared image",
        )
    } finally {
        renderer.close()
        binding.deleteFramebuffers(1, intArrayOf(target.framebuffer))
        binding.deleteTextures(1, intArrayOf(target.texture))
    }
}

private suspend fun drawExternalFrameAndRead(
    binding: GlBinding,
    renderer: Renderer,
    renderTarget: RenderTarget,
    targetFramebuffer: Int,
    sourceId: String,
    frame: Long,
    readY: IntArray = intArrayOf(EXTERNAL_FRAME_READBACK_PIXELS / 2),
): List<IntArray> {
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
    binding.disable(GL_BLEND)
    binding.disable(GL_SCISSOR_TEST)
    binding.colorMask(true, true, true, true)
    binding.viewport(0, 0, EXTERNAL_FRAME_READBACK_PIXELS, EXTERNAL_FRAME_READBACK_PIXELS)
    binding.clearColor(0f, 0f, 0f, 0f)
    binding.clear(GL_COLOR_BUFFER_BIT)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)

    renderer.prepare(externalFramePlan(sourceId, frame)).use {
        renderer.draw(it, renderTarget)
    }

    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
    val pixels = readY.map { y ->
        val pixel = ByteArray(4)
        binding.readPixels(
            EXTERNAL_FRAME_READBACK_PIXELS / 2,
            y,
            1,
            1,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            pixel,
        )
        IntArray(4) { pixel[it].toInt() and 0xff }
    }
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    return pixels
}

private fun externalFramePlan(sourceId: String, frame: Long): FramePlan = FramePlan(
    frameIndex = frame,
    camera = Camera(0.0, 0.0, 2.0, 0.0, 0.0),
    drawBasemap = false,
    drawLabels = false,
    stickers = listOf(
        Sticker(
            placement = Placement(
                positionMode = AnchoringMode.SCREEN,
                position = Vector3(16.0, 16.0, 0.0),
                rotationMode = AnchoringMode.SCREEN,
                rotation = Vector3(0.0, 0.0, 0.0),
                scaleMode = AnchoringMode.SCREEN,
                scale = 8.0,
            ),
            image = ResourceLocator(FALLBACK_URL),
            external = ExternalFrame(sourceId, frame),
        ),
    ),
)

private class TextureExternalSource(
    private val binding: GlBinding,
    private val pixels: ByteArray,
    private val ready: Boolean = true,
) : ExternalFrameSource {
    private var textureName: Int = 0

    override fun attach(textureName: Int) {
        this.textureName = textureName
        binding.activeTexture(GL_TEXTURE0)
        binding.bindTexture(GL_TEXTURE_2D, textureName)
        binding.pixelStorei(GL_UNPACK_ALIGNMENT, 1)
        binding.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    override fun latch(frame: Long): ExternalFrameLatch? {
        if (!ready) return null
        return ExternalFrameLatch(
            target = ExternalTextureTarget.TEXTURE_2D,
            textureName = textureName,
            transform = if (frame == FLIPPED_FRAME) FLIP_V else IDENTITY,
        )
    }

    override fun detach() = Unit
}

private class ExternalFrameFallbackTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        require(request.locator.value == FALLBACK_URL)
        return TransportResponse(
            statusCode = 200,
            body = rawRgbaContainer(2, 2, solidPixels(FALLBACK), premultiplied = true),
            metadata = TransportResponseMetadata(
                contentType = "image/x-reng-rgba",
                freshUntilEpochMillis = Long.MAX_VALUE,
            ),
        )
    }
}

private class ExternalFrameNoOpStore : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? = null

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) = Unit
}

private data class ExternalFrameTarget(val framebuffer: Int, val texture: Int)

private fun createExternalFrameTarget(binding: GlBinding): ExternalFrameTarget {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(
        GL_TEXTURE_2D,
        1,
        GL_RGBA8,
        EXTERNAL_FRAME_READBACK_PIXELS,
        EXTERNAL_FRAME_READBACK_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(GL_FRAMEBUFFER_COMPLETE, binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER))
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return ExternalFrameTarget(framebuffer, texture)
}

private fun solidPixels(colour: IntArray): ByteArray = ByteArray(16) { index -> colour[index % 4].toByte() }

private fun splitPixels(first: IntArray, second: IntArray): ByteArray = ByteArray(16) { index ->
    val colour = if (index < 8) first else second
    colour[index % 4].toByte()
}

private const val FALLBACK_URL: String = "https://stickers.example/external-fallback.reng-rgba"
private const val SOLID_SOURCE: String = "solid"
private const val SPLIT_SOURCE: String = "split"
private const val NULL_SOURCE: String = "null"
private const val IDENTITY_FRAME: Long = 1L
private const val FLIPPED_FRAME: Long = 2L
private val TWO_READ_Y: IntArray = intArrayOf(11, 21)
private val FALLBACK: IntArray = intArrayOf(180, 20, 140, 255)
private val EXTERNAL_SOLID: IntArray = intArrayOf(24, 180, 48, 255)
private val EXTERNAL_TOP: IntArray = intArrayOf(220, 40, 20, 255)
private val EXTERNAL_BOTTOM: IntArray = intArrayOf(20, 60, 220, 255)
private val IDENTITY: FloatArray = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    0f, 0f, 0f, 1f,
)
private val FLIP_V: FloatArray = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, -1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    0f, 1f, 0f, 1f,
)
