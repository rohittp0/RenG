package com.rohittp.reng.internal.firewall

import com.rohittp.reng.DiagnosticCode
import com.rohittp.reng.DiagnosticSeverity
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.internal.failure.toException
import com.rohittp.rentile.BatchRenderException
import com.rohittp.rentile.ForeignPreparedBatchException
import com.rohittp.rentile.ForeignPreparedStyleException
import com.rohittp.rentile.InvalidTileIdException
import com.rohittp.rentile.PngEncodingException
import com.rohittp.rentile.PreparedBatchClosedException
import com.rohittp.rentile.RasterizationException
import com.rohittp.rentile.RasterizerClosedException
import com.rohittp.rentile.RentileErrorCode
import com.rohittp.rentile.RentileException
import com.rohittp.rentile.ResourceAcquisitionException
import com.rohittp.rentile.ResourceDecodeException
import com.rohittp.rentile.ResourceStoreException
import com.rohittp.rentile.SafetyLimitException
import com.rohittp.rentile.StylePreparationException
import com.rohittp.rentile.TileId
import com.rohittp.rentile.TileNotInPreparedBatchException
import com.rohittp.rentile.TileSubstitutionException
import com.rohittp.rentile.TileSubstitutionLimitException
import com.rohittp.rentile.TileSubstitutionStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import com.rohittp.rentile.PipelineStage as RentilePipelineStage
import com.rohittp.rentile.ResourceClass as RentileResourceClass

/**
 * The firewall's failure half. Every assertion here is a contract about what a consumer is allowed to
 * observe after Rentile fails, so each one is written against Rentile's real 0.2.0 exception surface --
 * the exemplars below are constructed with the exact constructor signatures Rentile publishes, so a
 * signature change in a future Rentile breaks this test's compilation rather than silently reclassifying.
 */
class EngineFailureClassificationTest {
    private val acquisitionDigest = "a".repeat(64)
    private val decodeDigest = "b".repeat(64)
    private val substitutionDigest = "c".repeat(64)
    private val credentialBearingMessage =
        "GET https://tiles.example.test/v1/0/0/0.pbf?access_token=SIGNED-SECRET-9f3 failed"

    private fun acquisitionFailure(
        resourceClass: RentileResourceClass = RentileResourceClass.VECTOR_TILE,
        sanitizedResourceId: String = acquisitionDigest,
        message: String = credentialBearingMessage,
    ): ResourceAcquisitionException = ResourceAcquisitionException(
        message = message,
        resourceClass = resourceClass,
        sanitizedResourceId = sanitizedResourceId,
        statusCode = 403,
        retryAfterMillis = 1_000L,
    )

    private fun decodeFailure(
        resourceClass: RentileResourceClass = RentileResourceClass.RASTER_TILE,
        sanitizedResourceId: String = decodeDigest,
        message: String = credentialBearingMessage,
    ): ResourceDecodeException = ResourceDecodeException(
        message = message,
        resourceClass = resourceClass,
        sanitizedResourceId = sanitizedResourceId,
    )

