package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlErrorQueueTest {
    @Test fun theQueueIsDrainedCompletelyAndTheFirstFlagIsReturned() {
        val binding = RecordingGlBinding()
        binding.errorQueue = mutableListOf(GL_INVALID_ENUM, GL_INVALID_VALUE, GL_NO_ERROR)
        assertEquals(GL_INVALID_ENUM, GlErrorQueue.drainOnEntry(binding))
        assertEquals(GL_NO_ERROR, GlErrorQueue.drainOnEntry(binding))
    }

    @Test fun aCleanQueueReportsNoError() {
        assertEquals(GL_NO_ERROR, GlErrorQueue.drainOnEntry(RecordingGlBinding()))
    }

    @Test fun aDriverStuckInErrorCannotSpinTheDrainForever() {
        val binding = object : GlBinding by RecordingGlBinding() {
            override fun getError(): Int = GL_OUT_OF_MEMORY
        }
        assertEquals(GL_OUT_OF_MEMORY, GlErrorQueue.drainOnEntry(binding))
    }

    @Test fun rengOwnFailuresCarryARedactedGpuDiagnostic() {
        val failure = glOperationFailure(PipelineStage.DRAW, resourceKey = null)
        assertEquals(RenGErrorCode.GPU_OPERATION_FAILED, failure.code)
        assertEquals(PipelineStage.DRAW, failure.stage)
        val diagnostic = assertNotNull(failure.diagnostic)
        assertEquals(null, diagnostic.fieldName)
        assertEquals(null, diagnostic.resourceKey)
        assertTrue("0x" !in failure.toString())
    }
}
