# Cycle D GL Foundation Design

## Outcome and scope

Cycle D builds the internal GL seam and its implementations, discovers the render context and its shading
dialect at setup, creates RenG's offscreen colour-and-depth surface and its composite pass, implements the
documented save-and-restore set, and compiles shaders with version-directive substitution and program
caching. It supplies real context, target, and handle observations to Cycle B's
`RendererLifecycleStateMachine` and executes the GL actions that machine emits.

The conformance suite lands here, and it is the reason ADR 0006 and ADR 0008 become claims rather than
hopes: state identical before and after a draw across the corrected restore set, and one GLSL ES 3.00 source
compiling unsubstituted on an ES context and substituted on a desktop one, both against real drivers.

Cycle D draws no frame content. It acquires no resource, calls no adapter, and touches no Rentile type — it
is independent of Cycle C by construction, which is why the two run in parallel. It exposes no public
renderer construction: there is still no factory, so nothing here is reachable through the public API, and
`checkKotlinAbi` must report **no public ABI change at all** for this cycle.

Everything remains inside the single published `:kmp` module. All six targets stay `android`, `iosArm64`,
`iosSimulatorArm64`, `macosArm64`, `linuxX64`, and `linuxArm64`.

## The GL seam

### Typed at Android's width

One internal interface is implemented by every platform. Its signatures are typed at Android's width,
because Android is the narrower side wherever the platforms disagree and narrowing is one-directional.

| Concept | GL C | Kotlin/Native klib | Android `GLES30` | Seam |
|---|---|---|---|---|
| Object name | `GLuint` | `UInt` | `int` | `Int` |
| Enum | `GLenum` | `UInt` | `int` | `Int` |
| Boolean in | `GLboolean` | `UByte` | `boolean` | `Boolean` |
| Boolean out | `GLboolean*` | `CValuesRef<UByteVar>` | `boolean[]` + offset | `BooleanArray` |
| Integer out | `GLint*` | `CValuesRef<IntVar>` | `int[]` + offset | `IntArray` |
| Float out | `GLfloat*` | `CValuesRef<FloatVar>` | `float[]` + offset | `FloatArray` |
| Buffer size | `GLsizeiptr` | `Long` | `int` | `Int` |
| Pixel/vertex data | `const void*` | `CValuesRef<*>?` | `java.nio.Buffer` | `ByteArray?` |
| Shader source | `char**` + count | `CValuesRef<CPointerVar<ByteVar>>` | a single `String` | `String` |

`glShaderSource` forces the decision rather than merely illustrating it: Android exposes exactly
`glShaderSource(int, String)` — no count, no length array — so the seam cannot offer the `char**` form even
though both Kotlin/Native klibs do. Once one call is forced to Android's shape, consistency argues for all of
them. Typing the seam in `UInt` would place a conversion on the one implementation with no zero-cost way to
perform it and would leak a Kotlin-only inline class the JVM side cannot honour; `Int` to `UInt` on the
native side is a compile-time no-op.

All marshalling asymmetry stays inside the implementations — `memScoped` arenas, `usePinned` with
`addressOf`, `.cstr`, `toKString`, copy-back of output arrays on the native side; an offset or a wrapped
buffer on Android. Every representative call in this mapping was executed against a real llvmpipe context
with `glGetError` returning zero afterwards.

### Source-set layout

Per ADR 0022, which supersedes ADR 0009's claim that platform GL klibs are invisible from shared source
sets. That claim is false on the Kotlin version this repository builds with: a file in `iosMain` importing
`platform.gles3` compiles for `iosArm64`.

The layout is therefore four hand-written implementations rather than five:

| Source set | Binding | Serves |
|---|---|---|
| `iosMain` | `platform.gles3` | `iosArm64`, `iosSimulatorArm64` |
| `macosMain` | `platform.OpenGL3` / `platform.OpenGLCommon` | `macosArm64` |
| `linuxMain` | `platform.posix` `dlopen`/`dlsym` + `eglGetProcAddress` | `linuxX64`, `linuxArm64` |
| `androidMain` | `android.opengl.GLES30` | `android` |

Each implementation sits in the shared source set above its targets, never in a leaf. The seam interface
itself is `commonMain`.

