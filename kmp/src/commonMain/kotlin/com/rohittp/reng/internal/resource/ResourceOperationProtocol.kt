package com.rohittp.reng.internal.resource

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.freshListCopy
import com.rohittp.reng.internal.identity.CanonicalBytes
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

internal sealed interface ResourceOperationEvent

internal sealed interface ResourceOperationAction

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
    ) : ResourceOperationState {
        private val occurrenceSnapshot: List<ResourceOccurrence> = freshListCopy(occurrences)
        private val routeRecordSnapshot: List<RouteRecord> = freshListCopy(routeRecords)
        private val privateRentileKeyClaimSnapshot: List<PrivateRentileKeyClaim> =
            freshListCopy(privateRentileKeyClaims)
        private val identityRecordSnapshot: List<CanonicalIdentityRecord> = freshListCopy(identityRecords)

        val occurrences: List<ResourceOccurrence>
            get() = freshListCopy(occurrenceSnapshot)

        val routeRecords: List<RouteRecord>
            get() = freshListCopy(routeRecordSnapshot)

        val privateRentileKeyClaims: List<PrivateRentileKeyClaim>
            get() = freshListCopy(privateRentileKeyClaimSnapshot)

        val identityRecords: List<CanonicalIdentityRecord>
            get() = freshListCopy(identityRecordSnapshot)
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
