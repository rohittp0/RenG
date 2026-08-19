package com.rohittp.reng.internal.glb

/** One accessor: a typed, possibly strided view into a [GltfBufferView]. [bufferView] is `null`
 * when the specification's "all zeros" accessor form is used -- legal, and the signature of a
 * Draco-compressed primitive -- so [parseGltf] must never treat its absence as malformed. */
internal data class GltfAccessor(
    val bufferView: Int?,
    val byteOffset: Long,
    val componentType: Int,
    val count: Long,
    val type: String,
    val normalized: Boolean,
    val sparse: Boolean,
)

/** A byte range inside one [GltfDocument.buffers] entry, optionally strided for interleaved
 * attributes. [byteStride] is `null` when the view is tightly packed. */
internal data class GltfBufferView(
    val buffer: Int,
    val byteOffset: Long,
    val byteLength: Long,
    val byteStride: Long?,
)

/** One draw call's worth of geometry: an accessor per attribute semantic, an optional index
 * buffer, a topology [mode] (glTF default `4`, `TRIANGLES`), an optional material, and the count
 * of morph-target objects declared on this primitive (`0` when none are present). Whether [mode]
 * or a non-zero [targetCount] is something RenG actually draws is `VALIDATE_GLB_FEATURES`'
 * concern, not this parse gate's. */
internal data class GltfPrimitive(
    val attributes: Map<String, Int>,
    val indices: Int?,
    val mode: Int,
    val material: Int?,
    val targetCount: Int,
)

internal data class GltfMesh(val primitives: List<GltfPrimitive>)

/** One node in the scene graph. Exactly one of [matrix] or any of [translation]/[rotation]/
 * [scale] may be present -- [parseGltf] rejects both, per the specification. [skin] and [camera]
 * are parsed only so a later gate can detect and reject them; [GltfDocument] retains no top-level
 * `skins` or `cameras` catalog because nothing else in RenG's vocabulary refers to either. */
internal data class GltfNode(
    val children: List<Int>,
    val mesh: Int?,
    val skin: Int?,
    val camera: Int?,
    val matrix: List<Double>?,
    val translation: List<Double>?,
    val rotation: List<Double>?,
    val scale: List<Double>?,
)

internal data class GltfScene(val nodes: List<Int>)

internal data class GltfAnimationChannel(val sampler: Int, val targetNode: Int?, val targetPath: String)

/** [interpolation] defaults to the specification's own default, `LINEAR`. */
internal data class GltfAnimationSampler(val input: Int, val output: Int, val interpolation: String)

/** [name] is optional and, per `CONTEXT.md`, not required to be unique -- only a non-blank
 * duplicate is rejected. See [parseGltf]. */
internal data class GltfAnimation(
    val name: String?,
    val channels: List<GltfAnimationChannel>,
    val samplers: List<GltfAnimationSampler>,
)

/** A `{index, texCoord}` texture reference, used by every texture slot a [GltfMaterial] carries.
 * [texCoord] defaults to the specification's own default, `0`. */
internal data class GltfTextureRef(val index: Int, val texCoord: Int)

internal data class GltfPbrMetallicRoughness(
    val baseColorFactor: List<Double>?,
    val baseColorTexture: GltfTextureRef?,
    val metallicFactor: Double?,
    val roughnessFactor: Double?,
    val metallicRoughnessTexture: GltfTextureRef?,
)

/** Every material property ADR 0021 commits to retaining -- the base-colour slot a [GltfTextureRef]
 * override replaces, the four secondary texture slots, and the alpha/cull state -- parsed and kept
 * even though shading them is a later cycle's decision. */
internal data class GltfMaterial(
    val pbrMetallicRoughness: GltfPbrMetallicRoughness?,
    val normalTexture: GltfTextureRef?,
    val occlusionTexture: GltfTextureRef?,
    val emissiveTexture: GltfTextureRef?,
    val emissiveFactor: List<Double>?,
    val alphaMode: String,
    val alphaCutoff: Double,
    val doubleSided: Boolean,
)

/** [uri] is parsed only so `VALIDATE_GLB_FEATURES` can reject an external or `data:` reference;
 * RenG's only supported image form is [bufferView] plus an `image/png` [mimeType]. */
internal data class GltfImage(val bufferView: Int?, val mimeType: String?, val uri: String?)