    /**
     * One exemplar per [RentileErrorCode], paired with the exact triple the firewall must produce. The
     * three wrapping codes carry an identity-bearing primary failure precisely so that "unwrapped, then
     * classified" is distinguishable from "collapsed to `BASEMAP_RENDER_FAILED`" -- the two would be
     * indistinguishable if the exemplars wrapped an opaque failure.
     */
    private fun expectations(): Map<RentileErrorCode, ExpectedClassification> = mapOf(
        RentileErrorCode.STYLE_PREPARATION_FAILED to ExpectedClassification(
            failure = StylePreparationException(credentialBearingMessage),
            code = RenGErrorCode.RESOURCE_PARSE_FAILED,
            stage = PipelineStage.RESOURCE_PARSING,
            diagnosticPresent = true,
        ),
        RentileErrorCode.RESOURCE_ACQUISITION_FAILED to ExpectedClassification(
            failure = acquisitionFailure(),
            code = RenGErrorCode.RESOURCE_UNAVAILABLE,
            stage = PipelineStage.RESOURCE_LOOKUP,
            diagnosticPresent = true,
        ),
        RentileErrorCode.RESOURCE_DECODE_FAILED to ExpectedClassification(
            failure = decodeFailure(),
            code = RenGErrorCode.RESOURCE_DECODE_FAILED,
            stage = PipelineStage.RESOURCE_DECODING,
            diagnosticPresent = true,
        ),
        RentileErrorCode.RESOURCE_STORE_FAILED to ExpectedClassification(
            failure = ResourceStoreException(credentialBearingMessage),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.SAFETY_LIMIT_EXCEEDED to ExpectedClassification(
            failure = SafetyLimitException(
                message = credentialBearingMessage,
                limitName = "maximumTiles",
                limit = 4L,
                observed = 9L,
                stage = RentilePipelineStage.RESOURCE_PLANNING,
            ),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.RASTERIZATION_FAILED to ExpectedClassification(
            failure = RasterizationException(credentialBearingMessage),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.PNG_ENCODING_FAILED to ExpectedClassification(
            failure = PngEncodingException(credentialBearingMessage),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.PREPARED_BATCH_CLOSED to ExpectedClassification(
            failure = PreparedBatchClosedException(),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.RASTERIZER_CLOSED to ExpectedClassification(
            failure = RasterizerClosedException(),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.FOREIGN_PREPARED_STYLE to ExpectedClassification(
            failure = ForeignPreparedStyleException(),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.FOREIGN_PREPARED_BATCH to ExpectedClassification(
            failure = ForeignPreparedBatchException(),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.INVALID_TILE_ID to ExpectedClassification(
            failure = InvalidTileIdException(TileId(3, 1, 2)),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.TILE_NOT_IN_PREPARED_BATCH to ExpectedClassification(
            failure = TileNotInPreparedBatchException(TileId(3, 1, 2)),
            code = RenGErrorCode.BASEMAP_RENDER_FAILED,
            stage = PipelineStage.BASEMAP_RENDER,
            diagnosticPresent = false,
        ),
        RentileErrorCode.TILE_SUBSTITUTION_LIMIT_EXCEEDED to ExpectedClassification(
            failure = TileSubstitutionLimitException(
                maximumSubstitutedTiles = 1,
                requiredSubstitutedTiles = 4,
                primaryFailure = acquisitionFailure(),
                affectedTiles = listOf(TileId(3, 1, 2)),
            ),
            code = RenGErrorCode.RESOURCE_UNAVAILABLE,
            stage = PipelineStage.RESOURCE_LOOKUP,
            diagnosticPresent = true,
        ),
        RentileErrorCode.TILE_SUBSTITUTION_FAILED to ExpectedClassification(
            failure = TileSubstitutionException(
                tile = TileId(3, 1, 2),
                resourceClass = RentileResourceClass.VECTOR_TILE,
                sanitizedResourceId = substitutionDigest,
                attemptedStrategies = listOf(TileSubstitutionStrategy.ANCESTOR),
                primaryFailure = acquisitionFailure(),
                substitutionFailures = emptyList(),
            ),
            code = RenGErrorCode.RESOURCE_UNAVAILABLE,
            stage = PipelineStage.RESOURCE_LOOKUP,
            diagnosticPresent = true,
        ),
        RentileErrorCode.BATCH_RENDER_FAILED to ExpectedClassification(
            failure = BatchRenderException(
                message = credentialBearingMessage,
                primaryFailure = decodeFailure(),
            ),
            code = RenGErrorCode.RESOURCE_DECODE_FAILED,
            stage = PipelineStage.RESOURCE_DECODING,
            diagnosticPresent = true,
        ),
    )

    @Test
    fun classifiesEveryEngineErrorCodeItsOwnWay() {
        val expectations = expectations()
        assertEquals(
            RentileErrorCode.entries.toSet(),
            expectations.keys,
            "every RentileErrorCode must have an asserted classification; a code Rentile adds must fail here",
        )

        RentileErrorCode.entries.forEach { engineCode ->
            val expected = assertNotNull(expectations[engineCode])
            assertEquals(engineCode, expected.failure.code, "exemplar for $engineCode carries the wrong code")

            val descriptor = classifyEngineFailure(expected.failure)

            assertEquals(expected.code, descriptor.code, "$engineCode mapped to the wrong RenG code")
            assertEquals(expected.stage, descriptor.stage, "$engineCode mapped to the wrong pipeline stage")
            assertEquals(
                expected.diagnosticPresent,
                descriptor.diagnostic != null,
                "$engineCode produced the wrong diagnostic presence",
            )
        }
    }

    @Test
    fun carriesOnlyTheEngineIdentityForTheTwoClassesThatExposeOne() {
        val acquisition = assertNotNull(classifyEngineFailure(acquisitionFailure()).diagnostic)
        assertEquals(DiagnosticCode.FAILURE_CONTEXT, acquisition.code)
        assertEquals(DiagnosticSeverity.ERROR, acquisition.severity)
        assertEquals(PipelineStage.RESOURCE_LOOKUP, acquisition.stage)
        assertEquals("resource", acquisition.fieldName)
        assertEquals(ResourceClass.BASEMAP_VECTOR_TILE, acquisition.resourceClass)
        assertEquals(ResourceKind.EXTERNAL, assertNotNull(acquisition.resourceKey).kind)
        assertEquals(acquisitionDigest, assertNotNull(acquisition.resourceKey).stableId)
        assertNull(acquisition.statusCode, "RESOURCE_UNAVAILABLE at RESOURCE_LOOKUP forbids a status code")
        assertNull(acquisition.limit)
        assertNull(acquisition.actual)

        val decode = assertNotNull(classifyEngineFailure(decodeFailure()).diagnostic)
        assertEquals(PipelineStage.RESOURCE_DECODING, decode.stage)
        assertEquals("resource", decode.fieldName)
        assertEquals(ResourceClass.BASEMAP_RASTER_TILE, decode.resourceClass)
        assertEquals(ResourceKind.EXTERNAL, assertNotNull(decode.resourceKey).kind)
        assertEquals(decodeDigest, assertNotNull(decode.resourceKey).stableId)
        assertNull(decode.statusCode)
    }

    /**
     * A style preparation failure names no resource, because Rentile's one [StylePreparationException]
     * exposes none. It still carries a diagnostic: `RESOURCE_PARSE_FAILED` at `RESOURCE_PARSING` is a
     * `FailureRule.Context`, and every such rule requires one -- `IdentityRequirement.OPTIONAL_EXTERNAL`
     * makes the resource *identity* optional, not the diagnostic. This asserts the emptiest legal shape,
     * so a later change that started naming a resource here would have to justify where it came from.
     */
    @Test
    fun namesNoResourceForAStylePreparationFailure() {
        val descriptor = classifyEngineFailure(StylePreparationException(credentialBearingMessage))

        val diagnostic = assertNotNull(descriptor.diagnostic)
        assertEquals(DiagnosticCode.FAILURE_CONTEXT, diagnostic.code)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(PipelineStage.RESOURCE_PARSING, diagnostic.stage)
        assertEquals("resource", diagnostic.fieldName)
        assertNull(diagnostic.resourceKey)
        assertNull(diagnostic.resourceClass)
        assertNull(diagnostic.statusCode)
        assertNull(diagnostic.limit)
    }

    @Test
    fun mapsEveryEngineResourceClassOntoARenGResourceClass() {
        val mapped = RentileResourceClass.entries.associateWith { rengResourceClassOf(it) }

        assertEquals(
            emptyList(),
            mapped.filterValues { it == null }.keys.toList(),
            "every Rentile 0.2.0 resource class has a RenG counterpart; an unmapped one must fail closed",
        )
        assertEquals(
            RentileResourceClass.entries.size,
            mapped.values.toSet().size,
            "the engine-to-RenG resource class correspondence must stay injective",
        )
        assertEquals(ResourceClass.BASEMAP_STYLE, rengResourceClassOf(RentileResourceClass.STYLE))
        assertEquals(ResourceClass.BASEMAP_GEO_JSON, rengResourceClassOf(RentileResourceClass.GEO_JSON))
    }

    @Test
    fun dropsTheEngineMessageAndEveryCredentialItCarries() {
        listOf(acquisitionFailure(), decodeFailure(), StylePreparationException(credentialBearingMessage))
            .forEach { engineFailure ->
                val descriptor = classifyEngineFailure(engineFailure)
                val rendered = descriptor.toString() + "|" + descriptor.toException().message

                assertTrue(
                    !rendered.contains("SIGNED-SECRET-9f3"),
                    "a credential from the engine message reached the RenG failure: $rendered",
                )
                assertTrue(
                    !rendered.contains("tiles.example.test"),
                    "a host from the engine message reached the RenG failure: $rendered",
                )
                assertNull(
                    descriptor.toException().cause,
                    "the engine failure must not be forwarded as a cause",
                )
            }
    }

    @Test
    fun rethrowsCancellationUnwrapped() {
        val cancellation = CancellationException("frame preparation cancelled")

        val thrown = assertFailsWith<CancellationException> { classifyEngineFailure(cancellation) }

        assertSame(cancellation, thrown)
    }

    @Test
    fun classifiesAnEngineFailureThatMerelyHasACancellationCause() {
        val engineFailure = ResourceDecodeException(
            message = credentialBearingMessage,
            resourceClass = RentileResourceClass.DEM_TILE,
            sanitizedResourceId = decodeDigest,
            cause = CancellationException("engine-internal cancellation"),
        )

        val descriptor = classifyEngineFailure(BatchRenderException("batch failed", engineFailure))

        assertEquals(RenGErrorCode.RESOURCE_DECODE_FAILED, descriptor.code)
        assertEquals(ResourceClass.BASEMAP_DEM_TILE, assertNotNull(descriptor.diagnostic).resourceClass)
    }

    @Test
    fun classifiesAWrappedFailureUpToTheUnwrapDepthCap() {
        val descriptor = classifyEngineFailure(wrappedInBatchFailures(depth = 7, innermost = decodeFailure()))

        assertEquals(RenGErrorCode.RESOURCE_DECODE_FAILED, descriptor.code)
        assertEquals(PipelineStage.RESOURCE_DECODING, descriptor.stage)
    }

    @Test
    fun failsClosedPastTheUnwrapDepthCap() {
        val descriptor = classifyEngineFailure(wrappedInBatchFailures(depth = 8, innermost = decodeFailure()))

        assertEquals(RenGErrorCode.BASEMAP_RENDER_FAILED, descriptor.code)
        assertEquals(PipelineStage.BASEMAP_RENDER, descriptor.stage)
        assertNull(descriptor.diagnostic)
    }

    @Test
    fun failsClosedForAThrowableTheEngineDidNotDeclare() {
        val descriptor = classifyEngineFailure(IllegalStateException(credentialBearingMessage))

        assertEquals(RenGErrorCode.BASEMAP_RENDER_FAILED, descriptor.code)
        assertEquals(PipelineStage.BASEMAP_RENDER, descriptor.stage)
        assertNull(descriptor.diagnostic)
        assertTrue(!descriptor.toString().contains("SIGNED-SECRET-9f3"))
    }

    @Test
    fun failsClosedWhenTheEngineIdentityIsNotADigest() {
        listOf("", "not-a-digest", "A".repeat(64), "a".repeat(63), "a".repeat(65)).forEach { malformed ->
            val descriptor = classifyEngineFailure(acquisitionFailure(sanitizedResourceId = malformed))

            assertEquals(
                RenGErrorCode.BASEMAP_RENDER_FAILED,
                descriptor.code,
                "a malformed engine identity must fail closed rather than throw: '$malformed'",
            )
            assertNull(descriptor.diagnostic)
        }
    }

    private fun wrappedInBatchFailures(depth: Int, innermost: RentileException): RentileException {
        var wrapped = innermost
        repeat(depth) {
            wrapped = BatchRenderException(message = credentialBearingMessage, primaryFailure = wrapped)
        }
        return wrapped
    }

    private data class ExpectedClassification(
        val failure: RentileException,
        val code: RenGErrorCode,
        val stage: PipelineStage,
        val diagnosticPresent: Boolean,
    )
}
