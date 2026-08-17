package com.rohittp.reng.internal.planning

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SpatialPlanningResultTest {
    @Test
    fun successRetainsItsTypedValue() {
        val outcome: SpatialOutcome<Int> = SpatialOutcome.Success(42)

        assertEquals(42, assertIs<SpatialOutcome.Success<Int>>(outcome).value)
        assertEquals(SpatialOutcome.Success(42), outcome)
    }

    @Test
    fun failureRetainsTheExistingSanitizedDescriptor() {
        val descriptor = FailureDescriptor(
            code = RenGErrorCode.INVALID_VALUE,
            stage = PipelineStage.FRAME_PLANNING,
            diagnostic = failureContextDiagnostic(
                stage = PipelineStage.FRAME_PLANNING,
                fieldName = DiagnosticField.MAP_POSITION_LATITUDE,
            ),
        )
        val outcome: SpatialOutcome<Nothing> = SpatialOutcome.Failure(descriptor)

        assertEquals(descriptor, assertIs<SpatialOutcome.Failure>(outcome).failure)
    }
}
