# Bloom light carries its own hue

ADR 0082's soft shoulder stopped bright fills clipping to white, but the composite was still additive:
the halo's colour was the ground plus the light, not the light. In Travel Animator a white route with
a red (#FF3B30) glow at alpha 0x8B, 6 px, read pink-mauve over the teal sea (56, 101, 117) and orange
over the default lime region: 4 px past the stroke its Oklab hue was 91 degrees off the glow's on the
sea (chroma 0.021, against 0.22 for the glow colour itself) and 65 degrees off on lime. Any purely
additive composite does this. A user who picks a red glow expects red light on every ground.

## The composite

The shoulder is unchanged and still computed first (knee, headroom, exponential roll-off, scaled
channels). Its output then has its chroma steered towards the bloom light's own hue:

- the bloom's strength is its brightest channel at the pixel, after whitening, intensity and inside
  suppression;
- the shouldered colour and the light's normalised colour (`bloom / strength`) are taken to Oklab;
- the target chroma is the light's `ab`, scaled by `min(1, L / L_light)` so a dim pixel never gets
  more chroma than the light has at that lightness;
- the pixel's `ab` moves to the target by `smoothstep(0.03, 0.30, strength)`
  (`BLOOM_HUE_STEER_START`, `BLOOM_HUE_STEER_FULL`); Oklab lightness is the shoulder's;
- back in linear RGB, the colour is rescaled to the shoulder's linear luminance (Rec. 709 weights),
  negatives are clamped, and a colour out of gamut is divided by its brightest channel, which keeps
  its chroma and gives up lightness.

At or below a strength of 0.03 the steer's weight is zero, so the composite skips it and the result is
exactly the shoulder's: the bloom's long faint tail is still plain light added to the ground, does not
desaturate it far from the emitter, and most of the frame pays nothing for the Oklab round trip.
Blending stays off, alpha writes stay masked and the destination copy is unchanged; only the
composite's final colour differs. The two thresholds are internal constants, not `Bloom` fields, for ADR 0082's reason: the ABI
and every Frame Identity carrying a Bloom stay put. There is no switch back to the plain shoulder.

## Measurements

On RenG readbacks through Travel Animator's harness (`--glow-hue`, macOS GL, every metric against the
same project without the glow; exp/glow-hue mode 10):

| case | hue error at 4 px, F5 -> T10 | chroma at 4 px, F5 -> T10 |
|---|---|---|
| red 0x8B, 6 px, sea | 91 -> 2 degrees | 0.021 -> 0.185 |
| red 0x8B, 6 px, lime | 65 -> 0 | 0.141 -> 0.173 |
| mean over every ground and glow colour, 0x8B | 45.7 -> 2.8 | |
| mean over every ground and glow colour, 0xFF | 28.5 -> 0.6 | |

Light added on a dark ground stays 0.96-1.01x the shoulder's (0.93-0.99x at 12 px and 100%). No pixel
clips over lime. On snow the glow is more visible than with the shoulder (Oklab dE at 6 px 7-19, from
4-11).

Cost at 1080x1920: about 0.07 ms per frame on macOS (5.94 -> 6.01 ms, whole frame including
readback, measured with the steer computed wherever any bloom reached). On the CPH2629
(Travel Animator's BloomCompositeCostDeviceTest, four glowing routes over lime, interleaved runs in
mirrored order) the bloom's share of the frame went from ~13.8 to ~15.8 ms with the steer computed
wherever any bloom reached, and to ~14.9 ms once it was skipped at or below the start threshold, as
shipped: about 1 ms per frame.

## Consequences

- Over a bright fill strong coloured light makes the halo darker and heavier: Oklab lightness drops
  11-13 next to the line on lime, and up to 28-30 for a blue glow on snow. That is what lets a red
  halo read as red on lime; keeping lightness instead (measured as T9/T11) turns it pale pink.
- A white glow's halo becomes neutral white rather than a lighter tint of the ground (chroma 4 px out
  over lime 0.15 -> 0.09 at 0x8B, -> 0.007 at 0xFF). That is the glow's own colour, but it changes a
  white route over a coloured fill. ADR 0082's readback case, white over orange, now reads whiter, not
  a brighter orange.
- Over the sea the halo carries 11-28% less added luminance than the shoulder's, but it is red.
- The outer tail (strength below 0.03) is still additive.

This extends ADR 0082: its shoulder, frame copy, blend state and constants are unchanged, and ADR
0074's chain, targets, suppression and placement (as amended by ADR 0080) are unchanged.

## Note, 2026-10-22: the steer is skipped below its start

Skipping the Oklab round trip where the steer's weight is zero (strength at or below
`BLOOM_HUE_STEER_START`) took the composite's extra cost on an OPPO CPH2629 from about 2.0 to about
1.0 ms per 1080x1920 frame.
