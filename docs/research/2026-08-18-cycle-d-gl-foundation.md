# Cycle D GL foundation — research and spike record, 2026-08-18

Cycle D owns RenG's internal GL seam and its three implementations. This note records what was
measured rather than assumed, on the Linux development host, before that cycle's specification is
written. Everything below is either a measurement with the command that produced it, a prior finding
explicitly labelled as inherited, or a decision left open for the spec. Throwaway spike programs were
written outside the repository and are not checked in; they are named here only so the measurements can
be reproduced.

Two prior claims were **corrected** by these measurements and both matter to an approved ADR. The Linux
leg of ADR 0011 was reached — a genuine surfaceless EGL context on Mesa llvmpipe, both as OpenGL ES 3.2
and as desktop 4.5 core — and it shows that ADR 0008's version-directive substitution cannot be keyed on
the target platform, because one Linux target serves both dialects and the substitution that saves a
desktop context is fatal to an ES one. ADR 0006's restore set is also incomplete as written, and one
piece of the state RenG touches is not restorable at all.

Throughout, `$KN` is `~/.konan/kotlin-native-prebuilt-linux-x86_64-2.3.21` and `$PK` is
`$KN/klib/platform`. Kotlin/Native is 2.3.21. Mesa is 25.2.8-0ubuntu0.24.04.2 with LLVM 20.1.2.

## Verified facts

| Fact | Command that produced it |
|---|---|
| `platform.OpenGL3` on `macos_arm64` declares **509** external functions | `$KN/bin/klib dump-metadata $PK/macos_arm64/org.jetbrains.kotlin.native.platform.OpenGL3 \| grep -cE '^\s+public final external fun '` |
| `platform.OpenGLCommon` declares **52** external functions, every one a `CGL*` entry point, plus the `GLenum`/`GLuint`/`GLboolean`… typealiases | same command against `…platform.OpenGLCommon`; names via `grep -oE 'fun [A-Za-z0-9_]+'` |
| The module `OpenGLES3` on `ios_arm64` publishes package **`platform.gles3`** with **296** external functions | `$KN/bin/klib dump-metadata $PK/ios_arm64/org.jetbrains.kotlin.native.platform.OpenGLES3 \| grep -oE '// package name: [a-zA-Z0-9_.]*'` and `grep -cE '^\s+public final external fun '` |
| `OpenGLES2` → `platform.gles2` (208 functions); `OpenGLESCommon` → `platform.glescommon` (**0** functions, types only); `EAGL` → `platform.EAGL` (5 free functions) | same two commands against those three klibs |
| `linux_x64` and `linux_arm64` each ship exactly **five** platform klibs — `builtin`, `iconv`, `linux`, `posix`, `zlib` — and no GL, GLES or EGL klib | `ls $PK/linux_x64 $PK/linux_arm64` |
| `platform.posix` exposes `dlopen`, `dlsym`, `dlclose`, `dlerror` and the `RTLD_*` constants, on **both** Linux targets | `$KN/bin/klib dump-metadata $PK/linux_x64/…platform.posix \| grep -nE 'fun (dlopen\|dlsym\|dlclose\|dlerror)\('`, repeated for `linux_arm64` |
| Both Kotlin/Native Linux sysroots ship `dlfcn.h` and **zero** `GL/`, `GLES3/` or `EGL/` headers | `find ~/.konan/dependencies -name dlfcn.h` and `find ~/.konan/dependencies \( -path '*/GL/*' -o -path '*/GLES3/*' -o -path '*/EGL/*' -o -name gl.h -o -name egl.h \) \| wc -l` → `0` |
| `android.opengl.GLES30 extends android.opengl.GLES20`, so one import reaches both | `javap -classpath /home/user/android-sdk/platforms/android-37.0/android.jar android.opengl.GLES30 \| head -3` |
| Every entry point a renderer needs is present on all four bindings; the 84-name checklist has no gap | `javap` dumps plus `grep -cE " <name>\("` / `grep -cE "external fun <name>\("` across the four dumps |
| A Kotlin/Native function pointer can carry at most **21** parameters (`Function22`), against a 10-parameter worst case in GL | `$KN/bin/klib dump-metadata $KN/klib/common/stdlib`, then the maximum `FunctionN` among `CPointer<CFunction<…>>.invoke` overloads |
| A surfaceless EGL context is obtainable on this host as **OpenGL ES 3.2** on llvmpipe | `apt-get install -y --no-install-recommends libegl1 libegl-mesa0 libgles2`, then `cc -o egl_spike egl_spike.c -ldl && ./egl_spike` |
| The same host also yields a **desktop 4.5 core profile** context on llvmpipe | `./egl_spike desktop` |
| On the ES context `#version 300 es` compiles and links and `#version 330 core` **fails**; on the desktop core context **both** succeed | `./egl_spike` and `./egl_spike desktop`, dialect probe section |
| The desktop core context advertises `GL_ARB_ES3_compatibility`; the ES context does not (it has no need to) | `cc -o extcheck extcheck.c -ldl && ./extcheck && ./extcheck desktop` |
| `glGetString(GL_EXTENSIONS)` returns `NULL` with `GL_INVALID_ENUM` on the desktop core profile and works on ES | `cc -o extstr extstr.c -ldl && ./extstr && ./extstr desktop` |
| The complete ADR 0006 save/perturb/restore round trip is byte-exact on both context types | `cc -o restore_spike restore_spike.c -ldl && ./restore_spike && ./restore_spike desktop` |
| `glGetError` is destructive: a provoked flag reads `0x500` once and `0x0` thereafter, and cannot be pushed back | same spike, final line |
| `GL_LINE_SMOOTH` and `GL_DRAW_BUFFER` are queryable on desktop and raise `GL_INVALID_ENUM` on ES; `GL_FRAMEBUFFER_SRGB` is queryable on both but defaults **enabled** on ES and **disabled** on desktop core | `cc -o state_spike state_spike.c -ldl && ./state_spike && ./state_spike desktop` |
| A surfaceless context's initial viewport and scissor box are `0,0,0,0` | same spike |
| A Kotlin/Native `dlsym` GL seam compiles for `linux_x64` **and** `linux_arm64` from one source file, and runs against the real context | `$KN/bin/kotlinc-native -target linux_x64 -o glseam GlSeamSpike.kt && ./glseam.kexe`, then `-target linux_arm64` |
| cinterop derives its package name from the **`.def` filename**, with no `package=` line anywhere | `cinterop -def renggl.def …` → package `renggl`; the identical headers via `othername.def` → package `othername` |
| cinterop **silently drops** a declaration marked `__attribute__((unavailable))` — four of five test declarations survived, with no error and no warning | `cinterop -def avail.def …` then `klib dump-metadata avail_out.klib \| grep -cE 'external fun reng'` → `4` |

