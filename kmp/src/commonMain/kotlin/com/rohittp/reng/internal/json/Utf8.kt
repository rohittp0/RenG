package com.rohittp.reng.internal.json

/**
 * A strict, RFC 3629 (shortest-form only) UTF-8 scalar decoder. It never substitutes a
 * replacement character: every malformed byte sequence is reported as a distinct [JsonReject]
 * so the caller can reject the document rather than silently repair it.
 */
internal sealed interface Utf8Scalar {
    data class Decoded(val codePoint: Int, val nextIndex: Int) : Utf8Scalar

    data class Invalid(val reason: JsonReject) : Utf8Scalar
}

/**
 * Decodes exactly one Unicode scalar value starting at [index]. [endExclusive] bounds the span;
 * bytes at or beyond it are treated as absent (a truncated sequence), never read.
 */
internal fun decodeUtf8Scalar(bytes: ByteArray, index: Int, endExclusive: Int): Utf8Scalar {
    val lead = bytes[index].toUnsignedInt()

    return when {
        lead < 0x80 -> Utf8Scalar.Decoded(lead, index + 1)
        lead < 0xC0 -> Utf8Scalar.Invalid(JsonReject.UTF8_INVALID_LEAD_BYTE)
        lead < 0xC2 -> Utf8Scalar.Invalid(JsonReject.UTF8_OVERLONG)
        lead < 0xE0 -> decodeTwoByte(bytes, index, endExclusive, lead)
        lead < 0xF0 -> decodeThreeByte(bytes, index, endExclusive, lead)
        lead < 0xF5 -> decodeFourByte(bytes, index, endExclusive, lead)
        else -> Utf8Scalar.Invalid(JsonReject.UTF8_INVALID_LEAD_BYTE)
    }
}

private fun Byte.toUnsignedInt(): Int = toInt() and 0xFF

/** Returns the byte at [index] if it is present and shaped `10xxxxxx`, else null. */
private fun continuationByteAt(bytes: ByteArray, index: Int, endExclusive: Int): Int? {
    if (index >= endExclusive) return null
    val byte = bytes[index].toUnsignedInt()
    return if (byte and 0xC0 == 0x80) byte else null
}

private fun decodeTwoByte(bytes: ByteArray, index: Int, endExclusive: Int, lead: Int): Utf8Scalar {
    val continuation = continuationByteAt(bytes, index + 1, endExclusive)
        ?: return Utf8Scalar.Invalid(JsonReject.UTF8_TRUNCATED_SEQUENCE)
    val codePoint = ((lead and 0x1F) shl 6) or (continuation and 0x3F)
    return Utf8Scalar.Decoded(codePoint, index + 2)
}

private fun decodeThreeByte(bytes: ByteArray, index: Int, endExclusive: Int, lead: Int): Utf8Scalar {
    val first = continuationByteAt(bytes, index + 1, endExclusive)
        ?: return Utf8Scalar.Invalid(JsonReject.UTF8_TRUNCATED_SEQUENCE)
    if (lead == 0xE0 && first < 0xA0) return Utf8Scalar.Invalid(JsonReject.UTF8_OVERLONG)
    if (lead == 0xED && first >= 0xA0) return Utf8Scalar.Invalid(JsonReject.UTF8_ENCODED_SURROGATE)
    val second = continuationByteAt(bytes, index + 2, endExclusive)
        ?: return Utf8Scalar.Invalid(JsonReject.UTF8_TRUNCATED_SEQUENCE)
    val codePoint = ((lead and 0x0F) shl 12) or ((first and 0x3F) shl 6) or (second and 0x3F)
    return Utf8Scalar.Decoded(codePoint, index + 3)
}

private fun decodeFourByte(bytes: ByteArray, index: Int, endExclusive: Int, lead: Int): Utf8Scalar {
    val first = continuationByteAt(bytes, index + 1, endExclusive)
        ?: return Utf8Scalar.Invalid(JsonReject.UTF8_TRUNCATED_SEQUENCE)
    if (lead == 0xF0 && first < 0x90) return Utf8Scalar.Invalid(JsonReject.UTF8_OVERLONG)
    if (lead == 0xF4 && first > 0x8F) return Utf8Scalar.Invalid(JsonReject.UTF8_INVALID_CONTINUATION)
    val second = continuationByteAt(bytes, index + 2, endExclusive)
        ?: return Utf8Scalar.Invalid(JsonReject.UTF8_TRUNCATED_SEQUENCE)
    val third = continuationByteAt(bytes, index + 3, endExclusive)
        ?: return Utf8Scalar.Invalid(JsonReject.UTF8_TRUNCATED_SEQUENCE)
    val codePoint = ((lead and 0x07) shl 18) or ((first and 0x3F) shl 12) or ((second and 0x3F) shl 6) or (third and 0x3F)
    return Utf8Scalar.Decoded(codePoint, index + 4)
}
