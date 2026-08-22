# F-2: the gap between RenG's glTF layer and drawing an animated, textured model

Research only. No code was changed. Every claim below carries a `file:line` citation against the
`feat/cycle-e-basemap` working tree.

## Summary answer

**The existing glTF layer is a fully-populated, internally-consistent document model that is thrown away
at the seam.** `parseGltf` really does produce a scene graph — accessors, buffer views, meshes,
primitives with attribute maps, nodes with TRS or matrix, scenes, animations with channels and samplers,
materials with all five texture slots, images, textures, samplers, buffers
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GltfDocument.kt:120-134`) — but the only caller
in production, `RenGClassGateRunner`, reduces it to `Valid` / `Failed` and drops the object
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/ClassGateRunner.kt:69-82`). Nothing in RenG
has ever held a `GltfDocument` beyond the lifetime of one gate call, and **no code anywhere reads a single
byte of the BIN chunk** — `parseGltf` receives `binChunkLength: Long`, never the bytes
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GltfParse.kt:44`,
`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/ClassGateRunner.kt:92-96`).

So the honest framing is not "the parser answers a different question than the renderer asks." It is:
**the parser answers the renderer's structural question and then the plumbing discards the answer, and the
whole numeric half of glTF — every vertex, index, keyframe and embedded image byte — has never been
touched by any code in this repository.**

---

## 1. What the existing parser actually produces

### 1.1 The two layers

`scanGlb(bytes, maximumJsonChunkBytes)`
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GlbContainer.kt:96`) walks the GLB container and
returns `GlbScan.Admitted(json: JsonValue.Obj, binChunk: IntRange?)`
(`.../GlbContainer.kt:17`). Note what `binChunk` is: an `IntRange` **into the caller's array**, not a copy.
That is convenient for F-2 — the BIN bytes are addressable without a second allocation — provided the
array is still resident. It is: `ResidentGeneration` retains `stored` raw bytes for the life of the
generation and explicitly never drops them after decode
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/cache/ResidentCache.kt:14-24`).

`parseGltf(json, binChunkLength, maximumNodeDepth)` (`.../GltfParse.kt:44`) turns the JSON object into
`GltfDocument`.

### 1.2 What survives parsing — a real document model, not a verdict

`GltfDocument` (`.../GltfDocument.kt:120-134`) carries thirteen fields:

| Field | Retained shape | Citation |
|---|---|---|
| `accessors` | `bufferView: Int?`, `byteOffset`, `componentType`, `count`, `type`, `normalized`, `sparse` | `GltfDocument.kt:6-14` |
| `bufferViews` | `buffer`, `byteOffset`, `byteLength`, `byteStride: Long?` | `GltfDocument.kt:18-23` |
| `meshes` → `primitives` | `attributes: Map<String, Int>`, `indices: Int?`, `mode`, `material: Int?`, `targetCount` | `GltfDocument.kt:30-38` |
| `nodes` | `children`, `mesh`, `skin`, `camera`, `matrix`, `translation`, `rotation`, `scale` | `GltfDocument.kt:46-55` |
| `scenes`, `defaultScene` | root node index lists, plus resolved `scene` index | `GltfDocument.kt:57`, `GltfParse.kt:146` |
| `animations` | `name`, `channels(sampler, targetNode, targetPath)`, `samplers(input, output, interpolation)` | `GltfDocument.kt:59-70` |
| `materials` | full `pbrMetallicRoughness` + `normal`/`occlusion`/`emissive` refs + `emissiveFactor` + `alphaMode` + `alphaCutoff` + `doubleSided` | `GltfDocument.kt:74-96` |
| `images` | `bufferView: Int?`, `mimeType`, `uri` | `GltfDocument.kt:100` |
| `textures`, `samplers` | `source`/`sampler`; `magFilter`/`minFilter`/`wrapS`/`wrapT` | `GltfDocument.kt:102-105` |
| `extensionsRequired`, `buffers` | strings; `byteLength` + `uri` | `GltfDocument.kt:109, 132-133` |

The guarantee attached to it is stated in its own KDoc (`GltfDocument.kt:111-119`): *every index reference
resolves*, *every accessor's arithmetic fits its backing storage*, and *the node hierarchy is a set of
disjoint strict trees*. That is exactly the invariant a decoder wants before it starts reading bytes.

### 1.3 What is discarded

Deliberately dropped by the parser:

- **`asset.version` / `minVersion`** — checked, then not retained (`GltfParse.kt:40-42`).
- **Top-level `skins` and `cameras` catalogs** — only their *sizes* are kept, purely to bounds-check
  `node.skin` / `node.camera` (`GltfParse.kt:136, 142-143`; `GltfDocument.kt:41-45`).
- **Accessor `min` / `max`** — read transiently for the index-value check via a side-channel
  (`accessorsJsonMembers`, `GltfParse.kt:123-125, 347-352`), never stored on `GltfAccessor`.
- **`mesh.weights`, node/mesh/animation `name` (except animation), `extensionsUsed`, `extras`** — never
  read.
- **The entire BIN chunk.** Only its length crosses the boundary.

Discarded by the *caller*, which is the bigger loss: the whole `GltfDocument`
(`ClassGateRunner.kt:69-82`). And it is parsed **twice** per acquisition — `PARSE_GLB` and
`VALIDATE_GLB_FEATURES` each re-run `scanGlb` + `parseGltf` from raw bytes with no result cache between
them, which the KDoc calls out as accepted redundancy (`ClassGateRunner.kt:44-50, 92-96`). Both gates run
for `MODEL_GLB` (`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationProtocol.kt:644-647`).

### 1.4 The renderer never even asks for a model's bytes today

`FramePlanningCore.staticResourceTraversal` **does** traverse a `Model`, emitting one `MODEL_GLB`
reference and an optional `MODEL_TEXTURE` reference
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/FramePlanningCore.kt:232-234`). But
`RenGRenderer.prepare` filters the traversal down to `STICKER_IMAGE` only, then adds geometry consumer
textures it re-derives independently
(`kmp/src/commonMain/kotlin/com/rohittp/reng/RenGRenderer.kt:371-377, 386-391, 404`). Model references are
therefore **derived, hashed into frame identity, and then never fetched**. A `FramePlan` carrying a
`Model` today performs no GLB acquisition, runs no GLB gate, and draws nothing.

