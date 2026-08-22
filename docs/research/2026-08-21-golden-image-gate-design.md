# The rendered-frame gate: what exists, what can run, and one design to grill

Researched 2026-08-21 on branch `feat/cycle-e-basemap` at `e0e932e` (plus the in-flight Task E-C4/E-C5
work recorded in the SDD ledger). Nothing in this document was implemented; it is evidence and one
recommendation.

`docs/decomposition.md:41` makes "golden baseline per reported renderer" an outcome gate for E-basemap
and `docs/decomposition.md:42` makes "per-platform golden baselines" one for F-2. `CLAUDE.md:274-277`
records that the gate is undesigned. This document closes that gap far enough to be argued with.

---

## 1. What pixel verification exists today

### 1.1 The deferral decision, and what it deferred to

Pixel verification was deferred **once, explicitly, by owner decision**:

> `docs/superpowers/specs/2026-08-19-cycle-f1-stickers-and-geometries-design.md:204-205`
> "**All pixel verification is deferred to Cycle J**, by owner decision, including golden images and their
> renderer-string keying."

The same section states the risk it accepts, at lines 212-213: *"a misplaced sticker is invisible to every
test this cycle has and immediately obvious to a consumer."* F-1 verifies the draw path by call-log
assertion instead (`docs/superpowers/specs/2026-08-19-cycle-f1-stickers-and-geometries-design.md:207-210`),
and that is what `GlFrameDrawerTest.kt`, `StickerPipelineTest.kt`, `GeometryPipelineTest.kt`,
`CompositePipelineTest.kt` and `SceneContentTest.kt` do today, against
`kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/RecordingGlBinding.kt`.

Cycle J is the destination: `docs/decomposition.md:225-230` scopes it as "a corpus of frame plans rendered
per platform and compared against baselines with a tolerance", slotted into "a job in `ci.yml` and a step
in `publish.yml` before upload".

### 1.2 The analytical readback: designed, not landed

The E-basemap design **departs** from F-1 and commits to reading pixels back:

> `docs/superpowers/specs/2026-08-20-cycle-e-basemap-design.md:100-122`, "Basemap is verified by analytical
> readback, departing from Cycle F-1". Its argument is the one worth keeping: F-1's failure modes are loud
> (missing sticker, wrong colour); basemap's are quiet and plausible — an off-by-one in tile selection
> "yields a map that looks correct except for one missing tile at one edge under one camera angle", a wrong
> LOD "yields a map that is merely blurrier than it should be", a dedup error "appears only when panning
> across the antimeridian", and **"none is catchable by call-log assertion — every draw call looks right."**

Its prescription (lines 117-120): *"draw a known camera over known tiles, read back specific pixels, and
assert relationships … Sample well inside solid regions rather than near edges where filtering legitimately
differs, and assert relationships where absolute values would be fragile."* It explicitly leaves golden
images to Cycle J (line 122).

**It has not landed.** The cycle's own ledger orders it last:

> `.superpowers/sdd/2026-08-20-cycle-e-basemap/progress.md:11-12`
> "Corrected task order … then tile decode/upload, ground draw, world-copy instancing, analytical readback."

