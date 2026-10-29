package com.rohittp.reng

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic

/**
 * A rendered basemap tile that will not decode, named by the tile it is about.
 *
 * Reported at [PipelineStage.DRAW] rather than at `RESOURCE_DECODING` because that is genuinely where
 * it happens -- see [RenGRenderer.resolveGroundTiles] for why the decode is deferred to the draw -- and
 * with `RESOURCE_DECODE_FAILED` rather than `GPU_OPERATION_FAILED` because the fault is in the bytes or
 * in `ResourceLimits.maximumDecodedImageBytes`, not in the caller's GL state.
 */
internal fun basemapTileDecodeFailure(key: ResourceKey): FailureDescriptor = FailureDescriptor(
    code = RenGErrorCode.RESOURCE_DECODE_FAILED,
    stage = PipelineStage.DRAW,
    diagnostic = failureContextDiagnostic(
        stage = PipelineStage.DRAW,
        fieldName = DiagnosticField.RESOURCE,
        resourceKey = key,
    ),
)
