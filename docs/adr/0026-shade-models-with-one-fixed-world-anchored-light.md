# Shade models with one fixed world-anchored light

RenG draws models in the cycle after the basemap, and glTF models carry `NORMAL` attributes and PBR
materials that presuppose a light. RenG has never had one. There is no lighting term in `CONTEXT.md`, no
prior ADR, and the word "radiance" appears once in the whole repository, about the ground. ADR 0021
nonetheless admits `NORMAL` into the supported GLB subset on the stated grounds that it is "needed for any
lit shading" — so the subset already assumed a decision the rest of the design had never made.

Three answers were available. Render unlit: sample base colour, multiply by the material factor, ignore
normals. Invent a light and bake it into RenG's model shader. Or add lighting to the public API.

Unlit is the least defensible. It is not wrong in any checkable sense, but a glTF model that looks
sculpted in every other viewer renders as a paper cutout in RenG, and the marquee feature of the cycle
would ship looking broken to anyone who compared. A public lighting API is the most honest and the most
expensive: it drags in how many lights, in what units, whether lighting reaches stickers and geometries,
and what happens to the basemap ground — a design worth its own cycle, not a rider on models, in a cycle
that already grows the public ABI with model resource kinds.

So RenG invents a light, and this ADR exists so that it is invented once, in the open, rather than
appearing as an unexplained constant in a shader.

The light is directional, at azimuth 335 degrees and elevation 45 degrees, with an ambient term so that a
surface facing away from it stays readable instead of going black against a bright basemap. The azimuth is
not chosen for taste: 335 degrees is the cartographic convention for relief shading, and it is
MapLibre's own `hillshade-illumination-direction` default, adopted because light from the north-west avoids
the inversion illusion in which hills read as valleys. Taking the same value means model shading and
terrain hillshading agree by construction when terrain lands, rather than RenG carrying two lights pointing
different ways.

The light is **world-anchored**, and that is the half most likely to be questioned, because Mapbox anchors
its own light to the viewport by default. Mapbox's light exists to shade extruded *map features*, where
buildings should look consistent whatever the bearing. RenG's models are consumer-placed objects at real
coordinates. A viewport-anchored light makes a model's shading swim as the camera orbits, so the object
reads as lit by the viewer's flashlight rather than by the world — wrong for something pinned to a place.
World-anchoring keeps one side of the model bright as the camera circles it.

Deriving the light from the style was considered and rejected on measurement rather than principle.
Rentile exposes `GroundRadianceDescriptor`, but it carries red, green and blue only — a colour, no
direction. The style specification does define a `light` object that carries one, and RenG already parses
the style document, so reading it would have been cheap. **Zero of the 34 map styles RenG is verified
against declare a `light` object.** Parsing it would be dead code for every style anyone actually uses, and
there is correspondingly nothing to agree or disagree with.

The consequence is that RenG imposes this light on every model in every consumer's scene, and that changing
the constant later changes everybody's pixels. That is accepted deliberately: the alternative is designing
a public lighting API under the pressure of a cycle that has other work to do. When such an API arrives it
supersedes a written decision rather than silently breaking existing renders, which is the whole reason for
recording the constant here instead of in a shader comment.

Stickers, geometries and the ground remain unlit. A geometry is painted by the consumer's own shader pair
under ADR 0008, and lighting it would contradict that contract outright.
