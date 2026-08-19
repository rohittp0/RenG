package com.rohittp.reng.internal.gl

import com.rohittp.reng.FramebufferName
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.lifecycle.DeferredDeletion
import com.rohittp.reng.internal.lifecycle.DeletionId
import com.rohittp.reng.internal.lifecycle.GpuLedger
import com.rohittp.reng.internal.lifecycle.PreparedFrameFact
import com.rohittp.reng.internal.lifecycle.RenderTargetFact
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOperation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOutcome
import com.rohittp.reng.internal.lifecycle.RendererLifecycleSnapshot
import com.rohittp.reng.internal.lifecycle.RendererOwnerState
import com.rohittp.reng.internal.shader.ShaderProfilePlan
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VERTEX_SOURCE: String = "#version 300 es\nvoid main() { gl_Position = vec4(0.0); }\n"
private const val FRAGMENT_SOURCE: String =
    "#version 300 es\nprecision highp float;\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n"

class GlLifecycleDriverTest {
    private val surfaceKey = ResourceKey(ResourceKind.OFFSCREEN_SURFACE, "c".repeat(64), null)
    private val adopted = RenderContextIdentity(0x4000L)
    private val deriver = ResourceKeyDeriver()

    @Test fun freeingWithoutACurrentContextFailsWithoutDeletingAnything() {
        val world = driverWorld(currentContext = null, liveHandles = true)
        val outcome = world.driver.run(
            RendererLifecycleOperation.FreeResources(ResourceSelector.All),
        ) { null }
        val failed = outcome as RendererLifecycleOutcome.Failed
        assertEquals(RenGErrorCode.NO_CURRENT_RENDER_CONTEXT, failed.failure.code)
        assertTrue(world.binding.log.none { it.startsWith("delete") })
        assertTrue(world.registry.hasLiveGpuObjects())
    }

    @Test fun freeingUnderADifferentContextFailsWithoutChangingState() {
        val world = driverWorld(currentContext = RenderContextIdentity(0x9999L), liveHandles = true)
        val before = world.driver.snapshot
        val outcome = world.driver.run(
            RendererLifecycleOperation.FreeResources(ResourceSelector.All),
        ) { null }
        assertEquals(
            RenGErrorCode.DIFFERENT_CURRENT_RENDER_CONTEXT,
            (outcome as RendererLifecycleOutcome.Failed).failure.code,
        )
        assertEquals(before, world.driver.snapshot)
        assertTrue(world.binding.log.none { it.startsWith("delete") })
    }

    @Test fun deferredDeletionsDrainUnderTheExactContextBeforeTheOperationRuns() {
        val world = driverWorld(currentContext = adopted, liveHandles = true)
        world.registry.defer(surfaceKey, DeletionId(1L))
        world.setLedger(deferred = listOf(DeletionId(1L) to surfaceKey))
        var executed = false
        val outcome = world.driver.run(
            RendererLifecycleOperation.FreeResources(ResourceSelector.All),
        ) {
            executed = true
            assertTrue(world.binding.log.any { it.startsWith("deleteTextures") })
            null
        }
        assertEquals(RendererLifecycleOutcome.Succeeded, outcome)
        assertTrue(executed)
        assertTrue(world.driver.snapshot.gpuLedger.deferredDeletions.isEmpty())
    }

    @Test fun aFailedDeferredDeletionStopsBeforeTheOperation() {
        val world = driverWorld(currentContext = adopted, liveHandles = true)
        world.registry.defer(surfaceKey, DeletionId(1L))
        world.setLedger(deferred = listOf(DeletionId(1L) to surfaceKey))
        world.binding.errorQueue = mutableListOf(GL_NO_ERROR, GL_OUT_OF_MEMORY, GL_NO_ERROR)
        var executed = false
        val outcome = world.driver.run(
            RendererLifecycleOperation.FreeResources(ResourceSelector.All),
        ) { executed = true; null }
        assertEquals(
            RenGErrorCode.GPU_OPERATION_FAILED,
            (outcome as RendererLifecycleOutcome.Failed).failure.code,
        )
        assertTrue(!executed)
    }

    @Test fun gpuObjectLossForgetsHandlesAndProgramsWithoutOneGlCall() {
        val world = driverWorld(currentContext = null, liveHandles = true)
        world.programs.getOrCompile(
            world.binding, ShaderDialect.GLES, geometryKey(), vertexPlan(), fragmentPlan(),
        )
        world.binding.log.clear()
        val outcome = world.driver.run(RendererLifecycleOperation.NotifyGpuObjectsGone) { null }
        assertEquals(RendererLifecycleOutcome.Succeeded, outcome)
        assertEquals(RendererOwnerState.AWAITING_CONTEXT_ADOPTION, world.driver.snapshot.ownerState)
        assertTrue(world.binding.log.isEmpty())
        assertTrue(!world.registry.hasLiveGpuObjects())
        assertTrue(world.programs.keys().isEmpty())
        assertNull(world.driver.adoptedContext)
    }

