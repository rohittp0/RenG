package com.rohittp.reng

import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.renGFailure
import com.rohittp.reng.internal.resourceReloadedAfterFreeDiagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsAndFailuresTest {
    @Test
    fun publicDiagnosticEnumsHaveTheSpecifiedMembersInOrder() {
        assertEquals(
            listOf(
                "CONFIGURATION",
                "FRAME_PLANNING",
                "FRAME_PREPARATION",
                "RESOURCE_LOOKUP",
                "STORE_READ",
                "STORE_VALIDATION",
                "TRANSPORT",
                "TRANSPORT_VALIDATION",
                "STORE_WRITE",
                "RESOURCE_DECODING",
                "RESOURCE_PARSING",
                "SHADER_COMPILATION",
                "GPU_RESOURCE",
                "RENDER_TARGET",
                "DRAW",
                "RESOURCE_FREE",
                "RENDERER_CLOSE",
                "CONTEXT_ADOPTION",
            ),
            PipelineStage.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "INVALID_VALUE",
                "RESOURCE_LIMIT_EXCEEDED",
                "UNSUPPORTED_PROJECTION_MODE",
                "PREPARATION_ORDER_VIOLATION",
                "PREPARATION_IN_PROGRESS",
                "RENDERER_CLOSED",
                "RENDER_CONTEXT_ADOPTION_REQUIRED",
                "NO_CURRENT_RENDER_CONTEXT",
                "DIFFERENT_CURRENT_RENDER_CONTEXT",
                "UNSUPPORTED_RENDER_CONTEXT",
                "FOREIGN_PREPARED_FRAME",
                "PREPARED_FRAME_CLOSED",
                "FOREIGN_RENDER_TARGET",
                "STALE_RENDER_TARGET",
                "INVALID_RENDER_TARGET",
                "AMBIGUOUS_RESOURCE_ROUTE",
                "RESOURCE_UNAVAILABLE",
                "TRANSPORT_EXECUTION_FAILED",
                "INVALID_TRANSPORT_RESPONSE",
                "STORE_READ_FAILED",
                "STORE_WRITE_FAILED",
                "STORE_INTEGRITY_FAILED",
                "RESOURCE_DECODE_FAILED",
                "RESOURCE_PARSE_FAILED",
                "UNSUPPORTED_RESOURCE_FEATURE",
                "SHADER_COMPILE_FAILED",
                "SHADER_LINK_FAILED",
                "GPU_OPERATION_FAILED",
                "IDENTITY_COLLISION",
            ),
            RenGErrorCode.entries.map { it.name },
        )
        assertEquals(listOf("INFO", "WARNING", "ERROR"), DiagnosticSeverity.entries.map { it.name })
        assertEquals(
            listOf("RESOURCE_RELOADED_AFTER_FREE", "FAILURE_CONTEXT"),
            DiagnosticCode.entries.map { it.name },
        )
    }

    @Test
    fun diagnosticFieldAllowlistHasExactlyTheSpecifiedWireNames() {
        assertEquals(
            listOf(
                "plans",
                "frameIndex",
                "projectionMode",
                "camera.latitude",
                "camera.unwrappedLongitude",
                "mapPosition.latitude",
                "mapPosition.unwrappedLongitude",
                "mapPosition.altitude",
                "screenPosition.x",
                "screenPosition.y",
                "placement.scale",
                "geometry.latitude",
                "geometry.unwrappedLongitude",
                "geometry.altitude",
                "basemapTileInstances",
                "responseBodyBytes",
                "resource",
                "frameIdentity",
                "animationSelector",
                "shaderPair",
                "renderTarget",
            ),
            DiagnosticField.entries.map { it.wireName },
        )

        assertFailsWith<IllegalArgumentException> {
            Diagnostic(
                code = DiagnosticCode.FAILURE_CONTEXT,
                severity = DiagnosticSeverity.ERROR,
                stage = PipelineStage.FRAME_PLANNING,
                fieldName = "locator=https://example.test/signed-url",
            )
        }
    }

    @Test
    fun failureMessageCauseAndDiagnosticAreFixed() {
        val diagnostic = failureContextDiagnostic(
            stage = PipelineStage.FRAME_PLANNING,
            fieldName = DiagnosticField.FRAME_INDEX,
        )
        val failure = renGFailure(
            RenGErrorCode.PREPARATION_ORDER_VIOLATION,
            PipelineStage.FRAME_PLANNING,
            diagnostic,
        )

        assertEquals("RenG failure: PREPARATION_ORDER_VIOLATION at FRAME_PLANNING", failure.message)
        assertNull(failure.cause)
        assertEquals(listOf(diagnostic), failure.diagnostics)
    }

    @Test
    fun failureFactoryAcceptsEveryErrorCodeAtAnAllowlistedStage() {
        val externalKey = externalKey('a')
        val geometryProgramKey = ResourceKey(ResourceKind.GEOMETRY_PROGRAM, stableId('b'), null)
        val cases = listOf(
            FactoryCase(RenGErrorCode.INVALID_VALUE, PipelineStage.CONTEXT_ADOPTION),
            FactoryCase(
                RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
                PipelineStage.FRAME_PLANNING,
                failureContextDiagnostic(
                    stage = PipelineStage.FRAME_PLANNING,
                    fieldName = DiagnosticField.PLANS,
                    limit = 1L,
                    actual = 2L,
                ),
            ),
            FactoryCase(
                RenGErrorCode.UNSUPPORTED_PROJECTION_MODE,
                PipelineStage.FRAME_PLANNING,
                failureContextDiagnostic(PipelineStage.FRAME_PLANNING, DiagnosticField.PROJECTION_MODE),
            ),
            FactoryCase(
                RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                PipelineStage.FRAME_PLANNING,
                failureContextDiagnostic(PipelineStage.FRAME_PLANNING, DiagnosticField.FRAME_INDEX),
            ),
            FactoryCase(RenGErrorCode.PREPARATION_IN_PROGRESS, PipelineStage.FRAME_PREPARATION),
            FactoryCase(RenGErrorCode.RENDERER_CLOSED, PipelineStage.FRAME_PREPARATION),
            FactoryCase(RenGErrorCode.RENDER_CONTEXT_ADOPTION_REQUIRED, PipelineStage.DRAW),
            FactoryCase(RenGErrorCode.NO_CURRENT_RENDER_CONTEXT, PipelineStage.CONTEXT_ADOPTION),
            FactoryCase(RenGErrorCode.DIFFERENT_CURRENT_RENDER_CONTEXT, PipelineStage.DRAW),
            FactoryCase(RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT, PipelineStage.CONFIGURATION),
            FactoryCase(RenGErrorCode.FOREIGN_PREPARED_FRAME, PipelineStage.DRAW),
            FactoryCase(RenGErrorCode.PREPARED_FRAME_CLOSED, PipelineStage.DRAW),
            FactoryCase(RenGErrorCode.FOREIGN_RENDER_TARGET, PipelineStage.RENDER_TARGET),
            FactoryCase(RenGErrorCode.STALE_RENDER_TARGET, PipelineStage.RENDER_TARGET),
            FactoryCase(
                RenGErrorCode.INVALID_RENDER_TARGET,
                PipelineStage.RENDER_TARGET,
                failureContextDiagnostic(PipelineStage.RENDER_TARGET, DiagnosticField.RENDER_TARGET),
            ),
            FactoryCase(
                RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
                PipelineStage.RESOURCE_LOOKUP,
                failureContextDiagnostic(PipelineStage.RESOURCE_LOOKUP, DiagnosticField.RESOURCE),
            ),
            FactoryCase(
                RenGErrorCode.RESOURCE_UNAVAILABLE,
                PipelineStage.RESOURCE_LOOKUP,
                failureContextDiagnostic(
                    PipelineStage.RESOURCE_LOOKUP,
                    DiagnosticField.RESOURCE,
                    ResourceClass.STICKER_IMAGE,
                    externalKey,
                ),
            ),
            FactoryCase(
                RenGErrorCode.TRANSPORT_EXECUTION_FAILED,
                PipelineStage.TRANSPORT,
                failureContextDiagnostic(
                    PipelineStage.TRANSPORT,
                    resourceClass = ResourceClass.STICKER_IMAGE,
                    resourceKey = externalKey,
                ),
            ),
            FactoryCase(
                RenGErrorCode.INVALID_TRANSPORT_RESPONSE,
                PipelineStage.TRANSPORT_VALIDATION,
                failureContextDiagnostic(
                    PipelineStage.TRANSPORT_VALIDATION,
                    resourceClass = ResourceClass.STICKER_IMAGE,
                    resourceKey = externalKey,
                    statusCode = 304,
                ),
            ),
            FactoryCase(
                RenGErrorCode.STORE_READ_FAILED,
                PipelineStage.STORE_READ,
                failureContextDiagnostic(
                    PipelineStage.STORE_READ,
                    resourceClass = ResourceClass.STICKER_IMAGE,
                    resourceKey = externalKey,
                ),
            ),
            FactoryCase(
                RenGErrorCode.STORE_WRITE_FAILED,
                PipelineStage.STORE_WRITE,
                failureContextDiagnostic(
                    PipelineStage.STORE_WRITE,
                    resourceClass = ResourceClass.STICKER_IMAGE,
                    resourceKey = externalKey,
                ),
            ),
            FactoryCase(
                RenGErrorCode.STORE_INTEGRITY_FAILED,
                PipelineStage.STORE_VALIDATION,
                failureContextDiagnostic(
                    PipelineStage.STORE_VALIDATION,
                    DiagnosticField.RESOURCE,
                    ResourceClass.STICKER_IMAGE,
                    externalKey,
                ),
            ),
            FactoryCase(
                RenGErrorCode.RESOURCE_DECODE_FAILED,
                PipelineStage.RESOURCE_DECODING,
                failureContextDiagnostic(
                    PipelineStage.RESOURCE_DECODING,
                    DiagnosticField.RESOURCE,
                    ResourceClass.STICKER_IMAGE,
                    externalKey,
                ),
            ),
            FactoryCase(
                RenGErrorCode.RESOURCE_PARSE_FAILED,
                PipelineStage.RESOURCE_PARSING,
                failureContextDiagnostic(
                    PipelineStage.RESOURCE_PARSING,
                    DiagnosticField.ANIMATION_SELECTOR,
                    ResourceClass.STICKER_IMAGE,
                    externalKey,
                ),
            ),
            FactoryCase(
                RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE,
                PipelineStage.RESOURCE_PARSING,
                failureContextDiagnostic(
                    PipelineStage.RESOURCE_PARSING,
                    DiagnosticField.RESOURCE,
                    ResourceClass.STICKER_IMAGE,
                    externalKey,
                ),
            ),
            FactoryCase(
                RenGErrorCode.SHADER_COMPILE_FAILED,
                PipelineStage.SHADER_COMPILATION,
                failureContextDiagnostic(
                    PipelineStage.SHADER_COMPILATION,
                    DiagnosticField.SHADER_PAIR,
                    resourceKey = geometryProgramKey,
                ),
            ),
            FactoryCase(
                RenGErrorCode.SHADER_LINK_FAILED,
                PipelineStage.SHADER_COMPILATION,
                failureContextDiagnostic(
                    PipelineStage.SHADER_COMPILATION,
                    DiagnosticField.SHADER_PAIR,
                    resourceKey = geometryProgramKey,
                ),
            ),
            FactoryCase(
                RenGErrorCode.GPU_OPERATION_FAILED,
                PipelineStage.GPU_RESOURCE,
                failureContextDiagnostic(PipelineStage.GPU_RESOURCE),
            ),
            FactoryCase(
                RenGErrorCode.IDENTITY_COLLISION,
                PipelineStage.FRAME_PLANNING,
                failureContextDiagnostic(PipelineStage.FRAME_PLANNING, DiagnosticField.FRAME_IDENTITY),
            ),
        )

        assertEquals(RenGErrorCode.entries.size, cases.size)
        cases.forEach { case ->
            val failure = renGFailure(case.code, case.stage, case.diagnostic)
            assertEquals(case.code, failure.code)
            assertEquals(case.stage, failure.stage)
            assertEquals(case.diagnostic?.let(::listOf) ?: emptyList(), failure.diagnostics)
        }
    }

    @Test
    fun factoryRejectsNonallowlistedFailureCombinationsAndUnmatchedContext() {
        val externalKey = externalKey('c')
        val plansDiagnostic = failureContextDiagnostic(
            stage = PipelineStage.FRAME_PLANNING,
            fieldName = DiagnosticField.PLANS,
        )
        val resourceDiagnostic = failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_LOOKUP,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = ResourceClass.STICKER_IMAGE,
            resourceKey = externalKey,
        )

        assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(PipelineStage.RESOURCE_LOOKUP, DiagnosticField.PLANS)
        }
        assertFailsWith<IllegalArgumentException> {
            renGFailure(
                RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                PipelineStage.FRAME_PLANNING,
                plansDiagnostic,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            renGFailure(
                RenGErrorCode.RESOURCE_UNAVAILABLE,
                PipelineStage.RESOURCE_LOOKUP,
                failureContextDiagnostic(PipelineStage.RESOURCE_LOOKUP, DiagnosticField.RESOURCE),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            renGFailure(
                RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                PipelineStage.FRAME_PLANNING,
                resourceDiagnostic,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            renGFailure(RenGErrorCode.RENDERER_CLOSED, PipelineStage.FRAME_PREPARATION, plansDiagnostic)
        }
    }

    @Test
    fun diagnosticValidatesStatusLimitsAndEstablishedResourceIdentity() {
        val externalKey = externalKey('d')
        val geometryProgramKey = ResourceKey(ResourceKind.GEOMETRY_PROGRAM, stableId('e'), null)

        assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(PipelineStage.TRANSPORT, statusCode = 200)
        }
        assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(PipelineStage.FRAME_PLANNING, limit = 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(PipelineStage.FRAME_PLANNING, actual = 2L)
        }
        assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(
                PipelineStage.RESOURCE_LOOKUP,
                resourceClass = ResourceClass.STICKER_IMAGE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(
                PipelineStage.RESOURCE_LOOKUP,
                resourceClass = ResourceClass.MODEL_GLB,
                resourceKey = externalKey,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            failureContextDiagnostic(
                PipelineStage.RESOURCE_LOOKUP,
                resourceClass = ResourceClass.STICKER_IMAGE,
                resourceKey = geometryProgramKey,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            renGFailure(
                RenGErrorCode.INVALID_TRANSPORT_RESPONSE,
                PipelineStage.TRANSPORT_VALIDATION,
                failureContextDiagnostic(
                    PipelineStage.TRANSPORT_VALIDATION,
                    resourceClass = ResourceClass.STICKER_IMAGE,
                    resourceKey = externalKey,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            renGFailure(
                RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
                PipelineStage.FRAME_PLANNING,
                failureContextDiagnostic(
                    PipelineStage.FRAME_PLANNING,
                    DiagnosticField.PLANS,
                    limit = 2L,
                    actual = 1L,
                ),
            )
        }
    }

    @Test
    fun reloadDiagnosticHasStableWarningShapeAndSafeStructuralText() {
        val key = externalKey('f')
        val diagnostic = resourceReloadedAfterFreeDiagnostic(key)

        assertEquals(DiagnosticCode.RESOURCE_RELOADED_AFTER_FREE, diagnostic.code)
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
        assertEquals(PipelineStage.RESOURCE_LOOKUP, diagnostic.stage)
        assertEquals(null, diagnostic.fieldName)
        assertEquals(ResourceClass.STICKER_IMAGE, diagnostic.resourceClass)
        assertEquals(key, diagnostic.resourceKey)
        assertEquals(null, diagnostic.statusCode)
        assertEquals(null, diagnostic.limit)
        assertEquals(null, diagnostic.actual)
        assertEquals(diagnostic, resourceReloadedAfterFreeDiagnostic(externalKey('f')))
        assertEquals(diagnostic.hashCode(), resourceReloadedAfterFreeDiagnostic(externalKey('f')).hashCode())
        assertRedacted(diagnostic.toString(), key.stableId)

        assertFailsWith<IllegalArgumentException> {
            Diagnostic(
                code = DiagnosticCode.RESOURCE_RELOADED_AFTER_FREE,
                severity = DiagnosticSeverity.WARNING,
                stage = PipelineStage.RESOURCE_LOOKUP,
                resourceClass = ResourceClass.STICKER_IMAGE,
            )
        }
    }

    @Test
    fun exceptionSnapshotsAtMostOneDiagnosticAndUsesIdentityEquality() {
        val diagnostic = failureContextDiagnostic(
            PipelineStage.FRAME_PLANNING,
            DiagnosticField.FRAME_INDEX,
        )
        val supplied = mutableListOf(diagnostic)
        val first = RenGException(
            RenGErrorCode.PREPARATION_ORDER_VIOLATION,
            PipelineStage.FRAME_PLANNING,
            supplied,
        )
        supplied.clear()
        val returned = first.diagnostics
        assertFalse(returned === first.diagnostics)
        assertTrue(returned is MutableList<Diagnostic>)
        returned.clear()
        assertEquals(listOf(diagnostic), first.diagnostics)
        assertNotEquals(
            first,
            RenGException(
                RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                PipelineStage.FRAME_PLANNING,
                listOf(diagnostic),
            ),
        )
        assertTrue(first === first)
        assertEquals(emptyList(), RenGException(RenGErrorCode.RENDERER_CLOSED, PipelineStage.DRAW).diagnostics)
        assertFailsWith<IllegalArgumentException> {
            RenGException(
                RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                PipelineStage.FRAME_PLANNING,
                listOf(diagnostic, diagnostic),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RenGException(
                RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                PipelineStage.FRAME_PREPARATION,
                listOf(diagnostic),
            )
        }
    }

    @Test
    fun sinkNoneAndFailureDescriptorsRetainOnlySanitizedContext() {
        DiagnosticSink.None.emit(
            resourceReloadedAfterFreeDiagnostic(externalKey('0')),
        )

        val diagnostic = failureContextDiagnostic(
            PipelineStage.FRAME_PLANNING,
            DiagnosticField.FRAME_INDEX,
        )
        val descriptor = FailureDescriptor(
            RenGErrorCode.PREPARATION_ORDER_VIOLATION,
            PipelineStage.FRAME_PLANNING,
            diagnostic,
        )
        assertEquals(
            descriptor,
            FailureDescriptor(
                RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                PipelineStage.FRAME_PLANNING,
                diagnostic,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            FailureDescriptor(
                RenGErrorCode.PREPARATION_ORDER_VIOLATION,
                PipelineStage.FRAME_PREPARATION,
                diagnostic,
            )
        }
    }

    @Test
    fun failuresNeverExposeSensitiveCallerOrAdapterText() {
        val locator = ResourceLocator("https://example.test/assets/model.glb?signature=locator-secret")
        val shaderSource = "shader-secret"
        val validator = "validator-secret"
        val metadata = "metadata-secret"
        val adapterMessage = "adapter-secret"
        val failure = renGFailure(
            RenGErrorCode.RESOURCE_UNAVAILABLE,
            PipelineStage.RESOURCE_LOOKUP,
            failureContextDiagnostic(
                PipelineStage.RESOURCE_LOOKUP,
                DiagnosticField.RESOURCE,
                ResourceClass.STICKER_IMAGE,
                externalKey('1'),
            ),
        )

        assertNull(failure.cause)
        assertRedacted(
            failure.toString(),
            locator.value,
            shaderSource,
            validator,
            metadata,
            adapterMessage,
            externalKey('1').stableId,
        )
        assertRedacted(
            failure.diagnostics.single().toString(),
            locator.value,
            shaderSource,
            validator,
            metadata,
            adapterMessage,
            externalKey('1').stableId,
        )
    }

    private data class FactoryCase(
        val code: RenGErrorCode,
        val stage: PipelineStage,
        val diagnostic: Diagnostic? = null,
    )

    private fun externalKey(stableIdCharacter: Char): ResourceKey =
        ResourceKey(ResourceKind.EXTERNAL, stableId(stableIdCharacter), ResourceClass.STICKER_IMAGE)

    private fun stableId(character: Char): String = character.toString().repeat(64)

    private fun assertRedacted(text: String, vararg sensitiveValues: String) {
        sensitiveValues.forEach { sensitiveValue ->
            assertFalse(text.contains(sensitiveValue), "text leaked $sensitiveValue: $text")
        }
    }
}