## What each target actually binds

The prior inventory was accurate. `macosArm64` gets `platform.OpenGL3` for the 509 GL entry points and
`platform.OpenGLCommon` for the 52 CGL calls and the shared `GL*` typealiases — and note that the GL
scalar types RenG has to marshal live in `OpenGLCommon`, not in `OpenGL3`, so both klibs are needed even
for code that never creates a context. A third klib, `platform.OpenGL`, carries 1043 functions spanning
the legacy compatibility profile; RenG has no use for it and importing it would make it easy to call a
function that does not exist in a core profile.

The iOS package name really is `platform.gles3` while the *module* is named `OpenGLES3`, which is the
kind of mismatch that costs an afternoon. `platform.glescommon` — also lowercase, also unlike its module
name `OpenGLESCommon` — declares no functions at all; it exists to hold the type aliases, exactly as
`OpenGLCommon` does on macOS. `platform.EAGL` is context management and RenG must not touch it (ADR 0001).

Linux has no GL binding of any kind and no headers to build one from, on either target, which is the
whole justification for ADR 0009's `dlsym` table. `platform.posix` supplies `dlopen`, `dlsym`, `dlclose`,
`dlerror` and the `RTLD_*` constants on both.

One clarification the prior notes did not make, and which is easy to get wrong: `$PK/android_arm64/`
does contain `platform.egl`, `platform.gles3` and friends. Those belong to Kotlin/Native's
`androidNativeArm64` family of targets. RenG's `android` target is the AGP/JVM one (ADR 0010), so those
klibs are unreachable from it and irrelevant to Cycle D. Android's binding is `android.opengl.GLES30`,
which extends `GLES20`, so a single import covers both. `GLES20` declares 302 integer constants and
`GLES30` another 304; every ES 3.0-only token RenG needs for state restore — `GL_VERTEX_ARRAY_BINDING`,
`GL_SAMPLER_BINDING`, `GL_DRAW_FRAMEBUFFER_BINDING`, `GL_READ_FRAMEBUFFER_BINDING`,
`GL_UNPACK_ROW_LENGTH`, `GL_NUM_EXTENSIONS`, `GL_MAX_COLOR_ATTACHMENTS` — is on `GLES30` and absent from
`GLES20`, so `GLES20` alone is not sufficient even though it holds most of the functions.

An 84-name checklist covering framebuffers, renderbuffers, textures, samplers, buffers, VAOs, shaders,
programs, uniforms, blend/depth/cull/scissor/viewport state, the `glGet*` family, `glGetError` and the
draw and readback calls was run against all four bindings. Nothing is missing anywhere. The only
distribution detail worth remembering is that the ES-3-era calls RenG depends on — `glGenVertexArrays`,
`glBindVertexArray`, `glBlitFramebuffer`, `glDrawBuffers`, `glReadBuffer`, `glTexStorage2D`,
`glBindSampler`, `glGetStringi` — are all on `GLES30` rather than `GLES20`.

## The Linux dlsym path, and the shape of the seam

The seam is the hard part of Cycle D, because the same interface must be satisfiable by three
pointer-based Kotlin/Native bindings and by Android's JVM bindings, which speak `String`, `int[]` with an
offset, `java.nio.Buffer`, and `boolean`. A working `linuxX64` implementation was built and run to make
the tension concrete rather than argue about it in the abstract.

