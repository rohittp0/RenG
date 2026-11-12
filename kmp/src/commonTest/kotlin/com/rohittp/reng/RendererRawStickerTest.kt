package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_MAX_COLOR_ATTACHMENTS
import com.rohittp.reng.internal.gl.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS
import com.rohittp.reng.internal.gl.GL_MAX_TEXTURE_SIZE
import com.rohittp.reng.internal.gl.GL_NUM_EXTENSIONS
import com.rohittp.reng.internal.gl.GL_RENDERER
import com.rohittp.reng.internal.gl.GL_SHADING_LANGUAGE_VERSION
import com.rohittp.reng.internal.gl.GL_VENDOR
import com.rohittp.reng.internal.gl.GL_VERSION
import com.rohittp.reng.internal.gl.RecordingGlBinding
import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.gl.STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME
import com.rohittp.reng.internal.gl.STICKER_TEXTURE_UNIFORM_NAME
import com.rohittp.reng.internal.gl.composeScreenModelViewProjection
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.image.rawRgbaContainer
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.resolvePlacement
import com.rohittp.reng.internal.projection.resolveMercatorCamera
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR 0092 end to end: a raw RGBA sticker is admitted at `prepare()` and uploaded from its stored bytes.
 * ADR 0093 end to end: a transient sticker's image leaves the CPU cache when its frame closes and its
 * texture at the next draw, without disturbing a second draw of a frame still open.
 */
class RendererRawStickerTest {
    private val pixels = ByteArray(3 * 2 * 4) { (it * 11 + 3).toByte() }

    @Test
    fun aRawStickerUploadsItsTexelsAsTheyAreAndSizesTheQuadByItsExtent() = runTest {
        val transport = RecordingTransport { rawRgbaContainer(3, 2, pixels, premultiplied = true) }
        val binding = binding().apply {
            declaredNames = mapOf(STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME to MVP, STICKER_TEXTURE_UNIFORM_NAME to 42)
        }
        val renderer = createRenderer(configuration(transport), binding, probe())

        renderer.prepare(plan(0L, sticker(A))).use { frame ->
            binding.log.clear()
            renderer.draw(frame, target(renderer))
        }

        assertEquals(1, binding.log.count { it == "texImage2D(0xDE1,0,0x8058,3,2,0,0x1908,0x1401)" })
        assertEquals(pixels.toList(), binding.lastTexImageBytes())
        assertTrue(binding.log.none { it.startsWith("generateMipmap") || it.startsWith("compressedTexImage2D") })
        val camera = (resolveMercatorCamera(camera(), OUTPUT) as SpatialOutcome.Success).value
        val resolved = (resolvePlacement(placement(), camera) as SpatialOutcome.Success).value
        assertContentEquals(
            composeScreenModelViewProjection(OUTPUT, resolved, DoubleVector3(3.0, 2.0, 1.0)),
            binding.uniformMatrix4fvValues.getValue(MVP),
        )
        val usage = renderer.queryResources(ResourceSelector.ByClass(ResourceClass.STICKER_IMAGE)).entries.single().usage
        assertEquals(0L, usage.decodedCpuBytes, "a raw container is admitted, never decoded or attached")
        assertEquals(
            "image/x-reng-etc2, image/x-reng-rgba, image/png",
            transport.requests.single().metadata.accept,
        )
        renderer.close()
    }

    @Test
    fun aMalformedRawContainerFailsAsAnUndecodableStickerImage() = runTest {
        val bytes = rawRgbaContainer(3, 2, pixels)
        val renderer = createRenderer(configuration(RecordingTransport { bytes.copyOf(bytes.size - 1) }), binding(), probe())
        val failure = assertFailsWith<RenGException> { renderer.prepare(plan(0L, sticker(A))) }
        assertEquals(RenGErrorCode.RESOURCE_DECODE_FAILED, failure.code)
        renderer.close()
    }

