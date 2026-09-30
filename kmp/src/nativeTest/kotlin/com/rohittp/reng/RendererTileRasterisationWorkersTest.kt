package com.rohittp.reng

import com.rohittp.reng.internal.firewall.BasemapTilePixels
import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.RenderContextProbe
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * `RendererConfiguration.maximumConcurrentTileRasterisations` (ADR 0076): how many of a frame's tiles the
 * engine draws at once changes how long preparation takes and nothing it produces.
 *
 * **Native-only** for [RendererBasemapTileTest]'s reason: preparing a frame rasterises its tiles through
 * Skia, which this project's `androidHostTest` runtime lacks.
 *
 * **The fixture has to be able to tell two orderings apart.** Every tile the transport serves is a
 * *different* colour, derived from its own url, and the assertions below require the frame's tiles to
 * disagree with one another. A solid-colour fixture would pass for a build that handed tile A's pixels to
 * tile B, which is precisely the defect a concurrent renderer could have. The pixels are compared against
 * a one-worker render of the same frames rather than against stored bytes, so the claim is exactly
 * "the same at every count" and nothing about the rasteriser's own output.
 */
class RendererTileRasterisationWorkersTest {

    @Test
    fun tilePixelsAreByteIdenticalWhetherOneWorkerDrawsThemOrSeveral() = runTest {
        val reference = renderFrames(workers = 1)

        assertTrue(
            reference.all { frame -> frame.size >= MINIMUM_TILES_PER_FRAME },
            "each frame must have enough tiles for workers to race over: ${reference.map { it.size }}",
        )
        reference.forEach { frame ->
            assertTrue(
                frame.values.map { it.pixels.toList() }.toSet().size > 1,
                "the fixture's tiles must differ from one another, or a swapped tile would pass",
            )
        }

        for (workers in listOf(4, 16)) {
            // Twice, since a race that corrupts a tile does not do so every time.
            repeat(2) { round ->
                val concurrent = renderFrames(workers = workers)
                assertEquals(reference.size, concurrent.size)
                reference.indices.forEach { frameIndex ->
                    val expected = reference[frameIndex]
                    val actual = concurrent[frameIndex]
                    assertEquals(
                        expected.keys,
                        actual.keys,
                        "$workers workers, round $round, frame $frameIndex: the same tiles are drawn",
                    )
                    expected.forEach { (tile, one) ->
                        val many = actual.getValue(tile)
                        assertContentEquals(
                            one.pixels,
                            many.pixels,
                            "$workers workers, round $round, frame $frameIndex, tile $tile: same pixels",
                        )
                        assertEquals(one.contentKey, many.contentKey)
                        assertEquals(one.key, many.key)
                    }
                }
            }
        }
    }

    @Test
    fun theDefaultIsOneAndTheRangeIsOneThroughSixteen() {
        assertEquals(1, configuration(workers = 1).maximumConcurrentTileRasterisations)
        assertEquals(
            1,
            RendererConfiguration(
                outputPixelSize = OutputPixelSize(64, 64),
                transport = TileTransport(),
                store = RecordingStyleStore(),
            ).maximumConcurrentTileRasterisations,
        )
        assertEquals(16, configuration(workers = 16).maximumConcurrentTileRasterisations)
        for (invalid in listOf(0, -1, 17)) {
            val failure = assertFailsWith<IllegalArgumentException> { configuration(workers = invalid) }
            assertTrue(
                failure.message.orEmpty().contains("maximumConcurrentTileRasterisations"),
                "the error names the option: ${failure.message}",
            )
        }
    }

    private class TileOutput(val pixels: ByteArray, val contentKey: String, val key: com.rohittp.reng.ResourceKey)

    private fun configuration(workers: Int): RendererConfiguration = RendererConfiguration(
        outputPixelSize = OutputPixelSize(OUTPUT_SIZE_PX, OUTPUT_SIZE_PX),
        transport = DistinctTileTransport(),
        store = RecordingStyleStore(),
        basemapStyle = ResourceLocator(STYLE_URL),
        maximumConcurrentTileRasterisations = workers,
    )

