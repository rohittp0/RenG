package com.rohittp.reng.internal.preparation

import com.rohittp.reng.FramePlan
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.freshListCopy
import com.rohittp.reng.internal.identity.EncodedFramePlan
import com.rohittp.reng.internal.planning.FramePlanningOutcome
import com.rohittp.reng.internal.planning.FramePlanningRequest
import com.rohittp.reng.internal.planning.PlannedFrameCore
import com.rohittp.reng.internal.resource.CancellationSelection
import com.rohittp.reng.internal.resource.ResourceOperationDefinition
import com.rohittp.reng.internal.resource.ResourceOperationOutcome
import com.rohittp.reng.internal.resource.VisibleResource
import kotlin.jvm.JvmInline

@JvmInline
internal value class PreparationInvocationId(val value: Long) {
    init {
        require(value > 0L) { "preparation invocation ID must be positive" }
    }
}

@JvmInline
internal value class PreparationItemId(val value: Long) {
    init {
        require(value >= 0L) { "preparation item ID must be non-negative" }
    }
}

@JvmInline
internal value class LeaseId(val value: Long) {
    init {
        require(value > 0L) { "lease ID must be positive" }
    }
}

internal data class CommittedFrameHistory(
    val frameIndex: Long,
    val encodedPlan: EncodedFramePlan,
    val selectedLod: Int,
) {
    init {
        require(frameIndex >= 0L) { "committed frame index must be non-negative" }
        require(selectedLod >= 0) { "committed selected LOD must be non-negative" }
    }
}

internal data class PreparationEnvironment(
    val outputPixelSize: OutputPixelSize,
    val basemapStyle: ResourceLocator?,
    val resourceLimits: ResourceLimits,
    val maximumBasemapTileInstances: Int,
    val maximumPreparationBatchSize: Int,
    val maximumConcurrentResourceOperations: Int,
) {
    init {
        require(maximumBasemapTileInstances in MINIMUM_BUDGET..MAXIMUM_TILE_INSTANCES) {
            "maximumBasemapTileInstances must be within its configured range"
        }
        require(maximumPreparationBatchSize in MINIMUM_BUDGET..MAXIMUM_BATCH_SIZE) {
            "maximumPreparationBatchSize must be within its configured range"
        }
        require(maximumConcurrentResourceOperations in MINIMUM_BUDGET..MAXIMUM_CONCURRENCY) {
            "maximumConcurrentResourceOperations must be within its configured range"
        }
    }

    private companion object {
        const val MINIMUM_BUDGET: Int = 1
        const val MAXIMUM_TILE_INSTANCES: Int = 4096
        const val MAXIMUM_BATCH_SIZE: Int = 4096
        const val MAXIMUM_CONCURRENCY: Int = 64
    }
}

internal class PreparationInvocation(
    val id: PreparationInvocationId,
    val accessMode: ResourceAccessMode,
    plans: List<FramePlan>,
    val environment: PreparationEnvironment,
    val initialHistory: CommittedFrameHistory?,
) {
    private val planSnapshot: List<FramePlan> = freshListCopy(plans)

    init {
        require(planSnapshot.isNotEmpty()) { "a preparation invocation requires a nonempty batch" }
        require(planSnapshot.size <= environment.maximumPreparationBatchSize) {
            "a preparation invocation must respect its configured batch limit"
        }
        var bound: Long? = initialHistory?.frameIndex
        for (plan in planSnapshot) {
            val previous = bound
            require(previous == null || plan.frameIndex > previous) {
                "batch frame indices must strictly increase above committed history"
            }
            bound = plan.frameIndex
        }
    }

    val plans: List<FramePlan>
        get() = freshListCopy(planSnapshot)

    override fun equals(other: Any?): Boolean =
        other is PreparationInvocation &&
            id == other.id &&
            accessMode == other.accessMode &&
            planSnapshot == other.planSnapshot &&
            environment == other.environment &&
            initialHistory == other.initialHistory

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + accessMode.hashCode()
        result = 31 * result + planSnapshot.hashCode()
        result = 31 * result + environment.hashCode()
        result = 31 * result + initialHistory.hashCode()
        return result
    }

    override fun toString(): String =
        "PreparationInvocation(id=$id, accessMode=$accessMode, planCount=${planSnapshot.size}, " +
            "historyPresent=${initialHistory != null})"
}

