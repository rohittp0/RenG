package com.rohittp.reng.internal.firewall

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.planning.BasemapTileInstance
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.resource.ResourceRouteKey
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

/**
 * The first tests in RenG that construct a real Rentile engine. Everything Skia-free lives here, so it
 * runs on every executable target; the rasterizing half — which needs Skia's native library, absent from
 * this project's `androidHostTest` runtime — lives in `nativeTest`'s `BasemapEngineRenderTest`.
 */
class BasemapEngineHostTest {

    // ---- setup purity ---------------------------------------------------------------------------

    @Test
    fun createsTheRasterizerAtSetupWithoutSuspendingOrPerformingIo() {
        val transport = CountingHostTransport()
        val store = CountingHostStore()
        // Deliberately NOT inside runTest: this constructor is not `suspend`, and a real engine must be
        // built by the time it returns without a single consumer exchange.
        val host = basemapEngineHost(transport, store)
        assertEquals(0, transport.executeCalls, "setup performs no Transport call")
        assertEquals(0, store.readCalls + store.writeCalls, "setup performs no Store call")
        assertFalse(host.isClosed)
        host.close()
    }

    @Test
    fun closesIdempotently() {
        val host = basemapEngineHost()
        host.close()
        host.close()
        assertTrue(host.isClosed)
    }

