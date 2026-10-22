package com.rohittp.reng

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.model.ModelDecodeResult

/**
 * A GLB whose bytes are not the glTF 2.0 binary RenG can draw, named by the model it is about.
 *
 * Reachable for two disjoint reasons. `PARSE_GLB` and `VALIDATE_GLB_FEATURES` already refused a
 * container or structural fault during acquisition, so what survives to `decodeModel` and still reports
 * [ModelDecodeResult.Malformed] is precisely the class of fault those gates cannot see — `parseGltf`
 * receives the BIN chunk's *length* and never its bytes, so an accessor overrunning the chunk actually
 * delivered, an index naming no vertex, or an attribute disagreeing with `POSITION`'s count all arrive
 * here. Unreadable animation data and an unposeable rig report the same way, and for the same reason:
 * both are only knowable from those same bytes.
 */
internal fun modelParseFailure(key: ResourceKey): RenGException = RenGException(
    code = RenGErrorCode.RESOURCE_PARSE_FAILED,
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
