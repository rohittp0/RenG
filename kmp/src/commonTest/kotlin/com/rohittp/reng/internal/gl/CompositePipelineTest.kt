package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompositePipelineTest {
    @Test fun floatsEncodeLittleEndianBecauseEveryPublishedTargetIsLittleEndian() {
        assertContentEquals(
            byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3f),
            littleEndianBytes(floatArrayOf(1.0f)),
        )
        assertContentEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80.toByte(), 0xbf.toByte()),
            littleEndianBytes(floatArrayOf(0.0f, -1.0f)),
        )
        assertEquals(64, littleEndianBytes(COMPOSITE_QUAD).size)
    }

    @Test fun theCompositeSourcesAreAcceptedShaderProfileSources() {
        assertTrue(COMPOSITE_VERTEX_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(COMPOSITE_FRAGMENT_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(scanShaderProfile(COMPOSITE_VERTEX_SOURCE) != null)
        assertTrue(scanShaderProfile(COMPOSITE_FRAGMENT_SOURCE) != null)
    }

    @Test fun creationBuildsAProgramAQuadAndTwoAttributes() {
        val binding = RecordingGlBinding()
        val pipeline = createCompositePipeline(binding, ShaderDialect.GLES, GlProgramCache())
            as CompositePipelineResult.Created
        assertTrue(pipeline.pipeline.program > 0)
        assertTrue(pipeline.pipeline.vertexArray > 0)
        assertTrue(pipeline.pipeline.vertexBuffer > 0)
        assertEquals(2, binding.log.count { it.startsWith("enableVertexAttribArray") })
        assertEquals(2, binding.log.count { it.startsWith("vertexAttribPointer") })
        assertTrue(binding.log.any { it.startsWith("bufferData(0x8892,64") })
    }

    @Test fun deletionRemovesTheQuadAndTheProgram() {
        val binding = RecordingGlBinding()
        val cache = GlProgramCache()
        val pipeline =
            (createCompositePipeline(binding, ShaderDialect.GLES, cache) as CompositePipelineResult.Created).pipeline
        binding.log.clear()
        deleteCompositePipeline(binding, cache, pipeline)
        assertEquals(1, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(1, binding.log.count { it.startsWith("deleteBuffers") })
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram") })
        assertEquals(null, cache.program(pipeline.key))
    }
}