    @Test
    fun refusesFurtherWorkOnceClosed() = runTest {
        val host = basemapEngineHost()
        val style = host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
            host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
        }
        host.close()
        // Reported as the engine states BASEMAP_RENDER_FAILED already carries: "the ground did not draw",
        // reachable only if RenG mismanaged a handle it owns.
        val failure = assertFailsWith<RenGException> {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                host.prepareTiles(style, listOf(HOST_RASTER_TILE))
            }
        }
        assertEquals(RenGErrorCode.BASEMAP_RENDER_FAILED, failure.code)
        assertFailsWith<RenGException> {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
            }
        }
    }

    // ---- rendered-tile identity ------------------------------------------------------------------

    @Test
    fun derivesTileIdentityFromRenGsOwnCanonicalRootNotTheEnginesKey() {
        val tile = CanonicalBasemapTile(lod = 2, tileY = 1, canonicalX = 1)
        val baseline = basemapTileKey(HOST_STYLE_DIGEST_A, tile, HOST_TILE_OUTPUT_SIZE)

        assertEquals(ResourceKind.BASEMAP_TILE, baseline.kind)
        assertNull(baseline.resourceClass, "a rendered tile is not an external resource class")

        // Every component independently keys the tile: change exactly one and nothing else.
        assertNotEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_B, tile, HOST_TILE_OUTPUT_SIZE),
            "style digest",
        )
        assertNotEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_A, tile.copy(lod = 3), HOST_TILE_OUTPUT_SIZE),
            "lod",
        )
        assertNotEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_A, tile.copy(tileY = 2), HOST_TILE_OUTPUT_SIZE),
            "tileY",
        )
        assertNotEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_A, tile.copy(canonicalX = 0), HOST_TILE_OUTPUT_SIZE),
            "canonicalX",
        )
        assertNotEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_A, tile, OutputPixelSize(256, 512)),
            "output width",
        )
        assertNotEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_A, tile, OutputPixelSize(512, 256)),
            "output height",
        )
        assertEquals(
            baseline,
            basemapTileKey(HOST_STYLE_DIGEST_A, tile, HOST_TILE_OUTPUT_SIZE),
            "derivation is pure",
        )
    }

    @Test
    fun sharesOneRenderedTileAcrossEveryWorldCopyOfTheSameCanonicalTile() {
        // BasemapTileSelector emits instances (which carry unwrappedX and instanceCopy) separately from
        // canonicalResources, so a CanonicalBasemapTile is already post-world-copy-dedup. Two draw
        // instances of the same tile in different Mercator world copies must therefore resolve to one
        // rendered-tile resource and one engine render, never N.
        val first = BasemapTileInstance(lod = 2, tileY = 1, unwrappedX = 1L, instanceCopy = 0, canonicalX = 1)
        val second = BasemapTileInstance(lod = 2, tileY = 1, unwrappedX = 5L, instanceCopy = 1, canonicalX = 1)
        assertNotEquals(first, second, "the fixture must genuinely differ in its world copy")

        // Through the production instance overload, not a test-local projection: the world-copy dedup has
        // to live in shipped code for this to be a claim about the renderer rather than about the test.
        assertEquals(
            basemapTileKey(HOST_STYLE_DIGEST_A, first, HOST_TILE_OUTPUT_SIZE),
            basemapTileKey(HOST_STYLE_DIGEST_A, second, HOST_TILE_OUTPUT_SIZE),
        )
        assertEquals(
            basemapTileKey(HOST_STYLE_DIGEST_A, CanonicalBasemapTile(2, 1, 1), HOST_TILE_OUTPUT_SIZE),
            basemapTileKey(HOST_STYLE_DIGEST_A, first, HOST_TILE_OUTPUT_SIZE),
            "an instance keys the very same rendered tile its canonical tile does",
        )
    }

    // ---- style compilation -----------------------------------------------------------------------

    @Test
    fun compilesThePreparedStyleLazilyAndReusesItWhileResident() = runTest {
        val transport = CountingHostTransport()
        val host = basemapEngineHost(transport = transport)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                assertEquals(0, transport.executeCalls, "opening an operation performs no exchange")
                val first = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                val second = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertSame(first, second, "a resident style compiles once")
                assertEquals(
                    0,
                    transport.executeCalls,
                    "an inline-source style hands RenG's own bytes to the engine and fetches nothing",
                )
            }
        } finally {
            host.close()
        }
    }

    /**
     * "Accessing a freed resource reloads it" is a claim about **residency**, not about compilation. A
     * freed style is genuinely reinstalled and re-leased here; its compilation is reused because the
     * bytes are identical, and recompiling identical bytes would re-run the engine's whole sprite,
     * TileJSON and GeoJSON acquisition for no result the caller could distinguish.
     */
    @Test
    fun reloadsAFreedStyleGenerationWithoutRecompilingIdenticalBytes() = runTest {
        val cache = ResidentCache()
        val host = basemapEngineHost(cache = cache)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val first = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertNotNullGeneration(cache)
                cache.free(ResourceSelector.ByKey(hostStyleKey))
                assertNull(cache.current(hostStyleKey), "the fixture must genuinely free the generation")

                val second = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)

                assertSame(first, second, "identical bytes are never recompiled")
                assertNotNullGeneration(cache)
            }
        } finally {
            host.close()
        }
    }

    /**
     * The defect this binding exists to close, at its smallest reproducible size. Compilation runs
     * strictly before the style's own visibility install, and installing always retires and replaces the
     * current generation — so a compilation bound to a generation *object* misses on the very next
     * lookup and recompiles, once per frame, forever. Bound to content, it survives.
     */
    @Test
    fun keepsTheCompilationAcrossAFreshGenerationOfIdenticalBytes() = runTest {
        val cache = ResidentCache()
        val host = basemapEngineHost(cache = cache)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val first = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                val compiledGeneration = cache.current(hostStyleKey)

                // Exactly what ResourceActionExecutor.installVisibility does after CompileBasemapStyle.
                cache.installAndTakeLease(hostStyleKey, hostStyleRecord(), decoded = null)
                assertNotSame(
                    compiledGeneration,
                    cache.current(hostStyleKey),
                    "the fixture must genuinely replace the generation the compilation observed",
                )

                val second = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)

                assertSame(first, second, "a fresh generation of identical bytes reuses the compilation")
                assertSame(
                    host.currentPreparedStyle(hostStyleKey),
                    second,
                    "the host retains exactly what it last compiled",
                )
            }
        } finally {
            host.close()
        }
    }

    /**
     * The resident generation is authoritative, so "the content changed" means a generation carrying
     * different bytes was installed — exactly what the driver's own visibility install does after a
     * transport re-fetch. That, and only that, recompiles.
     */
    @Test
    fun recompilesWhenTheInstalledStyleContentItselfChanges() = runTest {
        val cache = ResidentCache()
        val host = basemapEngineHost(cache = cache)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val first = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                val edited = HOST_STYLE_JSON.replace("reng-basemap-host-test", "reng-basemap-host-test-2")
                cache.installAndTakeLease(hostStyleKey, hostStyleRecord(edited), decoded = null)
                val second = host.preparedStyle(hostStyleKey, hostStyleRecord(edited), HOST_STYLE_BASE_URI)
                assertNotSame(first, second, "different bytes are a different style")
            }
        } finally {
            host.close()
        }
    }

    @Test
    fun holdsNoPreparedStyleUntilOneIsCompiled() = runTest {
        val host = basemapEngineHost()
        try {
            assertNull(host.currentPreparedStyle(hostStyleKey))
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
            }
            // Retained across invocations on purpose: a RESIDENT-provenance frame emits no compile
            // action at all, so this readback is the renderer's only way to hold the frame's style.
            assertNotNull(host.currentPreparedStyle(hostStyleKey))
        } finally {
            host.close()
        }
    }

    // ---- incremental route registration ----------------------------------------------------------

    /**
     * An invocation opened with no routes at all still admits exactly what is registered into it later —
     * which is the whole reason the registration is incremental: the routes a style's compilation needs
     * are not knowable until the style has been read, inside the invocation.
     *
     * The tile is served as a 404 so nothing rasterizes: this file runs on `androidHostTest` too, where
     * Skia's native library is absent (see [BasemapEngineRenderTest]). What is under test is which url
     * reaches the consumer, not what the engine does with it.
     */
    @Test
    fun admitsAnEngineExchangeOnlyThroughARouteRegisteredBeforeItHappens() = runTest {
        val transport = CountingHostTransport(statusCode = 404)
        val host = basemapEngineHost(transport = transport)
        try {
            host.withOperation(ResourceAccessMode.NORMAL) {
                // Registering the very same route twice is one occurrence joining one route, not a
                // conflict.
                host.registerRoutes(listOf(hostRasterRoute))
                host.registerRoutes(listOf(hostRasterRoute))
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }
        assertEquals(listOf(HOST_RASTER_TILE_URL), transport.requestedUrls)
    }

    @Test
    fun refusesARouteRegisteredForADifferentAccessModeOrOutsideAnyInvocation() = runTest {
        val host = basemapEngineHost()
        try {
            assertFailsWith<RenGException> { host.registerRoutes(listOf(hostRasterRoute)) }
            host.withOperation(ResourceAccessMode.CACHE_ONLY) {
                assertFailsWith<IllegalArgumentException> { host.registerRoutes(listOf(hostRasterRoute)) }
            }
        } finally {
            host.close()
        }
    }

    private fun assertNotNullGeneration(cache: ResidentCache) {
        assertTrue(
            cache.current(hostStyleKey) != null,
            "compiling a style installs and leases its resident generation",
        )
    }

    // ---- consumer Store key namespaces -----------------------------------------------------------

    @Test
    fun partitionsTheConsumerStoreIntoTwoDisjointKeyNamespaces() {
        // Two different key spaces address one consumer Store. RenG's own driver reads and writes with
        // `ResourceKeyDeriver.external`'s canonical hash (`ResourceActionExecutor`'s `action.rawKey`); the
        // firewall passes RENTILE's `sha256Hex(redacted url)` straight through (`OperationRegistry`'s
        // `RenGRawResourceKey(stableId = key.stableId, ...)`). Two keys for one logical resource would be
        // two reads and two writes where RenG's contract permits one exchange, so the partition below is a
        // contract, not an implementation detail: the engine keys exactly the seven classes it fetches
        // itself, and RenG keys exactly the four it fetches itself.
        //
        // What this pins is the table. What makes the spaces genuinely disjoint *today* is that no
        // production path hands an engine-keyed class to the driver at all: `FramePlanningCore`'s static
        // traversal builds `StaticResourceReference.External` only for the four below, and the discovered
        // children that would carry the other seven are produced by the basemap-style commit actions, which
        // `ResourceActionExecutor` still leaves to its `else`.
        val rengKeyed = ResourceClass.entries.filter { engineKeyedResourceClassOf(it) == null }.toSet()
        val engineKeyed = ResourceClass.entries.filter { engineKeyedResourceClassOf(it) != null }.toSet()

        assertEquals(emptySet(), rengKeyed intersect engineKeyed)
        assertEquals(ResourceClass.entries.toSet(), rengKeyed + engineKeyed)
        assertEquals(
            setOf(
                ResourceClass.BASEMAP_STYLE,
                ResourceClass.STICKER_IMAGE,
                ResourceClass.MODEL_GLB,
                ResourceClass.MODEL_TEXTURE,
            ),
            rengKeyed,
            "moving a class between these namespaces makes one consumer resource answer to two keys",
        )
        assertEquals(7, engineKeyed.size, "Rentile 0.2.0 fetches and keys exactly seven basemap classes")
    }

    // ---- preregistration matches what the engine actually requests -------------------------------

    @Test
    fun requestsExactlyTheUrlItPreregistered() = runTest {
        val transport = CountingHostTransport(statusCode = 404)
        val host = basemapEngineHost(transport = transport)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }
        assertEquals(1, transport.executeCalls, "the preregistered route is the one the engine asks for")
        assertEquals(
            HOST_RASTER_TILE_URL,
            transport.lastRequest?.locator?.value,
            "Rentile composes {z}/{x}/{y} itself; RenG must preregister the composed url, not the template",
        )
    }

    @Test
    fun failsClosedWhenTheRegisteredUrlIsNotTheOneTheEngineComposes() = runTest {
        val transport = CountingHostTransport()
        val host = basemapEngineHost(transport = transport)
        try {
            // The template, not the composed url -- the exact mistake that would present as a total
            // basemap outage rather than as a mismatch.
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostTemplateRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }
        assertEquals(0, transport.executeCalls, "an unpreregistered exchange never reaches the consumer")
    }

    @Test
    fun failsTheWholeBatchRatherThanSubstitutingForAnUnavailableTile() = runTest {
        // RenG "performs no repeated consumer exchanges, retries, repairs, or fallbacks", and Rentile's
        // tile substitution is exactly such a fallback -- so a 404 on a required tile must fail the batch,
        // never quietly become a stretched z0 ancestor. Both the exact route AND its ancestor are
        // preregistered here so the difference is observable at the consumer: with substitution disabled
        // Rentile never asks for the ancestor at all, and with it enabled it does.
        val transport = CountingHostTransport(
            statusForUrl = { url -> if (url == HOST_RASTER_TILE_URL) 404 else 200 },
        )
        val host = basemapEngineHost(transport = transport)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute, hostAncestorRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }
        assertEquals(
            listOf(HOST_RASTER_TILE_URL),
            transport.requestedUrls,
            "an unavailable tile is a failure, not an invitation to fetch a substitute",
        )
    }

    // ---- failures -------------------------------------------------------------------------------

    @Test
    fun translatesTheEnginesOwnDigestBackToRenGsCanonicalResourceKey() = runTest {
        val host = basemapEngineHost(transport = CountingHostTransport(statusCode = 404))
        val failure = try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }

        assertEquals(RenGErrorCode.RESOURCE_UNAVAILABLE, failure.code)
        val reported = failure.diagnostics.single().resourceKey
        val rengKey = ResourceKeyDeriver(PureKotlinSha256)
            .external(ResourceClass.BASEMAP_RASTER_TILE, ResourceLocator(HOST_RASTER_TILE_URL))
            .key
        assertEquals(rengKey, reported, "a descriptor leaving this host names RenG's own key")
        assertNotEquals(
            hostEngineSanitizedIdOf(HOST_RASTER_TILE_URL),
            reported?.stableId,
            "Rentile's sha256(redacted url) is a foreign namespace and must never escape as an identity",
        )
        assertEquals(ResourceClass.BASEMAP_RASTER_TILE, reported?.resourceClass)
    }

    @Test
    fun neverLetsARentileExceptionEscape() = runTest {
        val signedUrl = "https://tiles.example/r/1/0/0.png?access_token=SECRET"
        val transport = CountingHostTransport(throwable = RuntimeException(signedUrl))
        val host = basemapEngineHost(transport = transport)
        val failure = try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }
        val rendered = failure.toString() + failure.diagnostics.joinToString { it.toString() }
        assertFalse(rendered.contains("SECRET"), "an adapter's credential must never surface")
        assertNull(failure.cause, "a RenG failure never carries an engine cause")
    }

    @Test
    fun keepsAdapterCancellationUnwrapped() = runTest {
        val cancellingTransport = object : Transport {
            override suspend fun execute(request: TransportRequest): TransportResponse =
                throw CancellationException("cancelled")
        }
        val host = basemapEngineHost(transport = cancellingTransport)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                // Asserted on type, never on identity: Kotlin's stack recovery may hand back a copy
                // carrying the original as its immediate cause.
                assertFailsWith<CancellationException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            }
        } finally {
            host.close()
        }
    }

    @Test
    fun refusesAnEngineCallOutsideAnyPreparationInvocation() = runTest {
        // The registry's lifetime is exactly one preparation invocation (ADR 0016), so the engine's fixed
        // adapters must fail closed rather than answer from a registry that has already been discarded.
        val transport = CountingHostTransport()
        val store = CountingHostStore()
        val host = basemapEngineHost(transport = transport, store = store)
        try {
            val style = host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
            }
            assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
            assertEquals(0, transport.executeCalls + store.readCalls + store.writeCalls)
        } finally {
            host.close()
        }
    }

    @Test
    fun givesEachPreparationInvocationItsOwnOperationRegistry() = runTest {
        val transport = CountingHostTransport(throwable = RuntimeException("adapter down"))
        val host = basemapEngineHost(transport = transport)
        try {
            val style = host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
            }
            repeat(2) {
                host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                    assertFailsWith<RenGException> { host.prepareTiles(style, listOf(HOST_RASTER_TILE)) }
                }
            }
            // A latch never evicts within one registry, so a registry that outlived its invocation would
            // replay the first failure without calling the consumer again and this count would read 1.
            assertEquals(2, transport.executeCalls, "each invocation gets its own operation-scoped registry")
        } finally {
            host.close()
        }
    }
}

