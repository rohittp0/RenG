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
import com.rohittp.reng.internal.image.etc2Block
import com.rohittp.reng.internal.image.etc2Container
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
 * ADR 0084 end to end: a sticker whose Transport answers an ETC2 container is admitted at `prepare()`,
 * uploaded as blocks on a context that samples ETC2 and decoded on one that does not, sized by its
 * level-zero pixels, and refused -- with the PNG failure -- everywhere a sticker image is not expected.
 */
class RendererCompressedStickerTest {
    // 6x5, three levels: blocks pad level zero to 8x8, which the drawn quad must not inherit.
    private val chain = etc2Container(formatCode = 1, width = 6, height = 5, levelCount = 3) {
        etc2Block(Triple(63, 60, it + 1))
    }

    @Test
    fun aContextThatSamplesEtc2UploadsTheContainersBlocksAndSizesTheQuadByLevelZero() = runTest {
        val transport = RecordingTransport(chain)
        val binding = glesBinding().apply {
            declaredNames = mapOf(STICKER_MODEL_VIEW_PROJECTION_UNIFORM_NAME to MVP, STICKER_TEXTURE_UNIFORM_NAME to 42)
        }
        val renderer = createRenderer(configuration(transport), binding, probe())

        renderer.prepare(plan(0L, STICKER)).use { frame ->
            binding.log.clear()
            renderer.draw(frame, renderer.mintRenderTarget(FramebufferName(0u)))
        }

        assertEquals(
            listOf(
                "compressedTexImage2D(0xDE1,0,0x9274,6,5,0,32)",
                "compressedTexImage2D(0xDE1,1,0x9274,3,2,0,8)",
                "compressedTexImage2D(0xDE1,2,0x9274,1,1,0,8)",
            ),
            binding.log.filter { it.startsWith("compressedTexImage2D") },
        )
        assertTrue(binding.log.none { it.startsWith("generateMipmap") || it.startsWith("texStorage2D(0xDE1,3") })
        val camera = (resolveMercatorCamera(camera(), OUTPUT) as SpatialOutcome.Success).value
        val resolved = (resolvePlacement(placement(), camera) as SpatialOutcome.Success).value
        assertContentEquals(
            composeScreenModelViewProjection(OUTPUT, resolved, DoubleVector3(6.0, 5.0, 1.0)),
            binding.uniformMatrix4fvValues.getValue(MVP),
        )
        assertEquals(
            "image/x-reng-etc2, image/png",
            transport.requests.single { it.resourceClass == ResourceClass.STICKER_IMAGE }.metadata.accept,
        )
        renderer.close()
    }

    @Test
    fun aContextThatCannotDecodesEveryLevelIntoRgba8AndChargesWhatTheGpuHolds() = runTest {
        val binding = desktopBinding()
        val renderer = createRenderer(configuration(RecordingTransport(chain)), binding, probe())

        renderer.prepare(plan(0L, STICKER)).use { frame ->
            binding.log.clear()
            renderer.draw(frame, renderer.mintRenderTarget(FramebufferName(0u)))
        }

        assertTrue(binding.log.none { it.startsWith("compressedTexImage2D") || it.startsWith("generateMipmap") })
        assertTrue("texStorage2D(0xDE1,3,0x8058,6,5)" in binding.log, binding.log.toString())
        assertEquals(3, binding.log.count { it.startsWith("texSubImage2D(0xDE1,") })
        val usage = renderer.queryResources(ResourceSelector.ByClass(ResourceClass.STICKER_IMAGE)).entries.single().usage
        assertEquals(chain.size.toLong(), usage.rawBytes)
        assertEquals(0L, usage.decodedCpuBytes, "a container is admitted, never decoded on the CPU side")
        renderer.close()
    }

    @Test
    fun aMalformedContainerFailsAsAnUndecodableStickerImage() = runTest {
        val renderer = createRenderer(
            configuration(RecordingTransport(chain.copyOf(chain.size - 1))),
            glesBinding(),
            probe(),
        )
        val failure = assertFailsWith<RenGException> { renderer.prepare(plan(0L, STICKER)) }
        assertEquals(RenGErrorCode.RESOURCE_DECODE_FAILED, failure.code)
        renderer.close()
    }

