# Handoff

For whoever picks up RenG next. Cycle 0 — the graphics contract — is complete. Cycle A's
implementation and local gates are complete; the first public release is pending. Nothing renders and
there is no public runtime API yet. Cycle B becomes the next implementation cycle only after the release
workflow succeeds for the merged Cycle A commit.

## Read these first, in this order

1. `CLAUDE.md` — current project structure, contracts, tools, and local commands. Where it disagrees with
   an ADR, the ADR wins.
2. `CONTEXT.md` — vocabulary. Read it before naming anything.
3. `docs/adr/0001`–`0012` — the graphics contract. **Do not re-litigate these.** ADR 0013 separately
   governs the fail-closed release policy.
4. `docs/decomposition.md` — cycles A–J, their gates, and their order.

## What exists and what does not

Android Studio's placeholder `:app` is gone. Root settings include only `:kmp`, the single published
module for `com.rohittp.reng:kmp`. It declares exactly `android`, `iosArm64`, `iosSimulatorArm64`,
`macosArm64`, `linuxX64`, and `linuxArm64`; enables `explicitApi()` and KLIB ABI validation; and keeps
Rentile 0.1.5 as an `implementation` dependency. Cycle A's only Kotlin production code is an internal
Rentile linkage anchor exercised by a common test, so no Rentile type or RenG runtime symbol is public.

The standalone `consumer-smoke` build declares the same six targets and resolves RenG through an
`exclusiveContent` repository. It defaults to `build/local-maven`, reads the sole checked-in version from
root `gradle.properties`, and accepts explicit `rengRepositoryUrl`/`rengVersion` overrides for copied
public smoke. `VERSION_NAME=0.1.0`, `kmp/api/kmp.klib.api`, the dependency-free static `docs/` site,
Apache-2.0 legal files, and the publication/POM configuration all exist.

The standard-library Python tooling is implemented and unit-tested:

- `tools/check_repository_policy.py --root .` enforces the Cycle A repository contract.
- `tools/resolve_release_version.py --properties-file gradle.properties --repository-url <url>` selects
  one public candidate or fails closed.
- `tools/verify_publication.py` exposes:
  - `local --repository <path> --version <version> --manifest <path> [--require-signed-poms]`
  - `r2-preflight --endpoint <url> --bucket <bucket> --version <version> --manifest <path>`
  - `public --repository-url <url> --version <version> --manifest <path> [--attempts <n>] [--retry-delay <seconds>]`
  These cover the seven-publication local manifest, authoritative exact-key collisions, and anonymous
  public artifacts plus metadata.

`.github/workflows/ci.yml` now gates Ubuntu and macOS work rather than referencing missing projects.
`.github/workflows/publish.yml` adds version resolution, a Linux release gate, signed local publication,
seven POM checks, fresh-home six-target local smoke, exact-key R2 preflight, upload, anonymous artifact
and metadata verification, and a copied fresh-home credential-free public smoke.

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

## Cycle A local completion and pending public outcome

The approved design and its fail-closed release policy are in
`docs/superpowers/specs/2026-08-14-cycle-a-build-publication-design.md` and ADR 0013. The implementation
and local gates are complete. No push, merge, workflow dispatch, AWS operation, R2 upload, or public
publication has been performed from this worktree. **Public release pending** is the authoritative
outcome until the exact merged commit's workflow proves otherwise.

The release resolver follows one rule without searching for alternatives: if checked-in `VERSION_NAME`
is newer than every public stable version, that declaration is the candidate; otherwise the candidate is
exactly the next patch after the newest public stable version. Only that aggregate POM is probed. An
occupied candidate, remote uncertainty, malformed metadata, redirect, or unexpected status stops the run;
the resolver never skips ahead. If a partial release has uploaded any immutable key, recovery requires an
explicit upward `VERSION_NAME`, full gates, and fresh approval — never overwrite, delete, or reuse.

Run the local Python and policy gates exactly as follows:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools/tests -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
```

On macOS, run the locally compilable Ubuntu-equivalent tasks; `linuxX64Test` cannot execute there:

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

Then run Apple/target compilation, local publication, and clean smoke:

```bash
./gradlew --no-configuration-cache \
  :kmp:compileKotlinIosArm64 \
  :kmp:compileKotlinIosSimulatorArm64 \
  :kmp:macosArm64Test \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:publishAllPublicationsToLocalTestRepository

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

Ubuntu CI owns the host-executable Linux test and runs:

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:linuxX64Test \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

Every Gradle invocation in CI/publication passes `--no-configuration-cache`. The smoke commands use a
fresh Gradle home and `--refresh-dependencies`, so cached RenG artifacts cannot mask an incomplete local
or public repository.

## Next cycle after release success: B — public API and pure core

Do not begin Cycle B merely because the feature branch, local publication, or PR is green. First require
the public workflow to prove seven signed local POMs, no authoritative R2 collision, anonymous retrieval
of every manifest artifact, aggregate metadata containing the resolved version, and fresh credential-free
six-target resolution. After that observed success, Cycle B is the next implementation cycle.

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
