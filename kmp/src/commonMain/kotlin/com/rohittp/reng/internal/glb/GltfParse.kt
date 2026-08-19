package com.rohittp.reng.internal.glb

import com.rohittp.reng.internal.json.JsonValue

/** Byte size of one component, keyed by the six `componentType` values the specification defines
 * and ADR 0021 accepts. Any other value has no known size, so accessor arithmetic over it is
 * undecidable -- that is [GltfReject.COMPONENT_TYPE]. */
private val COMPONENT_SIZE_BYTES: Map<Int, Int> = mapOf(
    5120 to 1, 5121 to 1, 5122 to 2, 5123 to 2, 5125 to 4, 5126 to 4,
)

/** Component count per accessor `type` string. An unrecognised or missing string has no known
 * count -- that is [GltfReject.ACCESSOR_TYPE], distinct from [GltfReject.COMPONENT_TYPE]. */
private val COMPONENT_COUNT_BY_TYPE: Map<String, Int> = mapOf(
    "SCALAR" to 1, "VEC2" to 2, "VEC3" to 3, "VEC4" to 4, "MAT2" to 4, "MAT3" to 9, "MAT4" to 16,
)

/** The specification's reserved "primitive restart" maximum value per unsigned `componentType`,
 * which an index value MUST NOT equal. Component types never legally used for indices are absent
 * on purpose: the reserved-value rule has nothing to say about them. */
private val RESERVED_MAX_INDEX_VALUE: Map<Int, Long> = mapOf(
    5121 to 0xFFL, 5123 to 0xFFFFL, 5125 to 0xFFFFFFFFL,
)

/**
 * Parses [json] -- already scanned as a well-formed GLB JSON chunk by [scanGlb] -- into a fully
 * parsed, internally consistent [GltfDocument], or reports the first structural fault found.
 *
 * This is `PARSE_GLB`: it is permissive about anything the specification permits even when
 * `VALIDATE_GLB_FEATURES` (a later gate, over the [GltfDocument] this function produces) will
 * refuse it. An accessor with no `bufferView` is legal, means all zeros, and is the signature of a
 * Draco-compressed primitive; this function accepts it rather than reporting corruption for a
 * file whose real problem is an unsupported extension.
 *
 * [binChunkLength] is the BIN chunk's byte length (`0` when [GlbScan.Admitted.binChunk] was
 * `null`), used only to bound `buffers[0].byteLength`. [maximumNodeDepth] bounds the node
 * hierarchy walk so a cyclic graph -- which the specification forbids -- terminates instead of
 * recursing forever.
 *
 * `asset.version` is checked first, before any other field: ADR 0021 assigns it to `PARSE_GLB` by
 * name, requiring major version `2` and, when present, `asset.minVersion` at most `2.0`. Neither
 * value is retained on [GltfDocument] -- nothing downstream needs it once this gate has passed.
 */
internal fun parseGltf(json: JsonValue.Obj, binChunkLength: Long, maximumNodeDepth: Int): GltfParseResult =
    try {
        GltfParseResult.Parsed(GltfParser(json, binChunkLength, maximumNodeDepth).parse())
    } catch (signal: GltfRejectSignal) {
        GltfParseResult.Malformed(signal.reason)
    }

/** Internal control-flow signal: unwound by [parseGltf] into a [GltfParseResult.Malformed], never
 * seen outside this file. */
private class GltfRejectSignal(val reason: GltfReject) : RuntimeException()

private fun reject(reason: GltfReject): Nothing = throw GltfRejectSignal(reason)

private fun arrOf(value: JsonValue?): List<JsonValue> = (value as? JsonValue.Arr)?.elements ?: emptyList()

private fun membersOf(value: JsonValue?): Map<String, JsonValue> = (value as? JsonValue.Obj)?.members ?: emptyMap()

