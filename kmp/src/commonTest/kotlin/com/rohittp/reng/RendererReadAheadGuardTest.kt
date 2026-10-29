package com.rohittp.reng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The cases in which `Renderer.readAhead` must do nothing (ADR 0075), on every target: it needs no
 * prepared frame to prove that, and so needs no rasterizer either.
 *
 * The cases that need a successful `prepare` first -- `CACHE_ONLY`, a hint after close, and the frames
 * a window actually warms -- are in `nativeTest`'s `RendererReadAheadTest`, because a `prepare` that
 * renders tiles needs Skia.
 */
class RendererReadAheadGuardTest {

    @Test
    fun aHintBeforeTheFirstPrepareDoesNothing() = runTest {
        val transport = StyleTransport()
        val renderer = styleRenderer(transport) as RenGRenderer

        // Nothing has been compiled yet, so there is no style to plan the tiles against.
        renderer.readAhead(listOf(basemapPlan(frameIndex = 0L), basemapPlan(frameIndex = 1L)))
        renderer.awaitReadAheadIdle()

        assertEquals(emptyList(), transport.requestedUrls(), "no exchange of any kind")
        assertEquals(0L, renderer.queryMetrics()[RenGMetricName.READ_AHEAD_FETCHED])
        assertEquals(0L, renderer.queryMetrics()[RenGMetricName.READ_AHEAD_FAILED])
        renderer.close()
    }

    @Test
    fun aHintWithNoPlansDoesNothing() = runTest {
        val transport = StyleTransport()
        val renderer = styleRenderer(transport) as RenGRenderer

        renderer.readAhead(emptyList())
        renderer.awaitReadAheadIdle()

        assertEquals(emptyList(), transport.requestedUrls())
        renderer.close()
    }

    @Test
    fun waitingForIdleWithNothingEverHintedReturnsAtOnce() = runTest {
        val renderer = styleRenderer(StyleTransport())

        // Nothing has been hinted, so no worker exists: the public wait must not create one or block.
        kotlinx.coroutines.withTimeout(5_000) { renderer.awaitReadAheadIdle() }
        renderer.close()
        kotlinx.coroutines.withTimeout(5_000) { renderer.awaitReadAheadIdle() }
    }
}
