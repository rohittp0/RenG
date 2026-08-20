package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.internal.lifecycle.DeferredDeletion
import com.rohittp.reng.internal.lifecycle.DeletionId

internal enum class GlObjectType {
    TEXTURE,
    RENDERBUFFER,
    FRAMEBUFFER,
    BUFFER,
    VERTEX_ARRAY,
    SAMPLER,
    PROGRAM,
}

internal data class GlObjectHandle(val type: GlObjectType, val name: Int)

/**
 * A single-use claim that keeps a [GlObjectRegistry.registerTexture] entry exempt from
 * byte-budgeted eviction, mirroring [com.rohittp.reng.internal.cache.Lease]'s shape at the GPU
 * layer: Cycle B's lease answers what MUST stay resident, this one answers the same question for
 * the GL texture behind it. Consumed by exactly one matching [GlObjectRegistry.releaseLease] call;
 * releasing it again is a caller error rather than a silent no-op.
 */
internal class TextureLease internal constructor(internal val key: ResourceKey) {
    private var released: Boolean = false

    internal fun markReleased() {
        require(!released) { "a GL texture lease cannot be released more than once" }
        released = true
    }
}

/**
 * Owns every live GL object handle this renderer holds, plus a byte-budgeted, least-recently-used
 * residency policy over [registerTexture]'s budget-tracked textures specifically (a basemap tile
 * today; [register] remains the unbounded path Cycle F-1's sticker/geometry-consumer textures
 * already use, and this class does not change that).
 *
 * Cycle B's lease machinery already answers what MUST stay resident -- a texture a live Prepared
 * Frame still leases is never a candidate here. [residentTextureByteBudget] answers the separate
 * question of what MAY stay: an unleased, budget-tracked texture survives losing its lease so a
 * pan back over the same tile costs nothing, up to that byte budget, beyond which the
 * least-recently-used unleased texture is evicted first. Bytes rather than a texture count,
 * because memory is what actually runs out and a count means something different at every tile
 * size and on every device -- see [ResourceLimits.maximumResidentGpuTextureBytes].
 */
