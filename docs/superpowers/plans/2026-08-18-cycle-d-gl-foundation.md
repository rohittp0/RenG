# Cycle D GL Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build RenG's internal GL seam and its four platform implementations, discover the Render Context and its shading dialect at runtime, create the offscreen colour-and-depth surface and its composite pass, implement the corrected save-and-restore set, compile shaders with version-directive substitution and program caching, drive Cycle B's `RendererLifecycleStateMachine` with real GL observations, and prove all of it against a real ES context and a real desktop context — without adding one byte of public ABI.

**Architecture:** One `internal interface GlBinding` in `commonMain`, typed at Android's width, declares eighty-four entry points. Four hand-written implementations sit in the shared source set above their targets — `iosMain` over `platform.gles3`, `macosMain` over `platform.OpenGL3`/`platform.OpenGLCommon`, `linuxMain` over `platform.posix` `dlopen`/`eglGetProcAddress`, and `androidMain` over `android.opengl.GLES30` — reached from common code through one `internal expect fun openPlatformGlBinding()`. Every consumer of the seam is pure common Kotlin: context adoption and dialect detection, the error-queue drain, the state snapshot, the shader compiler and program cache, the offscreen surface, the composite pass, the GL object registry, and the lifecycle driver that supplies observations to Cycle B's existing reducer and executes the actions it emits. The conformance suite body is one common test function; two platform fixtures create the real contexts, because RenG never does.

**Tech Stack:** Kotlin Multiplatform 2.3.21, Gradle 9.5.0, AGP 9.3.1, `kotlin.test`, Kotlin ABI validation, `kotlinx.cinterop` from the shipped Kotlin/Native platform klibs, dependency-free common Kotlin.

**Spec:** `docs/superpowers/specs/2026-08-18-cycle-d-gl-foundation-design.md`

## Global Constraints

- Keep exactly six targets: `android`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64`, and `linuxArm64`. Add no JVM, `macosX64`, or `iosX64` target.
- Keep `explicitApi()` enabled.
- `checkKotlinAbi` must report **no public ABI change** for this whole cycle. `kmp/api/kmp.klib.api` is byte-identical at the last commit of this plan to what it was at the first. Every declaration Cycle D adds is `internal`.
- Add **no new Gradle subproject**. Everything lands inside the single published `:kmp` module.
- Add **no cinterop definition on any target**. A declaration carrying `__attribute__((unavailable))` vanishes from a produced klib with no error and no warning (ADR 0009, re-measured 2026-08-18), so the only bindings used are the ones Kotlin/Native already ships plus runtime resolution on Linux.
- Every Gradle invocation passes `--no-configuration-cache`; fresh consumer smoke also uses a new Gradle home and `--refresh-dependencies`.
- The seam is `internal` and every file that touches a foreign API needs `@file:OptIn(ExperimentalForeignApi::class)`. That opt-in is harmless while the seam is `internal` and must never reach public API.
- Platform-library resolution for a shared source set is enforced **per leaf compilation**, not by pre-computing an intersection, so a misplaced GL file fails at one specific target's compile task and a partial compile can look green. Every target is compiled before the layout is trusted (ADR 0022).
- Keep `com.rohittp.rentile:kmp:0.1.5` as the only `commonMain` dependency and `kotlin("test")` as the only `commonTest` dependency; do not edit `kmp/build.gradle.kts` at all. The four new source sets come from the Kotlin default hierarchy template, which already provides `iosMain`, `macosMain`, `linuxMain`, `linuxTest`, and `macosTest`; `androidMain` comes from the Android KMP library plugin.
- No target implies a shading dialect. The substitution trigger is a runtime query of the adopted context and nothing else.
- RenG creates no Render Context, chooses no pixel format, owns no window, and references no CGL, EAGL, EGL, `NSOpenGLContext`, or `ANativeWindow` entry point in production source (ADR 0001). Context creation and the current-context probe live in the conformance fixtures.
- Typed failures only: stable error code, pipeline stage, and redacted diagnostics. No driver info log, entry-point name, shader source, or library path ever reaches a `Diagnostic`, a `RenGException`, or a `toString()`.
- Never commit a `mavenLocal()` entry or a `-SNAPSHOT` dependency; `VERSION_NAME` in the root `gradle.properties` stays the sole checked-in version input and stays `0.1.0`.
- Do not edit the approved Cycle D specification, the Cycle D research record, or any historical Cycle A/Cycle B design or plan document.

---

## File Structure and Ownership

### New internal GL package (`commonMain`)

| File | Responsibility |
|---|---|
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlTokens.kt` | every GL enum constant RenG uses, once, because GLES 3.0 and GL 3.3 agree on their values |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlEntryPoint.kt` | the eighty-four-name entry-point roster with its exact C names |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlBinding.kt` | the seam interface, typed at Android's width |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/PlatformGlBinding.kt` | `GlBindingResult` and `internal expect fun openPlatformGlBinding()` |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/RenderContextProfile.kt` | GL version parsing, dialect detection, extension query, context adoption |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlErrorQueue.kt` | the destructive-drain policy and its one declared exception |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlStateSnapshot.kt` | the corrected save-and-restore set |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlShaderCompiler.kt` | dialect-selected source, compile, link, typed failures, info-log observer |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlProgramCache.kt` | programs keyed by Cycle B's `GEOMETRY_PROGRAM`/`INTERNAL_PIPELINE` resource keys |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/OffscreenSurface.kt` | the colour-and-depth surface of ADR 0005 |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/CompositePipeline.kt` | the composite program, its quad, and the little-endian float encoder |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlFrameDrawer.kt` | drain, capture, clear, frame content, composite, restore |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlObjectRegistry.kt` | live handles, queued deletions, GPU-object-loss forgetting, the deleter |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/RenderContextIdentity.kt` | the opaque context identity and its injected probe seam |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlLifecycleDriver.kt` | supplies observations to and executes actions from `RendererLifecycleStateMachine` |

### Platform implementations

| File | Serves |
|---|---|
| `kmp/src/iosMain/kotlin/com/rohittp/reng/internal/gl/IosGlBinding.kt` | `iosArm64`, `iosSimulatorArm64` |
| `kmp/src/macosMain/kotlin/com/rohittp/reng/internal/gl/MacosGlBinding.kt` | `macosArm64` |
| `kmp/src/linuxMain/kotlin/com/rohittp/reng/internal/gl/LinuxGlBinding.kt` | `linuxX64`, `linuxArm64` |
| `kmp/src/androidMain/kotlin/com/rohittp/reng/internal/gl/AndroidGlBinding.kt` | `android` |

### Modified existing file

| File | Change |
|---|---|
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/ResourceKeyDerivation.kt` | freeze the `INTERNAL_PIPELINE` and `OFFSCREEN_SURFACE` descriptor tags the Cycle B specification left to their owning cycle |

### Tests

| File | Responsibility |
|---|---|
| `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/RecordingGlBinding.kt` | the programmable fake seam every pure GL test drives |
| `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlEntryPointRosterTest.kt` | roster size, uniqueness, and C-name shape |
| `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/RenderContextProfileTest.kt` | version parsing, dialect detection, ES 3.0 requirement |
| `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlErrorQueueTest.kt` | drain-on-entry, bounded loop, attribution |
| `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlStateSnapshotTest.kt` | capture/restore ordering, dialect gating, defaults |
| `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlShaderCompilerTest.kt` | substitution selection, typed failures, log containment, cache hits |
| `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/OffscreenSurfaceTest.kt` | creation order, incompleteness rollback, deletion |
| `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/CompositePipelineTest.kt` | quad bytes, program creation, deletion |
| `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlFrameDrawerTest.kt` | draw order, restore-on-failure, sRGB explicitness |
| `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlObjectRegistryTest.kt` | registration, deferral, loss forgetting, deleter dispatch |
| `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlLifecycleDriverTest.kt` | every action the machine emits, exact-context and loss behaviour |
| `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/identity/InternalResourceKeyTest.kt` | frozen canonical bytes for the two internal roots |
| `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlConformanceSuite.kt` | the shared real-context suite body, called only by platform fixtures |
| `kmp/src/linuxTest/kotlin/com/rohittp/reng/internal/gl/SurfacelessEglContext.kt` | surfaceless EGL context creation and the Linux context probe |
| `kmp/src/linuxTest/kotlin/com/rohittp/reng/internal/gl/LinuxGlConformanceTest.kt` | runs the suite on a real ES 3.2 and a real 4.5 core context |
| `kmp/src/macosTest/kotlin/com/rohittp/reng/internal/gl/CglCoreProfileContext.kt` | CGL core-profile context, acceleration never requested |
| `kmp/src/macosTest/kotlin/com/rohittp/reng/internal/gl/MacosGlConformanceTest.kt` | runs the suite on the real Apple core-profile context |

### Workflows and documentation

| File | Change |
|---|---|
| `.github/workflows/ci.yml` | install Mesa EGL before the Ubuntu Gradle step |
| `.github/workflows/publish.yml` | install Mesa EGL before the Linux release gate |
| `docs/adr/0023-restore-the-corrected-gl-state-set-and-consume-the-error-queue.md` | supersede ADR 0006's set and declare the error-queue exception |
| `CONTEXT.md` | add the **Restore Set** vocabulary entry |
| `CLAUDE.md`, `HANDOFF.md`, `docs/decomposition.md` | record Cycle D implementation state factually |

## Parallel Execution Map

Use isolated worktrees only for these parallel waves. Each worker owns only its listed production/test files, commits them, and returns the commit SHA. The controller reviews and cherry-picks each commit before the next wave.

```text
Task 0 freeze exact worker base
  └─ Task 1 tokens and roster
       └─ Task 2 seam interface, expect declaration, recording fake
            ├─ Task 3 iosMain            ┐
            ├─ Task 4 macosMain          │ four independent binding lanes
            ├─ Task 5 linuxMain          │
            └─ Task 6 androidMain        ┘
                 └─ Task 7 six-target layout proof
       └─ (in parallel with 3–6, all pure and fake-driven)
            ├─ Task 8 context profile and dialect
            ├─ Task 9 error queue
            └─ Task 11 internal resource identities

After Task 8 and Task 9:
  └─ Task 10 save-and-restore set

After Task 8, Task 10, and Task 11:
  ├─ Task 12 shader compiler and program cache
  └─ Task 13 offscreen surface

After Task 12 and Task 13:
  └─ Task 14 composite pass and draw path

After Task 13:
  └─ Task 15 object registry and context identity

After Task 14 and Task 15:
  └─ Task 16 lifecycle driver

After Task 7 and Task 16:
  └─ Task 17 shared conformance suite body
       ├─ Task 18 Linux fixture and suite run
       └─ Task 19 macOS fixture and suite run

Task 20 CI wiring after Task 18
Task 21 documentation and full local gates after Tasks 19 and 20
```

At most three independent implementation workers run at once. A practical schedule is `(3,4,5)`, then `(6,8,9)`, then `(7,10,11)`, then `(12,13)`, then `(14,15)`, then the serial `16 → 17 → (18,19) → 20 → 21`. Each dependent dispatch uses a new exact controller SHA after its prerequisites are reviewed and incorporated.

No two workers edit the same seam interface file, roster file, recording fake, ABI dump, workflow, or documentation status file.

---

### Task 0: Freeze the Execution Base

**Files:**
- Verify tracked: `docs/superpowers/plans/2026-08-18-cycle-d-gl-foundation.md`
- Verify tracked: `docs/superpowers/specs/2026-08-18-cycle-d-gl-foundation-design.md`
- Verify tracked: `docs/research/2026-08-18-cycle-d-gl-foundation.md`
- Modify: none.

**Interfaces:**
- Consumes: the committed, independently reviewed plan; the approved Cycle D specification; ADRs 0001, 0005, 0006, 0007, 0008, 0009, 0011, 0012, 0015, 0022.
- Produces: one exact clean controller SHA used as the base of every worker in the next wave, and a recorded baseline ABI digest.

- [ ] **Step 1: Require the reviewed artifacts to be committed**

```bash
git ls-files --error-unmatch \
  docs/superpowers/plans/2026-08-18-cycle-d-gl-foundation.md \
  docs/superpowers/specs/2026-08-18-cycle-d-gl-foundation-design.md \
  docs/research/2026-08-18-cycle-d-gl-foundation.md \
  docs/adr/0022-place-gl-implementations-by-measured-source-set-visibility.md
test -z "$(git status --porcelain)"
```

If any command fails, stop before implementation and commit the missing documentation rather than giving workers an untracked side channel.

- [ ] **Step 2: Record the ABI baseline this cycle must not move**

```bash
shasum -a 256 kmp/api/kmp.klib.api | tee /tmp/reng-cycle-d-abi-baseline.txt
```

Every task that touches `kmp/src` re-runs `:kmp:checkKotlinAbi` and re-checks this digest. A changed digest means an `internal` declaration leaked into the public surface, and is a stop-the-line defect rather than a dump to regenerate.

- [ ] **Step 3: Capture and enforce each wave base**

```bash
WAVE_BASE="$(git rev-parse HEAD)"
printf '%s\n' "$WAVE_BASE"
```

Every isolated worker starts by running, in its clean temporary branch:

```bash
git merge --ff-only "$WAVE_BASE"
test "$(git rev-parse HEAD)" = "$WAVE_BASE"
test -z "$(git status --porcelain)"
```

The controller includes the literal `WAVE_BASE` SHA in each dispatch and captures a new base after reviewing and cherry-picking a worker commit. Remove completed `.claude/worktrees/agent-*` entries only after confirming each is clean and incorporated; keep the primary checkout.

---

### Task 1: GL Tokens and the Eighty-Four-Name Entry-Point Roster

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlTokens.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlEntryPoint.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlEntryPointRosterTest.kt`

**Interfaces:**
- Consumes: only the Kotlin standard library.
- Produces: `internal const val GL_*` tokens shared by every implementation, and `internal enum class GlEntryPoint(val cName: String)` with exactly eighty-four entries — the roster the Linux implementation resolves at runtime and the checklist the other three satisfy at compile time.

- [ ] **Step 1: Write the roster tests**

```kotlin
package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlEntryPointRosterTest {
    @Test fun rosterHasExactlyEightyFourEntryPoints() {
        assertEquals(84, GlEntryPoint.entries.size)
    }

    @Test fun everyCNameIsDistinctAndWellFormed() {
        val names = GlEntryPoint.entries.map { it.cName }
        assertEquals(names.size, names.toSet().size)
        names.forEach { name ->
            assertTrue(name.startsWith("gl"), "entry point $name must be a GL C name")
            assertTrue(name.length > 2 && name[2].isUpperCase(), "entry point $name is malformed")
            assertTrue(name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' })
        }
    }

    @Test fun rosterOrderIsStableForOrdinalIndexedTables() {
        assertEquals(GlEntryPoint.GET_ERROR, GlEntryPoint.entries.first())
        assertEquals("glGetError", GlEntryPoint.GET_ERROR.cName)
        assertEquals("glGetStringi", GlEntryPoint.GET_STRINGI.cName)
        assertEquals("glDepthRangef", GlEntryPoint.DEPTH_RANGEF.cName)
        assertEquals("glTexStorage2D", GlEntryPoint.TEX_STORAGE_2D.cName)
    }

    @Test fun tokensThatDifferBetweenDialectsAreNotFolded() {
        assertEquals(0x8DB9, GL_FRAMEBUFFER_SRGB)
        assertEquals(0x0C01, GL_DRAW_BUFFER)
        assertEquals(0x0B20, GL_LINE_SMOOTH)
        assertEquals(0x0CF5, GL_UNPACK_ALIGNMENT)
        assertEquals(0x0D05, GL_PACK_ALIGNMENT)
        assertEquals(0x821D, GL_NUM_EXTENSIONS)
        assertEquals(0x1F03, GL_EXTENSIONS)
    }
}
```

The last case is not decoration: `GL_DRAW_BUFFER` and `GL_LINE_SMOOTH` are the two tokens Task 10 must query only on a desktop context, and `GL_NUM_EXTENSIONS` is the token that makes `glGetStringi` necessary at all, so their values are pinned where a reader will look for them.

- [ ] **Step 2: Run the focused test and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.GlEntryPointRosterTest"
```

Expected: compilation fails because neither the roster nor the tokens exist.

- [ ] **Step 3: Implement the tokens**

`GlTokens.kt` declares every constant as `internal const val` with its hexadecimal value. GLES 3.0 and GL 3.3 agree on every one of them, which is exactly why they live once in common code (ADR 0009).

```kotlin
package com.rohittp.reng.internal.gl

internal const val GL_NO_ERROR: Int = 0x0000
internal const val GL_INVALID_ENUM: Int = 0x0500
internal const val GL_INVALID_VALUE: Int = 0x0501
internal const val GL_INVALID_OPERATION: Int = 0x0502
internal const val GL_OUT_OF_MEMORY: Int = 0x0505
internal const val GL_INVALID_FRAMEBUFFER_OPERATION: Int = 0x0506

internal const val GL_VENDOR: Int = 0x1F00
internal const val GL_RENDERER: Int = 0x1F01
internal const val GL_VERSION: Int = 0x1F02
internal const val GL_EXTENSIONS: Int = 0x1F03
internal const val GL_SHADING_LANGUAGE_VERSION: Int = 0x8B8C
internal const val GL_NUM_EXTENSIONS: Int = 0x821D

internal const val GL_FRAMEBUFFER: Int = 0x8D40
internal const val GL_DRAW_FRAMEBUFFER: Int = 0x8CA9
internal const val GL_READ_FRAMEBUFFER: Int = 0x8CA8
internal const val GL_RENDERBUFFER: Int = 0x8D41
internal const val GL_DRAW_FRAMEBUFFER_BINDING: Int = 0x8CA6
internal const val GL_READ_FRAMEBUFFER_BINDING: Int = 0x8CAA
internal const val GL_RENDERBUFFER_BINDING: Int = 0x8CA7
internal const val GL_FRAMEBUFFER_COMPLETE: Int = 0x8CD5
internal const val GL_FRAMEBUFFER_UNDEFINED: Int = 0x8219
internal const val GL_COLOR_ATTACHMENT0: Int = 0x8CE0
internal const val GL_DEPTH_ATTACHMENT: Int = 0x8D00
internal const val GL_MAX_COLOR_ATTACHMENTS: Int = 0x8CDF

internal const val GL_TEXTURE_2D: Int = 0x0DE1
internal const val GL_TEXTURE0: Int = 0x84C0
internal const val GL_TEXTURE_BINDING_2D: Int = 0x8069
internal const val GL_ACTIVE_TEXTURE: Int = 0x84E0
internal const val GL_SAMPLER_BINDING: Int = 0x8919
internal const val GL_MAX_TEXTURE_SIZE: Int = 0x0D33
internal const val GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS: Int = 0x8B4D
internal const val GL_TEXTURE_MIN_FILTER: Int = 0x2801
internal const val GL_TEXTURE_MAG_FILTER: Int = 0x2800
internal const val GL_TEXTURE_WRAP_S: Int = 0x2802
internal const val GL_TEXTURE_WRAP_T: Int = 0x2803
internal const val GL_NEAREST: Int = 0x2600
internal const val GL_LINEAR: Int = 0x2601
internal const val GL_CLAMP_TO_EDGE: Int = 0x812F

internal const val GL_RGBA: Int = 0x1908
internal const val GL_RGBA8: Int = 0x8058
internal const val GL_DEPTH_COMPONENT24: Int = 0x81A6
internal const val GL_UNSIGNED_BYTE: Int = 0x1401
internal const val GL_FLOAT: Int = 0x1406
internal const val GL_UNSIGNED_INT: Int = 0x1405

internal const val GL_ARRAY_BUFFER: Int = 0x8892
internal const val GL_ELEMENT_ARRAY_BUFFER: Int = 0x8893
internal const val GL_PIXEL_UNPACK_BUFFER: Int = 0x88EC
internal const val GL_UNIFORM_BUFFER: Int = 0x8A11
internal const val GL_ARRAY_BUFFER_BINDING: Int = 0x8894
internal const val GL_ELEMENT_ARRAY_BUFFER_BINDING: Int = 0x8895
internal const val GL_PIXEL_UNPACK_BUFFER_BINDING: Int = 0x88EF
internal const val GL_UNIFORM_BUFFER_BINDING: Int = 0x8A28
internal const val GL_VERTEX_ARRAY_BINDING: Int = 0x85B5
internal const val GL_STATIC_DRAW: Int = 0x88E4

internal const val GL_VERTEX_SHADER: Int = 0x8B31
internal const val GL_FRAGMENT_SHADER: Int = 0x8B30
internal const val GL_COMPILE_STATUS: Int = 0x8B81
internal const val GL_LINK_STATUS: Int = 0x8B82
internal const val GL_INFO_LOG_LENGTH: Int = 0x8B84
internal const val GL_CURRENT_PROGRAM: Int = 0x8B8D

internal const val GL_BLEND: Int = 0x0BE2
internal const val GL_BLEND_SRC_RGB: Int = 0x80C9
internal const val GL_BLEND_DST_RGB: Int = 0x80C8
internal const val GL_BLEND_SRC_ALPHA: Int = 0x80CB
internal const val GL_BLEND_DST_ALPHA: Int = 0x80CA
internal const val GL_BLEND_EQUATION_RGB: Int = 0x8009
internal const val GL_BLEND_EQUATION_ALPHA: Int = 0x883D
internal const val GL_BLEND_COLOR: Int = 0x8005
internal const val GL_ZERO: Int = 0x0000
internal const val GL_ONE: Int = 0x0001
internal const val GL_SRC_ALPHA: Int = 0x0302
internal const val GL_ONE_MINUS_SRC_ALPHA: Int = 0x0303
internal const val GL_FUNC_ADD: Int = 0x8006

internal const val GL_DEPTH_TEST: Int = 0x0B71
internal const val GL_DEPTH_FUNC: Int = 0x0B74
internal const val GL_DEPTH_WRITEMASK: Int = 0x0B72
internal const val GL_DEPTH_RANGE: Int = 0x0B70
internal const val GL_DEPTH_CLEAR_VALUE: Int = 0x0B73
internal const val GL_LESS: Int = 0x0201
internal const val GL_GREATER: Int = 0x0204

internal const val GL_CULL_FACE: Int = 0x0B44
internal const val GL_CULL_FACE_MODE: Int = 0x0B45
internal const val GL_FRONT_FACE: Int = 0x0B46
internal const val GL_BACK: Int = 0x0405
internal const val GL_CCW: Int = 0x0901

internal const val GL_VIEWPORT: Int = 0x0BA2
internal const val GL_SCISSOR_TEST: Int = 0x0C11
internal const val GL_SCISSOR_BOX: Int = 0x0C10
internal const val GL_COLOR_WRITEMASK: Int = 0x0C23
internal const val GL_COLOR_CLEAR_VALUE: Int = 0x0C22
internal const val GL_COLOR_BUFFER_BIT: Int = 0x4000
internal const val GL_DEPTH_BUFFER_BIT: Int = 0x0100

internal const val GL_UNPACK_ALIGNMENT: Int = 0x0CF5
internal const val GL_UNPACK_ROW_LENGTH: Int = 0x0CF2
internal const val GL_UNPACK_SKIP_ROWS: Int = 0x0CF3
internal const val GL_UNPACK_SKIP_PIXELS: Int = 0x0CF4
internal const val GL_PACK_ALIGNMENT: Int = 0x0D05

internal const val GL_FRAMEBUFFER_SRGB: Int = 0x8DB9
internal const val GL_DRAW_BUFFER: Int = 0x0C01
internal const val GL_LINE_SMOOTH: Int = 0x0B20

internal const val GL_TRIANGLES: Int = 0x0004
internal const val GL_TRIANGLE_STRIP: Int = 0x0005
internal const val GL_NONE: Int = 0x0000

internal const val GL_UNPACK_ALIGNMENT_DEFAULT: Int = 4
internal const val GL_PACK_ALIGNMENT_DEFAULT: Int = 4
```

`GL_UNPACK_ALIGNMENT_DEFAULT` is `4`, not `1`. That value was read from a live llvmpipe context and from a hosted macOS runner, and an implementation that assumes `1` both corrupts non-aligned rows and leaves the caller's state dirty.

- [ ] **Step 4: Implement the roster**

`GlEntryPoint.kt` declares the eighty-four entries in six groups, in the order the Linux table indexes them by ordinal.

```kotlin
package com.rohittp.reng.internal.gl

internal enum class GlEntryPoint(internal val cName: String) {
    GET_ERROR("glGetError"),
    GET_STRING("glGetString"),
    GET_STRINGI("glGetStringi"),
    GET_INTEGERV("glGetIntegerv"),
    GET_FLOATV("glGetFloatv"),
    GET_BOOLEANV("glGetBooleanv"),
    IS_ENABLED("glIsEnabled"),

    GEN_FRAMEBUFFERS("glGenFramebuffers"),
    DELETE_FRAMEBUFFERS("glDeleteFramebuffers"),
    BIND_FRAMEBUFFER("glBindFramebuffer"),
    FRAMEBUFFER_TEXTURE_2D("glFramebufferTexture2D"),
    FRAMEBUFFER_RENDERBUFFER("glFramebufferRenderbuffer"),
    CHECK_FRAMEBUFFER_STATUS("glCheckFramebufferStatus"),
    IS_FRAMEBUFFER("glIsFramebuffer"),
    GEN_RENDERBUFFERS("glGenRenderbuffers"),
    DELETE_RENDERBUFFERS("glDeleteRenderbuffers"),
    BIND_RENDERBUFFER("glBindRenderbuffer"),
    RENDERBUFFER_STORAGE("glRenderbufferStorage"),
    BLIT_FRAMEBUFFER("glBlitFramebuffer"),
    DRAW_BUFFERS("glDrawBuffers"),
    READ_BUFFER("glReadBuffer"),

    GEN_TEXTURES("glGenTextures"),
    DELETE_TEXTURES("glDeleteTextures"),
    BIND_TEXTURE("glBindTexture"),
    ACTIVE_TEXTURE("glActiveTexture"),
    TEX_IMAGE_2D("glTexImage2D"),
    TEX_STORAGE_2D("glTexStorage2D"),
    TEX_PARAMETERI("glTexParameteri"),
    GENERATE_MIPMAP("glGenerateMipmap"),
    GEN_SAMPLERS("glGenSamplers"),
    DELETE_SAMPLERS("glDeleteSamplers"),
    BIND_SAMPLER("glBindSampler"),
    SAMPLER_PARAMETERI("glSamplerParameteri"),
    PIXEL_STOREI("glPixelStorei"),
    READ_PIXELS("glReadPixels"),

    GEN_BUFFERS("glGenBuffers"),
    DELETE_BUFFERS("glDeleteBuffers"),
    BIND_BUFFER("glBindBuffer"),
    BUFFER_DATA("glBufferData"),
    BUFFER_SUB_DATA("glBufferSubData"),
    GEN_VERTEX_ARRAYS("glGenVertexArrays"),
    DELETE_VERTEX_ARRAYS("glDeleteVertexArrays"),
    BIND_VERTEX_ARRAY("glBindVertexArray"),
    ENABLE_VERTEX_ATTRIB_ARRAY("glEnableVertexAttribArray"),
    DISABLE_VERTEX_ATTRIB_ARRAY("glDisableVertexAttribArray"),
    VERTEX_ATTRIB_POINTER("glVertexAttribPointer"),

    CREATE_SHADER("glCreateShader"),
    DELETE_SHADER("glDeleteShader"),
    SHADER_SOURCE("glShaderSource"),
    COMPILE_SHADER("glCompileShader"),
    GET_SHADERIV("glGetShaderiv"),
    GET_SHADER_INFO_LOG("glGetShaderInfoLog"),
    CREATE_PROGRAM("glCreateProgram"),
    DELETE_PROGRAM("glDeleteProgram"),
    ATTACH_SHADER("glAttachShader"),
    LINK_PROGRAM("glLinkProgram"),
    GET_PROGRAMIV("glGetProgramiv"),
    GET_PROGRAM_INFO_LOG("glGetProgramInfoLog"),
    USE_PROGRAM("glUseProgram"),
    GET_ATTRIB_LOCATION("glGetAttribLocation"),
    GET_UNIFORM_LOCATION("glGetUniformLocation"),
    UNIFORM_1I("glUniform1i"),
    UNIFORM_1F("glUniform1f"),
    UNIFORM_4F("glUniform4f"),
    UNIFORM_MATRIX_4FV("glUniformMatrix4fv"),

    ENABLE("glEnable"),
    DISABLE("glDisable"),
    BLEND_FUNC_SEPARATE("glBlendFuncSeparate"),
    BLEND_EQUATION_SEPARATE("glBlendEquationSeparate"),
    BLEND_COLOR("glBlendColor"),
    DEPTH_FUNC("glDepthFunc"),
    DEPTH_MASK("glDepthMask"),
    DEPTH_RANGEF("glDepthRangef"),
    CULL_FACE("glCullFace"),
    FRONT_FACE("glFrontFace"),
    VIEWPORT("glViewport"),
    SCISSOR("glScissor"),
    COLOR_MASK("glColorMask"),
    CLEAR_COLOR("glClearColor"),
    CLEAR_DEPTHF("glClearDepthf"),
    CLEAR("glClear"),
    DRAW_ARRAYS("glDrawArrays"),
    DRAW_ELEMENTS("glDrawElements"),
    FINISH("glFinish"),
}
```

`glGetStringi` sits beside `glGetString` because `glGetString(GL_EXTENSIONS)` returns `NULL` with `GL_INVALID_ENUM` on a desktop core profile, so every extension query must go through `glGetIntegerv(GL_NUM_EXTENSIONS)` plus `glGetStringi`. `glDepthRangef` is in the roster and the `double` form `glDepthRange` is not, because the `double` form is desktop-only. `glTexStorage2D`, `glBindSampler`, `glGenVertexArrays`, `glBindVertexArray`, `glDrawBuffers`, `glReadBuffer`, `glBlitFramebuffer`, and `glGetStringi` are the ES-3-era calls that live on `GLES30` and not `GLES20`, which is why `GLES20` alone is insufficient on Android even though it holds most of the functions.

- [ ] **Step 5: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.GlEntryPointRosterTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.gl.GlEntryPointRosterTest"
./gradlew --no-configuration-cache :kmp:checkKotlinAbi
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl
git commit -m "feat: add the GL token set and entry-point roster"
```

