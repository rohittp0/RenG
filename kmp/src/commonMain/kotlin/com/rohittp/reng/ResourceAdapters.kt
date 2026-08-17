package com.rohittp.reng

import com.rohittp.reng.internal.freshCopy

public class TransportRequestMetadata internal constructor(
    public val ifNoneMatch: String? = null,
    public val ifModifiedSince: String? = null,
    public val accept: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is TransportRequestMetadata &&
            ifNoneMatch == other.ifNoneMatch &&
            ifModifiedSince == other.ifModifiedSince &&
            accept == other.accept

    override fun hashCode(): Int {
        var result = ifNoneMatch?.hashCode() ?: 0
        result = 31 * result + (ifModifiedSince?.hashCode() ?: 0)
        result = 31 * result + (accept?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "TransportRequestMetadata(" +
            "ifNoneMatchPresent=${ifNoneMatch != null}, " +
            "ifModifiedSincePresent=${ifModifiedSince != null}, " +
            "acceptPresent=${accept != null})"
}

public class TransportRequest internal constructor(
    public val locator: ResourceLocator,
    public val resourceClass: ResourceClass,
    public val maximumResponseBytes: Long,
    public val metadata: TransportRequestMetadata = TransportRequestMetadata(),
) {
    override fun equals(other: Any?): Boolean =
        other is TransportRequest &&
            locator == other.locator &&
            resourceClass == other.resourceClass &&
            maximumResponseBytes == other.maximumResponseBytes &&
            metadata == other.metadata

    override fun hashCode(): Int {
        var result = locator.hashCode()
        result = 31 * result + resourceClass.hashCode()
        result = 31 * result + maximumResponseBytes.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }

    override fun toString(): String =
        "TransportRequest(resourceClass=$resourceClass, " +
            "maximumResponseBytes=$maximumResponseBytes, metadata=$metadata)"
}

public class TransportResponseMetadata(
    public val contentType: String? = null,
    public val etag: String? = null,
    public val lastModified: String? = null,
    public val freshUntilEpochMillis: Long? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is TransportResponseMetadata &&
            contentType == other.contentType &&
            etag == other.etag &&
            lastModified == other.lastModified &&
            freshUntilEpochMillis == other.freshUntilEpochMillis

    override fun hashCode(): Int {
        var result = contentType?.hashCode() ?: 0
        result = 31 * result + (etag?.hashCode() ?: 0)
        result = 31 * result + (lastModified?.hashCode() ?: 0)
        result = 31 * result + (freshUntilEpochMillis?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "TransportResponseMetadata(" +
            "contentTypePresent=${contentType != null}, " +
            "etagPresent=${etag != null}, " +
            "lastModifiedPresent=${lastModified != null}, " +
            "freshUntilEpochMillisPresent=${freshUntilEpochMillis != null})"
}

public class TransportResponse(
    public val statusCode: Int,
    body: ByteArray,
    public val metadata: TransportResponseMetadata = TransportResponseMetadata(),
) {
    private val bodyBytes: ByteArray = body.freshCopy()

    public val body: ByteArray
        get() = bodyBytes.freshCopy()

    override fun equals(other: Any?): Boolean =
        other is TransportResponse &&
            statusCode == other.statusCode &&
            bodyBytes.contentEquals(other.bodyBytes) &&
            metadata == other.metadata

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + bodyBytes.contentHashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }

    override fun toString(): String = "TransportResponse(statusCode=$statusCode, metadata=$metadata)"
}

public fun interface Transport {
    public suspend fun execute(request: TransportRequest): TransportResponse
}

@ConsistentCopyVisibility
public data class RawResourceKey internal constructor(
    public val stableId: String,
    public val resourceClass: ResourceClass,
) {
    override fun toString(): String = "RawResourceKey(resourceClass=$resourceClass)"
}

public class StoredRawResourceMetadata(
    public val contentType: String? = null,
    public val etag: String? = null,
    public val lastModified: String? = null,
    public val freshUntilEpochMillis: Long? = null,
    public val storedAtEpochMillis: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is StoredRawResourceMetadata &&
            contentType == other.contentType &&
            etag == other.etag &&
            lastModified == other.lastModified &&
            freshUntilEpochMillis == other.freshUntilEpochMillis &&
            storedAtEpochMillis == other.storedAtEpochMillis

    override fun hashCode(): Int {
        var result = contentType?.hashCode() ?: 0
        result = 31 * result + (etag?.hashCode() ?: 0)
        result = 31 * result + (lastModified?.hashCode() ?: 0)
        result = 31 * result + (freshUntilEpochMillis?.hashCode() ?: 0)
        result = 31 * result + storedAtEpochMillis.hashCode()
        return result
    }

    override fun toString(): String =
        "StoredRawResourceMetadata(" +
            "contentTypePresent=${contentType != null}, " +
            "etagPresent=${etag != null}, " +
            "lastModifiedPresent=${lastModified != null}, " +
            "freshUntilEpochMillisPresent=${freshUntilEpochMillis != null}, " +
            "storedAtEpochMillisPresent=true)"
}

public class StoredRawResource(
    bytes: ByteArray,
    public val contentDigest: String,
    public val metadata: StoredRawResourceMetadata,
) {
    private val storedBytes: ByteArray = bytes.freshCopy()

    public val bytes: ByteArray
        get() = storedBytes.freshCopy()

    override fun equals(other: Any?): Boolean =
        other is StoredRawResource &&
            storedBytes.contentEquals(other.storedBytes) &&
            contentDigest == other.contentDigest &&
            metadata == other.metadata

    override fun hashCode(): Int {
        var result = storedBytes.contentHashCode()
        result = 31 * result + contentDigest.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }

    override fun toString(): String = "StoredRawResource(contentDigest=<redacted>, metadata=$metadata)"
}

public interface Store {
    public suspend fun read(key: RawResourceKey): StoredRawResource?

    public suspend fun write(key: RawResourceKey, resource: StoredRawResource): Unit
}