`RenGPreparedFrame` has no `models` field (`RenGRenderer.kt:136-155`), and `Scene` has no models slot
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/SceneContent.kt:84-89`).

---

## 2. ADR 0021 in full — and what it was written to bound

`docs/adr/0021-fix-the-supported-glb-subset.md`.

### 2.1 The committed subset

**Accepted:** component types `5120`–`5123`, `5125`, `5126`; triangles only; `POSITION` required with
`NORMAL`/`TEXCOORD_0`/`TANGENT`/`COLOR_0` optional (`TANGENT` and `COLOR_0` "parsed and otherwise
unused"); indexed and non-indexed primitives; node `matrix` **or** TRS but never both; arbitrary breadth
and bounded depth; `scene` present, or absent with exactly one scene; multiple mesh-bearing nodes and
multiple primitives per mesh; the full `pbrMetallicRoughness` block plus the four secondary texture slots
and the alpha/cull state, "parsed and retained because a texture override is specified to preserve every
other material property"; embedded PNG images; `byteStride` and interleaved attributes; `translation`,
`rotation`, `scale` animation channels with `LINEAR` and `STEP`.

**Rejected:** sparse accessors; strips, fans, points, lines; `TEXCOORD_n`/`COLOR_n` above zero; joints and
weights; skins; morph targets; `weights` animation targets; JPEG images; any non-empty
`extensionsRequired`; any buffer or image with a `uri` (including `data:`); `CUBICSPLINE`.

Also: three container rules stricter than the specification (exact declared length, no unknown chunk in
position two, `0x20`-only JSON padding); RenG's own JSON reader with integer-spelling classification,
duplicate-member rejection and lone-surrogate rejection; a JSON-chunk ceiling separate from the whole-GLB
ceiling (a boxed value tree measured at ~27× its text); `Long` accessor arithmetic before allocation;
fixed non-configurable JSON-nesting and node-depth bounds; no time budget.

### 2.2 Was the subset chosen to bound parsing or rendering?

**Both, but unevenly — and the unevenness is where F-2 gets hurt.**

The ADR's own framing is rendering-driven: *"The subset is driven by what a `Model` is — one placement,
one GLB, an optional base-colour texture override, and animation tracks sampled at a time."* Most
rejections are honestly renderer rejections: strips and fans are rejected because converting them would be
a repair; `TEXCOORD_n` above zero because "RenG binds one texture"; skins because "a whole skinning
pipeline"; `CUBICSPLINE` because approximating with `LINEAR` "would silently change motion". Those are
statements about the draw path, not the parser.

But the *accept* list is expressed at the **wrong granularity for a renderer**. It accepts component types
`5120`–`5123`, `5125`, `5126` as a flat set across all accessors, with no per-semantic constraint. glTF
itself constrains by semantic: `POSITION` must be `VEC3`/`FLOAT` (byte/short positions need
`KHR_mesh_quantization`, which the blanket `extensionsRequired` rule already rejects); `TEXCOORD_0` may be
`FLOAT` or normalized `UNSIGNED_BYTE`/`UNSIGNED_SHORT`; indices must be `SCALAR` unsigned. The shipped
validator enforces none of that — `validateAccessors` checks only `sparse`, missing `bufferView`, and
`normalized` on `FLOAT`/`UNSIGNED_INT`
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GltfFeatures.kt:142-150`).

Consequence: **a document with a `SCALAR`/`BYTE` `POSITION` accessor, or a `MAT4` `TEXCOORD_0`, currently
passes both gates as "supported"** and the renderer has no defined behaviour for it. ADR 0021's own
reasoning ("widening later is a compatible change, narrowing is not") makes tightening this *after* a
release that draws models a breaking change. It is free right now, because no release draws models.

### 2.3 Checks the ADR's own source research proposed that were never implemented

`docs/research/2026-08-18-cycle-c-glb-parse.md:335-410` proposed a reject table. Several rows did not land
in code, and each is a gap a renderer will walk into:

| Proposed rule | Source | Implemented? |
|---|---|---|
| `POSITION` required; a primitive missing it is `PARSE_GLB`-malformed | research `:352` | **No.** No POSITION check exists in `GltfParse.kt` or `GltfFeatures.kt`. `validateIndexValues` silently `return`s when POSITION is absent (`GltfParse.kt:344`). |
| Index count validated as a non-zero multiple of three | research `:363` | **No.** Nothing reads an indices accessor's `count` against 3. |
| Indices accessor must be `SCALAR` unsigned | research `:363` | **No.** Any `type`/`componentType` is accepted for `indices` (`GltfParse.kt:320`). |
| `matrix` on an *animated* node rejected | research `:369` | **No.** Only matrix-vs-TRS on the same node is checked (`GltfParse.kt:371-373`); the ADR dropped the animated-node half entirely. |
| Unspecified sampler enum values rejected at `PARSE_GLB` | research `:375` | **No.** `parseSamplers` reads raw ints with no enumeration check (`GltfParse.kt:258-267`). |
| `normalized` on `indices` rejected | research `:341` | **No.** Only `FLOAT`/`UNSIGNED_INT` are guarded (`GltfFeatures.kt:146-148`). |
| Animation `min`/`max` presence required, duration derived from the buffer | research `:452-457` | **No.** `min`/`max` are read only for indices accessors and never retained. |
| "`accessor.count` has schema `minimum: 1`, so a zero-keyframe input accessor is already invalid at `PARSE_GLB`" | research `:449` | **False of the implementation.** `count` defaults to `0` and has no lower bound (`GltfParse.kt:226`). |

