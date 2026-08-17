package com.rohittp.reng.internal.resource

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
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

internal enum class ContentProvenance {
    RESIDENT,
    STORE,
    TRANSPORT_200,
    TRANSPORT_304_MERGED,
}

internal data class ResolvedResourceContent(
    val route: ResourceRouteKey,
    val resourceKey: ResourceKey,
    val stored: StoredRawResource,
    val provenance: ContentProvenance,
) {
    init {
        require(resourceKey.resourceClass == route.resourceClass) {
            "resolved content must match its route resource class"
        }
    }
}

internal data class LookupProgress(
    val sampleEpochMillis: Long?,
    val resident: StoredRawResource?,
    val staleBaseline: StoredRawResource?,
    val storeReadStarted: Boolean,
    val transportLatch: TransportLatchKey?,
    val selectedContent: ResolvedResourceContent?,
) {
    init {
        require(sampleEpochMillis == null || sampleEpochMillis >= 0L) {
            "freshness sample must be non-negative"
        }
        if (sampleEpochMillis == null) {
            require(
                resident == null && staleBaseline == null && !storeReadStarted &&
                    transportLatch == null && selectedContent == null,
            ) { "lookup work requires a freshness sample" }
        }
        require(staleBaseline == null || storeReadStarted) {
            "a stale baseline requires a started Store read"
        }
    }
}

internal sealed interface SuppliedCallOutcome<out T> {
    data class Success<T>(val value: T) : SuppliedCallOutcome<T>

    data object Failed : SuppliedCallOutcome<Nothing>

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : SuppliedCallOutcome<Nothing> {
        init {
            require(cancellation.cause == CancellationCause.ADAPTER) {
                "supplied call cancellation must originate from an adapter"
            }
        }
    }
}

internal sealed interface SuppliedValidationOutcome {
    data object Valid : SuppliedValidationOutcome

    data object Failed : SuppliedValidationOutcome

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : SuppliedValidationOutcome {
        init {
            require(cancellation.cause == CancellationCause.ADAPTER) {
                "supplied validation cancellation must originate from an adapter"
            }
        }
    }
}

internal sealed interface SuppliedInstallOutcome {
    data object Succeeded : SuppliedInstallOutcome

    data class Failed(val failure: FailureDescriptor) : SuppliedInstallOutcome

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : SuppliedInstallOutcome {
        init {
            require(cancellation.cause == CancellationCause.ADAPTER) {
                "supplied install cancellation must originate from an adapter"
            }
        }
    }
}

internal sealed interface LatchedTransportOutcome {
    data class Response(val response: TransportResponse) : LatchedTransportOutcome

    data object Failed : LatchedTransportOutcome

    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : LatchedTransportOutcome {
        init {
            require(cancellation.cause == CancellationCause.ADAPTER) {
                "latched cancellation must originate from an adapter"
            }
        }
    }
}

internal data class TransportLatchRecord(
    val key: TransportLatchKey,
    val outcome: LatchedTransportOutcome,
)

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

