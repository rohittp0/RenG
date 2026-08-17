# Draw only into a caller-current OpenGL context

RenG renders through OpenGL on every published target and requires the consumer to hand it a
**Render Context** that is already current on the calling thread, plus a **Render Target** naming a
framebuffer object and its pixel dimensions. RenG never creates a context, never makes one current,
never presents or swaps buffers, and never references CGL, EAGL, EGL, `NSOpenGLContext`, or
`ANativeWindow`. A consumer that wants a window, a swapchain, a capture path, or an encoder builds it
outside RenG. ADR 0012 later moved pixel dimensions out of **Render Target** and into immutable renderer
configuration; the target now publicly names only its framebuffer object.

OpenGL is the only API available across `android`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`,
`linuxX64`, and `linuxArm64` without shipping a translation layer, and the public API already commits
to consumer-supplied OpenGL shaders for **Geometry**, so a Metal or Vulkan backend would change the
shader contract rather than sit behind it. Apple deprecated both OpenGL and OpenGL ES, which is the
strongest argument for this boundary rather than against it: because the consumer creates the context,
Apple's deprecation is the consumer's decision and its consequences land in consumer code, while RenG
links no deprecated context-management entry point at all. Apple still ships the frameworks, and
macOS routes OpenGL through Metal — a headless `macosArm64` core-profile context reports
`4.1 Metal - 90.5` — so the deprecation costs nothing today.

Accepting platform surface handles instead was rejected because it would pull windowing APIs, four
context-creation paths, and every deprecated Apple entry point into a library whose stated contract is
that it makes no changes to the host system. The cost of this decision is that the simplest possible
consumer has more work to do before its first frame, and that RenG cannot recover from a lost context
on its own.
