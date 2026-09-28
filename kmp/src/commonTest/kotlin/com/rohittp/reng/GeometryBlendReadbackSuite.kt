package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_BLEND
import com.rohittp.reng.internal.gl.GL_COLOR_ATTACHMENT0
import com.rohittp.reng.internal.gl.GL_COLOR_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_DRAW_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_FRAMEBUFFER_COMPLETE
import com.rohittp.reng.internal.gl.GL_PACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_READ_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_RGBA
import com.rohittp.reng.internal.gl.GL_RGBA8
import com.rohittp.reng.internal.gl.GL_SCISSOR_TEST
import com.rohittp.reng.internal.gl.GL_TEXTURE_2D
import com.rohittp.reng.internal.gl.GL_UNSIGNED_BYTE
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.gl.ShaderDialect
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

internal const val GEOMETRY_BLEND_READBACK_PIXELS: Int = 32

internal fun runGeometryBlendReadbackSuite(
    binding: GlBinding,
    probe: RenderContextProbe,
    dialect: ShaderDialect,
) {
    val target = createGeometryBlendReadbackTarget(binding)
    val renderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(GEOMETRY_BLEND_READBACK_PIXELS, GEOMETRY_BLEND_READBACK_PIXELS),
            transport = ReadbackTransport(),
            store = RecordingStyleStore(),
            basemapStyle = null,
        ),
        binding,
        probe,
    )
    val failures = GeometryBlendCollectedFailures()
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))
        failures.run("a translucent Geometry blends over an earlier opaque Geometry") {
            val actual = clearAndDrawGeometryBlend(
                binding = binding,
                renderer = renderer,
                renderTarget = renderTarget,
                targetFramebuffer = target,
                plan = geometryBlendFramePlan(),
            )
            val expected = intArrayOf(80, 112, 144, 255)
            println(
                "RenG geometry blend readback: dialect=$dialect actual=${actual.describe()} " +
                    "expected=${expected.describe()}",
            )
            assertTrue(
                actual.indices.all { abs(actual[it] - expected[it]) <= 2 },
                "premultiplied (64,64,64,128) over opaque ${OPAQUE_BYTES.describe()} must be " +
                    "${expected.describe()} within 2, was ${actual.describe()}",
            )
        }
    } finally {
        renderer.close()
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
    failures.throwIfAny()
}

private fun geometryBlendFramePlan(): FramePlan = FramePlan(
    frameIndex = 0L,
    camera = Camera(
        latitude = -55.0,
        unwrappedLongitude = -135.0,
        zoom = 4.0,
        bearing = 0.0,
        pitch = 0.0,
    ),
    geometries = listOf(
        geometryAtAltitude(0.0, OPAQUE_FRAGMENT_SOURCE),
        geometryAtAltitude(1.0, TRANSLUCENT_FRAGMENT_SOURCE),
    ),
)

private fun geometryAtAltitude(altitudeMetres: Double, fragmentSource: String): Geometry = Geometry(
    topLeft = Vector3(-54.4, -136.0, altitudeMetres),
    bottomRight = Vector3(-55.6, -134.0, altitudeMetres),
    shaderPair = ShaderPair(GEOMETRY_VERTEX_SOURCE, fragmentSource),
)

private fun clearAndDrawGeometryBlend(
    binding: GlBinding,
    renderer: Renderer,
    renderTarget: RenderTarget,
    targetFramebuffer: Int,
    plan: FramePlan,
): IntArray {
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
    binding.disable(GL_BLEND)
    binding.disable(GL_SCISSOR_TEST)
    binding.colorMask(true, true, true, true)
    binding.viewport(0, 0, GEOMETRY_BLEND_READBACK_PIXELS, GEOMETRY_BLEND_READBACK_PIXELS)
    binding.clearColor(0f, 0f, 0f, 0f)
    binding.clear(GL_COLOR_BUFFER_BIT)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)

    val frame = runBlocking { renderer.prepare(plan) }
    try {
        renderer.draw(frame, renderTarget)
    } finally {
        frame.close()
    }

    val pixel = ByteArray(4)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
    binding.readPixels(
        GEOMETRY_BLEND_READBACK_PIXELS / 2,
        GEOMETRY_BLEND_READBACK_PIXELS / 2,
        1,
        1,
        GL_RGBA,
        GL_UNSIGNED_BYTE,
        pixel,
    )
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    return IntArray(4) { pixel[it].toInt() and 0xff }
}

private fun createGeometryBlendReadbackTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(
        GL_TEXTURE_2D,
        1,
        GL_RGBA8,
        GEOMETRY_BLEND_READBACK_PIXELS,
        GEOMETRY_BLEND_READBACK_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the geometry blend readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

private class GeometryBlendCollectedFailures {
    private val failures = ArrayList<String>()

    fun run(name: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            failures += "$name: ${failure.message ?: failure::class.simpleName}"
        }
    }

    fun throwIfAny() {
        if (failures.isNotEmpty()) {
            throw AssertionError(
                failures.joinToString(prefix = "Geometry blend readback failures:\n", separator = "\n"),
            )
        }
    }
}

private fun IntArray.describe(): String = joinToString(prefix = "(", postfix = ")")

private val OPAQUE_BYTES: IntArray = intArrayOf(32, 96, 160, 255)

private val GEOMETRY_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "in vec3 aPosition;\n" +
        "uniform mat4 uModelViewProjection;\n" +
        "void main() {\n" +
        "    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);\n" +
        "}\n"

private val OPAQUE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 rengGeometryColour;\n" +
        "void main() {\n" +
        "    rengGeometryColour = vec4(0.125490196, 0.376470588, 0.627450980, 1.0);\n" +
        "}\n"

private val TRANSLUCENT_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 rengGeometryColour;\n" +
        "void main() {\n" +
        "    rengGeometryColour = vec4(0.25, 0.25, 0.25, 0.5);\n" +
        "}\n"
