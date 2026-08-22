# F-2 preflight — draw regimes, the transform pipeline, depth, and what a model pass must establish

Read-only survey of `feat/cycle-e-basemap` (HEAD `e0e932e`), which contains every `feat/f1-*` branch plus
the basemap work merged to date. No code was changed. Every claim below carries a `file:line`.

**One correction to the brief up front.** The brief assumes "a basemap ground now drawn". It is not drawn.
On this branch the ground is *acquired* — Rentile renders each canonical tile to PNG bytes and those bytes
ride on the prepared frame — but nothing decodes, uploads or draws them. `RenGRenderer.kt:149-152` says so
in its own words: "The bytes are encoded PNG, exactly as `BasemapRasterizer.render` produced them:
decoding them, uploading them and drawing the ground are later tasks, and there is no basemap draw path in
`internal/gl/` yet." `performDraw` (`RenGRenderer.kt:796-858`) never reads `frame.basemapTiles`; the
`Scene` it builds carries only stickers and geometries (`RenGRenderer.kt:842-847`). `decodePng` is called
for sticker/geometry images, the class gates and sprite validation only — never for a rendered tile
(`RenGRenderer.kt:584`, `ClassGateRunner.kt:61,117`, `OperationRegistry.kt:465`). So the ground pass is
still unwritten, and several of the questions below are really questions about a pass that F-2 will meet
half-built or not built at all.

---

## 1. What the MVP actually built for draw regimes

### 1.1 ADR 0024's governing decision, quoted

`docs/adr/0024-draw-the-map-regime-before-compositing-the-screen-regime.md:3-9`:

> `CONTEXT.md` already fixes ordering *within* each draw regime — greater z-index composites on top, and
> ties break by stable plan order (stickers before models, later entries on top within each list). What
> stayed open until now was the rule *between* the two regimes: when a screen-anchored sticker and a
> map-anchored model both want the same region of the frame, which one wins. **The answer is that the map
> regime draws first, depth-tested against the whole 3D scene, and the screen regime then composites on top
> as a single ordered stack, with no depth test against the map regime at all.**

The ADR rejects splitting the screen regime by the sign of its z-index, because that "would overload the
sign of one number with two unrelated meanings" (lines 11-20). It also carries a second, unrelated ruling
worth reading before F-2 touches any documented name: renaming a documented uniform or attribute is a
**silent** breaking change, because ADR 0008 binds a name only when the program declares it, so an old name
keeps compiling and simply stops receiving its value (lines 26-35).

The ADR closes with the load-bearing forward claim (lines 22-24): "This rule is fully testable in the cycle
that introduces it even though the basemap itself has not shipped yet, because the map regime already
contains map-anchored things depth-testing one another; the basemap is one more depth-tested surface
joining that same regime later, and drawing it changes nothing about this ordering." Section 5 below tests
that claim and finds it true for *ordering* and false for *visibility*.

### 1.2 Where each drawn thing lands, and its depth state

There are exactly three draw passes in a frame, all inside `drawFrame` (`GlFrameDrawer.kt:38-97`).

| Pass | Code | Regime | Depth test | Depth write | Blend | Cull |
|---|---|---|---|---|---|---|
| Geometries | `SceneContent.kt:134-152` → `GeometryPipeline.kt:231-309` | map | **on**, `GL_GREATER` | **on** | **inherited from caller** | mode pinned, **enable inherited** |
| Map-anchored stickers | `SceneContent.kt:154-183` → `StickerPipeline.kt:178-179` | map | **on**, `GL_GREATER` | **on** | `GL_ONE / GL_ONE_MINUS_SRC_ALPHA` | mode pinned, **enable inherited** |
| Screen-anchored stickers | `StickerPipeline.kt:180-182` | screen | **off** | off (bypassed) | same premultiplied func | as above |
| Composite to target | `GlFrameDrawer.kt:71-89` | n/a | off | `depthMask(false)` | `GL_SRC_ALPHA / GL_ONE_MINUS_SRC_ALPHA` | `disable(GL_CULL_FACE)` |

