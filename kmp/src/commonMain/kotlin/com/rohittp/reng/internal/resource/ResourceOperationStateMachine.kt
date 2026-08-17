package com.rohittp.reng.internal.resource

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic

internal object ResourceOperationStateMachine {
    internal fun preRegister(
        definition: ResourceOperationDefinition,
    ): ResourceOperationTransition {
        val occurrences = definition.staticOccurrences
        val identityRecords = mutableListOf<CanonicalIdentityRecord>()
        val identityByStableId = mutableMapOf<String, CanonicalIdentityRecord>()
        val routeRecords = mutableListOf<RouteRecord>()
        val routeRecordIndexByRoute = mutableMapOf<ResourceRouteKey, Int>()
        val privateRentileKeyClaims = mutableListOf<PrivateRentileKeyClaim>()
        val claimIndexByPrivateKey = mutableMapOf<RentilePrivateKey, Int>()

        definition.resourceIdentities.forEach { attempted ->
            val established = identityByStableId[attempted.resourceKey.stableId]
            when {
                established == null -> {
                    identityRecords += attempted
                    identityByStableId[attempted.resourceKey.stableId] = attempted
                }
                established.canonicalBytes != attempted.canonicalBytes -> {
                    return failed(
                        definition = definition,
                        occurrences = occurrences,
                        routeRecords = routeRecords,
                        privateRentileKeyClaims = privateRentileKeyClaims,
                        identityRecords = identityRecords,
                        failure = identityCollisionFailure(established),
                    )
                }
            }
        }

        occurrences.forEach { occurrence ->
            val registration = occurrence.registration
            val joinedRouteIndex = routeRecordIndexByRoute[registration.route] ?: -1
            if (joinedRouteIndex >= 0) {
                val joined = routeRecords[joinedRouteIndex]
                routeRecords[joinedRouteIndex] = joined.copyWith(
                    joinedOccurrenceIds = joined.joinedOccurrenceIds + occurrence.id,
                )
                return@forEach
            }

            val claimIndex = claimIndexByPrivateKey[registration.privateRentileKey] ?: -1
            if (claimIndex < 0) {
                claimIndexByPrivateKey[registration.privateRentileKey] = privateRentileKeyClaims.size
                privateRentileKeyClaims += PrivateRentileKeyClaim(
                    privateKey = registration.privateRentileKey,
                    firstRoute = registration.route,
                    usable = true,
                )
                routeRecordIndexByRoute[registration.route] = routeRecords.size
                routeRecords += preregisteredRouteRecord(occurrence)
                return@forEach
            }

            val establishedClaim = privateRentileKeyClaims[claimIndex]
            privateRentileKeyClaims[claimIndex] = establishedClaim.copy(usable = false)
            routeRecords += RouteRecord(
                registration = registration,
                joinedOccurrenceIds = listOf(occurrence.id),
                ordinal = null,
                cursor = null,
                status = ResourceRouteStatus.BLOCKED_BY_COLLISION,
            )
            return failed(
                definition = definition,
                occurrences = occurrences,
                routeRecords = routeRecords,
                privateRentileKeyClaims = privateRentileKeyClaims,
                identityRecords = identityRecords,
                failure = ambiguousRouteFailure(),
            )
        }

        return ResourceOperationTransition(
            state = runningState(
                definition = definition,
                occurrences = occurrences,
                routeRecords = routeRecords,
                privateRentileKeyClaims = privateRentileKeyClaims,
                identityRecords = identityRecords,
            ),
            actions = emptyList(),
            outcome = null,
        )
    }

    internal fun start(
        definition: ResourceOperationDefinition,
    ): ResourceOperationTransition {
        val preRegistered = preRegister(definition)
        if (preRegistered.outcome != null) return preRegistered

        val work = SchedulerWork(requireNotNull(preRegistered.state))
        work.staticContinuation += definition.staticOccurrences.map(ResourceOccurrence::id)
        work.releaseKnownTraversal()
        work.scheduleEligibleOccurrences()
        return work.toTransition()
    }

