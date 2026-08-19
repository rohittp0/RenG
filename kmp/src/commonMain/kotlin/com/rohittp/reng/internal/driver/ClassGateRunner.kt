package com.rohittp.reng.internal.driver

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLimits
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
 * scan a GLB container's JSON chunk; `VALIDATE_DEM_TERRAIN_ENCODING` decodes a DEM tile's PNG bytes and
 * inspects the result.
 *
 * **GLB scope note:** the canonical `scanGlb`/`parseGltf`/`validateGltfFeatures` — a full JSON value
 * tree and glTF document model, reviewed and enforcing ADR 0021's complete supported subset — live on
 * the sibling `feat/cycle-c-glb` branch (Task 10/16) and are not visible from this worktree, the same
 * cross-branch gap that left [com.rohittp.reng.internal.cache.ResidentCache] a stand-in here. Rather
 * than duplicate ~1500 lines of reviewed JSON/glTF parsing into a resource-layer task, this class
 * performs its own narrower, genuinely-real check: GLB container framing (magic, version, declared
 * length, JSON chunk bounds), a generic string-aware brace/bracket well-formedness scan of the JSON
 * chunk, and one feature check (a non-empty `extensionsRequired` array — the single rule the canonical
 * `GltfUnsupported.EXTENSION_REQUIRED` already covers, folding Draco/meshopt/Basis/every future required
 * extension into it). It is deliberately NOT full glTF semantic validation (asset.version, accessors,
 * buffer sizes, node graph, primitive/attribute/animation feature checks are all out of scope here).
 * Replace `runParseGlb`/`runValidateGlbFeatures`'s bodies with real `scanGlb`/`parseGltf`/
 * `validateGltfFeatures` calls once that branch merges, and delete the private GLB helpers below.
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

    private fun runParseGlb(content: ResolvedResourceContent): SuppliedValidationOutcome {
        val jsonText = scanMinimalGlbJsonChunk(content.stored.bytes) ?: return SuppliedValidationOutcome.Failed
        return if (isWellFormedJsonObjectShape(jsonText)) {
            SuppliedValidationOutcome.Valid
        } else {
            SuppliedValidationOutcome.Failed
        }
    }

    private fun runValidateGlbFeatures(content: ResolvedResourceContent): SuppliedValidationOutcome {
        val jsonText = scanMinimalGlbJsonChunk(content.stored.bytes) ?: return SuppliedValidationOutcome.Failed
        if (!isWellFormedJsonObjectShape(jsonText)) return SuppliedValidationOutcome.Failed
        return if (declaresRequiredExtensions(jsonText)) {
            SuppliedValidationOutcome.Failed
        } else {
            SuppliedValidationOutcome.Valid
        }
    }

    private fun reportUnobservedFirewallOutcome(gate: ResourceClassGate, resourceClass: ResourceClass): Nothing =
        error(
            "RenGClassGateRunner does not yet observe the Rentile firewall outcome for $gate on " +
                "$resourceClass; Task 18 supplies it, wired as a constructor parameter this class does " +
                "not yet take",
        )
}

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

private const val GLB_MAGIC = 0x46546C67L // "glTF" little-endian
private const val GLB_VERSION = 2L
private const val GLB_JSON_CHUNK_TYPE = 0x4E4F534AL // "JSON" little-endian
private const val GLB_HEADER_BYTES = 12
private const val GLB_CHUNK_HEADER_BYTES = 8

/** Reads an unsigned 32-bit little-endian integer as [Long] so no admitted value can wrap into a
 *  negative Int before it is bounds-checked. */
private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

/**
 * Scans [bytes] as a GLB container down to its JSON chunk's raw text, checking only the container
 * framing this stand-in needs: magic, version, an exact declared length, and a JSON-typed first chunk
 * fully inside the file. Returns `null` for any framing violation. Deliberately NOT the canonical
 * `scanGlb` — see this file's class-level KDoc.
 */
private fun scanMinimalGlbJsonChunk(bytes: ByteArray): String? {
    if (bytes.size < GLB_HEADER_BYTES + GLB_CHUNK_HEADER_BYTES) return null
    if (readUInt32LE(bytes, 0) != GLB_MAGIC) return null
    if (readUInt32LE(bytes, 4) != GLB_VERSION) return null
    val declaredLength = readUInt32LE(bytes, 8)
    if (declaredLength != bytes.size.toLong() || declaredLength % 4L != 0L) return null

    val chunkLength = readUInt32LE(bytes, GLB_HEADER_BYTES)
    val chunkType = readUInt32LE(bytes, GLB_HEADER_BYTES + 4)
    if (chunkType != GLB_JSON_CHUNK_TYPE) return null
    if (chunkLength % 4L != 0L) return null

    val dataStart = GLB_HEADER_BYTES + GLB_CHUNK_HEADER_BYTES
    val dataEnd = dataStart.toLong() + chunkLength
    if (dataEnd > bytes.size.toLong()) return null

    return bytes.copyOfRange(dataStart, dataEnd.toInt()).decodeToString()
}

/**
 * A generic, semantics-free JSON well-formedness check: string-aware brace/bracket balance plus a
 * top-level object shape. Not JSON grammar validation — numbers, commas, and escapes beyond a bare
 * backslash are not checked — only enough to distinguish a well-formed object from garbage, which is
 * all this stand-in's `PARSE_GLB` gate claims to do.
 */
private fun isWellFormedJsonObjectShape(text: String): Boolean {
    val trimmed = text.trimEnd(' ')
    if (trimmed.isEmpty() || trimmed.first() != '{' || trimmed.last() != '}') return false
    var depth = 0
    var inString = false
    var escaped = false
    for (ch in trimmed) {
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
            continue
        }
        when (ch) {
            '"' -> inString = true
            '{', '[' -> depth += 1
            '}', ']' -> {
                depth -= 1
                if (depth < 0) return false
            }
        }
    }
    return !inString && depth == 0
}

/**
 * Reports whether the JSON text declares a non-empty `extensionsRequired` array — the one feature check
 * this stand-in performs, covering the same fault the canonical `GltfUnsupported.EXTENSION_REQUIRED`
 * names (Draco, meshopt, Basis, and every future required extension collapse into this one rule). A
 * plain substring/bracket scan, not a JSON-object field read.
 */
private fun declaresRequiredExtensions(text: String): Boolean {
    val key = "\"extensionsRequired\""
    val keyIndex = text.indexOf(key)
    if (keyIndex < 0) return false
    val colonIndex = text.indexOf(':', keyIndex + key.length)
    if (colonIndex < 0) return false
    var cursor = colonIndex + 1
    while (cursor < text.length && text[cursor].isWhitespace()) cursor += 1
    if (cursor >= text.length || text[cursor] != '[') return false
    val closeIndex = text.indexOf(']', cursor)
    if (closeIndex < 0) return false
    return text.substring(cursor + 1, closeIndex).isNotBlank()
}
