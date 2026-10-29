# A sticker may carry a consumer fragment program

Travel Animator wants an image clipped by a map region: a photo of two people standing "inside" a
country, legs cut by the coastline, heads breaking out above it. Everything about that -- which region,
its signed-distance field, the offset, the pop-out line, inside or outside -- is the app's, and none of
it belongs in a renderer. What the app cannot do on its own is decide, per pixel of a sticker, where on
the ground that pixel is. So RenG gains one generic capability and nothing region-shaped: a `Sticker`
may carry a fragment program the consumer writes.

## Public surface

- `Sticker.shader: StickerShader? = null`, appended last, so it is `component5` and no earlier
  component moves. `null` is the plain sticker, unchanged.
- `StickerShader(fragmentSource: String, uniforms: Map<String, ShaderValue> = emptyMap(),
  textures: Map<String, ResourceLocator> = emptyMap(), ground: GroundRectangle? = null)`. Not a data
  class, for `Backdrop.Shader`'s reason (ADR 0071): a generated `toString` would print the source. Its
  `toString` redacts source, uniforms and textures.
- `GroundRectangle(topLeft: Vector3, bottomRight: Vector3)`, a data class, by a Geometry's corner
  convention and validation: latitude, unwrapped longitude, absolute altitude; top-left strictly north
  and west; at most 360 degrees of longitude.

Uniform and texture names in `RESERVED_SHADER_NAMES` or under the prefix `rengSticker` are refused at
construction, on ADR 0071's reasoning: holding the whole prefix now is what lets a later cycle add a
name without breaking a consumer who claimed it. Textures upload as `TextureContent.DATA` -- bit-exact,
never premultiplied, `GL_NEAREST`, clamped -- exactly as a Geometry's do, which is what a packed 16-bit
distance field needs. At most **fourteen**, not a Geometry's fifteen: the image holds unit zero and the
consumer's textures take units one onwards, and fourteen keeps the same one unit of headroom below
GLES 3.0's sixteen guaranteed units that `MAXIMUM_CONSUMER_TEXTURES` keeps.

## The source shape: a whole fragment shader, and a preamble after its directive

`fragmentSource` is a complete GLSL ES 3.00 fragment shader in the Shader Profile: `#version 300 es`
as its directive, its own `main`, and its own `out vec4` at location zero, written premultiplied. RenG
inserts a fixed preamble on the line after the directive and changes nothing else. The composed source
is still a Shader Profile whose directive is the consumer's, so desktop substitution replaces exactly
that line as it always has. The vertex stage is RenG's own sticker vertex stage.

This is an injected include, which ADR 0008 refuses for a Geometry and `CONTEXT.md` lists under the
Shader Interface's _Avoid_. It is taken here deliberately, for the argument ADR 0072 made for the
backdrop's matrix: RenG should hand over the part that moves. Two parts move. The colour is one -- the
image is a premultiplied PNG or an ETC2 chain today (ADR 0084), and how a sticker samples its image is
RenG's to change -- and a consumer who wrote the sample themselves would silently stop matching the
plain sticker the day it changed. The ground mapping is the other: how the homography is packed is an
implementation choice, and a function hides it. Handing over functions rather than uniform names is
what lets RenG promise that a pass-through program draws the plain sticker's pixels, and keep that
promise. Geometry and the backdrop keep ADR 0008's rule; this exception is the sticker program's alone.

A source may not carry `#extension`: a directive must precede the preamble's declarations. Frame
planning refuses any physical line beginning `#extension` (after spaces, tabs and a `#` with spaces)
as `INVALID_VALUE` naming `sticker.shader.fragmentSource`, the field a source that is not a Shader
Profile fails with too. By line rather than by token, so one hidden in a block comment is refused as
well: a false refusal costs a comment, a false acceptance a driver-dependent compile error.

## The interface a program sees

    precision highp float;                 // a default; the program may redeclare it
    in vec2 rengStickerUv;                 // (0,0) at the image's top-left texel, (1,1) bottom-right
    vec4 rengStickerColour();              // image sample * opacity, premultiplied: the plain sticker
    vec2 rengStickerGroundUv();            // the ground rectangle's coordinate under this fragment
    bool rengStickerHasGround();           // whether this frame gives the program a rectangle at all

plus the uniforms behind them (`rengStickerTexture`, `rengStickerOpacity`,
`rengStickerWindowToGround`, `rengStickerGroundOrigin`, `rengStickerGroundPresent`), which are
reserved and the program must not declare.

`rengStickerColour()` is the plain fragment shader's expression verbatim, for a PNG and an ETC2 image
alike, since both reach the same sampler. A program whose `main` writes only it reads back
byte-for-byte the frame the plain sticker draws; the readback suite holds it to that.

