# Composite Bloom after the last Emissive Geometry

ADR 0074 placed Bloom after all map content, so its additive composite brightened Geometry, Models
and Map Stickers that the consumer had ordered above an Emissive Geometry. Bloom now composites
immediately after the last Emissive Geometry in `FramePlan.geometries`; the Geometry pass then
continues, followed by Models and Map Stickers as before. This makes the existing consumer layer
order authoritative without adding a public ordering API. The emission redraw still tests against
the shared depth renderbuffer, but at this boundary only displaced ground has written map depth:
Models have not drawn yet and Geometry never writes depth. Later opaque content covers the
already-composited light in painter's order, while translucent later content transmits it according
to its ordinary blend.

That painter's-order result does not reproduce the old emission image when a Model occludes an
emitter. The redraw no longer tests against Model depth, so the occluded part of an emitter still
feeds the halo beyond the Model's silhouette and still participates in `insideSuppression`; only the
Model's own opaque pixels cover the composite. Consequently the emitter-last frame is byte-identical
to ADR 0074's placement only when it has no Models or Map Stickers whose later draw can change the
composite result. The readback fixture records the Model case explicitly: its opaque sample remains
`(32,176,240,255)`, while a sample beyond its silhouette rises from `(64,64,64,255)` to
`(72,72,72,255)` with `insideSuppression=1`.

## Consequences

A non-emissive Geometry between two emitters is below the final emitter boundary, so it still
receives Bloom. This sandwich case is a known limit of one frame-wide emission image and one
composite. The bloom phase now interrupts the Geometry loop, but the state at that boundary is owned
by the loop rather than discovered from the driver. After the composite it explicitly rebinds the
surface framebuffer, full-output viewport, enabled `GEQUAL` depth test with writes off, disabled
culling, enabled `FUNC_ADD` blending, and the loop's blend factors. It then invalidates the loop's
blend cache so the next Geometry binds its own factors. No `glGet*`, program, vertex-array, buffer,
or generic texture restoration is needed: `drawGeometry` rebinds the pipeline and re-uploads both
shared buffers on every draw. Consumer uniform and sampler values persist on their program, texture
unit bindings persist on the context, and omitted consumer values intentionally inherit that state;
each program touched by an emission redraw therefore receives the last consumer values its normal
pass bound before Bloom.

## Considered option

Write an occlusion mask from every Geometry after each emitter, then apply that mask while
compositing Bloom. This would resolve the sandwich case, but it adds another frame-sized render
target and per-Geometry mask writes to every affected frame. That cost is rejected for a case the
single last-emitter boundary cannot represent; consumers that require a non-emissive sandwich to
cover an earlier emitter must place it after the final emitter.

This amends ADR 0074's phase placement. ADRs 0024, 0025, 0027, 0030, 0034 and 0073 keep their draw
order and state contracts.
