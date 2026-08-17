package com.rohittp.reng

import com.rohittp.reng.internal.acceptValue
import com.rohittp.reng.internal.maximumBytesFor
import com.rohittp.reng.internal.reportOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ResourcesTest {
    @Test
    fun resourceLocatorRejectsBlankAndIsolatedSurrogateText() {
        listOf("", " \t\n", "\uD800", "prefix\uDC00suffix").forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { ResourceLocator(invalid) }
        }
    }

    @Test
    fun resourceLocatorUsesExactTextForEqualityAndRedactsItFromText() {
        val first = ResourceLocator("resource?credential=alpha")
        val equal = ResourceLocator("resource?credential=alpha")
        val different = ResourceLocator("resource?credential=beta")

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertFalse(first == different)
        assertEquals("ResourceLocator(<redacted>)", first.toString())
    }

    @Test
    fun resourceLocatorFailureMessageDoesNotExposeItsText() {
        val secret = "resource?credential=alpha"
        val exception = assertFailsWith<IllegalArgumentException> {
            ResourceLocator("$secret\uD800")
        }

        assertFalse(exception.message.orEmpty().contains(secret))
    }

    @Test
    fun resourceEnumsContainEveryClosedMemberInContractOrder() {
        assertEquals(
            listOf(
                ResourceClass.BASEMAP_STYLE,
                ResourceClass.BASEMAP_TILE_JSON,
                ResourceClass.BASEMAP_VECTOR_TILE,
                ResourceClass.BASEMAP_RASTER_TILE,
                ResourceClass.BASEMAP_DEM_TILE,
                ResourceClass.BASEMAP_SPRITE_JSON,
                ResourceClass.BASEMAP_SPRITE_IMAGE,
                ResourceClass.BASEMAP_GEO_JSON,
                ResourceClass.STICKER_IMAGE,
                ResourceClass.MODEL_GLB,
                ResourceClass.MODEL_TEXTURE,
            ),
            ResourceClass.entries,
        )
        assertEquals(
            listOf(
                ResourceKind.EXTERNAL,
                ResourceKind.GEOMETRY_PROGRAM,
                ResourceKind.INTERNAL_PIPELINE,
                ResourceKind.OFFSCREEN_SURFACE,
            ),
            ResourceKind.entries,
        )
        assertEquals(
            listOf(
                ResourceAccessMode.NORMAL,
                ResourceAccessMode.CACHE_ONLY,
                ResourceAccessMode.RELOAD,
            ),
            ResourceAccessMode.entries,
        )
    }

    @Test
    fun everyResourceLimitAcceptsBothInclusiveBounds() {
        LimitField.entries.forEach { field ->
            assertEquals(1L, limitsWith(field, 1L).valueFor(field))
            assertEquals(Int.MAX_VALUE.toLong(), limitsWith(field, Int.MAX_VALUE.toLong()).valueFor(field))
        }
    }

    @Test
    fun everyResourceLimitRejectsValuesOutsideInclusiveBounds() {
        LimitField.entries.forEach { field ->
            assertFailsWith<IllegalArgumentException> { limitsWith(field, 0L) }
            assertFailsWith<IllegalArgumentException> {
                limitsWith(field, Int.MAX_VALUE.toLong() + 1L)
            }
        }
    }

    @Test
    fun resourceClassesMapToTheirLimitAcceptValuesAndReportOrder() {
        val limits = ResourceLimits()
        val expected = listOf(
            ResourceClass.BASEMAP_STYLE to Pair(8L * mib, "application/json"),
            ResourceClass.BASEMAP_TILE_JSON to Pair(4L * mib, "application/json"),
            ResourceClass.BASEMAP_VECTOR_TILE to Pair(32L * mib, "application/vnd.mapbox-vector-tile"),
            ResourceClass.BASEMAP_RASTER_TILE to Pair(32L * mib, "image/png"),
            ResourceClass.BASEMAP_DEM_TILE to Pair(32L * mib, "image/png"),
            ResourceClass.BASEMAP_SPRITE_JSON to Pair(4L * mib, "application/json"),
            ResourceClass.BASEMAP_SPRITE_IMAGE to Pair(32L * mib, "image/png"),
            ResourceClass.BASEMAP_GEO_JSON to Pair(64L * mib, "application/json"),
            ResourceClass.STICKER_IMAGE to Pair(32L * mib, "image/png"),
            ResourceClass.MODEL_GLB to Pair(256L * mib, "model/gltf-binary"),
            ResourceClass.MODEL_TEXTURE to Pair(32L * mib, "image/png"),
        )

        expected.forEachIndexed { index, (resourceClass, expectation) ->
            assertEquals(expectation.first, limits.maximumBytesFor(resourceClass))
            assertEquals(expectation.second, resourceClass.acceptValue)
            assertEquals(index, resourceClass.reportOrder)
        }
        ResourceKind.entries.forEachIndexed { index, resourceKind ->
            assertEquals(index, resourceKind.reportOrder)
        }
    }

    private fun limitsWith(field: LimitField, value: Long): ResourceLimits =
        when (field) {
            LimitField.BASEMAP_STYLE -> ResourceLimits(maximumBasemapStyleBytes = value)
            LimitField.BASEMAP_METADATA -> ResourceLimits(maximumBasemapMetadataBytes = value)
            LimitField.BASEMAP_TILE -> ResourceLimits(maximumBasemapTileBytes = value)
            LimitField.BASEMAP_SPRITE_IMAGE -> ResourceLimits(maximumBasemapSpriteImageBytes = value)
            LimitField.BASEMAP_GEO_JSON -> ResourceLimits(maximumBasemapGeoJsonBytes = value)
            LimitField.STICKER_IMAGE -> ResourceLimits(maximumStickerImageBytes = value)
            LimitField.MODEL_GLB -> ResourceLimits(maximumModelGlbBytes = value)
            LimitField.MODEL_TEXTURE -> ResourceLimits(maximumModelTextureBytes = value)
        }

    private fun ResourceLimits.valueFor(field: LimitField): Long =
        when (field) {
            LimitField.BASEMAP_STYLE -> maximumBasemapStyleBytes
            LimitField.BASEMAP_METADATA -> maximumBasemapMetadataBytes
            LimitField.BASEMAP_TILE -> maximumBasemapTileBytes
            LimitField.BASEMAP_SPRITE_IMAGE -> maximumBasemapSpriteImageBytes
            LimitField.BASEMAP_GEO_JSON -> maximumBasemapGeoJsonBytes
            LimitField.STICKER_IMAGE -> maximumStickerImageBytes
            LimitField.MODEL_GLB -> maximumModelGlbBytes
            LimitField.MODEL_TEXTURE -> maximumModelTextureBytes
        }

    private enum class LimitField {
        BASEMAP_STYLE,
        BASEMAP_METADATA,
        BASEMAP_TILE,
        BASEMAP_SPRITE_IMAGE,
        BASEMAP_GEO_JSON,
        STICKER_IMAGE,
        MODEL_GLB,
        MODEL_TEXTURE,
    }

    private companion object {
        const val mib: Long = 1024L * 1024L
    }
}
