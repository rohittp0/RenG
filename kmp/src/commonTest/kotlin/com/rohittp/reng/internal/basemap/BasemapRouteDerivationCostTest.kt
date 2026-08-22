package com.rohittp.reng.internal.basemap

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.firewall.BasemapEngineHost
import com.rohittp.reng.internal.firewall.OperationRegistry
import com.rohittp.reng.internal.firewall.ProductionRentilePrivateKeyResolver
import com.rohittp.reng.internal.firewall.basemapEngineHost
import com.rohittp.reng.internal.firewall.hostStyleRecord
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * What one frame's tile-route phase actually costs, measured rather than assumed.
 *
 * A review of the derivation flagged the shape as a hazard worth measuring before it was inherited
 * unmeasured: route count is `sources x tiles`, with a `raster-dem` source multiplied by nine because a
 * hillshade layer samples a 3x3 neighbourhood, and every `OperationRegistry.preregister` computes a
 * pure-Kotlin SHA-256 digest over the composed url. Nothing in the tree caches or fast-paths any of it,
 * deliberately: the numbers below are the evidence for that, not an oversight.
 *
 * Two corrections to the estimate that prompted this measurement, both established by it. The DEM
 * fan-out is **not** `tiles x 9`: neighbourhoods overlap, so a WxH block of output tiles needs a
 * (W+2)x(H+2) DEM block, not `9WH` tiles. And `preregister` computes **two** digests per engine-keyed
 * route, not three -- `redactedLocatorHex` is computed once and shared by the store and engine-digest
 * indices, and `ProductionRentilePrivateKeyResolver` computes the same shape once more.
 *
 * **Measured on an Apple Silicon macOS host** (`./gradlew --no-configuration-cache --rerun-tasks
 * :kmp:macosArm64Test :kmp:testAndroidHostTest --tests
 * "com.rohittp.reng.internal.basemap.BasemapRouteDerivationCostTest"`), over the three-source style
 * below (one vector, one raster, one `raster-dem`):
 *
 * | tiles | distinct routes |
 * |---|---|
 * | 40 (a full 1080p viewport at LOD 12) | 150 = 40 vector + 40 raster + 70 DEM |
 * | 512 (`maximumBasemapTileInstances`, the ceiling) | 1636 = 512 + 512 + 612 DEM |
 *
 * Wall time after one untimed warmup pass, `derive` / `derive + preregister`, as a range across runs --
 * run-to-run variance on both targets is large and quoting a single run overstates the precision:
 *
 * | target | 40 tiles | 512 tiles |
 * |---|---|---|
 * | `testAndroidHostTest` (JVM, JIT warm) | 1.2-1.5 ms / **2.8-3.7 ms** | 3.5-5.4 ms / **6.6-10.4 ms** |
 * | `macosArm64Test` (Kotlin/Native **debug** binary) | 6.4-7.5 ms / 8.6-10.9 ms | 59.7-69.2 ms / 83.7-93.1 ms |
 *
 * **Verdict on the routes: immaterial, so nothing caches them.** A realistic frame pays a few ms of pure
 * CPU to name 150 routes, on the same frame that then performs 150 tile acquisitions through the
 * consumer's transport and rasterizes 40 tiles through Skia. It is smaller than one tile fetch. The
 * `macosArm64Test` column is an unoptimized debug test binary (`linkDebugTestMacosArm64`) and overstates
 * release cost; it is recorded because it is what CI actually runs, not as a release figure.
 *
 * ## The second per-frame cost: the style document used to be parsed twice
 *
 * `RenGRenderer.renderBasemapTiles` needs the manifest the driver's own `ValidateBasemapStyle` derived
 * from the same bytes on the same frame. It used to re-run `deriveBasemapStyleManifest`, and so
 * `parseJson` over the **whole** style document, to get it -- so a basemap frame parsed its style twice.
 * That cost scales with **document size**, not with tile count, so the route table above says nothing
 * about it: the style it uses is ~400 bytes and real basemap styles are two to three orders of magnitude
 * larger. It is therefore measured separately, by
 * [parsesTheWholeStyleDocumentOnceRatherThanTwicePerBasemapFrameAndReportsTheSaving] against generated
 * documents shaped like production styles.
 *
 * `BasemapEngineHost.styleManifest` now binds the derivation to the style's content digest, beside the
 * compilation the host already binds that way, so the second read is a digest comparison rather than a
 * parse. One parse / whole frame before / whole frame after, as a range across the runs recorded here:
 *
 * | style | JVM | native (debug) |
 * |---|---|---|
 * | 150 layers, 74 KB | 1.2-4.2 ms / **2.5-8.3 ms** / **1.2-4.2 ms** | 7.5-8.0 ms / 15.0-16.1 ms / 7.5-8.0 ms |
 * | 500 layers, 248 KB | 1.9-3.7 ms / **3.9-7.4 ms** / **1.9-3.7 ms** | 25.8-27.0 ms / 51.5-54.0 ms / 25.8-27.0 ms |
 *
 * The JVM ranges are wide because they are: run-to-run variance there exceeds the difference between the
 * two document sizes, and quoting a single run would overstate the precision. The read itself measures
 * 2.3-3.6 us on the JVM and 0.56-0.61 us on the native debug binary, and -- unlike a parse -- does not
 * grow with the document: 74 KB and 248 KB read alike. Halving the per-frame style cost is the whole of
 * the saving, since the surviving parse is the driver's own and has to happen.
 *
 * **Why a cache rather than the other option.** Removing the duplication meant either widening the pure
 * core's protocol to carry the manifest out of `ValidateBasemapStyle`, or giving the engine host a
 * content-digest-bound cache. The second was chosen: it keeps the change inside the component whose
 * lifetime already spans frames, and leaves the layer this project tests hardest untouched.
 *
 * The absolute figures are recorded here but deliberately **not asserted**, for the same reason
 * [com.rohittp.reng.internal.resource.ResourceOperationScaleBenchmarkTest] stopped asserting a
 * wall-clock ceiling: a number calibrated on one machine is a release blocker on a slower one. Both
 * guards below are ratios between measurements taken in the same run on the same machine.
 *
 * The route guard is a **shape** guard, following that same precedent, and it is deliberately loose.
 * This phase is linear in tiles, so a 12.8x tile increase should cost about 12.8x. Observed cost ratios
 * span 1.8x-4.3x on the JVM and 8.5x-13.1x on native -- i.e. at or below linear, with the top of the
 * native range sitting essentially exactly on it. The ceiling is 51.2x, which leaves roughly 3.9x
 * headroom over the highest ratio actually observed and stays far below the ~164x a quadratic regression
 * would produce, so it separates the two shapes without being tuned to any one run.
 */