internal data class PlannedPreparationItem(
    val itemId: PreparationItemId,
    val plannedFrame: PlannedFrameCore,
)

internal sealed interface LeaseResource {
    data class External(val visible: VisibleResource) : LeaseResource

    data class PlannedLogical(val key: ResourceKey) : LeaseResource
}

internal data class LeaseInstallRequest(
    val itemId: PreparationItemId,
    val traversalIndex: Int,
    val resource: LeaseResource,
) {
    init {
        require(traversalIndex >= 0) { "lease traversal index must be non-negative" }
    }
}

internal data class AcknowledgedLease(
    val leaseId: LeaseId,
    val request: LeaseInstallRequest,
)

internal class PreparedFrameSeed(
    val itemId: PreparationItemId,
    val frameIndex: Long,
    val plannedFrame: PlannedFrameCore,
    leases: List<AcknowledgedLease>,
) {
    private val leaseSnapshot: List<AcknowledgedLease> = freshListCopy(leases)

    init {
        require(frameIndex >= 0L) { "a prepared frame seed requires a non-negative frame index" }
        require(leaseSnapshot.all { it.request.itemId == itemId }) {
            "a prepared frame seed carries only its own item's leases"
        }
    }

    val leases: List<AcknowledgedLease>
        get() = freshListCopy(leaseSnapshot)

    override fun equals(other: Any?): Boolean =
        other is PreparedFrameSeed &&
            itemId == other.itemId &&
            frameIndex == other.frameIndex &&
            plannedFrame == other.plannedFrame &&
            leaseSnapshot == other.leaseSnapshot

    override fun hashCode(): Int {
        var result = itemId.hashCode()
        result = 31 * result + frameIndex.hashCode()
        result = 31 * result + plannedFrame.hashCode()
        result = 31 * result + leaseSnapshot.hashCode()
        return result
    }

    override fun toString(): String =
        "PreparedFrameSeed(itemId=$itemId, frameIndex=$frameIndex, leaseCount=${leaseSnapshot.size})"
}

internal sealed interface OrderedPreparationTerminal {
    data class Failure(val failure: FailureDescriptor) : OrderedPreparationTerminal

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : OrderedPreparationTerminal
}

internal sealed interface OrderedPreparationState {
    data class Idle(
        val history: CommittedFrameHistory?,
    ) : OrderedPreparationState

    class Planning(
        val invocation: PreparationInvocation,
        val nextItemIndex: Int,
        val provisionalHistory: CommittedFrameHistory?,
        plannedItems: List<PlannedPreparationItem>,
    ) : OrderedPreparationState {
        private val plannedItemSnapshot: List<PlannedPreparationItem> = freshListCopy(plannedItems)

        init {
            require(nextItemIndex in invocation.plans.indices) {
                "the outstanding planning index must address a batch item"
            }
            require(nextItemIndex == plannedItemSnapshot.size) {
                "planning completes batch items in strict input order"
            }
        }

        val plannedItems: List<PlannedPreparationItem>
            get() = freshListCopy(plannedItemSnapshot)

        override fun equals(other: Any?): Boolean =
            other is Planning &&
                invocation == other.invocation &&
                nextItemIndex == other.nextItemIndex &&
                provisionalHistory == other.provisionalHistory &&
                plannedItemSnapshot == other.plannedItemSnapshot

        override fun hashCode(): Int {
            var result = invocation.hashCode()
            result = 31 * result + nextItemIndex
            result = 31 * result + provisionalHistory.hashCode()
            result = 31 * result + plannedItemSnapshot.hashCode()
            return result
        }

        override fun toString(): String =
            "Planning(invocationId=${invocation.id}, nextItemIndex=$nextItemIndex, " +
                "plannedItemCount=${plannedItemSnapshot.size}, " +
                "provisionalHistoryPresent=${provisionalHistory != null})"
    }

