# Bloom reach per Geometry, and a half-float emission chain

ADRs 0074, 0080, 0082 and 0083 give a frame one emission image, one dual-Kawase chain and one
composite. Everything after the emission redraw is frame-wide: `levelWeights` decides how far every
emitter's light reaches, and an emitter controls only the RGB and alpha it writes. Those writes land
in an RGBA8 image, so an emitter's light can never exceed one. Travel Animator wants a route's glow to
reach further than a region's in the same frame, and a glow stronger than "opaque" without spreading it.
This ADR adds both, opt-in, with every existing frame unchanged.

## Public surface

- `Geometry.bloomReach: Float = 0f`, in `0..1`, finite, not negative zero. Appended last, so it is
  `component9`. Meaningful only on an Emissive Geometry in a frame with a Bloom.
- `Bloom.reachLevel: Int = 3` (`Bloom.DEFAULT_REACH_LEVEL`), in `1..7` (`Bloom.MAXIMUM_REACH_LEVEL`):
  the downsample level the far band is added at.
- `Bloom.reachGain: Float = 1f`, finite and non-negative: the far band's scale.
- `Bloom.highPrecisionEmission: Boolean = false`: ask for a half-float emission chain.
- `DiagnosticCode.BLOOM_EMISSION_PRECISION_REDUCED`, appended: the frame asked for half-float and drew
  with RGBA8.