Expected: both test runs pass and `checkKotlinAbi` reports no change.

---

### Task 2: The GL Seam, Its Platform Entry Point, and the Recording Fake

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlBinding.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/PlatformGlBinding.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/RecordingGlBinding.kt`

**Interfaces:**
- Consumes: Task 1 tokens and roster.
- Produces: `internal interface GlBinding` with eighty-four methods typed at Android's width; `internal sealed interface GlBindingResult`; `internal expect fun openPlatformGlBinding(): GlBindingResult`; and `RecordingGlBinding`, the programmable fake that every pure test in Tasks 8–16 drives.

- [ ] **Step 1: Write the seam-shape test**

```kotlin
package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordingGlBindingTest {
    @Test fun outParametersAreWrittenThroughAndCallsAreLogged() {
        val binding = RecordingGlBinding()
        binding.integers[GL_ACTIVE_TEXTURE] = intArrayOf(GL_TEXTURE0 + 3)
        val out = IntArray(1)
        binding.getIntegerv(GL_ACTIVE_TEXTURE, out)
        assertContentEquals(intArrayOf(GL_TEXTURE0 + 3), out)
        assertEquals(listOf("getIntegerv(0x84E0)"), binding.log)
    }

    @Test fun generatedNamesAreDistinctAndNonZero() {
        val binding = RecordingGlBinding()
        val first = IntArray(2)
        val second = IntArray(1)
        binding.genTextures(2, first)
        binding.genBuffers(1, second)
        assertTrue(first.all { it > 0 })
        assertTrue(second.single() > 0)
        assertEquals(3, (first + second).toSet().size)
    }

    @Test fun emptyOutputArraysAreRejectedRatherThanSilentlyIgnored() {
        val binding = RecordingGlBinding()
        val failure = runCatching { binding.getIntegerv(GL_VIEWPORT, IntArray(0)) }
        assertTrue(failure.isFailure)
    }
}
```

The third case encodes the measured native trap at the seam's own boundary: `addressOf(0)` throws at runtime on an empty array, so every pinned-array call needs a zero-length guard. Output arrays are a caller error and fail loudly; optional input data is a supported `null`-equivalent and passes a null pointer through.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.RecordingGlBindingTest"
```

Expected: compilation fails because `GlBinding` and `RecordingGlBinding` do not exist.

- [ ] **Step 3: Declare the seam**

`GlBinding.kt` holds exactly one interface with eighty-four methods, one per roster entry, in roster order. Every object name and enum is `Int`, every GL boolean in is `Boolean`, every boolean output is `BooleanArray`, integer outputs are `IntArray`, float outputs are `FloatArray`, buffer sizes are `Int`, pixel and vertex data are `ByteArray?`, and shader source is a single `String`. Android is the narrower side in every one of those cases, and `glShaderSource(int, String)` — no count, no length array — forces the decision rather than merely illustrating it.

```kotlin
package com.rohittp.reng.internal.gl

internal interface GlBinding {
    fun getError(): Int
    fun getString(name: Int): String?
    fun getStringi(name: Int, index: Int): String?
    fun getIntegerv(pname: Int, out: IntArray)
    fun getFloatv(pname: Int, out: FloatArray)
    fun getBooleanv(pname: Int, out: BooleanArray)
    fun isEnabled(cap: Int): Boolean

    fun genFramebuffers(count: Int, out: IntArray)
    fun deleteFramebuffers(count: Int, names: IntArray)
    fun bindFramebuffer(target: Int, framebuffer: Int)
    fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int)
    fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: Int)
    fun checkFramebufferStatus(target: Int): Int
    fun isFramebuffer(framebuffer: Int): Boolean
    fun genRenderbuffers(count: Int, out: IntArray)
    fun deleteRenderbuffers(count: Int, names: IntArray)
    fun bindRenderbuffer(target: Int, renderbuffer: Int)
    fun renderbufferStorage(target: Int, internalFormat: Int, width: Int, height: Int)
    fun blitFramebuffer(
        sourceX0: Int, sourceY0: Int, sourceX1: Int, sourceY1: Int,
        destinationX0: Int, destinationY0: Int, destinationX1: Int, destinationY1: Int,
        mask: Int, filter: Int,
    )
    fun drawBuffers(count: Int, buffers: IntArray)
    fun readBuffer(mode: Int)

    fun genTextures(count: Int, out: IntArray)
    fun deleteTextures(count: Int, names: IntArray)
    fun bindTexture(target: Int, texture: Int)
    fun activeTexture(unit: Int)
    fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: ByteArray?,
    )
    fun texStorage2D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int)
    fun texParameteri(target: Int, pname: Int, value: Int)
    fun generateMipmap(target: Int)
    fun genSamplers(count: Int, out: IntArray)
    fun deleteSamplers(count: Int, names: IntArray)
    fun bindSampler(unit: Int, sampler: Int)
    fun samplerParameteri(sampler: Int, pname: Int, value: Int)
    fun pixelStorei(pname: Int, value: Int)
    fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, out: ByteArray)

    fun genBuffers(count: Int, out: IntArray)
    fun deleteBuffers(count: Int, names: IntArray)
    fun bindBuffer(target: Int, buffer: Int)
    fun bufferData(target: Int, size: Int, data: ByteArray?, usage: Int)
    fun bufferSubData(target: Int, offset: Int, size: Int, data: ByteArray)
    fun genVertexArrays(count: Int, out: IntArray)
    fun deleteVertexArrays(count: Int, names: IntArray)
    fun bindVertexArray(array: Int)
    fun enableVertexAttribArray(index: Int)
    fun disableVertexAttribArray(index: Int)
    fun vertexAttribPointer(
        index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int,
    )

    fun createShader(type: Int): Int
    fun deleteShader(shader: Int)
    fun shaderSource(shader: Int, source: String)
    fun compileShader(shader: Int)
    fun getShaderiv(shader: Int, pname: Int, out: IntArray)
    fun getShaderInfoLog(shader: Int): String
    fun createProgram(): Int
    fun deleteProgram(program: Int)
    fun attachShader(program: Int, shader: Int)
    fun linkProgram(program: Int)
    fun getProgramiv(program: Int, pname: Int, out: IntArray)
    fun getProgramInfoLog(program: Int): String
    fun useProgram(program: Int)
    fun getAttribLocation(program: Int, name: String): Int
    fun getUniformLocation(program: Int, name: String): Int
    fun uniform1i(location: Int, value: Int)
    fun uniform1f(location: Int, value: Float)
    fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float)
    fun uniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray)

    fun enable(cap: Int)
    fun disable(cap: Int)
    fun blendFuncSeparate(sourceRgb: Int, destinationRgb: Int, sourceAlpha: Int, destinationAlpha: Int)
    fun blendEquationSeparate(modeRgb: Int, modeAlpha: Int)
    fun blendColor(red: Float, green: Float, blue: Float, alpha: Float)
    fun depthFunc(function: Int)
    fun depthMask(enabled: Boolean)
    fun depthRangef(near: Float, far: Float)
    fun cullFace(mode: Int)
    fun frontFace(mode: Int)
    fun viewport(x: Int, y: Int, width: Int, height: Int)
    fun scissor(x: Int, y: Int, width: Int, height: Int)
    fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean)
    fun clearColor(red: Float, green: Float, blue: Float, alpha: Float)
    fun clearDepthf(depth: Float)
    fun clear(mask: Int)
    fun drawArrays(mode: Int, first: Int, count: Int)
    fun drawElements(mode: Int, count: Int, type: Int, offset: Int)
    fun finish()
}
```

- [ ] **Step 4: Declare the platform entry point**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.failure.FailureDescriptor

internal sealed interface GlBindingResult {
    data class Bound(val binding: GlBinding) : GlBindingResult

    data class Unsupported(val failure: FailureDescriptor) : GlBindingResult
}

internal fun unsupportedRenderContext(): GlBindingResult.Unsupported =
    GlBindingResult.Unsupported(
        FailureDescriptor(
            code = RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT,
            stage = PipelineStage.CONTEXT_ADOPTION,
        ),
    )

internal expect fun openPlatformGlBinding(): GlBindingResult
```

`UNSUPPORTED_RENDER_CONTEXT` at `CONTEXT_ADOPTION` carries no diagnostic under the existing failure rules, which is exactly the redaction the design wants: a missing entry point, an unloadable dispatch library, and a driver below ES 3.0 are all one typed setup failure with no library path, symbol name, or driver text attached.

- [ ] **Step 5: Implement the recording fake**

```kotlin
package com.rohittp.reng.internal.gl

internal class RecordingGlBinding : GlBinding {
    val log: MutableList<String> = mutableListOf()
    val integers: MutableMap<Int, IntArray> = mutableMapOf()
    val floats: MutableMap<Int, FloatArray> = mutableMapOf()
    val booleans: MutableMap<Int, BooleanArray> = mutableMapOf()
    val enabled: MutableMap<Int, Boolean> = mutableMapOf()
    val strings: MutableMap<Int, String?> = mutableMapOf()
    val indexedStrings: MutableList<String> = mutableListOf()
    var errorQueue: MutableList<Int> = mutableListOf()
    var compileStatus: Int = 1
    var linkStatus: Int = 1
    var framebufferStatus: Int = GL_FRAMEBUFFER_COMPLETE
    var shaderInfoLog: String = ""
    var programInfoLog: String = ""
    var uniformLocation: Int = 0
    val shaderSources: MutableMap<Int, String> = mutableMapOf()
    private var nextName: Int = 1

    override fun getError(): Int = if (errorQueue.isEmpty()) GL_NO_ERROR else errorQueue.removeAt(0)

    override fun getString(name: Int): String? {
        log += "getString(0x${name.toString(16).uppercase()})"
        return strings[name]
    }

    override fun getStringi(name: Int, index: Int): String? {
        log += "getStringi(0x${name.toString(16).uppercase()},$index)"
        return indexedStrings.getOrNull(index)
    }

    override fun getIntegerv(pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an integer query needs a destination" }
        log += "getIntegerv(0x${pname.toString(16).uppercase()})"
        integers[pname]?.copyInto(out, endIndex = minOf(out.size, integers.getValue(pname).size))
    }

    override fun genTextures(count: Int, out: IntArray) = generate("genTextures", count, out)

    private fun generate(call: String, count: Int, out: IntArray) {
        require(out.size >= count) { "$call needs room for $count names" }
        log += "$call($count)"
        repeat(count) { index ->
            out[index] = nextName
            nextName += 1
        }
    }
}
```

Complete the remaining methods mechanically under three rules, with no exceptions. Every method appends `"<methodName>(<comma-separated arguments, enums as 0xHEX>)"` to `log`. Every value-returning method returns the corresponding programmable field (`compileStatus`/`linkStatus` through `getShaderiv`/`getProgramiv`, `framebufferStatus` through `checkFramebufferStatus`, `uniformLocation` through `getUniformLocation` and `getAttribLocation`, `shaderInfoLog`/`programInfoLog` through the info-log getters, `enabled[cap] ?: false` through `isEnabled`, `true` through `isFramebuffer` for a non-zero name). Every generator (`genFramebuffers`, `genRenderbuffers`, `genBuffers`, `genVertexArrays`, `genSamplers`) delegates to `generate`, `createShader` and `createProgram` return a fresh `nextName`, `shaderSource` records into `shaderSources`, `getFloatv`/`getBooleanv` mirror `getIntegerv` against `floats`/`booleans` with the same non-empty requirement, and every remaining `Unit` method only logs. `RecordingGlBinding` lives in `commonTest` and never in production source.

- [ ] **Step 6: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.RecordingGlBindingTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.gl.RecordingGlBindingTest"
```

Expected: both fail to link `openPlatformGlBinding` until Tasks 3–6 land, because an `expect` declaration without actuals fails every target. Land Tasks 3–6 before re-running, and commit this task together with the first binding that makes its target compile.

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlBinding.kt \
  kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/PlatformGlBinding.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/RecordingGlBinding.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/RecordingGlBindingTest.kt
git commit -m "feat: declare the internal GL seam"
```

---

### Task 3: The iOS Implementation over `platform.gles3`

**Files:**
- Create: `kmp/src/iosMain/kotlin/com/rohittp/reng/internal/gl/IosGlBinding.kt`

**Interfaces:**
- Consumes: Task 2's `GlBinding` and `openPlatformGlBinding` expectation; Task 1's roster as the checklist.
- Produces: `internal object IosGlBinding : GlBinding` and the `iosMain` actual of `openPlatformGlBinding()`, serving `iosArm64` and `iosSimulatorArm64` from one file.

The package is `platform.gles3` even though the module is named `OpenGLES3`, and `platform.glescommon` declares no functions at all — it exists to hold the type aliases. `platform.EAGL` is context management and this file must never touch it (ADR 0001).

- [ ] **Step 1: Prove the source set resolves the binding before writing the body**

```bash
cat > kmp/src/iosMain/kotlin/com/rohittp/reng/internal/gl/IosGlBinding.kt <<'PROBE'
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import platform.gles3.glGetError

internal fun iosGlProbe(): Int = glGetError().toInt()
PROBE
./gradlew --no-configuration-cache :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64
```

Expected: both compile. ADR 0022 measured exactly this — a file in `iosMain` importing `platform.gles3` resolves for both iOS targets — and it is the fact the whole source-set layout hangs on. If it fails, stop and escalate rather than moving the file into a leaf, because the ADR would then be wrong and the layout decision reopens.

- [ ] **Step 2: Implement the marshalling shapes**

Replace the probe with the full object. These bodies cover every marshalling shape the remaining seventy-odd methods reuse.

```kotlin
@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.gles3.glBufferData
import platform.gles3.glDeleteTextures
import platform.gles3.glGenTextures
import platform.gles3.glGetBooleanv
import platform.gles3.glGetError
import platform.gles3.glGetIntegerv
import platform.gles3.glGetShaderInfoLog
import platform.gles3.glGetShaderiv
import platform.gles3.glGetString
import platform.gles3.glGetStringi
import platform.gles3.glGetUniformLocation
import platform.gles3.glIsEnabled
import platform.gles3.glShaderSource
import platform.gles3.glTexImage2D
import platform.gles3.glVertexAttribPointer

internal object IosGlBinding : GlBinding {
    override fun getError(): Int = glGetError().toInt()

    override fun getString(name: Int): String? =
        glGetString(name.toUInt())?.reinterpret<ByteVar>()?.toKString()

    override fun getStringi(name: Int, index: Int): String? =
        glGetStringi(name.toUInt(), index.toUInt())?.reinterpret<ByteVar>()?.toKString()

    override fun getIntegerv(pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an integer query needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            glGetIntegerv(pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index]
        }
    }

    override fun getBooleanv(pname: Int, out: BooleanArray) {
        require(out.isNotEmpty()) { "a boolean query needs a destination" }
        memScoped {
            val buffer = allocArray<UByteVar>(out.size)
            glGetBooleanv(pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index].toInt() != 0
        }
    }

    override fun isEnabled(cap: Int): Boolean = glIsEnabled(cap.toUInt()).toInt() != 0

    override fun genTextures(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenTextures needs room for $count names" }
        if (count == 0) return
        memScoped {
            val buffer = allocArray<UIntVar>(count)
            glGenTextures(count, buffer)
            for (index in 0 until count) out[index] = buffer[index].toInt()
        }
    }

    override fun deleteTextures(count: Int, names: IntArray) {
        require(names.size >= count) { "glDeleteTextures was given fewer names than $count" }
        if (count == 0) return
        memScoped { glDeleteTextures(count, unsignedNames(names, count)) }
    }

    override fun shaderSource(shader: Int, source: String) {
        memScoped {
            val sources = allocArray<CPointerVar<ByteVar>>(1)
            sources[0] = source.cstr.ptr
            glShaderSource(shader.toUInt(), 1, sources, null)
        }
    }

    override fun getShaderInfoLog(shader: Int): String = memScoped {
        val length = alloc<IntVar>()
        glGetShaderiv(shader.toUInt(), GL_INFO_LOG_LENGTH.toUInt(), length.ptr)
        val size = length.value
        if (size <= 0) return@memScoped ""
        val buffer = allocArray<ByteVar>(size)
        glGetShaderInfoLog(shader.toUInt(), size, null, buffer)
        buffer.toKString()
    }

    override fun getUniformLocation(program: Int, name: String): Int =
        memScoped { glGetUniformLocation(program.toUInt(), name.cstr.ptr) }

    override fun bufferData(target: Int, size: Int, data: ByteArray?, usage: Int) {
        if (data == null || data.isEmpty()) {
            glBufferData(target.toUInt(), size.toLong(), null, usage.toUInt())
            return
        }
        data.usePinned { pinned ->
            glBufferData(target.toUInt(), size.toLong(), pinned.addressOf(0), usage.toUInt())
        }
    }

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: ByteArray?,
    ) {
        if (pixels == null || pixels.isEmpty()) {
            glTexImage2D(
                target.toUInt(), level, internalFormat, width, height,
                border, format.toUInt(), type.toUInt(), null,
            )
            return
        }
        pixels.usePinned { pinned ->
            glTexImage2D(
                target.toUInt(), level, internalFormat, width, height,
                border, format.toUInt(), type.toUInt(), pinned.addressOf(0),
            )
        }
    }

    override fun vertexAttribPointer(
        index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int,
    ) {
        glVertexAttribPointer(
            index.toUInt(), size, type.toUInt(), normalized.toGlBoolean(), stride,
            offset.toLong().toCPointer<ByteVar>(),
        )
    }
}

private fun Boolean.toGlBoolean(): UByte = if (this) 1u else 0u

private fun MemScope.unsignedNames(names: IntArray, count: Int): CPointer<UIntVar> {
    val buffer = allocArray<UIntVar>(count)
    for (index in 0 until count) buffer[index] = names[index].toUInt()
    return buffer
}
```

Write the remaining methods under four mechanical rules, applied without exception and in roster order:

1. **Scalars.** Every `Int` that is a GL object name or enum is passed `.toUInt()`; every `GLint`, `GLsizei`, and `GLsizeiptr` parameter is passed as `Int` or `.toLong()` to match the declared klib signature; every returned `GLenum`/`GLuint` is `.toInt()`; every returned `GLboolean` is `.toInt() != 0`; every `Boolean` in is `.toGlBoolean()`.
2. **Output arrays.** `require(out.isNotEmpty())`, allocate the matching `IntVar`/`UIntVar`/`FloatVar`/`UByteVar` array in `memScoped`, call, then copy back element by element. Never hand a Kotlin array straight to an output parameter.
3. **Input arrays and data.** `IntArray` name lists convert through `unsignedNames`; `FloatArray` uniform payloads and `ByteArray` pixel payloads use `usePinned { it.addressOf(0) }` guarded by an emptiness check, because `addressOf(0)` throws at runtime on an empty array; a `null` or empty optional payload passes a null pointer through.
4. **Strings.** Every `const GLchar*` parameter is `name.cstr.ptr` inside `memScoped`; every returned string is `reinterpret<ByteVar>().toKString()`; every info log is sized by its `GL_INFO_LOG_LENGTH` query first and returns `""` at length zero.

- [ ] **Step 3: Add the actual**

Append to the same file:

```kotlin
internal actual fun openPlatformGlBinding(): GlBindingResult = GlBindingResult.Bound(IosGlBinding)
```

Apple's ES entry points are resolved by the linker, so there is nothing to fail here; the only `Unsupported` result on iOS comes later, from Task 8's context adoption.

- [ ] **Step 4: Compile both iOS targets and commit**

```bash
./gradlew --no-configuration-cache :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64
git add kmp/src/iosMain/kotlin/com/rohittp/reng/internal/gl/IosGlBinding.kt
git commit -m "feat: implement the GL seam on iOS"
```

Expected: both compile. Both targets must be compiled, not one: resolution is enforced per leaf compilation, so a single green target proves nothing about the other.

---

### Task 4: The macOS Implementation over `platform.OpenGL3`

**Files:**
- Create: `kmp/src/macosMain/kotlin/com/rohittp/reng/internal/gl/MacosGlBinding.kt`

**Interfaces:**
- Consumes: Task 2's seam; Task 3's four marshalling rules verbatim.
- Produces: `internal object MacosGlBinding : GlBinding` and the `macosMain` actual of `openPlatformGlBinding()`.

`macosArm64` needs **both** klibs: `platform.OpenGL3` for the 509 GL entry points and `platform.OpenGLCommon` for the `GLenum`/`GLuint`/`GLboolean` typealiases, because the scalar types this file marshals live in `OpenGLCommon` rather than `OpenGL3`. A third klib, `platform.OpenGL`, carries 1043 functions spanning the legacy compatibility profile; do not import it, because it makes calling a function that does not exist in a core profile easy.

- [ ] **Step 1: Prove the source set resolves both klibs**

```bash
mkdir -p kmp/src/macosMain/kotlin/com/rohittp/reng/internal/gl
cat > kmp/src/macosMain/kotlin/com/rohittp/reng/internal/gl/MacosGlBinding.kt <<'PROBE'
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import platform.OpenGL3.glGetError
import platform.OpenGLCommon.GLenum

internal fun macosGlProbe(): Int {
    val error: GLenum = glGetError()
    return error.toInt()
}
PROBE
./gradlew --no-configuration-cache :kmp:compileKotlinMacosArm64
```

Expected: it compiles. `appleMain` can host neither package — a `platform.gles3` file there fails only at `compileKotlinMacosArm64` and a `platform.OpenGL3` file fails only at `compileKotlinIosArm64` — so this file stays in `macosMain` and never moves up.

- [ ] **Step 2: Implement the object**

For all twelve representative calls examined, `platform.OpenGL3` and `platform.gles3` declare byte-identical Kotlin signatures — same parameter types, same `CValuesRef` shapes, `GLboolean` as `UByte` on both — and only the package differs. Copy `IosGlBinding` verbatim, rename the object to `MacosGlBinding`, and replace every `import platform.gles3.<name>` with `import platform.OpenGL3.<name>`. Kotlin has no conditional import, so this near-duplication is irreducible; at four implementations a generator costs more than the duplication it removes.

```kotlin
@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.OpenGL3.glGetError
import platform.OpenGL3.glGetIntegerv
import platform.OpenGL3.glGetString

internal object MacosGlBinding : GlBinding {
    override fun getError(): Int = glGetError().toInt()

    override fun getString(name: Int): String? =
        glGetString(name.toUInt())?.reinterpret<ByteVar>()?.toKString()

    override fun getIntegerv(pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an integer query needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            glGetIntegerv(pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index]
        }
    }
}

internal actual fun openPlatformGlBinding(): GlBindingResult = GlBindingResult.Bound(MacosGlBinding)
```

If any one of the eighty-four signatures turns out to differ from its `platform.gles3` twin, fix that single method here rather than reshaping the seam; the seam's types are fixed by Android, not by either native klib.

- [ ] **Step 3: Verify no roster name is missing from the Apple klibs**

```bash
KN="$HOME/.konan/kotlin-native-prebuilt-macos-aarch64-2.3.21"
PK="$KN/klib/platform"
"$KN/bin/klib" dump-metadata "$PK/macos_arm64/org.jetbrains.kotlin.native.platform.OpenGL3" \
  > /tmp/reng-opengl3.txt
"$KN/bin/klib" dump-metadata "$PK/ios_arm64/org.jetbrains.kotlin.native.platform.OpenGLES3" \
  > /tmp/reng-gles3.txt
grep -oE 'fun gl[A-Za-z0-9]+' /tmp/reng-opengl3.txt | sed 's/^fun //' | sort -u > /tmp/reng-opengl3-names.txt
grep -oE 'fun gl[A-Za-z0-9]+' /tmp/reng-gles3.txt   | sed 's/^fun //' | sort -u > /tmp/reng-gles3-names.txt
grep -oE '"gl[A-Za-z0-9]+"' kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlEntryPoint.kt \
  | tr -d '"' | sort -u > /tmp/reng-roster.txt
wc -l /tmp/reng-roster.txt
comm -23 /tmp/reng-roster.txt /tmp/reng-opengl3-names.txt
comm -23 /tmp/reng-roster.txt /tmp/reng-gles3-names.txt
```

Expected: the roster holds 84 names and both `comm` invocations print nothing. The Kotlin/Native prebuilt directory name differs by host; use the one that exists under `~/.konan`. A name that appears only in one dump is a real finding — record it and remove that entry point from the roster and the seam rather than writing an implementation that cannot compile everywhere.

- [ ] **Step 4: Compile and commit**

```bash
./gradlew --no-configuration-cache :kmp:compileKotlinMacosArm64
git add kmp/src/macosMain/kotlin/com/rohittp/reng/internal/gl/MacosGlBinding.kt
git commit -m "feat: implement the GL seam on macOS"
```

---

### Task 5: The Linux Implementation over `dlopen` and `eglGetProcAddress`

**Files:**
- Create: `kmp/src/linuxMain/kotlin/com/rohittp/reng/internal/gl/LinuxGlBinding.kt`

**Interfaces:**
- Consumes: Task 1's roster, whose ordinal order indexes the resolved table; Task 2's seam and `GlBindingResult`.
- Produces: `internal class LinuxGlBinding` over a fully resolved eighty-four-entry function-pointer table, and the `linuxMain` actual of `openPlatformGlBinding()` serving `linuxX64` and `linuxArm64` from one file.

Kotlin/Native's Linux sysroots ship `dlfcn.h` and zero `GL/`, `GLES3/`, or `EGL/` headers on both targets, and `linux_x64` and `linux_arm64` each ship exactly five platform klibs — `builtin`, `iconv`, `linux`, `posix`, `zlib` — with no GL, GLES, or EGL klib at all. Runtime resolution is therefore the only option, and it needs no headers, cross-compiles from any host, and tolerates Mesa and proprietary drivers alike.

- [ ] **Step 1: Write the resolution-failure test**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxGlBindingResolutionTest {
    @Test fun everyRosterNameResolvesAgainstTheRealDispatchLibrary() {
        when (val result = openPlatformGlBinding()) {
            is GlBindingResult.Bound -> assertTrue(result.binding.getError() >= 0)
            is GlBindingResult.Unsupported -> throw AssertionError(
                "libEGL.so.1 must be installed for the GL conformance gate",
            )
        }
    }

    @Test fun anUnresolvableLibraryIsATypedRedactedSetupFailure() {
        val result = openLinuxGlBinding(libraryName = "libRenGDefinitelyMissing.so.99")
        val unsupported = result as GlBindingResult.Unsupported
        assertEquals(RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT, unsupported.failure.code)
        assertEquals(PipelineStage.CONTEXT_ADOPTION, unsupported.failure.stage)
        assertEquals(null, unsupported.failure.diagnostic)
        assertTrue("libRenGDefinitelyMissing" !in unsupported.failure.toString())
    }
}
```

This test file lives at `kmp/src/linuxTest/kotlin/com/rohittp/reng/internal/gl/LinuxGlBindingResolutionTest.kt`. The second case is the redaction gate: a missing entry point or an unloadable dispatch library must be a typed setup failure with no library path or symbol name anywhere in it, never a null-pointer call.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:linuxX64Test \
  --tests "com.rohittp.reng.internal.gl.LinuxGlBindingResolutionTest"
```

Expected: compilation fails because `openLinuxGlBinding` does not exist. On a macOS host this task's tests cannot run at all; compile with `:kmp:compileKotlinLinuxX64` and run the test on Linux or in continuous integration.

- [ ] **Step 3: Implement eager resolution**

```kotlin
@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import platform.posix.RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym

private const val EGL_DISPATCH_LIBRARY: String = "libEGL.so.1"

internal fun openLinuxGlBinding(
    libraryName: String = EGL_DISPATCH_LIBRARY,
): GlBindingResult {
    val library = dlopen(libraryName, RTLD_NOW) ?: return unsupportedRenderContext()
    val getProcAddress = dlsym(library, "eglGetProcAddress")
        ?.reinterpret<CFunction<(CPointer<ByteVar>?) -> COpaquePointer?>>()
        ?: return unsupportedRenderContext()

    val table = ArrayList<COpaquePointer>(GlEntryPoint.entries.size)
    for (entry in GlEntryPoint.entries) {
        val address = memScoped { getProcAddress(entry.cName.cstr.ptr) }
            ?: dlsym(library, entry.cName)
            ?: return unsupportedRenderContext()
        table += address
    }
    return GlBindingResult.Bound(LinuxGlBinding(table))
}

