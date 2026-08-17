package com.rohittp.reng.internal.failure

import com.rohittp.reng.Diagnostic
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.requireAllowedFailureContext

internal data class FailureDescriptor(
    val code: RenGErrorCode,
    val stage: PipelineStage,
    val diagnostic: Diagnostic? = null,
) {
    init {
        requireAllowedFailureContext(code, stage, diagnostic)
    }
}