Resolution is unremarkable: `dlopen("libEGL.so.1", RTLD_NOW)` from `platform.posix`, then each entry point
through `eglGetProcAddress` with `dlsym` as a fallback, each `COpaquePointer` reinterpreted to a
hand-declared `CPointer<CFunction<…>>`. Function-pointer arity is not a constraint — Kotlin/Native
supplies `invoke` overloads up to 21 parameters and GL's worst case is `glBlitFramebuffer` at ten.
Loading `libEGL.so.1` rather than `libGLESv2.so.2` matters, because `eglGetProcAddress` on a
glvnd-dispatched system is the only resolver guaranteed to return the entry points belonging to the
current context's vendor; `dlsym` against the dispatch library happens to work here but is not the
contract. RenG resolves entry points, never contexts: the spike creates the context because it is
standing in for the test fixture, which is where that code belongs under ADR 0001.

Two decisions fall out of the measured signatures. The first is that the seam must be typed at
Android's width, not Kotlin/Native's, because Android is the narrower side everywhere they differ:

| Concept | GL C | Kotlin/Native klib | Android `GLES30` | Seam should use |
|---|---|---|---|---|
| Object name | `GLuint` | `UInt` | `int` | `Int` |
| Enum | `GLenum` | `UInt` | `int` | `Int` |
| Boolean in | `GLboolean` | `UByte` | `boolean` | `Boolean` |
| Boolean out | `GLboolean*` | `CValuesRef<UByteVar>` | `boolean[]` + offset | `BooleanArray` |
| Integer out | `GLint*` | `CValuesRef<IntVar>` | `int[]` + offset | `IntArray` |
| Buffer size | `GLsizeiptr` (`Long`) | `Long` | `int` | `Int` |
| Pixel/vertex data | `const void*` | `CValuesRef<*>?` | `java.nio.Buffer` | `ByteArray?` |
| Shader source | `char**` + count | `CValuesRef<CPointerVar<ByteVar>>` | a single `String` | `String` |

`glShaderSource` is the sharpest case. Android exposes exactly `glShaderSource(int, String)` — one
string, no count, no length array — so the seam cannot expose the `char**` form even though both
Kotlin/Native klibs do. `Int` for object names is likewise forced: Android's API cannot express `UInt`,
and `Int` ↔ `UInt` conversion on the native side is free. Going the other way — typing the seam in
`UInt` and converting on Android — would put a conversion on the one implementation that has no
zero-cost way to do it, and would leak a type the JVM side cannot honour.

The second decision is that all the marshalling asymmetry stays inside the implementations. A seam
declared this way:

```kotlin
internal interface GlBinding {
    fun getIntegerv(pname: Int, out: IntArray)
    fun getBooleanv(pname: Int, out: BooleanArray)
    fun isEnabled(cap: Int): Boolean
    fun shaderSource(shader: Int, source: String)
    fun bufferData(target: Int, data: ByteArray, usage: Int)
    fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: ByteArray?,
    )
}
```

is realised on the native side by `memScoped`/`usePinned` and on Android by an offset or a wrapped
buffer:

| Seam call | Kotlin/Native realisation | Android realisation |
|---|---|---|
| `getIntegerv` | `memScoped { allocArray<IntVar>(n) }`, call, copy back | `GLES30.glGetIntegerv(pname, out, 0)` |
| `getBooleanv` | `allocArray<UByteVar>(n)`, then `!= 0` per element | `GLES30.glGetBooleanv(pname, out, 0)` |
| `isEnabled` | `glIsEnabled(cap.toUInt()).toInt() != 0` | `GLES30.glIsEnabled(cap)` |
| `shaderSource` | one-element `allocArray<CPointerVar<ByteVar>>`, `source.cstr.ptr`, count `1` | `GLES30.glShaderSource(shader, source)` |
| `bufferData` | `data.usePinned { glBufferData(t, size.toLong(), it.addressOf(0), u) }` | `GLES30.glBufferData(target, data.size, buffer, usage)` |
| `texImage2D` | `pixels.usePinned { … it.addressOf(0) }`, `null` passes through | `GLES30.glTexImage2D(…, buffer)` |

Every row of that table was executed on the real llvmpipe context by the Kotlin/Native spike, including
the `ByteArray` upload paths, and `glGetError` returned `0` afterwards.

There is one pleasant surprise. For all twelve representative calls examined, `platform.OpenGL3` and
`platform.gles3` declare **byte-identical Kotlin signatures** — same parameter types, same
`CValuesRef` shapes, `GLboolean` as `UByte` on both. Only the package differs. So the macOS and iOS
implementations differ solely in their import line, and Kotlin has no conditional import. The spec has
to choose between three near-duplicate thin translation objects in three leaf source sets, generating
them, or a per-leaf `typealias` indirection; it should not assume the duplication can simply be
factored into a shared source set, for the reason in the next paragraph.

