package com.rohittp.reng.internal.identity

import com.rohittp.reng.Camera
import com.rohittp.reng.FramePlan
import com.rohittp.reng.Geometry
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.gl.InternalPipelineRole
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * The memo that stops a shader pair's canonical form and keys being re-encoded and re-hashed on every
 * frame. Every expectation about *values* is computed here from [CanonicalBinary] directly, the way the
 * derivation did before the memo existed, so a memo that returned a wrong or stale value cannot agree.
 */
class ShaderKeyMemoTest {

    private var digests = 0

    private fun counting(memo: ShaderPairMemo = ShaderPairMemo()): ResourceKeyDeriver = ResourceKeyDeriver(
        sha256 = Sha256Function { bytes ->
            digests += 1
            PureKotlinSha256.digest(bytes)
        },
        shaderPairs = memo,
    )

    private fun pair(tag: String) = ShaderPair(
        vertexSource = "void main() { /* $tag */ }",
        fragmentSource = "void main() { /* fragment $tag */ }",
    )

    // ---- The pre-memo derivations, transcribed -------------------------------------------------

    private fun referenceGeometryProgramHex(pair: ShaderPair): String = PureKotlinSha256.digest(
        CanonicalBinary.root(CanonicalRootKind.GEOMETRY_PROGRAM) {
            field(1, CanonicalBinary.u16(2))
            field(2, CanonicalBinary.u16(1))
            field(3, CanonicalBinary.exactUtf8(pair.vertexSource))
            field(4, CanonicalBinary.exactUtf8(pair.fragmentSource))
        },
    ).lowercaseHex

    private fun referenceInternalPipelineHex(role: InternalPipelineRole, pair: ShaderPair): String =
        PureKotlinSha256.digest(
            CanonicalBinary.root(CanonicalRootKind.INTERNAL_PIPELINE) {
                field(1, CanonicalBinary.u16(3))
                field(2, CanonicalBinary.u16(role.wireValue))
                field(3, CanonicalBinary.u16(1))
                field(4, CanonicalBinary.exactUtf8(pair.vertexSource))
                field(5, CanonicalBinary.exactUtf8(pair.fragmentSource))
            },
        ).lowercaseHex

    private fun referenceEncodedPair(pair: ShaderPair): CanonicalBytes = CanonicalBinary.fields {
        field(1, CanonicalBinary.exactUtf8(pair.vertexSource))
        field(2, CanonicalBinary.exactUtf8(pair.fragmentSource))
    }

    // ---- Tests --------------------------------------------------------------------------------

    @Test
    fun equalShaderPairsDeriveOneKeyAndHashOnce() {
        val deriver = counting()
        val first = deriver.geometryProgram(pair("a"))
        repeat(50) {
            // A distinct but equal pair, as a consumer that rebuilds its plan every frame supplies.
            assertEquals(first, deriver.geometryProgram(pair("a")))
        }
        assertEquals(1, digests)
    }

    @Test
    fun differentShaderPairsDeriveDifferentKeys() {
        val deriver = counting()
        val a = deriver.geometryProgram(pair("a"))
        val b = deriver.geometryProgram(pair("b"))
        val fragmentOnly = deriver.geometryProgram(ShaderPair(pair("a").vertexSource, pair("b").fragmentSource))
        assertEquals(3, digests)
        assertNotEquals(a.key, b.key)
        assertNotEquals(a.key, fragmentOnly.key)
        assertNotEquals(b.key, fragmentOnly.key)
    }

    @Test
    fun memoisedKeysAreByteIdenticalToTheDerivationWithoutTheMemo() {
        val deriver = ResourceKeyDeriver()
        repeat(3) {
            for (tag in listOf("a", "b", "é ☃")) {
                val geometry = deriver.geometryProgram(pair(tag))
                assertEquals(referenceGeometryProgramHex(pair(tag)), geometry.key.stableId)
                assertEquals(ResourceKind.GEOMETRY_PROGRAM, geometry.key.kind)
                for (role in listOf(InternalPipelineRole.BACKDROP, InternalPipelineRole.LABEL)) {
                    val internal = deriver.internalPipeline(role, pair(tag))
                    assertEquals(referenceInternalPipelineHex(role, pair(tag)), internal.key.stableId)
                    assertEquals(ResourceKind.INTERNAL_PIPELINE, internal.key.kind)
                }
            }
        }
    }

    @Test
    fun theSameSourcesUnderAnotherRoleOrKindAreAnotherKey() {
        val deriver = counting()
        val program = deriver.geometryProgram(pair("a"))
        val backdrop = deriver.internalPipeline(InternalPipelineRole.BACKDROP, pair("a"))
        val label = deriver.internalPipeline(InternalPipelineRole.LABEL, pair("a"))
        assertEquals(3, digests)
        assertNotEquals(program.key, backdrop.key)
        assertNotEquals(backdrop.key, label.key)
        deriver.internalPipeline(InternalPipelineRole.BACKDROP, pair("a"))
        assertEquals(3, digests)
    }

    @Test
    fun aSharedMemoServesEveryDeriverAndTheEncoder() {
        val memo = ShaderPairMemo()
        val one = counting(memo)
        val two = counting(memo)
        one.geometryProgram(pair("a"))
        two.geometryProgram(pair("a"))
        assertEquals(1, digests)

        val plan = FramePlan(
            frameIndex = 0,
            camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
            geometries = listOf(Geometry(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), pair("a"))),
        )
        val withMemo = FramePlanCanonicalEncoder(shaderPairs = memo).encode(plan)
        val without = FramePlanCanonicalEncoder(shaderPairs = ShaderPairMemo()).encode(plan)
        repeat(3) { assertEquals(without, FramePlanCanonicalEncoder(shaderPairs = memo).encode(plan)) }
        assertEquals(without, withMemo)
        assertContentEquals(without.identity.canonicalBytes.bytes, withMemo.identity.canonicalBytes.bytes)
    }

    @Test
    fun theEncodedPairIsByteIdenticalToEncodingItAfresh() {
        val memo = ShaderPairMemo()
        for (tag in listOf("a", "b", "é ☃", "😀")) {
            repeat(2) {
                val canonical = memo.canonicalOf(pair(tag))
                assertContentEquals(referenceEncodedPair(pair(tag)).bytes, canonical.encodedPair.bytes)
                assertContentEquals(
                    CanonicalBinary.exactUtf8(pair(tag).vertexSource).bytes,
                    canonical.vertex.bytes,
                )
            }
        }
    }

    @Test
    fun theBoundForgetsTheOldestAndRederivesAnEqualKey() {
        val memo = ShaderPairMemo(capacity = 4)
        val deriver = counting(memo)
        val first = deriver.geometryProgram(pair("0"))
        repeat(4) { deriver.geometryProgram(pair("x$it")) }
        val before = digests
        assertEquals(first, deriver.geometryProgram(pair("0")))
        assertEquals(before + 1, digests)
    }

    @Test
    fun concurrentCallersAllSeeTheKeyASingleThreadDerives() = runBlocking {
        val deriver = ResourceKeyDeriver(shaderPairs = ShaderPairMemo(capacity = 8))
        val expected: List<ResourceKey> = (0 until 100).map { ResourceKeyDeriver().geometryProgram(pair("p$it")).key }
        val seen = (1..8).map {
            async(Dispatchers.Default) {
                (0 until 2_000).map { round -> (round % 100) to deriver.geometryProgram(pair("p${round % 100}")).key }
            }
        }.awaitAll().flatten()
        seen.forEach { (index, key) -> assertEquals(expected[index], key) }
    }
}
