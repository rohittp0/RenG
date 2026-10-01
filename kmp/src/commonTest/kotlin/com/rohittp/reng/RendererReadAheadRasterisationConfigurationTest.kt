package com.rohittp.reng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * `RendererConfiguration.maximumReadAheadRasterisedTileBytes` (ADR 0077): off by default, validated, and
 * inert until a prepare has succeeded -- the parts of it that need no rasteriser, so they run everywhere.
 */
class RendererReadAheadRasterisationConfigurationTest {

    private fun configuration(bytes: Long, transport: Transport = StyleTransport()) = RendererConfiguration(
        outputPixelSize = OutputPixelSize(64, 64),
        transport = transport,
        store = RecordingStyleStore(),
        basemapStyle = ResourceLocator(STYLE_URL),
        maximumReadAheadRasterisedTileBytes = bytes,
    )

    @Test
    fun itDefaultsToZeroWhichMeansNoRenderAhead() {
        assertEquals(
            0L,
            RendererConfiguration(
                outputPixelSize = OutputPixelSize(64, 64),
                transport = StyleTransport(),
                store = RecordingStyleStore(),
            ).maximumReadAheadRasterisedTileBytes,
        )
    }

    @Test
    fun itAcceptsZeroThroughOneGibibyteAndNothingOutsideThat() {
        assertEquals(0L, configuration(0L).maximumReadAheadRasterisedTileBytes)
        assertEquals(1L, configuration(1L).maximumReadAheadRasterisedTileBytes)
        assertEquals(1_073_741_824L, configuration(1_073_741_824L).maximumReadAheadRasterisedTileBytes)
        for (invalid in listOf(-1L, 1_073_741_825L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            val failure = assertFailsWith<IllegalArgumentException>("$invalid") { configuration(invalid) }
            assertTrue(
                failure.message.orEmpty().contains("maximumReadAheadRasterisedTileBytes"),
                "the error names the option: ${failure.message}",
            )
        }
    }

    @Test
    fun anEnabledRendererThatHasPreparedNothingHasNothingToDrawAheadForAndClosesCleanly() = runTest {
        val transport = StyleTransport()
        val renderer = createRenderer(
            configuration(bytes = 8L * 1024L * 1024L, transport = transport),
            styleGlBinding(),
            com.rohittp.reng.internal.gl.RenderContextProbe {
                com.rohittp.reng.internal.gl.RenderContextIdentity(1L)
            },
        ) as RenGRenderer

        renderer.readAhead(listOf(basemapPlan(frameIndex = 0L)))
        renderer.awaitReadAheadIdle()

        assertEquals(emptyList(), transport.requestedUrls(), "no exchange of any kind")
        assertEquals(0, renderer.renderedTileCacheTiles)
        assertEquals(0L, renderer.queryMetrics()[RenGMetricName.READ_AHEAD_RASTERISED])
        renderer.close()
        renderer.readAhead(listOf(basemapPlan(frameIndex = 1L)))
        assertEquals(0, renderer.renderedTileCacheTiles, "a closed renderer holds nothing")
    }
}
