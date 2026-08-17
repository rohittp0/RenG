package com.rohittp.reng

import com.rohittp.reng.internal.requireUnicodeScalars

public enum class AnchoringMode {
    MAP,
    SCREEN,
}

public class ResourceLocator(value: String) {
    public val value: String

    init {
        val validatedValue = requireUnicodeScalars(value, "resourceLocator", nonBlank = true)
        this.value = validatedValue
    }

    override fun equals(other: Any?): Boolean =
        other is ResourceLocator && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "ResourceLocator(<redacted>)"
}

public data class OutputPixelSize(
    public val width: Int,
    public val height: Int,
) {
    init {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(width.toLong() * height.toLong() <= Int.MAX_VALUE.toLong()) {
            "output pixel count exceeds the maximum"
        }
    }
}

public enum class ResourceClass {
    BASEMAP_STYLE,
    BASEMAP_TILE_JSON,
    BASEMAP_VECTOR_TILE,
    BASEMAP_RASTER_TILE,
    BASEMAP_DEM_TILE,
    BASEMAP_SPRITE_JSON,
    BASEMAP_SPRITE_IMAGE,
    BASEMAP_GEO_JSON,
    STICKER_IMAGE,
    MODEL_GLB,
    MODEL_TEXTURE,
}

public enum class ResourceKind {
    EXTERNAL,
    GEOMETRY_PROGRAM,
    INTERNAL_PIPELINE,
    OFFSCREEN_SURFACE,
}

public enum class ResourceAccessMode {
    NORMAL,
    CACHE_ONLY,
    RELOAD,
}

public data class ResourceLimits(
    public val maximumBasemapStyleBytes: Long = 8L * 1024L * 1024L,
    public val maximumBasemapMetadataBytes: Long = 4L * 1024L * 1024L,
    public val maximumBasemapTileBytes: Long = 32L * 1024L * 1024L,
    public val maximumBasemapSpriteImageBytes: Long = 32L * 1024L * 1024L,
    public val maximumBasemapGeoJsonBytes: Long = 64L * 1024L * 1024L,
    public val maximumStickerImageBytes: Long = 32L * 1024L * 1024L,
    public val maximumModelGlbBytes: Long = 256L * 1024L * 1024L,
    public val maximumModelTextureBytes: Long = 32L * 1024L * 1024L,
) {
    init {
        val minimum = 1L
        val maximum = Int.MAX_VALUE.toLong()
        require(maximumBasemapStyleBytes in minimum..maximum) {
            "maximumBasemapStyleBytes must be within the supported range"
        }
        require(maximumBasemapMetadataBytes in minimum..maximum) {
            "maximumBasemapMetadataBytes must be within the supported range"
        }
        require(maximumBasemapTileBytes in minimum..maximum) {
            "maximumBasemapTileBytes must be within the supported range"
        }
        require(maximumBasemapSpriteImageBytes in minimum..maximum) {
            "maximumBasemapSpriteImageBytes must be within the supported range"
        }
        require(maximumBasemapGeoJsonBytes in minimum..maximum) {
            "maximumBasemapGeoJsonBytes must be within the supported range"
        }
        require(maximumStickerImageBytes in minimum..maximum) {
            "maximumStickerImageBytes must be within the supported range"
        }
        require(maximumModelGlbBytes in minimum..maximum) {
            "maximumModelGlbBytes must be within the supported range"
        }
        require(maximumModelTextureBytes in minimum..maximum) {
            "maximumModelTextureBytes must be within the supported range"
        }
    }
}
