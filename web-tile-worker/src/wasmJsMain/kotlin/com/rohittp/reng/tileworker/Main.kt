@file:OptIn(
    kotlin.js.ExperimentalWasmJsInterop::class,
    kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class,
)

package com.rohittp.reng.tileworker

import com.rohittp.rentile.BasemapRasterizer
import com.rohittp.rentile.CredentialProvider
import com.rohittp.rentile.ExecutionPolicy
import com.rohittp.rentile.MapSessionProvider
import com.rohittp.rentile.PreparedStyle
import com.rohittp.rentile.RasterAcquisitionDensity
import com.rohittp.rentile.RawResourceKey
import com.rohittp.rentile.RawResourceMetadata
import com.rohittp.rentile.RawResourceStore
import com.rohittp.rentile.RenderOptions
import com.rohittp.rentile.RenderPriority
import com.rohittp.rentile.Rentile
import com.rohittp.rentile.RentileClock
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.ResourceAccessMode
import com.rohittp.rentile.ResourceTransport
import com.rohittp.rentile.StoredRawResource
import com.rohittp.rentile.StyleInput
import com.rohittp.rentile.TileId
import com.rohittp.rentile.TileSubstitutionPolicy
import com.rohittp.rentile.TransportRequest
import com.rohittp.rentile.TransportResponse
import com.rohittp.rentile.TransportResponseMetadata
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.wasm.unsafe.withScopedMemoryAllocator

fun main() {
    val scope = MainScope()
    scope.launch {
        val renderer = TileRenderer(createTileStore())
        installMessageHandler { request ->
            scope.launch {
                try {
                    val rendered = renderer.render(
                        styleLocator = request.style.toString(),
                        tile = TileId(request.zoom, request.column, request.row),
                        outputSizePx = request.outputSizePx,
                        outputRequestKey = request.outputRequestKey.toString(),
                        resourceAccess = resourceAccessMode(request.accessMode.toString()),
                    )
                    postSuccess(request.id, rendered)
                } catch (failure: Throwable) {
                    val type = failure::class.simpleName ?: "Throwable"
                    postFailure(request.id, "$type: ${failure.message ?: "no message"}".toJsString())
                }
            }
        }
    }
}

private class TileRenderer(store: RawResourceStore) {
    private val engine: BasemapRasterizer = Rentile.create(
        RentileConfiguration(
            transport = FetchTransport(),
            rawResourceStore = store,
            sessionProvider = MapSessionProvider.None,
            credentialProvider = CredentialProvider.None,
            clock = RentileClock.System,
            executionPolicy = ExecutionPolicy(maxConcurrentMetatileWorkers = 1),
        ),
    )
    private val styles = HashMap<String, PreparedStyle>()

    suspend fun render(
        styleLocator: String,
        tile: TileId,
        outputSizePx: Int,
        outputRequestKey: String,
        resourceAccess: ResourceAccessMode,
    ): WorkerRenderedTile {
        val style = styles[styleLocator] ?: engine.prepare(StyleInput.Remote(styleLocator)).also {
            styles[styleLocator] = it
        }
        val options = RenderOptions(
            outputSizePx = outputSizePx,
            rasterAcquisitionDensity = RasterAcquisitionDensity.OUTPUT_ZOOM,
        )
        require(engine.outputRequestKey(style, tile, options) == outputRequestKey) {
            "the render worker and tile worker disagree on the output request identity"
        }
        val batch = engine.prepareBatch(
            style = style,
            tiles = listOf(tile),
            options = options,
            resourceAccess = resourceAccess,
            substitutionPolicy = TileSubstitutionPolicy.Disabled,
        )
        return try {
            val rendered = engine.renderRaw(batch, tiles = listOf(tile), priority = RenderPriority.NORMAL)
                .tiles
                .single()
            WorkerRenderedTile(rendered.rgbaBytes, rendered.contentKey)
        } finally {
            batch.close()
        }
    }
}

private class WorkerRenderedTile(
    val rgbaBytes: ByteArray,
    val contentKey: String,
)

private fun resourceAccessMode(value: String): ResourceAccessMode = when (value) {
    "NORMAL" -> ResourceAccessMode.NORMAL
    "CACHE_ONLY" -> ResourceAccessMode.CACHE_ONLY
    "RELOAD" -> ResourceAccessMode.RELOAD
    else -> error("unknown resource access mode")
}

private class MemoryStore : RawResourceStore {
    private val resources = HashMap<RawResourceKey, StoredRawResource>()

    override suspend fun read(key: RawResourceKey): StoredRawResource? = resources[key]

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        resources[key] = resource
    }

    override suspend fun remove(key: RawResourceKey) {
        resources.remove(key)
    }
}

