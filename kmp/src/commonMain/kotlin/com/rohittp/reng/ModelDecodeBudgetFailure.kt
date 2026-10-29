package com.rohittp.reng

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failureContextDiagnostic

/**
 * A drawable GLB whose expanded CPU form does not fit `ResourceLimits.maximumDecodedImageBytes`.
 * `RESOURCE_DECODE_FAILED` at `RESOURCE_DECODING`, the same code at the same stage a sticker PNG
 * overrunning that identical budget already reports — the fault is in the caller's own limit or in the
 * asset's size, and reporting it as a parse failure would send them to look at the file's structure.
 */
internal fun modelDecodeBudgetFailure(key: ResourceKey): RenGException = RenGException(
    code = RenGErrorCode.RESOURCE_DECODE_FAILED,
    stage = PipelineStage.RESOURCE_DECODING,
    diagnostics = listOf(
        failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_DECODING,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = ResourceClass.MODEL_GLB,
            resourceKey = key,
        ),
    ),
)