ADR 0009 states that platform GL klibs are invisible from shared source sets, so a file in `iosMain`
cannot resolve `platform.gles3` while the same file in `iosArm64Main` can. **That claim was not
re-verified here**, because checking it means running Gradle in a repository where sibling work is in
flight. It is worth re-confirming early in Cycle D at almost no cost, because it is the single fact that
decides the source-set layout, and because both iOS targets do ship the klib — which makes the
intersection argument for `iosMain` seeing it at least plausible on a current Kotlin version.

## A real headless GL context on Linux

This is the result that most changes what Cycle D can promise. ADR 0011 commits `linuxX64Test` to a
surfaceless EGL context on llvmpipe; that mechanism now runs here, in two distinct flavours, on an
ordinary container with no GPU, no display server, and no window.

`libEGL` was **not** present initially. Installing it took one command, and `apt-get update` was
required first because the cached `libegl-mesa0` version 404s:

```
apt-get update
apt-get install -y --no-install-recommends libegl1 libegl-mesa0 libgles2
```

That pulled `libegl1 1.7.0-1build1` (glvnd dispatch), `libegl-mesa0 25.2.8-0ubuntu0.24.04.2`,
`libgles2 1.7.0-1build1`, and upgraded `libgl1-mesa-dri`, `mesa-libgallium`, `libgbm1` and
`libglx-mesa0` to `25.2.8-0ubuntu0.24.04.2`. Mesa's ICD registers itself at
`/usr/share/glvnd/egl_vendor.d/50_mesa.json`, and `swrast_dri.so` was already installed, so llvmpipe
needed nothing further. No X server, Wayland compositor, GBM device, or DRM node was involved.

The path is `eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, NULL, NULL)` — the client extension
string advertises `EGL_MESA_platform_surfaceless`, and the initialised display advertises
`EGL_KHR_surfaceless_context` — then `eglInitialize`, `eglBindAPI`, `eglChooseConfig`,
`eglCreateContext`, and `eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)`. Binding
`EGL_OPENGL_ES_API` with `EGL_OPENGL_ES3_BIT` gives:

```
GL_VENDOR:                   Mesa
GL_RENDERER:                 llvmpipe (LLVM 20.1.2, 256 bits)
GL_VERSION:                  OpenGL ES 3.2 Mesa 25.2.8-0ubuntu0.24.04.2
GL_SHADING_LANGUAGE_VERSION: OpenGL ES GLSL ES 3.20
```

Binding `EGL_OPENGL_API` with `EGL_OPENGL_BIT` and a `3.3` core-profile attribute list, on the same
host in the same process shape, gives:

```
GL_VENDOR:                   Mesa
GL_RENDERER:                 llvmpipe (LLVM 20.1.2, 256 bits)
GL_VERSION:                  4.5 (Core Profile) Mesa 25.2.8-0ubuntu0.24.04.2
GL_SHADING_LANGUAGE_VERSION: 4.50
```

Both report `GL_MAX_TEXTURE_SIZE` 16384, `GL_MAX_COLOR_ATTACHMENTS` 8, `GL_MAX_SAMPLES` 4 and
`GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS` 192 — comfortably more than the offscreen colour-and-depth
surface of ADR 0005 requires. `eglGetCurrentContext()` returns the created context by pointer identity,
which is the mechanism ADR 0015's exact-context check needs on Linux, and it is the fixture's business
rather than RenG's. Note for whoever writes the conformance harness that a surfaceless context starts
with viewport and scissor box `0,0,0,0`, so the harness must set a viewport before anything it draws
can produce pixels.

Mesa asks for both requested versions upward: an ES 3.0 request yields ES 3.2, and a 3.3 core request
yields 4.5 core. Any dialect or capability logic must therefore read what it got, never what it asked
for.

## The shader dialect, and what it means for ADR 0008

ADR 0008's substitution rule was driven by macOS evidence: on a headless Apple Silicon core-profile
context reporting `4.1 Metal - 90.5`, `#version 300 es` fails outright while the same body under
`#version 330 core` compiles and links. Linux now supplies the other half of the picture, and it does
not simply agree.

The identical GLSL ES 3.00 body — `precision mediump float;`, `in`/`out`, `layout(location = …)`,
`texture()`, `textureSize()`, an integer uniform, and a `mat4` uniform — was compiled and linked under
both directives on both llvmpipe contexts, once through C and again through the Kotlin/Native seam:

| Context | `#version 300 es` | `#version 330 core` |
|---|---|---|
| llvmpipe, OpenGL ES 3.2 | compiles and links | **fails**: `GLSL 3.30 is not supported. Supported versions are: 1.00 ES, 3.00 ES, 3.10 ES, and 3.20 ES` |
| llvmpipe, 4.5 core profile | compiles and links | compiles and links |
| Apple `4.1 Metal - 90.5` core (prior finding, not re-run here) | fails: `version '300' is not supported` | compiles and links |

Two things follow, and the first is a correction.

