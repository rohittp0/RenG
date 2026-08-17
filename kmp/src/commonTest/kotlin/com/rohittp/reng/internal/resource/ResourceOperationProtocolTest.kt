package com.rohittp.reng.internal.resource

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.identity.CanonicalBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ResourceOperationProtocolTest {
    @Test
    fun routeLatchAndRegistrationUseStructuralEqualityAndRedactedText() {
        val locatorText = "https://signed.example/resource?credential=locator-secret"
        val rawStableId = "b".repeat(64)
        val privateToken = "opaque-rentile-private-token"
        val canonicalText = "canonical-resource-secret"
        val etag = "credential-bearing-etag"
        val lastModified = "credential-bearing-last-modified"
        val accept = "application/credential-bearing-format"

        val route = ResourceRouteKey(
            accessMode = ResourceAccessMode.NORMAL,
            locator = ResourceLocator(locatorText),
            resourceClass = ResourceClass.MODEL_GLB,
            maximumResponseBytes = 4096L,
        )
        val equalRoute = ResourceRouteKey(
            accessMode = ResourceAccessMode.NORMAL,
            locator = ResourceLocator(locatorText),
            resourceClass = ResourceClass.MODEL_GLB,
            maximumResponseBytes = 4096L,
        )
        val latch = TransportLatchKey(route, etag, lastModified, accept)
        val equalLatch = TransportLatchKey(equalRoute, etag, lastModified, accept)
        val registration = ResourceRouteRegistration(
            route = route,
            resourceKey = externalKey("a", ResourceClass.MODEL_GLB),
            rawKey = RawResourceKey(rawStableId, ResourceClass.MODEL_GLB),
            privateRentileKey = RentilePrivateKey(privateToken),
            canonicalBytes = CanonicalBytes(canonicalText.encodeToByteArray()),
        )
        val equalRegistration = ResourceRouteRegistration(
            route = equalRoute,
            resourceKey = externalKey("a", ResourceClass.MODEL_GLB),
            rawKey = RawResourceKey(rawStableId, ResourceClass.MODEL_GLB),
            privateRentileKey = RentilePrivateKey(privateToken),
            canonicalBytes = CanonicalBytes(canonicalText.encodeToByteArray()),
        )
        val occurrence = ResourceOccurrence(
            id = ResourceOccurrenceId(1L),
            ownerId = ResourceOwnerId(2L),
            registration = registration,
            discoveryRequired = true,
            commitBinding = ResourceCommitBinding.BasemapStyle(StyleGroupId(3L)),
        )
        val identity = CanonicalIdentityRecord(registration.resourceKey, registration.canonicalBytes)
        val definition = ResourceOperationDefinition(1, listOf(occurrence), listOf(identity))
        val routeRecord = RouteRecord(
            registration = registration,
            joinedOccurrenceIds = listOf(occurrence.id),
            ordinal = null,
            cursor = null,
            status = ResourceRouteStatus.PREREGISTERED,
        )
        val claim = PrivateRentileKeyClaim(registration.privateRentileKey, route, usable = true)
        val running = ResourceOperationState.Running(
            definition = definition,
            occurrences = listOf(occurrence),
            routeRecords = listOf(routeRecord),
            privateRentileKeyClaims = listOf(claim),
            identityRecords = listOf(identity),
        )
        val transition = ResourceOperationTransition(running, emptyList(), outcome = null)

        assertEquals(route, equalRoute)
        assertEquals(route.hashCode(), equalRoute.hashCode())
        assertEquals(latch, equalLatch)
        assertEquals(latch.hashCode(), equalLatch.hashCode())
        assertEquals(registration, equalRegistration)
        assertEquals(registration.hashCode(), equalRegistration.hashCode())

        val textValues = listOf(
            route,
            equalRoute,
            latch,
            equalLatch,
            registration,
            equalRegistration,
            occurrence,
            identity,
            definition,
            routeRecord,
            claim,
            running,
            transition,
        ).map(Any::toString)
        val secrets = listOf(
            locatorText,
            rawStableId,
            privateToken,
            canonicalText,
            etag,
            lastModified,
            accept,
        )
        textValues.forEach { text ->
            secrets.forEach { secret ->
                assertFalse(text.contains(secret), "protocol text disclosed a secret")
            }
        }
        assertTrue(registration.toString().contains("ResourceKey"))
    }

    @Test
    fun everyProtocolListSnapshotsInputAndReturnsFreshCopies() {
        val first = occurrence(1L, "first")
        val second = occurrence(2L, "second")
        val firstIdentity = CanonicalIdentityRecord(
            first.registration.resourceKey,
            first.registration.canonicalBytes,
        )
        val secondIdentity = CanonicalIdentityRecord(
            second.registration.resourceKey,
            second.registration.canonicalBytes,
        )
        val occurrenceInput = mutableListOf(first)
        val identityInput = mutableListOf(firstIdentity)
        val definition = ResourceOperationDefinition(2, occurrenceInput, identityInput)
        occurrenceInput += second
        identityInput += secondIdentity

        assertFreshCopy(definition.staticOccurrences, definition.staticOccurrences, listOf(first))
        assertFreshCopy(definition.resourceIdentities, definition.resourceIdentities, listOf(firstIdentity))

        val joinedInput = mutableListOf(first.id)
        val routeRecord = RouteRecord(
            registration = first.registration,
            joinedOccurrenceIds = joinedInput,
            ordinal = null,
            cursor = null,
            status = ResourceRouteStatus.PREREGISTERED,
        )
        joinedInput += second.id
        assertFreshCopy(routeRecord.joinedOccurrenceIds, routeRecord.joinedOccurrenceIds, listOf(first.id))

        val routeRecordsInput = mutableListOf(routeRecord)
        val claim = PrivateRentileKeyClaim(
            first.registration.privateRentileKey,
            first.registration.route,
            usable = true,
        )
        val claimsInput = mutableListOf(claim)
        val stateOccurrencesInput = mutableListOf(first)
        val stateIdentitiesInput = mutableListOf(firstIdentity)
        val running = ResourceOperationState.Running(
            definition = definition,
            occurrences = stateOccurrencesInput,
            routeRecords = routeRecordsInput,
            privateRentileKeyClaims = claimsInput,
            identityRecords = stateIdentitiesInput,
        )
        stateOccurrencesInput += second
        routeRecordsInput.clear()
        claimsInput.clear()
        stateIdentitiesInput += secondIdentity

        assertFreshCopy(running.occurrences, running.occurrences, listOf(first))
        assertFreshCopy(running.routeRecords, running.routeRecords, listOf(routeRecord))
        assertFreshCopy(running.privateRentileKeyClaims, running.privateRentileKeyClaims, listOf(claim))
        assertFreshCopy(running.identityRecords, running.identityRecords, listOf(firstIdentity))

        val transition = ResourceOperationTransition(running, mutableListOf(), outcome = null)
        val firstActions = transition.actions
        val secondActions = transition.actions
        assertNotSame(firstActions, secondActions)
        (firstActions as MutableList<ResourceOperationAction>).clear()
        assertTrue(transition.actions.isEmpty())
    }

    @Test
    fun idsAndConcurrencyRequirePositiveValuesWithoutEchoingRejectedValues() {
        val invalidValue = -37L
        val failures = listOf(
            assertFailsWith<IllegalArgumentException> { CancellationId(invalidValue) },
            assertFailsWith<IllegalArgumentException> { ResourceOwnerId(invalidValue) },
            assertFailsWith<IllegalArgumentException> { ResourceOccurrenceId(invalidValue) },
            assertFailsWith<IllegalArgumentException> { SpriteGroupId(invalidValue) },
            assertFailsWith<IllegalArgumentException> { StyleGroupId(invalidValue) },
            assertFailsWith<IllegalArgumentException> { ResourceActionId(invalidValue) },
            assertFailsWith<IllegalArgumentException> {
                ResourceOperationDefinition(invalidValue.toInt(), emptyList(), emptyList())
            },
        )

        failures.forEach { failure ->
            assertFalse(failure.message.orEmpty().contains(invalidValue.toString()))
        }
        ResourceOperationDefinition(1, emptyList(), emptyList())
    }

    private fun occurrence(id: Long, marker: String): ResourceOccurrence {
        val resourceClass = ResourceClass.STICKER_IMAGE
        val route = ResourceRouteKey(
            accessMode = ResourceAccessMode.NORMAL,
            locator = ResourceLocator("$marker-locator"),
            resourceClass = resourceClass,
            maximumResponseBytes = 1024L,
        )
        return ResourceOccurrence(
            id = ResourceOccurrenceId(id),
            ownerId = ResourceOwnerId(id),
            registration = ResourceRouteRegistration(
                route = route,
                resourceKey = externalKey(if (id == 1L) "1" else "2", resourceClass),
                rawKey = RawResourceKey((if (id == 1L) "3" else "4").repeat(64), resourceClass),
                privateRentileKey = RentilePrivateKey("$marker-private-key"),
                canonicalBytes = CanonicalBytes("$marker-canonical".encodeToByteArray()),
            ),
            discoveryRequired = false,
            commitBinding = ResourceCommitBinding.Single,
        )
    }

    private fun externalKey(marker: String, resourceClass: ResourceClass): ResourceKey = ResourceKey(
        kind = ResourceKind.EXTERNAL,
        stableId = marker.repeat(64),
        resourceClass = resourceClass,
    )

    private fun <T> assertFreshCopy(first: List<T>, second: List<T>, expected: List<T>) {
        assertEquals(expected, first)
        assertEquals(expected, second)
        assertNotSame(first, second)
        (first as MutableList<T>).clear()
        assertEquals(expected, second)
    }
}
