package com.rohittp.reng.internal.gl

import com.rohittp.reng.BASEMAP_READBACK_PIXELS
import com.rohittp.reng.BLOOM_PER_ELEMENT_PIXELS
import com.rohittp.reng.BLOOM_READBACK_PIXELS
import com.rohittp.reng.DEPTH_READBACK_PIXELS
import com.rohittp.reng.DISPLACEMENT_READBACK_PIXELS
import com.rohittp.reng.GEOMETRY_BLEND_READBACK_PIXELS
import com.rohittp.reng.GEOMETRY_SUBDIVISION_READBACK_PIXELS
import com.rohittp.reng.GLOBE_FRAME_READBACK_PIXELS
import com.rohittp.reng.GLOBE_GROUND_READBACK_PIXELS
import com.rohittp.reng.GROUND_ANCHOR_READBACK_PIXELS
import com.rohittp.reng.GROUND_CULL_READBACK_PIXELS
import com.rohittp.reng.LATITUDE_PROBE_PIXELS
import com.rohittp.reng.MODEL_READBACK_PIXELS
import com.rohittp.reng.SHADING_READBACK_PIXELS
import com.rohittp.reng.STICKER_OPACITY_READBACK_PIXELS
import com.rohittp.reng.STICKER_SHADER_READBACK_PIXELS
import com.rohittp.reng.TERRAIN_FRAME_READBACK_PIXELS
import com.rohittp.reng.clearCurrentWebGl2Context
import com.rohittp.reng.makeWebGl2ContextCurrent
import com.rohittp.reng.runBasemapReadbackSuite
import com.rohittp.reng.runBloomPerElementReadbackSuite
import com.rohittp.reng.runBloomReadbackSuite
import com.rohittp.reng.runGeometryBlendReadbackSuite
import com.rohittp.reng.runGeometrySubdivisionReadbackSuite
import com.rohittp.reng.runGlobeFrameReadbackSuite
import com.rohittp.reng.runGlobeGroundReadbackSuite
import com.rohittp.reng.runGroundAnchorReadbackSuite
import com.rohittp.reng.runGroundCullReadbackSuite
import com.rohittp.reng.runGroundDepthReadback
import com.rohittp.reng.runGroundDisplacementReadback
import com.rohittp.reng.runGroundShadingReadback
import com.rohittp.reng.runLatitudePrecisionProbeSuite
import com.rohittp.reng.runModelReadbackSuite
import com.rohittp.reng.runStickerOpacityReadbackSuite
import com.rohittp.reng.runStickerShaderReadbackSuite
import com.rohittp.reng.runTerrainFrameReadbackSuite
import com.rohittp.reng.runVertexTextureFetchProbeSuite
import kotlin.js.JsAny
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

class WebGl2ConformanceTest {
    @Test
    fun theSuitePassesOnARealWebGl2Context() = runOn(CONFORMANCE_SURFACE_PIXELS) { binding, probe ->
        val report = runGlConformanceSuite(binding, probe, ShaderDialect.GLES)
        assertEquals(ShaderDialect.GLES, report.dialect)
        assertEquals(9, report.checks.size)
        assertTrue(report.shadingLanguageVersionText.startsWith("OpenGL ES GLSL ES"))
        assertTrue(report.versionText.startsWith("OpenGL ES 3"))
        println(
            "RenG WebGL browser: GL_VENDOR=${binding.getString(GL_VENDOR)} " +
                "GL_RENDERER=${binding.getString(GL_RENDERER)} " +
                "GL_VERSION=${binding.getString(GL_VERSION)} " +
                "GL_SHADING_LANGUAGE_VERSION=${binding.getString(GL_SHADING_LANGUAGE_VERSION)}",
        )
    }

    @Test
    fun theModelReadbackSuitePassesOnARealWebGl2Context() = runOn(MODEL_READBACK_PIXELS) { binding, probe ->
        runModelReadbackSuite(binding, probe, ShaderDialect.GLES)
    }

