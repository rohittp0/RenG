package com.rohittp.reng.internal.preparation

import com.rohittp.reng.FramePlan
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.planning.FramePlanningOutcome
import com.rohittp.reng.internal.planning.FramePlanningRequest
import com.rohittp.reng.internal.planning.StaticResourceReference
import com.rohittp.reng.internal.resource.CancellationSelection
import com.rohittp.reng.internal.resource.CanonicalIdentityRecord
import com.rohittp.reng.internal.resource.OwnerResourceSet
import com.rohittp.reng.internal.resource.ResourceCommitBinding
import com.rohittp.reng.internal.resource.ResourceOccurrence
import com.rohittp.reng.internal.resource.ResourceOccurrenceId
import com.rohittp.reng.internal.resource.ResourceOperationDefinition
import com.rohittp.reng.internal.resource.ResourceOperationOutcome
import com.rohittp.reng.internal.resource.ResourceOwnerId
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.internal.resource.ResourceRouteRegistration
import com.rohittp.reng.internal.resource.StyleGroupId

/**
 * The pure ordered-preparation reducer of ADR 0014. One renderer admits exactly one active invocation;
 * planning, structural diffing, and LOD selection advance in input order behind a complete planning
 * barrier; resource work and lease installation only follow that barrier; and Frame History commits
 * exactly once, atomically, from the batch's final item. Cancellation is modelled as an opaque selected
 * value, never as a thrown coroutine type, so the integration layer alone rethrows it unchanged.
 */
internal object OrderedPreparationStateMachine {
    internal fun transition(
        state: OrderedPreparationState,
        event: OrderedPreparationEvent,
    ): OrderedPreparationTransition = when (event) {
        is OrderedPreparationEvent.BeginSingleton -> begin(
            state = state,
            invocationId = event.invocationId,
            plans = listOf(event.plan),
            accessMode = event.accessMode,
            environment = event.environment,
        )

        is OrderedPreparationEvent.BeginBatch -> begin(
            state = state,
            invocationId = event.invocationId,
            plans = event.plans,
            accessMode = event.accessMode,
            environment = event.environment,
        )

        is OrderedPreparationEvent.PlanningCompleted -> planningCompleted(state, event)
        is OrderedPreparationEvent.ResourcesCompleted -> resourcesCompleted(state, event)
        is OrderedPreparationEvent.CancellationRequested -> cancellationRequested(state, event.cancellation)

        is OrderedPreparationEvent.LeaseInstallAcknowledged -> leaseInstallAcknowledged(state, event)

        is OrderedPreparationEvent.LeaseInstallFailed -> leaseInstallSettled(
            state = state,
            request = event.request,
            terminal = OrderedPreparationTerminal.Failure(event.failure),
        )

        is OrderedPreparationEvent.LeaseInstallCancelled -> leaseInstallSettled(
            state = state,
            request = event.request,
            terminal = OrderedPreparationTerminal.Cancelled(event.cancellation),
        )

        is OrderedPreparationEvent.LeaseReleaseAcknowledged -> leaseReleaseSettled(
            state = state,
            leaseId = event.leaseId,
            released = true,
        )

        is OrderedPreparationEvent.LeaseReleaseFailed -> leaseReleaseSettled(
            state = state,
            leaseId = event.leaseId,
            released = false,
        )

        OrderedPreparationEvent.ClearHistoryRequested -> clearHistory(state)
    }