macOS and iOS can never share regardless of visibility, because `platform.OpenGL3` and `platform.gles3` are
different packages and Kotlin has no conditional import — so the two remaining near-duplicates are
irreducible. At four implementations, a build-time generator for the entry-point table costs more than the
duplication it removes, and a per-leaf `typealias` indirection would save one file while betting that all
eighty-four signatures are byte-identical when twelve were checked.

That layout is measured, not assumed. A twelve-method translation object in `iosMain` compiled for both iOS
targets and appears in both produced klibs from the one shared file, exercising `memScoped`, `allocArray`
over four element types, `usePinned` with `addressOf`, `.cstr`, `toKString` on both an allocated buffer and
a reinterpreted `glGetString` return, `reinterpret` in both directions, and the `Int`/`UInt` and
`UByte`/`Boolean` conversions. `compileIosMainKotlinMetadata` passed as well, which is the compilation most
likely to reject platform GL because it resolves against commonized cinterop libraries. `appleMain` was
measured in both directions and can host neither package.

Two implementation facts follow from that measurement. Every seam file needs
`@OptIn(ExperimentalForeignApi::class)`, which is harmless while the seam is `internal` and must never reach
public API. And `addressOf(0)` throws at runtime on an empty array, so each pinned-array call needs a
zero-length guard — a runtime concern the compile check cannot catch.

A third is a process rule rather than a code one: platform-library resolution for a shared source set is
enforced **per leaf compilation**, not by pre-computing an intersection, so a misplaced GL file fails at one
specific target's compile task. A partial compile can look green, and every target must be compiled before
the layout is trusted.

Linux resolves entry points through `dlopen("libEGL.so.1", RTLD_NOW)` and `eglGetProcAddress`, with `dlsym`
as a fallback. Loading `libEGL.so.1` rather than `libGLESv2.so.2` is deliberate: on a glvnd-dispatched
system `eglGetProcAddress` is the only resolver guaranteed to return the entry points belonging to the
current context's vendor, and `dlsym` against the dispatch library happens to work but is not the contract.
Function-pointer arity is not a constraint — Kotlin/Native supplies `invoke` overloads to twenty-one
parameters and GL's worst case is `glBlitFramebuffer` at ten. RenG resolves entry points and never creates
a context, per ADR 0001.

No hand-written cinterop definition is used on any target, per ADR 0009. That prohibition is now measured
rather than inherited: a declaration carrying `__attribute__((unavailable))` vanishes from the produced klib
with no error and no warning of any kind, which is exactly how a definition naming `OpenGL/gl3.h` yields
nineteen GLU functions and no GL ones.

### The entry-point inventory

An eighty-four-name checklist of the entry points a renderer needs has no gap on any of the four
implementations; that inventory is measured, not remembered. The seam must expose `glGetStringi` alongside
`glGetString`, because `glGetString(GL_EXTENSIONS)` returns `NULL` with `GL_INVALID_ENUM` on a desktop core
profile, so any extension query must go through `glGetIntegerv(GL_NUM_EXTENSIONS)` plus `glGetStringi`.

## Context and dialect detection

Setup adopts the caller's already-current context, per ADRs 0001 and 0012, and queries it. RenG creates no
context, chooses no pixel format, and owns no window.

The dialect is determined by `GL_SHADING_LANGUAGE_VERSION`, which begins with `OpenGL ES GLSL ES` exactly
when the context is ES. This was unambiguous on all three real contexts measured.

**The dialect is a runtime property of the context, never a property of the target.** This is the
specification's single most important statement about shaders, and it is a correction rather than a
restatement. On `linuxX64` and `linuxArm64` the consumer creates the context and may reasonably create
either an ES 3.x context or a desktop core context — an EGL/Wayland application does the former, a GLX
application the latter, from the same binary on the same target. Keying substitution off the platform would
inject `#version 330 core` into an ES context, which is fatal, and that is the more likely Linux case. No
target implies a dialect, and no implementation may infer one.

An ES 3.0 context is required. A context that cannot satisfy it fails setup with
`UNSUPPORTED_RENDER_CONTEXT` at `CONTEXT_ADOPTION`, without modifying state.

## Shader compilation and version substitution

Per ADR 0008, RenG accepts GLSL ES 3.00 sources that are self-contained but for their version directive,
substitutes `#version 330 core` for `#version 300 es` on desktop contexts, and changes nothing else. Cycle B
already implements the scan and the substitution purely in `ShaderProfilePlanner`, whose plan now validates
that its declared span actually describes the directive line; Cycle D supplies the dialect and performs the
compile.

