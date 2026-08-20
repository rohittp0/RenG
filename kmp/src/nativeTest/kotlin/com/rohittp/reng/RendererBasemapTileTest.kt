package com.rohittp.reng

import com.rohittp.reng.internal.firewall.basemapTileKey
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The renderer end of basemap **tiles**: one `prepare()` acquires the style, compiles it through the
 * real engine, preregisters the exact urls that style's tiles compose, and hands back rendered ground
 * bytes carrying RenG's own identity.
 *
 * **Why this suite is native-only.** Every test here drives `BasemapRasterizer.render`, and Rentile
 * rasterizes through Skia. This project's `androidHostTest` runtime resolves `org.jetbrains.skiko:skiko`'s
 * API without its native library — Rentile adds `skiko-awt-runtime-<host>` only to its own JVM/Android
 * test source sets, never to what it publishes — so on that target `prepareTiles` fails with
 * `RESOURCE_DECODE_FAILED` and `renderTiles` with `BASEMAP_RENDER_FAILED`, both measured, for **any**
 * style including a source-less one. A renderer-level basemap test therefore cannot pass there at all;
 * it is the environment that is incapable, not RenG. Kotlin/Native links Skia in, so this runs for real
 * on `macosArm64Test` (Apple CI) and `linuxX64Test` (Ubuntu CI) — the same reasoning, and the same two
 * targets, as `internal.firewall.BasemapEngineRenderTest`.
 *
 * Urls are asserted as **exact composed strings**, never as shapes. RenG reproduces Rentile's private
 * url composition from a pinned version; a plausible-but-wrong url is not a mismatch a consumer can see,
 * it is `AMBIGUOUS_RESOURCE_ROUTE` on every tile at once — a total outage.
 */
class RendererBasemapTileTest {

    /**
     * The whole path in one assertion set: the frame's four selected ground tiles come back as encoded
     * pixels, each named by [basemapTileKey] over the style digest, the canonical tile, and the engine's
     * output size — RenG's own derivation, deliberately not Rentile's `outputRequestKey`.
     */
    @Test
    fun rendersEveryGroundTileTheFrameSelectedAndNamesItWithRenGsOwnIdentity() = runTest {
        val renderer = styleRenderer(TileTransport()) as RenGRenderer

        val frame = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame

        val style = assertNotNull(renderer.preparedBasemapStyle, "a basemap frame holds its compiled style")
        assertEquals(
            listOf(
                CanonicalBasemapTile(lod = 2, tileY = 1, canonicalX = 1),
                CanonicalBasemapTile(lod = 2, tileY = 1, canonicalX = 2),
                CanonicalBasemapTile(lod = 2, tileY = 2, canonicalX = 1),
                CanonicalBasemapTile(lod = 2, tileY = 2, canonicalX = 2),
            ),
            frame.basemapTiles.map { it.tile }.sortedWith(compareBy({ it.tileY }, { it.canonicalX })),
            "every canonical tile the spatial plan selected is rendered exactly once",
        )
        frame.basemapTiles.forEach { rendered ->
            assertEquals(
                basemapTileKey(style.digest, rendered.tile, TILE_OUTPUT_SIZE),
                rendered.key,
                "a rendered tile carries RenG's own canonical identity, not the engine's request key",
            )
            assertEquals(ResourceKind.BASEMAP_TILE, rendered.key.kind)
            // Not merely non-empty: the cheapest way to claim these are encoded pixels rather than any
            // non-empty byte array is the 8-byte signature the PNG format mandates.
            assertContentEquals(
                PNG_SIGNATURE,
                rendered.pngBytes.take(PNG_SIGNATURE.size).toByteArray(),
                "the engine produced encoded ground pixels",
            )
            assertTrue(rendered.contentKey.isNotEmpty(), "Rentile's own content key travels beside them")
            assertEquals(emptyList(), rendered.substitutions, "tile substitution stays disabled")
        }
    }

    /**
     * The exact four urls Rentile composes for `{z}/{x}/{y}` at the frame's own LOD, pinned as strings.
     * `min(z, maxZoom)`, the template hash, and the `{y}`/`{-y}` distinction are all silent-failure
     * traps: get one wrong and the firewall refuses every tile, which reads as a dead basemap rather
     * than as a mismatch.
     */
    @Test
    fun preregistersTheExactTileUrlsRentileComposesRatherThanTheirShape() = runTest {
        val transport = TileTransport()
        val renderer = styleRenderer(transport)

        renderer.prepare(basemapPlan(frameIndex = 0L))

        assertEquals(
            listOf(
                "https://tiles.example/r/2/1/1.png",
                "https://tiles.example/r/2/1/2.png",
                "https://tiles.example/r/2/2/1.png",
                "https://tiles.example/r/2/2/2.png",
            ),
            transport.requestedUrls().filter { it.startsWith("https://tiles.example/") }.sorted(),
            "the engine reaches the consumer only through routes RenG composed identically",
        )
    }

