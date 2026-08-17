package com.rohittp.reng.internal.identity

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ResourceKeyDerivationTest {
    @Test
    fun externalStickerKeyMatchesExactRootStableIdAndRawKey() {
        val derived = ResourceKeyDeriver().external(
            resourceClass = ResourceClass.STICKER_IMAGE,
            locator = ResourceLocator("é"),
        )

        assertEquals(
            "524e4743010200010000000200010002000000020009000300000002c3a9",
            derived.identity.canonicalBytes.fixtureLowercaseHex(),
        )
        assertEquals(
            "086f1d5f61081736cd1bb0145d5b9070cb9903796396f3b73c65cb6413b3db61",
            derived.identity.digest.lowercaseHex,
        )
        assertEquals(
            ResourceKey(
                kind = ResourceKind.EXTERNAL,
                stableId = derived.identity.digest.lowercaseHex,
                resourceClass = ResourceClass.STICKER_IMAGE,
            ),
            derived.key,
        )
        assertEquals(
            RawResourceKey(
                stableId = derived.identity.digest.lowercaseHex,
                resourceClass = ResourceClass.STICKER_IMAGE,
            ),
            derived.rawKey,
        )
    }

    @Test
    fun geometryProgramKeyMatchesExactRootAndHasNoRawStoreKey() {
        val derived = ResourceKeyDeriver().geometryProgram(ShaderPair("v", "f"))

        assertEquals(
            "524e47430103000100000002000200020000000200010003000000017600040000000166",
            derived.identity.canonicalBytes.fixtureLowercaseHex(),
        )
        assertEquals(
            "8b639140035249737f3f95cee835e93fe8fd2124b91cde5acaf6ee537a573df2",
            derived.identity.digest.lowercaseHex,
        )
        assertEquals(
            ResourceKey(
                kind = ResourceKind.GEOMETRY_PROGRAM,
                stableId = derived.identity.digest.lowercaseHex,
                resourceClass = null,
            ),
            derived.key,
        )
        assertNull(derived.rawKey)
    }

    @Test
    fun exactLocatorUnicodeRemainsUnnormalizedAndDerivationDoesNotRegisterIdentity() {
        val deriver = ResourceKeyDeriver(CONSTANT_SHA256)
        val nfc = deriver.external(ResourceClass.MODEL_TEXTURE, ResourceLocator("é"))
        val nfd = deriver.external(ResourceClass.MODEL_TEXTURE, ResourceLocator("é"))

        assertEquals(nfc.identity.digest, nfd.identity.digest)
        assertNotEquals(nfc.identity.canonicalBytes, nfd.identity.canonicalBytes)
        val nfcBytes = nfc.identity.canonicalBytes.bytes
        val nfdBytes = nfd.identity.canonicalBytes.bytes
        assertEquals("c3a9", nfcBytes.copyOfRange(nfcBytes.size - 2, nfcBytes.size).toFixtureLowercaseHex())
        assertEquals("65cc81", nfdBytes.copyOfRange(nfdBytes.size - 3, nfdBytes.size).toFixtureLowercaseHex())
    }

    @Test
    fun geometryDerivationDoesNotRegisterCollidingIdentity() {
        val deriver = ResourceKeyDeriver(CONSTANT_SHA256)
        val first = deriver.geometryProgram(ShaderPair("v1", "f"))
        val second = deriver.geometryProgram(ShaderPair("v2", "f"))

        assertEquals(first.identity.digest, second.identity.digest)
        assertNotEquals(first.identity.canonicalBytes, second.identity.canonicalBytes)
        assertEquals(first.key, second.key)
        assertNull(first.rawKey)
        assertNull(second.rawKey)
    }

    @Test
    fun derivedResourceKeyHasStructuralValueSemantics() {
        val first = ResourceKeyDeriver().external(ResourceClass.MODEL_GLB, ResourceLocator("model.glb"))
        val equal = ResourceKeyDeriver().external(ResourceClass.MODEL_GLB, ResourceLocator("model.glb"))
        val different = ResourceKeyDeriver().external(ResourceClass.MODEL_GLB, ResourceLocator("other.glb"))

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, different)
    }

    private companion object {
        val CONSTANT_SHA256: Sha256Function = Sha256Function {
            Sha256Digest(ByteArray(32) { 0x5a })
        }
    }
}
