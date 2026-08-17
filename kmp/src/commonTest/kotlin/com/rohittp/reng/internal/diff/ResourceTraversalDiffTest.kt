package com.rohittp.reng.internal.diff

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ResourceTraversalDiffTest {
    @Test
    fun traversalDiffDeduplicatesAtFirstOccurrenceAndPreservesRequiredOrders() {
        val a = key('a')
        val b = key('b')
        val c = key('c')
        val d = key('d')

        val diff = ResourceTraversalDiffer.diff(
            previous = listOf(a, b, a, c),
            current = listOf(c, d, c, b),
        )

        assertEquals(listOf(c, b), diff.retain)
        assertEquals(listOf(d), diff.acquire)
        assertEquals(listOf(a), diff.release)
    }

    @Test
    fun emptyTraversalsProduceDeterministicAcquireAndReleaseLists() {
        val a = key('a')
        val b = key('b')

        assertEquals(
            ResourceTraversalDiff(retain = emptyList(), acquire = listOf(a, b), release = emptyList()),
            ResourceTraversalDiffer.diff(previous = emptyList(), current = listOf(a, b, a)),
        )
        assertEquals(
            ResourceTraversalDiff(retain = emptyList(), acquire = emptyList(), release = listOf(a, b)),
            ResourceTraversalDiffer.diff(previous = listOf(a, b, a), current = emptyList()),
        )
    }

    @Test
    fun traversalDiffSnapshotsEveryListAndUsesStructuralValueSemantics() {
        val a = key('a')
        val b = key('b')
        val c = key('c')
        val retain = mutableListOf(a)
        val acquire = mutableListOf(b)
        val release = mutableListOf(c)
        val first = ResourceTraversalDiff(retain, acquire, release)
        val equal = ResourceTraversalDiff(listOf(a), listOf(b), listOf(c))
        val different = ResourceTraversalDiff(emptyList(), listOf(b), listOf(c))

        retain.clear()
        acquire.clear()
        release.clear()
        (first.retain as MutableList<ResourceKey>).clear()
        (first.acquire as MutableList<ResourceKey>).clear()
        (first.release as MutableList<ResourceKey>).clear()

        assertEquals(equal, first)
        assertEquals(equal.hashCode(), first.hashCode())
        assertNotEquals(different, first)
        assertEquals(listOf(a), first.retain)
        assertEquals(listOf(b), first.acquire)
        assertEquals(listOf(c), first.release)
        assertFalse(first.retain === first.retain)
        assertFalse(first.acquire === first.acquire)
        assertFalse(first.release === first.release)
    }

    private fun key(digit: Char): ResourceKey = ResourceKey(
        kind = ResourceKind.EXTERNAL,
        stableId = digit.toString().repeat(64),
        resourceClass = ResourceClass.STICKER_IMAGE,
    )
}