The ledger's tail shows Task E-C5 (a style-recompilation defect) as the currently dispatched work; tile
decode/upload, ground draw, world-copy instancing and analytical readback are all still ahead of it. This
is corroborated in the tree: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/SceneContent.kt`
contains no basemap or ground term at all, and `RenGRenderer.performDraw`
(`kmp/src/commonMain/kotlin/com/rohittp/reng/RenGRenderer.kt:796`) assembles only stickers and
geometries. Basemap tiles reach `RenGPreparedFrame.basemapTiles` as **encoded PNG bytes** from Rentile
(`RenGRenderer.kt:154-162`, `internal/firewall/BasemapEngineHost.kt:470`) and are not yet decoded,
uploaded, or drawn.

### 1.3 The only pixel assertion in the tree

Exactly one test anywhere reads pixels from a real driver:

`kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlConformanceSuite.kt:562-572`

```kotlin
val pixel = ByteArray(4)
binding.bindFramebuffer(GL_READ_FRAMEBUFFER, targetFramebuffer)
binding.readBuffer(GL_COLOR_ATTACHMENT0)
binding.readPixels(
    CONFORMANCE_SURFACE_PIXELS / 2, CONFORMANCE_SURFACE_PIXELS / 2, 1, 1,
    GL_RGBA, GL_UNSIGNED_BYTE, pixel,
)
assertEquals(0, pixel[0].toInt() and 0xff, "red channel")
...
assertEquals(255, pixel[2].toInt() and 0xff, "blue channel")
```

One pixel, at the centre of a 64×64 FBO, after a clear-to-opaque-blue inside `drawFrame`. It proves the
offscreen surface and composite pass move colour end to end. It compares nothing to a stored image, and it
draws no content.

The only other `readPixels` mentions are the fake binding
(`kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/RecordingGlBinding.kt:213-217`, which replays a
programmed byte array and is explicitly not evidence) and its own unit test.

**Summary: there is no image comparison, no baseline of any kind, no framebuffer capture-to-file, and no
tolerance machinery anywhere in RenG.** There is one clear-colour readback and a designed-but-unwritten
analytical readback.

### 1.4 The nearest existing precedent is in Rentile, and it is instructive

Rentile — the structural template for this repository — also has **no golden-image infrastructure**. Two
things it does instead are directly reusable:

- **Sampled pixels with a tolerance of 1**, not image equality:
  `/Users/rohittp/Data/Other/rentile/kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt:4554-4562`
  (`assertColorClose`, per-channel `abs(delta) <= tolerance`), used at lines 990, 1051, 2168, 2240 against
  the exact centre pixel of a rendered tile, with a comment justifying why that pixel is the right one.
- **A corpus gate that asserts structure and prints observations, and writes contact sheets for humans**:
  `/Users/rohittp/Data/Other/rentile/kmp/src/nativeCorpusTest/kotlin/com/rohittp/rentile/NativeMapCatalogCorpusSmokeTest.kt:60-115`
  asserts only "no style failed", "PNG signature and declared size are right" (line 269-275) and prints
  `z0_luma=` per style (line 280-305), then writes per-style PNGs and a contact sheet to a directory when
  one is configured. No baseline is checked in.

And Rentile's ADR 0010 states the rule RenG should inherit verbatim:

> `/Users/rohittp/Data/Other/rentile/docs/adr/0010-always-return-png.md:5`
> "Determinism is measured on **decoded pixels**, not compressed PNG byte identity across platforms. …
> identical same-target inputs remain deterministic, while cross-platform acceptance uses the versioned
> decoded-pixel tolerance."

---

## 2. What can actually run where

### 2.1 Targets, test tasks, and real GL contexts

| Target | Executable test task | Runs in CI | Real GL context | Rentile/Skia rasterization |
|---|---|---|---|---|
| `android` (JVM host) | `:kmp:testAndroidHostTest` | both jobs | **No** | **No** |
| `linuxX64` | `:kmp:linuxX64Test` | `android-linux` | **Yes** — surfaceless EGL / llvmpipe | Yes |
| `linuxArm64` | none invoked (compile only) | `compileKotlinLinuxArm64` | No | — |
| `macosArm64` | `:kmp:macosArm64Test` | `apple-publication` | **Yes** — headless CGL core profile | Yes |
| `iosArm64` | none (device) | compile only | No | — |
| `iosSimulatorArm64` | task exists, **never invoked** | compile only | No fixture exists | — |

Sources: `.github/workflows/ci.yml:38-45` (Ubuntu job: `checkKotlinAbi`, `testAndroidHostTest`,
`linuxX64Test`, `compileKotlinLinuxArm64`, `bundleAndroidMainAar`) and `.github/workflows/ci.yml:58-64`
(macOS job: both iOS compiles, `macosArm64Test`, local publication). `kmp/build.gradle.kts:31-38` declares
the six targets and `withHostTest {}` for Android.

**So exactly two test tasks in the entire project can hold a GL context: `linuxX64Test` and
`macosArm64Test`.** That is ADR 0011's design, stated at `docs/adr/0011-verify-the-gl-contract-against-real-contexts.md:1-10`,
and it is the hard boundary on any pixel gate.

### 2.2 Both context mechanisms are confirmed from the repo's own records, not assumed

**macOS, headless CGL core profile.** Implemented at
`kmp/src/macosTest/kotlin/com/rohittp/reng/internal/gl/CglCoreProfileContext.kt:22-24, 41-67` — a 3.2 core
profile pixel format with 24-bit colour and depth, no window, no display server. The load-bearing comment
is at lines 22-23:

> "kCGLPFAAccelerated (73) is deliberately absent: requesting it makes `CGLChoosePixelFormat` fail with
> `kCGLBadPixelFormat` on a hosted runner with no GPU."

Measured in `docs/research/2026-08-18-cycle-d-gl-foundation.md:384-397`: on `macos-latest` (Xcode 26.6) the
accelerated candidate fails with `cgl=10002`; dropping acceleration succeeds and reports
`GL_VERSION=4.1 APPLE-23.1.1`, `GL_RENDERER=Apple Software Renderer`, `GL_SHADING_LANGUAGE_VERSION=4.10`.
On a developer's Apple Silicon machine the same fixture reports `4.1 Metal - 90.5`
(`docs/adr/0011-…:2-4`, `kmp/src/macosTest/.../MacosGlConformanceTest.kt:21-24`).

**Linux, surfaceless EGL / llvmpipe.** Implemented at
`kmp/src/linuxTest/kotlin/com/rohittp/reng/internal/gl/SurfacelessEglContext.kt` (`dlopen` of libEGL from
`platform.posix`, `EGL_PLATFORM_SURFACELESS_MESA` at line 27). CI installs the runtime at
`.github/workflows/ci.yml:34-37` (`libegl1 libegl-mesa0 libgles2`). Both an ES 3.2 and a 4.5 desktop core
context are reachable in one process
(`kmp/src/linuxTest/.../LinuxGlConformanceTest.kt:7-28`), reporting
`GL_RENDERER = llvmpipe (LLVM 20.1.2, 256 bits)` (`docs/research/2026-08-18-cycle-d-gl-foundation.md:195,205`).
`HANDOFF.md` records the same under "Environment notes": *"A real GL context is available on Linux …
no display server needed."*

Two caveats already recorded and still true:

- The Mesa 25.2.8 cross-dialect `glLinkProgram` SIGSEGV forces `CrossDialectLinkPolicy.SKIP_ON_LINUX_MESA_LINK_SEGFAULT`
  on Linux (`GlConformanceSuite.kt:48-67`, `docs/research/2026-08-19-mesa-cross-dialect-link-segfault.md`).
  It does not affect drawing, only that one negative check.
- `docs/decomposition.md:152-158`: Cycle D's Linux verification "happened opportunistically under Docker
  against real Mesa, not through a hosted CI run of `:kmp:linuxX64Test`", and *"the hosted runner's no-GPU
  software-renderer fallback on macOS remains untested on any machine with a real GPU."* A pixel gate
  should not be the first thing to discover a difference between those two.

### 2.3 The Android host test source set cannot participate — twice over

Confirmed, and stronger than "no GL":

1. **No GL.** `androidHostTest` is a plain JVM unit-test source set (`kmp/build.gradle.kts:26`,
   `withHostTest {}`); `android.jar`'s `GLES30` stubs throw. `GlConformanceSuite.kt:23-26` states the
   consequence directly: *"only the `linuxTest` and `macosTest` fixtures … ever call
   `runGlConformanceSuite` — `androidHostTest` and the iOS test compilations have no real context to run
   it against."*

2. **No Skia, therefore no Rentile rasterization at all.** This is the recent finding and it is measured,
   not inferred:

   > `kmp/src/nativeTest/kotlin/com/rohittp/reng/internal/firewall/BasemapEngineRenderTest.kt:11-18`
   > "Rentile rasterizes through Skia, and this project's `androidHostTest` runtime resolves
   > `org.jetbrains.skiko:skiko`'s API **without its native library** — Rentile adds
   > `skiko-awt-runtime-<host>` only to its own JVM/Android test source sets, never to what it publishes —
   > so `Image.makeFromEncoded` there fails with `LibraryLoadException` and every rasterizing assertion
   > would be vacuous."

   And with measured error codes:

   > `kmp/src/nativeTest/kotlin/com/rohittp/reng/RendererBasemapTileTest.kt:17-27`
   > "on that target `prepareTiles` fails with `RESOURCE_DECODE_FAILED` and `renderTiles` with
   > `BASEMAP_RENDER_FAILED`, both measured, for **any** style including a source-less one. A
   > renderer-level basemap test therefore cannot pass there at all; **it is the environment that is
   > incapable, not RenG.**"

   This is why `RendererBasemapTileTest`, `RendererBasemapStyleRenderTest` and `BasemapEngineRenderTest`
   live in `kmp/src/nativeTest/` rather than `commonTest`.

**Consequence for the gate:** Android's `GLES30` binding — one of the four platform GL implementations —
has zero pixel coverage and no path to any, short of an emulator job that ADR 0011 already rejected
(`docs/adr/0011-…:20-23`). That must be stated in the gate's own documentation, not discovered.

---

## 3. The determinism problem

Golden images need byte-stable output. Here is every divergence source I can find in this project, and
what can be done about each.

### 3.1 Divergences RenG has already eliminated

These are worth naming because they are the ones most projects trip over, and this one already fixed them:

| Source | Status | Evidence |
|---|---|---|
| **Multisampling** | Eliminated. The offscreen surface is a plain `GL_RGBA8` texture + `GL_DEPTH_COMPONENT24` renderbuffer; no MSAA anywhere. | `internal/gl/OffscreenSurface.kt:8-19, 52-58` |
| **Mipmap selection** | Eliminated. No mipmap chain is generated; min and mag filters are set explicitly per content kind. | `internal/gl/GlTextureUpload.kt:36-42, 78-84` |
| **Wrap-mode edge bleed** | Eliminated. `GL_CLAMP_TO_EDGE` on both axes, both content kinds. | `GlTextureUpload.kt:52-53, 85-86` |
| **sRGB write conversion** | Eliminated. Set explicitly to disabled rather than inherited, precisely because Mesa's ES context arrives with it **enabled** and its desktop core context **disabled** on the same machine. | `internal/gl/GlFrameDrawer.kt:30-35, 65`; measured at `docs/research/2026-08-18-cycle-d-gl-foundation.md:331-336` |
| **Premultiply rounding** | Eliminated. Pinned to round-half-up `(c*a + 127)/255`, with the comment *"an unpinned choice is exactly the kind of thing that silently diverges between platforms."* | `GlTextureUpload.kt:91-98` |
| **Blend equation / func** | Eliminated. Set explicitly (`GL_FUNC_ADD`, `GL_SRC_ALPHA`/`GL_ONE_MINUS_SRC_ALPHA` for the composite; `GL_ONE`/`GL_ONE_MINUS_SRC_ALPHA` premultiplied for stickers). | `GlFrameDrawer.kt:78-80`, `StickerPipeline.kt:170-172` |
| **Clear colour / depth, cull, front face, scissor, colour/depth mask** | Eliminated. All set explicitly at the top of `drawFrame`. | `GlFrameDrawer.kt:56-66` |
| **Pixel store alignment** | Eliminated on the unpack side (`GL_UNPACK_ALIGNMENT` etc. reset per frame). **Not yet handled on the pack side** — `GL_PACK_ALIGNMENT` defaults to 4, measured, and a readback of a width not divisible by 4 will have row padding. | `GlFrameDrawer.kt:47-51`; the pack-side gap is flagged in `docs/research/2026-08-18-cycle-d-gl-foundation.md:325` and in the Cycle D design at line 196 |
| **Map text / font rasterization** | **Eliminated by design, and this is the single largest gift the project has.** `CONTEXT.md` already forbids text in a basemap tile; the E design restates it: *"the map draws label-free by existing design. Deferring labels means the map has no text — never distorted text."* | `docs/superpowers/specs/2026-08-20-cycle-e-basemap-design.md:31-39` |

> **Warning to carry forward:** the cycle after E-basemap ships labels
> (`docs/superpowers/specs/2026-08-20-cycle-e-basemap-design.md:33`, Rentile 0.3.0's
> `acquireLabelCandidates` / `LabelPrimitive` / `LabelGlyphAtlas` at lines 43-45). Glyph rasterization and
> hinting are the classic reason golden-image suites rot. Whatever tolerance is chosen for E must be
> re-argued when text arrives; do not let a text-free calibration silently become the text baseline.

### 3.2 Divergences that remain, and what each needs

**(a) Two different GL implementations — llvmpipe vs. Apple.** Irreducible. Rasterization rules
(fill conventions, interpolation, filter weights, depth precision) differ between Mesa's gallium
rasterizer and Apple's GL. `docs/decomposition.md:171-172` already rules on it: *"never cross-platform pixel
equality, since llvmpipe and Apple's GL will not agree."*
→ **Per-signature baselines. Never compare across.**

**(b) Different renderers on the *same* target.** The finding with the longest reach:

> `docs/research/2026-08-18-cycle-d-gl-foundation.md:414-420`
> "A hosted runner gives `Apple Software Renderer`, not the `4.1 Metal - 90.5` that real Apple Silicon
> reports. … per-platform is not a fine enough key: the same target produces different pixels under a
> software renderer in continuous integration than under Metal on a developer's machine. Baselines should
> be keyed by the reported renderer, not by the target, or the corpus gate will fail the first time it is
> run somewhere other than where its baselines were recorded."

→ **Per-renderer-string baselines** (see §3.3).

**(c) Two dialects on one machine, one renderer string.** llvmpipe serves both an ES 3.2 and a desktop 4.5
core context in the same process, with the same `GL_RENDERER`
(`LinuxGlConformanceTest.kt:24-28` asserts the renderer names are equal and the dialects differ), and
`GL_FRAMEBUFFER_SRGB` arrives differently on the two. RenG now forces sRGB off, which removes that
specific difference, but shader compilation still goes down two paths (`#version 300 es` vs the
substituted `#version 330 core`, ADR 0008), and two compilers can produce different arithmetic.
→ **The key is `(rendererString, dialect)`, not the renderer string alone.** The Cycle D design says
exactly this at `docs/superpowers/specs/2026-08-18-cycle-d-gl-foundation-design.md:260-263`.

