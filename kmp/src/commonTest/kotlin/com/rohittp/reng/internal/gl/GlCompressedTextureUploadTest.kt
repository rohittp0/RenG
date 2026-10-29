package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.image.CompressedImage
import com.rohittp.reng.internal.image.Etc2ContainerResult
import com.rohittp.reng.internal.image.etc2Block
import com.rohittp.reng.internal.image.etc2Container
import com.rohittp.reng.internal.image.parseEtc2Container
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ADR 0084: an ETC2 sticker's chain reaches the GPU as blocks, or decoded, and never generated. */
class GlCompressedTextureUploadTest {
    /** A flat individual-mode colour block: every texel `nibble * 17 + 2` on all three channels. */
    private fun flatBlock(nibble: Int): ByteArray = etc2Block(
        Triple(63, 60, nibble), Triple(59, 56, nibble), Triple(55, 52, nibble),
        Triple(51, 48, nibble), Triple(47, 44, nibble), Triple(43, 40, nibble),
    )

    private fun compressed(bytes: ByteArray): CompressedImage =
        (parseEtc2Container(bytes, Long.MAX_VALUE) as Etc2ContainerResult.Admitted).image

    // 6x5 with three levels, a different flat colour per level so each upload can be told apart.
    private val chain = etc2Container(formatCode = 1, width = 6, height = 5, levelCount = 3) { flatBlock(it + 1) }

    private fun RecordingGlBinding.calls(prefix: String): List<String> = log.filter { it.startsWith(prefix) }

    @Test
    fun aNativeUploadHandsEveryLevelItsOwnBlocksAndGeneratesNothing() {
        val binding = RecordingGlBinding()
        uploadCompressedTexture(binding, compressed(chain), native = true)
        assertEquals(
            listOf(
                "compressedTexImage2D(0xDE1,0,0x9274,6,5,0,32)",
                "compressedTexImage2D(0xDE1,1,0x9274,3,2,0,8)",
                "compressedTexImage2D(0xDE1,2,0x9274,1,1,0,8)",
            ),
            binding.calls("compressedTexImage2D"),
        )
        assertContentEquals(chain.copyOfRange(16, 48), binding.compressedTexImage2DPayloads[0])
        assertContentEquals(flatBlock(2), binding.compressedTexImage2DPayloads[1])
        assertContentEquals(flatBlock(3), binding.compressedTexImage2DPayloads[2])
        assertEquals(
            listOf(
                "texParameteri(0xDE1,0x813D,0x2)", // GL_TEXTURE_MAX_LEVEL = the container's last level
                "texParameteri(0xDE1,0x2801,0x2703)", // GL_TEXTURE_MIN_FILTER = GL_LINEAR_MIPMAP_LINEAR
                "texParameteri(0xDE1,0x2800,0x2601)", // GL_TEXTURE_MAG_FILTER = GL_LINEAR
                "texParameteri(0xDE1,0x2802,0x812F)", // GL_TEXTURE_WRAP_S = GL_CLAMP_TO_EDGE
                "texParameteri(0xDE1,0x2803,0x812F)", // GL_TEXTURE_WRAP_T = GL_CLAMP_TO_EDGE
            ),
            binding.calls("texParameteri"),
        )
        assertEquals(emptyList(), binding.calls("generateMipmap"))
        assertEquals(emptyList(), binding.calls("texImage2D") + binding.calls("texSubImage2D") + binding.calls("texStorage2D"))
    }

    @Test
    fun aSingleLevelSamplesLinearlyWithoutAChain() {
        val binding = RecordingGlBinding()
        uploadCompressedTexture(binding, compressed(etc2Container(1, 6, 5) { flatBlock(1) }), native = true)
        assertEquals("texParameteri(0xDE1,0x813D,0x0)", binding.calls("texParameteri")[0])
        assertEquals("texParameteri(0xDE1,0x2801,0x2601)", binding.calls("texParameteri")[1])
        assertEquals(emptyList(), binding.calls("generateMipmap"))
    }

    @Test
    fun eachFormatUploadsUnderItsOwnInternalFormat() {
        listOf(2 to "0x9276", 3 to "0x9278").forEach { (code, token) ->
            val binding = RecordingGlBinding()
            val blockBytes = if (code == 3) 16 else 8
            uploadCompressedTexture(binding, compressed(etc2Container(code, 4, 4) { ByteArray(blockBytes) }), true)
            assertEquals(
                listOf("compressedTexImage2D(0xDE1,0,$token,4,4,0,$blockBytes)"),
                binding.calls("compressedTexImage2D"),
            )
        }
    }