    @Test
    fun theBasemapReadbackSuitePassesOnARealWebGl2Context() = runOn(BASEMAP_READBACK_PIXELS) { binding, probe ->
        runBasemapReadbackSuite(binding, probe, ShaderDialect.GLES)
    }

    @Test
    fun theGroundCullReadbackSuitePassesOnARealWebGl2Context() = runOn(GROUND_CULL_READBACK_PIXELS) { binding, _ ->
        runGroundCullReadbackSuite(binding, ShaderDialect.GLES)
    }

    @Test
    fun theGroundShadingReadbackPassesOnARealWebGl2Context() = runOn(SHADING_READBACK_PIXELS) { binding, _ ->
        runGroundShadingReadback(binding, ShaderDialect.GLES)
    }

    @Test
    fun theGroundDisplacementReadbackPassesOnARealWebGl2Context() = runOn(DISPLACEMENT_READBACK_PIXELS) { binding, _ ->
        runGroundDisplacementReadback(binding, ShaderDialect.GLES)
    }

    @Test
    fun theGroundDepthReadbackPassesOnARealWebGl2Context() = runOn(DEPTH_READBACK_PIXELS) { binding, _ ->
        runGroundDepthReadback(binding, ShaderDialect.GLES)
    }

    @Test
    fun theGeometrySubdivisionReadbackSuitePassesOnARealWebGl2Context() =
        runOn(GEOMETRY_SUBDIVISION_READBACK_PIXELS) { binding, _ ->
            runGeometrySubdivisionReadbackSuite(binding, ShaderDialect.GLES)
        }

    @Test
    fun theGeometryBlendReadbackSuitePassesOnARealWebGl2Context() =
        runOn(GEOMETRY_BLEND_READBACK_PIXELS) { binding, probe ->
            runGeometryBlendReadbackSuite(binding, probe, ShaderDialect.GLES)
        }

    @Test
    fun theBloomReadbackSuitePassesOnARealWebGl2Context() = runOn(BLOOM_READBACK_PIXELS) { binding, probe ->
        runBloomReadbackSuite(binding, probe, ShaderDialect.GLES)
    }

    @Test
    fun thePerElementBloomReadbackSuitePassesOnARealWebGl2Context() =
        runOn(BLOOM_PER_ELEMENT_PIXELS) { binding, probe ->
            runBloomPerElementReadbackSuite(binding, probe, ShaderDialect.GLES)
        }

    @Test
    fun theStickerOpacityReadbackSuitePassesOnARealWebGl2Context() =
        runOn(STICKER_OPACITY_READBACK_PIXELS) { binding, probe ->
            runStickerOpacityReadbackSuite(binding, probe, ShaderDialect.GLES)
        }

    @Test
    fun theStickerShaderReadbackSuitePassesOnARealWebGl2Context() =
        runOn(STICKER_SHADER_READBACK_PIXELS) { binding, probe ->
            runStickerShaderReadbackSuite(binding, probe, ShaderDialect.GLES)
        }

    @Test
    fun theGlobeGroundReadbackSuitePassesOnARealWebGl2Context() =
        runOn(GLOBE_GROUND_READBACK_PIXELS) { binding, _ ->
            runGlobeGroundReadbackSuite(binding, ShaderDialect.GLES)
        }

    @Test
    fun theGlobeFrameReadbackSuitePassesOnARealWebGl2Context() =
        runOn(GLOBE_FRAME_READBACK_PIXELS) { binding, probe ->
            runGlobeFrameReadbackSuite(binding, probe, ShaderDialect.GLES)
        }

    @Test
    fun theTerrainFrameReadbackSuitePassesOnARealWebGl2Context() =
        runOn(TERRAIN_FRAME_READBACK_PIXELS) { binding, probe ->
            runTerrainFrameReadbackSuite(binding, probe, ShaderDialect.GLES)
        }

    @Test
    fun theGroundAnchorReadbackSuitePassesOnARealWebGl2Context() =
        runOn(GROUND_ANCHOR_READBACK_PIXELS) { binding, probe ->
            runGroundAnchorReadbackSuite(binding, probe, ShaderDialect.GLES)
        }