**(d) Driver-version drift within one renderer family.** Mesa on `ubuntu-latest` moves whenever GitHub
rebuilds the image; Apple's software renderer moves with Xcode. A 1-LSB change in a filtered texel is a
normal outcome of such a bump.
→ **Cannot be eliminated. Must be absorbed by tolerance** — and this is the main reason a
zero-tolerance digest gate is wrong (see §5.4).

**(e) Floating-point in the transform pipeline.** RenG keeps geographic and camera-relative math in
`Double` and crosses the GPU boundary as small camera-relative `Float`s
(`docs/decomposition.md:101-102`, `internal/gl/SceneContent.kt:123, 231`). Kotlin/Native's `Double`
arithmetic is IEEE-754 and identical across x86-64 and arm64 for the operations used, but transcendentals
(`sin`/`cos`/`tan` in the mercator and camera math) come from each platform's libm and **are not
guaranteed identical**. A last-place difference in a matrix element moves a projected vertex by a
sub-pixel amount, which moves an antialiased or filtered edge by one level.
→ **Bounded by tolerance, and by sampling well inside solid regions** — precisely what the E design
already prescribes at line 119. Do not sample near an edge.

**(f) Shader precision.** RenG's own shaders declare `precision highp float`
(`CompositePipeline.kt:35`, `StickerPipeline.kt:33`), which is the right thing, but `highp` on ES is a
minimum guarantee rather than an exact one, and desktop GLSL ignores the qualifier entirely. Consumer
geometry shader pairs are arbitrary GLSL the corpus does not control at all (ADR 0008).
→ **Bounded by tolerance for RenG's own pipelines. Out of scope for consumer shaders**; a corpus can
only cover shader pairs it writes itself.

