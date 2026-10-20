package com.rohittp.reng.internal.gl

import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.image.Etc2ContainerResult
import com.rohittp.reng.internal.image.Etc2Format
import com.rohittp.reng.internal.image.decodeEtc2Rows
import com.rohittp.reng.internal.image.parseEtc2Container
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Whether the profile's ETC2 answer is the driver's, and -- where the driver decodes ETC2 itself --
 * whether RenG's CPU decoder agrees with it texel for texel (ADR 0084).
 *
 * The decoder is the path a context that cannot sample ETC2 draws through, so the only independent
 * oracle for it is a GPU that can. Every format is uploaded natively as one 62x61 level of seeded
 * random blocks -- random bits reach every mode, and the test counts that they did -- read back with
 * `texelFetch` and compared exactly with [decodeEtc2Rows] over the same container. The odd size puts
 * partial blocks on two edges.
 *
 * Where [RenderContextProfile.supportsEtc2] is false, the driver must refuse the format, or the profile
 * is sending a context that could sample blocks down the decode path.
 */
internal fun assertEtc2DecodesAsTheDriverDoes(binding: GlBinding, profile: RenderContextProfile) {
    GlErrorQueue.drainOnEntry(binding)
    if (!profile.supportsEtc2) {
        val names = IntArray(1)
        binding.genTextures(1, names)
        binding.bindTexture(GL_TEXTURE_2D, names[0])
        binding.compressedTexImage2D(GL_TEXTURE_2D, 0, GL_COMPRESSED_RGB8_ETC2, 4, 4, 0, 8, ByteArray(8), 0)
        val flag = GlErrorQueue.firstOwnError(binding)
        binding.bindTexture(GL_TEXTURE_2D, 0)
        binding.deleteTextures(1, names)
        GlErrorQueue.drainOnEntry(binding)
        assertTrue(flag != GL_NO_ERROR, "a context the profile says cannot sample ETC2 accepted an upload")
        return
    }

    val program = linkTexelFetchProgram(binding, profile)
    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val buffer = names[0]
    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, buffer)
    val triangle = littleEndianBytes(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f))
    binding.bufferData(GL_ARRAY_BUFFER, triangle.size, triangle, GL_STATIC_DRAW)
    binding.enableVertexAttribArray(0)
    binding.vertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0)

    binding.genTextures(1, names)
    val target = names[0]
    binding.bindTexture(GL_TEXTURE_2D, target)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, WIDTH, HEIGHT)
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0)
    assertEquals(GL_FRAMEBUFFER_COMPLETE, binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER))
    binding.viewport(0, 0, WIDTH, HEIGHT)
    binding.disable(GL_SCISSOR_TEST)
    binding.disable(GL_BLEND)
    binding.colorMask(true, true, true, true)
    binding.useProgram(program)
    binding.uniform1i(binding.getUniformLocation(program, "rengEtc2Texture"), 0)
    binding.activeTexture(GL_TEXTURE0)

    val mismatches = ArrayList<String>()
    Etc2Format.entries.forEachIndexed { index, format ->
        val container = randomContainer(format, seed = 0x9E3779B97F4A7C15uL.toLong() + index)
        val image = (parseEtc2Container(container, Long.MAX_VALUE) as Etc2ContainerResult.Admitted).image
        val expected = ByteArray(WIDTH * HEIGHT * 4)
        decodeEtc2Rows(image, 0, 0, (HEIGHT + 3) / 4, expected)

        binding.genTextures(1, names)
        val source = names[0]
        binding.bindTexture(GL_TEXTURE_2D, source)
        image.readLevel(0) { bytes, offset, length ->
            binding.compressedTexImage2D(
                GL_TEXTURE_2D, 0, format.glToken, WIDTH, HEIGHT, 0, length, bytes, offset,
            )
        }
        binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0)
        binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        binding.drawArrays(GL_TRIANGLES, 0, 3)

        val actual = ByteArray(WIDTH * HEIGHT * 4)
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer)
        binding.readBuffer(GL_COLOR_ATTACHMENT0)
        binding.readPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, actual)
        binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        binding.deleteTextures(1, intArrayOf(source))
        assertEquals(GL_NO_ERROR, GlErrorQueue.firstOwnError(binding), "$format must upload, draw and read")

        val differing = (0 until WIDTH * HEIGHT).filter { texel ->
            (0 until 4).any { expected[texel * 4 + it] != actual[texel * 4 + it] }
        }
        if (differing.isNotEmpty()) {
            val first = differing.first()
            mismatches += "$format: ${differing.size} texels differ, first at " +
                "(${first % WIDTH}, ${first / WIDTH}): decoder ${rgba(expected, first)}, driver ${rgba(actual, first)}"
        }
    }

    binding.useProgram(0)
    binding.bindTexture(GL_TEXTURE_2D, 0)
    binding.bindVertexArray(0)
    binding.bindBuffer(GL_ARRAY_BUFFER, 0)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    binding.deleteFramebuffers(1, intArrayOf(framebuffer))
    binding.deleteTextures(1, intArrayOf(target))
    binding.deleteBuffers(1, intArrayOf(buffer))
    binding.deleteVertexArrays(1, intArrayOf(vertexArray))
    binding.deleteProgram(program)
    GlErrorQueue.drainOnEntry(binding)
    if (mismatches.isNotEmpty()) {
        fail("RenG's ETC2 decoder disagrees with the driver:\n" + mismatches.joinToString("\n"))
    }
}

