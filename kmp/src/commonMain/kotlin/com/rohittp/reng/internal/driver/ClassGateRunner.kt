package com.rohittp.reng.internal.driver

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.internal.glb.GlbScan
import com.rohittp.reng.internal.glb.GltfFeatureResult
import com.rohittp.reng.internal.glb.GltfParseResult
import com.rohittp.reng.internal.glb.parseGltf
import com.rohittp.reng.internal.glb.scanGlb
import com.rohittp.reng.internal.glb.validateGltfFeatures
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.image.PngDecodeResult
import com.rohittp.reng.internal.image.decodePng
import com.rohittp.reng.internal.resource.ResolvedResourceContent
import com.rohittp.reng.internal.resource.ResourceClassGate
import com.rohittp.reng.internal.resource.SuppliedValidationOutcome

/**
 * Runs exactly one [ResourceClassGate] over already-resolved content and reports whether it is
 * [SuppliedValidationOutcome.Valid] or [SuppliedValidationOutcome.Failed]. The gate's identity alone
 * decides which check runs; [ResourceOperationStateMachine][com.rohittp.reng.internal.resource.ResourceOperationStateMachine]
 * decides the [com.rohittp.reng.RenGErrorCode] a `Failed` outcome maps to (decode gates report
 * `RESOURCE_DECODE_FAILED`, feature gates report `UNSUPPORTED_RESOURCE_FEATURE`, and STORE-provenance
 * content collapses either into `STORE_INTEGRITY_FAILED`) — this interface never carries a reason code,
 * only the two outcomes that decision needs.
 */
internal fun interface ClassGateRunner {
    suspend fun run(gate: ResourceClassGate, content: ResolvedResourceContent): SuppliedValidationOutcome
}

/**
 * RenG's own class gates. `DECODE_PNG` genuinely decodes a sticker or model-texture PNG through
 * [decodePng], gated by [limits]'s decoded-byte ceiling; `PARSE_GLB`/`VALIDATE_GLB_FEATURES` genuinely
 * scan and parse a GLB container's JSON chunk into a [com.rohittp.reng.internal.glb.GltfDocument] via the
 * canonical [scanGlb]/[parseGltf]/[validateGltfFeatures] (ADR 0021's complete supported subset);
 * `VALIDATE_DEM_TERRAIN_ENCODING` decodes a DEM tile's PNG bytes and inspects the result.
 *
 * **GLB wiring:** `PARSE_GLB` and `VALIDATE_GLB_FEATURES` each independently re-derive the parsed
 * [com.rohittp.reng.internal.glb.GltfDocument] from [ResolvedResourceContent.stored]'s raw bytes via
 * [parseGlbDocument] — the same redundancy [runDecodePng] and [runValidateDemTerrainEncoding] already
 * accept for PNG, since this interface answers one gate at a time with no result cache between calls.
 * A [GlbScan] container-framing rejection or a [GltfParseResult.Malformed] structural rejection both
 * fail `PARSE_GLB`; `VALIDATE_GLB_FEATURES` additionally fails on either of those (a document
 * `PARSE_GLB` would already have refused can never be "supported"), or on a genuine
 * [GltfFeatureResult.Unsupported] feature outside ADR 0021's subset.
 *
 * **Engine-validated classes:** `BASEMAP_TILE_JSON`, `BASEMAP_VECTOR_TILE`, `BASEMAP_GEO_JSON`, and
 * `DECODE_PNG` over `BASEMAP_RASTER_TILE`/`BASEMAP_DEM_TILE` are Rentile's own firewall's job (ADR
 * 0016/0017) — RenG reports the outcome the firewall already observed rather than re-decoding or
 * re-parsing bytes Rentile already validated. Task 18 supplies that outcome; until it lands, and wired
 * as a constructor parameter this class does not yet take, those combinations are unreached and this
 * runner says so loudly (`error`) rather than rubber-stamping them `Valid`.
 */
internal class RenGClassGateRunner(private val limits: ResourceLimits) : ClassGateRunner {
    override suspend fun run(gate: ResourceClassGate, content: ResolvedResourceContent): SuppliedValidationOutcome {
        val resourceClass = content.route.resourceClass
        return when (gate) {
            ResourceClassGate.DECODE_PNG -> when (resourceClass) {
                ResourceClass.STICKER_IMAGE, ResourceClass.MODEL_TEXTURE -> runDecodePng(content)
                else -> reportUnobservedFirewallOutcome(gate, resourceClass)
            }
            ResourceClassGate.VALIDATE_DEM_TERRAIN_ENCODING -> runValidateDemTerrainEncoding(content)
            ResourceClassGate.PARSE_GLB -> runParseGlb(content)
            ResourceClassGate.VALIDATE_GLB_FEATURES -> runValidateGlbFeatures(content)
            ResourceClassGate.PARSE_TILEJSON,
            ResourceClassGate.DECODE_VECTOR_TILE,
            ResourceClassGate.PARSE_GEOJSON,
            -> reportUnobservedFirewallOutcome(gate, resourceClass)
        }
    }