`rengStickerGroundUv()` is where in `ground` the ground visible under this fragment's **window pixel**
lies: `u` from the west edge (0) to the east (1), `v` from the north edge (0) to the south (1), linear in
Mercator exactly as a Geometry's `aTexCoord` is. Because it is answered for the window pixel and not for
the sticker, it means the same thing for a billboard, a sticker lying on the map, and a
screen-composited sticker. Values outside `[0, 1]` are ground outside the rectangle. Where the line of
sight never meets the rectangle's plane -- above its horizon -- it returns `vec2(-1.0)`.

It answers for the **flat plane** through the rectangle's declared absolute altitudes. It does not
follow terrain: where the ground is displaced (ADR 0039) the visible ground is not that plane, and a
mask along a mountainous border shows parallax against the drawn relief. Keep the result `highp`:
`mediump` is half precision on mobile GPUs and truncates a coordinate to about three decimal digits.

`rengStickerHasGround()` is per frame, not per pixel. It is false with no `ground`, under a globe
camera, and when the frame's Mercator camera cannot carry the rectangle; true otherwise -- including
at a pixel above the horizon, which reads `vec2(-1.0)` from `rengStickerGroundUv()` while the frame
still has ground. Wherever it is false, `rengStickerGroundUv()` is `vec2(-1.0)` everywhere. A mask
needs the distinction: "no ground information this frame" usually means draw the image whole, while
"this pixel is off the rectangle" means cut it.

Blend and depth state are the plain sticker's, untouched: the map regime tests depth and writes none
(ADR 0027), the screen stack composites without a depth test (ADR 0024), and the sticker's own
`BlendMode` and opacity apply as before (ADR 0078). The consumer program changes the colour each
fragment writes and nothing about where or how it lands.

A minimal program that cuts a sticker to a region whose signed distance the consumer packs into a
texture, and lets everything above a line in the sticker's own space pop out:

    #version 300 es
    precision highp float;
    uniform sampler2D uDistance;   // the consumer's field over the ground rectangle
    uniform float uPopLine;        // sticker-space v above which the image is never cut
    out vec4 colour;
    void main() {
        vec4 image = rengStickerColour();
        if (!rengStickerHasGround()) { colour = image; return; }
        highp vec2 ground = rengStickerGroundUv();
        bool onRectangle = all(greaterThanEqual(ground, vec2(0.0))) && all(lessThanEqual(ground, vec2(1.0)));
        bool inside = onRectangle && texture(uDistance, ground).r > 0.5;
        colour = (inside || rengStickerUv.y < uPopLine) ? image : vec4(0.0);
    }

## The ground mapping is a homography, built in Double

The rectangle's four corners are coplanar -- the property ADR 0008's erratum and `GeometryGrid`
already rely on -- and a Geometry's coordinate is affine across that plane:
`NW + u (NE − NW) + v (SW − NW)`. So the camera maps `(u, v, 1)` to clip `(x, y, w)` by one 3x3 matrix,
the viewport maps clip to window by another, and the inverse of their product sends a window pixel to
`(u, v, 1) / w` -- what a Geometry's perspective-correct interpolation computes at that pixel. The sign
carries information too: `1 / w` is positive exactly where the line of sight meets the plane in front
of the camera, which is the test the shader divides under.

The corners come through `resolveMercatorRectangleCorners`, which is `resolveGeometry`'s own body
extracted rather than a second derivation, so the rectangle a program reads against is by construction
the one a Geometry over the same corners draws. Everything up to the single narrowing is `Double` and
camera-relative; the matrix is built with its columns normalised and scaled to a largest element of
one, which changes no answer because a homography means the same thing at any positive scale. No
absolute Mercator coordinate is ever narrowed.

**The map is relative to a whole-pixel origin near the rectangle.** The shader evaluates in `Float`,
and against raw `gl_FragCoord` the coordinate of a small rectangle near the far edge of a large frame
is a number of order one computed from terms the size of the frame that cancel. A review estimated
1e-3 to 1e-2 of the coordinate on a 4K-16K frame; `StickerGroundTest` measures 1.5e-3 of a **pixel**
for a 24-pixel rectangle at the edges of a 16384-pixel frame (about 6e-5 of the coordinate) -- smaller
than estimated, because the error is a few ulps of the frame coordinate in pixel terms whatever the
rectangle's size, but enough to flip a nearest-sampled texel that a Geometry over the same rectangle
would not, so it is removed rather than argued about.
So RenG builds the map relative to the window pixel nearest the rectangle's projected centre, clamped
into the frame (the frame's centre if the rectangle's centre is behind the camera), and uploads that
origin as `rengStickerGroundOrigin`. The preamble's `gl_FragCoord.xy - origin` is exact in `Float` --
half-integers below 2^24 minus an integer -- and around the rectangle every term of the product is
then the size of the coordinate itself. `StickerGroundTest` evaluates the shader's arithmetic at every
pixel of that rectangle at each edge and corner of the frame and holds it within 1e-4 of a pixel of the
`Double` map (measured 1.3e-6), with the uncentred map as a control that must do measurably worse.