class BasemapRouteDerivationCostTest {

    @Test
    fun derivesAndPreregistersAWholeFramesTileRoutesAndReportsTheCost() {
        val manifest = threeSourceManifest()

        val realistic = measure(manifest, tileCount = REALISTIC_VIEWPORT_TILES)
        val ceiling = measure(manifest, tileCount = TILE_BUDGET_CEILING)

        println(
            "tiles=${REALISTIC_VIEWPORT_TILES} routes=${realistic.routeCount} " +
                "deriveMicros=${realistic.deriveMicros} derivePlusPreregisterMicros=${realistic.totalMicros}",
        )
        println(
            "tiles=${TILE_BUDGET_CEILING} routes=${ceiling.routeCount} " +
                "deriveMicros=${ceiling.deriveMicros} derivePlusPreregisterMicros=${ceiling.totalMicros}",
        )

        // One vector route and one raster route per tile, plus a DEM neighbourhood that overlaps heavily
        // between adjacent tiles -- so the DEM share is far below the naive tiles x 9. Pinned so a change
        // in the derivation's fan-out is visible here as a number, not only as a timing drift.
        assertEquals(150, realistic.routeCount, "a full viewport over three sources")
        assertEquals(1636, ceiling.routeCount, "the whole tile budget over three sources")

        val tileRatio = TILE_BUDGET_CEILING.toDouble() / REALISTIC_VIEWPORT_TILES.toDouble()
        // A zero or negative elapsed measurement (possible on a very fast machine or a coarse monotonic
        // clock) makes a ratio undefined rather than merely small or large. Fail with a clear message
        // rather than coercing it into a denominator, which would silently convert an unmeasurable
        // baseline into a spurious ratio failure -- the same reasoning, and the same shape, as
        // ResourceOperationScaleBenchmarkTest's own guard.
        require(realistic.totalMicros > 0L && ceiling.totalMicros > 0L) {
            "cannot compute a cost ratio from a non-positive elapsed measurement: " +
                "tiles=$REALISTIC_VIEWPORT_TILES took ${realistic.totalMicros}us, " +
                "tiles=$TILE_BUDGET_CEILING took ${ceiling.totalMicros}us"
        }
        val costRatio = ceiling.totalMicros.toDouble() / realistic.totalMicros.toDouble()
        println("tile ratio ${tileRatio}x, cost ratio ${costRatio}x")
        assertTrue(
            costRatio < tileRatio * 4.0,
            "tile-route derivation is linear in tiles: a ${tileRatio}x tile increase scaled cost by " +
                "${costRatio}x (ceiling ${tileRatio * 4.0}x; quadratic would be ~${tileRatio * tileRatio}x)",
        )
    }

