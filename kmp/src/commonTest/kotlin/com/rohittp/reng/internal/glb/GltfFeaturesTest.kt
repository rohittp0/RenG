package com.rohittp.reng.internal.glb

import com.rohittp.reng.internal.json.JsonParse
import com.rohittp.reng.internal.json.JsonValue
import com.rohittp.reng.internal.json.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Every buffer/bufferView fixture below sizes its declared buffer at [FIXTURE_BIN_CHUNK_LENGTH]
 * or less, so a single generous [parse] default keeps buffer-length arithmetic (a `PARSE_GLB`
 * concern already covered by [GltfParseTest]) out of every fixture's way here. */
private const val FIXTURE_BIN_CHUNK_LENGTH = 4096L

class GltfFeaturesTest {
    @Test
    fun rejectsEveryUnsupportedFeatureWithItsOwnReason() {
        assertEquals(GltfUnsupported.EXTENSION_REQUIRED, unsupported(dracoShapedDocument))
        assertEquals(GltfUnsupported.ACCESSOR_WITHOUT_BUFFER_VIEW, unsupported(accessorWithoutBufferViewNoExtension))
        assertEquals(GltfUnsupported.SPARSE_ACCESSOR, unsupported(sparseAccessor))
        assertEquals(GltfUnsupported.PRIMITIVE_MODE, unsupported(triangleStrip))
        assertEquals(GltfUnsupported.PRIMITIVE_MODE, unsupported(pointsMode))
        assertEquals(GltfUnsupported.ATTRIBUTE_SEMANTIC, unsupported(texcoordOne))
        assertEquals(GltfUnsupported.ATTRIBUTE_SEMANTIC, unsupported(colourOne))
        assertEquals(GltfUnsupported.ATTRIBUTE_SEMANTIC, unsupported(customAttribute))
        assertEquals(GltfUnsupported.SKIN, unsupported(documentWithSkin))
        assertEquals(GltfUnsupported.MORPH_TARGET, unsupported(documentWithMorphTargets))
        assertEquals(GltfUnsupported.ANIMATION_TARGET_PATH, unsupported(weightsChannel))
        assertEquals(GltfUnsupported.INTERPOLATION, unsupported(cubicSplineSampler))
        assertEquals(GltfUnsupported.IMAGE_MEDIA_TYPE, unsupported(jpegImage))
        assertEquals(GltfUnsupported.EXTERNAL_URI, unsupported(imageWithUri))
        assertEquals(GltfUnsupported.EXTERNAL_URI, unsupported(bufferWithDataUri))
        assertEquals(GltfUnsupported.SCENE_AMBIGUOUS, unsupported(twoScenesNoDefault))
        assertEquals(GltfUnsupported.NORMALIZED_NOT_PERMITTED, unsupported(normalizedFloatAccessor))
    }

