package com.rohittp.reng.internal.planning

import kotlin.math.ceil

internal data class LodObservation(val selectedLod: Int)

internal fun observeMercatorLod(
    zoom: Double,
    previousSelectedLod: Int?,
): LodObservation {
    require(zoom.isFinite() && zoom in MINIMUM_LOD.toDouble()..MAXIMUM_LOD.toDouble()) {
        "zoom must be within the Mercator LOD range"
    }
    require(previousSelectedLod == null || previousSelectedLod in MINIMUM_LOD..MAXIMUM_LOD) {
        "previousSelectedLod must be within the Mercator LOD range"
    }

    if (previousSelectedLod == null) {
        val selected = ceil(zoom - 0.5).toInt().coerceIn(MINIMUM_LOD, MAXIMUM_LOD)
        return LodObservation(selected)
    }

    var selected = previousSelectedLod
    while (selected < MAXIMUM_LOD && zoom >= selected.toDouble() + UPPER_HYSTERESIS_OFFSET) {
        selected += 1
    }
    while (selected > MINIMUM_LOD && zoom < selected.toDouble() - LOWER_HYSTERESIS_OFFSET) {
        selected -= 1
    }
    return LodObservation(selected)
}

private const val MINIMUM_LOD: Int = 0
private const val MAXIMUM_LOD: Int = 22
private const val UPPER_HYSTERESIS_OFFSET: Double = 0.75
private const val LOWER_HYSTERESIS_OFFSET: Double = 0.75
