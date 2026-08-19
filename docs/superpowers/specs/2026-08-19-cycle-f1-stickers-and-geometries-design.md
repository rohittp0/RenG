# Cycle F-1 — stickers and geometries, and the renderer factory

**Status:** drafted 2026-08-19 from a grilling session; awaiting owner approval.

This cycle makes RenG operable. Every cycle before it built machinery with no way for a consumer to
obtain a renderer; this one adds the factory and draws the first two of the three drawn things. It is
the MVP that unblocks waiting downstream consumers, and it is an **internal release** — breaking the
public interface in a later cycle is accepted.

## Cycle naming and execution order

Existing cycle letters stay bound to their existing content so no prior reference is invalidated.
Cycle F (drawn things) splits, and execution order changes:

| Order | Cycle | Content |
|---|---|---|
| 1 | C (partial) | resource layer, MVP subset only — tasks 15 and 21 |
| 2 | D | GL foundation — **complete** |
| 3 | **F-1** | **stickers, geometries, renderer factory → MVP release** |
| 4 | E (basemap half) | basemap, plus deferred Cycle C tasks 14, 16, 17, 18, 19 → release |
| 5 | F-2 | models with textures and animation → release |
| 6 | E (terrain half) | terrain, plus deferred Cycle C task 20 |
| 7-10 | G, H, I, J | globe, mobile bring-up, macOS harness, golden-image corpus |

Six of the eight Cycle C tasks remaining at the reorder are basemap work and travel to slot 4. Terrain
moves behind models because it was already deferred once for having no consumer, while models have
consumers waiting.

## What ships, and what does not

**Ships:** the renderer factory; stickers drawn in both draw regimes; geometries painted by consumer
shader pairs, with consumer-supplied uniforms and textures; the documented shader interface.

**Does not ship:** the basemap, terrain, models, the globe projection, and all pixel verification.
`drawBasemap` remains in `FramePlan` and is honoured as described below.

## Public API additions

This cycle deliberately grows the public ABI, unlike Cycles C and D which added none. Each addition is
a reviewed `checkKotlinAbi` diff.

```
fun createRenderer(configuration: RendererConfiguration): Renderer   // top-level, synchronous, throwing

Geometry(topLeft, bottomRight, shaderPair,
         uniforms: Map<String, ShaderValue> = emptyMap(),
         textures: Map<String, ResourceLocator> = emptyMap())

sealed interface ShaderValue        // Float, Vec2, Vec3, Vec4, Int, Mat4
```

Uniform **arrays** are deliberately excluded; textures serve bulk data better and arrays bring size
limits and layout rules the MVP does not need.

## Decisions

### The renderer factory is synchronous and throws

`createRenderer` captures the already-current render context's identity, queries its profile, creates the
offscreen surface and compiles the composite pipeline — all synchronous GL work. It **records** the
basemap style locator and acquires it at the first `prepare()`; a setup that fetched would have to
suspend, and a library whose defining claim is purity should not perform network I/O in its constructor.

It throws `RenGException` rather than returning a sealed result, for consistency rather than taste:
`prepare` returns a `PreparedFrame` directly and therefore must throw, and two failure idioms in one
small API is a worse cost than the one saved.

Inputs needed no change. ADR 0004 already places the basemap style at setup and `RendererConfiguration`
already declares it nullable with a default; ADR 0012 already fixes output pixel size there.

**Carried to Cycle H:** Kotlin exceptions from non-suspend functions do not bridge to Swift errors without
`@Throws`. This is pre-existing for `prepare` and is not introduced here.

### Draw-regime ordering — ADR 0024

The map regime draws first, depth-tested. The screen regime then composites on top as a single stack.

`CONTEXT.md` already fixes ordering *within* each regime; only the between-regime rule was open. Splitting
the screen regime by sign of z-index was rejected: it would overload the sign with regime meaning while the
magnitude keeps ordering meaning, so `z = -5` and `z = -3` would order correctly relative to each other
while both silently jumping behind the map. Anything needing to sit behind the map uses map anchoring.