    class ResolvingResources(
        val invocation: PreparationInvocation,
        plannedItems: List<PlannedPreparationItem>,
        val pendingCancellation: CancellationSelection? = null,
    ) : OrderedPreparationState {
        private val plannedItemSnapshot: List<PlannedPreparationItem> = freshListCopy(plannedItems)

        init {
            require(plannedItemSnapshot.size == invocation.plans.size) {
                "resource resolution requires every batch item planned"
            }
        }

        val plannedItems: List<PlannedPreparationItem>
            get() = freshListCopy(plannedItemSnapshot)

        override fun equals(other: Any?): Boolean =
            other is ResolvingResources &&
                invocation == other.invocation &&
                plannedItemSnapshot == other.plannedItemSnapshot &&
                pendingCancellation == other.pendingCancellation

        override fun hashCode(): Int {
            var result = invocation.hashCode()
            result = 31 * result + plannedItemSnapshot.hashCode()
            result = 31 * result + pendingCancellation.hashCode()
            return result
        }

        override fun toString(): String =
            "ResolvingResources(invocationId=${invocation.id}, " +
                "plannedItemCount=${plannedItemSnapshot.size}, " +
                "pendingCancellationPresent=${pendingCancellation != null})"
    }

    class InstallingLeases(
        val invocation: PreparationInvocation,
        plannedItems: List<PlannedPreparationItem>,
        pendingInstalls: List<LeaseInstallRequest>,
        acknowledgedLeases: List<AcknowledgedLease>,
    ) : OrderedPreparationState {
        private val plannedItemSnapshot: List<PlannedPreparationItem> = freshListCopy(plannedItems)
        private val pendingInstallSnapshot: List<LeaseInstallRequest> = freshListCopy(pendingInstalls)
        private val acknowledgedLeaseSnapshot: List<AcknowledgedLease> = freshListCopy(acknowledgedLeases)

        init {
            require(plannedItemSnapshot.size == invocation.plans.size) {
                "lease installation requires every batch item planned"
            }
            require(pendingInstallSnapshot.isNotEmpty()) {
                "lease installation requires an outstanding install request"
            }
        }

        val plannedItems: List<PlannedPreparationItem>
            get() = freshListCopy(plannedItemSnapshot)

        val pendingInstalls: List<LeaseInstallRequest>
            get() = freshListCopy(pendingInstallSnapshot)

        val acknowledgedLeases: List<AcknowledgedLease>
            get() = freshListCopy(acknowledgedLeaseSnapshot)

        override fun equals(other: Any?): Boolean =
            other is InstallingLeases &&
                invocation == other.invocation &&
                plannedItemSnapshot == other.plannedItemSnapshot &&
                pendingInstallSnapshot == other.pendingInstallSnapshot &&
                acknowledgedLeaseSnapshot == other.acknowledgedLeaseSnapshot

        override fun hashCode(): Int {
            var result = invocation.hashCode()
            result = 31 * result + plannedItemSnapshot.hashCode()
            result = 31 * result + pendingInstallSnapshot.hashCode()
            result = 31 * result + acknowledgedLeaseSnapshot.hashCode()
            return result
        }

        override fun toString(): String =
            "InstallingLeases(invocationId=${invocation.id}, " +
                "pendingInstallCount=${pendingInstallSnapshot.size}, " +
                "acknowledgedLeaseCount=${acknowledgedLeaseSnapshot.size})"
    }