internal data class GltfTexture(val source: Int?, val sampler: Int?)

/** [wrapS]/[wrapT] default to the specification's own default, `10497` (`REPEAT`). */
internal data class GltfSampler(val magFilter: Int?, val minFilter: Int?, val wrapS: Int, val wrapT: Int)

/** [uri] is parsed only so `VALIDATE_GLB_FEATURES` can reject it; RenG's only supported buffer
 * form is `buffers[0]` embedded in the GLB's BIN chunk. */
internal data class GltfBuffer(val byteLength: Long, val uri: String?)

/** A fully parsed, internally consistent glTF 2.0 document: every index reference resolves, every
 * accessor's arithmetic fits its backing storage, and the node hierarchy is a set of disjoint
 * strict trees. No judgement is made yet about whether RenG can draw it -- that is
 * `VALIDATE_GLB_FEATURES`'s job, over this same structure. */
internal data class GltfDocument(
    val accessors: List<GltfAccessor>,
    val bufferViews: List<GltfBufferView>,
    val meshes: List<GltfMesh>,
    val nodes: List<GltfNode>,
    val scenes: List<GltfScene>,
    val defaultScene: Int?,
    val animations: List<GltfAnimation>,
    val materials: List<GltfMaterial>,
    val images: List<GltfImage>,
    val textures: List<GltfTexture>,
    val samplers: List<GltfSampler>,
    val extensionsRequired: List<String>,
    val buffers: List<GltfBuffer>,
)

/**
 * Every reason [parseGltf] can reject a document. These are exactly the malformation checks ADR
 * 0021 assigns to `PARSE_GLB`: the bytes are not a well-formed, internally consistent glTF 2.0
 * document. A feature the specification permits but RenG does not draw is never reported here --
 * that is `VALIDATE_GLB_FEATURES`'s distinct vocabulary ([GltfUnsupported] et al., a later task),
 * over the [GltfDocument] this gate produces.
 *
 * Several codes are deliberately reused across related faults rather than given one code apiece,
 * the same design already established for `GlbReject.DECLARED_LENGTH_MISMATCH`: one code per kind
 * of true statement the consumer needs, not one code per JSON field.
 *
 * - [NON_INTEGER_INDEX] covers every integer-typed field read anywhere in the document -- not
 *   only a reference into another array, but any field the specification types as an integer
 *   (`count`, `componentType`, `byteOffset`, `byteLength`, `byteStride`, `mode`, an accessor's
 *   declared `max`) -- whenever the JSON number token is fractional or exponential. `1e2` is a
 *   JSON number but is not an integer spelling, so it is never accepted as one.
 * - [INDEX_OUT_OF_RANGE] covers a syntactically valid integer reference that names no element of
 *   the array it indexes into, and also a structurally required index field that is absent
 *   entirely -- both mean the reference cannot be resolved.
 * - [COMPONENT_TYPE] covers an accessor whose numeric shape cannot be determined: an unrecognised
 *   or missing `componentType`, and equally an unrecognised or missing `type` string -- both leave
 *   the accessor's element size undecidable, which is the same underlying problem the code names.
 * - [ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW] covers both an accessor's byte span overflowing its
 *   buffer view and a `byteStride` that is below the accessor's element size or not a multiple of
 *   the component size -- both leave element addressing incoherent.
 * - [BUFFER_VIEW_EXCEEDS_BUFFER] covers both a buffer view's span overflowing its buffer and
 *   `buffers[0]`'s declared `byteLength` overflowing the actual BIN chunk -- both mean a declared
 *   size exceeds what its backing store actually provides, one level apart in the same chain.
 */
internal enum class GltfReject {
    ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW,
    BUFFER_VIEW_EXCEEDS_BUFFER,
    NODE_MATRIX_AND_TRS,
    NODE_GRAPH_NOT_DISJOINT_TREES,
    NODE_DEPTH_EXCEEDED,
    INDEX_OUT_OF_RANGE,
    INDEX_VALUE_OUT_OF_RANGE,
    DUPLICATE_ANIMATION_NAME,
    NON_INTEGER_INDEX,
    COMPONENT_TYPE,
}

internal sealed interface GltfParseResult {
    data class Parsed(val document: GltfDocument) : GltfParseResult

    data class Malformed(val reason: GltfReject) : GltfParseResult
}