internal actual fun openPlatformGlBinding(): GlBindingResult = openLinuxGlBinding()
```

Resolution is eager and total: all eighty-four names resolve at setup or the whole binding is `Unsupported`, which turns a partially resolvable driver into a setup-time typed error instead of a null-pointer call on the first frame. `libEGL.so.1` is loaded rather than `libGLESv2.so.2` because on a glvnd-dispatched system `eglGetProcAddress` is the only resolver guaranteed to return the entry points belonging to the current context's vendor; `dlsym` against the dispatch library happens to work and is kept only as a fallback, not as the contract. RenG resolves entry points and never creates a context (ADR 0001).

- [ ] **Step 4: Implement the typed table and the seam methods**

```kotlin
internal class LinuxGlBinding(table: List<COpaquePointer>) : GlBinding {
    private val getErrorFn: CPointer<CFunction<() -> UInt>> =
        table[GlEntryPoint.GET_ERROR.ordinal].reinterpret()
    private val getStringFn: CPointer<CFunction<(UInt) -> CPointer<UByteVar>?>> =
        table[GlEntryPoint.GET_STRING.ordinal].reinterpret()
    private val getStringiFn: CPointer<CFunction<(UInt, UInt) -> CPointer<UByteVar>?>> =
        table[GlEntryPoint.GET_STRINGI.ordinal].reinterpret()
    private val getIntegervFn: CPointer<CFunction<(UInt, CPointer<IntVar>?) -> Unit>> =
        table[GlEntryPoint.GET_INTEGERV.ordinal].reinterpret()
    private val isEnabledFn: CPointer<CFunction<(UInt) -> UByte>> =
        table[GlEntryPoint.IS_ENABLED.ordinal].reinterpret()
    private val shaderSourceFn: CPointer<CFunction<
        (UInt, Int, CPointer<CPointerVar<ByteVar>>?, CPointer<IntVar>?) -> Unit>> =
        table[GlEntryPoint.SHADER_SOURCE.ordinal].reinterpret()
    private val bufferDataFn: CPointer<CFunction<(UInt, Long, COpaquePointer?, UInt) -> Unit>> =
        table[GlEntryPoint.BUFFER_DATA.ordinal].reinterpret()

    override fun getError(): Int = getErrorFn().toInt()

    override fun getString(name: Int): String? =
        getStringFn(name.toUInt())?.reinterpret<ByteVar>()?.toKString()

    override fun getStringi(name: Int, index: Int): String? =
        getStringiFn(name.toUInt(), index.toUInt())?.reinterpret<ByteVar>()?.toKString()

    override fun getIntegerv(pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an integer query needs a destination" }
        memScoped {
            val buffer = allocArray<IntVar>(out.size)
            getIntegervFn(pname.toUInt(), buffer)
            for (index in out.indices) out[index] = buffer[index]
        }
    }

    override fun isEnabled(cap: Int): Boolean = isEnabledFn(cap.toUInt()).toInt() != 0

    override fun shaderSource(shader: Int, source: String) {
        memScoped {
            val sources = allocArray<CPointerVar<ByteVar>>(1)
            sources[0] = source.cstr.ptr
            shaderSourceFn(shader.toUInt(), 1, sources, null)
        }
    }

    override fun bufferData(target: Int, size: Int, data: ByteArray?, usage: Int) {
        if (data == null || data.isEmpty()) {
            bufferDataFn(target.toUInt(), size.toLong(), null, usage.toUInt())
            return
        }
        data.usePinned { pinned ->
            bufferDataFn(target.toUInt(), size.toLong(), pinned.addressOf(0), usage.toUInt())
        }
    }
}
```

Declare the remaining seventy-seven properties the same way — one `private val <name>Fn: CPointer<CFunction<...>>` per roster entry, reinterpreted from `table[GlEntryPoint.<ENTRY>.ordinal]`, with the C signature written in Kotlin/Native terms: `GLenum`/`GLuint` as `UInt`, `GLint`/`GLsizei` as `Int`, `GLboolean` as `UByte`, `GLfloat` as `Float`, `GLsizeiptr` and `GLintptr` as `Long`, pointer parameters as `CPointer<IntVar>?`, `CPointer<UIntVar>?`, `CPointer<FloatVar>?`, `CPointer<UByteVar>?`, `CPointer<ByteVar>?`, or `COpaquePointer?`, and `void` returns as `Unit`. Then write the method bodies under Task 3's four mechanical rules, calling the pointer instead of the linked symbol. Function-pointer arity is not a constraint: Kotlin/Native supplies `invoke` overloads up to twenty-one parameters and this roster's worst case is `glBlitFramebuffer` at ten.

- [ ] **Step 5: Compile both Linux targets, run on Linux, and commit**

```bash
./gradlew --no-configuration-cache :kmp:compileKotlinLinuxX64 :kmp:compileKotlinLinuxArm64
```

On a Linux host, after `sudo apt-get update && sudo apt-get install -y --no-install-recommends libegl1 libegl-mesa0 libgles2`:

```bash
./gradlew --no-configuration-cache :kmp:linuxX64Test \
  --tests "com.rohittp.reng.internal.gl.LinuxGlBindingResolutionTest"
```

```bash
git add kmp/src/linuxMain/kotlin/com/rohittp/reng/internal/gl/LinuxGlBinding.kt \
  kmp/src/linuxTest/kotlin/com/rohittp/reng/internal/gl/LinuxGlBindingResolutionTest.kt
git commit -m "feat: implement the GL seam on Linux"
```

`linuxArm64` is verified as a cross-compile only; do not claim it executes.

---

### Task 6: The Android Implementation over `GLES30`

**Files:**
- Create: `kmp/src/androidMain/kotlin/com/rohittp/reng/internal/gl/AndroidGlBinding.kt`

**Interfaces:**
- Consumes: Task 2's seam.
- Produces: `internal object AndroidGlBinding : GlBinding` and the `androidMain` actual of `openPlatformGlBinding()`.

`android.opengl.GLES30 extends GLES20`, so one import reaches both, and every ES 3.0-only token this cycle needs for state restore — `GL_VERTEX_ARRAY_BINDING`, `GL_SAMPLER_BINDING`, `GL_DRAW_FRAMEBUFFER_BINDING`, `GL_READ_FRAMEBUFFER_BINDING`, `GL_UNPACK_ROW_LENGTH`, `GL_NUM_EXTENSIONS`, `GL_MAX_COLOR_ATTACHMENTS` — is on `GLES30` and absent from `GLES20`. The `platform.gles3` klibs under `$KONAN/klib/platform/android_*` belong to Kotlin/Native's `androidNative*` targets and are unreachable from RenG's AGP `android` target; do not import them.

- [ ] **Step 1: Implement the object**

Android is the side the seam was typed for, so most methods are one-line pass-throughs with an explicit `0` offset. The only marshalling is `java.nio.ByteBuffer.wrap` for the four payload-carrying calls.

```kotlin
package com.rohittp.reng.internal.gl

import android.opengl.GLES30
import java.nio.ByteBuffer

internal object AndroidGlBinding : GlBinding {
    override fun getError(): Int = GLES30.glGetError()

    override fun getString(name: Int): String? = GLES30.glGetString(name)

    override fun getStringi(name: Int, index: Int): String? = GLES30.glGetStringi(name, index)

    override fun getIntegerv(pname: Int, out: IntArray) {
        require(out.isNotEmpty()) { "an integer query needs a destination" }
        GLES30.glGetIntegerv(pname, out, 0)
    }

    override fun getFloatv(pname: Int, out: FloatArray) {
        require(out.isNotEmpty()) { "a float query needs a destination" }
        GLES30.glGetFloatv(pname, out, 0)
    }

    override fun getBooleanv(pname: Int, out: BooleanArray) {
        require(out.isNotEmpty()) { "a boolean query needs a destination" }
        GLES30.glGetBooleanv(pname, out, 0)
    }

    override fun isEnabled(cap: Int): Boolean = GLES30.glIsEnabled(cap)

    override fun genTextures(count: Int, out: IntArray) {
        require(out.size >= count) { "glGenTextures needs room for $count names" }
        if (count == 0) return
        GLES30.glGenTextures(count, out, 0)
    }

    override fun shaderSource(shader: Int, source: String) {
        GLES30.glShaderSource(shader, source)
    }

    override fun getShaderInfoLog(shader: Int): String = GLES30.glGetShaderInfoLog(shader)

    override fun bufferData(target: Int, size: Int, data: ByteArray?, usage: Int) {
        GLES30.glBufferData(target, size, data?.takeIf { it.isNotEmpty() }?.let(ByteBuffer::wrap), usage)
    }

    override fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: ByteArray?,
    ) {
        GLES30.glTexImage2D(
            target, level, internalFormat, width, height, border, format, type,
            pixels?.takeIf { it.isNotEmpty() }?.let(ByteBuffer::wrap),
        )
    }

    override fun readPixels(
        x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, out: ByteArray,
    ) {
        require(out.isNotEmpty()) { "a pixel read needs a destination" }
        GLES30.glReadPixels(x, y, width, height, format, type, ByteBuffer.wrap(out))
    }

    override fun vertexAttribPointer(
        index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int,
    ) {
        GLES30.glVertexAttribPointer(index, size, type, normalized, stride, offset)
    }
}

internal actual fun openPlatformGlBinding(): GlBindingResult = GlBindingResult.Bound(AndroidGlBinding)
```

Write the remaining methods as direct `GLES30.<name>(...)` calls, passing `0` as the trailing offset for every array-taking overload, wrapping the four payload parameters in `ByteBuffer.wrap`, and keeping the same `require` guards on output arrays. `glShaderSource` takes exactly one `String` with no count and no length array, which is why the seam does too.

- [ ] **Step 2: Compile the Android archive and run the host tests**

```bash
./gradlew --no-configuration-cache :kmp:bundleAndroidMainAar :kmp:testAndroidHostTest
```

Expected: both succeed. `androidHostTest` runs on the JVM against `android.jar` stubs that throw on every real call, so no host test may invoke `AndroidGlBinding`; Android's real-context coverage is Cycle H's, and this seam is exercised in continuous integration by the macOS and Linux implementations instead (ADR 0011).

- [ ] **Step 3: Commit**

```bash
git add kmp/src/androidMain/kotlin/com/rohittp/reng/internal/gl/AndroidGlBinding.kt
git commit -m "feat: implement the GL seam on Android"
```

---

### Task 7: Prove the Layout on Every Leaf

**Files:**
- Modify: none. This task compiles and verifies.

**Interfaces:**
- Consumes: Tasks 3–6.
- Produces: recorded evidence that all six leaf compilations and the shared-source-set metadata compilation accept the layout, and that no GL type reached the public ABI.

- [ ] **Step 1: Compile every leaf plus the shared metadata compilation**

```bash
./gradlew --no-configuration-cache \
  :kmp:compileKotlinIosArm64 \
  :kmp:compileKotlinIosSimulatorArm64 \
  :kmp:compileKotlinMacosArm64 \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar \
  :kmp:compileIosMainKotlinMetadata
```

Expected: all seven succeed. `compileIosMainKotlinMetadata` matters because the shared-source-set metadata compilation resolves against commonized cinterop libraries and is the compilation most likely to reject platform GL. A partial compile can look green — platform-library resolution is enforced per leaf, not by pre-computing an intersection — so the layout is trusted only after every one of these passes.

- [ ] **Step 2: Confirm the seam produced no public ABI**

```bash
./gradlew --no-configuration-cache :kmp:checkKotlinAbi
shasum -a 256 kmp/api/kmp.klib.api
diff <(shasum -a 256 kmp/api/kmp.klib.api) /tmp/reng-cycle-d-abi-baseline.txt
! grep -nE 'platform\.|kotlinx\.cinterop|GLES30|GlBinding' kmp/api/kmp.klib.api
```

Expected: `checkKotlinAbi` passes, the digest matches Task 0's baseline exactly, and the grep finds nothing. `@OptIn(ExperimentalForeignApi::class)` is harmless while the seam is `internal` and must never reach public API; this step is what makes that a check rather than an intention.

- [ ] **Step 3: Commit the evidence in the message only**

```bash
test -z "$(git status --porcelain)"
```

Expected: nothing to commit. This task changes no file; if it reports changes, a previous task left work uncommitted.

---

### Task 8: Context Adoption and Runtime Dialect Detection

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/RenderContextProfile.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/RenderContextProfileTest.kt`

**Interfaces:**
- Consumes: Task 1 tokens, Task 2 seam, Task 9's `GlErrorQueue` (land Task 9 first or stub the drain call and fill it in when Task 9 merges).
- Produces: `ShaderDialect`, `GlVersion`, `RenderContextProfile`, `RenderContextAdoption`, `detectShaderDialect`, `parseGlVersion`, `readExtensionNames`, and `adoptRenderContext`.

> **The dialect is a runtime property of the adopted context and never a property of the target.** This is the specification's single most important statement about shaders. On `linuxX64` and `linuxArm64` the consumer creates the context and may reasonably create either an ES 3.x context or a desktop core context — an EGL/Wayland application does the former, a GLX application the latter, from the same binary on the same target. Keying substitution off the platform would inject `#version 330 core` into an ES context, which is fatal, and that is the more likely Linux case. No `expect`/`actual`, no `Platform.osFamily`, no target-conditional compilation, and no build flag may reach this decision. The only input is `GL_SHADING_LANGUAGE_VERSION` read from the live context.

- [ ] **Step 1: Write the detection tests against the three measured contexts**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenderContextProfileTest {
    @Test fun theEsPrefixIsTheOnlyDialectSignal() {
        assertEquals(ShaderDialect.GLES, detectShaderDialect("OpenGL ES GLSL ES 3.20"))
        assertEquals(ShaderDialect.GLES, detectShaderDialect("OpenGL ES GLSL ES 3.00"))
        assertEquals(ShaderDialect.DESKTOP, detectShaderDialect("4.50"))
        assertEquals(ShaderDialect.DESKTOP, detectShaderDialect("4.10"))
        assertEquals(ShaderDialect.DESKTOP, detectShaderDialect("3.30 NVIDIA via Cg compiler"))
    }

    @Test fun versionsParseFromTheThreeMeasuredVersionStrings() {
        assertEquals(
            GlVersion(3, 2),
            parseGlVersion("OpenGL ES 3.2 Mesa 25.2.8-0ubuntu0.24.04.2", ShaderDialect.GLES),
        )
        assertEquals(
            GlVersion(4, 5),
            parseGlVersion("4.5 (Core Profile) Mesa 25.2.8-0ubuntu0.24.04.2", ShaderDialect.DESKTOP),
        )
        assertEquals(GlVersion(4, 1), parseGlVersion("4.1 APPLE-23.1.1", ShaderDialect.DESKTOP))
        assertEquals(GlVersion(4, 1), parseGlVersion("4.1 Metal - 90.5", ShaderDialect.DESKTOP))
        assertEquals(GlVersion(3, 3), parseGlVersion("3.3.0 NVIDIA 550.54", ShaderDialect.DESKTOP))
    }

    @Test fun malformedOrMismatchedVersionTextIsRejectedRatherThanGuessed() {
        assertNull(parseGlVersion("4.5 (Core Profile)", ShaderDialect.GLES))
        assertNull(parseGlVersion("OpenGL ES 3.2", ShaderDialect.DESKTOP))
        assertNull(parseGlVersion("", ShaderDialect.DESKTOP))
        assertNull(parseGlVersion("4", ShaderDialect.DESKTOP))
        assertNull(parseGlVersion("4.", ShaderDialect.DESKTOP))
        assertNull(parseGlVersion("x.y", ShaderDialect.DESKTOP))
    }

    @Test fun anEsThreeContextIsAdoptedWithItsMeasuredCapabilities() {
        val binding = esContextBinding()
        val adoption = adoptRenderContext(binding) as RenderContextAdoption.Adopted
        assertEquals(ShaderDialect.GLES, adoption.profile.dialect)
        assertEquals(GlVersion(3, 2), adoption.profile.version)
        assertEquals("llvmpipe (LLVM 20.1.2, 256 bits)", adoption.profile.rendererName)
        assertTrue(adoption.profile.supportsSrgbWriteControl)
        assertTrue(!adoption.profile.supportsEs3Compatibility)
        assertEquals(16384, adoption.profile.maxTextureSize)
    }

    @Test fun aDesktopCoreContextIsAdoptedAndAdvertisesEsCompatibility() {
        val binding = desktopContextBinding()
        val adoption = adoptRenderContext(binding) as RenderContextAdoption.Adopted
        assertEquals(ShaderDialect.DESKTOP, adoption.profile.dialect)
        assertEquals(GlVersion(4, 5), adoption.profile.version)
        assertTrue(adoption.profile.supportsEs3Compatibility)
        assertTrue(adoption.profile.supportsSrgbWriteControl)
    }

    @Test fun aContextBelowTheRequirementIsRejectedWithoutModifyingState() {
        val binding = RecordingGlBinding()
        binding.strings[GL_SHADING_LANGUAGE_VERSION] = "1.20"
        binding.strings[GL_VERSION] = "2.1 INTEL-16.4.5"
        binding.strings[GL_RENDERER] = "Intel HD Graphics"
        binding.strings[GL_VENDOR] = "Intel Inc."
        val rejection = adoptRenderContext(binding) as RenderContextAdoption.Rejected
        assertEquals(RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT, rejection.failure.code)
        assertEquals(PipelineStage.CONTEXT_ADOPTION, rejection.failure.stage)
        assertNull(rejection.failure.diagnostic)
        assertTrue(binding.log.none { it.startsWith("enable") || it.startsWith("bind") })
    }

    @Test fun anEsContextBelowThreePointZeroIsRejected() {
        val binding = RecordingGlBinding()
        binding.strings[GL_SHADING_LANGUAGE_VERSION] = "OpenGL ES GLSL ES 1.00"
        binding.strings[GL_VERSION] = "OpenGL ES 2.0 Mesa"
        binding.strings[GL_RENDERER] = "llvmpipe"
        binding.strings[GL_VENDOR] = "Mesa"
        assertTrue(adoptRenderContext(binding) is RenderContextAdoption.Rejected)
    }

    @Test fun extensionsAreReadThroughGetStringiAndNeverThroughGetString() {
        val binding = desktopContextBinding()
        adoptRenderContext(binding)
        assertTrue(binding.log.none { it == "getString(0x1F03)" })
        assertTrue(binding.log.any { it.startsWith("getStringi(0x1F03") })
    }

    private fun esContextBinding(): RecordingGlBinding = RecordingGlBinding().apply {
        strings[GL_SHADING_LANGUAGE_VERSION] = "OpenGL ES GLSL ES 3.20"
        strings[GL_VERSION] = "OpenGL ES 3.2 Mesa 25.2.8-0ubuntu0.24.04.2"
        strings[GL_RENDERER] = "llvmpipe (LLVM 20.1.2, 256 bits)"
        strings[GL_VENDOR] = "Mesa"
        indexedStrings += listOf("GL_EXT_sRGB_write_control", "GL_OES_texture_float")
        integers[GL_NUM_EXTENSIONS] = intArrayOf(2)
        integers[GL_MAX_TEXTURE_SIZE] = intArrayOf(16384)
        integers[GL_MAX_COLOR_ATTACHMENTS] = intArrayOf(8)
        integers[GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS] = intArrayOf(192)
    }

    private fun desktopContextBinding(): RecordingGlBinding = RecordingGlBinding().apply {
        strings[GL_SHADING_LANGUAGE_VERSION] = "4.50"
        strings[GL_VERSION] = "4.5 (Core Profile) Mesa 25.2.8-0ubuntu0.24.04.2"
        strings[GL_RENDERER] = "llvmpipe (LLVM 20.1.2, 256 bits)"
        strings[GL_VENDOR] = "Mesa"
        indexedStrings += listOf("GL_ARB_ES3_compatibility", "GL_ARB_texture_storage")
        integers[GL_NUM_EXTENSIONS] = intArrayOf(2)
        integers[GL_MAX_TEXTURE_SIZE] = intArrayOf(16384)
        integers[GL_MAX_COLOR_ATTACHMENTS] = intArrayOf(8)
        integers[GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS] = intArrayOf(192)
    }
}
```

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.RenderContextProfileTest"
```

Expected: compilation fails because none of the profile types exist.

- [ ] **Step 3: Implement dialect detection and version parsing**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.failure.FailureDescriptor

internal enum class ShaderDialect {
    GLES,
    DESKTOP,
}

internal data class GlVersion(val major: Int, val minor: Int) : Comparable<GlVersion> {
    override fun compareTo(other: GlVersion): Int =
        if (major != other.major) major.compareTo(other.major) else minor.compareTo(other.minor)
}

internal const val ES_SHADING_LANGUAGE_PREFIX: String = "OpenGL ES GLSL ES"
internal const val ES_VERSION_PREFIX: String = "OpenGL ES "
internal val MINIMUM_GLES_VERSION: GlVersion = GlVersion(3, 0)
internal val MINIMUM_DESKTOP_VERSION: GlVersion = GlVersion(3, 3)
internal const val ES3_COMPATIBILITY_EXTENSION: String = "GL_ARB_ES3_compatibility"
internal const val SRGB_WRITE_CONTROL_EXTENSION: String = "GL_EXT_sRGB_write_control"

internal fun detectShaderDialect(shadingLanguageVersion: String): ShaderDialect =
    if (shadingLanguageVersion.startsWith(ES_SHADING_LANGUAGE_PREFIX)) {
        ShaderDialect.GLES
    } else {
        ShaderDialect.DESKTOP
    }

internal fun parseGlVersion(versionText: String, dialect: ShaderDialect): GlVersion? {
    val body = when (dialect) {
        ShaderDialect.GLES ->
            if (versionText.startsWith(ES_VERSION_PREFIX)) {
                versionText.substring(ES_VERSION_PREFIX.length)
            } else {
                return null
            }

        ShaderDialect.DESKTOP ->
            if (versionText.startsWith(ES_VERSION_PREFIX)) return null else versionText
    }

    var index = 0
    var major = 0
    var majorDigits = 0
    while (index < body.length && body[index] in '0'..'9') {
        if (majorDigits == MAXIMUM_VERSION_DIGITS) return null
        major = major * 10 + (body[index] - '0')
        majorDigits += 1
        index += 1
    }
    if (majorDigits == 0 || index >= body.length || body[index] != '.') return null
    index += 1

    var minor = 0
    var minorDigits = 0
    while (index < body.length && body[index] in '0'..'9') {
        if (minorDigits == MAXIMUM_VERSION_DIGITS) return null
        minor = minor * 10 + (body[index] - '0')
        minorDigits += 1
        index += 1
    }
    if (minorDigits == 0) return null
    return GlVersion(major, minor)
}

private const val MAXIMUM_VERSION_DIGITS: Int = 4
```

The version numbers are parsed from `GL_VERSION` rather than read from `GL_MAJOR_VERSION`, because those integer queries raise `GL_INVALID_ENUM` on exactly the pre-3.0 contexts that must be rejected, and a rejection path that itself provokes an error flag is worse than a string parse. Mesa answers both requested versions upward — an ES 3.0 request yields ES 3.2 and a 3.3 core request yields 4.5 core — so this reads what the context is, never what anyone asked for.

- [ ] **Step 4: Implement extension reading and adoption**

```kotlin
internal data class RenderContextProfile(
    val dialect: ShaderDialect,
    val version: GlVersion,
    val vendorName: String,
    val rendererName: String,
    val shadingLanguageVersionText: String,
    val supportsEs3Compatibility: Boolean,
    val supportsSrgbWriteControl: Boolean,
    val maxTextureSize: Int,
    val maxColorAttachments: Int,
    val maxCombinedTextureImageUnits: Int,
)

internal sealed interface RenderContextAdoption {
    data class Adopted(val profile: RenderContextProfile) : RenderContextAdoption

    data class Rejected(val failure: FailureDescriptor) : RenderContextAdoption
}

internal fun readExtensionNames(binding: GlBinding): Set<String> {
    val count = IntArray(1)
    binding.getIntegerv(GL_NUM_EXTENSIONS, count)
    if (GlErrorQueue.firstOwnError(binding) != GL_NO_ERROR || count[0] <= 0) return emptySet()
    val names = LinkedHashSet<String>()
    for (index in 0 until count[0]) {
        val name = binding.getStringi(GL_EXTENSIONS, index) ?: continue
        names += name
    }
    return names
}

internal fun adoptRenderContext(binding: GlBinding): RenderContextAdoption {
    GlErrorQueue.drainOnEntry(binding)

    val shadingLanguageVersionText = binding.getString(GL_SHADING_LANGUAGE_VERSION)
        ?: return rejectedRenderContext()
    val versionText = binding.getString(GL_VERSION) ?: return rejectedRenderContext()
    val dialect = detectShaderDialect(shadingLanguageVersionText)
    val version = parseGlVersion(versionText, dialect) ?: return rejectedRenderContext()
    val minimum = when (dialect) {
        ShaderDialect.GLES -> MINIMUM_GLES_VERSION
        ShaderDialect.DESKTOP -> MINIMUM_DESKTOP_VERSION
    }
    if (version < minimum) return rejectedRenderContext()

    val extensions = readExtensionNames(binding)
    val limits = IntArray(1)
    binding.getIntegerv(GL_MAX_TEXTURE_SIZE, limits)
    val maxTextureSize = limits[0]
    binding.getIntegerv(GL_MAX_COLOR_ATTACHMENTS, limits)
    val maxColorAttachments = limits[0]
    binding.getIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, limits)
    val maxCombinedTextureImageUnits = limits[0]
    if (GlErrorQueue.firstOwnError(binding) != GL_NO_ERROR) return rejectedRenderContext()

    return RenderContextAdoption.Adopted(
        RenderContextProfile(
            dialect = dialect,
            version = version,
            vendorName = binding.getString(GL_VENDOR).orEmpty(),
            rendererName = binding.getString(GL_RENDERER).orEmpty(),
            shadingLanguageVersionText = shadingLanguageVersionText,
            supportsEs3Compatibility = ES3_COMPATIBILITY_EXTENSION in extensions,
            supportsSrgbWriteControl = when (dialect) {
                ShaderDialect.DESKTOP -> true
                ShaderDialect.GLES -> SRGB_WRITE_CONTROL_EXTENSION in extensions
            },
            maxTextureSize = maxTextureSize,
            maxColorAttachments = maxColorAttachments,
            maxCombinedTextureImageUnits = maxCombinedTextureImageUnits,
        ),
    )
}

private fun rejectedRenderContext(): RenderContextAdoption.Rejected =
    RenderContextAdoption.Rejected(
        FailureDescriptor(
            code = RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT,
            stage = PipelineStage.CONTEXT_ADOPTION,
        ),
    )
```

Three properties of this function are load-bearing. It issues only `glGetString`, `glGetStringi`, and `glGetIntegerv`, so a rejected context is left exactly as it was found — no binding, enable, or parameter is touched on any path. Extensions are read through `glGetIntegerv(GL_NUM_EXTENSIONS)` plus `glGetStringi`, never through `glGetString(GL_EXTENSIONS)`, which returns `NULL` with `GL_INVALID_ENUM` on a desktop core profile. And `supportsEs3Compatibility` is recorded but **never consulted by the substitution decision**: ADR 0008 substitutes on every desktop context, which Mesa's 4.5 core profile makes harmless and Apple's 4.1 makes necessary, and probing the extension to skip substitution would be strictly more complex for no behavioural gain. The flag exists so the conformance suite can state the correct expectation for an unsubstituted source on a desktop driver that advertises it.

- [ ] **Step 5: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.RenderContextProfileTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.gl.RenderContextProfileTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/RenderContextProfile.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/RenderContextProfileTest.kt
git commit -m "feat: detect the render context dialect at runtime"
```

---

### Task 9: The Error Queue, and Its One Declared Exception

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlErrorQueue.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlErrorQueueTest.kt`

**Interfaces:**
- Consumes: Task 1 tokens, Task 2 seam.
- Produces: `GlErrorQueue.drainOnEntry`, `GlErrorQueue.firstOwnError`, and `glOperationFailure`.

`glGetError` is destructive: a provoked error reads `0x500` once and `0x0` thereafter, and there is no way to push a flag back. RenG drains on entry, treats any flag found as the consumer's rather than its own, and **documents that it consumes the error queue**. That is a real, stated exception to ADR 0006's guarantee that RenG modifies nothing outside the restore set — one to declare rather than let a consumer discover — and Task 21 writes it down where a consumer will read it.

- [ ] **Step 1: Write the drain tests**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlErrorQueueTest {
    @Test fun theQueueIsDrainedCompletelyAndTheFirstFlagIsReturned() {
        val binding = RecordingGlBinding()
        binding.errorQueue = mutableListOf(GL_INVALID_ENUM, GL_INVALID_VALUE, GL_NO_ERROR)
        assertEquals(GL_INVALID_ENUM, GlErrorQueue.drainOnEntry(binding))
        assertEquals(GL_NO_ERROR, GlErrorQueue.drainOnEntry(binding))
    }

    @Test fun aCleanQueueReportsNoError() {
        assertEquals(GL_NO_ERROR, GlErrorQueue.drainOnEntry(RecordingGlBinding()))
    }

    @Test fun aDriverStuckInErrorCannotSpinTheDrainForever() {
        val binding = object : GlBinding by RecordingGlBinding() {
            override fun getError(): Int = GL_OUT_OF_MEMORY
        }
        assertEquals(GL_OUT_OF_MEMORY, GlErrorQueue.drainOnEntry(binding))
    }

    @Test fun rengOwnFailuresCarryARedactedGpuDiagnostic() {
        val failure = glOperationFailure(PipelineStage.DRAW, resourceKey = null)
        assertEquals(RenGErrorCode.GPU_OPERATION_FAILED, failure.code)
        assertEquals(PipelineStage.DRAW, failure.stage)
        val diagnostic = assertNotNull(failure.diagnostic)
        assertEquals(null, diagnostic.fieldName)
        assertEquals(null, diagnostic.resourceKey)
        assertTrue("0x" !in failure.toString())
    }
}
```

