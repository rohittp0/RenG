package com.rohittp.reng.internal.resource

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.acceptValue
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.identity.CanonicalBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceOperationLookupTest {
    @Test
    fun normalUsesOnlyStrictlyFreshResident() {
        data class Case(
            val name: String,
            val resident: StoredRawResource?,
            val expectsResident: Boolean,
        )

        val cases = listOf(
            Case("fresh", stored(freshUntil = 101L), true),
            Case("equal", stored(freshUntil = 100L), false),
            Case("stale", stored(freshUntil = 99L), false),
            Case("absent freshness", stored(freshUntil = null), false),
            Case("missing", null, false),
        )

        cases.forEach { case ->
            var state = startedState(ResourceAccessMode.NORMAL).state
            val sample = ResourceOperationStateMachine.beginLookup(state, 0L)
            assertEquals(listOf(SampleClock(ResourceActionId(1L), 0L)), sample.actions, case.name)
            state = requireNotNull(sample.state)

            val observed = ResourceOperationStateMachine.transition(
                state,
                ClockSampled(ResourceActionId(1L), 100L),
            )
            assertEquals(
                listOf(ObserveResident(ResourceActionId(2L), 0L, state.routeRecords.single().registration.resourceKey)),
                observed.actions,
                case.name,
            )
            state = requireNotNull(observed.state)

            val decided = ResourceOperationStateMachine.transition(
                state,
                ResidentObserved(ResourceActionId(2L), case.resident),
            )
            assertNull(decided.outcome, case.name)
            if (case.expectsResident) {
                assertTrue(decided.actions.isEmpty(), case.name)
                val record = requireNotNull(decided.state).routeRecords.single()
                val pending = assertIs<PendingClassGates>(record.cursor, case.name)
                assertEquals(ContentProvenance.RESIDENT, pending.content.provenance, case.name)
                assertEquals(case.resident, pending.content.stored, case.name)
                assertEquals(pending.content, record.lookup?.selectedContent, case.name)
                assertEquals(ResourceRouteStatus.RUNNING, record.status, case.name)
            } else {
                assertEquals(
                    listOf(ReadStore(ResourceActionId(3L), 0L, state.routeRecords.single().registration.rawKey)),
                    decided.actions,
                    case.name,
                )
                val record = requireNotNull(decided.state).routeRecords.single()
                assertIs<AwaitingStoreRead>(record.cursor, case.name)
                assertEquals(case.resident, record.lookup?.staleBaseline, case.name)
                assertTrue(requireNotNull(record.lookup).storeReadStarted, case.name)
            }
        }
    }

    @Test
    fun invalidStoreEnvelopeOrIntegrityIsTerminalBeforeAStaleResident() {
        data class Case(val name: String, val value: StoredRawResource)

        val validMetadata = metadata(freshUntil = 101L)
        val cases = listOf(
            Case("empty bytes", StoredRawResource(byteArrayOf(), ABC_DIGEST, validMetadata)),
            Case("over limit", StoredRawResource("test".encodeToByteArray(), TEST_DIGEST, validMetadata)),
            Case("short digest", StoredRawResource(ABC_BYTES, "a".repeat(63), validMetadata)),
            Case("uppercase digest", StoredRawResource(ABC_BYTES, ABC_DIGEST.uppercase(), validMetadata)),
            Case("nonhex digest", StoredRawResource(ABC_BYTES, "g".repeat(64), validMetadata)),
            Case("mismatched digest", StoredRawResource(ABC_BYTES, TEST_DIGEST, validMetadata)),
            Case("blank content type", StoredRawResource(ABC_BYTES, ABC_DIGEST, metadata(contentType = " "))),
            Case("content type CR", StoredRawResource(ABC_BYTES, ABC_DIGEST, metadata(contentType = "a\rb"))),
            Case("etag LF", StoredRawResource(ABC_BYTES, ABC_DIGEST, metadata(etag = "a\nb"))),
            Case("last modified surrogate", StoredRawResource(ABC_BYTES, ABC_DIGEST, metadata(lastModified = "\uD800"))),
            Case("negative freshness", StoredRawResource(ABC_BYTES, ABC_DIGEST, metadata(freshUntil = -1L))),
            Case("negative stored at", StoredRawResource(ABC_BYTES, ABC_DIGEST, metadata(storedAt = -1L))),
        )

        cases.forEach { case ->
            var state = startedState(ResourceAccessMode.NORMAL, maximumResponseBytes = 3L).state
            state = startThroughResident(state, stored(etag = "resident-etag", freshUntil = 100L))
            val failed = ResourceOperationStateMachine.transition(
                state,
                StoreReadCompleted(ResourceActionId(3L), SuppliedCallOutcome.Success(case.value)),
            )

            assertTrue(failed.actions.isEmpty(), case.name)
            val outcome = assertIs<ResourceOperationOutcome.Failure>(failed.outcome, case.name)
            assertFailure(
                outcome,
                RenGErrorCode.STORE_INTEGRITY_FAILED,
                PipelineStage.STORE_VALIDATION,
                expectedField = "resource",
            )
            assertEquals(ResourceRouteStatus.RESOLVED, requireNotNull(failed.state).routeRecords.single().status)
            assertTrue(requireNotNull(failed.state).transportLatches.isEmpty(), case.name)
        }
    }

    @Test
    fun normalStoreDecisionTableSupersedesOrRetainsTheExactBaseline() {
        data class Case(
            val name: String,
            val resident: StoredRawResource?,
            val storeValue: StoredRawResource?,
            val selectedFromStore: Boolean,
            val expectedEtag: String?,
            val expectedLastModified: String?,
        )

        val cases = listOf(
            Case(
                "fresh Store supersedes stale resident",
                stored(etag = "resident-etag", freshUntil = 100L),
                stored(etag = "store-etag", freshUntil = 101L),
                true,
                null,
                null,
            ),
            Case(
                "stale Store supersedes resident and prefers ETag",
                stored(etag = "resident-etag", freshUntil = 100L),
                stored(etag = "store-etag", lastModified = "store-date", freshUntil = 100L),
                false,
                "store-etag",
                null,
            ),
            Case(
                "Store last-modified is fallback validator",
                stored(etag = "resident-etag", freshUntil = 100L),
                stored(lastModified = "store-date", freshUntil = null),
                false,
                null,
                "store-date",
            ),
            Case(
                "Store miss retains resident ETag",
                stored(etag = "resident-etag", freshUntil = 100L),
                null,
                false,
                "resident-etag",
                null,
            ),
            Case(
                "validator-free baseline requests unconditional 200",
                stored(freshUntil = 100L),
                null,
                false,
                null,
                null,
            ),
            Case(
                "no baseline requests unconditional 200",
                null,
                null,
                false,
                null,
                null,
            ),
        )

        cases.forEach { case ->
            var state = startedState(ResourceAccessMode.NORMAL).state
            state = startThroughResident(state, case.resident)
            val transition = ResourceOperationStateMachine.transition(
                state,
                StoreReadCompleted(ResourceActionId(3L), SuppliedCallOutcome.Success(case.storeValue)),
            )
            assertNull(transition.outcome, case.name)
            val record = requireNotNull(transition.state).routeRecords.single()

            if (case.selectedFromStore) {
                assertTrue(transition.actions.isEmpty(), case.name)
                val pending = assertIs<PendingClassGates>(record.cursor, case.name)
                assertEquals(ContentProvenance.STORE, pending.content.provenance, case.name)
                assertEquals(case.storeValue, pending.content.stored, case.name)
                assertEquals(case.storeValue, record.lookup?.selectedContent?.stored, case.name)
            } else {
                val call = assertIs<CallTransport>(transition.actions.single(), case.name)
                assertEquals(ResourceActionId(4L), call.actionId, case.name)
                assertEquals(case.expectedEtag, call.request.metadata.ifNoneMatch, case.name)
                assertEquals(case.expectedLastModified, call.request.metadata.ifModifiedSince, case.name)
                assertEquals(ResourceClass.MODEL_GLB.acceptValue, call.request.metadata.accept, case.name)
                assertEquals(call.request.metadata.ifNoneMatch, call.latchKey.ifNoneMatch, case.name)
                assertEquals(call.request.metadata.ifModifiedSince, call.latchKey.ifModifiedSince, case.name)
                assertEquals(call.request.metadata.accept, call.latchKey.accept, case.name)
                assertEquals(case.storeValue ?: case.resident, record.lookup?.staleBaseline, case.name)
                assertEquals(call.latchKey, record.lookup?.transportLatch, case.name)
                assertIs<AwaitingTransport>(record.cursor, case.name)
            }
        }
    }

    @Test
    fun cacheOnlyAndReloadApplyTheirExactSuppressionRules() {
        var cacheState = startedState(ResourceAccessMode.CACHE_ONLY).state
        cacheState = beginAndSample(cacheState)
        val staleResident = stored(freshUntil = 1L)
        val cacheResident = ResourceOperationStateMachine.transition(
            cacheState,
            ResidentObserved(ResourceActionId(2L), staleResident),
        )
        assertTrue(cacheResident.actions.isEmpty())
        assertEquals(
            ContentProvenance.RESIDENT,
            assertIs<PendingClassGates>(requireNotNull(cacheResident.state).routeRecords.single().cursor).content.provenance,
        )

        cacheState = startedState(ResourceAccessMode.CACHE_ONLY).state
        cacheState = startThroughResident(cacheState, null)
        val staleStore = stored(freshUntil = 1L)
        val cacheStore = ResourceOperationStateMachine.transition(
            cacheState,
            StoreReadCompleted(ResourceActionId(3L), SuppliedCallOutcome.Success(staleStore)),
        )
        assertTrue(cacheStore.actions.isEmpty())
        assertEquals(
            ContentProvenance.STORE,
            assertIs<PendingClassGates>(requireNotNull(cacheStore.state).routeRecords.single().cursor).content.provenance,
        )
        assertTrue(requireNotNull(cacheStore.state).transportLatches.isEmpty())

        cacheState = startedState(ResourceAccessMode.CACHE_ONLY).state
        cacheState = startThroughResident(cacheState, null)
        val unavailable = ResourceOperationStateMachine.transition(
            cacheState,
            StoreReadCompleted(ResourceActionId(3L), SuppliedCallOutcome.Success(null)),
        )
        assertTrue(unavailable.actions.isEmpty())
        assertFailure(
            assertIs(unavailable.outcome),
            RenGErrorCode.RESOURCE_UNAVAILABLE,
            PipelineStage.RESOURCE_LOOKUP,
            expectedField = "resource",
        )

        val reloadStarted = startedState(ResourceAccessMode.RELOAD)
        val reloadLookup = ResourceOperationStateMachine.beginLookup(reloadStarted.state, 0L)
        val reloadCall = ResourceOperationStateMachine.transition(
            requireNotNull(reloadLookup.state),
            ClockSampled(ResourceActionId(1L), 100L),
        )
        val call = assertIs<CallTransport>(reloadCall.actions.single())
        assertEquals(ResourceActionId(2L), call.actionId)
        assertNull(call.request.metadata.ifNoneMatch)
        assertNull(call.request.metadata.ifModifiedSince)
        assertIs<AwaitingTransport>(requireNotNull(reloadCall.state).routeRecords.single().cursor)
        assertFalse(requireNotNull(reloadCall.state).routeRecords.single().lookup?.storeReadStarted == true)
    }

    @Test
    fun everyResourceClassUsesItsFixedAcceptValue() {
        ResourceClass.entries.forEachIndexed { index, resourceClass ->
            val started = startedState(
                mode = ResourceAccessMode.RELOAD,
                resourceClass = resourceClass,
                idOffset = index.toLong() * 10L,
            )
            val lookup = ResourceOperationStateMachine.beginLookup(started.state, 0L)
            val called = ResourceOperationStateMachine.transition(
                requireNotNull(lookup.state),
                ClockSampled(ResourceActionId(1L), 0L),
            )
            val action = assertIs<CallTransport>(called.actions.single())
            assertEquals(resourceClass.acceptValue, action.request.metadata.accept, resourceClass.name)
        }
    }

    @Test
    fun joinedRouteHasOneStoreAndConsumerExchangeThenOnlyExactLatchReplay() {
        val started = startedState(ResourceAccessMode.NORMAL, joinedOccurrences = 4096)
        assertEquals(4096, started.state.routeRecords.single().joinedOccurrenceIds.size)
        assertEquals(1, started.initialActions.filterIsInstance<StartRoute>().size)

        var state = started.state
        val emitted = mutableListOf<ResourceOperationAction>()
        val lookup = ResourceOperationStateMachine.beginLookup(state, 0L)
        emitted += lookup.actions
        state = requireNotNull(lookup.state)
        val sampled = ResourceOperationStateMachine.transition(state, ClockSampled(ResourceActionId(1L), 100L))
        emitted += sampled.actions
        state = requireNotNull(sampled.state)
        val resident = ResourceOperationStateMachine.transition(state, ResidentObserved(ResourceActionId(2L), null))
        emitted += resident.actions
        state = requireNotNull(resident.state)
        val store = ResourceOperationStateMachine.transition(
            state,
            StoreReadCompleted(ResourceActionId(3L), SuppliedCallOutcome.Success(null)),
        )
        emitted += store.actions
        val call = assertIs<CallTransport>(store.actions.single())
        state = requireNotNull(store.state)
        val transported = ResourceOperationStateMachine.transition(
            state,
            TransportCompleted(
                call.actionId,
                SuppliedCallOutcome.Success(
                    TransportResponse(
                        statusCode = 200,
                        body = ABC_BYTES,
                        metadata = TransportResponseMetadata(etag = "new-etag", freshUntilEpochMillis = 200L),
                    ),
                ),
            ),
        )
        emitted += transported.actions
        state = requireNotNull(transported.state)
        assertTrue(transported.actions.isEmpty())
        assertIs<PendingClassGates>(state.routeRecords.single().cursor)
        assertEquals(1, state.transportLatches.size)
        assertIs<LatchedTransportOutcome.Response>(state.transportLatches.single().outcome)

        val replay = ResourceOperationStateMachine.beginLookup(state, 0L)
        emitted += replay.actions
        val replayAction = assertIs<ReplayLatchedTransport>(replay.actions.single())
        assertEquals(call.latchKey, replayAction.latch.key)
        assertEquals(state.transportLatches.single(), replayAction.latch)
        state = requireNotNull(replay.state)
        val replayed = ResourceOperationStateMachine.transition(
            state,
            LatchedTransportReplayCompleted(replayAction.actionId),
        )
        assertTrue(replayed.actions.isEmpty())
        assertIs<PendingClassGates>(requireNotNull(replayed.state).routeRecords.single().cursor)

        assertEquals(1, emitted.filterIsInstance<SampleClock>().size)
        assertEquals(1, emitted.filterIsInstance<ObserveResident>().size)
        assertEquals(1, emitted.filterIsInstance<ReadStore>().size)
        assertEquals(1, emitted.filterIsInstance<CallTransport>().size)
        assertEquals(1, emitted.filterIsInstance<ReplayLatchedTransport>().size)
        assertTrue(emitted.none { it::class.simpleName.orEmpty().contains("Retry") })
        assertTrue(emitted.none { it::class.simpleName.orEmpty().contains("Repair") })
        assertTrue(emitted.none { it::class.simpleName.orEmpty().contains("Remove") })
        assertTrue(emitted.none { it::class.simpleName.orEmpty().contains("Fallback") })
        assertTrue(emitted.none { it::class.simpleName.orEmpty().contains("Write") })
        assertTrue(emitted.none { it::class.simpleName.orEmpty().contains("Validate") })
        assertTrue(emitted.none { it::class.simpleName.orEmpty().contains("Parse") })
        assertTrue(emitted.none { it::class.simpleName.orEmpty().contains("Decode") })
        assertTrue(emitted.none { it::class.simpleName.orEmpty().contains("Install") })
    }

    @Test
    fun storeAndTransportFailuresAreSanitizedAndAdapterCancellationStaysOpaque() {
        var state = startedState(ResourceAccessMode.NORMAL).state
        state = startThroughResident(state, null)
        val storeFailure = ResourceOperationStateMachine.transition(
            state,
            StoreReadCompleted(ResourceActionId(3L), SuppliedCallOutcome.Failed),
        )
        assertFailure(
            assertIs(storeFailure.outcome),
            RenGErrorCode.STORE_READ_FAILED,
            PipelineStage.STORE_READ,
            expectedField = null,
        )
        assertFalse(storeFailure.toString().contains("secret"))

        state = startedState(ResourceAccessMode.RELOAD).state
        state = beginAndSample(state)
        val transportFailure = ResourceOperationStateMachine.transition(
            state,
            TransportCompleted(ResourceActionId(2L), SuppliedCallOutcome.Failed),
        )
        assertFailure(
            assertIs(transportFailure.outcome),
            RenGErrorCode.TRANSPORT_EXECUTION_FAILED,
            PipelineStage.TRANSPORT,
            expectedField = null,
        )
        val failedLatch = requireNotNull(transportFailure.state).transportLatches.single()
        assertIs<LatchedTransportOutcome.Failed>(failedLatch.outcome)

        val cancellation = CancellationSelection(CancellationCause.ADAPTER, CancellationId(73L))
        state = startedState(ResourceAccessMode.RELOAD).state
        state = beginAndSample(state)
        val cancelled = ResourceOperationStateMachine.transition(
            state,
            TransportCompleted(ResourceActionId(2L), SuppliedCallOutcome.Cancelled(cancellation)),
        )
        assertEquals(cancellation, assertIs<ResourceOperationOutcome.Cancelled>(cancelled.outcome).cancellation)
        assertEquals(
            cancellation,
            assertIs<LatchedTransportOutcome.Cancelled>(
                requireNotNull(cancelled.state).transportLatches.single().outcome,
            ).cancellation,
        )

        listOf(CancellationCause.CALLER, CancellationCause.CANCEL_PREPARATIONS).forEach { cause ->
            val external = CancellationSelection(cause, CancellationId(9L))
            assertFailsWith<IllegalArgumentException> { SuppliedCallOutcome.Cancelled(external) }
            assertFailsWith<IllegalArgumentException> { LatchedTransportOutcome.Cancelled(external) }
        }
    }

    @Test
    fun actionIdsCursorsSamplesAndEventsAreStrictlyCorrelated() {
        var state = startedState(ResourceAccessMode.NORMAL).state
        val lookup = ResourceOperationStateMachine.beginLookup(state, 0L)
        state = requireNotNull(lookup.state)
        assertEquals(2L, state.nextActionId)
        assertIs<AwaitingClockSample>(state.routeRecords.single().cursor)

        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(state, ClockSampled(ResourceActionId(2L), 1L))
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(state, ResidentObserved(ResourceActionId(1L), null))
        }
        assertFailsWith<IllegalArgumentException> { ClockSampled(ResourceActionId(1L), -1L) }

        state = requireNotNull(
            ResourceOperationStateMachine.transition(
                state,
                ClockSampled(ResourceActionId(1L), 10L),
            ).state,
        )
        assertEquals(3L, state.nextActionId)
        assertIs<AwaitingResident>(state.routeRecords.single().cursor)
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(state, ClockSampled(ResourceActionId(1L), 11L))
        }
        assertFailsWith<IllegalArgumentException> { ResourceOperationStateMachine.beginLookup(state, 0L) }
    }

    @Test
    fun terminalSelectionPreventsAStartedRouteFromBeginningLookupWork() {
        val started = startedState(ResourceAccessMode.NORMAL)
        val cancellation = CancellationSelection(CancellationCause.CALLER, CancellationId(91L))
        val terminal = ResourceOperationStateMachine.transition(
            started.state,
            ExternalCancellationRequested(cancellation),
        )
        assertEquals(listOf(CancelRoute(0L)), terminal.actions)
        val state = requireNotNull(terminal.state)
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.beginLookup(state, 0L)
        }
    }

    @Test
    fun task13ValuesAreStructuralFreshCopiedAndRedacted() {
        val secretBytes = ABC_BYTES.copyOf()
        val secretEtag = "secret-etag"
        val response = TransportResponse(
            200,
            secretBytes,
            TransportResponseMetadata(etag = secretEtag),
        )
        val route = route(ResourceAccessMode.RELOAD)
        val latchKey = TransportLatchKey(route, null, null, "model/gltf-binary")
        val first = TransportLatchRecord(latchKey, LatchedTransportOutcome.Response(response))
        val equal = TransportLatchRecord(
            TransportLatchKey(route(ResourceAccessMode.RELOAD), null, null, "model/gltf-binary"),
            LatchedTransportOutcome.Response(
                TransportResponse(200, ABC_BYTES, TransportResponseMetadata(etag = secretEtag)),
            ),
        )
        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())

        secretBytes[0] = 0
        val latchedBody = assertIs<LatchedTransportOutcome.Response>(first.outcome).response.body
        assertContentEquals(ABC_BYTES, latchedBody)
        latchedBody[0] = 0
        assertContentEquals(ABC_BYTES, assertIs<LatchedTransportOutcome.Response>(first.outcome).response.body)

        val started = startedState(ResourceAccessMode.RELOAD)
        val latchInput = mutableListOf(first)
        val copiedState = copyState(started.state, transportLatches = latchInput)
        latchInput.clear()
        val firstRead = copiedState.transportLatches
        val secondRead = copiedState.transportLatches
        assertEquals(listOf(first), firstRead)
        assertEquals(listOf(first), secondRead)
        assertNotSame(firstRead, secondRead)
        (firstRead as MutableList<TransportLatchRecord>).clear()
        assertEquals(listOf(first), copiedState.transportLatches)

        val texts = listOf(first, equal, copiedState).map(Any::toString)
        texts.forEach { text ->
            assertFalse(text.contains(secretEtag))
            assertFalse(text.contains(ABC_DIGEST))
        }
    }

    @Test
    fun malformedLookupStateThatWouldBreakIndexingOrArbitrationIsRejected() {
        val started = startedState(ResourceAccessMode.RELOAD)
        val record = started.state.routeRecords.single()
        val registration = record.registration
        val selected = ResolvedResourceContent(
            route = registration.route,
            resourceKey = registration.resourceKey,
            stored = stored(),
            provenance = ContentProvenance.RESIDENT,
        )

        assertFailsWith<IllegalArgumentException> {
            copyState(started.state, nextActionId = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                started.state,
                routeRecords = listOf(
                    RouteRecord(
                        registration,
                        record.joinedOccurrenceIds,
                        0L,
                        AwaitingClockSample(ResourceActionId(2L), 0L),
                        ResourceRouteStatus.RUNNING,
                        LookupProgress(null, null, null, false, null, null),
                    ),
                ),
                nextActionId = 2L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                started.state,
                routeRecords = listOf(
                    RouteRecord(
                        registration,
                        record.joinedOccurrenceIds,
                        0L,
                        PendingClassGates(1L, selected),
                        ResourceRouteStatus.RUNNING,
                        LookupProgress(1L, null, null, false, null, selected),
                    ),
                ),
            )
        }
        val latch = TransportLatchRecord(
            TransportLatchKey(registration.route, null, null, registration.route.resourceClass.acceptValue),
            LatchedTransportOutcome.Failed,
        )
        assertFailsWith<IllegalArgumentException> {
            copyState(started.state, transportLatches = listOf(latch, latch))
        }
        val wrongRoute = route(ResourceAccessMode.RELOAD, locator = "different")
        val wrongSelected = selected.copy(route = wrongRoute)
        assertFailsWith<IllegalArgumentException> {
            copyState(
                started.state,
                routeRecords = listOf(
                    RouteRecord(
                        registration,
                        record.joinedOccurrenceIds,
                        0L,
                        PendingClassGates(0L, wrongSelected),
                        ResourceRouteStatus.RUNNING,
                        LookupProgress(1L, null, null, false, null, wrongSelected),
                    ),
                ),
            )
        }
    }

    @Test
    fun retiringARouteWithAnInFlightAdapterActionIsRefused() {
        var state = startedState(ResourceAccessMode.NORMAL).state
        state = requireNotNull(ResourceOperationStateMachine.beginLookup(state, 0L).state)
        assertIs<AwaitingClockSample>(state.routeRecords.single().cursor)

        val refused = assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                state,
                RouteCompleted(0L, ResourceRouteOutcome.Failure(transportFailure())),
            )
        }
        assertEquals("route completion requires no in-flight adapter action", refused.message)
    }

    @Test
    fun discoveryRouteThatFetchedItsOwnBytesCommitsThemBeforeRetiringIntoChildDiscovery() {
        var state = startedState(
            ResourceAccessMode.RELOAD,
            resourceClass = ResourceClass.MODEL_TEXTURE,
            discoveryRequired = true,
        ).state
        val parentId = state.occurrences.single().id
        state = beginAndSample(state)
        state = requireNotNull(
            ResourceOperationStateMachine.transition(
                state,
                TransportCompleted(
                    ResourceActionId(2L),
                    SuppliedCallOutcome.Success(
                        TransportResponse(
                            statusCode = 200,
                            body = ABC_BYTES,
                            metadata = TransportResponseMetadata(etag = "new-etag", freshUntilEpochMillis = 200L),
                        ),
                    ),
                ),
            ).state,
        )
        val content = assertIs<PendingClassGates>(state.routeRecords.single().cursor).content

        assertEquals(
            "route discovery readiness requires its own installed visibility",
            assertFailsWith<IllegalArgumentException> {
                ResourceOperationStateMachine.transition(state, RouteReadyForDiscovery(0L, parentId))
            }.message,
        )

        val gated = ResourceOperationStateMachine.transition(state, AdvancePendingClassGates(0L))
        val gate = assertIs<ValidateResourceClass>(gated.actions.single())
        assertEquals(ResourceClassGate.DECODE_PNG, gate.gate)
        assertEquals(
            "route discovery readiness requires no in-flight adapter action",
            assertFailsWith<IllegalArgumentException> {
                ResourceOperationStateMachine.transition(
                    requireNotNull(gated.state),
                    RouteReadyForDiscovery(0L, parentId),
                )
            }.message,
        )
        val validated = ResourceOperationStateMachine.transition(
            requireNotNull(gated.state),
            ResourceClassValidationCompleted(gate.actionId, SuppliedValidationOutcome.Valid),
        )
        val write = assertIs<WriteStore>(validated.actions.single())
        val written = ResourceOperationStateMachine.transition(
            requireNotNull(validated.state),
            StoreWriteCompleted(write.actionId, SuppliedCallOutcome.Success(Unit)),
        )
        val install = assertIs<InstallVisibility>(written.actions.single())
        val installed = ResourceOperationStateMachine.transition(
            requireNotNull(written.state),
            VisibilityInstallCompleted(install.actionId, SuppliedInstallOutcome.Succeeded),
        )

        assertTrue(installed.actions.isEmpty())
        assertNull(installed.outcome)
        val installedState = requireNotNull(installed.state)
        val installedRecord = installedState.routeRecords.single()
        assertEquals(ResourceRouteStatus.RUNNING, installedRecord.status)
        assertTrue(installedRecord.visibilityInstalled)
        assertEquals(PendingChildDiscovery(0L, content), installedRecord.cursor)
        assertEquals(listOf(0L), installedState.activeRouteOrdinals)
        assertEquals(0L, installedState.nextRetirementOrdinal)
        assertTrue(installedState.bufferedRouteOutcomes.isEmpty())

        val ready = ResourceOperationStateMachine.transition(
            installedState,
            RouteReadyForDiscovery(0L, parentId),
        )

        assertNull(ready.outcome)
        assertEquals(listOf(DiscoverChildren(0L, parentId)), ready.actions)
        val record = requireNotNull(ready.state).routeRecords.single()
        assertEquals(ResourceRouteStatus.RESOLVED, record.status)
        assertNull(record.cursor)
        assertTrue(record.visibilityInstalled)
        assertEquals(
            ContentProvenance.TRANSPORT_200,
            record.lookup?.selectedContent?.provenance,
        )
    }

    @Test
    fun retiringARouteFromPendingClassGatesIsPermitted() {
        var state = startedState(ResourceAccessMode.NORMAL).state
        state = startThroughResident(state, stored(freshUntil = 101L))
        assertIs<PendingClassGates>(state.routeRecords.single().cursor)

        val completed = ResourceOperationStateMachine.transition(
            state,
            RouteCompleted(0L, ResourceRouteOutcome.Failure(transportFailure())),
        )

        val record = requireNotNull(completed.state).routeRecords.single()
        assertEquals(ResourceRouteStatus.RESOLVED, record.status)
        assertNull(record.cursor)
    }

    private fun transportFailure(): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.TRANSPORT_EXECUTION_FAILED,
        stage = PipelineStage.TRANSPORT,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.TRANSPORT,
            resourceClass = ResourceClass.MODEL_GLB,
            resourceKey = ResourceKey(ResourceKind.EXTERNAL, "a".repeat(64), ResourceClass.MODEL_GLB),
        ),
    )

    private fun beginAndSample(state: ResourceOperationState.Running): ResourceOperationState.Running {
        val lookup = ResourceOperationStateMachine.beginLookup(state, 0L)
        return requireNotNull(
            ResourceOperationStateMachine.transition(
                requireNotNull(lookup.state),
                ClockSampled(ResourceActionId(1L), 100L),
            ).state,
        )
    }

    private fun startThroughResident(
        state: ResourceOperationState.Running,
        resident: StoredRawResource?,
    ): ResourceOperationState.Running {
        val sampled = beginAndSample(state)
        return requireNotNull(
            ResourceOperationStateMachine.transition(
                sampled,
                ResidentObserved(ResourceActionId(2L), resident),
            ).state,
        )
    }

    private fun assertFailure(
        outcome: ResourceOperationOutcome.Failure,
        code: RenGErrorCode,
        stage: PipelineStage,
        expectedField: String?,
    ) {
        assertEquals(code, outcome.failure.code)
        assertEquals(stage, outcome.failure.stage)
        val diagnostic = requireNotNull(outcome.failure.diagnostic)
        assertEquals(expectedField, diagnostic.fieldName)
        assertEquals(ResourceClass.MODEL_GLB, diagnostic.resourceClass)
        assertEquals(ResourceKind.EXTERNAL, diagnostic.resourceKey?.kind)
    }
}