// ---- fixtures ------------------------------------------------------------------------------------

internal const val HOST_STYLE_BASE_URI: String = "https://styles.example/basic.json"
internal const val HOST_RASTER_TILE_URL: String = "https://tiles.example/r/1/0/0.png"
internal const val HOST_RASTER_TILE_TEMPLATE: String = "https://tiles.example/r/{z}/{x}/{y}.png"
internal const val HOST_ANCESTOR_TILE_URL: String = "https://tiles.example/r/0/0/0.png"

private const val HOST_STYLE_DIGEST_A = "digest-a"
private const val HOST_STYLE_DIGEST_B = "digest-b"
private val HOST_TILE_OUTPUT_SIZE = OutputPixelSize(512, 512)

/** `TileId(z = 1, x = 0, y = 0)` in RenG's own vocabulary. */
internal val HOST_RASTER_TILE: CanonicalBasemapTile = CanonicalBasemapTile(lod = 1, tileY = 0, canonicalX = 0)

internal val HOST_STYLE_JSON: String =
    """{"version":8,"name":"reng-basemap-host-test",""" +
        """"sources":{"s":{"type":"raster","tiles":["$HOST_RASTER_TILE_TEMPLATE"],"tileSize":256}},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}},""" +
        """{"id":"r","type":"raster","source":"s"}]}"""

