# The geometry pass sets its own blend state

Phase 2 used to set depth testing, the depth write mask and culling, but not blending. A Geometry
therefore inherited the ground or Backdrop's premultiplied blend state when either had drawn, and
otherwise inherited the consumer's captured `GL_BLEND` state. `drawGeometry` set no blend state of
its own. On an Adreno 750, with no basemap or backdrop and blending disabled on entry, a Geometry
writing premultiplied `(0.25, 0.25, 0.25, 0.5)` over an opaque Geometry consequently read back as
`(64, 64, 64, 128)`: it replaced the earlier pixel instead of blending with it.

Phase 2 will enable blending once before its Geometry loop and establish the premultiplied convention
used by RenG's other internal passes: `enable(GL_BLEND)`,
`blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)`, and
`blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`. The Shader
Interface contract is therefore explicit for Geometry as well: a fragment shader writes
premultiplied colour, with RGB already multiplied by alpha.

Blend enable, separate factors and separate equations are already in the Restore Set. These calls
change the state used inside the pass without changing the consumer's state after the draw returns.
The pass owns the state it needs rather than making its result depend on whether an earlier pass
happened to run.

This amends ADR 0005's offscreen-surface decision, ADR 0025's map-regime draw order and ADR 0027's
Geometry-pass state ruling: being in the ordered, depth-tested map regime now also means compositing
Geometry output with an explicit premultiplied blend state.