private data class StartedLookup(
    val state: ResourceOperationState.Running,
    val initialActions: List<ResourceOperationAction>,
)

private fun startedState(
    mode: ResourceAccessMode,
    maximumResponseBytes: Long = 3L,
    resourceClass: ResourceClass = ResourceClass.MODEL_GLB,
    joinedOccurrences: Int = 1,
    idOffset: Long = 0L,
    discoveryRequired: Boolean = false,
): StartedLookup {
    require(joinedOccurrences > 0)
    val registration = registration(mode, maximumResponseBytes, resourceClass, idOffset)
    val occurrences = (1..joinedOccurrences).map { index ->
        ResourceOccurrence(
            id = ResourceOccurrenceId(idOffset + index.toLong()),
            ownerId = ResourceOwnerId(idOffset + index.toLong()),
            registration = registration,
            discoveryRequired = discoveryRequired,
            commitBinding = ResourceCommitBinding.Single,
        )
    }
    val definition = ResourceOperationDefinition(
        maximumConcurrentRoutes = 1,
        staticOccurrences = occurrences,
        resourceIdentities = listOf(
            CanonicalIdentityRecord(registration.resourceKey, registration.canonicalBytes),
        ),
    )
    val transition = ResourceOperationStateMachine.start(definition)
    return StartedLookup(requireNotNull(transition.state), transition.actions)
}