**(g) `GL_PACK_ALIGNMENT` on readback.** Default 4, measured on both llvmpipe and the macOS runner
(`docs/research/2026-08-18-cycle-d-gl-foundation.md:325, 407-409`). A 64- or 128-wide RGBA8 readback is
already 4-aligned per row, so this is latent rather than active — but a non-multiple-of-4 width, or a
non-RGBA format, silently reads padded rows.
→ **Eliminate by setting `GL_PACK_ALIGNMENT` explicitly in the readback helper**, and by keeping corpus
frame widths powers of two. Note this is *not* in RenG's documented restore set today (ADR 0023,
`GlStateSnapshot.kt`), because RenG never reads pixels in production — the readback lives in the test, so
the test owns it.

**(h) Image decode — a non-problem, deliberately.** RenG owns its PNG decoder (ADR 0020) in pure common
Kotlin (`internal/image/PngDecoder.kt`), so decoding is bit-identical on all six targets. The Cycle C
research recorded this as a design goal: *"golden pixels from a decode are cross-platform comparable in a
way rendered pixels are not"* (`docs/research/2026-08-18-cycle-c-png-decode.md:207`), and the Cycle C
design claims *"byte-comparable goldens across all six targets"* for decode
(`docs/superpowers/specs/2026-08-18-cycle-c-resource-layer-design.md:322`).
→ **Eliminated. Decode is exactly comparable and can be gated at zero tolerance.**

**(i) Rentile's Skia rasterization — the largest remaining source, and the one nobody has costed.**
RenG does not draw the basemap's content; **Rentile does**, through Skia, and hands RenG an encoded PNG:

- `internal/firewall/BasemapEngineHost.kt:470` (`RenderedBasemapTile` carries `pngBytes`);
- `RendererBasemapTileTest.kt:60-67` asserts only the 8-byte PNG signature, deliberately: *"the cheapest
  way to claim these are encoded pixels rather than any non-empty byte array is the 8-byte signature."*

Skia's rasterization on `linuxX64` and `macosArm64` is two different builds on two different
architectures, and Rentile's own ADR 0010 refuses to promise byte identity across them. Every basemap
frame RenG draws therefore inherits **Skia's** cross-platform divergence on top of GL's. Worse, a Rentile
version bump or a Skia bump inside Rentile changes every basemap baseline at once, and a whole-corpus
baseline refresh is indistinguishable from a rubber stamp.

→ **This is the single hardest determinism problem in the design, and it needs its own mechanism**, not
just a tolerance. See §5.5.

**(j) Concurrency and ordering.** Rentile fetches concurrently on `Dispatchers.Default`
(`RendererBasemapTileTest.kt:184-186`), and RenG's own resource driver runs up to
`maximumConcurrentResourceOperations` in flight. Draw order within the map regime is what determines the
final colour where translucent things overlap.
→ **Must be pinned by the corpus fixture**: one deterministic ordering per case, or fully opaque content
so ordering does not matter. The ADR 0024 regime order (map first, depth-tested; screen composited on
top) is already deterministic.

### 3.3 The `reportedRenderer` concept: where it is and how it bears

There is no type named `reportedRenderer`. The concept is
`RenderContextProfile.rendererName` — the value of `glGetString(GL_RENDERER)` read at context adoption:

- `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/RenderContextProfile.kt:155`
  `rendererName = binding.getString(GL_RENDERER).orEmpty()`
- `internal/gl/GlTokens.kt:11` `internal const val GL_RENDERER: Int = 0x1F01`
- It is surfaced to tests through `GlConformanceReport.rendererName`
  (`GlConformanceSuite.kt:38-43`), asserted only to be non-blank
  (`GlConformanceSuite.kt:117`) and **printed, not asserted**, by the macOS fixture:

  > `kmp/src/macosTest/kotlin/com/rohittp/reng/internal/gl/MacosGlConformanceTest.kt:21-24`
  > "A hosted runner reports `"Apple Software Renderer"`; a developer's machine reports
  > `"4.1 Metal - 90.5"`. **Cycle E must key golden baselines by this string and the dialect.**"

  and the plan's rationale for printing rather than asserting
  (`docs/superpowers/plans/2026-08-18-cycle-d-gl-foundation.md:4917`): *"it legitimately differs between a
  hosted runner and a developer's machine — and that difference is exactly why Cycle E's golden baselines
  must be keyed by the reported renderer string and the context dialect rather than by the target."*

One constraint the design must respect: **RenG's production code deliberately never exposes the renderer
string outward.** `kmp/src/commonMain/kotlin/com/rohittp/reng/RendererFactory.kt:37` records that
`Diagnostic` has no free-text field and *"nothing here ever reads `GL_VENDOR`/`GL_RENDERER`/the"* … i.e.
the string stays internal. So the baseline key is available to `internal` test code and to nothing else.
A gate built on it is a test-source-set gate, not a public-API gate. That is fine, and it is a reason the
gate is *cheaper* than the decomposition assumes.

**A brittleness the existing notes do not address.** Keying on the *exact* string is too tight:
`llvmpipe (LLVM 20.1.2, 256 bits)` changes whenever the runner image bumps Mesa, and a first-class
"unknown signature" failure would then block a release on a non-regression. See §5.3 for the family-table
answer.

---