    private fun begin(
        state: OrderedPreparationState,
        invocationId: PreparationInvocationId,
        plans: List<FramePlan>,
        accessMode: ResourceAccessMode,
        environment: PreparationEnvironment,
    ): OrderedPreparationTransition {
        val idle = state as? OrderedPreparationState.Idle
            ?: return rejected(state, preparationInProgress(PipelineStage.FRAME_PREPARATION))
        if (plans.isEmpty()) {
            return rejected(
                state = state,
                failure = FailureDescriptor(
                    code = RenGErrorCode.INVALID_VALUE,
                    stage = PipelineStage.FRAME_PLANNING,
                    diagnostic = failureContextDiagnostic(
                        stage = PipelineStage.FRAME_PLANNING,
                        fieldName = DiagnosticField.PLANS,
                    ),
                ),
            )
        }
        if (plans.size > environment.maximumPreparationBatchSize) {
            return rejected(
                state = state,
                failure = FailureDescriptor(
                    code = RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    stage = PipelineStage.FRAME_PLANNING,
                    diagnostic = failureContextDiagnostic(
                        stage = PipelineStage.FRAME_PLANNING,
                        fieldName = DiagnosticField.PLANS,
                        limit = environment.maximumPreparationBatchSize.toLong(),
                        actual = plans.size.toLong(),
                    ),
                ),
            )
        }
        var bound: Long? = idle.history?.frameIndex
        for (plan in plans) {
            val previous = bound
            if (previous != null && plan.frameIndex <= previous) {
                return rejected(
                    state = state,
                    failure = FailureDescriptor(
                        code = RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                        stage = PipelineStage.FRAME_PLANNING,
                        diagnostic = failureContextDiagnostic(
                            stage = PipelineStage.FRAME_PLANNING,
                            fieldName = DiagnosticField.FRAME_INDEX,
                        ),
                    ),
                )
            }
            bound = plan.frameIndex
        }

        return startPlanning(
            invocation = PreparationInvocation(
                id = invocationId,
                accessMode = accessMode,
                plans = plans,
                environment = environment,
                initialHistory = idle.history,
            ),
            plannedItems = emptyList(),
            provisionalHistory = idle.history,
        )
    }

    private fun startPlanning(
        invocation: PreparationInvocation,
        plannedItems: List<PlannedPreparationItem>,
        provisionalHistory: CommittedFrameHistory?,
    ): OrderedPreparationTransition {
        val itemIndex = plannedItems.size
        return progress(
            state = OrderedPreparationState.Planning(
                invocation = invocation,
                nextItemIndex = itemIndex,
                provisionalHistory = provisionalHistory,
                plannedItems = plannedItems,
            ),
            actions = listOf(
                OrderedPreparationAction.RunPurePlanning(
                    itemId = PreparationItemId(itemIndex.toLong()),
                    request = FramePlanningRequest(
                        plan = invocation.plans[itemIndex],
                        outputPixelSize = invocation.environment.outputPixelSize,
                        basemapStyle = invocation.environment.basemapStyle,
                        resourceLimits = invocation.environment.resourceLimits,
                        maximumBasemapTileInstances = invocation.environment.maximumBasemapTileInstances,
                        previousPlan = provisionalHistory?.encodedPlan,
                        previousSelectedLod = provisionalHistory?.selectedLod,
                    ),
                ),
            ),
        )
    }

