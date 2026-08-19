package com.rohittp.reng

import com.rohittp.reng.internal.gl.MAXIMUM_CONSUMER_TEXTURES
import com.rohittp.reng.internal.gl.UNIFORM_MODEL_VIEW_PROJECTION
import com.rohittp.reng.internal.gl.UNIFORM_RESOLUTION
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Cycle F-1 Task 7: reserved-name rejection and the consumer-texture budget belong at `Geometry`
 * construction — loudly, at the point of the mistake — rather than at draw time, where RenG's own
 * binding would otherwise silently win.
 */
class GeometryValidationTest {

    @Test
    fun aConsumerUniformCollidingWithADocumentedNameIsRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            geometryWith(uniforms = mapOf(UNIFORM_MODEL_VIEW_PROJECTION to ShaderValue.Scalar(1f)))
        }
    }

    @Test
    fun aConsumerTextureCollidingWithADocumentedNameIsRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            geometryWith(textures = mapOf(UNIFORM_RESOLUTION to ResourceLocator("https://example.invalid/a.png")))
        }
    }

    @Test
    fun aNonCollidingConsumerNameConstructsNormally() {
        geometryWith(uniforms = mapOf("uTint" to ShaderValue.Scalar(0.5f)))
        geometryWith(textures = mapOf("uMask" to ResourceLocator("https://example.invalid/a.png")))
    }

    @Test
    fun exceedingTheConsumerTextureBudgetIsATypedRejectionNotASilentDrop() {
        val tooMany = (0 until MAXIMUM_CONSUMER_TEXTURES + 1).associate {
            "uMask$it" to ResourceLocator("https://example.invalid/$it.png")
        }
        assertFailsWith<IllegalArgumentException> { geometryWith(textures = tooMany) }
    }

    @Test
    fun exactlyTheConsumerTextureBudgetConstructsNormally() {
        val exactly = (0 until MAXIMUM_CONSUMER_TEXTURES).associate {
            "uMask$it" to ResourceLocator("https://example.invalid/$it.png")
        }
        geometryWith(textures = exactly)
    }

    private fun geometryWith(
        uniforms: Map<String, ShaderValue> = emptyMap(),
        textures: Map<String, ResourceLocator> = emptyMap(),
    ): Geometry = Geometry(
        topLeft = Vector3(1.0, 0.0, 0.0),
        bottomRight = Vector3(0.0, 1.0, 0.0),
        shaderPair = ShaderPair("vertex", "fragment"),
        uniforms = uniforms,
        textures = textures,
    )
}