/** Splits a `MAJOR.MINOR` version string (glTF's own `asset.version`/`asset.minVersion` form) into
 * its two integer components, or `null` when either half is not an integer spelling. A string with
 * no `.` is treated as `MAJOR.0`. */
private fun majorMinor(text: String): Pair<Int, Int>? {
    val dotIndex = text.indexOf('.')
    val majorText = if (dotIndex >= 0) text.substring(0, dotIndex) else text
    val minorText = if (dotIndex >= 0) text.substring(dotIndex + 1).substringBefore('.') else "0"
    val major = majorText.toIntOrNull() ?: return null
    val minor = minorText.toIntOrNull() ?: return null
    return major to minor
}

private fun numberValue(value: JsonValue?): Double? = when (value) {
    is JsonValue.Integer -> value.value.toDouble()
    is JsonValue.Real -> value.value
    else -> null
}

/** Reads a JSON array of numbers as a [List] of [Double], or `null` when [value] is absent or not
 * an array. An element that is neither an integer nor a real number aborts the whole read
 * (via the non-local `return` inside [kotlin.collections.map], which is `inline`), yielding `null`
 * rather than a partial list -- this field is descriptive only (`matrix`/`translation`/`rotation`/
 * `scale`/factor arrays), with no [GltfReject] code assigned to a malformed element. */
private fun numberList(value: JsonValue?): List<Double>? {
    val array = value as? JsonValue.Arr ?: return null
    return array.elements.map { element -> numberValue(element) ?: return null }
}

/** Reads [field] from [members] as a `Long`, requiring the JSON token to be integer-spelled
 * (rejecting [GltfReject.NON_INTEGER_FIELD] otherwise -- see that code's documentation for why an
 * ordinary integer field, not only an index, uses it). Returns [default] when [field] is absent. */
