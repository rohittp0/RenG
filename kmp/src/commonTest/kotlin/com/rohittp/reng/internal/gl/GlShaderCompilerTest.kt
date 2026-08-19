package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VERTEX_SOURCE: String = "#version 300 es\nvoid main() { gl_Position = vec4(0.0); }\n"
private const val FRAGMENT_SOURCE: String =
    "#version 300 es\nprecision highp float;\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n"

class GlShaderCompilerTest {
    private val deriver = ResourceKeyDeriver()
    private val pair = ShaderPair(VERTEX_SOURCE, FRAGMENT_SOURCE)
    private val key = deriver.geometryProgram(pair).key
    private val vertexPlan = requireNotNull(scanShaderProfile(VERTEX_SOURCE))
    private val fragmentPlan = requireNotNull(scanShaderProfile(FRAGMENT_SOURCE))

    @Test fun anEsContextCompilesTheSourceUnchanged() {
        val binding = RecordingGlBinding()
        compileShaderProgram(binding, ShaderDialect.GLES, key, vertexPlan, fragmentPlan)
        assertEquals(2, binding.shaderSources.size)
        assertTrue(binding.shaderSources.values.all { it.startsWith("#version 300 es") })
        assertTrue(binding.shaderSources.values.none { "#version 330 core" in it })
    }

    @Test fun aDesktopContextSubstitutesExactlyTheDirectiveLine() {
        val binding = RecordingGlBinding()
        compileShaderProgram(binding, ShaderDialect.DESKTOP, key, vertexPlan, fragmentPlan)
        assertTrue(binding.shaderSources.values.all { it.startsWith("#version 330 core") })
        assertTrue(binding.shaderSources.values.none { "#version 300 es" in it })
        assertEquals(
            "#version 330 core\nvoid main() { gl_Position = vec4(0.0); }\n",
            binding.shaderSources.values.first { "gl_Position" in it },
        )
    }

    @Test fun theDialectIsTheOnlyInputToSourceSelection() {
        assertEquals(VERTEX_SOURCE, vertexPlan.sourceFor(ShaderDialect.GLES))
        assertEquals(
            "#version 330 core\nvoid main() { gl_Position = vec4(0.0); }\n",
            vertexPlan.sourceFor(ShaderDialect.DESKTOP),
        )
    }

    @Test fun aCompileFailureIsTypedAndKeepsTheDriverLogOffTheBoundary() {
        val binding = RecordingGlBinding()
        binding.compileStatus = 0
        binding.shaderInfoLog = "0:1(10): error: GLSL 3.30 is not supported. sensitive-path/shader.frag"
        var observed = ""
        val result = compileShaderProgram(
            binding, ShaderDialect.GLES, key, vertexPlan, fragmentPlan,
        ) { _, log -> observed = log }
        val failed = result as GlProgramResult.Failed
        assertEquals(RenGErrorCode.SHADER_COMPILE_FAILED, failed.failure.code)
        assertEquals(PipelineStage.SHADER_COMPILATION, failed.failure.stage)
        val diagnostic = assertNotNull(failed.failure.diagnostic)
        assertEquals("shaderPair", diagnostic.fieldName)
        assertEquals(key, diagnostic.resourceKey)
        assertTrue("GLSL 3.30" in observed)
        assertTrue("GLSL 3.30" !in failed.failure.toString())
        assertTrue("sensitive-path" !in failed.failure.toString())
        assertTrue(binding.log.any { it.startsWith("deleteShader") })
    }

    @Test fun aLinkFailureIsTypedAndDeletesEverythingItCreated() {
        val binding = RecordingGlBinding()
        binding.linkStatus = 0
        val failed = compileShaderProgram(
            binding, ShaderDialect.DESKTOP, key, vertexPlan, fragmentPlan,
        ) as GlProgramResult.Failed
        assertEquals(RenGErrorCode.SHADER_LINK_FAILED, failed.failure.code)
        assertTrue(binding.log.any { it.startsWith("deleteProgram") })
        assertEquals(2, binding.log.count { it.startsWith("deleteShader") })
    }

    @Test fun anInternalPipelineFailureIsAGpuFailureRatherThanAConsumerShaderFailure() {
        val internalKey = deriver.internalPipeline(InternalPipelineRole.COMPOSITE, pair).key
        assertEquals(ResourceKind.INTERNAL_PIPELINE, internalKey.kind)
        val failure = shaderProgramFailure(RenGErrorCode.SHADER_COMPILE_FAILED, internalKey)
        assertEquals(RenGErrorCode.GPU_OPERATION_FAILED, failure.code)
        assertEquals(PipelineStage.GPU_RESOURCE, failure.stage)
        assertNull(assertNotNull(failure.diagnostic).fieldName)
    }

    @Test fun aCachedProgramIsNotCompiledTwice() {
        val binding = RecordingGlBinding()
        val cache = GlProgramCache()
        val first = cache.getOrCompile(binding, ShaderDialect.GLES, key, vertexPlan, fragmentPlan)
        val second = cache.getOrCompile(binding, ShaderDialect.GLES, key, vertexPlan, fragmentPlan)
        assertEquals(first, second)
        assertEquals(1, binding.log.count { it.startsWith("createProgram") })
        assertEquals(2, binding.log.count { it.startsWith("createShader") })
    }

    @Test fun forgettingTheCacheForcesRecompilationAfterContextLoss() {
        val binding = RecordingGlBinding()
        val cache = GlProgramCache()
        cache.getOrCompile(binding, ShaderDialect.GLES, key, vertexPlan, fragmentPlan)
        cache.forgetAll()
        cache.getOrCompile(binding, ShaderDialect.DESKTOP, key, vertexPlan, fragmentPlan)
        assertEquals(2, binding.log.count { it.startsWith("createProgram") })
        assertTrue(binding.log.none { it.startsWith("deleteProgram") })
    }
}