internal val hostStyleKey: ResourceKey = ResourceKeyDeriver(PureKotlinSha256)
    .external(ResourceClass.BASEMAP_STYLE, ResourceLocator(HOST_STYLE_BASE_URI))
    .key

internal val hostRasterRoute: ResourceRouteKey = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator(HOST_RASTER_TILE_URL),
    resourceClass = ResourceClass.BASEMAP_RASTER_TILE,
    maximumResponseBytes = 32L * 1024L * 1024L,
)

/**
 * The z0 ancestor of [HOST_RASTER_TILE]. Preregistered only by the substitution test, which needs the
 * engine's substitute attempt to be able to reach the consumer at all: the firewall would otherwise refuse
 * it before `Transport.execute`, and a refused attempt is invisible to a consumer-side counter.
 */
internal val hostAncestorRoute: ResourceRouteKey = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator(HOST_ANCESTOR_TILE_URL),
    resourceClass = ResourceClass.BASEMAP_RASTER_TILE,
    maximumResponseBytes = 32L * 1024L * 1024L,
)

internal val hostTemplateRoute: ResourceRouteKey = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator(HOST_RASTER_TILE_TEMPLATE),
    resourceClass = ResourceClass.BASEMAP_RASTER_TILE,
    maximumResponseBytes = 32L * 1024L * 1024L,
)

