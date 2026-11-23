package com.rohittp.reng.internal.identity

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.firewall.ProductionRentilePrivateKeyResolver
import com.rohittp.reng.internal.resource.RentilePrivateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * [RouteMemo] may only ever save work: a remembered route's key, and its Rentile private key, must be
 * exactly what a fresh derivation gives, and a bounded memo must keep the routes frames keep naming.
 */
class RouteMemoTest {
    private val routes: List<Pair<ResourceClass, ResourceLocator>> =
        ResourceClass.entries.filter { it != ResourceClass.BASEMAP_STYLE }.flatMap { resourceClass ->
            listOf("sticker.png", "https://example.test/a%20b.png?token=secret", "models/café.glb", "x")
                .map { resourceClass to ResourceLocator(it) }
        }

    @Test
    fun aRememberingDeriverGivesTheKeysAFreshOneDoes() {
        val fresh = ResourceKeyDeriver()
        val remembering = ResourceKeyDeriver(externalKeys = RouteMemo())
        repeat(2) { pass ->
            routes.forEach { (resourceClass, locator) ->
                val expected = fresh.external(resourceClass, locator)
                val actual = remembering.external(resourceClass, locator)
                assertEquals(expected.key, actual.key, "pass $pass $resourceClass ${locator.value}")
                assertEquals(expected.rawKey, actual.rawKey)
                assertEquals(expected.identity, actual.identity)
            }
        }
    }

    @Test
    fun aRememberingResolverGivesThePrivateKeysAFreshOneDoes() {
        val fresh = ProductionRentilePrivateKeyResolver(AcceleratedSha256)
        val remembering = ProductionRentilePrivateKeyResolver(
            sha256 = AcceleratedSha256,
            ownIdentityDeriver = ResourceKeyDeriver(externalKeys = RouteMemo()),
            privateKeys = RouteMemo(),
        )
        repeat(2) {
            routes.forEach { (resourceClass, locator) ->
                assertEquals(fresh.resolve(locator, resourceClass), remembering.resolve(locator, resourceClass))
            }
        }
    }

    @Test
    fun aHitReturnsTheRememberedValueWithoutDerivingAgain() {
        val memo = RouteMemo<String>()
        var derivations = 0
        val first = memo.valueOf(ResourceClass.STICKER_IMAGE, ResourceLocator("a")) { derivations++; "value" }
        val second = memo.valueOf(ResourceClass.STICKER_IMAGE, ResourceLocator("a")) { derivations++; "other" }
        assertSame(first, second)
        assertEquals(1, derivations)
        // The class is part of the route: the same locator under another class is another value.
        assertEquals("other", memo.valueOf(ResourceClass.MODEL_TEXTURE, ResourceLocator("a")) { "other" })
    }

    @Test
    fun itIsBoundedAndKeepsTheRoutesFramesKeepNaming() {
        val memo = RouteMemo<String>(capacity = 3)
        fun name(locator: String) = memo.valueOf(ResourceClass.STICKER_IMAGE, ResourceLocator(locator)) { locator }

        name("stable")
        // Routes that are new every frame, as a label's glyph data is, with the stable one named each time.
        repeat(10) { frame ->
            name("stable")
            name("per-frame-$frame")
        }
        assertEquals(3, memo.size)
        assertEquals("stable", memo.lookup(ResourceClass.STICKER_IMAGE, ResourceLocator("stable")))
        assertEquals(null, memo.lookup(ResourceClass.STICKER_IMAGE, ResourceLocator("per-frame-0")))
    }

    @Test
    fun rememberKeepsWhicheverValueArrivedFirst() {
        val memo = RouteMemo<RentilePrivateKey>()
        val first = RentilePrivateKey("first")
        val kept = memo.remember(ResourceClass.STICKER_IMAGE, ResourceLocator("a"), first)
        val again = memo.remember(ResourceClass.STICKER_IMAGE, ResourceLocator("a"), RentilePrivateKey("second"))
        assertSame(first, kept)
        assertSame(first, again)
    }
}
