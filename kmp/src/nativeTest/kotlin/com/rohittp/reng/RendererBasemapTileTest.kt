package com.rohittp.reng

import com.rohittp.reng.internal.firewall.basemapTileKey
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
                CanonicalBasemapTile(lod = 4, tileY = 10, canonicalX = 1),
                CanonicalBasemapTile(lod = 4, tileY = 10, canonicalX = 2),
                CanonicalBasemapTile(lod = 4, tileY = 11, canonicalX = 1),
                CanonicalBasemapTile(lod = 4, tileY = 11, canonicalX = 2),
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
                "https://tiles.example/r/4/1/10.png",
                "https://tiles.example/r/4/1/11.png",
                "https://tiles.example/r/4/2/10.png",
                "https://tiles.example/r/4/2/11.png",
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
        // The union of the 3x3 neighbourhoods of x in {1, 2}, y in {10, 11} at z4 is x in 0..3,
        // y in 9..12: sixteen distinct source tiles for four output tiles. That block is disjoint from
        // its own transpose too, so it catches an x/y swap on the DEM path as well as on the raster one.
        assertEquals(
            (9..12).flatMap { y -> (0..3).map { x -> "https://dem.example/4/$x/$y.png" } }.sorted(),
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

    /**
     * The whole tile phase, twice, over a style the consumer's transport never declares fresh — which is
     * the condition under which a per-frame style *manifest* is reachable at all, and the one
     * `BasemapEngineHost`'s digest-bound manifest cache has to survive. Frame two resolves the document
     * from the transport again and the driver installs a fresh generation carrying identical bytes, so a
     * cache bound to that generation would silently reparse the entire style on every frame forever.
     *
     * That reuse is pure, so no consumer adapter can see it and no assertion here can: the reuse itself
     * is asserted at its own level, by
     * `internal.firewall.BasemapEngineHostTest.keepsTheManifestAcrossAFreshGenerationOfIdenticalBytes`.
     * What this test does own is the end of the same path a consumer actually observes — that frame two's
     * ground is still composed from a manifest describing *this* style, rather than from a stale or empty
     * one, which would reach the consumer as `AMBIGUOUS_RESOURCE_ROUTE` on every tile at once.
     *
     * The transport declares **no** `freshUntilEpochMillis`, checked here as an assertion rather than
     * assumed: a fresh style would make frame two `RESIDENT`-provenance, install no new generation, and
     * leave this test passing whether the cache worked or not.
     */
    @Test
    fun rendersTheSameGroundOnEveryFrameThatReResolvesTheSameStyleDocument() = runTest {
        val transport = TileTransport()
        val renderer = styleRenderer(transport) as RenGRenderer

        val first = renderer.prepare(basemapPlan(frameIndex = 0L)) as RenGPreparedFrame
        val urlsAfterFirstFrame = transport.requestedUrls()
        val second = renderer.prepare(basemapPlan(frameIndex = 1L)) as RenGPreparedFrame

        assertEquals(
            2,
            transport.requestedUrls().count { it == STYLE_URL },
            "the fixture must genuinely re-resolve the style on frame two, or it proves nothing",
        )
        assertEquals(
            first.basemapTiles.map { it.key }.sortedBy { it.stableId },
            second.basemapTiles.map { it.key }.sortedBy { it.stableId },
            "a re-resolved style of identical bytes renders exactly the same identified ground",
        )
        assertEquals(
            urlsAfterFirstFrame.filter { it.startsWith("https://tiles.example/") }.sorted(),
            transport.requestedUrls()
                .filter { it.startsWith("https://tiles.example/") }
                .distinct()
                .sorted(),
            "and asks for it through exactly the urls the first frame's manifest composed",
        )
    }

    /**
     * The frame on which the consumer's own style document **changes** — the thing a downstream
     * integrator does constantly while iterating on a style, and the one case where the compilation and
     * the route derivation can disagree about which document the frame is on.
     *
     * `CompileBasemapStyle` runs strictly before `InstallBasemapStyleVisibility`, so at compile time the
     * resident generation still carries the *previous* frame's bytes; the tile-time manifest is derived
     * after that install, from this frame's. A compilation taken from the resident generation therefore
     * makes the engine ask for the superseded style's tile urls while the firewall holds only the edited
     * style's, and every tile at once fails closed as `AMBIGUOUS_RESOURCE_ROUTE` — surfacing to the
     * caller as `BASEMAP_RENDER_FAILED` on frame two.
     *
     * **It has to be frame two.** The defect self-heals: by frame three the edited bytes are resident, so
     * a test that prepares two frames and only asks whether it recovers passes without the fix. What is
     * asserted here is that the *second* frame — the one that fails today — succeeds and renders from the
     * edited document.
     *
     * The transport declares **no** `freshUntilEpochMillis`, asserted rather than assumed: a fresh style
     * would make frame two `RESIDENT`-provenance, re-resolve nothing, and never deliver the edit at all.
     */
    @Test
    fun rendersTheEditedStyleOnTheVeryFrameItsBytesChange() = runTest {
        val transport = EditedStyleTileTransport()
        val renderer = styleRenderer(transport) as RenGRenderer

        renderer.prepare(basemapPlan(frameIndex = 0L))
        val firstDigest = assertNotNull(renderer.preparedBasemapStyle, "frame one holds its style").digest
        val second = renderer.prepare(basemapPlan(frameIndex = 1L)) as RenGPreparedFrame
        val secondDigest = assertNotNull(renderer.preparedBasemapStyle, "frame two holds its style").digest

        assertEquals(
            2,
            transport.requestedUrls().count { it == STYLE_URL },
            "the fixture declares no freshness, so frame two must genuinely re-resolve the style",
        )
        assertNotEquals(
            firstDigest,
            secondDigest,
            "frame two compiled the document it is committing, not the one resident before it",
        )
        assertEquals(4, second.basemapTiles.size, "and rendered the ground that document describes")
        assertEquals(
            listOf(
                "https://tiles.example/edited/4/1/10.png",
                "https://tiles.example/edited/4/1/11.png",
                "https://tiles.example/edited/4/2/10.png",
                "https://tiles.example/edited/4/2/11.png",
            ),
            transport.requestedUrls()
                .filter { it.startsWith("https://tiles.example/edited/") }
                .distinct()
                .sorted(),
            "through the exact urls the edited style's own tile source composes",
        )
        second.basemapTiles.forEach { rendered ->
            assertEquals(
                basemapTileKey(secondDigest, rendered.tile, TILE_OUTPUT_SIZE),
                rendered.key,
                "and every rendered tile is identified by the edited style's digest",
            )
        }
        assertEquals(
            4,
            transport.requestedUrls().count { it.startsWith("https://tiles.example/r/") },
            "the superseded style's ground is asked for on its own frame only",
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

/** [STYLE_TILE_TEMPLATE] edited, so the same style url serves a genuinely different ground source. */
internal const val EDITED_STYLE_TILE_TEMPLATE: String = "https://tiles.example/edited/{z}/{x}/{y}.png"

/**
 * [STYLE_WITH_SPRITE_JSON] with its raster source repointed. Only the tile template moves, which is
 * enough to change both the compiled style's digest and every tile url the engine composes.
 */
internal val EDITED_STYLE_WITH_SPRITE_JSON: String =
    STYLE_WITH_SPRITE_JSON.replace(STYLE_TILE_TEMPLATE, EDITED_STYLE_TILE_TEMPLATE)

/**
 * A [TileTransport] whose style document is **edited between frames**: the first request for
 * [STYLE_URL] answers [STYLE_WITH_SPRITE_JSON] and every later one answers
 * [EDITED_STYLE_WITH_SPRITE_JSON], modelling a consumer saving a change to their own style.
 *
 * Which body to serve is decided from the recorder's own snapshot, taken under the same lock the append
 * took, so the decision is ordered with respect to every other recorded call rather than read off an
 * unguarded counter.
 *
 * Declares no `freshUntilEpochMillis` at all: a fresh style would be resolved from residency on frame
 * two and the edit would never be delivered.
 */
internal class EditedStyleTileTransport : Transport {
    private val recorded = ConcurrentRecorder<String>()

    suspend fun requestedUrls(): List<String> = recorded.snapshot()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        recorded.record(url)
        return when (url) {
            STYLE_URL -> TransportResponse(
                statusCode = 200,
                body = if (recorded.snapshot().count { it == STYLE_URL } <= 1) {
                    STYLE_WITH_SPRITE_JSON.encodeToByteArray()
                } else {
                    EDITED_STYLE_WITH_SPRITE_JSON.encodeToByteArray()
                },
                metadata = TransportResponseMetadata(contentType = "application/json"),
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
