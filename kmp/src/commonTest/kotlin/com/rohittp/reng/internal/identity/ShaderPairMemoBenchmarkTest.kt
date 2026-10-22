package com.rohittp.reng.internal.identity

import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.bench.RengBench
import kotlin.test.Test

/**
 * B6: [ResourceKeyDeriver.geometryProgram] -- the [ShaderPairMemo] lookup a frame makes per geometry --
 * with distinct shader pairs cycled in a fixed order.
 *
 * - `cycle32`: fewer pairs than the memo remembers, so after warm-up every lookup hits;
 * - `cycle100`: more pairs than it remembers. A first-in-first-out bound cycled in order evicts each pair
 *   just before it comes round again, so every lookup re-encodes and re-hashes both sources.
 * - `mixed48hot40cold`: each "frame" names [HOT_PAIRS] pairs that recur every frame, interleaved with
 *   [COLD_PAIRS] one-off pairs never seen again -- more distinct pairs per frame than the memo
 *   remembers, of which the recurring ones fit. The cold pairs are built before the timed region.
 *
 * One iteration is [LOOKUPS] lookups. Each pair is a realistic ~2 KB of GLSL, unique per pair.
 */
class ShaderPairMemoBenchmarkTest {
    @Test
    fun cyclingDistinctShaderPairsThroughTheMemo() {
        measure("b6.shader_pair_memo.cycle32.lookups$LOOKUPS", pairCount = 32)
        measure("b6.shader_pair_memo.cycle100.lookups$LOOKUPS", pairCount = 100)
        measureMixed("b6.shader_pair_memo.mixed${HOT_PAIRS}hot${COLD_PAIRS}cold.lookups$LOOKUPS")
    }

    private fun measureMixed(name: String) {
        val deriver = ResourceKeyDeriver()
        val hot = List(HOT_PAIRS) { shaderPair(it) }
        var nextCold = HOT_PAIRS
        var position = 0
        // One frame's order: hot and cold alternate until the cold ones run out, then the rest of the hot.
        val frameSize = HOT_PAIRS + COLD_PAIRS
        fun isHot(slot: Int): Boolean = slot >= 2 * COLD_PAIRS || slot % 2 == 0
        fun hotIndex(slot: Int): Int = if (slot >= 2 * COLD_PAIRS) slot - COLD_PAIRS else slot / 2
        RengBench.measure(name, warmupIterations = 5, minimumIterations = 20) {
            val start = position
            val pairs = List(LOOKUPS) { offset ->
                val slot = (start + offset) % frameSize
                if (isHot(slot)) hot[hotIndex(slot)] else shaderPair(nextCold++)
            }
            position = (start + LOOKUPS) % frameSize
            timed {
                for (pair in pairs) fold(deriver.geometryProgram(pair).key.stableId.hashCode())
            }
        }
    }

    private fun measure(name: String, pairCount: Int) {
        val deriver = ResourceKeyDeriver()
        val pairs = List(pairCount) { shaderPair(it) }
        var next = 0
        RengBench.measure(name, warmupIterations = 5, minimumIterations = 20) {
            timed {
                repeat(LOOKUPS) {
                    val key = deriver.geometryProgram(pairs[next])
                    next = (next + 1) % pairCount
                    fold(key.key.stableId.hashCode())
                }
            }
        }
    }

    private fun shaderPair(index: Int): ShaderPair {
        val body = buildString {
            repeat(40) { line -> append("    colour.rgb += vec3(${line * 0.01f + index}, ${line * 0.02f}, 0.5) * uStrength$line;\n") }
        }
        val uniforms = buildString { repeat(40) { append("uniform float uStrength$it;\n") } }
        return ShaderPair(
            vertexSource = "#version 300 es\n// pair $index\nin vec3 aPosition;\nuniform mat4 uModelViewProjection;\n" +
                "out vec2 vUv;\nvoid main() {\n    vUv = aPosition.xy;\n" +
                "    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);\n}\n",
            fragmentSource = "#version 300 es\nprecision highp float;\n// pair $index\nin vec2 vUv;\n$uniforms" +
                "out vec4 rengGeometryColour;\nvoid main() {\n    vec4 colour = vec4(vUv, 0.0, 1.0);\n$body" +
                "    rengGeometryColour = colour;\n}\n",
        )
    }

    private companion object {
        const val LOOKUPS = 1000
        const val HOT_PAIRS = 48
        const val COLD_PAIRS = 40
    }
}
