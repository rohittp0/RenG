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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RendererFactoryTest {

    // ---- Setup failures -------------------------------------------------------------------------

    @Test
    fun setupWithoutACurrentContextIsATypedFailure() {
        val failure = assertFailsWith<RenGException> {
            createRenderer(testConfiguration(), validGlesBinding(), RenderContextProbe { null })
        }
        assertEquals(RenGErrorCode.NO_CURRENT_RENDER_CONTEXT, failure.code)
    }

    @Test
    fun aSetupFailureWithNoCurrentContextCarriesNoDriverTextAtAll() {
        val binding = validGlesBinding()
        val failure = assertFailsWith<RenGException> {
            createRenderer(testConfiguration(), binding, RenderContextProbe { null })
        }
        assertNoDriverText(failure)
    }

    /**
     * The stronger redaction proof: a failure reached AFTER a context was genuinely adopted, so
     * [com.rohittp.reng.internal.gl.RenderContextProfile] is populated with real vendor/renderer
     * strings and the shader compiler observed a real (fake, but driver-shaped) info log — exactly
     * the situation the design spec calls out: "a factory is where a developer most wants to echo
     * the driver string back for debugging." Setting [RecordingGlBinding.compileStatus] to failure
     * fails both of RenG's own internal pipelines' shader compilation, well after `GL_VENDOR` /
     * `GL_RENDERER` / `GL_SHADING_LANGUAGE_VERSION` were read into the adopted profile.
     */
    @Test
    fun aShaderCompileFailureDuringSetupCarriesNoDriverTextAtAll() {
        val binding = validGlesBinding().apply {
            compileStatus = 0
            shaderInfoLog = "ERROR: 0:1: Mesa/llvmpipe internal compiler diagnostic XK-9182"
        }
        val failure = assertFailsWith<RenGException> {
            createRenderer(testConfiguration(), binding, fixedProbe())
        }
        assertEquals(RenGErrorCode.GPU_OPERATION_FAILED, failure.code)
        assertNoDriverText(failure)
    }

    private fun assertNoDriverText(failure: RenGException) {
        val rendered = failure.toString() + failure.message.orEmpty() +
            failure.diagnostics.joinToString { it.toString() }
        assertFalse(rendered.contains("Mesa", ignoreCase = true))
        assertFalse(rendered.contains("llvmpipe", ignoreCase = true))
        assertFalse(rendered.contains("Apple", ignoreCase = true))
        assertFalse(rendered.contains("XK-9182"))
        assertFalse(rendered.contains("GL_", ignoreCase = false))
    }

    // ---- Purity: no consumer exchange at setup ---------------------------------------------------

    @Test
    fun setupPerformsNoConsumerExchangeAtAll() {
        val transport = CountingTransport()
        val configuration = testConfiguration(
            transport = transport,
            basemapStyle = ResourceLocator("https://example.invalid/style.json"),
        )
        createRenderer(configuration, validGlesBinding(), fixedProbe())
        assertEquals(0, transport.executeCalls, "the style locator is recorded at setup and acquired at first prepare()")
    }

    @Test
    fun aPrepareCallWithNoStickersAndNoConfiguredBasemapPerformsNoConsumerExchangeEither() = runTest {
        val transport = CountingTransport()
        val renderer = createRenderer(testConfiguration(transport = transport), validGlesBinding(), fixedProbe())
        renderer.prepare(FramePlan(frameIndex = 0L, camera = testCamera()))
        assertEquals(0, transport.executeCalls)
    }

    // ---- drawBasemap warn-and-degrade -------------------------------------------------------------

    @Test
    fun requestingABasemapWithNoConfiguredStyleWarnsOnceAndKeepsDrawing() = runTest {
        val sink = RecordingDiagnosticSink()
        val renderer = createRenderer(
            testConfiguration(basemapStyle = null, diagnosticSink = sink),
            validGlesBinding(),
            fixedProbe(),
        )
        val frame = renderer.prepare(FramePlan(frameIndex = 0L, camera = testCamera(), drawBasemap = true))
        val target = renderer.mintRenderTarget(FramebufferName(0u))

        repeat(3) { renderer.draw(frame, target) }

        assertEquals(
            1,
            sink.diagnostics.count { it.code == DiagnosticCode.BASEMAP_NOT_CONFIGURED },
            "warn once per renderer, never once per frame",
        )
        assertTrue(sink.diagnostics.all { it.severity == DiagnosticSeverity.WARNING })
    }

    @Test
    fun aConfiguredBasemapStyleNeverWarns() = runTest {
        val sink = RecordingDiagnosticSink()
        val renderer = createRenderer(
            testConfiguration(basemapStyle = ResourceLocator("https://example.invalid/style.json"), diagnosticSink = sink),
            validGlesBinding(),
            fixedProbe(),
        )
        val frame = renderer.prepare(FramePlan(frameIndex = 0L, camera = testCamera(), drawBasemap = true))
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        renderer.draw(frame, target)
        assertTrue(sink.diagnostics.none { it.code == DiagnosticCode.BASEMAP_NOT_CONFIGURED })
    }

    @Test
    fun drawBasemapFalseNeverWarnsEvenWithNoConfiguredStyle() = runTest {
        val sink = RecordingDiagnosticSink()
        val renderer = createRenderer(testConfiguration(diagnosticSink = sink), validGlesBinding(), fixedProbe())
        val frame = renderer.prepare(FramePlan(frameIndex = 0L, camera = testCamera(), drawBasemap = false))
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        renderer.draw(frame, target)
        assertTrue(sink.diagnostics.isEmpty())
    }

    // ---- Leak discipline on partial construction ---------------------------------------------------

    /**
     * Forces both internal pipelines' shader compilation to fail (see
     * [aShaderCompileFailureDuringSetupCarriesNoDriverTextAtAll]) while the offscreen surface, which
     * compiles no shader, succeeds — proving the succeeded allocation is deleted rather than leaked
     * when a later allocation in the same construction fails.
     */
    @Test
    fun aFailedSetupDeletesEveryAllocationThatDidSucceed() {
        val binding = validGlesBinding().apply { compileStatus = 0 }
        assertFailsWith<RenGException> {
            createRenderer(testConfiguration(), binding, fixedProbe())
        }
        assertTrue(binding.log.any { it.startsWith("deleteFramebuffers") }, "the offscreen surface's framebuffer must be deleted")
        assertTrue(binding.log.any { it.startsWith("deleteRenderbuffers") }, "the offscreen surface's renderbuffer must be deleted")
        assertTrue(binding.log.any { it.startsWith("deleteTextures") }, "the offscreen surface's colour texture must be deleted")
    }

    @Test
    fun aSuccessfulSetupDeletesNothingExceptTheOrdinaryPostLinkShaderCleanup() {
        val binding = validGlesBinding()
        createRenderer(testConfiguration(), binding, fixedProbe())
        // deleteShader after a successful link is ordinary GL housekeeping (the linked program keeps
        // its own copy of the compiled stages) -- every OTHER delete call would mean a successful
        // setup destroyed one of its own live allocations.
        assertTrue(
            binding.log.none {
                it.startsWith("delete") && !it.startsWith("deleteShader")
            },
            "a successful setup must not delete any framebuffer, renderbuffer, texture, VAO, buffer, or program",
        )
    }

    // ---- CancelRoute reachability through the public API -------------------------------------------

    /**
     * Proves Task 1's `CancelRoute` fix is reachable through this factory's `prepare()`, not merely
     * through `PreparationDriver` directly: two distinct sticker locators (so `preRegister` cannot
     * merge them into one route) where one route's `Transport` call throws a bare
     * [CancellationException] of its own initiative while the other is still in flight. Before this
     * task, `Renderer` had no concrete implementation anywhere, so this path had no caller at all.
     */
    @Test
    fun cancelRouteDoesNotCrashAMultiStickerPrepareCall() = runTest {
        val cancellingLocator = ResourceLocator("https://example.invalid/first.png")
        val hangingLocator = ResourceLocator("https://example.invalid/second.png")
        val transport = Transport { request ->
            if (request.locator == cancellingLocator) {
                throw CancellationException("adapter cancelled itself")
            }
            TransportResponse(statusCode = 200, body = onePixelPng)
        }
        val renderer = createRenderer(testConfiguration(transport = transport), validGlesBinding(), fixedProbe())
        val plan = FramePlan(
            frameIndex = 0L,
            camera = testCamera(),
            stickers = listOf(
                Sticker(testPlacement(), cancellingLocator),
                Sticker(testPlacement(), hangingLocator),
            ),
        )

        // The meaningful claim is that a typed/cancellation outcome is reached at all, rather than an
        // unhandled `error(...)` crash inside ResourceActionExecutor's CancelRoute branch.
        assertFailsWith<CancellationException> { renderer.prepare(plan) }
    }

    // ---- A real sticker round trips through prepare() and draw() -----------------------------------

    @Test
    fun aStickerRoundTripsThroughPrepareAndDraw() = runTest {
        val transport = CountingTransport()
        val binding = validGlesBinding()
        val renderer = createRenderer(testConfiguration(transport = transport), binding, fixedProbe())
        val plan = FramePlan(
            frameIndex = 0L,
            camera = testCamera(),
            stickers = listOf(Sticker(testPlacement(), ResourceLocator("https://example.invalid/a.png"))),
        )

        val frame = renderer.prepare(plan)
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        binding.log.clear()
        renderer.draw(frame, target)

        assertEquals(1, transport.executeCalls)
        assertTrue(binding.log.any { it.startsWith("drawArrays") }, "a sticker must actually draw")
        assertTrue(binding.log.any { it.startsWith("genTextures") }, "the sticker image must upload a fresh texture")
    }

    // ---- Test doubles and fixtures -----------------------------------------------------------------

    private fun fixedProbe(): RenderContextProbe = RenderContextProbe { RenderContextIdentity(1L) }

    private fun testCamera(): Camera = Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 2.0, bearing = 0.0, pitch = 0.0)

    private fun testPlacement(): Placement = Placement(
        positionMode = AnchoringMode.SCREEN,
        position = Vector3(0.0, 0.0, 0.0),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.0,
    )

    private fun testConfiguration(
        transport: Transport = Transport { error("test transport must not execute") },
        store: Store = NoOpStore(),
        basemapStyle: ResourceLocator? = null,
        diagnosticSink: DiagnosticSink = DiagnosticSink.None,
    ): RendererConfiguration = RendererConfiguration(
        outputPixelSize = OutputPixelSize(64, 64),
        transport = transport,
        store = store,
        basemapStyle = basemapStyle,
        diagnosticSink = diagnosticSink,
    )

    /** A GLES 3.20 context report matching [com.rohittp.reng.internal.gl.GlLifecycleDriverTest]'s own default fixture. */
    private fun validGlesBinding(): RecordingGlBinding = RecordingGlBinding().apply {
        strings[GL_SHADING_LANGUAGE_VERSION] = "OpenGL ES GLSL ES 3.20"
        strings[GL_VERSION] = "OpenGL ES 3.2 Mesa 25.2.8-0ubuntu0.24.04.2"
        strings[GL_RENDERER] = "llvmpipe (LLVM 20.1.2, 256 bits)"
        strings[GL_VENDOR] = "Mesa"
        indexedStrings += listOf("GL_EXT_sRGB_write_control", "GL_OES_texture_float")
        integers[GL_NUM_EXTENSIONS] = intArrayOf(2)
        integers[GL_MAX_TEXTURE_SIZE] = intArrayOf(16384)
        integers[GL_MAX_COLOR_ATTACHMENTS] = intArrayOf(8)
        integers[GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS] = intArrayOf(192)
    }
}

private class NoOpStore : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? = null

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {}
}

/** Counts every [Transport.execute] call and answers a valid one-pixel PNG. */
private class CountingTransport : Transport {
    var executeCalls: Int = 0
        private set

    override suspend fun execute(request: TransportRequest): TransportResponse {
        executeCalls += 1
        return TransportResponse(statusCode = 200, body = onePixelPng)
    }
}

private class RecordingDiagnosticSink : DiagnosticSink {
    private val recorded: MutableList<Diagnostic> = mutableListOf()

    val diagnostics: List<Diagnostic> get() = ArrayList(recorded)

    override fun emit(diagnostic: Diagnostic) {
        recorded += diagnostic
    }
}

/** A minimal, well-formed 2x2 RGBA PNG, base64-encoded (the same fixture used across this cycle's tests). */
private val onePixelPng: ByteArray = kotlin.io.encoding.Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mPgEpHTMLJhcAuISsmrAAAPGAMNubnoZAAAAABJRU5ErkJggg==",
)
