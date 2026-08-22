package com.rohittp.reng.internal.planning

import com.rohittp.reng.internal.projection.ResolvedMercatorCamera

/**
 * One basemap tile's ground footprint, in camera-relative logical pixels: an axis-aligned square in
 * the map plane (`z = 0`), centred on [centreXLogicalPixels] / [centreYLogicalPixels], with side
 * [sideLogicalPixels]. `+x` is east and **`+y` is north**, matching
 * [resolveCameraRelativeMapPosition]'s own convention — Mercator `y` grows southward, so the sign is
 * flipped exactly once, here, and never again downstream.
 *
 * A square in both axes because a Mercator tile *is* square in Mercator space: one tile spans
 * `1 / 2^lod` in each axis, and both axes scale by the same
 * [ResolvedMercatorCamera.worldSizeLogicalPixels].
 */
internal data class BasemapTileQuad(
    val centreXLogicalPixels: Double,
    val centreYLogicalPixels: Double,
    val sideLogicalPixels: Double,
)

/**
 * Places one unwrapped draw [instance] on the ground relative to [camera].
 *
 * **[BasemapTileInstance.unwrappedX], not [BasemapTileInstance.canonicalX].** This is the one place
 * in the whole basemap path where the Mercator world copy still matters:
 * `internal.firewall.basemapTileKey`'s instance overload deliberately projects the copy away so N
 * visible copies of one tile share one acquisition, one engine render and one GL texture, and
 * placement is precisely the concern that must put those copies in N different places. Reading
 * `canonicalX` here would stack every world copy on top of the first one.
 *
 * **The subtraction happens in tile units, not in Mercator units.** `(unwrappedX + 0.5) -
 * anchorX * tileCount` keeps both operands at the same small magnitude near the camera, where
 * `(mercatorCentreX - anchorX) * worldSize` would difference two numbers whose magnitude is set by
 * the world-copy index (up to 16 384) before scaling up by a world size that reaches 2^31 logical
 * pixels at LOD 22. Both forms are correct; this one loses about four orders of magnitude less.
 *
 * **This cannot fail, so it returns no [SpatialOutcome].** Every instance reaching here came from
 * [selectBasemapTiles] over this same camera's own clipped ground footprint, so its centre is
 * within one tile of the visible ground and its coordinates are bounded by the output size — never
 * the unbounded caller-supplied values [resolveCameraRelativeMapPosition] has to guard against with
 * [isGpuRepresentable].
 */
internal fun resolveBasemapTileQuad(
    instance: BasemapTileInstance,
    camera: ResolvedMercatorCamera,
): BasemapTileQuad {
    val tileCount = (1L shl instance.lod).toDouble()
    val side = camera.worldSizeLogicalPixels / tileCount
    val anchorTileX = camera.mercatorAnchor.x * tileCount
    val anchorTileY = camera.mercatorAnchor.y * tileCount
    return BasemapTileQuad(
        centreXLogicalPixels = (instance.unwrappedX.toDouble() + HALF_TILE - anchorTileX) * side,
        centreYLogicalPixels = (anchorTileY - (instance.tileY.toDouble() + HALF_TILE)) * side,
        sideLogicalPixels = side,
    )
}

private const val HALF_TILE: Double = 0.5