**Additionally — not in any prior document — there are no non-negativity guards** on `count`,
`byteOffset`, or `byteLength` anywhere in `GltfParse.kt` (the only `< 0` tests are index-reference bounds
checks at lines 108, 317, 361, 407). A JSON integer is signed (`JsonReader.kt:42, 209`). With
`byteOffset = -1000, count = 1`, the span check
`byteOffset + (count - 1) * effectiveStride + elementSize` (`GltfParse.kt:240`) evaluates to a negative
number, which is `<= byteLength`, so it passes. Today that is harmless because nobody reads bytes. **The
moment F-2 adds accessor decoding it becomes an out-of-bounds read driven by consumer-supplied content.**
A negative `byteStride` is caught incidentally (`byteStride < elementSize`, `GltfParse.kt:233`); the others
are not.

### 2.4 The corpus check that ADR 0021 owed has been run

`docs/research/2026-08-19-glb-feature-subset-corpus-check.md`: 118 Khronos `.glb` sample assets,
**52 supported (44%)**. Rejections: `IMAGE_MEDIA_TYPE` 35 (JPEG), `EXTENSION_REQUIRED` 19,
`ATTRIBUTE_SEMANTIC`/`SKIN` 9 (skinned characters — `BrainStem`, `Fox`, `RiggedFigure`),
`ANIMATION_TARGET_PATH` 1, `MORPH_TARGET` 1, `INTERPOLATION` 1. No row moved. This is the best available
estimate of what fraction of real-world models F-2 will draw at all, and it is a useful number to say out
loud in release notes: **JPEG textures and skinned characters are the two things consumers will hit first.**

---

## 3. What F-2 must add

Nothing below exists in any form today.

### 3.1 Accessor and buffer-view decoding

The BIN chunk must be plumbed from `GlbScan.Admitted.binChunk` through to a decoder. Required cross
product, driven by what a draw actually needs:

| Semantic / role | glTF-legal within ADR 0021's subset | Needed decode |
|---|---|---|
| `POSITION` | `VEC3` `FLOAT` | direct float read, respecting `byteStride` |
| `NORMAL` | `VEC3` `FLOAT` | same |
| `TANGENT` | `VEC4` `FLOAT` | parsed, unused (ADR 0021) — still needs to be *skipped* correctly in an interleaved buffer |
| `TEXCOORD_0` | `VEC2` `FLOAT`, or normalized `UNSIGNED_BYTE`/`UNSIGNED_SHORT` | float read **plus two normalized affine dequantizations**, or upload the normalized form directly with `vertexAttribPointer(normalized = true)` |
| `COLOR_0` | `VEC3`/`VEC4` `FLOAT`, or normalized `UNSIGNED_BYTE`/`UNSIGNED_SHORT` | as above, plus a `VEC3 → VEC4` alpha=1 widening |
| `indices` | `SCALAR` `UNSIGNED_BYTE`/`UNSIGNED_SHORT`/`UNSIGNED_INT` | `UNSIGNED_BYTE` indices are **not drawable on desktop GL core profiles** and must be widened to `UNSIGNED_SHORT` |
| animation `input` | `SCALAR` `FLOAT` | float read; also the source of `durationSeconds` |
| animation `output` | `VEC3` `FLOAT` (T/S), `VEC4` `FLOAT` or normalized signed/unsigned byte/short (R) | float read plus normalized dequantization for quantized rotations |

Two structural facts make this cheaper than it looks and one makes it more expensive:

- Cheap: `byteStride` and interleaving are already validated for coherence (`GltfParse.kt:232-241`), and
  GL's `vertexAttribPointer` takes a stride directly (`GlBinding.kt:59-61`), so **interleaved attributes
  can be uploaded as-is without de-interleaving** whenever the whole buffer view is a single VBO.
- Cheap: total geometry bytes are bounded by `maximumModelGlbBytes` (256 MiB default,
  `kmp/src/commonMain/kotlin/com/rohittp/reng/Resources.kt:73`) because external URIs are rejected — the
  research doc calls this "the quiet payoff" (`2026-08-18-cycle-c-glb-parse.md:463-466`).
- Expensive: expanded forms are **not** bounded the same way. Widening `UNSIGNED_BYTE` indices, expanding
  normalized attributes, or de-interleaving costs up to ~4× stored bytes, and `ResourceUsage.decodedCpuBytes`
  is supposed to carry the expanded figure (`2026-08-18-cycle-c-glb-parse.md:466-469`;
  `kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceReports.kt:31-47`).

### 3.2 Primitive modes and index handling

`mode` is already constrained to `4` (`GltfFeatures.kt:10, 162`). Draw calls:

- `drawElements` exists on the seam and on all four platform bindings (`GlBinding.kt:110`;
  `LinuxGlBinding.kt:582`, `AndroidGlBinding.kt:394`, `MacosGlBinding.kt:549`, `IosGlBinding.kt:534`).
