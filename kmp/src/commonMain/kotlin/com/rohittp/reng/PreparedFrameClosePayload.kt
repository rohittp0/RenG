package com.rohittp.reng

import com.rohittp.reng.internal.cache.Lease
import com.rohittp.reng.internal.preparation.PreparedFrameCpuBudget
import com.rohittp.reng.internal.preparation.RawTileBudget

internal class PreparedFrameClosePayload(
    val leases: List<Lease>,
    val rawTileReservation: RawTileBudget.Reservation?,
    val preparedFrameCpuReservation: PreparedFrameCpuBudget.Reservation,
)