**The substitution trigger must be the context's queried dialect, not the target platform.** On
`linuxX64` and `linuxArm64` the consumer creates the context (ADR 0001) and may perfectly reasonably
create either an ES 3.x context or a desktop core context — an EGL/Wayland application does the former,
a GLX application the latter, on the same binary and the same target. A platform-keyed rule is therefore
not merely imprecise on Linux; substituting `#version 330 core` into an ES context is *fatal*, and it is
the case a Linux consumer is more likely to present. ADR 0008's prose already says RenG "detects the
Render Context's shading language at setup", which is the correct rule; the risk is that an
implementation reads "on a desktop OpenGL context" as "on a desktop platform" and keys off the target.
Cycle D's spec should state the trigger as a runtime query and say explicitly that no target implies a
dialect.

**Substitution on a desktop context is a compatibility choice, not a necessity.** `#version 300 es`
compiles fine on Mesa's 4.5 core profile, because that context advertises `GL_ARB_ES3_compatibility`
(along with the ES2, ES3.1 and ES3.2 compatibility extensions). Apple's 4.1 does not advertise it,
which is why the same source fails there. So the honest statement of the rule is that substitution is
required on desktop contexts *lacking* ES3 compatibility, and harmless on those that have it —
and since ADR 0008 already chose to substitute on every desktop context rather than probe for the
extension, that choice is now confirmed to be safe on Mesa as well as necessary on Apple. It should be
recorded as a deliberate simplification with evidence behind it, not left looking like an untested
assumption. Probing `GL_ARB_ES3_compatibility` and skipping the substitution would be strictly more
complex for no behavioural gain.

Detection itself has a trap worth writing into the spec. `glGetString(GL_EXTENSIONS)` returns `NULL`
with `GL_INVALID_ENUM` on a desktop core profile, and works on ES. Any extension-based capability check
must go through `glGetIntegerv(GL_NUM_EXTENSIONS)` plus `glGetStringi`, which is `GLES30`-only on
Android — not a problem, since RenG requires ES 3, but it is a reason the seam must expose `glGetStringi`
and not only `glGetString`. The primary dialect signal, `GL_SHADING_LANGUAGE_VERSION`, is unambiguous on
all three contexts measured: it begins with `OpenGL ES GLSL ES` exactly when the context is ES.

The one thing this cannot settle is `#version 300 es` on Apple's GLES contexts (`iosArm64`,
`iosSimulatorArm64`), where no substitution should occur. That needs real hardware and stays at
compile-and-host-test under ADR 0011.

## The state RenG must restore

ADR 0006 names the set: bound framebuffer, active program, bound vertex array, active texture unit and
the bindings on the units RenG uses, the blend, depth and cull enables and their parameters, the
viewport, and the scissor box. A save/perturb/restore round trip over that set, plus the additions
below, was run on both llvmpipe contexts. It came back **byte-exact** with `glGetError` clean, which is
good evidence the conformance test ADR 0006 promises is straightforwardly writable.

The query and set pairs, all confirmed accepted on both contexts unless noted:

| State | Query | Restore |
|---|---|---|
| Draw framebuffer | `glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING)` | `glBindFramebuffer(GL_DRAW_FRAMEBUFFER, v)` |
| Read framebuffer | `glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING)` | `glBindFramebuffer(GL_READ_FRAMEBUFFER, v)` |
| Renderbuffer | `glGetIntegerv(GL_RENDERBUFFER_BINDING)` | `glBindRenderbuffer` |
| Program | `glGetIntegerv(GL_CURRENT_PROGRAM)` | `glUseProgram` |
| Vertex array | `glGetIntegerv(GL_VERTEX_ARRAY_BINDING)` | `glBindVertexArray` |
| Array buffer | `glGetIntegerv(GL_ARRAY_BUFFER_BINDING)` | `glBindBuffer(GL_ARRAY_BUFFER, v)` |
| Element array buffer | `glGetIntegerv(GL_ELEMENT_ARRAY_BUFFER_BINDING)` | none needed — it is per-VAO state, restored implicitly by the VAO binding |
| Pixel unpack buffer | `glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING)` | `glBindBuffer(GL_PIXEL_UNPACK_BUFFER, v)` |
| Uniform buffer | `glGetIntegerv(GL_UNIFORM_BUFFER_BINDING)` | `glBindBuffer(GL_UNIFORM_BUFFER, v)` |
| Active texture unit | `glGetIntegerv(GL_ACTIVE_TEXTURE)` | `glActiveTexture` — **restore last** |
| Texture per used unit | `glActiveTexture(u)` then `glGetIntegerv(GL_TEXTURE_BINDING_2D)` | `glActiveTexture(u)`, `glBindTexture(GL_TEXTURE_2D, v)` |
| Sampler per used unit | `glGetIntegerv(GL_SAMPLER_BINDING)` | `glBindSampler(u, v)` |
| Blend enable | `glIsEnabled(GL_BLEND)` | `glEnable`/`glDisable(GL_BLEND)` |
| Blend factors | `GL_BLEND_SRC_RGB`, `GL_BLEND_DST_RGB`, `GL_BLEND_SRC_ALPHA`, `GL_BLEND_DST_ALPHA` | `glBlendFuncSeparate` |
| Blend equations | `GL_BLEND_EQUATION_RGB`, `GL_BLEND_EQUATION_ALPHA` | `glBlendEquationSeparate` |
| Blend colour | `glGetFloatv(GL_BLEND_COLOR)`, 4 floats | `glBlendColor` |
| Depth enable | `glIsEnabled(GL_DEPTH_TEST)` | `glEnable`/`glDisable(GL_DEPTH_TEST)` |
| Depth function | `glGetIntegerv(GL_DEPTH_FUNC)` | `glDepthFunc` |
| Depth write mask | `glGetBooleanv(GL_DEPTH_WRITEMASK)` | `glDepthMask` |
| Depth range | `glGetFloatv(GL_DEPTH_RANGE)`, 2 floats | `glDepthRangef` — the `double` `glDepthRange` is desktop-only |
| Cull enable | `glIsEnabled(GL_CULL_FACE)` | `glEnable`/`glDisable(GL_CULL_FACE)` |
| Cull mode / winding | `GL_CULL_FACE_MODE`, `GL_FRONT_FACE` | `glCullFace`, `glFrontFace` |
| Viewport | `glGetIntegerv(GL_VIEWPORT)`, 4 ints | `glViewport` |
| Scissor | `glIsEnabled(GL_SCISSOR_TEST)`, `glGetIntegerv(GL_SCISSOR_BOX)` | `glEnable`/`glDisable`, `glScissor` |

