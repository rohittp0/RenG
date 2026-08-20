package com.rohittp.reng.internal.firewall

import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The half of [BasemapEngineHostTest] that genuinely rasterizes, which is why it lives here rather than
 * in `commonTest`: Rentile rasterizes through Skia, and this project's `androidHostTest` runtime resolves
 * `org.jetbrains.skiko:skiko`'s API without its native library — Rentile adds `skiko-awt-runtime-<host>`
 * only to its own JVM/Android test source sets, never to what it publishes — so `Image.makeFromEncoded`
 * there fails with `LibraryLoadException` and every rasterizing assertion would be vacuous. Kotlin/Native
 * links Skia in, so this runs for real on `macosArm64Test` (Apple CI) and `linuxX64Test` (Ubuntu CI).
 */
class BasemapEngineRenderTest {

    @Test
    fun rendersOverAnAlreadyPreparedBatchWithoutAnyFurtherAdapterCall() = runTest {
        val transport = CountingHostTransport()
        val store = CountingHostStore()
        val host = basemapEngineHost(transport = transport, store = store)
        try {
            host.withOperation(ResourceAccessMode.NORMAL, listOf(hostRasterRoute)) {
                val style = host.preparedStyle(hostStyleKey, hostStyleRecord(), HOST_STYLE_BASE_URI)
                val prepared = host.prepareTiles(style, listOf(HOST_RASTER_TILE))
                prepared.use {
                    val exchangesAfterPreparation =
                        transport.executeCalls + store.readCalls + store.writeCalls
                    assertTrue(exchangesAfterPreparation > 0, "preparation must genuinely acquire the tile")

                    val rendered = host.renderTiles(prepared)

                    assertEquals(
                        exchangesAfterPreparation,
                        transport.executeCalls + store.readCalls + store.writeCalls,
                        "rendering an already-prepared batch is network-free and store-free",
                    )
                    val tile = rendered.single()
                    assertEquals(HOST_RASTER_TILE, tile.tile)
                    assertEquals(ResourceKind.BASEMAP_TILE, tile.key.kind)
                    assertEquals(
                        host.renderedTileKey(style, HOST_RASTER_TILE),
                        tile.key,
                        "a rendered tile carries RenG's own canonical identity",
                    )
                    // Not merely non-empty: these are RenG's first rendered basemap pixels, and the
                    // cheapest way to claim they are a PNG rather than any non-empty byte array is the
                    // 8-byte signature the format mandates.
                    assertTrue(tile.pngBytes.size > PNG_SIGNATURE.size, "the engine produced encoded ground pixels")
                    assertContentEquals(
                        PNG_SIGNATURE,
                        tile.pngBytes.take(PNG_SIGNATURE.size).toByteArray(),
                        "the encoded bytes carry the PNG signature",
                    )
                    assertTrue(tile.contentKey.isNotEmpty(), "Rentile's own content key is stored beside them")
                    assertEquals(emptyList(), tile.substitutions, "tile substitution stays disabled")
                }
            }
        } finally {
            host.close()
        }
    }
}

/** The 8 bytes every PNG datastream begins with (RFC 2083 section 3.1). */
private val PNG_SIGNATURE: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