    /** Every frame's rendered tiles, by canonical tile, from a fresh renderer with [workers] workers. */
    private suspend fun renderFrames(workers: Int): List<Map<String, TileOutput>> {
        val renderer = createRenderer(
            configuration(workers),
            styleGlBinding(),
            RenderContextProbe { RenderContextIdentity(1L) },
        ) as RenGRenderer
        try {
            return FRAMES.mapIndexed { index, camera ->
                val frame = renderer.prepare(
                    FramePlan(frameIndex = index.toLong(), camera = camera, drawBasemap = true),
                ) as RenGPreparedFrame
                try {
                    frame.basemapTiles.associate { rendered ->
                        val pixels = assertIs<BasemapTilePixels.Raw>(rendered.pixels, "raw pixels, the default path")
                        rendered.tile.toString() to TileOutput(pixels.rgba, rendered.contentKey, rendered.key)
                    }
                } finally {
                    frame.close()
                }
            }
        } finally {
            renderer.close()
        }
    }

    private companion object {
        const val OUTPUT_SIZE_PX = 2048
        const val MINIMUM_TILES_PER_FRAME = 6

        val FRAMES: List<Camera> = listOf(
            Camera(latitude = -55.0, unwrappedLongitude = -135.0, zoom = 4.0, bearing = 0.0, pitch = 0.0),
            Camera(latitude = -55.0, unwrappedLongitude = -112.5, zoom = 4.0, bearing = 0.0, pitch = 0.0),
            Camera(latitude = 12.0, unwrappedLongitude = 40.0, zoom = 5.0, bearing = 0.0, pitch = 0.0),
        )
    }
}

/**
 * Answers the style and sprite like [TileTransport], and every other url with a PNG whose colour is a
 * function of that url alone, so no two tiles of a frame share pixels.
 */
private class DistinctTileTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        return when (url) {
            STYLE_URL -> TransportResponse(
                statusCode = 200,
                body = STYLE_WITH_SPRITE_JSON.encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            SPRITE_JSON_URL -> TransportResponse(
                statusCode = 200,
                body = "{}".encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            else -> TransportResponse(
                statusCode = 200,
                body = if (url.startsWith("https://tiles.example/")) colouredPng(url) else STYLE_TEST_PNG,
                metadata = TransportResponseMetadata(contentType = "image/png"),
            )
        }
    }
}

/**
 * A valid 4x4 truecolour PNG whose four quadrants are colours derived from [url], written with stored
 * (uncompressed) deflate blocks so the test needs neither a compressor nor a stored blob. Its checksums
 * are computed here, by the textbook bitwise CRC-32 and Adler-32, and not by anything under test.
 */
private fun colouredPng(url: String): ByteArray {
    var hash = 0x811C9DC5.toInt()
    for (character in url) hash = (hash xor character.code) * 16777619
    val rows = ByteArray(4 * (1 + 4 * 3))
    var offset = 0
    for (row in 0 until 4) {
        rows[offset++] = 0
        for (column in 0 until 4) {
            val quadrant = (row / 2) * 2 + column / 2
            val mixed = hash * (quadrant + 3) + quadrant * 0x9E3779B1.toInt()
            rows[offset++] = (mixed ushr 16).toByte()
            rows[offset++] = (mixed ushr 8).toByte()
            rows[offset++] = mixed.toByte()
        }
    }
    val zlib = ByteArray(2 + 5 + rows.size + 4)
    zlib[0] = 0x78
    zlib[1] = 0x01
    zlib[2] = 1
    zlib[3] = (rows.size and 0xff).toByte()
    zlib[4] = (rows.size ushr 8).toByte()
    zlib[5] = (rows.size.inv() and 0xff).toByte()
    zlib[6] = (rows.size.inv() ushr 8).toByte()
    rows.copyInto(zlib, 7)
    var a = 1
    var b = 0
    for (value in rows) {
        a = (a + (value.toInt() and 0xff)) % 65521
        b = (b + a) % 65521
    }
    val adler = (b shl 16) or a
    for (index in 0 until 4) zlib[7 + rows.size + index] = (adler ushr (24 - 8 * index)).toByte()

    val header = ByteArray(13)
    header[3] = 4
    header[7] = 4
    header[8] = 8
    header[9] = 2
    return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        pngChunk("IHDR", header) + pngChunk("IDAT", zlib) + pngChunk("IEND", ByteArray(0))
}

private fun pngChunk(kind: String, payload: ByteArray): ByteArray {
    val body = kind.encodeToByteArray() + payload
    var crc = -1
    for (value in body) {
        crc = crc xor (value.toInt() and 0xff)
        repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
    }
    crc = crc.inv()
    fun be(value: Int) = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }
    return be(payload.size) + body + be(crc)
}
