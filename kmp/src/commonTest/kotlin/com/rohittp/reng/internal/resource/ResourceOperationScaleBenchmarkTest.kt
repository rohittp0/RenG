package com.rohittp.reng.internal.resource

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.internal.identity.CanonicalBytes
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * HANDOFF.md's deferred item 3 extrapolated a Θ(events × (routes + occurrences)) scheduler cost from unit
 * test timings, but nothing in the existing suite pays that cost directly: the scheduling tests register
 * many routes without driving one past [ResourceOperationStateMachine.start], and
 * [ResourceOperationLookupTest] drives 4096 *occurrences joined onto one route* — the linear, already-cheap
 * case. This test drives many DISTINCT routes to completion, which is the case the extrapolation was about,
 * and records real elapsed time so the eventual scheduler optimisation has a measurement to beat.
 *
 * Measured 2026-08-19 on a real run (`./gradlew --no-configuration-cache --rerun-tasks :kmp:macosArm64Test
 * --tests "com.rohittp.reng.internal.resource.ResourceOperationScaleBenchmarkTest"`, Apple Silicon macOS
 * host): routes=64 498ms, routes=128 1724ms, routes=256 6975ms, routes=512 29052ms (a repeat run measured
 * 503/1838/7274/33622ms — the same shape, with the ordinary variance of a wall-clock benchmark).
 * Successive-doubling ratios are ~3.5, ~4.0, and ~4.2 — at or above the quadratic signature (ratio 4), not
 * the linear one (ratio 2), which confirms the reducer's per-event O(routes + occurrences) rebuild, at
 * roughly nine events per route, compounds into effectively O(routes²) (or worse) total work rather than
 * the O(routes) a linear driver would show. The real 512-route number is ~5.8x-6.7x HANDOFF.md's prior
 * *extrapolated* ~5-second estimate for this exact scenario — the extrapolation undercounted the true cost.
 */
class ResourceOperationScaleBenchmarkTest {
    @Test
    fun drivesManyDistinctRoutesToCompletionAndReportsCost() {
        // Existing scheduling tests register many routes but never drive one past StartRoute,
        // and the lookup test drives 4096 occurrences joined onto ONE route. Neither pays the
        // per-route event multiplier, which is why the cost is unexercised rather than absent.
        var elapsedAt512 = -1L
        for (routeCount in listOf(64, 128, 256, 512)) {
            val elapsed = driveDistinctRoutesToCompletion(routeCount)
            println("routes=$routeCount elapsedMillis=$elapsed")
            if (routeCount == 512) elapsedAt512 = elapsed
        }
        // Guard, not a target: three real runs of this exact 512-route scenario measured 29052-33622ms,
        // roughly 5.8x-6.7x HANDOFF.md's prior *extrapolated* ~5-second estimate — the extrapolation
        // undercounted the true cost, so the ceiling below is anchored to the observed figure (worst of the
        // three runs) rather than to that estimate. The ~49% margin above the worst observed run (33622ms)
        // is headroom for slower CI hardware, not room for a further regression: extrapolating the 256-route
        // worst run (7274ms) forward at a cubic rather than the observed near-quadratic rate — i.e. an 8x
        // rather than ~4.2-4.6x doubling ratio — predicts ~58192ms for 512 routes, which this ceiling still
        // catches, while a ceiling loosened to comfortably clear any plausible run (e.g. several minutes)
        // would catch nothing short of runaway behaviour.
        assertTrue(
            elapsedAt512 < 50_000L,
            "distinct-route scheduling cost regressed: routes=512 took ${elapsedAt512}ms, ceiling is 50000ms",
        )
    }

    private fun driveDistinctRoutesToCompletion(routeCount: Int): Long {
        val definition = definitionOfDistinctStickerRoutes(routeCount)
        val started = TimeSource.Monotonic.markNow()
        val pending = ArrayDeque<ResourceOperationAction>()
        var transition = ResourceOperationStateMachine.start(definition)
        pending.addAll(transition.actions)
        transition = advanceAllPendingClassGates(transition, pending)
        while (transition.outcome == null) {
            val action = pending.removeFirstOrNull() ?: break
            // transition() is a two-argument function on the ResourceOperationStateMachine object,
            // not a method reachable through the previous transition's result.
            val runningState = requireNotNull(transition.state) { "no outcome yet but state is null" }
            // Deviation from the brief's harness: start() (and later route retirement) emits a
            // StartRoute action per newly active route, not an event-bearing action. The real driver
            // path is ResourceOperationStateMachine.beginLookup(state, ordinal) for that one action
            // kind; every other action in this sticker-only definition maps through eventFor/transition.
            transition = if (action is StartRoute) {
                ResourceOperationStateMachine.beginLookup(runningState, action.ordinal)
            } else {
                ResourceOperationStateMachine.transition(runningState, eventFor(action))
            }
            pending.addAll(transition.actions)
            // Second deviation: once a route's response is resolved, the reducer parks it in a
            // PendingClassGates cursor and waits for an explicit AdvancePendingClassGates(ordinal)
            // event — it is not among transition.actions, so nothing in the action queue would ever
            // produce it. Every existing commit test drives this by hand (search "AdvancePendingClassGates"
            // in ResourceOperationOrdinaryCommitTest.kt); the benchmark driver must do the same.
            transition = advanceAllPendingClassGates(transition, pending)
        }
        require(transition.outcome is ResourceOperationOutcome.Success) {
            "benchmark must drive every route to a successful completion, not merely register them"
        }
        return started.elapsedNow().inWholeMilliseconds
    }

