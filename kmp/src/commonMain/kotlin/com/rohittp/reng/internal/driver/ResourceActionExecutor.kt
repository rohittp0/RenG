package com.rohittp.reng.internal.driver

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.Store
import com.rohittp.reng.Transport
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.resource.CallTransport
import com.rohittp.reng.internal.resource.ClockSampled
import com.rohittp.reng.internal.resource.ContentProvenance
import com.rohittp.reng.internal.resource.InstallVisibility
import com.rohittp.reng.internal.resource.LatchedTransportReplayCompleted
import com.rohittp.reng.internal.resource.ObserveResident
import com.rohittp.reng.internal.resource.ReadStore
import com.rohittp.reng.internal.resource.ReplayLatchedTransport
import com.rohittp.reng.internal.resource.ResidentObserved
import com.rohittp.reng.internal.resource.ResolvedResourceContent
import com.rohittp.reng.internal.resource.ResourceClassValidationCompleted
import com.rohittp.reng.internal.resource.ResourceOperationAction
import com.rohittp.reng.internal.resource.ResourceOperationEvent
import com.rohittp.reng.internal.resource.SampleClock
import com.rohittp.reng.internal.resource.StoreReadCompleted
import com.rohittp.reng.internal.resource.StoreWriteCompleted
import com.rohittp.reng.internal.resource.SuppliedCallOutcome
import com.rohittp.reng.internal.resource.SuppliedInstallOutcome
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
 * [ReplayLatchedTransport] — plus three more: [WriteStore] (a real `Store.write` call, mapped the same
 * way as every other adapter call), [ValidateResourceClass] (a real [ClassGateRunner] call — see that
 * class for what "real" means for each gate, including the classes it does not yet observe), and
 * [InstallVisibility] (a real [ResidentCache] install-and-lease — see [installVisibility]). Every action
 * no task has reached is an unreachable `else`.
 *
 * Unlike every other `Failed` outcome here, [SuppliedInstallOutcome.Failed] carries a [FailureDescriptor]
 * this class constructs directly rather than a bare marker [ResourceOperationStateMachine] classifies —
 * [ResourceOperationStateMachine.visibilityInstallCompleted] forwards it verbatim. That asymmetry is
 * deliberate: an install failure is inherently something only this executor observes at the cache layer
 * (unlike a class gate, whose failure category the gate's own identity already determines), and — unlike
 * [Transport]/[Store] — [ResidentCache] is RenG's own internal component, not a consumer-injected
 * adapter, so nothing about a cache-observed condition needs redacting.
 */
internal class ResourceActionExecutor(
    private val transport: Transport,
    private val store: Store,
    private val cache: ResidentCache,
    private val classGateRunner: ClassGateRunner,
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

        is ValidateResourceClass -> ResourceClassValidationCompleted(
            action.actionId,
            classGateRunner.run(action.gate, action.content),
        )

        is WriteStore -> StoreWriteCompleted(
            action.actionId,
            suppliedCall { store.write(action.rawKey, action.resource) },
        )

        is InstallVisibility -> VisibilityInstallCompleted(action.actionId, installVisibility(action.content))

        else -> error("ResourceActionExecutor does not yet handle $action")
    }

    /**
     * Installs [content] into visibility: one conceptual action, "install the generation and take the
     * owner's lease," performed as one atomic [ResidentCache] call so no observer can ever see a
     * freshly installed, zero-leased generation in a gap between two separate calls (see
     * [ResidentCache.installAndTakeLease]'s own KDoc for the concurrency hazard this closes).
     *
     * [ContentProvenance.RESIDENT] content is different in kind, not just in the cache call it needs:
     * it names a generation this route already observed resident earlier in its own lookup, not new
     * bytes to install, so it re-leases the CURRENT generation via [ResidentCache.observeAndTakeLease]
     * rather than installing a redundant new one. Everything else that reaches this point is content
     * this route freshly resolved (from the Store or Transport) and never installed anywhere, so it
     * installs a new generation.
     *
     * The returned [com.rohittp.reng.internal.cache.Lease] is intentionally dropped: this driver has no
     * owner-lease bookkeeping yet (a future task's job), so today's install-and-lease is real but its
     * lease is not yet retained for a later release.
     *
     * A [ContentProvenance.RESIDENT] generation can genuinely vanish between when this route first
     * observed it and when visibility is installed here — a consumer's own concurrent
     * `free()`/`close()` call, or a competing route superseding the same key, are both real races this
     * architecture allows (ADR 0019's renderer mutex guards the cache, not the whole operation). That is
     * reported as a real, typed failure rather than a crash.
     */
    private fun installVisibility(content: ResolvedResourceContent): SuppliedInstallOutcome =
        when (content.provenance) {
            ContentProvenance.RESIDENT -> {
                val lease = cache.observeAndTakeLease(content.resourceKey)
                if (lease != null) SuppliedInstallOutcome.Succeeded else SuppliedInstallOutcome.Failed(
                    residentGenerationVanishedFailure(content),
                )
            }

            ContentProvenance.STORE,
            ContentProvenance.TRANSPORT_200,
            ContentProvenance.TRANSPORT_304_MERGED,
            -> {
                cache.installAndTakeLease(content.resourceKey, content.stored, decoded = null)
                SuppliedInstallOutcome.Succeeded
            }
        }

    /**
     * The resource this route selected as [ContentProvenance.RESIDENT] is no longer resident by the
     * time visibility is installed. Reuses [RenGErrorCode.RESOURCE_UNAVAILABLE] at
     * [PipelineStage.RESOURCE_LOOKUP] — the same (code, stage) pairing
     * [ResourceOperationStateMachine]'s own `resourceUnavailableFailure` uses for a `CACHE_ONLY` miss —
     * because both are the same fault: the resource this route needed is not available where it was
     * expected to be.
     */
    private fun residentGenerationVanishedFailure(content: ResolvedResourceContent): FailureDescriptor =
        FailureDescriptor(
            code = RenGErrorCode.RESOURCE_UNAVAILABLE,
            stage = PipelineStage.RESOURCE_LOOKUP,
            diagnostic = failureContextDiagnostic(
                stage = PipelineStage.RESOURCE_LOOKUP,
                fieldName = DiagnosticField.RESOURCE,
                resourceClass = content.route.resourceClass,
                resourceKey = content.resourceKey,
            ),
        )

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
