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
 * An immutable view of one compiled configured style (ADRs 0075 and 0086). It is published only by
 * a committed foreground `prepare`. The host's
 * manifest and remembered-key maps are unlocked and belong to the operation that produced them, so
 * the worker reads only this. [selectedLod] is populated for the committed Primary Style snapshot,
 * from which a hint's LOD chain starts exactly as `prepareBatch`'s does.
 */
internal class ReadAheadSnapshot(
    val style: PreparedStyle,
    val manifest: BasemapStyleManifest,
    val accessMode: ResourceAccessMode,
    val selectedLod: Int?,
) {
    fun withSelectedLod(lod: Int?): ReadAheadSnapshot = ReadAheadSnapshot(
        style = style,
        manifest = manifest,
        accessMode = accessMode,
        selectedLod = lod,
    )
}

/**
 * What the renderer's window needs beyond the cold list the scheduler computes: one batch per style
 * the plans name (ADRs 0077 and 0086).
 */
internal class ReadAheadRequest(
    val batches: List<StyleReadAheadRequest>,
)

internal class StyleReadAheadRequest(
    val styleIndex: Int,
    val snapshot: ReadAheadSnapshot,
    val tiles: List<CanonicalBasemapTile>,
)

/** One scheduler candidate; ADR 0086 makes the style digest part of warmed identity. */
internal data class ReadAheadTile(
    val styleDigest: String,
    val tile: CanonicalBasemapTile,
)

/**
 * One hint's worth of work: the style-qualified candidate [tiles] in cursor order and the [context]
 * the warm needs. Generic over that context so the scheduler can be exercised without an engine.
 */
internal class ReadAheadWindow<C : Any>(
    val context: C,
    val tiles: List<ReadAheadTile>,
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
    /**
     * Whether [warm] is called for a window with nothing cold left to warm (ADR 0077). Off, such a window
     * is skipped, as in ADR 0075. On, it is called with an empty list, because the renderer draws a
     * window's tiles inside the window's own operation even when their bytes were warmed by an earlier
     * window, and an operation is only opened by [warm].
     */
    private val warmWhenNothingIsCold: Boolean = false,
    private val warm: suspend (C, List<ReadAheadTile>) -> ReadAheadWarmSummary,
) {
    private class State<C : Any>(
        val pending: ReadAheadWindow<C>?,
        val running: Boolean,
        /** The worker is cancelled or finished: nothing queued will ever run, so nobody should wait for it. */
        val stopped: Boolean = false,
    )

    private val state = MutableStateFlow(State<C>(pending = null, running = false))

    // Worker-only. ADR 0086: a canonical tile warmed for one style is still cold for another style.
    // A pair joins only when its whole window finished without a failure, because the engine reports
    // one summary per window rather than one outcome per tile.
    private val warmed = HashSet<ReadAheadTile>()

    private val worker: Job = scope.launch { runWorker() }

    init {
        // However the worker ends -- cancel(), a scope cancelled from outside, or a coroutine that never
        // got to start -- a waiter must be released and a later window must not look pending forever.
        worker.invokeOnCompletion { markStopped() }
    }

    private fun markStopped() {
        state.update { State(pending = null, running = false, stopped = true) }
    }

    /** Queues [window], replacing any window queued but not yet started. Non-suspending and thread-safe. */
    fun submit(window: ReadAheadWindow<C>) {
        state.update {
            if (it.stopped) it else State(pending = window, running = it.running)
        }
    }

    /**
     * Suspends until nothing is queued and nothing is running, or the worker has been cancelled, in which
     * case nothing queued will ever run and this returns at once (ADR 0077).
     */
    suspend fun awaitIdle() {
        state.first { it.stopped || (it.pending == null && !it.running) }
    }

    /** Cancels the worker and everything it owns. Non-blocking and idempotent. */
    fun cancel() {
        scope.cancel()
        markStopped()
    }

    /** Cancels and waits for the worker to finish. For tests. */
    suspend fun cancelAndJoin() {
        cancel()
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
                state.update { State(pending = it.pending, running = false, stopped = it.stopped) }
            }
        }
    }

    private suspend fun takeNext(): ReadAheadWindow<C> {
        while (true) {
            val observed = state.first { it.pending != null }
            val window = observed.pending ?: continue
            if (state.compareAndSet(observed, State<C>(pending = null, running = true, stopped = observed.stopped))) {
                return window
            }
        }
    }

    private suspend fun process(window: ReadAheadWindow<C>) {
        if (warmed.size > MAXIMUM_WARMED_TILES) warmed.clear()
        val cold = ArrayList<ReadAheadTile>(minOf(window.tiles.size, maximumTiles))
        for (tile in window.tiles) {
            if (cold.size == maximumTiles) break
            if (tile !in warmed) cold += tile
        }
        if (cold.isEmpty() && !warmWhenNothingIsCold) return
        val summary = warm(window.context, cold)
        if (summary.failed == 0 && summary.fetched + summary.alreadyCached > 0) warmed.addAll(cold)
    }
}
