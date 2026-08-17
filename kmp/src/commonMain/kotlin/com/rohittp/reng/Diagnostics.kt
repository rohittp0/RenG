package com.rohittp.reng

import com.rohittp.reng.internal.isAllowedDiagnosticFieldName
import com.rohittp.reng.internal.isAllowedDiagnosticFieldStage

public enum class PipelineStage {
    CONFIGURATION,
    FRAME_PLANNING,
    FRAME_PREPARATION,
    RESOURCE_LOOKUP,
    STORE_READ,
    STORE_VALIDATION,
    TRANSPORT,
    TRANSPORT_VALIDATION,
    STORE_WRITE,
    RESOURCE_DECODING,
    RESOURCE_PARSING,
    SHADER_COMPILATION,
    GPU_RESOURCE,
    RENDER_TARGET,
    DRAW,
    RESOURCE_FREE,
    RENDERER_CLOSE,
    CONTEXT_ADOPTION,
}

public enum class DiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

public enum class DiagnosticCode {
    RESOURCE_RELOADED_AFTER_FREE,
    FAILURE_CONTEXT,
}

@ConsistentCopyVisibility
public data class Diagnostic internal constructor(
    public val code: DiagnosticCode,
    public val severity: DiagnosticSeverity,
    public val stage: PipelineStage,
    public val fieldName: String? = null,
    public val resourceClass: ResourceClass? = null,
    public val resourceKey: ResourceKey? = null,
    public val statusCode: Int? = null,
    public val limit: Long? = null,
    public val actual: Long? = null,
) {
    init {
        require(fieldName == null || isAllowedDiagnosticFieldName(fieldName)) {
            "fieldName is not allowlisted"
        }
        require(fieldName == null || isAllowedDiagnosticFieldStage(fieldName, stage)) {
            "fieldName is not valid at this pipeline stage"
        }
        require(resourceClass == resourceKey?.resourceClass) {
            "resource class requires its established resource key"
        }
        require(statusCode == null || stage == PipelineStage.TRANSPORT_VALIDATION) {
            "statusCode is only valid during transport validation"
        }
        require((limit == null) == (actual == null)) {
            "limit and actual must be present together"
        }
        require(limit == null || (limit >= 0L && actual!! >= 0L)) {
            "limit and actual must be non-negative"
        }
        when (code) {
            DiagnosticCode.RESOURCE_RELOADED_AFTER_FREE -> {
                require(severity == DiagnosticSeverity.WARNING) {
                    "reload diagnostics are warnings"
                }
                require(stage == PipelineStage.RESOURCE_LOOKUP) {
                    "reload diagnostics occur during resource lookup"
                }
                require(fieldName == null && resourceKey != null && statusCode == null && limit == null) {
                    "reload diagnostics contain only an established resource identity"
                }
            }

            DiagnosticCode.FAILURE_CONTEXT -> {
                require(severity == DiagnosticSeverity.ERROR) {
                    "failure context diagnostics are errors"
                }
            }
        }
    }
}

public fun interface DiagnosticSink {
    public fun emit(diagnostic: Diagnostic)

    public companion object {
        public val None: DiagnosticSink = DiagnosticSink { }
    }
}