- **`GL_UNSIGNED_SHORT` (0x1403) is not defined in `GlTokens.kt`** — only `GL_UNSIGNED_BYTE`, `GL_FLOAT`,
  `GL_UNSIGNED_INT` (`GlTokens.kt:48-50`). The single most common glTF index type has no token.
- glTF's sampler wrap/filter enums *are* GL enums (`10497 == GL_REPEAT == 0x2901`), so
  `GltfSampler`'s values map straight through — but `GL_REPEAT`, `GL_MIRRORED_REPEAT`, and the four
  mipmap minification filters are all absent from `GlTokens.kt:37-43`. `generateMipmap` is on the seam
  (`GlBinding.kt:41`), so honouring a mipmapped `minFilter` is reachable.
- Also absent from the seam: `uniformMatrix3fv` (a normal matrix), `vertexAttribIPointer` (not needed —
  `JOINTS_n` is rejected), and array-valued `uniform3fv`/`uniform4fv`. `uniformMatrix4fv` already takes a
  `count` (`GlBinding.kt:91`), so matrix arrays are available.

### 3.3 Material and texture binding — reconciling one locator with glTF's material graph

`CONTEXT.md:239-246` fixes the semantics: *"Without an override it uses GLB-authored material colours and
embedded textures; an override replaces every rendered primitive's base-colour texture while preserving
other material properties."*

So `Model.texture` is **not** a material — it is a single-slot substitution applied to *every* primitive.
Concretely, F-2 must:

1. Resolve each primitive's material → `pbrMetallicRoughness.baseColorTexture` →
   `textures[i].source` → `images[j].bufferView` → a slice of the BIN chunk → `decodePng`.
   All four hops are already parsed and index-checked; none is executed today.
2. Where `Model.texture != null`, replace step 1's *result* with the `MODEL_TEXTURE` PNG while keeping
   `baseColorFactor`, `metallicFactor`, `roughnessFactor`, `alphaMode`, `alphaCutoff`, `doubleSided`, and
   the four secondary slots (`GltfDocument.kt:76-96`).
3. Decide four things `CONTEXT.md` does not answer:
   - Does an override *add* a base-colour texture to a primitive whose material has none? ("replaces"
     suggests yes; the primitive may then have no `TEXCOORD_0` to sample it with.)
   - What sampler state does the override use, having no glTF `sampler`? (RenG's own `uploadTexture`
     default is `GL_LINEAR` + `GL_CLAMP_TO_EDGE`, `GlTextureUpload.kt:78-85`.)
   - What `texCoord` set? `TEXCOORD_0` is the only one in the subset, so this is forced, but a material
     declaring `baseColorTexture.texCoord = 1` currently parses fine (`GltfParse.kt:282`) and must be
     rejected or coerced.
   - What is the default material for `primitive.material == null`? glTF defines one (metallic 1,
     roughness 1, white base colour); RenG has no representation of it.
4. Texture *content kind*: a base-colour texture is `TextureContent.IMAGE` (premultiplied,
   `GlTextureUpload.kt:55-88`). Correct — but premultiplication interacts with glTF's alpha modes
   (§6.4).

### 3.4 Node transform composition

Not implemented anywhere. Required:

- `matrix` (16 doubles, column-major) → `DoubleMatrix4`. `DoubleMatrix4.fromRows` exists
  (`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/math/DoubleLinearAlgebra.kt:199`); a
  column-major constructor exists too (`:157`).
- TRS → `T * R * S`, where `R` is a **quaternion** `[x,y,z,w]`. **RenG has no quaternion type and no
  quaternion→matrix conversion.** `DoubleMatrix3` offers only Euler `rotationXyzDegrees`
  (`DoubleLinearAlgebra.kt:149`), which is the wrong parameterization. A quaternion type is new code, and
  it is also what `LINEAR` rotation sampling needs (slerp — §4.3).
- A pre-order walk of the default scene composing parent × child, producing one global transform per
  mesh-bearing node. `validateNodeGraph` already proves the walk terminates and every node is reachable
  from exactly one root (`GltfParse.kt:385-400`), so the walk itself is safe.
- Composition with the placement: the existing chain is
  `projection * cameraSpaceModel(placement) * vertex` (`SceneContent.kt:286-302`). A model inserts
  `nodeGlobalTransform` between `cameraSpaceModel` and `vertex`. Both `compose*ModelViewProjection`
  functions currently narrow to `FloatArray` at return (`SceneContent.kt:301, 334`), so F-2 needs either
  `DoubleMatrix4`-returning variants or a documented decision that node transforms compose in `Float`.
  Model-local values are small, so `Float` is defensible — but it should be a decision, not an accident,
  because `SceneContent`'s KDoc makes a precision promise (`SceneContent.kt:117-123`).
- `localDimensions` needs no work: `CONTEXT.md:202-203` says "Model local dimensions are GLB coordinates",
  i.e. `(1,1,1)`, which is `composeMapModelViewProjection`'s existing default (`SceneContent.kt:289`).

### 3.5 Vertex attributes the renderer needs, and the shader

RenG's own shaders are authored as GLSL ES 3.00 and travel through the same dialect-substitution path a
consumer's does (`StickerPipeline.kt:10-42`) — that is the template a model shader follows. A model
program needs, at minimum: `aPosition` (`vec3`), `aTexCoord0` (`vec2`), optional `aNormal` (`vec3`),
optional `aColor0` (`vec4`); uniforms for MVP, base-colour factor, base-colour sampler, a
"has base-colour texture" switch, and alpha cutoff.

