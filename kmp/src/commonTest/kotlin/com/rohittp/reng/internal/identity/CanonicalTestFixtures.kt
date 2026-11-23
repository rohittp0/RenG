package com.rohittp.reng.internal.identity

internal fun CanonicalBytes.fixtureLowercaseHex(): String = bytes.toFixtureLowercaseHex()

internal fun ByteArray.toFixtureLowercaseHex(): String = buildString(size * 2) {
    this@toFixtureLowercaseHex.forEach { byte ->
        append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
        append(HEX_DIGITS[byte.toInt() and 0x0f])
    }
}

private const val HEX_DIGITS: String = "0123456789abcdef"
