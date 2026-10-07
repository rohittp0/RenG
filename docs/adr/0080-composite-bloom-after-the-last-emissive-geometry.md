# Composite Bloom after the last Emissive Geometry

ADR 0074 placed Bloom after all map content, so its additive composite brightened Geometry, Models
and Map Stickers that the consumer had ordered above an Emissive Geometry. Bloom now composites
immediately after the last Emissive Geometry in `FramePlan.geometries`; the Geometry pass then
continues, followed by Models and Map Stickers as before. This makes the existing consumer layer
order authoritative without adding a public ordering API. The emission redraw still tests against
the shared depth renderbuffer, but at this boundary only displaced ground has written map depth:
Models have not drawn yet and Geometry never writes depth. That is correct because later opaque
content covers the already-composited light in painter's order; translucent later content transmits
it according to its ordinary blend.

## Consequences

A non-emissive Geometry between two emitters is below the final emitter boundary, so it still
receives Bloom. This sandwich case is a known limit of one frame-wide emission image and one
composite. The bloom phase now interrupts the Geometry loop, so it saves and restores exactly the
draw framebuffer, viewport, clear colour, depth test/function/mask, cull enable, blend
enable/equations/functions, program, vertex array, array buffer, active texture unit, and the texture
and sampler bindings on every unit the emission redraw or bloom chain uses before the loop resumes.

## Considered option

Write an occlusion mask from every Geometry after each emitter, then apply that mask while
compositing Bloom. This would resolve the sandwich case, but it adds another frame-sized render
target and per-Geometry mask writes to every affected frame. That cost is rejected for a case the
single last-emitter boundary cannot represent; consumers that require a non-emissive sandwich to
cover an earlier emitter must place it after the final emitter.

This amends ADR 0074's phase placement. ADRs 0024, 0025, 0027, 0030, 0034 and 0073 keep their draw
order and state contracts.
