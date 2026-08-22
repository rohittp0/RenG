# Pass coplanar map content and fix the map regime draw order

ADR 0024 settled the order *between* the two draw regimes and left the order *within* the map regime
stated as arbitrary, on the reasoning that the depth buffer decides visibility among map-anchored things.
That reasoning was correct only while the map regime had no ground in it. The depth comparison RenG has
shipped since Cycle D is strict `GL_GREATER`, so a fragment at exactly the depth already in the buffer
fails and is discarded. The moment the basemap ground exists at altitude 0, every altitude-0 map-anchored
thing is coplanar with it and disappears: a `Geometry` is defined as "a lat/lon/altitude-bounded quad", so
painting a region at altitude 0 is the normal thing a consumer does, and under a top-down camera a
map-anchored sticker at altitude 0 is a quad lying exactly in the ground plane. This is not an edge case
and it announces itself in no way at all — the draw call is issued, the pixels are simply never written.

**This ADR changes the depth comparison to `GL_GEQUAL`, and fixes the map regime's draw order as a
contract: the ground first, then geometries in `FramePlan.geometries` order, then map-anchored stickers in
`FramePlan.stickers` order, with the last thing drawn winning an exact depth tie.** Both halves are
required and neither is meaningful alone. `GL_GEQUAL` alone would leave ties resolved by whatever order the
GL layer happened to iterate in; a fixed order alone would leave the ties losing rather than resolving. A
consumer may now rely on two things they could not before: an altitude-0 drawn thing over the ground is
visible, and where two map-anchored things genuinely tie on depth, the later-declared one is the one seen.
The ground is first because it is the backdrop consumer content paints onto; declaration order for the
rest mirrors the `sourceIndex` tiebreak the screen regime's compositing comparator already uses, so both
regimes now agree about what "same depth" means. The geometries-before-stickers half of that order was
already what shipped, and this ADR fixes it as specified rather than reordering anything.

Three alternatives were considered and rejected. **`glPolygonOffset` on the ground pass** is the textbook
answer to coplanar surfaces, and it is the most expensive one available here: `polygonOffset` is not on the
GL seam at all, so it would need adding across four platform bindings plus the recording fake and the
entry-point roster, and `GL_POLYGON_OFFSET_FILL` is neither captured nor restored, so ADR 0023's Restore
Set would have to be amended too. Widening a seam and reopening a Restore Set to fix a tie-breaking rule is
disproportionate. **Drawing the ground at a small negative altitude offset** needs no new state and is
wrong in a way that is hard to see: with a 24-bit fixed-point reverse-Z buffer the resolvable depth step
grows with the square of distance, so any fixed offset stops working as the camera pulls back, and a
distance-dependent one is a tuning parameter nobody can test the boundaries of. **Suppressing the ground's
depth writes** is the cheapest of the three and the one that costs the most later: a ground that writes no
depth can never occlude anything, and terrain is exactly what makes that matter. Terrain displaces the
ground with real elevation, and a model buried in a hill must be clipped by that hill; buying coplanarity
by giving up occlusion trades a bug that exists today for one that arrives with the next cycle.
`GL_GEQUAL` keeps occlusion wherever depths differ at all and touches only the exact-tie case, which is the
narrowest change that solves the problem.

The cost is that draw order inside the map regime is now load-bearing where it was documented as
irrelevant. `StickerPipeline`'s `StickerWorld` said so in as many words — "draws depth-tested, in any
order — the GPU depth buffer decides visibility between map-anchored things, not draw order" — and that
sentence is false from here on; it has been replaced rather than left standing, and the contract is pinned
by a test at both levels: a call-log assertion that the later-declared map-anchored sticker draws last, and
a real-context readback in which two coplanar altitude-0 stickers over the ground must show the second
one's colour rather than the first one's or the ground's. Note also that the second-order consequence
predicted for translucent map content is now the specified behaviour rather than a latent bug: two
overlapping semi-transparent map-anchored things at the same depth composite in declaration order.

One implementation detail is worth recording because it is what makes the tie exact rather than
approximate. A ground tile's model matrix is composed in **map** space and the view matrix applied after
it, while a map-anchored placement's rotation and scale are applied in **camera** space. For a pitch-0
camera and altitude 0 both paths reduce the depth row to the same two doubles, so the two surfaces land on
bit-identical window depth and the tie is genuine rather than a near-miss the buffer's precision happens to
collapse. Under a pitched camera a screen-parallel sticker quad is only coplanar with the ground at its
anchor point, which is correct: away from the anchor it genuinely is at a different depth.

The GL side of the change is one constant. `GlStateSnapshot` already captures and restores
`GL_DEPTH_FUNC`, so nothing about ADR 0023's guarantee moves, and no seam grows.