    private fun runDecodePng(content: ResolvedResourceContent): SuppliedValidationOutcome =
        when (decodePng(content.stored.bytes, limits.maximumDecodedImageBytes)) {
            is PngDecodeResult.Success -> SuppliedValidationOutcome.Valid
            is PngDecodeResult.Malformed,
            is PngDecodeResult.Unsupported,
            PngDecodeResult.TooLarge,
            -> SuppliedValidationOutcome.Failed
        }

    private fun runValidateDemTerrainEncoding(content: ResolvedResourceContent): SuppliedValidationOutcome {
        val decoded = decodePng(content.stored.bytes, limits.maximumDecodedImageBytes)
        val image = (decoded as? PngDecodeResult.Success)?.image ?: return SuppliedValidationOutcome.Failed
        return if (isEightBitRgbTerrainEncoding(image)) {
            SuppliedValidationOutcome.Valid
        } else {
            SuppliedValidationOutcome.Failed
        }
    }

    private fun runParseGlb(content: ResolvedResourceContent): SuppliedValidationOutcome =
        when (parseGlbDocument(content.stored.bytes)) {
            is GltfParseResult.Parsed -> SuppliedValidationOutcome.Valid
            is GltfParseResult.Malformed, null -> SuppliedValidationOutcome.Failed
        }

    private fun runValidateGlbFeatures(content: ResolvedResourceContent): SuppliedValidationOutcome {
        val document = (parseGlbDocument(content.stored.bytes) as? GltfParseResult.Parsed)?.document
            ?: return SuppliedValidationOutcome.Failed
        return when (validateGltfFeatures(document)) {
            GltfFeatureResult.Supported -> SuppliedValidationOutcome.Valid
            is GltfFeatureResult.Unsupported -> SuppliedValidationOutcome.Failed
        }
    }

    /**
     * Runs the container scan and document parse `PARSE_GLB` performs — [scanGlb] bounded by
     * [ResourceLimits.maximumModelJsonChunkBytes], then [parseGltf] over the admitted JSON chunk and BIN
     * chunk length — sharing those exact steps with [runValidateGlbFeatures], which needs the same parsed
     * [com.rohittp.reng.internal.glb.GltfDocument] before it can inspect it. Returns `null` for a
     * container-framing fault ([scanGlb] rejected); a [GltfParseResult.Malformed] result still flows
     * through so callers see `PARSE_GLB`'s own malformation vocabulary rather than a second `null`.
     */
    private fun parseGlbDocument(bytes: ByteArray): GltfParseResult? {
        val admitted = scanGlb(bytes, limits.maximumModelJsonChunkBytes) as? GlbScan.Admitted ?: return null
        val binChunkLength = admitted.binChunk?.count()?.toLong() ?: 0L
        return parseGltf(admitted.json, binChunkLength, MAXIMUM_GLB_NODE_DEPTH)
    }

    private fun reportUnobservedFirewallOutcome(gate: ResourceClassGate, resourceClass: ResourceClass): Nothing =
        error(
            "RenGClassGateRunner does not yet observe the Rentile firewall outcome for $gate on " +
                "$resourceClass; Task 18 supplies it, wired as a constructor parameter this class does " +
                "not yet take",
        )
}

/** glTF scene-graph node depth bound for `PARSE_GLB`'s [parseGltf] call: generous for anything RenG
 *  draws, finite for a cyclic graph the specification forbids. Matches [parseGltf]'s own test suite
 *  default. */
private const val MAXIMUM_GLB_NODE_DEPTH = 128

private const val OPAQUE_ALPHA: Byte = -1 // 0xFF unsigned

/**
 * Admits a decoded image iff every pixel's alpha channel is fully opaque. Mapbox Terrain-RGB and
 * Terrarium are both plain eight-bit RGB byte triples — mathematically indistinguishable from the
 * decoded bytes alone, since both are just a caller-side formula's interpretation of R/G/B — so any PNG
 * whose source colour type carries no meaningful alpha (RenG's canonical decode leaves such pixels fully
 * opaque; see [com.rohittp.reng.internal.image.DecodedImage]) satisfies either encoding. A genuinely
 * four-channel encoding — alpha carrying real data — fails the instant any pixel is not fully opaque.
 */
private fun isEightBitRgbTerrainEncoding(image: DecodedImage): Boolean {
    val rgba = image.rgbaSnapshot()
    var index = 3
    while (index < rgba.size) {
        if (rgba[index] != OPAQUE_ALPHA) return false
        index += 4
    }
    return true
}
