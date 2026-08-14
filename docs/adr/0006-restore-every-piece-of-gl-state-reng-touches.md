# Restore every piece of GL state RenG touches

Drawing reads a closed, documented set of OpenGL state before it begins and restores exactly that set
before it returns, and RenG guarantees it modifies nothing outside that set. The set covers the bound
framebuffer, the active program, the bound vertex array, the active texture unit and the bindings on
the units RenG uses, the blend, depth, and cull enables and their parameters, the viewport, and the
scissor box. A consumer therefore sees the context exactly as it left it, and may share one context
between RenG and its own drawing without re-establishing anything.

RenG's contract is that it makes no changes to the host system, and a live OpenGL context is host
state. Documenting a list of state RenG leaves dirty would have been free, and it is the ordinary
convention for OpenGL middleware, but it turns the purity claim into a claim with a carve-out and
moves a class of subtle corruption onto every consumer that shares a context. Resetting to OpenGL's
specified defaults instead of to the caller's values needs no reads but silently discards any
non-default state the caller had set, so it is neither free nor pure.

The cost is a handful of `glGet` calls per frame, which stall the pipeline on some drivers. This is
measured per frame rather than per draw call, so it is negligible against the cost of drawing a 3D
scene. The guarantee is testable rather than aspirational: a conformance test reads the documented set
before and after a draw and asserts it is unchanged, and that test runs on every target where a
context is available.