    @Test
    fun aBackdropOrAGeometryTextureRefusesAContainerAsItRefusesAnyOtherNonPng() = runTest {
        val backdrop = FramePlan(frameIndex = 0L, camera = camera(), backdrop = Backdrop.Pattern(ResourceLocator(STICKER)))
        val geometry = FramePlan(
            frameIndex = 0L,
            camera = camera(),
            geometries = listOf(
                Geometry(
                    topLeft = Vector3(1.0, -1.0, 0.0),
                    bottomRight = Vector3(-1.0, 1.0, 0.0),
                    shaderPair = ShaderPair(GEOMETRY_VERTEX, GEOMETRY_FRAGMENT),
                    textures = mapOf("uData" to ResourceLocator(STICKER)),
                ),
            ),
        )
        listOf(backdrop to ResourceClass.BACKDROP_IMAGE, geometry to ResourceClass.MODEL_TEXTURE)
            .forEach { (plan, resourceClass) ->
                val renderer = createRenderer(configuration(RecordingTransport(chain)), glesBinding(), probe())
                val failure = assertFailsWith<RenGException> { renderer.prepare(plan) }
                assertEquals(RenGErrorCode.RESOURCE_DECODE_FAILED, failure.code, resourceClass.name)
                assertEquals(resourceClass, failure.diagnostics.single().resourceClass)
                renderer.close()
            }
    }

    // ---- Fixtures -----------------------------------------------------------------------------

    private class RecordingTransport(private val body: ByteArray) : Transport {
        val requests = mutableListOf<TransportRequest>()

        override suspend fun execute(request: TransportRequest): TransportResponse {
            requests += request
            return TransportResponse(
                statusCode = 200,
                body = body,
                metadata = TransportResponseMetadata(contentType = "image/x-reng-etc2"),
            )
        }
    }

    private class NoOpStore : Store {
        override suspend fun read(key: RawResourceKey): StoredRawResource? = null

        override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {}
    }

    private fun configuration(transport: Transport) = RendererConfiguration(
        outputPixelSize = OUTPUT,
        transport = transport,
        store = NoOpStore(),
        basemapStyle = null,
    )

    private fun probe(): RenderContextProbe = RenderContextProbe { RenderContextIdentity(1L) }

    private fun camera(): Camera = Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 2.0, bearing = 0.0, pitch = 0.0)

    private fun placement(): Placement = Placement(
        positionMode = AnchoringMode.SCREEN,
        position = Vector3(32.0, 32.0, 0.0),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.0,
    )

    private fun plan(frameIndex: Long, locator: String): FramePlan = FramePlan(
        frameIndex = frameIndex,
        camera = camera(),
        stickers = listOf(Sticker(placement(), ResourceLocator(locator))),
    )

    private fun RecordingGlBinding.limits() = apply {
        integers[GL_MAX_TEXTURE_SIZE] = intArrayOf(16384)
        integers[GL_MAX_COLOR_ATTACHMENTS] = intArrayOf(8)
        integers[GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS] = intArrayOf(32)
    }

    /** An ES 3.2 context: every ES 3.0 context samples ETC2. */
    private fun glesBinding(): RecordingGlBinding = RecordingGlBinding().limits().apply {
        strings[GL_SHADING_LANGUAGE_VERSION] = "OpenGL ES GLSL ES 3.20"
        strings[GL_VERSION] = "OpenGL ES 3.2 Mesa 25.2.8"
        strings[GL_RENDERER] = "llvmpipe"
        strings[GL_VENDOR] = "Mesa"
        integers[GL_NUM_EXTENSIONS] = intArrayOf(0)
    }

    /** Apple's desktop GL 4.1: no `GL_ARB_ES3_compatibility`, so no ETC2. */
    private fun desktopBinding(): RecordingGlBinding = RecordingGlBinding().limits().apply {
        strings[GL_SHADING_LANGUAGE_VERSION] = "4.10"
        strings[GL_VERSION] = "4.1 Metal - 90.5"
        strings[GL_RENDERER] = "Apple M3 Max"
        strings[GL_VENDOR] = "Apple"
        integers[GL_NUM_EXTENSIONS] = intArrayOf(0)
    }

    private companion object {
        const val STICKER = "https://example.invalid/frame-0.reng-etc2"
        const val MVP = 41
        val OUTPUT = OutputPixelSize(64, 64)

        const val GEOMETRY_VERTEX = "#version 300 es\nin vec3 aPosition;\nvoid main() { gl_Position = vec4(aPosition, 1.0); }\n"
        const val GEOMETRY_FRAGMENT = "#version 300 es\nprecision highp float;\nuniform sampler2D uData;\n" +
            "out vec4 colour;\nvoid main() { colour = texture(uData, vec2(0.5)); }\n"
    }
}