This rule is fully testable in this cycle despite the basemap's absence, because the map regime still
contains map-anchored things depth-testing one another.

### Alpha is premultiplied for images and never for data

Image textures — sticker images, and later model base-colour maps — are premultiplied at GL upload and
blended `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`. The CPU-side canonical decoded form stays unpremultiplied, so
Cycle C's contract is untouched; the multiply is an upload concern.

The reason is filtering, not blend arithmetic. Unpremultiplied transparent texels carry arbitrary RGB, and
bilinear interpolation across a transparent edge drags it into the visible result. Only scale exactly 1.0
avoids filtering, and map-anchored stickers under a pitched camera always filter.

**Premultiplying in the fragment shader does not fix this** and must not be mistaken for a solution:
filtering has already happened by the time the shader sees a sampled value.

**Consumer data textures are never premultiplied.** A boundary mask, a signed-distance field, or values
packed across RGBA are destroyed by a multiply, with no error. `CONTEXT.md` sets this precedent exactly for
terrain samples, which must stay bit-exact "because any of those silently change elevations". The
distinction is by purpose, not by file format — both are PNGs through the same decode path, differing only
at upload — and it is expressed in the type rather than left to a documented convention.

### Texture sampler state is mandatory, and filtering splits the same way alpha does

**This is not a quality preference — an unset filter renders nothing.** GL's default minification filter
is `GL_NEAREST_MIPMAP_LINEAR`, which expects a mipmap chain. A texture uploaded with neither mipmaps nor
an explicit `GL_TEXTURE_MIN_FILTER` is *incomplete* and samples as black on real drivers. Every uploaded
texture therefore sets its sampler state explicitly before any draw call reaches it.

**Image textures filter `GL_LINEAR`.** That is what premultiplication exists to make correct: interpolating
across a transparent edge is precisely the case unpremultiplied data gets wrong.

**Data textures filter `GL_NEAREST`.** Nearest never invents a value, which matches RenG's posture of never
silently altering consumer content — interpolating between index 3 and index 7 yields index 5, which is
meaningless. The known cost is that a signed-distance field genuinely *wants* linear filtering; that is
where its antialiasing and adjustable outline thickness come from, and under nearest an SDF gives hard
aliased edges instead.

That cost is accepted deliberately for the MVP rather than overlooked. **A per-texture filter choice is the
obvious additive fix** — one more enum threaded through the same upload call — and should be added the
moment a consumer needs an SDF to antialias. It is left out now because a wrong default that corrupts
packed data is worse than a conservative default that under-serves one use case, and because adding the
choice later breaks nobody.

**Wrap mode is `GL_CLAMP_TO_EDGE` for both.** The GL default is `GL_REPEAT`, which under linear filtering
samples the opposite edge of the texture at the boundary — visible edge bleeding on every sticker. This is
the same shape of hazard as the filter default: a GL default that is wrong for RenG's usage and silent
about it.

### `drawBasemap` with no configured style warns and degrades

When `drawBasemap` is true and no basemap style is configured, RenG draws no basemap, emits one warning
`Diagnostic` per renderer, and continues.

This is a permanent rule, not a shim for the MVP: `basemapStyle` stays nullable after the basemap ships.
Flipping the default to false was rejected because when the basemap lands and the default flips back, a
consumer who never touched the field changes behaviour silently on upgrade. Failing closed was rejected
because running without a map is the entire MVP use case and stays legitimate afterward.

It is not a silent no-op: `RendererConfiguration` already carries a public `DiagnosticSink`, and RenG
already warns-and-degrades when a freed resource is accessed. Once per renderer, never per frame, so it
cannot flood a render loop.

### The shader interface

| Kind | Name | Type |
|---|---|---|
| attribute | `aPosition` | `vec3` |
| attribute | `aTexCoord` | `vec2` |
| uniform | `uModelViewProjection` | `mat4` |
| uniform | `uResolution` | `vec2` |
| uniform | `uGeometryBounds` | `vec4` — west, south, east, north degrees |
| uniform | `uFrameIndex` | `uint` |

