package com.rohittp.reng.internal.firewall

/** Optional platform renderer for one already-prepared raw basemap tile. */
internal fun interface BasemapTileRenderer {
    suspend fun render(
        styleLocator: String,
        zoom: Int,
        column: Int,
        row: Int,
        outputSizePx: Int,
    ): ByteArray
}

/** Process-local because a Wasm worker has its own module instance and therefore its own renderer. */
internal var installedBasemapTileRenderer: BasemapTileRenderer? = null