Substituting on **every** desktop context is a deliberate simplification, and it is now confirmed safe
rather than assumed. The honest rule is that substitution is required on desktop contexts lacking
`GL_ARB_ES3_compatibility` and harmless on those that have it: Mesa's 4.5 core profile advertises the
extension and compiles `#version 300 es` unchanged, while Apple's 4.1 does not advertise it and rejects the
same source. Probing the extension and conditionally skipping substitution would be strictly more complex
for no behavioural gain.

Measured behaviour that the conformance suite reproduces:

| Context | `#version 300 es` | `#version 330 core` |
|---|---|---|
| llvmpipe, OpenGL ES 3.2 | compiles and links | fails — GLSL 3.30 unsupported |
| llvmpipe, 4.5 core profile | compiles and links | compiles and links |
| Apple `4.1 Metal - 90.5` core | fails | compiles and links |

Apple's ES contexts on `iosArm64` and `iosSimulatorArm64`, where no substitution occurs, cannot be reached
by continuous integration and stay at compile-and-host-test under ADR 0011 until Cycle H.

Compiled programs are cached by the `GEOMETRY_PROGRAM` resource key Cycle B already derives, whose canonical
root contains the shader profile version and the exact sources. Compilation and link failures are
`SHADER_COMPILE_FAILED` and `SHADER_LINK_FAILED` at `SHADER_COMPILATION`; the driver's info log is a
diagnostic aid and never crosses the public boundary, because a `Diagnostic` admits only allowlisted fields.

## The offscreen surface and composite pass

RenG renders into its own offscreen colour-and-depth surface at the configured output pixel size, per ADRs
0005 and 0012, then composites that surface into the caller's `RenderTarget` framebuffer. The surface is a
renderer-held resource of kind `OFFSCREEN_SURFACE`; the composite pass's program and geometry are
`INTERNAL_PIPELINE`.

`GL_FRAMEBUFFER_SRGB` arrives **enabled** on Mesa's ES context and **disabled** on its desktop core context
— a pixel-affecting difference between two contexts on the same machine. RenG sets it explicitly rather than
inheriting it, and restores the caller's value afterwards.

## The save-and-restore set

ADR 0006 promises RenG restores every piece of GL state it touches. The documented set was incomplete; the
corrected set below is what a full save, perturb, and restore round trip proved byte-exact on both a real ES
and a real desktop context.

Bindings: draw framebuffer, read framebuffer, renderbuffer, current program, vertex array, array buffer,
pixel unpack buffer, uniform buffer, active texture unit, and the texture and sampler binding on every unit
RenG uses. The element array buffer binding needs no restore because it is per-VAO state, restored implicitly
by the VAO binding — but the **array** buffer binding is not captured by the VAO and must be saved
explicitly.

Pipeline state: blend enable, separate blend factors, separate blend equations, blend colour; depth test
enable, depth function, depth write mask, depth range via `glDepthRangef` because the `double` form is
desktop-only; cull enable, cull mode, front-face winding; viewport; scissor enable and box; colour write
mask; and the colour and depth clear values, since `glClearColor` is global state rather than a parameter of
`glClear`.

Pixel store: `GL_UNPACK_ALIGNMENT` and `GL_UNPACK_ROW_LENGTH`, plus `GL_UNPACK_SKIP_ROWS` and
`GL_UNPACK_SKIP_PIXELS`, and `GL_PACK_ALIGNMENT` if pixels are ever read back. **The measured default for
unpack alignment is 4, not 1**, so an implementation that assumes 1 both corrupts non-aligned rows and
leaves the state dirty.

Two ordering and portability constraints are normative rather than advisory. Reading a texture binding
requires making its unit active, so `GL_ACTIVE_TEXTURE` is captured **first** and reinstated **last**, with
every per-unit read and write nested inside. And `GL_DRAW_BUFFER` and `GL_LINE_SMOOTH` are queryable on a
desktop core profile but raise `GL_INVALID_ENUM` on ES, so the save/restore code is dialect-aware or confines
itself to tokens valid in both — an unconditional query list would leave a spurious error flag on ES.

### The error queue, which cannot be restored

`glGetError` is destructive: a provoked error reads once and is gone, and there is no way to push a flag
back. If RenG drains the queue on entry it consumes state belonging to the caller; if it does not, its own
error reporting is unreliable because it may attribute a consumer's pre-existing error to its own work.

