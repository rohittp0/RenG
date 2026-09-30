package com.rohittp.reng.internal.basemap

import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/** The memo that keeps an unchanged style from being parsed again on every prepare. */
class StyleManifestMemoTest {

    private var derivations = 0

    private fun counting(): StyleManifestMemo = StyleManifestMemo { bytes, baseUri ->
        derivations += 1
        deriveBasemapStyleManifest(bytes, baseUri)
    }

    private fun style(name: String, digest: String = "digest-$name"): StoredRawResource =
        StoredRawResource(
            bytes = """{"version":8,"name":"$name","sources":{},"layers":[]}""".encodeToByteArray(),
            contentDigest = digest,
            metadata = StoredRawResourceMetadata(storedAtEpochMillis = 0L),
        )

    @Test
    fun manySameStylePreparesParseOnceAndASecondStyleParsesAgain() {
        val memo = counting()
        val first = style("a")
        val results = (1..20).map { memo.manifestFor(style("a"), BASE) }
        assertEquals(1, derivations)
        results.forEach { assertSame(results.first(), it) }

        memo.manifestFor(first, BASE)
        assertEquals(1, derivations)

        memo.manifestFor(style("b"), BASE)
        assertEquals(2, derivations)
    }

    @Test
    fun theSameBytesUnderAnotherBaseUriAreAnotherManifest() {
        val memo = counting()
        val one = memo.manifestFor(style("a"), BASE)
        val other = memo.manifestFor(style("a"), "https://other.example/style.json")
        assertEquals(2, derivations)
        assertNotSame(one, other)
    }

    @Test
    fun aRejectedStyleFailsEveryTimeAndIsNeverRemembered() {
        val memo = counting()
        val bad = StoredRawResource(
            bytes = "{ not json".encodeToByteArray(),
            contentDigest = "digest-bad",
            metadata = StoredRawResourceMetadata(storedAtEpochMillis = 0L),
        )
        repeat(3) { assertIs<BasemapStyleManifestOutcome.Rejected>(memo.manifestFor(bad, BASE)) }
        assertEquals(3, derivations)
    }

    @Test
    fun theOldestStyleIsForgottenOnceTheBoundIsSpent() {
        val memo = StyleManifestMemo(capacity = 2) { bytes, baseUri ->
            derivations += 1
            deriveBasemapStyleManifest(bytes, baseUri)
        }
        memo.manifestFor(style("a"), BASE)
        memo.manifestFor(style("b"), BASE)
        memo.manifestFor(style("c"), BASE)
        assertEquals(3, derivations)
        memo.manifestFor(style("c"), BASE)
        memo.manifestFor(style("b"), BASE)
        assertEquals(3, derivations)
        memo.manifestFor(style("a"), BASE)
        assertEquals(4, derivations)
    }

    @Test
    fun concurrentCallersAllSeeOneManifest() = runBlocking {
        val memo = StyleManifestMemo(capacity = 2) { bytes, baseUri -> deriveBasemapStyleManifest(bytes, baseUri) }
        val results = (1..8).map {
            async(Dispatchers.Default) {
                (1..200).map { round -> memo.manifestFor(style("s${round % 5}"), BASE) }
            }
        }.awaitAll().flatten()
        results.forEach { assertIs<BasemapStyleManifestOutcome.Derived>(it) }
    }

    private companion object {
        const val BASE = "https://example.test/style.json"
    }
}
