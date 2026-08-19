package com.rohittp.reng.internal.identity

import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.gl.InternalPipelineRole
import com.rohittp.reng.internal.gl.OffscreenSurfaceDescriptor

internal data class DerivedResourceKey(
    val key: ResourceKey,
    val rawKey: RawResourceKey?,
    val identity: HashedCanonicalBytes,
)

internal class ResourceKeyDeriver(
    private val sha256: Sha256Function = PureKotlinSha256,
) {
    internal fun external(
        resourceClass: ResourceClass,
        locator: ResourceLocator,
    ): DerivedResourceKey {
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.EXTERNAL_RESOURCE) {
                field(1, CanonicalBinary.u16(ResourceKind.EXTERNAL.wireValue))
                field(2, CanonicalBinary.u16(resourceClass.wireValue))
                field(3, CanonicalBinary.exactUtf8(locator.value))
            },
        )
        val stableId = identity.digest.lowercaseHex
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.EXTERNAL,
                stableId = stableId,
                resourceClass = resourceClass,
            ),
            rawKey = RawResourceKey(
                stableId = stableId,
                resourceClass = resourceClass,
            ),
            identity = identity,
        )
    }

    internal fun geometryProgram(shaderPair: ShaderPair): DerivedResourceKey {
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.GEOMETRY_PROGRAM) {
                field(1, CanonicalBinary.u16(ResourceKind.GEOMETRY_PROGRAM.wireValue))
                field(2, CanonicalBinary.u16(GEOMETRY_SHADER_PROFILE_WIRE_VALUE))
                field(3, CanonicalBinary.exactUtf8(shaderPair.vertexSource))
                field(4, CanonicalBinary.exactUtf8(shaderPair.fragmentSource))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.GEOMETRY_PROGRAM,
                stableId = identity.digest.lowercaseHex,
                resourceClass = null,
            ),
            rawKey = null,
            identity = identity,
        )
    }

    internal fun internalPipeline(
        role: InternalPipelineRole,
        shaderPair: ShaderPair,
    ): DerivedResourceKey {
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.INTERNAL_PIPELINE) {
                field(1, CanonicalBinary.u16(ResourceKind.INTERNAL_PIPELINE.wireValue))
                field(2, CanonicalBinary.u16(role.wireValue))
                field(3, CanonicalBinary.u16(GEOMETRY_SHADER_PROFILE_WIRE_VALUE))
                field(4, CanonicalBinary.exactUtf8(shaderPair.vertexSource))
                field(5, CanonicalBinary.exactUtf8(shaderPair.fragmentSource))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.INTERNAL_PIPELINE,
                stableId = identity.digest.lowercaseHex,
                resourceClass = null,
            ),
            rawKey = null,
            identity = identity,
        )
    }

    internal fun offscreenSurface(descriptor: OffscreenSurfaceDescriptor): DerivedResourceKey {
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.OFFSCREEN_SURFACE) {
                field(1, CanonicalBinary.u16(ResourceKind.OFFSCREEN_SURFACE.wireValue))
                field(2, CanonicalBinary.u64(descriptor.widthPixels.toLong()))
                field(3, CanonicalBinary.u64(descriptor.heightPixels.toLong()))
                field(4, CanonicalBinary.u16(descriptor.colourFormat.wireValue))
                field(5, CanonicalBinary.u16(descriptor.depthFormat.wireValue))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.OFFSCREEN_SURFACE,
                stableId = identity.digest.lowercaseHex,
                resourceClass = null,
            ),
            rawKey = null,
            identity = identity,
        )
    }

    private fun derive(canonicalBytes: CanonicalBytes): HashedCanonicalBytes = HashedCanonicalBytes(
        digest = sha256.digest(canonicalBytes),
        canonicalBytes = canonicalBytes,
    )
}

private val ResourceKind.wireValue: Int
    get() = when (this) {
        ResourceKind.EXTERNAL -> 1
        ResourceKind.GEOMETRY_PROGRAM -> 2
        ResourceKind.INTERNAL_PIPELINE -> 3
        ResourceKind.OFFSCREEN_SURFACE -> 4
    }

private val ResourceClass.wireValue: Int
    get() = when (this) {
        ResourceClass.BASEMAP_STYLE -> 1
        ResourceClass.BASEMAP_TILE_JSON -> 2
        ResourceClass.BASEMAP_VECTOR_TILE -> 3
        ResourceClass.BASEMAP_RASTER_TILE -> 4
        ResourceClass.BASEMAP_DEM_TILE -> 5
        ResourceClass.BASEMAP_SPRITE_JSON -> 6
        ResourceClass.BASEMAP_SPRITE_IMAGE -> 7
        ResourceClass.BASEMAP_GEO_JSON -> 8
        ResourceClass.STICKER_IMAGE -> 9
        ResourceClass.MODEL_GLB -> 10
        ResourceClass.MODEL_TEXTURE -> 11
    }

private const val GEOMETRY_SHADER_PROFILE_WIRE_VALUE: Int = 1