    @Test fun adoptionAcceptsASupportedContextAndRejectsAnUnsupportedOne() {
        val world = driverWorld(currentContext = RenderContextIdentity(0x7000L), lost = true)
        val outcome = world.driver.run(RendererLifecycleOperation.AdoptCurrentRenderContext) { null }
        assertEquals(RendererLifecycleOutcome.Succeeded, outcome)
        assertEquals(RendererOwnerState.LIVE, world.driver.snapshot.ownerState)
        assertEquals(RenderContextIdentity(0x7000L), world.driver.adoptedContext)
        assertEquals(ShaderDialect.GLES, world.driver.profile?.dialect)

        val legacy = driverWorld(currentContext = RenderContextIdentity(0x7000L), lost = true)
        legacy.binding.strings[GL_VERSION] = "2.1 INTEL-16.4.5"
        legacy.binding.strings[GL_SHADING_LANGUAGE_VERSION] = "1.20"
        val rejected = legacy.driver.run(RendererLifecycleOperation.AdoptCurrentRenderContext) { null }
        assertEquals(
            RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT,
            (rejected as RendererLifecycleOutcome.Failed).failure.code,
        )
        assertEquals(RendererOwnerState.AWAITING_CONTEXT_ADOPTION, legacy.driver.snapshot.ownerState)
    }

    // This is the litmus test for the `GlProgramCache.forgetAll()` wiring inside adoption: it
    // proves a program compiled before an adoption is genuinely recompiled after it, rather than
    // merely asserting the cache is empty (which would pass even for the wrong reason if adoption
    // happened to leave a differently-keyed entry behind). RecordingGlBinding.createProgram hands
    // out a fresh, strictly increasing name on every call and never deduplicates by key, so a
    // second getOrCompile for the identical key can only return the same program name as the first
    // if the cache still held the old entry. Deleting `programs.forgetAll()` from
    // GlLifecycleDriver's adoption path makes this test fail: `afterProgram` becomes equal to
    // `beforeProgram` and no new `createProgram` call is logged, because getOrCompile short-circuits
    // on the stale cache entry instead of recompiling.
    @Test fun adoptionForgetsProgramsCompiledUnderThePreviousContextSoTheyAreGenuinelyRecompiled() {
        val world = driverWorld(currentContext = adopted, lost = true)
        val key = geometryKey()
        val before = world.programs.getOrCompile(
            world.binding, ShaderDialect.GLES, key, vertexPlan(), fragmentPlan(),
        ) as GlProgramResult.Linked
        world.binding.log.clear()

        val outcome = world.driver.run(RendererLifecycleOperation.AdoptCurrentRenderContext) { null }
        assertEquals(RendererLifecycleOutcome.Succeeded, outcome)

        val after = world.programs.getOrCompile(
            world.binding, ShaderDialect.GLES, key, vertexPlan(), fragmentPlan(),
        ) as GlProgramResult.Linked
        assertTrue(world.binding.log.any { it.startsWith("createProgram") })
        assertTrue(before.program != after.program)
    }

    @Test fun mintingValidatesTheFramebufferAndRestoresThePreviousBinding() {
        val world = driverWorld(currentContext = adopted)
        world.binding.integers[GL_DRAW_FRAMEBUFFER_BINDING] = intArrayOf(12)
        val outcome = world.driver.run(
            RendererLifecycleOperation.MintRenderTarget(FramebufferName(5u)),
        ) { null }
        assertEquals(RendererLifecycleOutcome.Succeeded, outcome)
        assertEquals("bindFramebuffer(0x8CA9,12)", world.binding.log.last { it.startsWith("bindFramebuffer") })
    }

    @Test fun anIncompleteFramebufferIsAnInvalidRenderTarget() {
        val world = driverWorld(currentContext = adopted)
        world.binding.framebufferStatus = 0x8CD6
        val outcome = world.driver.run(
            RendererLifecycleOperation.MintRenderTarget(FramebufferName(5u)),
        ) { null }
        assertEquals(
            RenGErrorCode.INVALID_RENDER_TARGET,
            (outcome as RendererLifecycleOutcome.Failed).failure.code,
        )
    }

