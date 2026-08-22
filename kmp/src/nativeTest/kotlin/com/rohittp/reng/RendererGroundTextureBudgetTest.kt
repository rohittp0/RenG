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
     * Task E-J: a tile a draw **reuses** is as protected from that draw's own eviction as a tile the
     * draw uploads. Reuse is not a weaker kind of use.
     *
     * The pressure is real and is applied through the public surface only. The budget holds two of the
     * four tiles this camera needs, so the first draw evicts two of its own tiles on the way out and
     * leaves two resident. The second draw over identical ground therefore reuses those two and uploads
     * the other two — four tiles registered against a two-tile ceiling, which must cost two evictions.
     * The question this test asks is *which* two.
     *
     * With the reuse branch leasing what it reuses, the answer is: not the reused ones. They are
     * structurally unreachable to eviction for the whole draw, because `evictOverBudget` iterates only
     * unleased keys. With the reuse branch merely touching them — the shape this test was written
     * against — the two reused tiles sit unleased in the eviction order for the whole draw, are the
     * oldest candidates in it, and are exactly what the first release deletes: a draw evicting the very
     * textures it just sampled, kept safe only by the accident that the release sweep runs after the
     * last `drawArrays`.
     *
     * Every name here is derived from what the run actually did rather than assumed, so the test states
     * one belief about the fixture and no more: that two 512x512 tiles fit this budget and four do not.
     */
    @Test
    fun aReusedGroundTextureSurvivesThePressureThatEvictsTheTilesAroundIt() = runTest {
        val binding = styleGlBinding()
        val renderer = groundRenderer(
            binding,
            limits = ResourceLimits(maximumResidentGpuTextureBytes = 2 * ONE_TILE_BYTES),
        )
        val target = renderer.mintRenderTarget(FramebufferName(0u))

        val first = renderer.prepare(basemapPlan(frameIndex = 0L))
        val second = renderer.prepare(basemapPlan(frameIndex = 1L))

        binding.log.clear()
        binding.deletedNames.clear()
        renderer.draw(first, target)
        val firstDrawUploads = uploadedTextureNames(binding.log)
        assertEquals(GROUND_TILES_PER_FRAME, firstDrawUploads.size, "the first draw uploads every tile")
        val reusable = firstDrawUploads.filterNot { it in binding.deletedNames }
        assertEquals(
            2,
            reusable.size,
            "the fixture belief: a two-tile budget leaves exactly two of the four tiles resident",
        )

        binding.log.clear()
        binding.deletedNames.clear()
        renderer.draw(second, target)

        val boundTextures = binding.log.mapNotNull(::boundTextureName).toSet()
        assertTrue(
            reusable.all { it in boundTextures },
            "the second draw must actually reuse both resident tiles, or there is no reuse to protect",
        )
        val secondDrawUploads = uploadedTextureNames(binding.log)
        assertEquals(
            GROUND_TILES_PER_FRAME - reusable.size,
            secondDrawUploads.size,
            "and must upload only the tiles the first draw's eviction took",
        )
        assertTrue(
            binding.deletedNames.isNotEmpty(),
            "four tiles against a two-tile budget must evict something, or this test applies no pressure",
        )
        assertEquals(
            emptyList(),
            reusable.filter { it in binding.deletedNames },
            "a tile this draw reused is a tile this draw is using: it must not be what this draw evicts",
        )
        assertEquals(
            secondDrawUploads.toSet(),
            binding.deletedNames.toSet(),
            "the eviction falls on this draw's own uploads instead, which is the whole of the difference",
        )
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

    /**
     * The GL name every `genTextures(1)` in [log] produced, in call order, read back from the
     * `bindTexture` the uploader issues immediately afterwards — [RecordingGlBinding] hands out names
     * through an `IntArray` the log line cannot carry, and the bind is where the name it just took
     * becomes visible.
     */
    private fun uploadedTextureNames(log: List<String>): List<Int> =
        log.indices.filter { log[it] == "genTextures(1)" }.map { index ->
            val bind = log[index + 1]
            requireNotNull(boundTextureName(bind)) { "an upload binds the name it just generated, not \"$bind\"" }
        }

    /** The texture name in a `bindTexture(<target>,<name>)` log line, or null for any other line. */
    private fun boundTextureName(line: String): Int? =
        if (line.startsWith("bindTexture(")) line.substringAfterLast(',').removeSuffix(")").toIntOrNull() else null

    private companion object {
        /** `x in {1, 2}, y in {10, 11}` at LOD 4 — see `styleCamera`'s own KDoc for why that camera. */
        const val GROUND_TILES_PER_FRAME: Int = 4

        /** One canonical 512x512 RGBA8 basemap tile on the GPU. */
        const val ONE_TILE_BYTES: Long = 512L * 512L * 4L
    }
}
