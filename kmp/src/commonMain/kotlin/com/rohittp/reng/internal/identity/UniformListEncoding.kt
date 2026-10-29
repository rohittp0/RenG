package com.rohittp.reng.internal.identity

import com.rohittp.reng.ShaderValue
import com.rohittp.reng.internal.canonicalDouble

/**
 * A geometry's uniforms in canonical form -- exactly the bytes `CanonicalBinary.list` of
 * `CanonicalBinary.fields { field(1, exactUtf8(name)); field(2, value) }`, sorted by name, gives -- written
 * straight into one array.
 *
 * **Why.** Uniforms are most of a geometry's encoding, and composing it would make every uniform a name
 * record, a value record, a list and one canonical object per float, each allocated and copied into the
 * next level up on every `prepare` (ADR 0081). Here the list is sized in one pass and
 * written in a second, with no intermediate objects.
 *
 * **The bytes and the failures are the composed encoders'.** Names go through [CanonicalBinary.exactUtf8]
 * (validated once, then remembered in [names]), in the same entry order, so an invalid name fails with
 * the message it always did. Values are written as [canonicalDouble] writes them -- negative zero as zero
 * -- but not checked again: a [ShaderValue] is immutable and refuses a non-finite component at
 * construction (every variant's `init`, and [ShaderValue] is sealed), so the finiteness check the
 * composed encoders made could never fail here. `UniformListEncodingTest` holds this to the composed encoding over
 * randomised maps.
 *
 * [names] belongs to one encoder, which only its own planning core calls, one `prepare` at a time.
 */
internal fun encodeUniformList(
    uniforms: Map<String, ShaderValue>,
    names: MutableMap<String, CanonicalBytes>,
): CanonicalBytes {
    val sortedNames = uniforms.keys.sorted()
    val count = sortedNames.size
    val encodedNames = arrayOfNulls<CanonicalBytes>(count)
    val values = arrayOfNulls<ShaderValue>(count)
    // Every name is validated before any size is checked, as the composed encoders encoded every
    // entry before `CanonicalBinary.list` summed them: an invalid name later in the map fails as an
    // invalid name, not as an oversized list.
    for (index in 0 until count) {
        val name = sortedNames[index]
        val encodedName = names[name] ?: CanonicalBinary.exactUtf8(name).also { rememberName(names, name, it) }
        encodedNames[index] = encodedName
        values[index] = uniforms.getValue(name)
    }
    var size = U32.toLong()
    for (index in 0 until count) {
        size += U32 + entrySize(encodedNames[index]!!.size, values[index]!!)
        requireCanonicalSize(size)
    }

    val output = ByteArray(size.toInt())
    var offset = putU32(output, 0, count)
    for (index in 0 until count) {
        val encodedName = encodedNames[index]!!
        val value = values[index]!!
        offset = putU32(output, offset, entrySize(encodedName.size, value).toInt())
        offset = putU16(output, offset, 1)
        offset = putU32(output, offset, encodedName.size)
        encodedName.copyInto(output, offset)
        offset += encodedName.size
        offset = putU16(output, offset, 2)
        offset = putU32(output, offset, valueSize(value).toInt())
        offset = writeValue(output, offset, value)
    }
    check(offset == output.size) { "uniform list sized ${output.size} bytes but wrote $offset" }
    return CanonicalBytes.adopting(output)
}

private fun rememberName(names: MutableMap<String, CanonicalBytes>, name: String, encoded: CanonicalBytes) {
    // A consumer has a handful of uniform names; one that generated a name per frame must not grow this.
    if (names.size >= MAXIMUM_REMEMBERED_UNIFORM_NAMES) names.clear()
    names[name] = encoded
}

/**
 * A `fields { 1: name, 2: value }` record: two field headers, the name, the value. In `Long`, and checked
 * at each field as `CanonicalFieldWriter.field` checks it, so a name too large to encode fails as it did.
 */
private fun entrySize(nameSize: Int, value: ShaderValue): Long {
    val withName = requireCanonicalSize(FIELD_HEADER.toLong() + nameSize)
    return requireCanonicalSize(withName.toLong() + FIELD_HEADER + valueSize(value)).toLong()
}

/** A value record: the u16 kind field, then the payload field. */
private fun valueSize(value: ShaderValue): Long = FIELD_HEADER.toLong() + U16 + FIELD_HEADER + when (value) {
    is ShaderValue.Scalar -> U64
    is ShaderValue.Vec2 -> listSize(2)
    is ShaderValue.Vec3 -> listSize(3)
    is ShaderValue.Vec4 -> listSize(4)
    is ShaderValue.Integer -> U64
    is ShaderValue.Mat4 -> listSize(MAT4_ELEMENTS)
}

