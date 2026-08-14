# Handoff

For whoever picks up RenG next. Cycle 0 — the graphics contract — is complete. No RenG code exists yet.

## Read these first, in this order

1. `CLAUDE.md` — project instructions. Corrected in this commit; where it disagrees with an ADR, the ADR wins.
2. `CONTEXT.md` — vocabulary. Read it before naming anything.
3. `docs/adr/0001`–`0012` — the graphics contract. **Do not re-litigate these.** They are backed by
   driver-level evidence, reproduced below.
4. `docs/decomposition.md` — cycles A–J, their gates, and their order.

## What exists and what does not

The tree is still Android Studio's skeleton plus this design record. `:app` exists and is not RenG —
cycle A deletes it. `:kmp`, `consumer-smoke`, the `docs/` static site, `CONTEXT-MAP.md`, and
`gradle.properties`'s `VERSION_NAME` do **not** exist yet.

`.github/workflows/ci.yml` and `publish.yml` are ported and reference `:kmp` and `consumer-smoke`, so
**both currently fail**. Cycle A's job is to make them pass. Note that `publish.yml` cuts a release on
every non-doc push to `main`; `**/*.md`, `docs`, and `LICENSE` are `paths-ignore`d, which is why this
design record does not consume a version.

## Verified environment facts

Re-verify rather than trust if months have passed. Each fact below was established by running something,
not by recall.

| Fact | How it was verified |
|---|---|
| Rentile 0.1.5 publishes all six targets RenG needs | `curl` on `maven.rohittp.com` for `kmp-{android,iosarm64,iossimulatorarm64,macosarm64,linuxx64,linuxarm64}-0.1.5.pom` → all 200 |
| Depend on `com.rohittp.rentile:kmp:0.1.5`, no `mavenLocal()` | same |
| Kotlin/Native ships Apple GL bindings | `klib dump-metadata` on `~/.konan/.../klib/platform/*` |
| `macosArm64` → `platform.OpenGL3` (509 fns) + `platform.OpenGLCommon` (52 CGL fns) | same |
| iOS targets → **`platform.gles3`** (296 fns) — *not* `platform.OpenGLES3` | same; the module is named `OpenGLES3` but its package is `platform.gles3` |
| Linux has no GL platform klib (only `builtin`, `iconv`, `linux`, `posix`, `zlib`) | `ls ~/.konan/.../klib/platform/linux_x64` |
| Linux sysroots ship `dlfcn.h`, no `GL/` or `EGL/` headers | `find ~/.konan -name 'GL' -o -name 'EGL'` → empty |
| Android `GLES20`+`GLES30` cover every entry point needed | `javap` over `android.jar` (android-37.0) |
| Toolchain: Kotlin 2.3.21, AGP 9.3.1, Gradle 9.5.0 wrapper | `gradle/libs.versions.toml` here and in rentile |

Shader dialect, from a headless CGL context on an M3 Max reporting `4.1 Metal - 90.5`:

| Source | Core 4.1 | Legacy 2.1 |
|---|---|---|
| `#version 300 es` | **FAIL** — "version '300' is not supported" | FAIL |
| `#version 410 core` + ES-style body | OK | FAIL |
| `#version 330 core` + ES-style body | **OK, and links** | FAIL |
| `#version 120` / no directive | FAIL | OK |

"ES-style body" means `precision mediump float;`, `in`/`out`, `texture()`, `textureSize()`,
`layout(location = …)`, and integer uniforms. This is why ADR 0008 substitutes `330 core`.

## Traps that cost time — do not rediscover them

- **Hand-rolled cinterop against Apple GL headers silently produces an empty binding.** Clang
  availability attributes (`API_DEPRECATED(macos(10.5, 10.14))`) make cinterop *drop* declarations with
  no error and no warning: a def naming `OpenGL/gl3.h` yielded 19 GLU functions and zero GL ones.
  `GL_SILENCE_DEPRECATION` in `compilerOpts` did not fix it under Clang modules. Use the shipped
  platform klibs (ADR 0009).
- **A cinterop package name comes from the `.def` filename, not the `cinterops.create(...)` name.**
  `gl_macos.def` produces package `gl_macos`.