    /**
     * The **other** per-frame cost, and the one the manifest cache closes. `renderBasemapTiles` needs the
     * manifest the driver's own `ValidateBasemapStyle` already derived on the same frame from the same
     * bytes; deriving it a second time re-runs `parseJson` over the *whole* style document, so a basemap
     * frame parsed its style twice. It now asks [BasemapEngineHost] instead, which binds the derivation
     * to the style's content digest beside the compilation it already binds that way.
     *
     * This is measured separately from the routes because it scales with **document size**, not with tile
     * count, and so the route figures above say nothing about it. The style used by the route measurement
     * is ~400 bytes; real basemap styles are two to three orders of magnitude larger, which is what
     * [realisticStyleDocument] reproduces.
     *
     * Both halves are measured here: the driver's own parse, which is work that has to happen and is
     * unchanged, and the second read, which is now a digest comparison. The before/after per-frame
     * figures are in this test's own printout and in the class KDoc above.
     */
    @Test
    fun parsesTheWholeStyleDocumentOnceRatherThanTwicePerBasemapFrameAndReportsTheSaving() {
        for (layerCount in listOf(TYPICAL_STYLE_LAYERS, LARGE_STYLE_LAYERS)) {
            val json = realisticStyleDocument(layerCount)
            val document = json.encodeToByteArray()

            // One untimed pass: this runs every frame, so steady state is what matters.
            assertIs<BasemapStyleManifestOutcome.Derived>(deriveBasemapStyleManifest(document, STYLE_BASE))

            val started = TimeSource.Monotonic.markNow()
            val outcome = deriveBasemapStyleManifest(document, STYLE_BASE)
            val parseNanos = started.elapsedNow().inWholeNanoseconds

            val manifest = assertIs<BasemapStyleManifestOutcome.Derived>(outcome).manifest
            assertEquals(3, manifest.sources.size, "the fixture must be a style RenG fully derives")

            val cachedNanos = perCachedReadNanos(json, document)

            println(
                "layers=$layerCount documentBytes=${document.size} " +
                    "parseAndDeriveMicros=${parseNanos / 1_000L} cachedReadNanos=$cachedNanos " +
                    "perFrameBeforeMicros=${(2L * parseNanos) / 1_000L} " +
                    "perFrameAfterMicros=${(parseNanos + cachedNanos) / 1_000L}",
            )

            // A ratio between two measurements taken in the same run on the same machine, never a
            // wall-clock ceiling -- for the same reason every other guard in this file and in
            // ResourceOperationScaleBenchmarkTest is a ratio: a number calibrated on a fast machine once
            // blocked a release here. Without the cache the second read *is* a parse and this reads 1x;
            // the factor below is deliberately far looser than the several-hundred-fold saving observed.
            require(parseNanos > 0L) {
                "cannot compare against a non-positive parse measurement: layers=$layerCount"
            }
            assertTrue(
                cachedNanos * CACHED_READ_SAVING_FACTOR < parseNanos,
                "the second read of one frame's style must not be another parse: layers=$layerCount " +
                    "parsed in ${parseNanos}ns, read again in ${cachedNanos}ns (ceiling " +
                    "${parseNanos / CACHED_READ_SAVING_FACTOR}ns)",
            )
        }
    }

