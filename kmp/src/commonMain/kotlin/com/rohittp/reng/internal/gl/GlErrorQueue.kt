package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic

internal object GlErrorQueue {
    internal const val MAXIMUM_DRAIN_ITERATIONS: Int = 32

    /**
     * Drains flags left by the consumer before RenG's work begins and returns the first one found.
     *
     * RenG consumes the caller's GL error queue. `glGetError` is destructive and no flag can be
     * pushed back, so this is a declared exception to the restore guarantee rather than an
     * oversight. A flag found here belongs to the consumer and is never converted into a RenG
     * failure.
     */
    internal fun drainOnEntry(binding: GlBinding): Int = drain(binding)

    /** Drains flags provoked by RenG's own work; a non-zero result is RenG's failure to report. */
    internal fun firstOwnError(binding: GlBinding): Int = drain(binding)

    private fun drain(binding: GlBinding): Int {
        var first = GL_NO_ERROR
        var iterations = 0
        while (iterations < MAXIMUM_DRAIN_ITERATIONS) {
            val flag = binding.getError()
            if (flag == GL_NO_ERROR) break
            if (first == GL_NO_ERROR) first = flag
            iterations += 1
        }
        return first
    }
}

internal fun glOperationFailure(
    stage: PipelineStage,
    resourceKey: ResourceKey?,
): FailureDescriptor = FailureDescriptor(
    code = RenGErrorCode.GPU_OPERATION_FAILED,
    stage = stage,
    diagnostic = failureContextDiagnostic(
        stage = stage,
        resourceClass = resourceKey?.resourceClass,
        resourceKey = resourceKey,
    ),
)
