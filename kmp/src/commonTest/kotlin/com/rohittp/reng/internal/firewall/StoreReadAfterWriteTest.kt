package com.rohittp.reng.internal.firewall

import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.Transport
import com.rohittp.reng.RawResourceKey as RenGRawResourceKey
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.rentile.RawResourceKey as EngineRawResourceKey
import com.rohittp.rentile.RawResourceMetadata as EngineRawResourceMetadata
import com.rohittp.rentile.ResourceClass as EngineResourceClass
import com.rohittp.rentile.StoredRawResource as EngineStoredRawResource
import com.rohittp.rentile.TransportRequest as EngineTransportRequest
import com.rohittp.rentile.TransportRequestMetadata as EngineTransportRequestMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/**
 * ADR 0016 erratum: a store read that found nothing is a fact about the Store *then*, not for the
 * registry's lifetime. Rentile's warm probes the Store, fetches, writes, and rasterises inside one
 * registry, so a latched miss made the engine's own acquire report `RAW_CACHE_MISS` for bytes it had
 * written a moment earlier and transport them again.
 */
class StoreReadAfterWriteTest {
    @Test
    fun aReadAfterTheWriteThatFilledTheMissReturnsTheRecord() = runTest {
        val store = KeyedStore()
        val registry = registry(store)
        val key = engineKey()

        assertNull(registry.readStore(key), "nothing is stored yet")
        val response = registry.executeTransport(engineRequest())
        assertEquals(200, response.statusCode)
        registry.writeStore(key, engineRecord(BODY, FRESH))

        val read = assertNotNull(registry.readStore(key), "the write must not be hidden behind the latched miss")
        assertEquals(sha(BODY), read.contentDigest)
        assertEquals(1, store.writes)
    }

    @Test
    fun theWarmProbeForgetsItsMissButKeepsItsHit() = runTest {
        val store = KeyedStore()
        val registry = registry(store)
        val key = engineKey()

        assertNull(registry.probeStore(key))
        assertNull(registry.probeStore(key))
        assertEquals(2, store.reads, "a probe's miss is not latched")

        val response = registry.executeTransport(engineRequest())
        registry.writeStore(key, engineRecord(BODY, FRESH))
        assertNotNull(registry.probeStore(key))
        assertNotNull(registry.readStore(key))
        assertEquals(3, store.reads, "the hit the probe read is replayed to the acquire, not read again")
    }

    @Test
    fun concurrentReadsOfOneRouteStillShareOneStoreRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = KeyedStore(gate)
        val registry = registry(store)
        val key = engineKey()

        val readers = List(8) { async { registry.readStore(key) } }
        yield()
        assertEquals(1, store.reads, "eight in-flight reads are one consumer read")
        gate.complete(Unit)
        readers.forEach { assertNull(it.await()) }
        assertEquals(1, store.reads)
    }

    @Test
    fun aMissIsStillLatchedWhenNothingWasWrittenSoNoStormReopens() = runTest {
        val store = KeyedStore()
        val registry = registry(store)
        repeat(16) { assertNull(registry.readStore(engineKey())) }
        assertEquals(1, store.reads)
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private suspend fun registry(store: Store): OperationRegistry = OperationRegistry(
        transport = FreshTransport(),
        store = store,
        privateKeyResolver = ProductionRentilePrivateKeyResolver(PureKotlinSha256),
    ).also { it.preregister(listOf(ROUTE)) }

    private fun engineKey() = EngineRawResourceKey(sha(redactAuthenticationQuery(URL)), EngineResourceClass.VECTOR_TILE)

    private fun engineRequest() = EngineTransportRequest(
        url = URL,
        resourceClass = EngineResourceClass.VECTOR_TILE,
        maxResponseBytes = ROUTE.maximumResponseBytes,
        metadata = EngineTransportRequestMetadata(),
    )

    private fun engineRecord(bytes: ByteArray, freshUntil: Long?) = EngineStoredRawResource(
        bytes = bytes,
        contentDigest = sha(bytes),
        metadata = EngineRawResourceMetadata(
            contentType = "application/x-protobuf",
            freshUntilEpochMillis = freshUntil,
            storedAtEpochMillis = 0L,
        ),
    )

    private companion object {
        const val URL = "https://tiles.example/v/4/3/5.pbf"
        const val FRESH = 4_000_000_000_000L
        val BODY = ByteArray(32) { (it * 7 + 1).toByte() }
        val ROUTE = ResourceRouteKey(
            accessMode = ResourceAccessMode.NORMAL,
            locator = ResourceLocator(URL),
            resourceClass = ResourceClass.BASEMAP_VECTOR_TILE,
            maximumResponseBytes = 1L shl 20,
        )

        fun sha(bytes: ByteArray) = PureKotlinSha256.digest(CanonicalBytes(bytes)).lowercaseHex
        fun sha(text: String) = sha(text.encodeToByteArray())
    }

    private class FreshTransport : Transport {
        override suspend fun execute(request: TransportRequest) = TransportResponse(
            statusCode = 200,
            body = BODY,
            metadata = TransportResponseMetadata(
                contentType = "application/x-protobuf",
                freshUntilEpochMillis = FRESH,
            ),
        )
    }

    /** The shape of a file-backed Store: entries keyed by stable id, stamped fresh, answering null until written. */
    private class KeyedStore(private val readGate: CompletableDeferred<Unit>? = null) : Store {
        private val entries = HashMap<String, StoredRawResource>()
        var reads = 0
            private set
        var writes = 0
            private set

        override suspend fun read(key: RenGRawResourceKey): StoredRawResource? {
            reads += 1
            val found = entries[key.stableId]
            readGate?.await()
            return found
        }

        override suspend fun write(key: RenGRawResourceKey, resource: StoredRawResource) {
            writes += 1
            entries[key.stableId] = StoredRawResource(
                bytes = resource.bytes,
                contentDigest = resource.contentDigest,
                metadata = StoredRawResourceMetadata(
                    contentType = "application/x-protobuf",
                    freshUntilEpochMillis = FRESH,
                    storedAtEpochMillis = 0L,
                ),
            )
        }
    }
}
