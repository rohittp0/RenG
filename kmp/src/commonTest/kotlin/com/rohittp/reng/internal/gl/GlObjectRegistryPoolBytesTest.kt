package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.internal.cache.ResidentGenerationId
import com.rohittp.reng.internal.lifecycle.DeletionId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [GlObjectRegistry] keeps running totals of its three byte pools instead of re-summing them on every
 * release. These tests drive every path that writes the pools -- register, lease, release, evict,
 * retire, defer, context loss and close -- and recount after each step.
 */
class GlObjectRegistryPoolBytesTest {
    @Test fun runningPoolTotalsMatchARecountAfterEveryOperationWithOneSharedPool() {
        walk(GlObjectRegistry(residentTextureByteBudget = 40L, residentBufferByteBudget = 30L), seed = 1)
    }

    @Test fun runningPoolTotalsMatchARecountAfterEveryOperationWithSplitPools() {
        walk(
            GlObjectRegistry(
                residentTextureByteBudget = 40L,
                residentBufferByteBudget = 30L,
                residentContentTextureByteBudget = 35L,
            ),
            seed = 2,
        )
    }

    // The eviction cursor skips a buffer-only allocation while looking for texture bytes; the older
    // texture-holding allocation behind it must still go before the newer tile.
    @Test fun aSharedPoolEvictsTheOlderOfTileAndTextureAllocationPastABufferOnlyOne() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry(residentTextureByteBudget = 10L, residentBufferByteBudget = 100L)
        val bufferOnly = identity(owner(0), 0, primitive = true)
        val texture = identity(owner(1), 0, primitive = false)
        registry.releaseAllocation(registry.registerAllocation(bufferOnly, handle(1, GlObjectType.BUFFER), 0L, 4L, 1), binding)
        registry.releaseAllocation(registry.registerAllocation(texture, handle(2, GlObjectType.TEXTURE), 6L, 0L, 2), binding)
        registry.releaseLease(registry.registerTexture(tile(0), GlObjectHandle(GlObjectType.TEXTURE, 3), 6L), binding)

        assertNull(registry.leaseAllocation(texture), "the older texture allocation is evicted")
        assertNotNull(registry.leaseResident(tile(0)), "the newer tile stays")
        assertNotNull(registry.leaseAllocation(bufferOnly), "a buffer-only allocation is no texture candidate")
        assertConsistent(registry)
    }

    private fun walk(registry: GlObjectRegistry, seed: Int) {
        val random = Random(seed)
        val binding = RecordingGlBinding()
        val textureLeases = mutableListOf<TextureLease>()
        val allocationLeases = mutableListOf<GpuAllocationLease>()
        var generation = 1L
        var name = 1
        repeat(4_000) { step ->
            when (random.nextInt(10)) {
                0, 1 -> {
                    val key = tile(random.nextInt(8))
                    val lease = registry.leaseResident(key)?.lease
                        ?: registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, name++), random.nextLong(1L, 9L))
                    textureLeases += lease
                }
                2, 3 -> {
                    val identity = identity(owner(random.nextInt(6)), random.nextInt(3), random.nextBoolean(), generation)
                    val lease = registry.leaseAllocation(identity)?.lease ?: registry.registerAllocation(
                        identity,
                        handle(name++, GlObjectType.TEXTURE),
                        textureBytes = random.nextLong(0L, 7L),
                        bufferBytes = random.nextLong(1L, 7L),
                        payload = name,
                    )
                    allocationLeases += lease
                }
                4, 5 -> if (textureLeases.isNotEmpty()) {
                    registry.releaseLease(textureLeases.removeAt(random.nextInt(textureLeases.size)), binding)
                }
                6, 7 -> if (allocationLeases.isNotEmpty()) {
                    registry.releaseAllocation(allocationLeases.removeAt(random.nextInt(allocationLeases.size)), binding)
                }
                8 -> when (random.nextInt(4)) {
                    0 -> {
                        val owner = owner(random.nextInt(6))
                        registry.retireResources(
                            ResourceSelector.ByKey(owner),
                            mapOf(owner to setOf(ResidentGenerationId(generation))),
                            binding,
                        )
                        generation += 1L
                    }
                    1 -> registry.retireResources(ResourceSelector.ByKey(tile(random.nextInt(8))), emptyMap(), binding)
                    else -> registry.defer(tile(random.nextInt(8)), DeletionId(step.toLong()))
                }
                else -> if (random.nextInt(20) == 0) {
                    if (random.nextBoolean()) registry.forgetEverything() else registry.takeAllHandlesForDeletion()
                }
            }
            assertConsistent(registry)
        }
        textureLeases.forEach { registry.releaseLease(it, binding) }
        allocationLeases.forEach { registry.releaseAllocation(it, binding) }
        assertConsistent(registry)
    }

    private fun assertConsistent(registry: GlObjectRegistry) {
        assertEquals(registry.recountPoolBytes(), registry.poolBytes(), "running pool totals drifted from a recount")
    }

    private fun tile(index: Int): ResourceKey =
        ResourceKey(ResourceKind.BASEMAP_TILE, index.toString().repeat(64).take(64), null)

    private fun owner(index: Int): ResourceKey =
        ResourceKey(ResourceKind.EXTERNAL, ('a' + index).toString().repeat(64), ResourceClass.MODEL_GLB)

    private fun identity(owner: ResourceKey, index: Int, primitive: Boolean, generation: Long = 1L) =
        GpuResourceIdentity(
            ownerKey = owner,
            generationId = ResidentGenerationId(generation),
            subresource = if (primitive) GpuSubresource.ModelPrimitive(0, index) else GpuSubresource.ModelImage(index),
        )

    private fun handle(name: Int, type: GlObjectType) = listOf(GlObjectHandle(type, name))
}
