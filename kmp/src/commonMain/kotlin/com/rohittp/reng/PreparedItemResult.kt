package com.rohittp.reng

import com.rohittp.reng.internal.basemap.BasemapStyleManifest
import com.rohittp.rentile.PreparedStyle

internal data class PreparedItemResult(
    val frame: RenGPreparedFrame,
    val nextHistory: PreparationHistory,
    val preparedStyle: PreparedStyle?,
    val styleManifest: BasemapStyleManifest?,
)
