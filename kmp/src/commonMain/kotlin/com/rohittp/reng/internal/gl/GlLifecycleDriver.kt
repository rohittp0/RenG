package com.rohittp.reng.internal.gl

import com.rohittp.reng.FramebufferName
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.lifecycle.AdoptionContextFact
import com.rohittp.reng.internal.lifecycle.ExactContextFact
import com.rohittp.reng.internal.lifecycle.FramebufferFact
import com.rohittp.reng.internal.lifecycle.RendererLifecycleAction
import com.rohittp.reng.internal.lifecycle.RendererLifecycleObservation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOperation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOutcome
import com.rohittp.reng.internal.lifecycle.RendererLifecycleSnapshot
import com.rohittp.reng.internal.lifecycle.RendererLifecycleStateMachine
import com.rohittp.reng.internal.lifecycle.RendererOwnerState

internal fun interface PermittedOperationExecutor {
    fun execute(operation: RendererLifecycleOperation): FailureDescriptor?
}

internal fun interface RenderCallBarrier {
    fun awaitRenderCallQuiescence()
}

internal fun interface PreparationController {
    fun requestCancellationAndAwaitTermination()
}

/**
 * Drives [RendererLifecycleStateMachine] with the real GL facts the machine's actions ask for, and
 * executes the single permitted operation it authorizes. This driver re-decides nothing: every
 * branch below either supplies a fact the machine requested or performs the one action the machine
 * named for the current step. No `when` here duplicates a decision the reducer already made, and
 * no GL result overrides a machine outcome.
 */