    internal fun transition(
        state: ResourceOperationState.Running,
        event: ResourceOperationEvent,
    ): ResourceOperationTransition {
        val work = SchedulerWork(state)
        when (event) {
            is RouteReadyForDiscovery -> work.routeReadyForDiscovery(event)
            is ChildrenDiscovered -> work.childrenDiscovered(event)
        }
        return work.toTransition()
    }

    private class SchedulerWork(
        private val initial: ResourceOperationState.Running,
    ) {
        val occurrences: MutableList<ResourceOccurrence> = initial.occurrences.toMutableList()
        val routeRecords: MutableList<RouteRecord> = initial.routeRecords.toMutableList()
        val privateRentileKeyClaims: MutableList<PrivateRentileKeyClaim> =
            initial.privateRentileKeyClaims.toMutableList()
        val identityRecords: MutableList<CanonicalIdentityRecord> = initial.identityRecords.toMutableList()
        val eligibleFifo: MutableList<ResourceOccurrenceId> = initial.traversal.eligibleFifo.toMutableList()
        val staticContinuation: MutableList<ResourceOccurrenceId> =
            initial.traversal.staticContinuation.toMutableList()
        val frontierStack: MutableList<DiscoveryFrontier> = initial.traversal.frontierStack.toMutableList()
        val activeRouteOrdinals: MutableList<Long> = initial.activeRouteOrdinals.toMutableList()
        val actions: MutableList<ResourceOperationAction> = mutableListOf()
        var nextRouteOrdinal: Long = initial.nextRouteOrdinal
        var failure: FailureDescriptor? = null
        var failureOrdinal: Long? = null

        fun routeReadyForDiscovery(event: RouteReadyForDiscovery) {
            val parent = occurrence(event.parentOccurrenceId)
            require(parent.discoveryRequired) { "route discovery readiness requires a discovery occurrence" }
            require(frontierStack.lastOrNull()?.parentOccurrenceId == parent.id) {
                "route discovery readiness must match the active depth-first frontier"
            }
            val routeIndex = routeIndex(parent.registration.route)
            require(routeIndex >= 0) { "route discovery readiness requires a registered route" }
            val record = routeRecords[routeIndex]
            require(record.ordinal == event.ordinal) { "route discovery readiness must match its ordinal" }
            require(record.status == ResourceRouteStatus.RUNNING) {
                "route discovery readiness requires a running route"
            }
            require(activeRouteOrdinals.remove(event.ordinal)) {
                "route discovery readiness requires an active route"
            }
            routeRecords[routeIndex] = record.copyWith(status = ResourceRouteStatus.RESOLVED)
            actions += DiscoverChildren(event.ordinal, parent.id)
            startEligibleRoutes()
        }

        fun childrenDiscovered(event: ChildrenDiscovered) {
            val frontier = frontierStack.lastOrNull()
            require(frontier?.parentOccurrenceId == event.parentOccurrenceId) {
                "discovered children must match the active depth-first frontier"
            }
            val parent = occurrence(event.parentOccurrenceId)
            require(parent.discoveryRequired) { "discovered children require a discovery occurrence" }
            val parentRouteIndex = routeIndex(parent.registration.route)
            require(parentRouteIndex >= 0) { "discovered children require a registered parent route" }
            require(routeRecords[parentRouteIndex].status == ResourceRouteStatus.RESOLVED) {
                "discovered children require a resolved parent route"
            }

            val children = event.children
            val existingIds = occurrences.mapTo(mutableSetOf(), ResourceOccurrence::id)
            val dynamicIds = mutableSetOf<ResourceOccurrenceId>()
            require(children.all { child ->
                child.occurrence.id !in existingIds && dynamicIds.add(child.occurrence.id)
            }) { "discovered resource occurrence IDs must be unique" }

            occurrences += children.map(DiscoveredResourceChild::occurrence)
            frontierStack[frontierStack.lastIndex] = DiscoveryFrontier(
                parentOccurrenceId = frontier.parentOccurrenceId,
                childOccurrenceIds = children.map { it.occurrence.id },
                withheldContinuation = frontier.withheldContinuation,
            )
            releaseKnownTraversal()
            scheduleEligibleOccurrences()
        }

        fun releaseKnownTraversal() {
            while (true) {
                if (frontierStack.isNotEmpty()) {
                    val frontierIndex = frontierStack.lastIndex
                    val frontier = frontierStack[frontierIndex]
                    val children = frontier.childOccurrenceIds
                    if (children.isNotEmpty()) {
                        val childId = children.first()
                        val remainingChildren = children.drop(1)
                        eligibleFifo += childId
                        val child = occurrence(childId)
                        if (child.discoveryRequired) {
                            frontierStack[frontierIndex] = DiscoveryFrontier(
                                parentOccurrenceId = frontier.parentOccurrenceId,
                                childOccurrenceIds = emptyList(),
                                withheldContinuation = frontier.withheldContinuation,
                            )
                            frontierStack += DiscoveryFrontier(
                                parentOccurrenceId = child.id,
                                childOccurrenceIds = emptyList(),
                                withheldContinuation = remainingChildren,
                            )
                            return
                        }
                        frontierStack[frontierIndex] = DiscoveryFrontier(
                            parentOccurrenceId = frontier.parentOccurrenceId,
                            childOccurrenceIds = remainingChildren,
                            withheldContinuation = frontier.withheldContinuation,
                        )
                        continue
                    }

                    frontierStack.removeAt(frontierIndex)
                    restoreContinuation(frontier.withheldContinuation)
                    continue
                }

                if (staticContinuation.isEmpty()) return
                val occurrenceId = staticContinuation.removeAt(0)
                eligibleFifo += occurrenceId
                val occurrence = occurrence(occurrenceId)
                if (occurrence.discoveryRequired) {
                    val withheld = staticContinuation.toList()
                    staticContinuation.clear()
                    frontierStack += DiscoveryFrontier(
                        parentOccurrenceId = occurrence.id,
                        childOccurrenceIds = emptyList(),
                        withheldContinuation = withheld,
                    )
                    return
                }
            }
        }

        private fun restoreContinuation(withheld: List<ResourceOccurrenceId>) {
            if (frontierStack.isEmpty()) {
                require(staticContinuation.isEmpty()) { "static traversal continuation must be singular" }
                staticContinuation += withheld
                return
            }

            val parentIndex = frontierStack.lastIndex
            val parent = frontierStack[parentIndex]
            require(parent.childOccurrenceIds.isEmpty()) { "frontier continuation must be singular" }
            frontierStack[parentIndex] = DiscoveryFrontier(
                parentOccurrenceId = parent.parentOccurrenceId,
                childOccurrenceIds = withheld,
                withheldContinuation = parent.withheldContinuation,
            )
        }

        fun scheduleEligibleOccurrences() {
            while (eligibleFifo.isNotEmpty() && failure == null) {
                val occurrenceId = eligibleFifo.removeAt(0)
                makeOccurrenceEligible(occurrence(occurrenceId))
            }
            startEligibleRoutes()
        }

        private fun makeOccurrenceEligible(occurrence: ResourceOccurrence) {
            val identityCollision = identityCollision(occurrence.registration)
            if (identityCollision != null) {
                blockByCollision(occurrence, identityCollision)
                return
            }

            val existingRouteIndex = routeIndex(occurrence.registration.route)
            if (existingRouteIndex >= 0) {
                makeJoinedOccurrenceEligible(existingRouteIndex, occurrence)
                return
            }

            val ordinal = takeNextOrdinal()
            val identityAlreadyRegistered = identityRecords.any {
                it.resourceKey.stableId == occurrence.registration.resourceKey.stableId
            }
            if (!identityAlreadyRegistered) {
                identityRecords += CanonicalIdentityRecord(
                    occurrence.registration.resourceKey,
                    occurrence.registration.canonicalBytes,
                )
            }
            val claimIndex = claimIndex(occurrence.registration.privateRentileKey)
            if (claimIndex >= 0) {
                val claim = privateRentileKeyClaims[claimIndex]
                privateRentileKeyClaims[claimIndex] = claim.copy(usable = false)
                routeRecords += RouteRecord(
                    registration = occurrence.registration,
                    joinedOccurrenceIds = listOf(occurrence.id),
                    ordinal = ordinal,
                    cursor = null,
                    status = ResourceRouteStatus.BLOCKED_BY_COLLISION,
                )
                failure = ambiguousRouteFailure()
                failureOrdinal = ordinal
                return
            }

            privateRentileKeyClaims += PrivateRentileKeyClaim(
                privateKey = occurrence.registration.privateRentileKey,
                firstRoute = occurrence.registration.route,
                usable = true,
            )
            routeRecords += RouteRecord(
                registration = occurrence.registration,
                joinedOccurrenceIds = listOf(occurrence.id),
                ordinal = ordinal,
                cursor = null,
                status = ResourceRouteStatus.ELIGIBLE,
            )
        }

        private fun makeJoinedOccurrenceEligible(
            routeIndex: Int,
            occurrence: ResourceOccurrence,
        ) {
            val record = routeRecords[routeIndex]
            val joinedIds = if (occurrence.id in record.joinedOccurrenceIds) {
                record.joinedOccurrenceIds
            } else {
                record.joinedOccurrenceIds + occurrence.id
            }
            if (record.ordinal == null) {
                routeRecords[routeIndex] = record.copyWith(
                    joinedOccurrenceIds = joinedIds,
                    ordinal = takeNextOrdinal(),
                    status = ResourceRouteStatus.ELIGIBLE,
                )
                return
            }

            routeRecords[routeIndex] = record.copyWith(joinedOccurrenceIds = joinedIds)
            if (occurrence.discoveryRequired && record.status == ResourceRouteStatus.RESOLVED) {
                actions += DiscoverChildren(record.ordinal, occurrence.id)
            }
        }

        private fun identityCollision(registration: ResourceRouteRegistration): CanonicalIdentityRecord? {
            val established = identityRecords.firstOrNull {
                it.resourceKey.stableId == registration.resourceKey.stableId
            } ?: return null
            return established.takeIf { it.canonicalBytes != registration.canonicalBytes }
        }

        private fun blockByCollision(
            occurrence: ResourceOccurrence,
            established: CanonicalIdentityRecord,
        ) {
            val ordinal = takeNextOrdinal()
            routeRecords += RouteRecord(
                registration = occurrence.registration,
                joinedOccurrenceIds = listOf(occurrence.id),
                ordinal = ordinal,
                cursor = null,
                status = ResourceRouteStatus.BLOCKED_BY_COLLISION,
            )
            failure = identityCollisionFailure(established)
            failureOrdinal = ordinal
        }

        private fun startEligibleRoutes() {
            val ceiling = failureOrdinal
            val candidates = routeRecords.withIndex()
                .filter { (_, record) ->
                    record.status == ResourceRouteStatus.ELIGIBLE &&
                        record.ordinal != null &&
                        privateRentileKeyClaims.any {
                            it.privateKey == record.registration.privateRentileKey && it.usable
                        } &&
                        (ceiling == null || record.ordinal < ceiling)
                }
                .sortedBy { it.value.ordinal }

            for ((index, record) in candidates) {
                if (activeRouteOrdinals.size >= initial.definition.maximumConcurrentRoutes) break
                val ordinal = requireNotNull(record.ordinal)
                routeRecords[index] = record.copyWith(status = ResourceRouteStatus.RUNNING)
                activeRouteOrdinals += ordinal
                actions += StartRoute(ordinal, record.registration)
            }
        }

        private fun takeNextOrdinal(): Long {
            val ordinal = nextRouteOrdinal
            check(ordinal < Long.MAX_VALUE) { "route ordinal space exhausted" }
            nextRouteOrdinal += 1L
            return ordinal
        }

        private fun occurrence(id: ResourceOccurrenceId): ResourceOccurrence =
            requireNotNull(occurrences.singleOrNull { it.id == id }) {
                "resource occurrence must be registered exactly once"
            }

        private fun routeIndex(route: ResourceRouteKey): Int =
            routeRecords.indexOfFirst { it.registration.route == route }

        private fun claimIndex(privateKey: RentilePrivateKey): Int =
            privateRentileKeyClaims.indexOfFirst { it.privateKey == privateKey }

        fun toTransition(): ResourceOperationTransition = ResourceOperationTransition(
            state = runningState(
                definition = initial.definition,
                occurrences = occurrences,
                routeRecords = routeRecords,
                privateRentileKeyClaims = privateRentileKeyClaims,
                identityRecords = identityRecords,
                traversal = TraversalState(eligibleFifo, staticContinuation, frontierStack),
                nextRouteOrdinal = nextRouteOrdinal,
                activeRouteOrdinals = activeRouteOrdinals,
            ),
            actions = actions,
            outcome = failure?.let(ResourceOperationOutcome::Failure),
        )
    }