The third case needs the bounded loop: a driver that answers every `glGetError` with the same flag must not hang a render call.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.GlErrorQueueTest"
```

Expected: compilation fails because `GlErrorQueue` does not exist.

- [ ] **Step 3: Implement the drain and the typed GL failure**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic

internal object GlErrorQueue {
    internal const val MAXIMUM_DRAIN_ITERATIONS: Int = 32

    /**
     * Drains flags left by the consumer before RenG's work begins and returns the first one found.
     *
     * RenG consumes the caller's GL error queue. `glGetError` is destructive and no flag can be
     * pushed back, so this is a declared exception to the restore guarantee rather than an
     * oversight. A flag found here belongs to the consumer and is never converted into a RenG
     * failure.
     */
    internal fun drainOnEntry(binding: GlBinding): Int = drain(binding)

    /** Drains flags provoked by RenG's own work; a non-zero result is RenG's failure to report. */
    internal fun firstOwnError(binding: GlBinding): Int = drain(binding)

    private fun drain(binding: GlBinding): Int {
        var first = GL_NO_ERROR
        var iterations = 0
        while (iterations < MAXIMUM_DRAIN_ITERATIONS) {
            val flag = binding.getError()
            if (flag == GL_NO_ERROR) break
            if (first == GL_NO_ERROR) first = flag
            iterations += 1
        }
        return first
    }
}

internal fun glOperationFailure(
    stage: PipelineStage,
    resourceKey: ResourceKey?,
): FailureDescriptor = FailureDescriptor(
    code = RenGErrorCode.GPU_OPERATION_FAILED,
    stage = stage,
    diagnostic = failureContextDiagnostic(
        stage = stage,
        resourceClass = resourceKey?.resourceClass,
        resourceKey = resourceKey,
    ),
)
```

The two public names are one behaviour and two attributions on purpose: the call site says which side of the boundary a flag belongs to, and neither the flag value nor any driver text reaches the returned `FailureDescriptor`, whose diagnostic carries only the pipeline stage and an optional established resource identity.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.GlErrorQueueTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.gl.GlErrorQueueTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlErrorQueue.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlErrorQueueTest.kt
git commit -m "feat: drain the GL error queue on entry"
```

---

### Task 10: The Corrected Save-and-Restore Set

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlStateSnapshot.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlStateSnapshotTest.kt`

**Interfaces:**
- Consumes: Task 1 tokens, Task 2 seam, Task 8's `RenderContextProfile` and `ShaderDialect`.
- Produces: `GlTextureUnitState`, `GlStateSnapshot`, `captureGlState`, and `restoreGlState`.

Two ordering and portability constraints are normative rather than advisory. Reading a texture binding requires making its unit active, so `GL_ACTIVE_TEXTURE` is captured **first** and reinstated **last**, with every per-unit read and write nested inside. And `GL_DRAW_BUFFER` and `GL_LINE_SMOOTH` are queryable on a desktop core profile but raise `GL_INVALID_ENUM` on ES, so this code is dialect-aware; an unconditional query list would leave a spurious error flag on ES. The **array** buffer binding is captured explicitly because the VAO does not capture it, while the **element** array buffer binding needs no restore because it is per-VAO state restored implicitly by the VAO binding.

- [ ] **Step 1: Write the ordering, gating, and round-trip tests**

```kotlin
package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlStateSnapshotTest {
    @Test fun activeTextureIsReadFirstAndReinstatedAfterThePerUnitLoop() {
        val binding = populatedBinding()
        captureGlState(binding, esProfile(), textureUnitCount = 2)
        val activeReads = binding.log.indexOfFirst { it == "getIntegerv(0x84E0)" }
        val firstUnitSwitch = binding.log.indexOfFirst { it == "activeTexture(0x84C0)" }
        val firstBindingRead = binding.log.indexOfFirst { it == "getIntegerv(0x8069)" }
        assertEquals(0, activeReads)
        assertTrue(activeReads < firstUnitSwitch)
        assertTrue(firstUnitSwitch < firstBindingRead)
        assertEquals("activeTexture(0x84C3)", binding.log.last { it.startsWith("activeTexture") })
    }

    @Test fun restoreReinstatesTheActiveUnitLast() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, esProfile(), textureUnitCount = 2)
        binding.log.clear()
        restoreGlState(binding, snapshot)
        assertEquals("activeTexture(0x84C3)", binding.log.last())
    }

    @Test fun theElementArrayBufferBindingIsNeverQueried() {
        val binding = populatedBinding()
        captureGlState(binding, esProfile(), textureUnitCount = 1)
        assertTrue(binding.log.none { it == "getIntegerv(0x8895)" })
        assertTrue(binding.log.any { it == "getIntegerv(0x8894)" })
    }

    @Test fun desktopOnlyTokensAreQueriedOnlyOnADesktopContext() {
        val esBinding = populatedBinding()
        val esSnapshot = captureGlState(esBinding, esProfile(), textureUnitCount = 1)
        assertNull(esSnapshot.drawBuffer)
        assertNull(esSnapshot.lineSmoothEnabled)
        assertTrue(esBinding.log.none { it == "getIntegerv(0x0C01)" })
        assertTrue(esBinding.log.none { it == "isEnabled(0xB20)" })

        val desktopBinding = populatedBinding()
        val desktopSnapshot = captureGlState(desktopBinding, desktopProfile(), textureUnitCount = 1)
        assertEquals(GL_BACK, desktopSnapshot.drawBuffer)
        assertEquals(false, desktopSnapshot.lineSmoothEnabled)
    }

    @Test fun theUnpackAlignmentDefaultIsFourNotOne() {
        val binding = populatedBinding()
        val snapshot = captureGlState(binding, esProfile(), textureUnitCount = 1)
        assertEquals(4, snapshot.unpackAlignment)
        assertEquals(4, snapshot.packAlignment)
        assertEquals(GL_UNPACK_ALIGNMENT_DEFAULT, snapshot.unpackAlignment)
    }

    @Test fun captureRestoreCaptureIsIdenticalOnTheFake() {
        val binding = populatedBinding()
        val first = captureGlState(binding, desktopProfile(), textureUnitCount = 3)
        restoreGlState(binding, first)
        val second = captureGlState(binding, desktopProfile(), textureUnitCount = 3)
        assertEquals(first, second)
    }

    @Test fun theSnapshotCoversEverySetMemberTheSpecificationNames() {
        val snapshot = captureGlState(populatedBinding(), desktopProfile(), textureUnitCount = 1)
        assertEquals(listOf(0f, 0f, 0f, 0f), snapshot.blendColour)
        assertEquals(listOf(0f, 1f), snapshot.depthRange)
        assertEquals(listOf(0, 0, 64, 64), snapshot.viewport)
        assertEquals(listOf(0, 0, 64, 64), snapshot.scissorBox)
        assertEquals(listOf(true, true, true, true), snapshot.colourWriteMask)
        assertEquals(listOf(0f, 0f, 0f, 0f), snapshot.colourClearValue)
        assertEquals(1f, snapshot.depthClearValue)
    }
}
```

Add `populatedBinding()`, `esProfile()`, and `desktopProfile()` as private helpers in the same file: the binding seeds `integers`, `floats`, `booleans`, and `enabled` for every token the capture reads, with the active texture unit at `GL_TEXTURE0 + 3` so the reinstatement assertions are not testing zero against zero; the two profile helpers return `RenderContextProfile` values differing only in `dialect` and `supportsSrgbWriteControl`.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.GlStateSnapshotTest"
```

Expected: compilation fails because the snapshot type does not exist.

- [ ] **Step 3: Declare the snapshot**

Every array-valued member is a `List`, so `GlStateSnapshot` gets structural equality for free and "byte-exact round trip" becomes a single `assertEquals` rather than a hand-written comparison. `Float` list equality is bit-based, which is stricter than `==` on raw floats and exactly what byte-exactness means here.

```kotlin
package com.rohittp.reng.internal.gl

internal data class GlTextureUnitState(
    val unit: Int,
    val texture2d: Int,
    val sampler: Int,
)

internal data class GlStateSnapshot(
    val activeTextureUnit: Int,
    val textureUnits: List<GlTextureUnitState>,
    val drawFramebuffer: Int,
    val readFramebuffer: Int,
    val renderbuffer: Int,
    val program: Int,
    val vertexArray: Int,
    val arrayBuffer: Int,
    val pixelUnpackBuffer: Int,
    val uniformBuffer: Int,
    val blendEnabled: Boolean,
    val blendSourceRgb: Int,
    val blendDestinationRgb: Int,
    val blendSourceAlpha: Int,
    val blendDestinationAlpha: Int,
    val blendEquationRgb: Int,
    val blendEquationAlpha: Int,
    val blendColour: List<Float>,
    val depthTestEnabled: Boolean,
    val depthFunction: Int,
    val depthWriteMask: Boolean,
    val depthRange: List<Float>,
    val depthClearValue: Float,
    val cullEnabled: Boolean,
    val cullMode: Int,
    val frontFace: Int,
    val viewport: List<Int>,
    val scissorEnabled: Boolean,
    val scissorBox: List<Int>,
    val colourWriteMask: List<Boolean>,
    val colourClearValue: List<Float>,
    val unpackAlignment: Int,
    val unpackRowLength: Int,
    val unpackSkipRows: Int,
    val unpackSkipPixels: Int,
    val packAlignment: Int,
    val framebufferSrgbEnabled: Boolean?,
    val drawBuffer: Int?,
    val lineSmoothEnabled: Boolean?,
)
```

The three nullable members are the dialect-gated ones: `framebufferSrgbEnabled` is `null` when the context is ES without `GL_EXT_sRGB_write_control`, and `drawBuffer`/`lineSmoothEnabled` are `null` on every ES context. `null` means "never queried and never restored", not "queried and found unset".

- [ ] **Step 4: Implement capture**

```kotlin
internal fun captureGlState(
    binding: GlBinding,
    profile: RenderContextProfile,
    textureUnitCount: Int,
): GlStateSnapshot {
    require(textureUnitCount > 0) { "at least one texture unit is captured" }

    val activeTextureUnit = binding.integer(GL_ACTIVE_TEXTURE)
    val units = ArrayList<GlTextureUnitState>(textureUnitCount)
    for (index in 0 until textureUnitCount) {
        binding.activeTexture(GL_TEXTURE0 + index)
        units += GlTextureUnitState(
            unit = GL_TEXTURE0 + index,
            texture2d = binding.integer(GL_TEXTURE_BINDING_2D),
            sampler = binding.integer(GL_SAMPLER_BINDING),
        )
    }
    binding.activeTexture(activeTextureUnit)

    val desktop = profile.dialect == ShaderDialect.DESKTOP
    return GlStateSnapshot(
        activeTextureUnit = activeTextureUnit,
        textureUnits = units,
        drawFramebuffer = binding.integer(GL_DRAW_FRAMEBUFFER_BINDING),
        readFramebuffer = binding.integer(GL_READ_FRAMEBUFFER_BINDING),
        renderbuffer = binding.integer(GL_RENDERBUFFER_BINDING),
        program = binding.integer(GL_CURRENT_PROGRAM),
        vertexArray = binding.integer(GL_VERTEX_ARRAY_BINDING),
        arrayBuffer = binding.integer(GL_ARRAY_BUFFER_BINDING),
        pixelUnpackBuffer = binding.integer(GL_PIXEL_UNPACK_BUFFER_BINDING),
        uniformBuffer = binding.integer(GL_UNIFORM_BUFFER_BINDING),
        blendEnabled = binding.isEnabled(GL_BLEND),
        blendSourceRgb = binding.integer(GL_BLEND_SRC_RGB),
        blendDestinationRgb = binding.integer(GL_BLEND_DST_RGB),
        blendSourceAlpha = binding.integer(GL_BLEND_SRC_ALPHA),
        blendDestinationAlpha = binding.integer(GL_BLEND_DST_ALPHA),
        blendEquationRgb = binding.integer(GL_BLEND_EQUATION_RGB),
        blendEquationAlpha = binding.integer(GL_BLEND_EQUATION_ALPHA),
        blendColour = binding.floats(GL_BLEND_COLOR, 4),
        depthTestEnabled = binding.isEnabled(GL_DEPTH_TEST),
        depthFunction = binding.integer(GL_DEPTH_FUNC),
        depthWriteMask = binding.booleans(GL_DEPTH_WRITEMASK, 1).single(),
        depthRange = binding.floats(GL_DEPTH_RANGE, 2),
        depthClearValue = binding.floats(GL_DEPTH_CLEAR_VALUE, 1).single(),
        cullEnabled = binding.isEnabled(GL_CULL_FACE),
        cullMode = binding.integer(GL_CULL_FACE_MODE),
        frontFace = binding.integer(GL_FRONT_FACE),
        viewport = binding.integers(GL_VIEWPORT, 4),
        scissorEnabled = binding.isEnabled(GL_SCISSOR_TEST),
        scissorBox = binding.integers(GL_SCISSOR_BOX, 4),
        colourWriteMask = binding.booleans(GL_COLOR_WRITEMASK, 4),
        colourClearValue = binding.floats(GL_COLOR_CLEAR_VALUE, 4),
        unpackAlignment = binding.integer(GL_UNPACK_ALIGNMENT),
        unpackRowLength = binding.integer(GL_UNPACK_ROW_LENGTH),
        unpackSkipRows = binding.integer(GL_UNPACK_SKIP_ROWS),
        unpackSkipPixels = binding.integer(GL_UNPACK_SKIP_PIXELS),
        packAlignment = binding.integer(GL_PACK_ALIGNMENT),
        framebufferSrgbEnabled =
            if (profile.supportsSrgbWriteControl) binding.isEnabled(GL_FRAMEBUFFER_SRGB) else null,
        drawBuffer = if (desktop) binding.integer(GL_DRAW_BUFFER) else null,
        lineSmoothEnabled = if (desktop) binding.isEnabled(GL_LINE_SMOOTH) else null,
    )
}

private fun GlBinding.integer(pname: Int): Int = integers(pname, 1).single()

private fun GlBinding.integers(pname: Int, count: Int): List<Int> {
    val out = IntArray(count)
    getIntegerv(pname, out)
    return out.toList()
}

private fun GlBinding.floats(pname: Int, count: Int): List<Float> {
    val out = FloatArray(count)
    getFloatv(pname, out)
    return out.toList()
}

private fun GlBinding.booleans(pname: Int, count: Int): List<Boolean> {
    val out = BooleanArray(count)
    getBooleanv(pname, out)
    return out.toList()
}
```

Capture is itself non-mutating: the per-unit loop changes the active unit and the line after the loop puts it back, so a capture that is never followed by a restore still leaves the context as it was found.

- [ ] **Step 5: Implement restore**

```kotlin
internal fun restoreGlState(binding: GlBinding, snapshot: GlStateSnapshot) {
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, snapshot.drawFramebuffer)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, snapshot.readFramebuffer)
    binding.bindRenderbuffer(GL_RENDERBUFFER, snapshot.renderbuffer)
    binding.useProgram(snapshot.program)
    binding.bindVertexArray(snapshot.vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, snapshot.arrayBuffer)
    binding.bindBuffer(GL_PIXEL_UNPACK_BUFFER, snapshot.pixelUnpackBuffer)
    binding.bindBuffer(GL_UNIFORM_BUFFER, snapshot.uniformBuffer)

    binding.setEnabled(GL_BLEND, snapshot.blendEnabled)
    binding.blendFuncSeparate(
        snapshot.blendSourceRgb,
        snapshot.blendDestinationRgb,
        snapshot.blendSourceAlpha,
        snapshot.blendDestinationAlpha,
    )
    binding.blendEquationSeparate(snapshot.blendEquationRgb, snapshot.blendEquationAlpha)
    binding.blendColor(
        snapshot.blendColour[0],
        snapshot.blendColour[1],
        snapshot.blendColour[2],
        snapshot.blendColour[3],
    )

    binding.setEnabled(GL_DEPTH_TEST, snapshot.depthTestEnabled)
    binding.depthFunc(snapshot.depthFunction)
    binding.depthMask(snapshot.depthWriteMask)
    binding.depthRangef(snapshot.depthRange[0], snapshot.depthRange[1])
    binding.clearDepthf(snapshot.depthClearValue)

    binding.setEnabled(GL_CULL_FACE, snapshot.cullEnabled)
    binding.cullFace(snapshot.cullMode)
    binding.frontFace(snapshot.frontFace)

    binding.viewport(
        snapshot.viewport[0], snapshot.viewport[1], snapshot.viewport[2], snapshot.viewport[3],
    )
    binding.setEnabled(GL_SCISSOR_TEST, snapshot.scissorEnabled)
    binding.scissor(
        snapshot.scissorBox[0], snapshot.scissorBox[1], snapshot.scissorBox[2], snapshot.scissorBox[3],
    )
    binding.colorMask(
        snapshot.colourWriteMask[0],
        snapshot.colourWriteMask[1],
        snapshot.colourWriteMask[2],
        snapshot.colourWriteMask[3],
    )
    binding.clearColor(
        snapshot.colourClearValue[0],
        snapshot.colourClearValue[1],
        snapshot.colourClearValue[2],
        snapshot.colourClearValue[3],
    )

    binding.pixelStorei(GL_UNPACK_ALIGNMENT, snapshot.unpackAlignment)
    binding.pixelStorei(GL_UNPACK_ROW_LENGTH, snapshot.unpackRowLength)
    binding.pixelStorei(GL_UNPACK_SKIP_ROWS, snapshot.unpackSkipRows)
    binding.pixelStorei(GL_UNPACK_SKIP_PIXELS, snapshot.unpackSkipPixels)
    binding.pixelStorei(GL_PACK_ALIGNMENT, snapshot.packAlignment)

    snapshot.framebufferSrgbEnabled?.let { binding.setEnabled(GL_FRAMEBUFFER_SRGB, it) }
    snapshot.drawBuffer?.let { binding.drawBuffers(1, intArrayOf(it)) }
    snapshot.lineSmoothEnabled?.let { binding.setEnabled(GL_LINE_SMOOTH, it) }

    snapshot.textureUnits.forEach { unit ->
        binding.activeTexture(unit.unit)
        binding.bindTexture(GL_TEXTURE_2D, unit.texture2d)
        binding.bindSampler(unit.unit - GL_TEXTURE0, unit.sampler)
    }
    binding.activeTexture(snapshot.activeTextureUnit)
}

private fun GlBinding.setEnabled(cap: Int, enabled: Boolean) {
    if (enabled) enable(cap) else disable(cap)
}
```

`glClearColor` and `glClearDepthf` are restored because they are global state rather than parameters of `glClear`, and RenG clears its offscreen surface every frame. `glBindSampler` takes a texture unit **index**, not the `GL_TEXTUREi` token, which is why the restore subtracts `GL_TEXTURE0`.

- [ ] **Step 6: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.GlStateSnapshotTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.gl.GlStateSnapshotTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlStateSnapshot.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlStateSnapshotTest.kt
git commit -m "feat: capture and restore the corrected GL state set"
```

---

### Task 11: Freeze the Internal Pipeline and Offscreen Surface Identities

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/ResourceKeyDerivation.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/identity/InternalResourceKeyTest.kt`

**Interfaces:**
- Consumes: Cycle B's `CanonicalBinary`, `CanonicalRootKind.INTERNAL_PIPELINE` (root byte 4), `CanonicalRootKind.OFFSCREEN_SURFACE` (root byte 5), `ResourceKeyDeriver`, and `ShaderPair`.
- Produces: `InternalPipelineRole`, `OffscreenColourFormat`, `OffscreenDepthFormat`, `OffscreenSurfaceDescriptor`, `ResourceKeyDeriver.internalPipeline`, and `ResourceKeyDeriver.offscreenSurface`.

The Cycle B specification states that pipeline and offscreen roots are domain-separated kinds 4 and 5 and that **their owning cycles must freeze descriptor tags before creating those resource entries**. Cycle D is that owning cycle, because it is the first to create either resource, so the tags are frozen here and never renumbered afterwards.

- [ ] **Step 1: Write the frozen-bytes tests**

```kotlin
package com.rohittp.reng.internal.identity

import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.gl.InternalPipelineRole
import com.rohittp.reng.internal.gl.OffscreenColourFormat
import com.rohittp.reng.internal.gl.OffscreenDepthFormat
import com.rohittp.reng.internal.gl.OffscreenSurfaceDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class InternalResourceKeyTest {
    private val deriver = ResourceKeyDeriver()

    @Test fun offscreenSurfaceBytesAreFrozen() {
        val derived = deriver.offscreenSurface(
            OffscreenSurfaceDescriptor(
                widthPixels = 2,
                heightPixels = 3,
                colourFormat = OffscreenColourFormat.RGBA8,
                depthFormat = OffscreenDepthFormat.DEPTH_COMPONENT24,
            ),
        )
        val expected = "524e47430105" +
            "0001000000020004" +
            "0002000000080000000000000002" +
            "0003000000080000000000000003" +
            "0004000000020001" +
            "0005000000020001"
        assertEquals(expected, derived.identity.canonicalBytes.bytes.toLowercaseHex())
        assertEquals(ResourceKind.OFFSCREEN_SURFACE, derived.key.kind)
        assertNull(derived.key.resourceClass)
        assertNull(derived.rawKey)
    }

    @Test fun internalPipelineBytesAreFrozen() {
        val derived = deriver.internalPipeline(
            role = InternalPipelineRole.COMPOSITE,
            shaderPair = ShaderPair(vertexSource = "a", fragmentSource = "b"),
        )
        val expected = "524e47430104" +
            "0001000000020003" +
            "0002000000020001" +
            "0003000000020001" +
            "00040000000161" +
            "00050000000162"
        assertEquals(expected, derived.identity.canonicalBytes.bytes.toLowercaseHex())
        assertEquals(ResourceKind.INTERNAL_PIPELINE, derived.key.kind)
        assertNull(derived.key.resourceClass)
        assertNull(derived.rawKey)
    }

    @Test fun identitiesAreStableAndSeparateAcrossDescriptors() {
        val first = deriver.offscreenSurface(
            OffscreenSurfaceDescriptor(2, 3, OffscreenColourFormat.RGBA8, OffscreenDepthFormat.DEPTH_COMPONENT24),
        )
        val again = deriver.offscreenSurface(
            OffscreenSurfaceDescriptor(2, 3, OffscreenColourFormat.RGBA8, OffscreenDepthFormat.DEPTH_COMPONENT24),
        )
        val other = deriver.offscreenSurface(
            OffscreenSurfaceDescriptor(3, 2, OffscreenColourFormat.RGBA8, OffscreenDepthFormat.DEPTH_COMPONENT24),
        )
        assertEquals(first.key.stableId, again.key.stableId)
        assertNotEquals(first.key.stableId, other.key.stableId)
        assertNotEquals(
            first.key.stableId,
            deriver.internalPipeline(
                InternalPipelineRole.COMPOSITE,
                ShaderPair(vertexSource = "a", fragmentSource = "b"),
            ).key.stableId,
        )
    }
}

private fun ByteArray.toLowercaseHex(): String = buildString(size * 2) {
    this@toLowercaseHex.forEach { byte ->
        append("0123456789abcdef"[(byte.toInt() ushr 4) and 0x0f])
        append("0123456789abcdef"[byte.toInt() and 0x0f])
    }
}
```

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.identity.InternalResourceKeyTest"
```

Expected: compilation fails because neither deriver method exists.

- [ ] **Step 3: Declare the descriptors**

Put these three enums and the descriptor in the GL package, beside the code that creates the resources they describe, at `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/OffscreenSurface.kt` (Task 13 fills in the rest of that file) and `CompositePipeline.kt` (Task 14). If Task 11 lands before either, create the files with only these declarations.

```kotlin
internal enum class InternalPipelineRole(internal val wireValue: Int) {
    COMPOSITE(1),
}

internal enum class OffscreenColourFormat(
    internal val wireValue: Int,
    internal val glInternalFormat: Int,
) {
    RGBA8(1, GL_RGBA8),
}

internal enum class OffscreenDepthFormat(
    internal val wireValue: Int,
    internal val glInternalFormat: Int,
) {
    DEPTH_COMPONENT24(1, GL_DEPTH_COMPONENT24),
}

internal data class OffscreenSurfaceDescriptor(
    val widthPixels: Int,
    val heightPixels: Int,
    val colourFormat: OffscreenColourFormat,
    val depthFormat: OffscreenDepthFormat,
) {
    init {
        require(widthPixels > 0 && heightPixels > 0) { "an offscreen surface has positive dimensions" }
    }
}
```

`GL_DEPTH_COMPONENT24` is `GLES30`-only on Android, which the seam already handles because the whole binding is `GLES30`.

- [ ] **Step 4: Add the two derivations**

Append to `ResourceKeyDeriver`, keeping the existing `external` and `geometryProgram` methods untouched:

```kotlin
    internal fun internalPipeline(
        role: InternalPipelineRole,
        shaderPair: ShaderPair,
    ): DerivedResourceKey {
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.INTERNAL_PIPELINE) {
                field(1, CanonicalBinary.u16(ResourceKind.INTERNAL_PIPELINE.wireValue))
                field(2, CanonicalBinary.u16(role.wireValue))
                field(3, CanonicalBinary.u16(GEOMETRY_SHADER_PROFILE_WIRE_VALUE))
                field(4, CanonicalBinary.exactUtf8(shaderPair.vertexSource))
                field(5, CanonicalBinary.exactUtf8(shaderPair.fragmentSource))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.INTERNAL_PIPELINE,
                stableId = identity.digest.lowercaseHex,
                resourceClass = null,
            ),
            rawKey = null,
            identity = identity,
        )
    }

    internal fun offscreenSurface(descriptor: OffscreenSurfaceDescriptor): DerivedResourceKey {
        val identity = derive(
            CanonicalBinary.root(CanonicalRootKind.OFFSCREEN_SURFACE) {
                field(1, CanonicalBinary.u16(ResourceKind.OFFSCREEN_SURFACE.wireValue))
                field(2, CanonicalBinary.u64(descriptor.widthPixels.toLong()))
                field(3, CanonicalBinary.u64(descriptor.heightPixels.toLong()))
                field(4, CanonicalBinary.u16(descriptor.colourFormat.wireValue))
                field(5, CanonicalBinary.u16(descriptor.depthFormat.wireValue))
            },
        )
        return DerivedResourceKey(
            key = ResourceKey(
                kind = ResourceKind.OFFSCREEN_SURFACE,
                stableId = identity.digest.lowercaseHex,
                resourceClass = null,
            ),
            rawKey = null,
            identity = identity,
        )
    }
```

Dimensions are `u64be` rather than `u16be` because an `OutputPixelSize` accepts any positive `Int` width and height. Both roots repeat the resource kind at tag 1 exactly as the external and geometry-program roots do, so all four roots read the same way. Neither derivation produces a `RawResourceKey`: an internal pipeline and an offscreen surface are never fetched through a consumer adapter.

- [ ] **Step 5: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.identity.InternalResourceKeyTest" \
  --tests "com.rohittp.reng.internal.identity.ResourceKeyDerivationTest" \
  --tests "com.rohittp.reng.internal.identity.CanonicalBinaryTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.identity.InternalResourceKeyTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/ResourceKeyDerivation.kt \
  kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/identity/InternalResourceKeyTest.kt
git commit -m "feat: freeze the internal pipeline and offscreen surface identities"
```

Expected: the pre-existing Cycle B identity tests still pass unchanged, which is the check that the two new roots did not disturb the frame, external, or geometry-program encodings.

---

### Task 12: Shader Compilation, Version Substitution, and the Program Cache

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlShaderCompiler.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlProgramCache.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlShaderCompilerTest.kt`

**Interfaces:**
- Consumes: Cycle B's `ShaderProfilePlan` (`gles300Source()`, `desktop330Source()`) and `ResourceKeyDeriver.geometryProgram`; Task 8's `ShaderDialect`; Task 9's `glOperationFailure`.
- Produces: `ShaderCompileStep`, `ShaderInfoLogObserver`, `GlProgramResult`, `ShaderProfilePlan.sourceFor`, `compileShaderProgram`, `shaderProgramFailure`, and `GlProgramCache`.

Cycle B already implements the scan and the substitution purely, and its plan now validates that the declared span actually describes the directive line, so a `ShaderProfilePlan` cannot emit `#version 330 core#version 300 es`. Cycle D supplies the dialect and performs the compile, and adds nothing to the substitution rule itself.

- [ ] **Step 1: Write the substitution, failure, and cache tests**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val VERTEX_SOURCE: String = "#version 300 es\nvoid main() { gl_Position = vec4(0.0); }\n"
private const val FRAGMENT_SOURCE: String =
    "#version 300 es\nprecision highp float;\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n"

class GlShaderCompilerTest {
    private val deriver = ResourceKeyDeriver()
    private val pair = ShaderPair(VERTEX_SOURCE, FRAGMENT_SOURCE)
    private val key = deriver.geometryProgram(pair).key
    private val vertexPlan = requireNotNull(scanShaderProfile(VERTEX_SOURCE))
    private val fragmentPlan = requireNotNull(scanShaderProfile(FRAGMENT_SOURCE))

    @Test fun anEsContextCompilesTheSourceUnchanged() {
        val binding = RecordingGlBinding()
        compileShaderProgram(binding, ShaderDialect.GLES, key, vertexPlan, fragmentPlan)
        assertTrue(binding.shaderSources.values.all { it.startsWith("#version 300 es") })
        assertTrue(binding.shaderSources.values.none { "#version 330 core" in it })
    }

