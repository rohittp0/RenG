package com.rohittp.reng

import com.rohittp.reng.internal.planning.PlannedFrameCore

internal data class PlannedPreparationItem(
    val plan: FramePlan,
    val planned: PlannedFrameCore,
)
