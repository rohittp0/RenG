package com.rohittp.reng.internal.driver

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.internal.cache.Lease
import com.rohittp.reng.internal.cache.ResidentCache
import com.rohittp.reng.internal.firewall.ProductionRentilePrivateKeyResolver
import com.rohittp.reng.internal.firewall.basemapEngineHost
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.maximumBytesFor
import com.rohittp.reng.internal.planning.StaticResourceReference
import com.rohittp.reng.internal.resource.ResolvedResourceContent
import com.rohittp.reng.internal.resource.ResourceClassGate
import com.rohittp.reng.internal.resource.SuppliedValidationOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `PreparationDriver.leaseSteadyStateResident`, the steady-state shortcut. Every reference it bypasses
 * never reaches the scheduler session, so the driver's shadow check cannot see it; these cases are what
 * pin which references qualify and what a bypass leaves behind (a lease in the sink, observed after it is
 * recorded, once per route) -- and that anything the operation would decide differently is kept for it.
 */
class SteadyStateShortcutTest {
    private val now = 1_700_000_000_000L

    @Test
    fun aResidentFreshReferenceWhoseGatesHavePassedIsLeasedAndLeftOutOfTheOperation() {
        val fixture = Fixture()
        val sticker = fixture.resident(external(ResourceClass.STICKER_IMAGE, "a"))
        val observed = mutableListOf<Lease>()

        val remaining = fixture.shortcut(listOf(sticker), observer = { observed += it })

        assertTrue(remaining.isEmpty(), "nothing is left for the operation")
        val lease = fixture.sink.single()
        assertSame(lease, observed.single(), "the lease is observed for admission exactly once")
        assertEquals(sticker.resourceKey, lease.generation.key)
        assertEquals(1, lease.generation.leaseCount)
    }

    @Test
    fun aReferenceWithAGateNotYetPassedIsKeptAndItsTrialLeaseReleased() {
        val fixture = Fixture(remembersPasses = false)
        val sticker = fixture.resident(external(ResourceClass.STICKER_IMAGE, "b"))

        val remaining = fixture.shortcut(listOf(sticker))

        assertEquals(listOf(sticker), remaining)
        assertTrue(fixture.sink.isEmpty())
        assertEquals(0, fixture.cache.current(sticker.resourceKey)!!.leaseCount, "the trial lease was released")
    }

    @Test
    fun aStaleReferenceIsKeptButCacheOnlyTakesItResidentRegardless() {
        val fixture = Fixture()
        val sticker = fixture.resident(external(ResourceClass.STICKER_IMAGE, "c"), freshUntil = now - 1L)

        assertEquals(listOf(sticker), fixture.shortcut(listOf(sticker)), "NORMAL revalidates a stale resource")
        assertTrue(fixture.shortcut(listOf(sticker), accessMode = ResourceAccessMode.CACHE_ONLY).isEmpty())
    }

    @Test
    fun reloadBypassesNothing() {
        val fixture = Fixture()
        val sticker = fixture.resident(external(ResourceClass.STICKER_IMAGE, "d"))

        assertEquals(listOf(sticker), fixture.shortcut(listOf(sticker), accessMode = ResourceAccessMode.RELOAD))
        assertTrue(fixture.sink.isEmpty())
    }

    @Test
    fun twoRoutesClaimingOnePrivateKeyBypassNothingSoTheOperationReportsTheAmbiguity() {
        val fixture = Fixture()
        val first = fixture.resident(external(ResourceClass.STICKER_IMAGE, "e"))
        val second = fixture.resident(external(ResourceClass.STICKER_IMAGE, "f"))
        val clashing = second.copy(privateRentileKey = first.privateRentileKey)

        assertEquals(listOf(first, clashing), fixture.shortcut(listOf(first, clashing)))
        assertTrue(fixture.sink.isEmpty())
    }

    @Test
    fun oneRouteNamedTwiceIsLeasedOnce() {
        val fixture = Fixture()
        val sticker = fixture.resident(external(ResourceClass.STICKER_IMAGE, "g"))

        assertTrue(fixture.shortcut(listOf(sticker, sticker.copy())).isEmpty())
        assertEquals(1, fixture.sink.size, "route joining: one lease however many references name the route")
    }

