package com.rohittp.reng.internal.planning

import com.rohittp.reng.internal.projection.ResolvedMercatorCamera

/**
 * One basemap instance's ground footprint, in camera-relative logical pixels: an axis-aligned
 * rectangle in the map plane (`z = 0`), centred on [centreXLogicalPixels] /
 * [centreYLogicalPixels], with width [sideLogicalPixels] and height [heightLogicalPixels]. `+x` is
 * east and **`+y` is north**, matching
 * [resolveCameraRelativeMapPosition]'s own convention — Mercator `y` grows southward, so the sign is
 * flipped exactly once, here, and never again downstream.
 *
 * An ordinary tile is square because it spans `1 / 2^lod` in each Mercator axis. A [GroundCap]
 * keeps that width and extends [BasemapTileInstance.capSpanTiles] tile heights away from the world
 * edge.
 */
internal data class BasemapTileQuad(
    val centreXLogicalPixels: Double,
    val centreYLogicalPixels: Double,
    val sideLogicalPixels: Double,
    val heightLogicalPixels: Double = sideLogicalPixels,
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
    val height = side * instance.capSpanTiles
    val centreY = when (instance.cap) {
        GroundCap.NORTH -> (anchorTileY + instance.capSpanTiles * HALF_TILE) * side
        GroundCap.SOUTH -> (anchorTileY - tileCount - instance.capSpanTiles * HALF_TILE) * side
        null -> (anchorTileY - (instance.tileY.toDouble() + HALF_TILE)) * side
    }
    return BasemapTileQuad(
        centreXLogicalPixels = (instance.unwrappedX.toDouble() + HALF_TILE - anchorTileX) * side,
        centreYLogicalPixels = centreY,
        sideLogicalPixels = side,
        heightLogicalPixels = height,
    )
}

private const val HALF_TILE: Double = 0.5