    /**
     * What `renderBasemapTiles`'s own read of the manifest costs once the frame has already derived it:
     * the mean of [CACHED_READS] warm reads through a real [BasemapEngineHost], over a style installed in
     * a real [ResidentCache] exactly as the driver's visibility install leaves it.
     *
     * Averaged over many reads rather than timed once because a digest comparison is far below the
     * monotonic clock's useful resolution on both targets, and a single sample would report the clock.
     */
    private fun perCachedReadNanos(json: String, document: ByteArray): Long {
        val cache = ResidentCache()
        val host = basemapEngineHost(cache = cache)
        val stored = hostStyleRecord(json)
        return try {
            cache.install(COST_STYLE_KEY, stored, decoded = null)
            // The frame's own first read, which derives; every read after it must not.
            val warm = assertIs<BasemapStyleManifestOutcome.Derived>(
                host.styleManifest(COST_STYLE_KEY, stored, STYLE_BASE),
            ).manifest

            val started = TimeSource.Monotonic.markNow()
            repeat(CACHED_READS) { host.styleManifest(COST_STYLE_KEY, stored, STYLE_BASE) }
            val elapsedNanos = started.elapsedNow().inWholeNanoseconds

            assertSame(
                warm,
                assertIs<BasemapStyleManifestOutcome.Derived>(
                    host.styleManifest(COST_STYLE_KEY, stored, STYLE_BASE),
                ).manifest,
                "the measurement must genuinely have been reading a cache, not reparsing " +
                    "${document.size} bytes each time",
            )
            elapsedNanos / CACHED_READS
        } finally {
            host.close()
        }
    }

    /**
     * A style document shaped like a real one: the same three sources, plus [layerCount] layers each
     * carrying a filter expression and a data-driven paint property, which is where a real style's bytes
     * actually are. Generated rather than checked in as a fixture file so it stays readable and so its
     * size is a number this test states rather than a file it hides.
     */
    private fun realisticStyleDocument(layerCount: Int): String {
        val layers = (0 until layerCount).joinToString(",") { index ->
            """{"id":"layer-$index","type":"fill","source":"v","source-layer":"landuse-$index",""" +
                """"minzoom":${index % 14},"maxzoom":${14 + index % 8},""" +
                """"filter":["all",["==",["geometry-type"],"Polygon"],""" +
                """["match",["get","class"],["park-$index","grass-$index","pitch-$index"],true,false],""" +
                """[">=",["get","area"],${100 * index}]],""" +
                """"layout":{"visibility":"visible"},""" +
                """"paint":{"fill-color":["interpolate",["linear"],["zoom"],""" +
                """8,"hsl(${index % 360},60%,80%)",16,"hsl(${index % 360},60%,45%)"],""" +
                """"fill-opacity":["interpolate",["linear"],["zoom"],8,0.2,16,0.9],""" +
                """"fill-outline-color":"rgba(${index % 256},${(index * 7) % 256},${(index * 13) % 256},0.4)"}}"""
        }
        return THREE_SOURCE_STYLE.removeSuffix(""""layers":[]}""") + """"layers":[$layers]}"""
    }

