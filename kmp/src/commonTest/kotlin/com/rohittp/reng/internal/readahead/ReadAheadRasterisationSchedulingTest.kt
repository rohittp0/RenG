package com.rohittp.reng.internal.readahead

import com.rohittp.reng.internal.firewall.ReadAheadWarmSummary
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * What ADR 0077 asks of the worker's scheduling, with the engine replaced by a warm function the test
 * controls: a window whose tiles were all warmed earlier is still handed on when the renderer draws, and
 * `close()` stops a window that is in the middle of that work.
 */
class ReadAheadRasterisationSchedulingTest {

    private fun tiles(count: Int): List<CanonicalBasemapTile> =
        (0 until count).map { CanonicalBasemapTile(lod = 12, tileY = it, canonicalX = it) }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun <T> real(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(30_000) { block() } }

    @Test
    fun aWindowWithNothingColdIsSkippedUnlessTheRendererDrawsIt() = runTest {
        for (draws in listOf(false, true)) {
            val calls = Collected<List<CanonicalBasemapTile>>()
            val scheduler = ReadAheadScheduler<String>(newScope(), warmWhenNothingIsCold = draws) { _, cold ->
                calls.add(cold)
                ReadAheadWarmSummary(fetched = cold.size, alreadyCached = 0, failed = 0)
            }
            try {
                val two = tiles(2)
                scheduler.submit(ReadAheadWindow("ctx", "style", two))
                real { scheduler.awaitIdle() }
                // The same tiles again: every one is warmed now, so nothing is cold.
                scheduler.submit(ReadAheadWindow("ctx", "style", two))
                real { scheduler.awaitIdle() }

                assertEquals(
                    if (draws) listOf(two, emptyList()) else listOf(two),
                    calls.snapshot(),
                    "with drawing off the second window is skipped as in ADR 0075; with it on the renderer is " +
                        "called with an empty cold list, because the drawing happens inside the window's operation",
                )
            } finally {
                scheduler.cancelAndJoin()
            }
        }
    }

    @Test
    fun closingWhileAWindowIsMidDrawStopsItAndLeavesNothingRunning() = runTest {
        val entered = CompletableDeferred<Unit>()
        val unwound = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        val started = Collected<String>()
        val scheduler = ReadAheadScheduler<String>(newScope(), warmWhenNothingIsCold = true) { context, cold ->
            started.add(context)
            entered.complete(Unit)
            try {
                // Stands for the engine drawing a slice. It never finishes on its own.
                neverReleased.await()
            } finally {
                unwound.complete(Unit)
            }
            ReadAheadWarmSummary(fetched = cold.size, alreadyCached = 0, failed = 0)
        }
        scheduler.submit(ReadAheadWindow("first", "style", tiles(3)))
        real { entered.await() }

        // What `close()` does: cancel and do not wait. Then wait here, to see that it did stop.
        scheduler.cancel()
        real { unwound.await() }
        real { scheduler.cancelAndJoin() }

        // A hint after close is accepted and never starts: no stray work for it to wake.
        scheduler.submit(ReadAheadWindow("second", "style", tiles(2)))
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(100) }
        assertEquals(listOf("first"), started.snapshot(), "nothing runs after the worker is cancelled")
        assertTrue(unwound.isCompleted, "the window's cleanup ran, which is where a batch is closed")
    }

    @Test
    fun waitingForIdleReturnsOnceTheWorkerIsCancelledEvenWithAWindowStillQueued() = runTest {
        val entered = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        val scheduler = ReadAheadScheduler<String>(newScope()) { _, cold ->
            entered.complete(Unit)
            neverReleased.await()
            ReadAheadWarmSummary(fetched = cold.size, alreadyCached = 0, failed = 0)
        }
        scheduler.submit(ReadAheadWindow("running", "style", tiles(2)))
        real { entered.await() }
        scheduler.submit(ReadAheadWindow("queued", "style", tiles(3)))

        // Someone is already waiting when the cancel arrives: it must be released, not left hanging on a
        // queue that will never drain.
        val waiter = async(Dispatchers.Default) { scheduler.awaitIdle() }
        withContext(Dispatchers.Default) { delay(50) }
        assertTrue(!waiter.isCompleted, "still waiting while a window runs and another is queued")

        scheduler.cancel()
        real { waiter.await() }
        real { scheduler.awaitIdle() }
        real { scheduler.cancelAndJoin() }
    }

    @Test
    fun waitingForIdleOnACancelledOrNeverUsedSchedulerReturnsAtOnce() = runTest {
        val scheduler = ReadAheadScheduler<String>(newScope()) { _, cold ->
            ReadAheadWarmSummary(fetched = cold.size, alreadyCached = 0, failed = 0)
        }
        real { scheduler.awaitIdle() }
        scheduler.cancelAndJoin()
        scheduler.submit(ReadAheadWindow("late", "style", tiles(1)))
        real { scheduler.awaitIdle() }
        real { scheduler.awaitIdle() }
    }

    private class Collected<T> {
        private val mutex = Mutex()
        private val entries = mutableListOf<T>()

        suspend fun add(value: T) {
            mutex.withLock { entries += value }
        }

        suspend fun snapshot(): List<T> = mutex.withLock { entries.toList() }
    }
}
