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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * ADR 0075's worker: one window at a time, a queue of one that a newer window replaces, and a running
 * window that nothing but `close()` ever cancels. The engine is replaced by a warm function the test
 * controls, so each ordering is forced rather than raced.
 */
class ReadAheadSchedulerTest {

    private fun tiles(count: Int, from: Int = 0): List<CanonicalBasemapTile> =
        (from until from + count).map { CanonicalBasemapTile(lod = 12, tileY = it, canonicalX = it) }

    private fun window(digest: String, tiles: List<CanonicalBasemapTile>, context: String = digest) =
        ReadAheadWindow(context = context, styleDigest = digest, tiles = tiles)

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun <T> real(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(30_000) { block() } }

    @Test
    fun aWindowIsCappedAtTheMaximumAfterTheWarmedTilesAreRemoved() = runTest {
        val warmed = Recorder<List<CanonicalBasemapTile>>()
        val scheduler = ReadAheadScheduler<String>(newScope()) { _, cold ->
            warmed.record(cold)
            ReadAheadWarmSummary(fetched = cold.size, alreadyCached = 0, failed = 0)
        }
        try {
            val all = tiles(MAX_READ_AHEAD_TILES + 36)
            scheduler.submit(window("style", all))
            real { scheduler.awaitIdle() }
            assertEquals(listOf(all.take(MAX_READ_AHEAD_TILES)), warmed.snapshot(), "the first 64, in cursor order")

            // The first 64 are warmed now, so the next window over the same tiles spends its cap on the
            // remainder rather than on tiles it already did.
            scheduler.submit(window("style", all))
            real { scheduler.awaitIdle() }
            assertEquals(all.drop(MAX_READ_AHEAD_TILES), warmed.snapshot().last())
        } finally {
            scheduler.cancelAndJoin()
        }
    }

    @Test
    fun aWindowThatHadAFailureLeavesEveryTileEligibleForTheNextOne() = runTest {
        val warmed = Recorder<List<CanonicalBasemapTile>>()
        val outcomes = ArrayDeque(listOf(
            ReadAheadWarmSummary(fetched = 1, alreadyCached = 0, failed = 1),
            ReadAheadWarmSummary(fetched = 2, alreadyCached = 0, failed = 0),
        ))
        val scheduler = ReadAheadScheduler<String>(newScope()) { _, cold ->
            warmed.record(cold)
            outcomes.removeFirst()
        }
        try {
            val two = tiles(2)
            scheduler.submit(window("style", two))
            real { scheduler.awaitIdle() }
            scheduler.submit(window("style", two))
            real { scheduler.awaitIdle() }
            scheduler.submit(window("style", two))
            real { scheduler.awaitIdle() }

            assertEquals(
                listOf(two, two),
                warmed.snapshot(),
                "a failed window is retried whole; a fully confirmed one is never warmed again",
            )
        } finally {
            scheduler.cancelAndJoin()
        }
    }

    @Test
    fun aDifferentStyleForgetsWhatWasWarmedUnderTheLastOne() = runTest {
        val warmed = Recorder<List<CanonicalBasemapTile>>()
        val scheduler = ReadAheadScheduler<String>(newScope()) { _, cold ->
            warmed.record(cold)
            ReadAheadWarmSummary(fetched = cold.size, alreadyCached = 0, failed = 0)
        }
        try {
            val two = tiles(2)
            scheduler.submit(window("one", two))
            real { scheduler.awaitIdle() }
            scheduler.submit(window("two", two))
            real { scheduler.awaitIdle() }
            assertEquals(listOf(two, two), warmed.snapshot(), "another style names other urls")
        } finally {
            scheduler.cancelAndJoin()
        }
    }

    @Test
    fun aReplacedQueuedWindowNeverCancelsTheRunningOne() = runTest {
        val started = Recorder<String>()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val cancelled = Recorder<String>()
        val finished = Recorder<String>()
        val scheduler = ReadAheadScheduler<String>(newScope()) { context, cold ->
            started.record(context)
            entered.complete(Unit)
            try {
                if (context == "first") gate.await()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                cancelled.record(context)
                throw cancellation
            }
            finished.record(context)
            ReadAheadWarmSummary(fetched = cold.size, alreadyCached = 0, failed = 0)
        }
        try {
            scheduler.submit(window("style", tiles(1, from = 0), context = "first"))
            real { entered.await() }

            // Three windows arrive while the first is in the air. Only the last is kept, and none of
            // them touches the one running.
            scheduler.submit(window("style", tiles(1, from = 10), context = "second"))
            scheduler.submit(window("style", tiles(1, from = 20), context = "third"))
            scheduler.submit(window("style", tiles(1, from = 30), context = "fourth"))
            assertEquals(emptyList(), cancelled.snapshot(), "the running window is never cancelled by a successor")
            assertEquals(listOf("first"), started.snapshot(), "and nothing starts while it runs")

            gate.complete(Unit)
            real { scheduler.awaitIdle() }

            assertEquals(listOf("first", "fourth"), started.snapshot(), "the queue held one window, the newest")
            assertEquals(listOf("first", "fourth"), finished.snapshot(), "the running window ran to completion")
            assertEquals(emptyList(), cancelled.snapshot())
        } finally {
            scheduler.cancelAndJoin()
        }
    }

    @Test
    fun aFailingWarmEndsOnlyItsOwnWindow() = runTest {
        val started = Recorder<String>()
        val scheduler = ReadAheadScheduler<String>(newScope()) { context, cold ->
            started.record(context)
            if (context == "bad") error("the engine is unhappy")
            ReadAheadWarmSummary(fetched = cold.size, alreadyCached = 0, failed = 0)
        }
        try {
            scheduler.submit(window("style", tiles(1), context = "bad"))
            real { scheduler.awaitIdle() }
            scheduler.submit(window("style", tiles(1, from = 5), context = "good"))
            real { scheduler.awaitIdle() }
            assertEquals(listOf("bad", "good"), started.snapshot(), "a hint never takes the worker down with it")
        } finally {
            scheduler.cancelAndJoin()
        }
    }

    @Test
    fun cancellingStopsARunningWindowAndAnythingQueuedBehindIt() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val started = Recorder<String>()
        val scheduler = ReadAheadScheduler<String>(newScope()) { context, cold ->
            started.record(context)
            entered.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                cancelled.complete(Unit)
                throw cancellation
            }
            ReadAheadWarmSummary(fetched = cold.size, alreadyCached = 0, failed = 0)
        }
        scheduler.submit(window("style", tiles(1), context = "running"))
        real { entered.await() }
        scheduler.submit(window("style", tiles(1, from = 9), context = "queued"))

        scheduler.cancelAndJoin()

        assertTrue(cancelled.isCompleted, "close is the one thing that cancels the running window")
        assertEquals(listOf("running"), started.snapshot(), "and the queued one never starts")
    }
}

private class Recorder<T> {
    private val mutex = Mutex()
    private val entries = mutableListOf<T>()

    suspend fun record(value: T) {
        mutex.withLock { entries += value }
    }

    suspend fun snapshot(): List<T> = mutex.withLock { entries.toList() }
}
