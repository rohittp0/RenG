package com.rohittp.reng

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failureContextDiagnostic

/**
 * A well-formed GLB asking for something outside ADR 0021's supported subset. Distinct from
 * [modelParseFailure] because the two send a consumer to two different fixes — re-export the asset
 * against RenG's subset, rather than repair a broken file.
 */
internal fun modelUnsupportedFailure(key: ResourceKey): RenGException = RenGException(
    code = RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE,
    stage = PipelineStage.RESOURCE_PARSING,
    diagnostics = listOf(
        failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_PARSING,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = ResourceClass.MODEL_GLB,
            resourceKey = key,
        ),
    ),
)