    private fun preregisteredRouteRecord(occurrence: ResourceOccurrence): RouteRecord = RouteRecord(
        registration = occurrence.registration,
        joinedOccurrenceIds = listOf(occurrence.id),
        ordinal = null,
        cursor = null,
        status = ResourceRouteStatus.PREREGISTERED,
    )

    private fun RouteRecord.copyWith(
        registration: ResourceRouteRegistration = this.registration,
        joinedOccurrenceIds: List<ResourceOccurrenceId> = this.joinedOccurrenceIds,
        ordinal: Long? = this.ordinal,
        cursor: ResourceRouteCursor? = this.cursor,
        status: ResourceRouteStatus = this.status,
    ): RouteRecord = RouteRecord(
        registration = registration,
        joinedOccurrenceIds = joinedOccurrenceIds,
        ordinal = ordinal,
        cursor = cursor,
        status = status,
    )

    private fun failed(
        definition: ResourceOperationDefinition,
        occurrences: List<ResourceOccurrence>,
        routeRecords: List<RouteRecord>,
        privateRentileKeyClaims: List<PrivateRentileKeyClaim>,
        identityRecords: List<CanonicalIdentityRecord>,
        failure: FailureDescriptor,
    ): ResourceOperationTransition = ResourceOperationTransition(
        state = runningState(
            definition = definition,
            occurrences = occurrences,
            routeRecords = routeRecords,
            privateRentileKeyClaims = privateRentileKeyClaims,
            identityRecords = identityRecords,
        ),
        actions = emptyList(),
        outcome = ResourceOperationOutcome.Failure(failure),
    )