    /** Advances every route currently parked at [PendingClassGates] until none remain, queuing each
     *  advance's own emitted actions (its first class gate's [ValidateResourceClass]) for the normal loop. */
    private fun advanceAllPendingClassGates(
        transitionIn: ResourceOperationTransition,
        pending: ArrayDeque<ResourceOperationAction>,
    ): ResourceOperationTransition {
        var current = transitionIn
        while (true) {
            val state = current.state ?: return current
            val ordinal = state.routeRecords.firstOrNull { it.cursor is PendingClassGates }?.ordinal
                ?: return current
            current = ResourceOperationStateMachine.transition(state, AdvancePendingClassGates(ordinal))
            pending.addAll(current.actions)
        }
    }

    /** One supplied outcome per action, always the success path, so the measurement is of
     *  scheduling cost alone rather than of failure arbitration. */
    private fun eventFor(action: ResourceOperationAction): ResourceOperationEvent = when (action) {
        is SampleClock -> ClockSampled(action.actionId, sampleEpochMillis = 1_700_000_000_000L)
        is ObserveResident -> ResidentObserved(action.actionId, resource = null)
        is ReadStore -> StoreReadCompleted(action.actionId, SuppliedCallOutcome.Success(null))
        is CallTransport -> TransportCompleted(action.actionId, SuppliedCallOutcome.Success(okResponse()))
        is ValidateResourceClass -> ResourceClassValidationCompleted(action.actionId, SuppliedValidationOutcome.Valid)
        is WriteStore -> StoreWriteCompleted(action.actionId, SuppliedCallOutcome.Success(Unit))
        // SuppliedInstallOutcome's success case is `Succeeded`, a data object with no payload.
        is InstallVisibility -> VisibilityInstallCompleted(action.actionId, SuppliedInstallOutcome.Succeeded)
        else -> error("the sticker-only definition emits no other action: $action")
    }

    private fun okResponse(): TransportResponse = TransportResponse(
        statusCode = 200,
        body = byteArrayOf(1, 2, 3, 4),
    )

    /**
     * [routeCount] distinct sticker routes: one occurrence per route, no joining, no discovery, no sprite
     * or style commit binding. This is the shape the existing suite never exercises — every route pays its
     * own full ~nine-event lifecycle rather than sharing one route's lifecycle across many occurrences.
     * All routes are made concurrently eligible so the benchmark measures the reducer's per-event rebuild
     * cost against route/occurrence count, not an artificial concurrency-cap serialization.
     */
    private fun definitionOfDistinctStickerRoutes(routeCount: Int): ResourceOperationDefinition {
        val occurrences = (0 until routeCount).map { index -> stickerOccurrence(index) }
        return ResourceOperationDefinition(
            maximumConcurrentRoutes = routeCount,
            staticOccurrences = occurrences,
            resourceIdentities = occurrences.map {
                CanonicalIdentityRecord(it.registration.resourceKey, it.registration.canonicalBytes)
            },
        )
    }

    private fun stickerOccurrence(index: Int): ResourceOccurrence {
        val id = ResourceOccurrenceId((index + 1).toLong())
        return ResourceOccurrence(
            id = id,
            ownerId = ResourceOwnerId((index + 1).toLong()),
            registration = stickerRegistration(index),
            discoveryRequired = false,
            commitBinding = ResourceCommitBinding.Single,
        )
    }

    private fun stickerRegistration(index: Int): ResourceRouteRegistration {
        val stableId = (index + 1).toString(16).padStart(64, '0')
        val rawStableId = (index + 1_000_000).toString(16).padStart(64, '0')
        return ResourceRouteRegistration(
            route = ResourceRouteKey(
                accessMode = ResourceAccessMode.NORMAL,
                locator = ResourceLocator("scale-benchmark-sticker-$index"),
                resourceClass = ResourceClass.STICKER_IMAGE,
                maximumResponseBytes = 4096L,
            ),
            resourceKey = ResourceKey(ResourceKind.EXTERNAL, stableId, ResourceClass.STICKER_IMAGE),
            rawKey = RawResourceKey(rawStableId, ResourceClass.STICKER_IMAGE),
            privateRentileKey = RentilePrivateKey("scale-benchmark-private-$index"),
            canonicalBytes = CanonicalBytes("scale-benchmark-canonical-$index".encodeToByteArray()),
        )
    }
}