    @Test fun aDesktopContextSubstitutesExactlyTheDirectiveLine() {
        val binding = RecordingGlBinding()
        compileShaderProgram(binding, ShaderDialect.DESKTOP, key, vertexPlan, fragmentPlan)
        assertTrue(binding.shaderSources.values.all { it.startsWith("#version 330 core") })
        assertTrue(binding.shaderSources.values.none { "#version 300 es" in it })
        assertEquals(
            "#version 330 core\nvoid main() { gl_Position = vec4(0.0); }\n",
            binding.shaderSources.values.first { "gl_Position" in it },
        )
    }

    @Test fun theDialectIsTheOnlyInputToSourceSelection() {
        assertEquals(VERTEX_SOURCE, vertexPlan.sourceFor(ShaderDialect.GLES))
        assertEquals(
            "#version 330 core\nvoid main() { gl_Position = vec4(0.0); }\n",
            vertexPlan.sourceFor(ShaderDialect.DESKTOP),
        )
    }

    @Test fun aCompileFailureIsTypedAndKeepsTheDriverLogOffTheBoundary() {
        val binding = RecordingGlBinding()
        binding.compileStatus = 0
        binding.shaderInfoLog = "0:1(10): error: GLSL 3.30 is not supported. sensitive-path/shader.frag"
        var observed = ""
        val result = compileShaderProgram(
            binding, ShaderDialect.GLES, key, vertexPlan, fragmentPlan,
        ) { _, log -> observed = log }
        val failed = result as GlProgramResult.Failed
        assertEquals(RenGErrorCode.SHADER_COMPILE_FAILED, failed.failure.code)
        assertEquals(PipelineStage.SHADER_COMPILATION, failed.failure.stage)
        val diagnostic = assertNotNull(failed.failure.diagnostic)
        assertEquals("shaderPair", diagnostic.fieldName)
        assertEquals(key, diagnostic.resourceKey)
        assertTrue("GLSL 3.30" in observed)
        assertTrue("GLSL 3.30" !in failed.failure.toString())
        assertTrue("sensitive-path" !in failed.failure.toString())
        assertTrue(binding.log.any { it.startsWith("deleteShader") })
    }

    @Test fun aLinkFailureIsTypedAndDeletesEverythingItCreated() {
        val binding = RecordingGlBinding()
        binding.linkStatus = 0
        val failed = compileShaderProgram(
            binding, ShaderDialect.DESKTOP, key, vertexPlan, fragmentPlan,
        ) as GlProgramResult.Failed
        assertEquals(RenGErrorCode.SHADER_LINK_FAILED, failed.failure.code)
        assertTrue(binding.log.any { it.startsWith("deleteProgram") })
        assertEquals(2, binding.log.count { it.startsWith("deleteShader") })
    }

    @Test fun anInternalPipelineFailureIsAGpuFailureRatherThanAConsumerShaderFailure() {
        val internalKey = deriver.internalPipeline(InternalPipelineRole.COMPOSITE, pair).key
        assertEquals(ResourceKind.INTERNAL_PIPELINE, internalKey.kind)
        val failure = shaderProgramFailure(RenGErrorCode.SHADER_COMPILE_FAILED, internalKey)
        assertEquals(RenGErrorCode.GPU_OPERATION_FAILED, failure.code)
        assertEquals(PipelineStage.GPU_RESOURCE, failure.stage)
        assertNull(assertNotNull(failure.diagnostic).fieldName)
    }

    @Test fun aCachedProgramIsNotCompiledTwice() {
        val binding = RecordingGlBinding()
        val cache = GlProgramCache()
        val first = cache.getOrCompile(binding, ShaderDialect.GLES, key, vertexPlan, fragmentPlan)
        val second = cache.getOrCompile(binding, ShaderDialect.GLES, key, vertexPlan, fragmentPlan)
        assertEquals(first, second)
        assertEquals(1, binding.log.count { it.startsWith("createProgram") })
        assertEquals(2, binding.log.count { it.startsWith("createShader") })
    }

    @Test fun forgettingTheCacheForcesRecompilationAfterContextLoss() {
        val binding = RecordingGlBinding()
        val cache = GlProgramCache()
        cache.getOrCompile(binding, ShaderDialect.GLES, key, vertexPlan, fragmentPlan)
        cache.forgetAll()
        cache.getOrCompile(binding, ShaderDialect.DESKTOP, key, vertexPlan, fragmentPlan)
        assertEquals(2, binding.log.count { it.startsWith("createProgram") })
        assertTrue(binding.log.none { it.startsWith("deleteProgram") })
    }
}
```

The last case is the one that matters for correctness rather than for economy: the `GEOMETRY_PROGRAM` key contains the shader profile version and the exact sources but **not** the dialect, so a program compiled under one dialect must never be handed back under another. GPU object loss and context adoption both call `forgetAll()`, and after loss there is nothing to delete, which is why that path issues no `glDeleteProgram`.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.GlShaderCompilerTest"
```

Expected: compilation fails because the compiler does not exist.

- [ ] **Step 3: Implement the compiler**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.shader.ShaderProfilePlan

internal enum class ShaderCompileStep {
    VERTEX_COMPILE,
    FRAGMENT_COMPILE,
    LINK,
}

internal fun interface ShaderInfoLogObserver {
    fun observe(step: ShaderCompileStep, log: String)
}

internal sealed interface GlProgramResult {
    data class Linked(val program: Int) : GlProgramResult

    data class Failed(val failure: FailureDescriptor) : GlProgramResult
}

internal fun ShaderProfilePlan.sourceFor(dialect: ShaderDialect): String = when (dialect) {
    ShaderDialect.GLES -> gles300Source()
    ShaderDialect.DESKTOP -> desktop330Source()
}

internal fun shaderProgramFailure(code: RenGErrorCode, key: ResourceKey): FailureDescriptor =
    if (key.kind == ResourceKind.GEOMETRY_PROGRAM) {
        FailureDescriptor(
            code = code,
            stage = PipelineStage.SHADER_COMPILATION,
            diagnostic = failureContextDiagnostic(
                stage = PipelineStage.SHADER_COMPILATION,
                fieldName = DiagnosticField.SHADER_PAIR,
                resourceKey = key,
            ),
        )
    } else {
        glOperationFailure(PipelineStage.GPU_RESOURCE, key)
    }

internal fun compileShaderProgram(
    binding: GlBinding,
    dialect: ShaderDialect,
    key: ResourceKey,
    vertexPlan: ShaderProfilePlan,
    fragmentPlan: ShaderProfilePlan,
    infoLogObserver: ShaderInfoLogObserver = ShaderInfoLogObserver { _, _ -> },
): GlProgramResult {
    val vertexShader = compileStage(
        binding, GL_VERTEX_SHADER, vertexPlan.sourceFor(dialect),
        ShaderCompileStep.VERTEX_COMPILE, infoLogObserver,
    ) ?: return GlProgramResult.Failed(
        shaderProgramFailure(RenGErrorCode.SHADER_COMPILE_FAILED, key),
    )

    val fragmentShader = compileStage(
        binding, GL_FRAGMENT_SHADER, fragmentPlan.sourceFor(dialect),
        ShaderCompileStep.FRAGMENT_COMPILE, infoLogObserver,
    )
    if (fragmentShader == null) {
        binding.deleteShader(vertexShader)
        return GlProgramResult.Failed(
            shaderProgramFailure(RenGErrorCode.SHADER_COMPILE_FAILED, key),
        )
    }

    val program = binding.createProgram()
    binding.attachShader(program, vertexShader)
    binding.attachShader(program, fragmentShader)
    binding.linkProgram(program)

    val status = IntArray(1)
    binding.getProgramiv(program, GL_LINK_STATUS, status)
    if (status[0] == 0) {
        infoLogObserver.observe(ShaderCompileStep.LINK, binding.getProgramInfoLog(program))
        binding.deleteShader(vertexShader)
        binding.deleteShader(fragmentShader)
        binding.deleteProgram(program)
        return GlProgramResult.Failed(
            shaderProgramFailure(RenGErrorCode.SHADER_LINK_FAILED, key),
        )
    }

    binding.deleteShader(vertexShader)
    binding.deleteShader(fragmentShader)
    return GlProgramResult.Linked(program)
}

private fun compileStage(
    binding: GlBinding,
    type: Int,
    source: String,
    step: ShaderCompileStep,
    infoLogObserver: ShaderInfoLogObserver,
): Int? {
    val shader = binding.createShader(type)
    binding.shaderSource(shader, source)
    binding.compileShader(shader)
    val status = IntArray(1)
    binding.getShaderiv(shader, GL_COMPILE_STATUS, status)
    if (status[0] != 0) return shader
    infoLogObserver.observe(step, binding.getShaderInfoLog(shader))
    binding.deleteShader(shader)
    return null
}
```

The info log is fetched, handed to the observer, and dropped. It never reaches a `FailureDescriptor`, because a `Diagnostic` admits only allowlisted fields and none of them is free text; the observer exists so the conformance suite can prove the driver actually rejected the source and that the rejection text stayed inside RenG. Deleting both shaders after a successful link is correct rather than sloppy: a deleted shader that is still attached is flagged and freed when the program is deleted.

- [ ] **Step 4: Implement the cache**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.internal.shader.ShaderProfilePlan

internal class GlProgramCache {
    private val programs: LinkedHashMap<ResourceKey, Int> = LinkedHashMap()

    internal fun getOrCompile(
        binding: GlBinding,
        dialect: ShaderDialect,
        key: ResourceKey,
        vertexPlan: ShaderProfilePlan,
        fragmentPlan: ShaderProfilePlan,
        infoLogObserver: ShaderInfoLogObserver = ShaderInfoLogObserver { _, _ -> },
    ): GlProgramResult {
        programs[key]?.let { return GlProgramResult.Linked(it) }
        val result = compileShaderProgram(binding, dialect, key, vertexPlan, fragmentPlan, infoLogObserver)
        if (result is GlProgramResult.Linked) programs[key] = result.program
        return result
    }

    internal fun program(key: ResourceKey): Int? = programs[key]

    internal fun keys(): List<ResourceKey> = ArrayList(programs.keys)

    internal fun remove(key: ResourceKey): Int? = programs.remove(key)

    /** Drops every cached program name without issuing a delete, for declared GPU object loss. */
    internal fun forgetAll() {
        programs.clear()
    }
}
```

- [ ] **Step 5: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.GlShaderCompilerTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.gl.GlShaderCompilerTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlShaderCompiler.kt \
  kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlProgramCache.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlShaderCompilerTest.kt
git commit -m "feat: compile shaders against the detected dialect"
```

---

### Task 13: The Offscreen Colour-and-Depth Surface

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/OffscreenSurface.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlStateSnapshot.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/OffscreenSurfaceTest.kt`

**Interfaces:**
- Consumes: Task 8's `RenderContextProfile`, Task 9's `GlErrorQueue`/`glOperationFailure`, Task 10's snapshot, Task 11's `OffscreenSurfaceDescriptor` and `ResourceKeyDeriver.offscreenSurface`, and public `OutputPixelSize`.
- Produces: `withCapturedGlState`, `OffscreenSurface`, `OffscreenSurfaceResult`, `createOffscreenSurface`, and `deleteOffscreenSurface`.

RenG renders into its own offscreen colour-and-depth surface at the configured output pixel size and then composites it into the caller's `RenderTarget`, so a target only has to be a colour-writable framebuffer of the configured dimensions (ADR 0005). The surface is allocated once and never resized, because output size is fixed at setup (ADR 0012).

- [ ] **Step 1: Write the creation, rollback, and deletion tests**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OffscreenSurfaceTest {
    private val deriver = ResourceKeyDeriver()

    @Test fun theSurfaceIsColourAndDepthAndCompleteBeforeItIsReturned() {
        val binding = RecordingGlBinding()
        val created = createSurface(binding) as OffscreenSurfaceResult.Created
        assertTrue(created.surface.colourTexture > 0)
        assertTrue(created.surface.depthRenderbuffer > 0)
        assertTrue(created.surface.framebuffer > 0)
        assertTrue(
            binding.log.indexOfFirst { it.startsWith("texStorage2D") } <
                binding.log.indexOfFirst { it.startsWith("framebufferTexture2D") },
        )
        assertTrue(binding.log.any { it.startsWith("renderbufferStorage(0x8D41,0x81A6") })
        assertTrue(binding.log.any { it.startsWith("checkFramebufferStatus") })
    }

    @Test fun anIncompleteFramebufferDeletesEverythingItCreated() {
        val binding = RecordingGlBinding()
        binding.framebufferStatus = 0x8CD6
        val failed = createSurface(binding) as OffscreenSurfaceResult.Failed
        assertEquals(RenGErrorCode.GPU_OPERATION_FAILED, failed.failure.code)
        assertEquals(PipelineStage.GPU_RESOURCE, failed.failure.stage)
        assertTrue(binding.log.any { it.startsWith("deleteFramebuffers") })
        assertTrue(binding.log.any { it.startsWith("deleteRenderbuffers") })
        assertTrue(binding.log.any { it.startsWith("deleteTextures") })
    }

    @Test fun aSurfaceLargerThanTheContextAllowsFailsBeforeAnyAllocation() {
        val binding = RecordingGlBinding()
        val result = createOffscreenSurface(
            binding = binding,
            profile = profile(maxTextureSize = 256),
            key = deriver.offscreenSurface(descriptor(1024, 1024)).key,
            descriptor = descriptor(1024, 1024),
        )
        assertTrue(result is OffscreenSurfaceResult.Failed)
        assertTrue(binding.log.isEmpty())
    }

    @Test fun deletionRemovesAllThreeObjectsExactlyOnce() {
        val binding = RecordingGlBinding()
        val surface = (createSurface(binding) as OffscreenSurfaceResult.Created).surface
        binding.log.clear()
        deleteOffscreenSurface(binding, surface)
        assertEquals(1, binding.log.count { it.startsWith("deleteFramebuffers") })
        assertEquals(1, binding.log.count { it.startsWith("deleteRenderbuffers") })
        assertEquals(1, binding.log.count { it.startsWith("deleteTextures") })
    }

    @Test fun theCapturedStateGuardRestoresEvenWhenTheBlockThrows() {
        val binding = RecordingGlBinding()
        val before = captureGlState(binding, profile(), textureUnitCount = 1)
        runCatching {
            withCapturedGlState(binding, profile(), textureUnitCount = 1) {
                binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 7)
                throw IllegalStateException("frame content failed")
            }
        }
        assertEquals("bindFramebuffer(0x8CA9,0)", binding.log.first { it.startsWith("bindFramebuffer") && it.endsWith(",0)") })
        assertEquals(before, captureGlState(binding, profile(), textureUnitCount = 1))
    }

    private fun createSurface(binding: RecordingGlBinding): OffscreenSurfaceResult {
        val descriptor = descriptor(64, 64)
        return createOffscreenSurface(
            binding = binding,
            profile = profile(),
            key = deriver.offscreenSurface(descriptor).key,
            descriptor = descriptor,
        )
    }

    private fun descriptor(width: Int, height: Int): OffscreenSurfaceDescriptor =
        OffscreenSurfaceDescriptor(
            widthPixels = width,
            heightPixels = height,
            colourFormat = OffscreenColourFormat.RGBA8,
            depthFormat = OffscreenDepthFormat.DEPTH_COMPONENT24,
        )
}
```

Add a private `profile(maxTextureSize: Int = 16384)` helper returning a `RenderContextProfile` with `ShaderDialect.GLES`, `GlVersion(3, 2)`, `supportsSrgbWriteControl = true`, and `supportsEs3Compatibility = false`, and seed `RecordingGlBinding` so the guard's captures return stable values.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.OffscreenSurfaceTest"
```

Expected: compilation fails because the surface factory does not exist.

- [ ] **Step 3: Add the state guard to `GlStateSnapshot.kt`**

```kotlin
internal inline fun <T> withCapturedGlState(
    binding: GlBinding,
    profile: RenderContextProfile,
    textureUnitCount: Int,
    block: () -> T,
): T {
    val snapshot = captureGlState(binding, profile, textureUnitCount)
    try {
        return block()
    } finally {
        restoreGlState(binding, snapshot)
    }
}
```

Restoring in a `finally` is what turns ADR 0006 from a happy-path promise into a guarantee: a frame that fails halfway through still hands the context back exactly as it was found.

- [ ] **Step 4: Implement creation and deletion**

```kotlin
internal class OffscreenSurface(
    val key: ResourceKey,
    val descriptor: OffscreenSurfaceDescriptor,
    val framebuffer: Int,
    val colourTexture: Int,
    val depthRenderbuffer: Int,
)

internal sealed interface OffscreenSurfaceResult {
    data class Created(val surface: OffscreenSurface) : OffscreenSurfaceResult

    data class Failed(val failure: FailureDescriptor) : OffscreenSurfaceResult
}

internal fun offscreenSurfaceDescriptorFor(size: OutputPixelSize): OffscreenSurfaceDescriptor =
    OffscreenSurfaceDescriptor(
        widthPixels = size.width,
        heightPixels = size.height,
        colourFormat = OffscreenColourFormat.RGBA8,
        depthFormat = OffscreenDepthFormat.DEPTH_COMPONENT24,
    )

internal fun createOffscreenSurface(
    binding: GlBinding,
    profile: RenderContextProfile,
    key: ResourceKey,
    descriptor: OffscreenSurfaceDescriptor,
): OffscreenSurfaceResult {
    if (
        descriptor.widthPixels > profile.maxTextureSize ||
        descriptor.heightPixels > profile.maxTextureSize
    ) {
        return OffscreenSurfaceResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    }

    GlErrorQueue.drainOnEntry(binding)
    val names = IntArray(1)

    binding.genTextures(1, names)
    val colourTexture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, colourTexture)
    binding.texStorage2D(
        GL_TEXTURE_2D, 1, descriptor.colourFormat.glInternalFormat,
        descriptor.widthPixels, descriptor.heightPixels,
    )
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    binding.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

    binding.genRenderbuffers(1, names)
    val depthRenderbuffer = names[0]
    binding.bindRenderbuffer(GL_RENDERBUFFER, depthRenderbuffer)
    binding.renderbufferStorage(
        GL_RENDERBUFFER, descriptor.depthFormat.glInternalFormat,
        descriptor.widthPixels, descriptor.heightPixels,
    )

    binding.genFramebuffers(1, names)
    val framebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
    binding.framebufferTexture2D(
        GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colourTexture, 0,
    )
    binding.framebufferRenderbuffer(
        GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRenderbuffer,
    )

    val status = binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER)
    val error = GlErrorQueue.firstOwnError(binding)
    if (status != GL_FRAMEBUFFER_COMPLETE || error != GL_NO_ERROR) {
        binding.deleteFramebuffers(1, intArrayOf(framebuffer))
        binding.deleteRenderbuffers(1, intArrayOf(depthRenderbuffer))
        binding.deleteTextures(1, intArrayOf(colourTexture))
        return OffscreenSurfaceResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    }

    return OffscreenSurfaceResult.Created(
        OffscreenSurface(
            key = key,
            descriptor = descriptor,
            framebuffer = framebuffer,
            colourTexture = colourTexture,
            depthRenderbuffer = depthRenderbuffer,
        ),
    )
}

internal fun deleteOffscreenSurface(binding: GlBinding, surface: OffscreenSurface) {
    binding.deleteFramebuffers(1, intArrayOf(surface.framebuffer))
    binding.deleteRenderbuffers(1, intArrayOf(surface.depthRenderbuffer))
    binding.deleteTextures(1, intArrayOf(surface.colourTexture))
}
```

The colour attachment uses immutable `glTexStorage2D` storage rather than `glTexImage2D`, so the surface is allocated once with a fixed level count and a fixed `GL_RGBA8` format and can never be silently reshaped. Creation deliberately leaves its objects bound; the caller wraps it in `withCapturedGlState`, which is the single place restoration happens.

- [ ] **Step 5: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.OffscreenSurfaceTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.gl.OffscreenSurfaceTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/OffscreenSurface.kt \
  kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlStateSnapshot.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/OffscreenSurfaceTest.kt
git commit -m "feat: allocate RenG's offscreen colour and depth surface"
```

---

### Task 14: The Composite Pass and the Cycle D Draw Path

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/CompositePipeline.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlFrameDrawer.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/CompositePipelineTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlFrameDrawerTest.kt`

**Interfaces:**
- Consumes: Tasks 8, 9, 10, 11, 12, 13; public `FramebufferName`.
- Produces: `COMPOSITE_VERTEX_SOURCE`, `COMPOSITE_FRAGMENT_SOURCE`, `COMPOSITE_SHADER_PAIR`, `littleEndianBytes`, `CompositePipeline`, `createCompositePipeline`, `deleteCompositePipeline`, `GlFrameContent`, `REVERSE_Z_FAR_DEPTH`, and `drawFrame`.

Compositing is a blended draw rather than a framebuffer blit, because a blit does not blend and a consumer compositing RenG's output over existing content needs it to (ADR 0005). Cycle D draws no frame content: `GlFrameContent.Empty` is what production passes, and Cycle E replaces it.

- [ ] **Step 1: Write the pipeline and draw-order tests**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompositePipelineTest {
    @Test fun floatsEncodeLittleEndianBecauseEveryPublishedTargetIsLittleEndian() {
        assertContentEquals(
            byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3f),
            littleEndianBytes(floatArrayOf(1.0f)),
        )
        assertContentEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80.toByte(), 0xbf.toByte()),
            littleEndianBytes(floatArrayOf(0.0f, -1.0f)),
        )
        assertEquals(64, littleEndianBytes(COMPOSITE_QUAD).size)
    }

    @Test fun theCompositeSourcesAreAcceptedShaderProfileSources() {
        assertTrue(COMPOSITE_VERTEX_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(COMPOSITE_FRAGMENT_SOURCE.startsWith("#version 300 es\n"))
        assertTrue(scanShaderProfile(COMPOSITE_VERTEX_SOURCE) != null)
        assertTrue(scanShaderProfile(COMPOSITE_FRAGMENT_SOURCE) != null)
    }

    @Test fun creationBuildsAProgramAQuadAndTwoAttributes() {
        val binding = RecordingGlBinding()
        val pipeline = createCompositePipeline(binding, ShaderDialect.GLES, GlProgramCache())
            as CompositePipelineResult.Created
        assertTrue(pipeline.pipeline.program > 0)
        assertTrue(pipeline.pipeline.vertexArray > 0)
        assertTrue(pipeline.pipeline.vertexBuffer > 0)
        assertEquals(2, binding.log.count { it.startsWith("enableVertexAttribArray") })
        assertEquals(2, binding.log.count { it.startsWith("vertexAttribPointer") })
        assertTrue(binding.log.any { it.startsWith("bufferData(0x8892,64") })
    }

    @Test fun deletionRemovesTheQuadAndTheProgram() {
        val binding = RecordingGlBinding()
        val cache = GlProgramCache()
        val pipeline =
            (createCompositePipeline(binding, ShaderDialect.GLES, cache) as CompositePipelineResult.Created).pipeline
        binding.log.clear()
        deleteCompositePipeline(binding, cache, pipeline)
        assertEquals(1, binding.log.count { it.startsWith("deleteVertexArrays") })
        assertEquals(1, binding.log.count { it.startsWith("deleteBuffers") })
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram") })
        assertEquals(null, cache.program(pipeline.key))
    }
}
```

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.FramebufferName
import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RenGErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlFrameDrawerTest {
    @Test fun theOffscreenSurfaceIsClearedBeforeContentAndCompositedAfterIt() {
        val world = drawWorld()
        var contentIndex = -1
        world.draw { contentIndex = world.binding.log.size }
        val clearIndex = world.binding.log.indexOfFirst { it.startsWith("clear(") }
        val compositeIndex = world.binding.log.indexOfFirst { it.startsWith("drawArrays") }
        assertTrue(clearIndex in 0 until contentIndex)
        assertTrue(contentIndex < compositeIndex)
        assertTrue(
            world.binding.log.indexOfFirst { it == "bindFramebuffer(0x8CA9,${world.target.value.toInt()})" } >
                contentIndex,
        )
    }

    @Test fun reverseZClearsDepthToZeroAndTestsGreater() {
        val world = drawWorld()
        world.draw { }
        assertTrue(world.binding.log.any { it == "clearDepthf(0.0)" })
        assertTrue(world.binding.log.any { it == "depthFunc(0x204)" })
    }

    @Test fun srgbIsSetExplicitlyAndRestoredRatherThanInherited() {
        val world = drawWorld(srgbSupported = true, srgbInitiallyEnabled = true)
        world.draw { }
        assertTrue(world.binding.log.any { it == "disable(0x8DB9)" })
        assertEquals("enable(0x8DB9)", world.binding.log.last { "0x8DB9" in it })
    }

    @Test fun anEsContextWithoutWriteControlNeverTouchesSrgb() {
        val world = drawWorld(srgbSupported = false)
        world.draw { }
        assertTrue(world.binding.log.none { "0x8DB9" in it })
    }

    @Test fun theFullStateSetIsIdenticalBeforeAndAfterADraw() {
        val world = drawWorld()
        val before = captureGlState(world.binding, world.profile, textureUnitCount = 1)
        world.draw { }
        assertEquals(before, captureGlState(world.binding, world.profile, textureUnitCount = 1))
    }

    @Test fun aDriverErrorDuringTheDrawBecomesATypedGpuFailureAndStillRestores() {
        val world = drawWorld()
        world.binding.errorQueue = mutableListOf(GL_NO_ERROR, GL_INVALID_OPERATION, GL_NO_ERROR)
        val before = captureGlState(world.binding, world.profile, textureUnitCount = 1)
        val failure = assertNotNull(world.draw { })
        assertEquals(RenGErrorCode.GPU_OPERATION_FAILED, failure.code)
        assertEquals(PipelineStage.DRAW, failure.stage)
        assertEquals(before, captureGlState(world.binding, world.profile, textureUnitCount = 1))
    }

    @Test fun aConsumerErrorPresentOnEntryIsNotReportedAsRengFailure() {
        val world = drawWorld()
        world.binding.errorQueue = mutableListOf(GL_INVALID_ENUM, GL_NO_ERROR)
        assertNull(world.draw { })
    }
}
```

Add a private `drawWorld(...)` helper in the test file that builds a `RecordingGlBinding`, a profile, a created `OffscreenSurface`, a created `CompositePipeline`, a `FramebufferName(9u)` target, and a `draw(content: (GlBinding) -> Unit)` shortcut over `drawFrame`.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.CompositePipelineTest" \
  --tests "com.rohittp.reng.internal.gl.GlFrameDrawerTest"
```

Expected: compilation fails because neither the pipeline nor the drawer exists.

- [ ] **Step 3: Implement the composite pipeline**

```kotlin
internal const val COMPOSITE_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec2 rengCompositePosition;\n" +
        "layout(location = 1) in vec2 rengCompositeTexCoord;\n" +
        "out vec2 rengCompositeUv;\n" +
        "void main() {\n" +
        "    rengCompositeUv = rengCompositeTexCoord;\n" +
        "    gl_Position = vec4(rengCompositePosition, 0.0, 1.0);\n" +
        "}\n"

internal const val COMPOSITE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision highp float;\n" +
        "uniform sampler2D rengCompositeSource;\n" +
        "in vec2 rengCompositeUv;\n" +
        "layout(location = 0) out vec4 rengCompositeColour;\n" +
        "void main() {\n" +
        "    rengCompositeColour = texture(rengCompositeSource, rengCompositeUv);\n" +
        "}\n"

internal val COMPOSITE_SHADER_PAIR: ShaderPair =
    ShaderPair(vertexSource = COMPOSITE_VERTEX_SOURCE, fragmentSource = COMPOSITE_FRAGMENT_SOURCE)

internal const val COMPOSITE_SOURCE_UNIFORM_NAME: String = "rengCompositeSource"
internal const val COMPOSITE_TEXTURE_UNIT_COUNT: Int = 1

internal val COMPOSITE_QUAD: FloatArray = floatArrayOf(
    -1.0f, -1.0f, 0.0f, 0.0f,
    1.0f, -1.0f, 1.0f, 0.0f,
    -1.0f, 1.0f, 0.0f, 1.0f,
    1.0f, 1.0f, 1.0f, 1.0f,
)

