@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.Camera
import com.rohittp.reng.FramePlan
import com.rohittp.reng.FramebufferName
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.Placement
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RendererConfiguration
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.Sticker
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.Vector3
import com.rohittp.reng.createRenderer
import com.rohittp.reng.internal.image.Etc2ContainerResult
import com.rohittp.reng.internal.image.crc32
import com.rohittp.reng.internal.image.decodeEtc2Rows
import com.rohittp.reng.internal.image.parseEtc2Container
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * The per-frame cost an animated sticker pays (ADR 0084): every frame a new locator, so every frame
 * a Transport exchange, a class gate, an acquisition and an upload. It measures and prints; it
 * asserts nothing about time, because a timing ceiling calibrated on a developer machine is exactly
 * what once failed a release on a hosted runner.
 *
 * macOS's GL 4.1 cannot sample ETC2, so here the container is decoded on the CPU at upload -- the
 * capability path, and the slower one. The decode alone is timed separately so that the native path's
 * draw cost can be read off as the PNG draw without its premultiply, plus a block upload.
 */
class Etc2StickerBenchmarkTest {
    @Test
    fun anAnimatedStickerPaysThisPerFrameAsPngAndAsEtc2() {
        val fixture = CglCoreProfileContext.create()
        try {
            val binding = (openPlatformGlBinding() as GlBindingResult.Bound).binding
            println("RenG ETC2 sticker benchmark: GL_RENDERER=${binding.getString(GL_RENDERER)}, $FRAMES frames of ${WIDTH}x$HEIGHT")
            val png = List(FRAMES) { pngFrame(it) }
            val etc2 = List(FRAMES) { etc2Frame(it, levels = 1) }
            val etc2Chain = List(FRAMES) { etc2Frame(it, levels = FULL_CHAIN) }
            println("  mean bytes per frame: png=${png.sumOf { it.size } / FRAMES}, etc2=${etc2[0].size}, etc2+mips=${etc2Chain[0].size}")

            report("png", run(binding, fixture, png))
            report("etc2 1 level (CPU decode)", run(binding, fixture, etc2))
            report("etc2 $FULL_CHAIN levels (CPU decode)", run(binding, fixture, etc2Chain))
            report("etc2 1 level, decode alone", decodeOnly(etc2))
            report("etc2 $FULL_CHAIN levels, decode alone", decodeOnly(etc2Chain))
        } finally {
            fixture.destroy()
        }
    }

    private class Timings(val prepare: List<Double>, val draw: List<Double>)

    private fun run(binding: GlBinding, fixture: CglCoreProfileContext, frames: List<ByteArray>): Timings {
        val transport = Transport { request ->
            TransportResponse(statusCode = 200, body = frames[request.locator.value.substringAfterLast('/').toInt()])
        }
        val renderer = createRenderer(
            RendererConfiguration(outputPixelSize = OUTPUT, transport = transport, store = NoStore, basemapStyle = null),
            binding,
            fixture.probe,
        )
        val (framebuffer, texture) = target(binding)
        val prepare = ArrayList<Double>()
        val draw = ArrayList<Double>()
        try {
            val renderTarget = renderer.mintRenderTarget(FramebufferName(framebuffer.toUInt()))
            frames.indices.forEach { index ->
                val start = TimeSource.Monotonic.markNow()
                val frame = runBlocking { renderer.prepare(plan(index)) }
                val prepared = start.elapsedNow()
                frame.use { renderer.draw(it, renderTarget) }
                binding.finish()
                val drawn = start.elapsedNow() - prepared
                if (index >= WARM_UP) {
                    prepare += prepared.inWholeMicroseconds / 1000.0
                    draw += drawn.inWholeMicroseconds / 1000.0
                }
            }
        } finally {
            renderer.close()
            binding.deleteFramebuffers(1, intArrayOf(framebuffer))
            binding.deleteTextures(1, intArrayOf(texture))
        }
        return Timings(prepare, draw)
    }

    private fun decodeOnly(frames: List<ByteArray>): Timings {
        val workspace = ByteArray(WIDTH * HEIGHT * 4)
        val decode = frames.drop(WARM_UP).map { bytes ->
            val start = TimeSource.Monotonic.markNow()
            val image = (parseEtc2Container(bytes, Long.MAX_VALUE) as Etc2ContainerResult.Admitted).image
            for (level in 0 until image.levelCount) {
                decodeEtc2Rows(image, level, 0, (image.levelHeight(level) + 3) / 4, workspace)
            }
            start.elapsedNow().inWholeMicroseconds / 1000.0
        }
        return Timings(emptyList(), decode)
    }

    private fun report(name: String, timings: Timings) {
        fun summary(values: List<Double>): String {
            if (values.isEmpty()) return "-"
            val sorted = values.sorted()
            fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
            return "mean ${format(values.average())} p50 ${format(at(0.5))} p95 ${format(at(0.95))} ms"
        }
        println("  $name: prepare ${summary(timings.prepare)} | draw ${summary(timings.draw)}")
    }

