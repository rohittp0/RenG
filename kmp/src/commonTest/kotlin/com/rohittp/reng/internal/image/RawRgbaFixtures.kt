package com.rohittp.reng.internal.image

/** A raw RGBA container header (ADR 0092), little-endian, with every field overridable. */
internal fun rawRgbaHeader(
    width: Long,
    height: Long,
    flags: Int = 0,
    version: Int = 1,
    reserved: Int = 0,
    magic: String = "RRGB",
): ByteArray {
    val header = ByteArray(16)
    magic.encodeToByteArray().copyInto(header)
    header[4] = version.toByte()
    header[5] = flags.toByte()
    header[6] = reserved.toByte()
    header[7] = reserved.toByte()
    for (i in 0 until 4) {
        header[8 + i] = (width shr (8 * i)).toByte()
        header[12 + i] = (height shr (8 * i)).toByte()
    }
    return header
}

/** A whole raw RGBA container holding [rgba] as `width x height` texels. */
internal fun rawRgbaContainer(width: Int, height: Int, rgba: ByteArray, premultiplied: Boolean = false): ByteArray {
    require(rgba.size == width * height * 4)
    return rawRgbaHeader(width.toLong(), height.toLong(), flags = if (premultiplied) 1 else 0) + rgba
}
