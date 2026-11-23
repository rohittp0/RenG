package com.rohittp.reng.internal.planning

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.RecordingStyleStore
import com.rohittp.reng.RendererConfiguration
import com.rohittp.reng.StyleTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * ADR 0057's rule for a tile that could not be downloaded, without a renderer: a frame leaves out all of
 * the failed tiles or none of them, and only when every one has somewhere to be drawn from.
 */
class ProvisionalFailedTilesTest {
    private val a = CanonicalBasemapTile(lod = 4, tileY = 10, canonicalX = 1)
    private val b = CanonicalBasemapTile(lod = 4, tileY = 11, canonicalX = 2)
    private val c = CanonicalBasemapTile(lod = 4, tileY = 11, canonicalX = 1)
    private val requested = listOf(a, b, c)

    @Test
    fun theOptionIsOffByDefaultSoAnExportStillFailsClosed() {
        assertFalse(
            RendererConfiguration(
                outputPixelSize = OutputPixelSize(64, 64),
                transport = StyleTransport(),
                store = RecordingStyleStore(),
            ).presentFailedTilesProvisionally,
        )
    }

    @Test
    fun failedTilesThatAllHaveAnAncestorAreLeftOutTogether() {
        assertEquals(setOf(a, b), presentableFailedTiles(setOf(a, b), requested) { true })
    }

    @Test
    fun oneFailedTileWithNoAncestorFailsTheWholeFrame() {
        // Not "leave out the other one": the frame fails either way, and failing with today's failure
        // is what keeps the consumer's handling of it unchanged.
        assertNull(presentableFailedTiles(setOf(a, b), requested) { it != b })
    }

    @Test
    fun aFailureNamingNoTileLeavesNothingToLeaveOut() {
        assertNull(presentableFailedTiles(emptySet(), requested) { true })
    }

    @Test
    fun aFailureAboutATileTheFrameDidNotAskForIsNotTheFramesToPresent() {
        val elsewhere = CanonicalBasemapTile(lod = 4, tileY = 3, canonicalX = 3)
        assertNull(presentableFailedTiles(setOf(a, elsewhere), requested) { true })
    }

    @Test
    fun theAncestorQuestionIsAskedOnlyAboutTheFailedTiles() {
        val asked = mutableListOf<CanonicalBasemapTile>()
        presentableFailedTiles(setOf(b), requested) { tile -> asked += tile; true }
        assertEquals(listOf(b), asked)
    }
}