internal fun littleEndianBytes(values: FloatArray): ByteArray {
    val bytes = ByteArray(values.size * Float.SIZE_BYTES)
    var offset = 0
    values.forEach { value ->
        val bits = value.toRawBits()
        bytes[offset] = (bits and 0xff).toByte()
        bytes[offset + 1] = ((bits ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((bits ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((bits ushr 24) and 0xff).toByte()
        offset += Float.SIZE_BYTES
    }
    return bytes
}

internal class CompositePipeline(
    val key: ResourceKey,
    val program: Int,
    val vertexArray: Int,
    val vertexBuffer: Int,
    val sourceUniformLocation: Int,
)

internal sealed interface CompositePipelineResult {
    data class Created(val pipeline: CompositePipeline) : CompositePipelineResult

    data class Failed(val failure: FailureDescriptor) : CompositePipelineResult
}

internal fun createCompositePipeline(
    binding: GlBinding,
    dialect: ShaderDialect,
    cache: GlProgramCache,
    deriver: ResourceKeyDeriver = ResourceKeyDeriver(),
): CompositePipelineResult {
    val key = deriver.internalPipeline(InternalPipelineRole.COMPOSITE, COMPOSITE_SHADER_PAIR).key
    val vertexPlan = scanShaderProfile(COMPOSITE_VERTEX_SOURCE)
        ?: return CompositePipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))
    val fragmentPlan = scanShaderProfile(COMPOSITE_FRAGMENT_SOURCE)
        ?: return CompositePipelineResult.Failed(glOperationFailure(PipelineStage.GPU_RESOURCE, key))

    val program = when (
        val result = cache.getOrCompile(binding, dialect, key, vertexPlan, fragmentPlan)
    ) {
        is GlProgramResult.Linked -> result.program
        is GlProgramResult.Failed -> return CompositePipelineResult.Failed(result.failure)
    }

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.genBuffers(1, names)
    val vertexBuffer = names[0]

    binding.bindVertexArray(vertexArray)
    binding.bindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
    val quad = littleEndianBytes(COMPOSITE_QUAD)
    binding.bufferData(GL_ARRAY_BUFFER, quad.size, quad, GL_STATIC_DRAW)
    binding.enableVertexAttribArray(0)
    binding.vertexAttribPointer(0, 2, GL_FLOAT, false, COMPOSITE_STRIDE_BYTES, 0)
    binding.enableVertexAttribArray(1)
    binding.vertexAttribPointer(1, 2, GL_FLOAT, false, COMPOSITE_STRIDE_BYTES, COMPOSITE_UV_OFFSET_BYTES)

    return CompositePipelineResult.Created(
        CompositePipeline(
            key = key,
            program = program,
            vertexArray = vertexArray,
            vertexBuffer = vertexBuffer,
            sourceUniformLocation = binding.getUniformLocation(program, COMPOSITE_SOURCE_UNIFORM_NAME),
        ),
    )
}

internal fun deleteCompositePipeline(
    binding: GlBinding,
    cache: GlProgramCache,
    pipeline: CompositePipeline,
) {
    binding.deleteVertexArrays(1, intArrayOf(pipeline.vertexArray))
    binding.deleteBuffers(1, intArrayOf(pipeline.vertexBuffer))
    cache.remove(pipeline.key)?.let { binding.deleteProgram(it) }
}

private const val COMPOSITE_STRIDE_BYTES: Int = 16
private const val COMPOSITE_UV_OFFSET_BYTES: Int = 8
```

The composite sources are written in the same **Shader Profile** the public API accepts, so they travel the identical scan-and-substitute path a consumer's `ShaderPair` does. That is deliberate: the substitution machinery is exercised on every context RenG ever runs on, not only on frames that happen to carry a `Geometry`. All six published targets are little-endian and GL reads client memory in host byte order, so `littleEndianBytes` is correct by construction on every one of them.

- [ ] **Step 4: Implement the draw path**

```kotlin
internal fun interface GlFrameContent {
    fun draw(binding: GlBinding)
}

internal val EmptyGlFrameContent: GlFrameContent = GlFrameContent { }

/**
 * Cycle B's projection maps the near plane to `+1` and infinity to `-1` in clip space, so window
 * depth runs from `1` at the near plane down to `0` at infinity. RenG therefore clears depth to `0`
 * and tests with `GL_GREATER`.
 */
internal const val REVERSE_Z_FAR_DEPTH: Float = 0.0f

internal fun drawFrame(
    binding: GlBinding,
    profile: RenderContextProfile,
    surface: OffscreenSurface,
    composite: CompositePipeline,
    targetFramebuffer: FramebufferName,
    content: GlFrameContent = EmptyGlFrameContent,
): FailureDescriptor? {
    GlErrorQueue.drainOnEntry(binding)

    return withCapturedGlState(binding, profile, COMPOSITE_TEXTURE_UNIT_COUNT) {
        binding.pixelStorei(GL_UNPACK_ALIGNMENT, GL_UNPACK_ALIGNMENT_DEFAULT)
        binding.pixelStorei(GL_UNPACK_ROW_LENGTH, 0)
        binding.pixelStorei(GL_UNPACK_SKIP_ROWS, 0)
        binding.pixelStorei(GL_UNPACK_SKIP_PIXELS, 0)
        binding.bindBuffer(GL_PIXEL_UNPACK_BUFFER, 0)

        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, surface.framebuffer)
        binding.viewport(0, 0, surface.descriptor.widthPixels, surface.descriptor.heightPixels)
        binding.disable(GL_SCISSOR_TEST)
        binding.colorMask(true, true, true, true)
        binding.depthMask(true)
        binding.clearColor(0.0f, 0.0f, 0.0f, 0.0f)
        binding.clearDepthf(REVERSE_Z_FAR_DEPTH)
        binding.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        binding.enable(GL_DEPTH_TEST)
        binding.depthFunc(GL_GREATER)
        binding.frontFace(GL_CCW)
        binding.cullFace(GL_BACK)
        profile.setFramebufferSrgb(binding, enabled = false)

        content.draw(binding)

        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer.value.toInt())
        binding.viewport(0, 0, surface.descriptor.widthPixels, surface.descriptor.heightPixels)
        binding.disable(GL_DEPTH_TEST)
        binding.depthMask(false)
        binding.disable(GL_CULL_FACE)
        binding.disable(GL_SCISSOR_TEST)
        binding.colorMask(true, true, true, true)
        binding.enable(GL_BLEND)
        binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
        binding.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        binding.activeTexture(GL_TEXTURE0)
        binding.bindTexture(GL_TEXTURE_2D, surface.colourTexture)
        binding.bindSampler(0, 0)
        binding.useProgram(composite.program)
        if (composite.sourceUniformLocation >= 0) {
            binding.uniform1i(composite.sourceUniformLocation, 0)
        }
        binding.bindVertexArray(composite.vertexArray)
        binding.drawArrays(GL_TRIANGLE_STRIP, 0, 4)

        if (GlErrorQueue.firstOwnError(binding) == GL_NO_ERROR) {
            null
        } else {
            glOperationFailure(PipelineStage.DRAW, resourceKey = null)
        }
    }
}

private fun RenderContextProfile.setFramebufferSrgb(binding: GlBinding, enabled: Boolean) {
    if (!supportsSrgbWriteControl) return
    if (enabled) binding.enable(GL_FRAMEBUFFER_SRGB) else binding.disable(GL_FRAMEBUFFER_SRGB)
}
```

`GL_FRAMEBUFFER_SRGB` arrives **enabled** on Mesa's ES context and **disabled** on its desktop core context — a pixel-affecting difference between two contexts on the same machine — so RenG sets it explicitly rather than inheriting it, and the surrounding guard restores the caller's value. It is set to disabled because the colour attachment is a linear `GL_RGBA8` texture and an sRGB write conversion would encode those texels twice. On an ES context lacking `GL_EXT_sRGB_write_control` the token is not queryable at all, so RenG neither reads nor writes it and the snapshot records `null`.

- [ ] **Step 5: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.CompositePipelineTest" \
  --tests "com.rohittp.reng.internal.gl.GlFrameDrawerTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.gl.CompositePipelineTest" \
  --tests "com.rohittp.reng.internal.gl.GlFrameDrawerTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/CompositePipeline.kt \
  kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlFrameDrawer.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/CompositePipelineTest.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlFrameDrawerTest.kt
git commit -m "feat: composite RenG's offscreen surface into the caller's target"
```

---

### Task 15: The GL Object Registry and the Render Context Identity Seam

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlObjectRegistry.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/RenderContextIdentity.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlObjectRegistryTest.kt`

**Interfaces:**
- Consumes: Cycle B's `DeletionId`, `DeferredDeletion`, and `ExactContextFact`; Task 2's seam.
- Produces: `GlObjectType`, `GlObjectHandle`, `GlObjectRegistry`, `deleteGlObjects`, `RenderContextIdentity`, `RenderContextProbe`, and `exactContextFact`.

> **Why the context probe is injected rather than implemented here.** ADR 0001 states that RenG never references CGL, EAGL, EGL, `NSOpenGLContext`, or `ANativeWindow`, and the Cycle D research repeats it for the exact call this would need: `platform.EAGL` is context management and RenG must not touch it, and `eglGetCurrentContext` "is the fixture's business rather than RenG's". So Cycle D defines the identity type, the probe seam, and the comparison that turns a probe result into an `ExactContextFact`, and the only implementations in this cycle are the two conformance fixtures. Carrying a probe into production belongs to the cycle that adds public renderer construction, which Cycle D explicitly does not.

- [ ] **Step 1: Write the registry and probe tests**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.internal.lifecycle.DeletionId
import com.rohittp.reng.internal.lifecycle.ExactContextFact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlObjectRegistryTest {
    private val surfaceKey = ResourceKey(ResourceKind.OFFSCREEN_SURFACE, "a".repeat(64), null)
    private val pipelineKey = ResourceKey(ResourceKind.INTERNAL_PIPELINE, "b".repeat(64), null)

    @Test fun liveHandlesAreReportedUntilTheyAreDeferred() {
        val registry = GlObjectRegistry()
        assertFalse(registry.hasLiveGpuObjects())
        registry.register(surfaceKey, listOf(GlObjectHandle(GlObjectType.TEXTURE, 4)))
        assertTrue(registry.hasLiveGpuObjects())
        val deferred = registry.defer(surfaceKey, DeletionId(1L))
        assertEquals(surfaceKey, deferred?.resourceKey)
        assertFalse(registry.hasLiveGpuObjects())
        assertEquals(listOf(GlObjectHandle(GlObjectType.TEXTURE, 4)), registry.takeQueued(DeletionId(1L)))
    }

    @Test fun gpuObjectLossForgetsEveryHandleAndIssuesNoDelete() {
        val binding = RecordingGlBinding()
        val registry = GlObjectRegistry()
        registry.register(surfaceKey, listOf(GlObjectHandle(GlObjectType.FRAMEBUFFER, 3)))
        registry.register(pipelineKey, listOf(GlObjectHandle(GlObjectType.PROGRAM, 9)))
        registry.defer(pipelineKey, DeletionId(2L))
        registry.forgetEverything()
        assertFalse(registry.hasLiveGpuObjects())
        assertTrue(registry.liveKeys().isEmpty())
        assertTrue(registry.takeQueued(DeletionId(2L)).isEmpty())
        assertTrue(binding.log.isEmpty())
    }

    @Test fun theDeleterGroupsByTypeAndSkipsEmptyGroups() {
        val binding = RecordingGlBinding()
        deleteGlObjects(
            binding,
            listOf(
                GlObjectHandle(GlObjectType.TEXTURE, 1),
                GlObjectHandle(GlObjectType.TEXTURE, 2),
                GlObjectHandle(GlObjectType.PROGRAM, 5),
            ),
        )
        assertEquals(1, binding.log.count { it.startsWith("deleteTextures(2") })
        assertEquals(1, binding.log.count { it.startsWith("deleteProgram(5") })
        assertTrue(binding.log.none { it.startsWith("deleteBuffers") })
        assertTrue(binding.log.none { it.startsWith("deleteRenderbuffers") })
    }

    @Test fun deletingNothingIssuesNothing() {
        val binding = RecordingGlBinding()
        deleteGlObjects(binding, emptyList())
        assertTrue(binding.log.isEmpty())
    }

    @Test fun theProbeDistinguishesExactMissingAndForeignContexts() {
        val adopted = RenderContextIdentity(0x1000L)
        assertEquals(
            ExactContextFact.EXACT,
            exactContextFact(adopted) { RenderContextIdentity(0x1000L) },
        )
        assertEquals(ExactContextFact.NONE, exactContextFact(adopted) { null })
        assertEquals(
            ExactContextFact.DIFFERENT,
            exactContextFact(adopted) { RenderContextIdentity(0x2000L) },
        )
    }
}
```

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.GlObjectRegistryTest"
```

Expected: compilation fails because neither the registry nor the identity seam exists.

- [ ] **Step 3: Implement the registry and the deleter**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.ResourceKey
import com.rohittp.reng.internal.lifecycle.DeferredDeletion
import com.rohittp.reng.internal.lifecycle.DeletionId

internal enum class GlObjectType {
    TEXTURE,
    RENDERBUFFER,
    FRAMEBUFFER,
    BUFFER,
    VERTEX_ARRAY,
    SAMPLER,
    PROGRAM,
}

internal data class GlObjectHandle(val type: GlObjectType, val name: Int)

internal class GlObjectRegistry {
    private val live: LinkedHashMap<ResourceKey, MutableList<GlObjectHandle>> = LinkedHashMap()
    private val queued: LinkedHashMap<DeletionId, List<GlObjectHandle>> = LinkedHashMap()

    internal fun register(key: ResourceKey, handles: List<GlObjectHandle>) {
        live.getOrPut(key) { mutableListOf() }.addAll(handles)
    }

    internal fun handles(key: ResourceKey): List<GlObjectHandle> = ArrayList(live[key].orEmpty())

    internal fun liveKeys(): List<ResourceKey> = ArrayList(live.keys)

    internal fun hasLiveGpuObjects(): Boolean = live.values.any { it.isNotEmpty() }

    internal fun defer(key: ResourceKey, id: DeletionId): DeferredDeletion? {
        val handles = live.remove(key) ?: return null
        queued[id] = ArrayList(handles)
        return DeferredDeletion(id = id, resourceKey = key)
    }

    internal fun takeQueued(id: DeletionId): List<GlObjectHandle> = queued.remove(id).orEmpty()

    /**
     * Declared GPU object loss: forget every live and queued handle without issuing a delete.
     *
     * A replacement context cannot delete handles from the lost one, and object names there may
     * refer to unrelated live state, so this method must never call the binding.
     */
    internal fun forgetEverything() {
        live.clear()
        queued.clear()
    }
}

internal fun deleteGlObjects(binding: GlBinding, handles: List<GlObjectHandle>) {
    if (handles.isEmpty()) return
    val byType = handles.groupBy { it.type }
    byType[GlObjectType.FRAMEBUFFER]?.let { binding.deleteFramebuffers(it.size, it.names()) }
    byType[GlObjectType.RENDERBUFFER]?.let { binding.deleteRenderbuffers(it.size, it.names()) }
    byType[GlObjectType.TEXTURE]?.let { binding.deleteTextures(it.size, it.names()) }
    byType[GlObjectType.SAMPLER]?.let { binding.deleteSamplers(it.size, it.names()) }
    byType[GlObjectType.VERTEX_ARRAY]?.let { binding.deleteVertexArrays(it.size, it.names()) }
    byType[GlObjectType.BUFFER]?.let { binding.deleteBuffers(it.size, it.names()) }
    byType[GlObjectType.PROGRAM]?.forEach { binding.deleteProgram(it.name) }
}

private fun List<GlObjectHandle>.names(): IntArray = IntArray(size) { this[it].name }
```

Framebuffers and renderbuffers are deleted before the texture they reference so no attachment outlives its owner, and empty groups are skipped entirely rather than passed a zero-length array: on the native implementations a zero-length array reaches `addressOf(0)`, which throws.

- [ ] **Step 4: Implement the identity seam**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.lifecycle.ExactContextFact
import kotlin.jvm.JvmInline

/**
 * An opaque identity for the caller's already-current Render Context.
 *
 * RenG never creates, makes current, or destroys a context, and never references CGL, EAGL, EGL,
 * `NSOpenGLContext`, or `ANativeWindow` (ADR 0001). The value is whatever the supplier considers
 * the context's identity — a pointer on the platforms measured — and RenG only compares it.
 */
@JvmInline
internal value class RenderContextIdentity(val value: Long)

internal fun interface RenderContextProbe {
    fun currentContextIdentity(): RenderContextIdentity?
}

internal fun exactContextFact(
    adopted: RenderContextIdentity,
    probe: RenderContextProbe,
): ExactContextFact = when (probe.currentContextIdentity()) {
    null -> ExactContextFact.NONE
    adopted -> ExactContextFact.EXACT
    else -> ExactContextFact.DIFFERENT
}
```

A renderer is affine to the exact context identity it captured, not merely to a share group, so "no current context" and "a different current context" stay distinct facts and each maps to its own typed failure inside the state machine (ADR 0015).

- [ ] **Step 5: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.GlObjectRegistryTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.gl.GlObjectRegistryTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlObjectRegistry.kt \
  kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/RenderContextIdentity.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlObjectRegistryTest.kt
git commit -m "feat: track GL handles and the adopted context identity"
```

---

### Task 16: Drive Cycle B's Lifecycle State Machine with Real GL Facts

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlLifecycleDriver.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlLifecycleDriverTest.kt`

**Interfaces:**
- Consumes: `RendererLifecycleStateMachine.begin`/`resume`, `RendererLifecycleSnapshot`, `RendererLifecycleOperation`, `RendererLifecycleAction`, `RendererLifecycleObservation`, `RendererLifecycleOutcome`, `GpuLedger`, `RendererOwnerState`, `FramebufferFact`, `AdoptionContextFact`; Tasks 8, 9, 13, 14, 15.
- Produces: `PermittedOperationExecutor`, `RenderCallBarrier`, `PreparationController`, and `GlLifecycleDriver`.

`RendererLifecycleStateMachine` already owns the three owner states and the total operation and error precedence from supplied facts. This driver supplies those facts and executes the actions the machine emits. **It re-decides nothing**: there is no `when` here that duplicates a decision the reducer already makes, and no place where a GL result overrides a machine outcome.

- [ ] **Step 1: Write the action-execution tests**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.FramebufferName
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceSelector
import com.rohittp.reng.internal.lifecycle.DeletionId
import com.rohittp.reng.internal.lifecycle.GpuLedger
import com.rohittp.reng.internal.lifecycle.PreparedFrameFact
import com.rohittp.reng.internal.lifecycle.RenderTargetFact
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOperation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOutcome
import com.rohittp.reng.internal.lifecycle.RendererLifecycleSnapshot
import com.rohittp.reng.internal.lifecycle.RendererOwnerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlLifecycleDriverTest {
    private val surfaceKey = ResourceKey(ResourceKind.OFFSCREEN_SURFACE, "c".repeat(64), null)
    private val adopted = RenderContextIdentity(0x4000L)

    @Test fun freeingWithoutACurrentContextFailsWithoutDeletingAnything() {
        val world = driverWorld(currentContext = null, liveHandles = true)
        val outcome = world.driver.run(
            RendererLifecycleOperation.FreeResources(ResourceSelector.All),
        ) { null }
        val failed = outcome as RendererLifecycleOutcome.Failed
        assertEquals(RenGErrorCode.NO_CURRENT_RENDER_CONTEXT, failed.failure.code)
        assertTrue(world.binding.log.none { it.startsWith("delete") })
        assertTrue(world.registry.hasLiveGpuObjects())
    }

    @Test fun freeingUnderADifferentContextFailsWithoutChangingState() {
        val world = driverWorld(currentContext = RenderContextIdentity(0x9999L), liveHandles = true)
        val before = world.driver.snapshot
        val outcome = world.driver.run(
            RendererLifecycleOperation.FreeResources(ResourceSelector.All),
        ) { null }
        assertEquals(
            RenGErrorCode.DIFFERENT_CURRENT_RENDER_CONTEXT,
            (outcome as RendererLifecycleOutcome.Failed).failure.code,
        )
        assertEquals(before, world.driver.snapshot)
        assertTrue(world.binding.log.none { it.startsWith("delete") })
    }

    @Test fun deferredDeletionsDrainUnderTheExactContextBeforeTheOperationRuns() {
        val world = driverWorld(currentContext = adopted, liveHandles = true)
        world.registry.defer(surfaceKey, DeletionId(1L))
        world.setLedger(deferred = listOf(DeletionId(1L) to surfaceKey))
        var executed = false
        val outcome = world.driver.run(
            RendererLifecycleOperation.FreeResources(ResourceSelector.All),
        ) {
            executed = true
            assertTrue(world.binding.log.any { it.startsWith("deleteTextures") })
            null
        }
        assertEquals(RendererLifecycleOutcome.Succeeded, outcome)
        assertTrue(executed)
        assertTrue(world.driver.snapshot.gpuLedger.deferredDeletions.isEmpty())
    }

    @Test fun aFailedDeferredDeletionStopsBeforeTheOperation() {
        val world = driverWorld(currentContext = adopted, liveHandles = true)
        world.registry.defer(surfaceKey, DeletionId(1L))
        world.setLedger(deferred = listOf(DeletionId(1L) to surfaceKey))
        world.binding.errorQueue = mutableListOf(GL_NO_ERROR, GL_OUT_OF_MEMORY, GL_NO_ERROR)
        var executed = false
        val outcome = world.driver.run(
            RendererLifecycleOperation.FreeResources(ResourceSelector.All),
        ) { executed = true; null }
        assertEquals(
            RenGErrorCode.GPU_OPERATION_FAILED,
            (outcome as RendererLifecycleOutcome.Failed).failure.code,
        )
        assertTrue(!executed)
    }

    @Test fun gpuObjectLossForgetsHandlesAndProgramsWithoutOneGlCall() {
        val world = driverWorld(currentContext = null, liveHandles = true)
        world.programs.getOrCompile(
            world.binding, ShaderDialect.GLES, geometryKey(), vertexPlan(), fragmentPlan(),
        )
        world.binding.log.clear()
        val outcome = world.driver.run(RendererLifecycleOperation.NotifyGpuObjectsGone) { null }
        assertEquals(RendererLifecycleOutcome.Succeeded, outcome)
        assertEquals(RendererOwnerState.AWAITING_CONTEXT_ADOPTION, world.driver.snapshot.ownerState)
        assertTrue(world.binding.log.isEmpty())
        assertTrue(!world.registry.hasLiveGpuObjects())
        assertTrue(world.programs.keys().isEmpty())
        assertNull(world.driver.adoptedContext)
    }

    @Test fun adoptionAcceptsASupportedContextAndRejectsAnUnsupportedOne() {
        val world = driverWorld(currentContext = RenderContextIdentity(0x7000L), lost = true)
        val outcome = world.driver.run(RendererLifecycleOperation.AdoptCurrentRenderContext) { null }
        assertEquals(RendererLifecycleOutcome.Succeeded, outcome)
        assertEquals(RendererOwnerState.LIVE, world.driver.snapshot.ownerState)
        assertEquals(RenderContextIdentity(0x7000L), world.driver.adoptedContext)
        assertEquals(ShaderDialect.GLES, world.driver.profile?.dialect)

        val legacy = driverWorld(currentContext = RenderContextIdentity(0x7000L), lost = true)
        legacy.binding.strings[GL_VERSION] = "2.1 INTEL-16.4.5"
        legacy.binding.strings[GL_SHADING_LANGUAGE_VERSION] = "1.20"
        val rejected = legacy.driver.run(RendererLifecycleOperation.AdoptCurrentRenderContext) { null }
        assertEquals(
            RenGErrorCode.UNSUPPORTED_RENDER_CONTEXT,
            (rejected as RendererLifecycleOutcome.Failed).failure.code,
        )
        assertEquals(RendererOwnerState.AWAITING_CONTEXT_ADOPTION, legacy.driver.snapshot.ownerState)
    }

    @Test fun mintingValidatesTheFramebufferAndRestoresThePreviousBinding() {
        val world = driverWorld(currentContext = adopted)
        world.binding.integers[GL_DRAW_FRAMEBUFFER_BINDING] = intArrayOf(12)
        val outcome = world.driver.run(
            RendererLifecycleOperation.MintRenderTarget(FramebufferName(5u)),
        ) { null }
        assertEquals(RendererLifecycleOutcome.Succeeded, outcome)
        assertEquals("bindFramebuffer(0x8CA9,12)", world.binding.log.last { it.startsWith("bindFramebuffer") })
    }

    @Test fun anIncompleteFramebufferIsAnInvalidRenderTarget() {
        val world = driverWorld(currentContext = adopted)
        world.binding.framebufferStatus = 0x8CD6
        val outcome = world.driver.run(
            RendererLifecycleOperation.MintRenderTarget(FramebufferName(5u)),
        ) { null }
        assertEquals(
            RenGErrorCode.INVALID_RENDER_TARGET,
            (outcome as RendererLifecycleOutcome.Failed).failure.code,
        )
    }

    @Test fun drawingRunsTheExecutorOnlyAfterProvenanceContextAndFramebufferChecks() {
        val world = driverWorld(currentContext = adopted)
        val order = mutableListOf<String>()
        val outcome = world.driver.run(
            RendererLifecycleOperation.Draw(
                frame = PreparedFrameFact.OwnedOpen,
                target = RenderTargetFact.OwnedCurrent(FramebufferName(5u)),
            ),
        ) { order += "execute"; null }
        assertEquals(RendererLifecycleOutcome.Succeeded, outcome)
        assertEquals(listOf("execute"), order)
        assertTrue(
            world.binding.log.indexOfFirst { it.startsWith("checkFramebufferStatus") } >= 0,
        )
    }

    @Test fun closingAfterLossIsContextFreeAndTerminal() {
        val world = driverWorld(currentContext = null, lost = true)
        val outcome = world.driver.run(RendererLifecycleOperation.CloseRenderer) { null }
        assertEquals(RendererLifecycleOutcome.Succeeded, outcome)
        assertEquals(RendererOwnerState.CLOSED, world.driver.snapshot.ownerState)
        assertTrue(world.binding.log.none { it.startsWith("delete") })
    }
}
```

Add a private `driverWorld(...)` helper building a `RecordingGlBinding` seeded like Task 8's ES context, a `GlObjectRegistry` optionally holding one texture handle under `surfaceKey`, a `GlProgramCache`, a probe returning the supplied identity, an initial `RendererLifecycleSnapshot` whose owner state is `LIVE` or `AWAITING_CONTEXT_ADOPTION`, and `setLedger` to install deferred entries into both the registry and the snapshot's `GpuLedger`. Add `geometryKey()`, `vertexPlan()`, and `fragmentPlan()` helpers reusing Task 12's sources.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.GlLifecycleDriverTest"
```

Expected: compilation fails because the driver does not exist.

- [ ] **Step 3: Implement the driver loop**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.FramebufferName
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.lifecycle.AdoptionContextFact
import com.rohittp.reng.internal.lifecycle.FramebufferFact
import com.rohittp.reng.internal.lifecycle.RendererLifecycleAction
import com.rohittp.reng.internal.lifecycle.RendererLifecycleCursor
import com.rohittp.reng.internal.lifecycle.RendererLifecycleObservation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOperation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOutcome
import com.rohittp.reng.internal.lifecycle.RendererLifecycleSnapshot
import com.rohittp.reng.internal.lifecycle.RendererLifecycleStateMachine
import com.rohittp.reng.internal.lifecycle.RendererOwnerState
import com.rohittp.reng.PipelineStage

internal fun interface PermittedOperationExecutor {
    fun execute(operation: RendererLifecycleOperation): FailureDescriptor?
}

internal fun interface RenderCallBarrier {
    fun awaitRenderCallQuiescence()
}

internal fun interface PreparationController {
    fun requestCancellationAndAwaitTermination()
}

internal class GlLifecycleDriver(
    private val binding: GlBinding,
    private val probe: RenderContextProbe,
    private val registry: GlObjectRegistry,
    private val programs: GlProgramCache,
    private val barrier: RenderCallBarrier = RenderCallBarrier { },
    private val preparation: PreparationController = PreparationController { },
    initialSnapshot: RendererLifecycleSnapshot,
    initialContext: RenderContextIdentity? = null,
    initialProfile: RenderContextProfile? = null,
) {
    var snapshot: RendererLifecycleSnapshot = initialSnapshot
        private set

    var adoptedContext: RenderContextIdentity? = initialContext
        private set

    var profile: RenderContextProfile? = initialProfile
        private set

    internal fun run(
        operation: RendererLifecycleOperation,
        executor: PermittedOperationExecutor,
    ): RendererLifecycleOutcome {
        var transition = RendererLifecycleStateMachine.begin(snapshot, operation)
        while (true) {
            snapshot = transition.snapshot
            val outcome = transition.outcome
            if (outcome != null) {
                applyTerminal(operation, outcome)
                return outcome
            }
            val cursor = requireNotNull(transition.cursor) { "a transition has a cursor or an outcome" }
            val observation = perform(transition.actions.single(), executor)
            transition = RendererLifecycleStateMachine.resume(cursor, observation)
        }
    }

    private fun perform(
        action: RendererLifecycleAction,
        executor: PermittedOperationExecutor,
    ): RendererLifecycleObservation = when (action) {
        RendererLifecycleAction.AwaitRenderCallQuiescence -> {
            barrier.awaitRenderCallQuiescence()
            RendererLifecycleObservation.RenderCallsQuiesced
        }

        RendererLifecycleAction.ObserveExactCurrentContext ->
            RendererLifecycleObservation.ExactContextObserved(
                adoptedContext?.let { exactContextFact(it, probe) }
                    ?: com.rohittp.reng.internal.lifecycle.ExactContextFact.NONE,
            )

        RendererLifecycleAction.ObserveAdoptableCurrentContext -> observeAdoption()

        is RendererLifecycleAction.DeleteDeferred -> {
            GlErrorQueue.drainOnEntry(binding)
            deleteGlObjects(binding, registry.takeQueued(action.deletion.id))
            if (GlErrorQueue.firstOwnError(binding) == GL_NO_ERROR) {
                RendererLifecycleObservation.DeferredDeletionAcknowledged(action.deletion.id)
            } else {
                RendererLifecycleObservation.DeferredDeletionFailed(
                    deletionId = action.deletion.id,
                    failure = glOperationFailure(
                        PipelineStage.RESOURCE_FREE,
                        action.deletion.resourceKey,
                    ),
                )
            }
        }

        is RendererLifecycleAction.ValidateFramebuffer ->
            RendererLifecycleObservation.FramebufferObserved(framebufferFact(action.framebufferName))

        RendererLifecycleAction.RequestPreparationCancellation -> {
            preparation.requestCancellationAndAwaitTermination()
            RendererLifecycleObservation.PreparationTerminated
        }

        is RendererLifecycleAction.ExecutePermittedOperation ->
            executor.execute(action.operation)
                ?.let { RendererLifecycleObservation.PermittedOperationFailed(it) }
                ?: RendererLifecycleObservation.PermittedOperationSucceeded
    }
}
```

