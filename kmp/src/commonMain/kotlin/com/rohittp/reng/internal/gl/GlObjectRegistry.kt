package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
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

internal class GlObjectRegistry {
    private val live: LinkedHashMap<ResourceKey, MutableList<GlObjectHandle>> = LinkedHashMap()
    private val queued: LinkedHashMap<DeletionId, List<GlObjectHandle>> = LinkedHashMap()

    internal fun register(key: ResourceKey, handles: List<GlObjectHandle>) {
        live.getOrPut(key) { mutableListOf() }.addAll(handles)
    }

    internal fun handles(key: ResourceKey): List<GlObjectHandle> = ArrayList(live[key].orEmpty())

    internal fun liveKeys(): List<ResourceKey> = ArrayList(live.keys)

    internal fun hasLiveGpuObjects(): Boolean = live.values.any { it.isNotEmpty() }

    internal fun defer(key: ResourceKey, id: DeletionId): DeferredDeletion? {
        val handles = live.remove(key) ?: return null
        queued[id] = ArrayList(handles)
        return DeferredDeletion(id = id, resourceKey = key)
    }

    internal fun takeQueued(id: DeletionId): List<GlObjectHandle> = queued.remove(id).orEmpty()

    /**
     * Declared GPU object loss: forget every live and queued handle without issuing a delete.
     *
     * A replacement context cannot delete handles from the lost one, and object names there may
     * refer to unrelated live state, so this method must never call the binding.
     */
    internal fun forgetEverything() {
        live.clear()
        queued.clear()
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
