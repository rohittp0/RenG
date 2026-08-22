package com.rohittp.reng

import com.rohittp.reng.internal.gl.GL_COLOR_ATTACHMENT0
import com.rohittp.reng.internal.gl.GL_COLOR_BUFFER_BIT
import com.rohittp.reng.internal.gl.GL_DRAW_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_FRAMEBUFFER_COMPLETE
import com.rohittp.reng.internal.gl.GL_PACK_ALIGNMENT
import com.rohittp.reng.internal.gl.GL_READ_FRAMEBUFFER
import com.rohittp.reng.internal.gl.GL_RGBA
import com.rohittp.reng.internal.gl.GL_RGBA8
import com.rohittp.reng.internal.gl.GL_SCISSOR_TEST
import com.rohittp.reng.internal.gl.GL_TEXTURE_2D
import com.rohittp.reng.internal.gl.GL_UNSIGNED_BYTE
import com.rohittp.reng.internal.gl.GlBinding
import com.rohittp.reng.internal.gl.RenderContextProbe
import com.rohittp.reng.internal.gl.ShaderDialect
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.PureKotlinSha256
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Cycle E's gate: draw a known camera over known ground through the **public** API, read the whole
 * frame back off a real driver, and assert relationships between the pixels.
 *
 * **Why this exists at all.** Every other basemap assertion in the tree is a call log or an exact
 * url, and the E design says why that is not enough
 * (`docs/superpowers/specs/2026-08-20-cycle-e-basemap-design.md`, "Basemap is verified by analytical
 * readback"): basemap failures are quiet and plausible. A transposed tile index, a wrong LOD, a
 * v-flipped texture, a ground that draws nothing, a `drawBasemap` flag that does nothing — every one
 * of them issues a draw call that looks perfectly correct.
 *
 * **What it catches.** Exactly that class:
 * - a ground that silently draws nothing (no interior pixel may be the target's own colour);
 * - a transposed or mis-mapped tile index (four named interior samples must carry the four *specific*
 *   fixture colours, and the fixture's tile set is disjoint from its own transpose);
 * - a v-flipped or u-flipped ground quad (each fixture tile's four texels are different, and the
 *   camera sees exactly one corner of each, so a flip shows a decoy grey/white/black instead);
 * - a mirrored or rotated frame (the four quadrant red means must stand in a strict order);
 * - a dead `drawBasemap = false` (the negative case, which is also what stops the other three
 *   passing vacuously);
 * - ADR 0025's depth ruling in pixels: two coplanar altitude-0 map-anchored stickers over the ground,
 *   where the later-declared one must win;
 * - ADR 0027's depth ruling in pixels, under **pitched** cameras: a coplanar altitude-0 `Geometry`
 *   must keep every pixel the ground covers, at every camera in a sweep, and a map-anchored
 *   billboard must be the same size at pitch as it is at pitch 0.
 *
 * **What it does NOT catch, stated so nobody reads more into a green run than is there:**
 * sub-pixel tile placement; filtering quality; blend correctness at tile edges, which is exactly
 * where it deliberately does not sample; the antimeridian seam and world-copy dedup (the fixture
 * camera straddles neither); models; and **any regression that preserves the four quadrant means
 * and the four sample colours** — which is why the fixture is asymmetric in both axes, and why "add
 * a fifth sample point" is the cheapest next increment. It stores no baseline and compares no
 * image, so it says nothing at all about how the frame *looks*; golden images remain Cycle J's.
 *
 * **One camera is not a sweep.** Two defects shipped past 942 green tests and were found by watching
 * a video: a coplanar `Geometry` losing up to 100% of its pixels between consecutive frames, and a
 * map-anchored billboard sliced in half along its own anchor row. Both are invisible at pitch 0 —
 * the one camera where a coplanar surface and the ground land on bit-identical window depth — and
 * every fixture in this file used to be pitch 0. The two pitched cases below therefore sweep the
 * camera rather than sample it, and assert a *quantity* (which pixels, how many) rather than mere
 * presence, because "the quad appeared in this one frame" is exactly the assertion both defects
 * would have passed.
 *
 * **No baselines, and no shared decoder between the two sides.** The expected colours are constants
 * written by hand from the fixture PNGs; the actual side is a raw `glReadPixels`. Nothing on the
 * expected side runs `decodePng`, so a decoder regression can only make this fail, never pass.
 */
internal fun runBasemapReadbackSuite(
    binding: GlBinding,
    probe: RenderContextProbe,
    dialect: ShaderDialect,
) {
    val target = createReadbackTarget(binding)
    val transport = ReadbackTransport()
    val renderer = createRenderer(
        RendererConfiguration(
            outputPixelSize = OutputPixelSize(READBACK_PIXELS, READBACK_PIXELS),
            transport = transport,
            store = RecordingStyleStore(),
            basemapStyle = ResourceLocator(STYLE_URL),
        ),
        binding,
        probe,
    )
    try {
        val renderTarget = renderer.mintRenderTarget(FramebufferName(target.toUInt()))

        assertGroundCoversTheFrameInTheFixturesOwnArrangement(binding, renderer, renderTarget, target, dialect)
        assertDrawBasemapFalseLeavesTheFrameUntouched(binding, renderer, renderTarget, target)
        assertTheLaterOfTwoCoplanarMapAnchoredThingsWins(binding, renderer, renderTarget, target)
        assertACoplanarGeometryKeepsEveryGroundCoveredPixelAcrossACameraSweep(
            binding, renderer, renderTarget, target,
        )
        assertAMapAnchoredBillboardIsTheSameSizeAtEveryPitch(binding, renderer, renderTarget, target)
    } finally {
        renderer.close()
        binding.deleteFramebuffers(1, intArrayOf(target))
    }
}

/**
 * The positive case. Four named samples, one per on-screen quadrant, plus the quadrant-mean ordering
 * and the "something drew at all" invariant.
 *
 * The camera is the asymmetric `(-55, -135)` zoom-4 one this cycle already proved is **disjoint from
 * its own transpose** (`RendererBasemapStyleTest.styleCamera`'s own KDoc records why). A symmetric
 * camera cannot catch an x/y transposition, and this cycle has already shipped one suite of exact-url
 * assertions that could not, because every fixture happened to be symmetric.
 */
private fun assertGroundCoversTheFrameInTheFixturesOwnArrangement(
    binding: GlBinding,
    renderer: Renderer,
    renderTarget: RenderTarget,
    targetFramebuffer: Int,
    dialect: ShaderDialect,
) {
    val frame = clearAndDraw(binding, renderer, renderTarget, targetFramebuffer, basemapPlan(frameIndex = 0L))
    val absent = frame.count { pixel -> pixel.isCloseTo(ABSENT) }
    assertEquals(
        0,
        absent,
        "the ground must cover the whole frame: $absent interior pixels are still the target's own colour",
    )

    NAMED_SAMPLES.forEach { sample ->
        val observed = frame.at(sample.x, sample.y)
        assertTrue(
            observed.isCloseTo(sample.expected),
            "${sample.name} at (${sample.x}, ${sample.y}) is ${observed.describe()}, " +
                "expected ${sample.expected.describe()}; nearest fixture colour is " +
                FIXTURE_COLOURS.minBy { observed.distanceTo(it.second) }.first,
        )
    }

    val meansByQuadrant = QUADRANTS.map { it.name to frame.quadrantMean(it) }
    val reds = meansByQuadrant.map { it.second[0] }
    assertTrue(
        reds[0] > reds[1] && reds[1] > reds[2] && reds[2] > reds[3],
        "quadrant red means must stand in the fixture's order " +
            QUADRANTS.joinToString(" > ") { it.name } + ", observed " +
            meansByQuadrant.joinToString(", ") { "${it.first}=${it.second[0]}" },
    )

    println(
        "RenG basemap readback: dialect=$dialect " +
            meansByQuadrant.joinToString(" ") { (name, mean) ->
                "$name=(${mean[0]},${mean[1]},${mean[2]},${mean[3]})"
            } +
            " sha256=" + PureKotlinSha256.digest(CanonicalBytes(frame.bytes)).lowercaseHex,
    )
}

/**
 * The negative case, and the reason the positive one is not vacuous: with `drawBasemap = false` the
 * frame must come back exactly as the target was left, because RenG's own offscreen surface clears to
 * a fully transparent black that composites to nothing.
 */
private fun assertDrawBasemapFalseLeavesTheFrameUntouched(
    binding: GlBinding,
    renderer: Renderer,
    renderTarget: RenderTarget,
    targetFramebuffer: Int,
) {
    val frame = clearAndDraw(
        binding,
        renderer,
        renderTarget,
        targetFramebuffer,
        FramePlan(frameIndex = 1L, camera = styleCamera(), drawBasemap = false),
    )
    val drawn = frame.count { pixel -> !pixel.isCloseTo(ABSENT) }
    assertEquals(0, drawn, "drawBasemap = false must draw no ground at all, but $drawn pixels changed")
}

/**
 * ADR 0025's ordering contract, in pixels, and the only place it is enforced.
 *
 * Both stickers are map-anchored at the camera's own ground anchor with altitude 0, under a pitch-0
 * camera — so both quads lie exactly in the ground plane. Three distinct outcomes are therefore
 * distinguishable at one sample point:
 * - the **second** sticker's colour: ground-first plus declaration order — correct;
 * - the **first** sticker's colour: the map regime drew in reverse, so the order resolved backwards;
 * - the **ground's** colour: a coplanar altitude-0 sticker lost the depth test to the ground and
 *   vanished — the silent deletion ADR 0025 exists to stop.
 *
 * This case predates ADR 0027 and is deliberately kept rather than folded into the pitched sweeps
 * below: it is the *ordering* half of the contract, which ADR 0027 leaves standing and in fact
 * strengthens from "later-declared wins a tie" to "later-declared wins". Under ADR 0027 the third
 * outcome can no longer be produced by the depth function alone — nothing on the map plane writes
 * depth for a sticker to lose to — but it remains the right thing to name in the failure message,
 * because it is still what a consumer would see if some future pass started writing depth again.
 */
private fun assertTheLaterOfTwoCoplanarMapAnchoredThingsWins(
    binding: GlBinding,
    renderer: Renderer,
    renderTarget: RenderTarget,
    targetFramebuffer: Int,
) {
    val plan = FramePlan(
        frameIndex = 2L,
        camera = styleCamera(),
        drawBasemap = true,
        stickers = listOf(
            Sticker(placement = coplanarGroundPlacement(), image = ResourceLocator(FIRST_STICKER_URL)),
            Sticker(placement = coplanarGroundPlacement(), image = ResourceLocator(SECOND_STICKER_URL)),
        ),
    )
    val frame = clearAndDraw(binding, renderer, renderTarget, targetFramebuffer, plan)

    STICKER_SAMPLES.forEach { (x, y) ->
        val observed = frame.at(x, y)
        assertTrue(
            observed.isCloseTo(SECOND_STICKER),
            "at ($x, $y) the later-declared coplanar sticker must win, but the pixel is " +
                observed.describe() + " (first sticker = " + FIRST_STICKER.describe() +
                ", ground here = " + NORTH_WEST_TILE.describe() + ")",
        )
    }
}

/**
 * ADR 0027's first defect, in pixels, under cameras ADR 0025's own evidence never looked at.
 *
 * A `Geometry` at altitude 0 is coplanar with the basemap ground by construction — `CONTEXT.md`
 * calls painting a region at altitude 0 the ordinary thing a consumer does. ADR 0025 made the
 * *exact* tie pass and stopped there, having verified on a pitch-0 camera that the two surfaces land
 * on bit-identical window depth. They do, and only there: the ground's depth comes from
 * `projection * view * mapSpaceModel` while a geometry corner's comes from `projection * view`
 * applied to an already-camera-relative corner, so under any other camera the two differ by a float
 * epsilon whose **sign changes as the camera moves**. Measured on a real map style before this
 * changed, one coplanar quad's pixel count over fifteen consecutive frames ran 3609, 7026, 9468,
 * 1257, 25, 0, 8607, 0, 6501 — twice erased outright.
 *
 * **The assertion is per-pixel, not a count, and it is a three-way render.** For each camera in
 * [DEPTH_SWEEP_CAMERAS] the same geometry is drawn three times: with no ground, over ground with no
 * geometry, and over ground with the geometry. Every pixel that the geometry paints on its own AND
 * that the ground covers on its own must still be the geometry's colour in the combined frame. That
 * phrasing is deliberate:
 * - comparing pixel *sets* rather than totals means a quad that loses one region and gains another
 *   cannot cancel out to a passing count;
 * - intersecting with the ground-only frame is what makes the case non-vacuous — a pixel above the
 *   horizon has no ground beneath it and proves nothing, so it is excluded rather than allowed to
 *   dilute the result;
 * - and requiring a floor on both the geometry's own pixels and the covered fraction stops the whole
 *   assertion passing on an empty intersection.
 *
 * The sweep varies pitch and bearing together. Pitch is what the defect needs; bearing is what stops
 * a fix that happens to work along one screen axis reading as a fix.
 */
private fun assertACoplanarGeometryKeepsEveryGroundCoveredPixelAcrossACameraSweep(
    binding: GlBinding,
    renderer: Renderer,
    renderTarget: RenderTarget,
    targetFramebuffer: Int,
) {
    var frameIndex = 100L
    DEPTH_SWEEP_CAMERAS.forEach { camera ->
        val geometryOnly = clearAndDraw(
            binding, renderer, renderTarget, targetFramebuffer,
            FramePlan(
                frameIndex = frameIndex++,
                camera = camera,
                drawBasemap = false,
                geometries = listOf(coplanarGeometry()),
            ),
        )
        val groundOnly = clearAndDraw(
            binding, renderer, renderTarget, targetFramebuffer,
            FramePlan(frameIndex = frameIndex++, camera = camera, drawBasemap = true),
        )
        val both = clearAndDraw(
            binding, renderer, renderTarget, targetFramebuffer,
            FramePlan(
                frameIndex = frameIndex++,
                camera = camera,
                drawBasemap = true,
                geometries = listOf(coplanarGeometry()),
            ),
        )

        val where = "pitch ${camera.pitch} bearing ${camera.bearing}"
        var painted = 0
        var covered = 0
        var kept = 0
        for (y in INTERIOR) {
            for (x in INTERIOR) {
                if (!geometryOnly.at(x, y).isCloseTo(COPLANAR_GEOMETRY)) continue
                painted += 1
                if (groundOnly.at(x, y).isCloseTo(ABSENT)) continue
                covered += 1
                if (both.at(x, y).isCloseTo(COPLANAR_GEOMETRY)) kept += 1
            }
        }

        assertTrue(
            painted >= MINIMUM_SWEEP_GEOMETRY_PIXELS,
            "at $where the coplanar geometry drew only $painted pixels with no ground beneath it, " +
                "so this camera proves nothing; the fixture must keep the quad on screen",
        )
        assertTrue(
            covered * 2 >= painted,
            "at $where only $covered of the geometry's $painted pixels have ground beneath them, " +
                "so the ground is not actually under the quad and the case is vacuous",
        )
        assertEquals(
            covered,
            kept,
            "at $where the ground deleted ${covered - kept} of the $covered ground-covered pixels " +
                "of a coplanar altitude-0 geometry (ADR 0027: no map-regime draw writes depth, so " +
                "nothing on the map plane can win or lose a near-tie against anything else on it)",
        )
    }
}

/**
 * ADR 0027's second defect, in pixels: a map-anchored billboard must be the same size at pitch as at
 * pitch 0.
 *
 * A map-anchored sticker with `SCREEN` rotation is `CONTEXT.md`'s billboard — a screen-parallel quad
 * pinned to a coordinate — so every one of its fragments carries the anchor's single depth, while
 * the map plane it stands on has depth varying down the screen. Below the anchor row the plane is
 * nearer, so under any nonzero pitch the depth test used to delete the lower half of the quad along
 * a hard horizontal line through the anchor. ADR 0025's tie rule rescued exactly the anchor row and
 * no other. Measured before this changed, the visible half of one billboard shrank from a 41x40
 * bounding box to 39x19 — the width intact, the height halved.
 *
 * **Why an exact-count assertion is legitimate here rather than a tolerance-heavy one.** The sticker
 * is anchored at the camera's own ground anchor, and [resolveMercatorCamera] orbits that anchor at a
 * fixed [ResolvedMercatorCamera.cameraDistanceLogicalPixels] whatever the pitch, so the anchor's
 * view-space position is `(0, 0, -distance)` at every pitch in the sweep. With `SCREEN` rotation and
 * `SCREEN` scale the quad's whole model-view-projection matrix is therefore *identical* across the
 * sweep, and the only thing that differs between these frames is the ground behind it. A pitched
 * count that differs from the pitch-0 count at all is the depth buffer eating the billboard; it
 * cannot be projection drift.
 *
 * A small tolerance is allowed anyway, because a driver is free to rasterise the same quad over an
 * opaque ground of a different colour with a boundary pixel resolved differently.
 */
private fun assertAMapAnchoredBillboardIsTheSameSizeAtEveryPitch(
    binding: GlBinding,
    renderer: Renderer,
    renderTarget: RenderTarget,
    targetFramebuffer: Int,
) {
    var frameIndex = 200L
    val counts = BILLBOARD_PITCHES.map { pitch ->
        val frame = clearAndDraw(
            binding, renderer, renderTarget, targetFramebuffer,
            FramePlan(
                frameIndex = frameIndex++,
                camera = sweepCamera(pitch = pitch, bearing = 0.0),
                drawBasemap = true,
                stickers = listOf(
                    Sticker(
                        placement = coplanarGroundPlacement(),
                        image = ResourceLocator(SECOND_STICKER_URL),
                    ),
                ),
            ),
        )
        pitch to frame.count { it.isCloseTo(SECOND_STICKER) }
    }

    val (levelPitch, levelCount) = counts.first()
    assertEquals(0.0, levelPitch, "the sweep's first camera must be the pitch-0 reference")
    assertTrue(
        levelCount >= MINIMUM_BILLBOARD_PIXELS,
        "the pitch-0 billboard drew only $levelCount pixels, so every later comparison is vacuous",
    )
    val tolerance = maxOf(2, levelCount / 50)
    counts.drop(1).forEach { (pitch, count) ->
        assertTrue(
            abs(count - levelCount) <= tolerance,
            "the map-anchored billboard is $count pixels at pitch $pitch against $levelCount at " +
                "pitch 0 (tolerance $tolerance). Its model-view-projection matrix is identical at " +
                "every pitch in this sweep, so a difference is the ground beneath it winning the " +
                "depth test against a screen-parallel quad — the bisection ADR 0027 removes by " +
                "taking depth writes off every map-regime draw.",
        )
    }
    println("RenG billboard-at-pitch readback: " + counts.joinToString(" ") { "${it.first}=${it.second}" })
}

// ---- harness -------------------------------------------------------------------------------------

/** The gate's frame size. Small enough to reason about, a power of two so pack alignment never bites. */
internal const val BASEMAP_READBACK_PIXELS: Int = 128

private const val READBACK_PIXELS: Int = BASEMAP_READBACK_PIXELS

/**
 * Per-channel tolerance. The fixture colours are separated by at least 32 in some channel, and the
 * regions sampled are flat colour well inside a clamped texel, so this absorbs driver rounding
 * without ever admitting a neighbouring fixture colour.
 */
private const val CHANNEL_TOLERANCE: Int = 8

private class ReadbackFrame(val bytes: ByteArray) {
    /** [x] rightward and [y] **downward** from the top-left, converting `glReadPixels`' bottom-up rows. */
    fun at(x: Int, y: Int): IntArray {
        val row = READBACK_PIXELS - 1 - y
        val offset = (row * READBACK_PIXELS + x) * 4
        return IntArray(4) { bytes[offset + it].toInt() and 0xff }
    }

    fun count(predicate: (IntArray) -> Boolean): Int {
        var total = 0
        for (y in INTERIOR) {
            for (x in INTERIOR) {
                if (predicate(at(x, y))) total += 1
            }
        }
        return total
    }

    fun quadrantMean(quadrant: Quadrant): IntArray {
        val sums = LongArray(4)
        var pixels = 0L
        for (y in quadrant.top until quadrant.top + READBACK_PIXELS / 2) {
            for (x in quadrant.left until quadrant.left + READBACK_PIXELS / 2) {
                val pixel = at(x, y)
                for (channel in 0 until 4) sums[channel] += pixel[channel].toLong()
                pixels += 1
            }
        }
        return IntArray(4) { (sums[it] / pixels).toInt() }
    }
}

private val INTERIOR: IntRange = 1 until READBACK_PIXELS - 1

private class Quadrant(val name: String, val left: Int, val top: Int)

/** Listed in the order their red means must descend; see the fixture colours below. */
private val QUADRANTS: List<Quadrant> = listOf(
    Quadrant("north-west", 0, 0),
    Quadrant("north-east", READBACK_PIXELS / 2, 0),
    Quadrant("south-west", 0, READBACK_PIXELS / 2),
    Quadrant("south-east", READBACK_PIXELS / 2, READBACK_PIXELS / 2),
)

private class NamedSample(val name: String, val x: Int, val y: Int, val expected: IntArray)

private fun IntArray.isCloseTo(other: IntArray): Boolean =
    indices.all { abs(this[it] - other[it]) <= CHANNEL_TOLERANCE }

private fun IntArray.distanceTo(other: IntArray): Int = indices.maxOf { abs(this[it] - other[it]) }

private fun IntArray.describe(): String = "(${this[0]},${this[1]},${this[2]},${this[3]})"

/** Draws [plan] into a freshly [ABSENT]-cleared [targetFramebuffer] and reads the whole frame back. */
private fun clearAndDraw(
    binding: GlBinding,
    renderer: Renderer,
    renderTarget: RenderTarget,
    targetFramebuffer: Int,
    plan: FramePlan,
): ReadbackFrame {
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
    binding.disable(GL_SCISSOR_TEST)
    binding.colorMask(true, true, true, true)
    binding.viewport(0, 0, READBACK_PIXELS, READBACK_PIXELS)
    binding.clearColor(ABSENT[0] / 255f, ABSENT[1] / 255f, ABSENT[2] / 255f, ABSENT[3] / 255f)
    binding.clear(GL_COLOR_BUFFER_BIT)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)

    // prepare() is suspending and reaches the Rentile engine on Dispatchers.Default; draw() is
    // synchronous GL work that must happen on the thread holding the context, so the two are split
    // rather than run inside one runBlocking body.
    val frame = runBlocking { renderer.prepare(plan) }
    try {
        renderer.draw(frame, renderTarget)
    } finally {
        frame.close()
    }

    val pixels = ByteArray(READBACK_PIXELS * READBACK_PIXELS * 4)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    // GL_PACK_ALIGNMENT defaults to 4 on both measured drivers and 128 is a multiple of 4, so this is
    // belt and braces -- but a readback that silently acquires row padding is exactly the kind of
    // defect that makes every assertion below wrong in a plausible-looking way.
    binding.pixelStorei(GL_PACK_ALIGNMENT, 1)
    binding.readPixels(0, 0, READBACK_PIXELS, READBACK_PIXELS, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    return ReadbackFrame(pixels)
}

private fun createReadbackTarget(binding: GlBinding): Int {
    val names = IntArray(1)
    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, READBACK_PIXELS, READBACK_PIXELS)
    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    assertEquals(
        GL_FRAMEBUFFER_COMPLETE,
        binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER),
        "the readback target must be a complete framebuffer",
    )
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    return framebuffer
}

// ---- the fixture ---------------------------------------------------------------------------------

/**
 * What the readback target is cleared to before every draw. Not a fixture tile colour and not a
 * decoy, so "still absent" is unambiguous. RenG's own offscreen surface clears to `(0, 0, 0, 0)` and
 * composites with source alpha, so an undrawn frame leaves this standing untouched.
 */
private val ABSENT: IntArray = intArrayOf(0, 96, 32, 255)

/**
 * At `(-55, -135)` zoom 4 with a 128x128 output, the visible ground is 128 logical pixels square out
 * of a 512-logical-pixel tile, so the camera sees roughly an eighth of each of its four tiles — and
 * one *different corner* of each:
 *
 * | tile | on screen | visible corner |
 * |---|---|---|
 * | `x = 1, y = 10` | north-west | its south-east corner |
 * | `x = 2, y = 10` | north-east | its south-west corner |
 * | `x = 1, y = 11` | south-west | its north-east corner |
 * | `x = 2, y = 11` | south-east | its north-west corner |
 *
 * Each fixture tile is therefore served as a 2x2 PNG whose four texels differ: the corner the camera
 * actually sees carries that tile's own colour, and the other three carry white, black and grey
 * decoys. Rentile scales the 2x2 to 512x512 with clamped bilinear filtering (measured), so every
 * visible corner is a flat, pure texel — and a u-flip or a v-flip in the ground quad swaps in a decoy
 * that no tolerance can mistake for the expected colour.
 */
private val NORTH_WEST_TILE: IntArray = intArrayOf(240, 40, 40, 255)
private val NORTH_EAST_TILE: IntArray = intArrayOf(200, 200, 40, 255)
private val SOUTH_WEST_TILE: IntArray = intArrayOf(40, 40, 200, 255)
private val SOUTH_EAST_TILE: IntArray = intArrayOf(0, 160, 160, 255)
private val DECOY_WHITE: IntArray = intArrayOf(255, 255, 255, 255)
private val DECOY_BLACK: IntArray = intArrayOf(0, 0, 0, 255)
private val DECOY_GREY: IntArray = intArrayOf(128, 128, 128, 255)
private val FIRST_STICKER: IntArray = intArrayOf(255, 0, 255, 255)
private val SECOND_STICKER: IntArray = intArrayOf(255, 128, 0, 255)

/**
 * What [COPLANAR_GEOMETRY_FRAGMENT_SOURCE] paints, opaque.
 *
 * Opaque on purpose. `drawGeometry` runs a consumer's own program and establishes no blend state of
 * its own, so a geometry-only frame inherits whatever blend function was last set while a
 * geometry-over-ground frame inherits `drawGround`'s premultiplied one. At `alpha = 1` every one of
 * those functions — premultiplied, straight, and blending disabled — returns the source colour
 * unchanged, so the pixel-set comparison in the sweep is comparing depth behaviour and nothing else.
 */
private val COPLANAR_GEOMETRY: IntArray = intArrayOf(0, 255, 128, 255)

/**
 * Served for any tile url inside the fixture's own template that the four named tiles do not cover.
 *
 * A pitched camera sees ground the pitch-0 fixture never reaches, and [ReadbackTransport] used to
 * fail closed on the first such url. This colour is deliberately not any expected sample value, so
 * the fallback cannot rescue a wrong-url regression in
 * [assertGroundCoversTheFrameInTheFixturesOwnArrangement]: a transposed index there now yields this
 * purple at a named sample and the failure message names it, rather than the fallback quietly
 * serving a colour that happens to match.
 */
private val HORIZON_FILLER_TILE: IntArray = intArrayOf(96, 32, 160, 255)

private val FIXTURE_COLOURS: List<Pair<String, IntArray>> = listOf(
    "north-west tile" to NORTH_WEST_TILE,
    "north-east tile" to NORTH_EAST_TILE,
    "south-west tile" to SOUTH_WEST_TILE,
    "south-east tile" to SOUTH_EAST_TILE,
    "decoy white" to DECOY_WHITE,
    "decoy black" to DECOY_BLACK,
    "decoy grey" to DECOY_GREY,
    "first sticker" to FIRST_STICKER,
    "second sticker" to SECOND_STICKER,
    "coplanar geometry" to COPLANAR_GEOMETRY,
    "horizon filler tile" to HORIZON_FILLER_TILE,
    "absent" to ABSENT,
)

/**
 * One sample per on-screen quadrant, well inside a flat region.
 *
 * The horizontal tile seam is at screen column 64 exactly (the camera's own longitude sits on a tile
 * boundary at LOD 4) and the vertical one at screen row 95, so `y = 112` is inside the southern band
 * with 16 rows of margin below and 17 above, and `y = 32` is 63 rows clear of it.
 */
private val NAMED_SAMPLES: List<NamedSample> = listOf(
    NamedSample("north-west", 32, 32, NORTH_WEST_TILE),
    NamedSample("north-east", 96, 32, NORTH_EAST_TILE),
    NamedSample("south-west", 32, 112, SOUTH_WEST_TILE),
    NamedSample("south-east", 96, 112, SOUTH_EAST_TILE),
)

/**
 * Two points inside the 32x32 output-pixel sticker quad centred on the frame, kept 4 pixels clear of
 * its edges so bilinear filtering at the boundary never reaches them.
 */
private val STICKER_SAMPLES: List<Pair<Int, Int>> = listOf(52 to 52, 74 to 74)

private const val FIRST_STICKER_URL: String = "https://images.example/readback-first.png"
private const val SECOND_STICKER_URL: String = "https://images.example/readback-second.png"

/**
 * Map-anchored at the camera's own ground anchor with altitude 0, so under this fixture's pitch-0
 * camera the quad lies exactly in the ground plane. Screen rotation and screen scale keep the quad
 * axis-aligned and its size predictable: the 2x2 source image scaled by 16 is 32 output pixels
 * square at the ground's depth.
 */
private fun coplanarGroundPlacement(): Placement = Placement(
    positionMode = AnchoringMode.MAP,
    position = Vector3(-55.0, -135.0, 0.0),
    rotationMode = AnchoringMode.SCREEN,
    rotation = Vector3(0.0, 0.0, 0.0),
    scaleMode = AnchoringMode.SCREEN,
    scale = 16.0,
)

/**
 * The cameras the coplanar sweep runs. Pitch is the axis the defect lives on; bearing moves with it
 * so a fix that only happens to work along one screen axis cannot read as a fix.
 *
 * Pitch stops at 55 degrees. The projection's vertical field of view is 45 degrees
 * (`FOCAL_LENGTH_SCALE = 1 + sqrt(2)`), so at 55 degrees the top screen row still looks 77.5 degrees
 * from nadir — below the horizon, and therefore still ground rather than sky. Past about 67 degrees
 * the top of the frame stops hitting the ground at all and the sweep would be measuring the horizon
 * clip instead of the depth rule.
 */
private val DEPTH_SWEEP_CAMERAS: List<Camera> = listOf(
    sweepCamera(pitch = 0.0, bearing = 0.0),
    sweepCamera(pitch = 15.0, bearing = 37.0),
    sweepCamera(pitch = 30.0, bearing = 113.0),
    sweepCamera(pitch = 45.0, bearing = 206.0),
    sweepCamera(pitch = 55.0, bearing = 301.0),
)

/**
 * [styleCamera] moved off pitch 0 and bearing 0. `Camera` is not a data class — construction
 * canonicalizes, so there is no `copy` — and the fixture's latitude, longitude and zoom are repeated
 * here rather than hardcoded a second time somewhere else.
 */
private fun sweepCamera(pitch: Double, bearing: Double): Camera {
    val level = styleCamera()
    return Camera(
        latitude = level.latitude,
        unwrappedLongitude = level.unwrappedLongitude,
        zoom = level.zoom,
        bearing = bearing,
        pitch = pitch,
    )
}

/** The pitches the billboard sweep runs, pitch 0 first because every later camera is compared to it. */
private val BILLBOARD_PITCHES: List<Double> = listOf(0.0, 15.0, 30.0, 45.0, 55.0)

/**
 * A floor on the coplanar quad's own footprint, so a camera that has swung the fixture off screen
 * fails loudly instead of passing on an empty comparison. The quad covers thousands of pixels of the
 * 128x128 frame at every camera in [DEPTH_SWEEP_CAMERAS]; this is an order of magnitude below that.
 */
private const val MINIMUM_SWEEP_GEOMETRY_PIXELS: Int = 200

/** The same floor for the billboard: its 2x2 image at scale 16 is a 32x32 output-pixel quad. */
private const val MINIMUM_BILLBOARD_PIXELS: Int = 700

/**
 * An altitude-0 rectangle around the fixture camera's own anchor — `CONTEXT.md`'s ordinary case, a
 * region painted flat on the ground — sized to stay well inside the frame at every camera in
 * [DEPTH_SWEEP_CAMERAS]. Latitude runs north-to-south and longitude west-to-east, as `Geometry`
 * requires.
 */
private fun coplanarGeometry(): Geometry = Geometry(
    topLeft = Vector3(-54.4, -136.0, 0.0),
    bottomRight = Vector3(-55.6, -134.0, 0.0),
    shaderPair = ShaderPair(COPLANAR_GEOMETRY_VERTEX_SOURCE, COPLANAR_GEOMETRY_FRAGMENT_SOURCE),
)

/**
 * The smallest shader pair that is still a real consumer shader: it declares `aPosition` and
 * `uModelViewProjection` from the documented interface and nothing else, and paints one flat opaque
 * colour so a pixel either is the geometry or is not. Anything with a gradient or an alpha ramp would
 * make the sweep's pixel-set comparison a threshold argument instead of an identity.
 */
private val COPLANAR_GEOMETRY_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "in vec3 aPosition;\n" +
        "uniform mat4 uModelViewProjection;\n" +
        "void main() {\n" +
        "    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);\n" +
        "}\n"

private val COPLANAR_GEOMETRY_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "out vec4 rengCoplanarColour;\n" +
        "void main() {\n" +
        "    rengCoplanarColour = vec4(0.0, 1.0, 0.501960784, 1.0);\n" +
        "}\n"

/**
 * A style with an opaque background and one raster source, so a rendered tile is exactly its source
 * image and nothing else. No sprite: this fixture is about pixels on the ground, and the sprite path
 * is already covered by `RendererBasemapStyleTest`.
 */
private val READBACK_STYLE_JSON: String =
    """{"version":8,"name":"reng-readback",""" +
        """"sources":{"s":{"type":"raster","tiles":["$STYLE_TILE_TEMPLATE"],"tileSize":512}},""" +
        """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#000000"}},""" +
        """{"id":"r","type":"raster","source":"s"}]}"""

/**
 * Serves the style, one distinctly-textured PNG per fixture tile url, the two sticker images, and
 * [HORIZON_FILLER_TILE_PNG] for any other tile inside the fixture's own url template.
 *
 * The fallback exists only because the pitched sweep sees ground the four named tiles do not cover.
 * It is scoped to the template's own prefix, so a url RenG composed from the wrong template — a
 * different source, a stale style — still fails closed here rather than being handed a plausible
 * body.
 */
private class ReadbackTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        val url = request.locator.value
        return when {
            url == STYLE_URL -> TransportResponse(
                statusCode = 200,
                body = READBACK_STYLE_JSON.encodeToByteArray(),
                metadata = TransportResponseMetadata(contentType = "application/json"),
            )
            else -> TransportResponse(
                statusCode = 200,
                body = READBACK_PNGS[url]
                    ?: HORIZON_FILLER_TILE_PNG.takeIf { url.startsWith(READBACK_TILE_URL_PREFIX) }
                    ?: error("the readback fixture serves no body for $url"),
                metadata = TransportResponseMetadata(contentType = "image/png"),
            )
        }
    }
}