**Note the naming trap.** `RESERVED_SHADER_NAMES` (`GeometryPipeline.kt:44-52`) already reserves
`aPosition`, `aTexCoord`, `uModelViewProjection`, `uResolution`, `uGeometryBounds`, `uFrameIndex` against
consumer collision, and ADR 0024 closes with an explicit warning that renaming any documented shader name
is a *silent* breaking change. A model shader is RenG-internal (like `rengSticker*`,
`StickerPipeline.kt:20-45`), so it should use a `reng`-prefixed private naming scheme and **must not**
extend the documented consumer interface — otherwise F-2 quietly grows the public shader contract.

Program variants: the shipped `GlProgramCache` keys by `ResourceKey`
(`GlProgramCache.kt:6-21`) and `ResourceKeyDeriver.internalPipeline` takes an `InternalPipelineRole` plus a
`ShaderPair` (`StickerPipeline.kt:76`). If F-2 needs permutations (with/without normals, with/without
vertex colour, opaque/mask/blend), each permutation is a distinct `ShaderPair` and therefore a distinct
key — the cache handles it, but the permutation count should be bounded deliberately.

### 3.6 New residency and identity

- **A parsed `GltfDocument` has nowhere to live.** `ResidentGeneration` holds `stored` +
  `decoded: DecodedImage?` and nothing else (`ResidentCache.kt:14-25`), and in production `decoded` is
  *always* `null` — the one production install site passes `decoded = null`
  (`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/ResourceActionExecutor.kt:263`), with image
  decode repeated per-`prepare` in `RenGRenderer` (`RenGRenderer.kt:580-601`). F-2 either accepts a
  **third** full GLB parse per prepared frame (on top of the two the gates already do) or introduces a
  parsed-model residency.
- **GPU-side model objects need a `ResourceKind` that does not exist.** `GlObjectRegistry.resident(key)`
  returns only the first `TEXTURE` handle for a key (`GlObjectRegistry.kt:112-113`), so VBO/EBO/VAO sets
  need a separate lookup. And `ResourceKind` is a **public enum** with five entries
  (`Resources.kt:52-58`) plus a wire-value mapping used in canonical identity
  (`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/ResourceKeyDerivation.kt:170-176`). Making
  a model's uploaded primitives or its embedded images visible to `ResourceReport` / `ResourceSelector.ByKind`
  (`ResourceReports.kt:21-28`) requires new entries — **public ABI growth, and an exhaustive-`when` source
  break for consumers.** This is the one place F-2 touches the published surface.
- Embedded GLB images have no `ResourceLocator` and therefore no `ResourceClass`, so they cannot use
  `ResourceKeyDeriver.external`. They need a derivation of their own (e.g. model key + image index, or a
  content hash), which is the same kind of decision ADR 0018 already governs.

### 3.7 Animation selector resolution has a stage and a vocabulary already reserved

