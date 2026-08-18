package com.rohittp.reng.internal.resource

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.Sha256Function

internal fun resolveTransportResponse(
    route: ResourceRouteKey,
    resourceKey: ResourceKey,
    sampleEpochMillis: Long,
    staleBaseline: StoredRawResource?,
    conditionalRequest: Boolean,
    response: TransportResponse,
    sha256: Sha256Function,
): ResponseRuleOutcome {
    require(sampleEpochMillis >= 0L) { "freshness sample must be non-negative" }
    require(resourceKey.resourceClass == route.resourceClass) {
        "response resource key must match its route class"
    }

    if (!isValidMetadata(response.metadata)) {
        return ResponseRuleOutcome.Failure(invalidResponseFailure(route, resourceKey, response.statusCode))
    }

    return when (response.statusCode) {
        200 -> resolveFullResponse(route, resourceKey, sampleEpochMillis, response, sha256)
        304 -> resolveNotModifiedResponse(
            route,
            resourceKey,
            sampleEpochMillis,
            staleBaseline,
            conditionalRequest,
            response,
            sha256,
        )
        else -> ResponseRuleOutcome.Failure(
            invalidResponseFailure(route, resourceKey, response.statusCode),
        )
    }
}

internal fun copyValidStoredResource(
    stored: StoredRawResource,
    maximumResponseBytes: Long,
    sha256: Sha256Function,
): StoredRawResource? {
    require(maximumResponseBytes > 0L) { "maximum response bytes must be positive" }
    val bytes = stored.byteSnapshot
    if (bytes.isEmpty() || bytes.size.toLong() > maximumResponseBytes) return null
    if (!isLowercaseSha256(stored.contentDigest)) return null
    if (!isValidMetadata(stored.metadata)) return null
    val computedDigest = sha256.digest(CanonicalBytes(bytes)).lowercaseHex
    if (computedDigest != stored.contentDigest) return null
    return StoredRawResource(
        bytes = bytes,
        contentDigest = stored.contentDigest,
        metadata = copyMetadata(stored.metadata),
    )
}

private fun resolveFullResponse(
    route: ResourceRouteKey,
    resourceKey: ResourceKey,
    sampleEpochMillis: Long,
    response: TransportResponse,
    sha256: Sha256Function,
): ResponseRuleOutcome {
    val body = response.bodySnapshot
    if (body.isEmpty()) {
        return ResponseRuleOutcome.Failure(
            invalidResponseFailure(
                route,
                resourceKey,
                response.statusCode,
                DiagnosticField.RESPONSE_BODY_BYTES,
            ),
        )
    }
    if (body.size.toLong() > route.maximumResponseBytes) {
        return ResponseRuleOutcome.Failure(
            responseLimitFailure(route, resourceKey, response.statusCode, body.size.toLong()),
        )
    }

    val digest = sha256.digest(CanonicalBytes(body)).lowercaseHex
    val stored = StoredRawResource(
        bytes = body,
        contentDigest = digest,
        metadata = StoredRawResourceMetadata(
            contentType = response.metadata.contentType,
            etag = response.metadata.etag,
            lastModified = response.metadata.lastModified,
            freshUntilEpochMillis = response.metadata.freshUntilEpochMillis,
            storedAtEpochMillis = sampleEpochMillis,
        ),
    )
    return ResponseRuleOutcome.Selected(
        ResolvedResourceContent(
            route = route,
            resourceKey = resourceKey,
            stored = stored,
            provenance = ContentProvenance.TRANSPORT_200,
        ),
    )
}

