package com.rohittp.reng.internal.planning

import com.rohittp.reng.internal.failure.FailureDescriptor

internal sealed interface SpatialOutcome<out T> {
    data class Success<T>(val value: T) : SpatialOutcome<T>

    data class Failure(val failure: FailureDescriptor) : SpatialOutcome<Nothing>
}
