package com.rohittp.reng.internal.failure

import com.rohittp.reng.Diagnostic
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.RenGException
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

/**
 * Renders this internal failure as the one public failure shape a consumer ever sees. Every
 * internal GL/resource-layer function that can fail already reports a [FailureDescriptor] rather
 * than throwing directly, so this is the single seam the renderer's public methods convert through
 * on their way out — never re-deriving a code or stage, only re-shaping what was already decided.
 */
internal fun FailureDescriptor.toException(): RenGException =
    RenGException(code = code, stage = stage, diagnostics = diagnostic?.let(::listOf) ?: emptyList())