Each field is encoded in the Frame Identity only away from its default (Bloom tags 5, 6, 7; Geometry
tag 10, after the program's tag 9 because tags increase), the precedent `Geometry.blend` and
`Sticker.opacity` set, so a plan that leaves them alone keeps the identity it had. JSON omits a default
property as it always has.

`reachLevel` is not bounded by `levelWeights.lastIndex`, as the design first proposed: that would make
every existing `Bloom(levelWeights = <three values>)` refuse to construct with the default level. A
reach level deeper than the chain injects into the deepest level the chain computes; a chain of one
level has no downsample and no far band.

## The far band

With some Emissive Geometry at a positive reach and `reachGain > 0`, the bloom phase does, after the
ordinary emission redraw:

1. Clears a full-resolution **reach image** that shares the main depth renderbuffer exactly as the
   emission image does, and redraws every reaching emitter into it with the emission redraw's grid,
   view-projection, consumer values, `rengGeometryEmissionPass = 1` and depth state (`GEQUAL`, no
   writes). Light is scaled by the emitter's reach through the blend constant: `CONSTANT_ALPHA,
   ONE_MINUS_SRC_ALPHA` for colour, `ONE, ONE_MINUS_SRC_ALPHA` for alpha, so one reaching emitter
   composites over another as it does in the emission image. The blend constant goes back to GL's
   default, `(0, 0, 0, 0)`, after the last far redraw; it is already a member of the Restore Set,
   so the consumer's own value is restored when the draw returns.

   "Consumer values" includes the ones an emitter omits and so inherits from its program (ADR 0008).
   RenG tracks, per program, every uniform the frame's normal pass set before the emission redraw
   (the same history ADR 0080's amended restore rebinds after the bloom phase),
   then walks the emitters in the emission redraw's order merging each drawn one's uniforms over it,
   and draws each reaching emitter with that merged set: the values its emission redraw saw. The one
   uniform that can still differ is one nothing earlier in the frame set but a later emitter does:
   the emission redraw saw whatever the program held before the frame, which RenG does not query.
2. Downsamples the reach image through the main chain's own kernel, to `reachLevel` and no further,
   into its own levels.
3. When the main chain has written level `reachLevel`, adds the reach image's level into it at
   `reachGain` (`ONE, ONE` for colour, `ZERO, ONE` so the level's alpha is kept), with the upsample
   program and no accumulation, then continues the chain from that level.

So an emitter with reach `r` adds `r * reachGain` of a wide-only bloom made of the levels at and below
`reachLevel`. Over a uniform field that band's gain is `reachGain * intensity * sum(levelWeights[reachLevel..])`:
1.7 for the default weights at level 3, against 3.4 for the whole chain. It is not normalised; a
consumer who changes `levelWeights` changes how much a reach of one adds. The near band, the
emission redraw, is untouched. With every reach at zero, or a zero gain, nothing is drawn, nothing is
allocated and the frame is byte-identical to ADR 0083's.

The reach image and its levels are allocated on the first frame that has a far band, freed with the
other bloom targets, and forgotten on a lost context. A refused allocation is `BLOOM_UNAVAILABLE`
and the frame draws without bloom, as a refused emission image is.

Because the reach image is full resolution, the far redraw rasterises where the emission redraw does:
`gl_FragCoord`, `fwidth` and `uResolution` mean what they mean in the emission redraw, terrain occludes
it as it occludes the near band, and a consumer shader needs no change. Each reaching emitter costs a
second draw at full resolution plus a partial downsample.

### Considered: rasterising straight into the level

The design's first form drew reaching emitters directly into level `reachLevel`, at its resolution,
with the depth test off (the level has no depth attachment). It is cheaper, but a 4 px line is half a
pixel at level 3: it falls between sample centres, skips the downsample kernel's footprint, shimmers
as the camera moves, ignores displaced terrain's occlusion, and hands consumer shaders a `gl_FragCoord`
and `fwidth` eight times coarser than the output. It is kept as an internal strategy
(`BloomFarBandStrategy.COARSE_REDRAW`, set only by RenG's own tests) so the difference can be measured;
its blend constant is `min(1, reach * reachGain)`, since ES 3.0 clamps a blend constant to one.

## Half-float emission

With `highPrecisionEmission` and a context whose profile reports half-float colour rendering
(`EXT_color_buffer_half_float` or `EXT_color_buffer_float` on ES; always on desktop GL 3.3+), the
emission image, its downsample levels, the reach image and its levels are `RGBA16F`; the upsample
levels already were. The emission redraw blends into that target, which the upsample targets never
did: `EXT_color_buffer_half_float` includes blending to its formats, and `EXT_color_buffer_float`
excludes blending only for 32-bit float formats, which RenG does not use. A context that reports the
capability but refuses a half-float emission target as a format (an incomplete framebuffer, or a GL
error other than `GL_OUT_OF_MEMORY` while allocating it) falls back to RGBA8 for the rest of the
context's life. Either fallback stages `BLOOM_EMISSION_PRECISION_REDUCED` on every frame that asked.
Any other failure while making the half-float set (a bloom program that fails to link, memory running
out, an upsample target, which is half-float whenever the context can render it whatever the frame
asked) is `BLOOM_UNAVAILABLE`, as it is for the RGBA8 set, and the next frame tries half-float again.

The precision is fixed when the targets are made, so a frame asking for the other precision frees
them and makes a new set; alternating frames therefore reallocate every frame. That suits the A/B
measurement it exists for; a consumer should not toggle it per frame.

A half-float emitter can write anything up to half-float's 65504 or an infinity, or a negative value,
and the composite's shoulder divides by the light's peak. The chain reads light only through
`clamp(value, 0.0, 64.0)` (`BLOOM_LIGHT_CEILING`): on every downsample tap, and on the level sample
the upsample adds. So the images the chain does not write itself (the emission image, read by the
first downsample and as level 0 by the final upsample or a one-level chain's only upsample; the reach
image; a coarse redraw's level) are bounded wherever they are read as light, and negative light adds
nothing rather than darkening the frame. The upsample skips its tent when the accumulation weight is
zero, so a one-level chain, whose accumulation is the emission image, cannot form `0 * Inf`; a zero
level weight multiplies a clamped, finite sample. The composite reads the emission image's alpha
only for inside suppression, through a `smoothstep`, which saturates. RGBA8 light is already in
`0..1`, so for it the clamp changes nothing.

(As first published in 0.6.1-local6 the bound was `min(value, 64.0)` on the downsample's output
only: level 0 reached the upsample unbounded, a zero level-0 weight could form `0 * Inf`, and
negative light darkened the frame. On the M-series GPU an emitter writing -1 at 0.2 intensity took
its own 128-grey stroke to 31 and the ground 6 px away from 26 to 14; a 1e6 emitter's own stroke stayed
white there, so the `0 * Inf` case did not show on that driver.)

## Measurements

RenG's readback suite (`BloomPerElementReadbackSuite`, 256 x 256, a dark grey ground at 0.1, macOS
CGL on the M-series GPU and on Apple's software rasteriser; numbers are the GPU's, the software
rasteriser's agree within one level except where noted):

- **Defaults.** Explicit default values, `reachLevel`/`reachGain` without a reaching emitter, a zero
  `reachGain`, and `bloomReach` on a non-emissive Geometry each draw the default frame byte for byte.
  ADR 0083's eleven bloom readback cases print the same values and the same frame SHA-256s before and
  after this change, on both rasterisers.
- **Reach widens, it does not only extend.** An 8 px emitter drawn grey and emitting white at
  intensity 0.5, light added past its edge with reach 0 / reach 1: 0 px 134/163, 2 px 70/96,
  8 px 23/40, 16 px 11/19, 24 px 7/11, 40 px 3/5, 64 px 1/2. The relative gain rises with distance
  (1.37x at 2 px, 1.7-1.9x from 8 px out), but in levels the far band lands mostly in the first
  8-24 px: the levels at and below 3 carry a thin line's light close to it. A tail that reaches far
  needs a deeper `reachLevel` or a larger `reachGain`. With `insideSuppression = 1` the emitter's own
  pixels are unchanged (128 grey either way).
- **Per element.** A second emitter 128 px away keeps its own halo exactly (31 levels 6 px out with or
  without the first emitter's reach). Drawing every emitter into the reach image regardless of its
  reach (a deliberate mutation) adds 19 there.
- **DC gain.** Over a full-frame emitter of 0.1: the whole chain adds 3.45x (3.4 predicted), the far
  band 1.73x (1.77x on the software rasteriser; 1.7 predicted).
- **Half-float.** At intensity 0.2, 6 px out, an emitter writing 1 adds 12 levels on either chain;
  writing 2 adds 12 on RGBA8 (the frame is byte-identical to writing 1) and 25 on half-float. Writing
  1e6 adds 229 and leaves the far frame untouched: the ceiling keeps it finite.
- **Shimmer.** A 4 px line moved through the eight phases of one level-3 pixel, far light 16 px out at
  intensity 1: the reach image adds 12-13 levels at every phase; the coarse redraw adds
  0, 16, 14, 13, 13, 0, 0, 0 -- the line drops out of level 3 entirely at half the phases.

Cost and the phone GPU are measured in Travel Animator, not here.

## Consequences

- An emitter's halo width is now partly its own. The sandwich limit of ADR 0080 and the frame-wide
  whiteness, intensity and inside suppression are unchanged.
- The far band adds light at the stroke as well as beyond it; with `insideSuppression = 1` the stroke's
  own opaque interior is unchanged, since the composite still reads suppression from the full-resolution
  emission image.
- Half-float costs bandwidth on every bloom target it touches; it is opt-in for that reason.

This extends ADRs 0074 and 0080 (a second redraw inside the bloom phase, which leaves the same restored
state; after both redraws each touched program gets back the normal pass's merged uniform history, per
ADR 0080's 2026-10-21 amendment) and leaves 0082 and 0083's composite as it was.

## Amendment, 2026-10-22: a refusal is a refusal of any half-float target, and the retry is RGBA8

The upsample levels are half-float wherever the context can render it (ADR 0074), so a context that
advertises the capability but refuses `RGBA16F` as a format refused them too and read as
`BLOOM_UNAVAILABLE`, and the RGBA8 retry asked for half-float upsample levels again. A refused `RGBA16F`
target of any role is now a half-float refusal, and every set made after one is RGBA8 throughout,
upsample levels included. `BLOOM_EMISSION_PRECISION_REDUCED` is still staged only for a frame that
asked for half-float. The light helper also zeroes a NaN before it clamps (GLSL leaves `clamp` of a NaN
undefined), and the composite reads the emission alpha it suppresses by through the same helper.

## Amendment, 2026-10-22: the coarse-redraw comparison strategy is removed

`BloomFarBandStrategy.COARSE_REDRAW`, kept only so RenG's tests could compare it with the reach image,
is deleted along with the strategy switch; the reach image is the far band's only form.