private fun registration(
    mode: ResourceAccessMode,
    maximumResponseBytes: Long,
    resourceClass: ResourceClass,
    idOffset: Long,
): ResourceRouteRegistration {
    val marker = ((idOffset % 10L).toInt() + 'a'.code).toChar()
    val route = route(mode, maximumResponseBytes, resourceClass, "locator-$marker")
    return ResourceRouteRegistration(
        route = route,
        resourceKey = ResourceKey(ResourceKind.EXTERNAL, marker.toString().repeat(64), resourceClass),
        rawKey = RawResourceKey(((marker.code + 1).toChar()).toString().repeat(64), resourceClass),
        privateRentileKey = RentilePrivateKey("private-$marker"),
        canonicalBytes = CanonicalBytes("canonical-$marker".encodeToByteArray()),
    )
}

private fun route(
    mode: ResourceAccessMode,
    maximumResponseBytes: Long = 3L,
    resourceClass: ResourceClass = ResourceClass.MODEL_GLB,
    locator: String = "locator-a",
): ResourceRouteKey = ResourceRouteKey(
    accessMode = mode,
    locator = ResourceLocator(locator),
    resourceClass = resourceClass,
    maximumResponseBytes = maximumResponseBytes,
)

private fun metadata(
    contentType: String? = "model/gltf-binary",
    etag: String? = null,
    lastModified: String? = null,
    freshUntil: Long? = null,
    storedAt: Long = 1L,
): StoredRawResourceMetadata = StoredRawResourceMetadata(
    contentType = contentType,
    etag = etag,
    lastModified = lastModified,
    freshUntilEpochMillis = freshUntil,
    storedAtEpochMillis = storedAt,
)

