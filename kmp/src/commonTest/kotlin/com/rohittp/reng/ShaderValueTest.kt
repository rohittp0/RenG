package com.rohittp.reng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ShaderValueTest {
    @Test
    fun negativeZeroIsCanonicalizedLikeEveryOtherRenGValue() {
        assertEquals(ShaderValue.Vec2(0.0f, 0.0f), ShaderValue.Vec2(-0.0f, -0.0f))
    }

    @Test
    fun everyNonFiniteComponentIsRejected() {
        assertFailsWith<IllegalArgumentException> { ShaderValue.Scalar(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { ShaderValue.Vec3(1f, Float.POSITIVE_INFINITY, 3f) }
        assertFailsWith<IllegalArgumentException> { ShaderValue.Mat4(FloatArray(16) { Float.NaN }) }
    }

    @Test
    fun aMat4RequiresExactlySixteenElementsAndComparesByValue() {
        assertFailsWith<IllegalArgumentException> { ShaderValue.Mat4(FloatArray(15)) }
        assertEquals(ShaderValue.Mat4(FloatArray(16) { it.toFloat() }), ShaderValue.Mat4(FloatArray(16) { it.toFloat() }))
    }

    @Test
    fun everyRemainingVariantRejectsAnyNonFiniteComponent() {
        assertFailsWith<IllegalArgumentException> { ShaderValue.Vec2(Float.NaN, 0f) }
        assertFailsWith<IllegalArgumentException> { ShaderValue.Vec2(0f, Float.NEGATIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { ShaderValue.Vec4(0f, 0f, 0f, Float.NaN) }
        // Integer has no non-finite representation to reject.
        assertEquals(ShaderValue.Integer(7), ShaderValue.Integer(7))
    }

    @Test
    fun aMat4CopiesItsInputArrayDefensivelyAndCanonicalizesNegativeZero() {
        val elements = FloatArray(16) { -0.0f }
        val mat4 = ShaderValue.Mat4(elements)
        elements[0] = 42f

        assertEquals(ShaderValue.Mat4(FloatArray(16) { 0.0f }), mat4)
    }

    @Test
    fun mat4ToStringNeverExposesItsElements() {
        val mat4 = ShaderValue.Mat4(FloatArray(16) { 123.456f })

        assertFalse(mat4.toString().contains("123.456"))
    }

    @Test
    fun distinctShaderValueVariantsAreNeverEqualToEachOther() {
        assertNotEquals<ShaderValue>(ShaderValue.Scalar(1f), ShaderValue.Integer(1))
    }
}
