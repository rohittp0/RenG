# Verify the GL contract against real contexts

CI runs RenG's GL conformance tests against genuine OpenGL contexts on two targets. `macosArm64Test`
creates a headless core-profile context through CGL, with no window and no display server, which the
spike proved works and which reports `4.1 Metal - 90.5` on Apple Silicon. `linuxX64Test` creates a
surfaceless EGL context backed by Mesa's llvmpipe software rasterizer, which needs no GPU and no
display server on an ordinary `ubuntu-latest` runner. A Cycle B throwaway fixture has now proved the
surfaceless EGL/llvmpipe exact-context mechanism; the permanent GL conformance job and production binding
still land with Cycle D rather than being inferred from that fixture. The remaining targets — `iosArm64`,
`iosSimulatorArm64`, `linuxArm64`, and `android` — stay at compile-and-host-test.

These two cover both binding implementations that can be tested cheaply: the shipped `platform.*` klibs
on macOS and the hand-written `dlsym` entry-point table on Linux. Without the Linux leg the entire
`dlsym` table — the one binding implementation RenG writes by hand, and therefore the one most likely to
be wrong — would never execute anywhere in CI. These are also the runs that make the guarantees in
ADR 0006 and ADR 0008 real rather than aspirational: that the documented GL state is identical before
and after a draw, and that a GLSL ES 3.00 source compiles under both a substituted and an unsubstituted
version directive.

Android's `GLES30` path is knowingly left without a real-context run, because an emulator job is the
slowest and flakiest thing that could be added to every push, and the seam it implements is exercised
by the other two. An `androidDeviceTest` gate remains available if Android-specific breakage ever
justifies it.
