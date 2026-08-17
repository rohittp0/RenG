package com.rohittp.reng.internal.resource

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.identity.CanonicalBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceOperationSchedulingTest {
    @Test
    fun startKeepsPreRegistrationOrderFreeAndActivatesTheFirstDepthFirstFrontier() {
        val frontier = occurrence(1L, registration("a"), discoveryRequired = true)
        val later = occurrence(2L, registration("b"))
        val definition = definition(3, frontier, later)

        val preRegistered = ResourceOperationStateMachine.preRegister(definition)

        assertNull(preRegistered.outcome)
        assertTrue(preRegistered.actions.isEmpty())
        val preRegisteredState = requireNotNull(preRegistered.state)
        assertTrue(preRegisteredState.routeRecords.all { it.ordinal == null })
        assertTrue(preRegisteredState.activeRouteOrdinals.isEmpty())
        assertEquals(0L, preRegisteredState.nextRouteOrdinal)
        assertTrue(preRegisteredState.traversal.eligibleFifo.isEmpty())
        assertTrue(preRegisteredState.traversal.staticContinuation.isEmpty())
        assertTrue(preRegisteredState.traversal.frontierStack.isEmpty())

        val started = ResourceOperationStateMachine.start(definition)

        assertNull(started.outcome)
        assertEquals(listOf(StartRoute(0L, frontier.registration)), started.actions)
        val state = requireNotNull(started.state)
        assertEquals(0L, routeRecord(state, frontier).ordinal)
        assertEquals(ResourceRouteStatus.RUNNING, routeRecord(state, frontier).status)
        assertNull(routeRecord(state, later).ordinal)
        assertEquals(listOf(0L), state.activeRouteOrdinals)
        assertEquals(1L, state.nextRouteOrdinal)
        assertEquals(listOf(frontier.id), state.traversal.frontierStack.map(DiscoveryFrontier::parentOccurrenceId))
        assertEquals(listOf(later.id), state.traversal.frontierStack.single().withheldContinuation)
    }

    @Test
    fun startReturnsAStaticCollisionTransitionUnchanged() {
        val privateKey = RentilePrivateKey("colliding-private")
        val first = occurrence(1L, registration("a", privateKey = privateKey))
        val second = occurrence(2L, registration("b", privateKey = privateKey))
        val definition = definition(2, first, second)
        val expected = ResourceOperationStateMachine.preRegister(definition)

        val actual = ResourceOperationStateMachine.start(definition)

        val expectedFailure = assertIs<ResourceOperationOutcome.Failure>(expected.outcome)
        val actualFailure = assertIs<ResourceOperationOutcome.Failure>(actual.outcome)
        assertEquals(expectedFailure, actualFailure)
        assertEquals(expected.actions, actual.actions)
        assertEquals(expected.state?.occurrences, actual.state?.occurrences)
        assertEquals(expected.state?.routeRecords?.map(RouteRecord::status), actual.state?.routeRecords?.map(RouteRecord::status))
    }

    @Test
    fun dynamicFrontiersAssignEligibilityOrdinalsBeforeLaterStaticOccurrences() {
        val rootA = occurrence(1L, registration("a"), discoveryRequired = true)
        val rootB = occurrence(2L, registration("b"))
        val laterStaticX = occurrence(3L, registration("c"))
        val dynamicX = occurrence(4L, laterStaticX.registration)
        val frontierC = occurrence(5L, registration("d"), discoveryRequired = true)
        val childD = occurrence(6L, registration("e"))
        val definition = definition(8, rootA, rootB, laterStaticX)

        val start = ResourceOperationStateMachine.start(definition)
        assertEquals(listOf(StartRoute(0L, rootA.registration)), start.actions)

        val rootReady = ResourceOperationStateMachine.transition(
            requireNotNull(start.state),
            RouteReadyForDiscovery(0L, rootA.id),
        )
        assertEquals(listOf(DiscoverChildren(0L, rootA.id)), rootReady.actions)

        val rootChildren = ResourceOperationStateMachine.transition(
            requireNotNull(rootReady.state),
            ChildrenDiscovered(
                rootA.id,
                listOf(
                    DiscoveredResourceChild(ResourceChildTraversal.DeclaredArray(1), frontierC),
                    DiscoveredResourceChild(ResourceChildTraversal.DeclaredArray(0), dynamicX),
                ),
            ),
        )
        assertEquals(
            listOf(
                StartRoute(1L, dynamicX.registration),
                StartRoute(2L, frontierC.registration),
            ),
            rootChildren.actions,
        )

        val childFrontierReady = ResourceOperationStateMachine.transition(
            requireNotNull(rootChildren.state),
            RouteReadyForDiscovery(2L, frontierC.id),
        )
        assertEquals(listOf(DiscoverChildren(2L, frontierC.id)), childFrontierReady.actions)

        val childFrontierClosed = ResourceOperationStateMachine.transition(
            requireNotNull(childFrontierReady.state),
            ChildrenDiscovered(
                frontierC.id,
                listOf(
                    DiscoveredResourceChild(ResourceChildTraversal.DeclaredArray(0), childD),
                ),
            ),
        )
        assertEquals(
            listOf(
                StartRoute(3L, childD.registration),
                StartRoute(4L, rootB.registration),
            ),
            childFrontierClosed.actions,
        )

        val state = requireNotNull(childFrontierClosed.state)
        assertEquals(0L, routeRecord(state, rootA).ordinal)
        assertEquals(1L, routeRecord(state, dynamicX).ordinal)
        assertEquals(2L, routeRecord(state, frontierC).ordinal)
        assertEquals(3L, routeRecord(state, childD).ordinal)
        assertEquals(4L, routeRecord(state, rootB).ordinal)
        assertEquals(1L, routeRecord(state, laterStaticX).ordinal)
        assertEquals(listOf(laterStaticX.id, dynamicX.id), routeRecord(state, dynamicX).joinedOccurrenceIds)
        assertEquals(
            listOf(rootA, rootB, laterStaticX, dynamicX, frontierC, childD),
            state.occurrences,
        )
        assertEquals(5L, state.nextRouteOrdinal)
        assertEquals(listOf(1L, 3L, 4L), state.activeRouteOrdinals)
        assertTrue(state.traversal.eligibleFifo.isEmpty())
        assertTrue(state.traversal.staticContinuation.isEmpty())
        assertTrue(state.traversal.frontierStack.isEmpty())
    }

    @Test
    fun joinedDiscoveryOccurrencesEachReceiveDiscoveryWithoutRestartingTheRoute() {
        val first = occurrence(1L, registration("a"), discoveryRequired = true)
        val laterJoin = occurrence(2L, first.registration, discoveryRequired = true)
        val definition = definition(2, first, laterJoin)
        val start = ResourceOperationStateMachine.start(definition)
        val firstReady = ResourceOperationStateMachine.transition(
            requireNotNull(start.state),
            RouteReadyForDiscovery(0L, first.id),
        )
        assertEquals(listOf(DiscoverChildren(0L, first.id)), firstReady.actions)

        val firstClosed = ResourceOperationStateMachine.transition(
            requireNotNull(firstReady.state),
            ChildrenDiscovered(first.id, emptyList()),
        )

        assertEquals(listOf(DiscoverChildren(0L, laterJoin.id)), firstClosed.actions)
        val state = requireNotNull(firstClosed.state)
        assertEquals(1, state.routeRecords.size)
        assertEquals(0L, state.routeRecords.single().ordinal)
        assertEquals(listOf(first.id, laterJoin.id), state.routeRecords.single().joinedOccurrenceIds)
        assertTrue(state.activeRouteOrdinals.isEmpty())
        assertEquals(listOf(laterJoin.id), state.traversal.frontierStack.map(DiscoveryFrontier::parentOccurrenceId))
    }

    @Test
    fun concurrencyOneAssignsAllEligibleOrdinalsButStartsOneDistinctRoute() {
        val root = occurrence(1L, registration("a"), discoveryRequired = true)
        val first = occurrence(2L, registration("b"))
        val eligibleJoin = occurrence(3L, registration("c"))
        val secondEligibleJoin = occurrence(4L, eligibleJoin.registration)
        val last = occurrence(5L, registration("d"))
        val start = ResourceOperationStateMachine.start(definition(1, root))
        val ready = ResourceOperationStateMachine.transition(
            requireNotNull(start.state),
            RouteReadyForDiscovery(0L, root.id),
        )

        val discovered = ResourceOperationStateMachine.transition(
            requireNotNull(ready.state),
            ChildrenDiscovered(
                root.id,
                listOf(
                    child(ResourceChildTraversal.DeclaredArray(3), last),
                    child(ResourceChildTraversal.DeclaredArray(2), secondEligibleJoin),
                    child(ResourceChildTraversal.DeclaredArray(1), eligibleJoin),
                    child(ResourceChildTraversal.DeclaredArray(0), first),
                ),
            ),
        )

        assertEquals(listOf(StartRoute(1L, first.registration)), discovered.actions)
        val state = requireNotNull(discovered.state)
        assertEquals(listOf(1L), state.activeRouteOrdinals)
        assertEquals(2L, routeRecord(state, eligibleJoin).ordinal)
        assertEquals(ResourceRouteStatus.ELIGIBLE, routeRecord(state, eligibleJoin).status)
        assertEquals(
            listOf(eligibleJoin.id, secondEligibleJoin.id),
            routeRecord(state, eligibleJoin).joinedOccurrenceIds,
        )
        assertEquals(3L, routeRecord(state, last).ordinal)
        assertEquals(ResourceRouteStatus.ELIGIBLE, routeRecord(state, last).status)
        assertEquals(4L, state.nextRouteOrdinal)
    }

    @Test
    fun nestedFrontierRestoresLaterSiblingsBeforeTheStaticContinuation() {
        val root = occurrence(1L, registration("a"), discoveryRequired = true)
        val laterStatic = occurrence(2L, registration("b"))
        val nested = occurrence(3L, registration("c"), discoveryRequired = true)
        val sibling = occurrence(4L, registration("d"))
        val nestedChild = occurrence(5L, registration("e"))
        val start = ResourceOperationStateMachine.start(definition(8, root, laterStatic))
        val rootReady = ResourceOperationStateMachine.transition(
            requireNotNull(start.state),
            RouteReadyForDiscovery(0L, root.id),
        )
        val rootChildren = ResourceOperationStateMachine.transition(
            requireNotNull(rootReady.state),
            ChildrenDiscovered(
                root.id,
                listOf(
                    child(ResourceChildTraversal.DeclaredArray(1), sibling),
                    child(ResourceChildTraversal.DeclaredArray(0), nested),
                ),
            ),
        )
        assertEquals(listOf(StartRoute(1L, nested.registration)), rootChildren.actions)
        val nestedReady = ResourceOperationStateMachine.transition(
            requireNotNull(rootChildren.state),
            RouteReadyForDiscovery(1L, nested.id),
        )

        val closed = ResourceOperationStateMachine.transition(
            requireNotNull(nestedReady.state),
            ChildrenDiscovered(
                nested.id,
                listOf(child(ResourceChildTraversal.DeclaredArray(0), nestedChild)),
            ),
        )

        assertEquals(
            listOf(
                StartRoute(2L, nestedChild.registration),
                StartRoute(3L, sibling.registration),
                StartRoute(4L, laterStatic.registration),
            ),
            closed.actions,
        )
        assertEquals(
            listOf(0L, 4L, 1L, 2L, 3L),
            requireNotNull(closed.state).routeRecords.map { requireNotNull(it.ordinal) },
        )
    }

    @Test
    fun discoveryOccurrenceCanJoinARunningRouteAndUsesThatRoutesReadiness() {
        val root = occurrence(1L, registration("a"), discoveryRequired = true)
        val runningLeaf = occurrence(2L, registration("b"))
        val nested = occurrence(3L, registration("c"), discoveryRequired = true)
        val runningJoin = occurrence(4L, runningLeaf.registration, discoveryRequired = true)
        val start = ResourceOperationStateMachine.start(definition(2, root))
        val rootReady = ResourceOperationStateMachine.transition(
            requireNotNull(start.state),
            RouteReadyForDiscovery(0L, root.id),
        )
        val rootChildren = ResourceOperationStateMachine.transition(
            requireNotNull(rootReady.state),
            ChildrenDiscovered(
                root.id,
                listOf(
                    child(ResourceChildTraversal.DeclaredArray(1), nested),
                    child(ResourceChildTraversal.DeclaredArray(0), runningLeaf),
                ),
            ),
        )
        assertEquals(
            listOf(StartRoute(1L, runningLeaf.registration), StartRoute(2L, nested.registration)),
            rootChildren.actions,
        )
        val nestedReady = ResourceOperationStateMachine.transition(
            requireNotNull(rootChildren.state),
            RouteReadyForDiscovery(2L, nested.id),
        )

        val joined = ResourceOperationStateMachine.transition(
            requireNotNull(nestedReady.state),
            ChildrenDiscovered(
                nested.id,
                listOf(child(ResourceChildTraversal.DeclaredArray(0), runningJoin)),
            ),
        )

        assertTrue(joined.actions.isEmpty())
        val joinedState = requireNotNull(joined.state)
        assertEquals(ResourceRouteStatus.RUNNING, routeRecord(joinedState, runningJoin).status)
        assertEquals(listOf(runningLeaf.id, runningJoin.id), routeRecord(joinedState, runningJoin).joinedOccurrenceIds)
        assertEquals(listOf(1L), joinedState.activeRouteOrdinals)

        val ready = ResourceOperationStateMachine.transition(
            joinedState,
            RouteReadyForDiscovery(1L, runningJoin.id),
        )
        assertEquals(listOf(DiscoverChildren(1L, runningJoin.id)), ready.actions)
    }

    @Test
    fun malformedDiscoveryEventsFailWithoutMutatingTheSuppliedState() {
        val root = occurrence(1L, registration("a"), discoveryRequired = true)
        val later = occurrence(2L, registration("b"))
        val startState = requireNotNull(ResourceOperationStateMachine.start(definition(2, root, later)).state)
        val originalRecords = startState.routeRecords
        val originalTraversal = startState.traversal.frontierStack.map(DiscoveryFrontier::parentOccurrenceId)

        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(startState, ChildrenDiscovered(root.id, emptyList()))
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(startState, RouteReadyForDiscovery(1L, root.id))
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(startState, RouteReadyForDiscovery(0L, later.id))
        }
        assertEquals(originalRecords, startState.routeRecords)
        assertEquals(originalTraversal, startState.traversal.frontierStack.map(DiscoveryFrontier::parentOccurrenceId))

        val readyState = requireNotNull(
            ResourceOperationStateMachine.transition(
                startState,
                RouteReadyForDiscovery(0L, root.id),
            ).state,
        )
        val duplicated = occurrence(3L, registration("c"))
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                readyState,
                ChildrenDiscovered(
                    root.id,
                    listOf(
                        child(ResourceChildTraversal.DeclaredArray(0), duplicated),
                        child(ResourceChildTraversal.DeclaredArray(1), duplicated),
                    ),
                ),
            )
        }
        assertEquals(listOf(root, later), readyState.occurrences)
    }

    @Test
    fun runningStateRejectsDuplicateOccurrencesOrdinalsAndInvalidActiveSets() {
        val first = occurrence(1L, registration("a"))
        val second = occurrence(2L, registration("b"))
        val firstRunning = RouteRecord(first.registration, listOf(first.id), 0L, null, ResourceRouteStatus.RUNNING)
        val secondRunning = RouteRecord(second.registration, listOf(second.id), 1L, null, ResourceRouteStatus.RUNNING)
        val definition = definition(1, first, second)
        val identities = listOf(
            CanonicalIdentityRecord(first.registration.resourceKey, first.registration.canonicalBytes),
            CanonicalIdentityRecord(second.registration.resourceKey, second.registration.canonicalBytes),
        )
        val claims = listOf(
            PrivateRentileKeyClaim(first.registration.privateRentileKey, first.registration.route, true),
            PrivateRentileKeyClaim(second.registration.privateRentileKey, second.registration.route, true),
        )

        assertFailsWith<IllegalArgumentException> {
            ResourceOperationState.Running(
                definition,
                listOf(first, first),
                listOf(firstRunning),
                listOf(claims.first()),
                listOf(identities.first()),
                nextRouteOrdinal = 1L,
                activeRouteOrdinals = listOf(0L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationState.Running(
                definition,
                listOf(first, second),
                listOf(firstRunning, secondRunning.copyRecord(ordinal = 0L)),
                claims,
                identities,
                nextRouteOrdinal = 2L,
                activeRouteOrdinals = listOf(0L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationState.Running(
                definition,
                listOf(first, second),
                listOf(firstRunning, secondRunning),
                claims,
                identities,
                nextRouteOrdinal = 2L,
                activeRouteOrdinals = listOf(0L, 1L),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationState.Running(
                definition,
                listOf(first),
                listOf(firstRunning.copyRecord(status = ResourceRouteStatus.ELIGIBLE)),
                listOf(claims.first()),
                listOf(identities.first()),
                nextRouteOrdinal = 1L,
                activeRouteOrdinals = listOf(0L),
            )
        }
    }

    @Test
    fun joinedOccurrencesDoNotConsumeDistinctRouteCapacityAndRawKeysNeverCauseAmbiguity() {
        val root = occurrence(1L, registration("a"), discoveryRequired = true)
        val sharedRawKey = rawKey('f', ResourceClass.STICKER_IMAGE)
        val firstX = occurrence(2L, registration("b", rawKey = sharedRawKey))
        val joinedX = occurrence(3L, firstX.registration)
        val routeY = occurrence(
            4L,
            registration(
                marker = "c",
                rawKey = sharedRawKey,
                privateKey = RentilePrivateKey("private-c-distinct"),
            ),
        )
        val start = ResourceOperationStateMachine.start(definition(2, root))
        val ready = ResourceOperationStateMachine.transition(
            requireNotNull(start.state),
            RouteReadyForDiscovery(0L, root.id),
        )

        val children = ResourceOperationStateMachine.transition(
            requireNotNull(ready.state),
            ChildrenDiscovered(
                root.id,
                listOf(
                    DiscoveredResourceChild(ResourceChildTraversal.DeclaredArray(2), routeY),
                    DiscoveredResourceChild(ResourceChildTraversal.DeclaredArray(1), joinedX),
                    DiscoveredResourceChild(ResourceChildTraversal.DeclaredArray(0), firstX),
                ),
            ),
        )

        assertNull(children.outcome)
        assertEquals(
            listOf(StartRoute(1L, firstX.registration), StartRoute(2L, routeY.registration)),
            children.actions,
        )
        val state = requireNotNull(children.state)
        assertEquals(listOf(1L, 2L), state.activeRouteOrdinals)
        assertEquals(2, state.routeRecords.count { it.status == ResourceRouteStatus.RUNNING })
        assertEquals(listOf(firstX.id, joinedX.id), routeRecord(state, firstX).joinedOccurrenceIds)
        assertTrue(state.privateRentileKeyClaims.all(PrivateRentileKeyClaim::usable))
    }

    @Test
    fun maximumFrontierSharesRemainingSlicesAndAssignsEveryOrdinalInCanonicalOrder() {
        val root = occurrence(1L, registration("a"), discoveryRequired = true)
        val nested = occurrence(2L, indexedRegistration(0), discoveryRequired = true)
        val laterChildren = (1 until 4096).map { index ->
            occurrence(index.toLong() + 2L, indexedRegistration(index))
        }
        val canonicalChildren = listOf(nested) + laterChildren
        val start = ResourceOperationStateMachine.start(definition(64, root))
        val ready = ResourceOperationStateMachine.transition(
            requireNotNull(start.state),
            RouteReadyForDiscovery(0L, root.id),
        )

        val discovered = ResourceOperationStateMachine.transition(
            requireNotNull(ready.state),
            ChildrenDiscovered(
                root.id,
                canonicalChildren.mapIndexed { index, child ->
                    DiscoveredResourceChild(ResourceChildTraversal.DeclaredArray(index), child)
                }.reversed(),
            ),
        )

        assertEquals(listOf(StartRoute(1L, nested.registration)), discovered.actions)
        val withheldFrontier = requireNotNull(discovered.state).traversal.frontierStack.last()
        assertEquals(4096, withheldFrontier.withheldBackingSize)
        assertEquals(1, withheldFrontier.withheldStartIndex)
        assertEquals(laterChildren.map(ResourceOccurrence::id), withheldFrontier.withheldContinuation)

        val nestedReady = ResourceOperationStateMachine.transition(
            requireNotNull(discovered.state),
            RouteReadyForDiscovery(1L, nested.id),
        )
        val completed = ResourceOperationStateMachine.transition(
            requireNotNull(nestedReady.state),
            ChildrenDiscovered(nested.id, emptyList()),
        )

        assertEquals((2L..65L).toList(), completed.actions.map { assertIs<StartRoute>(it).ordinal })
        val state = requireNotNull(completed.state)
        assertEquals((0L..4096L).toList(), state.routeRecords.map { requireNotNull(it.ordinal) })
        assertEquals(listOf(root) + canonicalChildren, state.occurrences)
        assertEquals(4097L, state.nextRouteOrdinal)
        assertEquals((2L..65L).toList(), state.activeRouteOrdinals)
        assertTrue(state.traversal.eligibleFifo.isEmpty())
        assertTrue(state.traversal.staticContinuation.isEmpty())
        assertTrue(state.traversal.frontierStack.isEmpty())
    }

    @Test
    fun childOrderingIsCanonicalAndIndependentOfSuppliedListOrder() {
        val parentId = ResourceOccurrenceId(99L)
        val spriteExpected = listOf(
            child(ResourceChildTraversal.BasemapSprite(SpriteMember.JSON), 1L, "a"),
            child(ResourceChildTraversal.BasemapSprite(SpriteMember.IMAGE), 2L, "b"),
        )
        val sourceExpected = listOf(
            child(ResourceChildTraversal.BasemapSource("a", BasemapSourceMember.Metadata), 3L, "c"),
            child(ResourceChildTraversal.BasemapSource("a", BasemapSourceMember.Tile(0, 1, 7)), 4L, "d"),
            child(ResourceChildTraversal.BasemapSource("a", BasemapSourceMember.Tile(0, 1, 9)), 5L, "e"),
            child(ResourceChildTraversal.BasemapSource("a", BasemapSourceMember.Tile(0, 3, 1)), 6L, "f"),
            child(ResourceChildTraversal.BasemapSource("a", BasemapSourceMember.Tile(1, 1, 7)), 7L, "0"),
            child(ResourceChildTraversal.BasemapSource("z", BasemapSourceMember.Metadata), 8L, "1"),
            child(ResourceChildTraversal.BasemapSource("é", BasemapSourceMember.Metadata), 9L, "2"),
        )
        val arrayExpected = listOf(
            child(ResourceChildTraversal.DeclaredArray(0), 10L, "3"),
            child(ResourceChildTraversal.DeclaredArray(1), 11L, "4"),
            child(ResourceChildTraversal.DeclaredArray(9), 12L, "5"),
        )
        val objectExpected = listOf(
            child(ResourceChildTraversal.ObjectMember("a"), 13L, "6"),
            child(ResourceChildTraversal.ObjectMember("z"), 14L, "7"),
            child(ResourceChildTraversal.ObjectMember("é"), 15L, "8"),
        )

        listOf(spriteExpected, sourceExpected, arrayExpected, objectExpected).forEach { expected ->
            val supplied = expected.reversed().toMutableList()
            val event = ChildrenDiscovered(parentId, supplied)
            supplied.clear()

            val firstRead = event.children
            val secondRead = event.children
            assertEquals(expected, firstRead)
            assertEquals(expected, secondRead)
            assertNotSame(firstRead, secondRead)
            (firstRead as MutableList<DiscoveredResourceChild>).clear()
            assertEquals(expected, secondRead)
        }
    }

    @Test
    fun duplicateTraversalDescriptorsAreRejectedEvenWhenOccurrencesDiffer() {
        val duplicate = ResourceChildTraversal.ObjectMember("same-secret-key")

        assertFailsWith<IllegalArgumentException> {
            ChildrenDiscovered(
                ResourceOccurrenceId(99L),
                listOf(
                    child(duplicate, 1L, "a"),
                    child(ResourceChildTraversal.ObjectMember("same-secret-key"), 2L, "b"),
                ),
            )
        }
    }

    @Test
    fun metadataTraversalUsesStructuralEqualityButRedactsExactText() {
        val sourceSecret = "source-credential-secret"
        val objectSecret = "object-member-secret"
        val firstSource = ResourceChildTraversal.BasemapSource(sourceSecret, BasemapSourceMember.Metadata)
        val equalSource = ResourceChildTraversal.BasemapSource(sourceSecret, BasemapSourceMember.Metadata)
        val differentSource = ResourceChildTraversal.BasemapSource("other", BasemapSourceMember.Metadata)
        val tileSource = ResourceChildTraversal.BasemapSource(
            sourceSecret,
            BasemapSourceMember.Tile(123456789, 987654321, 246813579),
        )
        val firstObject = ResourceChildTraversal.ObjectMember(objectSecret)
        val equalObject = ResourceChildTraversal.ObjectMember(objectSecret)
        val differentObject = ResourceChildTraversal.ObjectMember("other")
        val event = ChildrenDiscovered(
            ResourceOccurrenceId(99L),
            listOf(
                child(firstSource, 1L, "a"),
                child(firstObject, 2L, "b"),
            ),
        )

        assertEquals(firstSource, equalSource)
        assertEquals(firstSource.hashCode(), equalSource.hashCode())
        assertFalse(firstSource == differentSource)
        assertEquals(firstObject, equalObject)
        assertEquals(firstObject.hashCode(), equalObject.hashCode())
        assertFalse(firstObject == differentObject)
        listOf(firstSource, equalSource, tileSource, firstObject, equalObject, event).forEach { value ->
            val text = value.toString()
            assertFalse(text.contains(sourceSecret))
            assertFalse(text.contains(objectSecret))
            assertFalse(text.contains("123456789"))
            assertFalse(text.contains("987654321"))
            assertFalse(text.contains("246813579"))
        }
    }

    @Test
    fun frontierTraversalAndActiveOrdinalListsAreDefensiveCopies() {
        val firstId = ResourceOccurrenceId(1L)
        val secondId = ResourceOccurrenceId(2L)
        val childInput = mutableListOf(firstId)
        val withheldInput = mutableListOf(secondId)
        val frontier = DiscoveryFrontier(ResourceOccurrenceId(3L), childInput, withheldInput)
        childInput.clear()
        withheldInput.clear()
        assertFreshCopy(frontier.childOccurrenceIds, frontier.childOccurrenceIds, listOf(firstId))
        assertFreshCopy(frontier.withheldContinuation, frontier.withheldContinuation, listOf(secondId))

        val eligibleInput = mutableListOf(firstId)
        val staticInput = mutableListOf(secondId)
        val stackInput = mutableListOf(frontier)
        val traversal = TraversalState(eligibleInput, staticInput, stackInput)
        eligibleInput.clear()
        staticInput.clear()
        stackInput.clear()
        assertFreshCopy(traversal.eligibleFifo, traversal.eligibleFifo, listOf(firstId))
        assertFreshCopy(traversal.staticContinuation, traversal.staticContinuation, listOf(secondId))
        assertFreshCopy(traversal.frontierStack, traversal.frontierStack, listOf(frontier))

        val activeOccurrence = occurrence(4L, registration("a"))
        val activeIdentity = CanonicalIdentityRecord(
            activeOccurrence.registration.resourceKey,
            activeOccurrence.registration.canonicalBytes,
        )
        val activeClaim = PrivateRentileKeyClaim(
            activeOccurrence.registration.privateRentileKey,
            activeOccurrence.registration.route,
            usable = true,
        )
        val activeRecord = RouteRecord(
            activeOccurrence.registration,
            listOf(activeOccurrence.id),
            0L,
            null,
            ResourceRouteStatus.RUNNING,
        )
        val activeInput = mutableListOf(0L)
        val running = ResourceOperationState.Running(
            definition = definition(1, activeOccurrence),
            occurrences = listOf(activeOccurrence),
            routeRecords = listOf(activeRecord),
            privateRentileKeyClaims = listOf(activeClaim),
            identityRecords = listOf(activeIdentity),
            traversal = traversal,
            nextRouteOrdinal = 1L,
            activeRouteOrdinals = activeInput,
        )
        activeInput.clear()
        assertFreshCopy(running.activeRouteOrdinals, running.activeRouteOrdinals, listOf(0L))
    }

    @Test
    fun equalDynamicCanonicalIdentitiesShareOneRegistryRecordAcrossDistinctRoutes() {
        val root = occurrence(1L, registration("a"), discoveryRequired = true)
        val sharedKey = externalKey('d', ResourceClass.STICKER_IMAGE)
        val first = occurrence(
            2L,
            registration(
                marker = "b",
                resourceKey = sharedKey,
                privateKey = RentilePrivateKey("first-distinct-private"),
                canonicalText = "shared-canonical",
            ),
        )
        val second = occurrence(
            3L,
            registration(
                marker = "c",
                resourceKey = sharedKey,
                privateKey = RentilePrivateKey("second-distinct-private"),
                canonicalText = "shared-canonical",
            ),
        )
        val start = ResourceOperationStateMachine.start(definition(3, root))
        val ready = ResourceOperationStateMachine.transition(
            requireNotNull(start.state),
            RouteReadyForDiscovery(0L, root.id),
        )

        val discovered = ResourceOperationStateMachine.transition(
            requireNotNull(ready.state),
            ChildrenDiscovered(
                root.id,
                listOf(
                    child(ResourceChildTraversal.DeclaredArray(1), second),
                    child(ResourceChildTraversal.DeclaredArray(0), first),
                ),
            ),
        )

        assertNull(discovered.outcome)
        assertEquals(
            listOf(StartRoute(1L, first.registration), StartRoute(2L, second.registration)),
            discovered.actions,
        )
        val state = requireNotNull(discovered.state)
        assertEquals(
            listOf(
                CanonicalIdentityRecord(root.registration.resourceKey, root.registration.canonicalBytes),
                CanonicalIdentityRecord(sharedKey, first.registration.canonicalBytes),
            ),
            state.identityRecords,
        )
    }

    @Test
    fun dynamicPrivateKeyCollisionPreventsAStillEligibleExistingRouteFromStarting() {
        val root = occurrence(1L, registration("a"), discoveryRequired = true)
        val sharedPrivateKey = RentilePrivateKey("shared-private-key")
        val laterStatic = occurrence(
            2L,
            registration(
                marker = "b",
                rawKey = rawKey('b', ResourceClass.STICKER_IMAGE),
                privateKey = sharedPrivateKey,
            ),
        )
        val earlierDynamicJoin = occurrence(3L, laterStatic.registration)
        val dynamicCollision = occurrence(
            4L,
            registration(
                marker = "c",
                rawKey = rawKey('c', ResourceClass.STICKER_IMAGE),
                privateKey = sharedPrivateKey,
            ),
        )
        val start = ResourceOperationStateMachine.start(definition(2, root, laterStatic))
        val ready = ResourceOperationStateMachine.transition(
            requireNotNull(start.state),
            RouteReadyForDiscovery(0L, root.id),
        )

        val collision = ResourceOperationStateMachine.transition(
            requireNotNull(ready.state),
            ChildrenDiscovered(
                root.id,
                listOf(
                    child(ResourceChildTraversal.DeclaredArray(1), dynamicCollision),
                    child(ResourceChildTraversal.DeclaredArray(0), earlierDynamicJoin),
                ),
            ),
        )

        assertIs<ResourceOperationOutcome.Failure>(collision.outcome)
        assertTrue(collision.actions.isEmpty())
        val state = requireNotNull(collision.state)
        assertEquals(1L, routeRecord(state, earlierDynamicJoin).ordinal)
        assertEquals(ResourceRouteStatus.ELIGIBLE, routeRecord(state, earlierDynamicJoin).status)
        assertEquals(2L, routeRecord(state, dynamicCollision).ordinal)
        assertEquals(ResourceRouteStatus.BLOCKED_BY_COLLISION, routeRecord(state, dynamicCollision).status)
        assertTrue(state.activeRouteOrdinals.isEmpty())
        assertFalse(state.privateRentileKeyClaims.single { it.privateKey == sharedPrivateKey }.usable)
    }

    @Test
    fun dynamicPrivateKeyCollisionRetainsBlockedOccurrenceAndStartsNoCollidingRoute() {
        val root = occurrence(1L, registration("a"), discoveryRequired = true)
        val sharedPrivateKey = RentilePrivateKey("shared-private-key")
        val laterStatic = occurrence(
            2L,
            registration(
                marker = "b",
                rawKey = rawKey('b', ResourceClass.STICKER_IMAGE),
                privateKey = sharedPrivateKey,
            ),
        )
        val dynamicCollision = occurrence(
            3L,
            registration(
                marker = "c",
                rawKey = rawKey('c', ResourceClass.STICKER_IMAGE),
                privateKey = sharedPrivateKey,
            ),
        )
        val start = ResourceOperationStateMachine.start(definition(4, root, laterStatic))
        val ready = ResourceOperationStateMachine.transition(
            requireNotNull(start.state),
            RouteReadyForDiscovery(0L, root.id),
        )

        val collision = ResourceOperationStateMachine.transition(
            requireNotNull(ready.state),
            ChildrenDiscovered(
                root.id,
                listOf(child(ResourceChildTraversal.DeclaredArray(0), dynamicCollision)),
            ),
        )

        val failure = assertIs<ResourceOperationOutcome.Failure>(collision.outcome).failure
        assertEquals(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, failure.code)
        assertEquals(PipelineStage.RESOURCE_LOOKUP, failure.stage)
        assertEquals("resource", failure.diagnostic?.fieldName)
        assertNull(failure.diagnostic?.resourceClass)
        assertNull(failure.diagnostic?.resourceKey)
        assertTrue(collision.actions.isEmpty())
        val state = requireNotNull(collision.state)
        assertEquals(listOf(root, laterStatic, dynamicCollision), state.occurrences)
        assertEquals(ResourceRouteStatus.BLOCKED_BY_COLLISION, routeRecord(state, dynamicCollision).status)
        assertEquals(1L, routeRecord(state, dynamicCollision).ordinal)
        assertEquals(2L, state.nextRouteOrdinal)
        assertEquals(
            listOf(
                CanonicalIdentityRecord(root.registration.resourceKey, root.registration.canonicalBytes),
                CanonicalIdentityRecord(laterStatic.registration.resourceKey, laterStatic.registration.canonicalBytes),
                CanonicalIdentityRecord(
                    dynamicCollision.registration.resourceKey,
                    dynamicCollision.registration.canonicalBytes,
                ),
            ),
            state.identityRecords,
        )
        assertEquals(
            listOf(PrivateRentileKeyClaim(sharedPrivateKey, laterStatic.registration.route, usable = false)),
            state.privateRentileKeyClaims.filter { it.privateKey == sharedPrivateKey },
        )
        assertNull(routeRecord(state, laterStatic).ordinal)
    }

    @Test
    fun dynamicIdentityCollisionTakesPrecedenceOverPrivateKeyAmbiguity() {
        val root = occurrence(1L, registration("a"), discoveryRequired = true)
        val sharedPrivateKey = RentilePrivateKey("combined-collision-private")
        val stableId = "d".repeat(64)
        val established = occurrence(
            2L,
            registration(
                marker = "b",
                resourceKey = ResourceKey(ResourceKind.EXTERNAL, stableId, ResourceClass.MODEL_GLB),
                resourceClass = ResourceClass.MODEL_GLB,
                privateKey = sharedPrivateKey,
                canonicalText = "established-combined-canonical",
            ),
        )
        val collision = occurrence(
            3L,
            registration(
                marker = "c",
                resourceKey = ResourceKey(ResourceKind.EXTERNAL, stableId, ResourceClass.MODEL_TEXTURE),
                resourceClass = ResourceClass.MODEL_TEXTURE,
                privateKey = sharedPrivateKey,
                canonicalText = "different-combined-canonical",
            ),
        )
        val start = ResourceOperationStateMachine.start(definition(2, root, established))
        val ready = ResourceOperationStateMachine.transition(
            requireNotNull(start.state),
            RouteReadyForDiscovery(0L, root.id),
        )

        val failed = ResourceOperationStateMachine.transition(
            requireNotNull(ready.state),
            ChildrenDiscovered(
                root.id,
                listOf(child(ResourceChildTraversal.DeclaredArray(0), collision)),
            ),
        )

        val failure = assertIs<ResourceOperationOutcome.Failure>(failed.outcome).failure
        assertEquals(RenGErrorCode.IDENTITY_COLLISION, failure.code)
        assertTrue(failed.actions.isEmpty())
        val state = requireNotNull(failed.state)
        assertTrue(state.privateRentileKeyClaims.single { it.privateKey == sharedPrivateKey }.usable)
        assertEquals(ResourceRouteStatus.BLOCKED_BY_COLLISION, routeRecord(state, collision).status)
        assertEquals(1L, routeRecord(state, collision).ordinal)
    }

    @Test
    fun dynamicIdentityCollisionRetainsEstablishedIdentityAndFullBlockedOccurrence() {
        val root = occurrence(1L, registration("a"), discoveryRequired = true)
        val stableId = "d".repeat(64)
        val establishedRegistration = registration(
            marker = "b",
            resourceKey = ResourceKey(ResourceKind.EXTERNAL, stableId, ResourceClass.MODEL_GLB),
            resourceClass = ResourceClass.MODEL_GLB,
            canonicalText = "established-canonical",
        )
        val laterStatic = occurrence(2L, establishedRegistration)
        val collidingRegistration = registration(
            marker = "c",
            resourceKey = ResourceKey(ResourceKind.EXTERNAL, stableId, ResourceClass.MODEL_TEXTURE),
            resourceClass = ResourceClass.MODEL_TEXTURE,
            canonicalText = "different-canonical",
        )
        val dynamicCollision = occurrence(3L, collidingRegistration)
        val start = ResourceOperationStateMachine.start(definition(4, root, laterStatic))
        val ready = ResourceOperationStateMachine.transition(
            requireNotNull(start.state),
            RouteReadyForDiscovery(0L, root.id),
        )

        val collision = ResourceOperationStateMachine.transition(
            requireNotNull(ready.state),
            ChildrenDiscovered(
                root.id,
                listOf(child(ResourceChildTraversal.DeclaredArray(0), dynamicCollision)),
            ),
        )

        val failure = assertIs<ResourceOperationOutcome.Failure>(collision.outcome).failure
        assertEquals(RenGErrorCode.IDENTITY_COLLISION, failure.code)
        assertEquals(PipelineStage.RESOURCE_LOOKUP, failure.stage)
        assertEquals(establishedRegistration.resourceKey, failure.diagnostic?.resourceKey)
        assertEquals(ResourceClass.MODEL_GLB, failure.diagnostic?.resourceClass)
        assertTrue(collision.actions.isEmpty())
        val state = requireNotNull(collision.state)
        assertEquals(listOf(root, laterStatic, dynamicCollision), state.occurrences)
        assertEquals(ResourceRouteStatus.BLOCKED_BY_COLLISION, routeRecord(state, dynamicCollision).status)
        assertEquals(1L, routeRecord(state, dynamicCollision).ordinal)
        assertEquals(2L, state.nextRouteOrdinal)
        assertEquals(
            listOf(
                CanonicalIdentityRecord(root.registration.resourceKey, root.registration.canonicalBytes),
                CanonicalIdentityRecord(establishedRegistration.resourceKey, establishedRegistration.canonicalBytes),
            ),
            state.identityRecords,
        )
        assertNull(routeRecord(state, laterStatic).ordinal)
    }

    private fun definition(
        maximumConcurrentRoutes: Int,
        vararg occurrences: ResourceOccurrence,
    ): ResourceOperationDefinition = ResourceOperationDefinition(
        maximumConcurrentRoutes = maximumConcurrentRoutes,
        staticOccurrences = occurrences.toList(),
        resourceIdentities = occurrences.map {
            CanonicalIdentityRecord(it.registration.resourceKey, it.registration.canonicalBytes)
        },
    )

    private fun occurrence(
        id: Long,
        registration: ResourceRouteRegistration,
        discoveryRequired: Boolean = false,
    ): ResourceOccurrence = ResourceOccurrence(
        id = ResourceOccurrenceId(id),
        ownerId = ResourceOwnerId(id + 100L),
        registration = registration,
        discoveryRequired = discoveryRequired,
        commitBinding = ResourceCommitBinding.Single,
    )

    private fun indexedRegistration(index: Int): ResourceRouteRegistration {
        val stableId = (index + 1).toString(16).padStart(64, '0')
        val rawStableId = (index + 5000).toString(16).padStart(64, '0')
        return ResourceRouteRegistration(
            route = ResourceRouteKey(
                accessMode = ResourceAccessMode.NORMAL,
                locator = ResourceLocator("indexed-locator-$index"),
                resourceClass = ResourceClass.STICKER_IMAGE,
                maximumResponseBytes = 4096L,
            ),
            resourceKey = ResourceKey(ResourceKind.EXTERNAL, stableId, ResourceClass.STICKER_IMAGE),
            rawKey = RawResourceKey(rawStableId, ResourceClass.STICKER_IMAGE),
            privateRentileKey = RentilePrivateKey("indexed-private-$index"),
            canonicalBytes = CanonicalBytes("indexed-canonical-$index".encodeToByteArray()),
        )
    }

    private fun registration(
        marker: String,
        resourceClass: ResourceClass = ResourceClass.STICKER_IMAGE,
        resourceKey: ResourceKey = externalKey(marker.first(), resourceClass),
        rawKey: RawResourceKey = rawKey(marker.first(), resourceClass),
        privateKey: RentilePrivateKey = RentilePrivateKey("private-$marker"),
        canonicalText: String = "canonical-$marker",
    ): ResourceRouteRegistration = ResourceRouteRegistration(
        route = ResourceRouteKey(
            accessMode = ResourceAccessMode.NORMAL,
            locator = ResourceLocator("locator-$marker"),
            resourceClass = resourceClass,
            maximumResponseBytes = 4096L,
        ),
        resourceKey = resourceKey,
        rawKey = rawKey,
        privateRentileKey = privateKey,
        canonicalBytes = CanonicalBytes(canonicalText.encodeToByteArray()),
    )

    private fun child(
        traversal: ResourceChildTraversal,
        id: Long,
        marker: String,
    ): DiscoveredResourceChild = child(traversal, occurrence(id, registration(marker)))

    private fun child(
        traversal: ResourceChildTraversal,
        occurrence: ResourceOccurrence,
    ): DiscoveredResourceChild = DiscoveredResourceChild(traversal, occurrence)

    private fun RouteRecord.copyRecord(
        ordinal: Long? = this.ordinal,
        status: ResourceRouteStatus = this.status,
    ): RouteRecord = RouteRecord(
        registration = registration,
        joinedOccurrenceIds = joinedOccurrenceIds,
        ordinal = ordinal,
        cursor = cursor,
        status = status,
    )

    private fun routeRecord(
        state: ResourceOperationState.Running,
        occurrence: ResourceOccurrence,
    ): RouteRecord = state.routeRecords.single { it.registration.route == occurrence.registration.route }

    private fun externalKey(marker: Char, resourceClass: ResourceClass): ResourceKey = ResourceKey(
        kind = ResourceKind.EXTERNAL,
        stableId = marker.lowercaseChar().toString().repeat(64),
        resourceClass = resourceClass,
    )

    private fun rawKey(marker: Char, resourceClass: ResourceClass): RawResourceKey =
        RawResourceKey(marker.lowercaseChar().toString().repeat(64), resourceClass)

    private fun <T> assertFreshCopy(first: List<T>, second: List<T>, expected: List<T>) {
        assertEquals(expected, first)
        assertEquals(expected, second)
        assertNotSame(first, second)
        (first as MutableList<T>).clear()
        assertEquals(expected, second)
    }
}