private fun readLong(members: Map<String, JsonValue>, field: String, default: Long): Long {
    val value = members[field] ?: return default
    return (value as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
}

private fun readLongOrNull(members: Map<String, JsonValue>, field: String): Long? {
    val value = members[field] ?: return null
    return (value as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
}

/** Reads [field] as an index into an array of size [bound]: integer-spelled (else
 * [GltfReject.NON_INTEGER_FIELD]) and in `[0, bound)` (else [GltfReject.INDEX_OUT_OF_RANGE]).
 * Returns `null` when [field] is genuinely optional and absent. */
private fun optionalIndex(members: Map<String, JsonValue>, field: String, bound: Int): Int? {
    val value = members[field] ?: return null
    val index = (value as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
    if (index < 0 || index >= bound) reject(GltfReject.INDEX_OUT_OF_RANGE)
    return index.toInt()
}

/** As [optionalIndex], but a missing [field] is itself unresolvable -- reused as
 * [GltfReject.INDEX_OUT_OF_RANGE], the same code an out-of-bounds value gets, since neither can be
 * resolved to an element. */
private fun requiredIndex(members: Map<String, JsonValue>, field: String, bound: Int): Int =
    optionalIndex(members, field, bound) ?: reject(GltfReject.INDEX_OUT_OF_RANGE)

private class GltfParser(
    private val json: JsonValue.Obj,
    private val binChunkLength: Long,
    private val maximumNodeDepth: Int,
) {
    /** Set by [parseAccessors]; kept only so [validateIndexValues] can read an indices accessor's
     * declared `max`, a field [GltfAccessor] itself does not retain. */
    private var accessorsJsonMembers: List<Map<String, JsonValue>> = emptyList()

    fun parse(): GltfDocument {
        validateAssetVersion()

        val extensionsRequired = arrOf(json.members["extensionsRequired"])
            .mapNotNull { (it as? JsonValue.Text)?.value }

        val buffers = parseBuffers()
        val bufferViews = parseBufferViews(buffers)
        val accessors = parseAccessors(bufferViews)
        val skinsCount = arrOf(json.members["skins"]).size
        val images = parseImages(bufferViews.size)
        val samplers = parseSamplers()
        val textures = parseTextures(images.size, samplers.size)
        val materials = parseMaterials(textures.size)
        val meshes = parseMeshes(accessors, materials.size)
        val camerasCount = arrOf(json.members["cameras"]).size
        val nodes = parseNodes(meshes.size, skinsCount, camerasCount)
        validateNodeGraph(nodes)
        val scenes = parseScenes(nodes.size)
        val defaultScene = optionalIndex(json.members, "scene", scenes.size)
        val animations = parseAnimations(nodes.size, accessors.size)

        return GltfDocument(
            accessors = accessors,
            bufferViews = bufferViews,
            meshes = meshes,
            nodes = nodes,
            scenes = scenes,
            defaultScene = defaultScene,
            animations = animations,
            materials = materials,
            images = images,
            textures = textures,
            samplers = samplers,
            extensionsRequired = extensionsRequired,
            buffers = buffers,
        )
    }

    /** ADR 0021 assigns `asset.version` checking to `PARSE_GLB` by name. Requires `asset.version`
     * to be present and major version `2`; when `asset.minVersion` is also present, requires it be
     * at most `2.0`. Both fields are `MAJOR.MINOR` text per the specification's own `asset`
     * schema, read here without relying on JSON number spelling since they are strings, not
     * numbers. */
    private fun validateAssetVersion() {
        val assetMembers = membersOf(json.members["asset"])
        val version = (assetMembers["version"] as? JsonValue.Text)?.value
            ?: reject(GltfReject.ASSET_VERSION_UNSUPPORTED)
        val (major, _) = majorMinor(version) ?: reject(GltfReject.ASSET_VERSION_UNSUPPORTED)
        if (major != 2) reject(GltfReject.ASSET_VERSION_UNSUPPORTED)

        val minVersion = (assetMembers["minVersion"] as? JsonValue.Text)?.value ?: return
        val (minMajor, minMinor) = majorMinor(minVersion) ?: reject(GltfReject.ASSET_VERSION_UNSUPPORTED)
        if (minMajor > 2 || (minMajor == 2 && minMinor > 0)) reject(GltfReject.ASSET_VERSION_UNSUPPORTED)
    }

    private fun parseBuffers(): List<GltfBuffer> {
        val buffers = arrOf(json.members["buffers"]).map { element ->
            val members = membersOf(element)
            GltfBuffer(
                byteLength = readLong(members, "byteLength", default = 0L),
                uri = (members["uri"] as? JsonValue.Text)?.value,
            )
        }
        // Only buffers[0] can be GLB-embedded. Reusing BUFFER_VIEW_EXCEEDS_BUFFER: both mean a
        // declared size exceeds what its actual backing store provides, one link further down
        // the buffer/bufferView/accessor chain than the code's name alone suggests.
        if (buffers.isNotEmpty() && buffers[0].byteLength > binChunkLength) {
            reject(GltfReject.BUFFER_VIEW_EXCEEDS_BUFFER)
        }
        return buffers
    }

    private fun parseBufferViews(buffers: List<GltfBuffer>): List<GltfBufferView> =
        arrOf(json.members["bufferViews"]).map { element ->
            val members = membersOf(element)
            val bufferIndex = requiredIndex(members, "buffer", buffers.size)
            val byteOffset = readLong(members, "byteOffset", default = 0L)
            val byteLength = readLong(members, "byteLength", default = 0L)
            val byteStride = readLongOrNull(members, "byteStride")
            if (byteOffset + byteLength > buffers[bufferIndex].byteLength) {
                reject(GltfReject.BUFFER_VIEW_EXCEEDS_BUFFER)
            }
            GltfBufferView(bufferIndex, byteOffset, byteLength, byteStride)
        }

    private fun parseAccessors(bufferViews: List<GltfBufferView>): List<GltfAccessor> {
        accessorsJsonMembers = arrOf(json.members["accessors"]).map { membersOf(it) }
        return accessorsJsonMembers.map { members ->
            val componentType = readLong(members, "componentType", default = -1L).toInt()
            val componentSize = COMPONENT_SIZE_BYTES[componentType] ?: reject(GltfReject.COMPONENT_TYPE)
            val typeName = (members["type"] as? JsonValue.Text)?.value ?: reject(GltfReject.ACCESSOR_TYPE)
            val numComponents = COMPONENT_COUNT_BY_TYPE[typeName] ?: reject(GltfReject.ACCESSOR_TYPE)
            val elementSize = componentSize.toLong() * numComponents

            val bufferView = optionalIndex(members, "bufferView", bufferViews.size)
            val byteOffset = readLong(members, "byteOffset", default = 0L)
            val count = readLong(members, "count", default = 0L)
            val normalized = (members["normalized"] as? JsonValue.Bool)?.value ?: false
            val sparse = members["sparse"] != null

            if (bufferView != null) {
                val view = bufferViews[bufferView]
                if (view.byteStride != null &&
                    (view.byteStride < elementSize || view.byteStride % componentSize != 0L)
                ) {
                    // Below the element size or not a multiple of the component size: incoherent
                    // addressing, distinct from a span overrunning the view.
                    reject(GltfReject.BYTE_STRIDE)
                }
                val effectiveStride = view.byteStride ?: elementSize
                val span = byteOffset + (count - 1) * effectiveStride + elementSize
                if (span > view.byteLength) reject(GltfReject.ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW)
            }

            GltfAccessor(bufferView, byteOffset, componentType, count, typeName, normalized, sparse)
        }
    }

    private fun parseImages(bufferViewsCount: Int): List<GltfImage> =
        arrOf(json.members["images"]).map { element ->
            val members = membersOf(element)
            GltfImage(
                bufferView = optionalIndex(members, "bufferView", bufferViewsCount),
                mimeType = (members["mimeType"] as? JsonValue.Text)?.value,
                uri = (members["uri"] as? JsonValue.Text)?.value,
            )
        }

    private fun parseSamplers(): List<GltfSampler> =
        arrOf(json.members["samplers"]).map { element ->
            val members = membersOf(element)
            GltfSampler(
                magFilter = readLongOrNull(members, "magFilter")?.toInt(),
                minFilter = readLongOrNull(members, "minFilter")?.toInt(),
                wrapS = readLong(members, "wrapS", default = 10497L).toInt(),
                wrapT = readLong(members, "wrapT", default = 10497L).toInt(),
            )
        }

    private fun parseTextures(imagesCount: Int, samplersCount: Int): List<GltfTexture> =
        arrOf(json.members["textures"]).map { element ->
            val members = membersOf(element)
            GltfTexture(
                source = optionalIndex(members, "source", imagesCount),
                sampler = optionalIndex(members, "sampler", samplersCount),
            )
        }

    private fun textureRef(members: Map<String, JsonValue>, field: String, texturesCount: Int): GltfTextureRef? {
        val refMembers = (members[field] as? JsonValue.Obj)?.members ?: return null
        return GltfTextureRef(
            index = requiredIndex(refMembers, "index", texturesCount),
            texCoord = readLong(refMembers, "texCoord", default = 0L).toInt(),
        )
    }

    private fun parseMaterials(texturesCount: Int): List<GltfMaterial> =
        arrOf(json.members["materials"]).map { element ->
            val members = membersOf(element)
            val pbrMembers = (members["pbrMetallicRoughness"] as? JsonValue.Obj)?.members
            val pbr = pbrMembers?.let {
                GltfPbrMetallicRoughness(
                    baseColorFactor = numberList(it["baseColorFactor"]),
                    baseColorTexture = textureRef(it, "baseColorTexture", texturesCount),
                    metallicFactor = numberValue(it["metallicFactor"]),
                    roughnessFactor = numberValue(it["roughnessFactor"]),
                    metallicRoughnessTexture = textureRef(it, "metallicRoughnessTexture", texturesCount),
                )
            }
            GltfMaterial(
                pbrMetallicRoughness = pbr,
                normalTexture = textureRef(members, "normalTexture", texturesCount),
                occlusionTexture = textureRef(members, "occlusionTexture", texturesCount),
                emissiveTexture = textureRef(members, "emissiveTexture", texturesCount),
                emissiveFactor = numberList(members["emissiveFactor"]),
                alphaMode = (members["alphaMode"] as? JsonValue.Text)?.value ?: "OPAQUE",
                alphaCutoff = numberValue(members["alphaCutoff"]) ?: 0.5,
                doubleSided = (members["doubleSided"] as? JsonValue.Bool)?.value ?: false,
            )
        }

    private fun parseMeshes(accessors: List<GltfAccessor>, materialsCount: Int): List<GltfMesh> =
        arrOf(json.members["meshes"]).map { meshElement ->
            val primitives = arrOf(membersOf(meshElement)["primitives"]).map { primitiveElement ->
                val members = membersOf(primitiveElement)
                val attributes = membersOf(members["attributes"]).mapValues { (_, value) ->
                    val index = (value as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
                    if (index < 0 || index >= accessors.size) reject(GltfReject.INDEX_OUT_OF_RANGE)
                    index.toInt()
                }
                val indices = optionalIndex(members, "indices", accessors.size)
                val mode = readLong(members, "mode", default = 4L).toInt()
                val material = optionalIndex(members, "material", materialsCount)
                val targetCount = arrOf(members["targets"]).size

                if (indices != null) validateIndexValues(indices, attributes, accessors)

                GltfPrimitive(attributes, indices, mode, material, targetCount)
            }
            GltfMesh(primitives)
        }

    /** The specification's own index-value rules: an index MUST NOT equal the reserved maximum
     * for its component type, and MUST NOT be at or above the vertex count. Both are read from
     * the indices accessor's own declared `max`, per the specification's own promise that a
     * declared `min`/`max` matches the buffer's true contents -- this parser has no buffer bytes
     * to check independently, only [binChunkLength]. An absent `max` is not proof of a violation,
     * so [GltfReject.INDEX_VALUE_OUT_OF_RANGE] is never raised without one: `PARSE_GLB` stays
     * permissive rather than guessing. */
    private fun validateIndexValues(
        indicesAccessorIndex: Int,
        attributes: Map<String, Int>,
        accessors: List<GltfAccessor>,
    ) {
        val positionAccessorIndex = attributes["POSITION"] ?: return
        val attributeCount = accessors[positionAccessorIndex].count
        val indicesAccessor = accessors[indicesAccessorIndex]
        val maxElement = (accessorsJsonMembers[indicesAccessorIndex]["max"] as? JsonValue.Arr)
            ?.elements?.firstOrNull() ?: return
        val declaredMax = (maxElement as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
        val reservedMax = RESERVED_MAX_INDEX_VALUE[indicesAccessor.componentType]
        if (reservedMax != null && declaredMax == reservedMax) reject(GltfReject.INDEX_VALUE_OUT_OF_RANGE)
        if (declaredMax >= attributeCount) reject(GltfReject.INDEX_VALUE_OUT_OF_RANGE)
    }

    private fun parseNodes(meshesCount: Int, skinsCount: Int, camerasCount: Int): List<GltfNode> {
        val nodesArray = arrOf(json.members["nodes"])
        return nodesArray.map { element ->
            val members = membersOf(element)
            val children = arrOf(members["children"]).map { value ->
                val index = (value as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
                if (index < 0 || index >= nodesArray.size) reject(GltfReject.INDEX_OUT_OF_RANGE)
                index.toInt()
            }
            val mesh = optionalIndex(members, "mesh", meshesCount)
            val skin = optionalIndex(members, "skin", skinsCount)
            val camera = optionalIndex(members, "camera", camerasCount)
            val matrix = numberList(members["matrix"])
            val translation = numberList(members["translation"])
            val rotation = numberList(members["rotation"])
            val scale = numberList(members["scale"])
            if (matrix != null && (translation != null || rotation != null || scale != null)) {
                reject(GltfReject.NODE_MATRIX_AND_TRS)
            }
            GltfNode(children, mesh, skin, camera, matrix, translation, rotation, scale)
        }
    }

    /** The specification requires the node hierarchy to be a set of disjoint strict trees: every
     * node has at most one parent, and no cycle exists. A node referenced as a child more than
     * once -- by one parent twice, or by two different parents -- is rejected immediately. The
     * remaining walk is iterative and bounded by [maximumNodeDepth] so a cycle terminates instead
     * of recursing forever: since every surviving node has at most one parent, a node can only be
     * reached once from any root, so a cyclic component with no root simply never gets visited --
     * which is exactly how it is detected, without a separate visited-set check mid-walk. */
    private fun validateNodeGraph(nodes: List<GltfNode>) {
        val parentCount = IntArray(nodes.size)
        for (node in nodes) for (child in node.children) parentCount[child]++
        if (parentCount.any { it > 1 }) reject(GltfReject.NODE_GRAPH_NOT_DISJOINT_TREES)

        val visited = BooleanArray(nodes.size)
        val stack = ArrayDeque<Pair<Int, Int>>()
        for (index in nodes.indices) if (parentCount[index] == 0) stack.addLast(index to 1)
        while (stack.isNotEmpty()) {
            val (index, depth) = stack.removeLast()
            if (depth > maximumNodeDepth) reject(GltfReject.NODE_DEPTH_EXCEEDED)
            visited[index] = true
            for (child in nodes[index].children) stack.addLast(child to depth + 1)
        }
        if (visited.any { !it }) reject(GltfReject.NODE_GRAPH_NOT_DISJOINT_TREES)
    }

    private fun parseScenes(nodesCount: Int): List<GltfScene> =
        arrOf(json.members["scenes"]).map { element ->
            val members = membersOf(element)
            val nodeIndices = arrOf(members["nodes"]).map { value ->
                val index = (value as? JsonValue.Integer)?.value ?: reject(GltfReject.NON_INTEGER_FIELD)
                if (index < 0 || index >= nodesCount) reject(GltfReject.INDEX_OUT_OF_RANGE)
                index.toInt()
            }
            GltfScene(nodeIndices)
        }

    private fun parseAnimations(nodesCount: Int, accessorsCount: Int): List<GltfAnimation> {
        val seenNames = mutableSetOf<String>()
        return arrOf(json.members["animations"]).map { element ->
            val members = membersOf(element)
            val name = (members["name"] as? JsonValue.Text)?.value
            if (!name.isNullOrBlank() && !seenNames.add(name)) reject(GltfReject.DUPLICATE_ANIMATION_NAME)

            val samplers = arrOf(members["samplers"]).map { samplerElement ->
                val samplerMembers = membersOf(samplerElement)
                GltfAnimationSampler(
                    input = requiredIndex(samplerMembers, "input", accessorsCount),
                    output = requiredIndex(samplerMembers, "output", accessorsCount),
                    interpolation = (samplerMembers["interpolation"] as? JsonValue.Text)?.value ?: "LINEAR",
                )
            }
            val channels = arrOf(members["channels"]).map { channelElement ->
                val channelMembers = membersOf(channelElement)
                val targetMembers = membersOf(channelMembers["target"])
                GltfAnimationChannel(
                    sampler = requiredIndex(channelMembers, "sampler", samplers.size),
                    targetNode = optionalIndex(targetMembers, "node", nodesCount),
                    targetPath = (targetMembers["path"] as? JsonValue.Text)?.value ?: "",
                )
            }
            GltfAnimation(name, channels, samplers)
        }
    }
}
