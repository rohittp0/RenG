package com.rohittp.reng

import com.rohittp.reng.internal.canonicalDouble

public class Vector3(x: Double, y: Double, z: Double) {
    public val x: Double
    public val y: Double
    public val z: Double

    init {
        val canonicalX = canonicalDouble(x, "x")
        val canonicalY = canonicalDouble(y, "y")
        val canonicalZ = canonicalDouble(z, "z")
        this.x = canonicalX
        this.y = canonicalY
        this.z = canonicalZ
    }

    override fun equals(other: Any?): Boolean =
        other is Vector3 && x == other.x && y == other.y && z == other.z

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + z.hashCode()
        return result
    }

    override fun toString(): String = "Vector3(x=$x, y=$y, z=$z)"
}

public class Camera(
    latitude: Double,
    unwrappedLongitude: Double,
    zoom: Double,
    bearing: Double,
    pitch: Double,
) {
    public val latitude: Double
    public val unwrappedLongitude: Double
    public val zoom: Double
    public val bearing: Double
    public val pitch: Double

    init {
        val canonicalLatitude = canonicalDouble(latitude, "latitude")
        val canonicalLongitude = canonicalDouble(unwrappedLongitude, "unwrappedLongitude")
        val canonicalZoom = canonicalDouble(zoom, "zoom")
        val canonicalBearing = canonicalDouble(bearing, "bearing")
        val canonicalPitch = canonicalDouble(pitch, "pitch")

        require(canonicalLatitude in -90.0..90.0) { "latitude must be within the supported range" }
        require(canonicalZoom in 0.0..22.0) { "zoom must be within the supported range" }
        require(canonicalBearing >= 0.0 && canonicalBearing < 360.0) {
            "bearing must be within the supported range"
        }
        require(canonicalPitch >= 0.0 && canonicalPitch < 90.0) {
            "pitch must be within the supported range"
        }

        this.latitude = canonicalLatitude
        this.unwrappedLongitude = canonicalLongitude
        this.zoom = canonicalZoom
        this.bearing = canonicalBearing
        this.pitch = canonicalPitch
    }

    override fun equals(other: Any?): Boolean =
        other is Camera &&
            latitude == other.latitude &&
            unwrappedLongitude == other.unwrappedLongitude &&
            zoom == other.zoom &&
            bearing == other.bearing &&
            pitch == other.pitch

    override fun hashCode(): Int {
        var result = latitude.hashCode()
        result = 31 * result + unwrappedLongitude.hashCode()
        result = 31 * result + zoom.hashCode()
        result = 31 * result + bearing.hashCode()
        result = 31 * result + pitch.hashCode()
        return result
    }

    override fun toString(): String =
        "Camera(latitude=$latitude, unwrappedLongitude=$unwrappedLongitude, zoom=$zoom, " +
            "bearing=$bearing, pitch=$pitch)"
}

public class Placement(
    positionMode: AnchoringMode,
    position: Vector3,
    rotationMode: AnchoringMode,
    rotation: Vector3,
    scaleMode: AnchoringMode,
    scale: Double,
) {
    public val positionMode: AnchoringMode
    public val position: Vector3
    public val rotationMode: AnchoringMode
    public val rotation: Vector3
    public val scaleMode: AnchoringMode
    public val scale: Double

    init {
        val canonicalScale = canonicalDouble(scale, "scale")
        require(rotation.x >= -180.0 && rotation.x < 180.0) {
            "rotation.x must be within the supported range"
        }
        require(rotation.y >= -180.0 && rotation.y < 180.0) {
            "rotation.y must be within the supported range"
        }
        require(rotation.z >= -180.0 && rotation.z < 180.0) {
            "rotation.z must be within the supported range"
        }
        require(canonicalScale >= 0.0) { "scale must be non-negative" }

        this.positionMode = positionMode
        this.position = position
        this.rotationMode = rotationMode
        this.rotation = rotation
        this.scaleMode = scaleMode
        this.scale = canonicalScale
    }

    override fun equals(other: Any?): Boolean =
        other is Placement &&
            positionMode == other.positionMode &&
            position == other.position &&
            rotationMode == other.rotationMode &&
            rotation == other.rotation &&
            scaleMode == other.scaleMode &&
            scale == other.scale

    override fun hashCode(): Int {
        var result = positionMode.hashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + rotationMode.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + scaleMode.hashCode()
        result = 31 * result + scale.hashCode()
        return result
    }

    override fun toString(): String =
        "Placement(positionMode=$positionMode, position=$position, rotationMode=$rotationMode, " +
            "rotation=$rotation, scaleMode=$scaleMode, scale=$scale)"
}