RenG drains on entry, treats any flag found as the consumer's rather than its own, and **documents that it
consumes the error queue**. This is a real, stated exception to ADR 0006's guarantee that RenG modifies
nothing outside the restore set — one to declare rather than let a consumer discover.

## Driving Cycle B's lifecycle decisions

`RendererLifecycleStateMachine` already owns the three owner states and the total operation and error
precedence from supplied facts. Cycle D supplies those facts — whether a context is current, whether it is
the renderer's exact context, whether live GL handles exist, framebuffer validity, target provenance and
generation — and executes the GL actions the machine emits. It re-decides nothing.

The behaviours that must hold, all already specified: `close()` and `freeResources()` are idempotent
deletion operations that, while live GL handles exist, require the renderer's exact context to be current
and otherwise fail without changing state, per ADR 0015. Losing the context is not freeing — `notifyGpuObjectsGone()`
makes RenG forget its GL handles without deleting them, keeps every CPU-side resource intact, and discards
queued deferred deletions, because a replacement context cannot delete handles from the lost one, per ADRs
0007 and 0015. Deferred deletion drains after exact-context validation and before operation-specific work in
render-target minting, drawing, resource freeing, and live-handle close, and validation failure changes
nothing.

## The conformance suite

The suite runs against real contexts on a hosted macOS runner and on Ubuntu llvmpipe, and **never requests
acceleration**. This is stated in the specification because it is a trap that reads as a broken suite: a
macOS runner yields a context only when the accelerated pixel-format requirement is dropped, reporting
`Apple Software Renderer`; request acceleration and no context is obtained at all, with an error naming an
invalid pixel format rather than the absence of a GPU.

Linux creates a surfaceless context through `EGL_PLATFORM_SURFACELESS_MESA` with no display server, where
both an ES 3.2 and a 4.5 core profile context are reachable. Context creation lives in the test fixture, not
in RenG, per ADR 0001.

The suite proves: the full save, perturb, and restore round trip is byte-exact on both dialects; one GLSL ES
3.00 source compiles unsubstituted on an ES context and substituted on a desktop one; the eighty-four-name
inventory resolves on every implementation; and the error queue behaves as this specification says.

The negative half of that shader expectation must be keyed on what the driver advertises, not asserted
symmetrically, and the measured table above is why. Substituting on an ES context fails everywhere, so the
suite asserts that unconditionally. But leaving an ES directive **unsubstituted** on a desktop context fails
only where `GL_ARB_ES3_compatibility` is absent: Mesa's 4.5 core profile advertises it and compiles the
source unchanged, while Apple's 4.1 does not and rejects it. A suite asserting failure on every desktop
context would therefore fail on Mesa against correct behaviour. This affects the test's expectation only —
RenG still substitutes on every desktop context, per ADR 0008, because probing the extension buys no
behavioural gain.

Android and iOS device contexts are Cycle H's, as the decomposition already scopes.

One consequence lands on Cycle E and is recorded here because its root cause is measured here: a hosted
macOS runner renders through a software renderer while a developer's machine renders through Metal, and
`GL_FRAMEBUFFER_SRGB` differs by context dialect on one machine. Golden baselines must therefore be keyed by
the **reported renderer string and context dialect**, not by the target, or the first run on new hardware
fails on a difference that is not a regression.

## Implementation boundary and gates

Cycle D implements the GL seam and its four implementations, context and dialect detection, shader
compilation with substitution and program caching, the offscreen surface and composite pass, the corrected
save-and-restore set, and the conformance suite.

It does not implement resource acquisition, decoding, parsing, caching, any Rentile call, any consumer
adapter call, frame content, a renderer factory, or a public runtime entry point. It adds no Gradle
subproject and no cinterop definition.

Required gates:

- `checkKotlinAbi` reporting **no public ABI change**;
- Android host, `linuxX64Test`, and `macosArm64Test`, plus compile gates for both iOS targets and Linux
  ARM64;
- the conformance suite passing against a real ES context and a real desktop context, on Ubuntu llvmpipe and
  on a hosted macOS runner with acceleration never requested;
- state round-trip equality proven byte-exact across the complete corrected restore set on both dialects;
- shader substitution proven in all four combinations of dialect and directive;
- repository policy, local publication, and the fresh six-target consumer smoke before merge.

Cycle D produces no frame content and no public renderer construction.