internal class GlObjectRegistry(
    private val residentTextureByteBudget: Long = ResourceLimits().maximumResidentGpuTextureBytes,
) {
    private val live: LinkedHashMap<ResourceKey, MutableList<GlObjectHandle>> = LinkedHashMap()
    private val queued: LinkedHashMap<DeletionId, List<GlObjectHandle>> = LinkedHashMap()

    // Budget-tracked texture residency (registerTexture/releaseLease only). textureByteSizes
    // covers every budget-tracked key regardless of lease state, so its sum is the true resident
    // total; unleasedOrder holds only the currently-unleased subset, oldest (least-recently-used)
    // first, and is exactly the eviction candidate list.
    private val textureByteSizes: MutableMap<ResourceKey, Long> = mutableMapOf()
    private val textureLeaseCounts: MutableMap<ResourceKey, Int> = mutableMapOf()
    private val unleasedOrder: LinkedHashMap<ResourceKey, Unit> = LinkedHashMap()

    internal fun register(key: ResourceKey, handles: List<GlObjectHandle>) {
        live.getOrPut(key) { mutableListOf() }.addAll(handles)
    }

    /**
     * Registers a budget-tracked texture and returns the caller's [TextureLease] on it. The entry
     * starts leased -- registration itself is a use -- so it can never be evicted until that lease
     * (and every other outstanding one) is released; see [releaseLease].
     */
    internal fun registerTexture(key: ResourceKey, handle: GlObjectHandle, byteSize: Long): TextureLease {
        require(byteSize >= 0L) { "byteSize must be non-negative" }
        live.getOrPut(key) { mutableListOf() }.add(handle)
        textureByteSizes[key] = byteSize
        textureLeaseCounts[key] = (textureLeaseCounts[key] ?: 0) + 1
        unleasedOrder.remove(key) // re-leasing a currently-unleased entry withdraws it from eviction.
        return TextureLease(key)
    }

    /**
     * Releases [lease]. If this was the key's last outstanding lease, the entry becomes evictable
     * and moves to the most-recently-used end of the LRU order, then [evictOverBudget] runs.
     *
     * Eviction deletes GL textures, and ADR 0015 requires the renderer's exact GL context to be
     * current for any GL delete call -- [binding] is threaded through for exactly that call, so
     * this method must only ever be invoked from an operation that has already confirmed the exact
     * context is current, the same discipline [deleteGlObjects] itself already assumes throughout
     * this file.
     */
    internal fun releaseLease(lease: TextureLease, binding: GlBinding) {
        lease.markReleased()
        val key = lease.key
        val remaining = (textureLeaseCounts[key] ?: 0) - 1
        check(remaining >= 0) { "cannot release a texture lease with no outstanding lease" }
        textureLeaseCounts[key] = remaining
        if (remaining > 0) return
        unleasedOrder.remove(key)
        unleasedOrder[key] = Unit
        evictOverBudget(binding)
    }

    /** Marks [key] most-recently-used without changing its lease state; a no-op if not currently unleased. */
    internal fun touch(key: ResourceKey) {
        if (unleasedOrder.remove(key) != null) {
            unleasedOrder[key] = Unit
        }
    }

    /** The resident [GlObjectHandle] for [key], whether registered via [register] or [registerTexture]. */
    internal fun resident(key: ResourceKey): GlObjectHandle? =
        live[key]?.firstOrNull { it.type == GlObjectType.TEXTURE }

    internal fun handles(key: ResourceKey): List<GlObjectHandle> = ArrayList(live[key].orEmpty())

    internal fun liveKeys(): List<ResourceKey> = ArrayList(live.keys)

    internal fun hasLiveGpuObjects(): Boolean = live.values.any { it.isNotEmpty() }

    internal fun defer(key: ResourceKey, id: DeletionId): DeferredDeletion? {
        val handles = live.remove(key) ?: return null
        queued[id] = ArrayList(handles)
        textureByteSizes.remove(key)
        textureLeaseCounts.remove(key)
        unleasedOrder.remove(key)
        return DeferredDeletion(id = id, resourceKey = key)
    }

    internal fun takeQueued(id: DeletionId): List<GlObjectHandle> = queued.remove(id).orEmpty()

    /**
     * Declared GPU object loss: forget every live and queued handle without issuing a delete.
     *
     * A replacement context cannot delete handles from the lost one, and object names there may
     * refer to unrelated live state, so this method must never call the binding. This is the whole
     * reason residency lives here rather than on `ResidentCache`: forgetting the GL name costs the
     * next draw a re-upload, never a re-fetch, because the decoded image this texture came from is
     * untouched in `ResidentCache`, which survives context loss intact (ADRs 0007, 0015).
     */
    internal fun forgetEverything() {
        live.clear()
        queued.clear()
        textureByteSizes.clear()
        textureLeaseCounts.clear()
        unleasedOrder.clear()
    }

    /**
     * Evicts the least-recently-used unleased budget-tracked textures, oldest first, until resident
     * bytes fit [residentTextureByteBudget] or no unleased candidate remains. A texture with an
     * outstanding lease is never a candidate: it was never added to [unleasedOrder] (or was removed
     * from it by [registerTexture]'s re-lease guard), so it is structurally unreachable here
     * regardless of how far its bytes push the total over budget -- exceeding the budget because a
     * live Prepared Frame still needs a tile is the correct outcome; breaking a drawable frame to
     * honour a cache limit is not.
     */
    private fun evictOverBudget(binding: GlBinding) {
        var total = textureByteSizes.values.sum()
        val iterator = unleasedOrder.keys.iterator()
        while (total > residentTextureByteBudget && iterator.hasNext()) {
            val key = iterator.next()
            iterator.remove()
            val size = textureByteSizes.remove(key) ?: 0L
            textureLeaseCounts.remove(key)
            val handles = live.remove(key).orEmpty()
            deleteGlObjects(binding, handles)
            total -= size
        }
    }
}

internal fun deleteGlObjects(binding: GlBinding, handles: List<GlObjectHandle>) {
    if (handles.isEmpty()) return
    val byType = handles.groupBy { it.type }
    byType[GlObjectType.FRAMEBUFFER]?.let { binding.deleteFramebuffers(it.size, it.names()) }
    byType[GlObjectType.RENDERBUFFER]?.let { binding.deleteRenderbuffers(it.size, it.names()) }
    byType[GlObjectType.TEXTURE]?.let { binding.deleteTextures(it.size, it.names()) }
    byType[GlObjectType.SAMPLER]?.let { binding.deleteSamplers(it.size, it.names()) }
    byType[GlObjectType.VERTEX_ARRAY]?.let { binding.deleteVertexArrays(it.size, it.names()) }
    byType[GlObjectType.BUFFER]?.let { binding.deleteBuffers(it.size, it.names()) }
    byType[GlObjectType.PROGRAM]?.forEach { binding.deleteProgram(it.name) }
}

private fun List<GlObjectHandle>.names(): IntArray = IntArray(size) { this[it].name }
