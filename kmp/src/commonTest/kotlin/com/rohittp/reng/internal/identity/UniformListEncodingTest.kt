package com.rohittp.reng.internal.identity

import com.rohittp.reng.ShaderValue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [encodeUniformList] writes a geometry's uniforms in one pass; it must write exactly the bytes the
 * composed encoders did -- [composedUniforms] below is that encoding, kept verbatim as the oracle -- and
 * fail exactly where they failed.
 */
class UniformListEncodingTest {
    @Test
    fun randomUniformMapsEncodeExactlyAsTheComposedEncodersDid() {
        val random = Random(SEED)
        val names = HashMap<String, CanonicalBytes>()
        repeat(400) { case ->
            val uniforms = randomUniforms(random)
            assertContentEquals(
                composedUniforms(uniforms).bytes,
                encodeUniformList(uniforms, names).bytes,
                "case $case: $uniforms",
            )
        }
    }

    @Test
    fun anEmptyMapIsAnEmptyList() {
        assertContentEquals(composedUniforms(emptyMap()).bytes, encodeUniformList(emptyMap(), HashMap()).bytes)
    }

    @Test
    // Non-finite values never reach the encoder: ShaderValue refuses them at construction.
    fun negativeZeroIsCanonicalisedLikeTheComposedEncoders() {
        val zeros = mapOf("z" to ShaderValue.Vec2(-0f, 0f), "s" to ShaderValue.Scalar(-0f))
        assertContentEquals(composedUniforms(zeros).bytes, encodeUniformList(zeros, HashMap()).bytes)
    }

    @Test
    fun anInvalidNameFailsAsTheComposedEncodersDid() {
        val bad = mapOf("a" to ShaderValue.Scalar(1f), "\uD800lonely" to ShaderValue.Scalar(2f))
        val expected = assertFailsWith<IllegalArgumentException> { composedUniforms(bad) }
        val actual = assertFailsWith<IllegalArgumentException> { encodeUniformList(bad, HashMap()) }
        assertEquals(expected.message, actual.message)
    }

    @Test
    fun rememberedNamesStayBoundedAndCorrect() {
        val names = HashMap<String, CanonicalBytes>()
        repeat(3000) { index ->
            val uniforms = mapOf("u$index" to ShaderValue.Integer(index), "uOpacity" to ShaderValue.Scalar(0.5f))
            assertContentEquals(composedUniforms(uniforms).bytes, encodeUniformList(uniforms, names).bytes)
        }
        assertTrue(names.size <= 1024, "remembered ${names.size} names")
    }

    private fun randomUniforms(random: Random): Map<String, ShaderValue> {
        val count = random.nextInt(0, 24)
        return (0 until count).associate { index ->
            val name = when (random.nextInt(4)) {
                0 -> "u$index"
                1 -> "uGlyph[${random.nextInt(64)}]"
                2 -> "\u00e9t\u00e9$index"
                else -> "\uD83D\uDE00$index"
            }
            name to randomValue(random)
        }
    }

    private fun randomValue(random: Random): ShaderValue {
        fun f(): Float = when (random.nextInt(6)) {
            0 -> -0f
            1 -> 0f
            2 -> Float.MAX_VALUE
            3 -> Float.MIN_VALUE
            else -> (random.nextFloat() - 0.5f) * 2000f
        }
        return when (random.nextInt(6)) {
            0 -> ShaderValue.Scalar(f())
            1 -> ShaderValue.Vec2(f(), f())
            2 -> ShaderValue.Vec3(f(), f(), f())
            3 -> ShaderValue.Vec4(f(), f(), f(), f())
            4 -> ShaderValue.Integer(random.nextInt())
            else -> ShaderValue.Mat4(FloatArray(16) { f() })
        }
    }

    /** The encoding before [encodeUniformList]: `FramePlanCanonicalEncoder.encodeUniforms` as it was. */
    private fun composedUniforms(uniforms: Map<String, ShaderValue>): CanonicalBytes =
        CanonicalBinary.list(
            uniforms.entries.sortedBy { it.key }.map { (name, value) ->
                CanonicalBinary.fields {
                    field(1, CanonicalBinary.exactUtf8(name))
                    field(2, composedShaderValue(value))
                }
            },
        )

    private companion object {
        const val SEED = 20261009
    }
}

private fun composedShaderValue(value: ShaderValue): CanonicalBytes = CanonicalBinary.fields {
    when (value) {
        is ShaderValue.Scalar -> {
            field(1, CanonicalBinary.u16(SHADER_VALUE_SCALAR_WIRE_VALUE))
            field(2, CanonicalBinary.binary64(value.value.toDouble()))
        }
        is ShaderValue.Vec2 -> {
            field(1, CanonicalBinary.u16(SHADER_VALUE_VEC2_WIRE_VALUE))
            field(
                2,
                CanonicalBinary.list(
                    listOf(
                        CanonicalBinary.binary64(value.x.toDouble()),
                        CanonicalBinary.binary64(value.y.toDouble()),
                    ),
                ),
            )
        }
        is ShaderValue.Vec3 -> {
            field(1, CanonicalBinary.u16(SHADER_VALUE_VEC3_WIRE_VALUE))
            field(
                2,
                CanonicalBinary.list(
                    listOf(
                        CanonicalBinary.binary64(value.x.toDouble()),
                        CanonicalBinary.binary64(value.y.toDouble()),
                        CanonicalBinary.binary64(value.z.toDouble()),
                    ),
                ),
            )
        }
        is ShaderValue.Vec4 -> {
            field(1, CanonicalBinary.u16(SHADER_VALUE_VEC4_WIRE_VALUE))
            field(
                2,
                CanonicalBinary.list(
                    listOf(
                        CanonicalBinary.binary64(value.x.toDouble()),
                        CanonicalBinary.binary64(value.y.toDouble()),
                        CanonicalBinary.binary64(value.z.toDouble()),
                        CanonicalBinary.binary64(value.w.toDouble()),
                    ),
                ),
            )
        }
        is ShaderValue.Integer -> {
            field(1, CanonicalBinary.u16(SHADER_VALUE_INTEGER_WIRE_VALUE))
            // Widen the Int's exact 32-bit two's-complement pattern into a nonnegative Long payload;
            // sign-extension by toLong() is masked off, so the mapping is exact and injective.
            field(2, CanonicalBinary.u64(value.value.toLong() and 0xFFFFFFFFL))
        }
        is ShaderValue.Mat4 -> {
            field(1, CanonicalBinary.u16(SHADER_VALUE_MAT4_WIRE_VALUE))
            field(
                2,
                CanonicalBinary.list(
                    value.elementsForCore().map { CanonicalBinary.binary64(it.toDouble()) },
                ),
            )
        }
    }
}
