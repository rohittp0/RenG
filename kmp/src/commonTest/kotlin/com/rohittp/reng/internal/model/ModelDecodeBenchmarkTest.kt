package com.rohittp.reng.internal.model

import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.internal.bench.RengBench
import com.rohittp.reng.internal.image.crc32
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * B5: [decodeModel] on a GLB carrying [IMAGES] embedded PNG textures of [SIDE]x[SIDE] RGBA, one
 * material each, so the decode is dominated by its images: each is admitted by projecting its RGBA
 * size from the PNG and then decoded, and both scan the PNG.
 *
 * The PNGs are written here, as zlib *stored* blocks with Sub-filtered rows: commonTest has no deflate
 * encoder, and a stored stream still pays every chunk walk, CRC and filter a compressed one does --
 * only the inflate itself is cheaper. The fixture is built once; one decode is one iteration.
 */
class ModelDecodeBenchmarkTest {
    @Test
    fun decodingAModelWithEmbeddedPngTextures() {
        val glb = texturedGlb()
        val limits = ResourceLimits()
        RengBench.info("b5.model_decode.glb_4x512px_png", "glb_bytes=${glb.size}")
        RengBench.measure("b5.model_decode.glb_4x512px_png", warmupIterations = 5, minimumIterations = 20) {
            val result = timed { decodeModel(glb, limits) }
            val model = assertIs<ModelDecodeResult.Success>(result).model
            fold(model.images.size)
            fold(model.decodedCpuBytes)
        }
    }

    private fun texturedGlb(): ByteArray {
        val pngs = List(IMAGES) { pngOf(seed = it) }
        val bin = Bytes()
        bin.f32(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        bin.u16(0, 1, 2)
        bin.alignTo4()
        val views = mutableListOf("""{"buffer": 0, "byteOffset": 0, "byteLength": 36}""", """{"buffer": 0, "byteOffset": 36, "byteLength": 6}""")
        for (png in pngs) {
            views += """{"buffer": 0, "byteOffset": ${bin.size}, "byteLength": ${png.size}}"""
            bin.raw(png)
            bin.alignTo4()
        }
        val binChunk = bin.build()
        val json = """
            {"asset": {"version": "2.0"},
             "buffers": [{"byteLength": ${binChunk.size}}],
             "bufferViews": [${views.joinToString(",")}],
             "accessors": [
               {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3", "min": [0, 0, 0], "max": [1, 1, 0]},
               {"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"}],
             "images": [${List(IMAGES) { """{"bufferView": ${it + 2}, "mimeType": "image/png"}""" }.joinToString(",")}],
             "textures": [${List(IMAGES) { """{"source": $it}""" }.joinToString(",")}],
             "materials": [${List(IMAGES) { """{"pbrMetallicRoughness": {"baseColorTexture": {"index": $it}}}""" }.joinToString(",")}],
             "meshes": [{"primitives": [${List(IMAGES) { """{"attributes": {"POSITION": 0}, "indices": 1, "material": $it}""" }.joinToString(",")}]}],
             "nodes": [{"mesh": 0}],
             "scenes": [{"nodes": [0]}]}
        """.trimIndent()
        return glb(json, binChunk)
    }

    /** A [SIDE]x[SIDE] RGBA PNG: gradients and a seeded grain, Sub-filtered, in zlib stored blocks. */
    private fun pngOf(seed: Int): ByteArray {
        val stride = SIDE * 4 + 1
        val raw = ByteArray(stride * SIDE)
        var state = seed * 7919 + 1
        for (y in 0 until SIDE) {
            raw[y * stride] = 1 // Sub
            var left = IntArray(4)
            for (x in 0 until SIDE) {
                state = state * 1103515245 + 12345
                val grain = (state ushr 16) and 7
                val pixel = intArrayOf((x + grain) and 0xFF, (y + grain) and 0xFF, (x xor y) and 0xFF, 255)
                for (channel in 0 until 4) raw[y * stride + 1 + x * 4 + channel] = (pixel[channel] - left[channel]).toByte()
                left = pixel
            }
        }
        val zlib = Bytes()
        zlib.u8(0x78, 0x01)
        var offset = 0
        while (offset < raw.size) {
            val length = minOf(65_535, raw.size - offset)
            val last = offset + length == raw.size
            zlib.u8(if (last) 1 else 0, length, length ushr 8, length.inv(), length.inv() ushr 8)
            zlib.raw(raw.copyOfRange(offset, offset + length))
            offset += length
        }
        val adler = adler32(raw)
        zlib.u8(adler ushr 24, adler ushr 16, adler ushr 8, adler)
        val ihdr = Bytes().apply { be32(SIDE, SIDE); u8(8, 6, 0, 0, 0) }.build()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", ihdr) + chunk("IDAT", zlib.build()) +
            chunk("IEND", ByteArray(0))
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typed = type.encodeToByteArray() + data
        val crc = crc32(0u, typed, 0, typed.size).toInt()
        return Bytes().apply { be32(data.size) }.build() + typed + Bytes().apply { be32(crc) }.build()
    }

    private fun adler32(data: ByteArray): Int {
        var a = 1
        var b = 0
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }

    /** The glTF 2.0 binary container: header, a JSON chunk padded with spaces, a zero-padded BIN chunk. */
    private fun glb(json: String, binChunk: ByteArray): ByteArray {
        val jsonBytes = json.encodeToByteArray()
        val jsonPadding = (4 - jsonBytes.size % 4) % 4
        val binPadding = (4 - binChunk.size % 4) % 4
        val out = Bytes()
        out.u32(0x46546C67, 2, 12 + 8 + jsonBytes.size + jsonPadding + 8 + binChunk.size + binPadding)
        out.u32(jsonBytes.size + jsonPadding, 0x4E4F534A)
        out.raw(jsonBytes)
        repeat(jsonPadding) { out.u8(0x20) }
        out.u32(binChunk.size + binPadding, 0x004E4942)
        out.raw(binChunk)
        repeat(binPadding) { out.u8(0) }
        return out.build()
    }

    /** A growable byte buffer: little-endian [u16]/[u32]/[f32] as glTF wants, big-endian [be32] as PNG does. */
    private class Bytes {
        private var buffer = ByteArray(1024)
        var size = 0
            private set

        fun u8(vararg values: Int) {
            for (value in values) {
                if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
                buffer[size++] = value.toByte()
            }
        }

        fun u16(vararg values: Int) = values.forEach { u8(it, it ushr 8) }

        fun u32(vararg values: Int) = values.forEach { u8(it, it ushr 8, it ushr 16, it ushr 24) }

        fun be32(vararg values: Int) = values.forEach { u8(it ushr 24, it ushr 16, it ushr 8, it) }

        fun f32(vararg values: Float) = values.forEach { u32(it.toRawBits()) }

        fun raw(bytes: ByteArray) {
            if (size + bytes.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + bytes.size))
            bytes.copyInto(buffer, size)
            size += bytes.size
        }

        fun alignTo4() {
            while (size % 4 != 0) u8(0)
        }

        fun build(): ByteArray = buffer.copyOf(size)
    }

    private companion object {
        const val IMAGES = 4
        const val SIDE = 512
    }
}