The loop is total and mechanical, and the two non-GL actions are delegated to injected seams because Cycle D owns no threads and no preparation engine: `RenderCallBarrier` and `PreparationController` default to no-ops here and are supplied for real by the cycle that adds renderer construction.

- [ ] **Step 4: Implement adoption, framebuffer validation, and the terminal mirror**

```kotlin
    private fun observeAdoption(): RendererLifecycleObservation {
        val identity = probe.currentContextIdentity()
            ?: return RendererLifecycleObservation.AdoptionContextObserved(AdoptionContextFact.NONE)
        return when (val adoption = adoptRenderContext(binding)) {
            is RenderContextAdoption.Adopted -> {
                pendingContext = identity
                pendingProfile = adoption.profile
                RendererLifecycleObservation.AdoptionContextObserved(AdoptionContextFact.SUPPORTED)
            }

            is RenderContextAdoption.Rejected -> {
                pendingContext = null
                pendingProfile = null
                RendererLifecycleObservation.AdoptionContextObserved(AdoptionContextFact.UNSUPPORTED)
            }
        }
    }

    private fun framebufferFact(framebufferName: FramebufferName): FramebufferFact {
        val name = framebufferName.value.toInt()
        if (name != 0 && !binding.isFramebuffer(name)) return FramebufferFact.MISSING_OR_INCOMPLETE
        val previous = IntArray(1)
        binding.getIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, previous)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, name)
        val status = binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER)
        binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, previous[0])
        return if (status == GL_FRAMEBUFFER_COMPLETE) {
            FramebufferFact.COMPLETE
        } else {
            FramebufferFact.MISSING_OR_INCOMPLETE
        }
    }

    private fun applyTerminal(
        operation: RendererLifecycleOperation,
        outcome: RendererLifecycleOutcome,
    ) {
        if (outcome !is RendererLifecycleOutcome.Succeeded) return
        when (operation) {
            RendererLifecycleOperation.NotifyGpuObjectsGone -> forgetWithoutDeleting()
            RendererLifecycleOperation.AdoptCurrentRenderContext -> {
                forgetWithoutDeleting()
                adoptedContext = pendingContext
                profile = pendingProfile
            }

            RendererLifecycleOperation.CloseRenderer -> {
                forgetWithoutDeleting()
                adoptedContext = null
                profile = null
            }

            else -> Unit
        }
        if (snapshot.ownerState == RendererOwnerState.AWAITING_CONTEXT_ADOPTION) {
            adoptedContext = null
        }
    }

    private fun forgetWithoutDeleting() {
        registry.forgetEverything()
        programs.forgetAll()
    }

    private var pendingContext: RenderContextIdentity? = null
    private var pendingProfile: RenderContextProfile? = null
```

Three behaviours are what this mirror exists for. Losing the context is not freeing: `NotifyGpuObjectsGone` forgets live and queued handles, keeps every CPU-side value, and issues no GL call, because a replacement context cannot delete handles from the lost one (ADRs 0007 and 0015). Adoption forgets again before recording the new identity and profile, so no program compiled against the previous dialect can survive into a context with a different one. And closing while live handles exist has already passed exact-context validation and deferred-deletion draining inside the machine before the executor runs, so this mirror only clears bookkeeping.

- [ ] **Step 5: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.gl.GlLifecycleDriverTest" \
  --tests "com.rohittp.reng.internal.lifecycle.RendererLifecycleMatrixTest" \
  --tests "com.rohittp.reng.internal.lifecycle.RendererLifecyclePrecedenceTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.gl.GlLifecycleDriverTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlLifecycleDriver.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlLifecycleDriverTest.kt
git commit -m "feat: drive the lifecycle machine with real GL observations"
```

Expected: the two pre-existing Cycle B lifecycle suites still pass untouched, which is the check that the driver drives the machine rather than replacing any part of it.

---

### Task 17: The Shared Conformance Suite Body

**Files:**
- Create: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlConformanceSuite.kt`

**Interfaces:**
- Consumes: every component from Tasks 8–16 and the real `GlBinding` a platform fixture supplies.
- Produces: `GlConformanceReport` and `runGlConformanceSuite(binding, probe, expectedDialect)`, called only from `linuxTest` and `macosTest`.

The suite body lives in `commonTest` so one set of assertions runs against both dialects and both binding implementations without a custom source set or a build-script change. It is compiled for every target and invoked on exactly two; `androidHostTest` and the iOS test compilations never call it, because they have no real context.

- [ ] **Step 1: Write the suite skeleton and its report**

```kotlin
package com.rohittp.reng.internal.gl

import com.rohittp.reng.FramebufferName
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.lifecycle.DeletionId
import com.rohittp.reng.internal.lifecycle.GpuLedger
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOperation
import com.rohittp.reng.internal.lifecycle.RendererLifecycleOutcome
import com.rohittp.reng.internal.lifecycle.RendererLifecycleSnapshot
import com.rohittp.reng.internal.lifecycle.RendererOwnerState
import com.rohittp.reng.internal.shader.scanShaderProfile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val CONFORMANCE_SURFACE_PIXELS: Int = 64
internal const val CONFORMANCE_TEXTURE_UNITS: Int = 2

internal data class GlConformanceReport(
    val dialect: ShaderDialect,
    val rendererName: String,
    val versionText: String,
    val shadingLanguageVersionText: String,
    val checks: List<String>,
)

internal fun runGlConformanceSuite(
    binding: GlBinding,
    probe: RenderContextProbe,
    expectedDialect: ShaderDialect,
): GlConformanceReport {
    val checks = mutableListOf<String>()

    val profile = assertContextAdoption(binding, expectedDialect)
    checks += "context-adoption"
    assertEntryPointInventory(binding)
    checks += "entry-point-inventory"
    assertErrorQueueIsDestructive(binding)
    checks += "error-queue"
    assertStateRoundTripIsExact(binding, profile)
    checks += "state-round-trip"
    assertShaderDialectMatrix(binding, profile)
    checks += "shader-dialect-matrix"
    assertOffscreenCompositeAndRestore(binding, profile)
    checks += "offscreen-composite"
    assertLifecycleUnderARealContext(binding, probe, profile)
    checks += "lifecycle"

    return GlConformanceReport(
        dialect = profile.dialect,
        rendererName = profile.rendererName,
        versionText = binding.getString(GL_VERSION).orEmpty(),
        shadingLanguageVersionText = profile.shadingLanguageVersionText,
        checks = checks,
    )
}

private fun assertContextAdoption(
    binding: GlBinding,
    expectedDialect: ShaderDialect,
): RenderContextProfile {
    val adoption = adoptRenderContext(binding)
    val adopted = adoption as? RenderContextAdoption.Adopted
        ?: throw AssertionError("the fixture context must satisfy the ES 3.0 requirement")
    val profile = adopted.profile
    assertEquals(expectedDialect, profile.dialect, "the fixture created a ${expectedDialect} context")
    assertTrue(profile.rendererName.isNotBlank(), "GL_RENDERER must identify the driver")
    assertTrue(profile.maxTextureSize >= CONFORMANCE_SURFACE_PIXELS)
    assertEquals(GL_NO_ERROR, GlErrorQueue.firstOwnError(binding), "adoption must leave no error flag")
    return profile
}
```

`expectedDialect` is supplied by the fixture that created the context, so the assertion compares what RenG detected at runtime against what the fixture actually asked the driver for. That is the whole proof that detection is a runtime query: the same binary, on the same target, is run twice on Linux with two different answers.

- [ ] **Step 2: Write the inventory and error-queue checks**

```kotlin
private fun assertEntryPointInventory(binding: GlBinding) {
    assertEquals(84, GlEntryPoint.entries.size)
    assertTrue(binding.getString(GL_VENDOR)?.isNotBlank() == true)

    val names = IntArray(1)
    binding.genVertexArrays(1, names)
    val vertexArray = names[0]
    binding.bindVertexArray(vertexArray)

    binding.genSamplers(1, names)
    val sampler = names[0]
    binding.bindSampler(0, sampler)
    binding.samplerParameteri(sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
    binding.bindSampler(0, 0)

    binding.genTextures(1, names)
    val texture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, texture)
    binding.texStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 4, 4)

    val framebuffers = IntArray(2)
    binding.genFramebuffers(2, framebuffers)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffers[0])
    binding.framebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    binding.drawBuffers(1, intArrayOf(GL_COLOR_ATTACHMENT0))
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, framebuffers[0])
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffers[1])
    binding.blitFramebuffer(0, 0, 4, 4, 0, 0, 4, 4, GL_COLOR_BUFFER_BIT, GL_NEAREST)

    assertTrue(binding.isFramebuffer(framebuffers[0]))
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    binding.bindVertexArray(0)
    binding.deleteFramebuffers(2, framebuffers)
    binding.deleteTextures(1, intArrayOf(texture))
    binding.deleteSamplers(1, intArrayOf(sampler))
    binding.deleteVertexArrays(1, intArrayOf(vertexArray))

    // The second blit target is intentionally incomplete on some drivers, so tolerate that one
    // flag and require the rest of the sequence to be clean.
    val flag = GlErrorQueue.firstOwnError(binding)
    assertTrue(
        flag == GL_NO_ERROR || flag == GL_INVALID_FRAMEBUFFER_OPERATION,
        "the ES-3 entry points must execute without an unexpected error flag",
    )
}

private fun assertErrorQueueIsDestructive(binding: GlBinding) {
    GlErrorQueue.drainOnEntry(binding)
    val out = IntArray(1)
    binding.getIntegerv(UNDEFINED_GL_TOKEN, out)
    assertEquals(GL_INVALID_ENUM, binding.getError(), "a provoked flag reads once")
    assertEquals(GL_NO_ERROR, binding.getError(), "and is gone thereafter")

    binding.getIntegerv(UNDEFINED_GL_TOKEN, out)
    assertEquals(
        GL_INVALID_ENUM,
        GlErrorQueue.drainOnEntry(binding),
        "the drain reports the consumer's pre-existing flag",
    )
    assertEquals(GL_NO_ERROR, GlErrorQueue.drainOnEntry(binding), "and consumes it")
}

private const val UNDEFINED_GL_TOKEN: Int = 0x7FFF
```

`assertErrorQueueIsDestructive` is the executable form of the specification's stated exception: RenG consumes the caller's error queue, and this proves both that the flag cannot be pushed back and that the drain attributes it to the consumer rather than converting it into a RenG failure.

- [ ] **Step 3: Write the state round trip**

```kotlin
private fun assertStateRoundTripIsExact(binding: GlBinding, profile: RenderContextProfile) {
    val scratch = createScratchState(binding)
    GlErrorQueue.drainOnEntry(binding)

    perturbRestoredState(binding, profile, scratch, variant = 0)
    val captured = captureGlState(binding, profile, CONFORMANCE_TEXTURE_UNITS)
    assertEquals(
        GL_NO_ERROR,
        GlErrorQueue.firstOwnError(binding),
        "capture must query only tokens valid on this dialect",
    )

    perturbRestoredState(binding, profile, scratch, variant = 1)
    val different = captureGlState(binding, profile, CONFORMANCE_TEXTURE_UNITS)
    assertTrue(captured != different, "the perturbation must actually change every captured item")

    restoreGlState(binding, captured)
    assertEquals(
        captured,
        captureGlState(binding, profile, CONFORMANCE_TEXTURE_UNITS),
        "save, perturb, and restore must be byte-exact",
    )
    assertEquals(GL_NO_ERROR, GlErrorQueue.firstOwnError(binding))

    if (profile.dialect == ShaderDialect.GLES) {
        assertNull(captured.drawBuffer)
        assertNull(captured.lineSmoothEnabled)
    } else {
        assertNotNull(captured.drawBuffer)
        assertNotNull(captured.lineSmoothEnabled)
    }

    deleteScratchState(binding, scratch)
}

private class ScratchGlState(
    val texture: Int,
    val secondTexture: Int,
    val sampler: Int,
    val buffer: Int,
    val vertexArray: Int,
    val framebuffer: Int,
    val renderbuffer: Int,
)

private fun perturbRestoredState(
    binding: GlBinding,
    profile: RenderContextProfile,
    scratch: ScratchGlState,
    variant: Int,
) {
    val bias = variant.toFloat()
    binding.activeTexture(GL_TEXTURE0 + 1)
    binding.bindTexture(GL_TEXTURE_2D, if (variant == 0) scratch.texture else scratch.secondTexture)
    binding.bindSampler(1, if (variant == 0) scratch.sampler else 0)
    binding.activeTexture(GL_TEXTURE0 + variant)

    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, if (variant == 0) scratch.framebuffer else 0)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, if (variant == 0) scratch.framebuffer else 0)
    binding.bindRenderbuffer(GL_RENDERBUFFER, if (variant == 0) scratch.renderbuffer else 0)
    binding.bindVertexArray(if (variant == 0) scratch.vertexArray else 0)
    binding.bindBuffer(GL_ARRAY_BUFFER, if (variant == 0) scratch.buffer else 0)
    binding.bindBuffer(GL_PIXEL_UNPACK_BUFFER, if (variant == 0) scratch.buffer else 0)
    binding.bindBuffer(GL_UNIFORM_BUFFER, if (variant == 0) scratch.buffer else 0)

    if (variant == 0) binding.enable(GL_BLEND) else binding.disable(GL_BLEND)
    binding.blendFuncSeparate(
        if (variant == 0) GL_SRC_ALPHA else GL_ONE,
        GL_ONE_MINUS_SRC_ALPHA,
        GL_ONE,
        if (variant == 0) GL_ZERO else GL_ONE,
    )
    binding.blendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD)
    binding.blendColor(0.25f + bias * 0.5f, 0.5f, 0.75f, 1.0f)

    if (variant == 0) binding.enable(GL_DEPTH_TEST) else binding.disable(GL_DEPTH_TEST)
    binding.depthFunc(if (variant == 0) GL_GREATER else GL_LESS)
    binding.depthMask(variant == 0)
    binding.depthRangef(0.25f * (variant + 1), 0.75f)
    binding.clearDepthf(0.125f * (variant + 1))

    if (variant == 0) binding.enable(GL_CULL_FACE) else binding.disable(GL_CULL_FACE)
    binding.cullFace(GL_BACK)
    binding.frontFace(if (variant == 0) GL_CCW else 0x0900)

    binding.viewport(variant, variant, 16 + variant, 24 + variant)
    if (variant == 0) binding.enable(GL_SCISSOR_TEST) else binding.disable(GL_SCISSOR_TEST)
    binding.scissor(variant, variant, 8 + variant, 12 + variant)
    binding.colorMask(true, variant == 0, true, variant == 0)
    binding.clearColor(0.1f * (variant + 1), 0.2f, 0.3f, 0.4f)

    binding.pixelStorei(GL_UNPACK_ALIGNMENT, if (variant == 0) 1 else 8)
    binding.pixelStorei(GL_UNPACK_ROW_LENGTH, 3 + variant)
    binding.pixelStorei(GL_UNPACK_SKIP_ROWS, variant)
    binding.pixelStorei(GL_UNPACK_SKIP_PIXELS, 1 + variant)
    binding.pixelStorei(GL_PACK_ALIGNMENT, if (variant == 0) 2 else 8)

    if (profile.supportsSrgbWriteControl) {
        if (variant == 0) binding.enable(GL_FRAMEBUFFER_SRGB) else binding.disable(GL_FRAMEBUFFER_SRGB)
    }
    if (profile.dialect == ShaderDialect.DESKTOP) {
        if (variant == 0) binding.enable(GL_LINE_SMOOTH) else binding.disable(GL_LINE_SMOOTH)
    }
    GlErrorQueue.drainOnEntry(binding)
}
```

Write `createScratchState` and `deleteScratchState` as the obvious generate-and-delete pairs for those seven objects, giving both textures `glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 4, 4)` storage, the renderbuffer `glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, 4, 4)`, and the framebuffer a colour attachment so it is complete. The perturbation deliberately sets the unpack alignment to `1` and then `8`, neither of which is the measured default of `4`, so a restore that assumed the default would be caught.

Note that a surfaceless context starts with viewport and scissor box `0,0,0,0`, so the round trip would be comparing zeroes if it did not set them first; `perturbRestoredState` sets both before anything is captured.

- [ ] **Step 4: Write the shader dialect matrix**

```kotlin
private const val CONFORMANCE_VERTEX_SOURCE: String =
    "#version 300 es\n" +
        "layout(location = 0) in vec3 rengConformancePosition;\n" +
        "uniform mat4 rengConformanceMatrix;\n" +
        "out vec2 rengConformanceUv;\n" +
        "void main() {\n" +
        "    rengConformanceUv = rengConformancePosition.xy;\n" +
        "    gl_Position = rengConformanceMatrix * vec4(rengConformancePosition, 1.0);\n" +
        "}\n"

private const val CONFORMANCE_FRAGMENT_SOURCE: String =
    "#version 300 es\n" +
        "precision mediump float;\n" +
        "uniform sampler2D rengConformanceTexture;\n" +
        "uniform int rengConformanceLevel;\n" +
        "in vec2 rengConformanceUv;\n" +
        "layout(location = 0) out vec4 rengConformanceColour;\n" +
        "void main() {\n" +
        "    vec2 size = vec2(textureSize(rengConformanceTexture, rengConformanceLevel));\n" +
        "    rengConformanceColour = texture(rengConformanceTexture, rengConformanceUv / max(size, vec2(1.0)));\n" +
        "}\n"

private fun assertShaderDialectMatrix(binding: GlBinding, profile: RenderContextProfile) {
    val pair = ShaderPair(CONFORMANCE_VERTEX_SOURCE, CONFORMANCE_FRAGMENT_SOURCE)
    val key = ResourceKeyDeriver().geometryProgram(pair).key
    val vertexPlan = requireNotNull(scanShaderProfile(CONFORMANCE_VERTEX_SOURCE))
    val fragmentPlan = requireNotNull(scanShaderProfile(CONFORMANCE_FRAGMENT_SOURCE))

    val matching = compileShaderProgram(binding, profile.dialect, key, vertexPlan, fragmentPlan)
    val linked = matching as? GlProgramResult.Linked
        ?: throw AssertionError("the ${profile.dialect} source must compile and link on this context")
    binding.deleteProgram(linked.program)

    val opposite = when (profile.dialect) {
        ShaderDialect.GLES -> ShaderDialect.DESKTOP
        ShaderDialect.DESKTOP -> ShaderDialect.GLES
    }
    var observedLog = ""
    val other = compileShaderProgram(binding, opposite, key, vertexPlan, fragmentPlan) { _, log ->
        observedLog = log
    }

    val oppositeShouldLink =
        profile.dialect == ShaderDialect.DESKTOP && profile.supportsEs3Compatibility
    if (oppositeShouldLink) {
        val tolerated = other as? GlProgramResult.Linked
            ?: throw AssertionError("a driver advertising $ES3_COMPATIBILITY_EXTENSION accepts 300 es")
        binding.deleteProgram(tolerated.program)
    } else {
        val failed = other as? GlProgramResult.Failed
            ?: throw AssertionError("the wrong directive must fail on this context")
        assertTrue(observedLog.isNotEmpty(), "the driver must explain its rejection to the observer")
        assertTrue(
            observedLog !in failed.failure.toString(),
            "the driver log must never cross the failure boundary",
        )
        assertEquals("shaderPair", assertNotNull(failed.failure.diagnostic).fieldName)
    }
    GlErrorQueue.drainOnEntry(binding)
}
```

This is the honest form of "the wrong substitution fails on each". `#version 330 core` fails on every ES context measured, which is unconditional. `#version 300 es` fails on Apple's 4.1, which does not advertise `GL_ARB_ES3_compatibility`, and **succeeds** on Mesa's 4.5 core profile, which does — so the expectation is keyed on the extension the driver actually advertises, not on a hope. RenG's own substitution rule stays blanket-on-desktop regardless, which is why `supportsEs3Compatibility` never appears in `sourceFor`.

- [ ] **Step 5: Write the composite and lifecycle checks**

```kotlin
private fun assertOffscreenCompositeAndRestore(binding: GlBinding, profile: RenderContextProfile) {
    val deriver = ResourceKeyDeriver()
    val descriptor = OffscreenSurfaceDescriptor(
        widthPixels = CONFORMANCE_SURFACE_PIXELS,
        heightPixels = CONFORMANCE_SURFACE_PIXELS,
        colourFormat = OffscreenColourFormat.RGBA8,
        depthFormat = OffscreenDepthFormat.DEPTH_COMPONENT24,
    )
    val surface = (
        createOffscreenSurface(binding, profile, deriver.offscreenSurface(descriptor).key, descriptor)
            as? OffscreenSurfaceResult.Created
        )?.surface ?: throw AssertionError("the offscreen surface must be framebuffer-complete")

    val cache = GlProgramCache()
    val composite = (
        createCompositePipeline(binding, profile.dialect, cache, deriver)
            as? CompositePipelineResult.Created
        )?.pipeline ?: throw AssertionError("the composite pipeline must link on this context")

    // A surfaceless context has no default framebuffer, and framebuffer zero then reports
    // GL_FRAMEBUFFER_UNDEFINED, so the target is an FBO this suite owns.
    val names = IntArray(1)
    binding.genTextures(1, names)
    val targetTexture = names[0]
    binding.bindTexture(GL_TEXTURE_2D, targetTexture)
    binding.texStorage2D(
        GL_TEXTURE_2D, 1, GL_RGBA8, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS,
    )
    binding.genFramebuffers(1, names)
    val targetFramebuffer = names[0]
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFramebuffer)
    binding.framebufferTexture2D(
        GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, targetTexture, 0,
    )
    assertEquals(GL_FRAMEBUFFER_COMPLETE, binding.checkFramebufferStatus(GL_DRAW_FRAMEBUFFER))
    binding.viewport(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
    binding.disable(GL_SCISSOR_TEST)
    binding.colorMask(true, true, true, true)
    binding.clearColor(1.0f, 0.0f, 0.0f, 1.0f)
    binding.clear(GL_COLOR_BUFFER_BIT)
    binding.bindFramebuffer(GL_DRAW_FRAMEBUFFER, 0)
    GlErrorQueue.drainOnEntry(binding)

    val before = captureGlState(binding, profile, CONFORMANCE_TEXTURE_UNITS)
    val failure = drawFrame(
        binding = binding,
        profile = profile,
        surface = surface,
        composite = composite,
        targetFramebuffer = FramebufferName(targetFramebuffer.toUInt()),
    ) { inner ->
        inner.clearColor(0.0f, 0.0f, 1.0f, 1.0f)
        inner.clear(GL_COLOR_BUFFER_BIT)
    }
    assertNull(failure, "a Cycle D frame must draw without provoking a GL error")
    assertEquals(
        before,
        captureGlState(binding, profile, CONFORMANCE_TEXTURE_UNITS),
        "the documented state must be identical before and after a draw",
    )

    val pixel = ByteArray(4)
    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
    binding.readBuffer(GL_COLOR_ATTACHMENT0)
    binding.readPixels(
        CONFORMANCE_SURFACE_PIXELS / 2, CONFORMANCE_SURFACE_PIXELS / 2, 1, 1,
        GL_RGBA, GL_UNSIGNED_BYTE, pixel,
    )
    assertEquals(0, pixel[0].toInt() and 0xff, "red channel")
    assertEquals(0, pixel[1].toInt() and 0xff, "green channel")
    assertEquals(255, pixel[2].toInt() and 0xff, "blue channel")
    assertEquals(255, pixel[3].toInt() and 0xff, "alpha channel")

    binding.bindFramebuffer(GL_READ_FRAMEBUFFER, 0)
    deleteCompositePipeline(binding, cache, composite)
    deleteOffscreenSurface(binding, surface)
    binding.deleteFramebuffers(1, intArrayOf(targetFramebuffer))
    binding.deleteTextures(1, intArrayOf(targetTexture))
    GlErrorQueue.drainOnEntry(binding)
}

private fun assertLifecycleUnderARealContext(
    binding: GlBinding,
    probe: RenderContextProbe,
    profile: RenderContextProfile,
) {
    val identity = assertNotNull(probe.currentContextIdentity(), "the fixture context must be current")
    val registry = GlObjectRegistry()
    val programs = GlProgramCache()
    val key = ResourceKeyDeriver().offscreenSurface(
        OffscreenSurfaceDescriptor(4, 4, OffscreenColourFormat.RGBA8, OffscreenDepthFormat.DEPTH_COMPONENT24),
    ).key

    val names = IntArray(1)
    binding.genFramebuffers(1, names)
    val deferredFramebuffer = names[0]
    registry.register(key, listOf(GlObjectHandle(GlObjectType.FRAMEBUFFER, deferredFramebuffer)))
    val deletion = assertNotNull(registry.defer(key, DeletionId(1L)))

    val driver = GlLifecycleDriver(
        binding = binding,
        probe = probe,
        registry = registry,
        programs = programs,
        initialSnapshot = RendererLifecycleSnapshot(
            ownerState = RendererOwnerState.LIVE,
            contextGeneration = 0L,
            preparationActive = false,
            gpuLedger = GpuLedger(hasLiveGpuObjects = false, deferredDeletions = listOf(deletion)),
        ),
        initialContext = identity,
        initialProfile = profile,
    )

    val freed = driver.run(RendererLifecycleOperation.FreeResources(com.rohittp.reng.ResourceSelector.All)) { null }
    assertEquals(RendererLifecycleOutcome.Succeeded, freed)
    assertTrue(!binding.isFramebuffer(deferredFramebuffer), "a drained deletion really deletes")

    binding.genFramebuffers(1, names)
    val survivor = names[0]
    registry.register(key, listOf(GlObjectHandle(GlObjectType.FRAMEBUFFER, survivor)))
    assertEquals(
        RendererLifecycleOutcome.Succeeded,
        driver.run(RendererLifecycleOperation.NotifyGpuObjectsGone) { null },
    )
    assertTrue(
        binding.isFramebuffer(survivor),
        "declared GPU object loss forgets handles without deleting them",
    )
    binding.deleteFramebuffers(1, intArrayOf(survivor))
    GlErrorQueue.drainOnEntry(binding)
}
```

The GPU-object-loss check is the one that cannot be written anywhere but here: only a real driver can answer whether the framebuffer still exists after RenG declared its objects gone, and ADR 0007's whole point is that it must.

- [ ] **Step 6: Compile the suite everywhere it is built and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest :kmp:macosArm64Test
git add kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlConformanceSuite.kt
git commit -m "test: add the shared GL conformance suite body"
```

Expected: both tasks succeed. The suite compiles for every target and is invoked by none of them yet.

---

### Task 18: The Linux Surfaceless EGL Fixture and Its Two Dialects

**Files:**
- Create: `kmp/src/linuxTest/kotlin/com/rohittp/reng/internal/gl/SurfacelessEglContext.kt`
- Create: `kmp/src/linuxTest/kotlin/com/rohittp/reng/internal/gl/LinuxGlConformanceTest.kt`

**Interfaces:**
- Consumes: Task 5's `openPlatformGlBinding`, Task 17's `runGlConformanceSuite`, `platform.posix` `dlopen`/`dlsym`.
- Produces: a surfaceless EGL context in either dialect, a `RenderContextProbe` over `eglGetCurrentContext`, and two conformance runs.

Context creation lives in the test fixture, not in RenG (ADR 0001). This is the cheapest place RenG will ever get two dialects on one machine, so the suite runs on both.

- [ ] **Step 1: Implement the fixture**

```kotlin
@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.posix.RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym

private const val EGL_NONE: Int = 0x3038
private const val EGL_PLATFORM_SURFACELESS_MESA: Int = 0x31DD
private const val EGL_OPENGL_ES_API: Int = 0x30A0
private const val EGL_OPENGL_API: Int = 0x30A2
private const val EGL_RENDERABLE_TYPE: Int = 0x3040
private const val EGL_SURFACE_TYPE: Int = 0x3033
private const val EGL_PBUFFER_BIT: Int = 0x0001
private const val EGL_OPENGL_ES3_BIT: Int = 0x0040
private const val EGL_OPENGL_BIT: Int = 0x0008
private const val EGL_CONTEXT_MAJOR_VERSION: Int = 0x3098
private const val EGL_CONTEXT_MINOR_VERSION: Int = 0x30FB
private const val EGL_CONTEXT_OPENGL_PROFILE_MASK: Int = 0x30FD
private const val EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT: Int = 0x0001