    @Test
    fun aDecodedUploadFillsImmutableStorageLevelByLevelAndGeneratesNothing() {
        val binding = RecordingGlBinding()
        uploadCompressedTexture(binding, compressed(chain), native = false)
        assertEquals(listOf("texStorage2D(0xDE1,3,0x8058,6,5)"), binding.calls("texStorage2D"))
        assertEquals(
            listOf(
                "texSubImage2D(0xDE1,0,0,0,6,5,0x1908,0x1401)",
                "texSubImage2D(0xDE1,1,0,0,3,2,0x1908,0x1401)",
                "texSubImage2D(0xDE1,2,0,0,1,1,0x1908,0x1401)",
            ),
            binding.calls("texSubImage2D"),
        )
        // Level 1 is flat nibble 2: 0x22 + 2 = 36 on every channel, opaque, for all 3x2 texels.
        assertEquals(
            List(6) { listOf(36, 36, 36, 255) }.flatten(),
            binding.texSubImage2DPayloads[1].take(24).map { it.toInt() and 0xFF },
        )
        assertEquals("texParameteri(0xDE1,0x813D,0x2)", binding.calls("texParameteri")[0])
        assertEquals(emptyList(), binding.calls("generateMipmap") + binding.calls("compressedTexImage2D"))
    }

    @Test
    fun aDecodedUploadNeverPremultipliesAgain() {
        // EAC alpha 128 everywhere (multiplier 0) over a flat (0x44, 0x88, 0xCC) + 2 colour block. The
        // producer premultiplied, so the texels go up exactly as decoded: (70, 138, 206, 128).
        val alpha = etc2Block(Triple(63, 56, 128))
        val colour = etc2Block(
            Triple(63, 60, 4), Triple(59, 56, 4), Triple(55, 52, 8),
            Triple(51, 48, 8), Triple(47, 44, 12), Triple(43, 40, 12),
        )
        val binding = RecordingGlBinding()
        uploadCompressedTexture(binding, compressed(etc2Container(3, 4, 4) { alpha + colour }), native = false)
        assertEquals(
            List(16) { listOf(70, 138, 206, 128) }.flatten(),
            binding.texSubImage2DPayloads.single().map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun theDecodeWorkspaceIsBoundedToOneMebibyteOfWholeBlockRows() {
        val binding = RecordingGlBinding()
        uploadCompressedTexture(binding, compressed(etc2Container(1, 2048, 256) { flatBlock(1) }), native = false)
        // 2048 texels x 4 rows x 4 bytes = 32 KiB a block row, so 32 block rows (128 texel rows) a call.
        assertEquals(
            listOf(
                "texSubImage2D(0xDE1,0,0,0,2048,128,0x1908,0x1401)",
                "texSubImage2D(0xDE1,0,0,128,2048,128,0x1908,0x1401)",
            ),
            binding.calls("texSubImage2D"),
        )
        assertTrue(binding.texSubImage2DPayloads.all { it.size <= 1024 * 1024 })
    }

    /** An upload that throws part-way deletes the texture it generated: nothing else knows its name. */
    @Test
    fun anUploadThatThrowsDeletesTheTextureItGenerated() {
        listOf(false, true).forEach { native ->
            val recording = RecordingGlBinding()
            val failing = object : GlBinding by recording {
                override fun texSubImage2D(
                    target: Int, level: Int, xOffset: Int, yOffset: Int, width: Int, height: Int,
                    format: Int, type: Int, pixels: ByteArray,
                ): Unit = throw IllegalStateException("upload refused")

                override fun compressedTexImage2D(
                    target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
                    border: Int, imageSize: Int, data: ByteArray, dataOffset: Int,
                ): Unit = throw IllegalStateException("upload refused")
            }
            assertFailsWith<IllegalStateException> { uploadCompressedTexture(failing, compressed(chain), native) }
            val generated = recording.log.count { it.startsWith("genTextures") }
            assertEquals(1, generated, "native=$native")
            assertEquals(1, recording.deletedNames.size, "native=$native: the generated texture must be deleted")
        }
    }

    @Test
    fun aRenderContextSamplesEtc2WhenItIsEsOrAdvertisesEs3Compatibility() {
        fun profile(dialect: ShaderDialect, es3: Boolean) = RenderContextProfile(
            dialect = dialect,
            version = GlVersion(3, 3),
            vendorName = "",
            rendererName = "",
            shadingLanguageVersionText = "",
            supportsEs3Compatibility = es3,
            supportsSrgbWriteControl = false,
            maxTextureSize = 4096,
            maxColorAttachments = 4,
            maxCombinedTextureImageUnits = 16,
        )
        assertTrue(profile(ShaderDialect.GLES, es3 = false).supportsEtc2)
        assertTrue(profile(ShaderDialect.DESKTOP, es3 = true).supportsEtc2)
        assertEquals(false, profile(ShaderDialect.DESKTOP, es3 = false).supportsEtc2, "Apple's GL 4.1")
    }
}
