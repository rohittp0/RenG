# Render into RenG's own offscreen surface

RenG allocates an offscreen colour-and-depth surface at the renderer's configured output size, draws
the whole frame into it, and then composites the result into the caller's framebuffer. A
**Render Target** therefore only has to be a colour-writable framebuffer of the configured dimensions;
it needs no depth attachment, no stencil, and no particular format. The surface is allocated once and
never resized, because output size is fixed at setup (ADR 0012), and it is reported through the same
API that reports every other resource RenG holds.

Depth is the reason. **Map Anchoring** requires real occlusion against the 3D scene, which requires a
depth buffer, and a consumer's framebuffer frequently has none — an Android `SurfaceView` or a
`CAEAGLLayer` configured without a depth renderbuffer is entirely ordinary. Requiring the consumer to
attach one would make RenG's occlusion quality hostage to a depth format RenG never chose and would
add a precondition that fails at the worst possible moment. Owning the surface also means a window
framebuffer and the harness's capture framebuffer take the identical code path.

The costs are one extra full-screen composite pass per frame and an offscreen allocation proportional
to the target size. Compositing is a blended draw rather than a framebuffer blit, because a blit does
not blend and a consumer compositing RenG's output over existing content needs it to.