The round trip also confirmed the ordering constraint that makes the texture rows work: reading the
binding on a unit requires making that unit active, so `GL_ACTIVE_TEXTURE` must be captured first and
reinstated last, with the per-unit reads and writes nested inside. This is easy to get wrong and it is
worth an explicit statement in the spec rather than a comment in the code.

Six pieces of state RenG will touch are **not** in ADR 0006's list and belong there:

- `GL_COLOR_WRITEMASK` / `glColorMask`. The composite pass of ADR 0005 has every reason to touch it, and
  a consumer that had masked a channel would silently get it back.
- `GL_UNPACK_ALIGNMENT` and `GL_UNPACK_ROW_LENGTH` (plus `GL_UNPACK_SKIP_ROWS` / `GL_UNPACK_SKIP_PIXELS`)
  via `glPixelStorei`. Texture upload of decoded PNG and Rentile tiles will set these, and the default
  measured on llvmpipe is `4`, not `1`, so an implementation that assumes `1` corrupts non-aligned rows
  as well as leaving the state dirty.
- `GL_PACK_ALIGNMENT`, if RenG ever reads pixels back.
- `GL_COLOR_CLEAR_VALUE` and `GL_DEPTH_CLEAR_VALUE`. RenG clears its offscreen surface, and
  `glClearColor` is global state, not a parameter of `glClear`.
- `GL_ARRAY_BUFFER_BINDING`. Unlike the element array binding, this is *not* captured by the VAO, so
  restoring the VAO does not restore it.
- `GL_PIXEL_UNPACK_BUFFER_BINDING`, if the upload path ever uses a PBO.

Two ES/desktop divergences constrain how the restore set is written. `GL_DRAW_BUFFER` and
`GL_LINE_SMOOTH` are queryable on the desktop core profile and raise `GL_INVALID_ENUM` on ES, so a
single unconditional query list would leave a spurious error flag on ES; the save/restore code must be
dialect-aware or must confine itself to tokens valid in both. And `GL_FRAMEBUFFER_SRGB` is queryable on
both but arrives **enabled** on Mesa's ES context and **disabled** on its desktop core context. That is a
pixel-affecting difference between two contexts on the same machine, so Cycle E's golden baselines have
to be keyed by context dialect and not only by platform — and RenG should probably set it explicitly
rather than inherit it.

Finally, one thing genuinely cannot be restored. **`glGetError` is destructive.** A provoked error reads
`0x500` on the first call and `0x0` on the second; there is no way to push a flag back. So if RenG drains
the error queue on entry — which it must, or it will misattribute a consumer's pre-existing error to its
own draw — it has consumed state belonging to the caller, and if it does not drain, its own error
reporting is unreliable. ADR 0006's guarantee that RenG "modifies nothing outside that set" therefore has
one unavoidable exception that should be stated rather than discovered. The reasonable resolution is to
drain on entry, treat any flag found as the consumer's and not RenG's, and document that RenG consumes
the error queue; the alternative of never calling `glGetError` gives up all internal error detection.

## Traps carried forward

Two of the four inherited traps were re-verified here directly, one could not be, and one was verified
only in its mechanism.

**A cinterop package name comes from the `.def` filename.** Confirmed, and unambiguously: a `.def` with
no `package=` line, named `renggl.def`, produced package `renggl`; the identical headers behind
`othername.def` produced `othername`. Nothing in the Gradle `cinterops.create(...)` name reaches the
package.