    @Test
    fun aTransientStickersBytesLeaveWhenItsFrameClosesWhileAPersistentOnesStay() = runTest {
        val binding = binding()
        val renderer = createRenderer(configuration(RecordingTransport { rawRgbaContainer(3, 2, pixels) }), binding, probe())

        renderer.prepare(plan(0L, sticker(A, transient = true), sticker(B))).use { frame ->
            renderer.draw(frame, target(renderer))
            assertEquals(2, stickerEntries(renderer).size, "an open frame keeps both")
        }

        // A's texture outlives its bytes until the next draw, so only B still has bytes here.
        assertEquals(listOf(B), stickerEntries(renderer).filter { it.usage.rawBytes > 0L }.map { it.key.locatorOf() })
        val residency = renderer.queryResources(ResourceSelector.All).cpuResidency
        assertEquals(1L, residency.transientDroppedCount)
        assertEquals(16L + 24L, residency.transientDroppedBytes)
        assertEquals(0L, residency.evictedKeyCount)
        renderer.close()
    }

    @Test
    fun aTransientTextureIsDeletedAtTheNextDrawAndNotBefore() = runTest {
        val binding = binding()
        val renderer = createRenderer(configuration(RecordingTransport { rawRgbaContainer(3, 2, pixels) }), binding, probe())

        renderer.prepare(plan(0L, sticker(A, transient = true))).use { frame ->
            renderer.draw(frame, target(renderer))
        }
        val first = binding.stickerTextureNames().single()
        assertTrue(first !in binding.deletedNames, "closing a frame deletes nothing: it may be off the GL thread")

        renderer.prepare(plan(1L, sticker(B, transient = true))).use { frame ->
            binding.log.clear()
            renderer.draw(frame, target(renderer))
        }
        assertTrue(first in binding.deletedNames, "the next draw deletes the closed frame's texture")
        val deleteIndex = binding.log.indexOf("deleteTextures(1)")
        val uploadIndex = binding.log.indexOfFirst { it.startsWith("texImage2D(0xDE1,0,0x8058,3,2") }
        assertTrue(deleteIndex in 0 until uploadIndex, "deleted before this draw uploads anything")
        assertTrue(binding.stickerTextureNames().single() !in binding.deletedNames)
        renderer.close()
    }

    @Test
    fun theSameTransientImageTwiceInAnOpenFrameIsUploadedOnceAndKeptAcrossDraws() = runTest {
        val binding = binding()
        val transport = RecordingTransport { rawRgbaContainer(3, 2, pixels) }
        val renderer = createRenderer(configuration(transport), binding, probe())

        val frame = renderer.prepare(plan(0L, sticker(A, transient = true), sticker(A, transient = true)))
        binding.log.clear()
        renderer.draw(frame, target(renderer))
        renderer.draw(frame, target(renderer))
        assertEquals(1, binding.log.count { it.startsWith("texImage2D(0xDE1,0,0x8058,3,2") }, binding.log.toString())
        assertTrue(binding.stickerTextureNames().none { it in binding.deletedNames }, "an open frame's texture stays")
        assertEquals(listOf(A), stickerEntries(renderer).map { it.key.locatorOf() })

        frame.close()
        assertEquals(listOf(0L), stickerEntries(renderer).map { it.usage.rawBytes }, "only the texture is left")
        renderer.prepare(plan(1L)).use { empty -> renderer.draw(empty, target(renderer)) }
        assertTrue(stickerEntries(renderer).isEmpty(), "and the next draw deletes it")
        assertEquals(1, transport.requests.size)
        renderer.close()
    }

