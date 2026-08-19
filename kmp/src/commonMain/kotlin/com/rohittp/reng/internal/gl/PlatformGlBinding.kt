package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.failure.FailureDescriptor

internal sealed interface GlBindingResult {
    data class Bound(val binding: GlBinding) : GlBindingResult

    data class Unsupported(val failure: FailureDescriptor) : GlBindingResult
}

internal fun unsupportedRenderContext(): GlBindingResult.Unsupported =
    GlBindingResult.Unsupported(
        FailureDescriptor(
            code = RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT,
            stage = PipelineStage.CONTEXT_ADOPTION,
        ),
    )

internal expect fun openPlatformGlBinding(): GlBindingResult
