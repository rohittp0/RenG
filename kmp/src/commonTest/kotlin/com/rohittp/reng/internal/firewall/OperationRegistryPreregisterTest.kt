package com.rohittp.reng.internal.firewall

import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.RawResourceKey as RenGRawResourceKey
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.RouteMemo
import com.rohittp.reng.internal.identity.Sha256Function
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.rentile.ResourceClass as EngineResourceClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * `OperationRegistry.preregister`'s cheap repeat: a batch is narrowed to the routes not already
 * registered identically before anything is copied, and a route's redacted-url digest may be
 * remembered across registries. Neither may change what the firewall accepts or refuses -- above all,
 * a *different* route landing on a key an identical-looking batch already holds must still fail
 * `AMBIGUOUS_RESOURCE_ROUTE`, and a failing batch must still publish none of its routes.
 */
class OperationRegistryPreregisterTest {

    @Test
    fun anIdenticalRepeatIsAcceptedAndLeavesEveryRouteResolvable() = runTest {
        val registry = registry()
        registry.preregister(listOf(vectorRoute, rasterRoute))
        registry.preregister(listOf(vectorRoute, rasterRoute))
        registry.preregister(listOf(rasterRoute))

        assertTrue(registry.knowsTransport(vectorRoute.locator.value, EngineResourceClass.VECTOR_TILE))
        assertTrue(registry.knowsTransport(rasterRoute.locator.value, EngineResourceClass.RASTER_TILE))
        assertTrue(registry.knowsStore(redactedDigest(vectorRoute.locator.value), EngineResourceClass.VECTOR_TILE))
        assertNotNull(
            registry.renGResourceKeyForEngineDigest(ResourceClass.BASEMAP_RASTER_TILE, redactedDigest(rasterRoute.locator.value)),
        )
    }

    @Test
    fun aDifferentRouteOnAnAlreadyRegisteredKeyStillFails() = runTest {
        val registry = registry()
        registry.preregister(listOf(vectorRoute))
        // Same locator and class, so the same private key and every same index -- but a different
        // ceiling makes it a different route, which the narrowing must not mistake for a repeat.
        val otherCeiling = route(vectorRoute.locator.value, ResourceClass.BASEMAP_VECTOR_TILE, maximumResponseBytes = 7L)
        val failure = assertFailsWith<RenGException> { registry.preregister(listOf(vectorRoute, otherCeiling)) }
        assertEquals(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, failure.code)
    }

    @Test
    fun aFailingBatchPublishesNoneOfItsNewRoutesEvenBesideRepeats() = runTest {
        val registry = registry()
        registry.preregister(listOf(vectorRoute))
        val fresh = route("https://tiles.example/fresh/1/2/3.pbf", ResourceClass.BASEMAP_VECTOR_TILE)
        val colliding = route(vectorRoute.locator.value, ResourceClass.BASEMAP_VECTOR_TILE, maximumResponseBytes = 7L)

        assertFailsWith<RenGException> { registry.preregister(listOf(vectorRoute, fresh, colliding)) }

        assertFalse(registry.knowsTransport(fresh.locator.value, EngineResourceClass.VECTOR_TILE))
        assertTrue(registry.knowsTransport(vectorRoute.locator.value, EngineResourceClass.VECTOR_TILE))
    }

    @Test
    fun twoUrlsDifferingOnlyInACredentialStillCollideWithARememberedDigest() = runTest {
        // Rentile keys both by the redacted url, so they are one engine resource and RenG must refuse
        // the second: the remembered digest has to be the redacted one, not one per exact url.
        val memo = RouteMemo<String>()
        registry(memo).preregister(listOf(route("https://tiles.example/a.pbf?token=one", ResourceClass.BASEMAP_VECTOR_TILE)))

        val registry = registry(memo)
        registry.preregister(listOf(route("https://tiles.example/a.pbf?token=one", ResourceClass.BASEMAP_VECTOR_TILE)))
        val failure = assertFailsWith<RenGException> {
            registry.preregister(listOf(route("https://tiles.example/a.pbf?token=two", ResourceClass.BASEMAP_VECTOR_TILE)))
        }
        assertEquals(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, failure.code)
    }

    @Test
    fun aRememberedDigestIsNotDerivedAgainByALaterRegistry() = runTest {
        val counting = PreregisterCountingSha256()
        val memo = RouteMemo<String>()
        registry(memo, counting).preregister(listOf(vectorRoute, rasterRoute))
        val derivedOnce = counting.calls
        assertEquals(2, derivedOnce, "one redacted-url digest per engine route")

        val later = registry(memo, counting)
        later.preregister(listOf(vectorRoute, rasterRoute))
        assertEquals(derivedOnce, counting.calls, "the second registry reads both digests from the memo")
        assertTrue(later.knowsStore(redactedDigest(rasterRoute.locator.value), EngineResourceClass.RASTER_TILE))
    }

    private fun registry(memo: RouteMemo<String>? = null, sha256: Sha256Function = PureKotlinSha256) = OperationRegistry(
        transport = PreregisterOnlyTransport,
        store = PreregisterOnlyStore,
        // Its own hash function, so PreregisterCountingSha256 counts the registry's digests and nothing else.
        privateKeyResolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256),
        sha256 = sha256,
        redactedLocatorDigests = memo,
    )
}

private val vectorRoute = route("https://tiles.example/v/1/2/3.pbf?key=secret", ResourceClass.BASEMAP_VECTOR_TILE)
private val rasterRoute = route("https://tiles.example/r/1/2/3.png", ResourceClass.BASEMAP_RASTER_TILE)

private fun route(url: String, resourceClass: ResourceClass, maximumResponseBytes: Long = 1L shl 20) = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator(url),
    resourceClass = resourceClass,
    maximumResponseBytes = maximumResponseBytes,
)

private fun redactedDigest(url: String): String =
    PureKotlinSha256.digest(CanonicalBytes(redactAuthenticationQuery(url).encodeToByteArray())).lowercaseHex

private class PreregisterCountingSha256 : Sha256Function {
    var calls = 0
        private set

    override fun digest(bytes: CanonicalBytes) = PureKotlinSha256.digest(bytes).also { calls += 1 }
}

private object PreregisterOnlyTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse = error("preregistration never fetches")
}

private object PreregisterOnlyStore : Store {
    override suspend fun read(key: RenGRawResourceKey): StoredRawResource? = error("preregistration never reads")

    override suspend fun write(key: RenGRawResourceKey, resource: StoredRawResource) = error("preregistration never writes")
}
