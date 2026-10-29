package com.rohittp.reng

import com.rohittp.reng.internal.gl.GlObjectHandle
import com.rohittp.reng.internal.gl.GlObjectType
import com.rohittp.reng.internal.gl.UploadedPrimitive

/**
 * Every GL object one [UploadedPrimitive] holds, as registry handles: its vertex array, one buffer per
 * attribute the primitive actually carries, and its index buffer.
 *
 * The index buffer is listed separately and deliberately, for the reason `deleteUploadedPrimitive`
 * gives: deleting a vertex array frees the vertex array object alone and never the buffers it recorded
 * bindings to, so an index buffer left off this list is a permanent leak with no GL error to find it by.
 */
internal fun primitiveHandles(primitive: UploadedPrimitive): List<GlObjectHandle> =
    buildList(primitive.buffers.size + 2) {
        add(GlObjectHandle(GlObjectType.VERTEX_ARRAY, primitive.vertexArray))
        primitive.buffers.forEach { add(GlObjectHandle(GlObjectType.BUFFER, it)) }
        add(GlObjectHandle(GlObjectType.BUFFER, primitive.indexBuffer))
    }