/** `CanonicalBinary.list` of [count] binary64s: the count, then each element's length and bytes. */
private fun listSize(count: Int): Int = U32 + count * (U32 + U64)

private fun writeValue(output: ByteArray, start: Int, value: ShaderValue): Int {
    var offset = putU16(output, start, 1)
    offset = putU32(output, offset, U16)
    offset = putU16(output, offset, wireValueOf(value))
    offset = putU16(output, offset, 2)
    return when (value) {
        is ShaderValue.Scalar -> putBinary64(output, putU32(output, offset, U64), value.value)
        is ShaderValue.Integer -> putLong(output, putU32(output, offset, U64), value.value.toLong() and 0xFFFFFFFFL)
        is ShaderValue.Vec2 -> {
            var at = putListHeader(output, offset, 2)
            at = putElement(output, at, value.x)
            putElement(output, at, value.y)
        }
        is ShaderValue.Vec3 -> {
            var at = putListHeader(output, offset, 3)
            at = putElement(output, at, value.x)
            at = putElement(output, at, value.y)
            putElement(output, at, value.z)
        }
        is ShaderValue.Vec4 -> {
            var at = putListHeader(output, offset, 4)
            at = putElement(output, at, value.x)
            at = putElement(output, at, value.y)
            at = putElement(output, at, value.z)
            putElement(output, at, value.w)
        }
        is ShaderValue.Mat4 -> {
            val elements = value.elementsForCore()
            var at = putListHeader(output, offset, elements.size)
            elements.forEach { element -> at = putElement(output, at, element) }
            at
        }
    }
}

private fun wireValueOf(value: ShaderValue): Int = when (value) {
    is ShaderValue.Scalar -> SHADER_VALUE_SCALAR_WIRE_VALUE
    is ShaderValue.Vec2 -> SHADER_VALUE_VEC2_WIRE_VALUE
    is ShaderValue.Vec3 -> SHADER_VALUE_VEC3_WIRE_VALUE
    is ShaderValue.Vec4 -> SHADER_VALUE_VEC4_WIRE_VALUE
    is ShaderValue.Integer -> SHADER_VALUE_INTEGER_WIRE_VALUE
    is ShaderValue.Mat4 -> SHADER_VALUE_MAT4_WIRE_VALUE
}

/** A payload field's length, then the list's own element count. */
private fun putListHeader(output: ByteArray, start: Int, count: Int): Int =
    putU32(output, putU32(output, start, listSize(count)), count)

/** One list element: its length, then its binary64. */
private fun putElement(output: ByteArray, start: Int, value: Float): Int = putBinary64(output, putU32(output, start, U64), value)

private fun requireCanonicalSize(size: Long): Int {
    require(size in 0L..Int.MAX_VALUE.toLong()) { "Canonical encoding exceeds the supported size" }
    return size.toInt()
}

/**
 * [canonicalDouble] without its finiteness check, which a [ShaderValue] component always passes (see
 * [encodeUniformList]): a finite `Float` widens to a finite `Double`, and negative zero becomes zero.
 */
private fun canonical(value: Float): Double {
    val widened = value.toDouble()
    return if (widened == 0.0) 0.0 else widened
}

private fun putBinary64(output: ByteArray, offset: Int, value: Float): Int = putLong(output, offset, canonical(value).toBits())

private fun putLong(output: ByteArray, offset: Int, value: Long): Int {
    for (index in 0 until U64) {
        output[offset + index] = (value ushr ((U64 - 1 - index) * Byte.SIZE_BITS)).toByte()
    }
    return offset + U64
}

private fun putU16(output: ByteArray, offset: Int, value: Int): Int {
    output[offset] = (value ushr Byte.SIZE_BITS).toByte()
    output[offset + 1] = value.toByte()
    return offset + U16
}

private fun putU32(output: ByteArray, offset: Int, value: Int): Int {
    output[offset] = (value ushr 24).toByte()
    output[offset + 1] = (value ushr 16).toByte()
    output[offset + 2] = (value ushr 8).toByte()
    output[offset + 3] = value.toByte()
    return offset + U32
}

private const val U16: Int = 2
private const val U32: Int = 4
private const val U64: Int = 8
private const val FIELD_HEADER: Int = U16 + U32
private const val MAT4_ELEMENTS: Int = 16
private const val MAXIMUM_REMEMBERED_UNIFORM_NAMES: Int = 1024
