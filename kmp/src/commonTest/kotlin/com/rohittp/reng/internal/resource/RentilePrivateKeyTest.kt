package com.rohittp.reng.internal.resource

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class RentilePrivateKeyTest {
    @Test
    fun privateKeyUsesExactStructuralTokenEquality() {
        val first = RentilePrivateKey("private-key")
        val equal = RentilePrivateKey("private-key")
        val different = RentilePrivateKey("other-private-key")

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, different)
    }

    @Test
    fun privateKeyRejectsBlankAndIsolatedSurrogateTokens() {
        listOf("", " \t\n", "\uD800", "prefix\uDC00suffix").forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { RentilePrivateKey(invalid) }
        }
    }

    @Test
    fun privateKeyTextIsShapeOnlyAndNeverContainsItsToken() {
        val token = "credential-bearing-private-key"
        val text = RentilePrivateKey(token).toString()

        assertEquals("RentilePrivateKey(<redacted>)", text)
        assertFalse(text.contains(token))
    }

    @Test
    fun fakeResolverMayCollapseDistinctRoutesWithoutRentile() {
        val resolver = RentilePrivateKeyResolver { _, _ -> RentilePrivateKey("shared-private-key") }
        val firstLocator = ResourceLocator("first-resource")
        val secondLocator = ResourceLocator("second-resource")

        val first = resolver.resolve(firstLocator, ResourceClass.BASEMAP_STYLE)
        val second = resolver.resolve(secondLocator, ResourceClass.MODEL_GLB)

        assertNotEquals(firstLocator, secondLocator)
        assertEquals(first, second)
    }
}
