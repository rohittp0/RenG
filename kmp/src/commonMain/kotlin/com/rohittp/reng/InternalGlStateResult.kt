package com.rohittp.reng

import com.rohittp.reng.internal.failure.FailureDescriptor

internal sealed interface InternalGlStateResult {
    data class Created(val state: InternalGlState) : InternalGlStateResult

    data class Failed(val failure: FailureDescriptor) : InternalGlStateResult
}