    @Test
    fun acceptsEverythingTheSubsetAdmits() {
        for (fixture in listOf(
            triangleIndexed, triangleNonIndexed, nodeMatrixOnly, nodeTrsOnly,
            tangentAndColourZero, sceneAbsentWithExactlyOneScene, interleavedStride,
            linearAndStepAnimation, embeddedPngImage, fullMaterialBlock,
            cameraAndLightIgnored, extensionsUsedWithoutRequired,
        )) {
            assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(fixture.document))
        }
    }

    // ---- supplementary coverage: called out by the task brief but not in its literal table ----

    @Test
    fun rejectsSceneAmbiguityWhenNoSceneIsDeclaredAtAll() {
        // The same SCENE_AMBIGUOUS rule's other half: "scene absent with zero ... scenes", as
        // distinct from twoScenesNoDefault's "... or two-or-more scenes".
        assertEquals(GltfUnsupported.SCENE_AMBIGUOUS, unsupported(zeroScenesNoDefault))
    }

    @Test
    fun rejectsAnyBufferOtherThanBufferZero() {
        // PARSE_GLB tolerates a second declared buffer -- nothing about it is malformed on its
        // own -- but RenG has no route to any buffer but the GLB-embedded buffers[0].
        assertEquals(GltfUnsupported.MULTIPLE_BUFFERS, unsupported(secondBufferDeclared))
    }

    @Test
    fun skinTakesPrecedenceOverAttributeSemanticWhenAMeshCarriesBoth() {
        // A skinned mesh carries both the flagged JOINTS_0 attribute and a node.skin reference.
        // Stripping the attribute alone would not fix the file -- the skin reference remains and
        // export fails again next round, now against SKIN. This pins that SKIN, not
        // ATTRIBUTE_SEMANTIC, is what the document actually reports.
        assertEquals(GltfUnsupported.SKIN, unsupported(skinnedMeshWithDisallowedAttribute))
    }

    @Test
    fun neverLeaksAnAttackerControlledUriIntoTheRejectionItself() {
        // GltfUnsupported.EXTERNAL_URI carries no payload -- confirm the actual uri text used by
        // the fixture below cannot be recovered from the result's own string form.
        val secretUri = "https://attacker.example/leak?token=super-secret-value"
        val json = """
            {
              "asset": {"version": "2.0"},
              "images": [{"uri": "$secretUri"}]
            }
        """.trimIndent()
        val result = validateGltfFeatures(json.document)
        assertEquals(GltfUnsupported.EXTERNAL_URI, assertIs<GltfFeatureResult.Unsupported>(result).reason)
        assertFalse(result.toString().contains("attacker"), "uri text leaked into a diagnostic")
    }

    // ---- reject fixtures ----

    // Same shape as GltfParseTest's dracoShapedDocument: extensionsRequired names a compression
    // extension and the one accessor has no bufferView. PARSE_GLB accepts it; this gate must
    // report the required extension, not the accessor shape that extension happens to produce.
    private val dracoShapedDocument = """
        {
          "asset": {"version": "2.0"},
          "extensionsRequired": ["KHR_draco_mesh_compression"],
          "accessors": [
            {"componentType": 5126, "count": 24, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val accessorWithoutBufferViewNoExtension = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "accessors": [
            {"componentType": 5126, "count": 3, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val sparseAccessor = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "accessors": [
            {
              "bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3",
              "sparse": {"count": 1, "indices": {}, "values": {}}
            }
          ]
        }
    """.trimIndent()

    private val triangleStrip = primitiveWithMode(5)
    private val pointsMode = primitiveWithMode(0)

    private fun primitiveWithMode(mode: Int) = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "accessors": [{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "mode": $mode}]}]
        }
    """.trimIndent()

    private val texcoordOne = primitiveWithAttribute("TEXCOORD_1")
    private val colourOne = primitiveWithAttribute("COLOR_1")
    private val customAttribute = primitiveWithAttribute("_CUSTOM")

    private fun primitiveWithAttribute(semantic: String) = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "accessors": [{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "$semantic": 0}, "mode": 4}]}]
        }
    """.trimIndent()

    private val documentWithSkin = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "skins": [{}],
          "nodes": [{"skin": 0}]
        }
    """.trimIndent()

    // Combines both faults SKIN and ATTRIBUTE_SEMANTIC guard against: the mesh's one primitive
    // carries the disallowed JOINTS_0 semantic, and the node drawing that mesh also carries a
    // skin reference. Pins that SKIN wins the precedence, since validateNodes() now runs before
    // validateMeshes().
    private val skinnedMeshWithDisallowedAttribute = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": [0]}],
          "skins": [{}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "accessors": [{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "JOINTS_0": 0}, "mode": 4}]}],
          "nodes": [{"mesh": 0, "skin": 0}]
        }
    """.trimIndent()

    private val documentWithMorphTargets = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "accessors": [{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "mode": 4, "targets": [{}]}]}]
        }
    """.trimIndent()

    private val weightsChannel = animationChannel(path = "weights", interpolation = "LINEAR")
    private val cubicSplineSampler = animationChannel(path = "translation", interpolation = "CUBICSPLINE")

    private fun animationChannel(path: String, interpolation: String) = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 100},
            {"buffer": 0, "byteOffset": 100, "byteLength": 100}
          ],
          "nodes": [{}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "SCALAR"},
            {"bufferView": 1, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "VEC3"}
          ],
          "animations": [
            {
              "channels": [{"sampler": 0, "target": {"node": 0, "path": "$path"}}],
              "samplers": [{"input": 0, "output": 1, "interpolation": "$interpolation"}]
            }
          ]
        }
    """.trimIndent()

    private val jpegImage = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "images": [{"mimeType": "image/jpeg"}]
        }
    """.trimIndent()

    private val imageWithUri = """
        {
          "asset": {"version": "2.0"},
          "images": [{"uri": "external-texture.png"}]
        }
    """.trimIndent()

    private val bufferWithDataUri = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 0, "uri": "data:application/octet-stream;base64,AAAA"}]
        }
    """.trimIndent()

    private val secondBufferDeclared = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 0}, {"byteLength": 0}]
        }
    """.trimIndent()

    private val twoScenesNoDefault = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}, {"nodes": []}]
        }
    """.trimIndent()

    private val zeroScenesNoDefault = """
        {"asset": {"version": "2.0"}}
    """.trimIndent()

    private val normalizedFloatAccessor = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "accessors": [
            {
              "bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3",
              "normalized": true
            }
          ]
        }
    """.trimIndent()

    // ---- accept fixtures ----

    private val triangleIndexed = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 100},
            {"buffer": 0, "byteOffset": 100, "byteLength": 100}
          ],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
            {"bufferView": 1, "byteOffset": 0, "componentType": 5123, "count": 3, "type": "SCALAR"}
          ],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1, "mode": 4}]}],
          "nodes": [{"mesh": 0}],
          "scene": 0,
          "scenes": [{"nodes": [0]}]
        }
    """.trimIndent()

    private val triangleNonIndexed = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "accessors": [{"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "mode": 4}]}],
          "nodes": [{"mesh": 0}],
          "scene": 0,
          "scenes": [{"nodes": [0]}]
        }
    """.trimIndent()

    private val nodeMatrixOnly = """
        {
          "asset": {"version": "2.0"},
          "nodes": [{"matrix": [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]}],
          "scenes": [{"nodes": []}]
        }
    """.trimIndent()

    private val nodeTrsOnly = """
        {
          "asset": {"version": "2.0"},
          "nodes": [{"translation": [1,2,3], "rotation": [0,0,0,1], "scale": [1,1,1]}],
          "scenes": [{"nodes": []}]
        }
    """.trimIndent()

    private val tangentAndColourZero = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 100},
            {"buffer": 0, "byteOffset": 100, "byteLength": 100},
            {"buffer": 0, "byteOffset": 200, "byteLength": 100}
          ],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
            {"bufferView": 1, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC4"},
            {"bufferView": 2, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC4"}
          ],
          "meshes": [
            {"primitives": [{"attributes": {"POSITION": 0, "TANGENT": 1, "COLOR_0": 2}, "mode": 4}]}
          ]
        }
    """.trimIndent()

    private val sceneAbsentWithExactlyOneScene = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}]
        }
    """.trimIndent()

    // Same interleaved shape as GltfParseTest's accessorInterleavedStride.
    private val interleavedStride = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 48, "byteStride": 24}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 2, "type": "VEC3"},
            {"bufferView": 0, "byteOffset": 12, "componentType": 5126, "count": 2, "type": "VEC3"}
          ],
          "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1}, "mode": 4}]}]
        }
    """.trimIndent()

    private val linearAndStepAnimation = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 100},
            {"buffer": 0, "byteOffset": 100, "byteLength": 100}
          ],
          "nodes": [{}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "SCALAR"},
            {"bufferView": 1, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "VEC3"}
          ],
          "animations": [
            {
              "channels": [
                {"sampler": 0, "target": {"node": 0, "path": "translation"}},
                {"sampler": 1, "target": {"node": 0, "path": "rotation"}}
              ],
              "samplers": [
                {"input": 0, "output": 1, "interpolation": "LINEAR"},
                {"input": 0, "output": 1, "interpolation": "STEP"}
              ]
            }
          ]
        }
    """.trimIndent()

    private val embeddedPngImage = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "images": [{"bufferView": 0, "mimeType": "image/png"}]
        }
    """.trimIndent()

    private val fullMaterialBlock = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "buffers": [{"byteLength": 1024}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 100}],
          "images": [{"bufferView": 0, "mimeType": "image/png"}],
          "samplers": [{}],
          "textures": [{"source": 0, "sampler": 0}],
          "materials": [
            {
              "pbrMetallicRoughness": {
                "baseColorFactor": [1, 1, 1, 1],
                "baseColorTexture": {"index": 0, "texCoord": 0},
                "metallicFactor": 1.0,
                "roughnessFactor": 1.0,
                "metallicRoughnessTexture": {"index": 0, "texCoord": 0}
              },
              "normalTexture": {"index": 0},
              "occlusionTexture": {"index": 0},
              "emissiveTexture": {"index": 0},
              "emissiveFactor": [0, 0, 0],
              "alphaMode": "BLEND",
              "alphaCutoff": 0.5,
              "doubleSided": true
            }
          ]
        }
    """.trimIndent()

    private val cameraAndLightIgnored = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "cameras": [{"type": "perspective"}],
          "nodes": [{"camera": 0}],
          "extensions": {"KHR_lights_punctual": {"lights": [{"type": "directional"}]}}
        }
    """.trimIndent()

    private val extensionsUsedWithoutRequired = """
        {
          "asset": {"version": "2.0"},
          "scenes": [{"nodes": []}],
          "extensionsUsed": ["KHR_materials_unlit"]
        }
    """.trimIndent()

    // ---- fixture-name plumbing ----

    private fun parse(json: String): GltfParseResult =
        parseGltf(obj(json), binChunkLength = FIXTURE_BIN_CHUNK_LENGTH, maximumNodeDepth = 128)

    private val String.document: GltfDocument
        get() = assertIs<GltfParseResult.Parsed>(parse(this)).document

    private fun unsupported(json: String): GltfUnsupported =
        assertIs<GltfFeatureResult.Unsupported>(validateGltfFeatures(json.document)).reason

    private fun obj(text: String): JsonValue.Obj {
        val bytes = text.encodeToByteArray()
        val parsed = assertIs<JsonParse.Parsed>(parseJson(bytes, 0, bytes.size, 64))
        return parsed.value as JsonValue.Obj
    }
}
