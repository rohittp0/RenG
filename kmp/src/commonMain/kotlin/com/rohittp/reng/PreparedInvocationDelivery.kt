package com.rohittp.reng

import com.rohittp.reng.internal.readahead.ReadAheadSnapshot
import com.rohittp.rentile.PreparedStyle

/**
 * A fully committed preparation result which remains owned by the session coordinator until its
 * `await` has delivered it. The before-images make a lost terminal result independently reversible.
 */
internal class PreparedInvocationDelivery(
    val frames: List<RenGPreparedFrame>,
    val initialHistory: PreparationHistory,
    val initialStyle: PreparedStyle?,
    val initialReadAheadSnapshot: ReadAheadSnapshot?,
)