`uFrameIndex` is a correctness item rather than a convenience. Without it, an animating consumer must
mutate shader source every frame, which changes the `ShaderPair` content, which changes the derived program
key, which recompiles every frame. `FramePlan.frameIndex` is a `Long` and GLSL ES 3.00 has no 64-bit
integer, so it is narrowed to `uint` with a documented wrap at roughly 2.3 years of continuous 60fps;
`float` was rejected as non-exact past about 77 hours.

`frameIndex` is an ordering key rather than a clock. Wall-clock animation would need a new `FramePlan`
field, deferred until real usage asks for it.

**Precision is split by purpose.** Placement stays camera-relative and exact through `aPosition` and
`uModelViewProjection` — Cycle B's spikes measured camera-relative Float error below 0.001 px, and absolute
degrees in a 32-bit float would discard that at the final step. `uGeometryBounds` is informational and must
be documented as unsuitable for placement arithmetic.

A per-vertex `aGeoPosition` attribute was rejected: mercator latitude is not linear in screen space, so
interpolating latitude across a tall quad is quietly wrong. `CONTEXT.md` specifies that altitude interpolates
north-to-south and deliberately says nothing about latitude. A consumer deriving latitude from
`uGeometryBounds` and `aTexCoord` makes that approximation visibly and by choice.

**Hazard the ADR must record:** ADR 0008 binds a documented name only when the compiled program declares it,
so renaming one later fails **silently** — consumer shaders keep compiling and drawing, simply without that
value. It is the one class of breaking change that does not announce itself, which is why these names deserve
care even in an internal release.

### Consumer-supplied data

Small parameters travel as uniforms; bulk data travels as textures bound by consumer-chosen sampler names.
Textures reuse the sticker path entirely — acquired, decoded, uploaded, content-keyed cached — so the same
boundary texture uploads once and is reused across every frame referencing it.

Point-in-polygon over uniform arrays is the trap to steer consumers away from: it is O(edges) per pixel, and
a real country outline is thousands of points. A signed-distance field texture is O(1) per pixel and
antialiases for free.

**Reserved names.** A consumer uniform colliding with a documented RenG name is rejected at `Geometry`
construction, loudly and at the point of the mistake, rather than letting RenG's binding silently win at draw
time.

**Bounds to document:** GLES 3.0 guarantees only 16 fragment texture units, so the texture count is capped.
An 8-bit PNG gives 256 levels per channel — ample for a mask or SDF, inadequate for encoding coordinates.
Float textures differ in extension availability across the six targets and are out of scope.

Uniform buffer objects were excluded deliberately: they would mean publishing an `std140` block-layout
contract, fiddly to specify and easy for a consumer to get subtly wrong.

## Verification

**All pixel verification is deferred to Cycle J**, by owner decision, including golden images and their
renderer-string keying.

This cycle still verifies the draw path without reading pixels: that draw calls are issued, that the expected
uniforms and textures are bound, that blend state is set as specified, and that the two regimes run in the
documented order. That is call-log assertion rather than image comparison, and it catches gross wiring errors
while leaving every image question to J.

The risk this accepts, stated plainly: a misplaced sticker is invisible to every test this cycle has and
immediately obvious to a consumer.

## Carried in from earlier cycles

**MVP-blocking.** `ResourceActionExecutor` does not handle `CancelRoute`. A multi-route operation where one
route observes an adapter cancellation while siblings are active crashes there. `Renderer.cancelPreparations()`
is public ABI, so this is reachable through the documented API and must be fixed before the MVP ships.

**Integration hygiene.** `CLAUDE.md`'s Cycle B paragraph contradicts itself, claiming both that Cycle B is
merged to `main` locally and that it is not. Resolve when the branches integrate.

**Release framing.** Publication remains immutable — breaking the interface later means new versions, never
overwriting a published coordinate. All six targets publish regardless of which are verified; only macOS and
Linux are, and that belongs in the release notes rather than being discovered by an Android consumer.