private class IndexedDbStore(private val database: IndexedDbDatabase) : RawResourceStore {
    override suspend fun read(key: RawResourceKey): StoredRawResource? = try {
        val record = indexedDbRead(
            database = database,
            key = indexedDbKey(DATA_RECORD, key.stableId, key.resourceClass.toString()).toJsString(),
        ).await() ?: return null
        StoredRawResource(
            bytes = record.body.copyToByteArray(),
            contentDigest = record.contentDigest.toString(),
            metadata = record.toMetadata(),
        )
    } catch (failure: Throwable) {
        logStoreFailureOnce("read", failure)
        null
    }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        try {
            val metadata = resource.metadata
            val bytes = resource.bytes
            val promise = withScopedMemoryAllocator { allocator ->
                val pointer = allocator.allocate(bytes.size)
                for (index in bytes.indices) (pointer + index).storeByte(bytes[index])
                indexedDbWrite(
                    database = database,
                    dataKey = indexedDbKey(DATA_RECORD, key.stableId, key.resourceClass.toString()).toJsString(),
                    metadataKey = indexedDbKey(METADATA_RECORD, key.stableId, key.resourceClass.toString()).toJsString(),
                    address = pointer.address.toInt(),
                    length = bytes.size,
                    contentDigest = resource.contentDigest.toJsString(),
                    contentType = metadata.contentType?.toJsString(),
                    etag = metadata.etag?.toJsString(),
                    lastModified = metadata.lastModified?.toJsString(),
                    freshUntilEpochMillis = metadata.freshUntilEpochMillis?.toDouble() ?: -1.0,
                    storedAtEpochMillis = metadata.storedAtEpochMillis.toDouble(),
                )
            }
            promise.await()
        } catch (failure: Throwable) {
            logStoreFailureOnce("write", failure)
        }
    }

    override suspend fun remove(key: RawResourceKey) {
        try {
            indexedDbRemove(
                database = database,
                dataKey = indexedDbKey(DATA_RECORD, key.stableId, key.resourceClass.toString()).toJsString(),
                metadataKey = indexedDbKey(METADATA_RECORD, key.stableId, key.resourceClass.toString()).toJsString(),
            ).await()
        } catch (failure: Throwable) {
            logStoreFailureOnce("remove", failure)
        }
    }

    override suspend fun metadata(key: RawResourceKey): RawResourceMetadata? = try {
        indexedDbReadMetadata(
            database = database,
            key = indexedDbKey(METADATA_RECORD, key.stableId, key.resourceClass.toString()).toJsString(),
        ).await()?.toMetadata()
    } catch (failure: Throwable) {
        logStoreFailureOnce("metadata read", failure)
        null
    }
}

private fun IndexedDbMetadataRecord.toMetadata(): RawResourceMetadata = RawResourceMetadata(
    contentType = contentType?.toString(),
    etag = etag?.toString(),
    lastModified = lastModified?.toString(),
    freshUntilEpochMillis = freshUntilEpochMillis.takeIf { it >= 0.0 }?.toLong(),
    storedAtEpochMillis = storedAtEpochMillis.toLong(),
)

private suspend fun createTileStore(): RawResourceStore {
    if (!persistentStoreRequested()) return MemoryStore()
    return try {
        IndexedDbStore(openIndexedDb(INDEXED_DB_NAME.toJsString()).await())
    } catch (failure: Throwable) {
        logStoreFailureOnce("open", failure)
        MemoryStore()
    }
}

private class FetchTransport : ResourceTransport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        val payload = fetchResource(request.url.toJsString()).await()
        require(payload.body.length.toLong() <= request.maxResponseBytes) {
            "response exceeded the ${request.maxResponseBytes}-byte limit"
        }
        return TransportResponse(
            statusCode = payload.statusCode,
            body = payload.body.copyToByteArray(),
            metadata = TransportResponseMetadata(
                contentType = payload.contentType?.toString(),
                etag = payload.etag?.toString(),
                lastModified = payload.lastModified?.toString(),
                expiresAtEpochMillis = payload.freshUntilEpochMillis.takeIf { it >= 0.0 }?.toLong(),
                wireByteCount = payload.body.length.toLong(),
            ),
        )
    }
}

private fun JsUint8Array.copyToByteArray(): ByteArray {
    if (length == 0) return ByteArray(0)
    return withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(length)
        copyJsBytesToLinearMemory(this, pointer.address.toInt(), length)
        ByteArray(length) { index -> (pointer + index).loadByte() }
    }
}

