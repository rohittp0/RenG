package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.internal.bench.RengBench
import com.rohittp.reng.internal.cache.ResidentGenerationId
import kotlin.test.Test

/**
 * B1: what releasing one frame's GPU leases costs [GlObjectRegistry].
 *
 * A frame of [TILES] basemap tile textures ([GlObjectRegistry.registerTexture] /
 * [GlObjectRegistry.leaseResident]) and [ALLOCATIONS] generation-aware allocations -- sticker textures,
 * plus a texture and a vertex buffer per model -- is leased the way a draw leases it, untimed: a resident
 * entry is leased, an evicted one is registered again as a re-upload would. Then every lease is released,
 * timed, in that order. Each release re-sums the pools as it stands at 30b5e1d, which is the cost this
 * measures.
 *
 * Two shapes: a registry with room for everything (no eviction ever runs), and one whose three pools
 * each hold three quarters of the frame, so the releases evict and the next frame re-registers.
 */
class GlObjectRegistryReleaseBenchmarkTest {
    @Test
    fun releasingAFramesLeases() {
        measure("b1.gl_registry.release_frame.under_budget", overBudget = false)
        measure("b1.gl_registry.release_frame.over_budget", overBudget = true)
    }

    private fun measure(name: String, overBudget: Boolean) {
        val registry = if (overBudget) {
            GlObjectRegistry(
                residentTextureByteBudget = TILES * TILE_BYTES * 3 / 4,
                residentBufferByteBudget = MODELS * MODEL_BUFFER_BYTES * 3 / 4,
                residentContentTextureByteBudget = (STICKERS * STICKER_BYTES + MODELS * MODEL_TEXTURE_BYTES) * 3 / 4,
            )
        } else {
            GlObjectRegistry(
                residentTextureByteBudget = Long.MAX_VALUE / 4,
                residentBufferByteBudget = Long.MAX_VALUE / 4,
                residentContentTextureByteBudget = Long.MAX_VALUE / 4,
            )
        }
        val tiles = List(TILES) { tileKey(it) }
        val allocations = allocationShapes()
        var nextName = 1
        var reRegistered = 0L
        RengBench.info(name, "tiles=$TILES allocations=${allocations.size} (stickers=$STICKERS models=$MODELS x2)")
        RengBench.measure(name, warmupIterations = 5, minimumIterations = 20) {
            val textureLeases = tiles.map { key ->
                registry.leaseResident(key)?.lease ?: run {
                    reRegistered += 1
                    registry.registerTexture(key, GlObjectHandle(GlObjectType.TEXTURE, nextName++), TILE_BYTES)
                }
            }
            val allocationLeases = allocations.map { shape ->
                registry.leaseAllocation(shape.identity)?.lease ?: run {
                    reRegistered += 1
                    registry.registerAllocation(
                        shape.identity,
                        listOf(GlObjectHandle(shape.type, nextName++)),
                        shape.textureBytes,
                        shape.bufferBytes,
                        shape,
                    )
                }
            }
            val binding = RecordingGlBinding()
            timed {
                for (lease in textureLeases) fold(registry.releaseLease(lease, binding).residentBytes)
                for (lease in allocationLeases) fold(registry.releaseAllocation(lease, binding).textureBytes)
            }
            fold(binding.log.size)
        }
        RengBench.info(name, "re-registered=$reRegistered")
    }

    private class AllocationShape(
        val identity: GpuResourceIdentity,
        val type: GlObjectType,
        val textureBytes: Long,
        val bufferBytes: Long,
    )

    private fun allocationShapes(): List<AllocationShape> {
        val image = GpuUploadVariant(TextureContent.IMAGE, defaultSamplerStateFor(TextureContent.IMAGE))
        val stickers = List(STICKERS) { index ->
            AllocationShape(
                GpuResourceIdentity(externalKey(0x1000 + index, ResourceClass.STICKER_IMAGE), ResidentGenerationId(1L), GpuSubresource.Texture, image),
                GlObjectType.TEXTURE,
                STICKER_BYTES,
                0L,
            )
        }
        val models = List(MODELS) { index ->
            val owner = externalKey(0x2000 + index, ResourceClass.MODEL_GLB)
            listOf(
                AllocationShape(
                    GpuResourceIdentity(owner, ResidentGenerationId(1L), GpuSubresource.ModelImage(0), image),
                    GlObjectType.TEXTURE,
                    MODEL_TEXTURE_BYTES,
                    0L,
                ),
                AllocationShape(
                    GpuResourceIdentity(owner, ResidentGenerationId(1L), GpuSubresource.ModelPrimitive(0, 0)),
                    GlObjectType.BUFFER,
                    0L,
                    MODEL_BUFFER_BYTES,
                ),
            )
        }.flatten()
        return stickers + models
    }

    private fun tileKey(index: Int): ResourceKey =
        ResourceKey(ResourceKind.BASEMAP_TILE, index.toString(16).padStart(64, '0'), null)

    private fun externalKey(index: Int, resourceClass: ResourceClass): ResourceKey =
        ResourceKey(ResourceKind.EXTERNAL, index.toString(16).padStart(64, 'e'), resourceClass)

    private companion object {
        const val TILES = 500
        const val STICKERS = 150
        const val MODELS = 75
        const val ALLOCATIONS = STICKERS + MODELS * 2
        const val TILE_BYTES = 256L * 256L * 4L
        const val STICKER_BYTES = 256L * 256L * 4L
        const val MODEL_TEXTURE_BYTES = 512L * 512L * 4L
        const val MODEL_BUFFER_BYTES = 256L * 1024L
    }
}
