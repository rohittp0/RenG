package com.rohittp.reng.internal.json

/**
 * Every reason [parseJson] can reject a document. Structural grammar violations, number-spelling
 * violations, string-escape violations, and the UTF-8 decoder's own violations are kept distinct
 * because collapsing them into one code would lose exactly the information a caller needs to
 * decide whether a document is malformed or merely unsupported.
 */
internal enum class JsonReject {
    UNEXPECTED_CHARACTER,
    UNEXPECTED_END_OF_INPUT,
    TRAILING_CONTENT,
    DEPTH_EXCEEDED,
    EXPECTED_MEMBER_NAME,
    EXPECTED_COLON,
    EXPECTED_COMMA_OR_CLOSE,
    DUPLICATE_MEMBER_NAME,
    INVALID_LITERAL,
    LEADING_ZERO,
    BAD_FRACTION,
    BAD_EXPONENT,
    NON_FINITE_NUMBER,
    UNESCAPED_CONTROL_CHARACTER,
    BAD_ESCAPE,
    LONE_HIGH_SURROGATE_ESCAPE,
    LONE_LOW_SURROGATE_ESCAPE,
    UTF8_INVALID_LEAD_BYTE,
    UTF8_INVALID_CONTINUATION,
    UTF8_OVERLONG,
    UTF8_ENCODED_SURROGATE,
    UTF8_TRUNCATED_SEQUENCE,
}

/** A parsed JSON value. [Arr] and [Obj] are plain classes, not data classes: identity, not deep
 * structural equality, is what the rest of the resource layer needs from a parsed tree. */
internal sealed interface JsonValue {
    data object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    /** A number token with no fraction and no exponent that fits exactly in a [Long]. */
    data class Integer(val value: Long) : JsonValue

    /** Every other number token: fractional, exponential, or too large for [Long]. */
    data class Real(val value: Double) : JsonValue

    data class Text(val value: String) : JsonValue

    class Arr(val elements: List<JsonValue>) : JsonValue

    class Obj(val members: Map<String, JsonValue>) : JsonValue
}

internal sealed interface JsonParse {
    /**
     * [endOffset] is the position immediately after the value's own closing token — the closing
     * `}`/`]`/`"`, or the last digit or literal character — and never includes any trailing
     * whitespace that follows it. A caller enforcing what comes after the document (Task 7's GLB
     * padding rule, which requires every byte from here to the chunk's end to be `0x20`) needs
     * exactly this position, not the end of the scanned span.
     */
    data class Parsed(val value: JsonValue, val endOffset: Int) : JsonParse

    data class Failed(val reason: JsonReject) : JsonParse
}

/**
 * Parses one JSON value (RFC 8259, restricted to the strictness this module documents) from
 * `bytes[offset, endExclusive)`. Whitespace after the value is consumed only to confirm nothing
 * but whitespace remains before `endExclusive`; anything else remaining is
 * [JsonReject.TRAILING_CONTENT]. `maximumDepth` bounds array/object nesting so a parse can never
 * recurse past a caller-chosen ceiling.
 */
internal fun parseJson(bytes: ByteArray, offset: Int, endExclusive: Int, maximumDepth: Int): JsonParse {
    val parser = JsonParser(bytes, offset, endExclusive, maximumDepth)
    return try {
        val value = parser.parseValue(depth = 0)
        // Capture where the value's own closing token ended, before consuming any trailing
        // whitespace: the caller (Task 7's GLB padding rule) needs the document's true end, not
        // the end of whatever whitespace happens to follow it.
        val endOffset = parser.pos
        parser.skipWhitespace()
        if (parser.pos != endExclusive) {
            JsonParse.Failed(JsonReject.TRAILING_CONTENT)
        } else {
            JsonParse.Parsed(value, endOffset)
        }
    } catch (signal: JsonRejectSignal) {
        JsonParse.Failed(signal.reason)
    }
}

/** Internal control-flow signal: unwound by [parseJson] into a [JsonParse.Failed], never seen
 * outside this file. */
private class JsonRejectSignal(val reason: JsonReject) : RuntimeException()

private fun reject(reason: JsonReject): Nothing = throw JsonRejectSignal(reason)

private const val QUOTE = 0x22
private const val BACKSLASH = 0x5C