private fun postSuccess(id: Int, rendered: WorkerRenderedTile) {
    val rgba = rendered.rgbaBytes
    withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(rgba.size)
        for (index in rgba.indices) (pointer + index).storeByte(rgba[index])
        postSuccessFromMemory(id, pointer.address.toInt(), rgba.size, rendered.contentKey.toJsString())
    }
}

private const val INDEXED_DB_NAME = "reng-demo-store"
private const val DATA_RECORD = "data"
private const val METADATA_RECORD = "meta"
private val loggedStoreFailures = HashSet<String>()

private fun indexedDbKey(recordKind: String, stableId: String, resourceClass: String): String =
    "$recordKind|${resourceClass.length}:$resourceClass$stableId"

private fun logStoreFailureOnce(operation: String, failure: Throwable) {
    if (!loggedStoreFailures.add(operation)) return
    val type = failure::class.simpleName ?: "Throwable"
    consoleError(
        ("IndexedDB Rentile $operation failed; continuing without it: " +
            "$type: ${failure.message ?: "no message"}").toJsString(),
    )
}

private external interface TileWorkerRequest : JsAny {
    val id: Int
    val style: JsString
    val zoom: Int
    val column: Int
    val row: Int
    val outputSizePx: Int
    val outputRequestKey: JsString
    val accessMode: JsString
}

private external interface IndexedDbDatabase : JsAny

private external interface IndexedDbMetadataRecord : JsAny {
    val contentType: JsString?
    val etag: JsString?
    val lastModified: JsString?
    val freshUntilEpochMillis: Double
    val storedAtEpochMillis: Double
}

private external interface IndexedDbRecord : IndexedDbMetadataRecord {
    val body: JsUint8Array
    val contentDigest: JsString
}

private external interface FetchPayload : JsAny {
    val statusCode: Int
    val body: JsUint8Array
    val contentType: JsString?
    val etag: JsString?
    val lastModified: JsString?
    val freshUntilEpochMillis: Double
}

private external interface JsUint8Array : JsAny {
    val length: Int
}

@JsFun(
    """callback => {
        if (self.__rengTileHold) self.removeEventListener('message', self.__rengTileHold);
        const accept = message => { if (message?.kind === 'tileRequest') callback(message); };
        self.onmessage = event => accept(event.data);
        for (const message of self.__rengTileMessages || []) accept(message);
        self.__rengTileMessages = [];
    }""",
)
private external fun installMessageHandler(callback: (TileWorkerRequest) -> Unit)

@JsFun(
    """(id, address, length, contentKey) => {
        const rgba = new Uint8Array(length);
        rgba.set(new Uint8Array(wasmExports.memory.buffer, address >>> 0, length));
        self.postMessage({ kind: 'tileResult', id, rgba: rgba.buffer, contentKey: String(contentKey) }, [rgba.buffer]);
    }""",
)
private external fun postSuccessFromMemory(id: Int, address: Int, length: Int, contentKey: JsString)

@JsFun("(id, message) => self.postMessage({ kind: 'tileError', id, message: String(message) })")
private external fun postFailure(id: Int, message: JsString)

@JsFun(
    """name => new Promise((resolve, reject) => {
        if (!globalThis.indexedDB) {
            reject(new Error('IndexedDB is unavailable'));
            return;
        }
        const request = globalThis.indexedDB.open(String(name), 1);
        request.onupgradeneeded = () => {
            const database = request.result;
            if (!database.objectStoreNames.contains('reng')) database.createObjectStore('reng');
            if (!database.objectStoreNames.contains('rentile')) database.createObjectStore('rentile');
        };
        request.onsuccess = () => {
            const database = request.result;
            if (!database.objectStoreNames.contains('rentile')) {
                database.close();
                reject(new Error('IndexedDB schema is missing the rentile object store; use ?store=clear'));
                return;
            }
            database.onversionchange = () => database.close();
            resolve(database);
        };
        request.onerror = () => reject(request.error || new Error('IndexedDB open failed'));
        request.onblocked = () => reject(new Error('IndexedDB open was blocked'));
    })""",
)
private external fun openIndexedDb(name: JsString): Promise<IndexedDbDatabase>

@JsFun(
    """(database, key) => new Promise((resolve, reject) => {
        const transaction = database.transaction('rentile', 'readonly');
        const request = transaction.objectStore('rentile').get(String(key));
        let result = null;
        request.onsuccess = () => {
            const record = request.result;
            result = record == null ? null : { ...record, body: new Uint8Array(record.bytes) };
        };
        transaction.oncomplete = () => resolve(result);
        transaction.onerror = () => reject(transaction.error || request.error || new Error('IndexedDB read failed'));
        transaction.onabort = () => reject(transaction.error || new Error('IndexedDB read aborted'));
    })""",
)
private external fun indexedDbRead(database: IndexedDbDatabase, key: JsString): Promise<IndexedDbRecord?>

