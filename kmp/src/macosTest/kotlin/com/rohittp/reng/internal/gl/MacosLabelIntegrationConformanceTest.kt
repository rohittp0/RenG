package com.rohittp.reng.internal.gl

import com.rohittp.reng.LABEL_INTEGRATION_PIXELS
import com.rohittp.reng.runLabelIntegrationReadbackSuite
import kotlin.test.Test

/**
 * The one Apple core-profile gate that stays Kotlin/Native-only: `runLabelIntegrationReadbackSuite`
 * lives in `nativeTest`, because rasterising its glyphs goes through Rentile's Skia, which only the
 * native targets link in. Every other gate is in `cglTest`'s [MacosGlConformanceTest] and runs on
 * the JVM as well.
 */
class MacosLabelIntegrationConformanceTest {
    /**
     * E-labels task 20's gate: a `FramePlan` in, drawn label pixels out, through the public API on a
     * real Apple core-profile context.
     *
     * Eleven merged tasks were green while the renderer drew no label at all -- every stage had its
     * own suite and nothing ran them in sequence -- so this is the first assertion in the tree that
     * the label path composes. See `runLabelIntegrationReadbackSuite` for what each case
     * discriminates, and for the two ways a "labels drew" assertion passes for the wrong reason.
     *
     * The default renderer only, unlike the readbacks above -- which is worth knowing precisely,
     * because on a hosted runner the default *is* `Apple Software Renderer`. Every quad here is about
     * ten pixels across, and task 18's `measureGlyphQuadRasterisation` opens the suite by measuring
     * them: 0 disagreeing pixels on `Apple M3 Max`. Where it distrusts the driver, the four cases
     * whose evidence is a drawn label pixel skip out loud and the two that assert an empty frame
     * still run.
     */
    @Test fun theLabelIntegrationReadbackSuitePassesOnARealAppleCoreProfileContext() {
        val fixture = CglCoreProfileContext.createOrNull(MacosGlRenderer.DEFAULT)
            ?: throw AssertionError("the default Apple renderer must be available on a developer machine")
        try {
            val binding = bindOrFail()
            binding.viewport(0, 0, LABEL_INTEGRATION_PIXELS, LABEL_INTEGRATION_PIXELS)
            binding.scissor(0, 0, LABEL_INTEGRATION_PIXELS, LABEL_INTEGRATION_PIXELS)
            runLabelIntegrationReadbackSuite(binding, fixture.probe)
        } finally {
            fixture.destroy()
        }
    }

    private fun bindOrFail(): GlBinding = when (val result = openPlatformGlBinding()) {
        is GlBindingResult.Bound -> result.binding
        is GlBindingResult.Unsupported -> throw AssertionError("platform.OpenGL3 must bind")
    }
}