    @Test fun drawingRunsTheExecutorOnlyAfterProvenanceContextAndFramebufferChecks() {
        val world = driverWorld(currentContext = adopted)
        val order = mutableListOf<String>()
        val outcome = world.driver.run(
            RendererLifecycleOperation.Draw(
                frame = PreparedFrameFact.OwnedOpen,
                target = RenderTargetFact.OwnedCurrent(FramebufferName(5u)),
            ),
        ) { order += "execute"; null }
        assertEquals(RendererLifecycleOutcome.Succeeded, outcome)
        assertEquals(listOf("execute"), order)
        assertTrue(
            world.binding.log.indexOfFirst { it.startsWith("checkFramebufferStatus") } >= 0,
        )
    }

    @Test fun closingAfterLossIsContextFreeAndTerminal() {
        val world = driverWorld(currentContext = null, lost = true)
        val outcome = world.driver.run(RendererLifecycleOperation.CloseRenderer) { null }
        assertEquals(RendererLifecycleOutcome.Succeeded, outcome)
        assertEquals(RendererOwnerState.CLOSED, world.driver.snapshot.ownerState)
        assertTrue(world.binding.log.none { it.startsWith("delete") })
    }

    private fun geometryKey(): ResourceKey =
        deriver.geometryProgram(ShaderPair(VERTEX_SOURCE, FRAGMENT_SOURCE)).key

    private fun vertexPlan(): ShaderProfilePlan = requireNotNull(scanShaderProfile(VERTEX_SOURCE))

    private fun fragmentPlan(): ShaderProfilePlan = requireNotNull(scanShaderProfile(FRAGMENT_SOURCE))

    private class DriverWorld(
        val binding: RecordingGlBinding,
        val registry: GlObjectRegistry,
        val programs: GlProgramCache,
        private val probe: RenderContextProbe,
        var driver: GlLifecycleDriver,
    ) {
        /**
         * Installs deferred-deletion entries into both the registry's queue (already moved there by
         * a prior [GlObjectRegistry.defer] call from the test) and the snapshot's [GpuLedger], then
         * rebuilds the driver against that snapshot. [GlLifecycleDriver.snapshot] has a private
         * setter by design (only the driver's own action loop may advance it), so a test that needs
         * to seed a ledger state the state machine did not itself produce must construct a fresh
         * driver rather than reach into the existing one.
         */
        fun setLedger(deferred: List<Pair<DeletionId, ResourceKey?>>) {
            val nextSnapshot = driver.snapshot.copy(
                gpuLedger = GpuLedger(
                    hasLiveGpuObjects = registry.hasLiveGpuObjects(),
                    deferredDeletions = deferred.map { (id, key) -> DeferredDeletion(id, key) },
                ),
            )
            driver = GlLifecycleDriver(
                binding = binding,
                probe = probe,
                registry = registry,
                programs = programs,
                initialSnapshot = nextSnapshot,
                initialContext = driver.adoptedContext,
                initialProfile = driver.profile,
            )
        }
    }

    private fun driverWorld(
        currentContext: RenderContextIdentity?,
        liveHandles: Boolean = false,
        lost: Boolean = false,
    ): DriverWorld {
        val binding = RecordingGlBinding().apply {
            strings[GL_SHADING_LANGUAGE_VERSION] = "OpenGL ES GLSL ES 3.20"
            strings[GL_VERSION] = "OpenGL ES 3.2 Mesa 25.2.8-0ubuntu0.24.04.2"
            strings[GL_RENDERER] = "llvmpipe (LLVM 20.1.2, 256 bits)"
            strings[GL_VENDOR] = "Mesa"
            indexedStrings += listOf("GL_EXT_sRGB_write_control", "GL_OES_texture_float")
            integers[GL_NUM_EXTENSIONS] = intArrayOf(2)
            integers[GL_MAX_TEXTURE_SIZE] = intArrayOf(16384)
            integers[GL_MAX_COLOR_ATTACHMENTS] = intArrayOf(8)
            integers[GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS] = intArrayOf(192)
        }
        val registry = GlObjectRegistry()
        if (liveHandles) {
            registry.register(surfaceKey, listOf(GlObjectHandle(GlObjectType.TEXTURE, 4)))
        }
        val programs = GlProgramCache()
        val probe = RenderContextProbe { currentContext }
        val snapshot = RendererLifecycleSnapshot(
            ownerState = if (lost) RendererOwnerState.AWAITING_CONTEXT_ADOPTION else RendererOwnerState.LIVE,
            contextGeneration = 0L,
            preparationActive = false,
            gpuLedger = GpuLedger(hasLiveGpuObjects = liveHandles, deferredDeletions = emptyList()),
        )
        val driver = GlLifecycleDriver(
            binding = binding,
            probe = probe,
            registry = registry,
            programs = programs,
            initialSnapshot = snapshot,
            initialContext = if (lost) null else adopted,
            initialProfile = null,
        )
        return DriverWorld(binding, registry, programs, probe, driver)
    }
}
