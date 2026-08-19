package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxGlConformanceTest {
    @Test fun theSuitePassesOnARealEsContext() {
        runOn(ShaderDialect.GLES) { report ->
            assertEquals(ShaderDialect.GLES, report.dialect)
            assertTrue(report.shadingLanguageVersionText.startsWith("OpenGL ES GLSL ES"))
            assertTrue(report.versionText.startsWith("OpenGL ES 3"))
        }
    }

    @Test fun theSuitePassesOnARealDesktopCoreContext() {
        runOn(ShaderDialect.DESKTOP) { report ->
            assertEquals(ShaderDialect.DESKTOP, report.dialect)
            assertTrue(!report.shadingLanguageVersionText.startsWith("OpenGL ES"))
        }
    }

    @Test fun theSameBinaryDetectsTwoDialectsOnOneTarget() {
        val esRenderer = runOn(ShaderDialect.GLES) { it }
        val desktopRenderer = runOn(ShaderDialect.DESKTOP) { it }
        assertEquals(esRenderer.rendererName, desktopRenderer.rendererName)
        assertTrue(esRenderer.dialect != desktopRenderer.dialect)
    }

    private fun <T> runOn(dialect: ShaderDialect, assertions: (GlConformanceReport) -> T): T {
        val fixture = SurfacelessEglContext.create(dialect)
        try {
            val binding = when (val result = openPlatformGlBinding()) {
                is GlBindingResult.Bound -> result.binding
                is GlBindingResult.Unsupported ->
                    throw AssertionError("every roster entry point must resolve on this driver")
            }
            // A surfaceless context starts with viewport and scissor box 0,0,0,0.
            binding.viewport(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
            binding.scissor(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
            val report = runGlConformanceSuite(binding, fixture.probe, dialect)
            assertEquals(7, report.checks.size)
            return assertions(report)
        } finally {
            fixture.destroy()
        }
    }
}
