package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.internal.lifecycle.DeletionId
import com.rohittp.reng.internal.lifecycle.ExactContextFact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlObjectRegistryTest {
    private val surfaceKey = ResourceKey(ResourceKind.OFFSCREEN_SURFACE, "a".repeat(64), null)
    private val pipelineKey = ResourceKey(ResourceKind.INTERNAL_PIPELINE, "b".repeat(64), null)

    @Test fun liveHandlesAreReportedUntilTheyAreDeferred() {
        val registry = GlObjectRegistry()
        assertFalse(registry.hasLiveGpuObjects())
        registry.register(surfaceKey, listOf(GlObjectHandle(GlObjectType.TEXTURE, 4)))
        assertTrue(registry.hasLiveGpuObjects())
        val deferred = registry.defer(surfaceKey, DeletionId(1L))
        assertEquals(surfaceKey, deferred?.resourceKey)
        assertFalse(registry.hasLiveGpuObjects())
        assertEquals(listOf(GlObjectHandle(GlObjectType.TEXTURE, 4)), registry.takeQueued(DeletionId(1L)))
    }

    @Test fun gpuObjectLossForgetsEveryHandleAndIssuesNoDelete() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry()
        registry.register(surfaceKey, listOf(GlObjectHandle(GlObjectType.FRAMEBUFFER, 3)))
        registry.register(pipelineKey, listOf(GlObjectHandle(GlObjectType.PROGRAM, 9)))
        registry.defer(pipelineKey, DeletionId(2L))
        registry.forgetEverything()
        assertFalse(registry.hasLiveGpuObjects())
        assertTrue(registry.liveKeys().isEmpty())
        assertTrue(registry.takeQueued(DeletionId(2L)).isEmpty())
        assertTrue(binding.log.isEmpty())
    }

    @Test fun theDeleterGroupsByTypeAndSkipsEmptyGroups() {
        val binding = RecordingGlBinding()
        deleteGlObjects(
            binding,
            listOf(
                GlObjectHandle(GlObjectType.TEXTURE, 1),
                GlObjectHandle(GlObjectType.TEXTURE, 2),
                GlObjectHandle(GlObjectType.PROGRAM, 5),
            ),
        )
        assertEquals(1, binding.log.count { it.startsWith("deleteTextures(2") })
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram(5") })
        assertTrue(binding.log.none { it.startsWith("deleteBuffers") })
        assertTrue(binding.log.none { it.startsWith("deleteRenderbuffers") })
    }

    @Test fun deletingNothingIssuesNothing() {
        val binding = RecordingGlBinding()
        deleteGlObjects(binding, emptyList())
        assertTrue(binding.log.isEmpty())
    }

    @Test fun theProbeDistinguishesExactMissingAndForeignContexts() {
        val adopted = RenderContextIdentity(0x1000L)
        assertEquals(
            ExactContextFact.EXACT,
            exactContextFact(adopted) { RenderContextIdentity(0x1000L) },
        )
        assertEquals(ExactContextFact.NONE, exactContextFact(adopted) { null })
        assertEquals(
            ExactContextFact.DIFFERENT,
            exactContextFact(adopted) { RenderContextIdentity(0x2000L) },
        )
    }
}
