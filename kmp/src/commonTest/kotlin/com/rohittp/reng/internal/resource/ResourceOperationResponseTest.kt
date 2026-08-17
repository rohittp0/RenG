package com.rohittp.reng.internal.resource

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.Sha256Digest
import com.rohittp.reng.internal.identity.Sha256Function
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceOperationResponseTest {
    @Test
    fun malformedMetadataBeatsEveryStatusAndBodyFault() {
        data class Case(val name: String, val metadata: TransportResponseMetadata)
        val cases = listOf(
            Case("blank content type", TransportResponseMetadata(contentType = " ")),
            Case("content type CR", TransportResponseMetadata(contentType = "a\rb")),
            Case("blank ETag", TransportResponseMetadata(etag = "\t")),
            Case("ETag LF", TransportResponseMetadata(etag = "a\nb")),
            Case("last-modified surrogate", TransportResponseMetadata(lastModified = "\uDC00")),
            Case("negative freshness", TransportResponseMetadata(freshUntilEpochMillis = -1L)),
        )
        val route = responseRoute(maximumResponseBytes = 1L)
        val key = responseKey()

        cases.forEach { case ->
            listOf(
                TransportResponse(200, byteArrayOf(), case.metadata),
                TransportResponse(200, byteArrayOf(1, 2), case.metadata),
                TransportResponse(304, byteArrayOf(1, 2), case.metadata),
                TransportResponse(399, byteArrayOf(), case.metadata),
            ).forEach { response ->
                val outcome = assertIs<ResponseRuleOutcome.Failure>(
                    resolveTransportResponse(
                        route,
                        key,
                        7L,
                        staleBaseline = null,
                        conditionalRequest = false,
                        response,
                        PureKotlinSha256,
                    ),
                    case.name,
                )
                assertInvalidResponse(outcome, response.statusCode, expectedField = null)
            }
        }
    }

    @Test
    fun statusAndBodyValidationFollowTheExactOrder() {
        data class Case(
            val name: String,
            val route: ResourceRouteKey,
            val response: TransportResponse,
            val expectedCode: RenGErrorCode,
            val expectedField: String?,
            val expectedLimit: Long? = null,
            val expectedActual: Long? = null,
        )
        val cases = listOf(
            Case(
                "empty 200 is shape failure",
                responseRoute(3L),
                TransportResponse(200, byteArrayOf()),
                RenGErrorCode.INVALID_TRANSPORT_RESPONSE,
                "responseBodyBytes",
            ),
            Case(
                "oversized nonempty 200 is limit failure",
                responseRoute(2L),
                TransportResponse(200, "abc".encodeToByteArray()),
                RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
                "responseBodyBytes",
                expectedLimit = 2L,
                expectedActual = 3L,
            ),
            Case(
                "nonempty 304 is shape failure without limit comparison",
                responseRoute(1L),
                TransportResponse(304, byteArrayOf(1, 2, 3)),
                RenGErrorCode.INVALID_TRANSPORT_RESPONSE,
                "responseBodyBytes",
            ),
            Case(
                "unsupported status ignores body shape",
                responseRoute(3L),
                TransportResponse(302, byteArrayOf()),
                RenGErrorCode.INVALID_TRANSPORT_RESPONSE,
                null,
            ),
        )

        cases.forEach { case ->
            val outcome = assertIs<ResponseRuleOutcome.Failure>(
                resolveTransportResponse(
                    case.route,
                    responseKey(),
                    7L,
                    staleBaseline = validBaseline(),
                    conditionalRequest = true,
                    case.response,
                    PureKotlinSha256,
                ),
                case.name,
            )
            assertEquals(case.expectedCode, outcome.failure.code, case.name)
            assertEquals(PipelineStage.TRANSPORT_VALIDATION, outcome.failure.stage, case.name)
            val diagnostic = requireNotNull(outcome.failure.diagnostic)
            assertEquals(case.response.statusCode, diagnostic.statusCode, case.name)
            assertEquals(case.expectedField, diagnostic.fieldName, case.name)
            assertEquals(case.expectedLimit, diagnostic.limit, case.name)
            assertEquals(case.expectedActual, diagnostic.actual, case.name)
        }
    }

    @Test
    fun valid200CopiesBodyHashesThatCopyAndRetainsMetadataAndSample() {
        val input = "abc".encodeToByteArray()
        val metadata = TransportResponseMetadata(
            contentType = "model/gltf-binary",
            etag = "new-etag",
            lastModified = "new-date",
            freshUntilEpochMillis = 900L,
        )
        val response = TransportResponse(200, input, metadata)
        input[0] = 0
        val capturingSha = CapturingSha(RESPONSE_ABC_DIGEST)

        val selected = assertIs<ResponseRuleOutcome.Selected>(
            resolveTransportResponse(
                responseRoute(3L),
                responseKey(),
                sampleEpochMillis = 700L,
                staleBaseline = null,
                conditionalRequest = false,
                response = response,
                sha256 = capturingSha,
            ),
        ).content

        assertEquals(ContentProvenance.TRANSPORT_200, selected.provenance)
        assertContentEquals("abc".encodeToByteArray(), capturingSha.observed)
        capturingSha.observed[0] = 0
        assertContentEquals("abc".encodeToByteArray(), selected.stored.bytes)
        val firstRead = selected.stored.bytes
        val secondRead = selected.stored.bytes
        assertNotSame(firstRead, secondRead)
        firstRead[0] = 0
        assertContentEquals("abc".encodeToByteArray(), secondRead)
        assertEquals(RESPONSE_ABC_DIGEST, selected.stored.contentDigest)
        assertEquals("model/gltf-binary", selected.stored.metadata.contentType)
        assertEquals("new-etag", selected.stored.metadata.etag)
        assertEquals("new-date", selected.stored.metadata.lastModified)
        assertEquals(900L, selected.stored.metadata.freshUntilEpochMillis)
        assertEquals(700L, selected.stored.metadata.storedAtEpochMillis)
        assertEquals(responseRoute(3L), selected.route)
        assertEquals(responseKey(), selected.resourceKey)
    }

    @Test
    fun responseRuleComputesTheKnownLowercaseSha256() {
        val selected = assertIs<ResponseRuleOutcome.Selected>(
            resolveTransportResponse(
                responseRoute(3L),
                responseKey(),
                0L,
                null,
                false,
                TransportResponse(200, "abc".encodeToByteArray()),
                PureKotlinSha256,
            ),
        ).content
        assertEquals(RESPONSE_ABC_DIGEST, selected.stored.contentDigest)
    }

    @Test
    fun empty304RequiresConditionalNormalAndAValidBaseline() {
        data class Case(
            val name: String,
            val route: ResourceRouteKey,
            val conditional: Boolean,
            val baseline: StoredRawResource?,
        )
        val cases = listOf(
            Case("not conditional", responseRoute(), false, validBaseline()),
            Case("cache-only", responseRoute(mode = ResourceAccessMode.CACHE_ONLY), true, validBaseline()),
            Case("reload", responseRoute(mode = ResourceAccessMode.RELOAD), true, validBaseline()),
            Case("missing baseline", responseRoute(), true, null),
            Case(
                "baseline without a validator",
                responseRoute(),
                true,
                validBaseline(etag = null, lastModified = null),
            ),
            Case(
                "strictly fresh baseline",
                responseRoute(),
                true,
                validBaseline(freshUntil = 701L),
            ),
            Case(
                "empty baseline",
                responseRoute(),
                true,
                StoredRawResource(byteArrayOf(), RESPONSE_ABC_DIGEST, baselineMetadata()),
            ),
            Case(
                "bad baseline digest",
                responseRoute(),
                true,
                StoredRawResource("abc".encodeToByteArray(), "f".repeat(64), baselineMetadata()),
            ),
            Case(
                "bad baseline metadata",
                responseRoute(),
                true,
                StoredRawResource(
                    "abc".encodeToByteArray(),
                    RESPONSE_ABC_DIGEST,
                    baselineMetadata(etag = "bad\netag"),
                ),
            ),
        )

        cases.forEach { case ->
            val failure = assertIs<ResponseRuleOutcome.Failure>(
                resolveTransportResponse(
                    case.route,
                    responseKey(),
                    700L,
                    case.baseline,
                    case.conditional,
                    TransportResponse(304, byteArrayOf()),
                    PureKotlinSha256,
                ),
                case.name,
            )
            assertInvalidResponse(failure, 304, expectedField = null)
        }
    }

    @Test
    fun valid304PreservesBytesAndDigestAndMergesEachMetadataFieldIndependently() {
        val baseline = validBaseline(
            contentType = "old/type",
            etag = "old-etag",
            lastModified = "old-date",
            freshUntil = 600L,
            storedAt = 10L,
        )
        val response = TransportResponse(
            statusCode = 304,
            body = byteArrayOf(),
            metadata = TransportResponseMetadata(
                contentType = "new/type",
                etag = null,
                lastModified = "new-date",
                freshUntilEpochMillis = null,
            ),
        )
        val sha = CountingSha()

        val selected = assertIs<ResponseRuleOutcome.Selected>(
            resolveTransportResponse(
                responseRoute(),
                responseKey(),
                sampleEpochMillis = 700L,
                staleBaseline = baseline,
                conditionalRequest = true,
                response = response,
                sha256 = sha,
            ),
        ).content

        assertEquals(ContentProvenance.TRANSPORT_304_MERGED, selected.provenance)
        assertContentEquals(baseline.bytes, selected.stored.bytes)
        assertEquals(RESPONSE_ABC_DIGEST, selected.stored.contentDigest)
        assertEquals("new/type", selected.stored.metadata.contentType)
        assertEquals("old-etag", selected.stored.metadata.etag)
        assertEquals("new-date", selected.stored.metadata.lastModified)
        assertEquals(600L, selected.stored.metadata.freshUntilEpochMillis)
        assertEquals(700L, selected.stored.metadata.storedAtEpochMillis)
        assertEquals(1, sha.calls)
    }

    @Test
    fun everyNullable304MetadataFieldCanOverrideOrRetain() {
        data class Case(
            val name: String,
            val response: TransportResponseMetadata,
            val contentType: String?,
            val etag: String?,
            val lastModified: String?,
            val freshUntil: Long?,
        )
        val baseline = validBaseline(
            contentType = "old/type",
            etag = "old-etag",
            lastModified = "old-date",
            freshUntil = 600L,
        )
        val cases = listOf(
            Case(
                "all retained",
                TransportResponseMetadata(),
                "old/type",
                "old-etag",
                "old-date",
                600L,
            ),
            Case(
                "all overridden",
                TransportResponseMetadata("new/type", "new-etag", "new-date", 800L),
                "new/type",
                "new-etag",
                "new-date",
                800L,
            ),
        )
        cases.forEach { case ->
            val selected = assertIs<ResponseRuleOutcome.Selected>(
                resolveTransportResponse(
                    responseRoute(),
                    responseKey(),
                    700L,
                    baseline,
                    true,
                    TransportResponse(304, byteArrayOf(), case.response),
                    PureKotlinSha256,
                ),
                case.name,
            ).content.stored.metadata
            assertEquals(case.contentType, selected.contentType, case.name)
            assertEquals(case.etag, selected.etag, case.name)
            assertEquals(case.lastModified, selected.lastModified, case.name)
            assertEquals(case.freshUntil, selected.freshUntilEpochMillis, case.name)
            assertEquals(700L, selected.storedAtEpochMillis, case.name)
        }
    }

    @Test
    fun responseRuleRejectsMalformedPureInputsBeforeIndexingOrMerging() {
        assertFailsWith<IllegalArgumentException> {
            resolveTransportResponse(
                responseRoute(),
                responseKey(),
                -1L,
                validBaseline(),
                true,
                TransportResponse(304, byteArrayOf()),
                PureKotlinSha256,
            )
        }
        val mismatchedClassKey = ResourceKey(
            ResourceKind.EXTERNAL,
            "c".repeat(64),
            ResourceClass.STICKER_IMAGE,
        )
        assertFailsWith<IllegalArgumentException> {
            resolveTransportResponse(
                responseRoute(),
                mismatchedClassKey,
                1L,
                null,
                false,
                TransportResponse(200, "abc".encodeToByteArray()),
                PureKotlinSha256,
            )
        }
    }

    private fun assertInvalidResponse(
        outcome: ResponseRuleOutcome.Failure,
        statusCode: Int,
        expectedField: String?,
    ) {
        assertEquals(RenGErrorCode.INVALID_TRANSPORT_RESPONSE, outcome.failure.code)
        assertEquals(PipelineStage.TRANSPORT_VALIDATION, outcome.failure.stage)
        val diagnostic = requireNotNull(outcome.failure.diagnostic)
        assertEquals(statusCode, diagnostic.statusCode)
        assertEquals(expectedField, diagnostic.fieldName)
        assertEquals(ResourceClass.MODEL_GLB, diagnostic.resourceClass)
        assertEquals(responseKey(), diagnostic.resourceKey)
        assertNull(diagnostic.limit)
        assertNull(diagnostic.actual)
    }
}

