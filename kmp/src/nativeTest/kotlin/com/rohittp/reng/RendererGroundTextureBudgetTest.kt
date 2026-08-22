package com.rohittp.reng

import com.rohittp.reng.internal.gl.RecordingGlBinding
import com.rohittp.reng.internal.gl.RenderContextIdentity
import com.rohittp.reng.internal.gl.RenderContextProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Where the frame's ground textures live, and what makes them go away.
 *
 * Native-only for the same reason `RendererBasemapTileTest` is: every case here rasterizes a real
 * basemap tile through Rentile's Skia, which this project's `androidHostTest` runtime cannot load. The
 * GL side runs against [RecordingGlBinding] rather than a real context, deliberately — what is being
 * asserted is *how many times RenG uploads and deletes*, which is a call-count property a fake states
 * far more precisely than a driver does, and which no pixel readback can see at all.
 *
 * The frame's four canonical tiles are 512x512 RGBA8, so one megabyte each and four megabytes a frame.
 */
class RendererGroundTextureBudgetTest {

    /**
     * The whole reason ground textures are budget-tracked rather than uploaded per frame. A second
     * frame over the same camera and the same style names the same [ResourceKey]s, so its tiles are
     * still registered and are neither decoded nor uploaded again — panning back over ground already
     * seen must cost nothing, and Cycle F-1 spent a whole task establishing that a per-frame
     * `genTextures` is unacceptable.
     */
    @Test
    fun aSecondDrawOfTheSameGroundUploadsNoTextureAgain() = runTest {
        val binding = styleGlBinding()
        val renderer = groundRenderer(binding)
        val target = renderer.mintRenderTarget(FramebufferName(0u))

        val first = renderer.prepare(basemapPlan(frameIndex = 0L))
        val second = renderer.prepare(basemapPlan(frameIndex = 1L))

        binding.log.clear()
        renderer.draw(first, target)
        assertEquals(
            GROUND_TILES_PER_FRAME,
            binding.log.count { it == "genTextures(1)" },
            "the first draw uploads one texture per canonical tile",
        )

        binding.log.clear()
        renderer.draw(second, target)
        assertEquals(
            0,
            binding.log.count { it == "genTextures(1)" },
            "the second draw over identical ground must upload nothing at all",
        )
        assertEquals(
            0,
            binding.log.count { it.startsWith("deleteTextures") },
            "and must not evict what it is still using",
        )
    }

    /**
     * The other half of the same contract: ground textures are **inside**
     * [ResourceLimits.maximumResidentGpuTextureBytes], not exempt from it. With a budget smaller than
     * one tile, every tile is evicted as soon as the draw that leased it releases its lease, and the
     * next frame pays for the upload again.
     *
     * This is also the guard on lease release itself. A draw that leaked its leases would leave every
     * tile permanently unevictable — `GlObjectRegistry.evictOverBudget` iterates only unleased keys —
     * so nothing would ever be deleted here no matter how small the budget.
     */
    @Test
    fun groundTexturesAreEvictedWhenTheyExceedTheResidentGpuByteBudget() = runTest {
        val binding = styleGlBinding()
        val renderer = groundRenderer(
            binding,
            limits = ResourceLimits(maximumResidentGpuTextureBytes = 1L),
        )
        val target = renderer.mintRenderTarget(FramebufferName(0u))

        val first = renderer.prepare(basemapPlan(frameIndex = 0L))
        val second = renderer.prepare(basemapPlan(frameIndex = 1L))

        binding.log.clear()
        renderer.draw(first, target)
        assertEquals(GROUND_TILES_PER_FRAME, binding.log.count { it == "genTextures(1)" })
        assertTrue(
            binding.log.count { it.startsWith("deleteTextures") } >= GROUND_TILES_PER_FRAME,
            "a budget below one tile must evict every tile once its draw releases the lease",
        )

        binding.log.clear()
        renderer.draw(second, target)
        assertEquals(
            GROUND_TILES_PER_FRAME,
            binding.log.count { it == "genTextures(1)" },
            "an evicted tile is uploaded again on the next frame that needs it",
        )
    }

    /**
     * Eviction may never break the frame that is drawing. Every tile a draw needs is leased for the
     * whole draw, so even a budget of one byte cannot delete a texture between the upload of tile one
     * and the draw call of tile four: the four deletes all land after the last `drawArrays`.
     */
    @Test
    fun noGroundTextureIsEvictedWhileTheFrameThatNeedsItIsStillDrawing() = runTest {
        val binding = styleGlBinding()
        val renderer = groundRenderer(
            binding,
            limits = ResourceLimits(maximumResidentGpuTextureBytes = 1L),
        )
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        val frame = renderer.prepare(basemapPlan(frameIndex = 0L))

        binding.log.clear()
        renderer.draw(frame, target)

        val lastDraw = binding.log.indexOfLast { it.startsWith("drawArrays") }
        val firstDelete = binding.log.indexOfFirst { it.startsWith("deleteTextures") }
        assertTrue(lastDraw >= 0, "the frame must actually draw")
        assertTrue(firstDelete > lastDraw, "no eviction may happen before the frame has finished drawing")
    }

    /**
     * The one way a rendered tile's decode can legitimately fail: a consumer whose
     * [ResourceLimits.maximumDecodedImageBytes] is below one tile has configured a renderer that
     * cannot draw a basemap at all. It must say so as a typed failure naming the tile, at the stage the
     * decode actually happens, rather than throwing something untyped out of a GL draw call.
     */
    @Test
    fun aTileTooLargeToDecodeFailsAsATypedDecodeFailureNamingTheTile() = runTest {
        val binding = styleGlBinding()
        val renderer = groundRenderer(binding, limits = ResourceLimits(maximumDecodedImageBytes = 1L))
        val target = renderer.mintRenderTarget(FramebufferName(0u))
        val frame = renderer.prepare(basemapPlan(frameIndex = 0L))

        val failure = assertFailsWith<RenGException> { renderer.draw(frame, target) }

        assertEquals(RenGErrorCode.RESOURCE_DECODE_FAILED, failure.code)
        assertEquals(PipelineStage.DRAW, failure.stage)
        val diagnostic = failure.diagnostics.single()
        assertEquals(ResourceKind.BASEMAP_TILE, diagnostic.resourceKey?.kind)
    }

    private fun groundRenderer(
        binding: RecordingGlBinding,
        limits: ResourceLimits = ResourceLimits(),
    ): Renderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(64, 64),
            transport = TileTransport(),
            store = RecordingStyleStore(),
            basemapStyle = ResourceLocator(STYLE_URL),
            resourceLimits = limits,
        ),
        binding,
        RenderContextProbe { RenderContextIdentity(1L) },
    )

    private companion object {
        /** `x in {1, 2}, y in {10, 11}` at LOD 4 — see `styleCamera`'s own KDoc for why that camera. */
        const val GROUND_TILES_PER_FRAME: Int = 4
    }
}
