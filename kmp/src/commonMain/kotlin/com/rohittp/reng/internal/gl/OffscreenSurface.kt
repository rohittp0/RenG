package com.rohittp.reng.internal.gl

internal enum class OffscreenColourFormat(
    internal val wireValue: Int,
    internal val glInternalFormat: Int,
) {
    RGBA8(1, GL_RGBA8),
}

internal enum class OffscreenDepthFormat(
    internal val wireValue: Int,
    internal val glInternalFormat: Int,
) {
    DEPTH_COMPONENT24(1, GL_DEPTH_COMPONENT24),
}

internal data class OffscreenSurfaceDescriptor(
    val widthPixels: Int,
    val heightPixels: Int,
    val colourFormat: OffscreenColourFormat,
    val depthFormat: OffscreenDepthFormat,
) {
    init {
        require(widthPixels > 0 && heightPixels > 0) { "an offscreen surface has positive dimensions" }
    }
}
