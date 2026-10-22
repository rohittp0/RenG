package com.rohittp.reng

import com.rohittp.reng.internal.identity.EncodedFramePlan
import com.rohittp.reng.internal.label.LabelFadeState
import com.rohittp.reng.internal.planning.CanonicalBasemapTile

internal data class PreparationHistory(
    val frameIndex: Long?,
    val encodedPlan: EncodedFramePlan?,
    val selectedLod: Int?,
    val labelFade: LabelFadeState,
    val labelTiles: List<CanonicalBasemapTile>?,
)
