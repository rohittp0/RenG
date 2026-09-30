package com.rohittp.reng.internal.readahead

import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.internal.basemap.BasemapStyleManifest
import com.rohittp.reng.internal.firewall.ReadAheadWarmSummary
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.rentile.PreparedStyle
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The most tiles one read-ahead window will warm (ADR 0075).
 *
 * Bounded because Rentile's own documentation records that a whole-session read-ahead became a five-
 * to seven-fold regression, spending the connection budget on tiles the cursor would not reach for
 * minutes. It is applied after tiles already warmed are removed, so it caps the tiles that are
 * genuinely cold rather than the ones a window merely mentions.
 */
internal const val MAX_READ_AHEAD_TILES: Int = 64

/** The most tiles remembered as already warmed before the memory is dropped and rebuilt. */
private const val MAXIMUM_WARMED_TILES: Int = 4096

/**
 * Everything the worker may know about the renderer, captured at the end of one successful `prepare`
 * (ADR 0075). An immutable snapshot rather than a view of live renderer state: the host's manifest
 * and remembered-key maps are unlocked and belong to the preparing thread, so the worker reads only
 * this. [selectedLod] is the LOD the last committed `prepare` selected, from which a hint's LOD
 * chain starts exactly as `prepareBatch`'s does.
 */
internal class ReadAheadSnapshot(
    val style: PreparedStyle,
    val manifest: BasemapStyleManifest,
    val accessMode: ResourceAccessMode,
    val selectedLod: Int?,
) {
    fun withSelectedLod(lod: Int?): ReadAheadSnapshot = ReadAheadSnapshot(style, manifest, accessMode, lod)
}

/**
 * One hint's worth of work: the candidate tiles in cursor order, the [styleDigest] they are warmed
 * under, and the [context] the warm needs -- in the renderer, the [ReadAheadSnapshot] it was planned
 * against. Generic over that context so the scheduler can be exercised without an engine.
 */
internal class ReadAheadWindow<C : Any>(
    val context: C,
    val styleDigest: String,
    val tiles: List<CanonicalBasemapTile>,
)

/**
 * One worker coroutine taking read-ahead windows one at a time (ADR 0075).
 *
 * **The queue is one slot and a newer window replaces it.** [submit] never suspends and never touches
 * the window being warmed: a running window is never cancelled by a successor, because a foreground
 * acquisition which joined one of its flights is waiting on it. Only [cancel], which the renderer
 * calls from `close()`, stops the worker.
 *
 * **Every piece of mutable state has one owner.** The queue and the running flag are one
 * [MutableStateFlow] updated by compare-and-set, so any thread may submit. The warmed set is read and
 * written by the worker alone.
 *
 * **A window never fails the renderer.** Whatever [warm] throws other than cancellation is dropped:
 * the hint is best-effort, and the frame which needs a tile that could not be fetched fails on it
 * itself.
 */
internal class ReadAheadScheduler<C : Any>(
    private val scope: CoroutineScope,
    private val maximumTiles: Int = MAX_READ_AHEAD_TILES,
    private val warm: suspend (C, List<CanonicalBasemapTile>) -> ReadAheadWarmSummary,
) {
    private class State<C : Any>(val pending: ReadAheadWindow<C>?, val running: Boolean)

    private val state = MutableStateFlow(State<C>(pending = null, running = false))

    // Worker-only. A tile joins it when its whole window finished without a failure, because the
    // engine reports one summary per window rather than one outcome per tile.
    private val warmed = HashSet<CanonicalBasemapTile>()
    private var warmedStyleDigest: String? = null

    private val worker: Job = scope.launch { runWorker() }

    /** Queues [window], replacing any window queued but not yet started. Non-suspending and thread-safe. */
    fun submit(window: ReadAheadWindow<C>) {
        state.update { State(pending = window, running = it.running) }
    }

    /** Suspends until nothing is queued and nothing is running. For tests: the renderer never waits. */
    suspend fun awaitIdle() {
        state.first { it.pending == null && !it.running }
    }

    /** Cancels the worker and everything it owns. Non-blocking and idempotent. */
    fun cancel() {
        scope.cancel()
    }

    /** Cancels and waits for the worker to finish. For tests. */
    suspend fun cancelAndJoin() {
        scope.cancel()
        worker.join()
    }

    private suspend fun runWorker() {
        while (true) {
            val window = takeNext()
            try {
                process(window)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (@Suppress("TooGenericExceptionCaught") hintFailure: Throwable) {
                // Best-effort by contract. A closed engine, an unowned style or a firewall refusal all
                // end this window and nothing else; the next one starts clean.
            } finally {
                state.update { State(pending = it.pending, running = false) }
            }
        }
    }

    private suspend fun takeNext(): ReadAheadWindow<C> {
        while (true) {
            val observed = state.first { it.pending != null }
            val window = observed.pending ?: continue
            if (state.compareAndSet(observed, State<C>(pending = null, running = true))) return window
        }
    }

    private suspend fun process(window: ReadAheadWindow<C>) {
        if (warmedStyleDigest != window.styleDigest) {
            // A different style names different urls, so nothing warmed under the old one is known.
            warmed.clear()
            warmedStyleDigest = window.styleDigest
        }
        if (warmed.size > MAXIMUM_WARMED_TILES) warmed.clear()
        val cold = ArrayList<CanonicalBasemapTile>(minOf(window.tiles.size, maximumTiles))
        for (tile in window.tiles) {
            if (cold.size == maximumTiles) break
            if (tile !in warmed) cold += tile
        }
        if (cold.isEmpty()) return
        val summary = warm(window.context, cold)
        if (summary.failed == 0 && summary.fetched + summary.alreadyCached > 0) warmed.addAll(cold)
    }
}
