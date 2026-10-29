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

A source may not use `#extension`: a directive must precede the preamble's declarations.

## The interface a program sees

    precision highp float;                 // a default; the program may redeclare it
    in vec2 rengStickerUv;                 // (0,0) at the image's top-left texel, (1,1) bottom-right
    vec4 rengStickerColour();              // image sample * opacity, premultiplied: the plain sticker
    vec2 rengStickerGroundUv();            // the ground rectangle's coordinate under this fragment

plus the uniforms behind them (`rengStickerTexture`, `rengStickerOpacity`,
`rengStickerWindowToGround`), which are reserved and the program must not declare.

`rengStickerColour()` is the plain fragment shader's expression verbatim, for a PNG and an ETC2 image
alike, since both reach the same sampler. A program whose `main` writes only it reads back
byte-for-byte the frame the plain sticker draws; the readback suite holds it to that.

`rengStickerGroundUv()` is where in `ground` the ground visible under this fragment's **window pixel**
lies: `u` from the west edge (0) to the east (1), `v` from the north edge (0) to the south (1), linear in
Mercator exactly as a Geometry's `aTexCoord` is. Because it is answered for the window pixel and not for
the sticker, it means the same thing for a billboard, a sticker lying on the map, and a
screen-composited sticker. Values outside `[0, 1]` are ground outside the rectangle. Where the line of
sight never meets the rectangle's plane -- above its horizon -- it returns `vec2(-1.0)`. With no
`ground`, and under a globe camera, it returns `vec2(-1.0)` everywhere.

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
        vec2 ground = rengStickerGroundUv();
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
one, which changes no answer because a homography means the same thing at any positive scale. What
reaches the shader is window pixels in and a texture coordinate out, both small, so the shader's error
is a few `Float` ulps of the coordinate -- the order of a Geometry's own interpolated `aTexCoord`. No
absolute Mercator coordinate is ever narrowed.

It is uploaded as a `mat4` acting on `vec4(gl_FragCoord.xy, 0, 1)`, the 3x3 in rows and columns 0, 1
and 3, only because `GlBinding` has no `glUniformMatrix3fv`; the nine numbers are the same.

The rectangle is resolved at frame planning as well as at draw, so a rectangle the Mercator camera
cannot carry fails as `INVALID_VALUE` at `FRAME_PLANNING` with the Geometry's own field. A source that
is not a Shader Profile fails there too, with `shaderPair`, as a backdrop's does; a compile or link
failure is reported as the consumer's (ADR 0071).

## Under a globe it is off-rectangle everywhere

The globe's ground is a sphere, and no homography maps a sphere. ADR 0072 handed the backdrop a matrix
and let the consumer choose the tangent-plane approximation; that works for a pattern, whose error
toward the limb is cosmetic. A mask is not cosmetic -- a coastline cut a few pixels wrong reads as a
defect -- so rather than a plane that is silently wrong away from the anchor, a globe frame binds the
zero matrix and `rengStickerGroundUv()` answers `vec2(-1.0)` for every fragment. The sticker still
draws; the program sees no rectangle. The globe planner still scans the source and resolves no corners.

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
