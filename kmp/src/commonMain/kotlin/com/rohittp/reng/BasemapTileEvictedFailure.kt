package com.rohittp.reng

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic

/**
 * A ground tile that was resident when the frame was prepared and gone by the time it was drawn.
 *
 * Distinct from a decode failure on purpose: nothing failed to decode, and reporting it as a decode
 * would send whoever reads the diagnostic looking at the bytes, which are fine and simply gone.
 * `RESOURCE_UNAVAILABLE` is the existing code that says what is true: the resource this frame needs is
 * not there. No new enum value, so the published ABI is untouched — a capacity condition pointing at
 * `maximumResidentGpuTextureBytes` does not warrant widening a public enum.
 */
internal fun basemapTileEvictedFailure(key: ResourceKey): FailureDescriptor = FailureDescriptor(
    code = RenGErrorCode.RESOURCE_UNAVAILABLE,
    stage = PipelineStage.DRAW,
    diagnostic = failureContextDiagnostic(
        stage = PipelineStage.DRAW,
        fieldName = DiagnosticField.RESOURCE,
        resourceKey = key,
    ),
)