`CONTEXT.md:247-258` requires preparation to reject a missing or out-of-range selector, and two selectors
in one `Model` resolving to the same animation. The failure vocabulary is **already allowlisted**:
`RESOURCE_PARSE_FAILED` at `PipelineStage.RESOURCE_PARSING` with `fieldName = animationSelector`
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/DiagnosticFactories.kt:33, 151-154, 391-397`) — and
`ANIMATION_SELECTOR` is currently referenced by nothing but tests. Note that `FRAME_PLANNING`'s allowed
field set does **not** include it (`DiagnosticFactories.kt:128-146`), so selector resolution must be
reported from the resource/parse stage, i.e. after acquisition — **not** inside `FramePlanningCore`. That
is a real ordering constraint on where the check lives.

---

## 4. Animation

### 4.1 What the parser retains

Everything structural, and only that: `GltfAnimation(name, channels, samplers)` with
`GltfAnimationChannel(sampler, targetNode: Int?, targetPath: String)` and
`GltfAnimationSampler(input, output, interpolation)` (`GltfDocument.kt:59-70`), parsed at
`GltfParse.kt:413-439`. Every index is bounds-checked. Duplicate non-blank animation names are rejected as
`PARSE_GLB` malformation (`GltfParse.kt:418`), which is exactly what `AnimationSelector.Name` needs.

`validateGltfFeatures` restricts `targetPath` to `{translation, rotation, scale}` and `interpolation` to
`{LINEAR, STEP}` (`GltfFeatures.kt:22, 26, 177-188`).

### 4.2 What the parser retains of skins: nothing

`node.skin` is parsed *solely* so the feature gate can reject it — the KDoc says so
(`GltfDocument.kt:41-45`) — and there is **no top-level `skins` catalog on `GltfDocument` at all**; only
`skinsCount` is computed, to bounds-check the index (`GltfParse.kt:136`). `validateNodes` rejects any node
with a `skin` (`GltfFeatures.kt:171-175`), and `JOINTS_n`/`WEIGHTS_n` are outside
`SUPPORTED_ATTRIBUTE_SEMANTICS` (`GltfFeatures.kt:18`). Nothing about `inverseBindMatrices`, joint arrays,
or a skeleton root is represented anywhere.

### 4.3 What sampling at an arbitrary time actually requires

The two-stage split (`2026-08-18-cycle-c-glb-parse.md:437-448`) is the right model and neither stage
exists:

**Outer stage — `CONTEXT.md:254-258`.** A positive-duration animation samples
`timeSeconds % durationSeconds`; a zero-duration animation samples time zero. `durationSeconds` is the
largest `input` value across *all* of that animation's samplers, so **duration cannot be known without
reading the BIN chunk** (or trusting a declared `max`, which is not retained — §2.3).

**Inner stage — the glTF specification.** Sampler inputs are relative to `t = 0` at the animation's start,
and output "MUST be clamped to the nearest end of the input range" outside it. So each sampler clamps the
wrapped time into `[firstInput, lastInput]` **independently** — samplers within one animation may have
different input ranges, and an animation whose first keyframe is at `t = 10` holds that value from `t = 0`.

Then, per channel:

- Binary-search the `input` accessor for the bracketing keyframe pair.
- `STEP`: take the earlier output.
- `LINEAR` on `translation`/`scale`: componentwise lerp.
- `LINEAR` on `rotation`: **spherical linear interpolation of quaternions**, with the shortest-arc
  sign fix (negate one quaternion when the dot product is negative) and a lerp fallback near
  antiparallel. Nothing in `DoubleLinearAlgebra.kt` does any of this.
- Normalized-integer rotation outputs must be dequantized before slerp.

Then: apply channel results to node TRS, **in `Model.animationTracks` list order**
(`CONTEXT.md:255-256`), so a later track's channel targeting the same node/path wins. Then recompose node
global transforms (§3.4) — which means the transform walk is per-frame per-model, not cached once at
upload.

Two more rules to decide, neither settled anywhere:

- A channel with `targetNode == null` is legal and defined as a no-op; the parser retains it as `null`
  (`GltfParse.kt:433`) and the validator does not reject it, so the sampler must simply skip it.
- Two channels in one animation targeting the same `(node, path)` is a specification `MUST NOT` that
  nothing currently checks.
- A node targeted by an animation channel **must** use TRS, not `matrix`. §2.3: not checked. Sampling a
  matrix-only node has no defined meaning.

### 4.4 Is skinning separable from node animation?

**Yes, cleanly — and it is already separated, in code and in the ADR.**

- The rejection is at a single, distinct code (`GltfUnsupported.SKIN`, `GltfFeatures.kt:91, 171-175`)
  with an explicitly-reasoned check ordering: `SKIN` is checked *before* `ATTRIBUTE_SEMANTIC` precisely so
  a skinned model is diagnosed by "remove the skin", not "remove these attributes"
  (`GltfFeatures.kt:57-64`).
- Node animation needs zero skinning machinery: sampled TRS → node global transforms → one uniform matrix
  per draw. Skinning needs joint index/weight attributes, `inverseBindMatrices`, a joint-matrix palette
  uniform array (or a bone texture), a skinned vertex shader, and per-frame palette upload — and
  `vertexAttribIPointer`, which the seam does not have.
- ADR 0021 already commits to rejecting skins; shipping F-2 without skinning changes no documented
  promise, and adding skinning later is purely additive behind the same gate (the same argument ADR 0021
  makes for `CUBICSPLINE`).

**What a consumer would notice.** The corpus number is the honest answer: **9 of 118 Khronos sample
assets (7.6%) are rejected for skinning** (`2026-08-19-glb-feature-subset-corpus-check.md`), and those 9
are exactly the recognisable ones — `BrainStem`, `Fox`, `RiggedFigure`. Anyone whose mental model of
"animated model" is "a walking character" will find their asset rejected outright, with
`UNSUPPORTED_RESOURCE_FEATURE` rather than a degraded render. Node animation without skinning covers
rigid-body motion: rotating machinery, opening doors, orbiting objects, vehicles, drone props — a real
category, but not the one people picture. This deserves an explicit sentence in F-2's release notes rather
than being discovered on first use.

---

## 5. Where model drawing would live

### 5.1 What exists and is directly reusable

`internal/gl/` after Cycle D + F-1:

| Component | Reusable for models? |
|---|---|
| `drawFrame` — offscreen pass, reverse-Z depth (`clearDepthf(0)`, `depthFunc(GL_GREATER)`), then a blended composite (`GlFrameDrawer.kt:38-97`) | **Yes, unchanged.** Models are frame content behind the same `GlFrameContent` seam (`GlFrameDrawer.kt:7-12, 69`). |
| `withCapturedGlState` / ADR 0023 restore set (`GlStateSnapshot.kt:258-276`) | **Yes.** Cull enable/mode, front face, depth func/mask/range, blend state and per-unit texture bindings are all captured and restored (`GlStateSnapshot.kt:119-133, 190-211`), so a model pass may freely enable culling and change blend state. |
| `GlProgramCache.getOrCompile` + `compileShaderProgram` + `#version` substitution (`GlProgramCache.kt:9-21`) | **Yes.** Same path the sticker pipeline uses. |
| `uploadTexture(binding, image, TextureContent)` with premultiply, filter and wrap defaults (`GlTextureUpload.kt:55-88`) | **Yes** for base-colour; see §6.4 for the alpha-mode interaction. |
| `RenGRenderer.cachedTexture(key) { … }` upload-once-by-key (`RenGRenderer.kt:809, 871`) | **Yes**, once model textures have keys (§3.6). |
| `composeMapModelViewProjection` / `composeScreenModelViewProjection` (`SceneContent.kt:286-335`) | **Yes**, with a node-transform insertion point and a `Double`-returning variant (§3.4). |
| `MercatorSpatialPlanner` already resolves every model's placement and sorts the screen regime with the documented `stickers-then-models` tie-break (`MercatorSpatialPlanner.kt:166-170, 246-260`) | **Yes** — this is genuinely done, and better than `SceneContent` uses today. |
| `drawElements` on all four platform bindings | **Yes** (§3.2). |
| `RecordingGlBinding` test fake + call-log assertion style (`kmp/src/commonTest/.../gl/RecordingGlBinding.kt`, `SceneContentTest.kt`) | **Yes** — F-1 verified its whole draw path by call log, which is the pattern F-2 inherits. |