    /**
     * A hillshade layer samples its DEM source over a 3x3 neighbourhood, so one output tile needs nine
     * source tiles. RenG does not model whether a style has a hillshade layer, so it expands **every**
     * `raster-dem` source that way; dropping the expansion leaves the engine asking for eight urls per
     * tile that no route covers, and the whole frame fails closed.
     */
    @Test
    fun expandsEveryDemSourceOverItsNeighbourhoodSoAHillshadeLayerNeverFailsClosed() = runTest {
        val transport = TileTransport(styleJson = DEM_HILLSHADE_STYLE)
        val renderer = styleRenderer(transport) as RenGRenderer

        val frame = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame

        assertEquals(4, frame.basemapTiles.size, "a hillshade frame renders its ground")
        // The union of the 3x3 neighbourhoods of (1,1), (2,1), (1,2) and (2,2) at z2 is the full
        // 0..3 x 0..3 grid: sixteen distinct source tiles for four output tiles.
        assertEquals(
            (0..3).flatMap { y -> (0..3).map { x -> "https://dem.example/2/$x/$y.png" } }.sorted(),
            transport.requestedUrls().filter { it.startsWith("https://dem.example/") }.distinct().sorted(),
            "one output tile needs its DEM tile plus its eight neighbours",
        )
    }

    /**
     * The frame that proves the style is read back from the host rather than taken from the driver's
     * compile action. With a style the transport declares fresh, frame two resolves it from residency
     * and the pure core emits **no** `CompileBasemapStyle` at all — so a renderer reading the action
     * would hold no style and render no ground from frame two onward. A single-frame test cannot see
     * this, because frame one has both.
     */
    @Test
    fun rendersGroundOnAFrameThatCompilesNoStyleBecauseTheStyleIsAlreadyResident() = runTest {
        val transport = TileTransport(styleFreshUntilEpochMillis = Long.MAX_VALUE)
        val renderer = styleRenderer(transport) as RenGRenderer

        val first = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame
        val second = renderer.prepare(basemapPlan(frameIndex = 1L)) as RenGPreparedFrame

        assertEquals(
            1,
            transport.requestedUrls().count { it == STYLE_URL },
            "the fixture must genuinely make frame two resident-provenance, or it proves nothing",
        )
        assertEquals(4, first.basemapTiles.size)
        assertEquals(
            first.basemapTiles.map { it.key }.sortedBy { it.stableId },
            second.basemapTiles.map { it.key }.sortedBy { it.stableId },
            "a frame that compiles no style still renders exactly the same identified ground",
        )
    }

    @Test
    fun aFrameThatDrawsNoBasemapRendersNoGroundAtAll() = runTest {
        val transport = TileTransport()
        val renderer = styleRenderer(transport)

        val frame = renderer.prepare(
            FramePlan(frameIndex = 0L, camera = styleCamera(), drawBasemap = false),
        ) as RenGPreparedFrame

        assertEquals(emptyList(), frame.basemapTiles, "drawBasemap = false renders no ground")
        assertEquals(emptyList(), transport.requestedUrls(), "and acquires nothing at all")
    }
}

// ---- fixtures ------------------------------------------------------------------------------------

/** `RenderOptions.DEFAULT_OUTPUT_SIZE_PX` square — what [BasemapEngineHost] renders a tile at. */
internal val TILE_OUTPUT_SIZE: OutputPixelSize = OutputPixelSize(512, 512)

/** The 8 bytes every PNG datastream begins with (RFC 2083 section 3.1). */
internal val PNG_SIGNATURE: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

internal const val DEM_TILE_TEMPLATE: String = "https://dem.example/{z}/{x}/{y}.png"

/** One `raster-dem` source drawn by a hillshade layer — the only layer kind that samples a 3x3. */
internal val DEM_HILLSHADE_STYLE: String =
    """{"version":8,"name":"reng-dem-tile-test",""" +
        """"sources":{"d":{"type":"raster-dem","tiles":["$DEM_TILE_TEMPLATE"],"tileSize":256}},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}},""" +
        """{"id":"h","type":"hillshade","source":"d"}]}"""

/**
 * Serves a style document, the sprite pair, and a valid PNG for everything else, recording every url in
 * call order through the same concurrency-safe recorder `RendererBasemapStyleTest` uses — Rentile
 * fetches concurrently on `Dispatchers.Default`, so an unguarded list loses entries.
 *
 * [styleFreshUntilEpochMillis] is declared on the **style response only**: it is what makes a later
 * frame resolve the style from residency instead of re-fetching it, which is the one condition under
 * which the pure core emits no `CompileBasemapStyle`.
 */
internal class TileTransport(
    private val styleJson: String = STYLE_WITH_SPRITE_JSON,
    private val styleFreshUntilEpochMillis: Long? = null,
) : Transport {
    private val recorded = ConcurrentRecorder<String>()

    suspend fun requestedUrls(): List<String> = recorded.snapshot()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        recorded.record(url)
        return when (url) {
            STYLE_URL -> TransportResponse(
                statusCode = 200,
                body = styleJson.encodeToByteArray(),
                metadata = TransportResponseMetadata(
                    contentType = "application/json",
                    freshUntilEpochMillis = styleFreshUntilEpochMillis,
                ),
            )
            SPRITE_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = "{}".encodeToByteArray(),
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
