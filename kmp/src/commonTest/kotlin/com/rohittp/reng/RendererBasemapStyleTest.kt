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
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The renderer end of the basemap style commit: `prepare()` acquires the configured style through its
 * own driver, derives the routes the Rentile engine will fetch while compiling it, preregisters them on
 * the firewall, and compiles the style through the real engine inside one preparation invocation.
 *
 * Every assertion here is stated in terms of what the **consumer's own adapters** observed, because that
 * is the only surface a caller has: an engine exchange RenG failed to preregister never reaches the
 * consumer at all (it fails closed as `AMBIGUOUS_RESOURCE_ROUTE`), and a style RenG recompiles per frame
 * shows up as the same sprite being fetched again and again.
 */
class RendererBasemapStyleTest {

    @Test
    fun acquiresTheConfiguredStyleAndEverythingCompilingItMakesTheEngineFetch() = runTest {
        val transport = StyleTransport()
        val renderer = styleRenderer(transport)

        renderer.prepare(basemapPlan(frameIndex = 0L))

        assertEquals(
            listOf(STYLE_URL, SPRITE_JSON_URL, SPRITE_IMAGE_URL).sorted(),
            transport.requestedUrls.sorted(),
            "the style is RenG's own resource; the sprite pair is the engine's, reached only through a " +
                "preregistered route",
        )
    }

    /**
     * The defect a single-frame test cannot see. Compilation is bound to the style's **content**, so a
     * second frame over identical bytes reuses it; binding it to a resident generation object instead
     * makes every frame recompile, which re-runs the whole sprite/TileJSON/GeoJSON acquisition through
     * the consumer's transport once per frame forever.
     *
     * The style itself is deliberately not fresh (the transport declares no expiry), so the driver
     * re-resolves it from the transport on the second frame and genuinely emits a second
     * `CompileBasemapStyle`. That is the case where recompilation is reachable at all — a fresh,
     * `RESIDENT`-provenance frame emits no compile action, so it could never observe the defect.
     */
    @Test
    fun compilesOneStyleOnceAcrossFramesRatherThanOncePerFrame() = runTest {
        val transport = StyleTransport()
        val renderer = styleRenderer(transport)

        renderer.prepare(basemapPlan(frameIndex = 0L))
        val spriteRequestsAfterFirstFrame = transport.requestedUrls.count { it == SPRITE_JSON_URL }
        renderer.prepare(basemapPlan(frameIndex = 1L))

        assertEquals(1, spriteRequestsAfterFirstFrame, "one frame compiles the style exactly once")
        assertEquals(
            1,
            transport.requestedUrls.count { it == SPRITE_JSON_URL },
            "a second frame over identical style bytes must reuse the compilation, not redo its whole " +
                "resource acquisition",
        )
        assertEquals(
            1,
            transport.requestedUrls.count { it == SPRITE_IMAGE_URL },
            "the sprite image is acquired by the same compilation as its metadata",
        )
        assertTrue(
            transport.requestedUrls.count { it == STYLE_URL } >= 2,
            "the style document itself is re-resolved per frame; only its compilation is reused",
        )
    }

    @Test
    fun aStyleThatWillNotParseIsATypedRenGFailureRatherThanAnEngineOne() = runTest {
        val transport = StyleTransport(styleJson = "{ not a style }")
        val renderer = styleRenderer(transport)

        val failure = kotlin.test.assertFailsWith<RenGException> { renderer.prepare(basemapPlan(frameIndex = 0L)) }

        assertEquals(RenGErrorCode.RESOURCE_PARSE_FAILED, failure.code)
        assertEquals(PipelineStage.RESOURCE_PARSING, failure.stage)
        assertEquals(
            listOf(STYLE_URL),
            transport.requestedUrls,
            "a style RenG cannot read is never handed to the engine",
        )
    }

    @Test
    fun aStyleOutsideRenGsSupportedSubsetIsReportedAsUnsupportedRatherThanMalformed() = runTest {
        val transport = StyleTransport(styleJson = """{"version":7,"sources":{},"layers":[]}""")
        val renderer = styleRenderer(transport)

        val failure = kotlin.test.assertFailsWith<RenGException> { renderer.prepare(basemapPlan(frameIndex = 0L)) }

        assertEquals(RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE, failure.code)
    }

