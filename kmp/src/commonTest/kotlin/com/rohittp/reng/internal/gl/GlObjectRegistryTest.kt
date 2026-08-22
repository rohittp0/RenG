package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.lifecycle.DeletionId
import com.rohittp.reng.internal.lifecycle.ExactContextFact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlObjectRegistryTest {
    private val surfaceKey = ResourceKey(ResourceKind.OFFSCREEN_SURFACE, "a".repeat(64), null)
    private val pipelineKey = ResourceKey(ResourceKind.INTERNAL_PIPELINE, "b".repeat(64), null)

    @Test fun liveHandlesAreReportedUntilTheyAreDeferred() {
        val registry = GlObjectRegistry()
        assertFalse(registry.hasLiveGpuObjects())
        registry.register(surfaceKey, listOf(GlObjectHandle(GlObjectType.TEXTURE, 4)))
        assertTrue(registry.hasLiveGpuObjects())
        val deferred = registry.defer(surfaceKey, DeletionId(1L))
        assertEquals(surfaceKey, deferred?.resourceKey)
        assertFalse(registry.hasLiveGpuObjects())
        assertEquals(listOf(GlObjectHandle(GlObjectType.TEXTURE, 4)), registry.takeQueued(DeletionId(1L)))
    }

    @Test fun gpuObjectLossForgetsEveryHandleAndIssuesNoDelete() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry()
        registry.register(surfaceKey, listOf(GlObjectHandle(GlObjectType.FRAMEBUFFER, 3)))
        registry.register(pipelineKey, listOf(GlObjectHandle(GlObjectType.PROGRAM, 9)))
        registry.defer(pipelineKey, DeletionId(2L))
        registry.forgetEverything()
        assertFalse(registry.hasLiveGpuObjects())
        assertTrue(registry.liveKeys().isEmpty())
        assertTrue(registry.takeQueued(DeletionId(2L)).isEmpty())
        assertTrue(binding.log.isEmpty())
    }

    @Test fun theDeleterGroupsByTypeAndSkipsEmptyGroups() {
        val binding = RecordingGlBinding()
        deleteGlObjects(
            binding,
            listOf(
                GlObjectHandle(GlObjectType.TEXTURE, 1),
                GlObjectHandle(GlObjectType.TEXTURE, 2),
                GlObjectHandle(GlObjectType.PROGRAM, 5),
            ),
        )
        assertEquals(1, binding.log.count { it.startsWith("deleteTextures(2") })
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram(5") })
        assertTrue(binding.log.none { it.startsWith("deleteBuffers") })
        assertTrue(binding.log.none { it.startsWith("deleteRenderbuffers") })
    }

    @Test fun deletingNothingIssuesNothing() {
        val binding = RecordingGlBinding()
        deleteGlObjects(binding, emptyList())
        assertTrue(binding.log.isEmpty())
    }

    @Test fun theProbeDistinguishesExactMissingAndForeignContexts() {
        val adopted = RenderContextIdentity(0x1000L)
        assertEquals(
            ExactContextFact.EXACT,
            exactContextFact(adopted) { RenderContextIdentity(0x1000L) },
        )
        assertEquals(ExactContextFact.NONE, exactContextFact(adopted) { null })
        assertEquals(
            ExactContextFact.DIFFERENT,
            exactContextFact(adopted) { RenderContextIdentity(0x2000L) },
        )
    }

    // ---- Task 1: the resident GPU texture byte budget -----------------------------------------

    @Test fun anUnleasedTextureStaysResidentWhileTheBudgetAllows() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 4 * ONE_TILE_BYTES)
        val lease = registry.registerTexture(tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)

        registry.releaseLease(lease, binding)

        assertEquals(
            GlObjectHandle(GlObjectType.TEXTURE, 100),
            registry.resident(tileKey(0)),
            "a tile must survive losing its lease, or panning re-uploads",
        )
        assertTrue(binding.deletedNames.isEmpty())
    }

    @Test fun theLeastRecentlyUsedUnleasedTextureIsEvictedFirstWhenTheBudgetIsExceeded() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 2 * ONE_TILE_BYTES)
        val lease0 = registry.registerTexture(tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        registry.releaseLease(lease0, binding)
        val lease1 = registry.registerTexture(tileKey(1), GlObjectHandle(GlObjectType.TEXTURE, 101), ONE_TILE_BYTES)
        registry.releaseLease(lease1, binding)

        registry.touch(tileKey(0)) // 1 becomes least-recently-used

        val lease2 = registry.registerTexture(tileKey(2), GlObjectHandle(GlObjectType.TEXTURE, 102), ONE_TILE_BYTES)
        registry.releaseLease(lease2, binding)

        assertEquals(GlObjectHandle(GlObjectType.TEXTURE, 100), registry.resident(tileKey(0)))
        assertNull(registry.resident(tileKey(1)), "the least recently used unleased tile is evicted first")
        assertEquals(GlObjectHandle(GlObjectType.TEXTURE, 102), registry.resident(tileKey(2)))
        assertEquals(
            listOf(101),
            binding.deletedNames,
            "eviction must delete exactly the evicted tile's own GL name, no more and no less",
        )
    }

    @Test fun aLeasedTextureIsNeverEvictedEvenWhenThatExceedsTheBudget() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = ONE_TILE_BYTES)
        // Held leased, never released.
        registry.registerTexture(tileKey(0), GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        val lease1 = registry.registerTexture(tileKey(1), GlObjectHandle(GlObjectType.TEXTURE, 101), ONE_TILE_BYTES)

        registry.releaseLease(lease1, binding)

        assertEquals(
            GlObjectHandle(GlObjectType.TEXTURE, 100),
            registry.resident(tileKey(0)),
            "a live PreparedFrame's tile must outrank the budget",
        )
        assertFalse(100 in binding.deletedNames, "the leased tile's GL name must never be deleted")
    }

    // ---- Task E-H: defer() must honour a lease the same way eviction does ----------------------

    @Test fun deferringALeasedTextureRetiresItInsteadOfQueuingItForDeletion() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 4 * ONE_TILE_BYTES)
        val key = tileKey(0)
        val lease = registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)

        // The sequence a consumer-triggered free would take against a tile an in-flight draw is
        // sampling. Nothing wires it today (see GlObjectRegistry.defer's KDoc), but the lease must
        // outrank this deletion path exactly as it outranks eviction.
        assertNull(
            registry.defer(key, DeletionId(1L)),
            "a leased texture must not produce a ledger entry the lifecycle machine would delete",
        )

        assertEquals(
            GlObjectHandle(GlObjectType.TEXTURE, 100),
            registry.resident(key),
            "the draw still sampling this texture must still find it",
        )
        assertTrue(
            registry.takeQueued(DeletionId(1L)).isEmpty(),
            "no handle of a leased texture may be queued for deferred deletion",
        )
        assertTrue(binding.deletedNames.isEmpty(), "nothing may be deleted while the lease is open")

        // The draw ends, the lease goes, and only now does the retired texture die.
        registry.releaseLease(lease, binding)

        assertEquals(listOf(100), binding.deletedNames, "the last release deletes the retired texture")
        assertNull(registry.resident(key))
        assertTrue(registry.liveKeys().isEmpty())
    }

    @Test fun aRetiredTextureSurvivesEveryLeaseButTheLast() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 4 * ONE_TILE_BYTES)
        val key = tileKey(0)
        val first = registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        val second = registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 101), ONE_TILE_BYTES)

        registry.defer(key, DeletionId(1L))
        registry.releaseLease(first, binding)

        assertTrue(binding.deletedNames.isEmpty(), "one released lease of two is not the last one")
        assertNotNull(registry.resident(key))

        registry.releaseLease(second, binding)
        assertEquals(
            listOf(100, 101),
            binding.deletedNames,
            "the last release deletes every handle the retired key still holds",
        )
    }

    @Test fun renderCloseDeletesATextureWhoseDeletionALeaseIsStillDelaying() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 4 * ONE_TILE_BYTES)
        val key = tileKey(0)
        registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        registry.defer(key, DeletionId(1L))

        // Exactly RenGRenderer.close()'s own sweep: every key liveKeys() still reports, deleted once.
        registry.liveKeys().forEach { live -> deleteGlObjects(binding, registry.handles(live)) }

        assertEquals(
            listOf(100),
            binding.deletedNames,
            "a lease may delay a deletion, never survive the renderer that issued it",
        )
    }

    @Test fun reRegisteringARetiredKeyRevivesIt() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 4 * ONE_TILE_BYTES)
        val key = tileKey(0)
        val stale = registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        registry.defer(key, DeletionId(1L))

        // The reload half of "accessing a freed resource reloads it": a fresh upload under the same key.
        val reloaded = registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 101), ONE_TILE_BYTES)
        registry.releaseLease(stale, binding)
        registry.releaseLease(reloaded, binding)

        assertTrue(binding.deletedNames.isEmpty(), "the reload cancels the pending deletion")
        assertNotNull(registry.resident(key))
    }

    @Test fun contextLossForgetsTexturesWhileTheDecodedImageStaysLeased() {
        val registry = GlObjectRegistry()
        val residentCache = ResidentCache()
        val key = tileKey(0)
        registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, 100), ONE_TILE_BYTES)
        val stored = StoredRawResource(
            bytes = "tile-bytes".encodeToByteArray(),
            contentDigest = "d".repeat(64),
            metadata = StoredRawResourceMetadata(storedAtEpochMillis = 1L),
        )
        val decoded = DecodedImage(width = 1, height = 1, rgba = ByteArray(4))
        val generation = residentCache.install(key, stored, decoded)
        residentCache.takeLease(generation)

        registry.forgetEverything()

        assertNull(registry.resident(key), "the GL name is meaningless after context loss")
        assertNotNull(residentCache.current(key), "the decoded pixels are still valid and leased")
    }
}

private const val ONE_TILE_BYTES: Long = 512L * 512L * 4L

private fun tileKey(index: Int): ResourceKey =
    ResourceKey(ResourceKind.BASEMAP_TILE, index.toString().repeat(64).take(64), null)
