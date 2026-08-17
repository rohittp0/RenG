package com.rohittp.reng.internal.resource

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.freshListCopy
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.requireUnicodeScalars
import kotlin.jvm.JvmInline

internal enum class CancellationCause {
    CALLER,
    CANCEL_PREPARATIONS,
    ADAPTER,
}

@JvmInline
internal value class CancellationId(val value: Long) {
    init {
        require(value > 0L) { "cancellation ID must be positive" }
    }
}

internal data class CancellationSelection(
    val cause: CancellationCause,
    val id: CancellationId,
)

@JvmInline
internal value class ResourceOwnerId(val value: Long) {
    init {
        require(value > 0L) { "resource owner ID must be positive" }
    }
}

@JvmInline
internal value class ResourceOccurrenceId(val value: Long) {
    init {
        require(value > 0L) { "resource occurrence ID must be positive" }
    }
}

@JvmInline
internal value class SpriteGroupId(val value: Long) {
    init {
        require(value > 0L) { "sprite group ID must be positive" }
    }
}

@JvmInline
internal value class StyleGroupId(val value: Long) {
    init {
        require(value > 0L) { "style group ID must be positive" }
    }
}

@JvmInline
internal value class ResourceActionId(val value: Long) {
    init {
        require(value > 0L) { "resource action ID must be positive" }
    }
}

internal class ResourceRouteKey(
    val accessMode: ResourceAccessMode,
    val locator: ResourceLocator,
    val resourceClass: ResourceClass,
    val maximumResponseBytes: Long,
) {
    init {
        require(maximumResponseBytes > 0L) { "maximum response bytes must be positive" }
    }

    override fun equals(other: Any?): Boolean =
        other is ResourceRouteKey &&
            accessMode == other.accessMode &&
            locator == other.locator &&
            resourceClass == other.resourceClass &&
            maximumResponseBytes == other.maximumResponseBytes

    override fun hashCode(): Int {
        var result = accessMode.hashCode()
        result = 31 * result + locator.hashCode()
        result = 31 * result + resourceClass.hashCode()
        result = 31 * result + maximumResponseBytes.hashCode()
        return result
    }

    override fun toString(): String =
        "ResourceRouteKey(" +
            "accessMode=$accessMode, " +
            "resourceClass=$resourceClass, " +
            "maximumResponseBytes=$maximumResponseBytes)"
}