private class CapturingSha(hex: String) : Sha256Function {
    private val result = Sha256Digest(hexToBytes(hex))
    lateinit var observed: ByteArray

    override fun digest(bytes: CanonicalBytes): Sha256Digest {
        observed = bytes.bytes
        return result
    }
}

private class CountingSha : Sha256Function {
    var calls: Int = 0

    override fun digest(bytes: CanonicalBytes): Sha256Digest {
        calls += 1
        return PureKotlinSha256.digest(bytes)
    }
}

private fun responseRoute(
    maximumResponseBytes: Long = 3L,
    mode: ResourceAccessMode = ResourceAccessMode.NORMAL,
): ResourceRouteKey = ResourceRouteKey(
    mode,
    ResourceLocator("response-locator"),
    ResourceClass.MODEL_GLB,
    maximumResponseBytes,
)

private fun responseKey(): ResourceKey = ResourceKey(
    ResourceKind.EXTERNAL,
    "c".repeat(64),
    ResourceClass.MODEL_GLB,
)

private fun baselineMetadata(
    contentType: String? = "old/type",
    etag: String? = "old-etag",
    lastModified: String? = "old-date",
    freshUntil: Long? = 600L,
    storedAt: Long = 10L,
): StoredRawResourceMetadata = StoredRawResourceMetadata(
    contentType,
    etag,
    lastModified,
    freshUntil,
    storedAt,
)

private fun validBaseline(
    contentType: String? = "old/type",
    etag: String? = "old-etag",
    lastModified: String? = "old-date",
    freshUntil: Long? = 600L,
    storedAt: Long = 10L,
): StoredRawResource = StoredRawResource(
    "abc".encodeToByteArray(),
    RESPONSE_ABC_DIGEST,
    baselineMetadata(contentType, etag, lastModified, freshUntil, storedAt),
)

private const val RESPONSE_ABC_DIGEST: String =
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

private fun hexToBytes(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
