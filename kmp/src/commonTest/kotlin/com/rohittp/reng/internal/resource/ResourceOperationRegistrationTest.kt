package com.rohittp.reng.internal.resource

import com.rohittp.reng.DiagnosticCode
import com.rohittp.reng.DiagnosticSeverity
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.identity.CanonicalBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceOperationRegistrationTest {
    @Test
    fun equalStaticRoutesJoinWithoutOrdinalCursorOrWork() {
        val firstRegistration = registration(
            route = route("shared-locator"),
            resourceKey = externalKey("1", ResourceClass.STICKER_IMAGE),
            rawKey = rawKey("2", ResourceClass.STICKER_IMAGE),
            privateKey = RentilePrivateKey("shared-private-key"),
            canonicalText = "shared-canonical-bytes",
        )
        val equalRegistration = registration(
            route = route("shared-locator"),
            resourceKey = externalKey("1", ResourceClass.STICKER_IMAGE),
            rawKey = rawKey("2", ResourceClass.STICKER_IMAGE),
            privateKey = RentilePrivateKey("shared-private-key"),
            canonicalText = "shared-canonical-bytes",
        )
        val first = occurrence(1L, 11L, firstRegistration)
        val second = occurrence(2L, 12L, equalRegistration)
        val firstIdentity = identity(firstRegistration)
        val equalIdentity = identity(equalRegistration)

        val transition = ResourceOperationStateMachine.preRegister(
            ResourceOperationDefinition(
                maximumConcurrentRoutes = 2,
                staticOccurrences = listOf(first, second),
                resourceIdentities = listOf(firstIdentity, equalIdentity),
            ),
        )

        assertNull(transition.outcome)
        assertTrue(transition.actions.isEmpty())
        val state = assertNotNull(transition.state)
        assertEquals(listOf(first, second), state.occurrences)
        assertEquals(listOf(firstIdentity), state.identityRecords)
        assertEquals(1, state.routeRecords.size)
        val routeRecord = state.routeRecords.single()
        assertEquals(firstRegistration, routeRecord.registration)
        assertEquals(listOf(first.id, second.id), routeRecord.joinedOccurrenceIds)
        assertNull(routeRecord.ordinal)
        assertNull(routeRecord.cursor)
        assertEquals(ResourceRouteStatus.PREREGISTERED, routeRecord.status)
        assertEquals(
            listOf(PrivateRentileKeyClaim(firstRegistration.privateRentileKey, firstRegistration.route, true)),
            state.privateRentileKeyClaims,
        )
    }

    @Test
    fun equalConsumerRawKeysNeverCreatePrivateRouteAmbiguity() {
        val sharedRawKey = rawKey("3", ResourceClass.STICKER_IMAGE)
        val firstRegistration = registration(
            route = route("first-locator"),
            resourceKey = externalKey("4", ResourceClass.STICKER_IMAGE),
            rawKey = sharedRawKey,
            privateKey = RentilePrivateKey("first-private-key"),
            canonicalText = "first-canonical",
        )
        val secondRegistration = registration(
            route = route("second-locator"),
            resourceKey = externalKey("5", ResourceClass.STICKER_IMAGE),
            rawKey = sharedRawKey,
            privateKey = RentilePrivateKey("second-private-key"),
            canonicalText = "second-canonical",
        )

        val transition = ResourceOperationStateMachine.preRegister(
            definition(firstRegistration, secondRegistration),
        )

        assertNull(transition.outcome)
        assertTrue(transition.actions.isEmpty())
        val state = assertNotNull(transition.state)
        assertEquals(2, state.routeRecords.size)
        assertTrue(state.routeRecords.all { it.status == ResourceRouteStatus.PREREGISTERED })
        assertEquals(2, state.privateRentileKeyClaims.size)
        assertTrue(state.privateRentileKeyClaims.all(PrivateRentileKeyClaim::usable))
    }

    @Test
    fun equalPrivateRentileKeyOnDistinctRoutesFailsAndMarksClaimUnusable() {
        val privateKey = RentilePrivateKey("colliding-private-key")
        val firstRegistration = registration(
            route = route("first-locator"),
            resourceKey = externalKey("6", ResourceClass.STICKER_IMAGE),
            rawKey = rawKey("7", ResourceClass.STICKER_IMAGE),
            privateKey = privateKey,
            canonicalText = "first-canonical",
        )
        val secondRegistration = registration(
            route = route("second-locator"),
            resourceKey = externalKey("8", ResourceClass.STICKER_IMAGE),
            rawKey = rawKey("9", ResourceClass.STICKER_IMAGE),
            privateKey = RentilePrivateKey("colliding-private-key"),
            canonicalText = "second-canonical",
        )

        val transition = ResourceOperationStateMachine.preRegister(
            definition(firstRegistration, secondRegistration),
        )

        assertTrue(transition.actions.isEmpty())
        assertFailure(
            transition = transition,
            code = RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
            resourceClass = null,
            resourceKey = null,
        )
        val state = assertNotNull(transition.state)
        assertEquals(2, state.routeRecords.size)
        assertEquals(
            listOf(ResourceRouteStatus.PREREGISTERED, ResourceRouteStatus.BLOCKED_BY_COLLISION),
            state.routeRecords.map(RouteRecord::status),
        )
        assertEquals(
            listOf(PrivateRentileKeyClaim(privateKey, firstRegistration.route, usable = false)),
            state.privateRentileKeyClaims,
        )
        assertEquals(2, state.identityRecords.size)
        assertEquals(2, state.occurrences.size)
    }

    @Test
    fun externalDigestCollisionRetainsFirstTraversalIdentityAndEstablishedExternalKey() {
        val stableDigest = "a".repeat(64)
        val firstKey = ResourceKey(ResourceKind.EXTERNAL, stableDigest, ResourceClass.MODEL_GLB)
        val collidingKey = ResourceKey(ResourceKind.EXTERNAL, stableDigest, ResourceClass.MODEL_TEXTURE)
        val firstRegistration = registration(
            route = route("first-model", ResourceClass.MODEL_GLB),
            resourceKey = firstKey,
            rawKey = rawKey("b", ResourceClass.MODEL_GLB),
            privateKey = RentilePrivateKey("first-model-private-key"),
            canonicalText = "first-external-canonical",
        )
        val collidingRegistration = registration(
            route = route("second-model", ResourceClass.MODEL_TEXTURE),
            resourceKey = collidingKey,
            rawKey = rawKey("c", ResourceClass.MODEL_TEXTURE),
            privateKey = RentilePrivateKey("second-model-private-key"),
            canonicalText = "different-external-canonical",
        )
        val firstIdentity = identity(firstRegistration)

        val transition = ResourceOperationStateMachine.preRegister(
            definition(firstRegistration, collidingRegistration),
        )

        assertTrue(transition.actions.isEmpty())
        assertFailure(
            transition = transition,
            code = RenGErrorCode.IDENTITY_COLLISION,
            resourceClass = ResourceClass.MODEL_GLB,
            resourceKey = firstKey,
        )
        val state = assertNotNull(transition.state)
        assertEquals(listOf(firstIdentity), state.identityRecords)
        assertTrue(state.routeRecords.isEmpty())
        assertTrue(state.privateRentileKeyClaims.isEmpty())
        assertEquals(2, state.occurrences.size)
    }

    @Test
    fun geometryProgramDigestCollisionRetainsFirstIdentityAndNullClass() {
        val stableDigest = "d".repeat(64)
        val firstKey = ResourceKey(ResourceKind.GEOMETRY_PROGRAM, stableDigest, null)
        val collidingKey = ResourceKey(ResourceKind.GEOMETRY_PROGRAM, stableDigest, null)
        val firstIdentity = CanonicalIdentityRecord(
            firstKey,
            CanonicalBytes("first-geometry-program".encodeToByteArray()),
        )
        val collidingIdentity = CanonicalIdentityRecord(
            collidingKey,
            CanonicalBytes("different-geometry-program".encodeToByteArray()),
        )

        val transition = ResourceOperationStateMachine.preRegister(
            ResourceOperationDefinition(
                maximumConcurrentRoutes = 1,
                staticOccurrences = emptyList(),
                resourceIdentities = listOf(firstIdentity, collidingIdentity),
            ),
        )

        assertTrue(transition.actions.isEmpty())
        assertFailure(
            transition = transition,
            code = RenGErrorCode.IDENTITY_COLLISION,
            resourceClass = null,
            resourceKey = firstKey,
        )
        val state = assertNotNull(transition.state)
        assertEquals(listOf(firstIdentity), state.identityRecords)
        assertTrue(state.occurrences.isEmpty())
        assertTrue(state.routeRecords.isEmpty())
        assertTrue(state.privateRentileKeyClaims.isEmpty())
    }

    private fun definition(
        firstRegistration: ResourceRouteRegistration,
        secondRegistration: ResourceRouteRegistration,
    ): ResourceOperationDefinition {
        val first = occurrence(1L, 11L, firstRegistration)
        val second = occurrence(2L, 12L, secondRegistration)
        return ResourceOperationDefinition(
            maximumConcurrentRoutes = 2,
            staticOccurrences = listOf(first, second),
            resourceIdentities = listOf(identity(firstRegistration), identity(secondRegistration)),
        )
    }

    private fun assertFailure(
        transition: ResourceOperationTransition,
        code: RenGErrorCode,
        resourceClass: ResourceClass?,
        resourceKey: ResourceKey?,
    ) {
        val outcome = assertIs<ResourceOperationOutcome.Failure>(transition.outcome)
        assertEquals(code, outcome.failure.code)
        assertEquals(PipelineStage.RESOURCE_LOOKUP, outcome.failure.stage)
        val diagnostic = assertNotNull(outcome.failure.diagnostic)
        assertEquals(DiagnosticCode.FAILURE_CONTEXT, diagnostic.code)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(PipelineStage.RESOURCE_LOOKUP, diagnostic.stage)
        assertEquals("resource", diagnostic.fieldName)
        assertEquals(resourceClass, diagnostic.resourceClass)
        assertEquals(resourceKey, diagnostic.resourceKey)
        assertNull(diagnostic.statusCode)
        assertNull(diagnostic.limit)
        assertNull(diagnostic.actual)
    }

    private fun route(
        locator: String,
        resourceClass: ResourceClass = ResourceClass.STICKER_IMAGE,
    ): ResourceRouteKey = ResourceRouteKey(
        accessMode = ResourceAccessMode.NORMAL,
        locator = ResourceLocator(locator),
        resourceClass = resourceClass,
        maximumResponseBytes = 4096L,
    )

    private fun registration(
        route: ResourceRouteKey,
        resourceKey: ResourceKey,
        rawKey: RawResourceKey,
        privateKey: RentilePrivateKey,
        canonicalText: String,
    ): ResourceRouteRegistration = ResourceRouteRegistration(
        route = route,
        resourceKey = resourceKey,
        rawKey = rawKey,
        privateRentileKey = privateKey,
        canonicalBytes = CanonicalBytes(canonicalText.encodeToByteArray()),
    )

    private fun occurrence(
        id: Long,
        ownerId: Long,
        registration: ResourceRouteRegistration,
    ): ResourceOccurrence = ResourceOccurrence(
        id = ResourceOccurrenceId(id),
        ownerId = ResourceOwnerId(ownerId),
        registration = registration,
        discoveryRequired = false,
        commitBinding = ResourceCommitBinding.Single,
    )

    private fun identity(registration: ResourceRouteRegistration): CanonicalIdentityRecord =
        CanonicalIdentityRecord(registration.resourceKey, registration.canonicalBytes)

    private fun externalKey(marker: String, resourceClass: ResourceClass): ResourceKey = ResourceKey(
        kind = ResourceKind.EXTERNAL,
        stableId = marker.repeat(64),
        resourceClass = resourceClass,
    )

    private fun rawKey(marker: String, resourceClass: ResourceClass): RawResourceKey =
        RawResourceKey(marker.repeat(64), resourceClass)
}
