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
 * the O(routes) a linear driver would show.
 *
 * This isolates that rebuild floor alone, not HANDOFF.md's other two named costs: every route here takes
 * a unique [ResourceOwnerId], so `OwnerResourceSet` never exceeds one element and the O(owners ×
 * occurrences) style-owner barrier is never exercised; every occurrence uses [ResourceCommitBinding.Single],
 * so the shared style/sprite group path is never taken; and the transport body is four bytes, not a
 * realistic ~50 KB tile, so full-payload content re-hashing is never exercised either. Both remain
 * extrapolations; only the rebuild floor above is a direct measurement.
 *
 * A 2026-08-19 review found this test's own driver had contributed a second, avoidable O(routes²) cost of
 * its own, stacked on the reducer's: [advanceAllPendingClassGates] re-scanned the full `routeRecords` list
 * from scratch after every action, restarting at index 0 each time it found a parked route. That scan is
 * gone — see the comments on [advanceAllPendingClassGates] and its caller for why naming the ordinal
 * directly off the [CallTransport] action that produces it is exact, not approximate, for this benchmark's
 * fixed event mapping. Re-running the fixed driver on the same host reproduced successive-doubling ratios
 * of roughly 3.9, 4.1, and 4.4 — inside the same at-or-above-quadratic range reported above — confirming
 * the reducer's own per-event rebuild, not the test's driving loop, produced these numbers. That re-run
 * shared its host with heavy concurrent load (`uptime` reported load averages above 140 against 14
 * physical cores, and `top` reported 0% idle CPU throughout both runs), which inflated every absolute
 * figure by roughly 7x uniformly across all four route counts without changing the doubling-ratio shape;
 * the raw contended-host figures are recorded in the task report rather than here so they are never
 * mistaken for a clean-host comparison against the guard below.
 *
 * The guard below no longer asserts an absolute-millisecond ceiling. A ceiling calibrated to this
 * machine's absolute speed (a prior version used a 50-second ceiling with ~49% headroom above the worst
 * run recorded above) failed on hosted CI runners that are simply slower at CPU-bound work than the
 * development machine it was calibrated on — raising the number only relocates the same problem to
 * whatever host is slower next. The successive-doubling ratio measured throughout this KDoc (~3.5-4.6x,
 * at or above the quadratic signature of 4x and well below the ~8x a cubic regression would produce) is
 * machine-independent: a slower host scales every measurement in proportion and leaves the ratio intact.
 * The test now asserts that ratio instead (see the comment above the assertion in the test body for the
 * exact threshold and why it sits there).
 *
 * The top route count also dropped from 512 to 256. The ratio is what this test exists to check, not the
 * absolute scale, and the 128-to-256 ratio (still the two longest-running, least noise-prone measurements
 * available at that top end) already shows the same at-or-above-quadratic shape that 256-to-512 did above
 * — so measuring out to 512 bought no additional signal for roughly 25-30 extra seconds on every run, on
 * every platform, on every build.
 */
class ResourceOperationScaleBenchmarkTest {
    @Test
    fun drivesManyDistinctRoutesToCompletionAndReportsCost() {
        // Existing scheduling tests register many routes but never drive one past StartRoute,
        // and the lookup test drives 4096 occurrences joined onto ONE route. Neither pays the
        // per-route event multiplier, which is why the cost is unexercised rather than absent.
        //
        // Top-end route count is 256, not 512: see the class KDoc for why the 128->256 ratio already
        // isolates the property under test as well as 256->512 did, at a fraction of the wall-clock cost.
        val routeCounts = listOf(64, 128, 256)
        val elapsedMillisByRouteCount = LinkedHashMap<Int, Long>()
        for (routeCount in routeCounts) {
            val elapsed = driveDistinctRoutesToCompletion(routeCount)
            println("routes=$routeCount elapsedMillis=$elapsed")
            elapsedMillisByRouteCount[routeCount] = elapsed
        }

        // Successive-doubling ratios: elapsed(2n) / elapsed(n) for each consecutive pair in routeCounts.
        // A zero or negative elapsed measurement (possible on a very fast machine or a coarse monotonic
        // clock) makes a ratio undefined rather than merely small or large, so fail with a clear message
        // instead of dividing by — or into — it.
        val doublingRatios = routeCounts.zipWithNext { smaller, larger ->
            val smallerElapsed = elapsedMillisByRouteCount.getValue(smaller)
            val largerElapsed = elapsedMillisByRouteCount.getValue(larger)
            require(smallerElapsed > 0L && largerElapsed > 0L) {
                "cannot compute a doubling ratio from a non-positive elapsed measurement: " +
                    "routes=$smaller took ${smallerElapsed}ms, routes=$larger took ${largerElapsed}ms"
            }
            largerElapsed.toDouble() / smallerElapsed.toDouble()
        }
        println("doubling ratios for route counts $routeCounts: $doublingRatios")

        // Base the assertion on the LARGEST pair (128 -> 256), not the smallest (64 -> 128): the 64-route
        // case finishes in roughly half a second on the reference host, where scheduler jitter and JIT/
        // native warmup are a large fraction of the total elapsed time, making its ratio comparatively
        // unstable run to run. The 128->256 pair runs long enough (roughly 1.7s -> 7s on the reference
        // host) that the same absolute jitter is a much smaller fraction of the total, making it the more
        // reliable signal of the underlying growth rate.
        val largestPairRatio = doublingRatios.last()

        // This guard checks the SHAPE of the growth, not its absolute speed, so it survives a move to
        // slower or faster hardware unchanged: a slower host scales every measurement in proportion and
        // leaves the ratio intact, unlike an absolute-millisecond ceiling. The reducer's own per-event
        // O(routes + occurrences) rebuild (see the class KDoc) doubles routes into roughly a 4x
        // elapsed-time increase; a cubic regression would double into roughly 8x. Real runs on the
        // reference host measured ratios of roughly 3.5x-4.6x for both the 64->128 and 128->256 pairs
        // (see the class KDoc). 6.0x sits near the geometric mean of 4 and 8 — comfortably above every
        // observed near-quadratic ratio (about 30% headroom over the highest, 4.6x) while still well below
        // the ~8x a cubic regression would produce, so it separates the two shapes clearly without being
        // tuned tightly to the last observed run. This is a check for an algorithmic regression, not a
        // performance target, so it is deliberately loose.
        assertTrue(
            largestPairRatio < 6.0,
            "distinct-route scheduling cost regressed: doubling routes from 128 to 256 scaled elapsed " +
                "time by ${largestPairRatio}x (ceiling 6.0x; quadratic scaling implies ~4x, cubic ~8x); " +
                "measurements: $elapsedMillisByRouteCount",
        )
    }