What remains is the coordinate's own resolution: one `Float` spans the whole rectangle, so the larger
the rectangle on screen, the coarser a pixel's share of it. At zoom 18 and pitch 45, over a 1920x1080
frame, a 40-degree rectangle reads to within 1.07 pixels (bounded below 2 in `StickerGroundTest`) and a
170-degree one to 5.4. Consumers should keep the rectangle tight around what the program reads.

It is uploaded as a `mat4` acting on `vec4(gl_FragCoord.xy - origin, 0, 1)`, the 3x3 in rows and
columns 0, 1 and 3, only because `GlBinding` has no `glUniformMatrix3fv`; the nine numbers are the same.

## A rectangle the camera cannot carry degrades, it does not fail

A rectangle beyond Mercator's latitude limit (85.0511 degrees) or its world-copy bounds cannot be
resolved by a Mercator camera. Failing the frame for it would be wrong twice over: a sticker draws
correctly without its ground, and the same plan would succeed under a globe and fail under Mercator,
so a globe-to-Mercator transition could fail mid-export. So it is resolved at draw only, and when the
camera cannot carry it the sticker draws with no ground -- `rengStickerHasGround()` false, the zero
matrix -- and the draw reports `DiagnosticCode.STICKER_GROUND_UNAVAILABLE`, a warning with field
`sticker.shader.ground` and the count of such stickers as `actual`. A globe frame reports nothing:
having no ground there is the contract, not a fault.

A source that is not a Shader Profile still fails at `FRAME_PLANNING` as `INVALID_VALUE`, in both
projections, naming `sticker.shader.fragmentSource`; a compile or link failure is reported as the
consumer's (ADR 0071).

## Sampler objects are cleared on every unit a sticker program binds

A sticker program's textures are data uploaded for `GL_NEAREST`; a `GL_LINEAR` sampler object a host
left on one of its units (Skia on a shared context does) would filter a packed 16-bit value into
nonsense. So the sticker pass binds sampler 0 on unit 0 for the image, and the shared consumer-value
binder binds sampler 0 on every unit it binds a consumer texture to -- which changes Geometry and the
shader backdrop the same way, for the same reason. The capture region saves each unit's sampler before
the first write and restores it after the frame (ADR 0055), so the host's state is untouched.

## Under a globe there is no ground

The globe's ground is a sphere, and no homography maps a sphere. ADR 0072 handed the backdrop a matrix
and let the consumer choose the tangent-plane approximation; that works for a pattern, whose error
toward the limb is cosmetic. A mask is not cosmetic -- a coastline cut a few pixels wrong reads as a
defect -- so rather than a plane that is silently wrong away from the anchor, a globe frame binds the
zero matrix: `rengStickerHasGround()` is false and `rengStickerGroundUv()` answers `vec2(-1.0)` for
every fragment. The sticker still draws. The globe planner still checks the source and resolves no
corners.

## Identity and serialization

Canonical encoding takes Sticker tag 4, written only when `shader` is non-null, so every plan without
one keeps its exact bytes and its Frame Identity (ADR 0018). Inside it: tag 1, the program's pipeline
key stable id -- a digest, never the source (ADR 0081) -- tag 2 the uniforms and tag 3 the textures
exactly as a Geometry's encode, tag 4 the ground rectangle's two corners when present. The JSON
surrogate gains a defaulted `shader`, so older documents decode unchanged and a plain sticker writes
no new key.

## Programs and residency

Consumer sticker programs are keyed under a new `InternalPipelineRole.CONSUMER_STICKER` (wire value
16, appended) over RenG's sticker vertex source and the consumer's fragment source as written, so no
consumer source can name RenG's own sticker program (the defect ADR 0071's 2026-10-22 amendment fixed
for the backdrop). They live in the consumer pipeline cache beside Geometry and consumer backdrop
pipelines, leased for the draw that uses them, and count toward `maximumConsumerPipelinesPerFrame`. A
program draws from the plain sticker pipeline's vertex array; drawing one restores the plain program
and unit zero afterwards, so the sticker loops need not know which of their stickers carried one.

## Rejected

**A region-shaped mask type in RenG** -- a `RegionMask(sdf, offset, popLine, side)` on `Sticker`. It
would be the shortest path to this one picture and the wrong renderer: every next variant (a feathered
edge, two regions, a mask that animates along a route) becomes a RenG release, and RenG would own
region semantics it has no business knowing. A program covers this picture and the next ones.

**A plain coverage texture** -- the consumer renders a mask image per frame and RenG multiplies the
sticker by it. Rasterised at one resolution, it blurs or steps as the camera zooms, it has to be
re-rendered whenever the camera moves, and it cannot express a line in the sticker's own space (the
pop-out line) because it knows nothing of the sticker. Sampling a distance field per pixel at the
ground point stays sharp at every zoom and needs nothing from the consumer per frame but the camera
they already send.

**Uniform names instead of functions** (ADR 0008's shape: declare `rengStickerTexture` and write the
sample yourself). Rejected for the reason above: the colour and the matrix layout are the parts that
move, and only functions let RenG move them without breaking a consumer.