### 5.2 What has no analogue

- **No vertex/index buffer path for dynamic content.** `createStickerPipeline` and
  `createGeometryPipeline` each allocate exactly *one* static quad VBO reused for every instance
  (`StickerPipeline.kt:89-102`, `GeometryPipeline.kt:113-140`). There is no code that uploads per-resource
  geometry, no per-primitive VAO, and no `GL_ELEMENT_ARRAY_BUFFER` bind anywhere in production — the token
  is defined at `GlTokens.kt:53` and `GlTokens.kt:57` and has **no use at all** outside those two
  declarations.
- **No multi-draw-call object.** Every existing pipeline is one `drawArrays` per instance. A model is
  `nodes × primitives` draw calls with per-primitive material state.
- **No basemap draw path yet.** `RenGPreparedFrame.basemapTiles` carries encoded PNG bytes with an
  explicit note that "decoding them, uploading them and drawing the ground are later tasks, and there is
  no basemap draw path in `internal/gl/` yet" (`RenGRenderer.kt:143-153`). So the "3D scene" a
  `MAP`-anchored model is supposed to be occluded by consists, today, of other map-anchored stickers and
  geometries only. F-2 lands after E-basemap in the plan order, so this should resolve — but if the
  cycles slip past each other, F-2's occlusion story has nothing to occlude against.
- **`SceneContent` owns the screen-regime stack and it is sticker-only.** `drawStickers` sorts
  `screenAnchored` by `screenCompositeZ` and draws them all through one program
  (`StickerPipeline.kt:165-183`). Adding models to the screen regime turns that into a **heterogeneous**
  ordered stack with program switching per element, and the merged ordering must match
  `CONTEXT.md:212-214` — stickers in list order, then models in list order, later entries on top. Note
  `MercatorSpatialPlanner.screenCompositingOrder` already computes exactly this
  (`MercatorSpatialPlanner.kt:246-250`) while `SceneContent` re-derives its own; F-2 should thread the
  planner's ordering through rather than growing a second copy.

### 5.3 Occlusion and ADR 0024

ADR 0024 fixes: map regime first, depth-tested; screen regime composites on top as a single ordered stack
**with no depth test at all**. `drawStickers` implements that literally —
`enable(GL_DEPTH_TEST)` for map-anchored, `disable(GL_DEPTH_TEST)` then the sorted screen stack
(`StickerPipeline.kt:178-182`).

For a flat quad, "no depth test" is correct. **For a 3D model it is a correctness bug** — see §6.1.

---

## 6. The honest risks — what gets discovered late

Ordered by how expensive each is to discover in week three rather than now.

### 6.1 A screen-anchored model has no self-occlusion (highest risk)

ADR 0024's screen regime disables the depth test outright, and in GL disabling `GL_DEPTH_TEST` also
disables depth *writes*. A screen-anchored `Model` drawn under that rule renders its back faces over its
front faces in whatever order its primitives happen to be indexed. It is not a subtle artifact; it looks
broken.

The rule is not wrong — ADR 0024 is about the relationship *between* regimes and it reasons that out
carefully. What it never considered is that a screen-regime *element* could itself be volumetric. The
tension is genuine:

- A screen-anchored model needs a depth test **within itself**.
- It must **not** depth-test against the map regime (ADR 0024) nor against other screen-regime elements
  (their order is the z-index stack).

Available answers, all of which cost something: clear the depth buffer before each screen-anchored model
(N clears per frame, and it discards the map regime's depth, which is acceptable only because the screen
regime never reads it); carve `glDepthRange` sub-slices per z-index (finite precision, and `depthRangef`
is on the seam at `GlBinding.kt:100` and captured/restored at `GlStateSnapshot.kt:130`); or refuse
depth-tested screen-anchored models and document it.

**This is the "the inherited ordering is impossible" candidate.** It is an ADR-level decision, it belongs
before implementation, and it is invisible until the first screen-anchored model is rendered.

### 6.2 The subset says "supported" for documents the renderer cannot draw

§2.2 and §2.3 together: a primitive with **no `POSITION`**, an accessor with **`count = 0`** or a
**negative `byteOffset`/`count`**, an **indices accessor that is not `SCALAR` unsigned**, an index count
that is **not a multiple of three**, a **`matrix` on an animated node**, and a **`POSITION` with a
non-`FLOAT` component type** all pass both shipped gates today. The first three are memory-safety issues
the moment decoding exists; the rest are "no defined behaviour".

Tightening these is free *now* and a documented compatibility break *after* the first release that draws
models — ADR 0021's own "narrowing is not a compatible change" reasoning applies to itself. This is the
single most valuable thing to fix before F-2's plan is written.

### 6.3 RenG has no lighting vocabulary, and `NORMAL` implies one

`CONTEXT.md` contains no term for a light, a radiance, an ambient level, or a shading model — grep finds
nothing. The only occurrence of "radiance" in the repository is one line in `docs/decomposition.md:183`
describing something Rentile hands over for the **ground** in E-terrain.

Yet ADR 0021 accepts `NORMAL` on the stated ground that it is "needed for any lit shading; absent means
flat", and retains the full PBR material block. So F-2 must decide, with no vocabulary and no ADR to
inherit:

- Unlit base-colour shading (`baseColorFactor × baseColorTexture × COLOR_0`)? Then `NORMAL` is parsed and
  unused, every model renders as a flat silhouette, and consumers with authored PBR assets will report it
  as a bug.
- Some fixed implicit light? Then RenG has invented an undocumented lighting model that a later cycle
  must either keep forever or break.