**cinterop silently drops unavailable declarations.** The mechanism is confirmed on this host. Five test
declarations were compiled: a plain one, a `deprecated` one, an `unavailable` one, and two carrying
`availability(macos, …)` attributes. Four reached the klib. The `__attribute__((unavailable))` one
vanished with no error and no warning of any kind. That is exactly the failure mode ADR 0009 describes —
a definition naming `OpenGL/gl3.h` yielding nineteen GLU functions and no GL ones. The Apple-specific
*trigger* was not reproduced here, because the `availability(macos, …)` attributes are inert when
compiling for a Linux target, which is precisely why both of those survived. So: the silent-drop
behaviour is measured; the claim that Apple's `API_DEPRECATED` headers trigger it remains an inherited
finding from the earlier macOS spike. Either way, ADR 0009's conclusion holds and no hand-rolled cinterop
should go anywhere near Cycle D.

**Platform GL klibs are invisible from shared source sets.** Not re-verified, for the reason given above.
Flagged as the first thing Cycle D should confirm, because the source-set layout depends on it.

**cinterops on the `main` compilation are not visible to the test compilation.** Not re-verified — it is a
Gradle-level behaviour and no cinterop is planned for Cycle D anyway, so it matters only if the
conformance fixture needs one. Since the Linux fixture can reach EGL through `dlopen` from
`platform.posix`, as the spike demonstrates, it should not need one at all.

## The Apple context in continuous integration

ADR 0011 requires the conformance suite to run against real contexts on `macosArm64`, and continuous
integration is where that has to happen, since no Apple hardware is otherwise available to this project.
Whether a hosted runner can produce a context at all was untested. It can, with one caveat that matters
more than the answer.

A probe compiled on a `macos-latest` runner (Xcode 26.6) walked a ladder of pixel format requests from the
strongest to the weakest. The first candidate demanded an accelerated renderer and failed at
`CGLChoosePixelFormat` with `kCGLBadPixelFormat`; dropping that single requirement succeeded immediately:

| Candidate | Result |
|---|---|
| accelerated, 4.1 core | `CGLChoosePixelFormat` fails, `cgl=10002 (invalid pixel format)` |
| 4.1 core, acceleration not required | pixel format chosen, context created, `npix=1` |

The context reports `GL_VERSION=4.1 APPLE-23.1.1`, `GL_RENDERER=Apple Software Renderer`,
`GL_VENDOR=Apple Inc.`, `GL_SHADING_LANGUAGE_VERSION=4.10`. So the conformance suite can run in continuous
integration, provided it never asks for acceleration. A suite that requests an accelerated pixel format
will not fail a draw comparison; it will fail to obtain a context at all, and the failure names an invalid
pixel format rather than the absence of a GPU. That is worth writing into the suite's setup code as a
comment, because the error is not self-explanatory.

ADR 0008's rule reproduces exactly on that runner, which extends the evidence from one developer's machine
to the platform the gate will actually run on: the GLSL ES 3.00 body fails under `#version 300 es` with
`version '300' is not supported`, the identical body compiles under `#version 330 core`. Combined with the
llvmpipe measurements above, the substitution rule is now confirmed on both an Apple software renderer and
a Mesa desktop context, and contradicted on a Mesa ES context — which is the case that forces the trigger
to be a runtime dialect query.

The state RenG must restore is queryable there, and two initial values are worth noting. Pixel store pack
and unpack alignment both read `4`, confirming the correction above. The viewport reads `0,0,0,0`, because
a context created without a drawable has no default framebuffer dimensions — so a renderer must set the
viewport from its own surface rather than trusting the initial value, and a conformance test that saves and
restores the viewport will be comparing zeroes unless it sets one first.

**The renderer string is the finding with the longest reach.** A hosted runner gives
`Apple Software Renderer`, not the `4.1 Metal - 90.5` that real Apple Silicon reports. Cycle E's golden
baselines and Cycle J's corpus gate are already specified as per-platform with a tolerance rather than
cross-platform equality, but per-platform is not a fine enough key: the same target produces different
pixels under a software renderer in continuous integration than under Metal on a developer's machine.
Baselines should be keyed by the reported renderer, not by the target, or the corpus gate will fail the
first time it is run somewhere other than where its baselines were recorded.

One thing the runner could not supply: it carries no Kotlin/Native toolchain (`~/.konan` is absent until a
Gradle invocation downloads one), so the klib inventory cross-check on a real macOS host did not run. The
inventory in this document was measured from a Linux host, where the Apple platform klibs are present and
identical in content.

## Confirmed, corrected, and still unverified

**Confirmed by measurement.** `platform.OpenGL3` at 509 functions and `platform.OpenGLCommon` at 52 CGL
functions on `macosArm64`. The iOS package being `platform.gles3` at 296 functions despite the module
being named `OpenGLES3`. Linux having no GL platform klib — exactly `builtin`, `iconv`, `linux`, `posix`,
`zlib` — and Kotlin/Native's Linux sysroots shipping `dlfcn.h` and zero GL, GLES or EGL headers, on both
Linux targets. `GLES20` plus `GLES30` covering every entry point a renderer needs. The `.def`-filename
package rule. cinterop's silent drop of unavailable declarations.

