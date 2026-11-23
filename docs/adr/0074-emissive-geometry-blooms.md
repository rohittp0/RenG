# Emissive Geometry blooms in screen space

The old way to make a Geometry appear luminous was to build a distance-field glow into that
Geometry's fragment shader. It could not escape the Geometry's rectangle, and the approximation
showed beading, medial-axis creases and clipping at that rectangle. Measurements made outside RenG
on RenG readbacks, and repeated on a phone, found that a six-downsample dual-Kawase chain removes
all four defects. It costs 1.0--1.3 ms GPU at 720 x 1280 and 2.2 ms at 1080 x 1920 on an Adreno 750
using `GL_TIME_ELAPSED`, and about 1.3 ms and 2.0 ms respectively on an M2.

## Emission is content; Bloom is a frame decision

A Geometry may now be `emissive`. It still draws normally. When its Frame Plan also carries a
`Bloom`, RenG draws it a second time into a frame-sized emission image and applies the screen-space
chain. Emissive has no effect without Bloom, while Bloom with no emissive Geometry performs no
extra GL work and changes no pixel. Bloom supplies the additive intensity, one non-negative weight
per used level, opaque-interior suppression and whitening toward the brightest colour channel. Its
default level weights are `[0, 0.9, 0.8, 0.6, 0.45, 0.35, 0.3]`.

The consumer Geometry namespace reserves the `rengGeometry` prefix. The first documented name in
that namespace is `rengGeometryEmissionPass`, an `int` which is `0` in the normal Geometry pass and
`1` in the emission redraw. As ADR 0008 requires, RenG binds it only when the compiled program
declares it; a shader which omits it writes the same colour on both draws. Reserving the prefix now
keeps later Geometry-pass additions from turning an apparently additive API change into a consumer
name collision.

## The pass belongs after map content and before labels

The emission redraw and bloom chain run immediately after map-anchored stickers and before labels.
All ordinary map content has therefore established the scene and its depth before emission is
sampled. The emission framebuffer shares the main offscreen surface's depth renderbuffer, is
cleared in colour only, and redraws emissive Geometry with the map regime's `GL_GEQUAL` test and no
depth writes. A ridge or model which occludes an emissive rectangle consequently occludes its
emission too.

Labels and the screen stack run later and are never collected into the emission image. That is
intentional: emissive is a property of Geometry, while engine-derived labels and consumer screen
stickers remain crisp interface content above the lit scene. The work is interstitial after phase 4
and before phase 5; labels remain phase 5 and the screen stack phase 6.

Targets are lazy: the renderer allocates them only for the first frame containing both a Bloom and
an emissive Geometry. Their dimensions are fixed to the renderer's output size under ADR 0012 and
they are retained until GPU-object loss or renderer close. The emission image and all seven
downsample levels are RGBA8. Seven upsample targets use RGBA16F when desktop GL or the GLES
colour-buffer extensions make it renderable, and otherwise use RGBA8. Allocation always creates all seven
levels, although a frame uses only the levels named by its weights. An incomplete framebuffer is a
soft failure: RenG emits a diagnostic and draws that frame without bloom.

Downsampling uses the centre four times plus four diagonal source-texel taps, divided by eight.
Upsampling uses a tent of four edge taps plus twice four diagonal taps, divided by twelve, and adds
the weighted current level. The coarsest image seeds the accumulation. The final pass whitens,
suppresses pixels whose emission alpha says they are inside an opaque source, scales by intensity,
and adds RGB to the main offscreen surface. Separate blending factors preserve the destination
alpha, so enabling Bloom cannot change the surface's coverage contract.

Framebuffer, viewport, blend enable/equations/factors, depth enable/function/mask, cull state,
texture and sampler bindings, program and vertex array are already members of the draw Restore Set.
The pass changes them inside the draw and the existing capture restores the consumer's state on
exit.

This amends ADRs 0024, 0025, 0027, 0030 and 0034 only where they state or number the phase order.
ADR 0008's bind-if-declared shader-interface rule is kept unchanged.