    private fun planningCompleted(
        state: OrderedPreparationState,
        event: OrderedPreparationEvent.PlanningCompleted,
    ): OrderedPreparationTransition {
        val planning = state as? OrderedPreparationState.Planning
            ?: unexpectedEvent("pure planning completion requires an outstanding planning item")
        val invocation = planning.invocation
        val itemIndex = planning.nextItemIndex
        require(event.itemId == PreparationItemId(itemIndex.toLong())) {
            "pure planning completion must answer the outstanding batch item"
        }

        return when (val outcome = event.outcome) {
            is FramePlanningOutcome.Failure -> settled(
                history = invocation.initialHistory,
                outcome = OrderedPreparationOutcome.Failure(outcome.failure),
            )

            is FramePlanningOutcome.Success -> {
                val plannedItems = planning.plannedItems + PlannedPreparationItem(event.itemId, outcome.planned)
                if (plannedItems.size < invocation.plans.size) {
                    startPlanning(
                        invocation = invocation,
                        plannedItems = plannedItems,
                        provisionalHistory = historyFor(invocation, plannedItems),
                    )
                } else {
                    progress(
                        state = OrderedPreparationState.ResolvingResources(
                            invocation = invocation,
                            plannedItems = plannedItems,
                        ),
                        actions = listOf(
                            OrderedPreparationAction.RunResourceOperation(
                                definition = resourceDefinition(invocation, plannedItems),
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun resourcesCompleted(
        state: OrderedPreparationState,
        event: OrderedPreparationEvent.ResourcesCompleted,
    ): OrderedPreparationTransition {
        val resolving = state as? OrderedPreparationState.ResolvingResources
            ?: unexpectedEvent("resource completion requires an outstanding resource operation")
        val invocation = resolving.invocation
        return when (val outcome = event.outcome) {
            is ResourceOperationOutcome.Failure -> settled(
                history = invocation.initialHistory,
                outcome = OrderedPreparationOutcome.Failure(outcome.failure),
            )

            is ResourceOperationOutcome.Cancelled -> settled(
                history = invocation.initialHistory,
                outcome = OrderedPreparationOutcome.Cancelled(outcome.cancellation),
            )

            is ResourceOperationOutcome.Success -> {
                val pendingCancellation = resolving.pendingCancellation
                if (pendingCancellation != null) {
                    settled(
                        history = invocation.initialHistory,
                        outcome = OrderedPreparationOutcome.Cancelled(pendingCancellation),
                    )
                } else {
                    startLeaseInstallation(
                        invocation = invocation,
                        plannedItems = resolving.plannedItems,
                        resourceSets = outcome.resourceSets,
                    )
                }
            }
        }
    }

    private fun startLeaseInstallation(
        invocation: PreparationInvocation,
        plannedItems: List<PlannedPreparationItem>,
        resourceSets: List<OwnerResourceSet>,
    ): OrderedPreparationTransition {
        val requests = leaseInstallRequests(plannedItems, resourceSets)
        if (requests.isEmpty()) {
            return commit(invocation, plannedItems, emptyList())
        }
        return progress(
            state = OrderedPreparationState.InstallingLeases(
                invocation = invocation,
                plannedItems = plannedItems,
                pendingInstalls = requests,
                acknowledgedLeases = emptyList(),
            ),
            actions = listOf(OrderedPreparationAction.InstallLease(requests.first())),
        )
    }

    private fun leaseInstallAcknowledged(
        state: OrderedPreparationState,
        event: OrderedPreparationEvent.LeaseInstallAcknowledged,
    ): OrderedPreparationTransition {
        val installing = state as? OrderedPreparationState.InstallingLeases
            ?: unexpectedEvent("lease acknowledgement requires an outstanding install request")
        val pending = installing.pendingInstalls
        require(pending.first() == event.request) {
            "a lease acknowledgement must answer the outstanding install request"
        }
        val acknowledged = installing.acknowledgedLeases + AcknowledgedLease(event.leaseId, event.request)
        val remaining = pending.drop(1)
        if (remaining.isEmpty()) {
            return commit(installing.invocation, installing.plannedItems, acknowledged)
        }
        return progress(
            state = OrderedPreparationState.InstallingLeases(
                invocation = installing.invocation,
                plannedItems = installing.plannedItems,
                pendingInstalls = remaining,
                acknowledgedLeases = acknowledged,
            ),
            actions = listOf(OrderedPreparationAction.InstallLease(remaining.first())),
        )
    }

    private fun leaseInstallSettled(
        state: OrderedPreparationState,
        request: LeaseInstallRequest,
        terminal: OrderedPreparationTerminal,
    ): OrderedPreparationTransition {
        val installing = state as? OrderedPreparationState.InstallingLeases
            ?: unexpectedEvent("a lease install terminal requires an outstanding install request")
        require(installing.pendingInstalls.first() == request) {
            "a lease install terminal must answer the outstanding install request"
        }
        return rollback(installing.invocation, terminal, installing.acknowledgedLeases)
    }

    private fun leaseReleaseSettled(
        state: OrderedPreparationState,
        leaseId: LeaseId,
        released: Boolean,
    ): OrderedPreparationTransition {
        val rollingBack = state as? OrderedPreparationState.RollingBack
            ?: unexpectedEvent("a lease release terminal requires an outstanding release request")
        val pending = rollingBack.pendingReleases
        require(pending.first().leaseId == leaseId) {
            "a lease release terminal must answer the outstanding release request"
        }
        val remaining = pending.drop(1)
        if (remaining.isEmpty()) {
            return settled(
                history = rollingBack.invocation.initialHistory,
                outcome = rollingBack.originalOutcome.asOutcome(),
            )
        }
        return progress(
            state = OrderedPreparationState.RollingBack(
                invocation = rollingBack.invocation,
                originalOutcome = rollingBack.originalOutcome,
                pendingReleases = remaining,
                releasedLeases = if (released) {
                    rollingBack.releasedLeases + leaseId
                } else {
                    rollingBack.releasedLeases
                },
                failedReleaseLeases = if (released) {
                    rollingBack.failedReleaseLeases
                } else {
                    rollingBack.failedReleaseLeases + leaseId
                },
            ),
            actions = listOf(OrderedPreparationAction.ReleaseLease(remaining.first())),
        )
    }

    private fun cancellationRequested(
        state: OrderedPreparationState,
        cancellation: CancellationSelection,
    ): OrderedPreparationTransition = when (state) {
        is OrderedPreparationState.Idle -> OrderedPreparationTransition(
            state = state,
            actions = emptyList(),
            cursor = null,
            outcome = null,
        )

        is OrderedPreparationState.Planning -> settled(
            history = state.invocation.initialHistory,
            outcome = OrderedPreparationOutcome.Cancelled(cancellation),
        )

        is OrderedPreparationState.ResolvingResources -> if (state.pendingCancellation != null) {
            progress(state, emptyList())
        } else {
            progress(
                state = OrderedPreparationState.ResolvingResources(
                    invocation = state.invocation,
                    plannedItems = state.plannedItems,
                    pendingCancellation = cancellation,
                ),
                actions = listOf(
                    OrderedPreparationAction.RequestResourceCancellation(
                        invocationId = state.invocation.id,
                        cancellation = cancellation,
                    ),
                ),
            )
        }

        is OrderedPreparationState.InstallingLeases -> rollback(
            invocation = state.invocation,
            terminal = OrderedPreparationTerminal.Cancelled(cancellation),
            acknowledgedLeases = state.acknowledgedLeases,
        )

        is OrderedPreparationState.RollingBack -> progress(state, emptyList())
    }

    private fun clearHistory(state: OrderedPreparationState): OrderedPreparationTransition =
        if (state is OrderedPreparationState.Idle) {
            OrderedPreparationTransition(
                state = OrderedPreparationState.Idle(null),
                actions = emptyList(),
                cursor = null,
                outcome = OrderedPreparationOutcome.HistoryCleared,
            )
        } else {
            rejected(state, preparationInProgress(PipelineStage.FRAME_PLANNING))
        }

    private fun rollback(
        invocation: PreparationInvocation,
        terminal: OrderedPreparationTerminal,
        acknowledgedLeases: List<AcknowledgedLease>,
    ): OrderedPreparationTransition {
        val pendingReleases = acknowledgedLeases.reversed()
        if (pendingReleases.isEmpty()) {
            return settled(invocation.initialHistory, terminal.asOutcome())
        }
        return progress(
            state = OrderedPreparationState.RollingBack(
                invocation = invocation,
                originalOutcome = terminal,
                pendingReleases = pendingReleases,
                releasedLeases = emptyList(),
                failedReleaseLeases = emptyList(),
            ),
            actions = listOf(OrderedPreparationAction.ReleaseLease(pendingReleases.first())),
        )
    }

    private fun commit(
        invocation: PreparationInvocation,
        plannedItems: List<PlannedPreparationItem>,
        acknowledgedLeases: List<AcknowledgedLease>,
    ): OrderedPreparationTransition {
        val plans = invocation.plans
        val frameSeeds = plannedItems.mapIndexed { index, item ->
            PreparedFrameSeed(
                itemId = item.itemId,
                frameIndex = plans[index].frameIndex,
                plannedFrame = item.plannedFrame,
                leases = acknowledgedLeases.filter { it.request.itemId == item.itemId },
            )
        }
        return settled(
            history = historyFor(invocation, plannedItems),
            outcome = OrderedPreparationOutcome.Success(frameSeeds),
        )
    }

    private fun historyFor(
        invocation: PreparationInvocation,
        plannedItems: List<PlannedPreparationItem>,
    ): CommittedFrameHistory {
        val lastIndex = plannedItems.size - 1
        val planned = plannedItems[lastIndex].plannedFrame
        return CommittedFrameHistory(
            frameIndex = invocation.plans[lastIndex].frameIndex,
            encodedPlan = planned.encodedPlan,
            selectedLod = planned.spatialPlan.lodObservation.selectedLod,
        )
    }

    private fun leaseInstallRequests(
        plannedItems: List<PlannedPreparationItem>,
        resourceSets: List<OwnerResourceSet>,
    ): List<LeaseInstallRequest> {
        val requests = mutableListOf<LeaseInstallRequest>()
        plannedItems.forEachIndexed { index, item ->
            val ownerId = ResourceOwnerId(index + 1L)
            val visible = resourceSets.filter { it.ownerId == ownerId }.flatMap { it.resources }
            val leased = mutableListOf<LeaseResource>()
            item.plannedFrame.staticResourceTraversal.forEach { reference ->
                val resource = when (reference) {
                    is StaticResourceReference.External -> {
                        val match = visible.firstOrNull { it.resourceKey == reference.resourceKey }
                        requireNotNull(match) {
                            "successful resource resolution must make every planned reference visible"
                        }
                        LeaseResource.External(match)
                    }

                    is StaticResourceReference.GeometryProgram ->
                        LeaseResource.PlannedLogical(reference.resourceKey)
                }
                if (resource !in leased) {
                    leased += resource
                }
            }
            visible.forEach { resource ->
                val discovered = LeaseResource.External(resource)
                if (discovered !in leased) {
                    leased += discovered
                }
            }
            leased.forEachIndexed { traversalIndex, resource ->
                requests += LeaseInstallRequest(
                    itemId = item.itemId,
                    traversalIndex = traversalIndex,
                    resource = resource,
                )
            }
        }
        return requests
    }

    /**
     * Delegates to the one shared derivation [buildResourceOperationDefinition], which
     * [com.rohittp.reng.RenGRenderer] uses too. The occurrence shape -- above all one
     * [ResourceOwnerId] per preparation item -- is a contract the pure core reads back out through
     * `styleReferencingOwnerIds`, so two independent derivations of it is exactly how the style-owner
     * barrier silently stops being enforced on one of the two paths.
     */
    private fun resourceDefinition(
        invocation: PreparationInvocation,
        plannedItems: List<PlannedPreparationItem>,
    ): ResourceOperationDefinition = buildResourceOperationDefinition(
        traversalsByItem = plannedItems.map { it.plannedFrame.staticResourceTraversal },
        accessMode = invocation.accessMode,
        maximumConcurrentRoutes = invocation.environment.maximumConcurrentResourceOperations,
    )

    private fun OrderedPreparationTerminal.asOutcome(): OrderedPreparationOutcome = when (this) {
        is OrderedPreparationTerminal.Failure -> OrderedPreparationOutcome.Failure(failure)
        is OrderedPreparationTerminal.Cancelled -> OrderedPreparationOutcome.Cancelled(cancellation)
    }

    private fun preparationInProgress(stage: PipelineStage): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.PREPARATION_IN_PROGRESS,
        stage = stage,
    )

    private fun progress(
        state: OrderedPreparationState,
        actions: List<OrderedPreparationAction>,
    ): OrderedPreparationTransition = OrderedPreparationTransition(
        state = state,
        actions = actions,
        cursor = state.currentWait(),
        outcome = null,
    )

    private fun rejected(
        state: OrderedPreparationState,
        failure: FailureDescriptor,
    ): OrderedPreparationTransition = OrderedPreparationTransition(
        state = state,
        actions = emptyList(),
        cursor = state.currentWait(),
        outcome = OrderedPreparationOutcome.Failure(failure),
    )

    private fun settled(
        history: CommittedFrameHistory?,
        outcome: OrderedPreparationOutcome,
    ): OrderedPreparationTransition = OrderedPreparationTransition(
        state = OrderedPreparationState.Idle(history),
        actions = emptyList(),
        cursor = null,
        outcome = outcome,
    )

    private fun unexpectedEvent(message: String): Nothing = throw IllegalArgumentException(message)

}