    class RollingBack(
        val invocation: PreparationInvocation,
        val originalOutcome: OrderedPreparationTerminal,
        pendingReleases: List<AcknowledgedLease>,
        releasedLeases: List<LeaseId>,
        failedReleaseLeases: List<LeaseId>,
    ) : OrderedPreparationState {
        private val pendingReleaseSnapshot: List<AcknowledgedLease> = freshListCopy(pendingReleases)
        private val releasedLeaseSnapshot: List<LeaseId> = freshListCopy(releasedLeases)
        private val failedReleaseLeaseSnapshot: List<LeaseId> = freshListCopy(failedReleaseLeases)

        init {
            require(pendingReleaseSnapshot.isNotEmpty()) {
                "rollback requires an outstanding release request"
            }
        }

        val pendingReleases: List<AcknowledgedLease>
            get() = freshListCopy(pendingReleaseSnapshot)

        val releasedLeases: List<LeaseId>
            get() = freshListCopy(releasedLeaseSnapshot)

        val failedReleaseLeases: List<LeaseId>
            get() = freshListCopy(failedReleaseLeaseSnapshot)

        override fun equals(other: Any?): Boolean =
            other is RollingBack &&
                invocation == other.invocation &&
                originalOutcome == other.originalOutcome &&
                pendingReleaseSnapshot == other.pendingReleaseSnapshot &&
                releasedLeaseSnapshot == other.releasedLeaseSnapshot &&
                failedReleaseLeaseSnapshot == other.failedReleaseLeaseSnapshot

        override fun hashCode(): Int {
            var result = invocation.hashCode()
            result = 31 * result + originalOutcome.hashCode()
            result = 31 * result + pendingReleaseSnapshot.hashCode()
            result = 31 * result + releasedLeaseSnapshot.hashCode()
            result = 31 * result + failedReleaseLeaseSnapshot.hashCode()
            return result
        }

        override fun toString(): String =
            "RollingBack(invocationId=${invocation.id}, " +
                "pendingReleaseCount=${pendingReleaseSnapshot.size}, " +
                "releasedLeaseCount=${releasedLeaseSnapshot.size}, " +
                "failedReleaseLeaseCount=${failedReleaseLeaseSnapshot.size})"
    }
}

internal sealed interface OrderedPreparationAction {
    data class RunPurePlanning(
        val itemId: PreparationItemId,
        val request: FramePlanningRequest,
    ) : OrderedPreparationAction

    data class RunResourceOperation(
        val definition: ResourceOperationDefinition,
    ) : OrderedPreparationAction

    data class RequestResourceCancellation(
        val invocationId: PreparationInvocationId,
        val cancellation: CancellationSelection,
    ) : OrderedPreparationAction

    data class InstallLease(
        val request: LeaseInstallRequest,
    ) : OrderedPreparationAction

    data class ReleaseLease(
        val lease: AcknowledgedLease,
    ) : OrderedPreparationAction
}

internal sealed interface OrderedPreparationEvent {
    data class BeginSingleton(
        val invocationId: PreparationInvocationId,
        val plan: FramePlan,
        val accessMode: ResourceAccessMode,
        val environment: PreparationEnvironment,
    ) : OrderedPreparationEvent

    class BeginBatch(
        val invocationId: PreparationInvocationId,
        plans: List<FramePlan>,
        val accessMode: ResourceAccessMode,
        val environment: PreparationEnvironment,
    ) : OrderedPreparationEvent {
        private val planSnapshot: List<FramePlan> = freshListCopy(plans)

        val plans: List<FramePlan>
            get() = freshListCopy(planSnapshot)

        override fun equals(other: Any?): Boolean =
            other is BeginBatch &&
                invocationId == other.invocationId &&
                planSnapshot == other.planSnapshot &&
                accessMode == other.accessMode &&
                environment == other.environment

        override fun hashCode(): Int {
            var result = invocationId.hashCode()
            result = 31 * result + planSnapshot.hashCode()
            result = 31 * result + accessMode.hashCode()
            result = 31 * result + environment.hashCode()
            return result
        }

        override fun toString(): String =
            "BeginBatch(invocationId=$invocationId, planCount=${planSnapshot.size}, accessMode=$accessMode)"
    }

    data class PlanningCompleted(
        val itemId: PreparationItemId,
        val outcome: FramePlanningOutcome,
    ) : OrderedPreparationEvent

    data class ResourcesCompleted(
        val outcome: ResourceOperationOutcome,
    ) : OrderedPreparationEvent

    data class CancellationRequested(
        val cancellation: CancellationSelection,
    ) : OrderedPreparationEvent

    data class LeaseInstallAcknowledged(
        val request: LeaseInstallRequest,
        val leaseId: LeaseId,
    ) : OrderedPreparationEvent

    data class LeaseInstallFailed(
        val request: LeaseInstallRequest,
        val failure: FailureDescriptor,
    ) : OrderedPreparationEvent

    data class LeaseInstallCancelled(
        val request: LeaseInstallRequest,
        val cancellation: CancellationSelection,
    ) : OrderedPreparationEvent