@JsFun(
    """(database, dataKey, metadataKey, address, length, contentDigest, contentType, etag, lastModified,
        freshUntilEpochMillis, storedAtEpochMillis) => {
        const bytes = new Uint8Array(length);
        bytes.set(new Uint8Array(wasmExports.memory.buffer, address >>> 0, length));
        const metadata = {
            contentDigest: String(contentDigest),
            contentType: contentType == null ? null : String(contentType),
            etag: etag == null ? null : String(etag),
            lastModified: lastModified == null ? null : String(lastModified),
            freshUntilEpochMillis,
            storedAtEpochMillis
        };
        return new Promise((resolve, reject) => {
            const transaction = database.transaction('rentile', 'readwrite');
            const store = transaction.objectStore('rentile');
            store.put({ ...metadata, bytes: bytes.buffer }, String(dataKey));
            store.put(metadata, String(metadataKey));
            transaction.oncomplete = () => resolve({});
            transaction.onerror = () => reject(transaction.error || new Error('IndexedDB write failed'));
            transaction.onabort = () => reject(transaction.error || new Error('IndexedDB write aborted'));
        });
    }""",
)
private external fun indexedDbWrite(
    database: IndexedDbDatabase,
    dataKey: JsString,
    metadataKey: JsString,
    address: Int,
    length: Int,
    contentDigest: JsString,
    contentType: JsString?,
    etag: JsString?,
    lastModified: JsString?,
    freshUntilEpochMillis: Double,
    storedAtEpochMillis: Double,
): Promise<JsAny>

@JsFun(
    """(database, key) => new Promise((resolve, reject) => {
        const transaction = database.transaction('rentile', 'readonly');
        const request = transaction.objectStore('rentile').get(String(key));
        let result = null;
        request.onsuccess = () => { result = request.result == null ? null : request.result; };
        transaction.oncomplete = () => resolve(result);
        transaction.onerror = () => reject(transaction.error || request.error || new Error('IndexedDB metadata read failed'));
        transaction.onabort = () => reject(transaction.error || new Error('IndexedDB metadata read aborted'));
    })""",
)
private external fun indexedDbReadMetadata(
    database: IndexedDbDatabase,
    key: JsString,
): Promise<IndexedDbMetadataRecord?>

@JsFun(
    """(database, dataKey, metadataKey) => new Promise((resolve, reject) => {
        const transaction = database.transaction('rentile', 'readwrite');
        const store = transaction.objectStore('rentile');
        store.delete(String(dataKey));
        store.delete(String(metadataKey));
        transaction.oncomplete = () => resolve({});
        transaction.onerror = () => reject(transaction.error || new Error('IndexedDB remove failed'));
        transaction.onabort = () => reject(transaction.error || new Error('IndexedDB remove aborted'));
    })""",
)
private external fun indexedDbRemove(
    database: IndexedDbDatabase,
    dataKey: JsString,
    metadataKey: JsString,
): Promise<JsAny>

@JsFun(
    """url => fetch(url).then(async (response) => {
        const cacheControl = response.headers.get('cache-control');
        const maxAge = cacheControl && cacheControl.match(/(?:^|,)\s*max-age=(\d+)/i);
        const expires = response.headers.get('expires');
        let freshUntilEpochMillis = -1;
        if (maxAge) {
            freshUntilEpochMillis = Date.now() + Number(maxAge[1]) * 1000;
        } else if (expires) {
            const parsed = Date.parse(expires);
            if (Number.isFinite(parsed)) freshUntilEpochMillis = parsed;
        }
        return {
            statusCode: response.status,
            body: new Uint8Array(await response.arrayBuffer()),
            contentType: response.headers.get('content-type'),
            etag: response.headers.get('etag'),
            lastModified: response.headers.get('last-modified'),
            freshUntilEpochMillis
        };
    })""",
)
private external fun fetchResource(url: JsString): Promise<FetchPayload>

@JsFun("() => new URLSearchParams(self.location.search).get('store') === 'idb'")
private external fun persistentStoreRequested(): Boolean

@JsFun("(source, address, length) => new Uint8Array(wasmExports.memory.buffer, address >>> 0, length).set(source)")
private external fun copyJsBytesToLinearMemory(source: JsUint8Array, address: Int, length: Int)

@JsFun("message => console.error(message)")
private external fun consoleError(message: JsString)