    private fun runningState(
        definition: ResourceOperationDefinition,
        occurrences: List<ResourceOccurrence>,
        routeRecords: List<RouteRecord>,
        privateRentileKeyClaims: List<PrivateRentileKeyClaim>,
        identityRecords: List<CanonicalIdentityRecord>,
        traversal: TraversalState = TraversalState(emptyList(), emptyList(), emptyList()),
        nextRouteOrdinal: Long = 0L,
        activeRouteOrdinals: List<Long> = emptyList(),
    ): ResourceOperationState.Running = ResourceOperationState.Running(
        definition = definition,
        occurrences = occurrences,
        routeRecords = routeRecords,
        privateRentileKeyClaims = privateRentileKeyClaims,
        identityRecords = identityRecords,
        traversal = traversal,
        nextRouteOrdinal = nextRouteOrdinal,
        activeRouteOrdinals = activeRouteOrdinals,
    )

    private fun ambiguousRouteFailure(): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
        stage = PipelineStage.RESOURCE_LOOKUP,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_LOOKUP,
            fieldName = DiagnosticField.RESOURCE,
        ),
    )

    private fun identityCollisionFailure(
        established: CanonicalIdentityRecord,
    ): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.IDENTITY_COLLISION,
        stage = PipelineStage.RESOURCE_LOOKUP,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_LOOKUP,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = established.resourceKey.resourceClass,
            resourceKey = established.resourceKey,
        ),
    )
}