    @Test
    fun aLocatorAlsoUsedNonTransientlyKeepsTheLruBehaviour() = runTest {
        val binding = binding()
        val renderer = createRenderer(configuration(RecordingTransport { rawRgbaContainer(3, 2, pixels) }), binding, probe())

        renderer.prepare(plan(0L, sticker(A, transient = true), sticker(A))).use { frame ->
            renderer.draw(frame, target(renderer))
        }
        renderer.prepare(plan(1L)).use { frame -> renderer.draw(frame, target(renderer)) }

        assertEquals(listOf(A), stickerEntries(renderer).map { it.key.locatorOf() })
        assertTrue(binding.stickerTextureNames().none { it in binding.deletedNames })
        assertEquals(0L, renderer.queryResources(ResourceSelector.All).cpuResidency.transientDroppedCount)
        renderer.close()
    }

    // ---- Fixtures -----------------------------------------------------------------------------

    private class RecordingTransport(private val body: () -> ByteArray) : Transport {
        val requests = mutableListOf<TransportRequest>()

        override suspend fun execute(request: TransportRequest): TransportResponse {
            requests += request
            return TransportResponse(
                statusCode = 200,
                body = body(),
                metadata = TransportResponseMetadata(contentType = "image/x-reng-rgba"),
            )
        }
    }

    private class NoOpStore : Store {
        override suspend fun read(key: RawResourceKey): StoredRawResource? = null

        override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {}
    }

    private fun ResourceKey.locatorOf(): String? = listOf(A, B).singleOrNull {
        ResourceKeyDeriver().external(ResourceClass.STICKER_IMAGE, ResourceLocator(it)).key == this
    }

    private fun stickerEntries(renderer: Renderer): List<ResourceReportEntry> =
        renderer.queryResources(ResourceSelector.ByClass(ResourceClass.STICKER_IMAGE)).entries

    private fun configuration(transport: Transport) = RendererConfiguration(
        outputPixelSize = OUTPUT,
        transport = transport,
        store = NoOpStore(),
        basemapStyle = null,
    )

    private fun probe(): RenderContextProbe = RenderContextProbe { RenderContextIdentity(1L) }

    private fun target(renderer: Renderer) = renderer.mintRenderTarget(FramebufferName(0u))

    private fun camera(): Camera = Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 2.0, bearing = 0.0, pitch = 0.0)

    private fun placement(): Placement = Placement(
        positionMode = AnchoringMode.SCREEN,
        position = Vector3(32.0, 32.0, 0.0),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.0,
    )

    private fun sticker(locator: String, transient: Boolean = false) =
        Sticker(placement(), ResourceLocator(locator), transient = transient)

    private fun plan(frameIndex: Long, vararg stickers: Sticker): FramePlan =
        FramePlan(frameIndex = frameIndex, camera = camera(), stickers = stickers.toList())

    /** Every texture name bound right after a 3x2 sticker's level-zero allocation. */
    private fun RecordingGlBinding.stickerTextureNames(): List<Int> = log.indices
        .filter { log[it].startsWith("texImage2D(0xDE1,0,0x8058,3,2") }
        .map { index ->
            val bind = log.subList(0, index).last { it.startsWith("bindTexture(0xDE1,") }
            bind.removePrefix("bindTexture(0xDE1,").removeSuffix(")").toInt()
        }

    /** An ES 3.2 context. */
    private fun binding(): RecordingGlBinding = RecordingGlBinding().apply {
        integers[GL_MAX_TEXTURE_SIZE] = intArrayOf(16384)
        integers[GL_MAX_COLOR_ATTACHMENTS] = intArrayOf(8)
        integers[GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS] = intArrayOf(32)
        strings[GL_SHADING_LANGUAGE_VERSION] = "OpenGL ES GLSL ES 3.20"
        strings[GL_VERSION] = "OpenGL ES 3.2 Mesa 25.2.8"
        strings[GL_RENDERER] = "llvmpipe"
        strings[GL_VENDOR] = "Mesa"
        integers[GL_NUM_EXTENSIONS] = intArrayOf(0)
    }

    private companion object {
        const val A = "https://example.invalid/frame-a.rrgb"
        const val B = "https://example.invalid/frame-b.rrgb"
        const val MVP = 41
        val OUTPUT = OutputPixelSize(64, 64)
    }
}
