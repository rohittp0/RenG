package com.rohittp.reng.internal.identity

import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.gl.InternalPipelineRole
import com.rohittp.reng.internal.gl.OffscreenColourFormat
import com.rohittp.reng.internal.gl.OffscreenDepthFormat
import com.rohittp.reng.internal.gl.OffscreenSurfaceDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class InternalResourceKeyTest {
    private val deriver = ResourceKeyDeriver()

    @Test fun offscreenSurfaceBytesAreFrozen() {
        val derived = deriver.offscreenSurface(
            OffscreenSurfaceDescriptor(
                widthPixels = 2,
                heightPixels = 3,
                colourFormat = OffscreenColourFormat.RGBA8,
                depthFormat = OffscreenDepthFormat.DEPTH_COMPONENT24,
            ),
        )
        val expected = "524e47430105" +
            "0001000000020004" +
            "0002000000080000000000000002" +
            "0003000000080000000000000003" +
            "0004000000020001" +
            "0005000000020001"
        assertEquals(expected, derived.identity.canonicalBytes.bytes.toLowercaseHex())
        assertEquals(ResourceKind.OFFSCREEN_SURFACE, derived.key.kind)
        assertNull(derived.key.resourceClass)
        assertNull(derived.rawKey)
    }

    @Test fun internalPipelineBytesAreFrozen() {
        val derived = deriver.internalPipeline(
            role = InternalPipelineRole.COMPOSITE,
            shaderPair = ShaderPair(vertexSource = "a", fragmentSource = "b"),
        )
        val expected = "524e47430104" +
            "0001000000020003" +
            "0002000000020001" +
            "0003000000020001" +
            "00040000000161" +
            "00050000000162"
        assertEquals(expected, derived.identity.canonicalBytes.bytes.toLowercaseHex())
        assertEquals(ResourceKind.INTERNAL_PIPELINE, derived.key.kind)
        assertNull(derived.key.resourceClass)
        assertNull(derived.rawKey)
    }

    @Test fun identitiesAreStableAndSeparateAcrossDescriptors() {
        val first = deriver.offscreenSurface(
            OffscreenSurfaceDescriptor(2, 3, OffscreenColourFormat.RGBA8, OffscreenDepthFormat.DEPTH_COMPONENT24),
        )
        val again = deriver.offscreenSurface(
            OffscreenSurfaceDescriptor(2, 3, OffscreenColourFormat.RGBA8, OffscreenDepthFormat.DEPTH_COMPONENT24),
        )
        val other = deriver.offscreenSurface(
            OffscreenSurfaceDescriptor(3, 2, OffscreenColourFormat.RGBA8, OffscreenDepthFormat.DEPTH_COMPONENT24),
        )
        assertEquals(first.key.stableId, again.key.stableId)
        assertNotEquals(first.key.stableId, other.key.stableId)
        assertNotEquals(
            first.key.stableId,
            deriver.internalPipeline(
                InternalPipelineRole.COMPOSITE,
                ShaderPair(vertexSource = "a", fragmentSource = "b"),
            ).key.stableId,
        )
    }
}

private fun ByteArray.toLowercaseHex(): String = buildString(size * 2) {
    this@toLowercaseHex.forEach { byte ->
        append("0123456789abcdef"[(byte.toInt() ushr 4) and 0x0f])
        append("0123456789abcdef"[byte.toInt() and 0x0f])
    }
}
