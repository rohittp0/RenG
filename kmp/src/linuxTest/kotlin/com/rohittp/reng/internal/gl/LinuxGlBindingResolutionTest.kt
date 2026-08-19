package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxGlBindingResolutionTest {
    @Test fun everyRosterNameResolvesAgainstTheRealDispatchLibrary() {
        when (val result = openPlatformGlBinding()) {
            is GlBindingResult.Bound -> assertTrue(result.binding.getError() >= 0)
            is GlBindingResult.Unsupported -> throw AssertionError(
                "libEGL.so.1 must be installed for the GL conformance gate",
            )
        }
    }

    @Test fun anUnresolvableLibraryIsATypedRedactedSetupFailure() {
        val result = openLinuxGlBinding(libraryName = "libRenGDefinitelyMissing.so.99")
        val unsupported = result as GlBindingResult.Unsupported
        assertEquals(RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT, unsupported.failure.code)
        assertEquals(PipelineStage.CONTEXT_ADOPTION, unsupported.failure.stage)
        assertEquals(null, unsupported.failure.diagnostic)
        assertTrue("libRenGDefinitelyMissing" !in unsupported.failure.toString())
    }
}
