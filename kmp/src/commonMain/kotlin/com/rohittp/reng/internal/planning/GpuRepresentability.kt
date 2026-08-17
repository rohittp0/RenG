package com.rohittp.reng.internal.planning

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic

internal fun isGpuRepresentable(value: Double): Boolean = value.isFinite() && value.toFloat().isFinite()

internal fun gpuRepresentabilityFailure(field: DiagnosticField): SpatialOutcome.Failure =
    SpatialOutcome.Failure(
        FailureDescriptor(
            code = RenGErrorCode.INVALID_VALUE,
            stage = PipelineStage.FRAME_PLANNING,
            diagnostic = failureContextDiagnostic(
                stage = PipelineStage.FRAME_PLANNING,
                fieldName = field,
            ),
        ),
    )
