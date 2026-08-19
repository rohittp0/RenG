package com.rohittp.reng.internal.image

import com.rohittp.reng.internal.freshCopy

/**
 * One decoded image in RenG's single canonical form: tightly packed RGBA8, unpremultiplied, with no
 * row padding. Every read returns a fresh copy so a caller can never observe or corrupt the bytes this
 * holds, and mutating a returned array can never reach back into this instance.
 */
internal class DecodedImage(val width: Int, val height: Int, rgba: ByteArray) {
    private val bytes: ByteArray = rgba.freshCopy()

    val byteCount: Int get() = bytes.size

    fun rgbaSnapshot(): ByteArray = bytes.freshCopy()
}
