package com.rohittp.reng

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RenGExceptionCauseAndroidHostTest {
    @Test
    fun exceptionRejectsAnAttachedSecretAdapterCause() {
        val adapterSecret = "adapter-secret"
        val exception = RenGException(RenGErrorCode.RENDERER_CLOSED, PipelineStage.DRAW)

        assertFailsWith<IllegalStateException> {
            exception.initCause(IllegalStateException(adapterSecret))
        }

        assertNull(exception.cause)
        assertFalse(exception.message.orEmpty().contains(adapterSecret))
        assertFalse(exception.toString().contains(adapterSecret))
    }
}