private fun stored(
    etag: String? = null,
    lastModified: String? = null,
    freshUntil: Long? = null,
): StoredRawResource = StoredRawResource(
    bytes = ABC_BYTES,
    contentDigest = ABC_DIGEST,
    metadata = metadata(etag = etag, lastModified = lastModified, freshUntil = freshUntil),
)

private fun copyState(
    state: ResourceOperationState.Running,
    routeRecords: List<RouteRecord> = state.routeRecords,
    transportLatches: List<TransportLatchRecord> = state.transportLatches,
    nextActionId: Long = state.nextActionId,
): ResourceOperationState.Running = ResourceOperationState.Running(
    definition = state.definition,
    occurrences = state.occurrences,
    routeRecords = routeRecords,
    privateRentileKeyClaims = state.privateRentileKeyClaims,
    identityRecords = state.identityRecords,
    transportLatches = transportLatches,
    nextActionId = nextActionId,
    traversal = state.traversal,
    nextRouteOrdinal = state.nextRouteOrdinal,
    activeRouteOrdinals = state.activeRouteOrdinals,
    nextRetirementOrdinal = state.nextRetirementOrdinal,
    bufferedRouteOutcomes = state.bufferedRouteOutcomes,
    startCeilingOrdinal = state.startCeilingOrdinal,
    terminalSelection = state.terminalSelection,
)

private val ABC_BYTES: ByteArray = "abc".encodeToByteArray()
private const val ABC_DIGEST: String =
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
private const val TEST_DIGEST: String =
    "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
