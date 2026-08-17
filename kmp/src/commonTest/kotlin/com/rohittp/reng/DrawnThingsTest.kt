package com.rohittp.reng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

class DrawnThingsTest {
    @Test
    fun animationSelectorAndTimeValidationIsExact() {
        AnimationSelector.Index(0)
        AnimationSelector.Name("é")
        assertFailsWith<IllegalArgumentException> { AnimationSelector.Index(-1) }
        assertFailsWith<IllegalArgumentException> { AnimationSelector.Name(" ") }
        assertFailsWith<IllegalArgumentException> {
            AnimationTrack(AnimationSelector.Index(0), Double.NaN)
        }
    }

    @Test
    fun animationSelectorsPreserveExactUnicodeWithoutNormalization() {
        val composed = AnimationSelector.Name("é")
        val decomposed = AnimationSelector.Name("é")

        assertFalse(composed == decomposed)
        assertFailsWith<IllegalArgumentException> { AnimationSelector.Name("\uD800") }
    }

    @Test
    fun animationTrackCanonicalizesNegativeZeroAndUsesStructuralEquality() {
        val first = AnimationTrack(AnimationSelector.Name("walk"), -0.0)
        val equal = AnimationTrack(AnimationSelector.Name("walk"), 0.0)
        val different = AnimationTrack(AnimationSelector.Name("walk"), 1.0)

        assertEquals(0L, first.timeSeconds.toBits())
        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertFalse(first == different)
    }

    @Test
    fun animationTrackRequiresFiniteNonNegativeTime() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0001).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                AnimationTrack(AnimationSelector.Index(0), invalid)
            }
        }
    }

    @Test
    fun modelCopiesInputAndEveryListResultIncludingEmptyAndSingleton() {
        val track = AnimationTrack(AnimationSelector.Index(0), 1.0)
        assertModelListIsIndependent(emptyList())
        assertModelListIsIndependent(listOf(track))
        assertModelListIsIndependent(listOf(track, track))
    }

    @Test
    fun modelListSnapshotKeepsEqualityAndHashStableAfterMutation() {
        val track = AnimationTrack(AnimationSelector.Index(0), 1.0)
        val input = mutableListOf(track)
        val model = Model(screenPlacement(), ResourceLocator("model"), animationTracks = input)
        val equal = Model(screenPlacement(), ResourceLocator("model"), animationTracks = listOf(track))
        val hashCode = model.hashCode()

        input.clear()
        (model.animationTracks as MutableList<AnimationTrack>).clear()

        assertEquals(listOf(track), model.animationTracks)
        assertEquals(model, equal)
        assertEquals(hashCode, model.hashCode())
        assertEquals(listOf(track), model.animationTracksForCore())
    }

    @Test
    fun shaderPairValidatesSourcesButDoesNotScanProfiles() {
        ShaderPair("not a version directive", "also not a version directive")

        assertFailsWith<IllegalArgumentException> { ShaderPair("", "fragment") }
        assertFailsWith<IllegalArgumentException> { ShaderPair("vertex", "") }
        assertFailsWith<IllegalArgumentException> { ShaderPair("\uD800", "fragment") }
        assertFailsWith<IllegalArgumentException> { ShaderPair("vertex", "\uDFFF") }
    }

    @Test
    fun shaderPairToStringRedactsBothSources() {
        val vertexSource = "vertex-secret"
        val fragmentSource = "fragment-secret"
        val pair = ShaderPair(vertexSource, fragmentSource)

        assertFalse(pair.toString().contains(vertexSource))
        assertFalse(pair.toString().contains(fragmentSource))
    }

    @Test
    fun geometryRequiresStrictNorthWestBoundsAndAtMostOneWorldSpan() {
        val shaderPair = ShaderPair("vertex", "fragment")
        Geometry(Vector3(90.0, -180.0, 0.0), Vector3(-90.0, 180.0, 0.0), shaderPair)

        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(0.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(1.0, 180.0, 0.0), Vector3(0.0, -180.0, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 360.0001, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(90.0001, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), shaderPair)
        }
        assertFailsWith<IllegalArgumentException> {
            Geometry(Vector3(0.0, 0.0, 0.0), Vector3(-90.0001, 1.0, 0.0), shaderPair)
        }
    }

    @Test
    fun geometryToStringRedactsShaderSourcesTransitively() {
        val vertexSource = "vertex-secret"
        val fragmentSource = "fragment-secret"
        val geometry = Geometry(
            Vector3(1.0, 2.0, 3.0),
            Vector3(0.0, 3.0, 4.0),
            ShaderPair(vertexSource, fragmentSource),
        )

        assertFalse(geometry.toString().contains(vertexSource))
        assertFalse(geometry.toString().contains(fragmentSource))
    }

    private fun assertModelListIsIndependent(tracks: List<AnimationTrack>) {
        val input = ArrayList(tracks)
        val model = Model(screenPlacement(), ResourceLocator("model"), animationTracks = input)

        input.clear()
        val first = model.animationTracks
        val returned = first as MutableList<AnimationTrack>
        returned.clear()
        val second = model.animationTracks

        assertEquals(tracks, second)
        assertNotSame(first, second)
        assertEquals(tracks, model.animationTracksForCore())
    }

    private fun screenPlacement(): Placement =
        Placement(
            AnchoringMode.SCREEN,
            Vector3(0.0, 0.0, 0.0),
            AnchoringMode.SCREEN,
            Vector3(0.0, 0.0, 0.0),
            AnchoringMode.SCREEN,
            1.0,
        )
}