    @Test
    fun theLatitudePrecisionProbePassesOnARealWebGl2Context() = runOn(LATITUDE_PROBE_PIXELS) { binding, _ ->
        runLatitudePrecisionProbeSuite(binding, ShaderDialect.GLES)
    }

    @Test
    fun theVertexTextureFetchProbePassesOnARealWebGl2Context() = runOn(1) { binding, _ ->
        runVertexTextureFetchProbeSuite(binding, ShaderDialect.GLES)
    }

    @Test
    fun bytePayloadCopyThroughputIsMeasured() = runOn(1920, 1080) { binding, _ ->
        val texture = IntArray(1)
        binding.genTextures(1, texture)
        binding.bindTexture(GL_TEXTURE_2D, texture[0])
        val upload = ByteArray(16 * 1024 * 1024) { it.toByte() }
        binding.finish()
        val uploadStart = performanceNow()
        binding.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2048, 2048, 0, GL_RGBA, GL_UNSIGNED_BYTE, upload)
        binding.finish()
        val uploadSeconds = (performanceNow() - uploadStart) / 1000.0

        val readback = ByteArray(1920 * 1080 * 4)
        binding.finish()
        val readStart = performanceNow()
        binding.readPixels(0, 0, 1920, 1080, GL_RGBA, GL_UNSIGNED_BYTE, readback)
        binding.finish()
        val readSeconds = (performanceNow() - readStart) / 1000.0

        val uploadRate = 16.0 / uploadSeconds
        val readRate = (readback.size / (1024.0 * 1024.0)) / readSeconds
        println(
            "RenG WebGL copy throughput: texImage2D_16MiB=${uploadRate.rounded()} MB/s " +
                "readPixels_1920x1080_RGBA=${readRate.rounded()} MB/s",
        )
        binding.deleteTextures(1, texture)
    }

    private fun runOn(
        pixels: Int,
        block: suspend (GlBinding, RenderContextProbe) -> Unit,
    ) = runOn(pixels, pixels, block)

    private fun runOn(
        width: Int,
        height: Int,
        block: suspend (GlBinding, RenderContextProbe) -> Unit,
    ) = runTest(timeout = 15.minutes) {
        val canvas = createCanvas(width, height)
        val context = createWebGl2Context(canvas)
            ?: throw AssertionError("canvas.getContext(\"webgl2\") returned null")
        makeWebGl2ContextCurrent(context)
        val identity = RenderContextIdentity(nextIdentity++)
        val probe = RenderContextProbe { identity }
        try {
            val binding = when (val result = openPlatformGlBinding()) {
                is GlBindingResult.Bound -> result.binding
                is GlBindingResult.Unsupported -> throw AssertionError("the WebGL2 binding must open")
            }
            binding.viewport(0, 0, width, height)
            binding.scissor(0, 0, width, height)
            block(binding, probe)
        } finally {
            clearCurrentWebGl2Context()
            loseWebGlContext(context)
        }
    }

    private fun Double.rounded(): Double = (this * 10.0).roundToInt() / 10.0

    private companion object {
        var nextIdentity: Long = 1L
    }
}

@JsFun("(width, height) => { const canvas = document.createElement('canvas'); canvas.width = width; canvas.height = height; document.body.appendChild(canvas); return canvas; }")
private external fun createCanvas(width: Int, height: Int): JsAny

@JsFun("canvas => canvas.getContext('webgl2', { antialias: false, depth: true, stencil: false, alpha: true, premultipliedAlpha: false, preserveDrawingBuffer: true })")
private external fun createWebGl2Context(canvas: JsAny): JsAny?

@JsFun("context => { const extension = context.getExtension('WEBGL_lose_context'); if (extension) extension.loseContext(); const canvas = context.canvas; if (canvas && canvas.parentNode) canvas.parentNode.removeChild(canvas); }")
private external fun loseWebGlContext(context: JsAny)

@JsFun("() => performance.now()")
private external fun performanceNow(): Double
