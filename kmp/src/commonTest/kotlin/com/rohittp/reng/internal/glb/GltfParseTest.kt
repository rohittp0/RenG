package com.rohittp.reng.internal.glb

import com.rohittp.reng.internal.json.JsonParse
import com.rohittp.reng.internal.json.JsonValue
import com.rohittp.reng.internal.json.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GltfParseTest {
    @Test
    fun toleratesAnAccessorWithNoBufferView() {
        // Fixture 41's shape: extensionsRequired names a compression extension and the
        // accessor has no bufferView. PARSE_GLB must accept; the feature gate rejects it.
        val parsed = assertIs<GltfParseResult.Parsed>(parse(dracoShapedDocument))
        assertNull(parsed.document.accessors[0].bufferView)
        assertEquals(listOf("KHR_draco_mesh_compression"), parsed.document.extensionsRequired)
    }

    @Test
    fun checksAccessorArithmeticInLongBeforeAllocating() {
        assertEquals(
            GltfReject.ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW,
            reject(accessorCountTwoPowForty, binChunkLength = 36L),
        )
        assertEquals(
            GltfReject.ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW,
            reject(accessorOffsetPastView, binChunkLength = 36L),
        )
        assertEquals(GltfReject.BUFFER_VIEW_EXCEEDS_BUFFER, reject(bufferViewPastBuffer, binChunkLength = 36L))
        assertIs<GltfParseResult.Parsed>(parse(accessorFitsExactly, binChunkLength = 36L))
        assertIs<GltfParseResult.Parsed>(parse(accessorInterleavedStride, binChunkLength = 48L))
    }

    @Test
    fun rejectsAContradictoryOrCyclicNodeGraph() {
        assertEquals(GltfReject.NODE_MATRIX_AND_TRS, reject(nodeWithMatrixAndTrs))
        assertEquals(GltfReject.NODE_GRAPH_NOT_DISJOINT_TREES, reject(nodeCycle))
        assertEquals(GltfReject.NODE_GRAPH_NOT_DISJOINT_TREES, reject(nodeWithTwoParents))
        assertEquals(
            GltfReject.NODE_DEPTH_EXCEEDED,
            reject(nodeChainJson(200), maximumNodeDepth = 128),
        )
    }

    @Test
    fun rejectsIndexReferencesOutOfRangeAndReservedIndexValues() {
        assertEquals(GltfReject.INDEX_OUT_OF_RANGE, reject(meshNamingMissingAccessor))
        assertEquals(
            GltfReject.INDEX_VALUE_OUT_OF_RANGE,
            reject(indexValueAboveVertexCount, binChunkLength = 42L),
        )
    }

    @Test
    fun rejectsDuplicateNonBlankAnimationNames() {
        assertEquals(GltfReject.DUPLICATE_ANIMATION_NAME, reject(twoAnimationsNamedWalk))
        // Absent and blank names are legal and addressable only by index.
        assertIs<GltfParseResult.Parsed>(parse(animationsWithBlankAndAbsentNames))
    }

    @Test
    fun readsIntegerFieldsOnlyFromIntegerSpelling() {
        // 1e2 is a JSON number but is not an index.
        assertEquals(
            GltfReject.NON_INTEGER_INDEX,
            reject(bufferViewIndexWrittenAsExponent, binChunkLength = 36L),
        )
    }

    @Test
    fun rejectsAnUnknownComponentTypeAsMalformedNotUnsupported() {
        // An unknown componentType has no known size, so accessor arithmetic is undecidable.
        assertEquals(GltfReject.COMPONENT_TYPE, reject(componentType9999))
    }

    // ---- fixtures ----

    private val dracoShapedDocument = """
        {
          "asset": {"version": "2.0"},
          "extensionsRequired": ["KHR_draco_mesh_compression"],
          "accessors": [
            {"componentType": 5126, "count": 24, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    // A 36-byte bufferView backed by a 36-byte buffer -- Task 7's fixtures 36-40 use the same
    // "buffer=36" shape, kept here for the same reason: a single VEC3-float accessor (3 * 4 = 12
    // bytes per element) spans exactly 36 bytes across 3 elements.
    private val accessorCountTwoPowForty = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 36}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 1099511627776, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val accessorOffsetPastView = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 36}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 1099511627776, "componentType": 5126, "count": 1, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val bufferViewPastBuffer = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 36}],
          "bufferViews": [{"buffer": 0, "byteOffset": 30, "byteLength": 10}]
        }
    """.trimIndent()

    private val accessorFitsExactly = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 36}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    // Two VEC3-float accessors (12 bytes each) interleaved at stride 24 across 2 vertices: the
    // first spans bytes [0, 36), the second [12, 48), exactly filling the 48-byte view.
    private val accessorInterleavedStride = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 48}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 48, "byteStride": 24}],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 2, "type": "VEC3"},
            {"bufferView": 0, "byteOffset": 12, "componentType": 5126, "count": 2, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val nodeWithMatrixAndTrs = """
        {
          "asset": {"version": "2.0"},
          "nodes": [
            {
              "matrix": [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1],
              "translation": [1,2,3]
            }
          ]
        }
    """.trimIndent()

    // Node 0's only child is node 1, and node 1's only child is node 0: a two-node cycle with no
    // node left over to serve as a root, so the walk from roots visits neither.
    private val nodeCycle = """
        {
          "asset": {"version": "2.0"},
          "nodes": [
            {"children": [1]},
            {"children": [0]}
          ]
        }
    """.trimIndent()

    // Nodes 0 and 1 both name node 2 as their child: node 2 has two parents.
    private val nodeWithTwoParents = """
        {
          "asset": {"version": "2.0"},
          "nodes": [
            {"children": [2]},
            {"children": [2]},
            {}
          ]
        }
    """.trimIndent()

    private val meshNamingMissingAccessor = """
        {
          "asset": {"version": "2.0"},
          "accessors": [
            {"componentType": 5126, "count": 3, "type": "VEC3"}
          ],
          "meshes": [
            {"primitives": [{"attributes": {"POSITION": 5}}]}
          ]
        }
    """.trimIndent()

    // A 3-vertex POSITION accessor (accessor 0) and a 3-index SCALAR/unsigned-short indices
    // accessor (accessor 1) that declares max=[5] -- 5 is at or above the 3-vertex attribute
    // count, so the declared index value is out of range even though every reference and every
    // accessor/bufferView/buffer arithmetic check fits exactly.
    private val indexValueAboveVertexCount = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 42}],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": 36},
            {"buffer": 0, "byteOffset": 36, "byteLength": 6}
          ],
          "accessors": [
            {"bufferView": 0, "byteOffset": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
            {"bufferView": 1, "byteOffset": 0, "componentType": 5123, "count": 3, "type": "SCALAR", "max": [5]}
          ],
          "meshes": [
            {"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}
          ]
        }
    """.trimIndent()

    private val twoAnimationsNamedWalk = """
        {
          "asset": {"version": "2.0"},
          "animations": [
            {"name": "Walk", "channels": [], "samplers": []},
            {"name": "Walk", "channels": [], "samplers": []}
          ]
        }
    """.trimIndent()

    private val animationsWithBlankAndAbsentNames = """
        {
          "asset": {"version": "2.0"},
          "animations": [
            {"channels": [], "samplers": []},
            {"name": "   ", "channels": [], "samplers": []},
            {"name": "", "channels": [], "samplers": []}
          ]
        }
    """.trimIndent()

    private val bufferViewIndexWrittenAsExponent = """
        {
          "asset": {"version": "2.0"},
          "buffers": [{"byteLength": 36}],
          "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
          "accessors": [
            {"bufferView": 1e2, "byteOffset": 0, "componentType": 5126, "count": 1, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    private val componentType9999 = """
        {
          "asset": {"version": "2.0"},
          "accessors": [
            {"componentType": 9999, "count": 1, "type": "VEC3"}
          ]
        }
    """.trimIndent()

    /** A linear chain of [length] nodes, each the sole child of its predecessor: node 0 is the
     * only root, and its tree is exactly [length] nodes deep. */
    private fun nodeChainJson(length: Int): String {
        val nodes = (0 until length).joinToString(",\n") { index ->
            val children = if (index < length - 1) "[${index + 1}]" else "[]"
            """{"children": $children}"""
        }
        return """{"asset": {"version": "2.0"}, "nodes": [$nodes]}"""
    }

    // ---- fixture-name plumbing ----

    private fun parse(json: String, binChunkLength: Long = 0L, maximumNodeDepth: Int = 128): GltfParseResult =
        parseGltf(obj(json), binChunkLength, maximumNodeDepth)

    private fun reject(json: String, binChunkLength: Long = 0L, maximumNodeDepth: Int = 128): GltfReject =
        assertIs<GltfParseResult.Malformed>(parse(json, binChunkLength, maximumNodeDepth)).reason

    private fun obj(text: String): JsonValue.Obj {
        val bytes = text.encodeToByteArray()
        val parsed = assertIs<JsonParse.Parsed>(parseJson(bytes, 0, bytes.size, 64))
        return parsed.value as JsonValue.Obj
    }
}