internal class SurfacelessEglContext private constructor(
    private val display: COpaquePointer,
    private val context: COpaquePointer,
    private val destroyContext: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> UInt>>,
    private val makeCurrent: CPointer<CFunction<
        (COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> UInt>>,
    private val terminate: CPointer<CFunction<(COpaquePointer?) -> UInt>>,
    private val getCurrentContext: CPointer<CFunction<() -> COpaquePointer?>>,
) {
    internal val probe: RenderContextProbe = RenderContextProbe {
        getCurrentContext()?.let { RenderContextIdentity(it.rawValue.toLong()) }
    }

    internal fun destroy() {
        makeCurrent(display, null, null, null)
        destroyContext(display, context)
        terminate(display)
    }

    internal companion object {
        internal fun create(dialect: ShaderDialect): SurfacelessEglContext {
            val library = requireNotNull(dlopen("libEGL.so.1", RTLD_NOW)) {
                "libEGL.so.1 is required; install libegl1, libegl-mesa0 and libgles2"
            }

            val getPlatformDisplay = library.function<
                (UInt, COpaquePointer?, CPointer<LongVar>?) -> COpaquePointer?>("eglGetPlatformDisplay")
            val initialize = library.function<
                (COpaquePointer?, CPointer<IntVar>?, CPointer<IntVar>?) -> UInt>("eglInitialize")
            val bindApi = library.function<(UInt) -> UInt>("eglBindAPI")
            val chooseConfig = library.function<
                (COpaquePointer?, CPointer<IntVar>?, CPointer<COpaquePointerVar>?, Int, CPointer<IntVar>?) -> UInt>(
                "eglChooseConfig",
            )
            val createContext = library.function<
                (COpaquePointer?, COpaquePointer?, COpaquePointer?, CPointer<IntVar>?) -> COpaquePointer?>(
                "eglCreateContext",
            )
            val makeCurrent = library.function<
                (COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> UInt>("eglMakeCurrent")
            val getCurrentContext = library.function<() -> COpaquePointer?>("eglGetCurrentContext")
            val destroyContext = library.function<(COpaquePointer?, COpaquePointer?) -> UInt>("eglDestroyContext")
            val terminate = library.function<(COpaquePointer?) -> UInt>("eglTerminate")

            val display = requireNotNull(
                getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA.toUInt(), null, null),
            ) { "EGL_PLATFORM_SURFACELESS_MESA display is required" }
            memScoped {
                val major = alloc<IntVar>()
                val minor = alloc<IntVar>()
                require(initialize(display, major.ptr, minor.ptr) != 0u) { "eglInitialize failed" }
            }

            val renderableBit = when (dialect) {
                ShaderDialect.GLES -> EGL_OPENGL_ES3_BIT
                ShaderDialect.DESKTOP -> EGL_OPENGL_BIT
            }
            val api = when (dialect) {
                ShaderDialect.GLES -> EGL_OPENGL_ES_API
                ShaderDialect.DESKTOP -> EGL_OPENGL_API
            }
            require(bindApi(api.toUInt()) != 0u) { "eglBindAPI failed" }

            val context = memScoped {
                val configAttributes = allocArray<IntVar>(7)
                configAttributes[0] = EGL_SURFACE_TYPE
                configAttributes[1] = EGL_PBUFFER_BIT
                configAttributes[2] = EGL_RENDERABLE_TYPE
                configAttributes[3] = renderableBit
                configAttributes[4] = EGL_NONE
                val configs = allocArray<COpaquePointerVar>(1)
                val configCount = alloc<IntVar>()
                require(
                    chooseConfig(display, configAttributes, configs, 1, configCount.ptr) != 0u &&
                        configCount.value > 0,
                ) { "no surfaceless EGL config for $dialect" }

                val contextAttributes = allocArray<IntVar>(7)
                when (dialect) {
                    ShaderDialect.GLES -> {
                        contextAttributes[0] = EGL_CONTEXT_MAJOR_VERSION
                        contextAttributes[1] = 3
                        contextAttributes[2] = EGL_CONTEXT_MINOR_VERSION
                        contextAttributes[3] = 0
                        contextAttributes[4] = EGL_NONE
                    }

                    ShaderDialect.DESKTOP -> {
                        contextAttributes[0] = EGL_CONTEXT_MAJOR_VERSION
                        contextAttributes[1] = 3
                        contextAttributes[2] = EGL_CONTEXT_MINOR_VERSION
                        contextAttributes[3] = 3
                        contextAttributes[4] = EGL_CONTEXT_OPENGL_PROFILE_MASK
                        contextAttributes[5] = EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT
                        contextAttributes[6] = EGL_NONE
                    }
                }
                requireNotNull(createContext(display, configs[0], null, contextAttributes)) {
                    "eglCreateContext failed for $dialect"
                }
            }

            require(makeCurrent(display, null, null, context) != 0u) { "eglMakeCurrent failed" }
            return SurfacelessEglContext(
                display, context, destroyContext, makeCurrent, terminate, getCurrentContext,
            )
        }
    }
}

private inline fun <reified F : Function<*>> COpaquePointer.function(
    name: String,
): CPointer<CFunction<F>> = requireNotNull(dlsym(this, name)) { "$name is missing from libEGL.so.1" }
    .reinterpret()
```

Mesa answers both requested versions upward — an ES 3.0 request yields ES 3.2 and a 3.3 core request yields 4.5 core — which is why the fixture asks for the minimum RenG requires and the suite asserts on what the context reports.

- [ ] **Step 2: Write the two conformance runs**

```kotlin
package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxGlConformanceTest {
    @Test fun theSuitePassesOnARealEsContext() {
        runOn(ShaderDialect.GLES) { report ->
            assertEquals(ShaderDialect.GLES, report.dialect)
            assertTrue(report.shadingLanguageVersionText.startsWith("OpenGL ES GLSL ES"))
            assertTrue(report.versionText.startsWith("OpenGL ES 3"))
        }
    }

    @Test fun theSuitePassesOnARealDesktopCoreContext() {
        runOn(ShaderDialect.DESKTOP) { report ->
            assertEquals(ShaderDialect.DESKTOP, report.dialect)
            assertTrue(!report.shadingLanguageVersionText.startsWith("OpenGL ES"))
        }
    }

    @Test fun theSameBinaryDetectsTwoDialectsOnOneTarget() {
        val esRenderer = runOn(ShaderDialect.GLES) { it }
        val desktopRenderer = runOn(ShaderDialect.DESKTOP) { it }
        assertEquals(esRenderer.rendererName, desktopRenderer.rendererName)
        assertTrue(esRenderer.dialect != desktopRenderer.dialect)
    }

    private fun <T> runOn(dialect: ShaderDialect, assertions: (GlConformanceReport) -> T): T {
        val fixture = SurfacelessEglContext.create(dialect)
        try {
            val binding = when (val result = openPlatformGlBinding()) {
                is GlBindingResult.Bound -> result.binding
                is GlBindingResult.Unsupported ->
                    throw AssertionError("every roster entry point must resolve on this driver")
            }
            // A surfaceless context starts with viewport and scissor box 0,0,0,0.
            binding.viewport(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
            binding.scissor(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
            val report = runGlConformanceSuite(binding, fixture.probe, dialect)
            assertEquals(7, report.checks.size)
            return assertions(report)
        } finally {
            fixture.destroy()
        }
    }
}
```

The third case is the one that would have caught a platform-keyed substitution rule: one binary, one target, two dialects, the same renderer string, and different detected dialects.

- [ ] **Step 3: Run on Linux and commit**

```bash
sudo apt-get update
sudo apt-get install -y --no-install-recommends libegl1 libegl-mesa0 libgles2
./gradlew --no-configuration-cache :kmp:linuxX64Test \
  --tests "com.rohittp.reng.internal.gl.LinuxGlConformanceTest"
./gradlew --no-configuration-cache :kmp:compileKotlinLinuxArm64
git add kmp/src/linuxTest/kotlin/com/rohittp/reng/internal/gl
git commit -m "test: run the GL conformance suite on real llvmpipe contexts"
```

`apt-get update` first is not optional: the cached `libegl-mesa0` version 404s on a stock runner image. Do not claim `linuxX64Test` ran if the host is macOS.

---

### Task 19: The macOS CGL Fixture, With Acceleration Never Requested

**Files:**
- Create: `kmp/src/macosTest/kotlin/com/rohittp/reng/internal/gl/CglCoreProfileContext.kt`
- Create: `kmp/src/macosTest/kotlin/com/rohittp/reng/internal/gl/MacosGlConformanceTest.kt`

**Interfaces:**
- Consumes: Task 4's `openPlatformGlBinding`, Task 17's `runGlConformanceSuite`, `platform.OpenGLCommon` CGL entry points.
- Produces: a headless core-profile context and one conformance run.

> **Never request acceleration.** A hosted macOS runner yields a context only when the accelerated pixel-format requirement is dropped, and it then reports `Apple Software Renderer`. Request acceleration and no context is obtained at all, and the failure names an invalid pixel format rather than the absence of a GPU — which reads as a broken suite rather than as a missing GPU. `kCGLPFAAccelerated` must not appear in the attribute list.

- [ ] **Step 1: Implement the fixture**

```kotlin
@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.internal.gl

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.OpenGLCommon.CGLChoosePixelFormat
import platform.OpenGLCommon.CGLCreateContext
import platform.OpenGLCommon.CGLDestroyContext
import platform.OpenGLCommon.CGLDestroyPixelFormat
import platform.OpenGLCommon.CGLGetCurrentContext
import platform.OpenGLCommon.CGLSetCurrentContext

// CGLTypes.h attribute values. kCGLPFAAccelerated (73) is deliberately absent: requesting it makes
// CGLChoosePixelFormat fail with kCGLBadPixelFormat on a hosted runner with no GPU.
private const val K_CGL_PFA_OPENGL_PROFILE: UInt = 99u
private const val K_CGL_PFA_COLOR_SIZE: UInt = 8u
private const val K_CGL_PFA_DEPTH_SIZE: UInt = 12u
private const val K_CGL_OGLP_VERSION_3_2_CORE: UInt = 0x3200u

internal class CglCoreProfileContext private constructor(
    private val context: kotlinx.cinterop.COpaquePointer,
) {
    internal val probe: RenderContextProbe = RenderContextProbe {
        CGLGetCurrentContext()?.let { RenderContextIdentity(it.rawValue.toLong()) }
    }

    internal fun destroy() {
        CGLSetCurrentContext(null)
        CGLDestroyContext(context.reinterpret())
    }

    internal companion object {
        internal fun create(): CglCoreProfileContext = memScoped {
            val attributes = allocArray<UIntVar>(8)
            attributes[0] = K_CGL_PFA_OPENGL_PROFILE
            attributes[1] = K_CGL_OGLP_VERSION_3_2_CORE
            attributes[2] = K_CGL_PFA_COLOR_SIZE
            attributes[3] = 24u
            attributes[4] = K_CGL_PFA_DEPTH_SIZE
            attributes[5] = 24u
            attributes[6] = 0u

            val pixelFormat = alloc<kotlinx.cinterop.COpaquePointerVar>()
            val formatCount = alloc<kotlinx.cinterop.IntVar>()
            CGLChoosePixelFormat(attributes.reinterpret(), pixelFormat.ptr.reinterpret(), formatCount.ptr)
            val chosen = requireNotNull(pixelFormat.value) {
                "no core-profile pixel format; acceleration must not be requested"
            }
            require(formatCount.value > 0) { "CGLChoosePixelFormat returned no formats" }

            val contextSlot = alloc<kotlinx.cinterop.COpaquePointerVar>()
            CGLCreateContext(chosen.reinterpret(), null, contextSlot.ptr.reinterpret())
            val created = requireNotNull(contextSlot.value) { "CGLCreateContext failed" }
            CGLDestroyPixelFormat(chosen.reinterpret())
            CGLSetCurrentContext(created.reinterpret())
            require(CGLGetCurrentContext() != null) { "CGLSetCurrentContext failed" }
            CglCoreProfileContext(created)
        }
    }
}
```

The attribute array is allocated as `UIntVar` and reinterpreted, and the `CGLError` return values are ignored in favour of checking the out-parameters, so the fixture compiles regardless of whether cinterop generated `CGLPixelFormatAttribute` and `CGLError` as enum classes or as integer typealiases. If the generated signatures turn out to accept the enum types directly, replacing the reinterpretation with them is a mechanical cleanup and changes no behaviour.

- [ ] **Step 2: Write the conformance run**

```kotlin
package com.rohittp.reng.internal.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacosGlConformanceTest {
    @Test fun theSuitePassesOnARealAppleCoreProfileContext() {
        val fixture = CglCoreProfileContext.create()
        try {
            val binding = when (val result = openPlatformGlBinding()) {
                is GlBindingResult.Bound -> result.binding
                is GlBindingResult.Unsupported -> throw AssertionError("platform.OpenGL3 must bind")
            }
            binding.viewport(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)
            binding.scissor(0, 0, CONFORMANCE_SURFACE_PIXELS, CONFORMANCE_SURFACE_PIXELS)

            val report = runGlConformanceSuite(binding, fixture.probe, ShaderDialect.DESKTOP)
            assertEquals(ShaderDialect.DESKTOP, report.dialect)
            assertEquals(7, report.checks.size)
            assertTrue(report.rendererName.isNotBlank())
            // A hosted runner reports "Apple Software Renderer"; a developer's machine reports
            // "4.1 Metal - 90.5". Cycle E must key golden baselines by this string and the dialect.
            println("RenG conformance renderer: ${report.rendererName} / ${report.versionText}")
        } finally {
            fixture.destroy()
        }
    }
}
```

The renderer string is printed rather than asserted because it legitimately differs between a hosted runner and a developer's machine — and that difference is exactly why Cycle E's golden baselines must be keyed by the reported renderer string and the context dialect rather than by the target.

- [ ] **Step 3: Run on macOS and commit**

```bash
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.gl.MacosGlConformanceTest"
git add kmp/src/macosTest/kotlin/com/rohittp/reng/internal/gl
git commit -m "test: run the GL conformance suite on a real CGL core profile"
```

Expected: the suite passes and the printed renderer line appears in the test output. If `CGLChoosePixelFormat` yields no format, check first that no accelerated attribute crept into the list.

---

### Task 20: Install Mesa EGL in Both Linux Gates

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/publish.yml`

**Interfaces:**
- Consumes: Task 18's fixture, which needs `libEGL.so.1` at runtime.
- Produces: a `linuxX64Test` job that can create a surfaceless context in continuous integration and in the release gate.

Both workflows run `:kmp:linuxX64Test` on `ubuntu-latest`, and neither installs EGL today. `libEGL` is not present on a stock runner, and the cached `libegl-mesa0` version 404s, so `apt-get update` must precede the install. The macOS jobs need no change: they already run `:kmp:macosArm64Test`, which is where the Apple conformance run lands.

- [ ] **Step 1: Read both workflows before editing**

```bash
sed -n '1,50p' .github/workflows/ci.yml
sed -n '70,105p' .github/workflows/publish.yml
```

Confirm that `ci.yml`'s `android-linux` job and `publish.yml`'s `linux-release` job each contain a Gradle step invoking `:kmp:linuxX64Test`, and that no EGL package is installed anywhere.

- [ ] **Step 2: Add the install step to `ci.yml`**

Insert immediately before the `Test and compile` step of the `android-linux` job:

```yaml
      - name: Install Mesa EGL for the GL conformance suite
        run: |
          sudo apt-get update
          sudo apt-get install -y --no-install-recommends libegl1 libegl-mesa0 libgles2
```

- [ ] **Step 3: Add the same step to `publish.yml`**

Insert immediately before the `Test Linux and Android release outputs` step of the `linux-release` job:

```yaml
      - name: Install Mesa EGL for the GL conformance suite
        run: |
          sudo apt-get update
          sudo apt-get install -y --no-install-recommends libegl1 libegl-mesa0 libgles2
```

Mesa's ICD registers itself at `/usr/share/glvnd/egl_vendor.d/50_mesa.json` and `swrast_dri.so` is already installed on the runner image, so llvmpipe needs nothing further: no X server, no Wayland compositor, no GBM device, and no DRM node.

- [ ] **Step 4: Parse both workflows and re-run the policy gate**

```bash
ruby -e 'require "yaml"; YAML.safe_load(File.read(".github/workflows/ci.yml"), [], [], true); YAML.safe_load(File.read(".github/workflows/publish.yml"), [], [], true)'
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
```

Expected: the Ruby command exits silently and the policy check prints its pass line. The completion-record policy inspects five specific named steps in `publish.yml` and their relative order; an install step added to a different job does not touch any of them, and this run is the proof rather than the assumption.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml .github/workflows/publish.yml
git commit -m "ci: install Mesa EGL for the Linux GL conformance gate"
```

---

### Task 21: Record the Corrected Contract and Run Every Local Gate

**Files:**
- Create: `docs/adr/0023-restore-the-corrected-gl-state-set-and-consume-the-error-queue.md`
- Modify: `CONTEXT.md`
- Modify: `CLAUDE.md`
- Modify: `HANDOFF.md`
- Modify: `docs/decomposition.md`
- Verify: all production/tests, policy, ABI, publication, smoke.

**Interfaces:**
- Consumes: Tasks 1–20.
- Produces: a locally verified Cycle D implementation branch ready for integration review; no push, no publication.

ADR 0006's set is measured incomplete and its no-modification guarantee has one unavoidable exception, so a newer ADR records both — that is how this repository carries corrections forward, and CLAUDE.md already says the newer ADR wins where documents disagree. The specification requires the error-queue consumption to be **declared rather than discovered**, and this task is where it is declared.

- [ ] **Step 1: Write ADR 0023**

Create `docs/adr/0023-restore-the-corrected-gl-state-set-and-consume-the-error-queue.md` with the title `# Restore the corrected GL state set and consume the error queue` and a few paragraphs of prose, no template headings, covering exactly these decisions and no others:

- ADR 0006's list is superseded by the corrected set: the six additions are the colour write mask, the pixel-store unpack alignment/row length/skip rows/skip pixels, the pack alignment, the colour and depth clear values, the array buffer binding, and the pixel unpack buffer binding, alongside every binding, blend, depth, cull, viewport, and scissor item ADR 0006 already named.
- The element array buffer binding needs no restore because it is per-VAO state restored implicitly by the VAO binding, while the array buffer binding is not captured by the VAO and must be saved explicitly.
- `GL_ACTIVE_TEXTURE` is captured first and reinstated last, with every per-unit texture and sampler read and write nested inside, because reading a unit's binding requires making that unit active.
- The measured default unpack and pack alignment is `4`, not `1`, on both llvmpipe and a hosted macOS runner, so an implementation that assumes `1` corrupts non-aligned rows and leaves the state dirty.
- The save/restore code is dialect-aware because `GL_DRAW_BUFFER` and `GL_LINE_SMOOTH` are queryable on a desktop core profile and raise `GL_INVALID_ENUM` on ES; an unconditional query list would leave a spurious error flag on every ES context.
- RenG sets `GL_FRAMEBUFFER_SRGB` explicitly and restores the caller's value, because it arrives enabled on Mesa's ES context and disabled on its desktop core context, and inheriting it would make RenG's output depend on the consumer's context choice.
- `glGetError` is destructive and no flag can be pushed back. RenG drains on entry, treats any flag found as the consumer's, and consumes the caller's error queue. This is the one stated exception to ADR 0006's no-modification guarantee; the alternative of never calling `glGetError` would give up all internal error detection.
- The whole set is verified rather than asserted: a save, perturb, and restore round trip is byte-exact on a real ES context and a real desktop context in the Cycle D conformance suite.

- [ ] **Step 2: Add the vocabulary entry to `CONTEXT.md`**

Insert after the **Render Context** entry, in the same shape as its neighbours:

```markdown
**Restore Set**:
The closed, documented set of GL state RenG reads before it draws and restores before it returns: the draw
and read framebuffer, renderbuffer, program, vertex array, array buffer, pixel unpack buffer and uniform
buffer bindings; the active texture unit and the texture and sampler binding on every unit RenG uses; blend
enable, separate factors, separate equations and colour; depth test enable, function, write mask and range;
cull enable, mode and winding; viewport; scissor enable and box; the colour write mask; the colour and depth
clear values; the unpack alignment, row length, skip rows and skip pixels and the pack alignment; and, on a
desktop **Render Context** only, the draw buffer and line smoothing. `GL_FRAMEBUFFER_SRGB` is set explicitly
and restored wherever it is queryable. The element array buffer binding is excluded because the vertex array
binding restores it. `GL_ACTIVE_TEXTURE` is captured first and reinstated last. The GL error queue is the one
piece of state RenG cannot restore: reading it clears it, so RenG drains it on entry, attributes any flag
found to the consumer, and consumes it.
_Avoid_: GL state cache, context reset, default state, state stack
```

- [ ] **Step 3: Update repository status factually**

In `CLAUDE.md`, record that Cycle D is implemented on this branch and awaits integration review; that its authority is the Cycle D specification and this plan; that it adds the internal GL seam, four implementations, runtime dialect detection, the offscreen surface and composite pass, the corrected restore set, shader compilation with substitution and program caching, the lifecycle driver, and the conformance suite; and that it still adds **no public ABI**, no renderer factory, no resource acquisition, no Rentile call, no decoder or parser, and no frame content. State that exact merged-commit CI and publication have not been observed and that `VERSION_NAME` remains `0.1.0`. Add ADR 0023 to the ADR summary line.

In `HANDOFF.md`, replace the Cycle D section's "what it still needs" framing with what was implemented and what was measured, and correct the Cycle F line that projects the draw-regime ordering decision as "ADR 0023" — Cycle D took that number, so Cycle F's would be **ADR 0024**. In `docs/decomposition.md`, mark Cycle D's gates as met locally, without claiming CI.

Do not claim Linux executable coverage from a macOS host, do not claim `macosArm64Test` from a Linux host, and do not claim any public release.

- [ ] **Step 4: Run the Python and repository policy gates**

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools/tests -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
```

Expected: all Python tests pass and the policy output ends with its pass line.

- [ ] **Step 5: Prove the public ABI never moved**

```bash
./gradlew --no-configuration-cache --rerun-tasks :kmp:checkKotlinAbi
diff <(shasum -a 256 kmp/api/kmp.klib.api) /tmp/reng-cycle-d-abi-baseline.txt
git diff --stat "$(git merge-base HEAD main)"..HEAD -- kmp/api/kmp.klib.api
! grep -nE 'platform\.|kotlinx\.cinterop|GLES30|GlBinding|GlEntryPoint' kmp/api/kmp.klib.api
```

Expected: `checkKotlinAbi` passes, the digest is identical to Task 0's baseline, the ABI dump shows zero changed lines across the whole cycle, and the grep finds nothing. A changed ABI dump is a defect in this cycle, not a dump to regenerate.

- [ ] **Step 6: Run every locally compilable gate**

On macOS:

```bash
./gradlew --no-configuration-cache --rerun-tasks \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar \
  :kmp:compileKotlinIosArm64 \
  :kmp:compileKotlinIosSimulatorArm64 \
  :kmp:compileIosMainKotlinMetadata \
  :kmp:macosArm64Test \
  :kmp:publishAllPublicationsToLocalTestRepository
```

On Linux:

```bash
sudo apt-get update
sudo apt-get install -y --no-install-recommends libegl1 libegl-mesa0 libgles2
./gradlew --no-configuration-cache --rerun-tasks \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:linuxX64Test \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

`linuxX64Test` is Linux coverage and never a macOS-local gate; `macosArm64Test` is the one Apple target with a test task rather than a compile-only gate.

- [ ] **Step 7: Run the fresh six-target consumer smoke**

```bash
final_smoke_home="$(mktemp -d)"
./gradlew --no-configuration-cache \
  --gradle-user-home "$final_smoke_home" \
  --refresh-dependencies \
  -p consumer-smoke \
  compileAndroidMain \
  compileKotlinIosArm64 \
  compileKotlinIosSimulatorArm64 \
  compileKotlinMacosArm64 \
  compileKotlinLinuxX64 \
  compileKotlinLinuxArm64
```

Expected: all six compile from the locally published coordinate. The seam is `internal`, so a consumer sees exactly what it saw before Cycle D.

- [ ] **Step 8: Parse the workflows and inspect repository scope**

```bash
ruby -e 'require "yaml"; YAML.safe_load(File.read(".github/workflows/ci.yml"), [], [], true); YAML.safe_load(File.read(".github/workflows/publish.yml"), [], [], true)'
git diff --check
! grep -rn "mavenLocal\|-SNAPSHOT" kmp/build.gradle.kts build.gradle.kts settings.gradle.kts consumer-smoke
git status --short
```

- [ ] **Step 9: Commit the documentation**

```bash
git add docs/adr/0023-restore-the-corrected-gl-state-set-and-consume-the-error-queue.md \
  CONTEXT.md CLAUDE.md HANDOFF.md docs/decomposition.md
git commit -m "docs: record the corrected GL restore set and Cycle D state"
```

- [ ] **Step 10: Verify the committed range and final cleanliness**

```bash
git diff --check "$(git merge-base HEAD main)"..HEAD
test -z "$(git status --porcelain)"
```

Expected: both commands exit zero. If implementation commits changed the approved specification, the research record, or historical Cycle A or Cycle B documents, stop and remove that scope drift before review.

Execution stops before any push, merge, workflow dispatch, R2 upload, or publication side effect. Those remain explicit outward actions requiring approval and an observed workflow result.

---

## Plan Self-Review Checklist

- **The GL seam, typed at Android's width** — Task 2 declares all eighty-four methods with `Int` names and enums, `Boolean`/`BooleanArray`, `IntArray`, `FloatArray`, `Int` buffer sizes, `ByteArray?` payloads, and one `String` shader source; Task 1 supplies the roster and tokens, including `glGetStringi` beside `glGetString`.
- **Source-set layout per ADR 0022** — Tasks 3, 4, 5, and 6 place one implementation each in `iosMain`, `macosMain`, `linuxMain`, and `androidMain`, every one behind a probe step that proves the source set resolves its binding; Task 7 compiles every leaf plus `compileIosMainKotlinMetadata`, because resolution is enforced per leaf and a partial compile can look green.
- **Measured native traps** — the zero-length `addressOf(0)` guard is a stated rule in Tasks 3 and 5 and a test in Task 2; no cinterop definition exists anywhere; Linux resolves through `dlopen("libEGL.so.1")` plus `eglGetProcAddress` with `dlsym` as fallback and fails closed as a typed redacted setup error.
- **Context adoption and runtime dialect** — Task 8 keys the dialect on `GL_SHADING_LANGUAGE_VERSION` beginning `OpenGL ES GLSL ES`, states in a call-out that the trigger is a runtime query and never the target, requires ES 3.0 or desktop 3.3, and rejects with `UNSUPPORTED_RENDER_CONTEXT` at `CONTEXT_ADOPTION` while touching no state; Task 18 proves two dialects from one binary on one target.
- **Shader compilation, substitution, and caching** — Task 12 selects the source by dialect through `ShaderProfilePlan`, caches by Cycle B's `GEOMETRY_PROGRAM` key, emits `SHADER_COMPILE_FAILED` and `SHADER_LINK_FAILED` at `SHADER_COMPILATION`, keeps the info log behind an observer, and routes internal-pipeline failures to `GPU_OPERATION_FAILED` because the diagnostic rules admit only a geometry-program identity there.
- **Offscreen surface and composite pass** — Task 11 freezes the `OFFSCREEN_SURFACE` and `INTERNAL_PIPELINE` identities, Task 13 creates the colour-and-depth surface, and Task 14 composites it as a blended draw with `GL_FRAMEBUFFER_SRGB` set explicitly and restored.
- **The complete restore set** — Task 10 captures and restores every binding, pipeline, and pixel-store item the specification names, with `GL_ACTIVE_TEXTURE` first and last, the unpack alignment default of `4`, dialect-gated `GL_DRAW_BUFFER` and `GL_LINE_SMOOTH`, an explicitly captured array buffer binding, and no element array buffer query.
- **The error-queue exception** — Task 9 implements the bounded destructive drain and its two attributions; Task 17 proves destructiveness against a real driver; Task 21 declares it in ADR 0023 and in `CONTEXT.md`.
- **Driving the existing lifecycle machine** — Task 16 supplies `ExactContextFact`, `AdoptionContextFact`, `FramebufferFact`, deferred-deletion acknowledgement and failure, quiescence, and preparation termination, and executes `DeleteDeferred` and `ExecutePermittedOperation`; ADR 0015 exact-context deletion, ADR 0007 forgetting without deleting, and deferred-deletion draining each have their own test, and the Cycle B reducer is not modified.
- **The conformance suite** — Task 17 holds one shared body; Task 18 runs it on real ES 3.2 and 4.5 core llvmpipe contexts through `EGL_PLATFORM_SURFACELESS_MESA`; Task 19 runs it on a real CGL core profile with acceleration never requested; both fixtures own context creation, per ADR 0001.
- **CI wiring** — Task 20 adds the Mesa EGL install to both Ubuntu jobs after reading the workflows, because both run `:kmp:linuxX64Test` and neither installs EGL today; the macOS jobs already run `macosArm64Test` and need no change.
- **No public surface** — Tasks 0, 7, and 21 pin the ABI digest at three points in the cycle, and every declaration added is `internal`.
- **No placeholders** — every step contains real commands or real Kotlin; the only prose-specified bodies are mechanical expansions governed by explicitly stated rules (the remaining seam methods per implementation, the recording fake's remaining methods, and the scratch-object helpers), each with its rule set written out and its shape demonstrated in code.

## Recorded Execution Decision

Cycle D is independent of Cycle C by construction — it acquires no resource, calls no adapter, and touches no Rentile type — so the two cycles may run in parallel in separate worktrees without sharing a file. Within Cycle D, execute the waves in the Parallel Execution Map with at most three implementation workers at once, reviewing and cherry-picking each worker commit before dispatching its dependents. Stop before any push, merge, dispatch, or publication.