internal fun hostStyleRecord(json: String = HOST_STYLE_JSON): StoredRawResource {
    val bytes = json.encodeToByteArray()
    return StoredRawResource(
        bytes = bytes,
        contentDigest = PureKotlinSha256.digest(CanonicalBytes(bytes)).lowercaseHex,
        metadata = StoredRawResourceMetadata(storedAtEpochMillis = 0L),
    )
}

internal fun hostEngineSanitizedIdOf(url: String): String =
    PureKotlinSha256.digest(CanonicalBytes(redactAuthenticationQuery(url).encodeToByteArray())).lowercaseHex

internal fun basemapEngineHost(
    transport: Transport = CountingHostTransport(),
    store: Store = CountingHostStore(),
    cache: ResidentCache = ResidentCache(),
): BasemapEngineHost = BasemapEngineHost(
    transport = transport,
    store = store,
    cache = cache,
    tileOutputSizePixels = 512,
)

/** Counts every [Transport.execute] call and records the last request. Single-threaded by design: every
 *  test here drives it from one `runTest` scheduler. */
internal class CountingHostTransport(
    private val statusCode: Int = 200,
    private val body: ByteArray = VALID_TILE_PNG,
    private val throwable: Throwable? = null,
    private val statusForUrl: (String) -> Int = { statusCode },
) : Transport {
    var executeCalls: Int = 0
        private set
    var lastRequest: TransportRequest? = null
        private set
    val requestedUrls: MutableList<String> = mutableListOf()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        executeCalls += 1
        lastRequest = request
        requestedUrls += request.locator.value
        throwable?.let { throw it }
        return TransportResponse(
            statusCode = statusForUrl(request.locator.value),
            body = body,
            metadata = TransportResponseMetadata(contentType = "image/png"),
        )
    }
}

/** Counts every [Store] call and answers [response] on read. */
internal class CountingHostStore(
    private val response: StoredRawResource? = null,
) : Store {
    var readCalls: Int = 0
        private set
    var writeCalls: Int = 0
        private set

    override suspend fun read(key: RawResourceKey): StoredRawResource? {
        readCalls += 1
        return response
    }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        writeCalls += 1
    }
}

// A real, valid 2x2 truecolour PNG (colour type 2) -- the same fixture the driver and firewall suites
// use, so a rendering target genuinely decodes it rather than merely accepting bytes.
internal val VALID_TILE_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR42mPgEpHTMLJhcAuISsmrAAAPGAMNubnoZAAAAABJRU5ErkJggg==",
)
