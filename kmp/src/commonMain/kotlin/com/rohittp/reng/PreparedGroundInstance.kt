package com.rohittp.reng

import com.rohittp.reng.internal.planning.BasemapTileInstance

/**
 * One unwrapped basemap tile draw instance, already paired at `prepare()` time with the
 * [ResourceKey] naming the rendered tile it draws.
 *
 * The pairing is derived here, once per frame, rather than at draw time, for the same reason
 * [PreparedSticker] carries its own key: identity derivation (ADR 0018) is `prepare()`'s job, it
 * costs a SHA-256 per **canonical** tile rather than per instance when memoized as it is below, and
 * doing it here lets one `check` prove every instance names a tile this frame actually rendered
 * instead of leaving a lookup miss to be discovered inside a GL draw call.
 *
 * N world copies of one canonical tile produce N entries sharing one [resourceKey] -- that is
 * exactly what `basemapTileKey`'s instance overload is for.
 */
internal class PreparedGroundInstance(
    internal val instance: BasemapTileInstance,
    internal val resourceKey: ResourceKey,
)