    private fun driveDistinctRoutesToCompletion(routeCount: Int): Long {
        val definition = definitionOfDistinctStickerRoutes(routeCount)
        val started = TimeSource.Monotonic.markNow()
        val pending = ArrayDeque<ResourceOperationAction>()
        // Ordinals of routes known to need an AdvancePendingClassGates event, in the order their
        // CallTransport action resolved. See the note on advanceAllPendingClassGates below for why
        // this queue — populated in O(1) per action rather than discovered by scanning route state —
        // is exact for this benchmark's fixed event mapping.
        val pendingClassGateOrdinals = ArrayDeque<Long>()
        var transition = ResourceOperationStateMachine.start(definition)
        pending.addAll(transition.actions)
        transition = advanceAllPendingClassGates(transition, pending, pendingClassGateOrdinals)
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
            // Second deviation: once a route's response is resolved, the reducer parks it in a
            // PendingClassGates cursor and waits for an explicit AdvancePendingClassGates(ordinal)
            // event — it is not among transition.actions, so nothing in the action queue would ever
            // produce it. Every existing commit test drives this by hand (search "AdvancePendingClassGates"
            // in ResourceOperationOrdinaryCommitTest.kt); the benchmark driver must do the same.
            //
            // Which action parks a route there is exact, not guessed, for this benchmark's fixed
            // eventFor mapping: residentObserved always sees resource = null, so it never selects
            // content directly; storeReadCompleted always sees Success(null), so under NORMAL access
            // mode it always falls through to requestTransport rather than selecting content; and
            // every route uses a distinct locator, so CallTransport is never replayed from a closed
            // latch. That leaves transportCompleted's SuppliedCallOutcome.Success branch — which,
            // given a 200 status and a small non-empty body, resolveTransportResponse always resolves
            // to ResponseRuleOutcome.Selected (see resolveFullResponse in ResourceResponseRules.kt) —
            // as the only path into PendingClassGates. So naming the ordinal directly off the
            // CallTransport action that triggered it is O(1) and exact, with no route-list scan
            // needed. advancePendingClassGates's own precondition (cursor is PendingClassGates at
            // that same ordinal) still enforces this if this benchmark's event mapping ever changes.
            if (action is CallTransport) {
                pendingClassGateOrdinals.addLast(action.ordinal)
            }
            pending.addAll(transition.actions)
            transition = advanceAllPendingClassGates(transition, pending, pendingClassGateOrdinals)
        }
        require(transition.outcome is ResourceOperationOutcome.Success) {
            "benchmark must drive every route to a successful completion, not merely register them"
        }
        return started.elapsedNow().inWholeMilliseconds
    }

    /**
     * Advances every route named in [pendingOrdinals] until the queue is drained, queuing each
     * advance's own emitted actions (its first class gate's [ValidateResourceClass]) for the normal
     * loop.
     *
     * This used to find parked routes by scanning `state.routeRecords` for `it.cursor is
     * PendingClassGates`, restarting from index 0 after every route it found — an O(routes) scan
     * repeated on the order of `routes` times across a run, contributing an O(routes²) cost from
     * the TEST HARNESS ITSELF, stacked on top of whatever the reducer costs. That defeated the
     * point of this benchmark: it exists to show the reducer's own cost, not the driver's. The
     * queue-based version above tracks exactly which ordinals need advancing (see the comment at
     * the CallTransport check in the caller) so this function never inspects route state at all.
     */
    private fun advanceAllPendingClassGates(
        transitionIn: ResourceOperationTransition,
        pending: ArrayDeque<ResourceOperationAction>,
        pendingOrdinals: ArrayDeque<Long>,
    ): ResourceOperationTransition {
        var current = transitionIn
        while (true) {
            val state = current.state ?: return current
            val ordinal = pendingOrdinals.removeFirstOrNull() ?: return current
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
