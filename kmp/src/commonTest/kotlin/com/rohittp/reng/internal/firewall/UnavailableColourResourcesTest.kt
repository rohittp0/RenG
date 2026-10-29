package com.rohittp.reng.internal.firewall

import com.rohittp.rentile.BatchRenderException
import com.rohittp.rentile.ResourceAcquisitionException
import com.rohittp.rentile.ResourceDecodeException
import com.rohittp.rentile.ResourceStoreException
import com.rohittp.rentile.TileId
import com.rohittp.rentile.TileSubstitutionLimitException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import com.rohittp.rentile.ResourceClass as RentileResourceClass

/**
 * [unavailableColourResourcesOf], against the exception shapes Rentile 0.11.3 actually throws from a
 * `prepareBatch` whose substitution is disabled, built with its published constructors.
 *
 * The rule under test is that a frame may present from an ancestor only when **everything** that failed
 * was a colour tile the engine could not acquire and could name. Each `null` case below is one way of
 * being something else, and each is the frame failing exactly as it does without the option.
 */
class UnavailableColourResourcesTest {
    private val digest = "d".repeat(64)
    private val tile = TileId(z = 4, x = 2, y = 11)
    private val otherTile = TileId(z = 4, x = 1, y = 11)

    private fun acquisition(
        resourceClass: RentileResourceClass = RentileResourceClass.RASTER_TILE,
        tiles: List<TileId> = listOf(tile),
        sanitizedResourceId: String = digest,
    ) = ResourceAcquisitionException(
        message = "Raster tile transport failed",
        resourceClass = resourceClass,
        sanitizedResourceId = sanitizedResourceId,
        affectedTiles = tiles,
    )

    @Test
    fun oneRasterTileThatCouldNotBeAcquiredIsNamedWithItsTile() {
        val resources = assertNotNull(unavailableColourResourcesOf(acquisition()))

        val resource = resources.single()
        assertEquals(RentileResourceClass.RASTER_TILE, resource.resourceClass)
        assertEquals(digest, resource.sanitizedResourceId)
        assertEquals(listOf(tile), resource.affectedTiles)
    }

    @Test
    fun everyMemberOfABatchFailureIsReadNotOnlyThePrimary() {
        val failure = BatchRenderException(
            message = "Multiple resource acquisitions failed",
            primaryFailure = acquisition(),
            concurrentFailures = listOf(acquisition(RentileResourceClass.VECTOR_TILE, listOf(otherTile))),
        )

        val resources = assertNotNull(unavailableColourResourcesOf(failure))

        assertEquals(listOf(listOf(tile), listOf(otherTile)), resources.map { it.affectedTiles })
        assertEquals(
            listOf(RentileResourceClass.RASTER_TILE, RentileResourceClass.VECTOR_TILE),
            resources.map { it.resourceClass },
        )
    }

    @Test
    fun aDemTileIsElevationNotColourAndIsNeverPresentable() {
        assertNull(unavailableColourResourcesOf(acquisition(RentileResourceClass.DEM_TILE)))
    }

    @Test
    fun aResourceEveryTileSharesHasNoPerTileAnswer() {
        listOf(
            RentileResourceClass.STYLE,
            RentileResourceClass.TILE_JSON,
            RentileResourceClass.SPRITE_JSON,
            RentileResourceClass.SPRITE_IMAGE,
            RentileResourceClass.GEO_JSON,
            RentileResourceClass.GLYPH_RANGE,
        ).forEach { resourceClass ->
            assertNull(unavailableColourResourcesOf(acquisition(resourceClass)), "$resourceClass must fail closed")
        }
    }

    @Test
    fun oneMemberThatIsNotAColourAcquisitionFailsTheWholeAnswer() {
        val withDecode = BatchRenderException(
            message = "Multiple resource acquisitions failed",
            primaryFailure = acquisition(),
            concurrentFailures = listOf(
                ResourceDecodeException(
                    message = "not a png",
                    resourceClass = RentileResourceClass.RASTER_TILE,
                    sanitizedResourceId = digest,
                    affectedTiles = listOf(otherTile),
                ),
            ),
        )
        val withDem = BatchRenderException(
            message = "Multiple resource acquisitions failed",
            primaryFailure = acquisition(),
            concurrentFailures = listOf(acquisition(RentileResourceClass.DEM_TILE, listOf(otherTile))),
        )

        assertNull(unavailableColourResourcesOf(withDecode), "a tile that arrived and does not decode")
        assertNull(unavailableColourResourcesOf(withDem), "a DEM beside a colour tile")
    }

    @Test
    fun aFailureThatNamesNoTileCannotBePresented() {
        assertNull(unavailableColourResourcesOf(acquisition(tiles = emptyList())))
    }

    @Test
    fun aMalformedEngineIdentityCannotBePresented() {
        assertNull(unavailableColourResourcesOf(acquisition(sanitizedResourceId = "not-a-digest")))
    }

    @Test
    fun everythingThatIsNotAnAcquisitionFailsClosed() {
        assertNull(unavailableColourResourcesOf(ResourceStoreException("store read failed")))
        assertNull(unavailableColourResourcesOf(IllegalStateException("not the engine's")))
        assertNull(
            unavailableColourResourcesOf(
                TileSubstitutionLimitException(
                    maximumSubstitutedTiles = 0,
                    requiredSubstitutedTiles = 1,
                    primaryFailure = acquisition(),
                    affectedTiles = listOf(tile),
                ),
            ),
            "a substitution wrapper is unreachable with substitution disabled, so it is not interpreted",
        )
        assertNull(
            unavailableColourResourcesOf(
                BatchRenderException(
                    message = "nested",
                    primaryFailure = BatchRenderException(message = "inner", primaryFailure = acquisition()),
                ),
            ),
            "Rentile never nests batch failures here, so a nested one is not interpreted either",
        )
    }

    @Test
    fun rethrowsCancellationUnwrapped() {
        val cancellation = CancellationException("frame preparation cancelled")

        assertSame(cancellation, assertFailsWith<CancellationException> { unavailableColourResourcesOf(cancellation) })
    }
}
