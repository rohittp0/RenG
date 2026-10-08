package com.rohittp.reng

import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.driver.PreparationDriver
import com.rohittp.reng.internal.driver.RenGClassGateRunner
import com.rohittp.reng.internal.firewall.BasemapEngineHost
import com.rohittp.reng.internal.gl.GL_MAX_COLOR_ATTACHMENTS
import com.rohittp.reng.internal.gl.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS
import com.rohittp.reng.internal.gl.GL_MAX_TEXTURE_SIZE
import com.rohittp.reng.internal.gl.GL_NUM_EXTENSIONS
import com.rohittp.reng.internal.gl.GL_RENDERER
import com.rohittp.reng.internal.gl.GL_SHADING_LANGUAGE_VERSION
import com.rohittp.reng.internal.gl.GL_VENDOR
import com.rohittp.reng.internal.gl.GL_VERSION
import com.rohittp.reng.internal.gl.GlLifecycleDriver
import com.rohittp.reng.internal.gl.GlObjectRegistry
import com.rohittp.reng.internal.gl.GlProgramCache
import com.rohittp.reng.internal.gl.RecordingGlBinding
import com.rohittp.reng.internal.gl.RenderContextAdoption
import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.adoptRenderContext
import com.rohittp.reng.internal.lifecycle.GpuLedger
import com.rohittp.reng.internal.lifecycle.RendererLifecycleSnapshot
import com.rohittp.reng.internal.lifecycle.RendererOwnerState
import com.rohittp.reng.internal.metrics.EngineMetricRecorder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A frame consumes the generation it leased, not whatever is current by the time it decodes.
 *
 * The steady-state shortcut (`PreparationDriver.leaseSteadyStateResident`) leases a resident, fresh,
 * validated sticker *before* the resource operation runs. If something supersedes that key while the
 * operation is still running -- here, during another sticker's Transport call -- the frame must still
 * name, decode and attach to the generation it pinned (`FrameAcquisition.leasedGenerations`), which stays
 * resident as a retired, leased generation until the frame closes.
 *
 * The renderer is assembled here exactly as `createRenderer` assembles it, except that the test keeps
 * the [ResidentCache]: the public API offers no way to install into it mid-preparation (`freeResources`
 * and a nested `prepare` are both refused as `PREPARATION_IN_PROGRESS`).
 */
class RendererLeasedGenerationTest {

    @Test
    fun aFrameUsesTheGenerationItLeasedWhenTheKeyIsSupersededDuringItsOperation() = runTest {
        val residentLocator = ResourceLocator("https://example.invalid/s.png")
        val newLocator = ResourceLocator("https://example.invalid/t.png")
        var supersede: (() -> Unit)? = null
        val transport = Transport { request ->
            if (request.locator == newLocator) supersede?.invoke()
            // Fresh for the whole test, so the second frame's shortcut takes the resident sticker.
            TransportResponse(
                statusCode = 200,
                body = twoByTwoPng,
                metadata = TransportResponseMetadata(freshUntilEpochMillis = Long.MAX_VALUE),
            )
        }
        val cache = ResidentCache(residentByteBudget = ResourceLimits().maximumResidentCpuResourceBytes)
        val renderer = rendererOver(cache, testConfiguration(transport))

        // Frame 1: S becomes resident, decoded, and its PNG gate is remembered as passed (ADR 0060).
        val first = renderer.prepare(plan(0L, residentLocator)) as RenGPreparedFrame
        val firstSticker = first.stickers.single()
        val residentKey = firstSticker.resourceKey
        val generationA = assertNotNull(cache.current(residentKey))
        assertEquals(firstSticker.generationId, generationA.id)
        first.close()
        assertEquals(0, generationA.leaseCount, "nothing leases A between frames")

        // Frame 2: S (shortcut-leased before the operation) plus T, which the operation drives through
        // the Transport. T's fetch supersedes S's key with a different install, B.
        var generationBId: Any? = null
        supersede = {
            supersede = null
            assertEquals(1, generationA.leaseCount, "the shortcut leased A before the operation ran")
            generationBId = cache.install(
                residentKey,
                StoredRawResource(
                    bytes = twoByTwoPng,
                    contentDigest = "b".repeat(64),
                    metadata = StoredRawResourceMetadata(
                        freshUntilEpochMillis = Long.MAX_VALUE,
                        storedAtEpochMillis = 0L,
                    ),
                ),
                decoded = null,
            ).id
        }
        val second = renderer.prepare(plan(1L, residentLocator, newLocator)) as RenGPreparedFrame
        assertNotNull(generationBId, "T's Transport call must have run and superseded S's key")
        assertNotEquals(generationA.id, cache.current(residentKey)?.id, "B is current now")

        val secondSticker = second.stickers.single { it.resourceKey == residentKey }
        assertEquals(generationA.id, secondSticker.generationId, "the frame names the generation it leased")
        assertSame(firstSticker.image, secondSticker.image, "and draws A's pixels, not a decode of B")
        assertEquals(1, generationA.leaseCount, "A stays resident, retired and leased by the frame")
        assertTrue(
            generationA.id in cache.generationIds(ResourceSelector.ByClass(ResourceClass.STICKER_IMAGE))
                .getValue(residentKey),
        )

        second.close()
        assertEquals(0, generationA.leaseCount)
        assertFalse(
            generationA.id in cache.generationIds(ResourceSelector.ByClass(ResourceClass.STICKER_IMAGE))
                .getValue(residentKey),
            "closing the frame releases A, and a retired generation with no lease leaves the cache",
        )
        renderer.close()
    }