private fun resolveNotModifiedResponse(
    route: ResourceRouteKey,
    resourceKey: ResourceKey,
    sampleEpochMillis: Long,
    staleBaseline: StoredRawResource?,
    conditionalRequest: Boolean,
    response: TransportResponse,
    sha256: Sha256Function,
): ResponseRuleOutcome {
    val body = response.bodySnapshot
    if (body.isNotEmpty()) {
        return ResponseRuleOutcome.Failure(
            invalidResponseFailure(
                route,
                resourceKey,
                response.statusCode,
                DiagnosticField.RESPONSE_BODY_BYTES,
            ),
        )
    }
    if (!conditionalRequest || route.accessMode != ResourceAccessMode.NORMAL || staleBaseline == null) {
        return ResponseRuleOutcome.Failure(
            invalidResponseFailure(route, resourceKey, response.statusCode),
        )
    }
    val baseline = copyValidStoredResource(staleBaseline, route.maximumResponseBytes, sha256)
        ?: return ResponseRuleOutcome.Failure(
            invalidResponseFailure(route, resourceKey, response.statusCode),
        )
    if (
        (baseline.metadata.etag == null && baseline.metadata.lastModified == null) ||
        baseline.metadata.freshUntilEpochMillis?.let { it > sampleEpochMillis } == true
    ) {
        return ResponseRuleOutcome.Failure(
            invalidResponseFailure(route, resourceKey, response.statusCode),
        )
    }

    val baselineMetadata = baseline.metadata
    val responseMetadata = response.metadata
    val merged = StoredRawResource(
        bytes = baseline.byteSnapshot,
        contentDigest = baseline.contentDigest,
        metadata = StoredRawResourceMetadata(
            contentType = responseMetadata.contentType ?: baselineMetadata.contentType,
            etag = responseMetadata.etag ?: baselineMetadata.etag,
            lastModified = responseMetadata.lastModified ?: baselineMetadata.lastModified,
            freshUntilEpochMillis = responseMetadata.freshUntilEpochMillis
                ?: baselineMetadata.freshUntilEpochMillis,
            storedAtEpochMillis = sampleEpochMillis,
        ),
    )
    return ResponseRuleOutcome.Selected(
        ResolvedResourceContent(
            route = route,
            resourceKey = resourceKey,
            stored = merged,
            provenance = ContentProvenance.TRANSPORT_304_MERGED,
        ),
    )
}

private fun invalidResponseFailure(
    route: ResourceRouteKey,
    resourceKey: ResourceKey,
    statusCode: Int,
    field: DiagnosticField? = null,
): FailureDescriptor = FailureDescriptor(
    code = RenGErrorCode.INVALID_TRANSPORT_RESPONSE,
    stage = PipelineStage.TRANSPORT_VALIDATION,
    diagnostic = failureContextDiagnostic(
        stage = PipelineStage.TRANSPORT_VALIDATION,
        fieldName = field,
        resourceClass = route.resourceClass,
        resourceKey = resourceKey,
        statusCode = statusCode,
    ),
)

private fun responseLimitFailure(
    route: ResourceRouteKey,
    resourceKey: ResourceKey,
    statusCode: Int,
    actual: Long,
): FailureDescriptor = FailureDescriptor(
    code = RenGErrorCode.RESOURCE_LIMIT_EXCEEDED,
    stage = PipelineStage.TRANSPORT_VALIDATION,
    diagnostic = failureContextDiagnostic(
        stage = PipelineStage.TRANSPORT_VALIDATION,
        fieldName = DiagnosticField.RESPONSE_BODY_BYTES,
        resourceClass = route.resourceClass,
        resourceKey = resourceKey,
        statusCode = statusCode,
        limit = route.maximumResponseBytes,
        actual = actual,
    ),
)

private fun isValidMetadata(metadata: TransportResponseMetadata): Boolean =
    isValidOptionalMetadataText(metadata.contentType) &&
        isValidOptionalMetadataText(metadata.etag) &&
        isValidOptionalMetadataText(metadata.lastModified) &&
        (metadata.freshUntilEpochMillis == null || metadata.freshUntilEpochMillis >= 0L)

private fun isValidMetadata(metadata: StoredRawResourceMetadata): Boolean =
    isValidOptionalMetadataText(metadata.contentType) &&
        isValidOptionalMetadataText(metadata.etag) &&
        isValidOptionalMetadataText(metadata.lastModified) &&
        (metadata.freshUntilEpochMillis == null || metadata.freshUntilEpochMillis >= 0L) &&
        metadata.storedAtEpochMillis >= 0L

private fun copyMetadata(metadata: StoredRawResourceMetadata): StoredRawResourceMetadata =
    StoredRawResourceMetadata(
        contentType = metadata.contentType,
        etag = metadata.etag,
        lastModified = metadata.lastModified,
        freshUntilEpochMillis = metadata.freshUntilEpochMillis,
        storedAtEpochMillis = metadata.storedAtEpochMillis,
    )

private fun isValidOptionalMetadataText(value: String?): Boolean {
    if (value == null) return true
    if (value.isBlank() || '\r' in value || '\n' in value) return false

    var index = 0
    while (index < value.length) {
        when (value[index]) {
            in '\uD800'..'\uDBFF' -> {
                if (index + 1 >= value.length || value[index + 1] !in '\uDC00'..'\uDFFF') return false
                index += 2
            }
            in '\uDC00'..'\uDFFF' -> return false
            else -> index += 1
        }
    }
    return true
}

private fun isLowercaseSha256(value: String): Boolean =
    value.length == SHA256_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

private const val SHA256_HEX_LENGTH: Int = 64
