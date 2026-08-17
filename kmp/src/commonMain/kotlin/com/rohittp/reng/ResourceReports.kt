package com.rohittp.reng

import com.rohittp.reng.internal.reportOrder

@ConsistentCopyVisibility
public data class ResourceKey internal constructor(
    public val kind: ResourceKind,
    public val stableId: String,
    public val resourceClass: ResourceClass?,
) {
    init {
        require(stableId.isLowercaseSha256()) { "stableId must be a lowercase SHA-256 digest" }
        require((kind == ResourceKind.EXTERNAL) == (resourceClass != null)) {
            "external keys require a resource class and other keys do not"
        }
    }

    override fun toString(): String = "ResourceKey(kind=$kind, resourceClass=$resourceClass)"
}

public sealed interface ResourceSelector {
    public data object All : ResourceSelector

    public data class ByKind(public val kind: ResourceKind) : ResourceSelector

    public data class ByClass(public val resourceClass: ResourceClass) : ResourceSelector

    public data class ByKey(public val key: ResourceKey) : ResourceSelector
}

@ConsistentCopyVisibility
public data class ResourceUsage internal constructor(
    public val rawBytes: Long,
    public val decodedCpuBytes: Long,
    public val knownGpuBytes: Long?,
    public val hasUnknownGpuBytes: Boolean,
) {
    init {
        require(rawBytes >= 0L) { "rawBytes must be non-negative" }
        require(decodedCpuBytes >= 0L) { "decodedCpuBytes must be non-negative" }
        require(knownGpuBytes == null || knownGpuBytes >= 0L) {
            "knownGpuBytes must be non-negative when present"
        }
        require(knownGpuBytes != null || hasUnknownGpuBytes) {
            "unknown GPU bytes must be declared when known GPU bytes are absent"
        }
    }
}

@ConsistentCopyVisibility
public data class ResourceReportEntry internal constructor(
    public val key: ResourceKey,
    public val residentGenerationCount: Int,
    public val retiredGenerationCount: Int,
    public val leaseCount: Int,
    public val reloadRequired: Boolean,
    public val usage: ResourceUsage,
) {
    init {
        require(residentGenerationCount >= 0) { "residentGenerationCount must be non-negative" }
        require(retiredGenerationCount >= 0) { "retiredGenerationCount must be non-negative" }
        require(leaseCount >= 0) { "leaseCount must be non-negative" }
    }
}

public class ResourceReport internal constructor(
    entries: List<ResourceReportEntry>,
    public val totals: ResourceUsage,
) {
    private val entrySnapshot: List<ResourceReportEntry> = entries.sortedWith(resourceReportEntryComparator)

    public val entries: List<ResourceReportEntry>
        get() = ArrayList(entrySnapshot)

    override fun equals(other: Any?): Boolean =
        other is ResourceReport && entrySnapshot == other.entrySnapshot && totals == other.totals

    override fun hashCode(): Int = 31 * entrySnapshot.hashCode() + totals.hashCode()

    override fun toString(): String = "ResourceReport(entries=$entrySnapshot, totals=$totals)"
}

@ConsistentCopyVisibility
public data class ResourceFreeResult internal constructor(
    public val matchedKeys: Int,
    public val fullyFreedKeys: Int,
    public val deferredKeys: Int,
    public val alreadyFreeKeys: Int,
) {
    init {
        require(matchedKeys >= 0) { "matchedKeys must be non-negative" }
        require(fullyFreedKeys >= 0) { "fullyFreedKeys must be non-negative" }
        require(deferredKeys >= 0) { "deferredKeys must be non-negative" }
        require(alreadyFreeKeys >= 0) { "alreadyFreeKeys must be non-negative" }
        require(
            matchedKeys.toLong() ==
                fullyFreedKeys.toLong() + deferredKeys.toLong() + alreadyFreeKeys.toLong(),
        ) { "free result categories must sum to matchedKeys" }
    }
}

private val resourceReportEntryComparator: Comparator<ResourceReportEntry> =
    compareBy<ResourceReportEntry>(
        { it.key.kind.reportOrder },
        { it.key.resourceClass?.reportOrder ?: -1 },
        { it.key.stableId },
    )

private fun String.isLowercaseSha256(): Boolean =
    length == sha256HexLength && all { it in '0'..'9' || it in 'a'..'f' }

private const val sha256HexLength: Int = 64
