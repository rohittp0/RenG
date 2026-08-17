package com.rohittp.reng.internal.lifecycle

import com.rohittp.reng.DiagnosticCode
import com.rohittp.reng.DiagnosticSeverity
import com.rohittp.reng.FramebufferName
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RendererLifecyclePrecedenceTest {
    @Test
    fun activePreparationInterferenceWinsBeforeEveryOwnerAndContextConflict() {
        val conflicts = listOf(
            BlockedOperation(
                RendererLifecycleOperation.BeginPreparation,
                PipelineStage.FRAME_PREPARATION,
            ),
            BlockedOperation(
                RendererLifecycleOperation.ClearFrameHistory,
                PipelineStage.FRAME_PLANNING,
            ),
            BlockedOperation(
                RendererLifecycleOperation.FreeResources(ResourceSelector.All),
                PipelineStage.RESOURCE_FREE,
            ),
            BlockedOperation(
                RendererLifecycleOperation.CloseRenderer,
                PipelineStage.RENDERER_CLOSE,
            ),
        )
        val conflictingSnapshot = snapshot(
            ownerState = RendererOwnerState.CLOSED,
            preparationActive = true,
            hasLiveGpuObjects = true,
            deferredDeletions = listOf(deletion(0L)),
        )

        conflicts.forEach { conflict ->
            val transition = RendererLifecycleStateMachine.begin(
                conflictingSnapshot,
                conflict.operation,
            )

            assertFailure(
                transition,
                RenGErrorCode.PREPARATION_IN_PROGRESS,
                conflict.stage,
            )
            assertEquals(listOf(deletion(0L)), transition.snapshot.gpuLedger.deferredDeletions)
            assertTrue(transition.actions.isEmpty())
        }
    }

    @Test
    fun ownerStateThenFrameThenTargetFactsWinInTheFixedOrder() {
        val invalidDraw = RendererLifecycleOperation.Draw(
            frame = PreparedFrameFact.Foreign,
            target = RenderTargetFact.Foreign,
        )
        assertFailure(
            RendererLifecycleStateMachine.begin(
                snapshot(RendererOwnerState.CLOSED),
                invalidDraw,
            ),
            RenGErrorCode.RENDERER_CLOSED,
            PipelineStage.DRAW,
        )
        assertFailure(
            RendererLifecycleStateMachine.begin(
                snapshot(RendererOwnerState.AWAITING_CONTEXT_ADOPTION),
                invalidDraw,
            ),
            RenGErrorCode.RENDER_CONTEXT_ADOPTION_REQUIRED,
            PipelineStage.DRAW,
        )

        val foreignFrame = RendererLifecycleStateMachine.begin(
            snapshot(RendererOwnerState.LIVE),
            invalidDraw,
        )
        assertFailure(foreignFrame, RenGErrorCode.FOREIGN_PREPARED_FRAME, PipelineStage.DRAW)

        val closedFrame = RendererLifecycleStateMachine.begin(
            snapshot(RendererOwnerState.LIVE),
            RendererLifecycleOperation.Draw(
                frame = PreparedFrameFact.OwnedClosed,
                target = RenderTargetFact.Foreign,
            ),
        )
        assertFailure(closedFrame, RenGErrorCode.PREPARED_FRAME_CLOSED, PipelineStage.DRAW)

        val foreignTarget = RendererLifecycleStateMachine.begin(
            snapshot(RendererOwnerState.LIVE),
            RendererLifecycleOperation.Draw(
                frame = PreparedFrameFact.OwnedOpen,
                target = RenderTargetFact.Foreign,
            ),
        )
        assertFailure(foreignTarget, RenGErrorCode.FOREIGN_RENDER_TARGET, PipelineStage.RENDER_TARGET)

        val staleTarget = RendererLifecycleStateMachine.begin(
            snapshot(RendererOwnerState.LIVE),
            RendererLifecycleOperation.Draw(
                frame = PreparedFrameFact.OwnedOpen,
                target = RenderTargetFact.Stale,
            ),
        )
        assertFailure(staleTarget, RenGErrorCode.STALE_RENDER_TARGET, PipelineStage.RENDER_TARGET)

        listOf(foreignFrame, closedFrame, foreignTarget, staleTarget).forEach { transition ->
            assertTrue(transition.actions.isEmpty())
        }
    }

    @Test
    fun exactContextFailureWinsBeforeDeletionFramebufferAndPrimaryWorkAndKeepsTheQueue() {
        val framebufferName = FramebufferName(41u)
        val queue = listOf(deletion(0L), deletion(1L))
        val operation = RendererLifecycleOperation.Draw(
            frame = PreparedFrameFact.OwnedOpen,
            target = RenderTargetFact.OwnedCurrent(framebufferName),
        )
        val initial = snapshot(
            ownerState = RendererOwnerState.LIVE,
            hasLiveGpuObjects = true,
            deferredDeletions = queue,
        )

        listOf(
            ExactContextFact.NONE to RenGErrorCode.NO_CURRENT_RENDER_CONTEXT,
            ExactContextFact.DIFFERENT to RenGErrorCode.DIFFERENT_CURRENT_RENDER_CONTEXT,
        ).forEach { (contextFact, expectedCode) ->
            val begun = RendererLifecycleStateMachine.begin(initial, operation)
            assertEquals(
                listOf(RendererLifecycleAction.ObserveExactCurrentContext),
                begun.actions,
            )

            val failed = RendererLifecycleStateMachine.resume(
                requireNotNull(begun.cursor),
                RendererLifecycleObservation.ExactContextObserved(contextFact),
            )
            assertFailure(failed, expectedCode, PipelineStage.DRAW)
            assertEquals(queue, failed.snapshot.gpuLedger.deferredDeletions)
            assertTrue(failed.actions.isEmpty())
        }
    }

    @Test
    fun deferredQueueAloneRequiresExactContextAndDrainsOnlyAfterAnExactObservation() {
        val queued = listOf(deletion(8L))
        val operation = RendererLifecycleOperation.FreeResources(ResourceSelector.All)
        val initial = snapshot(
            ownerState = RendererOwnerState.LIVE,
            hasLiveGpuObjects = false,
            deferredDeletions = queued,
        )

        listOf(
            ExactContextFact.NONE to RenGErrorCode.NO_CURRENT_RENDER_CONTEXT,
            ExactContextFact.DIFFERENT to RenGErrorCode.DIFFERENT_CURRENT_RENDER_CONTEXT,
        ).forEach { (contextFact, expectedCode) ->
            val begun = RendererLifecycleStateMachine.begin(initial, operation)
            assertEquals(
                listOf(RendererLifecycleAction.ObserveExactCurrentContext),
                begun.actions,
            )

            val failed = RendererLifecycleStateMachine.resume(
                requireNotNull(begun.cursor),
                RendererLifecycleObservation.ExactContextObserved(contextFact),
            )
            assertFailure(failed, expectedCode, PipelineStage.RESOURCE_FREE)
            assertFalse(failed.snapshot.gpuLedger.hasLiveGpuObjects)
            assertEquals(queued, failed.snapshot.gpuLedger.deferredDeletions)
            assertTrue(failed.actions.isEmpty())
        }

        val begun = RendererLifecycleStateMachine.begin(initial, operation)
        val deleting = RendererLifecycleStateMachine.resume(
            requireNotNull(begun.cursor),
            RendererLifecycleObservation.ExactContextObserved(ExactContextFact.EXACT),
        )
        assertDeletionAction(deleting, queued.single(), queued)
        assertFalse(deleting.snapshot.gpuLedger.hasLiveGpuObjects)

        val drained = RendererLifecycleStateMachine.resume(
            requireNotNull(deleting.cursor),
            RendererLifecycleObservation.DeferredDeletionAcknowledged(DeletionId(8L)),
        )
        assertFalse(drained.snapshot.gpuLedger.hasLiveGpuObjects)
        assertTrue(drained.snapshot.gpuLedger.deferredDeletions.isEmpty())
        assertEquals(
            listOf(RendererLifecycleAction.ExecutePermittedOperation(operation)),
            drained.actions,
        )
    }

    @Test
    fun contextObservationUsesTheOperationStageAndIsSkippedOnlyForGpuFreeFreeAndClose() {
        val framebufferName = FramebufferName(0u)
        val requiringContext = listOf(
            ContextOperation(
                RendererLifecycleOperation.MintRenderTarget(framebufferName),
                PipelineStage.RENDER_TARGET,
                hasLiveGpuObjects = false,
            ),
            ContextOperation(
                RendererLifecycleOperation.Draw(
                    PreparedFrameFact.OwnedOpen,
                    RenderTargetFact.OwnedCurrent(framebufferName),
                ),
                PipelineStage.DRAW,
                hasLiveGpuObjects = false,
            ),
            ContextOperation(
                RendererLifecycleOperation.FreeResources(ResourceSelector.All),
                PipelineStage.RESOURCE_FREE,
                hasLiveGpuObjects = true,
            ),
            ContextOperation(
                RendererLifecycleOperation.CloseRenderer,
                PipelineStage.RENDERER_CLOSE,
                hasLiveGpuObjects = true,
            ),
        )

        requiringContext.forEach { case ->
            val begun = RendererLifecycleStateMachine.begin(
                snapshot(
                    ownerState = RendererOwnerState.LIVE,
                    hasLiveGpuObjects = case.hasLiveGpuObjects,
                ),
                case.operation,
            )
            assertEquals(
                listOf(RendererLifecycleAction.ObserveExactCurrentContext),
                begun.actions,
            )
            val failed = RendererLifecycleStateMachine.resume(
                requireNotNull(begun.cursor),
                RendererLifecycleObservation.ExactContextObserved(ExactContextFact.NONE),
            )
            assertFailure(failed, RenGErrorCode.NO_CURRENT_RENDER_CONTEXT, case.stage)
        }

        listOf(
            RendererLifecycleOperation.FreeResources(ResourceSelector.All),
            RendererLifecycleOperation.CloseRenderer,
        ).forEach { operation ->
            val begun = RendererLifecycleStateMachine.begin(
                snapshot(
                    ownerState = RendererOwnerState.LIVE,
                    hasLiveGpuObjects = false,
                ),
                operation,
            )
            assertEquals(
                listOf(RendererLifecycleAction.ExecutePermittedOperation(operation)),
                begun.actions,
            )
        }
    }

    @Test
    fun acknowledgedDeletionPrefixStaysRemovedWhenTheCurrentDeletionFailsAndRetryStartsThere() {
        val framebufferName = FramebufferName(23u)
        val resourceKey = ResourceKey(
            kind = ResourceKind.INTERNAL_PIPELINE,
            stableId = "a".repeat(64),
            resourceClass = null,
        )
        val queued = listOf(
            deletion(0L),
            deletion(1L),
            deletion(2L, resourceKey),
            deletion(3L),
        )
        val operation = RendererLifecycleOperation.MintRenderTarget(framebufferName)
        val initial = snapshot(
            ownerState = RendererOwnerState.LIVE,
            hasLiveGpuObjects = true,
            deferredDeletions = queued,
        )

        val begun = RendererLifecycleStateMachine.begin(initial, operation)
        val deletingZero = RendererLifecycleStateMachine.resume(
            requireNotNull(begun.cursor),
            RendererLifecycleObservation.ExactContextObserved(ExactContextFact.EXACT),
        )
        assertDeletionAction(deletingZero, queued[0], listOf(queued[0], queued[1], queued[2], queued[3]))

        val deletingOne = RendererLifecycleStateMachine.resume(
            requireNotNull(deletingZero.cursor),
            RendererLifecycleObservation.DeferredDeletionAcknowledged(DeletionId(0L)),
        )
        assertDeletionAction(deletingOne, queued[1], listOf(queued[1], queued[2], queued[3]))

        val deletingTwo = RendererLifecycleStateMachine.resume(
            requireNotNull(deletingOne.cursor),
            RendererLifecycleObservation.DeferredDeletionAcknowledged(DeletionId(1L)),
        )
        assertDeletionAction(deletingTwo, queued[2], listOf(queued[2], queued[3]))

        val suppliedFailure = FailureDescriptor(
            code = RenGErrorCode.GPU_OPERATION_FAILED,
            stage = PipelineStage.RENDER_TARGET,
            diagnostic = failureContextDiagnostic(
                stage = PipelineStage.RENDER_TARGET,
                resourceKey = resourceKey,
            ),
        )
        val failedAtTwo = RendererLifecycleStateMachine.resume(
            requireNotNull(deletingTwo.cursor),
            RendererLifecycleObservation.DeferredDeletionFailed(DeletionId(2L), suppliedFailure),
        )
        val failedOutcome = failedAtTwo.outcome as RendererLifecycleOutcome.Failed
        assertSame(suppliedFailure, failedOutcome.failure)
        assertEquals(listOf(queued[2], queued[3]), failedAtTwo.snapshot.gpuLedger.deferredDeletions)
        assertTrue(failedAtTwo.actions.isEmpty())

        val retried = RendererLifecycleStateMachine.begin(failedAtTwo.snapshot, operation)
        val retryDeletingTwo = RendererLifecycleStateMachine.resume(
            requireNotNull(retried.cursor),
            RendererLifecycleObservation.ExactContextObserved(ExactContextFact.EXACT),
        )
        assertDeletionAction(retryDeletingTwo, queued[2], listOf(queued[2], queued[3]))

        val deletingThree = RendererLifecycleStateMachine.resume(
            requireNotNull(retryDeletingTwo.cursor),
            RendererLifecycleObservation.DeferredDeletionAcknowledged(DeletionId(2L)),
        )
        assertDeletionAction(deletingThree, queued[3], listOf(queued[3]))

        val validatingFramebuffer = RendererLifecycleStateMachine.resume(
            requireNotNull(deletingThree.cursor),
            RendererLifecycleObservation.DeferredDeletionAcknowledged(DeletionId(3L)),
        )
        assertEquals(
            listOf(RendererLifecycleAction.ValidateFramebuffer(framebufferName)),
            validatingFramebuffer.actions,
        )
        assertTrue(validatingFramebuffer.snapshot.gpuLedger.deferredDeletions.isEmpty())
        assertTrue(
            validatingFramebuffer.cursor is RendererLifecycleCursor.AwaitingFramebuffer,
        )

        val invalidFramebuffer = RendererLifecycleStateMachine.resume(
            requireNotNull(validatingFramebuffer.cursor),
            RendererLifecycleObservation.FramebufferObserved(
                FramebufferFact.MISSING_OR_INCOMPLETE,
            ),
        )
        assertInvalidFramebufferFailure(invalidFramebuffer)
        assertTrue(invalidFramebuffer.snapshot.gpuLedger.deferredDeletions.isEmpty())
        assertTrue(invalidFramebuffer.actions.isEmpty())

        val framebufferRetry = RendererLifecycleStateMachine.begin(
            invalidFramebuffer.snapshot,
            operation,
        )
        val validatingOnRetry = RendererLifecycleStateMachine.resume(
            requireNotNull(framebufferRetry.cursor),
            RendererLifecycleObservation.ExactContextObserved(ExactContextFact.EXACT),
        )
        assertEquals(
            listOf(RendererLifecycleAction.ValidateFramebuffer(framebufferName)),
            validatingOnRetry.actions,
        )

        val readyForWork = RendererLifecycleStateMachine.resume(
            requireNotNull(validatingOnRetry.cursor),
            RendererLifecycleObservation.FramebufferObserved(FramebufferFact.COMPLETE),
        )
        assertEquals(
            listOf(RendererLifecycleAction.ExecutePermittedOperation(operation)),
            readyForWork.actions,
        )
        assertTrue(readyForWork.cursor is RendererLifecycleCursor.AwaitingPermittedOperation)

        val succeeded = RendererLifecycleStateMachine.resume(
            requireNotNull(readyForWork.cursor),
            RendererLifecycleObservation.PermittedOperationSucceeded,
        )
        assertEquals(RendererLifecycleOutcome.Succeeded, succeeded.outcome)
    }

    @Test
    fun activeCancellationWaitsForTerminationBeforeClearingThePreparationGate() {
        val initial = snapshot(
            ownerState = RendererOwnerState.AWAITING_CONTEXT_ADOPTION,
            preparationActive = true,
        )
        val begun = RendererLifecycleStateMachine.begin(
            initial,
            RendererLifecycleOperation.CancelPreparations,
        )
        assertEquals(
            listOf(RendererLifecycleAction.RequestPreparationCancellation),
            begun.actions,
        )
        assertTrue(begun.snapshot.preparationActive)

        val terminated = RendererLifecycleStateMachine.resume(
            requireNotNull(begun.cursor),
            RendererLifecycleObservation.PreparationTerminated,
        )
        assertEquals(RendererLifecycleOutcome.Succeeded, terminated.outcome)
        assertFalse(terminated.snapshot.preparationActive)
    }

    @Test
    fun eachCursorRejectsEveryNonmatchingObservationIncludingTheWrongDeletionId() {
        val snapshot = snapshot(RendererOwnerState.LIVE)
        val query = RendererLifecycleOperation.QueryResources(ResourceSelector.All)
        val adopt = RendererLifecycleOperation.AdoptCurrentRenderContext
        val deletionId = DeletionId(8L)
        val deletionSnapshot = snapshot(
            ownerState = RendererOwnerState.LIVE,
            deferredDeletions = listOf(deletion(8L)),
        )
        val framebufferName = FramebufferName(9u)
        val mismatches = listOf(
            CursorMismatch(
                RendererLifecycleCursor.AwaitingRenderCallQuiescence(snapshot, query),
                RendererLifecycleObservation.ExactContextObserved(ExactContextFact.EXACT),
            ),
            CursorMismatch(
                RendererLifecycleCursor.AwaitingExactContext(snapshot, query),
                RendererLifecycleObservation.RenderCallsQuiesced,
            ),
            CursorMismatch(
                RendererLifecycleCursor.AwaitingAdoptionContext(snapshot, adopt),
                RendererLifecycleObservation.ExactContextObserved(ExactContextFact.EXACT),
            ),
            CursorMismatch(
                RendererLifecycleCursor.AwaitingDeferredDeletion(deletionSnapshot, query, deletionId),
                RendererLifecycleObservation.FramebufferObserved(FramebufferFact.COMPLETE),
            ),
            CursorMismatch(
                RendererLifecycleCursor.AwaitingFramebuffer(snapshot, query, framebufferName),
                RendererLifecycleObservation.PermittedOperationSucceeded,
            ),
            CursorMismatch(
                RendererLifecycleCursor.AwaitingPreparationTermination(snapshot, query),
                RendererLifecycleObservation.PermittedOperationSucceeded,
            ),
            CursorMismatch(
                RendererLifecycleCursor.AwaitingPermittedOperation(snapshot, query),
                RendererLifecycleObservation.RenderCallsQuiesced,
            ),
            CursorMismatch(
                RendererLifecycleCursor.AwaitingDeferredDeletion(deletionSnapshot, query, deletionId),
                RendererLifecycleObservation.DeferredDeletionAcknowledged(DeletionId(9L)),
            ),
            CursorMismatch(
                RendererLifecycleCursor.AwaitingDeferredDeletion(deletionSnapshot, query, deletionId),
                RendererLifecycleObservation.DeferredDeletionFailed(
                    DeletionId(9L),
                    FailureDescriptor(
                        code = RenGErrorCode.GPU_OPERATION_FAILED,
                        stage = PipelineStage.GPU_RESOURCE,
                        diagnostic = failureContextDiagnostic(PipelineStage.GPU_RESOURCE),
                    ),
                ),
            ),
        )

        mismatches.forEach { mismatch ->
            assertFailsWith<IllegalArgumentException> {
                RendererLifecycleStateMachine.resume(mismatch.cursor, mismatch.observation)
            }
        }
    }

    private fun assertDeletionAction(
        transition: RendererLifecycleTransition,
        expectedDeletion: DeferredDeletion,
        expectedQueue: List<DeferredDeletion>,
    ) {
        assertEquals(
            listOf(RendererLifecycleAction.DeleteDeferred(expectedDeletion)),
            transition.actions,
        )
        assertEquals(expectedQueue, transition.snapshot.gpuLedger.deferredDeletions)
        val cursor = transition.cursor as RendererLifecycleCursor.AwaitingDeferredDeletion
        assertEquals(expectedDeletion.id, cursor.deletionId)
    }

    private fun assertInvalidFramebufferFailure(transition: RendererLifecycleTransition) {
        val outcome = transition.outcome as RendererLifecycleOutcome.Failed
        val failure = outcome.failure
        assertEquals(RenGErrorCode.INVALID_RENDER_TARGET, failure.code)
        assertEquals(PipelineStage.RENDER_TARGET, failure.stage)
        val diagnostic = requireNotNull(failure.diagnostic)
        assertEquals(DiagnosticCode.FAILURE_CONTEXT, diagnostic.code)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(PipelineStage.RENDER_TARGET, diagnostic.stage)
        assertEquals("renderTarget", diagnostic.fieldName)
        assertNull(diagnostic.resourceClass)
        assertNull(diagnostic.resourceKey)
        assertNull(diagnostic.statusCode)
        assertNull(diagnostic.limit)
        assertNull(diagnostic.actual)
    }

    private fun assertFailure(
        transition: RendererLifecycleTransition,
        code: RenGErrorCode,
        stage: PipelineStage,
    ) {
        val outcome = transition.outcome as RendererLifecycleOutcome.Failed
        assertEquals(code, outcome.failure.code)
        assertEquals(stage, outcome.failure.stage)
        assertNull(outcome.failure.diagnostic)
        assertNull(transition.cursor)
    }

    private fun deletion(id: Long, resourceKey: ResourceKey? = null): DeferredDeletion =
        DeferredDeletion(DeletionId(id), resourceKey)

    private fun snapshot(
        ownerState: RendererOwnerState,
        contextGeneration: Long = 1L,
        preparationActive: Boolean = false,
        hasLiveGpuObjects: Boolean = false,
        deferredDeletions: List<DeferredDeletion> = emptyList(),
    ): RendererLifecycleSnapshot = RendererLifecycleSnapshot(
        ownerState = ownerState,
        contextGeneration = contextGeneration,
        preparationActive = preparationActive,
        gpuLedger = GpuLedger(hasLiveGpuObjects, deferredDeletions),
    )

    private data class BlockedOperation(
        val operation: RendererLifecycleOperation,
        val stage: PipelineStage,
    )

    private data class ContextOperation(
        val operation: RendererLifecycleOperation,
        val stage: PipelineStage,
        val hasLiveGpuObjects: Boolean,
    )

    private data class CursorMismatch(
        val cursor: RendererLifecycleCursor,
        val observation: RendererLifecycleObservation,
    )
}
