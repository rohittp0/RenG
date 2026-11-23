# Bloom composites through a soft shoulder

ADR 0074's composite added the bloom image's RGB to the main offscreen surface with fixed-function
blending. Over a bright coloured fill that clips channel by channel: Travel Animator's white route,
glowing at its default 6 px and 60% over a lime region, left a 5 px band of pixels with two channels at
or above 250 beyond the stroke -- a white rim, not light of the fill's hue -- and 29 px at 12 px and
100%. Every compositing rule that leaves dark grounds alone was measured on RenG readbacks; this one
removed the band at both settings while keeping the light added on a dark ground within 1% of the
additive composite.

## The composite

The composite pass now reads the frame. It blits the surface's colour into a frame-sized RGBA8 copy
(a texture cannot be sampled while it is the bound colour attachment), then, after the unchanged
whitening, intensity and inside-suppression, computes `s = frame + bloom` and soft-shoulders the
brightest channel of `s`:

- the knee is `max(0.8, the frame's brightest channel)` at that pixel, so the frame itself is never
  compressed and a pixel can only get brighter;
- at or below the knee, the result is `s` -- exactly the additive composite;
- above it, the brightest channel maps to `knee + h * (1 - exp(-(m - knee) / h))` with
  `h = 1 - knee`, and all three channels are scaled by the same factor, so the sum keeps its hue as
  it approaches 1 instead of clipping to white.

It draws with blending disabled and alpha writes masked, then reopens the colour mask, so the surface's
coverage contract is unchanged. The read framebuffer binding it changes and the extra texture unit it
binds are both in the Restore Set.

The knee is an internal constant (`BLOOM_SHOULDER_KNEE`), not a `Bloom` field: nothing needs to tune
it, and adding a field would change the public ABI and every Frame Identity carrying a Bloom.

## Consequences

The surface is always single-sample RGBA8 (ADR 0005), so the blit is a plain same-format copy with no
resolve. It costs one extra frame-sized RGBA8 target, allocated lazily with the other bloom targets
and freed with them, and one full-frame copy per bloom frame. A saturated emitter's light over a dark
ground can lose a little of its white inner rim -- the shoulder keeps the emitter's own hue there --
and over a ground already brighter than the knee the added light is smaller than an add would have
been, which is the point.

This amends ADR 0074's composite only; its chain, targets, suppression and placement (as amended by
ADR 0080) are unchanged.

## Extended, 2026-10-14: ADR 0083 steers the shoulder's output to the light's hue

The shoulder still runs as above, but its output is no longer the final colour: where the bloom is
strong, its Oklab chroma is steered to the light's own hue at the shoulder's luminance, so a red glow
reads red over teal and lime instead of mauve and orange. A white emitter's halo over a bright fill is
now whiter, not a brighter tint of the fill, and the frame can no longer only get brighter.