    data class LeaseReleaseAcknowledged(
        val leaseId: LeaseId,
    ) : OrderedPreparationEvent

    data class LeaseReleaseFailed(
        val leaseId: LeaseId,
    ) : OrderedPreparationEvent

    data object ClearHistoryRequested : OrderedPreparationEvent
}

internal sealed interface OrderedPreparationCursor {
    data class AwaitingPlanning(val itemId: PreparationItemId) : OrderedPreparationCursor

    data object AwaitingResources : OrderedPreparationCursor

    data class AwaitingResourceCancellation(
        val cancellation: CancellationSelection,
    ) : OrderedPreparationCursor

    data class AwaitingLeaseInstall(
        val request: LeaseInstallRequest,
    ) : OrderedPreparationCursor

    data class AwaitingLeaseRelease(
        val lease: AcknowledgedLease,
    ) : OrderedPreparationCursor
}

internal sealed interface OrderedPreparationOutcome {
    class Success(
        frameSeeds: List<PreparedFrameSeed>,
    ) : OrderedPreparationOutcome {
        private val frameSeedSnapshot: List<PreparedFrameSeed> = freshListCopy(frameSeeds)

        init {
            require(frameSeedSnapshot.isNotEmpty()) { "successful preparation returns at least one seed" }
        }

        val frameSeeds: List<PreparedFrameSeed>
            get() = freshListCopy(frameSeedSnapshot)

        override fun equals(other: Any?): Boolean =
            other is Success && frameSeedSnapshot == other.frameSeedSnapshot

        override fun hashCode(): Int = frameSeedSnapshot.hashCode()

        override fun toString(): String = "Success(frameSeedCount=${frameSeedSnapshot.size})"
    }

    data class Failure(val failure: FailureDescriptor) : OrderedPreparationOutcome

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : OrderedPreparationOutcome

    data object HistoryCleared : OrderedPreparationOutcome
}

/**
 * The single wait a state implies. Every transition re-reports it, so a rejected interfering operation
 * never disturbs the active invocation's outstanding work and a terminal transition reports no wait.
 */
internal fun OrderedPreparationState.currentWait(): OrderedPreparationCursor? = when (this) {
    is OrderedPreparationState.Idle -> null
    is OrderedPreparationState.Planning ->
        OrderedPreparationCursor.AwaitingPlanning(PreparationItemId(nextItemIndex.toLong()))
    is OrderedPreparationState.ResolvingResources -> pendingCancellation
        ?.let(OrderedPreparationCursor::AwaitingResourceCancellation)
        ?: OrderedPreparationCursor.AwaitingResources
    is OrderedPreparationState.InstallingLeases ->
        OrderedPreparationCursor.AwaitingLeaseInstall(pendingInstalls.first())
    is OrderedPreparationState.RollingBack ->
        OrderedPreparationCursor.AwaitingLeaseRelease(pendingReleases.first())
}

internal class OrderedPreparationTransition(
    val state: OrderedPreparationState,
    actions: List<OrderedPreparationAction>,
    val cursor: OrderedPreparationCursor?,
    val outcome: OrderedPreparationOutcome?,
) {
    private val actionSnapshot: List<OrderedPreparationAction> = freshListCopy(actions)

    init {
        require(cursor == state.currentWait()) {
            "a transition reports exactly the wait its state implies"
        }
    }

    val actions: List<OrderedPreparationAction>
        get() = freshListCopy(actionSnapshot)

    override fun equals(other: Any?): Boolean =
        other is OrderedPreparationTransition &&
            state == other.state &&
            actionSnapshot == other.actionSnapshot &&
            cursor == other.cursor &&
            outcome == other.outcome

    override fun hashCode(): Int {
        var result = state.hashCode()
        result = 31 * result + actionSnapshot.hashCode()
        result = 31 * result + cursor.hashCode()
        result = 31 * result + outcome.hashCode()
        return result
    }

    override fun toString(): String =
        "OrderedPreparationTransition(state=$state, actionCount=${actionSnapshot.size}, " +
            "cursorPresent=${cursor != null}, outcomePresent=${outcome != null})"
}
