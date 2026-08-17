package com.rohittp.reng.internal.resource

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportRequestMetadata
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.acceptValue
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.identity.PureKotlinSha256

internal object ResourceOperationStateMachine {
    internal fun preRegister(
        definition: ResourceOperationDefinition,
    ): ResourceOperationTransition {
        val occurrences = definition.staticOccurrences
        val occurrenceIds = mutableSetOf<ResourceOccurrenceId>()
        require(occurrences.all { occurrenceIds.add(it.id) }) {
            "static resource occurrence IDs must be unique"
        }

        val identityRecords = mutableListOf<CanonicalIdentityRecord>()
        val identityByStableId = mutableMapOf<String, CanonicalIdentityRecord>()
        val routeRecords = mutableListOf<RouteRecord>()
        val joinedOccurrenceIdsByRoute = mutableListOf<MutableList<ResourceOccurrenceId>>()
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
                        routeRecords = freezeRouteRecords(routeRecords, joinedOccurrenceIdsByRoute),
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
                joinedOccurrenceIdsByRoute[joinedRouteIndex] += occurrence.id
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
                joinedOccurrenceIdsByRoute += mutableListOf(occurrence.id)
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
            joinedOccurrenceIdsByRoute += mutableListOf(occurrence.id)
            return failed(
                definition = definition,
                occurrences = occurrences,
                routeRecords = freezeRouteRecords(routeRecords, joinedOccurrenceIdsByRoute),
                privateRentileKeyClaims = privateRentileKeyClaims,
                identityRecords = identityRecords,
                failure = ambiguousRouteFailure(),
            )
        }