internal data class AwaitingClockSample(
    val actionId: ResourceActionId,
    val ordinal: Long,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class AwaitingResident(
    val actionId: ResourceActionId,
    val ordinal: Long,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class AwaitingStoreRead(
    val actionId: ResourceActionId,
    val ordinal: Long,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class AwaitingTransport(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val latchKey: TransportLatchKey,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class AwaitingLatchedTransportReplay(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val latchKey: TransportLatchKey,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class PendingClassGates(
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal enum class ResourceClassGate {
    PARSE_TILEJSON,
    DECODE_VECTOR_TILE,
    DECODE_PNG,
    VALIDATE_DEM_TERRAIN_ENCODING,
    PARSE_GEOJSON,
    PARSE_GLB,
    VALIDATE_GLB_FEATURES,
}

internal fun ordinaryResourceClassGates(resourceClass: ResourceClass): List<ResourceClassGate>? =
    when (resourceClass) {
        ResourceClass.BASEMAP_TILE_JSON -> listOf(ResourceClassGate.PARSE_TILEJSON)
        ResourceClass.BASEMAP_VECTOR_TILE -> listOf(ResourceClassGate.DECODE_VECTOR_TILE)
        ResourceClass.BASEMAP_RASTER_TILE -> listOf(ResourceClassGate.DECODE_PNG)
        ResourceClass.BASEMAP_DEM_TILE -> listOf(
            ResourceClassGate.DECODE_PNG,
            ResourceClassGate.VALIDATE_DEM_TERRAIN_ENCODING,
        )
        ResourceClass.BASEMAP_GEO_JSON -> listOf(ResourceClassGate.PARSE_GEOJSON)
        ResourceClass.STICKER_IMAGE -> listOf(ResourceClassGate.DECODE_PNG)
        ResourceClass.MODEL_TEXTURE -> listOf(ResourceClassGate.DECODE_PNG)
        ResourceClass.MODEL_GLB -> listOf(
            ResourceClassGate.PARSE_GLB,
            ResourceClassGate.VALIDATE_GLB_FEATURES,
        )
        ResourceClass.BASEMAP_STYLE,
        ResourceClass.BASEMAP_SPRITE_JSON,
        ResourceClass.BASEMAP_SPRITE_IMAGE,
        -> null
    }

internal fun requiresStoreWrite(provenance: ContentProvenance): Boolean =
    when (provenance) {
        ContentProvenance.RESIDENT,
        ContentProvenance.STORE,
        -> false
        ContentProvenance.TRANSPORT_200,
        ContentProvenance.TRANSPORT_304_MERGED,
        -> true
    }

internal data class AwaitingClassGate(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
    val gate: ResourceClassGate,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class AwaitingStoreWrite(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class AwaitingVisibilityInstall(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal class RouteRecord(
    val registration: ResourceRouteRegistration,
    joinedOccurrenceIds: List<ResourceOccurrenceId>,
    val ordinal: Long?,
    val cursor: ResourceRouteCursor?,
    val status: ResourceRouteStatus,
    val lookup: LookupProgress? = null,
    val visibilityInstalled: Boolean = false,
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
    ) : ResourceRouteOutcome {
        init {
            require(cancellation.cause == CancellationCause.ADAPTER) {
                "route cancellation must originate from an adapter"
            }
        }
    }
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
            require(outcome !is ResourceRouteOutcome.Success) {
                "route terminal selection must be a failure or cancellation"
            }
        }
    }

    data class External(
        val cancellation: CancellationSelection,
    ) : ResourceTerminalSelection {
        init {
            require(
                cancellation.cause == CancellationCause.CALLER ||
                    cancellation.cause == CancellationCause.CANCEL_PREPARATIONS,
            ) { "external terminal must originate from caller or cancelPreparations" }
        }
    }
}

internal sealed interface ResourceOperationEvent

internal data class ClockSampled(
    val actionId: ResourceActionId,
    val sampleEpochMillis: Long,
) : ResourceOperationEvent {
    init {
        require(sampleEpochMillis >= 0L) { "freshness sample must be non-negative" }
    }
}

internal data class ResidentObserved(
    val actionId: ResourceActionId,
    val resource: StoredRawResource?,
) : ResourceOperationEvent

internal data class StoreReadCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedCallOutcome<StoredRawResource?>,
) : ResourceOperationEvent

internal data class TransportCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedCallOutcome<TransportResponse>,
) : ResourceOperationEvent

internal data class LatchedTransportReplayCompleted(
    val actionId: ResourceActionId,
) : ResourceOperationEvent

internal data class AdvancePendingClassGates(
    val ordinal: Long,
) : ResourceOperationEvent {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class ResourceClassValidationCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedValidationOutcome,
) : ResourceOperationEvent

internal data class StoreWriteCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedCallOutcome<Unit>,
) : ResourceOperationEvent

internal data class VisibilityInstallCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedInstallOutcome,
) : ResourceOperationEvent

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
) : ResourceOperationEvent {
    init {
        require(
            cancellation.cause == CancellationCause.CALLER ||
                cancellation.cause == CancellationCause.CANCEL_PREPARATIONS,
        ) { "external cancellation must originate from caller or cancelPreparations" }
    }
}

internal data class CleanupCancellationObserved(
    val ordinal: Long,
) : ResourceOperationEvent {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal sealed interface ResourceOperationAction

internal data class SampleClock(
    val actionId: ResourceActionId,
    val ordinal: Long,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class ObserveResident(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val resourceKey: ResourceKey,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class ReadStore(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val rawKey: RawResourceKey,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class CallTransport(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val request: TransportRequest,
    val latchKey: TransportLatchKey,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
        require(
            latchKey.route.locator == request.locator &&
                latchKey.route.resourceClass == request.resourceClass &&
                latchKey.route.maximumResponseBytes == request.maximumResponseBytes,
        ) { "Transport request must match its latch route" }
        require(request.metadata.ifNoneMatch == latchKey.ifNoneMatch) {
            "Transport request ETag must match its latch"
        }
        require(request.metadata.ifModifiedSince == latchKey.ifModifiedSince) {
            "Transport request last-modified value must match its latch"
        }
        require(request.metadata.accept == latchKey.accept) {
            "Transport request accept value must match its latch"
        }
    }
}

internal data class ReplayLatchedTransport(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val latch: TransportLatchRecord,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class ValidateResourceClass(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
    val gate: ResourceClassGate,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class WriteStore(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val rawKey: RawResourceKey,
    val resource: StoredRawResource,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

internal data class InstallVisibility(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceOperationAction {
    init {
        require(ordinal >= 0L) { "route ordinal must be non-negative" }
    }
}

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

internal sealed interface ResponseRuleOutcome {
    data class Selected(val content: ResolvedResourceContent) : ResponseRuleOutcome

    data class Failure(val failure: FailureDescriptor) : ResponseRuleOutcome
}

internal data class VisibleResource(
    val resourceKey: ResourceKey,
    val content: ResolvedResourceContent,
) {
    init {
        require(resourceKey == content.resourceKey) {
            "a visible resource must carry its own content identity"
        }
    }
}

internal class OwnerResourceSet(
    val ownerId: ResourceOwnerId,
    resources: List<VisibleResource>,
) {
    private val resourceSnapshot: List<VisibleResource> = freshListCopy(resources)

    val resources: List<VisibleResource>
        get() = freshListCopy(resourceSnapshot)

    override fun equals(other: Any?): Boolean =
        other is OwnerResourceSet && ownerId == other.ownerId && resourceSnapshot == other.resourceSnapshot

    override fun hashCode(): Int = 31 * ownerId.hashCode() + resourceSnapshot.hashCode()

    override fun toString(): String =
        "OwnerResourceSet(ownerId=$ownerId, resourceCount=${resourceSnapshot.size})"
}

internal sealed interface ResourceOperationOutcome {
    class Success(
        resourceSets: List<OwnerResourceSet>,
    ) : ResourceOperationOutcome {
        private val resourceSetSnapshot: List<OwnerResourceSet> = freshListCopy(resourceSets)

        val resourceSets: List<OwnerResourceSet>
            get() = freshListCopy(resourceSetSnapshot)

        override fun equals(other: Any?): Boolean =
            other is Success && resourceSetSnapshot == other.resourceSetSnapshot

        override fun hashCode(): Int = resourceSetSnapshot.hashCode()

        override fun toString(): String = "Success(resourceSetCount=${resourceSetSnapshot.size})"
    }

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
        transportLatches: List<TransportLatchRecord> = emptyList(),
        val nextActionId: Long = 1L,
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
        private val transportLatchSnapshot: List<TransportLatchRecord> = freshListCopy(transportLatches)
        private val activeRouteOrdinalSnapshot: List<Long> = freshListCopy(activeRouteOrdinals)
        private val bufferedRouteOutcomeSnapshot: List<BufferedRouteOutcome> =
            freshListCopy(bufferedRouteOutcomes)

        init {
            require(nextRouteOrdinal >= 0L) { "next route ordinal must be non-negative" }
            require(nextActionId > 0L) { "next action ID must be positive" }
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
            val transportLatchKeys = mutableSetOf<TransportLatchKey>()
            require(transportLatchSnapshot.all { transportLatchKeys.add(it.key) }) {
                "Transport latch keys must be unique"
            }

            val assignedOrdinals = mutableSetOf<Long>()
            val runningOrdinals = mutableSetOf<Long>()
            val registeredRoutes = mutableSetOf<ResourceRouteKey>()
            val cursorActionIds = mutableSetOf<ResourceActionId>()
            routeRecordSnapshot.forEach { record ->
                registeredRoutes += record.registration.route
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

                val lookup = record.lookup
                lookup?.transportLatch?.let { latchKey ->
                    require(latchKey.route == record.registration.route) {
                        "lookup latch must belong to its route"
                    }
                }
                lookup?.selectedContent?.let { selected ->
                    require(
                        selected.route == record.registration.route &&
                            selected.resourceKey == record.registration.resourceKey,
                    ) { "selected content must belong to its registered route" }
                }
                require(!record.visibilityInstalled || lookup?.selectedContent != null) {
                    "installed visibility requires selected content"
                }

                record.cursor?.let { cursor ->
                    val ordinal = requireNotNull(record.ordinal) {
                        "a route cursor requires an assigned ordinal"
                    }
                    require(record.status == ResourceRouteStatus.RUNNING) {
                        "a route cursor requires a running route"
                    }
                    require(cursorOrdinal(cursor) == ordinal) {
                        "route cursor ordinal must match its record"
                    }
                    cursorActionId(cursor)?.let { actionId ->
                        require(actionId.value < nextActionId && cursorActionIds.add(actionId)) {
                            "cursor action IDs must be unique and allocated below the next action ID"
                        }
                    }
                    when (cursor) {
                        is AwaitingClockSample -> require(
                            lookup != null && lookup.sampleEpochMillis == null,
                        ) { "clock cursor requires an unsampled lookup" }
                        is AwaitingResident -> require(lookup?.sampleEpochMillis != null) {
                            "resident cursor requires a freshness sample"
                        }
                        is AwaitingStoreRead -> require(
                            lookup?.sampleEpochMillis != null && lookup.storeReadStarted,
                        ) { "Store cursor requires a sampled started read" }
                        is AwaitingTransport -> require(
                            lookup?.transportLatch == cursor.latchKey &&
                                cursor.latchKey !in transportLatchKeys,
                        ) { "Transport cursor requires one open matching latch" }
                        is AwaitingLatchedTransportReplay -> require(
                            lookup?.transportLatch == cursor.latchKey &&
                                cursor.latchKey in transportLatchKeys,
                        ) { "replay cursor requires one closed matching latch" }
                        is PendingClassGates -> require(
                            lookup?.selectedContent == cursor.content,
                        ) { "pending class gates require matching selected content" }
                        is AwaitingClassGate -> require(
                            lookup?.selectedContent == cursor.content &&
                                ordinaryResourceClassGates(cursor.content.route.resourceClass)
                                    ?.contains(cursor.gate) == true,
                        ) { "class gate cursor requires matching content and an ordinary class gate" }
                        is AwaitingStoreWrite -> require(
                            lookup?.selectedContent == cursor.content &&
                                requiresStoreWrite(cursor.content.provenance),
                        ) { "Store write cursor requires matching content that must be written" }
                        is AwaitingVisibilityInstall -> require(
                            lookup?.selectedContent == cursor.content,
                        ) { "visibility install cursor requires matching selected content" }
                    }
                }
                if (record.status == ResourceRouteStatus.RUNNING && lookup != null) {
                    require(record.cursor != null) { "started lookup requires a route cursor" }
                }
            }
            require(transportLatchSnapshot.all { it.key.route in registeredRoutes }) {
                "Transport latches must belong to registered routes"
            }
            require(assignedOrdinals.size.toLong() == nextRouteOrdinal) {
                "assigned route ordinals must form the complete contiguous range"
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

            var previousBufferedOrdinal: Long? = null
            require(bufferedRouteOutcomeSnapshot.all { buffered ->
                val previous = previousBufferedOrdinal
                val valid = buffered.ordinal >= nextRetirementOrdinal &&
                    buffered.ordinal < nextRouteOrdinal &&
                    (previous == null || previous < buffered.ordinal)
                previousBufferedOrdinal = buffered.ordinal
                valid
            }) { "buffered route outcomes must be strictly ascending unretired assigned ordinals" }
            val bufferedOrdinals = bufferedRouteOutcomeSnapshot.mapTo(mutableSetOf()) { it.ordinal }
            require(activeOrdinalSet.none(bufferedOrdinals::contains)) {
                "active routes cannot already have buffered outcomes"
            }

            startCeilingOrdinal?.let { ceiling ->
                require(ceiling >= 0L && ceiling < nextRouteOrdinal) {
                    "start ceiling must name an assigned route ordinal"
                }
            }
            if (terminalSelection is ResourceTerminalSelection.Route) {
                require(terminalSelection.outcome !is ResourceRouteOutcome.Success) {
                    "selected route terminal must be a failure or cancellation"
                }
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

        val transportLatches: List<TransportLatchRecord>
            get() = freshListCopy(transportLatchSnapshot)

        val activeRouteOrdinals: List<Long>
            get() = freshListCopy(activeRouteOrdinalSnapshot)

        val bufferedRouteOutcomes: List<BufferedRouteOutcome>
            get() = freshListCopy(bufferedRouteOutcomeSnapshot)
    }
}

private fun cursorOrdinal(cursor: ResourceRouteCursor): Long = when (cursor) {
    is AwaitingClockSample -> cursor.ordinal
    is AwaitingResident -> cursor.ordinal
    is AwaitingStoreRead -> cursor.ordinal
    is AwaitingTransport -> cursor.ordinal
    is AwaitingLatchedTransportReplay -> cursor.ordinal
    is PendingClassGates -> cursor.ordinal
    is AwaitingClassGate -> cursor.ordinal
    is AwaitingStoreWrite -> cursor.ordinal
    is AwaitingVisibilityInstall -> cursor.ordinal
}

private fun cursorActionId(cursor: ResourceRouteCursor): ResourceActionId? = when (cursor) {
    is AwaitingClockSample -> cursor.actionId
    is AwaitingResident -> cursor.actionId
    is AwaitingStoreRead -> cursor.actionId
    is AwaitingTransport -> cursor.actionId
    is AwaitingLatchedTransportReplay -> cursor.actionId
    is PendingClassGates -> null
    is AwaitingClassGate -> cursor.actionId
    is AwaitingStoreWrite -> cursor.actionId
    is AwaitingVisibilityInstall -> cursor.actionId
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