private class JsonParser(
    private val bytes: ByteArray,
    offset: Int,
    private val endExclusive: Int,
    private val maximumDepth: Int,
) {
    var pos: Int = offset

    private fun currentByte(): Int = bytes[pos].toInt() and 0xFF

    fun skipWhitespace() {
        while (pos < endExclusive) {
            when (currentByte()) {
                0x20, 0x09, 0x0A, 0x0D -> pos++
                else -> return
            }
        }
    }

    fun parseValue(depth: Int): JsonValue {
        skipWhitespace()
        if (pos >= endExclusive) reject(JsonReject.UNEXPECTED_END_OF_INPUT)
        return when (currentByte()) {
            '{'.code -> parseObject(depth)
            '['.code -> parseArray(depth)
            QUOTE -> JsonValue.Text(parseStringContent())
            't'.code -> parseLiteral("true", JsonValue.Bool(true))
            'f'.code -> parseLiteral("false", JsonValue.Bool(false))
            'n'.code -> parseLiteral("null", JsonValue.Null)
            '-'.code -> parseNumber()
            in '0'.code..'9'.code -> parseNumber()
            else -> reject(JsonReject.UNEXPECTED_CHARACTER)
        }
    }

    private fun nextDepth(depth: Int): Int {
        val next = depth + 1
        if (next > maximumDepth) reject(JsonReject.DEPTH_EXCEEDED)
        return next
    }

    private fun parseObject(depth: Int): JsonValue.Obj {
        val childDepth = nextDepth(depth)
        pos++ // consume '{'
        skipWhitespace()
        val members = LinkedHashMap<String, JsonValue>()
        if (pos < endExclusive && currentByte() == '}'.code) {
            pos++
            return JsonValue.Obj(members)
        }
        while (true) {
            skipWhitespace()
            if (pos >= endExclusive || currentByte() != QUOTE) reject(JsonReject.EXPECTED_MEMBER_NAME)
            val name = parseStringContent()
            skipWhitespace()
            if (pos >= endExclusive || currentByte() != ':'.code) reject(JsonReject.EXPECTED_COLON)
            pos++
            val value = parseValue(childDepth)
            if (members.containsKey(name)) reject(JsonReject.DUPLICATE_MEMBER_NAME)
            members[name] = value
            skipWhitespace()
            if (pos >= endExclusive) reject(JsonReject.UNEXPECTED_END_OF_INPUT)
            when (currentByte()) {
                ','.code -> pos++
                '}'.code -> {
                    pos++
                    return JsonValue.Obj(members)
                }
                else -> reject(JsonReject.EXPECTED_COMMA_OR_CLOSE)
            }
        }
    }

    private fun parseArray(depth: Int): JsonValue.Arr {
        val childDepth = nextDepth(depth)
        pos++ // consume '['
        skipWhitespace()
        val elements = ArrayList<JsonValue>()
        if (pos < endExclusive && currentByte() == ']'.code) {
            pos++
            return JsonValue.Arr(elements)
        }
        while (true) {
            elements.add(parseValue(childDepth))
            skipWhitespace()
            if (pos >= endExclusive) reject(JsonReject.UNEXPECTED_END_OF_INPUT)
            when (currentByte()) {
                ','.code -> pos++
                ']'.code -> {
                    pos++
                    return JsonValue.Arr(elements)
                }
                else -> reject(JsonReject.EXPECTED_COMMA_OR_CLOSE)
            }
        }
    }

    private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
        for (offset in literal.indices) {
            if (pos >= endExclusive || currentByte() != literal[offset].code) reject(JsonReject.INVALID_LITERAL)
            pos++
        }
        return value
    }

    private fun parseNumber(): JsonValue {
        val start = pos
        if (currentByte() == '-'.code) pos++
        if (pos >= endExclusive || currentByte() !in '0'.code..'9'.code) reject(JsonReject.UNEXPECTED_CHARACTER)

        if (currentByte() == '0'.code) {
            pos++
            if (pos < endExclusive && currentByte() in '0'.code..'9'.code) reject(JsonReject.LEADING_ZERO)
        } else {
            while (pos < endExclusive && currentByte() in '0'.code..'9'.code) pos++
        }

        var hasFractionOrExponent = false

        if (pos < endExclusive && currentByte() == '.'.code) {
            hasFractionOrExponent = true
            pos++
            if (pos >= endExclusive || currentByte() !in '0'.code..'9'.code) reject(JsonReject.BAD_FRACTION)
            while (pos < endExclusive && currentByte() in '0'.code..'9'.code) pos++
        }

        if (pos < endExclusive && (currentByte() == 'e'.code || currentByte() == 'E'.code)) {
            hasFractionOrExponent = true
            pos++
            if (pos < endExclusive && (currentByte() == '+'.code || currentByte() == '-'.code)) pos++
            if (pos >= endExclusive || currentByte() !in '0'.code..'9'.code) reject(JsonReject.BAD_EXPONENT)
            while (pos < endExclusive && currentByte() in '0'.code..'9'.code) pos++
        }

        val text = asciiSpan(start, pos)

        if (!hasFractionOrExponent) {
            val longValue = text.toLongOrNull()
            if (longValue != null) return JsonValue.Integer(longValue)
        }

        val doubleValue = text.toDouble()
        if (!doubleValue.isFinite()) reject(JsonReject.NON_FINITE_NUMBER)
        return JsonValue.Real(doubleValue)
    }

    private fun asciiSpan(start: Int, endExclusiveSpan: Int): String {
        val builder = StringBuilder(endExclusiveSpan - start)
        for (index in start until endExclusiveSpan) builder.append((bytes[index].toInt() and 0xFF).toChar())
        return builder.toString()
    }

    private fun parseStringContent(): String {
        pos++ // consume opening quote
        val builder = StringBuilder()
        while (true) {
            if (pos >= endExclusive) reject(JsonReject.UNEXPECTED_END_OF_INPUT)
            val byte = currentByte()
            when {
                byte == QUOTE -> {
                    pos++
                    return builder.toString()
                }
                byte == BACKSLASH -> parseEscape(builder)
                byte < 0x20 -> reject(JsonReject.UNESCAPED_CONTROL_CHARACTER)
                byte < 0x80 -> {
                    builder.append(byte.toChar())
                    pos++
                }
                else -> {
                    when (val scalar = decodeUtf8Scalar(bytes, pos, endExclusive)) {
                        is Utf8Scalar.Invalid -> reject(scalar.reason)
                        is Utf8Scalar.Decoded -> {
                            appendCodePoint(builder, scalar.codePoint)
                            pos = scalar.nextIndex
                        }
                    }
                }
            }
        }
    }

    private fun appendCodePoint(builder: StringBuilder, codePoint: Int) {
        if (codePoint <= 0xFFFF) {
            builder.append(codePoint.toChar())
        } else {
            val adjusted = codePoint - 0x10000
            builder.append((0xD800 + (adjusted shr 10)).toChar())
            builder.append((0xDC00 + (adjusted and 0x3FF)).toChar())
        }
    }

    private fun parseEscape(builder: StringBuilder) {
        pos++ // consume backslash
        if (pos >= endExclusive) reject(JsonReject.UNEXPECTED_END_OF_INPUT)
        when (currentByte()) {
            QUOTE -> {
                builder.append('"')
                pos++
            }
            BACKSLASH -> {
                builder.append('\\')
                pos++
            }
            '/'.code -> {
                builder.append('/')
                pos++
            }
            'b'.code -> {
                builder.append('\b')
                pos++
            }
            'f'.code -> {
                builder.append('\u000C')
                pos++
            }
            'n'.code -> {
                builder.append('\n')
                pos++
            }
            'r'.code -> {
                builder.append('\r')
                pos++
            }
            't'.code -> {
                builder.append('\t')
                pos++
            }
            'u'.code -> parseUnicodeEscape(builder)
            else -> reject(JsonReject.BAD_ESCAPE)
        }
    }

    private fun parseUnicodeEscape(builder: StringBuilder) {
        pos++ // consume 'u'
        val unit = readHex4()
        when {
            unit in 0xD800..0xDBFF -> {
                if (pos + 1 < endExclusive && currentByte() == BACKSLASH && (bytes[pos + 1].toInt() and 0xFF) == 'u'.code) {
                    pos += 2
                    val low = readHex4()
                    if (low in 0xDC00..0xDFFF) {
                        builder.append(unit.toChar())
                        builder.append(low.toChar())
                    } else {
                        reject(JsonReject.LONE_HIGH_SURROGATE_ESCAPE)
                    }
                } else {
                    reject(JsonReject.LONE_HIGH_SURROGATE_ESCAPE)
                }
            }
            unit in 0xDC00..0xDFFF -> reject(JsonReject.LONE_LOW_SURROGATE_ESCAPE)
            else -> builder.append(unit.toChar())
        }
    }

    private fun readHex4(): Int {
        if (pos + 4 > endExclusive) reject(JsonReject.BAD_ESCAPE)
        var value = 0
        for (offset in 0 until 4) {
            val digit = hexDigit(bytes[pos + offset].toInt() and 0xFF) ?: reject(JsonReject.BAD_ESCAPE)
            value = (value shl 4) or digit
        }
        pos += 4
        return value
    }

    private fun hexDigit(byte: Int): Int? = when (byte) {
        in '0'.code..'9'.code -> byte - '0'.code
        in 'a'.code..'f'.code -> byte - 'a'.code + 10
        in 'A'.code..'F'.code -> byte - 'A'.code + 10
        else -> null
    }
}