        return ResourceOperationTransition(
            state = runningState(
                definition = definition,
                occurrences = occurrences,
                routeRecords = freezeRouteRecords(routeRecords, joinedOccurrenceIdsByRoute),
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
        work.setStaticContinuation(definition.staticOccurrences.map(ResourceOccurrence::id))
        work.releaseKnownTraversal()
        work.scheduleEligibleOccurrences()
        return work.toTransition()
    }

    internal fun beginLookup(
        state: ResourceOperationState.Running,
        ordinal: Long,
    ): ResourceOperationTransition {
        val work = SchedulerWork(state)
        work.beginLookup(ordinal)
        return work.toTransition()
    }

    internal fun transition(
        state: ResourceOperationState.Running,
        event: ResourceOperationEvent,
    ): ResourceOperationTransition {
        val work = SchedulerWork(state)
        when (event) {
            is ClockSampled -> work.clockSampled(event)
            is ResidentObserved -> work.residentObserved(event)
            is StoreReadCompleted -> work.storeReadCompleted(event)
            is TransportCompleted -> work.transportCompleted(event)
            is LatchedTransportReplayCompleted -> work.latchedTransportReplayCompleted(event)
            is RouteReadyForDiscovery -> work.routeReadyForDiscovery(event)
            is ChildrenDiscovered -> work.childrenDiscovered(event)
            is RouteCompleted -> work.routeCompleted(event)
            is ExternalCancellationRequested -> work.externalCancellationRequested(event)
            is CleanupCancellationObserved -> work.cleanupCancellationObserved(event)
        }
        return work.toTransition()
    }

    private class SchedulerWork(
        private val initial: ResourceOperationState.Running,
    ) {
        private val occurrences: MutableList<ResourceOccurrence> = initial.occurrences.toMutableList()
        private val occurrenceById: MutableMap<ResourceOccurrenceId, ResourceOccurrence> = mutableMapOf()
        private val routeRecords: MutableList<RouteRecord> = initial.routeRecords.toMutableList()
        private val joinedOccurrenceIdsByRouteIndex: MutableList<MutableList<ResourceOccurrenceId>> =
            mutableListOf()
        private val joinedOccurrenceIdSetsByRouteIndex: MutableList<MutableSet<ResourceOccurrenceId>> =
            mutableListOf()
        private val routeIndexByRoute: MutableMap<ResourceRouteKey, Int> = mutableMapOf()
        private val routeIndexByOrdinal: MutableMap<Long, Int> = mutableMapOf()
        private val privateRentileKeyClaims: MutableList<PrivateRentileKeyClaim> =
            initial.privateRentileKeyClaims.toMutableList()
        private val claimIndexByPrivateKey: MutableMap<RentilePrivateKey, Int> = mutableMapOf()
        private val identityRecords: MutableList<CanonicalIdentityRecord> = initial.identityRecords.toMutableList()
        private val identityByStableId: MutableMap<String, CanonicalIdentityRecord> = mutableMapOf()
        private val transportLatches: MutableList<TransportLatchRecord> = initial.transportLatches.toMutableList()
        private val transportLatchIndexByKey: MutableMap<TransportLatchKey, Int> = mutableMapOf()
        private val routeIndexByActionId: MutableMap<ResourceActionId, Int> = mutableMapOf()
        private val eligibleFifo: ArrayDeque<ResourceOccurrenceId> = ArrayDeque()
        private var staticContinuation: ResourceOccurrenceIdSlice = initial.traversal.staticSlice()
        private val frontierStack: MutableList<DiscoveryFrontier> =
            initial.traversal.frontierStack.toMutableList()
        private val activeRouteOrdinals: MutableList<Long> = initial.activeRouteOrdinals.toMutableList()
        private val bufferedRouteOutcomes: MutableList<BufferedRouteOutcome> =
            initial.bufferedRouteOutcomes.toMutableList()
        private val actions: MutableList<ResourceOperationAction> = mutableListOf()
        private var nextActionId: Long = initial.nextActionId
        private var nextRouteOrdinal: Long = initial.nextRouteOrdinal
        private var nextRetirementOrdinal: Long = initial.nextRetirementOrdinal
        private var startCeilingOrdinal: Long? = initial.startCeilingOrdinal
        private var terminalSelection: ResourceTerminalSelection? = initial.terminalSelection

        init {
            initial.traversal.eligibleFifo.forEach(eligibleFifo::addLast)
            occurrences.forEach { occurrence ->
                require(occurrenceById.put(occurrence.id, occurrence) == null) {
                    "resource occurrence IDs must be unique"
                }
            }
            identityRecords.forEach { identity ->
                require(identityByStableId.put(identity.resourceKey.stableId, identity) == null) {
                    "canonical identity stable IDs must be unique"
                }
            }
            transportLatches.forEachIndexed { index, latch ->
                require(transportLatchIndexByKey.put(latch.key, index) == null) {
                    "Transport latch keys must be unique"
                }
            }
            privateRentileKeyClaims.forEachIndexed { index, claim ->
                require(claimIndexByPrivateKey.put(claim.privateKey, index) == null) {
                    "private Rentile key claims must be unique"
                }
            }
            routeRecords.forEachIndexed { index, record ->
                if (record.registration.route !in routeIndexByRoute) {
                    routeIndexByRoute[record.registration.route] = index
                }
                val joinedIds = record.joinedOccurrenceIds.toMutableList()
                val joinedIdSet = joinedIds.toMutableSet()
                require(joinedIds.size == joinedIdSet.size) {
                    "joined resource occurrence IDs must be unique"
                }
                require(joinedIds.all(occurrenceById::containsKey)) {
                    "joined resource occurrences must be registered"
                }
                joinedOccurrenceIdsByRouteIndex += joinedIds
                joinedOccurrenceIdSetsByRouteIndex += joinedIdSet
                record.ordinal?.let { ordinal ->
                    require(ordinal >= 0L && ordinal < nextRouteOrdinal) {
                        "route ordinals must have been assigned"
                    }
                    require(routeIndexByOrdinal.put(ordinal, index) == null) {
                        "route ordinals must be unique"
                    }
                }
                cursorActionId(record.cursor)?.let { actionId ->
                    require(routeIndexByActionId.put(actionId, index) == null) {
                        "route cursor action IDs must be unique"
                    }
                }
                require(
                    when (record.status) {
                        ResourceRouteStatus.PREREGISTERED -> record.ordinal == null
                        ResourceRouteStatus.ELIGIBLE,
                        ResourceRouteStatus.RUNNING,
                        ResourceRouteStatus.RESOLVED,
                        -> record.ordinal != null
                        ResourceRouteStatus.BLOCKED_BY_COLLISION -> true
                    },
                ) { "route status must agree with ordinal assignment" }
            }

            require(activeRouteOrdinals.size <= initial.definition.maximumConcurrentRoutes) {
                "active routes must not exceed configured concurrency"
            }
            require(activeRouteOrdinals.toSet().size == activeRouteOrdinals.size) {
                "active route ordinals must be distinct"
            }
            val runningOrdinals = routeRecords.mapNotNull { record ->
                record.ordinal?.takeIf { record.status == ResourceRouteStatus.RUNNING }
            }.toSet()
            require(activeRouteOrdinals.toSet() == runningOrdinals) {
                "active route ordinals must correspond exactly to running routes"
            }
        }

        fun setStaticContinuation(ids: List<ResourceOccurrenceId>) {
            require(staticContinuation.isEmpty) { "static traversal continuation must be initialized once" }
            staticContinuation = ResourceOccurrenceIdSlice.snapshot(ids)
        }

        fun beginLookup(ordinal: Long) {
            require(terminalSelection == null) { "lookup cannot begin after terminal selection" }
            val routeIndex = requireNotNull(routeIndexByOrdinal[ordinal]) {
                "lookup must name an assigned route"
            }
            val record = routeRecords[routeIndex]
            require(record.status == ResourceRouteStatus.RUNNING && ordinal in activeRouteOrdinals) {
                "lookup requires an active running route"
            }

            val cursor = record.cursor
            if (cursor is PendingClassGates) {
                val lookup = requireNotNull(record.lookup)
                val latchKey = requireNotNull(lookup.transportLatch) {
                    "only Transport-selected content has a latch to replay"
                }
                val latch = transportLatch(latchKey)
                val actionId = takeNextActionId()
                updateRouteRecord(
                    routeIndex,
                    cursor = AwaitingLatchedTransportReplay(actionId, ordinal, latchKey),
                )
                actions += ReplayLatchedTransport(actionId, ordinal, copyLatch(latch))
                return
            }

            require(cursor == null && record.lookup == null) {
                "lookup may begin only once before a closed latch replay"
            }
            val actionId = takeNextActionId()
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingClockSample(actionId, ordinal),
                lookup = LookupProgress(
                    sampleEpochMillis = null,
                    resident = null,
                    staleBaseline = null,
                    storeReadStarted = false,
                    transportLatch = null,
                    selectedContent = null,
                ),
            )
            actions += SampleClock(actionId, ordinal)
        }

        fun clockSampled(event: ClockSampled) {
            val routeIndex = routeIndexForAction<AwaitingClockSample>(event.actionId)
            if (finishActionAfterTerminal(routeIndex)) return
            val record = routeRecords[routeIndex]
            val cursor = record.cursor as AwaitingClockSample
            val progress = requireNotNull(record.lookup)
            require(progress.sampleEpochMillis == null) { "route freshness may be sampled exactly once" }
            val sampled = progress.copy(sampleEpochMillis = event.sampleEpochMillis)
            if (record.registration.route.accessMode == ResourceAccessMode.RELOAD) {
                requestTransport(routeIndex, cursor.ordinal, sampled)
                return
            }

            val actionId = takeNextActionId()
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingResident(actionId, cursor.ordinal),
                lookup = sampled,
            )
            actions += ObserveResident(
                actionId = actionId,
                ordinal = cursor.ordinal,
                resourceKey = record.registration.resourceKey,
            )
        }