- A real light in the public API? That is new public ABI in a cycle whose domain model was supposed to be
  settled in Cycle B.

None of these is obviously right, and the choice determines the shader, the uniforms, and how the result
looks. It is a design decision masquerading as an implementation detail.

### 6.4 Alpha modes fight premultiplication, depth, and the composite pass

`uploadTexture(TextureContent.IMAGE)` premultiplies alpha and `drawStickers` pairs it with
`GL_ONE, GL_ONE_MINUS_SRC_ALPHA` (`GlTextureUpload.kt:55-60`, `StickerPipeline.kt:170-172`). glTF's three
alpha modes each want something different:

- `OPAQUE` — blending **off**, alpha forced to 1. Straightforward, but the offscreen surface is RGBA and
  the composite pass blends by alpha (`GlFrameDrawer.kt:78-80`), so an opaque model must still write
  `alpha = 1` or it will composite as partially transparent.
- `MASK` — `discard` below `alphaCutoff` in the fragment shader, blending off. Needs the cutoff as a
  uniform and interacts badly with linear filtering of a premultiplied texture (the premultiplied RGB is
  fine; the *alpha* used for the cutoff is the filtered one, which softens the mask edge).
- `BLEND` — needs back-to-front sorting per primitive per frame *and* depth writes disabled for blended
  primitives while depth testing stays on. That is a two-pass split (opaque primitives first with depth
  writes, then blended primitives sorted, depth-test-on/depth-write-off) inside every model.

`doubleSided` adds culling: `drawFrame` sets `frontFace(GL_CCW)` and `cullFace(GL_BACK)` but **never
enables `GL_CULL_FACE`** (`GlFrameDrawer.kt:65-66`), so culling is currently off for all content. And
glTF requires the winding order to be **reversed** when a node's global transform has a negative
determinant (mirrored scale) — a real case in exported assets, and one that produces inside-out models
with no error if missed.

### 6.5 Parse cost is paid two-to-three times per model per frame

`PARSE_GLB` and `VALIDATE_GLB_FEATURES` each independently re-run `scanGlb` + `parseGltf`
(`ClassGateRunner.kt:44-50`), and `ResidentGeneration` has no slot to hold the result
(`ResidentCache.kt:14-25`). Whatever F-2 does — decode at prepare time, decode at upload time — it starts
from raw bytes and parses a third time unless a parsed-model residency is added. The precedent already
exists in the codebase and is *not* encouraging: `RenGRenderer` re-decodes every sticker PNG on every
`prepare()` (`RenGRenderer.kt:580-601`) because `decoded` is always installed as `null`
(`ResourceActionExecutor.kt:263`). A 256 MiB-ceiling GLB is a much worse thing to re-parse than a sticker
PNG. There is a comparable precedent for measuring rather than assuming: the basemap style's
double-parse was measured and recorded rather than waved away (`RenGRenderer.kt:616-622`).

### 6.6 Public ABI growth in a cycle that may not expect it

Cycles B through F-1 all report "no public ABI change" as a gate. F-2 probably cannot: making model GPU
objects and embedded model images visible to `ResourceReport` / `ResourceSelector.ByKind` needs new
`ResourceKind` entries (`Resources.kt:52-58`), which is a published enum with a canonical wire mapping
(`ResourceKeyDerivation.kt:170-176`) and an exhaustive-`when` source break for consumers. Worth deciding
deliberately: either accept the ABI change (the MVP release is explicitly internal, per
`docs/decomposition.md:59-61`), or accept that model GPU memory is invisible to the resource-report API,
which contradicts the lifecycle contract's promise that "RenG exposes API to query and free the resources
it holds."

### 6.7 Smaller, but each costs a day

- `GL_UNSIGNED_SHORT`, `GL_REPEAT`, `GL_MIRRORED_REPEAT` and the mipmap filters are absent from
  `GlTokens.kt`; `uniformMatrix3fv` is absent from `GlBinding` (§3.2). Trivial individually, but each
  touches **four** platform bindings plus `GlEntryPoint` plus `GlEntryPointRosterTest`.
- `UNSIGNED_BYTE` indices are legal glTF and not drawable on a desktop core profile; they must be widened.
- No quaternion type and no slerp anywhere in `internal/math/` (§3.4, §4.3).
- Animation duration requires reading the BIN chunk because accessor `min`/`max` are not retained (§2.3).
- Selector resolution must report at `RESOURCE_PARSING`, not `FRAME_PLANNING`, because the allowlist says
  so (`DiagnosticFactories.kt:128-154`) — which constrains where in the pipeline the check can live.
- `ResourceUsage.decodedCpuBytes` should carry the *expanded* geometry figure, not raw bytes
  (`2026-08-18-cycle-c-glb-parse.md:466-469`).

---

## 7. Suggested sizing posture

Three things are worth settling **before** a plan is written, because each is a decision rather than an
implementation:

1. **ADR: depth handling for volumetric content in the screen regime** (§6.1). Amends or extends ADR 0024.
2. **ADR or ADR-0021 amendment: tighten the subset to semantic-level accessor constraints and add the
   missing structural rules** (§2.3, §6.2). Free now; a compatibility break later.
3. **ADR: the shading model, and whether RenG acquires any lighting vocabulary** (§6.3).

Everything else — accessor decoding, node transforms, quaternion slerp, the model pipeline, the material
mapping — is ordinary work with clear precedents in `StickerPipeline` and `GeometryPipeline`. The parser
is a genuine asset: the structural half of the job is done and proved against 118 real assets. What is
missing is the numeric half, which has never been started, and three decisions that are cheap today and
expensive after the first release that draws a model.