internal class GlLifecycleDriver(
    private val binding: GlBinding,
    private val probe: RenderContextProbe,
    private val registry: GlObjectRegistry,
    private val programs: GlProgramCache,
    private val barrier: RenderCallBarrier = RenderCallBarrier { },
    private val preparation: PreparationController = PreparationController { },
    initialSnapshot: RendererLifecycleSnapshot,
    initialContext: RenderContextIdentity? = null,
    initialProfile: RenderContextProfile? = null,
) {
    var snapshot: RendererLifecycleSnapshot = initialSnapshot
        private set

    var adoptedContext: RenderContextIdentity? = initialContext
        private set

    var profile: RenderContextProfile? = initialProfile
        private set

    private var pendingContext: RenderContextIdentity? = null
    private var pendingProfile: RenderContextProfile? = null

    internal fun run(
        operation: RendererLifecycleOperation,
        executor: PermittedOperationExecutor,
    ): RendererLifecycleOutcome {
        var transition = RendererLifecycleStateMachine.begin(snapshot, operation)
        while (true) {
            snapshot = transition.snapshot
            val outcome = transition.outcome
            if (outcome != null) {
                applyTerminal(operation, outcome)
                return outcome
            }
            val cursor = requireNotNull(transition.cursor) { "a transition has a cursor or an outcome" }
            val observation = perform(transition.actions.single(), executor)
            transition = RendererLifecycleStateMachine.resume(cursor, observation)
        }
    }

    private fun perform(
        action: RendererLifecycleAction,
        executor: PermittedOperationExecutor,
    ): RendererLifecycleObservation = when (action) {
        RendererLifecycleAction.AwaitRenderCallQuiescence -> {
            barrier.awaitRenderCallQuiescence()
            RendererLifecycleObservation.RenderCallsQuiesced
        }

        RendererLifecycleAction.ObserveExactCurrentContext ->
            RendererLifecycleObservation.ExactContextObserved(
                adoptedContext?.let { exactContextFact(it, probe) }
                    ?: ExactContextFact.NONE,
            )

        RendererLifecycleAction.ObserveAdoptableCurrentContext -> observeAdoption()

        is RendererLifecycleAction.DeleteDeferred -> {
            GlErrorQueue.drainOnEntry(binding)
            deleteGlObjects(binding, registry.takeQueued(action.deletion.id))
            if (GlErrorQueue.firstOwnError(binding) == GL_NO_ERROR) {
                RendererLifecycleObservation.DeferredDeletionAcknowledged(action.deletion.id)
            } else {
                RendererLifecycleObservation.DeferredDeletionFailed(
                    deletionId = action.deletion.id,
                    failure = glOperationFailure(
                        PipelineStage.RESOURCE_FREE,
                        action.deletion.resourceKey,
                    ),
                )
            }
        }

        is RendererLifecycleAction.ValidateFramebuffer ->
            RendererLifecycleObservation.FramebufferObserved(framebufferFact(action.framebufferName))

        RendererLifecycleAction.RequestPreparationCancellation -> {
            preparation.requestCancellationAndAwaitTermination()
            RendererLifecycleObservation.PreparationTerminated
        }

        is RendererLifecycleAction.ExecutePermittedOperation ->
            executor.execute(action.operation)
                ?.let { RendererLifecycleObservation.PermittedOperationFailed(it) }
                ?: RendererLifecycleObservation.PermittedOperationSucceeded
    }

    private fun observeAdoption(): RendererLifecycleObservation {
        val identity = probe.currentContextIdentity()
            ?: return RendererLifecycleObservation.AdoptionContextObserved(AdoptionContextFact.NONE)
        return when (val adoption = adoptRenderContext(binding)) {
            is RenderContextAdoption.Adopted -> {
                pendingContext = identity
                pendingProfile = adoption.profile
                RendererLifecycleObservation.AdoptionContextObserved(AdoptionContextFact.SUPPORTED)
            }

            is RenderContextAdoption.Rejected -> {
                pendingContext = null
                pendingProfile = null
                RendererLifecycleObservation.AdoptionContextObserved(AdoptionContextFact.UNSUPPORTED)
            }
        }
    }

    private fun framebufferFact(framebufferName: FramebufferName): FramebufferFact {
        val name = framebufferName.value.toInt()
        if (name != 0 && !binding.isFramebuffer(name)) return FramebufferFact.MISSING_OR_INCOMPLETE
        val previous = IntArray(1)
        binding.getIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, previous)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, name)
        val status = binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, previous[0])
        return if (status == GL_FRAMEBUFFER_COMPLETE) {
            FramebufferFact.COMPLETE
        } else {
            FramebufferFact.MISSING_OR_INCOMPLETE
        }
    }

    private fun applyTerminal(
        operation: RendererLifecycleOperation,
        outcome: RendererLifecycleOutcome,
    ) {
        if (outcome !is RendererLifecycleOutcome.Succeeded) return
        when (operation) {
            RendererLifecycleOperation.NotifyGpuObjectsGone -> forgetWithoutDeleting()
            RendererLifecycleOperation.AdoptCurrentRenderContext -> {
                forgetWithoutDeleting()
                adoptedContext = pendingContext
                profile = pendingProfile
            }

            RendererLifecycleOperation.CloseRenderer -> {
                forgetWithoutDeleting()
                adoptedContext = null
                profile = null
            }

            else -> Unit
        }
        if (snapshot.ownerState == RendererOwnerState.AWAITING_CONTEXT_ADOPTION) {
            adoptedContext = null
        }
    }

    /**
     * Losing the context is not freeing (ADRs 0007, 0015): forgets every live and queued GL
     * object handle plus every cached compiled program, without issuing a single GL call, because
     * a replacement context cannot delete handles compiled or allocated against the lost one.
     *
     * This runs on every path that leaves [adoptedContext] behind: GPU object loss, a fresh
     * adoption (before recording the new identity and profile), and renderer close. The program
     * cache is included deliberately, not only the object registry: [GlProgramCache]'s key carries
     * no shader dialect, and the dialect is a runtime property of the context, read by
     * [adoptRenderContext] on every adoption. Two successive adoptions on the same machine can
     * resolve to different dialects, so a program compiled under the previous adoption must never
     * survive into the next one — leaving it cached would hand a program compiled for one dialect
     * (say GLES) to a context now resolved to the other (say DESKTOP), a defect that would surface
     * as a wrong-looking or non-compiling shader far from this call site.
     */
    private fun forgetWithoutDeleting() {
        registry.forgetEverything()
        programs.forgetAll()
    }
}