    private fun format(value: Double): String = ((value * 100).toLong() / 100.0).toString()

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

    private fun plan(index: Int): FramePlan = FramePlan(
        frameIndex = index.toLong(),
        camera = Camera(latitude = 0.0, unwrappedLongitude = 0.0, zoom = 2.0, bearing = 0.0, pitch = 0.0),
        drawBasemap = false,
        drawLabels = false,
        stickers = listOf(
            Sticker(
                Placement(
                    positionMode = AnchoringMode.SCREEN,
                    position = Vector3(OUTPUT.width / 2.0, OUTPUT.height / 2.0, 0.0),
                    rotationMode = AnchoringMode.SCREEN,
                    rotation = Vector3(0.0, 0.0, 0.0),
                    scaleMode = AnchoringMode.SCREEN,
                    scale = 1.0,
                ),
                ResourceLocator("https://gif.example/frame/$index"),
            ),
        ),
    )

    /**
     * A 480x272 RGBA PNG that changes every frame and compresses like a picture rather than like noise:
     * gradients, a disc that moves, and a little grain. Deflated by the system zlib at its default level.
     */
    private fun pngFrame(index: Int): ByteArray {
        val stride = WIDTH * 4 + 1
        val raw = ByteArray(stride * HEIGHT)
        var seed = index * 7919 + 1
        for (y in 0 until HEIGHT) {
            raw[y * stride] = 1 // Sub filter
            var left = IntArray(4)
            for (x in 0 until WIDTH) {
                seed = seed * 1103515245 + 12345
                val grain = (seed ushr 16) and 7
                val dx = x - (index * 3) % WIDTH
                val dy = y - HEIGHT / 2
                val inDisc = dx * dx + dy * dy < 60 * 60
                val pixel = intArrayOf(
                    (x * 255 / WIDTH + grain) and 0xFF,
                    (y * 255 / HEIGHT + grain) and 0xFF,
                    if (inDisc) 230 else 40 + grain,
                    if (inDisc || x % 97 > 8) 255 else 0,
                )
                for (channel in 0 until 4) {
                    raw[y * stride + 1 + x * 4 + channel] = (pixel[channel] - left[channel]).toByte()
                }
                left = pixel
            }
        }
        val ihdr = byteArrayOf(0, 0, (WIDTH shr 8).toByte(), WIDTH.toByte(), 0, 0, (HEIGHT shr 8).toByte(), HEIGHT.toByte(), 8, 6, 0, 0, 0)
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", ihdr) + chunk("IDAT", deflate(raw)) +
            chunk("IEND", ByteArray(0))
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typed = type.encodeToByteArray() + data
        return bigEndian(data.size) + typed + bigEndian(crc32(0u, typed, 0, typed.size).toInt())
    }

    private fun bigEndian(value: Int): ByteArray = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }

    private fun deflate(raw: ByteArray): ByteArray = memScoped {
        val out = ByteArray(compressBound(raw.size.convert()).toInt())
        val length = alloc<uLongfVar>()
        length.value = out.size.convert()
        val status = out.usePinned { destination ->
            raw.usePinned { source ->
                compress2(
                    destination.addressOf(0).reinterpret(),
                    length.ptr,
                    source.addressOf(0).reinterpret(),
                    raw.size.convert(),
                    6,
                )
            }
        }
        check(status == Z_OK) { "zlib compress2 failed: $status" }
        out.copyOf(length.value.toInt())
    }

    /** A 480x272 container of seeded random blocks: random bits reach every ETC2 mode, as content would. */
    private fun etc2Frame(index: Int, levels: Int): ByteArray {
        var payloadBytes = 0
        for (level in 0 until levels) {
            payloadBytes += ((maxOf(1, WIDTH shr level) + 3) / 4) * ((maxOf(1, HEIGHT shr level) + 3) / 4) * 8
        }
        var state = index * 0x9E3779B97F4A7C15uL.toLong() + 1
        val payload = ByteArray(payloadBytes) {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            state.toByte()
        }
        val header = "RET2".encodeToByteArray() + byteArrayOf(1, 1, levels.toByte(), 0) +
            byteArrayOf(WIDTH.toByte(), (WIDTH shr 8).toByte(), 0, 0, HEIGHT.toByte(), (HEIGHT shr 8).toByte(), 0, 0)
        return header + payload
    }

    private object NoStore : Store {
        override suspend fun read(key: RawResourceKey): StoredRawResource? = null

        override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {}
    }

    private companion object {
        const val FRAMES = 300
        const val WARM_UP = 10
        const val WIDTH = 480
        const val HEIGHT = 272
        const val FULL_CHAIN = 9 // floor(log2(480)) + 1
        val OUTPUT = OutputPixelSize(720, 1280)
    }
}
