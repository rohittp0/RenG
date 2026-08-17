package com.rohittp.reng.internal.shader

internal class ShaderProfilePlan(
    val originalSource: String,
    val directiveStartUtf16: Int,
    val directiveEndExclusiveUtf16: Int,
) {
    internal fun gles300Source(): String = originalSource

    internal fun desktop330Source(): String =
        originalSource.substring(0, directiveStartUtf16) +
            DESKTOP_DIRECTIVE +
            originalSource.substring(directiveEndExclusiveUtf16)

    override fun toString(): String = "ShaderProfilePlan(<redacted>)"
}

internal fun scanShaderProfile(source: String): ShaderProfilePlan? {
    var lineStart = 0
    var insideBlockComment = false

    while (lineStart <= source.length) {
        val lineEnd = source.findPhysicalLineEnd(lineStart)
        if (!insideBlockComment && source.lineAsciiTrimEquals(lineStart, lineEnd, GLES_DIRECTIVE)) {
            if (source.hasInvalidPostDirectiveProfile(lineEnd)) return null
            return ShaderProfilePlan(
                originalSource = source,
                directiveStartUtf16 = lineStart,
                directiveEndExclusiveUtf16 = lineEnd,
            )
        }

        insideBlockComment = source.scanPrefixLine(lineStart, lineEnd, insideBlockComment) ?: return null
        if (lineEnd == source.length) return null
        lineStart = source.indexAfterLineTerminator(lineEnd)
    }

    return null
}

private fun String.findPhysicalLineEnd(start: Int): Int {
    var index = start
    while (index < length && this[index] != '\n' && this[index] != '\r') index += 1
    return index
}

private fun String.indexAfterLineTerminator(terminatorStart: Int): Int =
    if (this[terminatorStart] == '\r' && terminatorStart + 1 < length && this[terminatorStart + 1] == '\n') {
        terminatorStart + 2
    } else {
        terminatorStart + 1
    }

private fun String.lineAsciiTrimEquals(start: Int, endExclusive: Int, expected: String): Boolean {
    var trimmedStart = start
    while (trimmedStart < endExclusive && this[trimmedStart].isAsciiProfileWhitespace()) trimmedStart += 1

    var trimmedEnd = endExclusive
    while (trimmedEnd > trimmedStart && this[trimmedEnd - 1].isAsciiProfileWhitespace()) trimmedEnd -= 1

    if (trimmedEnd - trimmedStart != expected.length) return false
    for (offset in expected.indices) {
        if (this[trimmedStart + offset] != expected[offset]) return false
    }
    return true
}

private fun String.scanPrefixLine(
    start: Int,
    endExclusive: Int,
    startsInsideBlockComment: Boolean,
): Boolean? {
    var index = start
    var insideBlockComment = startsInsideBlockComment

    while (index < endExclusive) {
        if (insideBlockComment) {
            if (startsWithAt(index, BLOCK_COMMENT_END)) {
                insideBlockComment = false
                index += BLOCK_COMMENT_END.length
            } else {
                index += 1
            }
        } else {
            when {
                this[index].isAsciiProfileWhitespace() -> index += 1
                startsWithAt(index, LINE_COMMENT_START) -> return false
                startsWithAt(index, BLOCK_COMMENT_START) -> {
                    insideBlockComment = true
                    index += BLOCK_COMMENT_START.length
                }

                else -> return null
            }
        }
    }

    return insideBlockComment
}

private fun String.hasInvalidPostDirectiveProfile(start: Int): Boolean {
    var index = start
    var insideBlockComment = false
    var insideLineComment = false
    var linePrefixOnly = true

    while (index < length) {
        if (insideLineComment) {
            if (this[index].isPhysicalLineTerminatorCodeUnit()) {
                insideLineComment = false
                linePrefixOnly = true
            }
            index += 1
        } else if (insideBlockComment) {
            if (startsWithAt(index, BLOCK_COMMENT_END)) {
                insideBlockComment = false
                index += BLOCK_COMMENT_END.length
            } else {
                if (this[index].isPhysicalLineTerminatorCodeUnit()) linePrefixOnly = true
                index += 1
            }
        } else {
            when {
                this[index].isPhysicalLineTerminatorCodeUnit() -> {
                    linePrefixOnly = true
                    index += 1
                }

                this[index].isAsciiProfileWhitespace() -> index += 1
                startsWithAt(index, LINE_COMMENT_START) -> {
                    insideLineComment = true
                    index += LINE_COMMENT_START.length
                }

                startsWithAt(index, BLOCK_COMMENT_START) -> {
                    insideBlockComment = true
                    index += BLOCK_COMMENT_START.length
                }

                linePrefixOnly && this[index] == '#' && hasVersionKeywordAfterHash(index) -> return true
                else -> {
                    linePrefixOnly = false
                    index += 1
                }
            }
        }
    }

    return insideBlockComment
}

private fun String.hasVersionKeywordAfterHash(hashIndex: Int): Boolean {
    var keywordStart = hashIndex + 1
    while (keywordStart < length && this[keywordStart].isAsciiProfileWhitespace()) keywordStart += 1
    if (!startsWithAt(keywordStart, VERSION_KEYWORD)) return false

    val keywordEnd = keywordStart + VERSION_KEYWORD.length
    return keywordEnd == length || !this[keywordEnd].isAsciiIdentifierContinuation()
}

private fun String.startsWithAt(start: Int, expected: String): Boolean {
    if (start < 0 || start + expected.length > length) return false
    for (offset in expected.indices) {
        if (this[start + offset] != expected[offset]) return false
    }
    return true
}

private fun Char.isAsciiProfileWhitespace(): Boolean = this == ' ' || this == '\t'

private fun Char.isPhysicalLineTerminatorCodeUnit(): Boolean = this == '\r' || this == '\n'

private fun Char.isAsciiIdentifierContinuation(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_'

private const val GLES_DIRECTIVE: String = "#version 300 es"
private const val DESKTOP_DIRECTIVE: String = "#version 330 core"
private const val VERSION_KEYWORD: String = "version"
private const val LINE_COMMENT_START: String = "//"
private const val BLOCK_COMMENT_START: String = "/*"
private const val BLOCK_COMMENT_END: String = "*/"