    private fun measure(manifest: BasemapStyleManifest, tileCount: Int): Measurement {
        val tiles = viewportTiles(tileCount)
        // One untimed pass first. This phase runs on every frame, so its steady-state cost is what
        // matters; a first-call measurement on a cold JIT (androidHostTest) or a cold code page
        // (Kotlin/Native) reports warmup, not the thing being measured.
        warmUp(manifest, tiles)
        val registry = OperationRegistry(
            transport = NoTransport,
            store = NoStore,
            privateKeyResolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256),
            sha256 = PureKotlinSha256,
        )
        val started = TimeSource.Monotonic.markNow()
        val routes = tileTimeRoutes(manifest, tiles, ResourceAccessMode.NORMAL, LIMITS)
        val derived = started.elapsedNow()
        routes.forEach(registry::preregister)
        val total = started.elapsedNow()
        return Measurement(
            routeCount = routes.size,
            deriveMicros = derived.inWholeMicroseconds,
            totalMicros = total.inWholeMicroseconds,
        )
    }

    private fun warmUp(manifest: BasemapStyleManifest, tiles: List<CanonicalBasemapTile>) {
        val registry = OperationRegistry(
            transport = NoTransport,
            store = NoStore,
            privateKeyResolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256),
            sha256 = PureKotlinSha256,
        )
        tileTimeRoutes(manifest, tiles, ResourceAccessMode.NORMAL, LIMITS).forEach(registry::preregister)
    }

    private class Measurement(val routeCount: Int, val deriveMicros: Long, val totalMicros: Long)

    /**
     * [tileCount] tiles in a contiguous rectangular block at LOD 12, which is what a viewport-shaped
     * selection looks like: adjacent tiles share most of their DEM neighbourhood, so a synthetic
     * scattered set would overstate the distinct-route count.
     */
    private fun viewportTiles(tileCount: Int): List<CanonicalBasemapTile> {
        val columns = ROW_WIDTH_BY_TILE_COUNT.getValue(tileCount)
        return (0 until tileCount).map { index ->
            CanonicalBasemapTile(lod = 12, tileY = 1000 + index / columns, canonicalX = 2000 + index % columns)
        }
    }

    private fun threeSourceManifest(): BasemapStyleManifest {
        val outcome = deriveBasemapStyleManifest(THREE_SOURCE_STYLE.encodeToByteArray(), STYLE_BASE)
        return assertIs<BasemapStyleManifestOutcome.Derived>(outcome).manifest
    }

    private companion object {
        /** A 1920x1080 viewport at 512px tiles is 4x3 tiles; a 256px style at device pixel ratio 2 is 8x5. */
        const val REALISTIC_VIEWPORT_TILES = 40

        /** `RendererConfiguration.maximumBasemapTileInstances`'s default, and RenG's own worst case. */
        const val TILE_BUDGET_CEILING = 512

        /** Roughly a mid-sized production basemap style. */
        const val TYPICAL_STYLE_LAYERS = 150

        /** Roughly the largest production basemap styles in circulation. */
        const val LARGE_STYLE_LAYERS = 500

        /** Enough warm reads that their mean is well above the monotonic clock's resolution. */
        const val CACHED_READS = 200

        /**
         * How much cheaper than a parse the second read of one frame's style has to be. Loose on
         * purpose: the observed saving is several hundred-fold, and this only has to separate "read a
         * cache" from "parse the document again", which reads as 1x.
         */
        const val CACHED_READ_SAVING_FACTOR = 8L

        val ROW_WIDTH_BY_TILE_COUNT: Map<Int, Int> = mapOf(
            REALISTIC_VIEWPORT_TILES to 8,
            TILE_BUDGET_CEILING to 32,
        )

        const val STYLE_BASE = "https://styles.example/full.json"

        /** RenG's own canonical identity for [STYLE_BASE], the key its style is resident under. */
        val COST_STYLE_KEY: ResourceKey = ResourceKeyDeriver(PureKotlinSha256)
            .external(ResourceClass.BASEMAP_STYLE, ResourceLocator(STYLE_BASE))
            .key

        val THREE_SOURCE_STYLE: String =
            """{"version":8,"name":"reng-route-cost",""" +
                """"sprite":"https://sprites.example/atlas",""" +
                """"sources":{""" +
                """"v":{"type":"vector","tiles":["https://tiles.example/v/{z}/{x}/{y}.pbf"]},""" +
                """"r":{"type":"raster","tiles":["https://tiles.example/r/{z}/{x}/{y}.png"],"tileSize":256},""" +
                """"d":{"type":"raster-dem","tiles":["https://dem.example/{z}/{x}/{y}.png"],"tileSize":256}""" +
                """},"layers":[]}"""

        val LIMITS = ResourceLimits()

        val NoTransport = object : Transport {
            override suspend fun execute(request: TransportRequest): TransportResponse =
                error("the cost measurement performs no consumer exchange")
        }

        val NoStore = object : Store {
            override suspend fun read(key: RawResourceKey): StoredRawResource? =
                error("the cost measurement performs no consumer exchange")

            override suspend fun write(key: RawResourceKey, resource: StoredRawResource) =
                error("the cost measurement performs no consumer exchange")
        }
    }
}