        fun residentObserved(event: ResidentObserved) {
            val routeIndex = routeIndexForAction<AwaitingResident>(event.actionId)
            if (finishActionAfterTerminal(routeIndex)) return
            val record = routeRecords[routeIndex]
            val cursor = record.cursor as AwaitingResident
            val progress = requireNotNull(record.lookup)
            val sample = requireNotNull(progress.sampleEpochMillis)
            val resident = event.resource?.let(::copyStored)

            when (record.registration.route.accessMode) {
                ResourceAccessMode.RELOAD -> error("reload must not observe resident content")
                ResourceAccessMode.CACHE_ONLY -> {
                    if (resident != null) {
                        selectContent(routeIndex, cursor.ordinal, progress.copy(resident = resident), resident, ContentProvenance.RESIDENT)
                    } else {
                        startStoreRead(routeIndex, cursor.ordinal, progress.copy(resident = null))
                    }
                }
                ResourceAccessMode.NORMAL -> {
                    if (resident != null && isFresh(resident, sample)) {
                        selectContent(routeIndex, cursor.ordinal, progress.copy(resident = resident), resident, ContentProvenance.RESIDENT)
                    } else {
                        startStoreRead(
                            routeIndex,
                            cursor.ordinal,
                            progress.copy(resident = resident),
                            staleBaseline = resident,
                        )
                    }
                }
            }
        }

