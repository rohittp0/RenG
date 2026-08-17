package com.rohittp.reng.internal.diff

import com.rohittp.reng.ResourceKey

internal class ResourceTraversalDiff(
    retain: List<ResourceKey>,
    acquire: List<ResourceKey>,
    release: List<ResourceKey>,
) {
    private val retainSnapshot: List<ResourceKey> = ArrayList(retain)
    private val acquireSnapshot: List<ResourceKey> = ArrayList(acquire)
    private val releaseSnapshot: List<ResourceKey> = ArrayList(release)

    val retain: List<ResourceKey>
        get() = ArrayList(retainSnapshot)

    val acquire: List<ResourceKey>
        get() = ArrayList(acquireSnapshot)

    val release: List<ResourceKey>
        get() = ArrayList(releaseSnapshot)

    override fun equals(other: Any?): Boolean =
        other is ResourceTraversalDiff &&
            retainSnapshot == other.retainSnapshot &&
            acquireSnapshot == other.acquireSnapshot &&
            releaseSnapshot == other.releaseSnapshot

    override fun hashCode(): Int {
        var result = retainSnapshot.hashCode()
        result = 31 * result + acquireSnapshot.hashCode()
        result = 31 * result + releaseSnapshot.hashCode()
        return result
    }
}

internal object ResourceTraversalDiffer {
    internal fun diff(
        previous: List<ResourceKey>,
        current: List<ResourceKey>,
    ): ResourceTraversalDiff {
        val previousUnique = deduplicateAtFirstOccurrence(previous.toList())
        val currentUnique = deduplicateAtFirstOccurrence(current.toList())
        val previousSet = previousUnique.toSet()
        val currentSet = currentUnique.toSet()

        return ResourceTraversalDiff(
            retain = currentUnique.filter { it in previousSet },
            acquire = currentUnique.filter { it !in previousSet },
            release = previousUnique.filter { it !in currentSet },
        )
    }

    private fun deduplicateAtFirstOccurrence(values: List<ResourceKey>): List<ResourceKey> {
        val seen = mutableSetOf<ResourceKey>()
        val result = ArrayList<ResourceKey>(values.size)
        values.forEach { key ->
            if (seen.add(key)) {
                result += key
            }
        }
        return result
    }
}