    /**
     * The renderer has to be able to hold the frame's compiled style on **every** frame, not only on the
     * one that compiled it: the pure core emits no `CompileBasemapStyle` on a `RESIDENT`-provenance
     * frame, so there is no compile action to take it from. Reading it back from the engine host gives
     * one uniform seam, and the identity below is what Cycle E-C3's `prepareTiles` will be handed.
     */
    @Test
    fun holdsTheOneCompiledStyleAcrossEveryFrameThatDrawsIt() = runTest {
        val renderer = styleRenderer(StyleTransport()) as RenGRenderer
        assertNull(renderer.preparedBasemapStyle, "nothing is compiled before the first prepare()")

        renderer.prepare(basemapPlan(frameIndex = 0L))
        val first = renderer.preparedBasemapStyle
        renderer.prepare(basemapPlan(frameIndex = 1L))
        val second = renderer.preparedBasemapStyle

        assertNotNull(first, "a frame that draws a basemap holds its compiled style")
        assertSame(first, second, "the same style document is the same compilation, frame after frame")
    }

    /**
     * ADR 0016: the style is written only after "successful compilation and completion of all other work
     * for the referencing preparation items". Made observable, and deterministic, by giving the driver a
     * single concurrency slot — the two possible orders are then fully determined by whether the style's
     * owner is also the sticker's, which is precisely what the barrier reads.
     *
     * With one owner per plan item the sticker's Store write and visibility install both complete before
     * the style's write is even requested. With an owner per reference the style's owner has no other
     * work at all, the barrier is vacuously satisfied, and the style is written first.
     */
    @Test
    fun writesTheStyleOnlyAfterEveryOtherResourceItsOwnFrameAskedFor() = runTest {
        val transport = StyleTransport()
        val store = RecordingStyleStore()
        val renderer = createRenderer(
            RendererConfiguration(
                outputPixelSize = OutputPixelSize(64, 64),
                transport = transport,
                store = store,
                basemapStyle = ResourceLocator(STYLE_URL),
                maximumConcurrentResourceOperations = 1,
            ),
            styleGlBinding(),
            RenderContextProbe { RenderContextIdentity(1L) },
        )

        renderer.prepare(
            basemapPlan(
                frameIndex = 0L,
                stickers = listOf(Sticker(placement = screenPlacement(), image = ResourceLocator(STICKER_URL))),
            ),
        )

        // Filtered to the two classes RenG's own driver writes: the engine writes its sprite pair through
        // the firewall during compilation too, on its own concurrent coroutines, and their order relative
        // to each other is the engine's business rather than this barrier's.
        assertEquals(
            listOf(ResourceClass.STICKER_IMAGE, ResourceClass.BASEMAP_STYLE),
            store.writes.map(RawResourceKey::resourceClass).filter {
                it == ResourceClass.STICKER_IMAGE || it == ResourceClass.BASEMAP_STYLE
            },
            "the style's write waits for every other resource of the item that referenced it",
        )
        assertTrue(
            store.writes.map(RawResourceKey::resourceClass).containsAll(
                listOf(ResourceClass.BASEMAP_SPRITE_JSON, ResourceClass.BASEMAP_SPRITE_IMAGE),
            ),
            "the engine's own sprite acquisition reached the consumer through the firewall",
        )
    }

    @Test
    fun aPlanThatDrawsNoBasemapAcquiresNoStyleAtAll() = runTest {
        val transport = StyleTransport()
        val renderer = styleRenderer(transport)

        renderer.prepare(
            FramePlan(frameIndex = 0L, camera = styleCamera(), drawBasemap = false),
        )

        assertEquals(emptyList(), transport.requestedUrls, "drawBasemap = false acquires nothing")
    }
}

// ---- fixtures ------------------------------------------------------------------------------------

