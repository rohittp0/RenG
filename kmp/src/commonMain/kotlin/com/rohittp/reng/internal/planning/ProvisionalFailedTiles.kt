package com.rohittp.reng.internal.planning

/**
 * Which of [failed] a frame may leave out and draw from a resident ancestor instead, or `null` when the
 * frame must fail (ADR 0057, "A tile that could not be downloaded").
 *
 * The answer is all of [failed] or nothing. `null` when [failed] is empty, because a failure that names no
 * tile gives the frame nothing to leave out and leaving out nothing would retry the same request; when
 * any of it is not in [requested], because a failure about a tile this frame did not ask for is not this
 * frame's to present; and when any of it has no resident ancestor, because a frame that cannot draw a
 * tile's ground is not an improvement on a frame that fails, and failing with the original failure keeps
 * a consumer's handling of it exactly what it is today.
 *
 * Pure, and separate from the renderer, so the rule can be read and tested without a GL context or an
 * engine; [hasResidentAncestor] is the one question it asks of the renderer's state.
 */
internal fun presentableFailedTiles(
    failed: Set<CanonicalBasemapTile>,
    requested: Collection<CanonicalBasemapTile>,
    hasResidentAncestor: (CanonicalBasemapTile) -> Boolean,
): Set<CanonicalBasemapTile>? {
    if (failed.isEmpty()) return null
    if (!requested.toHashSet().containsAll(failed)) return null
    if (!failed.all(hasResidentAncestor)) return null
    return failed
}