private fun linkTexelFetchProgram(binding: GlBinding, profile: RenderContextProfile): Int {
    val key = ResourceKeyDeriver().geometryProgram(ShaderPair(ETC2_VERTEX_SOURCE, ETC2_FRAGMENT_SOURCE)).key
    val result = compileShaderProgram(
        binding,
        profile.dialect,
        key,
        requireNotNull(scanShaderProfile(ETC2_VERTEX_SOURCE)),
        requireNotNull(scanShaderProfile(ETC2_FRAGMENT_SOURCE)),
    )
    return (result as? GlProgramResult.Linked)?.program ?: fail("the texelFetch program must link: $result")
}

/**
 * One level of seeded random blocks, asserted to reach every colour mode the format has, so a mode the
 * decoder gets wrong cannot hide behind a seed that never reached it.
 */
private fun randomContainer(format: Etc2Format, seed: Long): ByteArray {
    val blockCount = ((WIDTH + 3) / 4) * ((HEIGHT + 3) / 4)
    var state = seed
    fun next(): Long {
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        return state
    }
    val payload = ByteArray(blockCount * format.blockBytes)
    val colourOffset = if (format == Etc2Format.RGBA8_EAC) 8 else 0
    val modes = HashSet<String>()
    for (block in 0 until blockCount) {
        for (word in 0 until format.blockBytes / 8) {
            val value = next()
            val at = block * format.blockBytes + word * 8
            for (byte in 0 until 8) payload[at + byte] = (value ushr (56 - 8 * byte)).toByte()
        }
        modes += colourMode(payload, block * format.blockBytes + colourOffset, format)
    }
    val expectedModes = if (format == Etc2Format.RGB8_PUNCHTHROUGH_ALPHA1) 4 else 5
    assertEquals(expectedModes, modes.size, "$format's random blocks must reach every mode, reached $modes")
    val header = byteArrayOf(
        'R'.code.toByte(), 'E'.code.toByte(), 'T'.code.toByte(), '2'.code.toByte(),
        1, format.code.toByte(), 1, 0,
        WIDTH.toByte(), 0, 0, 0,
        HEIGHT.toByte(), 0, 0, 0,
    )
    return header + payload
}

/** The specification's mode selection, restated here so the coverage count does not trust the decoder. */
private fun colourMode(bytes: ByteArray, offset: Int, format: Etc2Format): String {
    fun field(byteIndex: Int, shift: Int, mask: Int) = (bytes[offset + byteIndex].toInt() ushr shift) and mask
    val differentialBit = field(3, 1, 1)
    if (format != Etc2Format.RGB8_PUNCHTHROUGH_ALPHA1 && differentialBit == 0) return "individual"
    fun signed3(value: Int) = if (value >= 4) value - 8 else value
    return when {
        field(0, 3, 31) + signed3(field(0, 0, 7)) !in 0..31 -> "T"
        field(1, 3, 31) + signed3(field(1, 0, 7)) !in 0..31 -> "H"
        field(2, 3, 31) + signed3(field(2, 0, 7)) !in 0..31 -> "planar"
        else -> "differential"
    }
}

private val Etc2Format.glToken: Int
    get() = when (this) {
        Etc2Format.RGB8 -> GL_COMPRESSED_RGB8_ETC2
        Etc2Format.RGB8_PUNCHTHROUGH_ALPHA1 -> GL_COMPRESSED_RGB8_PUNCHTHROUGH_ALPHA1_ETC2
        Etc2Format.RGBA8_EAC -> GL_COMPRESSED_RGBA8_ETC2_EAC
    }

private fun rgba(bytes: ByteArray, texel: Int): String =
    (0 until 4).joinToString(prefix = "(", postfix = ")") { (bytes[texel * 4 + it].toInt() and 0xFF).toString() }

private const val WIDTH: Int = 62
private const val HEIGHT: Int = 61

private const val ETC2_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengEtc2Position;\n" +
        "void main() { gl_Position = vec4(rengEtc2Position, 0.0, 1.0); }\n"

private const val ETC2_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform highp sampler2D rengEtc2Texture;\n" +
        "layout(location = 0) out vec4 rengEtc2Colour;\n" +
        "void main() { rengEtc2Colour = texelFetch(rengEtc2Texture, ivec2(gl_FragCoord.xy), 0); }\n"
