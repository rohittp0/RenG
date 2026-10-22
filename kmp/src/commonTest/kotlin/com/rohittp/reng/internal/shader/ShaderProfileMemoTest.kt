package com.rohittp.reng.internal.shader

import com.rohittp.reng.Camera
import com.rohittp.reng.DEFAULT_MAXIMUM_CONSUMER_PIPELINES_PER_FRAME
import com.rohittp.reng.FramePlan
import com.rohittp.reng.Geometry
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.planning.FrameSpatialPlan
import com.rohittp.reng.internal.planning.SpatialOutcome
import com.rohittp.reng.internal.planning.planSpatial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The memo that stops frame planning re-scanning a consumer's shader text on every frame. Every expected
 * value is computed here by calling [scanShaderProfile] directly, so a memo that answered wrongly or
 * stalely cannot agree with it.
 */
class ShaderProfileMemoTest {

    private var scans = 0

    private fun countingMemo(capacity: Int = shaderProfileMemoCapacity(DEFAULT_MAXIMUM_CONSUMER_PIPELINES_PER_FRAME)): ShaderProfileMemo =
        ShaderProfileMemo(capacity) { source ->
            scans += 1
            scanShaderProfile(source)
        }

    @Test
    fun aRememberedSourceIsNotScannedAgainAndAnswersExactlyAsAFreshScan() {
        val memo = countingMemo()
        val sources = listOf(
            "#version 300 es\nvoid main() {}",
            " \t#version 300 es\t \r\nbody",
            "/* leading */\n// line\n#version 300 es\rtail",
        )

        for (source in sources) {
            val fresh = assertNotNull(scanShaderProfile(source))
            // The same instance, then an equal but distinct `String`: both must hit.
            val answers = listOf(
                memo.profileOf(source),
                memo.profileOf(source),
                memo.profileOf(source.toCharArray().concatToString()),
            )
            for (answer in answers) {
                val plan = assertNotNull(answer)
                assertEquals(fresh.directiveStartUtf16, plan.directiveStartUtf16)
                assertEquals(fresh.directiveEndExclusiveUtf16, plan.directiveEndExclusiveUtf16)
                assertEquals(fresh.gles300Source(), plan.gles300Source())
                assertEquals(fresh.desktop330Source(), plan.desktop330Source())
            }
        }

        assertEquals(sources.size, scans, "each distinct source is scanned exactly once")
    }

    @Test
    fun aSourceWithNoUsableProfileIsRememberedAsNoUsableProfile() {
        val memo = countingMemo()
        val unusable = listOf(
            "void main() {}",
            "#version 300 es\n#version 300 es",
        )

        for (source in unusable) {
            assertNull(scanShaderProfile(source), "the fixture must be one the scanner refuses")
            repeat(3) { assertNull(memo.profileOf(source)) }
        }

        assertEquals(unusable.size, scans)
    }

    @Test
    fun theMemoForgetsAnUnusedSourceBeforeARecentlyUsedOne() {
        val memo = countingMemo(capacity = 2)
        val a = "#version 300 es\n// a"
        val b = "#version 300 es\n// b"
        val c = "#version 300 es\n// c"

        memo.profileOf(a)
        memo.profileOf(b)
        memo.profileOf(c) // evicts a
        assertEquals(3, scans)
        memo.profileOf(b) // still remembered, and now marked as used
        assertEquals(3, scans)
        memo.profileOf(a) // forgotten, so scanned again; b gets a second chance and the unused c goes
        assertEquals(4, scans)
        memo.profileOf(b)
        assertEquals(4, scans)
        memo.profileOf(c)
        assertEquals(5, scans)
    }

    @Test
    fun aSecondPlanOfTheSameShadersDoesNotRescanAndPlansEqually() {
        val memo = countingMemo()
        val plan = FramePlan(
            frameIndex = 1L,
            camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
            drawBasemap = false,
            drawLabels = false,
            geometries = listOf(
                Geometry(
                    topLeft = Vector3(20.0, 30.0, 100.0),
                    bottomRight = Vector3(10.0, 40.0, 50.0),
                    shaderPair = ShaderPair(
                        vertexSource = "#version 300 es\n// vertex",
                        fragmentSource = "#version 300 es\n// fragment",
                    ),
                ),
            ),
        )

        val first = planned(plan, memo)
        assertEquals(2, scans, "the first plan scans the pair's two sources")
        val second = planned(plan, memo)
        assertEquals(2, scans, "a second plan of the same shaders scans nothing")

        // Equal to a plan made with no remembered scan at all, and to each other.
        val unmemoized = planned(plan, ShaderProfileMemo())
        assertEquals(unmemoized, first)
        assertEquals(unmemoized, second)
        for (planned in listOf(first, second)) {
            val (vertex, fragment) = planned.shaderProfiles.single()
            val (freshVertex, freshFragment) = unmemoized.shaderProfiles.single()
            assertEquals(freshVertex.desktop330Source(), vertex.desktop330Source())
            assertEquals(freshFragment.desktop330Source(), fragment.desktop330Source())
        }
    }

    private fun planned(plan: FramePlan, memo: ShaderProfileMemo): FrameSpatialPlan =
        assertIs<SpatialOutcome.Success<FrameSpatialPlan>>(
            planSpatial(
                plan = plan,
                outputPixelSize = OutputPixelSize(100, 100),
                previousSelectedLod = null,
                maximumBasemapTileInstances = 1,
                basemapStyleConfigured = false,
                shaderProfileMemo = memo,
            ),
        ).value
}
