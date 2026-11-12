package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.image.RawRgbaContainerResult
import com.rohittp.reng.internal.image.RawRgbaImage
import com.rohittp.reng.internal.image.parseRawRgbaContainer
import com.rohittp.reng.internal.image.rawRgbaContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR 0092: a raw RGBA sticker uploads exactly what a PNG of the same pixels uploads. */
class GlRawRgbaTextureUploadTest {
    // Every alpha against a spread of colours, so rounding differences cannot hide.
    private val width = 37
    private val height = 7
    private val straight = ByteArray(width * height * 4) { index ->
        if (index % 4 == 3) (index / 4 * 7).toByte() else (index * 31 + 5).toByte()
    }

    private fun raw(bytes: ByteArray, premultiplied: Boolean): RawRgbaImage =
        (parseRawRgbaContainer(rawRgbaContainer(width, height, bytes, premultiplied), Long.MAX_VALUE)
            as RawRgbaContainerResult.Admitted).image

    private fun pngUpload(): RecordingGlBinding =
        RecordingGlBinding().also { uploadTexture(it, DecodedImage(width, height, straight), TextureContent.IMAGE) }

    @Test
    fun straightTexelsArePremultipliedAsAPngsAre() {
        val png = pngUpload()
        val binding = RecordingGlBinding()
        uploadRawRgbaTexture(binding, raw(straight, premultiplied = false))
        assertEquals(png.lastTexImageBytes(), binding.lastTexImageBytes())
    }

    @Test
    fun premultipliedTexelsAreCopiedAsTheyAre() {
        val png = pngUpload()
        // The producer's premultiply is the PNG path's own result, so the two must match byte for byte.
        val premultiplied = png.lastTexImageBytes().toByteArray()
        val binding = RecordingGlBinding()
        uploadRawRgbaTexture(binding, raw(premultiplied, premultiplied = true))
        assertEquals(png.lastTexImageBytes(), binding.lastTexImageBytes())
    }

    @Test
    fun itIsOneLinearClampedLevelWithNoChain() {
        val binding = RecordingGlBinding()
        uploadRawRgbaTexture(binding, raw(straight, premultiplied = false))
        assertEquals(
            listOf(
                "texParameteri(0xDE1,0x2801,0x2601)",
                "texParameteri(0xDE1,0x2800,0x2601)",
                "texParameteri(0xDE1,0x2802,0x812F)",
                "texParameteri(0xDE1,0x2803,0x812F)",
            ),
            binding.log.filter { it.startsWith("texParameteri") },
        )
        assertTrue(binding.log.none { it.startsWith("generateMipmap") })
        assertEquals(listOf("texImage2D(0xDE1,0,0x8058,37,7,0,0x1908,0x1401)"), binding.log.filter { it.startsWith("texImage2D") })
    }

    @Test
    fun aWideRowIsUploadedInBoundedChunks() {
        val wide = 300_000
        val bytes = ByteArray(wide * 4) { 9 }
        val image = (parseRawRgbaContainer(rawRgbaContainer(wide, 1, bytes, premultiplied = true), Long.MAX_VALUE)
            as RawRgbaContainerResult.Admitted).image
        val binding = RecordingGlBinding()
        uploadRawRgbaTexture(binding, image)
        assertEquals(2, binding.texSubImage2DPayloads.size)
        assertTrue(binding.texSubImage2DPayloads.all { it.size <= 1024 * 1024 })
        assertEquals(bytes.toList(), binding.lastTexImageBytes())
    }
}