**Placement.** A `Geometry` has no `Placement` at all (`CONTEXT.md:265` — "A **Geometry** carries no
**Placement**"), so it is map-anchored by definition and its `uModelViewProjection` is exactly the camera's
`projection * view` with no model term (`SceneContent.kt:257-264`). Its four vertices arrive already
resolved into camera-relative logical pixels by `resolveGeometry` and are narrowed to `Float` only in the
last step (`SceneContent.kt:228-243`). A `Sticker` carries a full `Placement` and is resolved through
`resolvePlacement` (`SceneContent.kt:157`).

**Regime selection is by `positionMode` alone**, and that is the specified behaviour, not a shortcut.
`PlacementResolver.kt:45-86` sets `drawRegime = MAP_OCCLUDED` in the `positionMode == MAP` branch and
`SCREEN_COMPOSITED` in the `SCREEN` branch, and never consults `rotationMode` or `scaleMode`.
`CONTEXT.md:555-556` states the rule ("Position anchoring selects the whole drawn thing's **Draw Regime**
… Rotation and scale anchoring select their transform basis and units without changing the **Draw
Regime**"), and `CONTEXT.md:594-597` spells out the exact `MAP` position + `SCREEN` rotation case as "the
map-occluded regime, because position anchoring selects the regime … producing an occluded billboard".

**Ordering between regimes is achieved by call order plus one `enable`/`disable` pair.**
`SceneContent.draw` (`SceneContent.kt:131-184`) draws every geometry first under
`binding.enable(GL_DEPTH_TEST)` (line 135), then partitions stickers into two `ArrayList`s by regime
(lines 154-181), then hands both to `drawStickers`. `drawStickers` (`StickerPipeline.kt:165-183`) does:

```kotlin
binding.enable(GL_DEPTH_TEST)
world.mapAnchored.forEach { drawOneSticker(binding, pipeline, it) }
binding.disable(GL_DEPTH_TEST)

world.screenAnchored.sortedBy { it.screenCompositeZ }.forEach { drawOneSticker(binding, pipeline, it) }
```

That is the entire between-regime mechanism: one `disable(GL_DEPTH_TEST)` at line 180 separates the two
halves of a single function. `SceneContentTest.kt:42-73` pins it by asserting index ordering in the
recording binding's call log around that one `disable`.

Two consequences of the state table above that F-2 inherits:

- **Depth writes are on for the whole map regime and blending is on for map-anchored stickers.**
  `StickerWorld`'s own KDoc claims `mapAnchored` "draws depth-tested, in any order — the GPU depth buffer
  decides visibility between map-anchored things, not draw order" (`StickerPipeline.kt:143-149`). That is
  true only for opaque content. With `GL_BLEND` on (line 170) and `depthMask(true)` still standing from
  `GlFrameDrawer.kt:59`, a partially transparent sticker writes depth for its transparent texels too, so
  two overlapping translucent map-anchored things *are* order-dependent, and a nearer transparent one
  permanently hides a farther one. Models with glTF `alphaMode = BLEND` make this routine rather than
  exotic.
- **Screen-regime depth writes are correctly suppressed** — not by `depthMask`, which is never lowered for
  that half, but by the GL rule that disabling `GL_DEPTH_TEST` also bypasses depth-buffer updates. It works
  today; it is worth knowing it is load-bearing, because any F-2 change that re-enables the depth test in
  the screen regime (see §6.2) instantly starts writing depth unless `depthMask(false)` is added.

---

## 2. The transform pipeline as built

### 2.1 The resolver genuinely resolves the three properties independently

`resolvePlacement` (`PlacementResolver.kt:36-128`) has three separate `when (placement.*Mode)` blocks:

- **Position** (lines 45-86): `MAP` projects through `validateMercatorMapPosition` and
  `resolveCameraRelativeMapPosition` into camera-relative logical pixels; `SCREEN` takes
  `(position.x, position.y, 0)` in output-pixel space and stashes `position.z` as `screenCompositeZ`.
- **Rotation** (lines 88-108): `SCREEN` yields the bare `localRotation`; `MAP` yields
  `viewBasis * cameraWgs84Basis.transpose() * anchorWgs84Basis * localRotation` — i.e. the anchor's ENU
  frame carried into camera space.
- **Scale** (lines 110-114): `SCREEN` passes `placement.scale` through; `MAP` converts metres to logical
  pixels with `scale * worldSizeLogicalPixels / (WORLD_CIRCUMFERENCE_METRES * cos(anchorLatitude))`.

All eight mode combinations are covered by
`PlacementResolverTest.kt:32-105` (`allEightAnchorCombinationsResolveEachPropertyIndependently`), plus
targeted tests for `SCREEN` position with `MAP` rotation and `MAP` scale
(`PlacementResolverTest.kt:170-244`). The `MAP` position + `SCREEN` rotation case is not a special case in
the code at all — it falls out of the two independent branches.

The **composition** layer preserves that independence rather than re-deriving it, because the resolver
hands it pre-baked quantities. `composeMapModelViewProjection` (`SceneContent.kt:286-302`) transforms the
anchor into view space with the view matrix, then applies `directionTransform` and `logicalScale` **in
camera space** — never re-multiplying the view rotation, which the KDoc at lines 266-284 explains would
double-apply it. So `SCREEN` rotation under `MAP` position becomes a rotation expressed directly in camera
axes, which is precisely a billboard, matching `CONTEXT.md:596-597`.

`composeScreenModelViewProjection` (`SceneContent.kt:319-335`) does the same in pixel space, with
`SCREEN_ROTATION_ROW_SIGN = (1, -1, -1)` (line 338) reconciling screen-space y-down against the drawn
thing's y-up local axes. `affineModelMatrix` (`SceneContent.kt:351-378`) applies scale per *column*, so
per-axis local dimensions (a sticker's image pixel size) and the placement's uniform scale compose without
either being lost.

**Verdict: the transform pipeline is not the shortcut.** Mode-independence is real, at both layers.

### 2.2 The shortcuts that *are* there

**(a) The GL layer re-derives the screen ordering and re-derives it more weakly than the pure core.**
`planMercatorSpatial` already computes the complete, correct, heterogeneous ordered stack:
`MercatorSpatialPlanner.kt:161-171` walks stickers then models, partitions by regime, and sorts
`screenEntries` with

```kotlin
private val screenCompositingOrder: Comparator<ResolvedDrawnThing> =
    compareBy<ResolvedDrawnThing> { requireNotNull(it.placement.screenCompositeZ) }
        .thenBy { it.reference.typeOrder }     // StickerAt = 0, ModelAt = 1
        .thenBy { it.reference.sourceIndex }
```

(`MercatorSpatialPlanner.kt:244-259`), and `MercatorSpatialPlan`'s own `init` asserts the result is in
ascending compositing order (`MercatorSpatialPlanner.kt:67-73`). That is exactly `CONTEXT.md:207-215`'s
rule, models included.

**That plan is then thrown away.** `RenGRenderer` reads only `planned.spatialPlan.tileSelection` and
`planned.spatialPlan.lodObservation` (`RenGRenderer.kt:407,443`); `RenGPreparedFrame`
(`RenGRenderer.kt:136-174`) retains camera, stickers, geometries and basemap tiles, and no spatial plan.
`SceneContent` therefore re-runs `resolvePlacement` at draw time (`SceneContent.kt:157`, justified at
lines 102-116) and re-sorts with `sortedBy { it.screenCompositeZ }` (`StickerPipeline.kt:182`). Kotlin's
`sortedBy` is stable, so for a sticker-only frame this reproduces the core's rule exactly — because every
entry has `typeOrder == 0` and stable order *is* source order. Add models and it stops reproducing it,
because a model and a sticker at equal z must break sticker-first regardless of which list they came from.

**(b) The two-regime ordering is structurally owned by a sticker function.** `StickerWorld`
(`StickerPipeline.kt:151-154`) and `drawStickers` (`StickerPipeline.kt:165-183`) hold both halves of ADR
0024's ordering inside one drawn-thing type's pipeline, and `SceneContent.draw`'s own KDoc leans on that:
"`drawStickers` already runs both sticker halves of that order in one call, so `draw` only has to draw
every geometry *before* calling it" (`SceneContent.kt:96-100`). Models cannot join that structure: the
screen regime becomes one ordered stack spanning two programs (sticker quad program and model program),
so the sort and the regime split must move up into `SceneContent` — and the honest fix is to consume the
core's `screenEntries` rather than to teach the GL layer `typeOrder` a second time.

**(c) Mixed modes are entirely untested at the GL layer.** Every `Placement` in `SceneContentTest.kt` and
`StickerPipelineTest.kt` uses `rotationMode = SCREEN` and `scaleMode = SCREEN`
(`SceneContentTest.kt:255-262, 317-323, 369-387`). Not one test drives `MAP` rotation or `MAP` scale
through `composeMapModelViewProjection` / `composeScreenModelViewProjection`. The resolver is well tested;
the matrices built from its mixed-mode output are not tested at all. Note that
`mapAnchoredStickersAtDifferentPositionsShareTheSameRotationScaleBlock`
(`SceneContentTest.kt:275-296`) asserts an invariant that is *only* true under `SCREEN` rotation — with
`MAP` rotation `directionTransform` depends on the anchor's own ENU basis, so the rotation-and-scale block
legitimately differs between two positions. Adding a mixed-mode case there means writing a different
assertion, not extending that one.

**(d) `SCREEN` scale under a `MAP` position is only nominally "output pixels per local unit".**
`CONTEXT.md:200-205` defines screen-anchored scale as "output pixels per local unit". Under `MAP` position
the scale is applied in camera space (`SceneContent.kt:295-300`) and then divided by the perspective `w`,
so apparent size falls off with distance. The identity holds *exactly* at one depth: with
`cameraDistance = height * FOCAL_LENGTH_SCALE / 2` (`CameraMatrices.kt:65`) and the projection at
`CameraMatrices.kt:76-83`, one camera-space logical pixel maps to exactly one output pixel at
`d = cameraDistance` and to `cameraDistance / d` output pixels elsewhere. For a ground-level sticker near
the view centre nobody notices. A model at altitude, or a model under a pitched camera, makes it visible.
This is an unresolved semantic, not obviously a bug — but F-2 is where somebody first sees it, so it should
be settled deliberately rather than discovered.

### 2.3 One thing that is right and worth not re-litigating

`MAP` scale and `MAP` altitude use the **identical** metres→logical-pixel factor.
`projectMercator` sets `z = altitudeMetres / (WORLD_CIRCUMFERENCE_METRES * cos(lat))`
(`MercatorProjection.kt:51`), `resolveCameraRelativeMapPosition` multiplies by `worldSizeLogicalPixels`
(`PlacementResolver.kt:140`), and `logicalScale` for `MAP` mode is
`scale * worldSizeLogicalPixels / (WORLD_CIRCUMFERENCE_METRES * cos(lat))` (`PlacementResolver.kt:112-113`).
So a GLB authored in metres, placed with `MAP` scale at `MAP` altitude, gets a consistent height relative
to its altitude with no fudge factor. That is the single most load-bearing correctness property a model
needs from the existing pipeline, and it is already true.

---

## 3. Depth and occlusion

### 3.1 The depth buffer that exists

`createOffscreenSurface` (`OffscreenSurface.kt:77-141`) allocates:

- colour: `GL_RGBA8` texture, immutable `glTexStorage2D`, `GL_LINEAR`, `GL_CLAMP_TO_EDGE`;
- depth: a **`GL_DEPTH_COMPONENT24` renderbuffer** (`OffscreenSurface.kt:15-20, 53-59, 105-111`);
- **no stencil attachment at all**, which ADR 0005 states as a deliberate consumer-facing promise: a
  Render Target "needs no depth attachment, no stencil, and no particular format".

Allocated once, never resized (ADR 0012 / `OffscreenSurface.kt:33-38`).

### 3.2 The depth convention

Reverse-Z with an infinite far plane. `CameraMatrices.kt:76-83` builds a projection whose z row is
`(0, 0, 1, 2·near)` over `w = -z_view`, giving window depth `= near / distance`: `+1` at the near plane
and `0` at infinity. `GlFrameDrawer.kt:14-19` documents it, and `drawFrame` clears depth to
`REVERSE_Z_FAR_DEPTH = 0.0f` and tests `GL_GREATER` (`GlFrameDrawer.kt:61-64`).

**Reverse-Z over a fixed-point 24-bit buffer does not buy the precision reverse-Z normally buys.** The
usual pairing is reverse-Z with a *floating-point* depth buffer, where the float's own exponent
distribution cancels the projection's hyperbolic one. With `GL_DEPTH_COMPONENT24` the quantization is
uniform, so the resolvable distance step is `d² / near × 2⁻²⁴` logical pixels — about 0.06 logical pixels
at 1 000, about 1.5 at 5 000, about 600 at 100 000. That is fine for content well separated in depth and
marginal for anything coplanar or near-coplanar with the ground (§5). It is not a defect to fix in F-2, but
it is the number to reach for the first time a model z-fights.

### 3.3 What each existing pass sets, and what it inherits

`drawFrame` establishes, before content (`GlFrameDrawer.kt:49-67`): unpack alignment/row-length/skip
rows/skip pixels, pixel-unpack buffer, draw framebuffer, viewport, `disable(GL_SCISSOR_TEST)`,
`colorMask(true,true,true,true)`, `depthMask(true)`, clear colour, clear depth, `clear`,
`enable(GL_DEPTH_TEST)`, `depthFunc(GL_GREATER)`, `frontFace(GL_CCW)`, `cullFace(GL_BACK)`, and
`GL_FRAMEBUFFER_SRGB` disabled where queryable.

Three things it conspicuously does **not** establish:

1. **`GL_CULL_FACE` enable.** The mode is pinned (`cullFace(GL_BACK)`, `frontFace(GL_CCW)`) but the enable
   is never set for the content pass. A repository-wide grep finds `enable(GL_CULL_FACE)` only in the test
   conformance suite (`GlConformanceSuite.kt:332`); production never calls it. Today this is harmless
   *by accident*: every quad F-1 draws is wound CCW under both composition paths, so culling on or off with
   `GL_BACK`/`GL_CCW` gives the same pixels. It stops being harmless the moment a mesh has a
   `doubleSided` material or a node whose matrix has negative determinant, both of which flip winding.
2. **`glDepthRange`.** It is captured and restored (`GlStateSnapshot.kt:130, 208`) but never *set*
   (`depthRangef` appears nowhere else in `commonMain`). A caller who left a non-default or reversed depth
   range in place changes RenG's depth mapping under it.
3. **Blend state for the geometry pass.** `drawFrame` sets blend only for the composite pass
   (`GlFrameDrawer.kt:78-80`); `drawStickers` sets it for stickers (`StickerPipeline.kt:170-172`);
   `drawGeometry` sets none. A consumer geometry draws under whatever blend state the caller left.

The generalisation matters for F-2: **the Restore Set is a restore set, not an establish set.** ADR 0006
guarantees RenG modifies nothing outside the set; it does not guarantee RenG's own output is independent of
what the caller left inside it. F-1 established most of what it needed and missed the three above.

### 3.4 The Restore Set (ADR 0023) as implemented, and its one real hole

`GlStateSnapshot.kt:28-68` is the snapshot type; `captureGlState` at 88-150 and `restoreGlState` at 180-251.
ADR 0023 supersedes ADR 0006's list and adds the colour write mask, all four unpack pixel-store parameters,
pack alignment, both clear values, the array buffer binding and the pixel unpack buffer binding; it notes
the element array buffer needs no explicit restore because it is per-VAO state
(`docs/adr/0023-…:3-10`). `GL_ACTIVE_TEXTURE` is captured first and reinstated last, with every per-unit
read nested inside (lines 12-15). `GL_DRAW_BUFFER` and `GL_LINE_SMOOTH` are desktop-only queries; the
`glGetError` queue is the one declared exception to the no-modification guarantee (lines 27-33).

**The hole: only texture unit 0 is captured and restored, while `drawGeometry` binds up to fifteen.**
`drawFrame` calls `withCapturedGlState(binding, profile, COMPOSITE_TEXTURE_UNIT_COUNT)`
(`GlFrameDrawer.kt:48`) and `COMPOSITE_TEXTURE_UNIT_COUNT = 1` (`CompositePipeline.kt:47`). But
`drawGeometry` walks a geometry's consumer textures and does `activeTexture(GL_TEXTURE0 + unitIndex)` +
`bindTexture` for each (`GeometryPipeline.kt:299-306`), with `MAXIMUM_CONSUMER_TEXTURES = 15`
(`GeometryPipeline.kt:64`). Units 1..14 are therefore clobbered and never restored, which contradicts ADR
0006's "the active texture unit and the bindings on the units RenG uses". No test catches it: the
real-context round trip in `GlConformanceSuite.kt:544-561` drives `drawFrame` with a trivial
clear-only content lambda, never with a `SceneContent`, and uses `CONFORMANCE_TEXTURE_UNITS = 2`
(`GlConformanceSuite.kt:36`).

This is a pre-existing F-1 defect, but F-2 makes it worse and cannot avoid touching it: a PBR-ish model
material binds base colour, metallic-roughness, normal, occlusion and emissive.

### 3.5 What a model pass must establish that no existing pass does

1. **`GL_CULL_FACE`, explicitly on or off, per primitive.** glTF `doubleSided` is already parsed and
   retained (`GltfDocument.kt:87-96`), so the data is there and the GL state is not.
2. **A winding rule that survives negative-determinant node transforms.** glTF nodes may carry a `matrix`
   or a TRS `scale` with negative components (`GltfDocument.kt:46-55`); either flips triangle winding and
   requires either `frontFace` inversion for that node or an explicit cull disable.
3. **Blend and depth-write state per alpha mode.** `GltfMaterial.alphaMode` / `alphaCutoff` are parsed
   (`GltfDocument.kt:93-94`). `OPAQUE` wants blend off and depth write on; `MASK` wants a shader `discard`
   with depth write on; `BLEND` wants blend on and, to be correct at all in the map regime, depth write
   **off** plus back-to-front ordering — which is exactly the ordering `StickerWorld`'s KDoc says the map
   regime does not need (`StickerPipeline.kt:143-146`).
4. **Index buffers, and validated index values.** `GlBinding.drawElements` exists
   (`GlBinding.kt:110`) and the element-array binding rides on the VAO, so the seam is there — but
   `parseGltf` receives only `binChunkLength`, never the BIN bytes (`GltfParse.kt:35, 44, 120`), and
   `GltfDocument`'s own KDoc warns that "a `Parsed` result does not by itself guarantee every index value
   is in range" (`GltfDocument.kt:111-119`, `GltfReject.INDEX_VALUE_OUT_OF_RANGE` at
   `GltfDocument.kt:173-182`). Feeding an unvalidated index buffer to `glDrawElements` is undefined
   behaviour. **F-2 must add a value-level index bounds check no existing gate performs.**
5. **Per-node model matrices.** Every existing pass sets exactly one `uModelViewProjection` per draw
   (`GeometryPipeline.kt:273-275`, `StickerPipeline.kt:186-193`). A model is a node tree
   (`GltfDocument.kt:46-57, 120-134`) so each mesh-bearing node needs its own composed matrix, and the
   `Placement` matrix becomes the tree's root rather than the whole transform.
6. **Texture sampler state per glTF sampler.** `uploadTexture` hardcodes `GL_CLAMP_TO_EDGE` on both axes
   and no mipmaps (`GlTextureUpload.kt:52-53, 78-85`, rationale at lines 34-53). glTF `GltfSampler` carries
   `wrapS`/`wrapT` defaulting to `REPEAT` and its own min/mag filters (`GltfDocument.kt:104-105`). Tiled
   UVs are ordinary in models and would render wrong under clamp; minified model textures without mipmaps
   alias badly. `generateMipmap` is already on the seam (`GlBinding.kt:41`); the upload function needs
   parameters it does not have.
7. **A wider `withCapturedGlState` texture-unit count**, per §3.4.
8. **A normal matrix, if the model shader lights anything.** `GlBinding` has no `uniformMatrix3fv`
   (`GlBinding.kt:78-91`), so either the seam grows a method across all four platform bindings plus the
   recording fake and the conformance roster, or the normal matrix travels as a `mat4`.

---

## 4. Uniforms and shaders

### 4.1 The documented set, verified

`GeometryPipeline.kt:24-27, 41-42` defines exactly six names, and `RESERVED_SHADER_NAMES`
(`GeometryPipeline.kt:45-52`) is the same six:

| Kind | Name | Type | Source |
|---|---|---|---|
| attribute | `aPosition` | `vec3` | `GeometryPipeline.kt:24` |
| attribute | `aTexCoord` | `vec2` | `GeometryPipeline.kt:25` |
| uniform | `uModelViewProjection` | `mat4` | `GeometryPipeline.kt:26` |
| uniform | `uResolution` | `vec2` | `GeometryPipeline.kt:27` |
| uniform | `uGeometryBounds` | `vec4` (W,S,E,N degrees, **informational only**) | `GeometryPipeline.kt:41` |
| uniform | `uFrameIndex` | `uint` | `GeometryPipeline.kt:42` |

So the brief's list is right, plus the two attributes. `CONTEXT.md:282-294` states the same set as the
**Shader Interface**. Every one is bound only when the program declares it (`GeometryPipeline.kt:128-165`
for creation, `273-290` for draw) — and for attributes that guard is load-bearing for correctness, not
only for the contract, because enabling a negative attrib index is a real GL error
(`GeometryPipeline.kt:124-127`).

**This vocabulary is for consumer geometry shaders only.** A model's shading is RenG's, so a model program
is an *internal* pipeline like the composite and sticker programs, not a member of this interface — which
is fortunate, because none of ADR 0024's rename hazard applies to names RenG never publishes.

### 4.2 Skinning is out of scope by ADR, not merely unbuilt

The brief says "possibly skinned". ADR 0021 rejects it. `docs/adr/0021-fix-the-supported-glb-subset.md`
lists under Rejected: "sparse accessors, strips and fans and point and line modes, `TEXCOORD_n` and
`COLOR_n` above zero, **joints and weights, skins, morph targets**, `weights` animation targets, JPEG
images, any non-empty `extensionsRequired`, and any buffer or image carrying a `uri`." Accepted animation
is "translation, rotation and scale animation channels with `LINEAR` and `STEP` interpolation" —
`CUBICSPLINE` explicitly rejected rather than approximated.

The corpus check that ADR 0021 owed has been run and is recorded at
`docs/research/2026-08-19-glb-feature-subset-corpus-check.md`: 118 Khronos `.glb` files, 52 supported, and
the nine skinned characters (`BrainStem`, `Fox`, `RiggedFigure`, …) rejected — the document notes those
nine now report `SKIN` rather than `ATTRIBUTE_SEMANTIC` after an ordering fix. So **F-2's animation is
node-TRS animation, and the model shader needs no joint matrices, no skinning attributes, and no
`uniformMatrix4fv` array upload** unless somebody reopens ADR 0021.

Note also that animation is currently *only* hashed, never sampled: `AnimationTrack` appears in
`FramePlanCanonicalEncoding.kt:108-125` and nowhere else in `commonMain`. Every part of sampling — reading
accessor bytes, `timeSeconds % durationSeconds`, applying channels in list order — is unbuilt.

### 4.3 What a model shader needs, and whether the existing machinery carries it

A rigid, textured, node-animated model needs roughly: `aPosition` (`vec3`), `aNormal` (`vec3`),
`aTexCoord0` (`vec2`), optionally `aTangent`/`aColor0`; uniforms for the node's model-view-projection, a
normal matrix, `baseColorFactor`, `metallicFactor`/`roughnessFactor`, `emissiveFactor`, `alphaCutoff`, and
a small set of `sampler2D`s. All of that is plain GLSL ES 3.00.

**The program cache and dialect substitution carry it unchanged.** `ShaderProfilePlan`
(`ShaderProfilePlanner.kt:3-49`) replaces exactly the `#version 300 es` physical line with
`#version 330 core` and nothing else; `sourceFor(dialect)` (`GlShaderCompiler.kt:28-31`) picks between
them; `compileShaderProgram` (`GlShaderCompiler.kt:48-89`) is entirely generic; `GlProgramCache`
(`GlProgramCache.kt:6-33`) is keyed only by `ResourceKey`. RenG's own composite and sticker sources already
travel that identical path, deliberately, so it is exercised on every context — see
`CompositePipeline.kt:15-22` and `StickerPipeline.kt:10-19`. `precision highp float;` and
`layout(location = …)` on `in`/`out` are already in the sticker source (`StickerPipeline.kt:20-39`) and are
legal in `330 core`, so the existing sources are the proof that a model shader in the same style compiles
on both dialects.

Two mechanical additions:

- `InternalPipelineRole` has only `COMPOSITE(1)` and `STICKER(2)` (`CompositePipeline.kt:10-13`); a
  `MODEL(3)` entry is additive and leaves every existing derived key unchanged
  (`ResourceKeyDerivation.kt:69-90`).
- `ResourceKind` is **public** (`Resources.kt:53-59`) with wire values 1..5
  (`ResourceKeyDerivation.kt:170-177`). GL mesh buffers keyed under a new kind means a reviewed
  `checkKotlinAbi` diff; reusing the GLB's own `EXTERNAL`/`MODEL_GLB` key avoids that but makes
  `queryResources` less informative. That is a decision for the F-2 spec, not a discovery.

### 4.4 One dialect landmine already recorded

`docs/research/2026-08-19-mesa-cross-dialect-link-segfault.md` records that Mesa segfaults inside
`libgallium` when a process creates more than one EGL context and any of them performs a deliberate
cross-`#version` link; the owner-approved fix skips that negative link on Linux entirely. Relevant only if
F-2 adds new cross-dialect negative-path conformance tests — do not reintroduce that shape.

---

## 5. Ordering against the basemap

### 5.1 Where models sit

Unambiguous, and already decided twice. ADR 0024: the map regime draws first, depth-tested. The Cycle E
design spec (`docs/superpowers/specs/2026-08-20-cycle-e-basemap-design.md:57-60`): "`CONTEXT.md` defines a
basemap tile as 'drawn as the ground beneath a frame', and ADR 0024 fixes the regime order… **The ground
therefore draws first within the map regime. No new decision was required.**"

So the map regime's intended order is: ground → geometries → map-anchored models and stickers, all
depth-tested against one another; then the screen regime composites on top with no depth test; then the
whole offscreen surface composites into the caller's framebuffer.

### 5.2 Nothing in the current ordering assumes there is no ground — but the current *depth test* does

The ordering claim survives: `SceneContent.draw` has an obvious insertion point before line 134, the state
it needs (`GL_DEPTH_TEST` on, `GL_GREATER`, `depthMask(true)`, cleared to `REVERSE_Z_FAR_DEPTH`) is
already standing from `drawFrame`, and ADR 0024's forward claim that "drawing it changes nothing about
this ordering" is correct as far as ordering goes.

**Visibility is a different matter, and this is the sharpest thing in this document.** The depth
comparison is strict `GL_GREATER` (`GlFrameDrawer.kt:64`). A fragment at exactly the same window depth as
what is already there **fails** and is discarded. Once the ground exists at altitude 0:

- Any `Geometry` with `topLeft.z == bottomRight.z == 0.0` — the natural "paint this rectangle on the map"
  case, and the shape of every geometry fixture in the test suite (`SceneContentTest.kt:389-393` uses
  altitudes 10 and 0) — is coplanar with the ground over part or all of its extent and disappears or
  z-fights where it is coplanar.
- Any map-anchored **sticker** at altitude 0 under a top-down camera is a quad lying exactly in the ground
  plane, so it disappears entirely. Under a pitched camera with `SCREEN` rotation (the billboard case) it
  is half-buried instead, which is arguably intended and arguably not.
- Any **model** with faces resting on the ground z-fights along its contact footprint, and a flat
  decal-like model vanishes the way a geometry does.

No existing test can catch any of this, because there is no ground to be coplanar with. It will be
discovered the first time somebody renders a frame with `drawBasemap = true` and a sticker at altitude 0 —
which is the most ordinary frame a consumer will write.

The available answers are all ADR-shaped, and none of them is free:

- **`glPolygonOffset` on the ground pass.** Not on the GL seam at all (`GlBinding.kt` has no
  `polygonOffset`), and `GL_POLYGON_OFFSET_FILL` is neither captured nor restored by
  `GlStateSnapshot.kt:28-68`, so this needs both a seam addition across four platform bindings and an
  amendment to ADR 0023's Restore Set.
- **Draw the ground at a small negative altitude offset.** Cheap, no state change, but the offset has to be
  camera-distance-dependent under a 24-bit fixed-point reverse-Z buffer (§3.2) or it stops working when the
  camera pulls back.
- **`GL_GEQUAL` for content drawn after the ground.** Changes ties from "hidden" to "last writer wins",
  which is order-dependent among map-anchored things — directly contradicting `StickerPipeline.kt:143-146`.
- **Give the ground `depthMask(false)`.** Makes the ground never occlude anything, which is wrong the
  moment terrain displacement lands in E-terrain.

Whichever way it goes, it is a decision that belongs to whoever writes the ground pass, and F-2 will
inherit whatever they choose. **If the ground pass ships before the F-2 spec is written, this question
should be forced into that spec explicitly rather than left to the ground cycle to settle silently.**

---

## 6. The honest risks — what is most likely to be discovered late

Ordered by how expensive each is to discover after implementation has started.

### 6.1 A screen-anchored model cannot have depth at all under the current screen projection

`screenOrthographicProjection` (`SceneContent.kt:386-397`) is:

```
[ 2/w    0     0   -1 ]
[  0   -2/h    0    1 ]
[  0     0     0    0 ]      <-- clip.z is identically 0 for every vertex
[  0     0     0    1 ]
```

with the KDoc explaining that "`z` is fixed at `0` … because the screen regime always draws with depth
testing disabled". For a flat sticker quad that is correct and cheap. For a **model** it is fatal in two
compounding ways:

1. Every vertex of a 3D mesh collapses to the same clip-space z, so even turning the depth test back on
   would recover nothing — there is no depth information to test.
2. `drawStickers` disables `GL_DEPTH_TEST` for the whole screen half (`StickerPipeline.kt:180`), so a
   screen-anchored model's own back faces draw over its front faces in primitive order.

ADR 0024's letter says "no depth test **against the map regime**", which leaves room for intra-object
depth; the implementation leaves none. Supporting `positionMode = SCREEN` on a `Model` therefore requires
replacing that projection with one that maps a real z range, and reworking the screen half to give each
screen-composited object its own depth interval (a per-object depth clear, a partitioned `glDepthRange`,
or an ordered depth-func regime). That is a design decision, not an implementation detail, and it is
exactly the sort of thing that looks like a one-line matrix change until someone tries it.

**Mitigation to consider early:** decide whether `SCREEN`-positioned models are in F-2's scope at all. If
they are deferred, say so in the spec and reject them loudly; if they are in, this is a task of its own.

### 6.2 z-index compositing against a depth-tested ground

The interaction the brief asks about turns out to be safe in one direction and unsafe in the other.

- **Screen regime over the ground: safe.** The screen half runs with the depth test off, so it always
  composites over whatever the map regime left, ground included. That is ADR 0024's intent and it needs no
  work.
- **Screen regime *behind* the ground: impossible, deliberately.** ADR 0024's rejected alternative was
  exactly this, and its rejection stands: anything that must go behind the map uses map anchoring with an
  appropriate altitude.
- **Map regime against the ground: the real hazard**, and it is §5.2 — strict `GL_GREATER` plus a coplanar
  ground silently deletes altitude-0 map content.

The second-order risk is the one in §1.2: the map regime blends with depth writes on, so translucent
map-anchored content is already order-dependent despite the code's comment claiming otherwise. glTF
`alphaMode = BLEND` models turn that latent bug into a routine one. Sorting map-anchored blended things
back-to-front, and dropping `depthMask` for them, is work the F-2 spec should name rather than let a
reviewer find.

### 6.3 A single `Double` scale is sufficient for the model *placement*, and models will still want more

For placing a model in the world, one uniform scalar is right and better than the alternative: `CONTEXT.md`
says "Model local dimensions are GLB coordinates" and "Map-anchored scale is metres per local unit"
(`CONTEXT.md:200-205`), a GLB's own authored units already define its proportions, and §2.3 shows the
metres→logical-pixel factor matches altitude exactly. Non-uniform placement scale would also break the
`DoubleMatrix3` rotation composition's orthonormality assumptions in `PlacementResolver.kt:93-108` and
would need a normal-matrix inverse-transpose that the current pipeline does not compute.

What *will* bite is adjacent to it:

- **Non-uniform scale arrives anyway, from inside the GLB.** glTF nodes carry their own `scale` or
  `matrix` (`GltfDocument.kt:46-55`) and those are routinely anisotropic and occasionally negative. So the
  pipeline needs anisotropic and sign-flipping node transforms regardless of what `Placement.scale` is,
  which means a normal matrix and a winding-aware cull decision (§3.5 items 1, 2, 8). `affineModelMatrix`
  already scales per column (`SceneContent.kt:351-378`), so the composition layer is ready; the node
  hierarchy walk is not written.
- **`scale = 0.0` is explicitly valid** (`CONTEXT.md:203` "Zero scale is valid";
  guard at `SpatialValues.kt:116`; test at `PlacementResolverTest.kt:246-267`). A
  zero-scale model produces a degenerate model matrix — every vertex collapses to a point. Harmless for a
  quad, but it produces a zero-determinant normal matrix, so a lighting shader that inverts it gets NaNs.
  Cheap to handle, easy to miss.
- **`[0, ∞)` is a wider range than a `Float` can carry**, which the resolver already guards
  (`PlacementResolver.kt:115-117`, diagnostics test at `PlacementResolverTest.kt:292-341`). Model vertex
  coordinates multiply that scale, so a large model plus a large scale can overflow where the placement
  alone did not. The existing `isGpuRepresentable` check runs on `logicalScale` only, not on
  `logicalScale × modelExtent`.

### 6.4 The mixed-mode cases the MVP never exercised

Concretely, the untested-but-reachable combinations, in rough order of likelihood a consumer hits them:

| Combination | What is untested | Where it would show |
|---|---|---|
| `MAP` position + `SCREEN` rotation + `MAP` scale | the composed camera-space matrix | the canonical "billboard pinned to a coordinate", `CONTEXT.md:594-597` |
| `MAP` position + `MAP` rotation | `directionTransform`'s ENU→camera chain fed through `composeMapModelViewProjection` | any model that must stand upright on the map under a rotating camera |
| `MAP` position + `SCREEN` scale | apparent size vs. distance, §2.2(d) | a model meant to stay a constant pixel size |
| `SCREEN` position + `MAP` rotation | `geographicAnchor` falls back to the camera ground anchor (`PlacementResolver.kt:77`), so the two ENU bases cancel and the transform reduces to `viewBasis × localRotation` | a HUD element that tracks camera bearing |
| `SCREEN` position + any mode, on a `Model` | §6.1 | any screen-anchored model at all |

The resolver tests cover the first four at the value level (`PlacementResolverTest.kt:32-105, 107-244`).
Nothing covers any of them at the matrix level.

### 6.5 The quieter ones

- **Texture unit 1..14 leak** (§3.4) — a shipped ADR 0006/0023 violation that F-2's multi-texture materials
  will amplify. Fixing it is a one-line constant change plus a conformance test that actually drives a
  `SceneContent`.
- **Unvalidated index values reaching `glDrawElements`** (§3.5 item 4) — the one item here that can crash a
  driver rather than draw wrongly. `GltfDocument.kt:173-182` documents the gap precisely; nobody has to
  discover it, but somebody has to close it.
- **Inherited GL state** (§3.3) — `GL_CULL_FACE` enable, `glDepthRange`, geometry blend state. Each is a
  one-line `establish` call; the risk is that they are found by a consumer with unusual state rather than
  by a test, because no test ever runs RenG under adversarial inherited state.
- **`uploadTexture` cannot express glTF sampler state** (§3.5 item 6) — clamp-only, mipmap-free. A model
  with tiled UVs renders visibly wrong and the cause is three levels away from the symptom.
- **The ordering rule is implemented twice** (§2.2(a)) — the pure core's version already handles models;
  the GL layer's does not. Two implementations of one rule, only one of them tested against models, is the
  classic shape of a late discovery.
- **Premultiplied alpha meets PBR.** `uploadTexture` premultiplies `TextureContent.IMAGE`
  (`GlTextureUpload.kt:55-60`, rationale at 15-32) — correct for filtering, but base colour is multiplied
  by `baseColorFactor` and fed into lighting, and lighting arithmetic on premultiplied values is wrong. A
  model base-colour texture needs either un-premultiplication in the shader or a third `TextureContent`
  kind.

---

## Appendix — the files F-2 will touch, with why

| File | Why |
|---|---|
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/SceneContent.kt` | regime partition, matrix composition, the screen projection (§6.1), consuming the core's ordering (§2.2a) |
| `…/internal/gl/StickerPipeline.kt` | `StickerWorld`/`drawStickers` can no longer own the two-regime ordering (§2.2b) |
| `…/internal/gl/GlFrameDrawer.kt` | establish cull enable and depth range; widen the captured texture-unit count (§3.3, §3.4) |
| `…/internal/gl/GlTextureUpload.kt` | wrap/filter/mipmap parameters for glTF samplers (§3.5.6) |
| `…/internal/gl/GlBinding.kt` + 4 platform impls + `RecordingGlBinding` + `GlEntryPointRoster` | `uniformMatrix3fv`, possibly `polygonOffset` (§3.5.8, §5.2) |
| `…/internal/gl/CompositePipeline.kt` | `InternalPipelineRole.MODEL` (§4.3) |
| `…/internal/glb/` (new file) | accessor→buffer reading, node hierarchy flattening, animation sampling — none of it exists (§4.2) |
| `…/internal/planning/MercatorSpatialPlanner.kt` | already correct for models; the change is that somebody finally reads its output |
| `…/RenGRenderer.kt` | GLB acquisition, mesh upload and caching, `Scene` assembly |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/Resources.kt` | possible `ResourceKind` addition — a reviewed ABI diff (§4.3) |
| `docs/adr/` | likely: ground-coplanarity tie-breaking (§5.2), screen-regime depth for models (§6.1), a Restore Set amendment if polygon offset is used (§3.4) |
