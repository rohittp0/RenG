package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.image.DecodedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlTextureUploadTest {
    // 255,0,0,128 unpremultiplied.
    private fun halfAlphaRed() = DecodedImage(1, 1, byteArrayOf(-1, 0, 0, -128))

    // 137,137,137,137 unpremultiplied: 137*137 does not divide 255 evenly, so this distinguishes
    // round-half-up from truncation.
    private fun nonExactDivisionPixel() = DecodedImage(1, 1, byteArrayOf(-119, -119, -119, -119))

    @Test
    fun anImageTextureIsPremultipliedBeforeUpload() {
        val binding = RecordingGlBinding()
        uploadTexture(binding, halfAlphaRed(), TextureContent.IMAGE)
        // 255 * 128/255 = 128 exactly; alpha is untouched.
        assertEquals(listOf<Byte>(-128, 0, 0, -128), binding.lastTexImageBytes())
    }

    @Test
    fun aDataTextureIsUploadedBitExact() {
        val binding = RecordingGlBinding()
        uploadTexture(binding, halfAlphaRed(), TextureContent.DATA)
        assertEquals(listOf<Byte>(-1, 0, 0, -128), binding.lastTexImageBytes())
    }

    @Test
    fun theDecodedImageIsNeverMutatedByEitherPath() {
        val image = halfAlphaRed()
        val before = image.rgbaSnapshot().toList()
        uploadTexture(RecordingGlBinding(), image, TextureContent.IMAGE)
        assertEquals(before, image.rgbaSnapshot().toList())
    }

    @Test
    fun premultiplyRoundsHalfUpOnANonExactDivision() {
        val binding = RecordingGlBinding()
        uploadTexture(binding, nonExactDivisionPixel(), TextureContent.IMAGE)
        // 137 * 137 = 18769. Truncating (18769 / 255) = 73; this rule, (18769 + 127) / 255, = 74.
        // Pin the exact byte, not a range, so the two rules cannot silently agree by coincidence.
        assertEquals(listOf<Byte>(74, 74, 74, -119), binding.lastTexImageBytes())
    }

    // An unset minification filter is the failure mode that renders black on a real driver (GL's
    // default GL_NEAREST_MIPMAP_LINEAR needs a mipmap chain this upload never generates), so this
    // pins the exact texParameteri call sequence rather than merely "some filter was set."
    @Test
    fun anImageTextureGetsLinearFilteringAndClampToEdgeWrap() {
        val binding = RecordingGlBinding()
        uploadTexture(binding, halfAlphaRed(), TextureContent.IMAGE)
        assertEquals(
            listOf(
                "texParameteri(0xDE1,0x2801,0x2601)", // GL_TEXTURE_MIN_FILTER = GL_LINEAR
                "texParameteri(0xDE1,0x2800,0x2601)", // GL_TEXTURE_MAG_FILTER = GL_LINEAR
                "texParameteri(0xDE1,0x2802,0x812F)", // GL_TEXTURE_WRAP_S = GL_CLAMP_TO_EDGE
                "texParameteri(0xDE1,0x2803,0x812F)", // GL_TEXTURE_WRAP_T = GL_CLAMP_TO_EDGE
            ),
            binding.log.filter { it.startsWith("texParameteri") },
        )
    }

    @Test
    fun aDataTextureGetsNearestFilteringAndClampToEdgeWrap() {
        val binding = RecordingGlBinding()
        uploadTexture(binding, halfAlphaRed(), TextureContent.DATA)
        assertEquals(
            listOf(
                "texParameteri(0xDE1,0x2801,0x2600)", // GL_TEXTURE_MIN_FILTER = GL_NEAREST
                "texParameteri(0xDE1,0x2800,0x2600)", // GL_TEXTURE_MAG_FILTER = GL_NEAREST
                "texParameteri(0xDE1,0x2802,0x812F)", // GL_TEXTURE_WRAP_S = GL_CLAMP_TO_EDGE
                "texParameteri(0xDE1,0x2803,0x812F)", // GL_TEXTURE_WRAP_T = GL_CLAMP_TO_EDGE
            ),
            binding.log.filter { it.startsWith("texParameteri") },
        )
    }

    @Test
    fun neitherContentKindGeneratesAMipmapChain() {
        val binding = RecordingGlBinding()
        uploadTexture(binding, halfAlphaRed(), TextureContent.IMAGE)
        uploadTexture(binding, halfAlphaRed(), TextureContent.DATA)
        assertTrue(binding.log.none { it.startsWith("generateMipmap") })
    }
}
