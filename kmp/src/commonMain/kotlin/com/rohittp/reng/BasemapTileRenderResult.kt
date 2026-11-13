package com.rohittp.reng

/**
 * One externally-rendered basemap tile. [rgbaBytes] are tightly-packed premultiplied RGBA8 and
 * [contentKey] is Rentile's eventual content identity for those exact pixels.
 */
public class BasemapTileRenderResult(
    public val rgbaBytes: ByteArray,
    public val contentKey: String,
)