    @Test
    fun aReferenceNotResidentIsKept() {
        val fixture = Fixture()
        val absent = external(ResourceClass.STICKER_IMAGE, "h")

        assertEquals(listOf(absent), fixture.shortcut(listOf(absent)))
        assertTrue(fixture.sink.isEmpty())
    }

    @Test
    fun aRefusedAdmissionLeavesTheLeaseInTheSinkForTheCallersRollback() {
        val fixture = Fixture()
        val sticker = fixture.resident(external(ResourceClass.STICKER_IMAGE, "i"))

        assertFailsWith<IllegalStateException> {
            fixture.shortcut(listOf(sticker), observer = { error("over the prepared-frame CPU budget") })
        }
        assertEquals(1, fixture.sink.size, "recorded before observed, as the executor records an install")
    }

    @Test
    fun aGlbBypassesOnlyWhenBothOfItsGatesHavePassed() {
        val glb = external(ResourceClass.MODEL_GLB, "j")
        val onlyParsed = Fixture(passed = setOf(ResourceClassGate.PARSE_GLB))
        assertEquals(listOf(glb), onlyParsed.shortcut(listOf(onlyParsed.resident(glb))))
        val both = Fixture(passed = setOf(ResourceClassGate.PARSE_GLB, ResourceClassGate.VALIDATE_GLB_FEATURES))
        assertTrue(both.shortcut(listOf(both.resident(glb))).isEmpty())
    }

    private inner class Fixture(
        remembersPasses: Boolean = true,
        passed: Set<ResourceClassGate> = ResourceClassGate.entries.toSet(),
    ) {
        val cache = ResidentCache()
        val sink = mutableListOf<Lease>()
        private val runner = RememberingGateRunner(if (remembersPasses) passed else emptySet())
        private val driver = PreparationDriver(
            transport = UnusedTransport,
            store = UnusedStore,
            cache = cache,
            classGateRunner = runner,
            resourceLimits = ResourceLimits(),
            basemapEngineHost = basemapEngineHost(cache = cache),
            maximumConcurrentOperations = 8,
            clock = { now },
        )

        fun resident(
            reference: StaticResourceReference.External,
            freshUntil: Long = Long.MAX_VALUE,
        ): StaticResourceReference.External {
            cache.install(
                reference.resourceKey,
                StoredRawResource(
                    bytes = byteArrayOf(1, 2, 3),
                    contentDigest = "d".repeat(64),
                    metadata = StoredRawResourceMetadata(freshUntilEpochMillis = freshUntil, storedAtEpochMillis = 0L),
                ),
                decoded = null,
            )
            return reference
        }

        fun shortcut(
            references: List<StaticResourceReference.External>,
            accessMode: ResourceAccessMode = ResourceAccessMode.NORMAL,
            observer: (Lease) -> Unit = {},
        ): List<StaticResourceReference.External> =
            driver.leaseSteadyStateResident(references, accessMode, sink, observer)
    }
}

/** Remembers [passed] gates as passed for every digest; `run` is never reached by the shortcut. */
private class RememberingGateRunner(private val passed: Set<ResourceClassGate>) : ClassGateRunner {
    override suspend fun run(gate: ResourceClassGate, content: ResolvedResourceContent): SuppliedValidationOutcome =
        error("the shortcut must not run a gate")

    override fun hasPassed(gate: ResourceClassGate, contentDigest: String): Boolean = gate in passed
}

private object UnusedTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse = error("the shortcut must not fetch")
}

private object UnusedStore : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? = error("the shortcut must not read the Store")

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) = error("the shortcut must not write")
}

private fun external(resourceClass: ResourceClass, marker: String): StaticResourceReference.External {
    val locator = ResourceLocator("https://shortcut.example/$marker")
    val derived = ResourceKeyDeriver(PureKotlinSha256).external(resourceClass, locator)
    return StaticResourceReference.External(
        resourceClass = resourceClass,
        locator = locator,
        maximumResponseBytes = ResourceLimits().maximumBytesFor(resourceClass),
        resourceKey = derived.key,
        rawKey = requireNotNull(derived.rawKey),
        privateRentileKey = ProductionRentilePrivateKeyResolver(PureKotlinSha256).resolve(locator, resourceClass),
        canonicalIdentity = derived.identity,
    )
}