internal class TransportLatchKey(
    val route: ResourceRouteKey,
    val ifNoneMatch: String?,
    val ifModifiedSince: String?,
    val accept: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is TransportLatchKey &&
            route == other.route &&
            ifNoneMatch == other.ifNoneMatch &&
            ifModifiedSince == other.ifModifiedSince &&
            accept == other.accept

    override fun hashCode(): Int {
        var result = route.hashCode()
        result = 31 * result + (ifNoneMatch?.hashCode() ?: 0)
        result = 31 * result + (ifModifiedSince?.hashCode() ?: 0)
        result = 31 * result + (accept?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "TransportLatchKey(" +
            "route=$route, " +
            "ifNoneMatchPresent=${ifNoneMatch != null}, " +
            "ifModifiedSincePresent=${ifModifiedSince != null}, " +
            "acceptPresent=${accept != null})"
}

internal data class ResourceRouteRegistration(
    val route: ResourceRouteKey,
    val resourceKey: ResourceKey,
    val rawKey: RawResourceKey,
    val privateRentileKey: RentilePrivateKey,
    val canonicalBytes: CanonicalBytes,
)

internal enum class SpriteMember {
    JSON,
    IMAGE,
}

internal sealed interface ResourceCommitBinding {
    data object Single : ResourceCommitBinding

    data class Sprite(
        val groupId: SpriteGroupId,
        val member: SpriteMember,
    ) : ResourceCommitBinding

    data class BasemapStyle(val groupId: StyleGroupId) : ResourceCommitBinding
}

internal data class ResourceOccurrence(
    val id: ResourceOccurrenceId,
    val ownerId: ResourceOwnerId,
    val registration: ResourceRouteRegistration,
    val discoveryRequired: Boolean,
    val commitBinding: ResourceCommitBinding,
)

internal sealed interface ResourceChildTraversal {
    data class BasemapSprite(val member: SpriteMember) : ResourceChildTraversal

    class BasemapSource(
        val sourceId: String,
        val member: BasemapSourceMember,
    ) : ResourceChildTraversal {
        init {
            requireUnicodeScalars(sourceId, "source ID", nonBlank = false)
        }

        override fun equals(other: Any?): Boolean =
            other is BasemapSource && sourceId == other.sourceId && member == other.member

        override fun hashCode(): Int = 31 * sourceId.hashCode() + member.hashCode()

        override fun toString(): String =
            when (member) {
                BasemapSourceMember.Metadata -> "BasemapSource(member=Metadata)"
                is BasemapSourceMember.Tile -> "BasemapSource(member=Tile)"
            }
    }

    data class DeclaredArray(val index: Int) : ResourceChildTraversal {
        init {
            require(index >= 0) { "declared array index must be non-negative" }
        }
    }

    class ObjectMember(
        val exactKey: String,
    ) : ResourceChildTraversal {
        init {
            requireUnicodeScalars(exactKey, "object member key", nonBlank = false)
        }

        override fun equals(other: Any?): Boolean = other is ObjectMember && exactKey == other.exactKey

        override fun hashCode(): Int = exactKey.hashCode()

        override fun toString(): String = "ObjectMember"
    }
}

internal sealed interface BasemapSourceMember {
    data object Metadata : BasemapSourceMember

    data class Tile(
        val lod: Int,
        val tileY: Int,
        val canonicalX: Int,
    ) : BasemapSourceMember {
        init {
            require(lod >= 0) { "tile LOD must be non-negative" }
            require(tileY >= 0) { "tile Y must be non-negative" }
            require(canonicalX >= 0) { "canonical tile X must be non-negative" }
        }
    }
}

internal data class DiscoveredResourceChild(
    val traversal: ResourceChildTraversal,
    val occurrence: ResourceOccurrence,
)

internal class ResourceOccurrenceIdSlice private constructor(
    private val backing: List<ResourceOccurrenceId>,
    internal val startIndex: Int,
) {
    init {
        require(startIndex in 0..backing.size) { "resource occurrence slice start must be in bounds" }
    }

    internal val backingSize: Int
        get() = backing.size

    internal val isEmpty: Boolean
        get() = startIndex == backing.size

    internal fun first(): ResourceOccurrenceId {
        check(!isEmpty) { "resource occurrence slice must not be empty" }
        return backing[startIndex]
    }

    internal fun advance(): ResourceOccurrenceIdSlice {
        check(!isEmpty) { "resource occurrence slice must not be empty" }
        return ResourceOccurrenceIdSlice(backing, startIndex + 1)
    }

    internal fun freshValues(): List<ResourceOccurrenceId> =
        ArrayList(backing.subList(startIndex, backing.size))

    internal companion object {
        internal fun snapshot(values: List<ResourceOccurrenceId>): ResourceOccurrenceIdSlice =
            ResourceOccurrenceIdSlice(freshListCopy(values), 0)

        internal fun empty(): ResourceOccurrenceIdSlice = ResourceOccurrenceIdSlice(emptyList(), 0)
    }
}

internal class DiscoveryFrontier private constructor(
    val parentOccurrenceId: ResourceOccurrenceId,
    private val childOccurrenceIdSlice: ResourceOccurrenceIdSlice,
    private val withheldContinuationSlice: ResourceOccurrenceIdSlice,
) {
    internal constructor(
        parentOccurrenceId: ResourceOccurrenceId,
        childOccurrenceIds: List<ResourceOccurrenceId>,
        withheldContinuation: List<ResourceOccurrenceId>,
    ) : this(
        parentOccurrenceId = parentOccurrenceId,
        childOccurrenceIdSlice = ResourceOccurrenceIdSlice.snapshot(childOccurrenceIds),
        withheldContinuationSlice = ResourceOccurrenceIdSlice.snapshot(withheldContinuation),
    )

    val childOccurrenceIds: List<ResourceOccurrenceId>
        get() = childOccurrenceIdSlice.freshValues()

    val withheldContinuation: List<ResourceOccurrenceId>
        get() = withheldContinuationSlice.freshValues()

    internal val withheldBackingSize: Int
        get() = withheldContinuationSlice.backingSize

    internal val withheldStartIndex: Int
        get() = withheldContinuationSlice.startIndex

    internal val hasChildren: Boolean
        get() = !childOccurrenceIdSlice.isEmpty

    internal fun firstChild(): ResourceOccurrenceId = childOccurrenceIdSlice.first()

    internal fun afterFirstChild(): DiscoveryFrontier = DiscoveryFrontier(
        parentOccurrenceId = parentOccurrenceId,
        childOccurrenceIdSlice = childOccurrenceIdSlice.advance(),
        withheldContinuationSlice = withheldContinuationSlice,
    )

    internal fun withoutChildren(): DiscoveryFrontier = DiscoveryFrontier(
        parentOccurrenceId = parentOccurrenceId,
        childOccurrenceIdSlice = ResourceOccurrenceIdSlice.empty(),
        withheldContinuationSlice = withheldContinuationSlice,
    )

    internal fun withChildren(childOccurrenceIds: List<ResourceOccurrenceId>): DiscoveryFrontier =
        DiscoveryFrontier(
            parentOccurrenceId = parentOccurrenceId,
            childOccurrenceIdSlice = ResourceOccurrenceIdSlice.snapshot(childOccurrenceIds),
            withheldContinuationSlice = withheldContinuationSlice,
        )

    internal fun remainingChildrenAsWithheld(
        nestedParentOccurrenceId: ResourceOccurrenceId,
    ): DiscoveryFrontier = DiscoveryFrontier(
        parentOccurrenceId = nestedParentOccurrenceId,
        childOccurrenceIdSlice = ResourceOccurrenceIdSlice.empty(),
        withheldContinuationSlice = childOccurrenceIdSlice,
    )

    internal fun restoreWithheldInto(parent: DiscoveryFrontier): DiscoveryFrontier {
        require(!parent.hasChildren) { "frontier continuation must be singular" }
        return DiscoveryFrontier(
            parentOccurrenceId = parent.parentOccurrenceId,
            childOccurrenceIdSlice = withheldContinuationSlice,
            withheldContinuationSlice = parent.withheldContinuationSlice,
        )
    }

    internal fun withheldSlice(): ResourceOccurrenceIdSlice = withheldContinuationSlice

    internal companion object {
        internal fun unresolved(
            parentOccurrenceId: ResourceOccurrenceId,
            withheldContinuation: ResourceOccurrenceIdSlice,
        ): DiscoveryFrontier = DiscoveryFrontier(
            parentOccurrenceId = parentOccurrenceId,
            childOccurrenceIdSlice = ResourceOccurrenceIdSlice.empty(),
            withheldContinuationSlice = withheldContinuation,
        )
    }
}

internal class TraversalState private constructor(
    eligibleFifo: List<ResourceOccurrenceId>,
    private val staticContinuationSlice: ResourceOccurrenceIdSlice,
    frontierStack: List<DiscoveryFrontier>,
) {
    internal constructor(
        eligibleFifo: List<ResourceOccurrenceId>,
        staticContinuation: List<ResourceOccurrenceId>,
        frontierStack: List<DiscoveryFrontier>,
    ) : this(
        eligibleFifo = eligibleFifo,
        staticContinuationSlice = ResourceOccurrenceIdSlice.snapshot(staticContinuation),
        frontierStack = frontierStack,
    )

    private val eligibleFifoSnapshot: List<ResourceOccurrenceId> = freshListCopy(eligibleFifo)
    private val frontierStackSnapshot: List<DiscoveryFrontier> = freshListCopy(frontierStack)

    val eligibleFifo: List<ResourceOccurrenceId>
        get() = freshListCopy(eligibleFifoSnapshot)

    val staticContinuation: List<ResourceOccurrenceId>
        get() = staticContinuationSlice.freshValues()

    val frontierStack: List<DiscoveryFrontier>
        get() = freshListCopy(frontierStackSnapshot)

    internal fun staticSlice(): ResourceOccurrenceIdSlice = staticContinuationSlice

    internal companion object {
        internal fun fromSlices(
            eligibleFifo: List<ResourceOccurrenceId>,
            staticContinuation: ResourceOccurrenceIdSlice,
            frontierStack: List<DiscoveryFrontier>,
        ): TraversalState = TraversalState(
            eligibleFifo = eligibleFifo,
            staticContinuationSlice = staticContinuation,
            frontierStack = frontierStack,
        )
    }
}

internal class ResourceOperationDefinition(
    val maximumConcurrentRoutes: Int,
    staticOccurrences: List<ResourceOccurrence>,
    resourceIdentities: List<CanonicalIdentityRecord>,
) {
    private val staticOccurrenceSnapshot: List<ResourceOccurrence> = freshListCopy(staticOccurrences)
    private val resourceIdentitySnapshot: List<CanonicalIdentityRecord> = freshListCopy(resourceIdentities)

    init {
        require(maximumConcurrentRoutes > 0) { "maximum concurrent routes must be positive" }
    }

    val staticOccurrences: List<ResourceOccurrence>
        get() = freshListCopy(staticOccurrenceSnapshot)

    val resourceIdentities: List<CanonicalIdentityRecord>
        get() = freshListCopy(resourceIdentitySnapshot)
}

internal enum class ResourceRouteStatus {
    PREREGISTERED,
    ELIGIBLE,
    RUNNING,
    RESOLVED,
    BLOCKED_BY_COLLISION,
}

internal sealed interface ResourceRouteCursor

internal class RouteRecord(
    val registration: ResourceRouteRegistration,
    joinedOccurrenceIds: List<ResourceOccurrenceId>,
    val ordinal: Long?,
    val cursor: ResourceRouteCursor?,
    val status: ResourceRouteStatus,
) {
    private val joinedOccurrenceIdSnapshot: List<ResourceOccurrenceId> = freshListCopy(joinedOccurrenceIds)

    val joinedOccurrenceIds: List<ResourceOccurrenceId>
        get() = freshListCopy(joinedOccurrenceIdSnapshot)
}

internal data class PrivateRentileKeyClaim(
    val privateKey: RentilePrivateKey,
    val firstRoute: ResourceRouteKey,
    val usable: Boolean,
)

internal data class CanonicalIdentityRecord(
    val resourceKey: ResourceKey,
    val canonicalBytes: CanonicalBytes,
)

internal sealed interface ResourceRouteOutcome {
    data object Success : ResourceRouteOutcome

    data class Failure(val failure: FailureDescriptor) : ResourceRouteOutcome

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : ResourceRouteOutcome
}

internal data class BufferedRouteOutcome(
    val ordinal: Long,
    val outcome: ResourceRouteOutcome,
) {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal sealed interface ResourceTerminalSelection {
    data class Route(
        val ordinal: Long,
        val outcome: ResourceRouteOutcome,
    ) : ResourceTerminalSelection {
        init {
            require(ordinal >= 0L) { "route ordinal must be non-negative" }
        }
    }

    data class External(
        val cancellation: CancellationSelection,
    ) : ResourceTerminalSelection
}

internal sealed interface ResourceOperationEvent

internal class ChildrenDiscovered(
    val parentOccurrenceId: ResourceOccurrenceId,
    children: List<DiscoveredResourceChild>,
) : ResourceOperationEvent {
    private val childSnapshot: List<DiscoveredResourceChild> =
        freshListCopy(children).sortedWith(resourceChildComparator)

    init {
        for (index in 1 until childSnapshot.size) {
            require(
                resourceChildComparator.compare(childSnapshot[index - 1], childSnapshot[index]) != 0,
            ) { "discovered child traversal descriptors must be distinguishable" }
        }
    }

    val children: List<DiscoveredResourceChild>
        get() = freshListCopy(childSnapshot)
}

internal data class RouteReadyForDiscovery(
    val ordinal: Long,
    val parentOccurrenceId: ResourceOccurrenceId,
) : ResourceOperationEvent {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class RouteCompleted(
    val ordinal: Long,
    val outcome: ResourceRouteOutcome,
) : ResourceOperationEvent {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class ExternalCancellationRequested(
    val cancellation: CancellationSelection,
) : ResourceOperationEvent

internal data class CleanupCancellationObserved(
    val ordinal: Long,
) : ResourceOperationEvent {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal sealed interface ResourceOperationAction

internal data class StartRoute(
    val ordinal: Long,
    val registration: ResourceRouteRegistration,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class DiscoverChildren(
    val ordinal: Long,
    val parentOccurrenceId: ResourceOccurrenceId,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class CancelRoute(
    val ordinal: Long,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

private val resourceChildComparator: Comparator<DiscoveredResourceChild> = Comparator { first, second ->
    compareChildTraversal(first.traversal, second.traversal)
}

private fun compareChildTraversal(
    first: ResourceChildTraversal,
    second: ResourceChildTraversal,
): Int {
    val rankComparison = childTraversalRank(first).compareTo(childTraversalRank(second))
    if (rankComparison != 0) return rankComparison

    return when (first) {
        is ResourceChildTraversal.BasemapSprite -> compareSpriteMembers(
            first.member,
            (second as ResourceChildTraversal.BasemapSprite).member,
        )
        is ResourceChildTraversal.BasemapSource -> compareBasemapSources(
            first,
            second as ResourceChildTraversal.BasemapSource,
        )
        is ResourceChildTraversal.DeclaredArray ->
            first.index.compareTo((second as ResourceChildTraversal.DeclaredArray).index)
        is ResourceChildTraversal.ObjectMember -> compareUnsignedUtf8(
            first.exactKey,
            (second as ResourceChildTraversal.ObjectMember).exactKey,
        )
    }
}

private fun childTraversalRank(traversal: ResourceChildTraversal): Int =
    when (traversal) {
        is ResourceChildTraversal.BasemapSprite -> 0
        is ResourceChildTraversal.BasemapSource -> 1
        is ResourceChildTraversal.DeclaredArray -> 2
        is ResourceChildTraversal.ObjectMember -> 3
    }

private fun compareSpriteMembers(first: SpriteMember, second: SpriteMember): Int =
    spriteMemberRank(first).compareTo(spriteMemberRank(second))

private fun spriteMemberRank(member: SpriteMember): Int =
    when (member) {
        SpriteMember.JSON -> 0
        SpriteMember.IMAGE -> 1
    }

private fun compareBasemapSources(
    first: ResourceChildTraversal.BasemapSource,
    second: ResourceChildTraversal.BasemapSource,
): Int {
    val sourceComparison = compareUnsignedUtf8(first.sourceId, second.sourceId)
    if (sourceComparison != 0) return sourceComparison
    return compareBasemapSourceMembers(first.member, second.member)
}

private fun compareBasemapSourceMembers(
    first: BasemapSourceMember,
    second: BasemapSourceMember,
): Int {
    val rankComparison = basemapSourceMemberRank(first).compareTo(basemapSourceMemberRank(second))
    if (rankComparison != 0) return rankComparison
    if (first is BasemapSourceMember.Metadata) return 0

    first as BasemapSourceMember.Tile
    second as BasemapSourceMember.Tile
    return first.lod.compareTo(second.lod)
        .takeIf { it != 0 }
        ?: first.tileY.compareTo(second.tileY).takeIf { it != 0 }
        ?: first.canonicalX.compareTo(second.canonicalX)
}

private fun basemapSourceMemberRank(member: BasemapSourceMember): Int =
    when (member) {
        BasemapSourceMember.Metadata -> 0
        is BasemapSourceMember.Tile -> 1
    }

private fun compareUnsignedUtf8(first: String, second: String): Int {
    val firstBytes = first.encodeToByteArray()
    val secondBytes = second.encodeToByteArray()
    val sharedSize = minOf(firstBytes.size, secondBytes.size)
    for (index in 0 until sharedSize) {
        val comparison = (firstBytes[index].toInt() and 0xff).compareTo(secondBytes[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return firstBytes.size.compareTo(secondBytes.size)
}

internal sealed interface ResourceOperationOutcome {
    data class Failure(val failure: FailureDescriptor) : ResourceOperationOutcome

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : ResourceOperationOutcome
}

internal sealed interface ResourceOperationState {
    class Running(
        val definition: ResourceOperationDefinition,
        occurrences: List<ResourceOccurrence>,
        routeRecords: List<RouteRecord>,
        privateRentileKeyClaims: List<PrivateRentileKeyClaim>,
        identityRecords: List<CanonicalIdentityRecord>,
        val traversal: TraversalState = TraversalState(emptyList(), emptyList(), emptyList()),
        val nextRouteOrdinal: Long = 0L,
        activeRouteOrdinals: List<Long> = emptyList(),
        val nextRetirementOrdinal: Long = 0L,
        bufferedRouteOutcomes: List<BufferedRouteOutcome> = emptyList(),
        val startCeilingOrdinal: Long? = null,
        val terminalSelection: ResourceTerminalSelection? = null,
    ) : ResourceOperationState {
        private val occurrenceSnapshot: List<ResourceOccurrence> = freshListCopy(occurrences)
        private val routeRecordSnapshot: List<RouteRecord> = freshListCopy(routeRecords)
        private val privateRentileKeyClaimSnapshot: List<PrivateRentileKeyClaim> =
            freshListCopy(privateRentileKeyClaims)
        private val identityRecordSnapshot: List<CanonicalIdentityRecord> = freshListCopy(identityRecords)
        private val activeRouteOrdinalSnapshot: List<Long> = freshListCopy(activeRouteOrdinals)
        private val bufferedRouteOutcomeSnapshot: List<BufferedRouteOutcome> =
            freshListCopy(bufferedRouteOutcomes)

        init {
            require(nextRouteOrdinal >= 0L) { "next route ordinal must be non-negative" }
            require(nextRetirementOrdinal in 0L..nextRouteOrdinal) {
                "next retirement ordinal must not exceed assigned route ordinals"
            }

            val occurrenceIds = mutableSetOf<ResourceOccurrenceId>()
            require(occurrenceSnapshot.all { occurrenceIds.add(it.id) }) {
                "resource occurrence IDs must be unique"
            }
            val identityStableIds = mutableSetOf<String>()
            require(identityRecordSnapshot.all { identityStableIds.add(it.resourceKey.stableId) }) {
                "canonical identity stable IDs must be unique"
            }
            val privateKeys = mutableSetOf<RentilePrivateKey>()
            require(privateRentileKeyClaimSnapshot.all { privateKeys.add(it.privateKey) }) {
                "private Rentile key claims must be unique"
            }

            val assignedOrdinals = mutableSetOf<Long>()
            val runningOrdinals = mutableSetOf<Long>()
            routeRecordSnapshot.forEach { record ->
                val joinedIds = record.joinedOccurrenceIds
                require(joinedIds.toSet().size == joinedIds.size && joinedIds.all(occurrenceIds::contains)) {
                    "joined resource occurrence IDs must be unique and registered"
                }
                record.ordinal?.let { ordinal ->
                    require(ordinal >= 0L && ordinal < nextRouteOrdinal && assignedOrdinals.add(ordinal)) {
                        "route ordinals must be unique and assigned below the next ordinal"
                    }
                    if (record.status == ResourceRouteStatus.RUNNING) runningOrdinals += ordinal
                }
                require(
                    when (record.status) {
                        ResourceRouteStatus.PREREGISTERED -> record.ordinal == null
                        ResourceRouteStatus.ELIGIBLE,
                        ResourceRouteStatus.RUNNING,
                        ResourceRouteStatus.RESOLVED,
                        -> record.ordinal != null
                        ResourceRouteStatus.BLOCKED_BY_COLLISION -> true
                    },
                ) { "route status must agree with ordinal assignment" }
            }

            require(activeRouteOrdinalSnapshot.size <= definition.maximumConcurrentRoutes) {
                "active routes must not exceed configured concurrency"
            }
            val activeOrdinalSet = activeRouteOrdinalSnapshot.toSet()
            require(activeOrdinalSet.size == activeRouteOrdinalSnapshot.size) {
                "active route ordinals must be distinct"
            }
            require(activeOrdinalSet == runningOrdinals) {
                "active route ordinals must correspond exactly to running routes"
            }

            val bufferedOrdinals = mutableSetOf<Long>()
            require(bufferedRouteOutcomeSnapshot.all { buffered ->
                buffered.ordinal >= nextRetirementOrdinal &&
                    buffered.ordinal < nextRouteOrdinal &&
                    bufferedOrdinals.add(buffered.ordinal)
            }) { "buffered route outcomes must be distinct unretired assigned ordinals" }
            require(activeOrdinalSet.none(bufferedOrdinals::contains)) {
                "active routes cannot already have buffered outcomes"
            }

            startCeilingOrdinal?.let { ceiling ->
                require(ceiling >= 0L && ceiling < nextRouteOrdinal) {
                    "start ceiling must name an assigned route ordinal"
                }
            }
            if (terminalSelection is ResourceTerminalSelection.Route) {
                require(terminalSelection.ordinal < nextRetirementOrdinal) {
                    "selected route terminal must already be retired"
                }
                require(activeOrdinalSet.all { it > terminalSelection.ordinal }) {
                    "selected route terminal may clean up only higher active routes"
                }
            }
        }

        val occurrences: List<ResourceOccurrence>
            get() = freshListCopy(occurrenceSnapshot)

        val routeRecords: List<RouteRecord>
            get() = freshListCopy(routeRecordSnapshot)

        val privateRentileKeyClaims: List<PrivateRentileKeyClaim>
            get() = freshListCopy(privateRentileKeyClaimSnapshot)

        val identityRecords: List<CanonicalIdentityRecord>
            get() = freshListCopy(identityRecordSnapshot)

        val activeRouteOrdinals: List<Long>
            get() = freshListCopy(activeRouteOrdinalSnapshot)

        val bufferedRouteOutcomes: List<BufferedRouteOutcome>
            get() = freshListCopy(bufferedRouteOutcomeSnapshot)
    }
}

internal class ResourceOperationTransition(
    val state: ResourceOperationState.Running?,
    actions: List<ResourceOperationAction>,
    val outcome: ResourceOperationOutcome?,
) {
    private val actionSnapshot: List<ResourceOperationAction> = freshListCopy(actions)

    val actions: List<ResourceOperationAction>
        get() = freshListCopy(actionSnapshot)
}
