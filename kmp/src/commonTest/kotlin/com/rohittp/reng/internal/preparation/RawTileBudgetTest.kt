package com.rohittp.reng.internal.preparation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ADR 0052's note of 2026-11-06: the raw-tile budget charges each distinct pixel array once, however
 * many open reservations hold it, and returns it when the last one releases.
 */
class RawTileBudgetTest {
    @Test
    fun anArrayAnotherReservationHoldsCostsNothingAndStaysChargedUntilItsLastHolderReleases() {
        val budget = RawTileBudget(10L)
        val shared = ByteArray(6)
        val first = budget.reserve()
        assertTrue(first.tryHold(listOf(shared), freshBytes = 0L))
        assertEquals(6L, budget.outstandingBytes())

        val second = budget.reserve()
        assertTrue(second.tryHold(listOf(shared), freshBytes = 4L), "the shared six are free, the fresh four fit")
        assertEquals(10L, budget.outstandingBytes())
        assertEquals(10L, second.bytes, "a reservation still covers every byte it carries, per occurrence")

        // Out of order: the first holder goes, the second still carries the pixels.
        first.release()
        assertEquals(10L, budget.outstandingBytes())
        second.release()
        assertEquals(0L, budget.outstandingBytes())
    }

    @Test
    fun equalPixelsInTwoArraysAreTwoCharges() {
        // Two styles' tiles at one coordinate can carry identical pixels; they are still two allocations.
        val budget = RawTileBudget(10L)
        val primary = ByteArray(6) { 7 }
        val blend = ByteArray(6) { 7 }
        val first = budget.reserve()
        assertTrue(first.tryHold(listOf(primary), freshBytes = 0L))

        val second = budget.reserve()
        assertFalse(second.tryHold(listOf(blend), freshBytes = 0L), "content-equal is not the same array")
        assertEquals(6L, budget.outstandingBytes())
        assertEquals(0L, second.bytes)
        first.release()
        second.release()
        assertEquals(0L, budget.outstandingBytes())
    }

    @Test
    fun aRequestThatDoesNotFitChangesNothing() {
        val budget = RawTileBudget(10L)
        val held = ByteArray(4)
        val fresh = ByteArray(4)
        val first = budget.reserve()
        assertTrue(first.tryHold(listOf(held), freshBytes = 0L))

        val second = budget.reserve()
        assertFalse(second.tryHold(listOf(held, fresh), freshBytes = 4L), "4 new + 4 fresh over the 6 left")
        assertEquals(4L, budget.outstandingBytes())
        assertEquals(0L, second.bytes)
        second.release()
        first.release()
        assertEquals(0L, budget.outstandingBytes(), "a refused request took no hold to release")
    }

    @Test
    fun boundFreshBytesBecomeASharedHoldWithoutMovingBytes() {
        val budget = RawTileBudget(8L)
        val rendering = requireNotNull(budget.tryReserve(8L))
        val rendered = listOf(ByteArray(4), ByteArray(4))
        rendering.bindFresh(rendered)
        assertEquals(8L, budget.outstandingBytes())
        assertEquals(8L, rendering.heldBytes)

        val remembering = budget.reserve()
        assertTrue(remembering.tryHold(rendered, freshBytes = 0L), "a full budget still admits what is held")
        rendering.release()
        assertEquals(8L, budget.outstandingBytes(), "the remembering frame keeps the pixels counted")
        remembering.release()
        assertEquals(0L, budget.outstandingBytes())
    }

    @Test
    fun holdingOnlyWhatIsAlreadyHeldAnswersTheRest() {
        val budget = RawTileBudget(4L)
        val held = ByteArray(4)
        val drawnAhead = ByteArray(4)
        val first = budget.reserve()
        assertTrue(first.tryHold(listOf(held), freshBytes = 0L))

        val second = budget.reserve()
        assertFalse(second.tryHold(listOf(held, drawnAhead), freshBytes = 0L))
        val unheld = second.holdAlreadyHeld(listOf(held, drawnAhead))
        assertSame(drawnAhead, unheld.single())
        assertEquals(4L, second.bytes)
        assertEquals(4L, budget.outstandingBytes())

        first.release()
        second.release()
        assertEquals(0L, budget.outstandingBytes())
    }

    @Test
    fun shrinkingGivesBackOnlyFreshBytes() {
        val budget = RawTileBudget(10L)
        val reservation = budget.reserve()
        assertTrue(reservation.tryHold(listOf(ByteArray(4)), freshBytes = 6L))
        reservation.shrinkTo(4L)
        assertEquals(4L, budget.outstandingBytes())
        assertFailsWith<IllegalArgumentException> { reservation.shrinkTo(3L) }
        reservation.release()
        assertEquals(0L, budget.outstandingBytes())
    }
}
