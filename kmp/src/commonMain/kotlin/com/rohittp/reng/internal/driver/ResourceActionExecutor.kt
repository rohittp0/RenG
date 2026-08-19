package com.rohittp.reng.internal.driver

import com.rohittp.reng.Store
import com.rohittp.reng.Transport
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.resource.CallTransport
import com.rohittp.reng.internal.resource.ClockSampled
import com.rohittp.reng.internal.resource.InstallVisibility
import com.rohittp.reng.internal.resource.LatchedTransportReplayCompleted
import com.rohittp.reng.internal.resource.ObserveResident
import com.rohittp.reng.internal.resource.ReadStore
import com.rohittp.reng.internal.resource.ReplayLatchedTransport
import com.rohittp.reng.internal.resource.ResidentObserved
import com.rohittp.reng.internal.resource.ResourceClassValidationCompleted
import com.rohittp.reng.internal.resource.ResourceOperationAction
import com.rohittp.reng.internal.resource.ResourceOperationEvent
import com.rohittp.reng.internal.resource.SampleClock
import com.rohittp.reng.internal.resource.StoreReadCompleted
import com.rohittp.reng.internal.resource.StoreWriteCompleted
import com.rohittp.reng.internal.resource.SuppliedCallOutcome
import com.rohittp.reng.internal.resource.SuppliedInstallOutcome
import com.rohittp.reng.internal.resource.SuppliedValidationOutcome
import com.rohittp.reng.internal.resource.TransportCompleted
import com.rohittp.reng.internal.resource.ValidateResourceClass
import com.rohittp.reng.internal.resource.VisibilityInstallCompleted
import com.rohittp.reng.internal.resource.WriteStore
import kotlinx.coroutines.CancellationException

/**
 * Executes exactly one [ResourceOperationAction] against the real [Transport], [Store], and
 * [ResidentCache] adapters and reports exactly one resulting [ResourceOperationEvent]. This is the ONE
 * seam where an injected consumer adapter's own [Throwable] is observed, so it is also the one seam that
 * must never let that throwable's message or cause escape: every non-cancellation failure collapses to a
 * bare [SuppliedCallOutcome.Failed] — a marker with no payload at all — and [ResourceOperationStateMachine]
 * (not this class) is what turns that marker into a stable, redacted [FailureDescriptor]. A
 * [CancellationException] is never caught here; it is left to propagate unwrapped, exactly as structured
 * concurrency and Kotlin's own stack-trace recovery expect.
 *
 * `ResourceOperationAction` is a sealed interface with 18 subtypes. This file's `when` handles the five
 * that need a real adapter call — [SampleClock], [ObserveResident], [ReadStore], [CallTransport], and
 * [ReplayLatchedTransport] — plus two more forced into this task by [PreparationDriverTest]'s own
 * verbatim requirement to reach a completed [WriteStore] over a genuine ordinary-class round trip:
 * [ValidateResourceClass] (a placeholder that always reports `Valid`, since no `ClassGateRunner` exists
 * yet) and [WriteStore] itself (a real `Store.write` call, mapped the same way as every other adapter
 * call). [InstallVisibility] is likewise a placeholder that always succeeds, without yet installing into
 * [ResidentCache] or taking an owner's lease. Every action no task has reached is an unreachable `else`.
 */
internal class ResourceActionExecutor(
    private val transport: Transport,
    private val store: Store,
    private val cache: ResidentCache,
    private val clock: () -> Long,
) {
    suspend fun execute(action: ResourceOperationAction): ResourceOperationEvent = when (action) {
        is SampleClock -> ClockSampled(action.actionId, clock())

        is ObserveResident -> ResidentObserved(
            action.actionId,
            cache.current(action.resourceKey)?.stored,
        )

        is ReadStore -> StoreReadCompleted(
            action.actionId,
            suppliedCall { store.read(action.rawKey) },
        )

        is CallTransport -> TransportCompleted(
            action.actionId,
            suppliedCall { transport.execute(action.request) },
        )

        is ReplayLatchedTransport -> LatchedTransportReplayCompleted(action.actionId)

        // Placeholder pending Task 13's ClassGateRunner: no decoder or parser is wired into this
        // driver yet, so every gate reports Valid rather than genuinely decoding or parsing anything.
        is ValidateResourceClass -> ResourceClassValidationCompleted(
            action.actionId,
            SuppliedValidationOutcome.Valid,
        )

        is WriteStore -> StoreWriteCompleted(
            action.actionId,
            suppliedCall { store.write(action.rawKey, action.resource) },
        )

        // Placeholder pending Task 13: real visibility install stages the generation into the
        // ResidentCache and takes the owning route's lease. This driver has no owner-lease bookkeeping
        // yet, so it only reports the install as succeeded.
        is InstallVisibility -> VisibilityInstallCompleted(action.actionId, SuppliedInstallOutcome.Succeeded)

        else -> error("ResourceActionExecutor does not yet handle $action")
    }

    /**
     * Runs one suspending adapter call, collapsing any non-cancellation [Throwable] into a bare
     * [SuppliedCallOutcome.Failed] with no message, no cause, and no adapter-supplied context — the
     * adapter's own exception text can carry signed URLs or other consumer secrets, so nothing about it
     * may reach a diagnostic. [CancellationException] is rethrown unwrapped rather than converted, so
     * structured concurrency and Kotlin's own stack-trace recovery keep working.
     */
    private suspend fun <T> suppliedCall(block: suspend () -> T): SuppliedCallOutcome<T> = try {
        SuppliedCallOutcome.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        SuppliedCallOutcome.Failed
    }
}
