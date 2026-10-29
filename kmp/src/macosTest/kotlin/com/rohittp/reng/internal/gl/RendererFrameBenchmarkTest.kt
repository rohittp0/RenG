@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.Bloom
import com.rohittp.reng.Camera
import com.rohittp.reng.FramePlan
import com.rohittp.reng.FramebufferName
import com.rohittp.reng.Geometry
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.Placement
import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RendererConfiguration
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.ShaderValue
import com.rohittp.reng.Sticker
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.Vector3
import com.rohittp.reng.createRenderer
import com.rohittp.reng.internal.bench.RengBench
import com.rohittp.reng.internal.image.crc32
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.runBlocking
import platform.zlib.Z_OK
import platform.zlib.compress2
import platform.zlib.compressBound
import platform.zlib.uLongfVar
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * B7: whole frames through the public renderer on the host's real GL driver, prepare and draw timed
 * separately (draw includes a `glFinish`, so it is the GPU's time too).
 *
 * - `mercator_tiles_stickers`: a Mercator camera at pitch 85 over a raster basemap at 2160x3840 -- the
 *   tile count it selects is printed beside it, about 400 -- plus [STICKERS] screen stickers;
 * - `globe_bloom`: a GLOBE frame over the same basemap with [EMITTERS] emissive geometries each spanning
 *   tens of degrees, so the globe subdivides them finely, under a [Bloom]; every other one has a
 *   positive `bloomReach`.
 *
 * Offline: the [BenchTransport] answers the style, every tile and every sticker from memory, and the
 * [Store] remembers nothing. The camera does not move, so after warm-up each frame is the steady state
 * a held shot costs -- planning, leasing and releasing every resident resource, and drawing it.
 */
class RendererFrameBenchmarkTest {
    @Test
    fun steadyStateFrames() {
        val fixture = CglCoreProfileContext.create()
        try {
            val binding = (openPlatformGlBinding() as GlBindingResult.Bound).binding
            RengBench.info("b7", "GL_RENDERER=${binding.getString(GL_RENDERER)}")
            measure("b7.frame.mercator_tiles_stickers", binding, fixture) { index -> mercatorPlan(index) }
            measure("b7.frame.globe_bloom", binding, fixture) { index -> globePlan(index) }
        } finally {
            fixture.destroy()
        }
    }

    private fun measure(name: String, binding: GlBinding, fixture: CglCoreProfileContext, plan: (Long) -> FramePlan) {
        val transport = BenchTransport()
        val renderer = createRenderer(
            RendererConfiguration(
                outputPixelSize = OUTPUT,
                transport = transport,
                store = NoStore,
                basemapStyle = ResourceLocator(STYLE_URL),
                maximumBasemapTileInstances = 4096,
            ),
            binding,
            fixture.probe,
        )
        val (framebuffer, texture) = target(binding)
        val prepare = ArrayList<Long>()
        val draw = ArrayList<Long>()
        var checksum = 0L
        try {
            val renderTarget = renderer.mintRenderTarget(FramebufferName(framebuffer.toUInt()))
            var index = 0L
            val started = TimeSource.Monotonic.markNow()
            while (prepare.size < MAXIMUM_FRAMES && (prepare.size < MINIMUM_FRAMES || started.elapsedNow() < BUDGET)) {
                if (index == 1L) {
                    RengBench.info(name, "tiles_requested=${transport.tileRequests.value} stickers_requested=${transport.stickerRequests.value}")
                }
                val start = TimeSource.Monotonic.markNow()
                val frame = runBlocking { renderer.prepare(plan(index)) }
                val prepared = start.elapsedNow()
                frame.use { renderer.draw(it, renderTarget) }
                binding.finish()
                val drawn = start.elapsedNow() - prepared
                checksum = checksum * 31 + frame.frameIndex
                if (index >= WARM_UP) {
                    prepare += prepared.inWholeNanoseconds
                    draw += drawn.inWholeNanoseconds
                }
                index += 1
            }
            RengBench.info(name, "transport_requests_total=${transport.requests.value}")
        } finally {
            renderer.close()
            binding.deleteFramebuffers(1, intArrayOf(framebuffer))
            binding.deleteTextures(1, intArrayOf(texture))
        }
        RengBench.report("$name.prepare", prepare, checksum)
        RengBench.report("$name.draw", draw, checksum)
    }

    private fun mercatorPlan(index: Long): FramePlan = FramePlan(
        frameIndex = index,
        camera = Camera(latitude = 48.85, unwrappedLongitude = 2.35, zoom = 12.0, bearing = 20.0, pitch = 85.0),
        drawLabels = false,
        stickers = List(STICKERS) { sticker ->
            Sticker(
                Placement(
                    positionMode = AnchoringMode.SCREEN,
                    position = Vector3(120.0 + (sticker % 10) * 200.0, 200.0 + (sticker / 10) * 600.0, 0.0),
                    rotationMode = AnchoringMode.SCREEN,
                    rotation = Vector3(0.0, 0.0, 0.0),
                    scaleMode = AnchoringMode.SCREEN,
                    scale = 1.0,
                ),
                ResourceLocator("$STICKER_URL_PREFIX$sticker.png"),
            )
        },
    )

    private fun globePlan(index: Long): FramePlan = FramePlan(
        frameIndex = index,
        camera = Camera(latitude = 20.0, unwrappedLongitude = 30.0, zoom = 1.5, bearing = 0.0, pitch = 0.0),
        projectionMode = ProjectionMode.GLOBE,
        drawLabels = false,
        geometries = List(EMITTERS) { emitter ->
            val row = emitter / 4
            val column = emitter % 4
            val north = 60.0 - row * 30.0
            val west = -20.0 + column * 25.0
            Geometry(
                topLeft = Vector3(north, west, 0.0),
                bottomRight = Vector3(north - 22.0, west + 20.0, 0.0),
                shaderPair = EMITTER_SHADERS,
                uniforms = mapOf("uColour" to ShaderValue.Vec4(0.2f + 0.05f * emitter, 0.8f, 1f - 0.05f * emitter, 1f)),
                emissive = true,
                bloomReach = if (emitter % 2 == 0) 0.6f else 0f,
            )
        },
        bloom = Bloom(intensity = 1.2f, reachLevel = 4, reachGain = 1f),
    )

    private class BenchTransport : Transport {
        val requests = AtomicInt(0)
        val tileRequests = AtomicInt(0)
        val stickerRequests = AtomicInt(0)

        override suspend fun execute(request: TransportRequest): TransportResponse {
            requests.incrementAndGet()
            val url = request.locator.value
            return when {
                url == STYLE_URL -> TransportResponse(
                    statusCode = 200,
                    body = STYLE_JSON.encodeToByteArray(),
                    metadata = TransportResponseMetadata(contentType = "application/json", freshUntilEpochMillis = FRESH_UNTIL),
                )
                url.startsWith(TILE_URL_PREFIX) -> {
                    tileRequests.incrementAndGet()
                    TransportResponse(200, TILE_PNG, PNG_METADATA)
                }
                url.startsWith(STICKER_URL_PREFIX) -> {
                    stickerRequests.incrementAndGet()
                    TransportResponse(200, STICKER_PNG, PNG_METADATA)
                }
                else -> error("the benchmark serves no body for $url")
            }
        }
    }

    private object NoStore : Store {
        override suspend fun read(key: RawResourceKey): StoredRawResource? = null

        override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {}
    }

    private fun target(binding: GlBinding): Pair<Int, Int> {
        val names = IntArray(1)
        binding.genTextures(1, names)
        val texture = names[0]
        binding.bindTexture(GL_TEXTURE_2D, texture)
        binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, OUTPUT.width, OUTPUT.height)
        binding.genFramebuffers(1, names)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, names[0])
        binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        assertEquals(GL_FRAMEBUFFER_COMPLETE, binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER))
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
        binding.bindTexture(GL_TEXTURE_2D, 0)
        return names[0] to texture
    }

    private companion object {
        // 4K portrait at pitch 85: the banded LOD selection keeps a 1080p frame near 140 tiles even at
        // that pitch, and this is the closest a real camera gets to the 512-instance default cap (~400).
        val OUTPUT = OutputPixelSize(2160, 3840)

        /** 2100-01-01: every response stays fresh, so a steady frame revalidates nothing. */
        const val FRESH_UNTIL = 4_102_444_800_000L
        val PNG_METADATA = TransportResponseMetadata(contentType = "image/png", freshUntilEpochMillis = FRESH_UNTIL)
        const val STICKERS = 50
        const val EMITTERS = 16
        const val WARM_UP = 10L
        const val MINIMUM_FRAMES = 30
        const val MAXIMUM_FRAMES = 300
        val BUDGET = 4.seconds

        const val STYLE_URL = "https://bench.example/style.json"
        const val TILE_URL_PREFIX = "https://bench.example/t/"
        const val STICKER_URL_PREFIX = "https://bench.example/sticker/"
        val STYLE_JSON: String =
            """{"version":8,"name":"reng-bench",""" +
                """"sources":{"s":{"type":"raster","tiles":["$TILE_URL_PREFIX{z}/{x}/{y}.png"],"tileSize":256}},""" +
                """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#203040"}},""" +
                """{"id":"r","type":"raster","source":"s"}]}"""

        val TILE_PNG: ByteArray = pngOf(256, 256, seed = 1)
        val STICKER_PNG: ByteArray = pngOf(96, 96, seed = 2)

        val EMITTER_SHADERS = ShaderPair(
            "#version 300 es\n" +
                "in vec3 aPosition;\n" +
                "uniform mat4 uModelViewProjection;\n" +
                "void main() {\n" +
                "    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);\n" +
                "}\n",
            "#version 300 es\n" +
                "precision highp float;\n" +
                "uniform vec4 uColour;\n" +
                "out vec4 rengGeometryColour;\n" +
                "void main() { rengGeometryColour = uColour; }\n",
        )

        /** An RGBA PNG of gradients and grain, Sub-filtered and deflated by the system zlib. */
        fun pngOf(width: Int, height: Int, seed: Int): ByteArray {
            val stride = width * 4 + 1
            val raw = ByteArray(stride * height)
            var state = seed * 7919 + 1
            for (y in 0 until height) {
                raw[y * stride] = 1
                var left = IntArray(4)
                for (x in 0 until width) {
                    state = state * 1103515245 + 12345
                    val grain = (state ushr 16) and 7
                    val pixel = intArrayOf((x * 255 / width + grain) and 0xFF, (y * 255 / height + grain) and 0xFF, 120 + grain, 255)
                    for (channel in 0 until 4) raw[y * stride + 1 + x * 4 + channel] = (pixel[channel] - left[channel]).toByte()
                    left = pixel
                }
            }
            val ihdr = bigEndian(width) + bigEndian(height) + byteArrayOf(8, 6, 0, 0, 0)
            return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", ihdr) + chunk("IDAT", deflate(raw)) +
                chunk("IEND", ByteArray(0))
        }

        fun chunk(type: String, data: ByteArray): ByteArray {
            val typed = type.encodeToByteArray() + data
            return bigEndian(data.size) + typed + bigEndian(crc32(0u, typed, 0, typed.size).toInt())
        }

        fun bigEndian(value: Int): ByteArray = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }

        fun deflate(raw: ByteArray): ByteArray = memScoped {
            val out = ByteArray(compressBound(raw.size.convert()).toInt())
            val length = alloc<uLongfVar>()
            length.value = out.size.convert()
            val status = out.usePinned { destination ->
                raw.usePinned { source ->
                    compress2(destination.addressOf(0).reinterpret(), length.ptr, source.addressOf(0).reinterpret(), raw.size.convert(), 6)
                }
            }
            check(status == Z_OK) { "zlib compress2 failed: $status" }
            out.copyOf(length.value.toInt())
        }
    }
}