/** Everything before `{z}` in [STYLE_TILE_TEMPLATE]; see [ReadbackTransport]. */
private val READBACK_TILE_URL_PREFIX: String = STYLE_TILE_TEMPLATE.substringBefore("{z}")

/** A 2x2 PNG whose four texels are all [HORIZON_FILLER_TILE]. */
private val HORIZON_FILLER_TILE_PNG: ByteArray = Base64.decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mNIUFjwH4QZYAwAR7QIfVj/5kkAAAAASUVORK5CYII=",
)

/**
 * Each tile PNG is 2x2 RGBA. Reading the four texels in `[north-west, north-east, south-west,
 * south-east]` order:
 * - `4/1/10`: white, black, grey, **red** — the camera sees its south-east corner;
 * - `4/2/10`: black, grey, **yellow**, white — its south-west corner;
 * - `4/1/11`: grey, **blue**, white, black — its north-east corner;
 * - `4/2/11`: **teal**, white, black, grey — its north-west corner.
 *
 * The two sticker PNGs are 2x2 solid magenta and solid orange.
 */
private val READBACK_PNGS: Map<String, ByteArray> = mapOf(
    "https://tiles.example/r/4/1/10.png" to Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAGUlEQVR42mP4DwQMDAz/GRoaGv5/0ND4DwBczAm6XLG13AAAAABJRU5ErkJggg==",
    ),
    "https://tiles.example/r/4/2/10.png" to Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAGElEQVR42mNgYGD439DQ8J/hxAmN/yAAAEWTCjK7PqFBAAAAAElFTkSuQmCC",
    ),
    "https://tiles.example/r/4/1/11.png" to Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAGUlEQVR42mNoaGj4r6Fx4j/DfyBgYGD4DwBXXwmS7TaUpwAAAABJRU5ErkJggg==",
    ),
    "https://tiles.example/r/4/2/11.png" to Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFklEQVR42mNgWLDgPwgwAMH/hoaG/wBZsAm6ga37uAAAAABJRU5ErkJggg==",
    ),
    FIRST_STICKER_URL to Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mP4z/D/PwgzwBgAaagL9Uu86vkAAAAASUVORK5CYII=",
    ),
    SECOND_STICKER_URL to Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEUlEQVR42mP438DwH4QZYAwAWsoJ+ZJB9b8AAAAASUVORK5CYII=",
    ),
)
