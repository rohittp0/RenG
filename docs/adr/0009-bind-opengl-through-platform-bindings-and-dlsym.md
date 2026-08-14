# Bind OpenGL through platform bindings and dlsym

RenG reaches OpenGL through three implementations of one internal seam. `macosArm64` uses Kotlin/Native's
shipped `platform.OpenGL3` and `platform.OpenGLCommon`; `iosArm64` and `iosSimulatorArm64` use
`platform.gles3` — note the package is `platform.gles3`, not `platform.OpenGLES3`; `linuxX64` and
`linuxArm64` resolve entry points at runtime with `dlopen` and `dlsym` against hand-declared function
pointers; `android` uses `android.opengl.GLES20` and `GLES30`. No target uses a hand-written cinterop
definition.

Hand-rolling cinterop against the Apple SDK headers is actively dangerous rather than merely redundant.
Clang availability attributes mark every OpenGL entry point deprecated past the deployment target
(`API_DEPRECATED(macos(10.5, 10.14))`), and cinterop responds by *silently dropping* those declarations:
a definition naming `OpenGL/gl3.h` produced a klib containing nineteen GLU functions and no GL ones,
with no error and no warning. `GL_SILENCE_DEPRECATION` in `compilerOpts` did not rescue it under Clang
modules either. The klibs Kotlin/Native ships are already built correctly — 509 functions for
`platform.OpenGL3`, 296 for `platform.gles3` — so the fix is to stop building our own.

Linux gets no cinterop for the opposite reason: Kotlin/Native's Linux sysroots ship `dlfcn.h` and no
`GL/` or `EGL/` headers at all, so there is nothing to bind against at compile time for either
`linuxX64` or `linuxArm64`. Runtime resolution needs no headers, cross-compiles from any host, and
tolerates Mesa and proprietary drivers alike. The price is a hand-written entry-point table, and the
enum constants that go with it live once in common code because GLES 3.0 and GL 3.3 agree on their
values.

Two structural constraints follow. Platform GL bindings are invisible from shared source sets — a file
in `iosMain` cannot resolve `platform.gles3` while the identical file in `iosArm64Main` can — so GL code
lives in leaf source sets behind `expect`/`actual`. And the seam itself is declared in terms both sides
can implement: Android's bindings take `String`, `java.nio.Buffer`, and `float[]` where Kotlin/Native
takes raw pointers, so the shared signatures speak in Kotlin arrays and the marshalling is each
implementation's business.