## 4. Where baselines can live: what the repository actually permits

`tools/check_repository_policy.py` runs first in both workflows (`.github/workflows/ci.yml:26-33`,
`.github/workflows/publish.yml:46-50`). Its constraints, read from the source:

**(1) Binary files: PNG is permitted, almost everything else is not.**
`_unexpected_build_payload_file` (line 1439-1452) walks **every git-tracked file** (line 1349-1373, via
`git ls-files -z`) and rejects any whose suffix is in `_FORBIDDEN_BUILD_PAYLOAD_SUFFIXES` (lines 257-262:
`.a .aar .apk .asc .bc .bin .bz2 .class .dll .dylib .exe .gz .jar .klib .md5 .module .o .obj .pom .sha1
.sha256 .sha512 .so .tar .tgz .war .wasm .xz .zip`) or whose first bytes match
`_FORBIDDEN_BUILD_PAYLOAD_MAGIC_PREFIXES` (lines 262-284: ELF, Mach-O, fat, ar, wasm, LLVM bitcode, zip,
gzip, bzip2, xz, 7z, rar), plus PE and ustar detection at lines 1418-1430. Only
`gradle/wrapper/gradle-wrapper.jar` is exempt (line 1440).

- `.png` is **not** a forbidden suffix, and `\x89PNG\r\n\x1a\n` is **not** a forbidden magic prefix.
  Checked-in PNG baselines pass the policy checker as written.
- Raw `.bin` dumps, any archive, and sidecar `.sha256` hash files are all **rejected**.
- Today the repository contains no binary except the Gradle wrapper jar
  (`git ls-files` filtered to non-source extensions yields `LICENSE`, `docs/.nojekyll`,
  `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`, `kmp/api/kmp.klib.api`).
  **A baseline directory would be the first binary content this repository has ever carried.**

**(2) There are no test resource directories, and no mechanism to read one.**
`find kmp/src -type d -name resources` returns nothing, and `kmp/build.gradle.kts` configures no resource
processing. Every fixture in the tree is an in-source literal. The established pattern is documented at
length: `kmp/src/commonTest/.../image/PngDecoderTest.kt:8-56` pastes generated PNG byte literals with the
full CPython regeneration script in the comment, and lines 322-336 fall back to
`kotlin.io.encoding.Base64.decode` for a 16 KB fixture, with the reason spelled out —
*"a plain `byteArrayOf(...)` element list overflows the JVM class file format's 64KB-per-method bytecode
limit (`Method too large: <init>`)."*

**(3) All Kotlin must live under `kmp/src`.** `_unexpected_build_logic_file` (lines 1270-1288) rejects
*any* `.kt` file whose parents do not include `kmp/src`, except under `consumer-smoke`. A standalone
comparison harness written in Kotlin is a policy violation on the first file.

**(4) The build scripts are byte-fingerprinted.** `_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS`
(lines 236-256) pins exact token-stream SHA-256s for `build.gradle.kts`, `gradle/libs.versions.toml`,
`kmp/build.gradle.kts` and `settings.gradle.kts`, and `_build_semantics_violations` (lines 1376-1406)
additionally rejects **any new** `.gradle`/`.gradle.kts`/`.versions.toml` in the root build (line 1400).
So: no new Gradle module, no new build script, and **no edit to `kmp/build.gradle.kts` without updating
the policy tool in the same commit.**

This is the constraint that kills the obvious file-based design. Rentile forwards environment variables
into its native corpus test explicitly —
`/Users/rohittp/Data/Other/rentile/kmp/build.gradle.kts:239-263` uses
`providers.environmentVariable(...)` and `environment("RENTILE_NATIVE_CORPUS_OUTPUT_DIR", it)` on the
test task, because a `KotlinNativeTest` does **not** inherit the ambient environment. RenG would need the
same wiring, and that wiring changes `kmp/build.gradle.kts`.

**(5) No new dependency.** `_FORBIDDEN_DEPENDENCY` (lines 58-61) rejects any coordinate matching
`wire|serialization|skiko|ktor|corpus|coroutines?|crypto|hash`, and
`_dependency_name_policy_token` (lines 1207-1266) permits **only** `libs.rentile.kmp`,
`libs.kotlinx.coroutines.core`, `libs.kotlinx.coroutines.test`, and `kotlin("test")`. No image library,
no okio, no file-system abstraction. Note the word `corpus` is literally on the forbidden list — a
coincidence of naming, but it means "add a corpus library" is a policy violation by name alone.

**(6) `tools/` is unconstrained.** The policy checker runs no check over `tools/`. Standard-library
Python there is the project's established idiom for exactly this kind of out-of-band verification
(`tools/verify_publication.py`, `tools/resolve_release_version.py`, and their unit tests under
`tools/tests/`, run by `.github/workflows/ci.yml:26-33`). A stdlib-only PNG comparator is entirely
feasible — `zlib` plus a chunk walk, which the PngDecoderTest header script already demonstrates in
reverse.

**(7) `docs/` is published.** It is the GitHub Pages site (`check_docs`, lines 2252-2310, requires
`.nojekyll`, `index.html`, `kmp.html`, `style.css`, `versions.js`, `robots.txt`, `sitemap.xml`,
`llms.txt` and rejects external resources). Extra files there are permitted but would be served publicly.
Baselines do not belong under `docs/`.

**(8) `publish.yml` can grow a step.** `check_completion_record_workflow` (lines 2324-2396) pins five step
names, their relative order, and their credential scoping. A new gate step before the R2 upload does not
disturb any of them.

**(9) Size and reviewability.** A binary diff is unreviewable in a pull request by construction. A 512×512
RGBA frame is 1 MiB raw; PNG-encoded map content is typically 30-200 KB. Base64 in source is ~1.37× the
PNG. Three renderer signatures × N cases multiplies it. **Frame size is the primary cost lever**, and
it should be chosen for reviewability rather than realism.

---

## 5. Recommendation — one design

### 5.0 Naming, because this repository names things first

`CONTEXT.md` has no vocabulary for any of this (no entry for golden, baseline, tolerance, or renderer
string). Proposed terms, for `CONTEXT.md` with the usual `_Avoid_:` lists:

- **Renderer Signature** — the pair `(renderer family, shading dialect)` that keys a baseline. The family
  is derived from `RenderContextProfile.rendererName` through a checked-in table (§5.3).
  *Avoid*: "platform", "target", "device" — all three are the wrong granularity, and the whole finding of
  `docs/research/2026-08-18-cycle-d-gl-foundation.md:414-420` is that they are.