- **Inline C in a def file's `---` section *is* imported** even when the header declarations are dropped.
  Useful as an escape hatch; not needed for RenG.
- **Platform GL klibs are invisible from shared source sets.** `platform.gles3` resolves in
  `iosArm64Main` and fails in `iosMain`. `kotlin.mpp.enableCInteropCommonization=true` did not change
  this. GL code lives in leaf source sets.
- **Cinterops declared on the `main` compilation are not visible to the test compilation.** Put GL calls
  in main source and have tests call them — which is what RenG does anyway.
- **`klib dump-metadata` is the tool** for answering "what is actually in this klib": `~/.konan/
  kotlin-native-prebuilt-macos-aarch64-<version>/bin/klib dump-metadata <path>`. `klib contents` does
  not exist.

## Next cycle: A — build and publication skeleton

Scope and gates are in `docs/decomposition.md`. Rentile at `/Users/rohittp/Data/Other/rentile` is the
structural template — mirror `kmp/build.gradle.kts`, `consumer-smoke/`, and `docs/` unless there is a
documented reason not to. RenG needs no Wire, no Skiko yet, and no `jvm` target (ADR 0010). The
approved Cycle A design and its fail-closed release policy are recorded in
`docs/superpowers/specs/2026-08-14-cycle-a-build-publication-design.md` and ADR 0013.

Local gate list, exactly what `ci.yml` runs:

```bash
./gradlew :kmp:checkKotlinAbi
./gradlew :kmp:testAndroidHostTest
./gradlew :kmp:linuxX64Test
./gradlew :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64
./gradlew :kmp:macosArm64Test
./gradlew :kmp:compileKotlinLinuxX64 :kmp:compileKotlinLinuxArm64
./gradlew :kmp:publishAllPublicationsToLocalTestRepository
./gradlew -p consumer-smoke compileAndroidMain compileKotlinIosArm64 \
    compileKotlinIosSimulatorArm64 compileKotlinMacosArm64 compileKotlinLinuxX64 compileKotlinLinuxArm64
```

Pass `--no-configuration-cache` on every invocation.

## Decisions still open, each needing a spike before its cycle's spec

- **Coordinate precision (cycle B).** Latitude/longitude need doubles at high zoom; the GPU has floats.
  Camera-relative rebasing is required and its boundary belongs in the transform code.
- **PNG decode and GLB parse (cycle C).** No free answer for PNG across six targets — Skiko is proven
  but heavy, a pure-Kotlin decoder needs inflate. GLB is tractable in pure Kotlin but its supported
  feature subset must be written down, not discovered.
- **Draw-regime ordering (cycle F).** Screen-anchored things composite by z-index with no depth test;
  map-anchored things are occlusion-tested. How the two interleave in one frame is ADR-worthy.
- **Golden images (cycle E onward).** llvmpipe and Apple's GL will never be pixel-identical. Baselines
  are per-platform with a tolerance, never cross-platform equality.
- **Basemap suppression per frame.** Left undecided in ADR 0004 until the basemap cycle needs an answer.

## Process expectations

- The repository owner's standing instruction: **run `/grill-with-docs` before writing any plan**, and
  use parallel subagents for genuinely independent implementation tasks.
- ADRs are a few paragraphs of prose, no template headings, `NNNN-imperative-title.md`. Next number is
  0014.
- Update `CONTEXT.md` as terms resolve rather than batching it.
- Never commit `mavenLocal()`, a `-SNAPSHOT` dependency, or an independently hardcoded RenG version —
  `VERSION_NAME` in the root `gradle.properties` is the sole checked-in version input. ADR 0013 defines
  how publication may derive a later patch from the public version line.

## Throwaway spike code

Cycle 0's probes live in this session's scratchpad, not the repo, and are deliberately disposable:
`gl_dialect_probe.c` and `gl_330_probe.c` (headless CGL, C), and `glspike/` (a five-target Kotlin/Native
build whose `macosArm64Test` drives a real context). If the scratchpad is gone, the tables above are the
findings; reproducing them takes about an hour.
