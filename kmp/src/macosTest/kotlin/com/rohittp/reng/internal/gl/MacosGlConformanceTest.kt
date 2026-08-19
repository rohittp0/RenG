package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacosGlConformanceTest {
    @Test fun theSuitePassesOnARealAppleCoreProfileContext() {
        val fixture = CglCoreProfileContext.create()
        try {
            val binding = when (val result = openPlatformGlBinding()) {
                is GlBindingResult.Bound -> result.binding
                is GlBindingResult.Unsupported -> throw AssertionError("platform.OpenGL3 must bind")
            }
            binding.viewport(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
            binding.scissor(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)

            val report = runGlConformanceSuite(binding, fixture.probe, ShaderDialect.DESKTOP)
            assertEquals(ShaderDialect.DESKTOP, report.dialect)
            assertEquals(7, report.checks.size)
            assertTrue(report.rendererName.isNotBlank())
            // A hosted runner reports "Apple Software Renderer"; a developer's machine reports
            // "4.1 Metal - 90.5". Cycle E must key golden baselines by this string and the dialect.
            println("RenG conformance renderer: ${report.rendererName} / ${report.versionText}")
        } finally {
            fixture.destroy()
        }
    }
}
