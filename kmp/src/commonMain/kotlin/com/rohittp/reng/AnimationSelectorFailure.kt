package com.rohittp.reng

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failureContextDiagnostic

/**
 * `CONTEXT.md`'s two selector rules, reported as one code with its own field: "Preparation rejects a
 * missing or out-of-range selector, or different selectors that resolve to the same animation."
 *
 * `fieldName = animationSelector` rather than `resource` because the fault is in the [FramePlan]'s own
 * [AnimationSelector] and not in the GLB — the model is perfectly good, and it is named here only so a
 * consumer with several models knows which one's catalogue the selector missed. The field is
 * allowlisted at [PipelineStage.RESOURCE_PARSING] and nowhere earlier, which is the allowlist recording
 * an ordering fact: nothing can resolve a selector before the catalogue it resolves against exists.
 */
internal fun animationSelectorFailure(key: ResourceKey): RenGException = RenGException(
    code = RenGErrorCode.RESOURCE_PARSE_FAILED,
    stage = PipelineStage.RESOURCE_PARSING,
    diagnostics = listOf(
        failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_PARSING,
            fieldName = DiagnosticField.ANIMATION_SELECTOR,
            resourceClass = ResourceClass.MODEL_GLB,
            resourceKey = key,
        ),
    ),
)