    // ---- Fixtures -----------------------------------------------------------------------------------

    /** `createRenderer(configuration, binding, probe)`, with the resident cache supplied by the test. */
    private fun rendererOver(cache: ResidentCache, configuration: RendererConfiguration): Renderer {
        val binding = validGlesBinding()
        val profile = when (val adoption = adoptRenderContext(binding)) {
            is RenderContextAdoption.Adopted -> adoption.profile
            is RenderContextAdoption.Rejected -> error("the fixture context must adopt: ${adoption.failure}")
        }
        val programs = GlProgramCache()
        val glState = when (val result = createInternalGlState(binding, profile, programs, configuration)) {
            is InternalGlStateResult.Created -> result.state
            is InternalGlStateResult.Failed -> error("the fixture GL state must build: ${result.failure}")
        }
        val metricRecorder = EngineMetricRecorder()
        val basemapEngineHost = BasemapEngineHost(
            transport = configuration.transport,
            store = configuration.store,
            cache = cache,
            metricRecorder = metricRecorder,
            maximumConcurrentTileRasterisations = configuration.maximumConcurrentTileRasterisations,
        )
        var clock = 0L
        val preparationDriver = PreparationDriver(
            transport = configuration.transport,
            store = configuration.store,
            cache = cache,
            classGateRunner = RenGClassGateRunner(configuration.resourceLimits),
            basemapEngineHost = basemapEngineHost,
            resourceLimits = configuration.resourceLimits,
            maximumConcurrentOperations = configuration.maximumConcurrentResourceOperations,
            clock = { clock++ },
        )
        val objectRegistry = GlObjectRegistry(
            residentTextureByteBudget = configuration.resourceLimits.maximumResidentGpuTextureBytes,
            residentBufferByteBudget = configuration.resourceLimits.maximumResidentGpuBufferBytes,
            residentContentTextureByteBudget = configuration.resourceLimits.maximumResidentGpuContentTextureBytes,
        )
        val driver = GlLifecycleDriver(
            binding = binding,
            probe = { RenderContextIdentity(1L) },
            registry = objectRegistry,
            programs = programs,
            initialSnapshot = RendererLifecycleSnapshot(
                ownerState = RendererOwnerState.LIVE,
                contextGeneration = 0L,
                preparationActive = false,
                gpuLedger = GpuLedger(hasLiveGpuObjects = true, deferredDeletions = emptyList()),
            ),
            initialContext = RenderContextIdentity(1L),
            initialProfile = profile,
        )
        return RenGRenderer(
            configuration = configuration,
            binding = binding,
            driver = driver,
            preparationDriver = preparationDriver,
            residentCache = cache,
            basemapEngineHost = basemapEngineHost,
            programs = programs,
            glObjectRegistry = objectRegistry,
            metricRecorder = metricRecorder,
            initialGlState = glState,
        )
    }

    private fun plan(frameIndex: Long, vararg locators: ResourceLocator): FramePlan = FramePlan(
        frameIndex = frameIndex,
        camera = Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 2.0, bearing = 0.0, pitch = 0.0),
        stickers = locators.map { Sticker(screenPlacement(), it) },
    )

    private fun screenPlacement(): Placement = Placement(
        positionMode = AnchoringMode.SCREEN,
        position = Vector3(0.0, 0.0, 0.0),
        rotationMode = AnchoringMode.SCREEN,
        rotation = Vector3(0.0, 0.0, 0.0),
        scaleMode = AnchoringMode.SCREEN,
        scale = 1.0,
    )

    private fun testConfiguration(transport: Transport): RendererConfiguration = RendererConfiguration(
        outputPixelSize = OutputPixelSize(64, 64),
        transport = transport,
        store = object : Store {
            override suspend fun read(key: RawResourceKey): StoredRawResource? = null

            override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {}
        },
    )

    /** The same GLES 3.20 report as RendererFactoryTest's fixture. */
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

/** RendererFactoryTest's well-formed 2x2 RGBA PNG. */
private val twoByTwoPng: ByteArray = kotlin.io.encoding.Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mPgEpHTMLJhcAuISsmrAAAPGAMNubnoZAAAAABJRU5ErkJggg==",
)