- **Frame Baseline** — the recorded expected pixels for one corpus case under one Renderer Signature.
  *Avoid*: "golden image", "snapshot", "screenshot", "reference render".
- **Frame Invariant** — a renderer-independent assertion about a readback that is never re-recorded.
  *Avoid*: "smoke assertion", "sanity check".

I keep "golden" out of the vocabulary deliberately: it invites "just re-record it", which is the failure
mode this design has to resist.

### 5.1 The shape: three layers, and only two of them gate

**Layer 1 — Frame Invariants. Runs on every Renderer Signature, always, and is never re-recorded.**

This is the E design's analytical readback (`…cycle-e-basemap-design.md:117-120`) promoted to the whole
corpus. Each case asserts *relationships*, not values:

- a named interior sample carries the colour the fixture put in that tile, within ±2 per channel
  (Rentile's own `assertColorClose` tolerance, `RentileRuntimeTest.kt:4554-4562`);
- the four quadrant mean colours stand in the order the fixture makes them stand;
- the antimeridian seam is continuous — the columns either side of it differ by less than the tolerance;
- a pitched camera covers the near ground — the bottom row is not clear-colour;
- the interior is nowhere the clear colour (`0,0,0,0`) when `drawBasemap = true`.

Layer 1 encodes **intent**. It is the anti-rubber-stamp mechanism: a baseline refresh cannot silence it,
because it is not derived from any observation. If a regression breaks intent, Layer 1 fails no matter
what the baselines say.

**Layer 2 — Frame Baselines. Runs where a Renderer Signature is recognised, and gates.**

Compare the **decoded RGBA8 readback** against a stored baseline for that signature — never encoded bytes
(Rentile ADR 0010's rule, and the right one). Three statistics, all three asserted:

| Statistic | Threshold | Catches |
|---|---|---|
| max per-channel absolute delta | ≤ 4 | a locally wrong pixel |
| fraction of pixels with any channel delta > 0 | ≤ 1.0 % | a shifted or resized feature |
| mean absolute per-channel delta over the whole frame | ≤ 0.25 | a whole-frame tint or gamma shift |

All three are needed and each covers the others' blind spot: a max-only rule tolerates the entire frame
shifting by 4 levels; a fraction-only rule tolerates one catastrophically wrong pixel; a mean-only rule
tolerates a small region going completely wrong. The numbers above are a **starting proposal to be
replaced by measurement** — the first implementation must print the observed statistics on every run so
they can be recalibrated against evidence rather than argued about (§5.4).

**Layer 3 — the frame digest. Printed, never asserted.**

Print `sha256` of the readback (`internal/identity/Sha256.kt`'s `PureKotlinSha256` is already available to
tests) alongside the renderer string and dialect. It answers "did anything at all change since the last
run on this exact machine?" in one line of CI log, at zero maintenance cost, and it must never gate —
see §5.4.

### 5.2 What gets compared, and on which targets

- **Targets:** `linuxX64Test` and `macosArm64Test`. Nothing else can hold a context (§2.1). The suite
  lives in `commonTest` as a shared body called by `linuxTest` and `macosTest` fixtures — exactly the
  structure `GlConformanceSuite.kt:20-30` already established and justified, and it reuses
  `SurfacelessEglContext` and `CglCoreProfileContext` unchanged.
- **Entry point:** the **public API** — `createRenderer` → `prepare` → `mintRenderTarget` → `draw`
  (`Renderer.kt:62-87`, `RenGRenderer.kt:345, 761, 769`) — into a test-owned FBO, then `glReadPixels`.
  Going through the public surface is what makes this a gate rather than a unit test, and it costs
  nothing extra: `RendererFactoryTest` already builds renderers this way against fake bindings.
- **Frame size: 128×128.** Small enough that a baseline PNG is a few kilobytes and a whole corpus is
  reviewable; large enough to carry a 2×2 tile arrangement with generous interiors to sample. Power of
  two, so `GL_PACK_ALIGNMENT` never bites. Set `GL_PACK_ALIGNMENT` explicitly in the readback helper
  regardless.
- **Corpus cases (E-basemap):** the smallest set that covers the quiet failures the E design names —
  1. one canonical camera, four ground tiles, `drawBasemap = true`;
  2. the same camera pitched, to prove near-ground coverage;
  3. an antimeridian-straddling camera, to prove world-copy dedup and seam continuity;
  4. `drawBasemap = false`, to prove the ground is genuinely absent (a negative case);
  5. one sticker and one geometry over the ground, to prove ADR 0024's regime order in pixels rather than
     in a call log.

  **Every fixture must be asymmetric in both axes.** The project already paid for this lesson: the E-C3
  fix round found the tile fixtures could not catch an x/y transposition, and fixed it by *enumerating*
  camera/output combinations and filtering on `original ∩ transposed = ∅`
  (`.superpowers/sdd/2026-08-20-cycle-e-basemap/progress.md`, "Task E-C3 FIX ROUND 1"). A symmetric golden
  frame passes under a mirror.

### 5.3 Where the baselines live, and how they are keyed

**Key:** `(family, dialect)`, where `dialect` is `ShaderDialect.GLES | DESKTOP`
(`RenderContextProfile.kt:7-10`) and `family` comes from a small checked-in table that matches a
substring of `rendererName`:

```
contains "llvmpipe"                 -> llvmpipe
equals   "Apple Software Renderer"  -> apple-software
contains "Metal"                    -> apple-metal
otherwise                           -> UNKNOWN
```

Substring rather than exact match, deliberately: `llvmpipe (LLVM 20.1.2, 256 bits)` carries a version that
moves with the runner image, and the tolerance in Layer 2 exists precisely to absorb the 1-LSB drift such
a bump produces. **The table is the reviewable artifact** — adding a family is a deliberate, visible act,
and the full renderer string is printed on every run and included in every failure message.

**Storage — recommended for the first version: Base64 PNG constants in Kotlin test sources under
`kmp/src/commonTest/.../frames/`, one file per Renderer Signature.**

Rationale, against the alternatives:

- *Checked-in `.png` files.* Permitted by the policy checker (§4.1) and the nicest artifact to look at,
  **but** reading them from a Kotlin/Native test requires either a `platform.posix` read against an
  assumed working directory, or an environment variable forwarded from `kmp/build.gradle.kts` — and that
  edit trips the build fingerprint (§4.4), forcing a same-commit change to
  `tools/check_repository_policy.py`. That is a real cost, and it is avoidable in v2 if the file route
  proves worth it.
- *Base64 in source.* Needs nothing: no dependency, no build change, no working directory. It has direct
  precedent in this repository at `PngDecoderTest.kt:323-336`, including the reason
  (`byteArrayOf` literals overflow the JVM 64 KB method limit; Base64 keeps it one string constant). At
  128×128 a map-content PNG is a few KB; three signatures × five cases is a manageable amount of source.
- *Raw RGBA base64.* 128×128×4 = 64 KB per baseline before encoding. Too large. Store PNG.

**Decoding the baseline uses RenG's own `decodePng`** (`internal/image/PngDecoder.kt:36`). That is not
circular in the dangerous direction: a decoder regression corrupts the **expected** side only, while the
**actual** side is a raw GL readback that never touches the decoder — so a decoder bug makes the gate
fail, never pass. Say so in the suite's KDoc.

### 5.4 The update path, and why it is not a rubber stamp

**On failure the suite must emit, in the test output:**

1. the full renderer string, the dialect, and the resolved family;
2. all three Layer 2 statistics with their thresholds;
3. the bounding box of the differing region and the sign of the mean channel shift;
4. the actual frame as a Base64 PNG, ready to paste.

Item 3 is what makes the diff reviewable without opening an image: *"max delta 1, 43 % of pixels differ,
mean shift +0.4, bounding box = whole frame"* reads as a driver bump; *"max delta 255, 6 % of pixels
differ, bounding box = the north-west quadrant"* reads as a missing tile. A reviewer can rule on that from
the log.

Item 4 requires writing a PNG, and RenG has no encoder. **A test-only encoder is ~40 lines and needs
nothing new**: PNG permits *stored* (uncompressed) deflate blocks, so no compressor is required, and the
CRC-32 seam already exists at `internal/image/Inflate.kt:24` (`internal expect fun crc32(...)`, used by
`PngContainer.kt:181`). Keep it in test sources; it must not enter production.

**Three rules make the refresh honest:**

1. **Layer 1 never moves.** Frame Invariants are hand-written intent and are not derived from any run. A
   baseline refresh cannot make them pass.
2. **A refresh commit touches baseline constants and nothing else.** Mixing a baseline update into a
   behaviour change is the rubber stamp; separating them makes the reviewer look at the pair of images.
3. **The pull request body carries the before/after images and the statistics.** Cheap, and it is the only
   step at which a human actually looks at what changed.

**Add a `tools/` helper, not a Gradle task:** `tools/update_frame_baselines.py` (standard library only)
takes the Base64 blocks from a CI log or a local run and rewrites the Kotlin constants in place, plus
`tools/compare_frames.py` for a local eyeball. Both get unit tests under `tools/tests/`, matching the
existing convention and running in the existing CI step.

### 5.5 The Rentile/Skia problem needs its own seam

Because Rentile draws the basemap's content (§3.2(i)), a frame baseline conflates two independent
variables: *what RenG did with the tile* and *what Skia produced as the tile*. A Rentile upgrade would
fail every basemap case at once, with no way to tell it from a RenG regression, and the only available
response would be a whole-corpus refresh — the rubber stamp.

**Split the attribution at the boundary.** Add a second, cheaper gate on the **decoded** tile, before
upload:

- assert Layer-1-style invariants on the decoded RGBA of one rasterized tile (the fixture's background
  colour appears at the tile centre, the tile is the declared 512×512, alpha is what the style implies);
- record a **per-Renderer-Signature digest of the decoded tile** and print it; gate it with the same
  tolerance triple against a small stored tile baseline.

Then a Rentile/Skia change fails the *tile* case and the frame cases together, while a RenG drawing
regression fails only the frame cases. That single bit of information is the difference between "refresh
the tile baselines and re-run" and "investigate". It also gives the tile baselines a much better claim to
being refreshable, since RenG does not own their content.

### 5.6 Wiring: no new CI job and no new publish step are required

This is the finding that most changes the shape of the work versus `docs/decomposition.md:225-230`.

The decomposition's "a job in `ci.yml` and a step in `publish.yml`" is inherited from Rentile, whose
corpus gate needs credentials and a live public map catalog
(`CLAUDE.md:270-277` records that those two gates were deliberately not ported). RenG's does not: its
fixtures are in-source, its transport is a fake, and it needs no network. So the gate is **ordinary test
methods in `linuxTest` and `macosTest`**, and both workflows already run those tasks:

- `ci.yml` — `:kmp:linuxX64Test` at `.github/workflows/ci.yml:43`, `:kmp:macosArm64Test` at line 63.
- `publish.yml` — `:kmp:linuxX64Test` in the `linux-release` job at `.github/workflows/publish.yml:103`,
  which `needs`-gates the publish job; and `:kmp:macosArm64Test` at line 158, inside the "Test and publish
  to isolated local repository" step, which runs **before** the R2 preflight (line 211) and the upload
  (line 217).

**The gate is therefore already before upload, by construction.** The only additions worth making to the
workflows are optional: an `actions/upload-artifact` step with `if: failure()` if the file-based route is
adopted later, and nothing at all if the Base64-in-log route is used.

If Cycle J later grows a corpus large enough that running it on every push is too slow, *then* it earns a
separate job with a subset on push and the full set on publish. Do not start there.

### 5.7 What this design does NOT cover — state it in the suite's own KDoc

A gate that claims more than it verifies is worse than none, so:

1. **Android and iOS get zero pixel coverage.** No context exists in CI for either (§2.1, ADR 0011). The
   `GLES30` and `platform.gles3` bindings are verified by compilation and by the shared seam only. That is
   Cycle H's problem and it does not get smaller by being unstated.
2. **No real GPU is covered.** llvmpipe and `Apple Software Renderer` are both software rasterizers. The
   gate proves *"RenG still draws what it drew"*, never *"RenG draws correctly on a GPU"*. Metal, Mali and
   Adreno divergence is uncovered by construction. `docs/decomposition.md:156-158` already flags that the
   macOS no-GPU fallback has never been compared against a machine with a real GPU.
3. **Consumer geometry shaders are uncovered** beyond the shader pairs the corpus itself writes. ADR 0008
   accepts arbitrary consumer GLSL; a corpus cannot enumerate it.
4. **Animation sampling is under-covered.** F-2's `AnimationTrack(timeSeconds)` is continuous; a frame
   baseline pins one sample. Wrong interpolation between two pinned samples is invisible.
5. **Nothing is verified about `iosSimulatorArm64`**, whose test task exists but is never invoked and has
   no context fixture.
6. **Text is not covered because there is none.** True today by design
   (`…cycle-e-basemap-design.md:31-39`); false the moment labels ship. The tolerance must be re-argued
   then, not inherited.
7. **The gate cannot distinguish a Rentile change from a RenG regression** unless §5.5's tile-level seam
   is built.

---

## 6. The absolute-threshold trap, and its analogue here

The project has already been bitten once, and wrote it down:

> `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationScaleBenchmarkTest.kt:53-60`
> "The guard below no longer asserts an absolute-millisecond ceiling. A ceiling calibrated to this
> machine's absolute speed (a prior version used a 50-second ceiling with ~49% headroom above the worst
> run recorded above) **failed on hosted CI runners that are simply slower at CPU-bound work than the
> development machine it was calibrated on** — raising the number only relocates the same problem to
> whatever host is slower next. The successive-doubling ratio … is machine-independent: a slower host
> scales every measurement in proportion and leaves the ratio intact."

The assertion is now a shape check with deliberate looseness (lines 110-125: *"6.0x sits near the
geometric mean of 4 and 8 … This is a check for an algorithmic regression, not a performance target, so it
is deliberately loose"*), and the pattern was copied to a second guard at
`kmp/src/commonTest/kotlin/com/rohittp/reng/internal/basemap/BasemapRouteDerivationCostTest.kt:121-135`
(`costRatio < tileRatio * 4.0`, with the same reasoning cited explicitly at line 123).

**Three analogues exist here, and all three are traps:**

**(a) A zero-tolerance digest gate is exactly the 50-second ceiling.** It will pass on the machine that
recorded it and fail on the next Mesa or Xcode bump, and the only available response — re-record — trains
everyone to re-record, which is how the gate stops meaning anything. **Recommendation: no digest gate.**
Print the digest; never assert it. (§5.1, Layer 3.)

**(b) A tolerance calibrated on the two software renderers CI happens to have today is an absolute
threshold in disguise.** The mitigation is the one the benchmark used: gate on a *relationship*, not a
raw number, wherever possible, and print the observed value on every run so recalibration is evidence-led.
Concretely: never compare across signatures (a cross-signature tolerance would have to be so wide it
proves nothing), always print all three statistics, and set the thresholds from a measured spread with
stated headroom rather than from the tightest observed run.

**(c) Failing on an unrecognised Renderer Signature blocks a release on a non-regression.** The first run
on a developer's Metal machine, or the first run after GitHub changes an image, would fail with nothing
wrong. **Recommendation: an unknown signature runs Layer 1 and skips Layer 2, loudly** — printing the full
renderer string and saying exactly which file to add a family to.

That skip could itself become a rubber stamp — a signature drift that silently disables Layer 2 in CI
forever. So pair it with a **coverage guard**: each job asserts that Layer 2 actually ran for at least one
signature. If Ubuntu's renderer string stops matching `llvmpipe`, the Ubuntu job fails with *"no Frame
Baseline was compared on this host"* — a failure that names its own cause. Layer 1 keeps running
everywhere regardless, so an unknown signature is never zero coverage.

---

## 7. The cheapest useful first version

**One task, not a cycle.** No stored baselines at all. Extend the existing real-context suites with a
single case, structured exactly like `runGlConformanceSuite` — a shared body in `commonTest`, called by
the `linuxTest` and `macosTest` fixtures that already exist:

1. Build a renderer through `createRenderer` at `OutputPixelSize(128, 128)` with a fake transport serving
   a fixture basemap style (the tree already has `TileTransport` and `STYLE_WITH_SPRITE_JSON` in
   `kmp/src/nativeTest/.../RendererBasemapTileTest.kt:186-224`).
2. `prepare` an **asymmetric** camera — reuse the `(-55, -135)` zoom-4 camera the project already proved
   is disjoint from its own transpose (`progress.md`, "Task E-C3 FIX ROUND 1") — then `draw` into a
   test-owned FBO.
3. `glReadPixels` the whole 128×128, with `GL_PACK_ALIGNMENT` set explicitly.
4. Assert four **Frame Invariants**:
   - no interior pixel is the clear colour `(0,0,0,0)` — catches "nothing drew";
   - four named interior sample points carry the fixture's per-tile colours within ±2 — catches x/y
     transposition, a wrong LOD, and a v-flip;
   - the four quadrant mean colours stand in the fixture's intended order — catches mirroring and
     rotation;
   - the same plan with `drawBasemap = false` produces an all-clear interior — the negative case that
     stops the first three passing vacuously.
5. Print the renderer string, the dialect, the four quadrant means, and the SHA-256 of the readback.

**What it costs:** no new dependency, no build-script change, no policy-tool change, no binary in the
repository, no new CI job, no new publish step, and no baseline maintenance. It lands as test code in
source sets that already exist and runs in workflow steps that already run.

**What it catches:** the entire class of "quiet and plausible" failures the E design was written about —
a transposed tile index, a wrong LOD, a v-flipped texture, a ground that silently draws nothing, a
`drawBasemap` flag that does nothing. Each of those is invisible to every call-log assertion in the tree
today, and each is a total-outage-grade defect for a consumer.

**What it misses**, stated plainly so the gate is not oversold:

- sub-pixel tile placement and filtering quality — quadrant means survive a half-pixel shift;
- blend correctness at edges, which is exactly where the invariants deliberately do not sample;
- antimeridian seam continuity and world-copy dedup (case 3 of §5.2);
- anything about stickers, geometries or models composited over the ground, including ADR 0024's regime
  order in pixels;
- **any regression that preserves the four quadrant means** — which is why the fixture must be asymmetric
  in both axes, and why "add a fifth sample point" is the cheapest next increment;
- everything in §5.7's non-coverage list, unchanged.

**The increment order after that**, cheapest first: (1) add the antimeridian and pitched cases as more
Frame Invariants — still no baselines; (2) add the tile-level decoded-pixel seam of §5.5, which is where
Rentile drift starts being attributable; (3) add Frame Baselines and the family table, which is the first
step that introduces maintenance cost and is the first that should be argued as a cycle rather than a
task.
