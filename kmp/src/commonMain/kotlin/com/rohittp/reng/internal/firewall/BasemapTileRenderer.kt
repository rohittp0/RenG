package com.rohittp.reng.internal.firewall

import com.rohittp.reng.BasemapTileRenderResult
import com.rohittp.reng.ResourceAccessMode

/** Optional platform renderer for one raw basemap tile which RenG has not acquired itself. */
internal fun interface BasemapTileRenderer {
    suspend fun render(
        styleLocator: String,
        zoom: Int,
        column: Int,
        row: Int,
        outputSizePx: Int,
        outputRequestKey: String,
        accessMode: ResourceAccessMode,
    ): BasemapTileRenderResult
}

/** Process-local because a Wasm worker has its own module instance and therefore its own renderer. */
internal var installedBasemapTileRenderer: BasemapTileRenderer? = null
