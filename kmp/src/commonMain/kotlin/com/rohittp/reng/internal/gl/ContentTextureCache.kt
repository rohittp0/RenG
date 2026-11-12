package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.internal.cache.ResidentGenerationId
import com.rohittp.reng.internal.image.CompressedImage
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.image.RawRgbaImage

/** Consumer and model textures, uploaded once per identity and leased from [GlObjectRegistry] per draw. */
internal class ContentTextureCache(
    private val glObjectRegistry: GlObjectRegistry,
) {
    /**
     * Looks [key] up in [glObjectRegistry] first — the
     * SAME registry [driver]'s own `forgetWithoutDeleting()` clears on context loss and on
     * [notifyGpuObjectsGone] (ADR 0007/0015: forgotten, not deleted, since the GL handles are already
     * gone and there is nothing valid left to delete) — and only calls [upload] on a genuine cache
     * miss, registering the freshly uploaded name under [key] so the NEXT draw of the same
     * [ResourceKey] (an unchanged sticker image or geometry consumer texture) reuses it instead of
     * calling [uploadTexture] (and therefore `genTextures`) again. [close] deletes every handle this
     * ever registers; nothing here calls a GL delete directly, mirroring how consumer pipelines are
     * retained until eviction or [close] and forgotten without deletion by [notifyGpuObjectsGone].
     */
    internal fun cachedTexture(
        key: ResourceKey,
        generationId: ResidentGenerationId,
        image: DecodedImage?,
        content: TextureContent,
        leases: MutableList<GpuAllocationLease>,
        binding: GlBinding,
        sampler: TextureSamplerState = defaultSamplerStateFor(content),
        subresource: GpuSubresource = GpuSubresource.Texture,
    ): Int {
        val decodedImage = requireNotNull(image) { "an eager content texture must carry decoded pixels" }
        return leaseOrUploadTexture(
            identity = GpuResourceIdentity(
                ownerKey = key,
                generationId = generationId,
                subresource = subresource,
                uploadVariant = GpuUploadVariant(content, sampler),
            ),
            leases = leases,
            textureBytes = { textureAllocationBytes(decodedImage.width, decodedImage.height, sampler) },
            upload = { uploadTexture(binding, decodedImage, content, sampler) },
        )
    }

    /**
     * Geometry-texture variant whose encoded bytes are decoded only inside the genuine GPU-miss
     * upload path. A rejected decode returns `null` without registering a partial allocation.
     */
    internal fun cachedGeometryTexture(
        key: ResourceKey,
        generationId: ResidentGenerationId,
        encodedPngBytes: ByteArray,
        width: Int,
        height: Int,
        content: TextureContent,
        leases: MutableList<GpuAllocationLease>,
        binding: GlBinding,
        decode: (ByteArray) -> DecodedImage?,
        sampler: TextureSamplerState = defaultSamplerStateFor(content),
    ): Int? = leaseOrUploadTextureOrNull(
        identity = GpuResourceIdentity(
            ownerKey = key,
            generationId = generationId,
            subresource = GpuSubresource.Texture,
            uploadVariant = GpuUploadVariant(content, sampler),
        ),
        leases = leases,
        textureBytes = { textureAllocationBytes(width, height, sampler) },
        upload = {
            val image = decode(encodedPngBytes) ?: return@leaseOrUploadTextureOrNull null
            uploadTexture(binding, image, content, sampler)
        },
    )

    /**
     * [cachedTexture] for an ETC2 sticker (ADR 0084). Whether the context samples ETC2 is read from the
     * profile at draw and is part of the upload's identity, so a texture decoded for one context is
     * never handed to a draw that expected blocks, or the reverse. Its cost is what the GPU holds: the
     * blocks themselves, or the RGBA8 they decoded to.
     */
    internal fun cachedCompressedTexture(
        key: ResourceKey,
        generationId: ResidentGenerationId,
        image: CompressedImage,
        native: Boolean,
        leases: MutableList<GpuAllocationLease>,
        binding: GlBinding,
    ): Int = leaseOrUploadTexture(
        identity = GpuResourceIdentity(
            ownerKey = key,
            generationId = generationId,
            subresource = GpuSubresource.Texture,
            uploadVariant = GpuUploadVariant(
                content = TextureContent.IMAGE,
                sampler = compressedSamplerState(image),
                storage = if (native) TextureStorage.ETC2 else TextureStorage.RGBA8,
            ),
        ),
        leases = leases,
        textureBytes = { if (native) image.payloadByteCount else image.rgbaByteCount },
        upload = { uploadCompressedTexture(binding, image, native) },
    )

    /** [cachedTexture] for a raw RGBA sticker (ADR 0092): uploaded from its stored bytes, one RGBA8 level. */
    internal fun cachedRawRgbaTexture(
        key: ResourceKey,
        generationId: ResidentGenerationId,
        image: RawRgbaImage,
        leases: MutableList<GpuAllocationLease>,
        binding: GlBinding,
    ): Int = leaseOrUploadTexture(
        identity = GpuResourceIdentity(
            ownerKey = key,
            generationId = generationId,
            subresource = GpuSubresource.Texture,
            uploadVariant = GpuUploadVariant(TextureContent.IMAGE, defaultSamplerStateFor(TextureContent.IMAGE)),
        ),
        leases = leases,
        textureBytes = { image.rgbaByteCount },
        upload = { uploadRawRgbaTexture(binding, image) },
    )

    private fun leaseOrUploadTexture(
        identity: GpuResourceIdentity,
        leases: MutableList<GpuAllocationLease>,
        textureBytes: () -> Long,
        upload: () -> Int,
    ): Int {
        glObjectRegistry.leaseAllocation(identity)?.let { resident ->
            leases += resident.lease
            return resident.payload as Int
        }
        val name = upload()
        leases += glObjectRegistry.registerAllocation(
            identity = identity,
            handles = listOf(GlObjectHandle(GlObjectType.TEXTURE, name)),
            textureBytes = textureBytes(),
            bufferBytes = 0L,
            payload = name,
        )
        return name
    }

    private fun leaseOrUploadTextureOrNull(
        identity: GpuResourceIdentity,
        leases: MutableList<GpuAllocationLease>,
        textureBytes: () -> Long,
        upload: () -> Int?,
    ): Int? {
        glObjectRegistry.leaseAllocation(identity)?.let { resident ->
            leases += resident.lease
            return resident.payload as Int
        }
        val name = upload() ?: return null
        leases += glObjectRegistry.registerAllocation(
            identity = identity,
            handles = listOf(GlObjectHandle(GlObjectType.TEXTURE, name)),
            textureBytes = textureBytes(),
            bufferBytes = 0L,
            payload = name,
        )
        return name
    }
}
