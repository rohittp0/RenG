package com.rohittp.reng.internal.driver

import com.rohittp.reng.Store
import com.rohittp.reng.Transport
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.resource.AdvancePendingClassGates
import com.rohittp.reng.internal.resource.PendingClassGates
import com.rohittp.reng.internal.resource.ResourceOperationDefinition
import com.rohittp.reng.internal.resource.ResourceOperationEvent
import com.rohittp.reng.internal.resource.ResourceOperationOutcome
import com.rohittp.reng.internal.resource.ResourceOperationState
import com.rohittp.reng.internal.resource.ResourceOperationStateMachine
import com.rohittp.reng.internal.resource.ResourceOperationTransition
import com.rohittp.reng.internal.resource.StartRoute
import com.rohittp.reng.internal.resource.ordinaryResourceClassGates
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Drives one [ResourceOperationDefinition] to its [ResourceOperationOutcome], sequencing clock sampling,
 * a resident-cache lookup, a Store read, a Transport fetch, and latch replay against the real adapters
 * [ResourceOperationStateMachine] asks for.
 *
 * `run` opens exactly one [coroutineScope]. Every action this driver executes is launched as a structured
 * child of that scope, so cancelling the caller's own coroutine cancels every in-flight adapter call and
 * no child ever outlives this one invocation. No dispatcher is selected anywhere in this class: every
 * coroutine it launches keeps running on the caller's own context, because RenG owns no thread pool of
 * its own. A [Semaphore] sized to [maximumConcurrentOperations] bounds how many actions run at once,
 * regardless of how many the state machine hands back in one batch.
 *
 * The loop is: ask the state machine for actions (via [ResourceOperationStateMachine.start], then via
 * [ResourceOperationStateMachine.transition] for every event this class feeds back), launch each action
 * under a permit, and feed exactly one event back per completed action, until a transition yields an
 * outcome. [StartRoute] is the one action that performs no adapter call and feeds back no event through
 * `transition`: it is how a route begins at all, so this driver executes it by calling
 * [ResourceOperationStateMachine.beginLookup] directly and folding the resulting transition into the same
 * loop as every other action.
 *
 * A route that finishes resolving its content sits at a [PendingClassGates] cursor until something asks
 * the state machine to advance it; this driver does that immediately, itself, for every ordinary resource
 * class ([ordinaryResourceClassGates] non-null) via [AdvancePendingClassGates] — the same synchronous,
 * no-adapter-call shape as [StartRoute]. A sprite- or style-classed route (whose gates are null) is left
 * untouched, since neither its own advancement event nor its actions are handled by this task.
 */
internal class PreparationDriver(
    private val transport: Transport,
    private val store: Store,
    private val cache: ResidentCache,
    private val maximumConcurrentOperations: Int,
    private val clock: () -> Long,
) {
    suspend fun run(definition: ResourceOperationDefinition): ResourceOperationOutcome = coroutineScope {
        val executor = ResourceActionExecutor(transport, store, cache, clock)
        val semaphore = Semaphore(maximumConcurrentOperations)
        val events = Channel<ResourceOperationEvent>(Channel.UNLIMITED)

        var state: ResourceOperationState.Running? = null
        var outcome: ResourceOperationOutcome? = null
        var pendingActionCount = 0

        fun apply(transition: ResourceOperationTransition) {
            transition.state?.let { state = it }
            transition.outcome?.let { outcome = it }
            transition.actions.forEach { action ->
                if (action is StartRoute) {
                    // StartRoute performs no adapter call and feeds back no event through `transition`:
                    // it is folded into the same loop as every other action by calling beginLookup
                    // directly and recursively applying whatever it yields.
                    apply(ResourceOperationStateMachine.beginLookup(requireNotNull(state), action.ordinal))
                } else {
                    pendingActionCount += 1
                    launch {
                        semaphore.withPermit {
                            events.send(executor.execute(action))
                        }
                    }
                }
            }
        }

        fun advancePendingClassGates() {
            while (true) {
                val running = state ?: return
                val record = running.routeRecords.firstOrNull { record ->
                    val cursor = record.cursor
                    cursor is PendingClassGates &&
                        ordinaryResourceClassGates(cursor.content.route.resourceClass) != null
                } ?: return
                val ordinal = (record.cursor as PendingClassGates).ordinal
                apply(ResourceOperationStateMachine.transition(running, AdvancePendingClassGates(ordinal)))
            }
        }

        apply(ResourceOperationStateMachine.start(definition))
        advancePendingClassGates()

        while (outcome == null) {
            check(pendingActionCount > 0) {
                "the resource operation state machine stalled with no pending action and no outcome"
            }
            val event = events.receive()
            pendingActionCount -= 1
            apply(ResourceOperationStateMachine.transition(requireNotNull(state), event))
            advancePendingClassGates()
        }

        requireNotNull(outcome)
    }
}
