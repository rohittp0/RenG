package com.rohittp.reng.internal.resource

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.identity.CanonicalBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceOperationArbitrationTest {
    @Test
    fun reverseCompletionRetiresInOrdinalOrderAndChoosesTheEarliestFailure() {
        val started = start(routeCount = 3, concurrency = 3)
        val laterFailure = failure(RenGErrorCode.TRANSPORT_EXECUTION_FAILED)
        val earlierFailure = failure(RenGErrorCode.STORE_READ_FAILED)

        val completedTwo = transition(started, RouteCompleted(2L, ResourceRouteOutcome.Failure(laterFailure)))
        assertNull(completedTwo.outcome)
        assertEquals(0L, state(completedTwo).nextRetirementOrdinal)
        assertEquals(
            listOf(BufferedRouteOutcome(2L, ResourceRouteOutcome.Failure(laterFailure))),
            state(completedTwo).bufferedRouteOutcomes,
        )
        assertEquals(2L, state(completedTwo).startCeilingOrdinal)

        val completedOne = transition(completedTwo, RouteCompleted(1L, ResourceRouteOutcome.Failure(earlierFailure)))
        assertNull(completedOne.outcome)
        assertEquals(1L, state(completedOne).startCeilingOrdinal)
        assertEquals(
            listOf(
                BufferedRouteOutcome(1L, ResourceRouteOutcome.Failure(earlierFailure)),
                BufferedRouteOutcome(2L, ResourceRouteOutcome.Failure(laterFailure)),
            ),
            state(completedOne).bufferedRouteOutcomes,
        )

        val completedZero = transition(completedOne, RouteCompleted(0L, ResourceRouteOutcome.Success))

        assertEquals(ResourceOperationOutcome.Failure(earlierFailure), completedZero.outcome)
        assertEquals(
            ResourceTerminalSelection.Route(1L, ResourceRouteOutcome.Failure(earlierFailure)),
            state(completedZero).terminalSelection,
        )
        assertEquals(2L, state(completedZero).nextRetirementOrdinal)
        assertEquals(
            listOf(BufferedRouteOutcome(2L, ResourceRouteOutcome.Failure(laterFailure))),
            state(completedZero).bufferedRouteOutcomes,
        )
    }

    @Test
    fun bufferedFailureSetsAStartCeilingAndDoesNotStartEligibleHigherOrdinals() {
        val started = start(routeCount = 4, concurrency = 2)
        assertEquals(listOf(0L, 1L), state(started).activeRouteOrdinals)
        assertEquals(
            listOf(ResourceRouteStatus.ELIGIBLE, ResourceRouteStatus.ELIGIBLE),
            state(started).routeRecords.drop(2).map(RouteRecord::status),
        )
        val selectedFailure = failure(RenGErrorCode.RESOURCE_UNAVAILABLE)

        val buffered = transition(started, RouteCompleted(1L, ResourceRouteOutcome.Failure(selectedFailure)))

        assertNull(buffered.outcome)
        assertTrue(buffered.actions.isEmpty())
        assertEquals(1L, state(buffered).startCeilingOrdinal)
        assertEquals(listOf(0L), state(buffered).activeRouteOrdinals)
        assertEquals(
            listOf(ResourceRouteStatus.ELIGIBLE, ResourceRouteStatus.ELIGIBLE),
            state(buffered).routeRecords.drop(2).map(RouteRecord::status),
        )

        val selected = transition(buffered, RouteCompleted(0L, ResourceRouteOutcome.Success))
        assertEquals(ResourceOperationOutcome.Failure(selectedFailure), selected.outcome)
        assertTrue(selected.actions.isEmpty())
        assertTrue(state(selected).activeRouteOrdinals.isEmpty())
    }

    @Test
    fun routeTerminalCancelsOnlyActiveHigherOrdinalsAndWaitsForEveryCleanupObservation() {
        val started = start(routeCount = 4, concurrency = 4)
        val selectedFailure = failure(RenGErrorCode.INVALID_TRANSPORT_RESPONSE)
        val buffered = transition(started, RouteCompleted(1L, ResourceRouteOutcome.Failure(selectedFailure)))

        val selected = transition(buffered, RouteCompleted(0L, ResourceRouteOutcome.Success))

        assertNull(selected.outcome)
        assertEquals(listOf(CancelRoute(2L), CancelRoute(3L)), selected.actions)
        assertEquals(listOf(2L, 3L), state(selected).activeRouteOrdinals)
        assertEquals(
            ResourceTerminalSelection.Route(1L, ResourceRouteOutcome.Failure(selectedFailure)),
            state(selected).terminalSelection,
        )

        val cleanedThree = transition(selected, CleanupCancellationObserved(3L))
        assertNull(cleanedThree.outcome)
        assertTrue(cleanedThree.actions.isEmpty())
        assertEquals(listOf(2L), state(cleanedThree).activeRouteOrdinals)

        val lateExternal = transition(
            cleanedThree,
            ExternalCancellationRequested(cancellation(CancellationCause.CALLER, 90L)),
        )
        assertNull(lateExternal.outcome)
        assertTrue(lateExternal.actions.isEmpty())
        assertEquals(state(cleanedThree).terminalSelection, state(lateExternal).terminalSelection)

        val cleanedTwo = transition(lateExternal, CleanupCancellationObserved(2L))
        assertEquals(ResourceOperationOutcome.Failure(selectedFailure), cleanedTwo.outcome)
        assertTrue(state(cleanedTwo).activeRouteOrdinals.isEmpty())
    }

    @Test
    fun externalCancellationClaimsTheTerminalSlotFirstAndRouteCompletionOnlyCountsAsCleanup() {
        val started = start(routeCount = 3, concurrency = 3)
        val caller = cancellation(CancellationCause.CALLER, 101L)
        val requested = transition(started, ExternalCancellationRequested(caller))

        assertNull(requested.outcome)
        assertEquals(listOf(CancelRoute(0L), CancelRoute(1L), CancelRoute(2L)), requested.actions)
        assertEquals(ResourceTerminalSelection.External(caller), state(requested).terminalSelection)

        val completedDuringCleanup = transition(
            requested,
            RouteCompleted(
                0L,
                ResourceRouteOutcome.Failure(failure(RenGErrorCode.STORE_WRITE_FAILED)),
            ),
        )
        assertNull(completedDuringCleanup.outcome)
        assertTrue(completedDuringCleanup.actions.isEmpty())
        assertEquals(ResourceTerminalSelection.External(caller), state(completedDuringCleanup).terminalSelection)
        assertEquals(listOf(1L, 2L), state(completedDuringCleanup).activeRouteOrdinals)

        val secondExternal = transition(
            completedDuringCleanup,
            ExternalCancellationRequested(cancellation(CancellationCause.CANCEL_PREPARATIONS, 102L)),
        )
        assertNull(secondExternal.outcome)
        assertTrue(secondExternal.actions.isEmpty())
        assertEquals(ResourceTerminalSelection.External(caller), state(secondExternal).terminalSelection)

        val cleanedTwo = transition(secondExternal, CleanupCancellationObserved(2L))
        assertNull(cleanedTwo.outcome)
        val cleanedOne = transition(cleanedTwo, CleanupCancellationObserved(1L))
        assertEquals(ResourceOperationOutcome.Cancelled(caller), cleanedOne.outcome)
    }

    @Test
    fun externalCancellationBeatsAnAlreadyBufferedFailureButNotAnAlreadySelectedFailure() {
        val selectedFailure = failure(RenGErrorCode.RESOURCE_PARSE_FAILED)
        val buffered = transition(
            start(routeCount = 3, concurrency = 3),
            RouteCompleted(1L, ResourceRouteOutcome.Failure(selectedFailure)),
        )
        val cancelPreparations = cancellation(CancellationCause.CANCEL_PREPARATIONS, 111L)

        val externalWon = transition(buffered, ExternalCancellationRequested(cancelPreparations))

        assertNull(externalWon.outcome)
        assertEquals(listOf(CancelRoute(0L), CancelRoute(2L)), externalWon.actions)
        assertEquals(ResourceTerminalSelection.External(cancelPreparations), state(externalWon).terminalSelection)
        val cleanedZero = transition(externalWon, CleanupCancellationObserved(0L))
        val cleanedTwo = transition(cleanedZero, CleanupCancellationObserved(2L))
        assertEquals(ResourceOperationOutcome.Cancelled(cancelPreparations), cleanedTwo.outcome)
    }

    @Test
    fun lowerOrdinalFailureAndAdapterCancellationUseExactBufferedOrdinalPrecedence() {
        val adapterAtTwo = cancellation(CancellationCause.ADAPTER, 201L)
        val failureAtOne = failure(RenGErrorCode.RESOURCE_DECODE_FAILED)
        val cancellationBuffered = transition(
            start(routeCount = 3, concurrency = 3),
            RouteCompleted(2L, ResourceRouteOutcome.Cancelled(adapterAtTwo)),
        )
        val failureBuffered = transition(
            cancellationBuffered,
            RouteCompleted(1L, ResourceRouteOutcome.Failure(failureAtOne)),
        )

        val lowerFailureWon = transition(failureBuffered, RouteCompleted(0L, ResourceRouteOutcome.Success))

        assertEquals(ResourceOperationOutcome.Failure(failureAtOne), lowerFailureWon.outcome)
        assertEquals(
            ResourceTerminalSelection.Route(1L, ResourceRouteOutcome.Failure(failureAtOne)),
            state(lowerFailureWon).terminalSelection,
        )

        val failureAtTwo = failure(RenGErrorCode.STORE_INTEGRITY_FAILED)
        val adapterAtOne = cancellation(CancellationCause.ADAPTER, 202L)
        val laterFailureBuffered = transition(
            start(routeCount = 3, concurrency = 3),
            RouteCompleted(2L, ResourceRouteOutcome.Failure(failureAtTwo)),
        )
        val earlierCancellationBuffered = transition(
            laterFailureBuffered,
            RouteCompleted(1L, ResourceRouteOutcome.Cancelled(adapterAtOne)),
        )

        val lowerCancellationWon = transition(
            earlierCancellationBuffered,
            RouteCompleted(0L, ResourceRouteOutcome.Success),
        )

        assertEquals(ResourceOperationOutcome.Cancelled(adapterAtOne), lowerCancellationWon.outcome)
        assertEquals(
            ResourceTerminalSelection.Route(1L, ResourceRouteOutcome.Cancelled(adapterAtOne)),
            state(lowerCancellationWon).terminalSelection,
        )
    }

    @Test
    fun successfulOutOfOrderCompletionsAdvanceOnlyAcrossAContiguousRetirementPrefix() {
        val completedTwo = transition(
            start(routeCount = 3, concurrency = 3),
            RouteCompleted(2L, ResourceRouteOutcome.Success),
        )
        assertEquals(0L, state(completedTwo).nextRetirementOrdinal)
        assertEquals(listOf(BufferedRouteOutcome(2L, ResourceRouteOutcome.Success)), state(completedTwo).bufferedRouteOutcomes)

        val completedZero = transition(completedTwo, RouteCompleted(0L, ResourceRouteOutcome.Success))
        assertEquals(1L, state(completedZero).nextRetirementOrdinal)
        assertEquals(listOf(BufferedRouteOutcome(2L, ResourceRouteOutcome.Success)), state(completedZero).bufferedRouteOutcomes)

        val completedOne = transition(completedZero, RouteCompleted(1L, ResourceRouteOutcome.Success))
        assertNull(completedOne.outcome)
        assertEquals(3L, state(completedOne).nextRetirementOrdinal)
        assertTrue(state(completedOne).bufferedRouteOutcomes.isEmpty())
        assertNull(state(completedOne).terminalSelection)
    }

    @Test
    fun cleanupObservationMustNameAnActiveRouteAfterTerminalSelection() {
        val started = start(routeCount = 2, concurrency = 2)
        assertFailsWith<IllegalArgumentException> {
            transition(started, CleanupCancellationObserved(1L))
        }
        assertEquals(listOf(0L, 1L), state(started).activeRouteOrdinals)

        val external = transition(
            started,
            ExternalCancellationRequested(cancellation(CancellationCause.CALLER, 301L)),
        )
        assertFailsWith<IllegalArgumentException> {
            transition(external, CleanupCancellationObserved(9L))
        }
        assertEquals(listOf(0L, 1L), state(external).activeRouteOrdinals)
    }

    @Test
    fun cancellationOriginsAreAcceptedOnlyThroughTheirMatchingChannels() {
        val adapter = cancellation(CancellationCause.ADAPTER, 401L)
        assertEquals(adapter, ResourceRouteOutcome.Cancelled(adapter).cancellation)
        listOf(CancellationCause.CALLER, CancellationCause.CANCEL_PREPARATIONS).forEach { cause ->
            assertFailsWith<IllegalArgumentException> {
                ResourceRouteOutcome.Cancelled(cancellation(cause, 402L))
            }
        }

        val caller = cancellation(CancellationCause.CALLER, 403L)
        val cancelPreparations = cancellation(CancellationCause.CANCEL_PREPARATIONS, 404L)
        assertEquals(caller, ExternalCancellationRequested(caller).cancellation)
        assertEquals(
            cancelPreparations,
            ExternalCancellationRequested(cancelPreparations).cancellation,
        )
        assertFailsWith<IllegalArgumentException> {
            ExternalCancellationRequested(cancellation(CancellationCause.ADAPTER, 405L))
        }
    }

    @Test
    fun externalTerminalSelectionAcceptsOnlyExternalCancellationOrigins() {
        val caller = cancellation(CancellationCause.CALLER, 406L)
        val cancelPreparations = cancellation(CancellationCause.CANCEL_PREPARATIONS, 407L)

        assertEquals(caller, ResourceTerminalSelection.External(caller).cancellation)
        assertEquals(
            cancelPreparations,
            ResourceTerminalSelection.External(cancelPreparations).cancellation,
        )
        assertFailsWith<IllegalArgumentException> {
            ResourceTerminalSelection.External(cancellation(CancellationCause.ADAPTER, 408L))
        }
    }

    @Test
    fun repeatedPrivateKeyCollidersAttributeTheInvalidatedRouteOnlyOnce() {
        val sharedPrivateKey = RentilePrivateKey("review-shared-private-key")
        val root = occurrence(index = 0, discoveryRequired = true)
        val laterStatic = occurrence(
            index = 1,
            registration = registration(2, privateKey = sharedPrivateKey),
        )
        val earlierLeaf = occurrence(index = 2)
        val activatedLaterStatic = occurrence(index = 3, registration = laterStatic.registration)
        val firstCollider = occurrence(
            index = 4,
            registration = registration(5, privateKey = sharedPrivateKey),
        )
        val secondCollider = occurrence(
            index = 5,
            registration = registration(6, privateKey = sharedPrivateKey),
        )
        val definition = definition(1, root, laterStatic)
        val started = ResourceOperationStateMachine.start(definition)
        val ready = transition(started, RouteReadyForDiscovery(0L, root.id))

        val collisions = transition(
            ready,
            ChildrenDiscovered(
                root.id,
                listOf(
                    DiscoveredResourceChild(ResourceChildTraversal.DeclaredArray(3), secondCollider),
                    DiscoveredResourceChild(ResourceChildTraversal.DeclaredArray(2), firstCollider),
                    DiscoveredResourceChild(ResourceChildTraversal.DeclaredArray(1), activatedLaterStatic),
                    DiscoveredResourceChild(ResourceChildTraversal.DeclaredArray(0), earlierLeaf),
                ),
            ),
        )

        assertNull(collisions.outcome)
        assertEquals(listOf(StartRoute(1L, earlierLeaf.registration)), collisions.actions)
        val collisionState = state(collisions)
        assertEquals(listOf(1L), collisionState.activeRouteOrdinals)
        assertEquals(2L, routeRecord(collisionState, laterStatic).ordinal)
        assertEquals(ResourceRouteStatus.ELIGIBLE, routeRecord(collisionState, laterStatic).status)
        assertEquals(
            listOf(laterStatic.id, activatedLaterStatic.id),
            routeRecord(collisionState, laterStatic).joinedOccurrenceIds,
        )
        assertEquals(
            listOf(2L, 4L),
            collisionState.bufferedRouteOutcomes.map(BufferedRouteOutcome::ordinal),
        )
        collisionState.bufferedRouteOutcomes.forEach { buffered ->
            val routeFailure = assertIs<ResourceRouteOutcome.Failure>(buffered.outcome)
            assertEquals(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, routeFailure.failure.code)
        }
        assertEquals(2L, collisionState.startCeilingOrdinal)
        assertTrue(
            collisionState.privateRentileKeyClaims
                .single { it.privateKey == sharedPrivateKey }
                .usable
                .not(),
        )

        val selected = transition(
            collisions,
            RouteCompleted(1L, ResourceRouteOutcome.Success),
        )

        val selectedFailure = assertIs<ResourceOperationOutcome.Failure>(selected.outcome)
        assertEquals(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, selectedFailure.failure.code)
        val terminal = assertIs<ResourceTerminalSelection.Route>(state(selected).terminalSelection)
        assertEquals(2L, terminal.ordinal)
        assertEquals(3L, state(selected).nextRetirementOrdinal)
        assertEquals(listOf(4L), state(selected).bufferedRouteOutcomes.map(BufferedRouteOutcome::ordinal))
    }

    @Test
    fun runningRejectsOrdinalHolesUnsortedBuffersAndSuccessfulRouteTerminal() {
        val started = state(start(routeCount = 3, concurrency = 3))
        val records = started.routeRecords

        assertFailsWith<IllegalArgumentException> {
            ResourceOperationState.Running(
                definition = started.definition,
                occurrences = started.occurrences,
                routeRecords = listOf(records[0], records[2]),
                privateRentileKeyClaims = started.privateRentileKeyClaims,
                identityRecords = started.identityRecords,
                traversal = started.traversal,
                nextRouteOrdinal = 3L,
                activeRouteOrdinals = listOf(0L, 2L),
            )
        }

        val resolvedRecords = records.map { record ->
            RouteRecord(
                registration = record.registration,
                joinedOccurrenceIds = record.joinedOccurrenceIds,
                ordinal = record.ordinal,
                cursor = record.cursor,
                status = ResourceRouteStatus.RESOLVED,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationState.Running(
                definition = started.definition,
                occurrences = started.occurrences,
                routeRecords = resolvedRecords,
                privateRentileKeyClaims = started.privateRentileKeyClaims,
                identityRecords = started.identityRecords,
                traversal = started.traversal,
                nextRouteOrdinal = 3L,
                activeRouteOrdinals = emptyList(),
                nextRetirementOrdinal = 0L,
                bufferedRouteOutcomes = listOf(
                    BufferedRouteOutcome(2L, ResourceRouteOutcome.Success),
                    BufferedRouteOutcome(1L, ResourceRouteOutcome.Success),
                ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            ResourceTerminalSelection.Route(0L, ResourceRouteOutcome.Success)
        }
    }

    @Test
    fun successfulCompletionCannotRetireAnUnresolvedDiscoveryFrontier() {
        val root = occurrence(index = 0, discoveryRequired = true)
        val later = occurrence(index = 1)
        val started = ResourceOperationStateMachine.start(definition(1, root, later))
        val startedState = state(started)

        assertFailsWith<IllegalArgumentException> {
            transition(started, RouteCompleted(0L, ResourceRouteOutcome.Success))
        }
        assertEquals(listOf(0L), startedState.activeRouteOrdinals)
        assertEquals(0L, startedState.nextRetirementOrdinal)
        assertEquals(
            listOf(root.id),
            startedState.traversal.frontierStack.map(DiscoveryFrontier::parentOccurrenceId),
        )
        assertNull(routeRecord(startedState, later).ordinal)

        val ready = transition(started, RouteReadyForDiscovery(0L, root.id))
        assertEquals(listOf(DiscoverChildren(0L, root.id)), ready.actions)
        assertEquals(1L, state(ready).nextRetirementOrdinal)
        val closed = transition(ready, ChildrenDiscovered(root.id, emptyList()))
        assertEquals(listOf(StartRoute(1L, later.registration)), closed.actions)
        assertEquals(listOf(1L), state(closed).activeRouteOrdinals)
        assertTrue(state(closed).traversal.frontierStack.isEmpty())
    }

    @Test
    fun retirementBuffersSnapshotInputAndReturnFreshCopies() {
        val started = state(start(routeCount = 1, concurrency = 1))
        val bufferedValue = BufferedRouteOutcome(0L, ResourceRouteOutcome.Success)
        val bufferedInput = mutableListOf(bufferedValue)
        val record = started.routeRecords.single()
        val copied = ResourceOperationState.Running(
            definition = started.definition,
            occurrences = started.occurrences,
            routeRecords = listOf(
                RouteRecord(
                    registration = record.registration,
                    joinedOccurrenceIds = record.joinedOccurrenceIds,
                    ordinal = record.ordinal,
                    cursor = record.cursor,
                    status = ResourceRouteStatus.RESOLVED,
                ),
            ),
            privateRentileKeyClaims = started.privateRentileKeyClaims,
            identityRecords = started.identityRecords,
            traversal = started.traversal,
            nextRouteOrdinal = started.nextRouteOrdinal,
            activeRouteOrdinals = emptyList(),
            nextRetirementOrdinal = 0L,
            bufferedRouteOutcomes = bufferedInput,
            startCeilingOrdinal = null,
            terminalSelection = null,
        )
        bufferedInput.clear()

        val first = copied.bufferedRouteOutcomes
        val second = copied.bufferedRouteOutcomes
        assertEquals(listOf(bufferedValue), first)
        assertEquals(listOf(bufferedValue), second)
        assertNotSame(first, second)
        (first as MutableList<BufferedRouteOutcome>).clear()
        assertEquals(listOf(bufferedValue), second)
    }

    private fun start(routeCount: Int, concurrency: Int): ResourceOperationTransition {
        val occurrences = (0 until routeCount).map { index -> occurrence(index) }
        val transition = ResourceOperationStateMachine.start(
            definition(concurrency, *occurrences.toTypedArray()),
        )
        assertNull(transition.outcome)
        assertEquals(
            (0 until minOf(routeCount, concurrency)).map { ordinal ->
                StartRoute(ordinal.toLong(), occurrences[ordinal].registration)
            },
            transition.actions,
        )
        return transition
    }

    private fun definition(
        maximumConcurrentRoutes: Int,
        vararg occurrences: ResourceOccurrence,
    ): ResourceOperationDefinition = ResourceOperationDefinition(
        maximumConcurrentRoutes = maximumConcurrentRoutes,
        staticOccurrences = occurrences.toList(),
        resourceIdentities = occurrences.map { occurrence ->
            CanonicalIdentityRecord(
                occurrence.registration.resourceKey,
                occurrence.registration.canonicalBytes,
            )
        },
    )

    private fun transition(
        previous: ResourceOperationTransition,
        event: ResourceOperationEvent,
    ): ResourceOperationTransition = ResourceOperationStateMachine.transition(state(previous), event)

    private fun state(transition: ResourceOperationTransition): ResourceOperationState.Running =
        requireNotNull(transition.state)

    private fun occurrence(
        index: Int,
        registration: ResourceRouteRegistration = registration(index + 1),
        discoveryRequired: Boolean = false,
    ): ResourceOccurrence {
        val marker = index + 1
        return ResourceOccurrence(
            id = ResourceOccurrenceId(marker.toLong()),
            ownerId = ResourceOwnerId((marker + 1000).toLong()),
            registration = registration,
            discoveryRequired = discoveryRequired,
            commitBinding = ResourceCommitBinding.Single,
        )
    }

    private fun registration(
        marker: Int,
        privateKey: RentilePrivateKey = RentilePrivateKey("arbitration-private-$marker"),
    ): ResourceRouteRegistration {
        val stableId = marker.toString(16).padStart(64, '0')
        val rawStableId = (marker + 100).toString(16).padStart(64, '0')
        val resourceClass = ResourceClass.STICKER_IMAGE
        return ResourceRouteRegistration(
            route = ResourceRouteKey(
                accessMode = ResourceAccessMode.NORMAL,
                locator = ResourceLocator("arbitration-locator-$marker"),
                resourceClass = resourceClass,
                maximumResponseBytes = 4096L,
            ),
            resourceKey = ResourceKey(ResourceKind.EXTERNAL, stableId, resourceClass),
            rawKey = RawResourceKey(rawStableId, resourceClass),
            privateRentileKey = privateKey,
            canonicalBytes = CanonicalBytes("arbitration-canonical-$marker".encodeToByteArray()),
        )
    }

    private fun routeRecord(
        state: ResourceOperationState.Running,
        occurrence: ResourceOccurrence,
    ): RouteRecord = state.routeRecords.single {
        it.registration.route == occurrence.registration.route
    }

    private fun cancellation(cause: CancellationCause, id: Long): CancellationSelection =
        CancellationSelection(cause, CancellationId(id))

    private fun failure(code: RenGErrorCode): FailureDescriptor {
        val stage = when (code) {
            RenGErrorCode.TRANSPORT_EXECUTION_FAILED -> PipelineStage.TRANSPORT
            RenGErrorCode.STORE_READ_FAILED -> PipelineStage.STORE_READ
            RenGErrorCode.RESOURCE_UNAVAILABLE -> PipelineStage.RESOURCE_LOOKUP
            RenGErrorCode.INVALID_TRANSPORT_RESPONSE -> PipelineStage.TRANSPORT_VALIDATION
            RenGErrorCode.STORE_WRITE_FAILED -> PipelineStage.STORE_WRITE
            RenGErrorCode.RESOURCE_PARSE_FAILED -> PipelineStage.RESOURCE_PARSING
            RenGErrorCode.RESOURCE_DECODE_FAILED -> PipelineStage.RESOURCE_DECODING
            RenGErrorCode.STORE_INTEGRITY_FAILED -> PipelineStage.STORE_VALIDATION
            else -> error("unsupported arbitration failure marker")
        }
        val carriesResourceField = code in setOf(
            RenGErrorCode.RESOURCE_UNAVAILABLE,
            RenGErrorCode.RESOURCE_PARSE_FAILED,
            RenGErrorCode.RESOURCE_DECODE_FAILED,
            RenGErrorCode.STORE_INTEGRITY_FAILED,
        )
        val resourceClass = ResourceClass.STICKER_IMAGE
        val resourceKey = ResourceKey(
            ResourceKind.EXTERNAL,
            "e".repeat(64),
            resourceClass,
        )
        return FailureDescriptor(
            code = code,
            stage = stage,
            diagnostic = failureContextDiagnostic(
                stage = stage,
                fieldName = DiagnosticField.RESOURCE.takeIf { carriesResourceField },
                resourceClass = resourceClass,
                resourceKey = resourceKey,
                statusCode = 599.takeIf { code == RenGErrorCode.INVALID_TRANSPORT_RESPONSE },
            ),
        )
    }
}