**Corrected.** ADR 0008's substitution cannot be keyed on the target platform: on the *same* Linux
target, `#version 300 es` succeeds and `#version 330 core` fails on an ES context, while both succeed on
a desktop core context. The trigger has to be a runtime dialect query, and substitution into an ES
context is actively fatal. Relatedly, substitution on desktop is not universally *required* — Mesa's 4.5
core profile accepts `#version 300 es` via `GL_ARB_ES3_compatibility`, which Apple's 4.1 lacks — so
ADR 0008's blanket desktop substitution is a deliberate and now-evidenced simplification rather than a
necessity. ADR 0006's restore set is incomplete: colour write mask, pixel store alignment and row
length, clear values, the array buffer binding, and the pack alignment all belong in it, and `glGetError`
is a genuine unrestorable exception to the no-modification guarantee. It is also worth recording that
the `platform.gles3` klibs under `$PK/android_*` belong to `androidNative*` targets and are unreachable
from RenG's AGP `android` target, which is a plausible wrong turn for anyone reading the klib tree.

**Still unverified, and not to be claimed.** Everything requiring Apple or Android hardware: that
`platform.OpenGL3` and `platform.OpenGLCommon` link and run on a real `macosArm64` CGL context; the
`4.1 Metal - 90.5` dialect results, which are inherited from the earlier spike and were not re-run; that
`#version 300 es` compiles unsubstituted on `iosArm64` and `iosSimulatorArm64`; that Android's `GLES30`
seam behaves identically on a device; that `linuxArm64` executes rather than merely compiles, which was
verified only as a cross-compile. Also unverified: the shared-source-set klib visibility claim, and
whether Apple's availability attributes are what trigger cinterop's silent drop.

## What Cycle D's spec must decide

- The exact seam interface: its method list, and its types. Measurement says `Int` handles and enums,
  `Boolean`, Kotlin arrays for out-params, `ByteArray?` for pixel and vertex data, and a single `String`
  for shader source, because Android is the narrower side in every case.
- How the three Kotlin/Native implementations avoid triplicating byte-identical bodies, given that
  `platform.OpenGL3` and `platform.gles3` differ only in package name and Kotlin has no conditional
  import. Three thin leaf objects, generation, or per-leaf typealiases.
- Whether platform GL klibs really are invisible from shared source sets on Kotlin 2.3.21. Confirm this
  first; the whole source-set layout hangs on it.
- Which library the Linux implementation opens and in what order it resolves — `libEGL.so.1` plus
  `eglGetProcAddress` is what was proven here — and what happens when a name does not resolve. A missing
  entry point must be a typed setup failure with a redacted diagnostic, not a null-pointer call.
- Whether the Linux entry-point table is resolved eagerly at setup or lazily per call, and whether a
  partially resolvable driver is a hard failure. Eager resolution makes the failure a setup-time typed
  error, which fits the rest of the design.
- How context dialect is detected and represented. `GL_SHADING_LANGUAGE_VERSION` beginning
  `OpenGL ES GLSL ES` was an unambiguous ES signal on all three contexts measured. Whatever is chosen,
  it must be a runtime query and no target may imply a dialect.
- The precise restatement of ADR 0008's substitution trigger, plus whether to substitute on all desktop
  contexts or probe `GL_ARB_ES3_compatibility`. Evidence favours the blanket rule.
- Whether the Cycle B `ShaderProfilePlan` gap listed as open decision 2 in `HANDOFF.md` is closed here or
  deferred. It emits `#version 330 core#version 300 es` for a directly constructed inconsistent profile,
  which will not compile on any context measured, and Cycle D is where that becomes observable.
- The final documented restore set, including the six additions above, and the rule that
  `GL_ACTIVE_TEXTURE` is saved first and restored last with per-unit reads nested inside.
- What to do about `glGetError` being destructive: whether RenG drains on entry and documents the
  exception to ADR 0006, or forgoes internal error detection.
- Whether to write the restore set dialect-aware, given that `GL_DRAW_BUFFER` and `GL_LINE_SMOOTH` are
  desktop-only queries that leave `GL_INVALID_ENUM` on ES, or to restrict it to tokens valid on both.
- Whether RenG sets `GL_FRAMEBUFFER_SRGB` explicitly. It defaults enabled on Mesa ES and disabled on
  Mesa desktop core, so inheriting it makes output depend on the consumer's context choice, and
  Cycle E's baselines would then have to be keyed by dialect.
- How the conformance suite obtains its contexts. The Linux fixture is the surfaceless EGL path recorded
  here, driven from `platform.posix` with no cinterop; it must set a viewport, since a surfaceless
  context starts at `0,0,0,0`. Which of the two llvmpipe dialects the suite exercises — ideally both,
  since that is the cheapest place RenG will ever get two dialects on one machine — is a spec decision.
- How `ci.yml` acquires `libegl1`, `libegl-mesa0` and `libgles2` on `ubuntu-latest`, and whether an
  `apt-get update` precedes it. It was required here.
- Which offscreen surface formats ADR 0005's colour-and-depth surface uses, given the limits measured
  (16384 max texture size, 8 colour attachments, 4 samples on llvmpipe) and that `GL_DEPTH_COMPONENT24`
  is `GLES30`-only on Android.