internal const val STYLE_URL: String = "https://styles.example/basic.json"
internal const val SPRITE_BASE_URL: String = "https://sprites.example/atlas"
internal const val SPRITE_JSON_URL: String = "https://sprites.example/atlas.json"
internal const val SPRITE_IMAGE_URL: String = "https://sprites.example/atlas.png"
internal const val STYLE_TILE_TEMPLATE: String = "https://tiles.example/r/{z}/{x}/{y}.png"
internal const val STICKER_URL: String = "https://images.example/sticker.png"

/**
 * A style whose compilation genuinely fetches something. `background-pattern` is what makes Rentile's
 * `layerRequiresSpriteUnconditionally` true, so the sprite pair is acquired inside `engine.prepare`
 * rather than at tile time — which is exactly the traffic this task's preregistration has to cover.
 */
internal val STYLE_WITH_SPRITE_JSON: String =
    """{"version":8,"name":"reng-style-commit-test",""" +
        """"sprite":"$SPRITE_BASE_URL",""" +
        """"sources":{"s":{"type":"raster","tiles":["$STYLE_TILE_TEMPLATE"],"tileSize":256}},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-pattern":"dot"}},""" +
        """{"id":"r","type":"raster","source":"s"}]}"""

/** An empty sprite atlas: a valid document with no entries, so nothing has to line up with the image. */
private const val SPRITE_ATLAS_JSON: String = "{}"

/** A real, valid 2x2 truecolour PNG — Rentile reads its IHDR dimensions while compiling the atlas. */
internal val STYLE_TEST_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mPgEpHTMLJhcAuISsmrAAAPGAMNubnoZAAAAABJRU5ErkJggg==",
)

internal fun screenPlacement(): Placement = Placement(
    positionMode = AnchoringMode.SCREEN,
    position = Vector3(0.0, 0.0, 0.0),
    rotationMode = AnchoringMode.SCREEN,
    rotation = Vector3(0.0, 0.0, 0.0),
    scaleMode = AnchoringMode.SCREEN,
    scale = 1.0,
)

internal fun styleCamera(): Camera =
    Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 2.0, bearing = 0.0, pitch = 0.0)

internal fun basemapPlan(frameIndex: Long, stickers: List<Sticker> = emptyList()): FramePlan = FramePlan(
    frameIndex = frameIndex,
    camera = styleCamera(),
    drawBasemap = true,
    stickers = stickers,
)

internal fun styleRenderer(
    transport: Transport,
    store: Store = RecordingStyleStore(),
    basemapStyle: ResourceLocator? = ResourceLocator(STYLE_URL),
): Renderer = createRenderer(
    RendererConfiguration(
        outputPixelSize = OutputPixelSize(64, 64),
        transport = transport,
        store = store,
        basemapStyle = basemapStyle,
    ),
    styleGlBinding(),
    RenderContextProbe { RenderContextIdentity(1L) },
)

internal fun styleGlBinding(): RecordingGlBinding = RecordingGlBinding().apply {
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

/**
 * Answers the style document, the sprite pair, and a PNG for anything else, recording every url in call
 * order. Declares no expiry at all, so nothing it serves is ever fresh — every frame re-resolves its
 * documents from here, which is what makes a per-frame recompilation visible as repeated sprite traffic.
 */
internal class StyleTransport(
    private val styleJson: String = STYLE_WITH_SPRITE_JSON,
) : Transport {
    val requestedUrls: MutableList<String> = mutableListOf()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        requestedUrls += url
        return when (url) {
            STYLE_URL -> TransportResponse(
                statusCode = 200,
                body = styleJson.encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            SPRITE_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = SPRITE_ATLAS_JSON.encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            else -> TransportResponse(
                statusCode = 200,
                body = STYLE_TEST_PNG,
                metadata = TransportResponseMetadata(contentType = "image/png"),
            )
        }
    }
}

/** Records every Store exchange in call order and never answers a read, so nothing is served twice. */
internal class RecordingStyleStore : Store {
    val reads: MutableList<RawResourceKey> = mutableListOf()
    val writes: MutableList<RawResourceKey> = mutableListOf()

    override suspend fun read(key: RawResourceKey): StoredRawResource? {
        reads += key
        return null
    }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        writes += key
    }
}
