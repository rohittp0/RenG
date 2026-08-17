package com.rohittp.reng.internal.planning

import com.rohittp.reng.Geometry
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Vector3
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.math.DoubleVector3
import com.rohittp.reng.internal.projection.GeographicPosition
import com.rohittp.reng.internal.projection.ResolvedMercatorCamera
import com.rohittp.reng.internal.projection.validateMercatorGeometryPosition

internal class ResolvedGeometry(
    cornersClockwiseFromTopLeft: List<DoubleVector3>,
    val shaderPair: ShaderPair,
) {
    private val cornerSnapshot: ArrayList<DoubleVector3> = ArrayList(cornersClockwiseFromTopLeft)

    val cornersClockwiseFromTopLeft: List<DoubleVector3>
        get() = ArrayList(cornerSnapshot)

    init {
        require(cornerSnapshot.size == CORNER_COUNT) { "resolved geometry requires exactly four corners" }
    }

    override fun equals(other: Any?): Boolean =
        other is ResolvedGeometry &&
            cornerSnapshot == other.cornerSnapshot &&
            shaderPair == other.shaderPair

    override fun hashCode(): Int = 31 * cornerSnapshot.hashCode() + shaderPair.hashCode()

    private companion object {
        const val CORNER_COUNT: Int = 4
    }
}

internal fun resolveGeometry(
    geometry: Geometry,
    camera: ResolvedMercatorCamera,
): SpatialOutcome<ResolvedGeometry> {
    val geographicCorners = listOf(
        geometry.topLeft,
        Vector3(geometry.topLeft.x, geometry.bottomRight.y, geometry.topLeft.z),
        geometry.bottomRight,
        Vector3(geometry.bottomRight.x, geometry.topLeft.y, geometry.bottomRight.z),
    )
    val resolvedCorners = ArrayList<DoubleVector3>(geographicCorners.size)

    for (corner in geographicCorners) {
        val projectedOutcome = validateMercatorGeometryPosition(
            GeographicPosition(
                latitude = corner.x,
                unwrappedLongitude = corner.y,
                altitudeMetres = corner.z,
            ),
        )
        if (projectedOutcome is SpatialOutcome.Failure) return projectedOutcome
        val projected = (projectedOutcome as SpatialOutcome.Success).value
        val logicalOutcome = resolveCameraRelativeMapPosition(
            projected = projected,
            camera = camera,
            latitudeField = DiagnosticField.GEOMETRY_LATITUDE,
            longitudeField = DiagnosticField.GEOMETRY_UNWRAPPED_LONGITUDE,
            altitudeField = DiagnosticField.GEOMETRY_ALTITUDE,
        )
        if (logicalOutcome is SpatialOutcome.Failure) return logicalOutcome
        resolvedCorners += (logicalOutcome as SpatialOutcome.Success).value
    }

    return SpatialOutcome.Success(
        ResolvedGeometry(
            cornersClockwiseFromTopLeft = resolvedCorners,
            shaderPair = geometry.shaderPair,
        ),
    )
}