        fun storeReadCompleted(event: StoreReadCompleted) {
            val routeIndex = routeIndexForAction<AwaitingStoreRead>(event.actionId)
            if (finishActionAfterTerminal(routeIndex)) return
            val record = routeRecords[routeIndex]
            val cursor = record.cursor as AwaitingStoreRead
            val progress = requireNotNull(record.lookup)
            val sample = requireNotNull(progress.sampleEpochMillis)

            when (val outcome = event.outcome) {
                SuppliedCallOutcome.Failed -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Failure(storeReadFailure(record)),
                )
                is SuppliedCallOutcome.Cancelled -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Cancelled(outcome.cancellation),
                )
                is SuppliedCallOutcome.Success -> {
                    val supplied = outcome.value
                    if (supplied == null) {
                        if (record.registration.route.accessMode == ResourceAccessMode.CACHE_ONLY) {
                            completeLookupRoute(
                                routeIndex,
                                ResourceRouteOutcome.Failure(resourceUnavailableFailure(record)),
                            )
                        } else {
                            requestTransport(routeIndex, cursor.ordinal, progress)
                        }
                        return
                    }

                    val validated = copyValidStoredResource(
                        supplied,
                        record.registration.route.maximumResponseBytes,
                        PureKotlinSha256,
                    )
                    if (validated == null) {
                        completeLookupRoute(
                            routeIndex,
                            ResourceRouteOutcome.Failure(storeIntegrityFailure(record)),
                        )
                        return
                    }

                    when (record.registration.route.accessMode) {
                        ResourceAccessMode.RELOAD -> error("reload must not read Store content")
                        ResourceAccessMode.CACHE_ONLY -> selectContent(
                            routeIndex,
                            cursor.ordinal,
                            progress,
                            validated,
                            ContentProvenance.STORE,
                        )
                        ResourceAccessMode.NORMAL -> {
                            if (isFresh(validated, sample)) {
                                selectContent(
                                    routeIndex,
                                    cursor.ordinal,
                                    progress,
                                    validated,
                                    ContentProvenance.STORE,
                                )
                            } else {
                                requestTransport(
                                    routeIndex,
                                    cursor.ordinal,
                                    progress.copy(staleBaseline = validated),
                                )
                            }
                        }
                    }
                }
            }
        }

        fun transportCompleted(event: TransportCompleted) {
            val routeIndex = routeIndexForAction<AwaitingTransport>(event.actionId)
            if (finishActionAfterTerminal(routeIndex)) return
            val record = routeRecords[routeIndex]
            val cursor = record.cursor as AwaitingTransport
            require(cursor.latchKey !in transportLatchIndexByKey) {
                "consumer Transport may close a latch only once"
            }

            when (val outcome = event.outcome) {
                SuppliedCallOutcome.Failed -> {
                    addTransportLatch(TransportLatchRecord(cursor.latchKey, LatchedTransportOutcome.Failed))
                    completeLookupRoute(
                        routeIndex,
                        ResourceRouteOutcome.Failure(transportFailure(record)),
                    )
                }
                is SuppliedCallOutcome.Cancelled -> {
                    addTransportLatch(
                        TransportLatchRecord(
                            cursor.latchKey,
                            LatchedTransportOutcome.Cancelled(outcome.cancellation),
                        ),
                    )
                    completeLookupRoute(
                        routeIndex,
                        ResourceRouteOutcome.Cancelled(outcome.cancellation),
                    )
                }
                is SuppliedCallOutcome.Success -> {
                    val response = copyResponse(outcome.value)
                    addTransportLatch(
                        TransportLatchRecord(cursor.latchKey, LatchedTransportOutcome.Response(response)),
                    )
                    resolveLatchedResponse(routeIndex, cursor.ordinal, cursor.latchKey, response)
                }
            }
        }

        fun latchedTransportReplayCompleted(event: LatchedTransportReplayCompleted) {
            val routeIndex = routeIndexForAction<AwaitingLatchedTransportReplay>(event.actionId)
            if (finishActionAfterTerminal(routeIndex)) return
            val record = routeRecords[routeIndex]
            val cursor = record.cursor as AwaitingLatchedTransportReplay
            val latch = transportLatch(cursor.latchKey)
            when (val outcome = latch.outcome) {
                LatchedTransportOutcome.Failed -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Failure(transportFailure(record)),
                )
                is LatchedTransportOutcome.Cancelled -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Cancelled(outcome.cancellation),
                )
                is LatchedTransportOutcome.Response -> resolveLatchedResponse(
                    routeIndex,
                    cursor.ordinal,
                    cursor.latchKey,
                    outcome.response,
                )
            }
        }

        private fun startStoreRead(
            routeIndex: Int,
            ordinal: Long,
            progress: LookupProgress,
            staleBaseline: StoredRawResource? = null,
        ) {
            require(!progress.storeReadStarted) { "Store read may start at most once per route" }
            val actionId = takeNextActionId()
            val started = progress.copy(
                staleBaseline = staleBaseline,
                storeReadStarted = true,
            )
            updateRouteRecord(
                routeIndex,
                cursor = AwaitingStoreRead(actionId, ordinal),
                lookup = started,
            )
            actions += ReadStore(
                actionId,
                ordinal,
                routeRecords[routeIndex].registration.rawKey,
            )
        }

        private fun requestTransport(
            routeIndex: Int,
            ordinal: Long,
            progress: LookupProgress,
        ) {
            require(progress.transportLatch == null) {
                "route may finalize Transport metadata only once"
            }
            val registration = routeRecords[routeIndex].registration
            val route = registration.route
            val baseline = progress.staleBaseline
            val etag = baseline?.metadata?.etag.takeIf { route.accessMode == ResourceAccessMode.NORMAL }
            val lastModified = baseline?.metadata?.lastModified
                .takeIf { route.accessMode == ResourceAccessMode.NORMAL && etag == null }
            val metadata = TransportRequestMetadata(
                ifNoneMatch = etag,
                ifModifiedSince = lastModified,
                accept = route.resourceClass.acceptValue,
            )
            val request = TransportRequest(
                locator = route.locator,
                resourceClass = route.resourceClass,
                maximumResponseBytes = route.maximumResponseBytes,
                metadata = metadata,
            )
            val latchKey = TransportLatchKey(
                route = route,
                ifNoneMatch = metadata.ifNoneMatch,
                ifModifiedSince = metadata.ifModifiedSince,
                accept = metadata.accept,
            )
            val actionId = takeNextActionId()
            val withLatch = progress.copy(transportLatch = latchKey)
            val closedLatchIndex = transportLatchIndexByKey[latchKey]
            if (closedLatchIndex == null) {
                updateRouteRecord(
                    routeIndex,
                    cursor = AwaitingTransport(actionId, ordinal, latchKey),
                    lookup = withLatch,
                )
                actions += CallTransport(actionId, ordinal, request, latchKey)
            } else {
                val latch = transportLatches[closedLatchIndex]
                updateRouteRecord(
                    routeIndex,
                    cursor = AwaitingLatchedTransportReplay(actionId, ordinal, latchKey),
                    lookup = withLatch,
                )
                actions += ReplayLatchedTransport(actionId, ordinal, copyLatch(latch))
            }
        }

        private fun resolveLatchedResponse(
            routeIndex: Int,
            ordinal: Long,
            latchKey: TransportLatchKey,
            response: TransportResponse,
        ) {
            val record = routeRecords[routeIndex]
            val progress = requireNotNull(record.lookup)
            val sample = requireNotNull(progress.sampleEpochMillis)
            val outcome = resolveTransportResponse(
                route = record.registration.route,
                resourceKey = record.registration.resourceKey,
                sampleEpochMillis = sample,
                staleBaseline = progress.staleBaseline,
                conditionalRequest = latchKey.ifNoneMatch != null || latchKey.ifModifiedSince != null,
                response = response,
                sha256 = PureKotlinSha256,
            )
            when (outcome) {
                is ResponseRuleOutcome.Failure -> completeLookupRoute(
                    routeIndex,
                    ResourceRouteOutcome.Failure(outcome.failure),
                )
                is ResponseRuleOutcome.Selected -> {
                    val selected = outcome.content
                    updateRouteRecord(
                        routeIndex,
                        cursor = PendingClassGates(ordinal, selected),
                        lookup = progress.copy(selectedContent = selected),
                    )
                }
            }
        }

        private fun selectContent(
            routeIndex: Int,
            ordinal: Long,
            progress: LookupProgress,
            stored: StoredRawResource,
            provenance: ContentProvenance,
        ) {
            val registration = routeRecords[routeIndex].registration
            val selected = ResolvedResourceContent(
                route = registration.route,
                resourceKey = registration.resourceKey,
                stored = copyStored(stored),
                provenance = provenance,
            )
            updateRouteRecord(
                routeIndex,
                cursor = PendingClassGates(ordinal, selected),
                lookup = progress.copy(selectedContent = selected),
            )
        }

        private fun completeLookupRoute(
            routeIndex: Int,
            outcome: ResourceRouteOutcome,
        ) {
            val record = routeRecords[routeIndex]
            val ordinal = requireNotNull(record.ordinal)
            require(record.status == ResourceRouteStatus.RUNNING) {
                "lookup completion requires a running route"
            }
            require(activeRouteOrdinals.remove(ordinal)) {
                "lookup completion requires an active route"
            }
            updateRouteRecord(routeIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)
            if (terminalSelection == null) {
                bufferRouteOutcome(ordinal, outcome)
                if (terminalSelection == null) startEligibleRoutes()
            }
        }

        private fun finishActionAfterTerminal(routeIndex: Int): Boolean {
            if (terminalSelection == null) return false
            val record = routeRecords[routeIndex]
            val ordinal = requireNotNull(record.ordinal)
            require(record.status == ResourceRouteStatus.RUNNING && activeRouteOrdinals.remove(ordinal)) {
                "terminal cleanup action must belong to an active route"
            }
            updateRouteRecord(routeIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)
            return true
        }

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
            updateRouteRecord(routeIndex, status = ResourceRouteStatus.RESOLVED)
            bufferRouteOutcome(event.ordinal, ResourceRouteOutcome.Success)
            if (terminalSelection == null) {
                actions += DiscoverChildren(event.ordinal, parent.id)
                startEligibleRoutes()
            }
        }

        fun routeCompleted(event: RouteCompleted) {
            val routeIndex = routeIndexByOrdinal[event.ordinal]
            require(routeIndex != null) { "route completion must name an assigned route" }
            val record = routeRecords[routeIndex]
            require(record.status == ResourceRouteStatus.RUNNING) {
                "route completion requires a running route"
            }
            if (event.outcome is ResourceRouteOutcome.Success) {
                val frontierParentId = frontierStack.lastOrNull()?.parentOccurrenceId
                require(frontierParentId == null || frontierParentId !in record.joinedOccurrenceIds) {
                    "successful discovery route must complete through discovery readiness"
                }
            }
            require(activeRouteOrdinals.remove(event.ordinal)) {
                "route completion requires an active route"
            }
            updateRouteRecord(routeIndex, status = ResourceRouteStatus.RESOLVED)

            if (terminalSelection == null) {
                bufferRouteOutcome(event.ordinal, event.outcome)
                if (terminalSelection == null) startEligibleRoutes()
            }
        }

        fun externalCancellationRequested(event: ExternalCancellationRequested) {
            if (terminalSelection != null) return
            terminalSelection = ResourceTerminalSelection.External(event.cancellation)
            cancelActiveRoutes(activeRouteOrdinals.toList())
        }

        fun cleanupCancellationObserved(event: CleanupCancellationObserved) {
            require(terminalSelection != null) {
                "cleanup cancellation requires a selected terminal"
            }
            require(activeRouteOrdinals.remove(event.ordinal)) {
                "cleanup cancellation must name an active route"
            }
            val routeIndex = routeIndexByOrdinal[event.ordinal]
            require(routeIndex != null) { "cleanup cancellation must name an assigned route" }
            require(routeRecords[routeIndex].status == ResourceRouteStatus.RUNNING) {
                "cleanup cancellation requires a running route"
            }
            updateRouteRecord(routeIndex, cursor = null, status = ResourceRouteStatus.RESOLVED)
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
            val dynamicIds = mutableSetOf<ResourceOccurrenceId>()
            require(children.all { child ->
                child.occurrence.id !in occurrenceById && dynamicIds.add(child.occurrence.id)
            }) { "discovered resource occurrence IDs must be unique" }

            children.forEach { child ->
                occurrences += child.occurrence
                occurrenceById[child.occurrence.id] = child.occurrence
            }
            frontierStack[frontierStack.lastIndex] = frontier.withChildren(
                children.map { it.occurrence.id },
            )
            releaseKnownTraversal()
            scheduleEligibleOccurrences()
        }

        fun releaseKnownTraversal() {
            while (true) {
                if (frontierStack.isNotEmpty()) {
                    val frontierIndex = frontierStack.lastIndex
                    val frontier = frontierStack[frontierIndex]
                    if (frontier.hasChildren) {
                        val childId = frontier.firstChild()
                        val remaining = frontier.afterFirstChild()
                        eligibleFifo.addLast(childId)
                        val child = occurrence(childId)
                        if (child.discoveryRequired) {
                            frontierStack[frontierIndex] = frontier.withoutChildren()
                            frontierStack += remaining.remainingChildrenAsWithheld(child.id)
                            return
                        }
                        frontierStack[frontierIndex] = remaining
                        continue
                    }

                    frontierStack.removeAt(frontierIndex)
                    restoreContinuation(frontier)
                    continue
                }

                if (staticContinuation.isEmpty) return
                val occurrenceId = staticContinuation.first()
                staticContinuation = staticContinuation.advance()
                eligibleFifo.addLast(occurrenceId)
                val occurrence = occurrence(occurrenceId)
                if (occurrence.discoveryRequired) {
                    val withheld = staticContinuation
                    staticContinuation = ResourceOccurrenceIdSlice.empty()
                    frontierStack += DiscoveryFrontier.unresolved(occurrence.id, withheld)
                    return
                }
            }
        }

        private fun restoreContinuation(frontier: DiscoveryFrontier) {
            if (frontierStack.isEmpty()) {
                require(staticContinuation.isEmpty) { "static traversal continuation must be singular" }
                staticContinuation = frontier.withheldSlice()
                return
            }

            val parentIndex = frontierStack.lastIndex
            frontierStack[parentIndex] = frontier.restoreWithheldInto(frontierStack[parentIndex])
        }

        fun scheduleEligibleOccurrences() {
            while (eligibleFifo.isNotEmpty() && terminalSelection == null) {
                val occurrenceId = eligibleFifo.removeFirst()
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
            if (occurrence.registration.resourceKey.stableId !in identityByStableId) {
                val identity = CanonicalIdentityRecord(
                    occurrence.registration.resourceKey,
                    occurrence.registration.canonicalBytes,
                )
                identityRecords += identity
                identityByStableId[identity.resourceKey.stableId] = identity
            }
            val claimIndex = claimIndex(occurrence.registration.privateRentileKey)
            if (claimIndex >= 0) {
                val claim = privateRentileKeyClaims[claimIndex]
                val invalidatedEligibleOrdinal = claim.takeIf { it.usable }
                    ?.let { routeIndex(it.firstRoute) }
                    ?.takeIf { it >= 0 }
                    ?.let(routeRecords::get)
                    ?.takeIf { it.status == ResourceRouteStatus.ELIGIBLE }
                    ?.ordinal
                privateRentileKeyClaims[claimIndex] = claim.copy(usable = false)
                addRouteRecord(
                    RouteRecord(
                        registration = occurrence.registration,
                        joinedOccurrenceIds = listOf(occurrence.id),
                        ordinal = ordinal,
                        cursor = null,
                        status = ResourceRouteStatus.BLOCKED_BY_COLLISION,
                    ),
                )
                val failureOutcome = ResourceRouteOutcome.Failure(ambiguousRouteFailure())
                bufferRouteOutcome(invalidatedEligibleOrdinal ?: ordinal, failureOutcome)
                return
            }

            claimIndexByPrivateKey[occurrence.registration.privateRentileKey] =
                privateRentileKeyClaims.size
            privateRentileKeyClaims += PrivateRentileKeyClaim(
                privateKey = occurrence.registration.privateRentileKey,
                firstRoute = occurrence.registration.route,
                usable = true,
            )
            addRouteRecord(
                RouteRecord(
                    registration = occurrence.registration,
                    joinedOccurrenceIds = listOf(occurrence.id),
                    ordinal = ordinal,
                    cursor = null,
                    status = ResourceRouteStatus.ELIGIBLE,
                ),
            )
        }

        private fun makeJoinedOccurrenceEligible(
            routeIndex: Int,
            occurrence: ResourceOccurrence,
        ) {
            if (joinedOccurrenceIdSetsByRouteIndex[routeIndex].add(occurrence.id)) {
                joinedOccurrenceIdsByRouteIndex[routeIndex] += occurrence.id
            }
            val record = routeRecords[routeIndex]
            if (record.ordinal == null) {
                updateRouteRecord(
                    routeIndex,
                    ordinal = takeNextOrdinal(),
                    status = ResourceRouteStatus.ELIGIBLE,
                )
                return
            }

            if (occurrence.discoveryRequired && record.status == ResourceRouteStatus.RESOLVED) {
                actions += DiscoverChildren(record.ordinal, occurrence.id)
            }
        }

        private fun identityCollision(registration: ResourceRouteRegistration): CanonicalIdentityRecord? {
            val established = identityByStableId[registration.resourceKey.stableId] ?: return null
            return established.takeIf { it.canonicalBytes != registration.canonicalBytes }
        }

        private fun blockByCollision(
            occurrence: ResourceOccurrence,
            established: CanonicalIdentityRecord,
        ) {
            val ordinal = takeNextOrdinal()
            addRouteRecord(
                RouteRecord(
                    registration = occurrence.registration,
                    joinedOccurrenceIds = listOf(occurrence.id),
                    ordinal = ordinal,
                    cursor = null,
                    status = ResourceRouteStatus.BLOCKED_BY_COLLISION,
                ),
            )
            bufferRouteOutcome(
                ordinal,
                ResourceRouteOutcome.Failure(identityCollisionFailure(established)),
            )
        }

        private fun startEligibleRoutes() {
            if (terminalSelection != null) return
            val ceiling = startCeilingOrdinal
            val candidates = routeRecords.withIndex()
                .filter { (_, record) ->
                    val claimIndex = claimIndexByPrivateKey[record.registration.privateRentileKey]
                    record.status == ResourceRouteStatus.ELIGIBLE &&
                        record.ordinal != null &&
                        claimIndex != null &&
                        privateRentileKeyClaims[claimIndex].usable &&
                        (ceiling == null || record.ordinal < ceiling)
                }
                .sortedBy { it.value.ordinal }

            for ((index, record) in candidates) {
                if (activeRouteOrdinals.size >= initial.definition.maximumConcurrentRoutes) break
                val ordinal = requireNotNull(record.ordinal)
                updateRouteRecord(index, status = ResourceRouteStatus.RUNNING)
                activeRouteOrdinals += ordinal
                actions += StartRoute(ordinal, record.registration)
            }
        }

        private fun bufferRouteOutcome(
            ordinal: Long,
            outcome: ResourceRouteOutcome,
        ) {
            require(ordinal >= nextRetirementOrdinal && ordinal < nextRouteOrdinal) {
                "route completion must name an unretired assigned ordinal"
            }
            val insertionPoint = bufferedRouteOutcomes.binarySearch { buffered ->
                buffered.ordinal.compareTo(ordinal)
            }
            require(insertionPoint < 0) { "route outcome must be observed exactly once" }
            bufferedRouteOutcomes.add(
                index = -insertionPoint - 1,
                element = BufferedRouteOutcome(ordinal, outcome),
            )
            if (outcome !is ResourceRouteOutcome.Success) {
                startCeilingOrdinal = minOf(startCeilingOrdinal ?: ordinal, ordinal)
            }
            retireBufferedPrefix()
        }

        private fun retireBufferedPrefix() {
            var retiredCount = 0
            while (terminalSelection == null && retiredCount < bufferedRouteOutcomes.size) {
                val retired = bufferedRouteOutcomes[retiredCount]
                if (retired.ordinal != nextRetirementOrdinal) break
                retiredCount += 1
                check(nextRetirementOrdinal < Long.MAX_VALUE) {
                    "route retirement ordinal space exhausted"
                }
                nextRetirementOrdinal += 1L
                when (retired.outcome) {
                    ResourceRouteOutcome.Success -> Unit
                    is ResourceRouteOutcome.Failure,
                    is ResourceRouteOutcome.Cancelled,
                    -> {
                        terminalSelection = ResourceTerminalSelection.Route(
                            retired.ordinal,
                            retired.outcome,
                        )
                        cancelActiveRoutes(
                            activeRouteOrdinals.filter { it > retired.ordinal },
                        )
                    }
                }
            }
            if (retiredCount > 0) {
                val retained = bufferedRouteOutcomes.subList(
                    retiredCount,
                    bufferedRouteOutcomes.size,
                ).toList()
                bufferedRouteOutcomes.clear()
                bufferedRouteOutcomes.addAll(retained)
            }
        }

        private fun cancelActiveRoutes(ordinals: List<Long>) {
            ordinals.sorted().forEach { ordinal -> actions += CancelRoute(ordinal) }
        }

        private fun terminalOutcome(): ResourceOperationOutcome? {
            val selected = terminalSelection ?: return null
            if (activeRouteOrdinals.isNotEmpty()) return null
            return when (selected) {
                is ResourceTerminalSelection.External ->
                    ResourceOperationOutcome.Cancelled(selected.cancellation)
                is ResourceTerminalSelection.Route -> when (val routeOutcome = selected.outcome) {
                    ResourceRouteOutcome.Success -> error("successful route cannot occupy the terminal slot")
                    is ResourceRouteOutcome.Failure -> ResourceOperationOutcome.Failure(routeOutcome.failure)
                    is ResourceRouteOutcome.Cancelled ->
                        ResourceOperationOutcome.Cancelled(routeOutcome.cancellation)
                }
            }
        }

        private fun addRouteRecord(record: RouteRecord): Int {
            val index = routeRecords.size
            routeRecords += record
            val joinedIds = record.joinedOccurrenceIds.toMutableList()
            joinedOccurrenceIdsByRouteIndex += joinedIds
            joinedOccurrenceIdSetsByRouteIndex += joinedIds.toMutableSet()
            if (record.registration.route !in routeIndexByRoute) {
                routeIndexByRoute[record.registration.route] = index
            }
            record.ordinal?.let { ordinal ->
                require(routeIndexByOrdinal.put(ordinal, index) == null) {
                    "route ordinals must be unique"
                }
            }
            cursorActionId(record.cursor)?.let { actionId ->
                require(routeIndexByActionId.put(actionId, index) == null) {
                    "route cursor action IDs must be unique"
                }
            }
            return index
        }

        private fun updateRouteRecord(
            index: Int,
            ordinal: Long? = routeRecords[index].ordinal,
            cursor: ResourceRouteCursor? = routeRecords[index].cursor,
            status: ResourceRouteStatus = routeRecords[index].status,
            lookup: LookupProgress? = routeRecords[index].lookup,
        ) {
            val previous = routeRecords[index]
            cursorActionId(previous.cursor)?.let { actionId ->
                require(routeIndexByActionId.remove(actionId) == index) {
                    "previous cursor action must remain indexed"
                }
            }
            if (previous.ordinal != ordinal) {
                previous.ordinal?.let(routeIndexByOrdinal::remove)
                ordinal?.let { assigned ->
                    require(routeIndexByOrdinal.put(assigned, index) == null) {
                        "route ordinals must be unique"
                    }
                }
            }
            cursorActionId(cursor)?.let { actionId ->
                require(routeIndexByActionId.put(actionId, index) == null) {
                    "route cursor action IDs must be unique"
                }
            }
            routeRecords[index] = RouteRecord(
                registration = previous.registration,
                joinedOccurrenceIds = joinedOccurrenceIdsByRouteIndex[index],
                ordinal = ordinal,
                cursor = cursor,
                status = status,
                lookup = lookup,
            )
        }

        private fun takeNextActionId(): ResourceActionId {
            check(nextActionId < Long.MAX_VALUE) { "resource action ID space exhausted" }
            val actionId = ResourceActionId(nextActionId)
            nextActionId += 1L
            return actionId
        }

        private inline fun <reified T : ResourceRouteCursor> routeIndexForAction(
            actionId: ResourceActionId,
        ): Int {
            val routeIndex = requireNotNull(routeIndexByActionId[actionId]) {
                "resource event must match a current action ID"
            }
            val cursor = routeRecords[routeIndex].cursor
            require(cursor is T && cursorActionId(cursor) == actionId) {
                "resource event must match its current cursor"
            }
            return routeIndex
        }

        private fun addTransportLatch(latch: TransportLatchRecord) {
            require(latch.key !in transportLatchIndexByKey) {
                "Transport latch may close only once"
            }
            val copied = copyLatch(latch)
            transportLatchIndexByKey[copied.key] = transportLatches.size
            transportLatches += copied
        }

        private fun transportLatch(key: TransportLatchKey): TransportLatchRecord {
            val index = requireNotNull(transportLatchIndexByKey[key]) {
                "latched replay requires a closed exact latch"
            }
            return transportLatches[index]
        }

        private fun takeNextOrdinal(): Long {
            val ordinal = nextRouteOrdinal
            check(ordinal < Long.MAX_VALUE) { "route ordinal space exhausted" }
            nextRouteOrdinal += 1L
            return ordinal
        }

        private fun occurrence(id: ResourceOccurrenceId): ResourceOccurrence =
            requireNotNull(occurrenceById[id]) { "resource occurrence must be registered exactly once" }

        private fun routeIndex(route: ResourceRouteKey): Int = routeIndexByRoute[route] ?: -1

        private fun claimIndex(privateKey: RentilePrivateKey): Int =
            claimIndexByPrivateKey[privateKey] ?: -1

        fun toTransition(): ResourceOperationTransition = ResourceOperationTransition(
            state = runningState(
                definition = initial.definition,
                occurrences = occurrences,
                routeRecords = freezeRouteRecords(routeRecords, joinedOccurrenceIdsByRouteIndex),
                privateRentileKeyClaims = privateRentileKeyClaims,
                identityRecords = identityRecords,
                transportLatches = transportLatches,
                nextActionId = nextActionId,
                traversal = TraversalState.fromSlices(
                    eligibleFifo = eligibleFifo.toList(),
                    staticContinuation = staticContinuation,
                    frontierStack = frontierStack,
                ),
                nextRouteOrdinal = nextRouteOrdinal,
                activeRouteOrdinals = activeRouteOrdinals,
                nextRetirementOrdinal = nextRetirementOrdinal,
                bufferedRouteOutcomes = bufferedRouteOutcomes,
                startCeilingOrdinal = startCeilingOrdinal,
                terminalSelection = terminalSelection,
            ),
            actions = actions,
            outcome = terminalOutcome(),
        )
    }

    private fun preregisteredRouteRecord(occurrence: ResourceOccurrence): RouteRecord = RouteRecord(
        registration = occurrence.registration,
        joinedOccurrenceIds = listOf(occurrence.id),
        ordinal = null,
        cursor = null,
        status = ResourceRouteStatus.PREREGISTERED,
    )

    private fun freezeRouteRecords(
        routeRecords: List<RouteRecord>,
        joinedOccurrenceIdsByRoute: List<List<ResourceOccurrenceId>>,
    ): List<RouteRecord> {
        require(routeRecords.size == joinedOccurrenceIdsByRoute.size) {
            "route records and joined occurrences must stay synchronized"
        }
        return routeRecords.mapIndexed { index, record ->
            RouteRecord(
                registration = record.registration,
                joinedOccurrenceIds = joinedOccurrenceIdsByRoute[index],
                ordinal = record.ordinal,
                cursor = record.cursor,
                status = record.status,
                lookup = record.lookup,
            )
        }
    }

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
        transportLatches: List<TransportLatchRecord> = emptyList(),
        nextActionId: Long = 1L,
        traversal: TraversalState = TraversalState(emptyList(), emptyList(), emptyList()),
        nextRouteOrdinal: Long = 0L,
        activeRouteOrdinals: List<Long> = emptyList(),
        nextRetirementOrdinal: Long = 0L,
        bufferedRouteOutcomes: List<BufferedRouteOutcome> = emptyList(),
        startCeilingOrdinal: Long? = null,
        terminalSelection: ResourceTerminalSelection? = null,
    ): ResourceOperationState.Running = ResourceOperationState.Running(
        definition = definition,
        occurrences = occurrences,
        routeRecords = routeRecords,
        privateRentileKeyClaims = privateRentileKeyClaims,
        identityRecords = identityRecords,
        transportLatches = transportLatches,
        nextActionId = nextActionId,
        traversal = traversal,
        nextRouteOrdinal = nextRouteOrdinal,
        activeRouteOrdinals = activeRouteOrdinals,
        nextRetirementOrdinal = nextRetirementOrdinal,
        bufferedRouteOutcomes = bufferedRouteOutcomes,
        startCeilingOrdinal = startCeilingOrdinal,
        terminalSelection = terminalSelection,
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

    private fun resourceUnavailableFailure(record: RouteRecord): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.RESOURCE_UNAVAILABLE,
        stage = PipelineStage.RESOURCE_LOOKUP,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_LOOKUP,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = record.registration.route.resourceClass,
            resourceKey = record.registration.resourceKey,
        ),
    )

    private fun storeReadFailure(record: RouteRecord): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.STORE_READ_FAILED,
        stage = PipelineStage.STORE_READ,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.STORE_READ,
            resourceClass = record.registration.route.resourceClass,
            resourceKey = record.registration.resourceKey,
        ),
    )

    private fun storeIntegrityFailure(record: RouteRecord): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.STORE_INTEGRITY_FAILED,
        stage = PipelineStage.STORE_VALIDATION,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.STORE_VALIDATION,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = record.registration.route.resourceClass,
            resourceKey = record.registration.resourceKey,
        ),
    )

    private fun transportFailure(record: RouteRecord): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.TRANSPORT_EXECUTION_FAILED,
        stage = PipelineStage.TRANSPORT,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.TRANSPORT,
            resourceClass = record.registration.route.resourceClass,
            resourceKey = record.registration.resourceKey,
        ),
    )

    private fun isFresh(stored: StoredRawResource, sampleEpochMillis: Long): Boolean =
        stored.metadata.freshUntilEpochMillis?.let { it > sampleEpochMillis } == true

    private fun cursorActionId(cursor: ResourceRouteCursor?): ResourceActionId? = when (cursor) {
        null,
        is PendingClassGates,
        -> null
        is AwaitingClockSample -> cursor.actionId
        is AwaitingResident -> cursor.actionId
        is AwaitingStoreRead -> cursor.actionId
        is AwaitingTransport -> cursor.actionId
        is AwaitingLatchedTransportReplay -> cursor.actionId
    }

    private fun copyStored(stored: StoredRawResource): StoredRawResource = StoredRawResource(
        bytes = stored.bytes,
        contentDigest = stored.contentDigest,
        metadata = StoredRawResourceMetadata(
            contentType = stored.metadata.contentType,
            etag = stored.metadata.etag,
            lastModified = stored.metadata.lastModified,
            freshUntilEpochMillis = stored.metadata.freshUntilEpochMillis,
            storedAtEpochMillis = stored.metadata.storedAtEpochMillis,
        ),
    )

    private fun copyResponse(response: TransportResponse): TransportResponse = TransportResponse(
        statusCode = response.statusCode,
        body = response.body,
        metadata = TransportResponseMetadata(
            contentType = response.metadata.contentType,
            etag = response.metadata.etag,
            lastModified = response.metadata.lastModified,
            freshUntilEpochMillis = response.metadata.freshUntilEpochMillis,
        ),
    )

    private fun copyLatch(latch: TransportLatchRecord): TransportLatchRecord = TransportLatchRecord(
        key = latch.key,
        outcome = when (val outcome = latch.outcome) {
            LatchedTransportOutcome.Failed -> LatchedTransportOutcome.Failed
            is LatchedTransportOutcome.Cancelled -> LatchedTransportOutcome.Cancelled(outcome.cancellation)
            is LatchedTransportOutcome.Response -> LatchedTransportOutcome.Response(copyResponse(outcome.response))
        },
    )
}
