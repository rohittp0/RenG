package com.rohittp.reng.internal.resource

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic

internal object ResourceOperationStateMachine {
    internal fun preRegister(
        definition: ResourceOperationDefinition,
    ): ResourceOperationTransition {
        val occurrences = definition.staticOccurrences
        val identityRecords = mutableListOf<CanonicalIdentityRecord>()
        val identityByStableId = mutableMapOf<String, CanonicalIdentityRecord>()
        val routeRecords = mutableListOf<RouteRecord>()
        val routeRecordIndexByRoute = mutableMapOf<ResourceRouteKey, Int>()
        val privateRentileKeyClaims = mutableListOf<PrivateRentileKeyClaim>()
        val claimIndexByPrivateKey = mutableMapOf<RentilePrivateKey, Int>()

        definition.resourceIdentities.forEach { attempted ->
            val established = identityByStableId[attempted.resourceKey.stableId]
            when {
                established == null -> {
                    identityRecords += attempted
                    identityByStableId[attempted.resourceKey.stableId] = attempted
                }
                established.canonicalBytes != attempted.canonicalBytes -> {
                    return failed(
                        definition = definition,
                        occurrences = occurrences,
                        routeRecords = routeRecords,
                        privateRentileKeyClaims = privateRentileKeyClaims,
                        identityRecords = identityRecords,
                        failure = identityCollisionFailure(established),
                    )
                }
            }
        }

        occurrences.forEach { occurrence ->
            val registration = occurrence.registration
            val joinedRouteIndex = routeRecordIndexByRoute[registration.route] ?: -1
            if (joinedRouteIndex >= 0) {
                val joined = routeRecords[joinedRouteIndex]
                routeRecords[joinedRouteIndex] = RouteRecord(
                    registration = joined.registration,
                    joinedOccurrenceIds = joined.joinedOccurrenceIds + occurrence.id,
                    ordinal = joined.ordinal,
                    cursor = joined.cursor,
                    status = joined.status,
                )
                return@forEach
            }

            val claimIndex = claimIndexByPrivateKey[registration.privateRentileKey] ?: -1
            if (claimIndex < 0) {
                claimIndexByPrivateKey[registration.privateRentileKey] = privateRentileKeyClaims.size
                privateRentileKeyClaims += PrivateRentileKeyClaim(
                    privateKey = registration.privateRentileKey,
                    firstRoute = registration.route,
                    usable = true,
                )
                routeRecordIndexByRoute[registration.route] = routeRecords.size
                routeRecords += preregisteredRouteRecord(occurrence)
                return@forEach
            }

            val establishedClaim = privateRentileKeyClaims[claimIndex]
            privateRentileKeyClaims[claimIndex] = establishedClaim.copy(usable = false)
            routeRecords += RouteRecord(
                registration = registration,
                joinedOccurrenceIds = listOf(occurrence.id),
                ordinal = null,
                cursor = null,
                status = ResourceRouteStatus.BLOCKED_BY_COLLISION,
            )
            return failed(
                definition = definition,
                occurrences = occurrences,
                routeRecords = routeRecords,
                privateRentileKeyClaims = privateRentileKeyClaims,
                identityRecords = identityRecords,
                failure = ambiguousRouteFailure(),
            )
        }

        return ResourceOperationTransition(
            state = runningState(
                definition = definition,
                occurrences = occurrences,
                routeRecords = routeRecords,
                privateRentileKeyClaims = privateRentileKeyClaims,
                identityRecords = identityRecords,
            ),
            actions = emptyList(),
            outcome = null,
        )
    }

    private fun preregisteredRouteRecord(occurrence: ResourceOccurrence): RouteRecord = RouteRecord(
        registration = occurrence.registration,
        joinedOccurrenceIds = listOf(occurrence.id),
        ordinal = null,
        cursor = null,
        status = ResourceRouteStatus.PREREGISTERED,
    )

    private fun failed(
        definition: ResourceOperationDefinition,
        occurrences: List<ResourceOccurrence>,
        routeRecords: List<RouteRecord>,
        privateRentileKeyClaims: List<PrivateRentileKeyClaim>,
        identityRecords: List<CanonicalIdentityRecord>,
        failure: FailureDescriptor,
    ): ResourceOperationTransition = ResourceOperationTransition(
        state = runningState(
            definition = definition,
            occurrences = occurrences,
            routeRecords = routeRecords,
            privateRentileKeyClaims = privateRentileKeyClaims,
            identityRecords = identityRecords,
        ),
        actions = emptyList(),
        outcome = ResourceOperationOutcome.Failure(failure),
    )

    private fun runningState(
        definition: ResourceOperationDefinition,
        occurrences: List<ResourceOccurrence>,
        routeRecords: List<RouteRecord>,
        privateRentileKeyClaims: List<PrivateRentileKeyClaim>,
        identityRecords: List<CanonicalIdentityRecord>,
    ): ResourceOperationState.Running = ResourceOperationState.Running(
        definition = definition,
        occurrences = occurrences,
        routeRecords = routeRecords,
        privateRentileKeyClaims = privateRentileKeyClaims,
        identityRecords = identityRecords,
    )

    private fun ambiguousRouteFailure(): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
        stage = PipelineStage.RESOURCE_LOOKUP,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_LOOKUP,
            fieldName = DiagnosticField.RESOURCE,
        ),
    )

    private fun identityCollisionFailure(
        established: CanonicalIdentityRecord,
    ): FailureDescriptor = FailureDescriptor(
        code = RenGErrorCode.IDENTITY_COLLISION,
        stage = PipelineStage.RESOURCE_LOOKUP,
        diagnostic = failureContextDiagnostic(
            stage = PipelineStage.RESOURCE_LOOKUP,
            fieldName = DiagnosticField.RESOURCE,
            resourceClass = established.resourceKey.resourceClass,
            resourceKey = established.resourceKey,
        ),
    )
}
