package com.rohittp.reng.internal.basemap

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.internal.firewall.OperationRegistry
import com.rohittp.reng.internal.firewall.ProductionRentilePrivateKeyResolver
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * What one frame's tile-route phase actually costs, measured rather than assumed.
 *
 * A review of the derivation flagged the shape as a hazard worth measuring before it was inherited
 * unmeasured: route count is `sources x tiles`, with a `raster-dem` source multiplied by nine because a
 * hillshade layer samples a 3x3 neighbourhood, and every `OperationRegistry.preregister` computes up to
 * three pure-Kotlin SHA-256 digests over the composed url. Nothing in the tree caches or fast-paths any
 * of it, deliberately: the numbers below are the evidence for that, not an oversight.
 *
 * **Measured 2026-08-20 on an Apple Silicon macOS host** (`./gradlew --no-configuration-cache
 * --rerun-tasks :kmp:macosArm64Test --tests
 * "com.rohittp.reng.internal.basemap.BasemapRouteDerivationCostTest"`), over the three-source style
 * below (one vector, one raster, one `raster-dem`):
 *
 * | tiles | distinct routes |
 * |---|---|
 * | 40 (a full 1080p viewport at LOD 12) | 150 = 40 vector + 40 raster + 70 DEM |
 * | 512 (`maximumBasemapTileInstances`, the ceiling) | 1636 = 512 + 512 + 612 DEM |
 *
 * The DEM share is far below the naive `tiles x 9` because adjacent output tiles share almost all of
 * their neighbourhood: a WxH block needs a (W+2)x(H+2) DEM block, not 9WH tiles.
 *
 * Wall time on the reference host, after one untimed warmup pass, `derive` / `derive + preregister`:
 *
 * | target | 40 tiles | 512 tiles |
 * |---|---|---|
 * | `testAndroidHostTest` (JVM, JIT warm) | 1.2 ms / **3.7 ms** | 3.5 ms / **6.6 ms** |
 * | `macosArm64Test` (Kotlin/Native **debug** binary) | 6.7 ms / 9.7 ms | 59.7 ms / 83.7 ms |
 *
 * **The verdict this records: immaterial, so nothing caches it.** A realistic frame pays ~3.7 ms of
 * pure CPU to name 150 routes, on the same frame that then performs 150 tile acquisitions through the
 * consumer's transport and rasterizes 40 tiles through Skia. It is smaller than one tile fetch. The
 * `macosArm64Test` column is an unoptimized debug test binary (`linkDebugTestMacosArm64`) and overstates
 * release cost; it is recorded because it is what CI actually runs, not as a release figure.
 *
 * The absolute figures are recorded here but deliberately **not asserted**, for the same reason
 * [com.rohittp.reng.internal.resource.ResourceOperationScaleBenchmarkTest] stopped asserting a
 * wall-clock ceiling: a number calibrated on one machine is a release blocker on a slower one.
 *
 * The guard below is a **shape** guard, following that same precedent, and it is deliberately loose:
 * this phase is linear in tiles, so a 12.8x tile increase should cost about 12.8x, and the ceiling is
 * set at 51.2x -- above every observed run (1.8x on the JVM, 8.6x on native, both below linear because
 * the fixed per-call costs amortize) and far below the ~164x a quadratic regression would produce.
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
        val costRatio = ceiling.totalMicros.toDouble() / realistic.totalMicros.toDouble().coerceAtLeast(1.0)
        println("tile ratio ${tileRatio}x, cost ratio ${costRatio}x")
        assertTrue(
            costRatio < tileRatio * 4.0,
            "tile-route derivation is linear in tiles: a ${tileRatio}x tile increase scaled cost by " +
                "${costRatio}x (ceiling ${tileRatio * 4.0}x; quadratic would be ~${tileRatio * tileRatio}x)",
        )
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

        val ROW_WIDTH_BY_TILE_COUNT: Map<Int, Int> = mapOf(
            REALISTIC_VIEWPORT_TILES to 8,
            TILE_BUDGET_CEILING to 32,
        )

        const val STYLE_BASE = "https://styles.example/full.json"

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
